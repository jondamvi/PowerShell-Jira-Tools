<#
.SYNOPSIS
    Audits, from the DC advanced audit log, what happened to objects that
    migrated filter JQLs still reference. Read-only.

    Targets:
      -FieldIds     cf[NNNNN] references           (matched by affectedObjects.id)
      -FieldNames   field names as written in JQL  (matched by affectedObjects.name
                    and by rename events' Name from/to)
      -FilterNames  filters referenced as filter = "Name"

    Method: search-by-id/name is unreliable (not indexed), so the history is
    walked newest-first filtered by action types only, following
    pagingInfo.nextPageLink, and every event is matched client-side.
    Because history is newest-first, a field's DELETION (under its new name)
    is seen BEFORE the RENAME that links the old name to it — so every custom
    field / filter event is buffered by object id during traversal, and when a
    name target gets bound to an id, its earlier-seen events are back-filled.

    Resolution (stops traversal when all targets resolved):
      field id/name : deleted, archived, or renamed to a field still active in DC
      filter name   : deleted, or a rename found

    -DiscoverActions N : prints the distinct action names seen in the first N
    unfiltered pages and exits — use it to learn this instance's exact
    (German/English) action vocabulary when a traversal finds nothing.

    With Cloud credentials, last known names are resolved to Cloud successors
    (fields via /rest/api/3/field, filters via quoted filterName search) and a
    ready -FieldRenameMap entry is printed for fields.

.EXAMPLE
    .\Audit-DcCustomFields.ps1 -DcBaseUrl "https://jirasite.org" -PersonalAccessToken $pat `
        -FieldIds 48483,38488 -FieldNames 'OS_ServiceGruppe','IT ServiceGruppe' -FilterNames 'IT BUS Tickets' `
        -CloudBaseUrl "https://company.atlassian.net" -CloudEmail "you@company.org" -CloudApiToken $token `
        -ExportCsv audit.csv
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)] [string]$DcBaseUrl,
    [Parameter(Mandatory)] [string]$PersonalAccessToken,

    [string[]]$FieldIds,
    [string[]]$FieldNames,
    [string[]]$FilterNames,

    # Field event types, mixed English/German (action names changed with upgrades/locale)
    [string]$Actions = 'Custom field created,Custom field updated,Custom field deleted,Custom field renamed,Custom field archived,' +
                       'Benutzerdefiniertes Feld erstellt,Benutzerdefiniertes Feld geändert,Benutzerdefiniertes Feld aktualisiert,' +
                       'Benutzerdefiniertes Feld gelöscht,Benutzerdefiniertes Feld umbenannt,Benutzerdefiniertes Feld archiviert',

    # Filter event types
    [string]$FilterActions = 'Filter created,Filter updated,Filter deleted,Filter renamed,' +
                             'Filter erstellt,Filter geändert,Filter aktualisiert,Filter gelöscht,Filter umbenannt',

    [int]$PageSize = 100,
    [int]$MaxPages = 2000,          # safety cap per traversal (0 = unlimited)
    [int]$DiscoverActions = 0,      # >0: list distinct actions in N unfiltered pages, then exit

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
try   { $dcMe = Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/myself" -Headers $dcH }
catch { throw "DC auth preflight failed: $($_.Exception.Message) — check -DcBaseUrl (context path!) and the PAT." }
Write-Host "DC authenticated   : $($dcMe.displayName)" -ForegroundColor Cyan

function ConvertTo-ActionsParam { param([string]$List)
    ((($List -split ',') | ForEach-Object { ([uri]::EscapeDataString($_.Trim())) -replace '%20', '+' }) -join ',')
}

# --- Discovery mode ---
if ($DiscoverActions -gt 0) {
    $seen = @{}
    $uri  = "$DcBaseUrl/rest/auditing/1.0/events?search=&limit=$PageSize"
    for ($p = 1; $p -le $DiscoverActions -and $uri; $p++) {
        $r = Invoke-RestMethod -Uri $uri -Headers $dcH
        foreach ($e in @($r.entities)) {
            $k = "$($e.type.category) :: $($e.type.action)"
            if ($seen.ContainsKey($k)) { $seen[$k]++ } else { $seen[$k] = 1 }
        }
        $uri = if ($r.pagingInfo.lastPage) { $null } else { $r.pagingInfo.nextPageLink }
    }
    Write-Host "Distinct category :: action names in $DiscoverActions page(s):" -ForegroundColor Cyan
    $seen.GetEnumerator() | Sort-Object Name | ForEach-Object { "{0,6}  {1}" -f $_.Value, $_.Name }
    return
}

if (-not $FieldIds -and -not $FieldNames -and -not $FilterNames) { throw "Supply -FieldIds, -FieldNames and/or -FilterNames (or -DiscoverActions N)." }

# --- Cloud (optional) ---
$cloudH = $null
if ($CloudBaseUrl -and $CloudEmail -and $CloudApiToken) {
    $basic  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${CloudEmail}:${CloudApiToken}"))
    $cloudH = @{ Authorization = "Basic $basic"; Accept = 'application/json' }
    $cMe    = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/myself" -Headers $cloudH
    Write-Host "Cloud authenticated: $($cMe.displayName)" -ForegroundColor Cyan
    $cloudFields = @(Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field" -Headers $cloudH)
}
else { Write-Host "Cloud credentials not supplied — DC-only audit." -ForegroundColor DarkYellow }

# --- DC field list ---
$dcFields = @(Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/field" -Headers $dcH)
Write-Host "DC fields loaded   : $($dcFields.Count)" -ForegroundColor Cyan

# ================= FIELD TARGETS =================
function New-Target { param([string]$Kind, [string]$Key)
    [ordered]@{ Kind=$Kind; Key=$Key; Id=''; Name=''; Type=''; DcActive=$false
                Created=$null; Renames=(New-Object System.Collections.Generic.List[string])
                RenamedTo=''; Deleted=$null; Archived=$null; Events=0; Resolved=$false }
}
$fieldTargets = New-Object System.Collections.Generic.List[object]
foreach ($raw in @($FieldIds)) {
    $n = "$raw".Trim() -replace '^(?i)cf\[(\d+)\]$','$1' -replace '^(?i)customfield_',''
    if ($n) { $t = New-Target 'id' "customfield_$n"; $t.Id = "customfield_$n"; $fieldTargets.Add($t) }
}
foreach ($raw in @($FieldNames)) { $n = "$raw".Trim(); if ($n) { $fieldTargets.Add((New-Target 'name' $n)) } }

foreach ($t in $fieldTargets) {
    $f = if ($t.Kind -eq 'id') { $dcFields | Where-Object { $_.id -eq $t.Id } | Select-Object -First 1 }
         else                  { $dcFields | Where-Object { $_.name -eq $t.Key } | Select-Object -First 1 }
    if ($f) {
        $t.DcActive = $true; $t.Id = $f.id; $t.Name = $f.name
        $t.Type = if ($f.schema) { $f.schema.custom } else { '' }
        $t.Resolved = $true   # active in DC: no deletion to wait for
        Write-Host "[$($t.Key)] ACTIVE in DC as '$($f.name)' ($($f.id)) — history collected, not awaited" -ForegroundColor Green
    }
}

function Resolve-FieldTarget { param($T)
    # Renamed to a field that is still active in DC => resolved (mapping known)
    if ($T.Id) {
        $f = $dcFields | Where-Object { $_.id -eq $T.Id } | Select-Object -First 1
        if ($f) { $T.DcActive = $true; $T.Name = $f.name; $T.Type = if ($f.schema) { $f.schema.custom } else { '' }; $T.Resolved = $true }
    }
    if ($T.Deleted -or $T.Archived) { $T.Resolved = $true }
}

# Compact event buffer keyed by object id: newest-first history means a rename that
# binds an old name to an id shows up AFTER (i.e. is older than) that id's deletion.
$buffer = @{}
function Add-Buffered { param([string]$ObjId, $Ev)
    if (-not $buffer.ContainsKey($ObjId)) { $buffer[$ObjId] = New-Object System.Collections.Generic.List[object] }
    $buffer[$ObjId].Add($Ev)
}

function Apply-FieldEvent { param($T, $Ev)
    $T.Events++
    if ($Ev.Name -and -not $T.Name) { $T.Name = $Ev.Name }
    switch ($Ev.Kind) {
        'created'  { $T.Created = "$($Ev.When) by $($Ev.Who)"; Write-Host "[$($T.Key)] CREATED  $($Ev.When) by $($Ev.Who) — '$($Ev.NameTo)'" -ForegroundColor DarkGray }
        'renamed'  {
            $T.Renames.Add("$($Ev.When) '$($Ev.NameFrom)' -> '$($Ev.NameTo)' by $($Ev.Who)")
            if ($T.Kind -eq 'name' -and $Ev.NameFrom -eq $T.Key) { $T.RenamedTo = $Ev.NameTo; if (-not $T.Name -or $T.Name -eq $T.Key) { $T.Name = $Ev.NameTo } }
            Write-Host "[$($T.Key)] RENAMED  $($Ev.When) '$($Ev.NameFrom)' -> '$($Ev.NameTo)' by $($Ev.Who)" -ForegroundColor DarkYellow
        }
        'deleted'  { $T.Deleted  = "$($Ev.When) by $($Ev.Who)$(if ($Ev.Source) { ' from ' + $Ev.Source })"; Write-Host "[$($T.Key)] DELETED  $($T.Deleted) — '$($Ev.Name)'" -ForegroundColor Red }
        'archived' { $T.Archived = "$($Ev.When) by $($Ev.Who)"; Write-Host "[$($T.Key)] ARCHIVED $($T.Archived) — '$($Ev.Name)'" -ForegroundColor DarkYellow }
    }
    Resolve-FieldTarget $T
}

function ConvertTo-CompactEvent { param($E, $Obj)
    $action = "$($E.type.action)"
    $kind = switch -Regex ($action) {
        'archived|archiviert'                   { 'archived'; break }
        'deleted|gel.scht'                      { 'deleted';  break }
        'created|erstellt'                      { 'created';  break }
        'renamed|umbenannt|updated|ge.ndert|aktualisiert' { 'updated'; break }
        default                                 { 'other' }
    }
    $nc = $E.changedValues | Where-Object { $_.key -eq 'Name' } | Select-Object -First 1
    if ($kind -eq 'updated' -and $nc -and $nc.from -ne $nc.to) { $kind = 'renamed' }
    [PSCustomObject]@{
        Kind = $kind; When = $E.timestamp
        Who  = if ($E.author -and $E.author.name) { $E.author.name } else { 'unknown' }
        Source = "$($E.source)"; ObjId = "$($Obj.id)"; Name = "$($Obj.name)"
        NameFrom = "$($nc.from)"; NameTo = "$($nc.to)"
    }
}

if ($fieldTargets.Count -gt 0) {
    $actionsParam = ConvertTo-ActionsParam $Actions
    Write-Host "Field actions      : $actionsParam" -ForegroundColor DarkGray
    $uri = "$DcBaseUrl/rest/auditing/1.0/events?search=&actions=$actionsParam&limit=$PageSize"
    $page = 0; $scanned = 0; $oldest = $null
    while ($uri) {
        $page++
        if ($MaxPages -gt 0 -and $page -gt $MaxPages) { Write-Host "Reached -MaxPages; stopping field traversal." -ForegroundColor DarkYellow; break }
        try { $r = Invoke-RestMethod -Uri $uri -Headers $dcH } catch { throw "Field audit query failed on page ${page}: $($_.Exception.Message)" }
        if ($page -eq 1 -and @($r.entities).Count -eq 0) { Write-Host "First page returned NO events — action names probably don't match this instance. Run with -DiscoverActions 5." -ForegroundColor Red }

        foreach ($e in @($r.entities)) {
            $scanned++; $oldest = $e.timestamp
            foreach ($obj in @($e.affectedObjects | Where-Object { $_.type -eq 'CUSTOM_FIELD' })) {
                $ev = ConvertTo-CompactEvent $e $obj
                Add-Buffered $ev.ObjId $ev
                foreach ($t in $fieldTargets) {
                    $match = $false
                    if ($t.Id -and $ev.ObjId -eq $t.Id) { $match = $true }
                    elseif ($t.Kind -eq 'name' -and -not $t.Id -and ($ev.Name -eq $t.Key -or $ev.NameFrom -eq $t.Key -or $ev.NameTo -eq $t.Key)) {
                        # Bind the name to its id and back-fill everything already seen for that id
                        $t.Id = $ev.ObjId
                        Write-Host "[$($t.Key)] bound to $($t.Id) — back-filling $($buffer[$t.Id].Count - 1) earlier-seen event(s)" -ForegroundColor Cyan
                        foreach ($past in $buffer[$t.Id]) { if ($past -ne $ev) { Apply-FieldEvent $t $past } }
                        $match = $true
                    }
                    if ($match) { Apply-FieldEvent $t $ev }
                }
            }
        }
        if ($page % 25 -eq 0) { Write-Host ("...page {0}, {1} events, reached {2}" -f $page, $scanned, $oldest) -ForegroundColor DarkGray }
        if (-not ($fieldTargets | Where-Object { -not $_.Resolved })) { Write-Host "All field targets resolved after $page page(s) / $scanned events." -ForegroundColor Green; break }
        if ($r.pagingInfo.lastPage -or -not $r.pagingInfo.nextPageLink) { Write-Host "Field history exhausted after $page page(s) / $scanned events (oldest: $oldest)." -ForegroundColor DarkYellow; break }
        $uri = $r.pagingInfo.nextPageLink
    }
}

# ================= FILTER TARGETS =================
$filterTargets = New-Object System.Collections.Generic.List[object]
foreach ($raw in @($FilterNames)) { $n = "$raw".Trim(); if ($n) {
    $filterTargets.Add([ordered]@{ Key=$n; Id=''; Name=$n; Owner=''; Created=$null; Renames=(New-Object System.Collections.Generic.List[string]); RenamedTo=''; Deleted=$null; Events=0; Resolved=$false })
}}
if ($filterTargets.Count -gt 0) {
    $fbuffer = @{}
    $fParam  = ConvertTo-ActionsParam $FilterActions
    Write-Host "Filter actions     : $fParam" -ForegroundColor DarkGray
    $uri = "$DcBaseUrl/rest/auditing/1.0/events?search=&actions=$fParam&limit=$PageSize"
    $page = 0; $scanned = 0; $oldest = $null
    while ($uri) {
        $page++
        if ($MaxPages -gt 0 -and $page -gt $MaxPages) { Write-Host "Reached -MaxPages; stopping filter traversal." -ForegroundColor DarkYellow; break }
        try { $r = Invoke-RestMethod -Uri $uri -Headers $dcH } catch { throw "Filter audit query failed on page ${page}: $($_.Exception.Message)" }
        if ($page -eq 1 -and @($r.entities).Count -eq 0) { Write-Host "First page returned NO filter events — check -FilterActions names via -DiscoverActions 5." -ForegroundColor Red }

        foreach ($e in @($r.entities)) {
            $scanned++; $oldest = $e.timestamp
            foreach ($obj in @($e.affectedObjects | Where-Object { $_.type -match '(?i)filter' -or $_.type -eq 'SEARCH_REQUEST' })) {
                $ev = ConvertTo-CompactEvent $e $obj
                if (-not $fbuffer.ContainsKey($ev.ObjId)) { $fbuffer[$ev.ObjId] = New-Object System.Collections.Generic.List[object] }
                $fbuffer[$ev.ObjId].Add($ev)
                foreach ($t in $filterTargets) {
                    $match = $false
                    if ($t.Id -and $ev.ObjId -eq $t.Id) { $match = $true }
                    elseif (-not $t.Id -and ($ev.Name -eq $t.Key -or $ev.NameFrom -eq $t.Key -or $ev.NameTo -eq $t.Key)) {
                        $t.Id = $ev.ObjId
                        foreach ($past in $fbuffer[$t.Id]) { if ($past -ne $ev) { $t.Events++; if ($past.Kind -eq 'deleted') { $t.Deleted = "$($past.When) by $($past.Who)" } } }
                        $match = $true
                    }
                    if ($match) {
                        $t.Events++
                        switch ($ev.Kind) {
                            'created' { $t.Created = "$($ev.When) by $($ev.Who)"; Write-Host "[filter '$($t.Key)'] CREATED $($t.Created)" -ForegroundColor DarkGray }
                            'renamed' { $t.Renames.Add("$($ev.When) '$($ev.NameFrom)' -> '$($ev.NameTo)' by $($ev.Who)"); if ($ev.NameFrom -eq $t.Key) { $t.RenamedTo = $ev.NameTo }; Write-Host "[filter '$($t.Key)'] RENAMED $($ev.When) '$($ev.NameFrom)' -> '$($ev.NameTo)' by $($ev.Who)" -ForegroundColor DarkYellow }
                            'deleted' { $t.Deleted = "$($ev.When) by $($ev.Who)$(if ($ev.Source) { ' from ' + $ev.Source })"; Write-Host "[filter '$($t.Key)'] DELETED $($t.Deleted)" -ForegroundColor Red }
                        }
                        if ($t.Deleted -or $t.RenamedTo) { $t.Resolved = $true }
                    }
                }
            }
        }
        if ($page % 25 -eq 0) { Write-Host ("...page {0}, {1} events, reached {2}" -f $page, $scanned, $oldest) -ForegroundColor DarkGray }
        if (-not ($filterTargets | Where-Object { -not $_.Resolved })) { Write-Host "All filter targets resolved after $page page(s)." -ForegroundColor Green; break }
        if ($r.pagingInfo.lastPage -or -not $r.pagingInfo.nextPageLink) { Write-Host "Filter history exhausted after $page page(s) / $scanned events (oldest: $oldest)." -ForegroundColor DarkYellow; break }
        $uri = $r.pagingInfo.nextPageLink
    }
}

# ================= VERDICTS =================
$results = New-Object System.Collections.Generic.List[object]
Write-Host ("=" * 70) -ForegroundColor Yellow

foreach ($t in $fieldTargets) {
    $cloudStatus = ''; $successor = ''; $mapping = ''
    if ($cloudH) {
        $c = $null
        if ($t.Id) { $c = $cloudFields | Where-Object { $_.id -eq $t.Id } | Select-Object -First 1 }
        if ($c) { $cloudStatus = "ACTIVE: '$($c.name)'$(if ($c.orderable) { ' (sortable)' } else { ' (NOT sortable)' })" }
        else {
            $cloudStatus = 'absent'
            $lookName = if ($t.RenamedTo) { $t.RenamedTo } else { $t.Name }
            if ($lookName) {
                $s = $cloudFields | Where-Object { $_.name -eq $lookName } | Select-Object -First 1
                if ($s) {
                    $successor = "$($s.name) [$($s.id)]$(if ($s.orderable) { ' sortable' } else { ' NOT sortable' })"
                    $old = if ($t.Kind -eq 'id') { "cf[$($t.Id -replace 'customfield_','')]" } else { $t.Key }
                    $mapping = "'$old' = '$($s.name)'"
                }
            }
        }
    }
    $verdict =
        if     ($cloudStatus -like 'ACTIVE*')  { 'ActiveInCloud — reference resolves; error is permission/context or sortability' }
        elseif ($t.Deleted -and $mapping)      { "DeletedInDc ($($t.Deleted)) — but same-named Cloud field exists; mapping possible: $mapping" }
        elseif ($t.Deleted)                    { "DeletedInDc ($($t.Deleted)) — referencing filters were broken pre-migration (Obsolete)" }
        elseif ($t.Archived -and $mapping)     { "ArchivedInDc ($($t.Archived)) — not migrated; same-named Cloud field exists, mapping: $mapping" }
        elseif ($t.Archived)                   { "ArchivedInDc ($($t.Archived)) — archived fields are not migrated; unarchive+remigrate or map" }
        elseif ($t.RenamedTo -and $mapping)    { "RenamedInDc — '$($t.Key)' became '$($t.RenamedTo)'; Cloud successor found, mapping: $mapping" }
        elseif ($t.RenamedTo)                  { "RenamedInDc — '$($t.Key)' became '$($t.RenamedTo)' (id $($t.Id)); resolve Cloud successor by that name" }
        elseif ($t.DcActive -and $mapping)     { "OrphanedId — alive in DC as '$($t.Name)'; ID not preserved by migration; mapping: $mapping" }
        elseif ($t.DcActive)                   { "OrphanedId — alive in DC as '$($t.Name)'; resolve Cloud successor by name" }
        elseif ($t.Events -gt 0)               { "HistoryOnly — events found, no deletion/rename resolution in traversed range (last name '$($t.Name)')" }
        else                                   { 'LongDead — not in DC field list and no audit events in traversed history; treat referencing filters as Obsolete' }

    Write-Host "FIELD $($t.Key)  ->  '$($t.Name)' $($t.Id)" -ForegroundColor Yellow
    Write-Host "  DC active : $($t.DcActive)    events: $($t.Events)"
    Write-Host "  created   : $(if ($t.Created) { $t.Created } else { '-' })"
    foreach ($rn in $t.Renames) { Write-Host "  rename    : $rn" }
    Write-Host "  archived  : $(if ($t.Archived) { $t.Archived } else { '-' })"
    Write-Host "  deleted   : $(if ($t.Deleted) { $t.Deleted } else { '-' })" -ForegroundColor $(if ($t.Deleted) { 'Red' } else { 'Gray' })
    if ($cloudH) { Write-Host "  cloud     : $cloudStatus$(if ($successor) { "; successor: $successor" })" }
    Write-Host "  VERDICT   : $verdict" -ForegroundColor Magenta

    $results.Add([PSCustomObject]([ordered]@{
        'Target Type'='Custom field'; 'Target'=$t.Key; 'DC Object Id'=$t.Id; 'Last Known Name'=$t.Name; 'DC Type'=$t.Type
        'DC Active'=$t.DcActive; 'Created'="$($t.Created)"; 'Renames'=($t.Renames -join "`n"); 'Renamed To'=$t.RenamedTo
        'Archived'="$($t.Archived)"; 'Deleted'="$($t.Deleted)"; 'Audit Events'=$t.Events
        'Cloud Status'=$cloudStatus; 'Cloud Successor'=$successor; 'Suggested Mapping'=$mapping; 'Verdict'=$verdict
    }))
}

