#requires -Version 5.1
<#
.SYNOPSIS
    Cross-references a Jira filter inventory CSV against a custom field CSV and reports
    every filter whose JQL references one of those custom fields (by id or by name),
    together with the DC -> Cloud remap for each reference.

.DESCRIPTION
    Detects three JQL reference forms:
        customfield_82822        (explicit id syntax)
        cf[82822]                (bracket syntax, whitespace tolerant)
        "Field Display Name"     (name syntax, quoted or bare, word-boundary anchored)

    When CloudName is empty, a built-in system field mapping table is consulted before
    the field is reported as unresolved. "Filter Errors" is parsed for known error
    patterns; fields reported as non-existent are excluded from the remap output.

    Only filters with at least one match are written to the output report.
    All IO is UTF-8 (BOM on output by default so Excel renders German characters correctly).
    The script source itself is pure ASCII - non-ASCII literals are built with [char]
    escapes so it behaves identically whether PowerShell reads it as UTF-8 or ANSI.

.PARAMETER FilterInventoryCsv
    Path to the filter inventory CSV. Must contain a "Filter JQL" column.

.PARAMETER CustomFieldsCsv
    Path to the custom field CSV. Must contain "DcId", "DcName", "CloudId", "CloudName".

.PARAMETER OutputCsv
    Path of the report to write.

.PARAMETER Delimiter
    Delimiter for both input and output. Default ','. Use ';' for German-locale Excel exports.

.PARAMETER MinNameLength
    Custom field names shorter than this are not name-matched (avoids noise from names
    like "ID" or "Typ" colliding with JQL keywords). Default 3. Their ids are still matched.

.PARAMETER SkipNameMatching
    Match ids only.

.PARAMETER NoBom
    Write UTF-8 without BOM.

.EXAMPLE
    .\Find-AffectedFilters.ps1 -FilterInventoryCsv .\filters.csv -CustomFieldsCsv .\cf.csv -OutputCsv .\affected.csv
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$FilterInventoryCsv,
    [Parameter(Mandatory = $true)][string]$CustomFieldsCsv,
    [Parameter(Mandatory = $true)][string]$OutputCsv,
    [string]$StatusOutputCsv,
    [string]$Delimiter = ',',
    [int]$MinNameLength = 3,
    [switch]$SkipNameMatching,
    [switch]$NoBom
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ARROW = [char]0x2192   # U+2192 RIGHTWARDS ARROW
$UE    = [char]0x00FC   # u-umlaut, used so this file stays ASCII-only on disk
$AE    = [char]0x00E4   # a-umlaut
$DASH  = [char]0x2014   # U+2014 EM DASH
$NL    = "`n"           # in-cell line break for the Comments column

