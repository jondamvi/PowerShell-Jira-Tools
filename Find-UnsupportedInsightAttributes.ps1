<#
.SYNOPSIS
    Audits Jira DC Insight/Assets for attribute types NOT supported in JSM Cloud Assets,
    and reports which of them have existing object data (i.e. will actually break migration).

.DESCRIPTION
    Workflow:
      1. GET /rest/insight/1.0/objectschema/list           -> all schemas (CMDB, ITSM, custom...)
      2. GET /rest/insight/1.0/objectschema/{id}/attributes -> attribute definitions incl. type
      3. Filter attributes whose type is not supported in Cloud:
            3 = Confluence, 5 = Version, 6 = Project   (legacy types, no Cloud equivalent)
      4. For each flagged attribute, IQL count of objects where the attribute is not empty:
            GET /rest/insight/1.0/iql/objects?objectSchemaId=..&iql=objectTypeId = X AND "Attr" is not empty
         -> totalFilterCount = number of objects with data in that attribute.

    Output: CSV + console table. ObjectsWithData > 0 means real migration impact.

.NOTES
    PowerShell 5.1 compatible. Auth: PAT (Bearer) preferred, Basic fallback.
    AQL/IQL cannot filter by attribute TYPE (schema metadata) - hence the REST+IQL hybrid.

.EXAMPLE
    .\Find-UnsupportedInsightAttributes.ps1 -BaseUrl "https://jira.company.com" -PersonalAccessToken "xxxx"

.EXAMPLE
    .\Find-UnsupportedInsightAttributes.ps1 -BaseUrl "https://jira.company.com" -Credential (Get-Credential) -SchemaNameFilter "CMDB","IT Service Management"
#>
[CmdletBinding(DefaultParameterSetName = 'PAT')]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true, ParameterSetName = 'PAT')]
    [string]$PersonalAccessToken,

    [Parameter(Mandatory = $true, ParameterSetName = 'Basic')]
    [System.Management.Automation.PSCredential]$Credential,

    # Optional: limit to specific schema names (exact match, case-insensitive). Default = all schemas.
    [string[]]$SchemaNameFilter,

    # Also flag Default-type "Time" attributes (defaultType.id = 5) for manual verification
    [switch]$IncludeTimeType,

    # Export per-object detail: every affected object + the actual referenced values
    # (which Confluence page / Jira project / version each object points at)
    [switch]$RemediationExport,

    # Safety cap when paginating objects per flagged attribute
    [int]$MaxObjectsPerAttribute = 5000,

    [string]$OutputCsv = ".\UnsupportedAssetAttributes_$(Get-Date -Format 'yyyyMMdd_HHmmss').csv",
    [string]$DetailCsv = ".\UnsupportedAssetAttributes_AffectedObjects_$(Get-Date -Format 'yyyyMMdd_HHmmss').csv"
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$BaseUrl = $BaseUrl.TrimEnd('/')

# --- Auth header -------------------------------------------------------------
if ($PSCmdlet.ParameterSetName -eq 'PAT') {
    $authHeader = @{ Authorization = "Bearer $PersonalAccessToken" }
}
else {
    $pair  = '{0}:{1}' -f $Credential.UserName, $Credential.GetNetworkCredential().Password
    $b64   = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
    $authHeader = @{ Authorization = "Basic $b64" }
}
$headers = $authHeader + @{ Accept = 'application/json' }

function Invoke-InsightApi {
    param([string]$Path)
    $uri = "$BaseUrl$Path"
    try {
        Invoke-RestMethod -Uri $uri -Headers $headers -Method Get -UseBasicParsing
    }
    catch {
        Write-Warning "API call failed: $uri"
        Write-Warning $_.Exception.Message
        throw
    }
}

# --- Helpers (must be defined before first API call) --------------------------
# StrictMode-safe property access
function Get-Prop {
    param($Object, [string]$Name, $Default = $null)
    if ($null -ne $Object -and $Object.PSObject.Properties[$Name]) { return $Object.$Name }
    return $Default
}

# Force a response into a flat one-item-per-element array, regardless of how
# Invoke-RestMethod shaped it (single object, Object[], nested arrays, or a
# wrapper object). Prevents member-enumeration bugs in downstream pipelines.
function ConvertTo-FlatArray {
    param($InputObject)
    $out = New-Object System.Collections.Generic.List[object]
    if ($null -eq $InputObject) { return ,@() }
    $stack = New-Object System.Collections.Stack
    $stack.Push($InputObject)
    while ($stack.Count -gt 0) {
        $item = $stack.Pop()
        if ($null -eq $item) { continue }
        if ($item -is [System.Collections.IEnumerable] -and $item -isnot [string] -and $item -isnot [System.Collections.IDictionary]) {
            # Push in reverse to preserve order
            $tmp = @($item)
            for ($i = $tmp.Count - 1; $i -ge 0; $i--) { $stack.Push($tmp[$i]) }
        }
        else {
            $out.Add($item)
        }
    }
    return ,$out.ToArray()
}

