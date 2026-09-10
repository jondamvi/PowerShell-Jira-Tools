<#
.SYNOPSIS
    Rewrites Jira Cloud filter JQLs that reference migrated asset object keys. v2.

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
    NOT rewritten, by design:
      - ~ / !~ text-search operands (free-text content matches). Filters whose keys
        sit ONLY there get Status=Skipped, reason "body content matching".
      - Filters already containing aqlFunction( (Status=SkippedAlreadyMigrated) —
        makes re-runs idempotent, nested rewriting impossible.
      - IN lists mixing keys with non-key values (left whole, flagged ManualReview).

    Target selection (first match wins):
      -FilterIds  : direct by Cloud filter ID, zero search calls. RECOMMENDED for
                    production batches — filter names are NOT unique across owners.
                    Source of truth: Find-FiltersWithAssetKeys CSV 'Filter ID' column.
      -Filters    : names without wildcards = exact name equality; the server-side
                    filterName query is sent double-quoted (unquoted input is
                    tokenized: "ACM -Filter" would mean ACM AND NOT Filter), with a
                    full-scan fallback if the quoted query returns nothing.
                    Names with wildcards (* ? [ ]) = full scan + -like.
      (neither)   : full scan of every visible filter.

    Update mechanics:
      - PUT body contains ONLY name + jql, sent as UTF-8 bytes (umlaut-safe on
        Windows PowerShell 5.1). Permissions/subscriptions are never sent, so
        Jira leaves them untouched.
      - Before/after state (owner, viewers, editors, subscriptions, JQL) is
        fetched, logged, and compared; any drift lands in the Errors column.
      - -OverrideSharePermissions (admin) includes other users' private filters in
        scanning and reads; it does NOT allow updating filters you don't own.
        -TakeOwnershipTemporarily swaps ownership to you for the PUT and restores
        the original owner in a finally block; owner drift is validated.
      - -Backup <name> writes .\FiltersBackup_<name>_<stamp>\<FilterID>.json and
        <FilterID>.JQL.txt per processed filter BEFORE any change; a failed
        backup blocks modification of that filter.
      - DRY RUN by default. -Commit asks to type YES; -Force skips the prompt.

    CSV columns: Filter Name, Filter ID, Owner Name, Owner Email, Owner ID,
                 Viewers, Editors, Original JQL, Updated JQL (empty when Skipped),
                 Status, Errors (newline-stacked), Comments (for manual input)

.EXAMPLE
    # Production pipeline:
    .\Find-FiltersWithAssetKeys.ps1 ... -ExportCsv cloud-affected.csv
    # review/prune the CSV, then:
    .\Update-FiltersWithAssetKeys_v2.ps1 -JiraBaseUrl "https://yoursite.atlassian.net" `
        -Email "you@company.com" -ApiToken $token -AssetKeyPrefixes CMDB,JRSK `
        -FilterIds (Import-Csv .\cloud-affected.csv).'Filter ID' `
        -OverrideSharePermissions -TakeOwnershipTemporarily `
        -Backup ProdAssetKeys -ExportCsv prod-dryrun.csv
    # then same command + -Commit
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
    [string[]]$AssetKeyPrefixes,          # e.g. CMDB, JRSK

    # AQL attribute holding the old DC key on migrated objects
    [string]$AqlAttributeName = 'Legacy-Key',

    # Wrap the attribute name in escaped quotes inside the AQL string:
    # aqlFunction("\"Legacy-Key\" = CMDB-1"). Use if AQL rejects the bare name.
    [switch]$QuoteAqlAttribute,

    # Match asset keys case-insensitively (catches lowercase usage like
    # "iam-8238") and normalize them to canonical uppercase in the generated
    # AQL. Enabled by default; disable with -CaseInsensitiveKeys:$false.
    [switch]$CaseInsensitiveKeys = $true,

    # Process exactly these Cloud filter IDs — no search calls, no name matching.
    # Takes precedence over -Filters. Filter names are NOT unique across owners;
    # IDs are. Source of truth: Find-FiltersWithAssetKeys CSV.
    [string[]]$FilterIds,

    # Restrict to these filter names. No wildcards = exact name equality
    # (double-quoted server-side query + full-scan fallback). Wildcards = full
    # scan with client-side -like matching.
    [string[]]$Filters,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
    # Includes other users' private filters when scanning and reading state.
    # NOTE: does NOT allow updating filters you don't own — see -TakeOwnershipTemporarily.
    [switch]$OverrideSharePermissions,

    # Without this switch the script is a pure dry run — zero write calls.
    [switch]$Commit,

    # Skip the interactive COMMIT confirmation prompt (for unattended runs).
    [switch]$Force,

    # Jira refuses JQL updates on filters you don't own or can't edit — admin
    # rights and overrideSharePermissions do NOT bypass this. This switch
    # temporarily reassigns ownership to the authenticated admin via
    # PUT /filter/{id}/owner, applies the JQL fix, then restores the original
    # owner. Owner drift is validated.
    [switch]$TakeOwnershipTemporarily,

    # Back up every processed filter before any change into
    # .\FiltersBackup_<BackupName>_<yyyyMMdd_HHmmss>\ as <FilterID>.json (full
    # filter object) and <FilterID>.JQL.txt (raw JQL string only). Also active
    # in dry run. In commit mode a failed backup blocks modification.
    [string]$Backup,

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
$script:MyAccountId = $me.accountId

# --- Backup directory ---
$script:BackupDir = $null
if ($Backup) {
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $script:BackupDir = Join-Path -Path (Get-Location) -ChildPath ("FiltersBackup_{0}_{1}" -f $Backup, $stamp)
    New-Item -ItemType Directory -Path $script:BackupDir -Force | Out-Null
    Write-Host "Backup directory  : $script:BackupDir" -ForegroundColor Cyan
}

function Backup-Filter {
    param($Detail)
    $jsonPath = Join-Path $script:BackupDir "$($Detail.id).json"
    $jqlPath  = Join-Path $script:BackupDir "$($Detail.id).JQL.txt"
    $Detail | ConvertTo-Json -Depth 20 | Set-Content -Path $jsonPath -Encoding UTF8
    Set-Content -Path $jqlPath -Value "$($Detail.jql)" -Encoding UTF8
}

# --- Attribute as rendered inside the AQL string ---
$script:AqlAttr = if ($QuoteAqlAttribute) { '\"{0}\"' -f $AqlAttributeName } else { $AqlAttributeName }

# --- Regexes ---
$prefixAlt  = ($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim()) }) -join '|'
$keyPattern = "(?:$prefixAlt)-\d+"
$ciPrefix   = if ($CaseInsensitiveKeys) { '(?i)' } else { '' }
$tokenRegex = [regex]"$ciPrefix\b$keyPattern\b"
# Bare-key branch of the scalar regex honors the same case sensitivity setting
$bareKeyPattern = if ($CaseInsensitiveKeys) { "(?i:$keyPattern)" } else { $keyPattern }

