<#
.SYNOPSIS
    Map Confluence Data Center pages to their Confluence Cloud equivalents by space + title.

.DESCRIPTION
    Input CSV columns (headers, case-insensitive): LINK_URL, SpaceName, DC_ConfPageId
      (also accepts "DC ConfPageId" with a space).
    For each row it determines the space key + page title, then searches Confluence Cloud via CQL
    to find the migrated page's Cloud id and URL. Output CSV columns:
        LINK_URL, SpaceName, DC_ConfPageId, Cloud_URL, Cloud_ConfPageId, Title, MatchStatus

    Title resolution:
      - Pretty DC URL (/display/SPACE/Page+Title)  -> title parsed from the URL (UTF-8 decoded).
      - pageId URL (viewpage.action?pageId=123)     -> title resolved from DC Confluence by id,
        if -DcConfluenceBaseUrl / -DcConfluencePat are supplied; otherwise MatchStatus = NO_TITLE.

    Encoding: titles are decoded from the DC URL ('+' -> space, %xx -> UTF-8), and the whole CQL
    string is URL-encoded as UTF-8 before the request, so German umlauts / ß round-trip correctly.

    MatchStatus: OK_EXACT | OK_FUZZY_EXACT | MULTIPLE_TOOK_FIRST | NOT_FOUND | NO_TITLE | ERROR

.PARAMETER InputCsv          Path to input CSV.
.PARAMETER CloudBaseUrl      Confluence Cloud base incl. /wiki, e.g. https://yoursite.atlassian.net/wiki
.PARAMETER CloudEmail        Atlassian account email (Basic auth).
.PARAMETER CloudToken        Atlassian API token (Basic auth).
.PARAMETER DcConfluenceBaseUrl  (optional) DC Confluence base, e.g. https://confluence.dc  - used to resolve title from pageId.
.PARAMETER DcConfluencePat   (optional) DC Confluence Personal Access Token (Bearer).
.PARAMETER OutCsv            Output CSV. Default .\confluence_pagemap.csv
.PARAMETER DelayMs           Optional throttle between Cloud calls (ms). Default 0.

.EXAMPLE
    .\ConfluenceDcToCloud-PageMap.ps1 -InputCsv .\confluence_links.csv `
        -CloudBaseUrl https://yoursite.atlassian.net/wiki -CloudEmail me@corp.com -CloudToken $env:ATL_TOKEN `
        -DcConfluenceBaseUrl https://confluence.dc -DcConfluencePat $env:DC_CONF_PAT
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputCsv,
    [Parameter(Mandatory)][string]$CloudBaseUrl,
    [Parameter(Mandatory)][string]$CloudEmail,
    [Parameter(Mandatory)][string]$CloudToken,
    [string]$DcConfluenceBaseUrl,
    [string]$DcConfluencePat,
    [string]$OutCsv = '.\confluence_pagemap.csv',
    [int]$DelayMs = 0
)

[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$CloudBaseUrl = $CloudBaseUrl.TrimEnd('/')
if ($DcConfluenceBaseUrl) { $DcConfluenceBaseUrl = $DcConfluenceBaseUrl.TrimEnd('/') }

$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($CloudEmail + ':' + $CloudToken)))
$cloudHeaders = @{ Authorization = "Basic $b64"; Accept = 'application/json' }
$dcHeaders = $null
if ($DcConfluencePat) { $dcHeaders = @{ Authorization = "Bearer $DcConfluencePat"; Accept = 'application/json' } }

