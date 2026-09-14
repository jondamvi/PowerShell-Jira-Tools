#requires -Version 5.1
<#
.SYNOPSIS
    Cross-references a Jira filter inventory CSV against a custom field CSV and reports
    every filter whose JQL references one of those custom fields (by id or by name).

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
    Path to the custom field CSV. Must contain "DcId" (e.g. customfield_82822) and "DcName".

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
Assert-Column -Row $cfRows[0] -Required @('DcId', 'DcName') -FileLabel 'Custom field CSV'

Write-Verbose "Reading filter inventory: $FilterInventoryCsv"
$filterRows = @(Import-Csv -LiteralPath $FilterInventoryCsv -Delimiter $Delimiter -Encoding UTF8)
if (-not $filterRows.Count) { throw "Filter inventory CSV contains no rows: $FilterInventoryCsv" }
Assert-Column -Row $filterRows[0] -Required @('Filter JQL') -FileLabel 'Filter inventory CSV'

# ------------------------------------------------- build the lookup tables ----

# numericId -> canonical "customfield_NNNNN"
$idToCanonical = @{}
# numericId -> display name (first one wins; collisions recorded)
$idToName      = @{}
# lowercase name -> [ordered list of canonical ids]
$nameToIds     = @{}
# lowercase name -> canonical (original-cased) name
$nameCanonical = @{}

$skippedShort = New-Object System.Collections.Generic.List[string]
$badIdRows    = 0

foreach ($cf in $cfRows) {
    $rawId   = (Get-Val $cf 'DcId').Trim()
    $rawName = (Get-Val $cf 'DcName').Trim()

    if ($rawId -notmatch '(\d+)') { $badIdRows++; continue }
    $num       = $Matches[1]
    $canonical = "customfield_$num"

    $idToCanonical[$num] = $canonical
    if ($rawName -and -not $idToName.ContainsKey($num)) { $idToName[$num] = $rawName }

    if ($rawName) {
        $key = $rawName.ToLowerInvariant()
        if ($rawName.Length -lt $MinNameLength) {
            if ($skippedShort -notcontains $rawName) { $skippedShort.Add($rawName) }
            continue
        }
        if (-not $nameToIds.ContainsKey($key)) {
            $nameToIds[$key]     = New-Object System.Collections.Generic.List[string]
            $nameCanonical[$key] = $rawName
        }
        if (-not $nameToIds[$key].Contains($canonical)) { $nameToIds[$key].Add($canonical) }
    }
}

