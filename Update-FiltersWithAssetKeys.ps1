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
      4. Single-value IN without parentheses (DC oddity):
                   field in "APP (JRSK-36294)"
                                              -> field IN aqlFunction("Legacy-Key = JRSK-36294")

    -Filters semantics:
      - Names without wildcards: EXACT match. Each name is queried server-side
        (filterName param), then the single filter whose name equals the given
        name is selected. A name with no exact match processes nothing.
      - Names with wildcards (* ? [ ]): full scan, client-side -like matching.

    Safety:
      - DRY RUN by default. Writes happen only with -Commit.
      - Filters already containing aqlFunction( are never rewritten
        (Status = SkippedAlreadyMigrated).
      - Quoted values are only converted if they ARE a key or END with "(KEY)".
      - IN lists mixing keys with non-key values are left untouched; leftover
        key tokens after rewrite set ManualReview = True.

.EXAMPLE
    .\Update-FiltersWithAssetKeys.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken $token `
        -AssetKeyPrefixes CMDB,JSRK -AqlAttributeName Key `
        -Filters "TestFilter_Compound","TestFilter_Compound_Public"
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

    # Restrict to these filter names. No wildcards = exact name match via server-side
    # query. Wildcards = full scan with client-side -like matching.
    [string[]]$Filters,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
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

# --- Preflight: verify credentials actually authenticate ---
# A request Jira can't authenticate may be served as ANONYMOUS on read endpoints
# instead of erroring — filter/search then silently returns zero filters.
try {
    $me = Invoke-RestMethod -Uri "$JiraBaseUrl/rest/api/3/myself" -Headers $headers -Method Get
}
catch {
    throw "Auth preflight against /rest/api/3/myself failed: $($_.Exception.Message) — check -JiraBaseUrl, -Email, -ApiToken (watch for trailing whitespace/newline in the token; note API tokens now expire, max 1 year)."
}
if (-not $me.accountId) {
    throw "Jira served the request as ANONYMOUS (no accountId from /myself). The -Email/-ApiToken pair is not authenticating."
}
Write-Host "Authenticated as  : $($me.displayName) [$($me.accountId)]" -ForegroundColor Cyan

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

# Patterns 1 + 3 + single-value IN: (= | != | in | not in) followed by bare key, "quoted", or 'quoted'
# Single-value IN covers the DC oddity:  "IT Service Team" in "APP (JRSK-36294)"  (no parentheses)
$scalarRegex = [regex]"((?i:\bnot\s+in\b|\bin\b)|!=|=)\s*(?:""([^""]*)""|'([^']*)'|($keyPattern)\b)"

$scalarEvaluator = {
    param($m)
    $val = if ($m.Groups[2].Success) { $m.Groups[2].Value }
           elseif ($m.Groups[3].Success) { $m.Groups[3].Value }
           else { $m.Groups[4].Value }
    $k = Get-KeyFromValue $val
    if (-not $k) { return $m.Value }       # ordinary string comparison -> untouched
    $op = if ($m.Groups[1].Value -eq '!=' -or $m.Groups[1].Value -match '(?i)^not') { 'NOT IN' } else { 'IN' }
    '{0} aqlFunction("{1} = {2}")' -f $op, $script:AqlAttr, $k
}

function Convert-Jql {
    param([string]$Jql)
    $out = $arrayRegex.Replace($Jql, [System.Text.RegularExpressions.MatchEvaluator]$arrayEvaluator)
    $out = $scalarRegex.Replace($out, [System.Text.RegularExpressions.MatchEvaluator]$scalarEvaluator)
    return $out
}

# --- Map GET permission objects to the minimal shapes the PUT input schema expects ---
function ConvertTo-PermissionRequest {
    param($Permissions)
    if (-not $Permissions) { return @() }
    $out = @()
    foreach ($p in $Permissions) {
        switch ($p.type) {
            'user'        { $out += @{ type = 'user'; user = @{ accountId = $p.user.accountId } } }
            'group'       {
                if ($p.group.groupId) { $out += @{ type = 'group'; group = @{ groupId = $p.group.groupId } } }
                else                  { $out += @{ type = 'group'; group = @{ name = $p.group.name } } }
            }
            'project'     { $out += @{ type = 'project'; project = @{ id = "$($p.project.id)" } } }
            'projectRole' { $out += @{ type = 'projectRole'; project = @{ id = "$($p.project.id)" }; role = @{ id = "$($p.role.id)" } } }
            default       { $out += @{ type = $p.type } }   # global / loggedin / authenticated
        }
    }
    return ,$out
}

# --- Extract the real error body from a failed REST call (PS 5.1 and 7.x) ---
function Get-JiraErrorDetail {
    param($ErrorRecord)
    if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
        return $ErrorRecord.ErrorDetails.Message
    }
    try {
        $stream = $ErrorRecord.Exception.Response.GetResponseStream()
        if ($stream) {
            if ($stream.CanSeek) { $stream.Position = 0 }
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd()
            if ($body) { return "$($ErrorRecord.Exception.Message) | $body" }
        }
    } catch { }
    return $ErrorRecord.Exception.Message
}