# Flatten a single objectAttributeValue entry into display + raw strings
function Convert-AttrValue {
    param($v)
    $display = Get-Prop $v 'displayValue'
    $raw     = Get-Prop $v 'value'
    $search  = Get-Prop $v 'searchValue'
    $addl    = Get-Prop $v 'additionalValue'

    # Referenced-object style values (defensive; mainly for type=1, harmless otherwise)
    $refObj = Get-Prop $v 'referencedObject'
    if ($refObj) {
        $display = '{0} ({1})' -f (Get-Prop $refObj 'name' $display), (Get-Prop $refObj 'objectKey' '')
    }

    [PSCustomObject]@{
        Display    = if ($display) { [string]$display } elseif ($raw) { [string]$raw } else { '' }
        Raw        = if ($null -ne $raw) { [string]$raw } else { '' }
        Search     = if ($null -ne $search) { [string]$search } else { '' }
        Additional = if ($null -ne $addl) { [string]$addl } else { '' }
    }
}

# --- Type maps ---------------------------------------------------------------
# Insight DC attribute 'type' enum
$typeNames = @{
    0 = 'Default'; 1 = 'Object'; 2 = 'User'; 3 = 'Confluence'
    4 = 'Group';   5 = 'Version'; 6 = 'Project'; 7 = 'Status'
}
# Not supported in JSM Cloud Assets (legacy types)
$unsupportedTypes = @(3, 5, 6)

# Default sub-types (for the optional Time check)
$defaultTypeNames = @{
    0='Text';1='Integer';2='Boolean';3='Double';4='Date';5='Time'
    6='DateTime';7='URL';8='Email';9='Textarea';10='Select';11='IP Address'
}

# --- 1. Enumerate schemas ----------------------------------------------------
Write-Host "Fetching object schemas from $BaseUrl ..." -ForegroundColor Cyan
$schemaList = Invoke-InsightApi '/rest/insight/1.0/objectschema/list'
$schemas = ConvertTo-FlatArray (Get-Prop $schemaList 'objectschemas')

if ($SchemaNameFilter) {
    $schemas = @($schemas | Where-Object {
        $name = $_.name
        ($SchemaNameFilter | Where-Object { $_ -ieq $name }).Count -gt 0
    })
}
Write-Host ("Schemas to scan: {0}" -f (($schemas | ForEach-Object { $_.name }) -join ', '))

$results = New-Object System.Collections.Generic.List[object]
$detailResults = New-Object System.Collections.Generic.List[object]

