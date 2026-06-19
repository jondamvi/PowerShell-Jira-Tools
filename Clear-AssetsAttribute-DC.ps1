<#
.SYNOPSIS
    Pre-migration: null out (clear) a single Assets attribute's value on ONE Jira DC object,
    so the unsupported Confluence attribute stops blocking JCMA. The attribute DEFINITION is kept
    on the schema - only the value on this object is cleared.

.DESCRIPTION
    SAFE BY DEFAULT: runs as a dry-run unless -Execute is given. It always backs up the full
    object (object + attributes) BEFORE, and - when -Execute is used - again AFTER, into separate
    .txt files for manual comparison. It also prints the object's web-view URL so you can verify
    in the UI that the attribute no longer shows a value.

    Clearing mechanism: PUT /object/{id} with the target attribute's objectAttributeValues = [].
    The Assets object update is a partial update - attributes you don't send are left untouched.
    The BEFORE/AFTER backups let you confirm nothing else changed.

    Backups (in -BackupDir):
        <ObjectKey>_BEFORE_object.json.txt
        <ObjectKey>_BEFORE_attributes.json.txt
        <ObjectKey>_AFTER_object.json.txt        (only with -Execute)
        <ObjectKey>_AFTER_attributes.json.txt    (only with -Execute)

.PARAMETER BaseUrl     Jira DC base URL, no trailing slash. e.g. https://jirasite
.PARAMETER Pat         DC Personal Access Token (Bearer). Falls back to -UseDefaultCredentials.
.PARAMETER Object      Object id (numeric) OR object key (e.g. CMDB-3493).
.PARAMETER AttrId      Target objectTypeAttributeId. Provide this OR -AttributeName.
.PARAMETER AttributeName  Target attribute name (resolved against this object's attributes).
.PARAMETER ApiBase     REST base path. Default "rest/assets/1.0".
.PARAMETER BackupDir   Backup dir. Default .\null_backup
.PARAMETER Execute     Actually perform the clear. Omit for dry-run.

.EXAMPLE
    # dry run
    .\Clear-AssetsAttribute-DC.ps1 -BaseUrl https://jirasite -Pat $env:JIRA_PAT `
        -Object CMDB-3493 -AttributeName "Linked Confluence Page"
    # execute
    .\Clear-AssetsAttribute-DC.ps1 -BaseUrl https://jirasite -Pat $env:JIRA_PAT `
        -Object CMDB-3493 -AttributeName "Linked Confluence Page" -Execute
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [string]$Pat,
    [Parameter(Mandatory)][string]$Object,
    [string]$AttrId,
    [string]$AttributeName,
    [string]$ApiBase = 'rest/assets/1.0',
    [string]$BackupDir = '.\null_backup',
    [switch]$Execute
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$BaseUrl = $BaseUrl.TrimEnd('/')

$headers = @{ Accept = 'application/json' }
$reqArgs = @{ Headers = $headers; ContentType = 'application/json'; ErrorAction = 'Stop' }
if ($Pat) { $headers['Authorization'] = "Bearer $Pat" }
else { Write-Warning 'No -Pat; using -UseDefaultCredentials.'; $reqArgs['UseDefaultCredentials'] = $true }

function Api { param([string]$Method, [string]$Rel, $Body)
    $uri = "$BaseUrl/$ApiBase/$Rel"
    $a = @{ Uri = $uri; Method = $Method } + $reqArgs
    if ($null -ne $Body) { $a['Body'] = ($Body | ConvertTo-Json -Depth 20) }
    try { return Invoke-RestMethod @a } catch { throw "$Method $uri failed: $($_.Exception.Message)" }
}

if (-not $AttrId -and -not $AttributeName) { throw 'Provide -AttrId or -AttributeName.' }
if (-not (Test-Path -LiteralPath $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

# --- resolve object id (accept id or key) ---
if ($Object -match '^\d+$') { $objectId = $Object }
else {
    Write-Host "Resolving object key '$Object' ..." -ForegroundColor Cyan
    $qlEnc = [uri]::EscapeDataString('Key = "' + $Object + '"')
    $look = Api GET "aql/objects?qlQuery=$qlEnc&resultPerPage=2&includeAttributes=false"
    $hits = @($look.objectEntries)
    if ($hits.Count -eq 0) { throw "Object key '$Object' not found." }
    if ($hits.Count -gt 1) { throw "Object key '$Object' is ambiguous." }
    $objectId = $hits[0].id
}

# --- fetch object + attributes ---
$obj  = Api GET "object/$objectId"
$attrs = Api GET "object/$objectId/attributes"
$objectKey = $obj.objectKey
$objectTypeId = $obj.objectType.id
$safeKey = ($objectKey -replace '[^\w\.\-]', '_')

($obj   | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "$($safeKey)_BEFORE_object.json.txt") -Encoding UTF8
($attrs | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "$($safeKey)_BEFORE_attributes.json.txt") -Encoding UTF8

# --- resolve attribute id (by name) against this object's attributes ---
if (-not $AttrId) {
    $defs = Api GET "objecttype/$objectTypeId/attributes"
    $match = @($defs | Where-Object { $_.name -eq $AttributeName })
    if ($match.Count -eq 0) { throw "Attribute '$AttributeName' not found on object type $objectTypeId." }
    if ($match.Count -gt 1) { throw "Attribute '$AttributeName' is ambiguous on object type $objectTypeId." }
    $AttrId = $match[0].id
}

# current value (for console context)
$cur = @($attrs | Where-Object { [string]$_.objectTypeAttributeId -eq [string]$AttrId })
$curVal = if ($cur.Count) { ($cur[0].objectAttributeValues | ConvertTo-Json -Depth 12 -Compress) } else { '(none)' }

# DC Assets object web-view links (path differs by version - both printed)
$viewA = "$BaseUrl/secure/insight/assets/$objectKey"
$viewB = "$BaseUrl/secure/ShowObject.jspa?id=$objectId"

$body = @{ objectTypeId = $objectTypeId; attributes = @(@{ objectTypeAttributeId = [int]$AttrId; objectAttributeValues = @() }) }

Write-Host "`n========== PLAN ==========" -ForegroundColor Cyan
Write-Host ("Object        : {0} (id {1}), objectType {2}" -f $objectKey, $objectId, $objectTypeId)
Write-Host ("Attribute     : id {0}{1}" -f $AttrId, $(if ($AttributeName) { " ('$AttributeName')" } else { '' }))
Write-Host ("Current value : {0}" -f $curVal)
Write-Host  "Action        : set objectAttributeValues = []  (clear value, keep attribute on schema)"
Write-Host  "PUT body      :"
Write-Host  ($body | ConvertTo-Json -Depth 20)

if (-not $Execute) {
    Write-Host "`nDRY RUN - no change made. Re-run with -Execute to apply." -ForegroundColor Yellow
    Write-Host ("Verify after execute at: {0}" -f $viewA) -ForegroundColor Yellow
    Write-Host ("                    or : {0}" -f $viewB) -ForegroundColor Yellow
    return
}

Write-Host "`nExecuting clear ..." -ForegroundColor Cyan
$null = Api PUT "object/$objectId" $body

# --- re-export AFTER ---
$objA  = Api GET "object/$objectId"
$attrsA = Api GET "object/$objectId/attributes"
($objA   | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "$($safeKey)_AFTER_object.json.txt") -Encoding UTF8
($attrsA | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "$($safeKey)_AFTER_attributes.json.txt") -Encoding UTF8

$curA = @($attrsA | Where-Object { [string]$_.objectTypeAttributeId -eq [string]$AttrId })
$nowVal = if ($curA.Count) { ($curA[0].objectAttributeValues | ConvertTo-Json -Depth 12 -Compress) } else { '(attribute absent from values = cleared)' }

Write-Host "`nDONE." -ForegroundColor Green
Write-Host ("Value now     : {0}" -f $nowVal)
Write-Host ("BEFORE/AFTER backups in: {0}" -f $BackupDir)
Write-Host ("Verify in web view: {0}" -f $viewA) -ForegroundColor Yellow
Write-Host ("              or  : {0}" -f $viewB) -ForegroundColor Yellow
