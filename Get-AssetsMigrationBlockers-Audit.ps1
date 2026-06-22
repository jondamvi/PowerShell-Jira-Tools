<#
.SYNOPSIS
    Discovery / dependency audit: find ALL Assets attribute types that block JCMA migration in a
    schema, and the relationships (inheritance, AQL usage) that make remediation incomplete if
    missed. Run this FIRST, before remediating any single attribute.

.DESCRIPTION
    Insight/Assets has no calculated/formula attributes, so nothing is "derived from" the
    Confluence attribute by computation. The real risks this script surfaces are:
      1. INHERITANCE  - a blocker attribute declared on a parent object type is inherited by all
                        children (same objectTypeAttributeId on many object types).
      2. OTHER BLOCKERS - Version (5) and Project (6) special types block migration the same way
                        Confluence (3) does; fixing only Confluence can leave the migration blocked.
      3. AQL USAGE    - an Object-reference attribute (type 1) carries an IQL/AQL filter; if that
                        filter references a blocker attribute by name, clearing/removing the blocker
                        can break the reference. This script scans config fields for such usage.

    Attribute type ids: 0 Default, 1 Object reference, 2 User, 3 Confluence, 4 Group,
                        5 Version, 6 Project, 7 Status.
    Default sub-type ids (type 0): 0 Text,1 Integer,2 Boolean,3 Double,4 Date,5 Time,6 DateTime,
                        7 URL,8 Email,9 Textarea,10 Select,11 IP Address.

    Outputs:
      -AuditCsv     one row per attribute (all object types in the schema), with IsBlocker,
                    IsInherited, InheritedOnObjectTypes, ReferencedObjectTypeId, ConfigSnippet.
      -FindingsCsv  one row per blocker attribute, with inheritance span, populated-object count
                    (if -CountPopulated), and any AQL-usage references found.

.PARAMETER BaseUrl        Jira DC base URL, no trailing slash. e.g. https://jirasite
.PARAMETER Pat            DC Personal Access Token (Bearer). Falls back to -UseDefaultCredentials.
.PARAMETER SchemaId       One schema id, or comma-separated list.
.PARAMETER BlockerTypes   Type ids treated as blockers. Default 3,5,6 (Confluence, Version, Project).
.PARAMETER CountPopulated Also count populated objects per blocker (extra AQL calls).
.PARAMETER ApiBase        REST base path. Default "rest/assets/1.0".
.PARAMETER AuditCsv       Default .\assets_attribute_audit.csv
.PARAMETER FindingsCsv    Default .\assets_blocker_findings.csv

.EXAMPLE
    .\Get-AssetsMigrationBlockers-Audit.ps1 -BaseUrl https://jirasite -Pat $env:JIRA_PAT `
        -SchemaId "1,2,15" -CountPopulated
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [string]$Pat,
    [Parameter(Mandatory)][string]$SchemaId,
    [int[]]$BlockerTypes = @(3, 5, 6),
    [switch]$CountPopulated,
    [string]$ApiBase = 'rest/assets/1.0',
    [string]$AuditCsv = '.\assets_attribute_audit.csv',
    [string]$FindingsCsv = '.\assets_blocker_findings.csv'
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$BaseUrl = $BaseUrl.TrimEnd('/')

$headers = @{ Accept = 'application/json' }
$reqArgs = @{ Headers = $headers; ContentType = 'application/json'; ErrorAction = 'Stop' }
if ($Pat) { $headers['Authorization'] = "Bearer $Pat" }
else { Write-Warning 'No -Pat; using -UseDefaultCredentials.'; $reqArgs['UseDefaultCredentials'] = $true }

function Api { param([string]$Rel)
    $uri = "$BaseUrl/$ApiBase/$Rel"
    try { return Invoke-RestMethod -Uri $uri -Method Get @reqArgs }
    catch { throw "GET $uri failed: $($_.Exception.Message)" }
}

