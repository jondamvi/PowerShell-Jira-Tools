#requires -Version 5.1
<#
.SYNOPSIS
    Reads every filter from Jira Cloud, runs custom field discovery against the
    Cloud JQL, aligns each filter to the DC inventory CSV by filter name, and
    writes a remediation report.

.DESCRIPTION
    Filters are read from /rest/api/3/filter/search with overrideSharePermissions=true
    so private filters are included. That parameter requires the Administer Jira
    global permission.

    All Cloud responses are decoded as UTF-8 explicitly, so German characters
    survive on Windows PowerShell 5.1 as well as PowerShell 7.

.PARAMETER CloudBaseUrl
    e.g. https://yoursite.atlassian.net

.PARAMETER CloudEmail
    Atlassian account e-mail used for Basic auth.

.PARAMETER CloudApiToken
    API token for that account (id.atlassian.com > Security > API tokens).

.PARAMETER CloudFiltersCacheJson
    Optional. If the file exists it is read instead of calling the API; if it does
    not exist, the fetched filters are written there. Saves re-fetching while
    iterating on the report.

.PARAMETER FilterInventoryCsv
    DC inventory CSV. Must contain "Filter Name".

.PARAMETER CustomFieldsCsv
    Custom field CSV. Must contain "DcId", "DcName", "CloudId", "CloudName";
    "NameNormalized" is used when present to rejoin rows split over two lines.

.EXAMPLE
    .\Find-AffectedFilters.ps1 -CloudBaseUrl https://acme.atlassian.net `
        -CloudEmail me@acme.com -CloudApiToken $tok `
        -FilterInventoryCsv .\FilterInventory.csv -CustomFieldsCsv .\CustomFields.csv `
        -OutputCsv .\affected-fields.csv -StatusOutputCsv .\affected-statuses.csv
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CloudBaseUrl,
    [Parameter(Mandatory = $true)][string]$CloudEmail,
    [Parameter(Mandatory = $true)][string]$CloudApiToken,
    [Parameter(Mandatory = $true)][string]$FilterInventoryCsv,
    [Parameter(Mandatory = $true)][string]$CustomFieldsCsv,
    [Parameter(Mandatory = $true)][string]$OutputCsv,
    [string]$StatusOutputCsv,
    [string]$CloudFiltersCacheJson,
    [ValidateSet('GlobalAndPrivate', 'All')]
    [string]$FilterScope = 'GlobalAndPrivate',
    [string]$Delimiter = ',',
    [int]$MinNameLength = 3,
    [switch]$SkipNameMatching,
    [switch]$NoBom
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ARROW = [char]0x2192   # U+2192 RIGHTWARDS ARROW
$DASH  = [char]0x2014   # U+2014 EM DASH
$UE    = [char]0x00FC   # u-umlaut
$AE    = [char]0x00E4   # a-umlaut
$OE    = [char]0x00F6   # o-umlaut
$NL    = "`n"           # in-cell line break

# ============================================================================
#  Pre-defined DC (German) -> Cloud (English) names for Jira built-in fields.
#  Also used as the join key when the custom field CSV splits a field over two
#  lines. Keys are lower-case DC names.
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

# ============================================================================
#  One-sentence notes for deprecated fields. Keys are lower-case DC names.
# ============================================================================
$ReviewFieldNotes = @{}
$ReviewFieldNotes["epic-verkn${UE}pfung"]  = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.'
$ReviewFieldNotes['epic link']             = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.'
$ReviewFieldNotes['epic-name']             = 'Custom field "Epic Name" is deprecated in Cloud.'
$ReviewFieldNotes['epic name']             = 'Custom field "Epic Name" is deprecated in Cloud.'
$ReviewFieldNotes['parent link']           = 'Custom field "Parent Link" is deprecated in Cloud, use "parent" instead.'