function Get-KeyFromValue {
    param([string]$Value)
    $v = $Value.Trim()
    if ($CaseInsensitiveKeys) {
        # -match is case-insensitive; normalize extracted keys to canonical uppercase
        if ($v -match "^(?<k>$keyPattern)$")        { return $Matches['k'].ToUpper() }
        if ($v -match "\((?<k>$keyPattern)\)\s*$")  { return $Matches['k'].ToUpper() }
    }
    else {
        if ($v -cmatch "^(?<k>$keyPattern)$")       { return $Matches['k'] }
        if ($v -cmatch "\((?<k>$keyPattern)\)\s*$") { return $Matches['k'] }
    }
    return $null
}

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
    '{0} aqlFunction("{1} IN ({2})")' -f $op, $script:AqlAttr, (($keys | Select-Object -Unique) -join ', ')
}

$scalarRegex = [regex]"((?i:\bnot\s+in\b|\bin\b)|!=|=)\s*(?:""([^""]*)""|'([^']*)'|($bareKeyPattern)\b)"

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

# Text-search operands: values after ~ or !~ are free-text CONTENT searches, not
# Assets field references. They are masked before rewriting so nothing inside them
# can ever be modified (e.g. prose containing "in (CMDB-123)" or "= CMDB-123"),
# then restored untouched. Keys found only inside them are reported as Skipped.
$textOpRegex = [regex]'(!~|~)\s*("[^"]*"|''[^'']*''|[^\s()"'']+)'

# aqlFunction("...") arguments contain the rewritten keys BY DESIGN — they are
# rewrite output, never leftovers. Handles escaped quotes from -QuoteAqlAttribute.
$aqlFuncRegex = [regex]'(?i)aqlFunction\s*\(\s*"(?:[^"\\]|\\.)*"\s*\)'

