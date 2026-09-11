<#
.SYNOPSIS
    Audits the fate of custom fields referenced as cf[NNNNN] in migrated filter
    JQLs by traversing the DC advanced audit log history. Read-only.

    Search-by-id/affectedObject is unreliable (fields may not be indexed), so the
    history is walked page by page filtered by event type only:
        /rest/auditing/1.0/events?search=&actions=Custom+field+created,Custom+field+updated,Custom+field+deleted&limit=100
    following pagingInfo.nextPageLink, and every event whose affectedObjects
    contain one of the target field ids is captured:
      - created : when, by whom, initial name
      - updated : Name changes (renames) with from -> to, when, by whom
      - deleted : when, by whom, source address
    Traversal stops as soon as every unresolved target has a deletion event,
    or when the history is exhausted (lastPage), or at -MaxPages.
    Fields still ACTIVE in DC (present in /rest/api/2/field) never get a
    deletion event, so they are treated as resolved up front.

    With Cloud credentials supplied, each field's last known DC name is used to
    locate the Cloud successor field and print a ready -FieldRenameMap entry.

.EXAMPLE
    .\Audit-DcCustomFields.ps1 -DcBaseUrl "https://jirasite.org" -PersonalAccessToken $pat `
        -FieldIds 38488,54282,64284 `
        -CloudBaseUrl "https://company.atlassian.net" -CloudEmail "you@company.org" -CloudApiToken $token `
        -ExportCsv fieldaudit.csv
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string]$DcBaseUrl,

    # DC Personal Access Token (sent as Bearer)
    [Parameter(Mandatory)]
    [string]$PersonalAccessToken,

    [Parameter(Mandatory)]
    [string[]]$FieldIds,               # numeric ids or customfield_NNNNN

    # Audit event types to traverse (comma-separated, exactly as Jira names them)
    [string]$Actions = 'Custom field created,Custom field updated,Custom field deleted',

    [int]$PageSize = 100,

    # Safety cap on pages walked (0 = unlimited)
    [int]$MaxPages = 2000,

    # Optional Cloud side — enables successor resolution by name
    [string]$CloudBaseUrl,
    [string]$CloudEmail,
    [string]$CloudApiToken,

    [string]$ExportCsv
)

$ErrorActionPreference = 'Stop'

foreach ($u in 'DcBaseUrl','CloudBaseUrl') {
    $v = Get-Variable $u -ValueOnly
    if ($v) {
        if     ($v -match '^(?i)http://')     { $v = 'https://' + $v.Substring(7) }
        elseif ($v -notmatch '^(?i)https://') { $v = "https://$v" }
        Set-Variable $u -Value $v.TrimEnd('/')
    }
}

$dcH = @{ Authorization = "Bearer $PersonalAccessToken"; Accept = 'application/json' }

# --- DC preflight ---
try   { $dcMe = Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/myself" -Headers $dcH }
catch { throw "DC auth preflight failed: $($_.Exception.Message) — check -DcBaseUrl (context path!) and the PAT." }
Write-Host "DC authenticated   : $($dcMe.displayName)" -ForegroundColor Cyan

# --- Cloud preflight (optional) ---
$cloudH = $null
if ($CloudBaseUrl -and $CloudEmail -and $CloudApiToken) {
    $basic  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${CloudEmail}:${CloudApiToken}"))
    $cloudH = @{ Authorization = "Basic $basic"; Accept = 'application/json' }
    try   { $cMe = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/myself" -Headers $cloudH }
    catch { throw "Cloud auth preflight failed: $($_.Exception.Message)" }
    Write-Host "Cloud authenticated: $($cMe.displayName)" -ForegroundColor Cyan
}
else {
    Write-Host "Cloud credentials not supplied — DC-only audit (no successor resolution)." -ForegroundColor DarkYellow
}

# --- Normalize targets and check DC presence ---
$targets = @{}
foreach ($raw in $FieldIds) {
    $n = "$raw".Trim() -replace '^(?i)customfield_',''
    if (-not $n) { continue }
    $targets["customfield_$n"] = [ordered]@{
        Id        = "customfield_$n"
        Num       = $n
        DcActive  = $false
        Name      = ''
        Type      = ''
        Created   = $null
        Renames   = New-Object System.Collections.Generic.List[string]
        Deleted   = $null
        Events    = 0
    }
}
if ($targets.Count -eq 0) { throw "No usable field ids supplied." }

$dcFields = @(Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/field" -Headers $dcH)
Write-Host "DC fields loaded   : $($dcFields.Count)" -ForegroundColor Cyan
foreach ($t in $targets.Values) {
    $f = $dcFields | Where-Object { $_.id -eq $t.Id } | Select-Object -First 1
    if ($f) {
        $t.DcActive = $true
        $t.Name     = $f.name
        $t.Type     = if ($f.schema) { $f.schema.custom } else { '' }
        Write-Host "$($t.Id) is ACTIVE in DC — '$($t.Name)' — no deletion expected; collecting history only" -ForegroundColor Green
    }
}

# Ids still needing a deletion event to be considered resolved
$pendingDeletion = @($targets.Values | Where-Object { -not $_.DcActive } | ForEach-Object { $_.Id })
Write-Host "Awaiting deletion events for: $(if ($pendingDeletion) { $pendingDeletion -join ', ' } else { '(none — all active)' })" -ForegroundColor Cyan

# --- Traverse audit history ---
$actionsParam = ($Actions -replace ' ', '+')
$uri   = "$DcBaseUrl/rest/auditing/1.0/events?search=&actions=$actionsParam&limit=$PageSize"
$page  = 0
$scanned = 0
$oldest  = $null

while ($uri) {
    $page++
    if ($MaxPages -gt 0 -and $page -gt $MaxPages) {
        Write-Host "Reached -MaxPages ($MaxPages); stopping traversal." -ForegroundColor DarkYellow
        break
    }
    try   { $r = Invoke-RestMethod -Uri $uri -Headers $dcH }
    catch { throw "Audit query failed on page ${page}: $($_.Exception.Message)`n$uri" }

    foreach ($e in @($r.entities)) {
        $scanned++
        $oldest = $e.timestamp
        $hits = @($e.affectedObjects | Where-Object { $_.type -eq 'CUSTOM_FIELD' -and $targets.ContainsKey("$($_.id)") })
        foreach ($obj in $hits) {
            $t      = $targets["$($obj.id)"]
            $t.Events++
            $action = "$($e.type.action)"
            $who    = if ($e.author -and $e.author.name) { $e.author.name } else { 'unknown' }
            $when   = $e.timestamp
            if ($obj.name -and -not $t.Name) { $t.Name = $obj.name }   # latest known name (history is newest-first)

            switch -Regex ($action) {
                'created' {
                    $t.Created = "$when by $who"
                    $nameAtCreate = ($e.changedValues | Where-Object { $_.key -eq 'Name' } | Select-Object -First 1).to
                    Write-Host "[$($t.Id)] CREATED  $when by $who — '$nameAtCreate'" -ForegroundColor DarkGray
                }
                'updated' {
                    $nameChange = $e.changedValues | Where-Object { $_.key -eq 'Name' -and $_.from -ne $_.to } | Select-Object -First 1
                    if ($nameChange) {
                        $t.Renames.Add("$when '$($nameChange.from)' -> '$($nameChange.to)' by $who")
                        Write-Host "[$($t.Id)] RENAMED  $when '$($nameChange.from)' -> '$($nameChange.to)' by $who" -ForegroundColor DarkYellow
                    }
                }
                'deleted' {
                    $src = if ($e.source) { " from $($e.source)" } else { '' }
                    $t.Deleted = "$when by $who$src"
                    Write-Host "[$($t.Id)] DELETED  $when by $who$src — '$($obj.name)'" -ForegroundColor Red
                    $pendingDeletion = @($pendingDeletion | Where-Object { $_ -ne $t.Id })
                }
            }
        }
    }

    if ($page % 25 -eq 0) {
        Write-Host ("...page {0}, {1} events scanned, reached {2}" -f $page, $scanned, $oldest) -ForegroundColor DarkGray
    }

    if ($pendingDeletion.Count -eq 0) {
        Write-Host "All target deletion events found after $page page(s) / $scanned events — stopping." -ForegroundColor Green
        break
    }
    if ($r.pagingInfo.lastPage -or -not $r.pagingInfo.nextPageLink) {
        Write-Host "History exhausted after $page page(s) / $scanned events (oldest: $oldest)." -ForegroundColor DarkYellow
        break
    }
    $uri = $r.pagingInfo.nextPageLink
}

