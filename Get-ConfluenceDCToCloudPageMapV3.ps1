<#
.SYNOPSIS
    Map Confluence DC pages to Confluence Cloud pages by space + title. (V3 - UTF-8 hardened)

.DESCRIPTION
    Fixes two issues from v2:
      1. ENCODING. German/Unicode titles failed because UTF-8 was not enforced end to end.
         V3 enforces UTF-8 at every level: input CSV read, request query encoding, RESPONSE
         decoding (PS 5.1 Invoke-RestMethod mis-decodes UTF-8, so responses are fetched as raw
         bytes and decoded explicitly), console output, and NFC normalization before comparing.
      2. QUERY. CQL '=' cannot be used with text fields such as 'title' (Atlassian docs). Only
         '~' (CONTAINS) works; exact phrase = title ~ "\"...\"". CQL title search is also buggy
         with special characters, so V3 uses a direct exact-title content lookup first, then CQL
         contains + in-memory exact filter, then (optional) a full space listing.

    Auth:
      Classic (site URL + Basic email:token)  - non-scoped API token.
      Scoped  (api.atlassian.com gateway + Bearer, cloudId auto-discovered) - scoped API token.

    Input CSV columns: LINK_URL, SpaceName, DC_ConfPageId (also accepts "DC ConfPageId").
    Output CSV columns: LINK_URL, SpaceName, DC_ConfPageId, Cloud_URL, Cloud_ConfPageId, Title, MatchStatus.

.PARAMETER InputCsv          Path to input CSV (read as UTF-8).
.PARAMETER CloudSiteUrl      Site root, e.g. https://yoursite.atlassian.net  (NO /wiki).
.PARAMETER CloudToken        API token.
.PARAMETER AuthMode          Classic (default) or Scoped.
.PARAMETER CloudEmail        Account email - required for Classic (Basic) mode.
.PARAMETER CloudId           Cloud id (UUID). Auto-discovered in Scoped mode if omitted.
.PARAMETER DcConfluenceBaseUrl  (optional) DC Confluence base - resolve title from a pageId-only link.
.PARAMETER DcConfluencePat   (optional) DC Confluence PAT (Bearer).
.PARAMETER DeepSearch        If exact + CQL find nothing, list the whole space and match in-memory.
.PARAMETER OutCsv            Output CSV (UTF-8). Default .\confluence_pagemap.csv
.PARAMETER DelayMs           Optional throttle between rows. Default 0.

.EXAMPLE
    .\Get-ConfluenceDCToCloudPageMapV3.ps1 -InputCsv .\confluence_links.csv `
        -CloudSiteUrl https://yoursite.atlassian.net -AuthMode Classic `
        -CloudEmail me@corp.com -CloudToken $env:ATL_CLASSIC_TOKEN
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputCsv,
    [Parameter(Mandatory)][string]$CloudSiteUrl,
    [Parameter(Mandatory)][string]$CloudToken,
    [ValidateSet('Classic', 'Scoped')][string]$AuthMode = 'Classic',
    [string]$CloudEmail,
    [string]$CloudId,
    [string]$DcConfluenceBaseUrl,
    [string]$DcConfluencePat,
    [switch]$DeepSearch,
    [string]$OutCsv = '.\confluence_pagemap.csv',
    [int]$DelayMs = 0
)

