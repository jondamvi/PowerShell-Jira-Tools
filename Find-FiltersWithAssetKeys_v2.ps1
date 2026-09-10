<#
.SYNOPSIS
    Read-only scan of Jira Cloud filters for asset object key references. v2.
    Lists filters whose JQL contains keys with the given prefixes (e.g. CMDB-3892).
    Case-insensitive by default (also catches lowercase usage like "iam-8238");
    matched keys are reported in canonical UPPERCASE. Makes NO changes to anything.

.EXAMPLE
    .\Find-FiltersWithAssetKeys_v2.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken $token `
        -AssetKeyPrefixes CMDB,JRSK -OverrideSharePermissions -ExportCsv "cloud-affected.csv"
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
    [string[]]$AssetKeyPrefixes,

    # Match asset keys case-insensitively; report them normalized to uppercase.
    # Enabled by default; disable with -CaseInsensitiveKeys:$false.
    [switch]$CaseInsensitiveKeys = $true,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
    # Includes private filters of other users in the scan.
    [switch]$OverrideSharePermissions,

    [int]$PageSize = 50
)

$ErrorActionPreference = 'Stop'

# Normalize base URL: a scheme-less URI is sent as http:// by PowerShell, and the
# http->https redirect strips the Authorization header, causing bogus 401/anonymous
# failures even with valid credentials. Force https.
if ($JiraBaseUrl -match '^(?i)http://') {
    $JiraBaseUrl = 'https://' + $JiraBaseUrl.Substring(7)
    Write-Host "Upgraded -JiraBaseUrl to HTTPS (plain HTTP drops the Authorization header on redirect)" -ForegroundColor DarkYellow
}
elseif ($JiraBaseUrl -notmatch '^(?i)https://') {
    $JiraBaseUrl = "https://$JiraBaseUrl"
    Write-Host "Prepended https:// to -JiraBaseUrl" -ForegroundColor DarkYellow
}
$JiraBaseUrl = $JiraBaseUrl.TrimEnd('/')

# --- Auth header + preflight ---
$pair    = "{0}:{1}" -f $Email, $ApiToken
$basic   = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic"; Accept = 'application/json' }

try {
    $me = Invoke-RestMethod -Uri "$JiraBaseUrl/rest/api/3/myself" -Headers $headers -Method Get
}
catch {
    throw "Auth preflight against /rest/api/3/myself failed: $($_.Exception.Message) — check -JiraBaseUrl, -Email, -ApiToken (tokens expire, max 1 year)."
}
if (-not $me.accountId) {
    throw "Jira served the request as ANONYMOUS. The -Email/-ApiToken pair is not authenticating."
}
Write-Host "Authenticated as  : $($me.displayName) [$($me.accountId)]" -ForegroundColor Cyan

# --- Detection regex ---
# Accept prefixes with or without a trailing dash ("CMDB" and "CMDB-" both work;
# the dash is appended by the pattern itself, so it is stripped from input here).
$prefixAlt  = ($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim().TrimEnd('-')) }) -join '|'
$ciPrefix   = if ($CaseInsensitiveKeys) { '(?i)' } else { '' }
$tokenRegex = [regex]"$ciPrefix\b(?:$prefixAlt)-\d+\b"
Write-Host "Detection pattern : $($tokenRegex.ToString())" -ForegroundColor Cyan
Write-Host "Key matching      : $(if ($CaseInsensitiveKeys) { 'case-insensitive (reported as UPPERCASE)' } else { 'case-sensitive' })" -ForegroundColor Cyan

# --- Walk all filters (paginated) ---
$startAt      = 0
$totalScanned = 0
$flagged      = New-Object System.Collections.Generic.List[object]

do {
    $uri = "$JiraBaseUrl/rest/api/3/filter/search?startAt=$startAt&maxResults=$PageSize&expand=jql"
    if ($OverrideSharePermissions) { $uri += "&overrideSharePermissions=true" }

    $page = Invoke-RestMethod -Uri $uri -Headers $headers -Method Get

    if ($startAt -eq 0) {
        Write-Host ("API reports {0} filter(s) in scope" -f $page.total) -ForegroundColor Cyan
        if ($page.total -eq 0) {
            Write-Host "ZERO filters returned. Verify the authenticated account, admin permission for -OverrideSharePermissions, and -JiraBaseUrl." -ForegroundColor Red
        }
    }

    foreach ($filter in @($page.values)) {
        $totalScanned++
        if ([string]::IsNullOrWhiteSpace($filter.jql)) { continue }

        $found = $tokenRegex.Matches($filter.jql)
        if ($found.Count -gt 0) {
            $rawKeys = $found | ForEach-Object { if ($CaseInsensitiveKeys) { $_.Value.ToUpper() } else { $_.Value } }
            $keys    = ($rawKeys | Sort-Object -Unique) -join ', '
            $flagged.Add([PSCustomObject]([ordered]@{
                'Filter Name'  = $filter.name
                'Filter ID'    = $filter.id
                'Owner Name'   = $filter.owner.displayName
                'Owner Email'  = "$($filter.owner.emailAddress)"
                'Owner ID'     = $filter.owner.accountId
                'Matched Keys' = $keys
                'JQL'          = $filter.jql
            }))
            Write-Host ("=" * 70) -ForegroundColor Yellow
            Write-Host "Filter ID    : $($filter.id)"
            Write-Host "Filter Name  : $($filter.name)"
            Write-Host "Owner        : $($filter.owner.displayName)"
            Write-Host "Matched Keys : $keys" -ForegroundColor Green
            Write-Host "JQL          : $($filter.jql)"
        }
    }

    $startAt += @($page.values).Count
    Write-Host "...scanned $totalScanned / $($page.total) filters" -ForegroundColor DarkGray

} while (-not $page.isLast -and @($page.values).Count -gt 0)

# --- Summary ---
Write-Host ""
Write-Host ("-" * 50)
Write-Host "Scanned : $totalScanned filters" -ForegroundColor Cyan
Write-Host "Flagged : $($flagged.Count) filters containing asset key references" -ForegroundColor Cyan
if (-not $OverrideSharePermissions) {
    Write-Host "Note: only filters visible to this account were scanned. Use -OverrideSharePermissions (admin) to include private filters." -ForegroundColor DarkYellow
}

if ($ExportCsv -and $flagged.Count -gt 0) {
    $flagged | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
