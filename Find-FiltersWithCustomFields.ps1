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
        -OutputCsv .\affected-filters.csv
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CloudBaseUrl,
    [Parameter(Mandatory = $true)][string]$CloudEmail,
    [Parameter(Mandatory = $true)][string]$CloudApiToken,
    [Parameter(Mandatory = $true)][string]$FilterInventoryCsv,
    [Parameter(Mandatory = $true)][string]$CustomFieldsCsv,
    [Parameter(Mandatory = $true)][string]$OutputCsv,
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
$NOTE_NO_DC = 'No matching filter in DC inventory, this filter is new or was renamed.'   # informational only, never a reason to report

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

# ============================================================================
#  DC owner statuses that mean the account was not usable at inventory time.
# ============================================================================
$InactiveOwnerStatuses = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($s in @('InactiveDeactivated', 'InactiveAnonymized', 'InactiveUnknown')) {
    [void]$InactiveOwnerStatuses.Add($s)
}

# ============================================================================
#  Asset object key prefixes, with or without the trailing dash. Keys with these
#  prefixes in a JQL clause need rewriting as aqlFunction with Legacy-Key.
#  ADD YOUR OWN PREFIXES HERE.
# ============================================================================
$AssetKeyPrefixes = @(
    'CMDB-'
    'JRSK-'
)

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

# Asset keys: PREFIX-123, case-insensitive, reported uppercase.
$assetKeyRegex = $null
if ($AssetKeyPrefixes.Count) {
    $prefixAlt = (@($AssetKeyPrefixes | ForEach-Object { [regex]::Escape($_.Trim().TrimEnd('-')) })) -join '|'
    $assetKeyRegex = [regex]::new("(?<![\w-])(?:$prefixAlt)-\d+(?![\w-])",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled)
}