if ($badIdRows) { Write-Warning "$badIdRows custom field row(s) had no numeric id in 'DcId' and were skipped." }
if ($skippedShort.Count) {
    Write-Warning ("Name matching skipped for {0} name(s) shorter than {1} chars: {2}" -f `
        $skippedShort.Count, $MinNameLength, ($skippedShort -join ', '))
}
Write-Verbose "Loaded $($idToCanonical.Count) custom field id(s), $($nameToIds.Count) distinct name(s)."

# One combined, case-insensitive alternation for names. Longest first so that
# "Team Name" wins over "Team" when both exist.
$nameRegex = $null
if (-not $SkipNameMatching -and $nameToIds.Count) {
    $alts = @(
        $nameToIds.Keys |
            Sort-Object -Property @{ Expression = { $_.Length } } -Descending |
            ForEach-Object { [regex]::Escape($nameCanonical[$_]) }
    )
    $pattern = '(?<!\w)(' + ($alts -join '|') + ')(?!\w)'
    $nameRegex = [regex]::new($pattern,
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant -bor
        [System.Text.RegularExpressions.RegexOptions]::Compiled)
}

$idRegex     = [regex]::new('(?<!\w)customfield[_\s]*(\d+)(?!\d)',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)
$bracketRegex = [regex]::new('(?<!\w)cf\s*\[\s*(\d+)\s*\]',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
    [System.Text.RegularExpressions.RegexOptions]::Compiled)

# ------------------------------------------------------------- scan filters --

$passThroughColumns = @(
    'Filter Name', 'Filter Id', 'Filter Type', 'Filter Status', 'Filter Errors',
    'Inventory Log', 'Filter JQL', 'OwnerName', 'Owner Id', 'Owner Key', 'Owner Status'
)

$absent = @($passThroughColumns | Where-Object { -not $filterRows[0].PSObject.Properties[$_] })
if ($absent.Count) {
    Write-Warning "Filter inventory has no column(s): $($absent -join ', '). They will be blank in the report."
}

$report  = New-Object System.Collections.Generic.List[object]
$scanned = 0

foreach ($row in $filterRows) {
    $scanned++
    $jql = Get-Val $row 'Filter JQL'
    if ([string]::IsNullOrWhiteSpace($jql)) { continue }

    $hitIds       = [ordered]@{}   # canonical id -> $true
    $hitNames     = [ordered]@{}   # canonical name -> $true
    $viaIdSyntax  = $false
    $viaCfSyntax  = $false
    $viaName      = $false
    $unknownIds   = New-Object System.Collections.Generic.List[string]

    foreach ($m in $idRegex.Matches($jql)) {
        $num = $m.Groups[1].Value
        if ($idToCanonical.ContainsKey($num)) {
            $hitIds["customfield_$num"] = $true
            $viaIdSyntax = $true
            if ($idToName.ContainsKey($num)) { $hitNames[$idToName[$num]] = $true }
        }
    }

    foreach ($m in $bracketRegex.Matches($jql)) {
        $num = $m.Groups[1].Value
        if ($idToCanonical.ContainsKey($num)) {
            $hitIds["customfield_$num"] = $true
            $viaCfSyntax = $true
            if ($idToName.ContainsKey($num)) { $hitNames[$idToName[$num]] = $true }
        }
    }

    if ($nameRegex) {
        foreach ($m in $nameRegex.Matches($jql)) {
            $key = $m.Groups[1].Value.ToLowerInvariant()
            if ($nameToIds.ContainsKey($key)) {
                $viaName = $true
                $hitNames[$nameCanonical[$key]] = $true
                foreach ($cid in $nameToIds[$key]) { $hitIds[$cid] = $true }
            }
        }
    }

    if ($hitIds.Count -eq 0 -and $hitNames.Count -eq 0) { continue }

    # --- comments -------------------------------------------------------------
    $notes = New-Object System.Collections.Generic.List[string]
    $forms = @()
    if ($viaIdSyntax) { $forms += 'customfield_NNN' }
    if ($viaCfSyntax) { $forms += 'cf[NNN]' }
    if ($viaName)     { $forms += 'display name' }
    $notes.Add("Referenced via: $($forms -join ', ')")

    if ($viaName -and -not ($viaIdSyntax -or $viaCfSyntax)) {
        $notes.Add('Name-only reference - remap depends on the Cloud field name, verify for homonyms')
    }
    foreach ($n in $hitNames.Keys) {
        $key = $n.ToLowerInvariant()
        if ($nameToIds.ContainsKey($key) -and $nameToIds[$key].Count -gt 1) {
            $notes.Add("Ambiguous name '$n' maps to $($nameToIds[$key].Count) ids: $($nameToIds[$key] -join ' / ')")
        }
    }
    if ($hitIds.Count -gt 1) { $notes.Add("$($hitIds.Count) custom fields referenced in one JQL") }

    # --- emit -----------------------------------------------------------------
    $out = [ordered]@{}
    foreach ($c in $passThroughColumns) { $out[$c] = Get-Val $row $c }
    $out['CustomField Ids']   = ($hitIds.Keys   -join ', ')
    $out['CustomField Names'] = ($hitNames.Keys -join ', ')
    $out['Comments']          = ($notes -join '; ')

    $report.Add([pscustomobject]$out)
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
    # still produce a well-formed, empty report with headers
    $header = [ordered]@{}
    foreach ($c in $passThroughColumns) { $header[$c] = '' }
    $header['CustomField Ids'] = ''; $header['CustomField Names'] = ''; $header['Comments'] = ''
    ([pscustomobject]$header) | Export-Csv -LiteralPath $OutputCsv -Delimiter $Delimiter -Encoding $enc -NoTypeInformation
    # drop the placeholder data row, keep the header line
    $lines = Get-Content -LiteralPath $OutputCsv -Encoding UTF8
    Set-Content -LiteralPath $OutputCsv -Value $lines[0] -Encoding $enc
}

Write-Host ("Scanned {0} filter row(s); {1} affected; written to {2}" -f $scanned, $report.Count, $OutputCsv)
