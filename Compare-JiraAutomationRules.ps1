#requires -Version 5.1
<#
.SYNOPSIS
    Compares Jira Data Center automation rules (Automation for Jira) with their migrated
    Jira Cloud counterparts: rule-level settings, step count, step types/names, and the
    configuration of every step. Read-only on both sides.

.DESCRIPTION
    Endpoints
      DC    : GET /rest/cb-automation/latest/project/GLOBAL/rule          (list, full config)
              GET /rest/cb-automation/latest/project/GLOBAL/rule/{id}     (fallback per rule)
              GET /rest/api/2/project                                      (projectId -> key)
      Cloud : GET /_edge/tenant_info                                       (cloudId)
              GET /gateway/api/automation/internal-api/jira/{cloudId}/pro/rest/GLOBAL/rule
              GET /gateway/api/automation/internal-api/jira/{cloudId}/pro/rest/GLOBAL/rule/{id}
              GET /rest/api/3/project/search                               (projectId -> key)
      The Cloud automation endpoints are the undocumented ones the admin UI uses.

    Matching: DC rule <-> Cloud rule by rule name (trimmed, case-insensitive). Duplicate
    names on either side are warned about and reported as MatchStatus = DuplicateName.

    Comparison, in order (default: stop at the first difference per rule, -FullDiff for all):
      1. rule settings  : state, scope (project keys), actor, canOtherRuleTrigger,
                          notifyOnError, writeAccessType, labels, description
      2. step count     : trigger + flattened component tree (children/conditions)
      3. per step       : component kind (TRIGGER/CONDITION/ACTION/BRANCH...) and type
      4. per step       : deep diff of the "value" configuration object

    Before the deep diff the DC side is rewritten with the custom field mapping CSV
    (same logic/columns as Find-FiltersWithCustomFields.ps1: DcId, DcName, CloudId,
    CloudName, optional NameNormalized; split rows are rejoined, German system field
    names are mapped): customfield_<dc> -> customfield_<cloud>, cf[<dc>] -> cf[<cloud>],
    "<DcName>" -> "<CloudName>". Whitespace is collapsed and string comparison is
    case-insensitive unless -CaseSensitive. Missing key == null == "" (false is NOT
    treated as missing, so an unticked checkbox is reported).

    Volatile keys (ids, timestamps, schema versions, ...) are ignored, see $IgnoreKeys.

.OUTPUTS
    <OutputDir>\AutomationCompare_Summary_<ts>.csv   one row per rule
    <OutputDir>\AutomationCompare_Details_<ts>.csv   one row per difference
    <OutputDir>\json\<SafeRuleName>_<id>_DC.json / _Cloud.json   raw rule JSON
    <CacheDir>\dc\*.json, <CacheDir>\cloud\*.json   when -CacheDir is given

.PARAMETER SkipRuleNames
    Rule names to skip (already verified). Also see $SkipRuleNamesInScript below.
.PARAMETER RuleName
    Process only these rule name(s). Useful for re-checking a single rule after fixing it.
.PARAMETER Skip / MaxRules
    Offset / limit over the sorted DC rule list, for testing (-MaxRules 3).
.PARAMETER FullDiff
    Report every difference instead of stopping at the first one per rule.
.PARAMETER CacheDir
    Cache fetched rules on disk; reused on the next run unless -RefreshCache.

.EXAMPLE
    .\Compare-JiraAutomationRules.ps1 -DcBaseUrl https://jira.corp.de -DcToken $pat `
        -CloudBaseUrl https://acme.atlassian.net -CloudEmail me@acme.com -CloudApiToken $tok `
        -CustomFieldsCsv .\CustomFields.csv -OutputDir .\out -CacheDir .\cache -MaxRules 3 -Delimiter ';'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DcBaseUrl,
    [Parameter(Mandatory = $true)][string]$DcToken,
    [Parameter(Mandatory = $true)][string]$CloudBaseUrl,
    [string]$CloudEmail,
    [string]$CloudApiToken,
    [string]$CloudCookie,
    [Parameter(Mandatory = $true)][string]$CustomFieldsCsv,
    [string]$OutputDir = '.\AutomationCompare',
    [string]$CacheDir,
    [switch]$RefreshCache,
    [string]$Delimiter = ',',
    [string[]]$SkipRuleNames = @(),
    [string[]]$RuleName = @(),
    [int]$Skip = 0,
    [int]$MaxRules = 0,
    [switch]$FullDiff,
    [switch]$CaseSensitive,
    [switch]$IncludeCloudOnlyRules,
    [int]$MinNameLength = 3
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not $CloudCookie -and (-not $CloudEmail -or -not $CloudApiToken)) {
    throw 'Provide -CloudEmail and -CloudApiToken, or -CloudCookie.'
}

$UE = [char]0x00FC; $AE = [char]0x00E4; $OE = [char]0x00F6
$NL = "`n"

# ============================================================================
#  Rules already verified - skipped. Populate manually between runs.
# ============================================================================
$SkipRuleNamesInScript = @(
    # 'Beispiel: Ticket an Team zuweisen'
)

# ============================================================================
#  Keys ignored in the deep diff (metadata that legitimately differs).
# ============================================================================
$IgnoreKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($k in @('id', 'ruleId', 'parentId', 'clientKey', 'created', 'updated', 'lastUpdated',
                 'schemaVersion', 'uuid', 'connectionId', 'authorAccountId', 'actor', 'ruleScope',
                 'projects', 'tags', 'collaborators', 'ruleHome', 'ownerId', 'linkedRules')) {
    [void]$IgnoreKeys.Add($k)
}

