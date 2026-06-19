<#
.SYNOPSIS
    Post-migration (Jira Assets CLOUD): create a replacement URL/Text attribute on an object type,
    then set its value on ONE object to the migrated Confluence Cloud URL. Single-object test flow.

.DESCRIPTION
    Confluence is NOT involved here - this is pure Jira Assets Cloud. The Assets Cloud REST API is
    workspace-scoped (a Jira/JSM concept), so the script auto-discovers the workspace id from
    https://<site>.atlassian.net/rest/servicedeskapi/assets/workspace and then calls
    https://api.atlassian.com/jsm/assets/workspace/{workspaceId}/v1/...  using Basic auth.

    SAFE BY DEFAULT: dry-run unless -Execute. Staged, with console narration of each stage and
    how to verify in the Cloud web view. Backs up the object type and object before/after changes.

    Stages:
      0  Discover workspace id (unless -WorkspaceId given).
      1  BACKUP: object type attributes + the object (BEFORE).
      2  CREATE the replacement attribute on the object type (URL or Text). Captures its new id.
            Skipped if -ExistingAttrId is supplied.
      3  SET the value on the object to -Value. Re-export object (AFTER).

    Backups (in -BackupDir):
        Cloud_ObjectType<otId>_BEFORE_attributes.json.txt
        Cloud_Object<objId>_BEFORE.json.txt
        Cloud_ObjectType<otId>_AFTER_attributes.json.txt   (after create)
        Cloud_Object<objId>_AFTER.json.txt                 (after set)

.PARAMETER CloudBaseUrl   Jira Cloud base, e.g. https://yoursite.atlassian.net
.PARAMETER CloudEmail     Atlassian account email (Basic auth).
.PARAMETER CloudToken     Atlassian API token (Basic auth).
.PARAMETER WorkspaceId    (optional) Assets workspace id. Auto-discovered if omitted.
.PARAMETER ObjectTypeId   Object type on which to create the replacement attribute.
.PARAMETER ObjectId       Object to set the value on (single-object test).
.PARAMETER NewAttrName    Name of the replacement attribute, e.g. "Confluence Link".
.PARAMETER NewAttrType    "URL" (default), "Text", or "Textarea".
.PARAMETER ExistingAttrId (optional) Use an already-created attribute id; skips stage 2.
.PARAMETER Value          The Confluence Cloud URL (or text) to set on the object.
.PARAMETER BackupDir      Backup dir. Default .\cloud_backup
.PARAMETER Execute        Actually create/set. Omit for dry-run.

.EXAMPLE
    # dry run
    .\New-And-Set-AssetsUrlAttribute-Cloud.ps1 -CloudBaseUrl https://yoursite.atlassian.net `
        -CloudEmail me@corp.com -CloudToken $env:ATL_TOKEN -ObjectTypeId 23 -ObjectId 1187 `
        -NewAttrName "Confluence Link" -NewAttrType URL -Value "https://yoursite.atlassian.net/wiki/spaces/DEV/pages/12345/Title"
    # execute
    .\New-And-Set-AssetsUrlAttribute-Cloud.ps1 ... -Execute
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CloudBaseUrl,
    [Parameter(Mandatory)][string]$CloudEmail,
    [Parameter(Mandatory)][string]$CloudToken,
    [string]$WorkspaceId,
    [Parameter(Mandatory)][string]$ObjectTypeId,
    [Parameter(Mandatory)][string]$ObjectId,
    [string]$NewAttrName = 'Confluence Link',
    [ValidateSet('URL', 'Text', 'Textarea')][string]$NewAttrType = 'URL',
    [string]$ExistingAttrId,
    [Parameter(Mandatory)][string]$Value,
    [string]$BackupDir = '.\cloud_backup',
    [switch]$Execute
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$CloudBaseUrl = $CloudBaseUrl.TrimEnd('/')

$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($CloudEmail + ':' + $CloudToken)))
$headers = @{ Authorization = "Basic $b64"; Accept = 'application/json' }

# Default-type ids: Text=0, URL=7, Textarea=9
$defaultTypeId = @{ 'Text' = 0; 'URL' = 7; 'Textarea' = 9 }[$NewAttrType]

if (-not (Test-Path -LiteralPath $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

function AssetsApi {
    param([string]$Method, [string]$Rel, $Body)
    $uri = "https://api.atlassian.com/jsm/assets/workspace/$WorkspaceId/v1/$Rel"
    $a = @{ Uri = $uri; Method = $Method; Headers = $headers; ContentType = 'application/json'; ErrorAction = 'Stop' }
    if ($null -ne $Body) { $a['Body'] = ($Body | ConvertTo-Json -Depth 20) }
    try { return Invoke-RestMethod @a } catch { throw "$Method $uri failed: $($_.Exception.Message)" }
}

# web-view links (Cloud)
$site = ([uri]$CloudBaseUrl).Host
$objView = "$CloudBaseUrl/jira/servicedesk/assets/object/$ObjectId"

Write-Host "`n==== STAGE 0: discover workspace ====" -ForegroundColor Cyan
if (-not $WorkspaceId) {
    $ws = Invoke-RestMethod -Uri "$CloudBaseUrl/rest/servicedeskapi/assets/workspace" -Headers $headers -Method Get -ErrorAction Stop
    $WorkspaceId = @($ws.values)[0].workspaceId
    if (-not $WorkspaceId) { throw 'Could not discover workspace id.' }
}
Write-Host "Workspace id: $WorkspaceId"
Write-Host "Verify: open Assets in $site and confirm you are in the right Assets app."

