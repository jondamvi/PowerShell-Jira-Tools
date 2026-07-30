<#
.SYNOPSIS
    Post-migration repair: finds Jira Cloud filters whose JQL is EMPTY (a known
    defect of the vendor's DC->Cloud migration tooling — filter migrates with
    name + owner but blank JQL), locates the corresponding source filter in
    Jira Data Center by exact name, verifies the DC JQL is non-empty, and
    writes the DC JQL into the Cloud filter.

    DC LOOKUP — IMPORTANT LIMITATION:
    Jira Data Center has NO REST endpoint to search filters by name (only
    GET /rest/api/2/filter/{id} by numeric id, and /filter/favourite). Two
    lookup sources are therefore supported; at least one is required:

      A) -DcFilterIdRange "10000-30000"
         Brute-scans GET /rest/api/2/filter/{id} across the id range and builds
         a name -> filter index. Filter ids live in the DC 'searchrequest' table
         and are sequential; 404/400 ids are skipped silently. The scanning DC
         account only sees filters it owns or that are shared with it — run it
         as an account with broad visibility.

      B) -DcJqlCsv <path>
         CSV export from the DC database (Atlassian-documented approach):
           SELECT id, filtername, authorname, reqcontent FROM searchrequest;
         Expected columns (case-insensitive): FilterName, Jql
         Optional columns: FilterId, OwnerName
         Covers private filters the REST scan cannot see.

    If both are given, the CSV wins on name collisions (assumed fresher intent),
    with REST as fallback. Name matching is exact (ordinal, case-sensitive).
    Multiple DC filters with the same name: if -DcJqlCsv provides OwnerName, a
    row whose owner matches the Cloud owner displayName is preferred; otherwise
    ambiguity is reported in Errors and the filter is NOT updated.

    DC auth: -DcPersonalAccessToken (Bearer, DC 8.14+) OR -DcUsername/-DcPassword.

    Update mechanics (same guarantees as sibling scripts):
      - PUT body contains ONLY name + jql -> permissions/subscriptions untouched.
      - Before/after state fetched, logged, compared; drift lands in Errors.
      - DRY RUN by default; -Commit + YES confirmation; -Force for unattended.

    Status column semantics:
      - Found        : dry-run mode, empty-JQL filter detected
      - Fixed        : -Commit mode, DC JQL written and verified
      - Update Error : -Commit mode, no usable DC JQL / PUT failed / validation failed

    CSV columns: Filter Name, Filter ID, Owner Name, Owner ID, Owner Email,
                 Viewers, Editors, DC JQL, Cloud JQL, Status, Errors, Comments
#>

[CmdletBinding()]
param (
    # ---- Jira Cloud ----
    [Parameter(Mandatory)]
    [string]$JiraBaseUrl,

    [Parameter(Mandatory)]
    [string]$Email,

    [Parameter(Mandatory)]
    [string]$ApiToken,

    # ---- Jira Data Center ----
    [Parameter(Mandatory)]
    [string]$DcBaseUrl,

    [string]$DcPersonalAccessToken,
    [string]$DcUsername,
    [string]$DcPassword,

    # DC filter id range to scan for the name->JQL index, e.g. "10000-30000".
    [string]$DcFilterIdRange,

    # CSV with DC filter data (FilterName, Jql [, FilterId, OwnerName]).
    [string]$DcJqlCsv,

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
$DcBaseUrl   = $DcBaseUrl.TrimEnd('/')

if (-not $DcFilterIdRange -and -not $DcJqlCsv) {
    throw "Provide at least one DC lookup source: -DcFilterIdRange (REST id scan) and/or -DcJqlCsv (DB export). DC REST cannot search filters by name."
}
if (-not $DcPersonalAccessToken -and -not ($DcUsername -and $DcPassword) -and $DcFilterIdRange) {
    throw "DC REST scan requested (-DcFilterIdRange) but no DC credentials: pass -DcPersonalAccessToken or -DcUsername/-DcPassword."
}

# --- Cloud auth header ---
$pair    = "{0}:{1}" -f $Email, $ApiToken
$basic   = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic"; Accept = 'application/json' }

# --- DC auth header ---
$script:dcHeaders = $null
if ($DcPersonalAccessToken) {
    $script:dcHeaders = @{ Authorization = "Bearer $DcPersonalAccessToken"; Accept = 'application/json' }
}
elseif ($DcUsername -and $DcPassword) {
    $dcPair  = "{0}:{1}" -f $DcUsername, $DcPassword
    $dcBasic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($dcPair))
    $script:dcHeaders = @{ Authorization = "Basic $dcBasic"; Accept = 'application/json' }
}