# ============================================================================
#  DC (German) -> Cloud (English) system field names (from Find-FiltersWithCustomFields.ps1)
# ============================================================================
$SystemFieldNameMap = @{}
$SystemFieldNameMap['rang']                     = 'Rank'
$SystemFieldNameMap['gekennzeichnet']           = 'Flagged'
$SystemFieldNameMap['story-punkte']             = 'Story Points'
$SystemFieldNameMap['epic-name']                = 'Epic Name'
$SystemFieldNameMap["epic-verkn${UE}pfung"]     = 'Epic Link'
$SystemFieldNameMap['epic-status']              = 'Epic Status'
$SystemFieldNameMap['epic-farbe']               = 'Epic Colour'
$SystemFieldNameMap['anfrageteilnehmer']        = 'Request participants'
$SystemFieldNameMap['kundenanfragetyp']         = 'Request Type'
$SystemFieldNameMap['genehmigungen']            = 'Approvals'
$SystemFieldNameMap['organisationen']           = 'Organizations'
$SystemFieldNameMap['zufriedenheit']            = 'Satisfaction'
$SystemFieldNameMap['zufriedenheitsdatum']      = 'Satisfaction date'
$SystemFieldNameMap['entwicklung']              = 'Development'
$SystemFieldNameMap["gesch${AE}ftswert"]        = 'Business Value'

# ---------------------------------------------------------------- helpers ----

function Get-Val {
    param($Row, [string]$Name)
    if ($null -eq $Row) { return '' }
    $p = $Row.PSObject.Properties[$Name]
    if ($null -eq $p -or $null -eq $p.Value) { return '' }
    return [string]$p.Value
}

function Assert-Column {
    param($Row, [string[]]$Required, [string]$FileLabel)
    $have = @($Row.PSObject.Properties.Name)
    $missing = @($Required | Where-Object { $have -notcontains $_ })
    if ($missing.Count) {
        throw "$FileLabel is missing required column(s): $($missing -join ', '). Found: $($have -join ', ')"
    }
}

function Invoke-JsonGet {
    param([Parameter(Mandatory)][string]$Uri, [Parameter(Mandatory)][hashtable]$Headers)
    $resp = Invoke-WebRequest -Uri $Uri -Headers $Headers -Method Get -UseBasicParsing
    # decode explicitly as UTF-8: PS 5.1 otherwise falls back to the ANSI code page
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    if ($text.Length -gt 0 -and [int][char]$text[0] -eq 0xFEFF) { $text = $text.Substring(1) }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return ($text | ConvertFrom-Json)
}

function Get-ListPayload {
    param($Obj)
    if ($null -eq $Obj) { return @() }
    if ($Obj -is [System.Array]) { return $Obj }
    foreach ($k in 'data', 'items', 'values', 'rules', 'results') {
        $p = $Obj.PSObject.Properties[$k]
        if ($null -ne $p -and $p.Value -is [System.Array]) { return $p.Value }
    }
    return @($Obj)
}

function Get-Prop {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return $null }
    if ($Obj -is [System.Collections.IDictionary]) { if ($Obj.Contains($Name)) { return $Obj[$Name] } else { return $null } }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Test-HasProp {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return $false }
    if ($Obj -is [System.Collections.IDictionary]) { return $Obj.Contains($Name) }
    return ($null -ne $Obj.PSObject.Properties[$Name])
}

function Get-ArrayProp {
    # Property as array; $null / missing -> empty array (never @($null))
    param($Obj, [string]$Name)
    $v = Get-Prop $Obj $Name
    if ($null -eq $v) { return @() }
    return @($v)
}

function Test-IsObject { param($x) return ($x -is [System.Management.Automation.PSCustomObject] -or $x -is [System.Collections.IDictionary]) }
function Test-IsArray  { param($x) return ($x -is [System.Array] -or ($x -is [System.Collections.IList] -and $x -isnot [string])) }

function Get-PropNames {
    param($Obj)
    if ($Obj -is [System.Collections.IDictionary]) { return @($Obj.Keys) }
    return @($Obj.PSObject.Properties.Name)
}

function ConvertTo-SafeFileName {
    param([string]$Name, [int]$MaxLen = 100)
    $invalid = [System.IO.Path]::GetInvalidFileNameChars() + @('<', '>', '|', ':', '"', '/', '\', '?', '*')
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $Name.ToCharArray()) {
        if ($invalid -contains $ch -or [int]$ch -lt 32) { [void]$sb.Append('_') } else { [void]$sb.Append($ch) }
    }
    $s = $sb.ToString().Trim().TrimEnd('.').Trim()
    $s = $s -replace '\s+', ' '
    if ($s.Length -gt $MaxLen) { $s = $s.Substring(0, $MaxLen).Trim() }
    if (-not $s) { $s = 'rule' }
    return $s
}

function Write-Utf8Json {
    param([string]$Path, $Obj)
    $json = $Obj | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText($Path, $json, (New-Object System.Text.UTF8Encoding $true))
}

function Read-Utf8Json {
    param([string]$Path)
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    return ($text | ConvertFrom-Json)
}

function Get-RuleNameKey { param([string]$Name) return (($Name -replace '\s+', ' ').Trim().ToLowerInvariant()) }

function Format-Value {
    param($v)
    if ($null -eq $v) { return '<null>' }
    if ($v -is [string]) { return $v }
    if ($v -is [bool]) { return $v.ToString().ToLowerInvariant() }
    if (Test-IsArray $v) {
        if (@($v).Count -eq 0) { return '[]' }
        return (($v | ConvertTo-Json -Depth 50 -Compress))
    }
    if (Test-IsObject $v) { return ($v | ConvertTo-Json -Depth 50 -Compress) }
    return [string]$v
}

function Test-IsEmptyLike {
    param($v)
    if ($null -eq $v) { return $true }
    if ($v -is [string]) { return ([string]::IsNullOrWhiteSpace($v)) }
    if (Test-IsArray $v) { return (@($v).Count -eq 0) }
    return $false
}

