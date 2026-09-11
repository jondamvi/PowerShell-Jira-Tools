<#
.SYNOPSIS
    Cloud-side custom field lookup: existence, exact JQL clause names, type,
    and SORTABILITY (the field bean's 'orderable' flag = usable in ORDER BY).
    Read-only. Accepts ids (cf[NNNNN] / customfield_NNNNN / NNNNN) and/or names.
    Names not found exactly get fuzzy candidates (substring on each word).

.EXAMPLE
    .\Get-CloudFieldInfo.ps1 -CloudBaseUrl "https://company.atlassian.net" -Email "you@company.org" -ApiToken $token `
        -FieldIds 48483,10677 -FieldNames 'OS_ServiceGruppe','IT ServiceGruppe' -ExportCsv cloudfields.csv
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory)] [string]$CloudBaseUrl,
    [Parameter(Mandatory)] [string]$Email,
    [Parameter(Mandatory)] [string]$ApiToken,
    [string[]]$FieldIds,
    [string[]]$FieldNames,
    [string]$ExportCsv
)

$ErrorActionPreference = 'Stop'
if     ($CloudBaseUrl -match '^(?i)http://')     { $CloudBaseUrl = 'https://' + $CloudBaseUrl.Substring(7) }
elseif ($CloudBaseUrl -notmatch '^(?i)https://') { $CloudBaseUrl = "https://$CloudBaseUrl" }
$CloudBaseUrl = $CloudBaseUrl.TrimEnd('/')
if (-not $FieldIds -and -not $FieldNames) { throw "Supply -FieldIds and/or -FieldNames." }

$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${Email}:${ApiToken}"))
$h     = @{ Authorization = "Basic $basic"; Accept = 'application/json' }
$me    = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/myself" -Headers $h
Write-Host "Authenticated as : $($me.displayName)" -ForegroundColor Cyan

# One call: every field with orderable/searchable/clauseNames/schema
$all = @(Invoke-RestMethod -Uri "$CloudBaseUrl/rest/api/3/field" -Headers $h)
Write-Host "Cloud fields     : $($all.Count)" -ForegroundColor Cyan

function New-Row {
    param($Query, $Field, [string]$Match, [string]$Candidates)
    $verdict =
        if (-not $Field)             { "NOT FOUND in Cloud$(if ($Candidates) { ' — candidates: ' + $Candidates })" }
        elseif ($Field.orderable)    { 'EXISTS, SORTABLE — usable in ORDER BY' }
        else                         { 'EXISTS, NOT SORTABLE in Cloud — remove from ORDER BY or sort by another field' }
    [PSCustomObject]([ordered]@{
        'Query'        = $Query
        'Match'        = $Match
        'Field ID'     = if ($Field) { $Field.id } else { '' }
        'Name'         = if ($Field) { $Field.name } else { '' }
        'Type'         = if ($Field) { if ($Field.schema.custom) { $Field.schema.custom } else { $Field.schema.type } } else { '' }
        'Orderable'    = if ($Field) { [bool]$Field.orderable } else { '' }
        'Searchable'   = if ($Field) { [bool]$Field.searchable } else { '' }
        'JQL Names'    = if ($Field) { ($Field.clauseNames -join ' | ') } else { '' }
        'Candidates'   = $Candidates
        'Verdict'      = $verdict
    })
}

$rows = New-Object System.Collections.Generic.List[object]

foreach ($raw in @($FieldIds)) {
    $n  = "$raw".Trim() -replace '^(?i)cf\[(\d+)\]$','$1' -replace '^(?i)customfield_',''
    if (-not $n) { continue }
    $id = "customfield_$n"
    $f  = $all | Where-Object { $_.id -eq $id } | Select-Object -First 1
    $rows.Add((New-Row -Query "cf[$n]" -Field $f -Match $(if ($f) { 'exact id' } else { '' }) -Candidates ''))
}

foreach ($name in @($FieldNames)) {
    $name = "$name".Trim(); if (-not $name) { continue }
    $f = $all | Where-Object { $_.name -eq $name -or ($_.clauseNames -contains $name) } | Select-Object -First 1
    $cands = ''
    if (-not $f) {
        # Fuzzy: any Cloud field whose name contains any word (>=3 chars) of the query
        $words = $name -split '[\s_\-/]+' | Where-Object { $_.Length -ge 3 }
        $c = $all | Where-Object { $fn = $_.name; ($words | Where-Object { $fn -like "*$_*" }).Count -gt 0 } |
             Select-Object -First 8 | ForEach-Object { "$($_.name) [$($_.id)$(if ($_.orderable) { ', sortable' } else { ', NOT sortable' })]" }
        $cands = ($c -join '; ')
    }
    $rows.Add((New-Row -Query $name -Field $f -Match $(if ($f) { 'exact name/clauseName' } else { '' }) -Candidates $cands))
}

foreach ($r in $rows) {
    $color = if ($r.Verdict -like 'NOT FOUND*') { 'Red' } elseif ($r.Verdict -like '*NOT SORTABLE*') { 'Yellow' } else { 'Green' }
    Write-Host ("=" * 70) -ForegroundColor $color
    Write-Host "Query     : $($r.Query)"
    if ($r.'Field ID') {
        Write-Host "Field     : $($r.Name) [$($r.'Field ID')]  type=$($r.Type)"
        Write-Host "JQL names : $($r.'JQL Names')"
        Write-Host "Orderable : $($r.Orderable)   Searchable : $($r.Searchable)"
    }
    elseif ($r.Candidates) { Write-Host "Candidates: $($r.Candidates)" -ForegroundColor DarkYellow }
    Write-Host "VERDICT   : $($r.Verdict)" -ForegroundColor $color
}

if ($ExportCsv -and $rows.Count -gt 0) {
    $rows | Export-Csv -Path $ExportCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Exported to: $ExportCsv" -ForegroundColor Cyan
}
