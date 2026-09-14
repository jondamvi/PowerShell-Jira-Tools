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

    Only filters with at least one match are written to the output report.
    All IO is UTF-8 (BOM on output by default so Excel renders German characters correctly).

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

.EXAMPLE
    .\Find-AffectedFilters.ps1 -FilterInventoryCsv .\filters.csv -CustomFieldsCsv .\cf.csv -OutputCsv .\affected.csv -Delimiter ';'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$FilterInventoryCsv,
    [Parameter(Mandatory = $true)][string]$CustomFieldsCsv,
    [Parameter(Mandatory = $true)][string]$OutputCsv,
    [string]$Delimiter = ',',
    [int]$MinNameLength = 3,
    [switch]$SkipNameMatching,
    [switch]$NoBom
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ARROW = [char]0x2192   # U+2192 RIGHTWARDS ARROW

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

# dcNum -> field record
$cfByNum   = @{}
# lowercase DcName -> list of dcNum
$nameToNum = @{}
# lowercase DcName -> original-cased DcName
$nameCanonical = @{}

$skippedShort = New-Object System.Collections.Generic.List[string]

foreach ($cf in $cfRows) {
    $rawDcId    = (Get-Val $cf 'DcId').Trim()
    $rawDcName  = (Get-Val $cf 'DcName').Trim()
    $rawCloudId = (Get-Val $cf 'CloudId').Trim()
    $rawCloudNm = (Get-Val $cf 'CloudName').Trim()

    if ($rawDcId -notmatch '(\d+)') { continue }
    $dcNum = $Matches[1]

    $cloudNum = ''
    if ($rawCloudId -match '(\d+)') { $cloudNum = $Matches[1] }

    if (-not $cfByNum.ContainsKey($dcNum)) {
        $cfByNum[$dcNum] = [pscustomobject]@{
            DcNum     = $dcNum
            DcName    = $rawDcName
            CloudNum  = $cloudNum
            CloudName = $rawCloudNm
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
    $pattern = '(?<!\w)(' + ($alts -join '|') + ')(?!\w)'
    $nameRegex = [regex]::new($pattern,
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

# ------------------------------------------------------------- scan filters --

$passThroughColumns = @(
    'Filter Name', 'Filter Id', 'Filter Type', 'Filter Status', 'Filter Errors',
    'Inventory Log', 'Filter JQL', 'Owner Name', 'Owner Id', 'Owner Key', 'Owner Status'
)

$absent = @($passThroughColumns | Where-Object { -not $filterRows[0].PSObject.Properties[$_] })
if ($absent.Count) {
    Write-Warning "Filter inventory has no column(s): $($absent -join ', '). They will be blank in the report."
}

$report          = New-Object System.Collections.Generic.List[object]
$missingCloudNm  = New-Object System.Collections.Generic.List[string]
$missingCloudId  = New-Object System.Collections.Generic.List[string]
$scanned         = 0

foreach ($row in $filterRows) {
    $scanned++
    $jql = Get-Val $row 'Filter JQL'
    if ([string]::IsNullOrWhiteSpace($jql)) { continue }

    $hitNums = [ordered]@{}   # dcNum -> $true, insertion ordered

    foreach ($m in $idRegex.Matches($jql)) {
        $n = $m.Groups[1].Value
        if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true }
    }
    foreach ($m in $bracketRegex.Matches($jql)) {
        $n = $m.Groups[1].Value
        if ($cfByNum.ContainsKey($n)) { $hitNums[$n] = $true }
    }
    if ($nameRegex) {
        foreach ($m in $nameRegex.Matches($jql)) {
            $key = $m.Groups[1].Value.ToLowerInvariant()
            if ($nameToNum.ContainsKey($key)) {
                foreach ($n in $nameToNum[$key]) { $hitNums[$n] = $true }
            }
        }
    }

    if ($hitNums.Count -eq 0) { continue }

    $ids     = New-Object System.Collections.Generic.List[string]
    $names   = New-Object System.Collections.Generic.List[string]
    $changes = New-Object System.Collections.Generic.List[string]
    $notes   = New-Object System.Collections.Generic.List[string]

    foreach ($n in $hitNums.Keys) {
        $f = $cfByNum[$n]

        $ids.Add("customfield_$($f.DcNum)")
        if ($f.DcName -and -not $names.Contains($f.DcName)) { $names.Add($f.DcName) }

        # --- id remap: always a change ---------------------------------------
        if ($f.CloudNum) {
            $changes.Add("cf[$($f.DcNum)] $ARROW cf[$($f.CloudNum)]")
        }
        else {
            $changes.Add("cf[$($f.DcNum)] $ARROW ???")
            $label = "$($f.DcName) (customfield_$($f.DcNum))"
            $notes.Add("No matching Cloud Id for DC CustomField ""$($f.DcName)""")
            if (-not $missingCloudId.Contains($label)) { $missingCloudId.Add($label) }
        }

        # --- name remap ------------------------------------------------------
        if ($f.DcName) {
            if (-not $f.CloudName) {
                $changes.Add("$($f.DcName) $ARROW ???")
                $notes.Add("No matching Cloud Name for DC CustomField ""$($f.DcName)""")
                if (-not $missingCloudNm.Contains($f.DcName)) { $missingCloudNm.Add($f.DcName) }
            }
            elseif ($f.DcName -cne $f.CloudName) {
                $changes.Add("$($f.DcName) $ARROW $($f.CloudName)")
            }
            else {
                $notes.Add("DC custom field ""$($f.DcName)"" is also ""$($f.CloudName)"" in Cloud")
            }
        }

        # --- ambiguity: same DC name behind more than one DC id --------------
        if ($f.DcName) {
            $key = $f.DcName.ToLowerInvariant()
            if ($nameToNum.ContainsKey($key) -and $nameToNum[$key].Count -gt 1) {
                $dupe = "Ambiguous DC name ""$($f.DcName)"" maps to customfield_$($nameToNum[$key] -join ' / customfield_')"
                if (-not $notes.Contains($dupe)) { $notes.Add($dupe) }
            }
        }
    }

    $out = [ordered]@{}
    foreach ($c in $passThroughColumns) { $out[$c] = Get-Val $row $c }
    $out['CustomField Ids']     = ($ids     -join ', ')
    $out['CustomField Names']   = ($names   -join ', ')
    $out['CustomField Changes'] = ($changes -join ', ')
    $out['Comments']            = ($notes   -join '; ')

    $report.Add([pscustomobject]$out)
}

foreach ($nm in $missingCloudNm) {
    Write-Warning "No matching Cloud Name for DC CustomField ""$nm"" - referenced by at least one filter."
}
foreach ($nm in $missingCloudId) {
    Write-Warning "No matching Cloud Id for DC CustomField $nm - referenced by at least one filter."
}

# ----------------------------------------------------------------- output ----

$outDir = Split-Path -Parent $OutputCsv
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

if ($PSVersionTable.PSVersion.Major -ge 6) {
    $enc = if ($NoBom) { 'utf8NoBOM' } else { 'utf8BOM' }
} else {
    # Windows PowerShell 5.1: -Encoding UTF8 always writes a BOM.
    $enc = 'UTF8'
    if ($NoBom) { Write-Warning 'Windows PowerShell 5.1 cannot write UTF-8 without BOM via Export-Csv; BOM will be present.' }
}

if ($report.Count) {
    $report | Export-Csv -LiteralPath $OutputCsv -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
} else {
    $header = [ordered]@{}
    foreach ($c in $passThroughColumns) { $header[$c] = '' }
    $header['CustomField Ids'] = ''; $header['CustomField Names'] = ''
    $header['CustomField Changes'] = ''; $header['Comments'] = ''
    ([pscustomobject]$header) | Export-Csv -LiteralPath $OutputCsv -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
    $lines = Get-Content -LiteralPath $OutputCsv -Encoding UTF8
    Set-Content -LiteralPath $OutputCsv -Value $lines[0] -Encoding $enc
}

Write-Host ("Scanned {0} filter row(s); {1} affected; written to {2}" -f $scanned, $report.Count, $OutputCsv)