# ------------------------------------------------------ input validation ----

foreach ($f in @($CustomFieldsCsv)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Input file not found: $f" }
}
$DcBaseUrl    = $DcBaseUrl.TrimEnd('/')
$CloudBaseUrl = $CloudBaseUrl.TrimEnd('/')
if ($CloudBaseUrl -notmatch '^https?://') { $CloudBaseUrl = "https://$CloudBaseUrl" }
if ($DcBaseUrl    -notmatch '^https?://') { $DcBaseUrl    = "https://$DcBaseUrl" }

$allSkip = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($n in @($SkipRuleNamesInScript) + @($SkipRuleNames)) { if ($n) { [void]$allSkip.Add((Get-RuleNameKey $n)) } }
$onlyNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($n in $RuleName) { if ($n) { [void]$onlyNames.Add((Get-RuleNameKey $n)) } }

$DcHeaders = @{ 'Accept' = 'application/json'; 'Authorization' = "Bearer $DcToken" }
$CloudHeaders = @{ 'Accept' = 'application/json'; 'X-Atlassian-Token' = 'no-check' }
if ($CloudCookie) {
    $CloudHeaders['Cookie'] = $CloudCookie
} else {
    $pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${CloudEmail}:${CloudApiToken}"))
    $CloudHeaders['Authorization'] = "Basic $pair"
}