foreach ($t in $filterTargets) {
    $cloudStatus = ''
    if ($cloudH) {
        $q = [uri]::EscapeDataString('"' + $t.Key + '"')
        $s = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/filter/search?filterName=$q&overrideSharePermissions=true" -Headers $cloudH
        $hit = $s.values | Where-Object { $_.name -eq $t.Key } | Select-Object -First 1
        $cloudStatus = if ($hit) { "EXISTS in Cloud (id $($hit.id), owner $($hit.owner.displayName))" } else { 'absent in Cloud' }
    }
    $verdict =
        if     ($cloudStatus -like 'EXISTS*') { "ExistsInCloud — filter = ""$($t.Key)"" should resolve; error is visibility (the referencing filter's owner can't see it)" }
        elseif ($t.Deleted)                   { "DeletedInDc ($($t.Deleted)) — the filter = ""$($t.Key)"" clause is dead; recreate the filter or rewrite/remove the clause" }
        elseif ($t.RenamedTo)                 { "RenamedInDc — now '$($t.RenamedTo)'; rewrite the clause to filter = ""$($t.RenamedTo)""" }
        elseif ($t.Events -gt 0)              { 'HistoryOnly — events found but no deletion/rename in traversed range' }
        else                                  { 'NoTrace — no audit events for this filter name in traversed history' }

    Write-Host "FILTER '$($t.Key)' $($t.Id)" -ForegroundColor Yellow
    Write-Host "  events    : $($t.Events)   created: $(if ($t.Created) { $t.Created } else { '-' })"
    foreach ($rn in $t.Renames) { Write-Host "  rename    : $rn" }
    Write-Host "  deleted   : $(if ($t.Deleted) { $t.Deleted } else { '-' })" -ForegroundColor $(if ($t.Deleted) { 'Red' } else { 'Gray' })
    if ($cloudH) { Write-Host "  cloud     : $cloudStatus" }
    Write-Host "  VERDICT   : $verdict" -ForegroundColor Magenta

    $results.Add([PSCustomObject]([ordered]@{
        'Target Type'='Filter'; 'Target'=$t.Key; 'DC Object Id'=$t.Id; 'Last Known Name'=$t.Name; 'DC Type'=''
        'DC Active'=''; 'Created'="$($t.Created)"; 'Renames'=($t.Renames -join "`n"); 'Renamed To'=$t.RenamedTo
        'Archived'=''; 'Deleted'="$($t.Deleted)"; 'Audit Events'=$t.Events
        'Cloud Status'=$cloudStatus; 'Cloud Successor'=''; 'Suggested Mapping'=''; 'Verdict'=$verdict
    }))
}

Write-Host ("-" * 50)
if ($ExportCsv -and $results.Count -gt 0) {
    $results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
