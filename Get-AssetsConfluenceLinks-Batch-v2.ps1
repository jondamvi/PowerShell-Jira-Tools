<#
.SYNOPSIS
    Batch inventory of Confluence-type Assets/Insight attribute values in Jira Data Center. (v2)

.DESCRIPTION
    Driven by an INPUT CSV with columns: SchemaId, AttributeName, AttributeType.
    Rows whose AttributeType != -TypeFilter (default "Confluence") are skipped. For the rest it
    resolves the attribute NAME to its objectTypeAttributeId(s) in the schema, runs the AQL
    "<Attribute>" is not Empty query (paginated), and writes one row per object/value.

    IMPORTANT - NO ROW IS FILTERED BY JIRA TYPE.
      The audit's AttributeType column is what filters the INPUT. The Jira-resolved attribute
      type (0 = Default/plain field, 3 = Confluence integration) only drives an informational
      NOTE. A type-0 object means the Confluence link is stored as plain URL/text - it is still
      extracted. Notes are per object-type DEFINITION; CSV rows are per POPULATED object, so the
      two counts legitimately differ. See the coverage CSV for the per-object-type breakdown.

    Outputs:
      -OutCsv        one row per object/value (fixed column order, see below)
      -CoverageCsv   per (schema, attribute, object type) populated-object counts
      -BackupDir     <ObjectKey>_Attribute<AttrId>_RawValue.txt  (pretty raw value JSON per object)

    Value-CSV columns, in order:
      SchemaId, SourceAttributeName, ObjectId, ObjectKey, Label, ObjectTypeId, ObjectTypeName,
      AttrId, AttrName, AttrType, AttrDefaultType, ConfluenceUri, ConfluenceName, FlatValue,
      LINK_URL, SpaceName, DC_ConfPageId, RawValueJson, Action, Comments

.PARAMETER InputCsv     Path to input CSV. Headers: SchemaId, AttributeName, AttributeType.
.PARAMETER BaseUrl      Jira DC base URL, no trailing slash. e.g. https://jirasite
.PARAMETER Pat          DC Personal Access Token (Bearer). Falls back to -UseDefaultCredentials.
.PARAMETER TypeFilter   AttributeType value to KEEP. Default "Confluence".
.PARAMETER ExpectedJiraAttrType  Numeric type expected for Confluence (default 3). Note-only.
.PARAMETER ApiBase      REST base path. Default "rest/assets/1.0" (or "rest/insight/1.0").
.PARAMETER ResultPerPage AQL page size. Default 50.
.PARAMETER OutCsv       Combined value CSV. Default .\confluence_attr_values.csv
.PARAMETER CoverageCsv  Coverage CSV. Default .\confluence_coverage.csv
.PARAMETER BackupDir    Per-object raw-value txt dir. Default .\rawvalue_backup
.PARAMETER InspectFirst Dump first matching value JSON and exit (to confirm the page-ID field).

.EXAMPLE
    .\Get-AssetsConfluenceLinks-Batch-v2.ps1 -InputCsv .\input.csv -BaseUrl https://jirasite `
        -Pat $env:JIRA_PAT -OutCsv .\confluence_links.csv
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputCsv,
    [Parameter(Mandatory)][string]$BaseUrl,
    [string]$Pat,
    [string]$TypeFilter = 'Confluence',
    [int]$ExpectedJiraAttrType = 3,
    [string]$ApiBase = 'rest/assets/1.0',
    [int]$ResultPerPage = 50,
    [string]$OutCsv = '.\confluence_attr_values.csv',
    [string]$CoverageCsv = '.\confluence_coverage.csv',
    [string]$BackupDir = '.\rawvalue_backup',
    [switch]$InspectFirst
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$BaseUrl = $BaseUrl.TrimEnd('/')