$ts = Get-Date -Format 'yyyyMMdd_HHmmss'
if (-not (Test-Path -LiteralPath $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }
$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
$jsonDir = Join-Path $OutputDir 'json'
if (-not (Test-Path -LiteralPath $jsonDir)) { New-Item -ItemType Directory -Path $jsonDir -Force | Out-Null }
if ($CacheDir) {
    foreach ($sub in @('dc', 'cloud')) {
        $d = Join-Path $CacheDir $sub
        if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
    }
    $CacheDir = (Resolve-Path -LiteralPath $CacheDir).Path
}

# --------------------------------------------------- custom field lookups ----
#  Identical to Find-FiltersWithCustomFields.ps1 (split-row rejoin via NameNormalized,
#  system field name map).

$cfRows = @(Import-Csv -LiteralPath $CustomFieldsCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $cfRows.Count) { throw "Custom field CSV contains no rows: $CustomFieldsCsv" }
Assert-Column -Row $cfRows[0] -Required @('DcId', 'DcName', 'CloudId', 'CloudName') -FileLabel 'Custom field CSV'

$cloudIndex = @{}
foreach ($cf in $cfRows) {
    $cId = (Get-Val $cf 'CloudId').Trim()
    $cNm = (Get-Val $cf 'CloudName').Trim()
    if (-not $cId -and -not $cNm) { continue }
    $key = (Get-Val $cf 'NameNormalized').Trim().ToLowerInvariant()
    if (-not $key) { $key = $cNm.ToLowerInvariant() }
    if (-not $key) { continue }
    if (-not $cloudIndex.ContainsKey($key)) {
        $cloudIndex[$key] = [pscustomobject]@{ CloudId = $cId; CloudName = $cNm }
    }
}

$cfByNum = @{}
foreach ($cf in $cfRows) {
    $rawDcId    = (Get-Val $cf 'DcId').Trim()
    $rawDcName  = (Get-Val $cf 'DcName').Trim()
    $rawCloudId = (Get-Val $cf 'CloudId').Trim()
    $rawCloudNm = (Get-Val $cf 'CloudName').Trim()

    if ($rawDcId -notmatch '(\d+)') { continue }
    $dcNum = $Matches[1]

    $mk = ''
    if ($rawDcName) { $mk = $rawDcName.ToLowerInvariant() }
    $hasMapping = ($mk -and $SystemFieldNameMap.ContainsKey($mk))

    if ((-not $rawCloudNm -or -not $rawCloudId) -and $rawDcName) {
        $own = (Get-Val $cf 'NameNormalized').Trim().ToLowerInvariant()
        if (-not $own) { $own = $mk }
        $hit = $null
        if ($cloudIndex.ContainsKey($own)) { $hit = $cloudIndex[$own] }
        if ($null -eq $hit -and $hasMapping) {
            $tk = $SystemFieldNameMap[$mk].ToLowerInvariant()
            if ($cloudIndex.ContainsKey($tk)) { $hit = $cloudIndex[$tk] }
        }
        if ($null -ne $hit) {
            if (-not $rawCloudNm) { $rawCloudNm = $hit.CloudName }
            if (-not $rawCloudId) { $rawCloudId = $hit.CloudId }
        }
    }
    if (-not $rawCloudNm -and $hasMapping) { $rawCloudNm = $SystemFieldNameMap[$mk] }

    $cloudNum = ''
    if ($rawCloudId -match '(\d+)') { $cloudNum = $Matches[1] }

    if (-not $cfByNum.ContainsKey($dcNum)) {
        $cfByNum[$dcNum] = [pscustomobject]@{
            DcNum = $dcNum; DcName = $rawDcName; CloudNum = $cloudNum; CloudName = $rawCloudNm; HasMapping = $hasMapping
        }
    }
}

# Name replacements: DC name -> Cloud name (longest first so "Epic-Name" is not eaten by "Epic")
$nameReplacements = New-Object System.Collections.Generic.List[object]
$seenNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($f in $cfByNum.Values) {
    if (-not $f.DcName -or -not $f.CloudName) { continue }
    if ($f.DcName.Length -lt $MinNameLength) { continue }
    if ($f.DcName -ieq $f.CloudName) { continue }
    if (-not $seenNames.Add($f.DcName)) { continue }
    $nameReplacements.Add([pscustomobject]@{
        Dc = $f.DcName; Cloud = $f.CloudName
        Regex = [regex]::new('(?<!\w)' + [regex]::Escape($f.DcName) + '(?!\w)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [System.Text.RegularExpressions.RegexOptions]::Compiled)
    })
}
foreach ($k in $SystemFieldNameMap.Keys) {
    if ($seenNames.Add($k)) {
        $nameReplacements.Add([pscustomobject]@{
            Dc = $k; Cloud = $SystemFieldNameMap[$k]
            Regex = [regex]::new('(?<!\w)' + [regex]::Escape($k) + '(?!\w)',
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [System.Text.RegularExpressions.RegexOptions]::Compiled)
        })
    }
}
$nameReplacements = @($nameReplacements | Sort-Object { $_.Dc.Length } -Descending)
$cfIdRegex      = [regex]::new('customfield_(\d+)', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$cfBracketRegex = [regex]::new('cf\[(\d+)\]', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

function ConvertTo-CloudString {
    # Rewrites a DC string so that it should equal the Cloud string if migration was correct.
    param([string]$s)
    if ([string]::IsNullOrEmpty($s)) { return $s }
    $out = $cfIdRegex.Replace($s, {
        param($m)
        $n = $m.Groups[1].Value
        if ($cfByNum.ContainsKey($n) -and $cfByNum[$n].CloudNum) { return "customfield_$($cfByNum[$n].CloudNum)" }
        return $m.Value
    })
    $out = $cfBracketRegex.Replace($out, {
        param($m)
        $n = $m.Groups[1].Value
        if ($cfByNum.ContainsKey($n) -and $cfByNum[$n].CloudNum) { return "cf[$($cfByNum[$n].CloudNum)]" }
        return $m.Value
    })
    foreach ($r in $nameReplacements) { $out = $r.Regex.Replace($out, $r.Cloud) }
    return $out
}

function Normalize-String {
    param([string]$s)
    if ($null -eq $s) { return '' }
    $s = $s -replace "`r`n", "`n"
    $s = $s -replace '[ \t]+', ' '
    $s = ($s -split "`n" | ForEach-Object { $_.Trim() }) -join "`n"
    $s = $s.Trim()
    if (-not $CaseSensitive) { $s = $s.ToLowerInvariant() }
    return $s
}

# ------------------------------------------------------------ fetch rules ----

function Get-CachePath { param([string]$Side, [string]$Id) if (-not $CacheDir) { return $null }; return (Join-Path (Join-Path $CacheDir $Side) "$Id.json") }

function Get-DcRules {
    $listPath = Get-CachePath 'dc' '_list'
    if ($listPath -and -not $RefreshCache -and (Test-Path -LiteralPath $listPath)) {
        Write-Host 'DC: using cached rule list'
        return @(Get-ListPayload (Read-Utf8Json $listPath))
    }
    Write-Host 'DC: loading automation rules ...'
    $raw = Invoke-JsonGet -Uri "$DcBaseUrl/rest/cb-automation/latest/project/GLOBAL/rule" -Headers $DcHeaders
    $list = @(Get-ListPayload $raw)
    # list may be summaries only - fetch full config where components are missing
    $full = New-Object System.Collections.Generic.List[object]
    $i = 0
    foreach ($r in $list) {
        $i++
        if (-not (Test-HasProp $r 'components') -and -not (Test-HasProp $r 'trigger')) {
            Write-Progress -Activity 'DC rules' -Status "$i / $($list.Count)" -PercentComplete ([int](100 * $i / [Math]::Max(1, $list.Count)))
            $r = Invoke-JsonGet -Uri "$DcBaseUrl/rest/cb-automation/latest/project/GLOBAL/rule/$(Get-Prop $r 'id')" -Headers $DcHeaders
        }
        $full.Add($r)
    }
    Write-Progress -Activity 'DC rules' -Completed
    if ($listPath) { Write-Utf8Json -Path $listPath -Obj @($full) }
    return @($full)
}

function Get-CloudRules {
    $listPath = Get-CachePath 'cloud' '_list'
    if ($listPath -and -not $RefreshCache -and (Test-Path -LiteralPath $listPath)) {
        Write-Host 'Cloud: using cached rule list'
        return @(Get-ListPayload (Read-Utf8Json $listPath))
    }
    Write-Host 'Cloud: resolving cloudId ...'
    $tenant = Invoke-JsonGet -Uri "$CloudBaseUrl/_edge/tenant_info" -Headers $CloudHeaders
    $cloudId = Get-Prop $tenant 'cloudId'
    if (-not $cloudId) { throw 'Could not read cloudId from /_edge/tenant_info' }
    $api = "$CloudBaseUrl/gateway/api/automation/internal-api/jira/$cloudId/pro/rest/GLOBAL"

    Write-Host 'Cloud: loading automation rules ...'
    $all = New-Object System.Collections.Generic.List[object]
    $offset = 0; $limit = 100
    do {
        $page = Invoke-JsonGet -Uri "$api/rule?limit=$limit&offset=$offset" -Headers $CloudHeaders
        $batch = @(Get-ListPayload $page)
        foreach ($r in $batch) { $all.Add($r) }
        $offset += $limit
    } while ($batch.Count -ge $limit)

    $full = New-Object System.Collections.Generic.List[object]
    $i = 0
    foreach ($r in $all) {
        $i++
        $rid = [string](Get-Prop $r 'id')
        $cp = Get-CachePath 'cloud' $rid
        if ($cp -and -not $RefreshCache -and (Test-Path -LiteralPath $cp)) {
            $full.Add((Read-Utf8Json $cp)); continue
        }
        if (-not (Test-HasProp $r 'components') -and -not (Test-HasProp $r 'trigger')) {
            Write-Progress -Activity 'Cloud rules' -Status "$i / $($all.Count)" -PercentComplete ([int](100 * $i / [Math]::Max(1, $all.Count)))
            $r = Invoke-JsonGet -Uri "$api/rule/$rid" -Headers $CloudHeaders
        }
        if ($cp) { Write-Utf8Json -Path $cp -Obj $r }
        $full.Add($r)
    }
    Write-Progress -Activity 'Cloud rules' -Completed
    if ($listPath) { Write-Utf8Json -Path $listPath -Obj @($full) }
    return @($full)
}

function Get-DcProjectKeys {
    $map = @{}
    try {
        $projects = @(Get-ListPayload (Invoke-JsonGet -Uri "$DcBaseUrl/rest/api/2/project" -Headers $DcHeaders))
        foreach ($p in $projects) { $map[[string](Get-Prop $p 'id')] = [string](Get-Prop $p 'key') }
    } catch { Write-Warning "DC: could not load projects ($($_.Exception.Message)); scope shown as ids." }
    return $map
}

function Get-CloudProjectKeys {
    $map = @{}
    try {
        $startAt = 0
        do {
            $page = Invoke-JsonGet -Uri "$CloudBaseUrl/rest/api/3/project/search?startAt=$startAt&maxResults=50" -Headers $CloudHeaders
            $vals = Get-ArrayProp $page 'values'
            foreach ($p in $vals) { $map[[string](Get-Prop $p 'id')] = [string](Get-Prop $p 'key') }
            $startAt += 50
            $isLast = Get-Prop $page 'isLast'
        } while ($vals.Count -gt 0 -and -not $isLast)
    } catch { Write-Warning "Cloud: could not load projects ($($_.Exception.Message)); scope shown as ids." }
    return $map
}

# -------------------------------------------------------- rule accessors ----

function Get-RuleScope {
    param($Rule, [hashtable]$ProjectKeys)
    $ids = New-Object System.Collections.Generic.List[string]
    $projects = Get-ArrayProp $Rule 'projects'
    if ($projects.Count) {
        foreach ($p in $projects) {
            $pid = Get-Prop $p 'projectId'
            if ($null -eq $pid) { $pid = Get-Prop $p 'id' }
            if ($null -ne $pid) { $ids.Add([string]$pid) }
        }
    }
    $scope = Get-Prop $Rule 'ruleScope'
    if ($null -ne $scope) {
        foreach ($res in (Get-ArrayProp $scope 'resources')) {
            $s = [string]$res
            if ($s -match 'project/(\d+)') { $ids.Add($Matches[1]) }
            elseif ($s -match '^ari:cloud:jira::site/') { $ids.Add('GLOBAL') }
            elseif ($s) { $ids.Add($s) }
        }
    }
    if ($ids.Count -eq 0) { return 'GLOBAL' }
    $keys = foreach ($id in ($ids | Select-Object -Unique)) {
        if ($ProjectKeys.ContainsKey($id)) { $ProjectKeys[$id] } else { $id }
    }
    return (($keys | Sort-Object) -join ', ')
}

function Get-RuleActor {
    param($Rule)
    $a = Get-Prop $Rule 'actor'
    if ($null -eq $a) { return '' }
    if ($a -is [string]) { return $a }
    $t = Get-Prop $a 'type'; $v = Get-Prop $a 'value'
    $dn = Get-Prop $a 'displayName'
    if ($dn) { return "$t:$dn" }
    return "$t:$v"
}

function Get-RuleLabels {
    param($Rule)
    $l = Get-ArrayProp $Rule 'labels'
    if ($l.Count -eq 0) { return '' }
    $names = foreach ($x in $l) { if ($x -is [string]) { $x } else { $n = Get-Prop $x 'name'; if ($n) { $n } else { Format-Value $x } } }
    return (($names | Sort-Object) -join ', ')
}

function Get-FlatSteps {
    # Depth-first flatten of trigger + components, descending into children and conditions.
    param($Rule)
    $steps = New-Object System.Collections.Generic.List[object]
    $trigger = Get-Prop $Rule 'trigger'
    if ($null -ne $trigger) { Add-FlatStep -Node $trigger -Path 'T' -Steps $steps }
    $comps = Get-ArrayProp $Rule 'components'
    for ($i = 0; $i -lt $comps.Count; $i++) { Add-FlatStep -Node $comps[$i] -Path ([string]($i + 1)) -Steps $steps }
    return $steps
}

function Add-FlatStep {
    param($Node, [string]$Path, $Steps)
    if ($null -eq $Node) { return }
    $kind = [string](Get-Prop $Node 'component')
    $type = [string](Get-Prop $Node 'type')
    $Steps.Add([pscustomobject]@{ Path = $Path; Kind = $kind; Type = $type; Node = $Node })
    $conds = Get-ArrayProp $Node 'conditions'
    for ($i = 0; $i -lt $conds.Count; $i++) { Add-FlatStep -Node $conds[$i] -Path "$Path.c$($i + 1)" -Steps $Steps }
    $children = Get-ArrayProp $Node 'children'
    for ($i = 0; $i -lt $children.Count; $i++) { Add-FlatStep -Node $children[$i] -Path "$Path.$($i + 1)" -Steps $Steps }
}

# ------------------------------------------------------------- deep diff ----

function Compare-Deep {
    # Walks DC and Cloud values in parallel; DC strings are rewritten with the field mapping.
    # Returns $true when the diff list reached the stop limit (stop-at-first).
    param($Dc, $Cloud, [string]$Path, $Diffs, [bool]$StopAtFirst)

    if ($StopAtFirst -and $Diffs.Count -gt 0) { return }

    $dcEmpty = Test-IsEmptyLike $Dc; $clEmpty = Test-IsEmptyLike $Cloud
    if ($dcEmpty -and $clEmpty) { return }
    if ($dcEmpty -ne $clEmpty) {
        $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value $Dc); Cloud = (Format-Value $Cloud) }); return
    }

    $dcObj = Test-IsObject $Dc; $clObj = Test-IsObject $Cloud
    $dcArr = Test-IsArray $Dc;  $clArr = Test-IsArray $Cloud

    if ($dcObj -and $clObj) {
        $keys = @(Get-PropNames $Dc) + @(Get-PropNames $Cloud) | Select-Object -Unique
        foreach ($k in $keys) {
            if ($IgnoreKeys.Contains($k)) { continue }
            if ($k -ieq 'children' -or $k -ieq 'conditions') { continue }   # compared as separate steps
            $dv = $null; $cv = $null
            if (Test-HasProp $Dc $k)    { $dv = Get-Prop $Dc $k }
            if (Test-HasProp $Cloud $k) { $cv = Get-Prop $Cloud $k }
            Compare-Deep -Dc $dv -Cloud $cv -Path "$Path.$k" -Diffs $Diffs -StopAtFirst $StopAtFirst
            if ($StopAtFirst -and $Diffs.Count -gt 0) { return }
        }
        return
    }
    if ($dcArr -and $clArr) {
        $da = @($Dc); $ca = @($Cloud)
        if ($da.Count -ne $ca.Count) {
            $Diffs.Add([pscustomobject]@{ Path = "$Path (count)"; Dc = "$($da.Count) items: $(Format-Value $da)"; Cloud = "$($ca.Count) items: $(Format-Value $ca)" })
            return
        }
        for ($i = 0; $i -lt $da.Count; $i++) {
            Compare-Deep -Dc $da[$i] -Cloud $ca[$i] -Path "$Path[$i]" -Diffs $Diffs -StopAtFirst $StopAtFirst
            if ($StopAtFirst -and $Diffs.Count -gt 0) { return }
        }
        return
    }
    if ($dcObj -or $clObj -or $dcArr -or $clArr) {
        $Diffs.Add([pscustomobject]@{ Path = "$Path (type)"; Dc = (Format-Value $Dc); Cloud = (Format-Value $Cloud) }); return
    }

    # scalars
    if ($Dc -is [bool] -or $Cloud -is [bool]) {
        if ([string]$Dc -ine [string]$Cloud) {
            $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value $Dc); Cloud = (Format-Value $Cloud) })
        }
        return
    }
    $ds = Normalize-String (ConvertTo-CloudString ([string]$Dc))
    $cs = Normalize-String ([string]$Cloud)
    if ($ds -cne $cs) {
        $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value $Dc); Cloud = (Format-Value $Cloud) })
    }
}

