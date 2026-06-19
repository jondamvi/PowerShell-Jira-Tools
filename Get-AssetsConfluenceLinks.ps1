<#
.SYNOPSIS
    Extracts the values of a Confluence-type (or any) Assets/Insight attribute from a
    Jira Data Center object schema, for migration backup and DC->Cloud page-ID remapping.

.DESCRIPTION
    Resolves the attribute NAME to its objectTypeAttributeId(s) across every object type in
    the schema, runs the AQL "<Attribute> is not Empty" query (paginated), and writes one CSV
    row per object/value with:
        ObjectId, ObjectKey, Label, ObjectTypeId, ObjectTypeName,
        AttrId, AttrName, AttrType, AttrDefaultType,
        ConfluenceUri, ConfluenceName, FlatValue, RawValueJson

    RawValueJson always contains the full, unmodified value object, so nothing is lost even
    if the Confluence page-ID lives in a field this script doesn't pre-extract. Run with
    -InspectFirst first to see the real value shape on YOUR instance/version, confirm which
    field holds the page ID, then do the full run.

.PARAMETER BaseUrl
    Jira DC base URL, no trailing slash. e.g. https://jirasite

.PARAMETER ObjectSchemaId
    Numeric object schema id (the <id> you already use in your AQL backup call).

.PARAMETER AttributeName
    Exact attribute display name, e.g. "Linked Confluence Page".

.PARAMETER Pat
    Personal Access Token (Jira DC 8.14+). Sent as Bearer. Strongly recommended over cookies.
    If omitted, the script falls back to -UseDefaultCredentials (Kerberos/NTLM on a domain box).

.PARAMETER ApiBase
    REST base path. Default "rest/assets/1.0". Switch to "rest/insight/1.0" on older builds.

.PARAMETER ResultPerPage
    AQL page size. Default 50.

.PARAMETER OutCsv
    Output CSV path. Default .\confluence_attr_values.csv

.PARAMETER InspectFirst
    Resolve the attribute, fetch ONE matching object, print its full attribute JSON, and exit.
    Use this to confirm the exact Confluence page-ID field before a full extraction run.

.EXAMPLE
    # 1) Inspect the real value shape first
    .\Get-AssetsConfluenceLinks.ps1 -BaseUrl https://jirasite -ObjectSchemaId 12 `
        -AttributeName "Linked Confluence Page" -Pat $env:JIRA_PAT -InspectFirst

.EXAMPLE
    # 2) Full extraction to CSV
    .\Get-AssetsConfluenceLinks.ps1 -BaseUrl https://jirasite -ObjectSchemaId 12 `
        -AttributeName "Linked Confluence Page" -Pat $env:JIRA_PAT -OutCsv .\confluence_links.csv
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$ObjectSchemaId,
    [Parameter(Mandatory)][string]$AttributeName,
    [string]$Pat,
    [string]$ApiBase = 'rest/assets/1.0',
    [int]$ResultPerPage = 50,
    [string]$OutCsv = '.\confluence_attr_values.csv',
    [switch]$InspectFirst
)

# --- PS 5.1: force TLS 1.2 so HTTPS to DC doesn't fail on older defaults ---
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

# ----------------------------------------------------------------------------
# 1) Resolve the attribute NAME -> objectTypeAttributeId(s) across the schema.
#    Same name can map to different ids per object type, so collect them all.
# ----------------------------------------------------------------------------
Write-Host "Resolving object types in schema $ObjectSchemaId ..." -ForegroundColor Cyan
$objectTypes = Invoke-AssetsApi "objectschema/$ObjectSchemaId/objecttypes/flat"

$attrMap = @{}  # key: objectTypeAttributeId (string) -> details
foreach ($ot in $objectTypes) {
    $attrs = Invoke-AssetsApi "objecttype/$($ot.id)/attributes"
    foreach ($a in $attrs) {
        if ($a.name -eq $AttributeName) {
            $attrMap[[string]$a.id] = [pscustomobject]@{
                ObjectTypeId    = $ot.id
                ObjectTypeName  = $ot.name
                AttrId          = $a.id
                AttrName        = $a.name
                AttrType        = $a.type          # integration type id (Confluence is one of these)
                AttrDefaultType = $a.defaultType    # only meaningful when type = Default
            }
        }
    }
}