# --- Cloud successor resolution + verdicts ---
$results = New-Object System.Collections.Generic.List[object]
Write-Host ("=" * 70) -ForegroundColor Yellow
foreach ($t in $targets.Values) {
    $cloudStatus = ''; $successor = ''; $mapping = ''
    if ($cloudH) {
        $act = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field/search?id=$($t.Id)" -Headers $cloudH
        if ($act.total -gt 0) { $cloudStatus = "ACTIVE: '$($act.values[0].name)'" }
        else {
            $cloudStatus = 'absent'
            if ($t.Name) {
                $s = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field/search?query=$([uri]::EscapeDataString($t.Name))" -Headers $cloudH
                if ($s.total -gt 0) {
                    $successor = $s.values[0].name
                    $mapping   = "'cf[$($t.Num)]' = '$successor'"
                    if ($s.total -gt 1) { $successor += " (+$($s.total - 1) more name matches — verify)" }
                }
            }
        }
    }

    $verdict =
        if     ($cloudStatus -like 'ACTIVE*') { 'ActiveInCloud — reference resolves; error is permission/context' }
        elseif ($t.Deleted -and $mapping)     { "DeletedInDc ($($t.Deleted)) — but a same-named Cloud field exists; mapping possible: $mapping" }
        elseif ($t.Deleted)                   { "DeletedInDc ($($t.Deleted)) — referencing filters were broken pre-migration (Obsolete)" }
        elseif ($t.DcActive -and $mapping)    { "OrphanedId — alive in DC as '$($t.Name)', ID not preserved by migration; map: $mapping" }
        elseif ($t.DcActive)                  { "OrphanedId — alive in DC as '$($t.Name)'; resolve Cloud successor by name" }
        elseif ($t.Events -gt 0)              { "HistoryOnly — events found but no deletion within traversed range (last name '$($t.Name)')" }
        else                                  { 'LongDead — absent from DC, no audit events in traversed history; treat filters as Obsolete' }

    Write-Host "$($t.Id)  '$($t.Name)'" -ForegroundColor Yellow
    Write-Host "  DC active : $($t.DcActive)"
    Write-Host "  created   : $(if ($t.Created) { $t.Created } else { 'not in traversed history' })"
    Write-Host "  renames   : $(if ($t.Renames.Count) { $t.Renames.Count } else { 'none' })"
    foreach ($rn in $t.Renames) { Write-Host "              $rn" }
    Write-Host "  deleted   : $(if ($t.Deleted) { $t.Deleted } else { 'no deletion event' })" -ForegroundColor $(if ($t.Deleted) { 'Red' } else { 'Gray' })
    if ($cloudH) { Write-Host "  cloud     : $cloudStatus$(if ($successor) { "; successor by name: $successor" })" }
    Write-Host "  VERDICT   : $verdict" -ForegroundColor Magenta

    $results.Add([PSCustomObject]([ordered]@{
        'Field ID'          = $t.Id
        'Last Known Name'   = $t.Name
        'DC Type'           = $t.Type
        'DC Active'         = $t.DcActive
        'Created'           = "$($t.Created)"
        'Renames'           = ($t.Renames -join "`n")
        'Deleted'           = "$($t.Deleted)"
        'Audit Events'      = $t.Events
        'Cloud Status'      = $cloudStatus
        'Cloud Successor'   = $successor
        'Suggested Mapping' = $mapping
        'Verdict'           = $verdict
    }))
}

Write-Host ("-" * 50)
Write-Host "Pages walked : $page   Events scanned : $scanned" -ForegroundColor Cyan
if ($ExportCsv -and $results.Count -gt 0) {
    $results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