# ================================================================= main ====

$dcRules    = Get-DcRules
$cloudRules = Get-CloudRules
Write-Host ("DC rules: {0}, Cloud rules: {1}" -f $dcRules.Count, $cloudRules.Count)

$dcProjectKeys    = Get-DcProjectKeys
$cloudProjectKeys = Get-CloudProjectKeys

# index by name
$dcByName = @{}; $cloudByName = @{}
foreach ($r in $dcRules) {
    $k = Get-RuleNameKey ([string](Get-Prop $r 'name'))
    if (-not $dcByName.ContainsKey($k)) { $dcByName[$k] = New-Object System.Collections.Generic.List[object] }
    $dcByName[$k].Add($r)
}
foreach ($r in $cloudRules) {
    $k = Get-RuleNameKey ([string](Get-Prop $r 'name'))
    if (-not $cloudByName.ContainsKey($k)) { $cloudByName[$k] = New-Object System.Collections.Generic.List[object] }
    $cloudByName[$k].Add($r)
}
foreach ($k in $dcByName.Keys)    { if ($dcByName[$k].Count -gt 1)    { Write-Warning "DC has $($dcByName[$k].Count) rules named ""$(Get-Prop $dcByName[$k][0] 'name')""." } }
foreach ($k in $cloudByName.Keys) { if ($cloudByName[$k].Count -gt 1) { Write-Warning "Cloud has $($cloudByName[$k].Count) rules named ""$(Get-Prop $cloudByName[$k][0] 'name')""." } }

