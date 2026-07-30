<#
.SYNOPSIS
    Finds Jira Cloud filters whose JQL uses the Data Center Advanced Roadmaps
    function childIssuesOf(...) — unsupported in Cloud — and rewrites it to the
    Cloud equivalent portfolioChildIssuesOf(...), preserving arguments as-is.

        issuekey in childIssuesOf("INIT-001")
            -> issuekey in portfolioChildIssuesOf("INIT-001")

    Detection is case-insensitive and word-bounded, so existing
    portfolioChildIssuesOf(...) occurrences are never touched or double-prefixed.

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

    Status column semantics:
      - Found        : dry-run mode, filter matched (would be updated)
      - Fixed        : -Commit mode, PUT succeeded and post-update JQL verified
      - Update Error : -Commit mode, PUT or post-update validation failed

    CSV columns: Filter Name, Filter ID, Owner Name, Owner ID, Owner Email,
                 Viewers, Editors, JQL Before, JQL After, Status, Errors, Comments
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string]$JiraBaseUrl,

    [Parameter(Mandatory)]
    [string]$Email,

    [Parameter(Mandatory)]
    [string]$ApiToken,

    # Cloud function name written into JQL. Documented casing per Atlassian docs.
    [string]$ReplacementFunctionName = 'portfolioChildIssuesOf',

    # No wildcards = exact name match (server-side query). Wildcards = full scan + -like.
    [string[]]$Filters,

    [string]$ExportCsv,

    # Requires Administer Jira global permission (experimental API param).
    [switch]$OverrideSharePermissions,

    # Without this switch the script is a pure dry run — zero write calls.
    [switch]$Commit,

    # Skip the interactive COMMIT confirmation prompt (for unattended runs).
    [switch]$Force,

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

# --- Detection regex ---
# \b before 'childIssuesOf' guarantees no match inside 'portfolioChildIssuesOf'
# (no word boundary between 'portfolio' and 'childIssuesOf'). Lookahead requires
# an opening parenthesis so plain text mentions are not rewritten.
$funcRegex = [regex]'(?i)\bchildIssuesOf\b(?=\s*\()'

function Convert-Jql {
    param([string]$Jql)
    return $funcRegex.Replace($Jql, $ReplacementFunctionName)
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
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    if ([string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] empty JQL — nothing to do" -ForegroundColor DarkGray }
        return
    }
    if (-not $funcRegex.IsMatch($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] no childIssuesOf() usage found" -ForegroundColor DarkGray }
        return
    }

    $errors = New-Object System.Collections.Generic.List[string]

    # Full BEFORE state (fresh GET: permissions, subscriptions, current JQL)
    $before = $null
    try   { $before = Get-FilterDetail -FilterId $filter.id }
    catch { $errors.Add("GET filter detail failed: $(Get-JiraErrorDetail $_)") }
    if (-not $before) { $before = $filter }

    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host "WARNING: filter uses Cloud-unsupported function childIssuesOf()" -ForegroundColor Magenta
    Write-FilterState -Label "BEFORE update" -Detail $before

    $originalJql = $before.jql
    $newJql      = Convert-Jql -Jql $originalJql
    $changed     = ($newJql -ne $originalJql)

    $status = 'Found'
    if ($Commit) {
        if (-not $changed) {
            # Should not occur (regex matched above), kept as a safety net.
            $status = 'Found'
            $errors.Add("WARN: matched but rewrite produced no change — review manually")
        }
        else {
            try {
                $putUri = "$JiraBaseUrl/rest/api/3/filter/$($filter.id)"
                if ($OverrideSharePermissions) { $putUri += "?overrideSharePermissions=true" }
                # Minimal body: ONLY name + jql. Permissions/subscriptions are never sent.
                $body = @{ name = $before.name; jql = $newJql } | ConvertTo-Json
                Invoke-RestMethod -Uri $putUri -Headers $script:headers -Method Put `
                    -ContentType 'application/json' -Body $body | Out-Null
                $status = 'Fixed'
                $script:countUpdated++
            }
            catch {
                $status = 'Update Error'
                $errors.Add("PUT failed: $(Get-JiraErrorDetail $_)")
                $script:countFailed++
            }
        }

        # AFTER state + built-in validation
        try {
            $after = Get-FilterDetail -FilterId $filter.id
            Write-FilterState -Label "AFTER update" -Detail $after

            if ($status -eq 'Fixed' -and $after.jql -ne $newJql) {
                $status = 'Update Error'
                $errors.Add("VALIDATION: JQL after update differs from intended value")
            }
            $checks = @(
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
        elseif ($status -eq 'Fixed')    { 'Green' }
        elseif ($errors.Count -gt 0)    { 'Magenta' }
        else { 'Yellow' })
    foreach ($e in $errors) { Write-Host "  ! $e" -ForegroundColor Magenta }

    $script:results.Add([PSCustomObject]([ordered]@{
        'Filter Name' = $before.name
        'Filter ID'   = $before.id
        'Owner Name'  = $before.owner.displayName
        'Owner ID'    = $before.owner.accountId
        'Owner Email' = "$($before.owner.emailAddress)"
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
Write-Host "Replacement       : $ReplacementFunctionName"  -ForegroundColor Cyan
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
Write-Host "Flagged        : $($script:results.Count)"        -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Fixed          : $($script:countUpdated)" -ForegroundColor Green
    Write-Host "Update errors  : $($script:countFailed)"  -ForegroundColor $(if ($script:countFailed) { 'Red' } else { 'Cyan' })
}

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
