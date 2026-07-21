<#
.SYNOPSIS
    Read-only scan of all Jira Cloud filters for asset object key references.
    Lists filters whose JQL contains keys with the given prefixes (e.g. CMDB-3892, JSRK-3228).
    Makes NO changes to anything.

.EXAMPLE
    .\Find-FiltersWithAssetKeys.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken "xxxx" `
        -AssetKeyPrefixes CMDB,JSRK -ExportCsv "flagged-filters.csv"
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string]$JiraBaseUrl,

    [Parameter(Mandatory)]
    [string]$Email,

    [Parameter(Mandatory)]
    [string]$ApiToken,

    [Parameter(Mandatory)]
    [string[]]$AssetKeyPrefixes,   # e.g. CMDB, JSRK

    [string]$ExportCsv,

    # Requires Jira admin. Scans ALL filters on the site, not just ones shared with this account.
    [switch]$OverrideSharePermissions,

    [int]$PageSize = 50
)

$ErrorActionPreference = 'Stop'
$JiraBaseUrl = $JiraBaseUrl.TrimEnd('/')

# --- Auth header ---
$pair    = "{0}:{1}" -f $Email, $ApiToken
$basic   = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic"; Accept = 'application/json' }

# --- Detection regex: one alternation of literal prefixes, then -digits. Nothing else. ---
$prefixAlt  = ($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim()) }) -join '|'
$tokenRegex = [regex]"\b(?:$prefixAlt)-\d+\b"
Write-Host "Detection pattern: $($tokenRegex.ToString())" -ForegroundColor Cyan

# --- Walk all filters (paginated) ---
$startAt      = 0
$totalScanned = 0
$flagged      = New-Object System.Collections.Generic.List[object]

do {
    $uri = "$JiraBaseUrl/rest/api/3/filter/search?startAt=$startAt&maxResults=$PageSize&expand=jql"
    if ($OverrideSharePermissions) { $uri += "&overrideSharePermissions=true" }

    $page = Invoke-RestMethod -Uri $uri -Headers $headers -Method Get

    foreach ($filter in $page.values) {
        $totalScanned++
        if ([string]::IsNullOrWhiteSpace($filter.jql)) { continue }

        $found = $tokenRegex.Matches($filter.jql)
        if ($found.Count -gt 0) {
            $keys = ($found | ForEach-Object { $_.Value } | Sort-Object -Unique) -join ', '
            $flagged.Add([PSCustomObject]@{
                FilterId    = $filter.id
                FilterName  = $filter.name
                Owner       = $filter.owner.displayName
                MatchedKeys = $keys
                FilterJql   = $filter.jql
            })
            Write-Host ("=" * 70) -ForegroundColor Yellow
            Write-Host "Filter ID   : $($filter.id)"
            Write-Host "Filter Name : $($filter.name)"
            Write-Host "Owner       : $($filter.owner.displayName)"
            Write-Host "Matched Keys: $keys" -ForegroundColor Green
            Write-Host "JQL         : $($filter.jql)"
        }
    }

    $startAt += $page.values.Count
    Write-Host "...scanned $totalScanned / $($page.total) filters" -ForegroundColor DarkGray

} while (-not $page.isLast -and $page.values.Count -gt 0)

# --- Summary ---
Write-Host ""
Write-Host ("-" * 50)
Write-Host "Scanned : $totalScanned filters" -ForegroundColor Cyan
Write-Host "Flagged : $($flagged.Count) filters containing asset key references" -ForegroundColor Cyan
if (-not $OverrideSharePermissions) {
    Write-Host "Note: only filters visible to this account were scanned. Use -OverrideSharePermissions (admin) for all filters." -ForegroundColor DarkYellow
}

if ($ExportCsv -and $flagged.Count -gt 0) {
    $flagged | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