foreach ($schema in $schemas) {
    Write-Host ("`n=== Schema [{0}] {1} (key: {2}) ===" -f $schema.id, $schema.name, $schema.objectSchemaKey) -ForegroundColor Yellow

    # --- 2. All attribute definitions in this schema -------------------------
    $attrResponse = Invoke-InsightApi ("/rest/insight/1.0/objectschema/{0}/attributes" -f $schema.id)

    # Some versions wrap the list; unwrap if so, then flatten to a clean array
    if ($attrResponse -isnot [System.Collections.IEnumerable] -or $attrResponse -is [string]) {
        $wrapped = Get-Prop $attrResponse 'objectTypeAttributes'
        if ($null -ne $wrapped) { $attrResponse = $wrapped }
    }
    $attributes = ConvertTo-FlatArray $attrResponse
    Write-Host ("  Attribute definitions found: {0}" -f $attributes.Count)

    # --- 3. Filter unsupported types ------------------------------------------
    # Explicit foreach + TryParse instead of pipeline Where-Object: immune to
    # member enumeration and to 'type' arriving as string/missing.
    $flagged = New-Object System.Collections.Generic.List[object]
    foreach ($a in $attributes) {
        if ($null -eq $a) { continue }
        $rawType = Get-Prop $a 'type'
        $tInt = 0
        if ($null -eq $rawType -or -not [int]::TryParse([string]$rawType, [ref]$tInt)) {
            Write-Verbose ("  Skipping attribute '{0}' - unparsable type value '{1}'" -f (Get-Prop $a 'name' '?'), $rawType)
            continue
        }
        $isUnsupported = $unsupportedTypes -contains $tInt
        $isTimeType = $false
        if ($IncludeTimeType -and $tInt -eq 0) {
            $dt = Get-Prop $a 'defaultType'
            $dtId = 0
            if ($null -ne $dt -and [int]::TryParse([string](Get-Prop $dt 'id' ''), [ref]$dtId) -and $dtId -eq 5) {
                $isTimeType = $true
            }
        }
        if ($isUnsupported -or $isTimeType) { $flagged.Add($a) }
    }

    if ($flagged.Count -eq 0) {
        Write-Host "  No unsupported attribute types found." -ForegroundColor Green
        continue
    }
    Write-Host ("  Flagged attribute definitions: {0}" -f $flagged.Count) -ForegroundColor Magenta

    foreach ($attr in $flagged) {
        $typeId   = [int](Get-Prop $attr 'type' -1)
        $typeName = if ($typeNames.ContainsKey($typeId)) { $typeNames[$typeId] } else { "Unknown($typeId)" }
        if ($typeId -eq 0) {
            $dtId = [int](Get-Prop (Get-Prop $attr 'defaultType') 'id' -1)
            $typeName = 'Default/' + $(if ($defaultTypeNames.ContainsKey($dtId)) { $defaultTypeNames[$dtId] } else { "Unknown($dtId)" })
        }

        # objectType *should* be in the per-schema attributes payload, but some
        # Insight versions omit it there. Fall back to the single-attribute
        # endpoint, which always includes it.
        $ot = Get-Prop $attr 'objectType'
        if ($null -eq $ot) {
            try {
                $attrFull = Invoke-InsightApi ("/rest/insight/1.0/objecttypeattribute/{0}" -f (Get-Prop $attr 'id'))
                $ot = Get-Prop $attrFull 'objectType'
            }
            catch {
                Write-Warning ("  Could not resolve objectType for attribute '{0}' (id {1}): {2}" -f (Get-Prop $attr 'name' '?'), (Get-Prop $attr 'id' '?'), $_.Exception.Message)
            }
        }
        $otId   = Get-Prop $ot 'id'
        $otName = Get-Prop $ot 'name' '?'
        if ($null -eq $otId) {
            # Keep it in the report rather than dropping it silently
            $results.Add([PSCustomObject]@{
                SchemaId        = $schema.id
                SchemaName      = $schema.name
                SchemaKey       = $schema.objectSchemaKey
                ObjectTypeId    = ''
                ObjectTypeName  = 'UNRESOLVED'
                AttributeId     = Get-Prop $attr 'id' ''
                AttributeName   = Get-Prop $attr 'name' '?'
                AttributeType   = $typeName
                TypeId          = $typeId
                ObjectsWithData = -1
                MigrationImpact = 'UNKNOWN - objectType unresolved, verify manually'
                IqlUsed         = ''
                Error           = 'objectType missing in schema payload and fallback lookup failed'
            })
            continue
        }

        # --- 4. IQL: does this attribute hold data on any object? ------------
        # Use objectTypeId to avoid name-quoting issues; attribute name still needs quotes.
        $attrNameEscaped = $attr.name -replace '"', '\"'
        $iql = 'objectTypeId = {0} AND "{1}" is not empty' -f $otId, $attrNameEscaped
        $iqlEncoded = [Uri]::EscapeDataString($iql)

        $countPath = "/rest/insight/1.0/iql/objects?objectSchemaId=$($schema.id)&iql=$iqlEncoded&resultPerPage=1&includeAttributes=false"
        $objectsWithData = 0
        $iqlError = ''
        try {
            $iqlResult = Invoke-InsightApi $countPath
            $objectsWithData = [int]$iqlResult.totalFilterCount
        }
        catch {
            $iqlError = $_.Exception.Message
        }

        # --- 5. Remediation detail: affected objects + referenced values ------
        if ($RemediationExport -and $objectsWithData -gt 0) {
            $perPage = 100
            $page = 1
            $fetched = 0
            $totalPages = 1
            while ($page -le $totalPages -and $fetched -lt $MaxObjectsPerAttribute) {
                $detailPath = "/rest/insight/1.0/iql/objects?objectSchemaId=$($schema.id)&iql=$iqlEncoded" +
                              "&page=$page&resultPerPage=$perPage&includeAttributes=true"
                try {
                    $pageResult = Invoke-InsightApi $detailPath
                }
                catch {
                    Write-Warning ("    Detail fetch failed for '{0}' page {1}: {2}" -f $attr.name, $page, $_.Exception.Message)
                    break
                }
                $totalPages = [int](Get-Prop $pageResult 'pageSize' 1)
                $entries = ConvertTo-FlatArray (Get-Prop $pageResult 'objectEntries')

                foreach ($obj in $entries) {
                    $fetched++
                    # Pick only the flagged attribute off this object
                    $objAttrs = ConvertTo-FlatArray (Get-Prop $obj 'attributes')
                    $values = @()
                    foreach ($m in $objAttrs) {
                        if ([string](Get-Prop $m 'objectTypeAttributeId' '') -ne [string]$attr.id) { continue }
                        foreach ($v in (ConvertTo-FlatArray (Get-Prop $m 'objectAttributeValues'))) {
                            $values += Convert-AttrValue $v
                        }
                    }
                    if ($values.Count -eq 0) {
                        # Shouldn't happen given the IQL, but keep the object visible anyway
                        $values = @([PSCustomObject]@{ Display=''; Raw=''; Search=''; Additional='' })
                    }
                    foreach ($val in $values) {
                        $detailResults.Add([PSCustomObject]@{
                            SchemaName      = $schema.name
                            SchemaKey       = $schema.objectSchemaKey
                            ObjectTypeName  = $otName
                            ObjectId        = $obj.id
                            ObjectKey       = Get-Prop $obj 'objectKey' ''
                            ObjectName      = Get-Prop $obj 'label' (Get-Prop $obj 'name' '')
                            ObjectUrl       = "$BaseUrl/secure/insight/assets/$(Get-Prop $obj 'objectKey' '')"
                            AttributeId     = $attr.id
                            AttributeName   = $attr.name
                            AttributeType   = $typeName
                            ReferencedValue = $val.Display
                            RawValue        = $val.Raw
                            SearchValue     = $val.Search
                            AdditionalValue = $val.Additional
                        })
                    }
                }
                if ($entries.Count -eq 0) { break }
                $page++
            }
            if ($fetched -ge $MaxObjectsPerAttribute) {
                Write-Warning ("    Hit MaxObjectsPerAttribute ({0}) for '{1}' - detail export truncated." -f $MaxObjectsPerAttribute, $attr.name)
            }
        }

        $row = [PSCustomObject]@{
            SchemaId        = $schema.id
            SchemaName      = $schema.name
            SchemaKey       = $schema.objectSchemaKey
            ObjectTypeId    = $otId
            ObjectTypeName  = $otName
            AttributeId     = $attr.id
            AttributeName   = $attr.name
            AttributeType   = $typeName
            TypeId          = $typeId
            ObjectsWithData = $objectsWithData
            MigrationImpact = if ($objectsWithData -gt 0) { 'YES - data present' } else { 'Definition only' }
            IqlUsed         = $iql
            Error           = $iqlError
        }
        $results.Add($row)

        $color = if ($objectsWithData -gt 0) { 'Red' } else { 'Gray' }
        Write-Host ("    [{0}] ObjectType='{1}' | Attribute='{2}' (id {3}) -> {4} object(s) with data" -f $typeName, $otName, $attr.name, $attr.id, $objectsWithData) -ForegroundColor $color
    }
}