function Get-TextMaskedJql {
    # Returns @{ Masked = jql with text operands replaced by tokens; Store = token -> operand }
    param([string]$Jql)
    $store = @{}
    $out   = $Jql
    $found = $textOpRegex.Matches($Jql)
    for ($i = $found.Count - 1; $i -ge 0; $i--) {
        $g     = $found[$i].Groups[2]
        $token = "%%TXTOPERAND$i%%"
        $store[$token] = $g.Value
        $out = $out.Remove($g.Index, $g.Length).Insert($g.Index, $token)
    }
    return @{ Masked = $out; Store = $store }
}

function Convert-Jql {
    param([string]$Jql)
    $mask = Get-TextMaskedJql -Jql $Jql
    $out  = $mask.Masked
    $out  = $arrayRegex.Replace($out, [System.Text.RegularExpressions.MatchEvaluator]$arrayEvaluator)
    $out  = $scalarRegex.Replace($out, [System.Text.RegularExpressions.MatchEvaluator]$scalarEvaluator)
    foreach ($token in $mask.Store.Keys) { $out = $out.Replace($token, $mask.Store[$token]) }
    return $out
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

# --- Human-readable renderers for permissions and subscriptions ---
function Format-Permissions {
    param($Permissions)
    if (-not $Permissions -or @($Permissions).Count -eq 0) { return 'Private' }
    $parts = foreach ($p in $Permissions) {
        switch ($p.type) {
            'global'        { 'Public' }
            'authenticated' { 'My Organization' }
            'loggedin'      { 'My Organization' }
            'project'       {
                if ($p.role) { "Project: $($p.project.name) / Role: $($p.role.name)" }
                else         { "Project: $($p.project.name)" }
            }
            'projectRole'   { "Project: $($p.project.name) / Role: $($p.role.name)" }
            'group'         { "Group: $($p.group.name)" }
            'user'          { "User: $($p.user.displayName)" }
            default         { "Unknown type '$($p.type)'" }
        }
    }
    return ($parts -join '; ')
}

function Format-Subscriptions {
    param($Subscriptions)
    $items = @($Subscriptions.items)
    if ($items.Count -eq 0) { return 'None' }
    $parts = foreach ($s in $items) {
        if ($s.group -and $s.group.name) { "Group: $($s.group.name)" }
        elseif ($s.user)                 { "User: $($s.user.displayName)" }
        else                             { "Subscription id $($s.id)" }
    }
    return ($parts -join '; ')
}

# --- Fetch full single-filter state (permissions + subscriptions) ---
function Get-FilterDetail {
    param([string]$FilterId)
    $uri = "$JiraBaseUrl/rest/api/3/filter/${FilterId}?expand=sharePermissions,editPermissions,subscriptions"
    if ($OverrideSharePermissions) { $uri += "&overrideSharePermissions=true" }
    return Invoke-RestMethod -Uri $uri -Headers $script:headers -Method Get
}

function Set-FilterOwner {
    param([string]$FilterId, [string]$AccountId)
    $uri  = "$JiraBaseUrl/rest/api/3/filter/${FilterId}/owner"
    $body = [System.Text.Encoding]::UTF8.GetBytes((@{ accountId = $AccountId } | ConvertTo-Json))
    Invoke-RestMethod -Uri $uri -Headers $script:headers -Method Put -ContentType 'application/json; charset=utf-8' -Body $body | Out-Null
}

function Write-FilterState {
    param([string]$Label, $Detail)
    Write-Host "--- $Label ---" -ForegroundColor Cyan
    Write-Host ("  Name          : {0}" -f $Detail.name)
    Write-Host ("  ID            : {0}" -f $Detail.id)
    Write-Host ("  Owner         : {0} <{1}> [{2}]" -f $Detail.owner.displayName, $Detail.owner.emailAddress, $Detail.owner.accountId)
    Write-Host ("  Viewers       : {0}" -f (Format-Permissions $Detail.sharePermissions))
    Write-Host ("  Editors       : {0}" -f (Format-Permissions $Detail.editPermissions))
    Write-Host ("  Subscriptions : {0}" -f (Format-Subscriptions $Detail.subscriptions))
    Write-Host ("  JQL           : {0}" -f $Detail.jql)
}

# --- Paginated fetch ---
$script:baseUri = "$JiraBaseUrl/rest/api/3/filter/search?maxResults=$PageSize&expand=jql"
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

# --- Per-filter processing ---
$script:totalScanned = 0
$script:countUpdated = 0
$script:countFailed  = 0
$script:countReview  = 0
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    if ([string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters -or $FilterIds) { Write-Host "[$($filter.name)] empty JQL — nothing to do" -ForegroundColor DarkGray }
        return
    }
    if (-not $tokenRegex.IsMatch($filter.jql)) {
        if ($Filters -or $FilterIds) { Write-Host "[$($filter.name)] no asset key references found" -ForegroundColor DarkGray }
        return
    }

    $errors = New-Object System.Collections.Generic.List[string]

    # Full BEFORE state (fresh GET: permissions, subscriptions, current JQL)
    $before = $null
    try   { $before = Get-FilterDetail -FilterId $filter.id }
    catch { $errors.Add("GET filter detail failed: $(Get-JiraErrorDetail $_)") }
    if (-not $before) { $before = $filter }

    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-FilterState -Label "BEFORE update" -Detail $before

    $backupFailed = $false
    if ($script:BackupDir) {
        try   { Backup-Filter -Detail $before }
        catch { $backupFailed = $true; $errors.Add("BACKUP FAILED: $($_.Exception.Message) — filter will NOT be modified") }
    }

    $originalJql = $before.jql

    # Rewrite
    $alreadyMigrated = $originalJql -match '(?i)aqlFunction\s*\('
    if ($alreadyMigrated) {
        $newJql       = $originalJql
        $manualReview = $true
        $errors.Add("WARN: already contains aqlFunction — skipped, review manually")
        $script:countReview++
        $status = 'SkippedAlreadyMigrated'
    }
    else {
        $newJql  = Convert-Jql -Jql $originalJql
        $changed = ($newJql -ne $originalJql)

        # Leftover analysis. Keys inside aqlFunction("...") arguments are rewrite
        # OUTPUT; keys inside ~ text operands are content matches. Only keys
        # outside BOTH contexts are genuinely unhandled.
        $mask       = Get-TextMaskedJql -Jql $newJql
        $keysInText = $false
        foreach ($v in $mask.Store.Values) {
            if ($tokenRegex.IsMatch($v)) { $keysInText = $true; break }
        }
        $checkJql     = $aqlFuncRegex.Replace($mask.Masked, 'aqlFunction("#")')
        $manualReview = $tokenRegex.IsMatch($checkJql)
        $textOnly     = (-not $changed) -and (-not $manualReview) -and $keysInText
        if ($textOnly) {
            $errors.Add("Skipped: body content matching (~ text search) — not relevant for replace")
        }
        elseif ($manualReview) {
            $errors.Add("WARN: key(s) left in unhandled JQL context — manual review needed")
            $script:countReview++
        }
        elseif ($keysInText) {
            $errors.Add("INFO: some asset key(s) also appear inside text-search (~) operands — content match, left untouched")
        }

        $status = if ($textOnly) { 'Skipped' } else { 'DryRun' }
        if ($backupFailed -and $Commit -and -not $textOnly) {
            $status = 'FAILED'
            $script:countFailed++
        }
        elseif ($Commit -and -not $textOnly) {
            if (-not $changed) {
                $status = 'SkippedNoChange'
            }
            else {
                $ownershipTaken  = $false
                $originalOwnerId = "$($before.owner.accountId)"
                $ownershipError  = $false
                if ($originalOwnerId -and $originalOwnerId -ne $script:MyAccountId) {
                    if ($TakeOwnershipTemporarily) {
                        try {
                            Write-Host "Taking temporary ownership (original owner: $($before.owner.displayName) [$originalOwnerId])" -ForegroundColor DarkYellow
                            Set-FilterOwner -FilterId $filter.id -AccountId $script:MyAccountId
                            $ownershipTaken = $true
                        }
                        catch {
                            $ownershipError = $true
                            $status = 'FAILED'
                            $errors.Add("OWNERSHIP TAKE failed: $(Get-JiraErrorDetail $_)")
                            $script:countFailed++
                        }
                    }
                    else {
                        Write-Host "Filter is owned by $($before.owner.displayName) — Jira may refuse the update without -TakeOwnershipTemporarily" -ForegroundColor DarkYellow
                    }
                }
                try {
                    if (-not $ownershipError) {
                        $putUri = "$JiraBaseUrl/rest/api/3/filter/$($filter.id)"
                        if ($OverrideSharePermissions) { $putUri += "?overrideSharePermissions=true" }
                        # Minimal body: ONLY name + jql, sent as explicit UTF-8 bytes so
                        # non-ASCII text (umlauts etc.) survives Windows PowerShell's
                        # legacy request encoding. Permissions/subscriptions never sent.
                        $bodyJson  = @{ name = $before.name; jql = $newJql } | ConvertTo-Json
                        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)
                        Invoke-RestMethod -Uri $putUri -Headers $script:headers -Method Put `
                            -ContentType 'application/json; charset=utf-8' -Body $bodyBytes | Out-Null
                        $status = 'Updated'
                        $script:countUpdated++
                    }
                }
                catch {
                    $status = 'FAILED'
                    $detail = Get-JiraErrorDetail $_
                    if ($detail -match 'does not exist') {
                        $errors.Add("PUT rejected by Jira JQL validation — pre-existing problem in OTHER clauses (field/value missing after migration), not the asset-key rewrite: $detail")
                    }
                    else {
                        $errors.Add("PUT failed: $detail")
                    }
                    if (-not $TakeOwnershipTemporarily -and $detail -match '(?i)owner|permission') {
                        $errors.Add("HINT: only the filter owner (or edit-permission grantees) may modify a filter — re-run with -TakeOwnershipTemporarily")
                    }
                    $script:countFailed++
                }
                finally {
                    if ($ownershipTaken) {
                        try {
                            Set-FilterOwner -FilterId $filter.id -AccountId $originalOwnerId
                            Write-Host "Restored original owner [$originalOwnerId]" -ForegroundColor DarkYellow
                        }
                        catch {
                            $errors.Add("CRITICAL: failed to restore original owner [$originalOwnerId]: $(Get-JiraErrorDetail $_) — restore manually via Jira admin filter management")
                        }
                    }
                }

                # AFTER state + built-in validation
                try {
                    $after = Get-FilterDetail -FilterId $filter.id
                    Write-FilterState -Label "AFTER update" -Detail $after

                    if ($status -eq 'Updated' -and $after.jql -ne $newJql) {
                        $errors.Add("VALIDATION: JQL after update differs from intended value")
                    }
                    $checks = @(
                        @{ Label = 'Owner';         B = "$($before.owner.accountId)"; A = "$($after.owner.accountId)" },
                        @{ Label = 'Viewers';       B = (Format-Permissions  $before.sharePermissions); A = (Format-Permissions  $after.sharePermissions) },
                        @{ Label = 'Editors';       B = (Format-Permissions  $before.editPermissions);  A = (Format-Permissions  $after.editPermissions)  },
                        @{ Label = 'Subscriptions'; B = (Format-Subscriptions $before.subscriptions);   A = (Format-Subscriptions $after.subscriptions)   }
                    )
                    foreach ($c in $checks) {
                        if ($c.B -ne $c.A) {
                            $errors.Add("VALIDATION: $($c.Label) changed: [$($c.B)] -> [$($c.A)]")
                        }
                    }
                }
                catch {
                    $errors.Add("GET after-state failed: $(Get-JiraErrorDetail $_)")
                }
            }
        }
        elseif (-not $changed -and -not $textOnly) {
            $status = 'DryRun-NoChange'
        }
    }

    if ($status -eq 'Skipped') {
        Write-Host "Proposed JQL : (none — skipped, body content matching)" -ForegroundColor DarkGray
    }
    else {
        Write-Host "Proposed JQL : $newJql" -ForegroundColor Green
    }
    Write-Host "Status       : $status" -ForegroundColor $(
        if ($status -eq 'FAILED') { 'Red' }
        elseif ($status -eq 'Skipped') { 'DarkGray' }
        elseif ($status -eq 'SkippedAlreadyMigrated') { 'DarkYellow' }
        elseif ($errors.Count -gt 0) { 'Magenta' }
        else { 'Yellow' })
    foreach ($e in $errors) { Write-Host "  ! $e" -ForegroundColor Magenta }

    $script:results.Add([PSCustomObject]([ordered]@{
        'Filter Name'  = $before.name
        'Filter ID'    = $before.id
        'Owner Name'   = $before.owner.displayName
        'Owner Email'  = "$($before.owner.emailAddress)"
        'Owner ID'     = $before.owner.accountId
        'Viewers'      = (Format-Permissions $before.sharePermissions)
        'Editors'      = (Format-Permissions $before.editPermissions)
        'Original JQL' = $originalJql
        'Updated JQL'  = $(if ($status -eq 'Skipped') { '' } else { $newJql })
        'Status'       = $status
        'Errors'       = ($errors -join "`n")
        'Comments'     = ''
    }))
}

# --- Startup info ---
Write-Host "Detection pattern : $($tokenRegex.ToString())" -ForegroundColor Cyan
Write-Host "AQL attribute     : $AqlAttributeName"          -ForegroundColor Cyan
Write-Host "Key matching      : $(if ($CaseInsensitiveKeys) { 'case-insensitive (normalized to UPPERCASE)' } else { 'case-sensitive' })" -ForegroundColor Cyan
if ($FilterIds)     { Write-Host "Filter ID scope   : $(@($FilterIds).Count) ids" -ForegroundColor Cyan }
elseif ($Filters)   { Write-Host "Filter name scope : $($Filters -join ', ')"     -ForegroundColor Cyan }
if ($Commit) {
    Write-Host "MODE: COMMIT — matching filters WILL be updated." -ForegroundColor Red
    if (-not $Force) {
        $answer = Read-Host "Type YES (uppercase) to confirm committing JQL updates"
        if ($answer -cne 'YES') {
            Write-Host "Not confirmed — aborting without changes." -ForegroundColor Yellow
            return
        }
    }
} else {
    Write-Host "MODE: DRY RUN — no writes will be made." -ForegroundColor Green
}

# --- Selection ---
$wildcardMode = [bool]($Filters | Where-Object { $_ -match '[\*\?\[\]]' })

if ($FilterIds) {
    Write-Host "Scan strategy     : direct by filter ID ($(@($FilterIds).Count) ids, zero search calls)" -ForegroundColor Cyan
    foreach ($fid in $FilterIds) {
        $fid = "$fid".Trim()
        if (-not $fid) { continue }
        try   { $f = Get-FilterDetail -FilterId $fid }
        catch { Write-Host "Filter id ${fid}: $(Get-JiraErrorDetail $_)" -ForegroundColor Red; continue }
        Invoke-FilterProcessing -filter $f
    }
}
elseif ($Filters -and -not $wildcardMode) {
    Write-Host "Scan strategy     : exact name match (quoted server-side query per name)" -ForegroundColor Cyan
    Write-Host "NOTE: filter names are NOT unique across owners — prefer -FilterIds for batches" -ForegroundColor DarkYellow
    foreach ($name in $Filters) {
        # Quote the name: unquoted filterName is tokenized fuzzy matching where a
        # leading '-' negates a term ("ACM -Filter" = ACM AND NOT Filter). A
        # double-quoted phrase is matched literally.
        $candidates = Get-AllFilterPages -ExtraQuery ("&filterName=" + [uri]::EscapeDataString('"' + $name + '"'))
        $target = $candidates | Where-Object { $_.name -eq $name } | Select-Object -First 1

        if (-not $target) {
            # filterName remains an undocumented matcher — fall back to a full scan
            # with the same strict equality rather than silently processing nothing.
            Write-Host "Server-side name query found no exact match for '$name' — falling back to full scan" -ForegroundColor DarkYellow
            if (-not $script:AllFiltersCache) { $script:AllFiltersCache = Get-AllFilterPages }
            $target = $script:AllFiltersCache | Where-Object { $_.name -eq $name } | Select-Object -First 1
        }

        if ($target) { Invoke-FilterProcessing -filter $target }
        else { Write-Host "No filter named '$name' found (server query and full scan both checked)." -ForegroundColor Red }
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
Write-Host "Flagged        : $($script:results.Count)"        -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Updated        : $($script:countUpdated)" -ForegroundColor Green
    Write-Host "Failed         : $($script:countFailed)"  -ForegroundColor $(if ($script:countFailed) { 'Red' } else { 'Cyan' })
}
Write-Host "Manual review  : $($script:countReview)" -ForegroundColor $(if ($script:countReview) { 'Magenta' } else { 'Cyan' })

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