$headers = @{ Accept = 'application/json' }
$reqArgs = @{ Headers = $headers; ContentType = 'application/json'; ErrorAction = 'Stop' }
if ($Pat) { $headers['Authorization'] = "Bearer $Pat" }
else {
    Write-Warning 'No -Pat supplied; falling back to -UseDefaultCredentials (Kerberos/NTLM).'
    $reqArgs['UseDefaultCredentials'] = $true
}

function Invoke-AssetsApi {
    param([string]$RelativeUrl)
    $uri = "$BaseUrl/$ApiBase/$RelativeUrl"
    try { return Invoke-RestMethod -Uri $uri -Method Get @reqArgs }
    catch { throw "GET $uri failed: $($_.Exception.Message)" }
}

# Best-effort parse of page link, space key and DC page id out of the value.
# Scans BOTH the flat value and the full raw JSON, so it works whether the link sits in a plain
# text/URL field (type 0) or inside the confluenceTypeValue object (type 3).
function Get-ConfluenceLinkParts {
    param([string]$FlatValue, [string]$RawJson)

    $linkUrl = $null; $spaceName = $null; $pageId = $null
    $blob = (@($FlatValue, $RawJson) | Where-Object { $_ }) -join ' '

    $urls = @()
    foreach ($m in [regex]::Matches($blob, 'https?://[^\s"''\\<>\)]+')) {
        $urls += ($m.Value.TrimEnd('.', ',', ';'))
    }

    $pageLike = @($urls | Where-Object { $_ -match 'pageId=|/display/|/pages/|viewpage\.action|/spaces/' })
    if     ($pageLike.Count) { $linkUrl = $pageLike[0] }
    elseif ($urls.Count)     { $linkUrl = $urls[0] }

    if     ($blob -match 'pageId=(\d+)') { $pageId = $matches[1] }
    elseif ($blob -match '/pages/(\d+)') { $pageId = $matches[1] }

    if ($linkUrl) {
        if     ($linkUrl -match '/display/([^/?#]+)') { $spaceName = [uri]::UnescapeDataString($matches[1]) }
        elseif ($linkUrl -match 'spaceKey=([^&"]+)')  { $spaceName = [uri]::UnescapeDataString($matches[1]) }
        elseif ($linkUrl -match '/spaces/([^/?#]+)')  { $spaceName = [uri]::UnescapeDataString($matches[1]) }
    }
    if (-not $spaceName -and ($blob -match 'spaceKey=([^&"]+)')) {
        $spaceName = [uri]::UnescapeDataString($matches[1])
    }

    if ($pageId -and ($linkUrl -notmatch 'pageId=|/pages/|/display/') -and $urls.Count) {
        try {
            $u = [uri]$urls[0]
            $linkUrl = ('{0}://{1}/pages/viewpage.action?pageId={2}' -f $u.Scheme, $u.Authority, $pageId)
        } catch { }
    }

    return [pscustomobject]@{ LinkUrl = $linkUrl; SpaceName = $spaceName; PageId = $pageId }
}

# Cache: schemaId -> list of { ObjectTypeId, ObjectTypeName, AttrId, AttrName, AttrType, AttrDefaultType }
$schemaAttrCache = @{}
function Get-SchemaAttributeIndex {
    param([string]$SchemaId)
    if ($schemaAttrCache.ContainsKey($SchemaId)) { return $schemaAttrCache[$SchemaId] }
    Write-Host "  Indexing object types/attributes for schema $SchemaId ..." -ForegroundColor DarkGray
    $idx = New-Object System.Collections.Generic.List[object]
    foreach ($ot in (Invoke-AssetsApi "objectschema/$SchemaId/objecttypes/flat")) {
        foreach ($a in (Invoke-AssetsApi "objecttype/$($ot.id)/attributes")) {
            $idx.Add([pscustomobject]@{
                ObjectTypeId = $ot.id; ObjectTypeName = $ot.name
                AttrId = $a.id; AttrName = $a.name; AttrType = $a.type; AttrDefaultType = $a.defaultType
            })
        }
    }
    $schemaAttrCache[$SchemaId] = $idx
    return $idx
}

