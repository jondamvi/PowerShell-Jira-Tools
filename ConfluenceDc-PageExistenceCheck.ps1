<#
.SYNOPSIS
    Verify whether pages from a DC->Cloud mapping input CSV still exist on Confluence DC.

.DESCRIPTION
    Companion to ConfluenceDcToCloud-PageMap-v2.ps1. Runs BEFORE that script, against the
    DC (on-prem) site, to catch pages that were deleted/trashed as part of pre-migration
    cleanup, so you don't waste time mapping pages that no longer need mapping.

    Accepts the SAME input CSV (LINK_URL, SpaceName, DC_ConfPageId). For each row:
      1. If DC_ConfPageId is present (or can be parsed from LINK_URL), looks it up by id via
         GET /rest/api/content/{id}. Tries current status first, then retries with
         status=trashed so "moved to trash" can be distinguished from "gone entirely".
      2. If no id is available, falls back to a CQL search by space + title (parsed from
         LINK_URL) to see if a page with that title still exists in that space.
      3. Reports EXISTS / EXISTS_BUT_TRASHED / NOT_FOUND / NO_IDENTIFIER / ERROR_* per row,
         plus the DC page's current title/space (useful for spotting renames/moves).

    Input CSV columns:  LINK_URL, SpaceName, DC_ConfPageId (also accepts "DC ConfPageId").
    Output CSV columns: LINK_URL, SpaceName, DC_ConfPageId, DC_Status, DC_CurrentTitle,
                         DC_CurrentSpace, MatchedBy, Notes

.PARAMETER InputCsv           Path to input CSV (same file used for the DC->Cloud page map).
.PARAMETER DcConfluenceBaseUrl DC Confluence base, e.g. https://confluence.corp.local (no /rest suffix).
.PARAMETER DcAuthMode          PAT (default, Bearer token) or Basic (username + password/token).
.PARAMETER DcPat               Personal Access Token - required for PAT mode.
.PARAMETER DcUsername          Username - required for Basic mode.
.PARAMETER DcPassword          Password or token - required for Basic mode.
.PARAMETER OutCsv              Output CSV. Default .\confluence_dc_existence.csv
.PARAMETER DelayMs             Optional throttle between DC calls. Default 0.

.EXAMPLE
    # PAT auth (default)
    .\ConfluenceDc-PageExistenceCheck.ps1 -InputCsv .\confluence_links.csv `
        -DcConfluenceBaseUrl https://confluence.corp.local -DcPat $env:DC_CONF_PAT

.EXAMPLE
    # Basic auth
    .\ConfluenceDc-PageExistenceCheck.ps1 -InputCsv .\confluence_links.csv `
        -DcConfluenceBaseUrl https://confluence.corp.local -DcAuthMode Basic `
        -DcUsername me -DcPassword $env:DC_CONF_PW
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputCsv,
    [Parameter(Mandatory)][string]$DcConfluenceBaseUrl,
    [ValidateSet('PAT', 'Basic')][string]$DcAuthMode = 'PAT',
    [string]$DcPat,
    [string]$DcUsername,
    [string]$DcPassword,
    [string]$OutCsv = '.\confluence_dc_existence.csv',
    [int]$DelayMs = 0
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$DcConfluenceBaseUrl = $DcConfluenceBaseUrl.TrimEnd('/')

# ------- auth -------
if ($DcAuthMode -eq 'PAT') {
    if (-not $DcPat) { throw 'PAT mode needs -DcPat (Bearer token).' }
    $dcHeaders = @{ Authorization = "Bearer $DcPat"; Accept = 'application/json' }
}
else {
    if (-not $DcUsername -or -not $DcPassword) { throw 'Basic mode needs -DcUsername and -DcPassword.' }
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($DcUsername + ':' + $DcPassword)))
    $dcHeaders = @{ Authorization = "Basic $b64"; Accept = 'application/json' }
}

function Get-HttpStatus { param($ErrorRecord)
    if ($ErrorRecord.Exception.Response) { try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { } }
    return $null
}

# ------- preflight auth check -------
Write-Host ("AuthMode={0}  base={1}" -f $DcAuthMode, $DcConfluenceBaseUrl) -ForegroundColor Cyan
try {
    $null = Invoke-RestMethod -Uri "$DcConfluenceBaseUrl/rest/api/space?limit=1" -Headers $dcHeaders -Method Get -ErrorAction Stop
    Write-Host "Preflight auth: OK" -ForegroundColor Green
} catch {
    $st = Get-HttpStatus $_
    if ($st -eq 401) { throw "Preflight 401 Unauthorized. Check the PAT/credentials." }
    elseif ($st -eq 403) { throw "Preflight 403 Forbidden. Token/user lacks required permissions." }
    else { throw "Preflight failed ($st): $($_.Exception.Message)" }
}