$typeLabels = @{ 0 = 'Default'; 1 = 'Object reference'; 2 = 'User'; 3 = 'Confluence'; 4 = 'Group'; 5 = 'Version'; 6 = 'Project'; 7 = 'Status' }
$defLabels = @{ 0 = 'Text'; 1 = 'Integer'; 2 = 'Boolean'; 3 = 'Double'; 4 = 'Date'; 5 = 'Time'; 6 = 'DateTime'; 7 = 'URL'; 8 = 'Email'; 9 = 'Textarea'; 10 = 'Select'; 11 = 'IP Address' }

function As-Int { param($v) if ($null -eq $v) { return $null }; if ($v -is [int] -or $v -is [long]) { return [int]$v }; if ($v.id -ne $null) { return [int]$v.id }; try { return [int]$v } catch { return $null } }

$schemaIds = $SchemaId -split '[,\s]+' | Where-Object { $_ }
$records = New-Object System.Collections.Generic.List[object]

foreach ($sid in $schemaIds) {
    Write-Host "Auditing schema $sid ..." -ForegroundColor Cyan
    $ots = Api "objectschema/$sid/objecttypes/flat"
    foreach ($ot in $ots) {
        foreach ($a in (Api "objecttype/$($ot.id)/attributes")) {
            $typeId = As-Int $a.type
            $dtId = if ($a.PSObject.Properties.Name -contains 'defaultType') { As-Int $a.defaultType } else { $null }
            $cfgFields = @('additionalValue', 'iql', 'typeValue', 'description') | ForEach-Object { if ($a.PSObject.Properties.Name -contains $_) { [string]$a.$_ } }
            $records.Add([pscustomobject]@{
                SchemaId         = $sid
                ObjectTypeId     = $ot.id
                ObjectTypeName   = $ot.name
                AttrId           = $a.id
                AttrName         = $a.name
                TypeId           = $typeId
                TypeLabel        = $typeLabels[$typeId]
                DefaultTypeId    = $dtId
                DefaultTypeLabel = if ($dtId -ne $null) { $defLabels[$dtId] } else { $null }
                IsBlocker        = ($BlockerTypes -contains $typeId)
                RefObjectTypeId  = if ($a.PSObject.Properties.Name -contains 'typeValue') { $a.typeValue } else { $null }
                ConfigText       = (($cfgFields | Where-Object { $_ }) -join ' | ')
                ConfigSnippet    = ($a | ConvertTo-Json -Depth 6 -Compress)
            })
        }
    }
}

# inheritance: same AttrId spanning multiple object types
$byAttrId = $records | Group-Object AttrId
$inheritMap = @{}
foreach ($g in $byAttrId) {
    if ($g.Count -gt 1) { $inheritMap[$g.Name] = (($g.Group | Select-Object -Expand ObjectTypeName -Unique) -join '; ') }
}
foreach ($r in $records) {
    $r | Add-Member -NotePropertyName IsInherited -NotePropertyValue ($inheritMap.ContainsKey([string]$r.AttrId)) -Force
    $r | Add-Member -NotePropertyName InheritedOnObjectTypes -NotePropertyValue ($(if ($inheritMap.ContainsKey([string]$r.AttrId)) { $inheritMap[[string]$r.AttrId] } else { '' })) -Force
}

# blocker names per schema, for AQL-usage scan
$blockerNamesBySchema = @{}
foreach ($r in ($records | Where-Object { $_.IsBlocker })) {
    if (-not $blockerNamesBySchema.ContainsKey($r.SchemaId)) { $blockerNamesBySchema[$r.SchemaId] = New-Object System.Collections.Generic.HashSet[string] }
    [void]$blockerNamesBySchema[$r.SchemaId].Add($r.AttrName)
}

# usage: non-blocker attribute whose config text references a blocker name
$usage = @{}   # blockerName -> list of "ObjectType.Attr"
foreach ($r in $records) {
    if (-not $r.ConfigText) { continue }
    $names = $blockerNamesBySchema[$r.SchemaId]
    if (-not $names) { continue }
    foreach ($bn in $names) {
        if ($r.AttrName -eq $bn) { continue }
        if ($r.ConfigText -match [regex]::Escape($bn)) {
            $key = "$($r.SchemaId)|$bn"
            if (-not $usage.ContainsKey($key)) { $usage[$key] = New-Object System.Collections.Generic.List[string] }
            $usage[$key].Add("$($r.ObjectTypeName).$($r.AttrName) (type $($r.TypeLabel))")
        }
    }
}

