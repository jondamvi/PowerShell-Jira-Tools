<#
.SYNOPSIS
    Rewrites Jira Cloud filter JQLs that use the deprecated childIssuesOf() JQL
    function, replacing it with portfolioChildIssuesOf(). Function arguments and
    the rest of the JQL are left byte-for-byte untouched — the rewrite renames
    the function only.

        issue in childIssuesOf("PROJ-123")   ->   issue in portfolioChildIssuesOf("PROJ-123")

    -Filters semantics:
      - Names without wildcards: EXACT name match via server-side query.
      - Names with wildcards (* ? [ ]): full scan, client-side -like matching.

    Update mechanics:
      - PUT body contains ONLY name + jql. Share permissions, edit permissions and
        subscriptions are never sent, so Jira leaves them untouched.
      - Before/after state (owner, viewers, editors, subscriptions, JQL) is fetched,
        logged to console, and compared. Any drift is recorded in the Errors column.
      - DRY RUN by default. -Commit asks for interactive confirmation (type YES);
        -Force skips the prompt for unattended runs.
      - -OverrideSharePermissions (admin) includes other users' private filters in
        scanning and state reads. Jira refuses JQL updates on filters you don't own
        or can't edit (admin rights and overrideSharePermissions do NOT bypass this);
        -TakeOwnershipTemporarily swaps ownership to you for the update and restores
        the original owner immediately after, with owner drift validated.

    CSV columns: Filter Name, Filter ID, Owner Name, Owner Email, Owner ID,
                 Viewers, Editors, JQL Before, JQL After, Status, Errors, Comments
    Status values: Found (dry run), Fixed (committed), Update Error (PUT failed)
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string]$JiraBaseUrl,

    [Parameter(Mandatory)]
    [string]$Email,

    [Parameter(Mandatory)]
    [string]$ApiToken,

    # Replacement function name. Default per Atlassian's supported Cloud function.
    [string]$ReplacementFunction = 'portfolioChildIssuesOf',

    # No wildcards = exact name match (server-side query). Wildcards = full scan + -like.
    [string[]]$Filters,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
    # Includes other users' private filters when scanning and reading filter state.
    # NOTE: does NOT allow updating filters you don't own — see -TakeOwnershipTemporarily.
    [switch]$OverrideSharePermissions,

    # Without this switch the script is a pure dry run — zero write calls.
    [switch]$Commit,

    # Skip the interactive COMMIT confirmation prompt (for unattended runs).
    [switch]$Force,

    # Jira refuses JQL updates on filters you don't own or can't edit — admin rights
    # and overrideSharePermissions do NOT bypass this. This switch temporarily
    # reassigns ownership to the authenticated admin via PUT /filter/{id}/owner,
    # applies the JQL fix, then restores the original owner. Owner drift is validated.
    [switch]$TakeOwnershipTemporarily,

    # Back up every processed filter BEFORE any change into
    # .\FiltersBackup_<BackupName>_<yyyyMMdd_HHmmss>\ containing <FilterID>.json
    # (full filter object incl. permissions/subscriptions) and <FilterID>.JQL.txt
    # (raw JQL string only). Also active in dry run. In commit mode a failed
    # backup blocks modification of that filter.
    [string]$Backup,

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

# --- Detection / rewrite regex ---
# Matches the bare function name childIssuesOf only when followed by "(",
# and never inside portfolioChildIssuesOf (word boundary + explicit lookbehind).
$funcRegex = [regex]'(?i)(?<!portfolio)\bchildIssuesOf(?=\s*\()'

function Convert-Jql {
    param([string]$Jql)
    return $funcRegex.Replace($Jql, $ReplacementFunction)
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
$script:countFixed   = 0
$script:countFailed  = 0
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    if ([string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] empty JQL — nothing to do" -ForegroundColor DarkGray }
        return
    }
    if (-not $funcRegex.IsMatch($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] no childIssuesOf usage found" -ForegroundColor DarkGray }
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
    $newJql      = Convert-Jql -Jql $originalJql

    if ($funcRegex.IsMatch($newJql)) {
        $errors.Add("VALIDATION: childIssuesOf still present after rewrite — inspect manually")
    }

    $status = 'Found'
    if ($backupFailed -and $Commit) {
        $status = 'Update Error'
        $script:countFailed++
    }
    elseif ($Commit) {
        if ($newJql -eq $originalJql) {
            $status = 'Found'   # nothing to change (should not occur past detection)
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
                        $status = 'Update Error'
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
                    $status = 'Fixed'
                    $script:countFixed++
                }
            }
            catch {
                $status = 'Update Error'
                $detail = Get-JiraErrorDetail $_
                if ($detail -match 'does not exist') {
                    $errors.Add("PUT rejected by Jira JQL validation — pre-existing problem in OTHER clauses, not the childIssuesOf rewrite: $detail")
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
        }

        # AFTER state + built-in validation
        try {
            $after = Get-FilterDetail -FilterId $filter.id
            Write-FilterState -Label "AFTER update" -Detail $after

            if ($status -eq 'Fixed' -and $after.jql -ne $newJql) {
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

    Write-Host "Proposed JQL : $newJql" -ForegroundColor Green
    Write-Host "Status       : $status" -ForegroundColor $(
        if ($status -eq 'Update Error') { 'Red' }
        elseif ($errors.Count -gt 0) { 'Magenta' }
        else { 'Yellow' })
    foreach ($e in $errors) { Write-Host "  ! $e" -ForegroundColor Magenta }

    $script:results.Add([PSCustomObject]([ordered]@{
        'Filter Name' = $before.name
        'Filter ID'   = $before.id
        'Owner Name'  = $before.owner.displayName
        'Owner Email' = "$($before.owner.emailAddress)"
        'Owner ID'    = $before.owner.accountId
        'Viewers'     = (Format-Permissions $before.sharePermissions)
        'Editors'     = (Format-Permissions $before.editPermissions)
        'JQL Before'  = $originalJql
        'JQL After'   = $newJql
        'Status'      = $status
        'Errors'      = ($errors -join "`n")
        'Comments'    = ''
    }))
}

# --- Startup info ---
Write-Host "Detection pattern : $($funcRegex.ToString())"  -ForegroundColor Cyan
Write-Host "Replacement       : $ReplacementFunction"      -ForegroundColor Cyan
if ($Filters) { Write-Host "Filter name scope : $($Filters -join ', ')" -ForegroundColor Cyan }
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
Write-Host "Found          : $($script:results.Count)"        -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Fixed          : $($script:countFixed)"  -ForegroundColor Green
    Write-Host "Update Errors  : $($script:countFailed)" -ForegroundColor $(if ($script:countFailed) { 'Red' } else { 'Cyan' })
}

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