# work list: DC rules sorted by name, filtered, offset/limit
$work = @($dcRules | Sort-Object { [string](Get-Prop $_ 'name') })
$work = @($work | Where-Object {
    $k = Get-RuleNameKey ([string](Get-Prop $_ 'name'))
    (-not $allSkip.Contains($k)) -and ($onlyNames.Count -eq 0 -or $onlyNames.Contains($k))
})
if ($Skip -gt 0)     { $work = @($work | Select-Object -Skip $Skip) }
if ($MaxRules -gt 0) { $work = @($work | Select-Object -First $MaxRules) }
Write-Host ("Processing {0} DC rule(s) (skip {1}, max {2}, {3} name(s) on skip list)" -f $work.Count, $Skip, $MaxRules, $allSkip.Count)

$summary = New-Object System.Collections.Generic.List[object]
$details = New-Object System.Collections.Generic.List[object]
$stopAtFirst = -not $FullDiff

function New-SummaryRow {
    param([string]$Name, $Dc, $Cloud, [string]$MatchStatus)
    $row = [ordered]@{
        'Rule Name'            = $Name
        'Match Status'         = $MatchStatus
        'Verdict'              = ''
        'Differences'          = 0
        'First Difference'     = ''
        'DC Rule ID'           = ''
        'Cloud Rule ID'        = ''
        'DC State'             = ''
        'Cloud State'          = ''
        'DC Scope'             = ''
        'Cloud Scope'          = ''
        'DC Actor'             = ''
        'Cloud Actor'          = ''
        'DC Allow Other Rules' = ''
        'Cloud Allow Other Rules' = ''
        'DC Notify On Error'   = ''
        'Cloud Notify On Error'= ''
        'DC Edit Access'       = ''
        'Cloud Edit Access'    = ''
        'DC Labels'            = ''
        'Cloud Labels'         = ''
        'DC Steps'             = ''
        'Cloud Steps'          = ''
        'DC JSON'              = ''
        'Cloud JSON'           = ''
    }
    if ($null -ne $Dc) {
        $row['DC Rule ID']           = [string](Get-Prop $Dc 'id')
        $row['DC State']             = [string](Get-Prop $Dc 'state')
        $row['DC Scope']             = Get-RuleScope $Dc $dcProjectKeys
        $row['DC Actor']             = Get-RuleActor $Dc
        $row['DC Allow Other Rules'] = Format-Value (Get-Prop $Dc 'canOtherRuleTrigger')
        $row['DC Notify On Error']   = [string](Get-Prop $Dc 'notifyOnError')
        $row['DC Edit Access']       = [string](Get-Prop $Dc 'writeAccessType')
        $row['DC Labels']            = Get-RuleLabels $Dc
    }
    if ($null -ne $Cloud) {
        $row['Cloud Rule ID']           = [string](Get-Prop $Cloud 'id')
        $row['Cloud State']             = [string](Get-Prop $Cloud 'state')
        $row['Cloud Scope']             = Get-RuleScope $Cloud $cloudProjectKeys
        $row['Cloud Actor']             = Get-RuleActor $Cloud
        $row['Cloud Allow Other Rules'] = Format-Value (Get-Prop $Cloud 'canOtherRuleTrigger')
        $row['Cloud Notify On Error']   = [string](Get-Prop $Cloud 'notifyOnError')
        $row['Cloud Edit Access']       = [string](Get-Prop $Cloud 'writeAccessType')
        $row['Cloud Labels']            = Get-RuleLabels $Cloud
    }
    return $row
}