# ============================================================================
#  Fields with no Cloud counterpart. No remap is proposed and no missing
#  id/name warning is raised - the filter has to be rewritten. Case-insensitive.
# ============================================================================
$UnsupportedInCloudFields = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($u in @('issueFunction', 'Original story points', 'Gruppen', 'Groups')) {
    [void]$UnsupportedInCloudFields.Add($u)
}

# ============================================================================
#  Clause names deprecated in Cloud, matched against the raw JQL so they are
#  caught even when the filter references the field by id.
# ============================================================================
$DeprecatedJqlTerms = @(
    [pscustomobject]@{ Term = 'Epic Link';                  Note = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = "Epic-Verkn${UE}pfung";       Note = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = 'Epic Name';                  Note = 'Custom field "Epic Name" is deprecated in Cloud.' }
    [pscustomobject]@{ Term = 'Epic-Name';                  Note = 'Custom field "Epic Name" is deprecated in Cloud.' }
    [pscustomobject]@{ Term = 'Parent Link';                Note = 'Custom field "Parent Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = 'parentEpic';                 Note = 'JQL function "parentEpic" is deprecated in Cloud, use "parent" instead.' }
)
foreach ($d in $DeprecatedJqlTerms) {
    $d | Add-Member -NotePropertyName Regex -NotePropertyValue ([regex]::new(
        '(?<!\w)' + [regex]::Escape($d.Term) + '(?!\w)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled))
}

# ============================================================================
#  Jira out-of-the-box statuses, English and German. Anything else is reported
#  as a custom status. Case-insensitive. Extend to match your own instance.
# ============================================================================
$BuiltInStatuses = @(
    'Open', 'In Progress', 'Reopened', 'Resolved', 'Closed',
    'To Do', 'In Review', 'Under Review', 'Done', 'Backlog',
    'Selected for Development', 'Approved', 'Rejected', 'Cancelled', 'Canceled',
    'Waiting for support', 'Waiting for customer', 'Pending', 'Escalated',
    'Work in progress', 'Under investigation', 'Declined', 'Completed',
    'Offen', 'In Arbeit', "Wiederer" + $OE + "ffnet", 'Erledigt', 'Geschlossen',
    'Zu erledigen', 'Fertig', "In Pr${UE}fung", "R${UE}ckstand",
    "Zur Entwicklung ausgew${AE}hlt", 'Genehmigt', 'Abgelehnt', 'Storniert',
    'Warten auf Support', 'Warten auf Kunde', 'Ausstehend', 'Eskaliert',
    'In Bearbeitung', 'Wird untersucht', 'Abgeschlossen'
)
$BuiltInStatusSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($s in $BuiltInStatuses) { [void]$BuiltInStatusSet.Add($s) }

# ---------------------------------------------------------------- regexes ----

$idRegex      = [regex]::new('(?<!\w)customfield[_\s]*(\d+)(?!\d)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$bracketRegex = [regex]::new('(?<!\w)cf\s*\[\s*(\d+)\s*\]',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)

$statusSingleRegex = [regex]::new(
    '(?<!\w)"?status"?(?!\w)\s*(?:=|!=|~|!~|was\s+not(?!\s+in)|was(?!\s+(?:not\s+)?in)|changed\s+(?:to|from))\s*(?:"([^"]*)"|''([^'']*)''|([^\s()"'',]+))',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$statusListRegex = [regex]::new(
    '(?<!\w)"?status"?(?!\w)\s*(?:(?:was\s+)?(?:not\s+)?in)\s*\(([^)]*)\)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$listItemRegex = [regex]::new('"([^"]*)"|''([^'']*)''|([^,\s][^,]*)')

$errLineRegex  = [regex]::new('^\s*ERROR\s*\d+\s*:\s*(.*?)\s*$',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$errFieldRegex = [regex]::new("^Field\s+'(.+?)'\s+does not exist or you do not have permission to view it\.?$",
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$errValueRegex = [regex]::new("^The value\s+'(.+?)'\s+does not exist for the field\s+'(.+?)'\.?$",
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

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

function Get-StatusesFromJql {
    param([string]$Jql)
    $found = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Jql)) { return $found.ToArray() }

    $add = {
        param([string]$v)
        $v = $v.Trim().Trim('"').Trim("'").Trim()
        if (-not $v) { return }
        if ($v -imatch '^(EMPTY|NULL|IN)$') { return }
        foreach ($e in $found) { if ($e -ieq $v) { return } }
        $found.Add($v)
    }

    foreach ($m in $statusListRegex.Matches($Jql)) {
        foreach ($im in $listItemRegex.Matches($m.Groups[1].Value)) {
            $val = if ($im.Groups[1].Success) { $im.Groups[1].Value }
                   elseif ($im.Groups[2].Success) { $im.Groups[2].Value }
                   else { $im.Groups[3].Value }
            & $add $val
        }
    }
    foreach ($m in $statusSingleRegex.Matches($Jql)) {
        $val = if ($m.Groups[1].Success) { $m.Groups[1].Value }
               elseif ($m.Groups[2].Success) { $m.Groups[2].Value }
               else { $m.Groups[3].Value }
        & $add $val
    }
    return $found.ToArray()
}

function ConvertFrom-FilterErrors {
    param([string]$Text)
    $notes = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Text)) { return $notes.ToArray() }
    foreach ($line in ($Text -split "`r?`n")) {
        $m = $errLineRegex.Match($line)
        if (-not $m.Success) { continue }
        $bodyTxt = $m.Groups[1].Value.Trim().Trim('"').Trim()
        if (-not $bodyTxt) { continue }

        $mf = $errFieldRegex.Match($bodyTxt)
        if ($mf.Success) { $notes.Add("Error: Custom field '$($mf.Groups[1].Value)' does not exist."); continue }
        $mv = $errValueRegex.Match($bodyTxt)
        if ($mv.Success) { $notes.Add("Error: Value '$($mv.Groups[1].Value)' does not exist for field '$($mv.Groups[2].Value)'."); continue }
        $notes.Add("Error: $bodyTxt")
    }
    if ($notes.Count -gt 0) { $notes.Insert(0, 'Filter was not working in DC due to errors.') }
    return $notes.ToArray()
}

# ------------------------------------------------------------ Jira Cloud -----

if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

$script:JiraBase = $CloudBaseUrl.TrimEnd('/')
$script:JiraHeaders = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("${CloudEmail}:${CloudApiToken}"))
    Accept        = 'application/json'
}

function Invoke-JiraGet {
    param([string]$Uri)
    $resp = Invoke-WebRequest -Uri $Uri -Headers $script:JiraHeaders -Method Get -UseBasicParsing
    # decode explicitly as UTF-8: PS 5.1 otherwise falls back to the ANSI code page
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    return ($text | ConvertFrom-Json)
}

function Get-CloudFilters {
    $all  = New-Object System.Collections.Generic.List[object]
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
    $script:ShareTypeCounts = @{}
    $script:OutOfScope = 0
    $startAt  = 0
    $pageSize = 50
    $dupes    = 0
    do {
        $uri = "$script:JiraBase/rest/api/3/filter/search" +
               "?startAt=$startAt&maxResults=$pageSize" +
               "&overrideSharePermissions=true" +
               "&expand=jql,owner,sharePermissions"
        Write-Verbose "GET $uri"
        $page = Invoke-JiraGet -Uri $uri

        $batch = @()
        if ($page.PSObject.Properties['values'] -and $page.values) { $batch = @($page.values) }
        foreach ($v in $batch) {
            if (-not $seen.Add([string]$v.id)) { $dupes++; continue }

            $tKey = (@(Get-FilterShareTypes -Filter $v) -join ', ')
            if (-not $tKey) { $tKey = '(private)' }
            if ($script:ShareTypeCounts.ContainsKey($tKey)) { $script:ShareTypeCounts[$tKey]++ }
            else { $script:ShareTypeCounts[$tKey] = 1 }

            if (Test-FilterInScope -Filter $v) { $all.Add($v) } else { $script:OutOfScope++ }
        }

        $isLast = $true
        if ($page.PSObject.Properties['isLast']) { $isLast = [bool]$page.isLast }

        # advance by what the server actually returned, not by what we asked for
        if ($batch.Count -eq 0) { break }
        $startAt += $batch.Count

        Write-Progress -Activity 'Reading filters from Jira Cloud' -Status "$($all.Count) fetched"
    } while (-not $isLast)
    Write-Progress -Activity 'Reading filters from Jira Cloud' -Completed
    if ($dupes) { Write-Warning "$dupes duplicate filter(s) returned by paging and ignored." }

    Write-Host "Filters by share scope:"
    foreach ($k in ($script:ShareTypeCounts.Keys | Sort-Object)) {
        Write-Host ("  {0,-40} {1}" -f $k, $script:ShareTypeCounts[$k])
    }
    if ($script:OutOfScope) {
        Write-Host "Skipped $($script:OutOfScope) filter(s) outside -FilterScope $FilterScope. Use -FilterScope All to keep them."
    }
    return $all.ToArray()
}

function Get-FilterShareTypes {
    param($Filter)
    $types = @()
    if ($Filter.PSObject.Properties['sharePermissions'] -and $null -ne $Filter.sharePermissions) {
        foreach ($p in $Filter.sharePermissions) {
            if ($p.PSObject.Properties['type'] -and $p.type) { $types += [string]$p.type }
        }
    }
    return ,@($types | Sort-Object -Unique)
}

function Test-FilterInScope {
    param($Filter)
    if ($FilterScope -eq 'All') { return $true }
    $types = @(Get-FilterShareTypes -Filter $Filter)
    if ($types.Count -eq 0) { return $true }                       # private
    foreach ($t in $types) {
        if ($t -in @('global', 'loggedin', 'authenticated')) { return $true }
    }
    return $false
}

function Get-CloudFilterType {
    param($Filter)
    if (-not $Filter.PSObject.Properties['sharePermissions'] -or $null -eq $Filter.sharePermissions) { return 'Private' }
    $types = @()
    foreach ($p in $Filter.sharePermissions) {
        if ($p.PSObject.Properties['type'] -and $p.type) { $types += [string]$p.type }
    }
    $types = @($types | Sort-Object -Unique)
    if (-not $types.Count) { return 'Private' }
    return 'Shared (' + ($types -join ', ') + ')'
}

# ------------------------------------------------------------ load inputs ----

foreach ($f in @($FilterInventoryCsv, $CustomFieldsCsv)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Input file not found: $f" }
}

$cfRows = @(Import-Csv -LiteralPath $CustomFieldsCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $cfRows.Count) { throw "Custom field CSV contains no rows: $CustomFieldsCsv" }
Assert-Column -Row $cfRows[0] -Required @('DcId', 'DcName', 'CloudId', 'CloudName') -FileLabel 'Custom field CSV'

$dcRows = @(Import-Csv -LiteralPath $FilterInventoryCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $dcRows.Count) { throw "Filter inventory CSV contains no rows: $FilterInventoryCsv" }
Assert-Column -Row $dcRows[0] -Required @('Filter Name') -FileLabel 'Filter inventory CSV'

# DC inventory indexed by filter name
$dcByName = @{}
foreach ($r in $dcRows) {
    $nm = (Get-Val $r 'Filter Name').Trim()
    if (-not $nm) { continue }
    $k = $nm.ToLowerInvariant()
    if (-not $dcByName.ContainsKey($k)) {
        $dcByName[$k] = New-Object System.Collections.Generic.List[object]
    }
    $dcByName[$k].Add($r)
}

# --------------------------------------------------- custom field lookups ----

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

$cfByNum       = @{}
$nameToNum     = @{}
$nameCanonical = @{}
$skippedShort  = New-Object System.Collections.Generic.List[string]

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
            DcNum      = $dcNum
            DcName     = $rawDcName
            CloudNum   = $cloudNum
            CloudName  = $rawCloudNm
            HasMapping = $hasMapping
        }
    }

    if ($rawDcName) {
        if ($rawDcName.Length -lt $MinNameLength) {
            if ($skippedShort -notcontains $rawDcName) { $skippedShort.Add($rawDcName) }
            continue
        }
        $key = $rawDcName.ToLowerInvariant()
        if (-not $nameToNum.ContainsKey($key)) {
            $nameToNum[$key]     = New-Object System.Collections.Generic.List[string]
            $nameCanonical[$key] = $rawDcName
        }
        if (-not $nameToNum[$key].Contains($dcNum)) { $nameToNum[$key].Add($dcNum) }
    }
}

