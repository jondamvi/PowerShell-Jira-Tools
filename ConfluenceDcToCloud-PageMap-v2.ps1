<#
.SYNOPSIS
    Map Confluence DC pages to Confluence Cloud pages by space + title. (v2 - scoped-token aware)

.DESCRIPTION
    Fixes 401s caused by SCOPED API tokens. Scoped tokens do NOT work against the site URL
    (https://<site>.atlassian.net/wiki/...); they must go through the platform gateway:
        https://api.atlassian.com/ex/confluence/{cloudId}/wiki/rest/api/...
    with a Bearer header. This script defaults to that (AuthMode = Scoped), auto-discovers the
    cloudId, and runs a preflight auth check so failures are explained instead of a raw 401.

    AuthMode:
      Scoped  (default) - Bearer token via api.atlassian.com/ex/confluence/{cloudId}/wiki  (scoped API token)
      Classic           - Basic (email:token) via https://<site>.atlassian.net/wiki       (non-scoped API token)

    Input CSV columns: LINK_URL, SpaceName, DC_ConfPageId (also accepts "DC ConfPageId").
    Output CSV columns: LINK_URL, SpaceName, DC_ConfPageId, Cloud_URL, Cloud_ConfPageId, Title, MatchStatus.
    Titles are UTF-8 decoded from the DC URL and re-encoded for CQL, so German umlauts / ss round-trip.

.PARAMETER InputCsv          Path to input CSV.
.PARAMETER CloudSiteUrl      Site root, e.g. https://yoursite.atlassian.net  (NO /wiki).
.PARAMETER CloudToken        API token (scoped for Scoped mode; classic for Classic mode).
.PARAMETER AuthMode          Scoped (default) or Classic.
.PARAMETER CloudEmail        Atlassian account email - required only for Classic (Basic) mode.
.PARAMETER CloudId           Cloud id. Auto-discovered from the site if omitted (Scoped mode).
.PARAMETER DcConfluenceBaseUrl  (optional) DC Confluence base - resolve title from a pageId-only link.
.PARAMETER DcConfluencePat   (optional) DC Confluence PAT (Bearer).
.PARAMETER OutCsv            Output CSV. Default .\confluence_pagemap.csv
.PARAMETER DelayMs           Optional throttle between Cloud calls. Default 0.

.EXAMPLE
    # scoped token (default)
    .\ConfluenceDcToCloud-PageMap-v2.ps1 -InputCsv .\confluence_links.csv `
        -CloudSiteUrl https://yoursite.atlassian.net -CloudToken $env:ATL_SCOPED_TOKEN `
        -DcConfluenceBaseUrl https://confluence.dc -DcConfluencePat $env:DC_CONF_PAT
    # classic (non-scoped) token
    .\ConfluenceDcToCloud-PageMap-v2.ps1 -InputCsv .\confluence_links.csv `
        -CloudSiteUrl https://yoursite.atlassian.net -AuthMode Classic `
        -CloudEmail me@corp.com -CloudToken $env:ATL_CLASSIC_TOKEN
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputCsv,
    [Parameter(Mandatory)][string]$CloudSiteUrl,
    [Parameter(Mandatory)][string]$CloudToken,
    [ValidateSet('Scoped', 'Classic')][string]$AuthMode = 'Scoped',
    [string]$CloudEmail,
    [string]$CloudId,
    [string]$DcConfluenceBaseUrl,
    [string]$DcConfluencePat,
    [string]$OutCsv = '.\confluence_pagemap.csv',
    [int]$DelayMs = 0
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$CloudSiteUrl = $CloudSiteUrl.TrimEnd('/')
if ($DcConfluenceBaseUrl) { $DcConfluenceBaseUrl = $DcConfluenceBaseUrl.TrimEnd('/') }

# ------- auth + base URL selection -------
if ($AuthMode -eq 'Classic') {
    if (-not $CloudEmail) { throw 'Classic mode needs -CloudEmail (Basic auth: email:token).' }
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($CloudEmail + ':' + $CloudToken)))
    $cloudHeaders = @{ Authorization = "Basic $b64"; Accept = 'application/json' }
    $searchBase = "$CloudSiteUrl/wiki"
}
else {
    # Scoped: discover cloudId, use gateway + Bearer
    if (-not $CloudId) {
        try {
            $ti = Invoke-RestMethod -Uri "$CloudSiteUrl/_edgeAuth/tenant-info" -Method Get -ErrorAction Stop
            $CloudId = $ti.cloudId
        } catch { throw "Could not auto-discover cloudId from $CloudSiteUrl/_edgeAuth/tenant-info : $($_.Exception.Message). Pass -CloudId explicitly." }
    }
    if (-not $CloudId) { throw 'cloudId is empty; pass -CloudId explicitly.' }
    $cloudHeaders = @{ Authorization = "Bearer $CloudToken"; Accept = 'application/json' }
    $searchBase = "https://api.atlassian.com/ex/confluence/$CloudId/wiki"
}
# Human-usable output URLs always use the site (not the gateway) base.
$siteWiki = "$CloudSiteUrl/wiki"

