<#
.SYNOPSIS
    Rewrites Jira Cloud filter JQLs that reference migrated asset object keys.
    Converts:   field  = CMDB-1235   ->   field IN aqlFunction("oldKey = CMDB-1235")
                field != CMDB-1235   ->   field NOT IN aqlFunction("oldKey = CMDB-1235")
    DRY RUN by default — no writes unless -Commit is passed.
    Keys found in other contexts (e.g. inside IN lists) are NOT rewritten; they are
    flagged in the ManualReview column.

.EXAMPLE
    # Dry run, review CSV first:
    .\Update-FiltersWithAssetKeys.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken $token `
        -AssetKeyPrefixes CMDB,JSRK -ExportCsv "replace-preview.csv"

    # Actually write:
    .\Update-FiltersWithAssetKeys.ps1 ... -Commit
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
    [string[]]$AssetKeyPrefixes,        # e.g. CMDB, JSRK

    # AQL attribute holding the old DC key on the migrated objects
    [string]$AqlAttributeName = 'oldKey',

    [string]$ExportCsv,

    # Requires Jira admin. Scans/edits ALL filters, not just ones visible to this account.
    [switch]$OverrideSharePermissions,

    # Without this switch the script is a pure dry run — zero write calls.
    [switch]$Commit,

    [int]$PageSize = 50
)

$ErrorActionPreference = 'Stop'
$JiraBaseUrl = $JiraBaseUrl.TrimEnd('/')

# --- Auth header ---
$pair    = "{0}:{1}" -f $Email, $ApiToken
$basic   = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic"; Accept = 'application/json' }

# --- Regexes ---
$prefixAlt = ($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim()) }) -join '|'

# Detection (same as find script): any key token anywhere
$tokenRegex = [regex]"\b(?:$prefixAlt)-\d+\b"

# Rewrite: operator (= or !=) followed by an optionally-quoted key token
$rewriteRegex = [regex]"(!=|=)\s*""?((?:$prefixAlt)-\d+)""?"

$evaluator = {
    param($m)
    $op = if ($m.Groups[1].Value -eq '!=') { 'NOT IN' } else { 'IN' }
    '{0} aqlFunction("{1} = {2}")' -f $op, $AqlAttributeName, $m.Groups[2].Value
}

Write-Host "Detection pattern : $($tokenRegex.ToString())"   -ForegroundColor Cyan
Write-Host "Rewrite pattern   : $($rewriteRegex.ToString())" -ForegroundColor Cyan
if ($Commit) {
    Write-Host "MODE: COMMIT — filters WILL be updated." -ForegroundColor Red
} else {
    Write-Host "MODE: DRY RUN — no writes will be made." -ForegroundColor Green
}

# --- Strip read-only 'id' from permission objects so GET output is PUT-compatible ---
function ConvertTo-PermissionRequest {
    param($Permissions)
    if (-not $Permissions) { return @() }
    return @($Permissions | ForEach-Object {
        $_ | Select-Object -Property * -ExcludeProperty id
    })
}

# --- Walk all filters (paginated) ---
$startAt      = 0
$totalScanned = 0
$results      = New-Object System.Collections.Generic.List[object]

do {
    $uri = "$JiraBaseUrl/rest/api/3/filter/search?startAt=$startAt&maxResults=$PageSize" +
           "&expand=jql,description,sharePermissions,editPermissions"
    if ($OverrideSharePermissions) { $uri += "&overrideSharePermissions=true" }

    $page = Invoke-RestMethod -Uri $uri -Headers $headers -Method Get

    foreach ($filter in $page.values) {
        $totalScanned++
        if ([string]::IsNullOrWhiteSpace($filter.jql)) { continue }

        $found = $tokenRegex.Matches($filter.jql)
        if ($found.Count -eq 0) { continue }

        $keys   = ($found | ForEach-Object { $_.Value } | Sort-Object -Unique) -join ', '
        $newJql = $rewriteRegex.Replace($filter.jql, $evaluator)

        # Keys still present after rewrite = contexts the rewrite doesn't handle (IN lists etc.)
        $leftover     = $tokenRegex.Matches($newJql)
        $manualReview = ($leftover.Count -gt 0)
        $changed      = ($newJql -ne $filter.jql)

        $status = 'DryRun'
        if ($Commit) {
            if (-not $changed) {
                $status = 'SkippedNoChange'
            }
            else {
                try {
                    $putUri = "$JiraBaseUrl/rest/api/3/filter/$($filter.id)"
                    if ($OverrideSharePermissions) { $putUri += "?overrideSharePermissions=true" }
                    $body = @{
                        name             = $filter.name
                        description      = $filter.description
                        jql              = $newJql
                        sharePermissions = ConvertTo-PermissionRequest $filter.sharePermissions
                        editPermissions  = ConvertTo-PermissionRequest $filter.editPermissions
                    } | ConvertTo-Json -Depth 10
                    Invoke-RestMethod -Uri $putUri -Headers $headers -Method Put `
                        -ContentType 'application/json' -Body $body | Out-Null
                    $status = 'Updated'
                }
                catch {
                    $status = "FAILED: $($_.Exception.Message)"
                }
            }
        }
        elseif (-not $changed) {
            $status = 'DryRun-NoChange'
        }

        $results.Add([PSCustomObject]@{
            FilterId     = $filter.id
            FilterName   = $filter.name
            Owner        = $filter.owner.displayName
            MatchedKeys  = $keys
            FilterJql    = $filter.jql
            ReplaceJQL   = $newJql
            ManualReview = $manualReview
            Status       = $status
        })

        $color = if ($status -like 'FAILED*') { 'Red' } elseif ($manualReview) { 'Magenta' } else { 'Yellow' }
        Write-Host ("=" * 70) -ForegroundColor $color
        Write-Host "Filter ID    : $($filter.id)"
        Write-Host "Filter Name  : $($filter.name)"
        Write-Host "Matched Keys : $keys"
        Write-Host "Old JQL      : $($filter.jql)"
        Write-Host "New JQL      : $newJql" -ForegroundColor Green
        if ($manualReview) {
            Write-Host "MANUAL REVIEW: key(s) in unhandled context (IN list etc.) left untouched" -ForegroundColor Magenta
        }
        Write-Host "Status       : $status"
    }

    $startAt += $page.values.Count
    Write-Host "...scanned $totalScanned / $($page.total) filters" -ForegroundColor DarkGray

} while (-not $page.isLast -and $page.values.Count -gt 0)

# --- Summary ---
$updated = @($results | Where-Object { $_.Status -eq 'Updated' }).Count
$failed  = @($results | Where-Object { $_.Status -like 'FAILED*' }).Count
$review  = @($results | Where-Object { $_.ManualReview }).Count

Write-Host ""
Write-Host ("-" * 50)
Write-Host "Scanned        : $totalScanned filters" -ForegroundColor Cyan
Write-Host "Flagged        : $($results.Count)"     -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Updated        : $updated" -ForegroundColor Green
    Write-Host "Failed         : $failed"  -ForegroundColor $(if ($failed) { 'Red' } else { 'Cyan' })
}
Write-Host "Manual review  : $review" -ForegroundColor $(if ($review) { 'Magenta' } else { 'Cyan' })
if (-not $OverrideSharePermissions) {
    Write-Host "Note: only filters visible to this account were scanned. Use -OverrideSharePermissions (admin) for all." -ForegroundColor DarkYellow
}

if ($ExportCsv -and $results.Count -gt 0) {
    $results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