if ($skippedShort.Count) {
    Write-Warning ("Name matching skipped for {0} name(s) shorter than {1} chars: {2}" -f `
        $skippedShort.Count, $MinNameLength, ($skippedShort -join ', '))
}

# Cloud JQL references Cloud names, so match on those; fall back to the DC name
# when the field has no Cloud name on file.
$searchNameToNum = @{}
$searchCanonical = @{}
foreach ($k in $cfByNum.Keys) {
    $f = $cfByNum[$k]
    foreach ($cand in @($f.CloudName, $f.DcName)) {
        if (-not $cand) { continue }
        if ($cand.Length -lt $MinNameLength) { continue }
        $ck = $cand.ToLowerInvariant()
        if (-not $searchNameToNum.ContainsKey($ck)) {
            $searchNameToNum[$ck] = New-Object System.Collections.Generic.List[string]
            $searchCanonical[$ck] = $cand
        }
        if (-not $searchNameToNum[$ck].Contains($f.DcNum)) { $searchNameToNum[$ck].Add($f.DcNum) }
    }
}

$nameRegex = $null
if (-not $SkipNameMatching -and $searchNameToNum.Count) {
    $alts = @(
        $searchNameToNum.Keys |
            Sort-Object -Property @{ Expression = { $_.Length } } -Descending |
            ForEach-Object { [regex]::Escape($searchCanonical[$_]) }
    )
    $nameRegex = [regex]::new('(?<!\w)(' + ($alts -join '|') + ')(?!\w)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled)
}

# -------------------------------------------------------- fetch the filters --

if ($CloudFiltersCacheJson -and (Test-Path -LiteralPath $CloudFiltersCacheJson)) {
    Write-Host "Reading cached Cloud filters from $CloudFiltersCacheJson"
    $raw = [System.IO.File]::ReadAllText($CloudFiltersCacheJson, [System.Text.Encoding]::UTF8)
    $cloudFilters = @($raw | ConvertFrom-Json)
}
else {
    $cloudFilters = @(Get-CloudFilters)
    if ($CloudFiltersCacheJson) {
        $json = $cloudFilters | ConvertTo-Json -Depth 8
        [System.IO.File]::WriteAllText($CloudFiltersCacheJson, $json, (New-Object System.Text.UTF8Encoding $true))
    }
}
Write-Host ("DC inventory rows: {0}; distinct DC filter names: {1}; Cloud filters read: {2}" -f $dcRows.Count, $dcByName.Count, $cloudFilters.Count)

# --------------------------------------------------------------- reporting ---

$cfColumns = @(
    'Filter Name', 'Filter DC Id', 'Filter Cloud Id', 'Filter Type',
    'Owner DC Name', 'Owner DC Id', 'Owner DC Status', 'Owner Cloud Name',
    'Filter DC Status', 'Filter DC Errors', 'Inventory Log DC', 'Filter Cloud JQL',
    'CustomField Ids', 'CustomField Names', 'CustomField Changes', 'Comments'
)
$stColumns = @(
    'Filter Name', 'Filter DC Id', 'Filter Cloud Id', 'Filter Type',
    'Owner DC Name', 'Owner DC Id', 'Owner DC Status', 'Owner Cloud Name',
    'Filter DC Status', 'Filter DC Errors', 'Inventory Log DC', 'Filter Cloud JQL',
    'Statuses', 'Custom Statuses', 'Comments'
)

$report       = New-Object System.Collections.Generic.List[object]
$statusReport = New-Object System.Collections.Generic.List[object]
$noDcMatch    = New-Object System.Collections.Generic.List[string]
$dupDcMatch   = New-Object System.Collections.Generic.List[string]
$missingCloudNm = New-Object System.Collections.Generic.List[string]
$missingCloudId = New-Object System.Collections.Generic.List[string]

foreach ($cflt in $cloudFilters) {
    $fName = [string]$cflt.name
    $jql   = ''
    if ($cflt.PSObject.Properties['jql'] -and $cflt.jql) { $jql = [string]$cflt.jql }

    # --- align to the DC inventory by filter name ----------------------------
    $dc = $null
    $dcMissing = $true
    $k = $fName.Trim().ToLowerInvariant()
    if ($dcByName.ContainsKey($k)) {
        $dc = $dcByName[$k][0]
        $dcMissing = $false
        if ($dcByName[$k].Count -gt 1 -and -not $dupDcMatch.Contains($fName)) { $dupDcMatch.Add($fName) }
    }

    $baseNotes = New-Object System.Collections.Generic.List[string]
    if ($dcMissing) {
        $baseNotes.Add('No matching filter found on DC.')
        if (-not $noDcMatch.Contains($fName)) { $noDcMatch.Add($fName) }
    }
    foreach ($n in (ConvertFrom-FilterErrors -Text (Get-Val $dc 'Filter Errors'))) { $baseNotes.Add($n) }

    $ownerCloud = ''
    if ($cflt.PSObject.Properties['owner'] -and $cflt.owner -and $cflt.owner.PSObject.Properties['displayName']) {
        $ownerCloud = [string]$cflt.owner.displayName
    }

    $common = [ordered]@{
        'Filter Name'      = $fName
        'Filter DC Id'     = Get-Val $dc 'Filter Id'
        'Filter Cloud Id'  = [string]$cflt.id
        'Filter Type'      = Get-CloudFilterType -Filter $cflt
        'Owner DC Name'    = Get-Val $dc 'Owner Name'
        'Owner DC Id'      = Get-Val $dc 'Owner Id'
        'Owner DC Status'  = Get-Val $dc 'Owner Status'
        'Owner Cloud Name' = $ownerCloud
        'Filter DC Status' = Get-Val $dc 'Filter Status'
        'Filter DC Errors' = Get-Val $dc 'Filter Errors'
        'Inventory Log DC' = Get-Val $dc 'Inventory Log'
        'Filter Cloud JQL' = $jql
    }

    if ([string]::IsNullOrWhiteSpace($jql)) { continue }

    # --- status report -------------------------------------------------------
    if ($StatusOutputCsv) {
        $statuses = @(Get-StatusesFromJql -Jql $jql)
        if ($statuses.Count) {
            $customStatuses = New-Object System.Collections.Generic.List[string]
            $snotes = New-Object System.Collections.Generic.List[string]
            foreach ($n in $baseNotes) { $snotes.Add($n) }
            foreach ($st in $statuses) {
                if (-not $BuiltInStatusSet.Contains($st)) { $customStatuses.Add($st) }
                if ($st -match '^\d+$') {
                    $idNote = "Status referenced by numeric id '$st', rewrite it by name."
                    if (-not $snotes.Contains($idNote)) { $snotes.Add($idNote) }
                }
            }
            $srow = [ordered]@{}
            foreach ($c in $common.Keys) { $srow[$c] = $common[$c] }
            $srow['Statuses']        = ($statuses       -join ', ')
            $srow['Custom Statuses'] = ($customStatuses -join ', ')
            $srow['Comments']        = ($snotes         -join $NL)
            $statusReport.Add([pscustomobject]$srow)
        }
    }

    # --- custom field discovery on the Cloud JQL -----------------------------
    $hitNums     = [ordered]@{}
    $matchedById = @{}
    foreach ($m in $idRegex.Matches($jql))      { $n = $m.Groups[1].Value; if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true; $matchedById[$n] = $true } }
    foreach ($m in $bracketRegex.Matches($jql)) { $n = $m.Groups[1].Value; if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true; $matchedById[$n] = $true } }
    if ($nameRegex) {
        foreach ($m in $nameRegex.Matches($jql)) {
            $ck = $m.Groups[1].Value.ToLowerInvariant()
            if ($searchNameToNum.ContainsKey($ck)) {
                foreach ($n in $searchNameToNum[$ck]) { $hitNums[$n] = $true }
            }
        }
    }
    if ($hitNums.Count -eq 0) { continue }

    $ids     = New-Object System.Collections.Generic.List[string]
    $names   = New-Object System.Collections.Generic.List[string]
    $changes = New-Object System.Collections.Generic.List[string]
    $notes   = New-Object System.Collections.Generic.List[string]
    foreach ($n in $baseNotes) { $notes.Add($n) }
    foreach ($d in $DeprecatedJqlTerms) {
        if ($d.Regex.IsMatch($jql) -and -not $notes.Contains($d.Note)) { $notes.Add($d.Note) }
    }

    foreach ($n in $hitNums.Keys) {
        $f = $cfByNum[$n]
        $ids.Add("customfield_$($f.DcNum)")
        $shown = if ($f.CloudName) { $f.CloudName } else { $f.DcName }
        if ($shown -and -not $names.Contains($shown)) { $names.Add($shown) }

        $lk = ''
        if ($f.DcName) { $lk = $f.DcName.ToLowerInvariant() }

        if ($f.DcName -and $UnsupportedInCloudFields.Contains($f.DcName)) {
            if ($matchedById.ContainsKey($n)) {
                $un = "Custom field cf[$($f.DcNum)] ($($f.DcName)) is not supported in Cloud, filter rewrite is needed."
            }
            else {
                $un = "Custom field ""$($f.DcName)"" is not supported in Cloud, filter rewrite is needed."
            }
            if (-not $notes.Contains($un)) { $notes.Add($un) }
            continue
        }

        if ($lk -and $ReviewFieldNotes.ContainsKey($lk)) {
            $rn = $ReviewFieldNotes[$lk]
            if (-not $notes.Contains($rn)) { $notes.Add($rn) }
        }

        $dcLabel    = if ($f.DcName)    { " ($($f.DcName))" }    else { '' }
        $cloudLabel = if ($f.CloudName) { " ($($f.CloudName))" } else { '' }

        # --- id: the Cloud JQL still carries the DC id ---------------------
        if ($matchedById.ContainsKey($n)) {
            if ($f.CloudNum) {
                $changes.Add("cf[$($f.DcNum)] $ARROW cf[$($f.CloudNum)]")
                $notes.Add("Custom field reference Id fix needed $DASH cf[$($f.DcNum)]$dcLabel $ARROW cf[$($f.CloudNum)]$cloudLabel.")
            }
            else {
                $notes.Add("No Cloud id found for cf[$($f.DcNum)]$dcLabel.")
                if (-not $f.HasMapping) {
                    $label = "cf[$($f.DcNum)]$dcLabel"
                    if (-not $missingCloudId.Contains($label)) { $missingCloudId.Add($label) }
                }
            }
        }

        # --- name: only a problem if the Cloud JQL still uses the DC name ---
        if ($f.DcName) {
            $dcNameRe = [regex]::new('(?<!\w)' + [regex]::Escape($f.DcName) + '(?!\w)',
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
            if ($dcNameRe.IsMatch($jql)) {
                if (-not $f.CloudName) {
                    $notes.Add("No Cloud name found for cf[$($f.DcNum)] ($($f.DcName)).")
                    if (-not $f.HasMapping -and -not $missingCloudNm.Contains($f.DcName)) { $missingCloudNm.Add($f.DcName) }
                }
                elseif ($f.DcName -cne $f.CloudName) {
                    $changes.Add("""$($f.DcName)"" $ARROW ""$($f.CloudName)""")
                    $notes.Add("Custom field reference Name fix needed $DASH ""$($f.DcName)"" $ARROW ""$($f.CloudName)"".")
                }
            }

            if ($nameToNum.ContainsKey($lk) -and $nameToNum[$lk].Count -gt 1) {
                $dupe = "Ambiguous DC name ""$($f.DcName)"" maps to cf[$($nameToNum[$lk] -join '] / cf[')]."
                if (-not $notes.Contains($dupe)) { $notes.Add($dupe) }
            }
        }
    }

    if ($changes.Count -eq 0 -and $notes.Count -eq 0) { continue }

    $out = [ordered]@{}
    foreach ($c in $common.Keys) { $out[$c] = $common[$c] }
    $out['CustomField Ids']     = ($ids     -join ', ')
    $out['CustomField Names']   = ($names   -join ', ')
    $out['CustomField Changes'] = ($changes -join (',' + $NL))
    $out['Comments']            = ($notes   -join $NL)
    $report.Add([pscustomobject]$out)
}

if ($noDcMatch.Count) {
    Write-Warning "$($noDcMatch.Count) Cloud filter(s) have no DC inventory entry matching by name."
    $shownCap = 50
    foreach ($nm in ($noDcMatch | Select-Object -First $shownCap)) {
        Write-Warning "  no DC match: ""$nm"""
    }
    if ($noDcMatch.Count -gt $shownCap) {
        Write-Warning "  ... and $($noDcMatch.Count - $shownCap) more (re-run with -Verbose for the full list)."
        foreach ($nm in ($noDcMatch | Select-Object -Skip $shownCap)) { Write-Verbose "  no DC match: $nm" }
    }
}
foreach ($nm in $dupDcMatch)     { Write-Warning "DC inventory has more than one entry named ""$nm""; the first was used." }
foreach ($nm in $missingCloudNm) { Write-Warning "No Cloud name on file for DC custom field ""$nm""." }
foreach ($nm in $missingCloudId) { Write-Warning "No Cloud id on file for DC custom field $nm." }

# ----------------------------------------------------------------- output ----

if ($PSVersionTable.PSVersion.Major -ge 6) {
    $enc = if ($NoBom) { 'utf8NoBOM' } else { 'utf8BOM' }
} else {
    $enc = 'UTF8'
    if ($NoBom) { Write-Warning 'Windows PowerShell 5.1 cannot write UTF-8 without BOM via Export-Csv; BOM will be present.' }
}

function Write-Report {
    param(
        [System.Collections.Generic.List[object]]$Rows,
        [string[]]$Columns,
        [string]$Path,
        [string]$Delim,
        [string]$Encoding
    )
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    if ($Rows.Count) {
        $Rows | Export-Csv -LiteralPath $Path -Delimiter $Delim -Encoding $Encoding -NoTypeInformation
    }
    else {
        $h = [ordered]@{}
        foreach ($c in $Columns) { $h[$c] = '' }
        ([pscustomobject]$h) | Export-Csv -LiteralPath $Path -Delimiter $Delim -Encoding $Encoding -NoTypeInformation
        $lines = Get-Content -LiteralPath $Path -Encoding UTF8
        Set-Content -LiteralPath $Path -Value $lines[0] -Encoding $Encoding
    }
}

Write-Report -Rows $report -Columns $cfColumns -Path $OutputCsv -Delim $Delimiter -Encoding $enc
if ($StatusOutputCsv) {
    Write-Report -Rows $statusReport -Columns $stColumns -Path $StatusOutputCsv -Delim $Delimiter -Encoding $enc
    Write-Host ("Filters referencing statuses: {0}; written to {1}" -f $statusReport.Count, $StatusOutputCsv)
}
Write-Host ("Cloud filters scanned: {0}; affected: {1}; written to {2}" -f $cloudFilters.Count, $report.Count, $OutputCsv)
