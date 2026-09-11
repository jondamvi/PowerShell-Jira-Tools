<#
.SYNOPSIS
    Audits the fate of custom fields referenced as cf[NNNNN] in migrated filter
    JQLs. Read-only. For each field ID it establishes, from the DC instance:
      - whether the field is still ACTIVE in DC (name + type)
      - what the DC advanced audit log recorded for it, queried both ways:
          by object: /rest/auditing/1.0/events?...&affectedObject=CUSTOM_FIELD,<id>
          by name  : /rest/auditing/1.0/events?search=customfield_<id>
      - whether a DELETION event exists (who / when)
    and, when Cloud credentials are supplied, completes the ladder:
      - is the ID active in Cloud, or in the Cloud custom-field trash (restorable)
      - the likely Cloud SUCCESSOR field found by the DC field's name, with a
        ready-to-paste -FieldRenameMap entry

    Verdicts:
      OrphanedId  — field alive in DC, ID absent in Cloud: migration does not
                    preserve custom field IDs; map cf[<id>] to the Cloud successor.
      DeletedInDc — audit log shows deletion; referencing filters were broken
                    before migration (Obsolete bucket).
      LongDead    — absent from DC and no audit trace within retention.
      ActiveInCloud / InCloudTrash — the Cloud side resolves it after all.

.EXAMPLE
    .\Audit-DcCustomFields.ps1 -DcBaseUrl "https://jiradc.company.com" -PersonalAccessToken $pat `
        -FieldIds 54282,64284 `
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
    [string[]]$FieldIds,               # numeric ids, e.g. 54282,64284

    # DC audit category holding custom field events; instance vocabulary via
    # /rest/auditing/1.0/categories if this default doesn't match.
    [string]$Category = 'Custom fields',

    [int]$Limit = 100,

    # Optional Cloud side — enables trash/active/successor resolution
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
catch { throw "DC auth preflight failed: $($_.Exception.Message) — check -DcBaseUrl and the PAT." }
Write-Host "DC authenticated  : $($dcMe.displayName)" -ForegroundColor Cyan

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

# --- Full DC field list once ---
$dcFields = @(Invoke-RestMethod -Uri "$DcBaseUrl/rest/api/2/field" -Headers $dcH)
Write-Host "DC fields loaded  : $($dcFields.Count)" -ForegroundColor Cyan

function Get-DcAuditEvents {
    param([string]$Query, [string]$AffectedObject)
    $uri = "$DcBaseUrl/rest/auditing/1.0/events?search=$([uri]::EscapeDataString($Query))" +
           "&category=$([uri]::EscapeDataString($Category))&limit=$Limit"
    if ($AffectedObject) { $uri += "&affectedObject=$AffectedObject" }
    try {
        $r = Invoke-RestMethod -Uri $uri -Headers $dcH
        return @($r.entities)
    }
    catch {
        Write-Host "  audit query failed ($($_.Exception.Message)) — advanced auditing unavailable or category name mismatch (see /rest/auditing/1.0/categories)" -ForegroundColor DarkYellow
        return @()
    }
}

function Format-AuditEvent {
    param($E)
    $action = if ($E.type -and $E.type.action) { $E.type.action } elseif ($E.action) { $E.action } else { '?' }
    $author = if ($E.author -and $E.author.name) { $E.author.name } else { 'system' }
    $when   = $E.timestamp
    [PSCustomObject]@{ When = $when; Action = $action; Author = $author }
}

$results = New-Object System.Collections.Generic.List[object]