# --- Preflight: Cloud ---
try {
    $me = Invoke-RestMethod -Uri "$JiraBaseUrl/rest/api/3/myself" -Headers $headers -Method Get
}
catch {
    throw "Cloud auth preflight against /rest/api/3/myself failed: $($_.Exception.Message) — check -JiraBaseUrl, -Email, -ApiToken (tokens expire, max 1 year)."
}
if (-not $me.accountId) {
    throw "Jira Cloud served the request as ANONYMOUS (no accountId from /myself). The -Email/-ApiToken pair is not authenticating."
}
Write-Host "Cloud authenticated as : $($me.displayName) [$($me.accountId)]" -ForegroundColor Cyan

# --- Preflight: DC (only if REST scan will be used) ---
if ($script:dcHeaders) {
    try {
        $dcMe = Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/myself" -Headers $script:dcHeaders -Method Get
        Write-Host "DC authenticated as    : $($dcMe.displayName) [$($dcMe.name)]" -ForegroundColor Cyan
    }
    catch {
        throw "DC auth preflight against /rest/api/2/myself failed: $($_.Exception.Message) — check -DcBaseUrl and DC credentials."
    }
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

# --- Paginated fetch (Cloud) ---
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

# =====================================================================
# DC name -> filter index. Built LAZILY, only if empty-JQL filters exist.
# Value per name: list of @{ Id; OwnerName; Jql; Source }
# =====================================================================
$script:dcIndex      = $null

function Initialize-DcIndex {
    if ($null -ne $script:dcIndex) { return }
    $script:dcIndex = @{}

    # Source B first (REST overwritten below has lower priority -> add REST first,
    # then CSV rows are PREPENDED so they win selection order).
    if ($DcFilterIdRange) {
        if ($DcFilterIdRange -notmatch '^\s*(\d+)\s*-\s*(\d+)\s*$') {
            throw "-DcFilterIdRange must look like '10000-30000' (got '$DcFilterIdRange')."
        }
        $idStart = [int]$Matches[1]; $idEnd = [int]$Matches[2]
        if ($idEnd -lt $idStart) { throw "-DcFilterIdRange end < start." }
        Write-Host "Building DC index via REST id scan $idStart..$idEnd (misses are skipped)..." -ForegroundColor Cyan
        $found = 0
        for ($id = $idStart; $id -le $idEnd; $id++) {
            try {
                $f = Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/filter/$id" -Headers $script:dcHeaders -Method Get
                if ($f -and $f.name) {
                    $entry = @{
                        Id        = $f.id
                        OwnerName = "$($f.owner.displayName)"
                        Jql       = "$($f.jql)"
                        Source    = 'REST'
                    }
                    if (-not $script:dcIndex.ContainsKey($f.name)) {
                        $script:dcIndex[$f.name] = New-Object System.Collections.Generic.List[object]
                    }
                    $script:dcIndex[$f.name].Add($entry)
                    $found++
                }
            }
            catch { } # 400/404 = id unused or not visible; skip.
            if (($id - $idStart) % 500 -eq 499) {
                Write-Host ("  ...scanned {0} ids, {1} filters indexed" -f ($id - $idStart + 1), $found) -ForegroundColor DarkGray
            }
        }
        Write-Host "DC REST scan done: $found filter(s) indexed." -ForegroundColor Cyan
    }

    if ($DcJqlCsv) {
        if (-not (Test-Path $DcJqlCsv)) { throw "-DcJqlCsv file not found: $DcJqlCsv" }
        $rows = Import-Csv -Path $DcJqlCsv
        # Resolve column names case-insensitively.
        $props   = @($rows | Select-Object -First 1).PSObject.Properties.Name
        $colName = $props | Where-Object { $_ -match '^(FilterName|filtername|name)$' }         | Select-Object -First 1
        $colJql  = $props | Where-Object { $_ -match '^(Jql|JQL|reqcontent)$' }                  | Select-Object -First 1
        $colId   = $props | Where-Object { $_ -match '^(FilterId|filter_id|id)$' }               | Select-Object -First 1
        $colOwn  = $props | Where-Object { $_ -match '^(OwnerName|owner|authorname)$' }          | Select-Object -First 1
        if (-not $colName -or -not $colJql) {
            throw "-DcJqlCsv must contain FilterName and Jql columns (found: $($props -join ', '))."
        }
        $added = 0
        foreach ($r in $rows) {
            $n = "$($r.$colName)"
            if ([string]::IsNullOrWhiteSpace($n)) { continue }
            $entry = @{
                Id        = if ($colId)  { "$($r.$colId)" }  else { '' }
                OwnerName = if ($colOwn) { "$($r.$colOwn)" } else { '' }
                Jql       = "$($r.$colJql)"
                Source    = 'CSV'
            }
            if (-not $script:dcIndex.ContainsKey($n)) {
                $script:dcIndex[$n] = New-Object System.Collections.Generic.List[object]
            }
            $script:dcIndex[$n].Insert(0, $entry)   # CSV rows take precedence
            $added++
        }
        Write-Host "DC CSV loaded: $added row(s) indexed from $DcJqlCsv." -ForegroundColor Cyan
    }
}

# Resolve DC JQL for a cloud filter. Returns @{ Jql; Detail; Error }.
function Resolve-DcJql {
    param([string]$FilterName, [string]$CloudOwnerDisplayName)
    Initialize-DcIndex
    if (-not $script:dcIndex.ContainsKey($FilterName)) {
        return @{ Jql = $null; Detail = ''; Error = "No DC filter named '$FilterName' found in DC index." }
    }
    $candidates = @($script:dcIndex[$FilterName])
    $usable     = @($candidates | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Jql) })
    if ($usable.Count -eq 0) {
        return @{ Jql = $null; Detail = ''; Error = "DC filter '$FilterName' found but its JQL is ALSO empty — cannot repair from DC." }
    }
    if ($usable.Count -gt 1) {
        # Disambiguate by owner display name if available.
        $byOwner = @($usable | Where-Object { $_.OwnerName -and $_.OwnerName -eq $CloudOwnerDisplayName })
        if ($byOwner.Count -eq 1) {
            $c = $byOwner[0]
            return @{ Jql = $c.Jql; Detail = "DC id $($c.Id) [$($c.Source)], owner-matched"; Error = $null }
        }
        $ids = ($usable | ForEach-Object { "$($_.Id)($($_.Source))" }) -join ', '
        return @{ Jql = $null; Detail = ''; Error = "AMBIGUOUS: multiple DC filters named '$FilterName' with non-empty JQL [ids: $ids] and owner match failed — resolve manually." }
    }
    $c = $usable[0]
    return @{ Jql = $c.Jql; Detail = "DC id $($c.Id) [$($c.Source)]"; Error = $null }
}