# --- input ---
if (-not (Test-Path -LiteralPath $InputCsv)) { throw "Input CSV not found: $InputCsv" }
$inRows = Import-Csv -LiteralPath $InputCsv

$seen = New-Object 'System.Collections.Generic.HashSet[string]'
$qualified = New-Object System.Collections.Generic.List[object]
$skipped = 0
foreach ($r in $inRows) {
    $sid = "$($r.SchemaId)".Trim(); $name = "$($r.AttributeName)".Trim(); $type = "$($r.AttributeType)".Trim()
    if ($type -ine $TypeFilter) { $skipped++; continue }
    if (-not $sid -or -not $name) { Write-Warning 'Row with empty SchemaId/AttributeName skipped.'; continue }
    if ($seen.Add("$sid|$name")) { $qualified.Add([pscustomobject]@{ SchemaId = $sid; AttributeName = $name }) }
}
Write-Host ("Input rows: {0} | qualifying ({1}): {2} | skipped (other types): {3}" -f `
    $inRows.Count, $TypeFilter, $qualified.Count, $skipped) -ForegroundColor Cyan
if ($qualified.Count -eq 0) { Write-Warning 'Nothing to do.'; return }

if (-not (Test-Path -LiteralPath $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

# --- process ---
$rows = New-Object System.Collections.Generic.List[object]
$rawBackup = @{}   # "ObjectKey|AttrId" -> List of raw value objects

foreach ($q in $qualified) {
    Write-Host "Schema $($q.SchemaId) :: '$($q.AttributeName)'" -ForegroundColor Cyan

    $matches = @((Get-SchemaAttributeIndex -SchemaId $q.SchemaId) | Where-Object { $_.AttrName -eq $q.AttributeName })
    if ($matches.Count -eq 0) { Write-Warning '  Attribute not found on any object type - skipping.'; continue }

    foreach ($m in $matches) {
        if ($m.AttrType -ne $ExpectedJiraAttrType) {
            Write-Host ("  NOTE: declared on object type '{0}' as Jira type {1} (plain/Default field), not {2} (Confluence integration). Objects still extracted." -f `
                $m.ObjectTypeName, $m.AttrType, $ExpectedJiraAttrType) -ForegroundColor DarkYellow
        }
    }

    $attrIdSet = New-Object 'System.Collections.Generic.HashSet[string]'
    $metaMap = @{}
    foreach ($m in $matches) { [void]$attrIdSet.Add([string]$m.AttrId); $metaMap[[string]$m.AttrId] = $m }

    $qlEnc = [uri]::EscapeDataString('"' + $q.AttributeName + '" is not Empty')
    $page = 1; $count = 0
    do {
        $resp = Invoke-AssetsApi "aql/objects?objectSchemaId=$($q.SchemaId)&qlQuery=$qlEnc&includeAttributes=true&page=$page&resultPerPage=$ResultPerPage"
        $entries = @($resp.objectEntries)
        foreach ($o in $entries) {
            foreach ($a in $o.attributes) {
                if (-not $attrIdSet.Contains([string]$a.objectTypeAttributeId)) { continue }
                $meta = $metaMap[[string]$a.objectTypeAttributeId]
                $otId   = if ($o.objectType -and $o.objectType.id)   { $o.objectType.id }   else { $meta.ObjectTypeId }
                $otName = if ($o.objectType -and $o.objectType.name) { $o.objectType.name } else { $meta.ObjectTypeName }

                foreach ($v in @($a.objectAttributeValues)) {
                    if ($InspectFirst) {
                        Write-Host "`n=== First matching value (schema $($q.SchemaId), object $($o.objectKey)) ===" -ForegroundColor Yellow
                        $v | ConvertTo-Json -Depth 12 | Write-Host
                        Write-Host "`nConfirm which field holds the Confluence PAGE ID, then re-run without -InspectFirst." -ForegroundColor Yellow
                        return
                    }
                    $vProps = $v.PSObject.Properties.Name
                    $confUri = $null; $confName = $null; $flat = $null
                    if ($vProps -contains 'value') { $flat = $v.value }
                    if (-not $flat -and ($vProps -contains 'displayValue')) { $flat = $v.displayValue }
                    if ($vProps -contains 'confluenceTypeValue' -and $v.confluenceTypeValue) {
                        $confUri = $v.confluenceTypeValue.uri; $confName = $v.confluenceTypeValue.name
                    }
                    $rawJson = ($v | ConvertTo-Json -Depth 12 -Compress)
                    $parts = Get-ConfluenceLinkParts -FlatValue ([string]$flat) -RawJson $rawJson

                    $bk = "$($o.objectKey)|$($meta.AttrId)"
                    if (-not $rawBackup.ContainsKey($bk)) { $rawBackup[$bk] = New-Object System.Collections.Generic.List[object] }
                    $rawBackup[$bk].Add($v)

                    $rows.Add([pscustomobject][ordered]@{
                        SchemaId            = $q.SchemaId
                        SourceAttributeName = $q.AttributeName
                        ObjectId            = $o.id
                        ObjectKey           = $o.objectKey
                        Label               = $o.label
                        ObjectTypeId        = $otId
                        ObjectTypeName      = $otName
                        AttrId              = $meta.AttrId
                        AttrName            = $meta.AttrName
                        AttrType            = $meta.AttrType
                        AttrDefaultType     = $meta.AttrDefaultType
                        ConfluenceUri       = $confUri
                        ConfluenceName      = $confName
                        FlatValue           = $flat
                        LINK_URL            = $parts.LinkUrl
                        SpaceName           = $parts.SpaceName
                        DC_ConfPageId       = $parts.PageId
                        RawValueJson        = $rawJson
                        Action              = ''
                        Comments            = ''
                    })
                    $count++
                }
            }
        }
        $page++
    } while ($entries.Count -eq $ResultPerPage)

    Write-Host ("  declared on {0} object type(s); populated objects: {1}" -f $matches.Count, $count) -ForegroundColor Green
}