# populated counts per blocker (optional)
$popCount = @{}
if ($CountPopulated) {
    $blockerDistinct = $records | Where-Object { $_.IsBlocker } | Group-Object SchemaId, AttrName
    foreach ($g in $blockerDistinct) {
        $sid = $g.Group[0].SchemaId; $name = $g.Group[0].AttrName
        $qlEnc = [uri]::EscapeDataString('"' + $name + '" is not Empty')
        $n = 0; $page = 1
        do {
            $resp = Api "aql/objects?objectSchemaId=$sid&qlQuery=$qlEnc&includeAttributes=false&page=$page&resultPerPage=50"
            $e = @($resp.objectEntries); $n += $e.Count; $page++
        } while ($e.Count -eq 50)
        $popCount["$sid|$name"] = $n
    }
}

# --- outputs ---
$records | Select-Object SchemaId, ObjectTypeId, ObjectTypeName, AttrId, AttrName, TypeId, TypeLabel, `
    DefaultTypeId, DefaultTypeLabel, IsBlocker, IsInherited, InheritedOnObjectTypes, RefObjectTypeId, ConfigSnippet |
    Export-Csv -Path $AuditCsv -NoTypeInformation -Encoding UTF8

$findings = $records | Where-Object { $_.IsBlocker } | Group-Object SchemaId, AttrName | ForEach-Object {
    $f = $_.Group[0]
    $key = "$($f.SchemaId)|$($f.AttrName)"
    [pscustomobject][ordered]@{
        SchemaId            = $f.SchemaId
        AttrName            = $f.AttrName
        TypeLabel           = $f.TypeLabel
        DeclaredOnTypes     = (($_.Group | Select-Object -Expand ObjectTypeName -Unique) -join '; ')
        IsInherited         = ($_.Group | Where-Object { $_.IsInherited }).Count -gt 0
        PopulatedObjects    = if ($CountPopulated) { $popCount[$key] } else { 'n/a (use -CountPopulated)' }
        ReferencedByAql     = if ($usage.ContainsKey($key)) { ($usage[$key] -join '; ') } else { '' }
    }
}
$findings | Export-Csv -Path $FindingsCsv -NoTypeInformation -Encoding UTF8

# --- console summary ---
Write-Host "`n===== TYPE BREAKDOWN =====" -ForegroundColor Cyan
$records | Group-Object TypeLabel | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0,-18} {1}" -f $_.Name, $_.Count)
}

Write-Host "`n===== BLOCKERS (will stop JCMA) =====" -ForegroundColor Cyan
if (@($findings).Count -eq 0) { Write-Host '  None found for the configured blocker types.' -ForegroundColor Green }
else {
    foreach ($x in $findings) {
        Write-Host ("  [{0}] '{1}' ({2})" -f $x.SchemaId, $x.AttrName, $x.TypeLabel) -ForegroundColor Yellow
        Write-Host ("       on object types: {0}" -f $x.DeclaredOnTypes)
        if ($x.IsInherited) { Write-Host "       INHERITED across multiple object types - remediate ALL of them." -ForegroundColor DarkYellow }
        if ($x.ReferencedByAql) { Write-Host ("       AQL USAGE: referenced by {0}" -f $x.ReferencedByAql) -ForegroundColor Red }
        if ($CountPopulated) { Write-Host ("       populated objects: {0}" -f $x.PopulatedObjects) }
    }
}

Write-Host ("`nAudit  -> {0}" -f $AuditCsv) -ForegroundColor Green
Write-Host ("Findings -> {0}" -f $FindingsCsv) -ForegroundColor Green
Write-Host "Cross-check the type breakdown against Atlassian's current JCMA Assets support matrix; treat any non-Default/non-basic type as 'verify before migrating'." -ForegroundColor Yellow
