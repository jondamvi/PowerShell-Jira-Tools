<#
.SYNOPSIS
    Batch extractor for Confluence-type Assets/Insight attribute values in Jira Data Center.

.DESCRIPTION
    Driven by an INPUT CSV with columns: SchemaId, AttributeName, AttributeType.
    For every row whose AttributeType matches the filter (default "Confluence", others skipped),
    it resolves the attribute NAME to its objectTypeAttributeId(s) inside that schema, runs the
    AQL "<Attribute>" is not Empty query (paginated), and writes one row per object/value to a
    single combined OUTPUT CSV with:

        SchemaId, SourceAttributeName,
        ObjectId, ObjectKey, Label, ObjectTypeId, ObjectTypeName,
        AttrId, AttrName, AttrType, AttrDefaultType,
        ConfluenceUri, ConfluenceName, FlatValue, RawValueJson

    RawValueJson always holds the full, unmodified value object, so nothing is lost even if the
    Confluence page-ID lives in a field this script doesn't pre-extract. Per-schema object-type
    attribute lookups are cached, so repeated attributes in the same schema don't re-hit the API.

.PARAMETER InputCsv
    Path to the input CSV. Required headers (case-insensitive): SchemaId, AttributeName, AttributeType.

.PARAMETER BaseUrl
    Jira DC base URL, no trailing slash. e.g. https://jirasite

.PARAMETER Pat
    Personal Access Token (Jira DC 8.14+). Sent as Bearer. Recommended over cookies.
    If omitted, falls back to -UseDefaultCredentials (Kerberos/NTLM on a domain box).

.PARAMETER TypeFilter
    AttributeType value to KEEP. Default "Confluence". Rows with any other value are skipped.

.PARAMETER ExpectedJiraAttrType
    Numeric Assets attribute type id expected for Confluence (default 3). Used only for a
    non-fatal warning if the type resolved from Jira doesn't match - helps catch stale input.

.PARAMETER ApiBase
    REST base path. Default "rest/assets/1.0". Switch to "rest/insight/1.0" on older builds.

.PARAMETER ResultPerPage
    AQL page size. Default 50.

.PARAMETER OutCsv
    Combined output CSV path. Default .\confluence_attr_values.csv

.PARAMETER InspectFirst
    Resolve and fetch ONE matching value from the first qualifying attribute, print its full
    JSON, and exit. Use to confirm the real Confluence page-ID field before a full run.

.EXAMPLE
    # input.csv:
    # SchemaId,AttributeName,AttributeType
    # 12,Linked Confluence Page,Confluence
    # 12,Owner,User
    # 15,Runbook,Confluence
    .\Get-AssetsConfluenceLinks-Batch.ps1 -InputCsv .\input.csv -BaseUrl https://jirasite `
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
    [switch]$InspectFirst
)

