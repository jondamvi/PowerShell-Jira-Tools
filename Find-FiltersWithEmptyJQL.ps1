<#
.SYNOPSIS
    READ-ONLY audit: finds Jira Cloud filters whose JQL is EMPTY (a known
    defect of the vendor's DC->Cloud migration — filter arrives with name and
    owner intact but blank JQL) and reports them. Performs ZERO write calls
    under all circumstances — there is no -Commit switch.

    -Filters semantics:
      - Names without wildcards: EXACT name match via server-side query.
      - Names with wildcards (* ? [ ]): full scan, client-side -like matching.

    CSV columns: Filter Name, Filter ID, Owner Name, Owner ID, Owner Email
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string]$JiraBaseUrl,

    [Parameter(Mandatory)]
    [string]$Email,

    [Parameter(Mandatory)]
    [string]$ApiToken,

    # No wildcards = exact name match (server-side query). Wildcards = full scan + -like.
    [string[]]$Filters,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
    [switch]$OverrideSharePermissions,

    [int]$PageSize = 50
)

$ErrorActionPreference = 'Stop'
$JiraBaseUrl = $JiraBaseUrl.TrimEnd('/')

# --- Auth header ---
$pair    = "{0}:{1}" -f $Email, $ApiToken
$basic   = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic"; Accept = 'application/json' }

# --- Preflight: verify credentials actually authenticate ---
try {
    $me = Invoke-RestMethod -Uri "$JiraBaseUrl/rest/api/3/myself" -Headers $headers -Method Get
}
catch {
    throw "Auth preflight against /rest/api/3/myself failed: $($_.Exception.Message) — check -JiraBaseUrl, -Email, -ApiToken (tokens expire, max 1 year)."
}
if (-not $me.accountId) {
    throw "Jira served the request as ANONYMOUS (no accountId from /myself). The -Email/-ApiToken pair is not authenticating."
}
Write-Host "Authenticated as  : $($me.displayName) [$($me.accountId)]" -ForegroundColor Cyan

# --- Paginated fetch ---
# expand=jql,owner so owner data arrives with the page — no per-filter GETs needed.
$script:baseUri = "$JiraBaseUrl/rest/api/3/filter/search?maxResults=$PageSize&expand=jql,owner"
if ($OverrideSharePermissions) { $script:baseUri += "&overrideSharePermissions=true" }

function Get-AllFilterPages {
    param([string]$ExtraQuery = '')
    $acc     = New-Object System.Collections.Generic.List[object]
    $startAt = 0
    do {
        $uri  = "$($script:baseUri)&startAt=$startAt$ExtraQuery"
        $page = Invoke-RestMethod -Uri $uri -Headers $script:headers -Method Get
        foreach ($f in @($page.values)) { if ($null -ne $f) { $acc.Add($f) } }
        $startAt += @($page.values).Count
    } while (-not $page.isLast -and @($page.values).Count -gt 0)
    return ,$acc
}

# --- Per-filter processing (read-only) ---
$script:totalScanned = 0
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    if (-not [string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] JQL not empty — OK" -ForegroundColor DarkGray }
        return
    }

    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host "WARNING: filter has EMPTY JQL" -ForegroundColor Magenta
    Write-Host ("  Name  : {0}" -f $filter.name)
    Write-Host ("  ID    : {0}" -f $filter.id)
    Write-Host ("  Owner : {0} <{1}> [{2}]" -f $filter.owner.displayName, $filter.owner.emailAddress, $filter.owner.accountId)

    $script:results.Add([PSCustomObject]([ordered]@{
        'Filter Name' = $filter.name
        'Filter ID'   = $filter.id
        'Owner Name'  = $filter.owner.displayName
        'Owner ID'    = $filter.owner.accountId
        'Owner Email' = "$($filter.owner.emailAddress)"
    }))
}

# --- Startup info ---
Write-Host "Detection         : filters with EMPTY JQL" -ForegroundColor Cyan
Write-Host "MODE: READ-ONLY — this script performs no write operations." -ForegroundColor Green
if ($Filters) { Write-Host "Filter name scope : $($Filters -join ', ')" -ForegroundColor Cyan }

# --- Selection ---
$wildcardMode = [bool]($Filters | Where-Object { $_ -match '[\*\?\[\]]' })

if ($Filters -and -not $wildcardMode) {
    Write-Host "Scan strategy     : exact name match (server-side query per name)" -ForegroundColor Cyan
    foreach ($name in $Filters) {
        $candidates = Get-AllFilterPages -ExtraQuery ("&filterName=" + [uri]::EscapeDataString($name))
        $target = $candidates | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($target) { Invoke-FilterProcessing -filter $target }
    }
}
else {
    if ($Filters) { Write-Host "Scan strategy     : full scan, client-side wildcard matching" -ForegroundColor Cyan }
    $all = Get-AllFilterPages
    Write-Host ("API reports {0} filter(s) visible to this account" -f $all.Count) -ForegroundColor Cyan
    if ($all.Count -eq 0) {
        Write-Host "ZERO filters returned. Verify the authenticated account, admin permission for -OverrideSharePermissions, and -JiraBaseUrl." -ForegroundColor Red
    }
    foreach ($f in $all) {
        if ($Filters) {
            $hit = $false
            foreach ($pattern in $Filters) { if ($f.name -like $pattern) { $hit = $true; break } }
            if (-not $hit) { continue }
        }
        Invoke-FilterProcessing -filter $f
    }
}

# --- Summary ---
Write-Host ""
Write-Host ("-" * 50)
Write-Host "Scanned        : $($script:totalScanned) filters" -ForegroundColor Cyan
Write-Host "Empty JQL found: $($script:results.Count)"        -ForegroundColor $(if ($script:results.Count) { 'Magenta' } else { 'Cyan' })

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