if ($attrMap.Count -eq 0) {
    throw "Attribute '$AttributeName' not found on any object type in schema $ObjectSchemaId."
}

Write-Host "Resolved attribute '$AttributeName' to $($attrMap.Count) attribute id(s):" -ForegroundColor Green
$attrMap.Values | Format-Table ObjectTypeName, AttrId, AttrType, AttrDefaultType -AutoSize | Out-String | Write-Host

$attrIdSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($k in $attrMap.Keys) { [void]$attrIdSet.Add($k) }

# ----------------------------------------------------------------------------
# 2) Run AQL "<Attribute>" is not Empty, paginated, with attribute values.
# ----------------------------------------------------------------------------
$ql      = '"' + $AttributeName + '" is not Empty'
$qlEnc   = [uri]::EscapeDataString($ql)
$rows    = New-Object System.Collections.Generic.List[object]
$page    = 1

do {
    $rel  = "aql/objects?objectSchemaId=$ObjectSchemaId&qlQuery=$qlEnc&includeAttributes=true&page=$page&resultPerPage=$ResultPerPage"
    Write-Host "Fetching page $page ..." -ForegroundColor DarkGray
    $resp    = Invoke-AssetsApi $rel
    $entries = @($resp.objectEntries)

    foreach ($o in $entries) {
        foreach ($a in $o.attributes) {
            if (-not $attrIdSet.Contains([string]$a.objectTypeAttributeId)) { continue }
            $meta = $attrMap[[string]$a.objectTypeAttributeId]

            foreach ($v in @($a.objectAttributeValues)) {
                $vProps = $v.PSObject.Properties.Name

                # --- INSPECT MODE: dump the first real value object and stop ---
                if ($InspectFirst) {
                    Write-Host "`n=== First matching value (object $($o.objectKey)) ===" -ForegroundColor Yellow
                    $v | ConvertTo-Json -Depth 12 | Write-Host
                    Write-Host "`nConfirm which field holds the Confluence PAGE ID, then re-run without -InspectFirst." -ForegroundColor Yellow
                    return
                }

                # --- best-effort extraction (raw JSON below keeps everything) ---
                $confUri = $null; $confName = $null; $flat = $null
                if ($vProps -contains 'value') { $flat = $v.value }
                if ($vProps -contains 'confluenceTypeValue' -and $v.confluenceTypeValue) {
                    $confUri  = $v.confluenceTypeValue.uri
                    $confName = $v.confluenceTypeValue.name
                }
                if (-not $flat -and ($vProps -contains 'displayValue')) { $flat = $v.displayValue }

                $rows.Add([pscustomobject]@{
                    ObjectId        = $o.id
                    ObjectKey       = $o.objectKey
                    Label           = $o.label
                    ObjectTypeId    = $meta.ObjectTypeId
                    ObjectTypeName  = $meta.ObjectTypeName
                    AttrId          = $meta.AttrId
                    AttrName        = $meta.AttrName
                    AttrType        = $meta.AttrType
                    AttrDefaultType = $meta.AttrDefaultType
                    ConfluenceUri   = $confUri
                    ConfluenceName  = $confName
                    FlatValue       = $flat
                    RawValueJson    = ($v | ConvertTo-Json -Depth 12 -Compress)
                })
            }
        }
    }

    $page++
} while ($entries.Count -eq $ResultPerPage)

# ----------------------------------------------------------------------------
# 3) Output
# ----------------------------------------------------------------------------
if ($rows.Count -eq 0) {
    Write-Warning "No values found. Either the attribute is empty everywhere, or the value shape differs - try -InspectFirst."
    return
}

$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
Write-Host "Wrote $($rows.Count) value row(s) to $OutCsv" -ForegroundColor Green
