<#
.SYNOPSIS
    Audits Insight/Assets object type attributes (flagged as unsupported in Cloud)
    and the assets that use them, on a Jira Data Center instance.

.DESCRIPTION
    For each ObjectTypeAttributeId provided:
      1. GET /rest/insight/1.0/objecttypeattribute/<id>
         - dumps the full raw JSON to  ObjectTypeAttribute_<id>.txt
         - collects Name, typeValue, objectType.objectSchemaId
      2. GET /rest/assets/1.0/aql/objects?objectSchemaId=<schemaId>&qlQuery="<Name>" is not Empty
         (paged through to the end so nothing is missed)
         - dumps the full raw JSON to  ASSETS_withAttribute_<id>.txt
         - collects one row per object entry

    Then writes two roll-up CSVs:
      - UnsupportedObjectTypeAttributesInCloud.csv
      - AssetsOfUnsupportedObjectTypeAttributesInCloud.csv

.NOTES
    Targets Windows PowerShell 5.1. Raw JSON is written UTF-8 (no BOM) and decoded
    from the response bytes as UTF-8 to avoid mangling non-ASCII attribute names.

.EXAMPLE
    .\Export-UnsupportedInsightAttributes.ps1 `
        -BaseUrl "https://jira.example.com" `
        -ObjectTypeAttributeId 1024,1037,1198 `
        -PatToken "NjAxMzI...your_PAT..." `
        -ExportDirectory "C:\Migration\InsightAudit"

.EXAMPLE
    # MFA / session-cookie auth instead of a PAT:
    .\Export-UnsupportedInsightAttributes.ps1 `
        -BaseUrl "https://jira.example.com" `
        -ObjectTypeAttributeId (Get-Content .\ids.txt) `
        -ExtraHeaders @{ Cookie = 'JSESSIONID=...; atlassian.xsrf.token=...' }
#>

#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$BaseUrl,

    [Parameter(Mandatory)]
    [int[]]$ObjectTypeAttributeId,

    [Parameter()]
    [string]$ExportDirectory = (Join-Path (Get-Location).Path 'InsightExport'),

    # Jira DC Personal Access Token (sent as: Authorization: Bearer <token>)
    [Parameter()]
    [string]$PatToken,

    # Basic-auth fallback (some DC instances). PAT is preferred.
    [Parameter()]
    [System.Management.Automation.PSCredential]$Credential,

    # Anything extra you need on every request, e.g. a session Cookie header.
    [Parameter()]
    [hashtable]$ExtraHeaders = @{},

    # Assets AQL page size. The script pages until the result set is exhausted.
    [Parameter()]
    [int]$ResultsPerPage = 100,

    [Parameter()]
    [switch]$SkipCertificateCheck
)

# ----------------------------------------------------------------------------
# Setup
# ----------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

# TLS 1.2 (older DC fronting / proxies)
[System.Net.ServicePointManager]::SecurityProtocol = `
    [System.Net.SecurityProtocolType]::Tls12 -bor [System.Net.ServicePointManager]::SecurityProtocol

if ($SkipCertificateCheck) {
    # PS 5.1 has no -SkipCertificateCheck switch, so override the validation callback.
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { param($s,$c,$ch,$e) $true }
}

# Build the common request headers
$script:Headers = @{ Accept = 'application/json' }
if ($PatToken) { $script:Headers['Authorization'] = "Bearer $PatToken" }
foreach ($k in $ExtraHeaders.Keys) { $script:Headers[$k] = $ExtraHeaders[$k] }

if (-not $PatToken -and -not $Credential -and -not $ExtraHeaders.ContainsKey('Authorization') -and -not $ExtraHeaders.ContainsKey('Cookie')) {
    Write-Warning 'No PAT, credential, or auth header supplied - requests will be anonymous and may 401.'
}

# Make sure the export directory exists
if (-not (Test-Path -LiteralPath $ExportDirectory)) {
    New-Item -ItemType Directory -Path $ExportDirectory -Force | Out-Null
}
Write-Host "Export directory: $ExportDirectory" -ForegroundColor Cyan

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
function Invoke-InsightGet {
    <# GET a URL and return the raw response body as a UTF-8 string. #>
    param([Parameter(Mandatory)][string]$Url)

    $params = @{
        Uri             = $Url
        Headers         = $script:Headers
        Method          = 'Get'
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($Credential) { $params['Credential'] = $Credential }

    $resp = Invoke-WebRequest @params

    # Decode straight from the bytes as UTF-8 so non-ASCII names survive intact.
    try {
        if ($resp.RawContentStream -and $resp.RawContentStream.CanSeek) {
            $bytes = $resp.RawContentStream.ToArray()
            return [System.Text.Encoding]::UTF8.GetString($bytes)
        }
    } catch { }
    return $resp.Content
}

function Write-Utf8NoBom {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Content)
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $enc)
}

# ----------------------------------------------------------------------------
# Pass 1 + 2: per attribute
# ----------------------------------------------------------------------------
$otaRows    = New-Object System.Collections.Generic.List[object]
$assetRows  = New-Object System.Collections.Generic.List[object]
$maxPages   = 10000   # runaway guard