# --- per-object raw value backups ---
foreach ($k in $rawBackup.Keys) {
    $kp = $k -split '\|', 2
    $fname = ('{0}_Attribute{1}_RawValue.txt' -f ($kp[0] -replace '[^\w\.\-]', '_'), $kp[1])
    ($rawBackup[$k] | ConvertTo-Json -Depth 12) | Out-File -FilePath (Join-Path $BackupDir $fname) -Encoding UTF8
}

# --- outputs ---
if ($rows.Count -eq 0) { Write-Warning 'No values found across all qualifying attributes.'; return }

$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8

$coverage = $rows |
    Group-Object SchemaId, SourceAttributeName, ObjectTypeId, ObjectTypeName, AttrId, AttrType, AttrDefaultType |
    ForEach-Object {
        $f = $_.Group[0]
        [pscustomobject][ordered]@{
            SchemaId = $f.SchemaId; AttributeName = $f.SourceAttributeName
            ObjectTypeId = $f.ObjectTypeId; ObjectTypeName = $f.ObjectTypeName
            AttrId = $f.AttrId; AttrType = $f.AttrType; AttrDefaultType = $f.AttrDefaultType
            PopulatedCount = $_.Count
        }
    }
$coverage | Export-Csv -Path $CoverageCsv -NoTypeInformation -Encoding UTF8

Write-Host "`nCoverage (populated objects per object type):" -ForegroundColor Cyan
$coverage | Sort-Object SchemaId, AttributeName, ObjectTypeName | Format-Table -AutoSize | Out-String | Write-Host

Write-Host ("Wrote {0} value row(s) -> {1}" -f $rows.Count, $OutCsv) -ForegroundColor Green
Write-Host ("Wrote coverage -> {0}" -f $CoverageCsv) -ForegroundColor Green
Write-Host ("Raw value backups -> {0} ({1} files)" -f $BackupDir, $rawBackup.Count) -ForegroundColor Green