function Add-Detail {
    param($Row, [string]$Area, [string]$StepPath, [string]$StepKind, [string]$StepType, [string]$Prop, [string]$DcVal, [string]$CloudVal)
    $details.Add([pscustomobject]@{
        'Rule Name'      = $Row['Rule Name']
        'DC Rule ID'     = $Row['DC Rule ID']
        'Cloud Rule ID'  = $Row['Cloud Rule ID']
        'Area'           = $Area
        'Step Path'      = $StepPath
        'Step Kind'      = $StepKind
        'Step Type'      = $StepType
        'Property'       = $Prop
        'DC Value'       = $DcVal
        'Cloud Value'    = $CloudVal
    })
    $Row['Differences'] = [int]$Row['Differences'] + 1
    if (-not $Row['First Difference']) {
        $label = if ($StepPath) { "[$StepPath $StepType] $Prop" } else { $Prop }
        $Row['First Difference'] = "$label | DC: $DcVal | Cloud: $CloudVal"
    }
}

function Write-RuleJsonFiles {
    param($Row, $Dc, $Cloud)
    $base = ConvertTo-SafeFileName $Row['Rule Name']
    if ($null -ne $Dc) {
        $p = Join-Path $jsonDir ("{0}_{1}_DC.json" -f $base, $Row['DC Rule ID'])
        Write-Utf8Json -Path $p -Obj $Dc; $Row['DC JSON'] = $p
    }
    if ($null -ne $Cloud) {
        $p = Join-Path $jsonDir ("{0}_{1}_Cloud.json" -f $base, $Row['Cloud Rule ID'])
        Write-Utf8Json -Path $p -Obj $Cloud; $Row['Cloud JSON'] = $p
    }
}