# --- Paginated fetch; returns all filter objects for the given extra query ---
$script:baseUri = "$JiraBaseUrl/rest/api/3/filter/search?maxResults=$PageSize" +
                  "&expand=jql,description,sharePermissions,editPermissions"
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

# --- Per-filter processing (shared by both selection modes) ---
$script:totalScanned = 0
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    if ([string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] empty JQL — nothing to do" -ForegroundColor DarkGray }
        return
    }

    $found = $tokenRegex.Matches($filter.jql)
    if ($found.Count -eq 0) {
        if ($Filters) { Write-Host "[$($filter.name)] no asset key references found" -ForegroundColor DarkGray }
        return
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
                    Invoke-RestMethod -Uri $putUri -Headers $script:headers -Method Put `
                        -ContentType 'application/json' -Body $body | Out-Null
                    $status = 'Updated'
                }
                catch {
                    $status = "FAILED: $(Get-JiraErrorDetail $_)"
                }
            }
        }
        elseif (-not $changed) {
            $status = 'DryRun-NoChange'
        }
    }

    $script:results.Add([PSCustomObject]@{
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

# --- Startup info ---
Write-Host "Detection pattern : $($tokenRegex.ToString())" -ForegroundColor Cyan
Write-Host "AQL attribute     : $AqlAttributeName"          -ForegroundColor Cyan
if ($Filters) { Write-Host "Filter name scope : $($Filters -join ', ')" -ForegroundColor Cyan }
if ($Commit) {
    Write-Host "MODE: COMMIT — matching filters WILL be updated." -ForegroundColor Red
} else {
    Write-Host "MODE: DRY RUN — no writes will be made." -ForegroundColor Green
}

# --- Selection ---
$wildcardMode = [bool]($Filters | Where-Object { $_ -match '[\*\?\[\]]' })

if ($Filters -and -not $wildcardMode) {
    # Exact-name mode: server-side name query per name, then exact equality, first match only
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
$updated = @($script:results | Where-Object { $_.Status -eq 'Updated' }).Count
$failed  = @($script:results | Where-Object { $_.Status -like 'FAILED*' }).Count
$review  = @($script:results | Where-Object { $_.ManualReview }).Count

Write-Host ""
Write-Host ("-" * 50)
Write-Host "Scanned        : $($script:totalScanned) filters" -ForegroundColor Cyan
Write-Host "Flagged        : $($script:results.Count)"        -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Updated        : $updated" -ForegroundColor Green
    Write-Host "Failed         : $failed"  -ForegroundColor $(if ($failed) { 'Red' } else { 'Cyan' })
}
Write-Host "Manual review  : $review" -ForegroundColor $(if ($review) { 'Magenta' } else { 'Cyan' })
if (-not $OverrideSharePermissions -and -not $Filters) {
    Write-Host "Note: only filters visible to this account were scanned. Use -OverrideSharePermissions (admin) for all." -ForegroundColor DarkYellow
}

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