# Regions where an asset-looking key is NOT an Assets field reference:
#   ~ / !~ operands are free-text content searches
#   aqlFunction(...) is already the correct construction
#   issueKey / key / issue operands are issue keys that share the prefix
$maskTextOpRegex   = [regex]::new('(!~|~)\s*("[^"]*"|''[^'']*''|[^\s()"'']+)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$maskAqlRegex      = [regex]::new('\baqlFunction\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
$maskIssueKeyRegex = [regex]::new('(?<!\w)(?:issuekey|issue|key)\s*(?:=|!=|(?:not\s+)?in)\s*(\([^)]*\)|"[^"]*"|''[^'']*''|[^\s()]+)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

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

function Set-MaskedRegion {
    # blanks one capture group everywhere it matches, preserving string length
    param([string]$Text, [regex]$Re, [int]$Group)
    $out = $Text
    $ms = $Re.Matches($Text)
    for ($i = $ms.Count - 1; $i -ge 0; $i--) {
        $g = $ms[$i].Groups[$Group]
        if (-not $g.Success -or $g.Length -eq 0) { continue }
        $out = $out.Remove($g.Index, $g.Length).Insert($g.Index, ('#' * $g.Length))
    }
    return $out
}

function Get-AssetKeysNeedingRewrite {
    param([string]$Jql)
    if ($null -eq $assetKeyRegex -or [string]::IsNullOrWhiteSpace($Jql)) { return @() }
    $m = $Jql
    $m = Set-MaskedRegion -Text $m -Re $maskTextOpRegex   -Group 2
    $m = Set-MaskedRegion -Text $m -Re $maskAqlRegex      -Group 1
    $m = Set-MaskedRegion -Text $m -Re $maskIssueKeyRegex -Group 1
    $hits = New-Object System.Collections.Generic.List[string]
    foreach ($x in $assetKeyRegex.Matches($m)) {
        $v = $x.Value.ToUpperInvariant()
        if (-not $hits.Contains($v)) { $hits.Add($v) }
    }
    return $hits.ToArray()
}

function Get-NormalizedOwnerName {
    # "Name Surname - Contoso" -> "Name Surname"
    param([string]$Name)
    if (-not $Name) { return '' }
    $n = $Name.Trim()
    $i = $n.IndexOf(' - ')
    if ($i -gt 0) { $n = $n.Substring(0, $i) }
    return (($n -replace '\s+', ' ').Trim())
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
               "&expand=jql,owner,sharePermissions,editPermissions"
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
    return @($types | Sort-Object -Unique)
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

function Get-FilterEditTypes {
    param($Filter)
    $types = @()
    if ($Filter.PSObject.Properties['editPermissions'] -and $null -ne $Filter.editPermissions) {
        foreach ($p in $Filter.editPermissions) {
            if ($p.PSObject.Properties['type'] -and $p.type) { $types += [string]$p.type }
        }
    }
    return @($types)
}

function Get-CloudFilterType {
    # Jira has no Shared/Private flag - the GUI derives it. With no share or edit
    # permissions the filter is visible only to the owner and Jira admins: Private.
    # Anything granted to anyone else makes it Shared.
    param($Filter)
    $v = @(Get-FilterShareTypes -Filter $Filter)
    $e = @(Get-FilterEditTypes  -Filter $Filter)
    if ($v.Count -eq 0 -and $e.Count -eq 0) { return 'Private' }
    return 'Shared'
}

function Get-SharePermissionLabel {
    param($P)
    $t = ''
    if ($P.PSObject.Properties['type'] -and $P.type) { $t = [string]$P.type }
    switch -Regex ($t) {
        '^(loggedin|authenticated)$' { return 'Any logged-in user' }
        '^(global|public)$'          { return 'Public' }
        '^group$' {
            if ($P.PSObject.Properties['group'] -and $P.group -and $P.group.PSObject.Properties['name']) {
                return [string]$P.group.name
            }
            return 'group'
        }
        '^user$' {
            if ($P.PSObject.Properties['user'] -and $P.user) {
                if ($P.user.PSObject.Properties['displayName'] -and $P.user.displayName) { return [string]$P.user.displayName }
                if ($P.user.PSObject.Properties['accountId'])  { return [string]$P.user.accountId }
            }
            return 'user'
        }
        '^project$' {
            if ($P.PSObject.Properties['project'] -and $P.project -and $P.project.PSObject.Properties['name']) {
                return [string]$P.project.name
            }
            return 'project'
        }
        '^projectRole$' {
            $pn = 'project'
            $rn = 'role'
            if ($P.PSObject.Properties['project'] -and $P.project -and $P.project.PSObject.Properties['name']) { $pn = [string]$P.project.name }
            if ($P.PSObject.Properties['role']    -and $P.role    -and $P.role.PSObject.Properties['name'])    { $rn = [string]$P.role.name }
            return "$pn / $rn"
        }
        default { if ($t) { return $t } else { return 'unknown' } }
    }
}

function Get-CloudSharedWith {
    param($Filter)
    $lines = New-Object System.Collections.Generic.List[string]
    if ($Filter.PSObject.Properties['sharePermissions'] -and $null -ne $Filter.sharePermissions) {
        foreach ($p in $Filter.sharePermissions) {
            $l = "'" + (Get-SharePermissionLabel -P $p) + "' (VIEW)"
            if (-not $lines.Contains($l)) { $lines.Add($l) }
        }
    }
    if ($Filter.PSObject.Properties['editPermissions'] -and $null -ne $Filter.editPermissions) {
        foreach ($p in $Filter.editPermissions) {
            $l = "'" + (Get-SharePermissionLabel -P $p) + "' (EDIT)"
            if (-not $lines.Contains($l)) { $lines.Add($l) }
        }
    }
    return ($lines -join $NL)
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

$outColumns = @(
    'Filter Name', 'Filter DC Id', 'Filter Cloud Id', 'Filter Type',
    'DC SharedWith', 'Cloud SharedWith',
    'Owner DC Name', 'Owner DC Id', 'Owner DC Status', 'Owner Cloud Name',
    'Filter DC Status', 'Filter DC Errors', 'Inventory Log DC', 'Filter Cloud JQL',
    'Statuses', 'Custom Statuses',
    'CustomField Ids', 'CustomField Names', 'CustomField Changes', 'Comments'
)

$report         = New-Object System.Collections.Generic.List[object]
$noDcMatch      = New-Object System.Collections.Generic.List[string]
$dupDcMatch     = New-Object System.Collections.Generic.List[string]
$missingCloudNm = New-Object System.Collections.Generic.List[string]
$missingCloudId = New-Object System.Collections.Generic.List[string]

# --- work items: every Cloud filter, plus DC filters that are not in Cloud ---
$workItems     = New-Object System.Collections.Generic.List[object]
$matchedDcKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)

foreach ($cflt in $cloudFilters) {
    $fName = [string]$cflt.name
    $k  = $fName.Trim().ToLowerInvariant()
    $dc = $null
    if ($dcByName.ContainsKey($k)) {
        $dc = $dcByName[$k][0]
        [void]$matchedDcKeys.Add($k)
        if ($dcByName[$k].Count -gt 1 -and -not $dupDcMatch.Contains($fName)) { $dupDcMatch.Add($fName) }
    }
    $cjql = ''
    if ($cflt.PSObject.Properties['jql'] -and $cflt.jql) { $cjql = [string]$cflt.jql }
    $workItems.Add([pscustomobject]@{ Name = $fName; Cloud = $cflt; Dc = $dc; Jql = $cjql; NotMigrated = $false })
}

foreach ($k in $dcByName.Keys) {
    if ($matchedDcKeys.Contains($k)) { continue }
    foreach ($r in $dcByName[$k]) {
        $workItems.Add([pscustomobject]@{
            Name        = (Get-Val $r 'Filter Name')
            Cloud       = $null
            Dc          = $r
            Jql         = (Get-Val $r 'Filter JQL')
            NotMigrated = $true
        })
    }
}
$notMigratedCount = @($workItems | Where-Object { $_.NotMigrated }).Count
Write-Host ("Cloud filters: {0}; DC filters with no Cloud counterpart: {1}" -f $cloudFilters.Count, $notMigratedCount)

foreach ($item in $workItems) {
    $fName       = [string]$item.Name
    $cflt        = $item.Cloud
    $dc          = $item.Dc
    $jql         = [string]$item.Jql
    $notMigrated = [bool]$item.NotMigrated
    $dcMissing   = ($null -eq $dc)

    $baseNotes = New-Object System.Collections.Generic.List[string]
    if ($notMigrated) { $baseNotes.Add('Filter not migrated, manual re-create needed.') }
    if ($dcMissing) {
        $baseNotes.Add($NOTE_NO_DC)
        if (-not $noDcMatch.Contains($fName)) { $noDcMatch.Add($fName) }
    }

    $errNotes = @(ConvertFrom-FilterErrors -Text (Get-Val $dc 'Filter Errors'))
    foreach ($n in $errNotes) { $baseNotes.Add($n) }

    $ownerCloud = ''
    if ($null -ne $cflt -and $cflt.PSObject.Properties['owner'] -and $cflt.owner -and $cflt.owner.PSObject.Properties['displayName']) {
        $ownerCloud = [string]$cflt.owner.displayName
    }

    $ownerDc       = Get-Val $dc 'Owner Name'
    $ownerStatusDc = (Get-Val $dc 'Owner Status').Trim()
    if ($InactiveOwnerStatuses.Contains($ownerStatusDc)) {
        $sameOwner = $notMigrated   # nothing in Cloud to compare against
        if (-not $notMigrated -and $ownerDc -and $ownerCloud) {
            $a = Get-NormalizedOwnerName $ownerDc
            $b = Get-NormalizedOwnerName $ownerCloud
            $sameOwner = ($a -and $b -and ($a -ieq $b))
        }
        if ($sameOwner) { $baseNotes.Add('Filter owner was inactive on DC, re-assign ownership may be needed.') }
    }

    $jqlCell = $jql
    if ($notMigrated -and $jql) { $jqlCell = "[Filter DC JQL]:$NL$jql" }

    $common = [ordered]@{
        'Filter Name'      = $fName
        'Filter DC Id'     = Get-Val $dc 'Filter Id'
        'Filter Cloud Id'  = $(if ($null -ne $cflt) { [string]$cflt.id } else { '' })
        'Filter Type'      = $(if ($null -ne $cflt) { Get-CloudFilterType -Filter $cflt } else { '' })
        'DC SharedWith'    = Get-Val $dc 'Shared Groups'
        'Cloud SharedWith' = $(if ($null -ne $cflt) { Get-CloudSharedWith -Filter $cflt } else { '' })
        'Owner DC Name'    = $ownerDc
        'Owner DC Id'      = Get-Val $dc 'Owner Id'
        'Owner DC Status'  = $ownerStatusDc
        'Owner Cloud Name' = $ownerCloud
        'Filter DC Status' = Get-Val $dc 'Filter Status'
        'Filter DC Errors' = Get-Val $dc 'Filter Errors'
        'Inventory Log DC' = Get-Val $dc 'Inventory Log'
        'Filter Cloud JQL' = $jqlCell
    }

    $statuses       = @()
    $customStatuses = New-Object System.Collections.Generic.List[string]
    $ids     = New-Object System.Collections.Generic.List[string]
    $names   = New-Object System.Collections.Generic.List[string]
    $changes = New-Object System.Collections.Generic.List[string]
    $notes   = New-Object System.Collections.Generic.List[string]
    foreach ($n in $baseNotes) { $notes.Add($n) }

    if (-not [string]::IsNullOrWhiteSpace($jql)) {

        # --- statuses --------------------------------------------------------
        $statuses = @(Get-StatusesFromJql -Jql $jql)
        foreach ($st in $statuses) {
            if (-not $BuiltInStatusSet.Contains($st)) { $customStatuses.Add($st) }
            if ($st -match '^\d+$') {
                $idNote = "Status referenced by numeric id '$st', rewrite it by name."
                if (-not $notes.Contains($idNote)) { $notes.Add($idNote) }
            }
        }

        # --- asset keys ------------------------------------------------------
        $assetKeys = @(Get-AssetKeysNeedingRewrite -Jql $jql)
        if ($assetKeys.Count) {
            $notes.Add("Filter contains asset keys, which need rewrite with aqlFunction and Legacy-Key attribute $DASH $($assetKeys -join ', ').")
        }

        # --- deprecated clause names ----------------------------------------
        foreach ($d in $DeprecatedJqlTerms) {
            if ($d.Regex.IsMatch($jql) -and -not $notes.Contains($d.Note)) { $notes.Add($d.Note) }
        }

        # --- custom fields ---------------------------------------------------
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
    }

    # --- inclusion: anything actionable. The "no DC match" line on its own
    #     is informational and never enough to report a filter.
    $actionable = @($notes | Where-Object { $_ -ne $NOTE_NO_DC })
    if ($changes.Count -eq 0 -and $actionable.Count -eq 0) { continue }

    $out = [ordered]@{}
    foreach ($c in $common.Keys) { $out[$c] = $common[$c] }
    $out['Statuses']            = ($statuses       -join ', ')
    $out['Custom Statuses']     = ($customStatuses -join ', ')
    $out['CustomField Ids']     = ($ids     -join ', ')
    $out['CustomField Names']   = ($names   -join ', ')
    $out['CustomField Changes'] = ($changes -join (',' + $NL))
    $out['Comments']            = ($notes   -join $NL)
    $report.Add([pscustomobject]$out)
}

if ($noDcMatch.Count) {
    Write-Warning "$($noDcMatch.Count) Cloud filter(s) have no DC inventory entry matching by name."
}
if ($notMigratedCount) {
    Write-Warning "$notMigratedCount DC filter(s) have no Cloud counterpart - reported as not migrated."
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

$outDir = Split-Path -Parent $OutputCsv
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

if ($report.Count) {
    $report | Export-Csv -LiteralPath $OutputCsv -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
}
else {
    $h = [ordered]@{}
    foreach ($c in $outColumns) { $h[$c] = '' }
    ([pscustomobject]$h) | Export-Csv -LiteralPath $OutputCsv -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
    $lines = Get-Content -LiteralPath $OutputCsv -Encoding UTF8
    Set-Content -LiteralPath $OutputCsv -Value $lines[0] -Encoding $enc
}

Write-Host ("Filters evaluated: {0}; affected: {1}; written to {2}" -f $workItems.Count, $report.Count, $OutputCsv)