Write-Host "`n==== STAGE 1: backup (BEFORE) ====" -ForegroundColor Cyan
$otAttrsBefore = AssetsApi GET "objecttype/$ObjectTypeId/attributes"
$objBefore     = AssetsApi GET "object/$ObjectId"
($otAttrsBefore | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "Cloud_ObjectType$($ObjectTypeId)_BEFORE_attributes.json.txt") -Encoding UTF8
($objBefore     | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "Cloud_Object$($ObjectId)_BEFORE.json.txt") -Encoding UTF8
Write-Host "Backed up object type attributes + object."
Write-Host "Verify: $objView"

# detect if attribute name already exists
$already = @($otAttrsBefore | Where-Object { $_.name -eq $NewAttrName })
if ($already.Count -and -not $ExistingAttrId) {
    Write-Host ("NOTE: an attribute named '{0}' already exists (id {1}). Pass -ExistingAttrId {1} to reuse it instead of creating a duplicate." -f $NewAttrName, $already[0].id) -ForegroundColor DarkYellow
}

Write-Host "`n==== STAGE 2: create replacement attribute ====" -ForegroundColor Cyan
$attrId = $ExistingAttrId
if ($ExistingAttrId) {
    Write-Host "Using existing attribute id $ExistingAttrId (stage 2 skipped)."
}
else {
    $createBody = @{ name = $NewAttrName; type = 0; defaultTypeId = $defaultTypeId }
    Write-Host ("Will POST objecttypeattribute/{0}:" -f $ObjectTypeId)
    Write-Host ($createBody | ConvertTo-Json -Depth 20)
    Write-Host ("This creates a {0} (Default/defaultTypeId={1}) attribute on object type {2}." -f $NewAttrType, $defaultTypeId, $ObjectTypeId)
    Write-Host "Verify after: Assets > object type > Attributes tab shows the new attribute."
    if ($Execute) {
        $created = AssetsApi POST "objecttypeattribute/$ObjectTypeId" $createBody
        $attrId = $created.id
        Write-Host "Created attribute id: $attrId" -ForegroundColor Green
        $otAttrsAfter = AssetsApi GET "objecttype/$ObjectTypeId/attributes"
        ($otAttrsAfter | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "Cloud_ObjectType$($ObjectTypeId)_AFTER_attributes.json.txt") -Encoding UTF8
    }
}

Write-Host "`n==== STAGE 3: set value on object ====" -ForegroundColor Cyan
if (-not $attrId) {
    Write-Host "DRY RUN - attribute not created yet, so its id is unknown; set body shown with <NEW_ATTR_ID> placeholder." -ForegroundColor Yellow
    $shownId = '<NEW_ATTR_ID>'
}
else { $shownId = $attrId }

$setBody = @{ objectTypeId = [int]$ObjectTypeId; attributes = @(@{ objectTypeAttributeId = $shownId; objectAttributeValues = @(@{ value = $Value }) }) }
Write-Host ("Will PUT object/{0}:" -f $ObjectId)
Write-Host ($setBody | ConvertTo-Json -Depth 20)
Write-Host "Verify after: $objView  (the new attribute shows the Cloud link)."

if (-not $Execute) {
    Write-Host "`nDRY RUN - no changes made. Re-run with -Execute to apply all stages." -ForegroundColor Yellow
    return
}

# real set (attrId is known here)
$setBody.attributes[0].objectTypeAttributeId = [int]$attrId
$null = AssetsApi PUT "object/$ObjectId" $setBody
$objAfter = AssetsApi GET "object/$ObjectId"
($objAfter | ConvertTo-Json -Depth 20) | Out-File (Join-Path $BackupDir "Cloud_Object$($ObjectId)_AFTER.json.txt") -Encoding UTF8

Write-Host "`nDONE." -ForegroundColor Green
Write-Host ("Attribute id {0} set to: {1}" -f $attrId, $Value)
Write-Host ("BEFORE/AFTER backups in: {0}" -f $BackupDir)
Write-Host ("Verify in web view: {0}" -f $objView) -ForegroundColor Yellow