foreach ($id in $ObjectTypeAttributeId) {

    Write-Host "`n=== ObjectTypeAttribute $id ===" -ForegroundColor Yellow

    # --- 1) objecttypeattribute -------------------------------------------------
    $otaUrl = '{0}/rest/insight/1.0/objecttypeattribute/{1}' -f $BaseUrl, $id
    try {
        $otaRaw = Invoke-InsightGet -Url $otaUrl
    } catch {
        Write-Warning "  Failed to fetch attribute $id : $($_.Exception.Message)"
        continue
    }

    Write-Utf8NoBom -Path (Join-Path $ExportDirectory "ObjectTypeAttribute_$id.txt") -Content $otaRaw

    $ota      = $otaRaw | ConvertFrom-Json
    $name     = $ota.name
    $schemaId = $ota.objectType.objectSchemaId

    $otaRows.Add([pscustomobject][ordered]@{
        ObjectTypeAttributeId = $(if ($null -ne $ota.id) { $ota.id } else { $id })
        Name                  = $name
        TypeValue             = $ota.typeValue
        ObjectSchemaId        = $schemaId
    })

    Write-Host ("  Name='{0}'  typeValue='{1}'  objectSchemaId='{2}'" -f $name, $ota.typeValue, $schemaId)

    if ([string]::IsNullOrWhiteSpace([string]$schemaId)) {
        Write-Warning "  No objectSchemaId for attribute $id - skipping the assets query."
        continue
    }
    if ([string]::IsNullOrWhiteSpace([string]$name)) {
        Write-Warning "  No name for attribute $id - skipping the assets query."
        continue
    }

    # --- 2) assets that use the attribute (paged) -------------------------------
    # AQL:  "<Name>" is not Empty   (name url-encoded, quotes kept literal)
    # If your instance is fussy about literal quotes, swap to fully-encoding the
    # whole AQL string with [System.Uri]::EscapeDataString('"' + $name + '" is not Empty').
    $encodedName = [System.Uri]::EscapeDataString($name)
    $qlQuery     = '"{0}"%20is%20not%20Empty' -f $encodedName

    $rawPages   = New-Object System.Collections.Generic.List[string]
    $entryCount = 0
    $page       = 1
    $more       = $true

    try {
        do {
            $assetsUrl = '{0}/rest/assets/1.0/aql/objects?objectSchemaId={1}&qlQuery={2}&page={3}&resultPerPage={4}' `
                            -f $BaseUrl, $schemaId, $qlQuery, $page, $ResultsPerPage

            $assetsRaw = Invoke-InsightGet -Url $assetsUrl
            $rawPages.Add($assetsRaw)

            $assetsObj = $assetsRaw | ConvertFrom-Json
            $entries   = @($assetsObj.objectEntries)

            foreach ($e in $entries) {
                $assetRows.Add([pscustomobject][ordered]@{
                    'SourceObjectTypeAttributeId' = $id
                    'SourceAttributeName'         = $name
                    'Object id'                   = $e.id
                    'label'                       = $e.label
                    'objectKey'                   = $e.objectKey
                    'Object type id'              = $e.objectType.id
                    'Object type name'            = $e.objectType.name
                    'Object Schema id'            = $e.objectType.objectSchemaId
                })
            }
            $entryCount += $entries.Count

            # Decide whether another page exists.
            if ($null -ne $assetsObj.pageSize) {
                $more = $page -lt [int]$assetsObj.pageSize
            } else {
                $more = ($entries.Count -ge $ResultsPerPage -and $entries.Count -gt 0)
            }
            $page++
        } while ($more -and $page -le $maxPages)
    } catch {
        Write-Warning "  Assets query failed for attribute $id : $($_.Exception.Message)"
    }

    # Full raw JSON: single page verbatim, or a JSON array of the page responses.
    if ($rawPages.Count -eq 1) {
        $assetsOut = $rawPages[0]
    } elseif ($rawPages.Count -gt 1) {
        $assetsOut = '[' + ($rawPages -join ',') + ']'
    } else {
        $assetsOut = ''
    }
    if ($assetsOut.Length -gt 0) {
        Write-Utf8NoBom -Path (Join-Path $ExportDirectory "ASSETS_withAttribute_$id.txt") -Content $assetsOut
    }

    Write-Host ("  Assets matched: {0} (across {1} page(s))" -f $entryCount, $rawPages.Count)
}

# ----------------------------------------------------------------------------
# Roll-up CSVs
# ----------------------------------------------------------------------------
$otaCsv    = Join-Path $ExportDirectory 'UnsupportedObjectTypeAttributesInCloud.csv'
$assetsCsv = Join-Path $ExportDirectory 'AssetsOfUnsupportedObjectTypeAttributesInCloud.csv'

if ($otaRows.Count -gt 0) {
    $otaRows | Export-Csv -Path $otaCsv -NoTypeInformation -Encoding UTF8
    Write-Host "`nWrote $($otaRows.Count) attribute row(s) -> $otaCsv" -ForegroundColor Green
} else {
    Write-Warning 'No attribute rows collected; CSV not written.'
}

if ($assetRows.Count -gt 0) {
    $assetRows | Export-Csv -Path $assetsCsv -NoTypeInformation -Encoding UTF8
    Write-Host "Wrote $($assetRows.Count) asset row(s) -> $assetsCsv" -ForegroundColor Green
} else {
    Write-Warning 'No asset rows collected; CSV not written.'
}

Write-Host "`nDone." -ForegroundColor Cyan