# ------- helpers -------
function Parse-DcLink {
    param([string]$Url)
    $space = $null; $title = $null; $pageId = $null
    if ($Url) {
        if     ($Url -match 'pageId=(\d+)') { $pageId = $matches[1] }
        elseif ($Url -match '/pages/(\d+)') { $pageId = $matches[1] }
        if ($Url -match '/display/([^/?#]+)/([^?#]+)') {
            $space = [uri]::UnescapeDataString($matches[1])
            $title = [uri]::UnescapeDataString(($matches[2] -replace '\+', ' '))
        }
        elseif ($Url -match '/pages/\d+/([^/?#]+)') {
            $title = [uri]::UnescapeDataString(($matches[1] -replace '\+', ' '))
        }
        if (-not $space -and ($Url -match 'spaceKey=([^&]+)')) { $space = [uri]::UnescapeDataString($matches[1]) }
    }
    return [pscustomobject]@{ Space = $space; Title = $title; PageId = $pageId }
}

function CqlLit { param([string]$s) return $s.Replace('\', '\\').Replace('"', '\"') }

# Look up a page by id. Tries current status first, then retries with status=trashed so
# "in the trash" can be distinguished from "does not exist at all".
function Get-DcPageById {
    param([string]$PageId)
    try {
        $r = Invoke-RestMethod -Uri ('{0}/rest/api/content/{1}?expand=space' -f $DcConfluenceBaseUrl, $PageId) `
            -Headers $dcHeaders -Method Get -ErrorAction Stop
        return [pscustomobject]@{ Found = $true; PageStatus = $r.status; Title = $r.title; Space = $r.space.key }
    } catch {
        $st = Get-HttpStatus $_
        if ($st -ne 404) {
            return [pscustomobject]@{ Found = $false; PageStatus = "ERROR_$st"; Title = $null; Space = $null }
        }
        try {
            $r2 = Invoke-RestMethod -Uri ('{0}/rest/api/content/{1}?status=trashed&expand=space' -f $DcConfluenceBaseUrl, $PageId) `
                -Headers $dcHeaders -Method Get -ErrorAction Stop
            return [pscustomobject]@{ Found = $true; PageStatus = $r2.status; Title = $r2.title; Space = $r2.space.key }
        } catch {
            $st2 = Get-HttpStatus $_
            if ($st2 -eq 404) { return [pscustomobject]@{ Found = $false; PageStatus = 'notfound'; Title = $null; Space = $null } }
            return [pscustomobject]@{ Found = $false; PageStatus = "ERROR_$st2"; Title = $null; Space = $null }
        }
    }
}

# Fallback when no page id is available: search current content by space+title via CQL,
# then retry with status=trashed if nothing current is found.
function Find-DcPageByTitle {
    param([string]$SpaceKey, [string]$Title)
    $titleLit = CqlLit $Title; $spaceLit = CqlLit $SpaceKey
    function Invoke-Cql { param([string]$Cql)
        Invoke-RestMethod -Uri ('{0}/rest/api/content/search?limit=25&cql={1}' -f $DcConfluenceBaseUrl, [uri]::EscapeDataString($Cql)) `
            -Headers $dcHeaders -Method Get -ErrorAction Stop
    }
    try {
        $resp = Invoke-Cql ('space = "{0}" and title = "{1}"' -f $spaceLit, $titleLit)
        $exact = @($resp.results | Where-Object { $_.title -ceq $Title })
        if ($exact.Count -gt 0) {
            return [pscustomobject]@{ Found = $true; PageStatus = 'current'; Title = $exact[0].title; Space = $SpaceKey }
        }
        $resp2 = Invoke-Cql ('space = "{0}" and title = "{1}" and status = "trashed"' -f $spaceLit, $titleLit)
        $exact2 = @($resp2.results | Where-Object { $_.title -ceq $Title })
        if ($exact2.Count -gt 0) {
            return [pscustomobject]@{ Found = $true; PageStatus = 'trashed'; Title = $exact2[0].title; Space = $SpaceKey }
        }
        return [pscustomobject]@{ Found = $false; PageStatus = 'notfound'; Title = $null; Space = $null }
    } catch {
        $st = Get-HttpStatus $_
        return [pscustomobject]@{ Found = $false; PageStatus = "ERROR_$st"; Title = $null; Space = $null }
    }
}

# ------- input -------
if (-not (Test-Path -LiteralPath $InputCsv)) { throw "Input CSV not found: $InputCsv" }
$inRows = Import-Csv -LiteralPath $InputCsv
function Get-Field { param($Row, [string[]]$Names)
    foreach ($n in $Names) { $p = $Row.PSObject.Properties[$n]; if ($p) { return $p.Value } }; return $null
}

$out = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($r in $inRows) {
    $i++
    $linkUrl = "$(Get-Field $r @('LINK_URL','LinkUrl','Link'))".Trim()
    $spaceIn = "$(Get-Field $r @('SpaceName','Space','SpaceKey'))".Trim()
    $dcPage  = "$(Get-Field $r @('DC_ConfPageId','DC ConfPageId','DcConfPageId','PageId'))".Trim()

    $status = $null; $curTitle = $null; $curSpace = $null; $matchedBy = $null; $notes = $null

    try {
        $parsed = Parse-DcLink -Url $linkUrl
        $titleFromLink = $parsed.Title
        $spaceKey = if ($spaceIn) { $spaceIn } elseif ($parsed.Space) { $parsed.Space } else { $null }
        if (-not $dcPage -and $parsed.PageId) { $dcPage = $parsed.PageId }

        if ($dcPage) {
            $matchedBy = 'ID'
            $res = Get-DcPageById -PageId $dcPage
            if ($res.Found) {
                $status = if ($res.PageStatus -eq 'current') { 'EXISTS' } else { "EXISTS_BUT_$($res.PageStatus.ToUpper())" }
                $curTitle = $res.Title; $curSpace = $res.Space
            }
            elseif ($res.PageStatus -like 'ERROR_*') { $status = $res.PageStatus }
            else { $status = 'NOT_FOUND' }
        }
        elseif ($spaceKey -and $titleFromLink) {
            $matchedBy = 'TITLE'
            $res = Find-DcPageByTitle -SpaceKey $spaceKey -Title $titleFromLink
            if ($res.Found) {
                $status = if ($res.PageStatus -eq 'current') { 'EXISTS' } else { "EXISTS_BUT_$($res.PageStatus.ToUpper())" }
                $curTitle = $res.Title; $curSpace = $res.Space
            }
            elseif ($res.PageStatus -like 'ERROR_*') { $status = $res.PageStatus }
            else { $status = 'NOT_FOUND' }
        }
        else {
            $status = 'NO_IDENTIFIER'
            $notes = 'No DC_ConfPageId and no pageId/space+title parseable from LINK_URL.'
        }
    } catch {
        $st = Get-HttpStatus $_
        $status = if ($st) { "ERROR_$st" } else { "ERROR: $($_.Exception.Message)" }
    }

    $color = switch -Wildcard ($status) {
        'EXISTS'          { 'Green' }
        'EXISTS_BUT_*'    { 'Yellow' }
        'NOT_FOUND'       { 'Red' }
        'NO_IDENTIFIER'   { 'Yellow' }
        'ERROR*'          { 'Red' }
        default           { 'DarkGray' }
    }
    Write-Host ("[{0}/{1}] {2} | id={3} '{4}' -> {5}" -f $i, $inRows.Count, $spaceIn, $dcPage, $curTitle, $status) -ForegroundColor $color

    $out.Add([pscustomobject][ordered]@{
        LINK_URL        = $linkUrl
        SpaceName       = $spaceIn
        DC_ConfPageId   = $dcPage
        DC_Status       = $status
        DC_CurrentTitle = $curTitle
        DC_CurrentSpace = $curSpace
        MatchedBy       = $matchedBy
        Notes           = $notes
    })
    if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}

$out | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8

$existsCount   = @($out | Where-Object { $_.DC_Status -eq 'EXISTS' }).Count
$trashedCount  = @($out | Where-Object { $_.DC_Status -like 'EXISTS_BUT_*' }).Count
$notFoundCount = @($out | Where-Object { $_.DC_Status -eq 'NOT_FOUND' }).Count
$errorCount    = @($out | Where-Object { $_.DC_Status -like 'ERROR*' }).Count
$noIdCount     = @($out | Where-Object { $_.DC_Status -eq 'NO_IDENTIFIER' }).Count

Write-Host ("`nDC existence check: {0} total" -f $out.Count) -ForegroundColor Cyan
Write-Host ("  EXISTS:          {0}" -f $existsCount) -ForegroundColor Green
Write-Host ("  EXISTS_BUT_*:    {0}  (trashed / soft-deleted - review)" -f $trashedCount) -ForegroundColor Yellow
Write-Host ("  NOT_FOUND:       {0}  (safe to drop from cloud page-map input)" -f $notFoundCount) -ForegroundColor Red
Write-Host ("  NO_IDENTIFIER:   {0}" -f $noIdCount) -ForegroundColor Yellow
Write-Host ("  ERROR_*:         {0}" -f $errorCount) -ForegroundColor Red
Write-Host ("`nOutput -> {0}" -f $OutCsv) -ForegroundColor Green
