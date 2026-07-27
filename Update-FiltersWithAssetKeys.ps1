<#
.SYNOPSIS
    Rewrites Jira Cloud filter JQLs that reference migrated asset object keys.

    Handled patterns:
      1. Direct:   field  = CMDB-74822        -> field IN aqlFunction("Legacy-Key = CMDB-74822")
                   field != CMDB-74822        -> field NOT IN aqlFunction("Legacy-Key = CMDB-74822")
      2. Arrays:   field in (CMDB-74822, CMDB-74825)
                                              -> field IN aqlFunction("Legacy-Key IN (CMDB-74822, CMDB-74825)")
                   (also NOT IN; items may be bare, quoted, or labeled "Name (KEY)")
      3. Labeled:  field = "Hardware (CMDB-74822)"
                                              -> field IN aqlFunction("Legacy-Key = CMDB-74822")

    Safety:
      - DRY RUN by default. Writes happen only with -Commit.
      - Filters already containing aqlFunction( are never rewritten
        (Status = SkippedAlreadyMigrated) so nested AQL cannot be corrupted.
      - Quoted values are only converted if they ARE a key or END with "(KEY)".
      - IN lists mixing keys with non-key values are left untouched; leftover
        key tokens after rewrite set ManualReview = True.
      - -Filters "name1","Test*" restricts processing to matching filter names
        (wildcards supported). Works in both dry-run and commit mode.

.EXAMPLE
    # Test mode, dry run on your 4 test filters:
    .\Update-FiltersWithAssetKeys.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken $token `
        -AssetKeyPrefixes CMDB,JSRK -Filters "AssetKeyTest*"

    # Test mode, commit on one filter:
    .\Update-FiltersWithAssetKeys.ps1 ... -Filters "AssetKeyTest-Direct" -Commit
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
    [string[]]$AssetKeyPrefixes,          # e.g. CMDB, JSRK

    # AQL attribute holding the old DC key on migrated objects
    [string]$AqlAttributeName = 'Legacy-Key',

    # Wrap the attribute name in escaped quotes inside the AQL string:
    # aqlFunction("\"Legacy-Key\" = CMDB-1"). Use if AQL rejects the bare hyphenated name.
    [switch]$QuoteAqlAttribute,

    # Restrict to these filter names (wildcards allowed). For testing.
    [string[]]$Filters,

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

# --- Attribute as rendered inside the AQL string ---
$script:AqlAttr = if ($QuoteAqlAttribute) { '\"{0}\"' -f $AqlAttributeName } else { $AqlAttributeName }

# --- Regexes ---
$prefixAlt  = ($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim()) }) -join '|'
$keyPattern = "(?:$prefixAlt)-\d+"
$tokenRegex = [regex]"\b$keyPattern\b"

# Extracts a key from a value: either the value IS a key, or it ends with "(KEY)".
function Get-KeyFromValue {
    param([string]$Value)
    $v = $Value.Trim()
    if ($v -match "^(?<k>$keyPattern)$")        { return $Matches['k'] }
    if ($v -match "\((?<k>$keyPattern)\)\s*$")  { return $Matches['k'] }
    return $null
}

# Pattern 2: in / not in ( item, item, ... ) — items are quoted strings or bare tokens
$listItem   = '"[^"]*"|''[^'']*''|[^,()\s]+'
$arrayRegex = [regex]"(?i)\b(not\s+in|in)\s*\(\s*(?:$listItem)(?:\s*,\s*(?:$listItem))*\s*\)"

$arrayEvaluator = {
    param($m)
    $op    = if ($m.Groups[1].Value -match '(?i)not') { 'NOT IN' } else { 'IN' }
    $inner = $m.Value.Substring($m.Value.IndexOf('(') + 1)
    $inner = $inner.Substring(0, $inner.LastIndexOf(')'))
    $keys  = @()
    foreach ($im in [regex]::Matches($inner, '"([^"]*)"|''([^'']*)''|([^,\s]+)')) {
        $raw = if ($im.Groups[1].Success) { $im.Groups[1].Value }
               elseif ($im.Groups[2].Success) { $im.Groups[2].Value }
               else { $im.Groups[3].Value }
        $k = Get-KeyFromValue $raw
        if (-not $k) { return $m.Value }   # any non-key item -> leave whole list untouched
        $keys += $k
    }
    if ($keys.Count -eq 0) { return $m.Value }
    '{0} aqlFunction("{1} IN ({2})")' -f $op, $script:AqlAttr, ($keys -join ', ')
}

# Patterns 1 + 3: (= | !=) followed by bare key, "quoted", or 'quoted'
$scalarRegex = [regex]"(!=|=)\s*(?:""([^""]*)""|'([^']*)'|($keyPattern)\b)"

$scalarEvaluator = {
    param($m)
    $val = if ($m.Groups[2].Success) { $m.Groups[2].Value }
           elseif ($m.Groups[3].Success) { $m.Groups[3].Value }
           else { $m.Groups[4].Value }
    $k = Get-KeyFromValue $val
    if (-not $k) { return $m.Value }       # ordinary string comparison -> untouched
    $op = if ($m.Groups[1].Value -eq '!=') { 'NOT IN' } else { 'IN' }
    '{0} aqlFunction("{1} = {2}")' -f $op, $script:AqlAttr, $k
}

function Convert-Jql {
    param([string]$Jql)
    $out = $arrayRegex.Replace($Jql, [System.Text.RegularExpressions.MatchEvaluator]$arrayEvaluator)
    $out = $scalarRegex.Replace($out, [System.Text.RegularExpressions.MatchEvaluator]$scalarEvaluator)
    return $out
}

# --- Strip read-only 'id' from permission objects so GET output is PUT-compatible ---
function ConvertTo-PermissionRequest {
    param($Permissions)
    if (-not $Permissions) { return @() }
    return @($Permissions | ForEach-Object {
        $_ | Select-Object -Property * -ExcludeProperty id
    })
}

Write-Host "Detection pattern : $($tokenRegex.ToString())" -ForegroundColor Cyan
Write-Host "AQL attribute     : $AqlAttributeName"          -ForegroundColor Cyan
if ($Filters) { Write-Host "Filter name scope : $($Filters -join ', ')" -ForegroundColor Cyan }
if ($Commit) {
    Write-Host "MODE: COMMIT — matching filters WILL be updated." -ForegroundColor Red
} else {
    Write-Host "MODE: DRY RUN — no writes will be made." -ForegroundColor Green
}

# --- Walk all filters (paginated) ---
$startAt        = 0
$totalScanned   = 0
$results        = New-Object System.Collections.Generic.List[object]
$processedNames = New-Object System.Collections.Generic.List[string]

do {
    $uri = "$JiraBaseUrl/rest/api/3/filter/search?startAt=$startAt&maxResults=$PageSize" +
           "&expand=jql,description,sharePermissions,editPermissions"
    if ($OverrideSharePermissions) { $uri += "&overrideSharePermissions=true" }

    $page = Invoke-RestMethod -Uri $uri -Headers $headers -Method Get

    foreach ($filter in $page.values) {
        $totalScanned++

        if ($Filters) {
            $nameHit = $false
            foreach ($pattern in $Filters) {
                if ($filter.name -like $pattern) { $nameHit = $true; break }
            }
            if (-not $nameHit) { continue }
            $processedNames.Add($filter.name)
        }

        if ([string]::IsNullOrWhiteSpace($filter.jql)) {
            if ($Filters) { Write-Host "[$($filter.name)] empty JQL — nothing to do" -ForegroundColor DarkGray }
            continue
        }

        $found = $tokenRegex.Matches($filter.jql)
        if ($found.Count -eq 0) {
            if ($Filters) { Write-Host "[$($filter.name)] no asset key references found" -ForegroundColor DarkGray }
            continue
        }

        $keys = ($found | ForEach-Object { $_.Value } | Sort-Object -Unique) -join ', '

        # Never touch filters that already use aqlFunction — nested AQL would be corrupted
        $alreadyMigrated = $filter.jql -match '(?i)aqlFunction\s*\('
        if ($alreadyMigrated) {
            $newJql       = $filter.jql
            $manualReview = $true
            $status       = 'SkippedAlreadyMigrated'
        }
        else {
            $newJql       = Convert-Jql -Jql $filter.jql
            $manualReview = $tokenRegex.IsMatch($newJql)   # leftovers = unhandled contexts
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

        $color = if ($status -like 'FAILED*') { 'Red' }
                 elseif ($status -eq 'SkippedAlreadyMigrated') { 'DarkYellow' }
                 elseif ($manualReview) { 'Magenta' }
                 else { 'Yellow' }
        Write-Host ("=" * 70) -ForegroundColor $color
        Write-Host "Filter ID    : $($filter.id)"
        Write-Host "Filter Name  : $($filter.name)"
        Write-Host "Matched Keys : $keys"
        Write-Host "Current JQL  : $($filter.jql)"
        Write-Host "Replace JQL  : $newJql" -ForegroundColor Green
        if ($status -eq 'SkippedAlreadyMigrated') {
            Write-Host "SKIPPED      : already contains aqlFunction — review manually" -ForegroundColor DarkYellow
        }
        elseif ($manualReview) {
            Write-Host "MANUAL REVIEW: key(s) left in unhandled context" -ForegroundColor Magenta
        }
        Write-Host "Status       : $status"
    }

    $startAt += $page.values.Count
    Write-Host "...scanned $totalScanned / $($page.total) filters" -ForegroundColor DarkGray

} while (-not $page.isLast -and $page.values.Count -gt 0)

# --- Warn about -Filters patterns that matched nothing ---
if ($Filters) {
    foreach ($pattern in $Filters) {
        $hit = $false
        foreach ($n in $processedNames) { if ($n -like $pattern) { $hit = $true; break } }
        if (-not $hit) {
            Write-Host "WARNING: no scanned filter matched -Filters pattern '$pattern'" -ForegroundColor Red
        }
    }
}

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