# --- Output ------------------------------------------------------------------
Write-Host "`n========== SUMMARY ==========" -ForegroundColor Cyan
if ($results.Count -eq 0) {
    Write-Host "No unsupported attribute types found in any scanned schema." -ForegroundColor Green
}
else {
    $results | Sort-Object -Property ObjectsWithData -Descending |
        Format-Table SchemaName, ObjectTypeName, AttributeName, AttributeId, AttributeType, ObjectsWithData, MigrationImpact -AutoSize

    $results | Export-Csv -Path $OutputCsv -NoTypeInformation -Encoding UTF8
    Write-Host ("Full report written to: {0}" -f (Resolve-Path $OutputCsv)) -ForegroundColor Cyan

    if ($RemediationExport -and $detailResults.Count -gt 0) {
        $detailResults | Export-Csv -Path $DetailCsv -NoTypeInformation -Encoding UTF8
        Write-Host ("Affected-objects detail ({0} rows) written to: {1}" -f $detailResults.Count, (Resolve-Path $DetailCsv)) -ForegroundColor Cyan
    }

    $withData = @($results | Where-Object { $_.ObjectsWithData -gt 0 })
    Write-Host ("`nAttributes with REAL data (must be remediated before migration): {0}" -f $withData.Count) -ForegroundColor $(if ($withData.Count) {'Red'} else {'Green'})
}