$dcHeaders = $null
if ($DcConfluencePat) { $dcHeaders = @{ Authorization = "Bearer $DcConfluencePat"; Accept = 'application/json' } }

function Get-HttpStatus { param($ErrorRecord)
    if ($ErrorRecord.Exception.Response) { try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { } }
    return $null
}

# ------- preflight auth check -------
Write-Host ("AuthMode={0}  base={1}" -f $AuthMode, $searchBase) -ForegroundColor Cyan
try {
    $null = Invoke-RestMethod -Uri "$searchBase/rest/api/space?limit=1" -Headers $cloudHeaders -Method Get -ErrorAction Stop
    Write-Host "Preflight auth: OK" -ForegroundColor Green
} catch {
    $st = Get-HttpStatus $_
    if ($st -eq 401) { throw "Preflight 401 Unauthorized. In Scoped mode check the cloudId/gateway URL and that the token is a scoped token. In Classic mode the token must be a NON-scoped token with -CloudEmail." }
    elseif ($st -eq 403) { throw "Preflight 403 Forbidden. The token authenticated but lacks the required scope (need Confluence read + search scopes) or space permission." }
    else { throw "Preflight failed ($st): $($_.Exception.Message)" }
}

# ------- helpers (unchanged matching logic) -------
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

function Resolve-DcTitle {
    param([string]$PageId)
    if (-not $dcHeaders -or -not $DcConfluenceBaseUrl) { return $null }
    try {
        $r = Invoke-RestMethod -Uri ('{0}/rest/api/content/{1}?expand=space' -f $DcConfluenceBaseUrl, $PageId) -Headers $dcHeaders -Method Get -ErrorAction Stop
        return [pscustomobject]@{ Title = $r.title; Space = $r.space.key }
    } catch { return $null }
}

function CqlLit { param([string]$s) return $s.Replace('\', '\\').Replace('"', '\"') }

function Find-CloudPage {
    param([string]$SpaceKey, [string]$Title)
    $titleLit = CqlLit $Title; $spaceLit = CqlLit $SpaceKey
    function Invoke-Cql { param([string]$Cql)
        Invoke-RestMethod -Uri ('{0}/rest/api/content/search?limit=25&cql={1}' -f $searchBase, [uri]::EscapeDataString($Cql)) -Headers $cloudHeaders -Method Get -ErrorAction Stop
    }
    $resp = Invoke-Cql ('space = "{0}" and title = "{1}"' -f $spaceLit, $titleLit)
    $exact = @($resp.results | Where-Object { $_.title -ceq $Title }); $status = 'OK_EXACT'
    if ($exact.Count -eq 0) {
        $resp = Invoke-Cql ('space = "{0}" and title ~ "{1}"' -f $spaceLit, $titleLit)
        $exact = @($resp.results | Where-Object { $_.title -ceq $Title }); $status = 'OK_FUZZY_EXACT'
    }
    if ($exact.Count -eq 0) { return @{ Id = $null; Url = $null; Status = 'NOT_FOUND' } }
    if ($exact.Count -gt 1) { $status = 'MULTIPLE_TOOK_FIRST' }
    $m = $exact[0]
    return @{ Id = $m.id; Url = "$siteWiki$($m._links.webui)"; Status = $status }
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

    $cloudUrl = $null; $cloudId2 = $null; $title = $null; $status = $null
    try {
        $parsed = Parse-DcLink -Url $linkUrl
        $title = $parsed.Title
        $spaceKey = if ($parsed.Space) { $parsed.Space } elseif ($spaceIn) { $spaceIn } else { $null }
        if (-not $dcPage -and $parsed.PageId) { $dcPage = $parsed.PageId }
        if (-not $title -and $dcPage) {
            $res = Resolve-DcTitle -PageId $dcPage
            if ($res) { $title = $res.Title; if (-not $spaceKey) { $spaceKey = $res.Space } }
        }
        if (-not $title -or -not $spaceKey) { $status = 'NO_TITLE' }
        else {
            $hit = Find-CloudPage -SpaceKey $spaceKey -Title $title
            $cloudId2 = $hit.Id; $cloudUrl = $hit.Url; $status = $hit.Status
        }
    } catch {
        $st = Get-HttpStatus $_
        $status = if ($st) { "ERROR_$st" } else { "ERROR: $($_.Exception.Message)" }
    }

    Write-Host ("[{0}/{1}] {2} | '{3}' -> {4}" -f $i, $inRows.Count, $spaceIn, $title, $status) -ForegroundColor DarkGray
    $out.Add([pscustomobject][ordered]@{
        LINK_URL = $linkUrl; SpaceName = $spaceIn; DC_ConfPageId = $dcPage
        Cloud_URL = $cloudUrl; Cloud_ConfPageId = $cloudId2; Title = $title; MatchStatus = $status
    })
    if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}

$out | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
$ok = @($out | Where-Object { $_.Cloud_ConfPageId }).Count
Write-Host ("`nMapped {0}/{1} pages -> {2}" -f $ok, $out.Count, $OutCsv) -ForegroundColor Green
Write-Host "Review NOT_FOUND / NO_TITLE / MULTIPLE_TOOK_FIRST / ERROR_* rows." -ForegroundColor Yellow