$n = 0
foreach ($dc in $work) {
    $n++
    $name = [string](Get-Prop $dc 'name')
    $key  = Get-RuleNameKey $name
    Write-Host ("[{0}/{1}] {2}" -f $n, $work.Count, $name)

    $matchStatus = 'Matched'
    $cloud = $null
    if ($cloudByName.ContainsKey($key)) {
        if ($cloudByName[$key].Count -gt 1 -or $dcByName[$key].Count -gt 1) { $matchStatus = 'DuplicateName' }
        $cloud = $cloudByName[$key][0]
    } else {
        $matchStatus = 'NotFoundInCloud'
    }

    $row = New-SummaryRow -Name $name -Dc $dc -Cloud $cloud -MatchStatus $matchStatus
    Write-RuleJsonFiles -Row $row -Dc $dc -Cloud $cloud

    if ($null -eq $cloud) {
        $row['Verdict'] = 'NotFoundInCloud'
        $row['DC Steps'] = (Get-FlatSteps $dc).Count
        $summary.Add([pscustomobject]$row); continue
    }

    # ---- 1. rule-level settings -------------------------------------------
    $ruleChecks = @(
        @{ Name = 'State';             Dc = $row['DC State'];             Cloud = $row['Cloud State'] }
        @{ Name = 'Scope';             Dc = $row['DC Scope'];             Cloud = $row['Cloud Scope'] }
        @{ Name = 'Allow Other Rules'; Dc = $row['DC Allow Other Rules']; Cloud = $row['Cloud Allow Other Rules'] }
        @{ Name = 'Notify On Error';   Dc = $row['DC Notify On Error'];   Cloud = $row['Cloud Notify On Error'] }
        @{ Name = 'Edit Access';       Dc = $row['DC Edit Access'];       Cloud = $row['Cloud Edit Access'] }
        @{ Name = 'Labels';            Dc = $row['DC Labels'];            Cloud = $row['Cloud Labels'] }
        @{ Name = 'Description';       Dc = [string](Get-Prop $dc 'description'); Cloud = [string](Get-Prop $cloud 'description') }
    )
    $stop = $false
    foreach ($c in $ruleChecks) {
        $a = Normalize-String (ConvertTo-CloudString ([string]$c.Dc)); $b = Normalize-String ([string]$c.Cloud)
        if ($a -cne $b) {
            Add-Detail -Row $row -Area 'Rule' -StepPath '' -StepKind '' -StepType '' -Prop $c.Name -DcVal ([string]$c.Dc) -CloudVal ([string]$c.Cloud)
            if ($stopAtFirst) { $stop = $true; break }
        }
    }

    # ---- 2. step count ---------------------------------------------------------
    $dcSteps = Get-FlatSteps $dc; $clSteps = Get-FlatSteps $cloud
    $row['DC Steps'] = $dcSteps.Count; $row['Cloud Steps'] = $clSteps.Count
    if (-not $stop -and $dcSteps.Count -ne $clSteps.Count) {
        $dcList = ($dcSteps | ForEach-Object { "$($_.Path) $($_.Kind) $($_.Type)" }) -join $NL
        $clList = ($clSteps | ForEach-Object { "$($_.Path) $($_.Kind) $($_.Type)" }) -join $NL
        Add-Detail -Row $row -Area 'Steps' -StepPath '' -StepKind '' -StepType '' -Prop 'Step count' -DcVal "$($dcSteps.Count)$NL$dcList" -CloudVal "$($clSteps.Count)$NL$clList"
        if ($stopAtFirst) { $stop = $true }
    }

    # ---- 3./4. per step ------------------------------------------------------------
    if (-not $stop) {
        $count = [Math]::Min($dcSteps.Count, $clSteps.Count)
        for ($i = 0; $i -lt $count -and -not $stop; $i++) {
            $ds = $dcSteps[$i]; $cs = $clSteps[$i]
            if ($ds.Path -ne $cs.Path -or $ds.Kind -ine $cs.Kind -or $ds.Type -ine $cs.Type) {
                Add-Detail -Row $row -Area 'Step' -StepPath $ds.Path -StepKind $ds.Kind -StepType $ds.Type -Prop 'Step kind/type' `
                    -DcVal "$($ds.Path) $($ds.Kind) $($ds.Type)" -CloudVal "$($cs.Path) $($cs.Kind) $($cs.Type)"
                if ($stopAtFirst) { $stop = $true; break }
                continue
            }
            $diffs = New-Object System.Collections.Generic.List[object]
            Compare-Deep -Dc $ds.Node -Cloud $cs.Node -Path '' -Diffs $diffs -StopAtFirst $stopAtFirst
            foreach ($d in $diffs) {
                Add-Detail -Row $row -Area 'StepConfig' -StepPath $ds.Path -StepKind $ds.Kind -StepType $ds.Type -Prop ($d.Path.TrimStart('.')) -DcVal $d.Dc -CloudVal $d.Cloud
                if ($stopAtFirst) { $stop = $true; break }
            }
        }
    }

    if ([int]$row['Differences'] -eq 0) { $row['Verdict'] = 'Identical' }
    elseif ($stopAtFirst)                 { $row['Verdict'] = 'Different (stopped at first)' }
    else                                   { $row['Verdict'] = 'Different' }
    $summary.Add([pscustomobject]$row)
}

# Cloud-only rules (no DC counterpart) - only when not limiting, or when explicitly asked
if ($IncludeCloudOnlyRules -or ($MaxRules -eq 0 -and $Skip -eq 0 -and $onlyNames.Count -eq 0)) {
    foreach ($k in $cloudByName.Keys) {
        if ($dcByName.ContainsKey($k) -or $allSkip.Contains($k)) { continue }
        foreach ($cr in $cloudByName[$k]) {
            $row = New-SummaryRow -Name ([string](Get-Prop $cr 'name')) -Dc $null -Cloud $cr -MatchStatus 'NotFoundInDC'
            $row['Verdict'] = 'NotFoundInDC'
            $row['Cloud Steps'] = (Get-FlatSteps $cr).Count
            Write-RuleJsonFiles -Row $row -Dc $null -Cloud $cr
            $summary.Add([pscustomobject]$row)
        }
    }
}

# ------------------------------------------------------------------ output ----

if ($PSVersionTable.PSVersion.Major -ge 6) { $enc = 'utf8BOM' } else { $enc = 'UTF8' }
$summaryPath = Join-Path $OutputDir "AutomationCompare_Summary_$ts.csv"
$detailsPath = Join-Path $OutputDir "AutomationCompare_Details_$ts.csv"
$summary | Export-Csv -LiteralPath $summaryPath -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
if ($details.Count) {
    $details | Export-Csv -LiteralPath $detailsPath -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
} else {
    [System.IO.File]::WriteAllText($detailsPath, ('"Rule Name"' + $Delimiter + '"DC Rule ID"' + $Delimiter + '"Cloud Rule ID"' + $Delimiter + '"Area"' + $Delimiter + '"Step Path"' + $Delimiter + '"Step Kind"' + $Delimiter + '"Step Type"' + $Delimiter + '"Property"' + $Delimiter + '"DC Value"' + $Delimiter + '"Cloud Value"' + "`r`n"), (New-Object System.Text.UTF8Encoding $true))
}

$ident = @($summary | Where-Object { $_.Verdict -eq 'Identical' }).Count
$diff  = @($summary | Where-Object { $_.Verdict -like 'Different*' }).Count
$noCl  = @($summary | Where-Object { $_.Verdict -eq 'NotFoundInCloud' }).Count
$noDc  = @($summary | Where-Object { $_.Verdict -eq 'NotFoundInDC' }).Count
Write-Host ("Done. Identical: {0}, Different: {1}, NotFoundInCloud: {2}, NotFoundInDC: {3}" -f $ident, $diff, $noCl, $noDc)
Write-Host "Summary : $summaryPath"
Write-Host "Details : $detailsPath"
Write-Host "JSON    : $jsonDir"