# --- PS 5.1: force TLS 1.2 ---
[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$BaseUrl = $BaseUrl.TrimEnd('/')

# --- Auth / common request splat ---
$headers = @{ Accept = 'application/json' }
$reqArgs = @{ Headers = $headers; ContentType = 'application/json'; ErrorAction = 'Stop' }
if ($Pat) {
    $headers['Authorization'] = "Bearer $Pat"
} else {
    Write-Warning 'No -Pat supplied; falling back to -UseDefaultCredentials (Kerberos/NTLM).'
    $reqArgs['UseDefaultCredentials'] = $true
}

function Invoke-AssetsApi {
    param([string]$RelativeUrl)
    $uri = "$BaseUrl/$ApiBase/$RelativeUrl"
    try {
        return Invoke-RestMethod -Uri $uri -Method Get @reqArgs
    } catch {
        throw "GET $uri failed: $($_.Exception.Message)"
    }
}

# Build (and cache) the full attribute index for a schema:
#   array of { ObjectTypeId, ObjectTypeName, AttrId, AttrName, AttrType, AttrDefaultType }
$schemaAttrCache = @{}
function Get-SchemaAttributeIndex {
    param([string]$SchemaId)
    if ($schemaAttrCache.ContainsKey($SchemaId)) { return $schemaAttrCache[$SchemaId] }

    Write-Host "  Indexing object types/attributes for schema $SchemaId ..." -ForegroundColor DarkGray
    $idx = New-Object System.Collections.Generic.List[object]
    $objectTypes = Invoke-AssetsApi "objectschema/$SchemaId/objecttypes/flat"
    foreach ($ot in $objectTypes) {
        $attrs = Invoke-AssetsApi "objecttype/$($ot.id)/attributes"
        foreach ($a in $attrs) {
            $idx.Add([pscustomobject]@{
                ObjectTypeId    = $ot.id
                ObjectTypeName  = $ot.name
                AttrId          = $a.id
                AttrName        = $a.name
                AttrType        = $a.type
                AttrDefaultType = $a.defaultType
            })
        }
    }
    $schemaAttrCache[$SchemaId] = $idx
    return $idx
}

# ----------------------------------------------------------------------------
# Load + filter the input CSV
# ----------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $InputCsv)) { throw "Input CSV not found: $InputCsv" }
$inRows = Import-Csv -LiteralPath $InputCsv

# qualifying rows = AttributeType == TypeFilter (case-insensitive, trimmed), de-duplicated
$seen      = New-Object 'System.Collections.Generic.HashSet[string]'
$qualified = New-Object System.Collections.Generic.List[object]
$skipped   = 0
foreach ($r in $inRows) {
    $sid  = "$($r.SchemaId)".Trim()
    $name = "$($r.AttributeName)".Trim()
    $type = "$($r.AttributeType)".Trim()
    if ($type -ine $TypeFilter) { $skipped++; continue }
    if (-not $sid -or -not $name) { Write-Warning "Row with empty SchemaId/AttributeName skipped."; continue }
    $key = "$sid|$name"
    if ($seen.Add($key)) {
        $qualified.Add([pscustomobject]@{ SchemaId = $sid; AttributeName = $name })
    }
}

Write-Host ("Input rows: {0} | qualifying ({1}): {2} | skipped (other types): {3}" -f `
    $inRows.Count, $TypeFilter, $qualified.Count, $skipped) -ForegroundColor Cyan
if ($qualified.Count -eq 0) { Write-Warning "Nothing to do."; return }

# ----------------------------------------------------------------------------
# Process each qualifying (schema, attribute)
# ----------------------------------------------------------------------------
$rows = New-Object System.Collections.Generic.List[object]
$summary = New-Object System.Collections.Generic.List[object]

foreach ($q in $qualified) {
    Write-Host "Schema $($q.SchemaId) :: '$($q.AttributeName)'" -ForegroundColor Cyan

    $index = Get-SchemaAttributeIndex -SchemaId $q.SchemaId
    $matches = @($index | Where-Object { $_.AttrName -eq $q.AttributeName })
    if ($matches.Count -eq 0) {
        Write-Warning "  Attribute not found on any object type - skipping."
        $summary.Add([pscustomobject]@{ SchemaId=$q.SchemaId; Attribute=$q.AttributeName; AttrIds=0; Values=0 })
        continue
    }

    foreach ($m in $matches) {
        if ($m.AttrType -ne $ExpectedJiraAttrType) {
            Write-Warning "  '$($m.AttrName)' on '$($m.ObjectTypeName)' has Jira type $($m.AttrType) (expected $ExpectedJiraAttrType for Confluence). Verify input."
        }
    }

    $attrIdSet = New-Object 'System.Collections.Generic.HashSet[string]'
    $metaMap   = @{}
    foreach ($m in $matches) {
        [void]$attrIdSet.Add([string]$m.AttrId)
        $metaMap[[string]$m.AttrId] = $m
    }

    $ql    = '"' + $q.AttributeName + '" is not Empty'
    $qlEnc = [uri]::EscapeDataString($ql)
    $page  = 1
    $count = 0

    do {
        $rel  = "aql/objects?objectSchemaId=$($q.SchemaId)&qlQuery=$qlEnc&includeAttributes=true&page=$page&resultPerPage=$ResultPerPage"
        $resp    = Invoke-AssetsApi $rel
        $entries = @($resp.objectEntries)

        foreach ($o in $entries) {
            foreach ($a in $o.attributes) {
                if (-not $attrIdSet.Contains([string]$a.objectTypeAttributeId)) { continue }
                $meta = $metaMap[[string]$a.objectTypeAttributeId]

                foreach ($v in @($a.objectAttributeValues)) {
                    $vProps = $v.PSObject.Properties.Name

                    if ($InspectFirst) {
                        Write-Host "`n=== First matching value (schema $($q.SchemaId), object $($o.objectKey)) ===" -ForegroundColor Yellow
                        $v | ConvertTo-Json -Depth 12 | Write-Host
                        Write-Host "`nConfirm which field holds the Confluence PAGE ID, then re-run without -InspectFirst." -ForegroundColor Yellow
                        return
                    }

                    $confUri = $null; $confName = $null; $flat = $null
                    if ($vProps -contains 'value') { $flat = $v.value }
                    if ($vProps -contains 'confluenceTypeValue' -and $v.confluenceTypeValue) {
                        $confUri  = $v.confluenceTypeValue.uri
                        $confName = $v.confluenceTypeValue.name
                    }
                    if (-not $flat -and ($vProps -contains 'displayValue')) { $flat = $v.displayValue }

                    $rows.Add([pscustomobject]@{
                        SchemaId            = $q.SchemaId
                        SourceAttributeName = $q.AttributeName
                        ObjectId            = $o.id
                        ObjectKey           = $o.objectKey
                        Label               = $o.label
                        ObjectTypeId        = $meta.ObjectTypeId
                        ObjectTypeName      = $meta.ObjectTypeName
                        AttrId              = $meta.AttrId
                        AttrName            = $meta.AttrName
                        AttrType            = $meta.AttrType
                        AttrDefaultType     = $meta.AttrDefaultType
                        ConfluenceUri       = $confUri
                        ConfluenceName      = $confName
                        FlatValue           = $flat
                        RawValueJson        = ($v | ConvertTo-Json -Depth 12 -Compress)
                    })
                    $count++
                }
            }
        }
        $page++
    } while ($entries.Count -eq $ResultPerPage)

    Write-Host "  -> $count value(s)" -ForegroundColor Green
    $summary.Add([pscustomobject]@{ SchemaId=$q.SchemaId; Attribute=$q.AttributeName; AttrIds=$attrIdSet.Count; Values=$count })
}

# ----------------------------------------------------------------------------
# Output
# ----------------------------------------------------------------------------
Write-Host "`nSummary:" -ForegroundColor Cyan
$summary | Format-Table -AutoSize | Out-String | Write-Host

if ($rows.Count -eq 0) {
    Write-Warning "No values found across all qualifying attributes."
    return
}

$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
Write-Host "Wrote $($rows.Count) value row(s) to $OutCsv" -ForegroundColor Green