# Pull space key, title and page id out of a DC link.
function Parse-DcLink {
    param([string]$Url)
    $space = $null; $title = $null; $pageId = $null
    if ($Url) {
        if     ($Url -match 'pageId=(\d+)')      { $pageId = $matches[1] }
        elseif ($Url -match '/pages/(\d+)')      { $pageId = $matches[1] }

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

# Resolve title + space from DC Confluence by page id (needs DC base + PAT).
function Resolve-DcTitle {
    param([string]$PageId)
    if (-not $dcHeaders -or -not $DcConfluenceBaseUrl) { return $null }
    try {
        $u = ('{0}/rest/api/content/{1}?expand=space' -f $DcConfluenceBaseUrl, $PageId)
        $r = Invoke-RestMethod -Uri $u -Headers $dcHeaders -Method Get -ErrorAction Stop
        return [pscustomobject]@{ Title = $r.title; Space = $r.space.key }
    } catch { return $null }
}

# CQL-escape a string literal: backslash first, then double-quote -> \"
function CqlLit { param([string]$s) return $s.Replace('\', '\\').Replace('"', '\"') }

# Search Cloud for a page by space + exact title; returns @{ Id; Url; Status }.
function Find-CloudPage {
    param([string]$SpaceKey, [string]$Title)

    $titleLit = CqlLit $Title
    $spaceLit = CqlLit $SpaceKey

    function Invoke-Cql {
        param([string]$Cql)
        $u = ('{0}/rest/api/content/search?limit=25&cql={1}' -f $CloudBaseUrl, [uri]::EscapeDataString($Cql))
        return Invoke-RestMethod -Uri $u -Headers $cloudHeaders -Method Get -ErrorAction Stop
    }

    # 1) exact
    $resp = Invoke-Cql ('space = "{0}" and title = "{1}"' -f $spaceLit, $titleLit)
    $exact = @($resp.results | Where-Object { $_.title -ceq $Title })
    $status = 'OK_EXACT'

    # 2) fuzzy fallback, then client-side exact
    if ($exact.Count -eq 0) {
        $resp = Invoke-Cql ('space = "{0}" and title ~ "{1}"' -f $spaceLit, $titleLit)
        $exact = @($resp.results | Where-Object { $_.title -ceq $Title })
        $status = 'OK_FUZZY_EXACT'
    }

    if ($exact.Count -eq 0) { return @{ Id = $null; Url = $null; Status = 'NOT_FOUND' } }
    if ($exact.Count -gt 1) { $status = 'MULTIPLE_TOOK_FIRST' }

    $m = $exact[0]
    $base = if ($resp._links -and $resp._links.base) { $resp._links.base } else { $CloudBaseUrl }
    $webui = $m._links.webui
    return @{ Id = $m.id; Url = "$base$webui"; Status = $status }
}

# --- input (tolerant of "DC ConfPageId" vs DC_ConfPageId) ---
if (-not (Test-Path -LiteralPath $InputCsv)) { throw "Input CSV not found: $InputCsv" }
$inRows = Import-Csv -LiteralPath $InputCsv

function Get-Field { param($Row, [string[]]$Names)
    foreach ($n in $Names) { $p = $Row.PSObject.Properties[$n]; if ($p) { return $p.Value } }
    return $null
}

$out = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($r in $inRows) {
    $i++
    $linkUrl = "$(Get-Field $r @('LINK_URL','LinkUrl','Link'))".Trim()
    $spaceIn = "$(Get-Field $r @('SpaceName','Space','SpaceKey'))".Trim()
    $dcPage  = "$(Get-Field $r @('DC_ConfPageId','DC ConfPageId','DcConfPageId','PageId'))".Trim()

    $cloudUrl = $null; $cloudId = $null; $title = $null; $status = $null
    try {
        $parsed = Parse-DcLink -Url $linkUrl
        $title = $parsed.Title
        $spaceKey = if ($parsed.Space) { $parsed.Space } elseif ($spaceIn) { $spaceIn } else { $null }
        if (-not $dcPage -and $parsed.PageId) { $dcPage = $parsed.PageId }

        # no title in URL -> resolve from DC by pageId
        if (-not $title -and $dcPage) {
            $res = Resolve-DcTitle -PageId $dcPage
            if ($res) { $title = $res.Title; if (-not $spaceKey) { $spaceKey = $res.Space } }
        }

        if (-not $title)    { $status = 'NO_TITLE' }
        elseif (-not $spaceKey) { $status = 'NO_TITLE' }   # need a space to scope the search
        else {
            $hit = Find-CloudPage -SpaceKey $spaceKey -Title $title
            $cloudId = $hit.Id; $cloudUrl = $hit.Url; $status = $hit.Status
        }
    } catch {
        $status = "ERROR: $($_.Exception.Message)"
    }

    Write-Host ("[{0}/{1}] {2} | '{3}' -> {4}" -f $i, $inRows.Count, $spaceIn, $title, $status) -ForegroundColor DarkGray

    $out.Add([pscustomobject][ordered]@{
        LINK_URL         = $linkUrl
        SpaceName        = $spaceIn
        DC_ConfPageId    = $dcPage
        Cloud_URL        = $cloudUrl
        Cloud_ConfPageId = $cloudId
        Title            = $title
        MatchStatus      = $status
    })

    if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}

$out | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8

$ok = @($out | Where-Object { $_.Cloud_ConfPageId }).Count
Write-Host ("`nMapped {0}/{1} pages -> {2}" -f $ok, $out.Count, $OutCsv) -ForegroundColor Green
Write-Host "Review rows where MatchStatus is NOT_FOUND / NO_TITLE / MULTIPLE_TOOK_FIRST / ERROR." -ForegroundColor Yellow