# ============================================================================
#  Pre-defined DC (German) -> Cloud (English) names for Jira built-in fields.
#  Consulted only when CloudName is empty. Keys are lower-case DC names.
#  Add to this table as you confirm more against the Cloud instance.
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
#  Deprecated fields and fields that always need a human decision, even when
#  a Cloud name exists. Reported in Comments whenever the field is referenced.
#  Keys are lower-case DC names; the value is appended to Comments verbatim.
# ============================================================================
$ReviewFieldNotes = @{}
$ReviewFieldNotes['epic-verkn' + $UE + 'pfung'] = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.'
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
#  Clause names that are deprecated in Cloud, matched against the raw JQL so
#  they are caught even when the filter references the field by id.
# ============================================================================
$DeprecatedJqlTerms = @(
    [pscustomobject]@{ Term = 'Epic Link';                    Note = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = 'Epic-Verkn' + $UE + 'pfung';   Note = 'Custom field "Epic Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = 'Epic Name';                    Note = 'Custom field "Epic Name" is deprecated in Cloud.' }
    [pscustomobject]@{ Term = 'Epic-Name';                    Note = 'Custom field "Epic Name" is deprecated in Cloud.' }
    [pscustomobject]@{ Term = 'Parent Link';                  Note = 'Custom field "Parent Link" is deprecated in Cloud, use "parent" instead.' }
    [pscustomobject]@{ Term = 'parentEpic';                   Note = 'JQL function "parentEpic" is deprecated in Cloud, use "parent" instead.' }
)
foreach ($d in $DeprecatedJqlTerms) {
    $d | Add-Member -NotePropertyName Regex -NotePropertyValue ([regex]::new(
        '(?<!\w)' + [regex]::Escape($d.Term) + '(?!\w)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled))
}

# ============================================================================
#  Jira out-of-the-box statuses (Software / Core / Service Management), in the
#  English and German spellings. Anything in a filter that is NOT in this list
#  is reported as a custom status. Comparison is case-insensitive.
#  Extend this list to match your own instance's definition of "standard".
# ============================================================================
$BuiltInStatuses = @(
    # Jira Software / Core - English
    'Open', 'In Progress', 'Reopened', 'Resolved', 'Closed',
    'To Do', 'In Review', 'Under Review', 'Done', 'Backlog',
    'Selected for Development', 'Approved', 'Rejected', 'Cancelled', 'Canceled',
    # Jira Service Management - English
    'Waiting for support', 'Waiting for customer', 'Pending', 'Escalated',
    'Work in progress', 'Under investigation', 'Declined', 'Completed',
    # Jira Software / Core - German
    'Offen', 'In Arbeit', 'Wiedereröffnet', 'Erledigt', 'Geschlossen',
    'Zu erledigen', 'Fertig', 'In Prüfung', 'Rückstand',
    'Zur Entwicklung ausgewählt', 'Genehmigt', 'Abgelehnt', 'Storniert',
    # Jira Service Management - German
    'Warten auf Support', 'Warten auf Kunde', 'Ausstehend', 'Eskaliert',
    'In Bearbeitung', 'Wird untersucht', 'Abgeschlossen'
)
$BuiltInStatusSet = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($s in $BuiltInStatuses) { [void]$BuiltInStatusSet.Add($s) }

# JQL status clauses. "(?!\w)" after the field name keeps statusCategory out.
$statusSingleRegex = [regex]::new(
    '(?<!\w)"?status"?(?!\w)\s*(?:=|!=|~|!~|was\s+not(?!\s+in)|was(?!\s+(?:not\s+)?in)|changed\s+(?:to|from))\s*(?:"([^"]*)"|''([^'']*)''|([^\s()"'',]+))',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$statusListRegex = [regex]::new(
    '(?<!\w)"?status"?(?!\w)\s*(?:(?:was\s+)?(?:not\s+)?in)\s*\(([^)]*)\)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$listItemRegex = [regex]::new('"([^"]*)"|''([^'']*)''|([^,\s][^,]*)')

function Get-StatusesFromJql {
    param([string]$Jql)
    $found = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Jql)) { return ,$found }

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
    return ,$found
}

# ---------------------------------------------------------------- helpers ----

function Get-Val {
    param($Row, [string]$Name)
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

# ------------------------------------------------------------ load inputs ----

foreach ($p in @($FilterInventoryCsv, $CustomFieldsCsv)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Input file not found: $p" }
}

Write-Verbose "Reading custom fields: $CustomFieldsCsv"
$cfRows = @(Import-Csv -LiteralPath $CustomFieldsCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $cfRows.Count) { throw "Custom field CSV contains no rows: $CustomFieldsCsv" }
Assert-Column -Row $cfRows[0] -Required @('DcId', 'DcName', 'CloudId', 'CloudName') -FileLabel 'Custom field CSV'

Write-Verbose "Reading filter inventory: $FilterInventoryCsv"
$filterRows = @(Import-Csv -LiteralPath $FilterInventoryCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $filterRows.Count) { throw "Filter inventory CSV contains no rows: $FilterInventoryCsv" }
Assert-Column -Row $filterRows[0] -Required @('Filter JQL') -FileLabel 'Filter inventory CSV'

# ------------------------------------------------- build the lookup tables ----

$cfByNum       = @{}   # dcNum -> field record
$nameToNum     = @{}   # lower DcName -> list of dcNum
$nameCanonical = @{}   # lower DcName -> original-cased DcName

$skippedShort = New-Object System.Collections.Generic.List[string]

# Pass 1: index every row that carries Cloud data, keyed by NameNormalized.
# The CSV splits some fields over two lines - the DC line has DcId/DcName only,
# the Cloud line has CloudId/CloudName only - so the two halves are rejoined here.
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
Write-Verbose "Indexed $($cloudIndex.Count) Cloud-side row(s) by normalized name."

# Pass 2: the DC side.
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
        # a) a Cloud-only line carrying the same normalized name
        if ($cloudIndex.ContainsKey($own)) { $hit = $cloudIndex[$own] }
        # b) the built-in mapping: "rang" -> "Rank" -> the line normalized as "rank"
        if ($null -eq $hit -and $hasMapping) {
            $tk = $SystemFieldNameMap[$mk].ToLowerInvariant()
            if ($cloudIndex.ContainsKey($tk)) { $hit = $cloudIndex[$tk] }
        }
        if ($null -ne $hit) {
            if (-not $rawCloudNm) { $rawCloudNm = $hit.CloudName }
            if (-not $rawCloudId) { $rawCloudId = $hit.CloudId }
        }
    }
    # last resort: the mapping table supplies the name even with no Cloud line
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
Write-Verbose "Loaded $($cfByNum.Count) custom field id(s), $($nameToNum.Count) distinct name(s)."

# One combined, case-insensitive alternation for names. Longest first so that
# "Team Name" wins over "Team" when both exist.
$nameRegex = $null
if (-not $SkipNameMatching -and $nameToNum.Count) {
    $alts = @(
        $nameToNum.Keys |
            Sort-Object -Property @{ Expression = { $_.Length } } -Descending |
            ForEach-Object { [regex]::Escape($nameCanonical[$_]) }
    )
    $nameRegex = [regex]::new('(?<!\w)(' + ($alts -join '|') + ')(?!\w)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled)
}

$idRegex      = [regex]::new('(?<!\w)customfield[_\s]*(\d+)(?!\d)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$bracketRegex = [regex]::new('(?<!\w)cf\s*\[\s*(\d+)\s*\]',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)

# --- "Filter Errors" parsing -------------------------------------------------
# Line format: ERROR 1: "Field 'X' does not exist or you do not have permission to view it."
$errLineRegex   = [regex]::new('^\s*ERROR\s*\d+\s*:\s*(.*?)\s*$',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$errFieldRegex  = [regex]::new("^Field\s+'(.+?)'\s+does not exist or you do not have permission to view it\.?$",
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$errValueRegex  = [regex]::new("^The value\s+'(.+?)'\s+does not exist for the field\s+'(.+?)'\.?$",
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

function ConvertFrom-FilterErrors {
    param([string]$Text)
    $notes   = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return [pscustomobject]@{ Notes = $notes; MissingFields = $missing }
    }
    foreach ($line in ($Text -split "`r?`n")) {
        $m = $errLineRegex.Match($line)
        if (-not $m.Success) { continue }          # skips the "error message count:" header
        $body = $m.Groups[1].Value.Trim().Trim('"').Trim()
        if (-not $body) { continue }

        $mf = $errFieldRegex.Match($body)
        if ($mf.Success) {
            $fname = $mf.Groups[1].Value
            $notes.Add("Error: Custom field '$fname' does not exist.")
            if (-not $missing.Contains($fname)) { $missing.Add($fname) }
            continue
        }
        $mv = $errValueRegex.Match($body)
        if ($mv.Success) {
            $notes.Add("Error: Value '$($mv.Groups[1].Value)' does not exist for field '$($mv.Groups[2].Value)'.")
            continue
        }
        $notes.Add("Error: $body")
    }
    if ($notes.Count -gt 0) { $notes.Insert(0, 'Filter was not working in DC due to errors.') }
    return [pscustomobject]@{ Notes = $notes; MissingFields = $missing }
}

# ------------------------------------------------------------- scan filters --

$passThroughColumns = @(
    'Filter Name', 'Filter Id', 'Filter Type', 'Filter Status', 'Filter Errors',
    'Inventory Log', 'Filter JQL', 'Owner Name', 'Owner Id', 'Owner Key', 'Owner Status'
)

$absent = @($passThroughColumns | Where-Object { -not $filterRows[0].PSObject.Properties[$_] })
if ($absent.Count) {
    Write-Warning "Filter inventory has no column(s): $($absent -join ', '). They will be blank in the report."
}

$statusColumns = @(
    'Filter Name', 'Filter Id', 'Filter Type', 'Filter Status', 'Filter Errors',
    'Inventory Log', 'Filter JQL', 'Owner Name', 'Owner Id', 'Owner Key', 'Owner Status'
)

$report         = New-Object System.Collections.Generic.List[object]
$statusReport   = New-Object System.Collections.Generic.List[object]
$missingCloudNm = New-Object System.Collections.Generic.List[string]
$missingCloudId = New-Object System.Collections.Generic.List[string]
$scanned        = 0

foreach ($row in $filterRows) {
    $scanned++
    $jql = Get-Val $row 'Filter JQL'
    if ([string]::IsNullOrWhiteSpace($jql)) { continue }

    $hitNums     = [ordered]@{}
    $matchedById = @{}
    foreach ($m in $idRegex.Matches($jql))      { $n = $m.Groups[1].Value; if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true; $matchedById[$n] = $true } }
    foreach ($m in $bracketRegex.Matches($jql)) { $n = $m.Groups[1].Value; if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true; $matchedById[$n] = $true } }
    if ($nameRegex) {
        foreach ($m in $nameRegex.Matches($jql)) {
            $key = $m.Groups[1].Value.ToLowerInvariant()
            if ($nameToNum.ContainsKey($key)) { foreach ($n in $nameToNum[$key]) { $hitNums[$n] = $true } }
        }
    }

    $parsed = ConvertFrom-FilterErrors -Text (Get-Val $row 'Filter Errors')

    # ---------------------------------------------------- status report ------
    if ($StatusOutputCsv) {
        $statuses = @(Get-StatusesFromJql -Jql $jql)
        if ($statuses.Count) {
            $customStatuses = New-Object System.Collections.Generic.List[string]
            $snotes         = New-Object System.Collections.Generic.List[string]
            foreach ($n in $parsed.Notes) { $snotes.Add($n) }

            foreach ($st in $statuses) {
                if (-not $BuiltInStatusSet.Contains($st)) { $customStatuses.Add($st) }
                if ($st -match '^\d+$') {
                    $idNote = "Status referenced by numeric id '$st' - ids differ in Cloud, rewrite by name."
                    if (-not $snotes.Contains($idNote)) { $snotes.Add($idNote) }
                }
            }

            $srow = [ordered]@{}
            foreach ($c in $statusColumns) { $srow[$c] = Get-Val $row $c }
            $srow['Statuses']        = ($statuses       -join ', ')
            $srow['Custom Statuses'] = ($customStatuses -join ', ')
            $srow['Comments']        = ($snotes         -join $NL)
            $statusReport.Add([pscustomobject]$srow)
        }
    }

    if ($hitNums.Count -eq 0) { continue }

    $ids     = New-Object System.Collections.Generic.List[string]
    $names   = New-Object System.Collections.Generic.List[string]
    $changes = New-Object System.Collections.Generic.List[string]
    $notes   = New-Object System.Collections.Generic.List[string]
    foreach ($n in $parsed.Notes) { $notes.Add($n) }
    foreach ($d in $DeprecatedJqlTerms) {
        if ($d.Regex.IsMatch($jql) -and -not $notes.Contains($d.Note)) { $notes.Add($d.Note) }
    }

    foreach ($n in $hitNums.Keys) {
        $f = $cfByNum[$n]

        $ids.Add("customfield_$($f.DcNum)")
        if ($f.DcName -and -not $names.Contains($f.DcName)) { $names.Add($f.DcName) }

        $lk = ''
        if ($f.DcName) { $lk = $f.DcName.ToLowerInvariant() }

        # --- no Cloud counterpart: state it, propose nothing -----------------
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

        # Field is reported non-existent by the filter itself - no remap to propose.
        $isMissing = $false
        if ($f.DcName) {
            foreach ($mn in $parsed.MissingFields) { if ($mn -ieq $f.DcName) { $isMissing = $true; break } }
        }
        if ($isMissing) { continue }

        $dcLabel    = if ($f.DcName)    { " ($($f.DcName))" }    else { '' }
        $cloudLabel = if ($f.CloudName) { " ($($f.CloudName))" } else { '' }

        # --- id remap: always a change ---------------------------------------
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

        # --- name remap ------------------------------------------------------
        if ($f.DcName) {
            if (-not $f.CloudName) {
                $notes.Add("No Cloud name found for cf[$($f.DcNum)] ($($f.DcName)).")
                if (-not $f.HasMapping -and -not $missingCloudNm.Contains($f.DcName)) { $missingCloudNm.Add($f.DcName) }
            }
            elseif ($f.DcName -cne $f.CloudName) {
                $changes.Add("""$($f.DcName)"" $ARROW ""$($f.CloudName)""")
                $notes.Add("Custom field reference Name fix needed $DASH ""$($f.DcName)"" $ARROW ""$($f.CloudName)"".")
            }

            if ($nameToNum.ContainsKey($lk) -and $nameToNum[$lk].Count -gt 1) {
                $dupe = "Ambiguous DC name ""$($f.DcName)"" maps to cf[$($nameToNum[$lk] -join '] / cf[')]."
                if (-not $notes.Contains($dupe)) { $notes.Add($dupe) }
            }
        }
    }

    $out = [ordered]@{}
    foreach ($c in $passThroughColumns) { $out[$c] = Get-Val $row $c }
    $out['CustomField Ids']     = ($ids     -join ', ')
    $out['CustomField Names']   = ($names   -join ', ')
    $out['CustomField Changes'] = ($changes -join (',' + $NL))
    $out['Comments']            = ($notes   -join $NL)

    $report.Add([pscustomobject]$out)
}

foreach ($nm in $missingCloudNm) {
    Write-Warning "No matching Cloud Name for DC CustomField ""$nm"" - referenced by at least one filter."
}
foreach ($nm in $missingCloudId) {
    Write-Warning "No matching Cloud Id for DC CustomField $nm - referenced by at least one filter."
}

# ----------------------------------------------------------------- output ----

if ($PSVersionTable.PSVersion.Major -ge 6) {
    $enc = if ($NoBom) { 'utf8NoBOM' } else { 'utf8BOM' }
} else {
    $enc = 'UTF8'   # Windows PowerShell 5.1 always writes a BOM
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
        # header-only file so downstream tooling still gets a well-formed CSV
        $h = [ordered]@{}
        foreach ($c in $Columns) { $h[$c] = '' }
        ([pscustomobject]$h) | Export-Csv -LiteralPath $Path -Delimiter $Delim -Encoding $Encoding -NoTypeInformation
        $lines = Get-Content -LiteralPath $Path -Encoding UTF8
        Set-Content -LiteralPath $Path -Value $lines[0] -Encoding $Encoding
    }
}

$cfColumns = @($passThroughColumns) + @('CustomField Ids', 'CustomField Names', 'CustomField Changes', 'Comments')
Write-Report -Rows $report -Columns $cfColumns -Path $OutputCsv -Delim $Delimiter -Encoding $enc

if ($StatusOutputCsv) {
    $stColumns = @($statusColumns) + @('Statuses', 'Custom Statuses', 'Comments')
    Write-Report -Rows $statusReport -Columns $stColumns -Path $StatusOutputCsv -Delim $Delimiter -Encoding $enc
    Write-Host ("Filters referencing statuses: {0}; written to {1}" -f $statusReport.Count, $StatusOutputCsv)
}

Write-Host ("Scanned {0} filter row(s); {1} affected; written to {2}" -f $scanned, $report.Count, $OutputCsv)