# --- Per-filter processing ---
$script:totalScanned = 0
$script:countUpdated = 0
$script:countFailed  = 0
$script:results      = New-Object System.Collections.Generic.List[object]

function Invoke-FilterProcessing {
    param($filter)
    $script:totalScanned++

    # Detection: this script targets filters whose JQL IS empty.
    if (-not [string]::IsNullOrWhiteSpace($filter.jql)) {
        if ($Filters) { Write-Host "[$($filter.name)] JQL not empty — nothing to do" -ForegroundColor DarkGray }
        return
    }

    $errors = New-Object System.Collections.Generic.List[string]

    # Full BEFORE state (fresh GET: permissions, subscriptions, current JQL)
    $before = $null
    try   { $before = Get-FilterDetail -FilterId $filter.id }
    catch { $errors.Add("GET filter detail failed: $(Get-JiraErrorDetail $_)") }
    if (-not $before) { $before = $filter }

    # Re-check on the fresh copy — search page could be stale.
    if (-not [string]::IsNullOrWhiteSpace($before.jql)) {
        Write-Host "[$($before.name)] JQL non-empty on fresh GET — skipping" -ForegroundColor DarkGray
        return
    }

    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host "WARNING: filter migrated with EMPTY JQL (vendor migration bug)" -ForegroundColor Magenta
    Write-Host ("  Name  : {0}" -f $before.name) -ForegroundColor Magenta
    Write-Host ("  ID    : {0}" -f $before.id) -ForegroundColor Magenta
    Write-Host ("  Owner : {0} <{1}> [{2}]" -f $before.owner.displayName, $before.owner.emailAddress, $before.owner.accountId) -ForegroundColor Magenta
    Write-FilterState -Label "BEFORE update" -Detail $before

    $cloudJql = "$($before.jql)"

    # DC lookup
    $dc = Resolve-DcJql -FilterName $before.name -CloudOwnerDisplayName $before.owner.displayName
    $dcJql = $dc.Jql
    if ($dc.Error) { $errors.Add($dc.Error) }
    elseif ($dc.Detail) { Write-Host "DC source    : $($dc.Detail)" -ForegroundColor Cyan }
    if ($dcJql) { Write-Host "DC JQL       : $dcJql" -ForegroundColor Green }

    $status = 'Found'
    if ($Commit) {
        if (-not $dcJql) {
            $status = 'Update Error'
            $script:countFailed++
        }
        else {
            try {
                $putUri = "$JiraBaseUrl/rest/api/3/filter/$($filter.id)"
                if ($OverrideSharePermissions) { $putUri += "?overrideSharePermissions=true" }
                # Minimal body: ONLY name + jql. Permissions/subscriptions are never sent.
                $body = @{ name = $before.name; jql = $dcJql } | ConvertTo-Json
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

            # AFTER state + built-in validation
            try {
                $after = Get-FilterDetail -FilterId $filter.id
                Write-FilterState -Label "AFTER update" -Detail $after

                if ($status -eq 'Fixed' -and $after.jql -ne $dcJql) {
                    $status = 'Update Error'
                    $errors.Add("VALIDATION: JQL after update differs from intended DC value")
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
    }

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
        'DC JQL'      = "$dcJql"
        'Cloud JQL'   = $cloudJql
        'Status'      = $status
        'Errors'      = ($errors -join "`n")
        'Comments'    = ''
    }))
}

# --- Startup info ---
Write-Host "Detection         : filters with EMPTY JQL in Cloud" -ForegroundColor Cyan
$dcSources = @()
if ($DcFilterIdRange) { $dcSources += "REST id scan ($DcFilterIdRange)" }
if ($DcJqlCsv)        { $dcSources += "CSV ($DcJqlCsv)" }
Write-Host "DC lookup sources : $($dcSources -join ' + ')" -ForegroundColor Cyan
if ($Filters) { Write-Host "Filter name scope : $($Filters -join ', ')" -ForegroundColor Cyan }
if ($Commit) {
    Write-Host "MODE: COMMIT — empty-JQL filters WILL be updated from DC JQL." -ForegroundColor Red
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
Write-Host "Empty JQL found: $($script:results.Count)"        -ForegroundColor Cyan
if ($Commit) {
    Write-Host "Fixed          : $($script:countUpdated)" -ForegroundColor Green
    Write-Host "Update errors  : $($script:countFailed)"  -ForegroundColor $(if ($script:countFailed) { 'Red' } else { 'Cyan' })
}

if ($ExportCsv -and $script:results.Count -gt 0) {
    $script:results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