# ---- UTF-8 everywhere (console + default encodings) ----
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'
[Net.ServicePointManager]::SecurityProtocol = `
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$CloudSiteUrl = $CloudSiteUrl.TrimEnd('/')
if ($DcConfluenceBaseUrl) { $DcConfluenceBaseUrl = $DcConfluenceBaseUrl.TrimEnd('/') }

function Nfc { param([string]$s) if ($null -eq $s) { return $s } return $s.Normalize([Text.NormalizationForm]::FormC) }

function Get-HttpStatus { param($ErrorRecord)
    if ($ErrorRecord.Exception.Response) { try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { } }
    return $null
}

# Single JSON GET path. Fetches RAW bytes and decodes UTF-8 explicitly, because PS 5.1's
# Invoke-RestMethod mangles UTF-8 response bodies (umlauts become mojibake).
function Invoke-JsonUtf8 {
    param([string]$Uri, [hashtable]$Headers)
    $wr = Invoke-WebRequest -Uri $Uri -Headers $Headers -Method Get -UseBasicParsing -ErrorAction Stop
    $bytes = $wr.RawContentStream.ToArray()
    if (-not $bytes -or $bytes.Length -eq 0) { return $null }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    return ($text | ConvertFrom-Json)
}

# ---- auth + base URL ----
if ($AuthMode -eq 'Classic') {
    if (-not $CloudEmail) { throw 'Classic mode needs -CloudEmail (Basic auth: email:token).' }
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($CloudEmail + ':' + $CloudToken)))
    $cloudHeaders = @{ Authorization = "Basic $b64"; Accept = 'application/json' }
    $searchBase = "$CloudSiteUrl/wiki"
}
else {
    $uuidRe = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    $discovered = $null
    try { $ti = Invoke-JsonUtf8 "$CloudSiteUrl/_edgeAuth/tenant-info" @{ Accept = 'application/json' }; $discovered = $ti.cloudId } catch { }
    if (-not $discovered) {
        try {
            $ar = Invoke-JsonUtf8 'https://api.atlassian.com/oauth/token/accessible-resources' @{ Authorization = "Bearer $CloudToken"; Accept = 'application/json' }
            $m = @($ar | Where-Object { $_.url -eq $CloudSiteUrl -or $_.url -eq "$CloudSiteUrl/" })
            if ($m.Count) { $discovered = $m[0].id } elseif (@($ar).Count -eq 1) { $discovered = @($ar)[0].id }
        } catch { }
    }
    if ($CloudId -and ($CloudId -notmatch $uuidRe)) { Write-Warning "-CloudId '$CloudId' is not a cloud UUID (looks like an org id); ignoring."; $CloudId = $null }
    if ($discovered) { $CloudId = $discovered }
    if (-not $CloudId) { throw "Could not determine cloudId. Provide -CloudId (a UUID) or use -AuthMode Classic." }
    Write-Host "Using cloudId: $CloudId" -ForegroundColor Cyan
    $cloudHeaders = @{ Authorization = "Bearer $CloudToken"; Accept = 'application/json' }
    $searchBase = "https://api.atlassian.com/ex/confluence/$CloudId/wiki"
}
$siteWiki = "$CloudSiteUrl/wiki"   # human-facing URLs always from the site
$dcHeaders = $null
if ($DcConfluencePat) { $dcHeaders = @{ Authorization = "Bearer $DcConfluencePat"; Accept = 'application/json' } }

# ---- preflight ----
Write-Host ("AuthMode={0}  base={1}" -f $AuthMode, $searchBase) -ForegroundColor Cyan
try { $null = Invoke-JsonUtf8 "$searchBase/rest/api/space?limit=1" $cloudHeaders; Write-Host 'Preflight auth: OK' -ForegroundColor Green }
catch {
    $st = Get-HttpStatus $_
    if ($st -eq 404) { throw "Preflight 404 - gateway/cloudId wrong (org id vs cloud id?). Using '$CloudId'." }
    elseif ($st -eq 401) { throw "Preflight 401 - Classic needs a NON-scoped token + -CloudEmail; Scoped needs the gateway URL + scoped token." }
    elseif ($st -eq 403) { throw "Preflight 403 - authenticated but missing scope/permission (need Confluence read + search)." }
    else { throw "Preflight failed ($st): $($_.Exception.Message)" }
}

# ---- DC link parsing (UTF-8 + NFC) ----
function Parse-DcLink {
    param([string]$Url)
    $space = $null; $title = $null; $pageId = $null
    if ($Url) {
        if     ($Url -match 'pageId=(\d+)') { $pageId = $matches[1] }
        elseif ($Url -match '/pages/(\d+)') { $pageId = $matches[1] }
        if ($Url -match '/display/([^/?#]+)/([^?#]+)') {
            $space = [uri]::UnescapeDataString($matches[1])
            $title = Nfc ([uri]::UnescapeDataString(($matches[2] -replace '\+', ' ')))
        }
        elseif ($Url -match '/pages/\d+/([^/?#]+)') {
            $title = Nfc ([uri]::UnescapeDataString(($matches[1] -replace '\+', ' ')))
        }
        if (-not $space -and ($Url -match 'spaceKey=([^&]+)')) { $space = [uri]::UnescapeDataString($matches[1]) }
    }
    return [pscustomobject]@{ Space = $space; Title = $title; PageId = $pageId }
}

function Resolve-DcTitle {
    param([string]$PageId)
    if (-not $dcHeaders -or -not $DcConfluenceBaseUrl) { return $null }
    try {
        $r = Invoke-JsonUtf8 ('{0}/rest/api/content/{1}?expand=space' -f $DcConfluenceBaseUrl, $PageId) $dcHeaders
        return [pscustomobject]@{ Title = Nfc $r.title; Space = $r.space.key }
    } catch { return $null }
}

function CqlLit { param([string]$s) return $s.Replace('\', '\\').Replace('"', '\"') }

# Multi-method exact lookup; all comparisons NFC-normalized, case-insensitive.
function Find-CloudPage {
    param([string]$SpaceKey, [string]$Title)
    $want = Nfc $Title
    $encTitle = [uri]::EscapeDataString($want)
    $encSpace = [uri]::EscapeDataString($SpaceKey)
    $pick = { param($results) @($results | Where-Object { $_.title -and ((Nfc $_.title) -eq $want) }) }

    # A) exact title on the content endpoint (server-side exact match)
    $r = Invoke-JsonUtf8 "$searchBase/rest/api/content?spaceKey=$encSpace&title=$encTitle&type=page&limit=50" $cloudHeaders
    $hits = & $pick $r.results
    $status = 'OK_CONTENT'

    # B) CQL contains-phrase, then in-memory exact
    if ($hits.Count -eq 0) {
        $cql = ('space = "{0}" and title ~ "\"{1}\""' -f (CqlLit $SpaceKey), (CqlLit $want))
        $r = Invoke-JsonUtf8 "$searchBase/rest/api/content/search?limit=50&cql=$([uri]::EscapeDataString($cql))" $cloudHeaders
        $hits = & $pick $r.results
        $status = 'OK_CQL'
    }

    # C) optional full-space listing, in-memory exact
    if ($hits.Count -eq 0 -and $DeepSearch) {
        $start = 0; $all = @()
        do {
            $r = Invoke-JsonUtf8 "$searchBase/rest/api/content?spaceKey=$encSpace&type=page&limit=100&start=$start" $cloudHeaders
            $batch = @($r.results); $all += $batch; $start += 100
        } while ($batch.Count -eq 100 -and $start -lt 5000)
        $hits = & $pick $all
        $status = 'OK_DEEP'
    }

    if ($hits.Count -eq 0) { return @{ Id = $null; Url = $null; Status = 'NOT_FOUND' } }
    if ($hits.Count -gt 1) { $status = 'MULTIPLE_TOOK_FIRST' }
    $m = $hits[0]
    return @{ Id = $m.id; Url = "$siteWiki$($m._links.webui)"; Status = $status }
}

# ---- input (UTF-8) ----
if (-not (Test-Path -LiteralPath $InputCsv)) { throw "Input CSV not found: $InputCsv" }
$inRows = Import-Csv -LiteralPath $InputCsv -Encoding UTF8
function Get-Field { param($Row, [string[]]$Names)
    foreach ($n in $Names) { $p = $Row.PSObject.Properties[$n]; if ($p) { return $p.Value } } return $null
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
Write-Host "Non-OK rows to review: NOT_FOUND / NO_TITLE / MULTIPLE_TOOK_FIRST / ERROR_*. Try -DeepSearch for stubborn NOT_FOUND." -ForegroundColor Yellow