foreach ($id in $FieldIds) {
    $id = "$id".Trim() -replace '^customfield_',''
    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host "customfield_$id / cf[$id]" -ForegroundColor Yellow

    # --- DC presence ---
    $dcField = $dcFields | Where-Object { $_.id -eq "customfield_$id" } | Select-Object -First 1
    $dcName  = if ($dcField) { $dcField.name } else { '' }
    $dcType  = if ($dcField -and $dcField.schema) { $dcField.schema.custom } else { '' }
    if ($dcField) { Write-Host "DC status   : ACTIVE — '$dcName' ($dcType)" -ForegroundColor Green }
    else          { Write-Host "DC status   : not present in field list" }

    # --- DC audit: by affected object id, then by name search ---
    $evById   = Get-DcAuditEvents -Query '' -AffectedObject "CUSTOM_FIELD,$id"
    $evByName = Get-DcAuditEvents -Query "customfield_$id"
    $events   = @($evById) + @($evByName)
    $evRows   = $events | ForEach-Object { Format-AuditEvent $_ } | Sort-Object When -Unique
    if ($evRows) {
        Write-Host "Audit events:" -ForegroundColor Cyan
        $evRows | Format-Table -AutoSize | Out-String | Write-Host
    }
    else { Write-Host "Audit events: none within retention" -ForegroundColor DarkGray }

    $deletion = $evRows | Where-Object { $_.Action -match '(?i)delet' } | Select-Object -First 1

    # --- Cloud side (optional) ---
    $cloudStatus = ''; $successorName = ''; $mapping = ''
    if ($cloudH) {
        $act = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field/search?id=customfield_$id" -Headers $cloudH
        if ($act.total -gt 0) {
            $cloudStatus = "ACTIVE: '$($act.values[0].name)'"
        }
        else {
            try {
                $tr = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field/search/trashed?id=customfield_$id" -Headers $cloudH
                if ($tr.total -gt 0) {
                    $cloudStatus = "IN TRASH: '$($tr.values[0].name)' (trashed $($tr.values[0].trashedDate)) — RESTORABLE"
                }
                else { $cloudStatus = 'absent' }
            }
            catch { $cloudStatus = 'absent (trash query failed)' }
        }
        Write-Host "Cloud status: $cloudStatus" -ForegroundColor $(if ($cloudStatus -like 'ACTIVE*') { 'Green' } elseif ($cloudStatus -like 'IN TRASH*') { 'Yellow' } else { 'Gray' })

        # Successor by DC name
        if ($dcName -and $cloudStatus -eq 'absent') {
            $succ = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field/search?query=$([uri]::EscapeDataString($dcName))" -Headers $cloudH
            if ($succ.total -gt 0) {
                $successorName = $succ.values[0].name
                $mapping = "'cf[$id]' = '$successorName'"
                Write-Host "Cloud successor by name: '$successorName' ($($succ.values[0].id))" -ForegroundColor Green
                Write-Host "Suggested -FieldRenameMap entry: $mapping" -ForegroundColor Green
                if ($succ.total -gt 1) {
                    Write-Host "  NOTE: $($succ.total) name matches — verify: $(($succ.values | ForEach-Object { $_.name }) -join ' | ')" -ForegroundColor DarkYellow
                }
            }
            else { Write-Host "No Cloud field matches DC name '$dcName'" -ForegroundColor DarkYellow }
        }
    }

    # --- Verdict ---
    $verdict =
        if     ($cloudStatus -like 'ACTIVE*')   { 'ActiveInCloud — reference is a permission/context issue, not existence' }
        elseif ($cloudStatus -like 'IN TRASH*') { 'InCloudTrash — restore the field, no JQL edit needed' }
        elseif ($deletion)                      { "DeletedInDc — $($deletion.When) by $($deletion.Author); filters were broken pre-migration (Obsolete)" }
        elseif ($dcField -and $mapping)         { "OrphanedId — alive in DC, ID not preserved by migration; map: $mapping" }
        elseif ($dcField)                       { 'OrphanedId — alive in DC, ID not preserved by migration; resolve Cloud successor by name' }
        else                                    { 'LongDead — absent from DC, no audit trace within retention; treat referencing filters as Obsolete' }
    Write-Host "VERDICT     : $verdict" -ForegroundColor Magenta

    $results.Add([PSCustomObject]([ordered]@{
        'Field ID'         = "customfield_$id"
        'DC Name'          = $dcName
        'DC Type'          = $dcType
        'DC Active'        = [bool]$dcField
        'Deletion Event'   = $(if ($deletion) { "$($deletion.When) by $($deletion.Author)" } else { '' })
        'Audit Events'     = ($evRows | ForEach-Object { "$($_.When) $($_.Action) ($($_.Author))" }) -join "`n"
        'Cloud Status'     = $cloudStatus
        'Cloud Successor'  = $successorName
        'Suggested Mapping'= $mapping
        'Verdict'          = $verdict
    }))
}

Write-Host ("-" * 50)
if ($ExportCsv -and $results.Count -gt 0) {
    $results | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
