#requires -Version 5.1
<#
.SYNOPSIS
    Compares Jira Data Center automation rules (Automation for Jira) with their migrated
    Jira Cloud counterparts: rule-level settings, step count, step types, and the
    configuration of every step. Read-only on both sides.

.DESCRIPTION
    Endpoints
      DC    : GET /rest/cb-automation/latest/project/GLOBAL/rule          (list, full config)
              GET /rest/cb-automation/latest/project/GLOBAL/rule/{id}     (fallback per rule)
              GET /rest/api/2/project                                      (projectId -> key)
      Cloud : GET /_edge/tenant_info                                       (cloudId)
              GET https://api.atlassian.com/automation/public/jira/{cloudId}/rest/v1/rule/summary
              GET https://api.atlassian.com/automation/public/jira/{cloudId}/rest/v1/rule/{ruleUuid}
              (public Automation Rule Management API; the site alias
               {CloudBaseUrl}/gateway/api/automation/public/jira/{cloudId}/rest/v1 is tried as fallback)
              GET /rest/api/3/project/search                               (projectId -> key)

    Matching: DC rule <-> Cloud rule by rule name (trimmed, case-insensitive). Duplicate
    names on either side are warned about and reported as MatchStatus = DuplicateName.

    Comparison, in order (default: stop at the first difference per rule, -FullDiff for all):
      1. rule settings  : state, scope (project keys), allow-other-rules-trigger,
                          notify-on-error, edit access, labels, description
      2. step count     : trigger + flattened component tree (children/conditions)
      3. per step       : component kind (TRIGGER/CONDITION/ACTION/BRANCH...) and type
      4. per step       : deep diff of the step configuration

    Before the deep diff the DC side is rewritten with the custom field mapping CSV
    (same logic/columns as Find-FiltersWithCustomFields.ps1: DcId, DcName, CloudId,
    CloudName, optional NameNormalized): customfield_<dc> -> customfield_<cloud>,
    cf[<dc>] -> cf[<cloud>], "<DcName>" -> "<CloudName>". Whitespace is collapsed and
    string comparison is case-insensitive unless -CaseSensitive. Missing key == null == ""
    (false is NOT treated as missing, so an unticked checkbox is reported).

.OUTPUTS
    <OutputDir>\AutomationCompare_Summary_<ts>.csv   one row per rule
    <OutputDir>\AutomationCompare_Details_<ts>.csv   one row per difference
    <OutputDir>\json\<SafeRuleName>_<id>_DC.json / _Cloud.json   raw rule JSON
    <CacheDir>\dc\*.json, <CacheDir>\cloud\*.json   when -CacheDir is given

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

# ============================================================================
#  1. GLOBAL SETTINGS AND SCRIPT VARIABLES
# ============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference    = 'Stop'
$Script:ErrorAction       = 'Stop'
$PSDefaultParameterValues = @{ '*:ErrorAction' = $Script:ErrorAction }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$UE = [char]0x00FC; $AE = [char]0x00E4; $OE = [char]0x00F6
$NL = "`n"

# Rules already verified - skipped. Populate manually between runs.
$Script:SkipRuleNamesInScript = @(
    # 'Beispiel: Ticket an Team zuweisen'
)

# Keys ignored in the deep diff (metadata that legitimately differs between DC and Cloud).
$Script:IgnoreKeys = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($k in @('id', 'ruleId', 'parentId', 'clientKey', 'created', 'updated', 'lastUpdated',
                 'schemaVersion', 'uuid', 'connectionId', 'authorAccountId', 'actor', 'ruleScope',
                 'projects', 'tags', 'collaborators', 'ruleHome', 'ownerId', 'linkedRules')) {
    [void]$Script:IgnoreKeys.Add($k)
}

# Parameter names whose values are masked in error reports.
$Script:SecretParameterPattern = 'Token|Cookie|Password|Secret|ApiKey|Headers'

# DC (German) -> Cloud (English) system field names (from Find-FiltersWithCustomFields.ps1)
$Script:SystemFieldNameMap = @{}
$Script:SystemFieldNameMap['rang']                     = 'Rank'
$Script:SystemFieldNameMap['gekennzeichnet']           = 'Flagged'
$Script:SystemFieldNameMap['story-punkte']             = 'Story Points'
$Script:SystemFieldNameMap['epic-name']                = 'Epic Name'
$Script:SystemFieldNameMap["epic-verkn${UE}pfung"]     = 'Epic Link'
$Script:SystemFieldNameMap['epic-status']              = 'Epic Status'
$Script:SystemFieldNameMap['epic-farbe']               = 'Epic Colour'
$Script:SystemFieldNameMap['anfrageteilnehmer']        = 'Request participants'
$Script:SystemFieldNameMap['kundenanfragetyp']         = 'Request Type'
$Script:SystemFieldNameMap['genehmigungen']            = 'Approvals'
$Script:SystemFieldNameMap['organisationen']           = 'Organizations'
$Script:SystemFieldNameMap['zufriedenheit']            = 'Satisfaction'
$Script:SystemFieldNameMap['zufriedenheitsdatum']      = 'Satisfaction date'
$Script:SystemFieldNameMap['entwicklung']              = 'Development'
$Script:SystemFieldNameMap["gesch${AE}ftswert"]        = 'Business Value'

$Script:RegexIgnoreCase = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
$Script:CfIdRegex       = New-Object System.Text.RegularExpressions.Regex ('customfield_(\d+)', $Script:RegexIgnoreCase)
$Script:CfBracketRegex  = New-Object System.Text.RegularExpressions.Regex ('cf\[(\d+)\]',       $Script:RegexIgnoreCase)

# Populated by the main block before any comparison runs.
$Script:DcHeaders        = @{}
$Script:CloudHeaders     = @{}
$Script:CfByNum          = @{}      # dcNum -> { DcNum, DcName, CloudNum, CloudName, HasMapping }
$Script:NameReplacements = @()      # { Dc, Cloud, Regex } sorted longest DC name first
$Script:DcProjectKeys    = @{}
$Script:CloudProjectKeys = @{}
$Script:JsonDir          = ''
$Script:Summary          = New-Object System.Collections.Generic.List[object]
$Script:Details          = New-Object System.Collections.Generic.List[object]
$Script:StopAtFirst      = $true
$Script:CompareCaseSensitive = $false
$Script:DetailColumns    = @('Rule Name', 'DC Rule ID', 'Cloud Rule ID', 'Area', 'Step Path', 'Step Kind', 'Step Type', 'Property', 'DC Value', 'Cloud Value')

# ============================================================================
#  2. FUNCTIONS
# ============================================================================

# ------------------------------------------------------------ error report ----

function Format-FunctionError {
    # Builds the error text for a function's catch block, including a report of the
    # function's parameters (bound values, defaults marked "(Default)", secrets masked).
    # Called from the failing function's own catch, so Get-Variable -Scope 1 is that function.
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$ErrorRecord,
        [Parameter(Mandatory = $true)]$Invocation,
        [Parameter(Mandatory = $true)]$BoundParameters
    )
    $lines = New-Object System.Collections.Generic.List[string]
    try {
        $common = @([System.Management.Automation.PSCmdlet]::CommonParameters) + @([System.Management.Automation.PSCmdlet]::OptionalCommonParameters)
        foreach ($name in @($Invocation.MyCommand.Parameters.Keys)) {
            if ($common -contains $name) { continue }
            $isBound = $BoundParameters.ContainsKey($name)
            $value = $null
            if ($isBound) {
                $value = $BoundParameters[$name]
            } else {
                try { $value = (Get-Variable -Name $name -Scope 1 -ValueOnly) } catch { $value = $null }
            }
            $shown = Format-ParameterValue -Name $name -Value $value
            $suffix = ''
            if (-not $isBound) { $suffix = ' (Default)' }
            $lines.Add("${name}: ${shown}${suffix}")
        }
    } catch {
        $lines.Add("(could not build parameter report: $($_.Exception.Message))")
    }
    $Parameters = $lines -join "`n"
    if (-not $Parameters) { $Parameters = '(none)' }
    return "$($ErrorRecord.Exception.Message)`n$($ErrorRecord | Format-List | Out-String)`nError Trace:`n$($ErrorRecord.ScriptStackTrace)`nFunction Parameters:`n$Parameters"
}

function Format-ParameterValue {
    [CmdletBinding()]
    param([string]$Name, $Value)
    try {
        if ($null -eq $Value) { return "'Null'" }
        if ($Name -match $Script:SecretParameterPattern) { return "'***'" }
        $s = ''
        if ($Value -is [string]) { $s = $Value }
        elseif ($Value -is [switch]) { $s = [string]$Value.IsPresent }
        elseif ($Value -is [System.Management.Automation.ErrorRecord]) { $s = $Value.Exception.Message }
        elseif ($Value -is [System.Management.Automation.InvocationInfo]) { $s = $Value.MyCommand.Name }
        elseif ($Value -is [System.Collections.IDictionary]) { $s = "hashtable(" + (@($Value.Keys) -join ', ') + ")" }
        elseif ($Value -is [System.Array] -or $Value -is [System.Collections.IList]) {
            $parts = foreach ($x in $Value) { if ($null -eq $x) { 'Null' } elseif ($x -is [string] -or $x.GetType().IsPrimitive) { [string]$x } else { $x.GetType().Name } }
            $s = "[" + ($parts -join ', ') + "] (" + @($Value).Count + " items)"
        }
        elseif ($Value -is [System.Management.Automation.PSCustomObject]) { $s = ($Value | ConvertTo-Json -Depth 3 -Compress) }
        else { $s = [string]$Value }
        if ($s.Length -gt 300) { $s = $s.Substring(0, 300) + '...' }
        return "'$s'"
    } catch { return "'<unprintable: $($_.Exception.Message)>'" }
}

# The catch block used by every function below. Innermost failure produces the full
# report; outer functions pass the same error record through unchanged.
function Invoke-FunctionCatch {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$ErrorRecord, [Parameter(Mandatory = $true)]$Cmdlet, [Parameter(Mandatory = $true)]$Invocation, [Parameter(Mandatory = $true)]$BoundParameters, [string]$Report)
    if ($ErrorRecord.FullyQualifiedErrorId -like 'FunctionError*') { $Cmdlet.ThrowTerminatingError($ErrorRecord) }
    $exception = New-Object System.Exception ($Report, $ErrorRecord.Exception)
    $record    = New-Object System.Management.Automation.ErrorRecord ($exception, 'FunctionError', [System.Management.Automation.ErrorCategory]::NotSpecified, $null)
    $Cmdlet.ThrowTerminatingError($record)
}

# ----------------------------------------------------------------- helpers ----

function Get-Val {
    [CmdletBinding()]
    param($Row, [string]$Name)
    try {
        if ($null -eq $Row) { return '' }
        $p = $Row.PSObject.Properties[$Name]
        if ($null -eq $p -or $null -eq $p.Value) { return '' }
        return [string]$p.Value
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Assert-Column {
    [CmdletBinding()]
    param($Row, [string[]]$Required, [string]$FileLabel)
    try {
        $have = @($Row.PSObject.Properties.Name)
        $missing = @($Required | Where-Object { $have -notcontains $_ })
        if ($missing.Count) {
            throw "$FileLabel is missing required column(s): $($missing -join ', '). Found: $($have -join ', ')"
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Invoke-JsonGet {
    # GET + explicit UTF-8 decode (PS 5.1 otherwise falls back to the ANSI code page).
    # A top-level JSON array is returned as ONE array object (comma operator).
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Uri, [Parameter(Mandatory = $true)][hashtable]$Headers)
    try {
        $resp  = Invoke-WebRequest -Uri $Uri -Headers $Headers -Method Get -UseBasicParsing
        $bytes = $resp.RawContentStream.ToArray()
        $text  = [System.Text.Encoding]::UTF8.GetString($bytes)
        if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }
        $obj = ConvertFrom-Json -InputObject $text
        return $obj
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-ListPayload {
    # Unwraps list envelopes ({data:[..]}, {values:[..]}, ...) to a plain array.
    [CmdletBinding()]
    param($Obj)
    try {
        if ($null -eq $Obj) { return @() }
        if ($Obj -is [System.Array]) { return $Obj }
        foreach ($k in 'data', 'items', 'values', 'rules', 'results') {
            $p = $Obj.PSObject.Properties[$k]
            if ($null -ne $p -and $p.Value -is [System.Array]) { return $p.Value }
        }
        return @($Obj)
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-Prop {
    [CmdletBinding()]
    param($Obj, [string]$Name)
    try {
        if ($null -eq $Obj) { return $null }
        if ($Obj -is [System.Collections.IDictionary]) { if ($Obj.Contains($Name)) { return $Obj[$Name] } else { return $null } }
        $p = $Obj.PSObject.Properties[$Name]
        if ($null -eq $p) { return $null }
        return $p.Value
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-ArrayProp {
    # Property as array; $null / missing -> empty array (never @($null)).
    [CmdletBinding()]
    param($Obj, [string]$Name)
    try {
        $v = Get-Prop -Obj $Obj -Name $Name
        if ($null -eq $v) { return @() }
        return @($v)
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Test-HasProp {
    [CmdletBinding()]
    param($Obj, [string]$Name)
    try {
        if ($null -eq $Obj) { return $false }
        if ($Obj -is [System.Collections.IDictionary]) { return [bool]$Obj.Contains($Name) }
        return ($null -ne $Obj.PSObject.Properties[$Name])
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Test-IsObject {
    [CmdletBinding()]
    param($x)
    try {
        return ($x -is [System.Management.Automation.PSCustomObject] -or $x -is [System.Collections.IDictionary])
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Test-IsArray {
    [CmdletBinding()]
    param($x)
    try {
        return ($x -is [System.Array] -or ($x -is [System.Collections.IList] -and $x -isnot [string]))
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-PropNames {
    [CmdletBinding()]
    param($Obj)
    try {
        if ($Obj -is [System.Collections.IDictionary]) { return @($Obj.Keys) }
        return @($Obj.PSObject.Properties.Name)
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function ConvertTo-SafeFileName {
    [CmdletBinding()]
    param([string]$Name, [int]$MaxLen = 100)
    try {
        $invalid = New-Object 'System.Collections.Generic.HashSet[char]'
        foreach ($c in [System.IO.Path]::GetInvalidFileNameChars()) { [void]$invalid.Add($c) }
        foreach ($c in '<>|:"/\?*'.ToCharArray())                  { [void]$invalid.Add($c) }
        $sb = New-Object System.Text.StringBuilder
        foreach ($ch in $Name.ToCharArray()) {
            if ($invalid.Contains($ch) -or [int]$ch -lt 32) { [void]$sb.Append('_') } else { [void]$sb.Append($ch) }
        }
        $s = $sb.ToString().Trim().TrimEnd('.').Trim()
        $s = $s -replace '\s+', ' '
        if ($s.Length -gt $MaxLen) { $s = $s.Substring(0, $MaxLen).Trim() }
        if (-not $s) { $s = 'rule' }
        return $s
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Write-Utf8Json {
    [CmdletBinding()]
    param([string]$Path, $Obj)
    try {
        $json = ConvertTo-Json -InputObject $Obj -Depth 100
        [System.IO.File]::WriteAllText($Path, [string]$json, (New-Object System.Text.UTF8Encoding $true))
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Read-Utf8Json {
    [CmdletBinding()]
    param([string]$Path)
    try {
        $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        $obj = ConvertFrom-Json -InputObject $text
        return $obj
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-RuleNameKey {
    [CmdletBinding()]
    param([string]$Name)
    try {
        return (($Name -replace '\s+', ' ').Trim().ToLowerInvariant())
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Format-Value {
    [CmdletBinding()]
    param($v)
    try {
        if ($null -eq $v) { return '<null>' }
        if ($v -is [string]) { return $v }
        if ($v -is [bool]) { return $v.ToString().ToLowerInvariant() }
        if (Test-IsArray -x $v) {
            if (@($v).Count -eq 0) { return '[]' }
            return (ConvertTo-Json -InputObject $v -Depth 50 -Compress)
        }
        if (Test-IsObject -x $v) { return (ConvertTo-Json -InputObject $v -Depth 50 -Compress) }
        return [string]$v
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Test-IsEmptyLike {
    [CmdletBinding()]
    param($v)
    try {
        if ($null -eq $v) { return $true }
        if ($v -is [string]) { return ([string]::IsNullOrWhiteSpace($v)) }
        if (Test-IsArray -x $v) { return (@($v).Count -eq 0) }
        return $false
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ------------------------------------------------- custom field mapping ----

function Initialize-CustomFieldMapping {
    # Fills $Script:CfByNum and $Script:NameReplacements from the mapping CSV.
    # Identical logic to Find-FiltersWithCustomFields.ps1 (split-row rejoin via NameNormalized).
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$CsvPath, [string]$CsvDelimiter = ',', [int]$MinLen = 3)
    try {
        $cfRows = @(Import-Csv -LiteralPath $CsvPath -Delimiter $CsvDelimiter -Encoding UTF8)
        if (-not $cfRows.Count) { throw "Custom field CSV contains no rows: $CsvPath" }
        Assert-Column -Row $cfRows[0] -Required @('DcId', 'DcName', 'CloudId', 'CloudName') -FileLabel 'Custom field CSV'

        $cloudIndex = @{}
        foreach ($cf in $cfRows) {
            $cId = (Get-Val -Row $cf -Name 'CloudId').Trim()
            $cNm = (Get-Val -Row $cf -Name 'CloudName').Trim()
            if (-not $cId -and -not $cNm) { continue }
            $key = (Get-Val -Row $cf -Name 'NameNormalized').Trim().ToLowerInvariant()
            if (-not $key) { $key = $cNm.ToLowerInvariant() }
            if (-not $key) { continue }
            if (-not $cloudIndex.ContainsKey($key)) {
                $cloudIndex[$key] = [pscustomobject]@{ CloudId = $cId; CloudName = $cNm }
            }
        }

        $byNum = @{}
        foreach ($cf in $cfRows) {
            $rawDcId    = (Get-Val -Row $cf -Name 'DcId').Trim()
            $rawDcName  = (Get-Val -Row $cf -Name 'DcName').Trim()
            $rawCloudId = (Get-Val -Row $cf -Name 'CloudId').Trim()
            $rawCloudNm = (Get-Val -Row $cf -Name 'CloudName').Trim()

            if ($rawDcId -notmatch '(\d+)') { continue }
            $dcNum = $Matches[1]

            $mk = ''
            if ($rawDcName) { $mk = $rawDcName.ToLowerInvariant() }
            $hasMapping = [bool]($mk -and $Script:SystemFieldNameMap.ContainsKey($mk))

            if ((-not $rawCloudNm -or -not $rawCloudId) -and $rawDcName) {
                $own = (Get-Val -Row $cf -Name 'NameNormalized').Trim().ToLowerInvariant()
                if (-not $own) { $own = $mk }
                $hit = $null
                if ($cloudIndex.ContainsKey($own)) { $hit = $cloudIndex[$own] }
                if ($null -eq $hit -and $hasMapping) {
                    $tk = $Script:SystemFieldNameMap[$mk].ToLowerInvariant()
                    if ($cloudIndex.ContainsKey($tk)) { $hit = $cloudIndex[$tk] }
                }
                if ($null -ne $hit) {
                    if (-not $rawCloudNm) { $rawCloudNm = $hit.CloudName }
                    if (-not $rawCloudId) { $rawCloudId = $hit.CloudId }
                }
            }
            if (-not $rawCloudNm -and $hasMapping) { $rawCloudNm = $Script:SystemFieldNameMap[$mk] }

            $cloudNum = ''
            if ($rawCloudId -match '(\d+)') { $cloudNum = $Matches[1] }

            if (-not $byNum.ContainsKey($dcNum)) {
                $byNum[$dcNum] = [pscustomobject]@{
                    DcNum = $dcNum; DcName = $rawDcName; CloudNum = $cloudNum; CloudName = $rawCloudNm; HasMapping = $hasMapping
                }
            }
        }
        $Script:CfByNum = $byNum

        # DC name -> Cloud name replacements, longest DC name first
        $repl = New-Object System.Collections.Generic.List[object]
        $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($f in $byNum.Values) {
            if (-not $f.DcName -or -not $f.CloudName) { continue }
            if ($f.DcName.Length -lt $MinLen) { continue }
            if ($f.DcName -ieq $f.CloudName) { continue }
            if (-not $seen.Add($f.DcName)) { continue }
            $re = New-Object System.Text.RegularExpressions.Regex (('(?<!\w)' + [regex]::Escape($f.DcName) + '(?!\w)'), $Script:RegexIgnoreCase)
            $repl.Add([pscustomobject]@{ Dc = $f.DcName; Cloud = $f.CloudName; Regex = $re })
        }
        foreach ($k in $Script:SystemFieldNameMap.Keys) {
            if ($seen.Add($k)) {
                $re = New-Object System.Text.RegularExpressions.Regex (('(?<!\w)' + [regex]::Escape($k) + '(?!\w)'), $Script:RegexIgnoreCase)
                $repl.Add([pscustomobject]@{ Dc = $k; Cloud = $Script:SystemFieldNameMap[$k]; Regex = $re })
            }
        }
        $Script:NameReplacements = @($repl | Sort-Object -Property @{ Expression = { $_.Dc.Length }; Descending = $true })
        Write-Host ("Custom field mapping: {0} DC fields, {1} name replacements" -f $byNum.Count, $Script:NameReplacements.Count)
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Replace-CfNumbers {
    # customfield_<dc> -> customfield_<cloud> / cf[<dc>] -> cf[<cloud>] via a manual
    # match loop (a MatchEvaluator script block is not reliable on PS 5.1).
    [CmdletBinding()]
    param([string]$Text, [System.Text.RegularExpressions.Regex]$Regex, [string]$Prefix, [string]$Suffix)
    try {
        $found = $Regex.Matches($Text)
        if ($found.Count -eq 0) { return $Text }
        $sb = New-Object System.Text.StringBuilder
        $pos = 0
        foreach ($m in $found) {
            [void]$sb.Append($Text.Substring($pos, [int]($m.Index - $pos)))
            $n = $m.Groups[1].Value
            if ($Script:CfByNum.ContainsKey($n) -and $Script:CfByNum[$n].CloudNum) {
                [void]$sb.Append($Prefix + $Script:CfByNum[$n].CloudNum + $Suffix)
            } else {
                [void]$sb.Append($m.Value)
            }
            $pos = [int]($m.Index + $m.Length)
        }
        [void]$sb.Append($Text.Substring($pos))
        return $sb.ToString()
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function ConvertTo-CloudString {
    # Rewrites a DC string so that it should equal the Cloud string if migration was correct.
    [CmdletBinding()]
    param([string]$s)
    try {
        if ([string]::IsNullOrEmpty($s)) { return $s }
        $out = Replace-CfNumbers -Text $s   -Regex $Script:CfIdRegex      -Prefix 'customfield_' -Suffix ''
        $out = Replace-CfNumbers -Text $out -Regex $Script:CfBracketRegex -Prefix 'cf['          -Suffix ']'
        foreach ($r in $Script:NameReplacements) { $out = $r.Regex.Replace($out, [string]$r.Cloud) }
        return $out
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-NormalizedString {
    [CmdletBinding()]
    param([string]$s)
    try {
        if ($null -eq $s) { return '' }
        $s = $s -replace "`r`n", "`n"
        $s = $s -replace '[ \t]+', ' '
        $s = (($s -split "`n") | ForEach-Object { $_.Trim() }) -join "`n"
        $s = $s.Trim()
        if (-not $Script:CompareCaseSensitive) { $s = $s.ToLowerInvariant() }
        return $s
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ------------------------------------------------------------ fetch rules ----

function Get-CachePath {
    [CmdletBinding()]
    param([string]$Side, [string]$Id)
    try {
        if (-not $CacheDir) { return $null }
        return (Join-Path (Join-Path $CacheDir $Side) "$Id.json")
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-DcRules {
    [CmdletBinding()]
    param()
    try {
        $listPath = Get-CachePath -Side 'dc' -Id '_list'
        if ($listPath -and -not $RefreshCache -and (Test-Path -LiteralPath $listPath)) {
            Write-Host 'DC: using cached rule list'
            return @(Get-ListPayload -Obj (Read-Utf8Json -Path $listPath))
        }
        Write-Host 'DC: loading automation rules ...'
        $raw  = Invoke-JsonGet -Uri "$DcBaseUrl/rest/cb-automation/latest/project/GLOBAL/rule" -Headers $Script:DcHeaders
        $list = @(Get-ListPayload -Obj $raw)
        Write-Host ("DC: {0} rule(s) in list" -f $list.Count)
        $full  = New-Object System.Collections.Generic.List[object]
        $total = [int]$list.Count
        if ($total -lt 1) { $total = 1 }
        $i = 0
        foreach ($r in $list) {
            $i++
            # list may be summaries only - fetch full config where components are missing
            if (-not (Test-HasProp -Obj $r -Name 'components') -and -not (Test-HasProp -Obj $r -Name 'trigger')) {
                $pct = [int](100 * $i / $total)
                Write-Progress -Activity 'DC rules' -Status "$i / $total" -PercentComplete $pct
                $rid = [string](Get-Prop -Obj $r -Name 'id')
                $r = Invoke-JsonGet -Uri "$DcBaseUrl/rest/cb-automation/latest/project/GLOBAL/rule/$rid" -Headers $Script:DcHeaders
            }
            $full.Add($r)
        }
        Write-Progress -Activity 'DC rules' -Completed
        $result = $full.ToArray()
        if ($listPath) { Write-Utf8Json -Path $listPath -Obj $result }
        return $result
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-NextPageUrl {
    # Cursor pagination of the public Automation API: links.next (URL or cursor) or a next cursor field.
    [CmdletBinding()]
    param($Page, [string]$BaseUrl)
    try {
        if ($null -eq $Page -or $Page -is [System.Array]) { return '' }
        $next = ''
        $links = Get-Prop -Obj $Page -Name 'links'
        if ($null -ne $links) { $next = [string](Get-Prop -Obj $links -Name 'next') }
        if (-not $next) {
            foreach ($k in 'nextCursor', 'nextPageToken', 'next') {
                $v = [string](Get-Prop -Obj $Page -Name $k)
                if ($v) { $next = $v; break }
            }
        }
        if (-not $next) { return '' }
        if ($next -match '^https?://') { return $next }
        return ($BaseUrl + '?cursor=' + [uri]::EscapeDataString($next))
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-CloudRuleSummaries {
    # Lists rules via the public Automation Rule Management API (GET /rest/v1/rule/summary).
    # Tries api.atlassian.com first, then the site gateway alias. Returns { Base, Rules }.
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$CloudId)
    try {
        $candidates = @(
            "https://api.atlassian.com/automation/public/jira/$CloudId/rest/v1",
            "$CloudBaseUrl/gateway/api/automation/public/jira/$CloudId/rest/v1"
        )
        $base = ''; $page = $null
        foreach ($c in $candidates) {
            try {
                $page = Invoke-JsonGet -Uri "$c/rule/summary" -Headers $Script:CloudHeaders
                $base = $c
                break
            } catch {
                Write-Warning ("Cloud: {0}/rule/summary failed: {1}" -f $c, (([string]$_.Exception.Message) -split "`n")[0])
            }
        }
        if (-not $base) { throw 'Automation public API not reachable via api.atlassian.com nor the site gateway (see warnings above).' }

        $all  = New-Object System.Collections.Generic.List[object]
        $seen = New-Object 'System.Collections.Generic.HashSet[string]'
        $listUrl = "$base/rule/summary"
        [void]$seen.Add($listUrl)
        $guard = 0
        while ($null -ne $page -and $guard -lt 1000) {
            $guard++
            foreach ($r in @(Get-ListPayload -Obj $page)) { $all.Add($r) }
            $nextUrl = Get-NextPageUrl -Page $page -BaseUrl $listUrl
            if (-not $nextUrl -or -not $seen.Add($nextUrl)) { break }
            $page = Invoke-JsonGet -Uri $nextUrl -Headers $Script:CloudHeaders
        }
        return [pscustomobject]@{ Base = $base; Rules = $all.ToArray() }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-CloudRules {
    [CmdletBinding()]
    param()
    try {
        $listPath = Get-CachePath -Side 'cloud' -Id '_list'
        if ($listPath -and -not $RefreshCache -and (Test-Path -LiteralPath $listPath)) {
            Write-Host 'Cloud: using cached rule list'
            return @(Get-ListPayload -Obj (Read-Utf8Json -Path $listPath))
        }
        Write-Host 'Cloud: resolving cloudId ...'
        $tenant  = Invoke-JsonGet -Uri "$CloudBaseUrl/_edge/tenant_info" -Headers $Script:CloudHeaders
        $cloudId = [string](Get-Prop -Obj $tenant -Name 'cloudId')
        if (-not $cloudId) { throw 'Could not read cloudId from /_edge/tenant_info' }

        Write-Host 'Cloud: loading rule summaries (public Automation API) ...'
        $sum = Get-CloudRuleSummaries -CloudId $cloudId
        $api = [string]$sum.Base
        $all = @($sum.Rules)
        Write-Host ("Cloud: {0} rule(s) in list via {1}" -f $all.Count, $api)

        $full  = New-Object System.Collections.Generic.List[object]
        $total = [int]$all.Count
        if ($total -lt 1) { $total = 1 }
        $i = 0
        foreach ($r in $all) {
            $i++
            # the full-rule endpoint is addressed by the rule UUID
            $rid = [string](Get-Prop -Obj $r -Name 'uuid')
            if (-not $rid) { $rid = [string](Get-Prop -Obj $r -Name 'id') }
            if (-not $rid) { Write-Warning "Cloud: summary entry without id/uuid skipped: $(Format-Value -v $r)"; continue }
            $cp = Get-CachePath -Side 'cloud' -Id $rid
            if ($cp -and -not $RefreshCache -and (Test-Path -LiteralPath $cp)) {
                $full.Add((Read-Utf8Json -Path $cp)); continue
            }
            $pct = [int](100 * $i / $total)
            Write-Progress -Activity 'Cloud rules' -Status "$i / $total" -PercentComplete $pct
            $resp = Invoke-JsonGet -Uri "$api/rule/$rid" -Headers $Script:CloudHeaders
            $rule = Get-Prop -Obj $resp -Name 'rule'      # GET /rule/{uuid} may wrap the rule in { rule: {...} }
            if ($null -eq $rule) { $rule = $resp }
            if ($cp) { Write-Utf8Json -Path $cp -Obj $rule }
            $full.Add($rule)
        }
        Write-Progress -Activity 'Cloud rules' -Completed
        $result = $full.ToArray()
        if ($listPath) { Write-Utf8Json -Path $listPath -Obj $result }
        return $result
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-DcProjectKeys {
    [CmdletBinding()]
    param()
    $map = @{}
    try {
        $projects = @(Get-ListPayload -Obj (Invoke-JsonGet -Uri "$DcBaseUrl/rest/api/2/project" -Headers $Script:DcHeaders))
        foreach ($p in $projects) { $map[[string](Get-Prop -Obj $p -Name 'id')] = [string](Get-Prop -Obj $p -Name 'key') }
    } catch {
        Write-Warning "DC: could not load projects; scope shown as ids. $($_.Exception.Message)"
    }
    return $map
}

function Get-CloudProjectKeys {
    [CmdletBinding()]
    param()
    $map = @{}
    try {
        $startAt = 0
        do {
            $page = Invoke-JsonGet -Uri "$CloudBaseUrl/rest/api/3/project/search?startAt=$startAt&maxResults=50" -Headers $Script:CloudHeaders
            $vals = @(Get-ArrayProp -Obj $page -Name 'values')
            foreach ($p in $vals) { $map[[string](Get-Prop -Obj $p -Name 'id')] = [string](Get-Prop -Obj $p -Name 'key') }
            $startAt += 50
            $isLast = [bool](Get-Prop -Obj $page -Name 'isLast')
        } while ($vals.Count -gt 0 -and -not $isLast)
    } catch {
        Write-Warning "Cloud: could not load projects; scope shown as ids. $($_.Exception.Message)"
    }
    return $map
}

# ------------------------------------------------------- rule accessors ----

function Get-RuleScope {
    [CmdletBinding()]
    param($Rule, [hashtable]$ProjectKeys)
    try {
        $ids = New-Object System.Collections.Generic.List[string]
        foreach ($p in @(Get-ArrayProp -Obj $Rule -Name 'projects')) {
            $pid = Get-Prop -Obj $p -Name 'projectId'
            if ($null -eq $pid) { $pid = Get-Prop -Obj $p -Name 'id' }
            if ($null -ne $pid) { $ids.Add([string]$pid) }
        }
        $scope = Get-Prop -Obj $Rule -Name 'ruleScope'
        if ($null -ne $scope) {
            foreach ($res in @(Get-ArrayProp -Obj $scope -Name 'resources')) {
                $s = [string]$res
                if ($s -match 'project/(\d+)') { $ids.Add($Matches[1]) }
                elseif ($s -match '^ari:cloud:jira::site/') { $ids.Add('GLOBAL') }
                elseif ($s) { $ids.Add($s) }
            }
        }
        if ($ids.Count -eq 0) { return 'GLOBAL' }
        $keys = foreach ($id in @($ids | Select-Object -Unique)) {
            if ($ProjectKeys.ContainsKey($id)) { $ProjectKeys[$id] } else { $id }
        }
        return ((@($keys) | Sort-Object) -join ', ')
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-RuleActor {
    [CmdletBinding()]
    param($Rule)
    try {
        $a = Get-Prop -Obj $Rule -Name 'actor'
        if ($null -eq $a) { return '' }
        if ($a -is [string]) { return $a }
        $t  = [string](Get-Prop -Obj $a -Name 'type')
        $v  = [string](Get-Prop -Obj $a -Name 'value')
        $dn = [string](Get-Prop -Obj $a -Name 'displayName')
        if ($dn) { return "${t}:${dn}" }
        return "${t}:${v}"
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-RuleLabels {
    [CmdletBinding()]
    param($Rule)
    try {
        $l = @(Get-ArrayProp -Obj $Rule -Name 'labels')
        if ($l.Count -eq 0) { return '' }
        $names = foreach ($x in $l) {
            if ($x -is [string]) { $x }
            else { $n = Get-Prop -Obj $x -Name 'name'; if ($n) { [string]$n } else { Format-Value -v $x } }
        }
        return ((@($names) | Sort-Object) -join ', ')
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Add-FlatStep {
    [CmdletBinding()]
    param($Node, [string]$Path, $Steps)
    try {
        if ($null -eq $Node) { return }
        $kind = [string](Get-Prop -Obj $Node -Name 'component')
        $type = [string](Get-Prop -Obj $Node -Name 'type')
        $Steps.Add([pscustomobject]@{ Path = $Path; Kind = $kind; Type = $type; Node = $Node })
        $conds = @(Get-ArrayProp -Obj $Node -Name 'conditions')
        for ($i = 0; $i -lt $conds.Count; $i++) { Add-FlatStep -Node $conds[$i] -Path "$Path.c$($i + 1)" -Steps $Steps }
        $children = @(Get-ArrayProp -Obj $Node -Name 'children')
        for ($i = 0; $i -lt $children.Count; $i++) { Add-FlatStep -Node $children[$i] -Path "$Path.$($i + 1)" -Steps $Steps }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-FlatSteps {
    # Depth-first flatten of trigger + components, descending into children and conditions.
    # DC export keeps the trigger in a separate "trigger" key; the Cloud public API puts it
    # into components[] as component = TRIGGER. Both end up at path "T"; the rest is numbered.
    [CmdletBinding()]
    param($Rule)
    try {
        $steps = New-Object System.Collections.Generic.List[object]
        $trigger = Get-Prop -Obj $Rule -Name 'trigger'
        if ($null -ne $trigger) { Add-FlatStep -Node $trigger -Path 'T' -Steps $steps }
        $comps = @(Get-ArrayProp -Obj $Rule -Name 'components')
        $n = 0
        foreach ($c in $comps) {
            $kind = [string](Get-Prop -Obj $c -Name 'component')
            if ($kind -eq 'TRIGGER') {
                if ($null -eq $trigger) { Add-FlatStep -Node $c -Path 'T' -Steps $steps; $trigger = $c }
                continue
            }
            $n++
            Add-FlatStep -Node $c -Path ([string]$n) -Steps $steps
        }
        return $steps.ToArray()
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ------------------------------------------------------------- deep diff ----

function Compare-Deep {
    # Walks DC and Cloud values in parallel; DC strings are rewritten with the field mapping.
    # Appends { Path, Dc, Cloud } to $Diffs; stops after the first one when $StopAtFirst.
    [CmdletBinding()]
    param($Dc, $Cloud, [string]$Path, $Diffs, [bool]$StopAtFirst)
    try {
        if ($StopAtFirst -and $Diffs.Count -gt 0) { return }

        $dcEmpty = Test-IsEmptyLike -v $Dc; $clEmpty = Test-IsEmptyLike -v $Cloud
        if ($dcEmpty -and $clEmpty) { return }
        if ($dcEmpty -ne $clEmpty) {
            $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value -v $Dc); Cloud = (Format-Value -v $Cloud) }); return
        }

        $dcObj = Test-IsObject -x $Dc; $clObj = Test-IsObject -x $Cloud
        $dcArr = Test-IsArray  -x $Dc; $clArr = Test-IsArray  -x $Cloud

        if ($dcObj -and $clObj) {
            $keys = @(@(Get-PropNames -Obj $Dc) + @(Get-PropNames -Obj $Cloud) | Select-Object -Unique)
            foreach ($k in $keys) {
                if ($Script:IgnoreKeys.Contains($k)) { continue }
                if ($k -ieq 'children' -or $k -ieq 'conditions') { continue }   # compared as separate steps
                $dv = $null; $cv = $null
                if (Test-HasProp -Obj $Dc -Name $k)    { $dv = Get-Prop -Obj $Dc -Name $k }
                if (Test-HasProp -Obj $Cloud -Name $k) { $cv = Get-Prop -Obj $Cloud -Name $k }
                Compare-Deep -Dc $dv -Cloud $cv -Path "$Path.$k" -Diffs $Diffs -StopAtFirst $StopAtFirst
                if ($StopAtFirst -and $Diffs.Count -gt 0) { return }
            }
            return
        }
        if ($dcArr -and $clArr) {
            $da = @($Dc); $ca = @($Cloud)
            if ($da.Count -ne $ca.Count) {
                $Diffs.Add([pscustomobject]@{ Path = "$Path (count)"; Dc = "$($da.Count) items: $(Format-Value -v $da)"; Cloud = "$($ca.Count) items: $(Format-Value -v $ca)" })
                return
            }
            for ($i = 0; $i -lt $da.Count; $i++) {
                Compare-Deep -Dc $da[$i] -Cloud $ca[$i] -Path "$Path[$i]" -Diffs $Diffs -StopAtFirst $StopAtFirst
                if ($StopAtFirst -and $Diffs.Count -gt 0) { return }
            }
            return
        }
        if ($dcObj -or $clObj -or $dcArr -or $clArr) {
            $Diffs.Add([pscustomobject]@{ Path = "$Path (type)"; Dc = (Format-Value -v $Dc); Cloud = (Format-Value -v $Cloud) }); return
        }

        # scalars
        if ($Dc -is [bool] -or $Cloud -is [bool]) {
            if ([string]$Dc -ine [string]$Cloud) {
                $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value -v $Dc); Cloud = (Format-Value -v $Cloud) })
            }
            return
        }
        $ds = Get-NormalizedString -s (ConvertTo-CloudString -s ([string]$Dc))
        $cs = Get-NormalizedString -s ([string]$Cloud)
        if ($ds -cne $cs) {
            $Diffs.Add([pscustomobject]@{ Path = $Path; Dc = (Format-Value -v $Dc); Cloud = (Format-Value -v $Cloud) })
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ---------------------------------------------------------- report rows ----

function New-SummaryRow {
    [CmdletBinding()]
    param([string]$Name, $Dc, $Cloud, [string]$MatchStatus)
    try {
        $row = [ordered]@{
            'Rule Name'               = $Name
            'Match Status'            = $MatchStatus
            'Verdict'                 = ''
            'Differences'             = 0
            'First Difference'        = ''
            'DC Rule ID'              = ''
            'Cloud Rule ID'           = ''
            'DC State'                = ''
            'Cloud State'             = ''
            'DC Scope'                = ''
            'Cloud Scope'             = ''
            'DC Actor'                = ''
            'Cloud Actor'             = ''
            'DC Allow Other Rules'    = ''
            'Cloud Allow Other Rules' = ''
            'DC Notify On Error'      = ''
            'Cloud Notify On Error'   = ''
            'DC Edit Access'          = ''
            'Cloud Edit Access'       = ''
            'DC Labels'               = ''
            'Cloud Labels'            = ''
            'DC Steps'                = ''
            'Cloud Steps'             = ''
            'DC JSON'                 = ''
            'Cloud JSON'              = ''
        }
        if ($null -ne $Dc) {
            $row['DC Rule ID']           = [string](Get-Prop -Obj $Dc -Name 'id')
            $row['DC State']             = [string](Get-Prop -Obj $Dc -Name 'state')
            $row['DC Scope']             = Get-RuleScope -Rule $Dc -ProjectKeys $Script:DcProjectKeys
            $row['DC Actor']             = Get-RuleActor -Rule $Dc
            $row['DC Allow Other Rules'] = Format-Value -v (Get-Prop -Obj $Dc -Name 'canOtherRuleTrigger')
            $row['DC Notify On Error']   = [string](Get-Prop -Obj $Dc -Name 'notifyOnError')
            $row['DC Edit Access']       = [string](Get-Prop -Obj $Dc -Name 'writeAccessType')
            $row['DC Labels']            = Get-RuleLabels -Rule $Dc
        }
        if ($null -ne $Cloud) {
            $row['Cloud Rule ID']           = [string](Get-Prop -Obj $Cloud -Name 'id')
            $row['Cloud State']             = [string](Get-Prop -Obj $Cloud -Name 'state')
            $row['Cloud Scope']             = Get-RuleScope -Rule $Cloud -ProjectKeys $Script:CloudProjectKeys
            $row['Cloud Actor']             = Get-RuleActor -Rule $Cloud
            $row['Cloud Allow Other Rules'] = Format-Value -v (Get-Prop -Obj $Cloud -Name 'canOtherRuleTrigger')
            $row['Cloud Notify On Error']   = [string](Get-Prop -Obj $Cloud -Name 'notifyOnError')
            $row['Cloud Edit Access']       = [string](Get-Prop -Obj $Cloud -Name 'writeAccessType')
            $row['Cloud Labels']            = Get-RuleLabels -Rule $Cloud
        }
        return $row
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Add-Detail {
    [CmdletBinding()]
    param($Row, [string]$Area, [string]$StepPath, [string]$StepKind, [string]$StepType, [string]$Prop, [string]$DcVal, [string]$CloudVal)
    try {
        $Script:Details.Add([pscustomobject]@{
            'Rule Name'     = $Row['Rule Name']
            'DC Rule ID'    = $Row['DC Rule ID']
            'Cloud Rule ID' = $Row['Cloud Rule ID']
            'Area'          = $Area
            'Step Path'     = $StepPath
            'Step Kind'     = $StepKind
            'Step Type'     = $StepType
            'Property'      = $Prop
            'DC Value'      = $DcVal
            'Cloud Value'   = $CloudVal
        })
        $Row['Differences'] = [int]$Row['Differences'] + 1
        if (-not $Row['First Difference']) {
            $label = $Prop
            if ($StepPath) { $label = "[$StepPath $StepType] $Prop" }
            $Row['First Difference'] = "$label | DC: $DcVal | Cloud: $CloudVal"
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Write-RuleJsonFiles {
    [CmdletBinding()]
    param($Row, $Dc, $Cloud)
    try {
        $base = ConvertTo-SafeFileName -Name $Row['Rule Name']
        if ($null -ne $Dc) {
            $p = Join-Path $Script:JsonDir ("{0}_{1}_DC.json" -f $base, $Row['DC Rule ID'])
            Write-Utf8Json -Path $p -Obj $Dc; $Row['DC JSON'] = $p
        }
        if ($null -ne $Cloud) {
            $p = Join-Path $Script:JsonDir ("{0}_{1}_Cloud.json" -f $base, $Row['Cloud Rule ID'])
            Write-Utf8Json -Path $p -Obj $Cloud; $Row['Cloud JSON'] = $p
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Compare-Rule {
    # Fills $Row with the comparison of one DC rule against its Cloud counterpart.
    [CmdletBinding()]
    param($Row, $Dc, $Cloud)
    try {
        $stop = $false

        # ---- 1. rule-level settings
        $ruleChecks = @(
            @{ Name = 'State';             Dc = $Row['DC State'];             Cloud = $Row['Cloud State'] }
            @{ Name = 'Scope';             Dc = $Row['DC Scope'];             Cloud = $Row['Cloud Scope'] }
            @{ Name = 'Allow Other Rules'; Dc = $Row['DC Allow Other Rules']; Cloud = $Row['Cloud Allow Other Rules'] }
            @{ Name = 'Notify On Error';   Dc = $Row['DC Notify On Error'];   Cloud = $Row['Cloud Notify On Error'] }
            @{ Name = 'Edit Access';       Dc = $Row['DC Edit Access'];       Cloud = $Row['Cloud Edit Access'] }
            @{ Name = 'Labels';            Dc = $Row['DC Labels'];            Cloud = $Row['Cloud Labels'] }
            @{ Name = 'Description';       Dc = [string](Get-Prop -Obj $Dc -Name 'description'); Cloud = [string](Get-Prop -Obj $Cloud -Name 'description') }
        )
        foreach ($c in $ruleChecks) {
            $a = Get-NormalizedString -s (ConvertTo-CloudString -s ([string]$c.Dc))
            $b = Get-NormalizedString -s ([string]$c.Cloud)
            if ($a -cne $b) {
                Add-Detail -Row $Row -Area 'Rule' -StepPath '' -StepKind '' -StepType '' -Prop $c.Name -DcVal ([string]$c.Dc) -CloudVal ([string]$c.Cloud)
                if ($Script:StopAtFirst) { $stop = $true; break }
            }
        }

        # ---- 2. step count
        $dcSteps = @(Get-FlatSteps -Rule $Dc)
        $clSteps = @(Get-FlatSteps -Rule $Cloud)
        $Row['DC Steps'] = $dcSteps.Count; $Row['Cloud Steps'] = $clSteps.Count
        if (-not $stop -and $dcSteps.Count -ne $clSteps.Count) {
            $dcList = @($dcSteps | ForEach-Object { "$($_.Path) $($_.Kind) $($_.Type)" }) -join $NL
            $clList = @($clSteps | ForEach-Object { "$($_.Path) $($_.Kind) $($_.Type)" }) -join $NL
            Add-Detail -Row $Row -Area 'Steps' -StepPath '' -StepKind '' -StepType '' -Prop 'Step count' -DcVal "$($dcSteps.Count)$NL$dcList" -CloudVal "$($clSteps.Count)$NL$clList"
            if ($Script:StopAtFirst) { $stop = $true }
        }

        # ---- 3./4. per step
        if (-not $stop) {
            $count = [Math]::Min([int]$dcSteps.Count, [int]$clSteps.Count)
            for ($i = 0; $i -lt $count -and -not $stop; $i++) {
                $ds = $dcSteps[$i]; $cs = $clSteps[$i]
                if ($ds.Path -ne $cs.Path -or $ds.Kind -ine $cs.Kind -or $ds.Type -ine $cs.Type) {
                    Add-Detail -Row $Row -Area 'Step' -StepPath $ds.Path -StepKind $ds.Kind -StepType $ds.Type -Prop 'Step kind/type' `
                        -DcVal "$($ds.Path) $($ds.Kind) $($ds.Type)" -CloudVal "$($cs.Path) $($cs.Kind) $($cs.Type)"
                    if ($Script:StopAtFirst) { $stop = $true; break }
                    continue
                }
                $diffs = New-Object System.Collections.Generic.List[object]
                Compare-Deep -Dc $ds.Node -Cloud $cs.Node -Path '' -Diffs $diffs -StopAtFirst $Script:StopAtFirst
                foreach ($d in $diffs) {
                    Add-Detail -Row $Row -Area 'StepConfig' -StepPath $ds.Path -StepKind $ds.Kind -StepType $ds.Type -Prop ($d.Path.TrimStart('.')) -DcVal $d.Dc -CloudVal $d.Cloud
                    if ($Script:StopAtFirst) { $stop = $true; break }
                }
            }
        }

        if ([int]$Row['Differences'] -eq 0) { $Row['Verdict'] = 'Identical' }
        elseif ($Script:StopAtFirst)         { $Row['Verdict'] = 'Different (stopped at first)' }
        else                                  { $Row['Verdict'] = 'Different' }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Export-Results {
    [CmdletBinding()]
    param([string]$Dir, [string]$Stamp, [string]$CsvDelimiter)
    try {
        $enc = 'UTF8'
        if ($PSVersionTable.PSVersion.Major -ge 6) { $enc = 'utf8BOM' }
        $summaryPath = Join-Path $Dir "AutomationCompare_Summary_$Stamp.csv"
        $detailsPath = Join-Path $Dir "AutomationCompare_Details_$Stamp.csv"
        $Script:Summary | Export-Csv -LiteralPath $summaryPath -Delimiter $CsvDelimiter -Encoding $enc -NoTypeInformation
        if ($Script:Details.Count) {
            $Script:Details | Export-Csv -LiteralPath $detailsPath -Delimiter $CsvDelimiter -Encoding $enc -NoTypeInformation
        } else {
            $header = (@($Script:DetailColumns | ForEach-Object { '"' + $_ + '"' }) -join $CsvDelimiter) + "`r`n"
            [System.IO.File]::WriteAllText($detailsPath, $header, (New-Object System.Text.UTF8Encoding $true))
        }
        return [pscustomobject]@{ Summary = $summaryPath; Details = $detailsPath }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ============================================================================
#  3. BUSINESS LOGIC
# ============================================================================

try {
    # ---- validation / setup
    if (-not $CloudCookie -and (-not $CloudEmail -or -not $CloudApiToken)) {
        throw 'Provide -CloudEmail and -CloudApiToken, or -CloudCookie.'
    }
    if (-not (Test-Path -LiteralPath $CustomFieldsCsv)) { throw "Input file not found: $CustomFieldsCsv" }

    $DcBaseUrl    = $DcBaseUrl.TrimEnd('/')
    $CloudBaseUrl = $CloudBaseUrl.TrimEnd('/')
    if ($DcBaseUrl    -notmatch '^https?://') { $DcBaseUrl    = "https://$DcBaseUrl" }
    if ($CloudBaseUrl -notmatch '^https?://') { $CloudBaseUrl = "https://$CloudBaseUrl" }

    $Script:StopAtFirst          = -not $FullDiff
    $Script:CompareCaseSensitive = [bool]$CaseSensitive

    $Script:DcHeaders    = @{ 'Accept' = 'application/json'; 'Authorization' = "Bearer $DcToken" }
    $Script:CloudHeaders = @{ 'Accept' = 'application/json'; 'X-Atlassian-Token' = 'no-check' }
    if ($CloudCookie) {
        $Script:CloudHeaders['Cookie'] = $CloudCookie
    } else {
        $pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${CloudEmail}:${CloudApiToken}"))
        $Script:CloudHeaders['Authorization'] = "Basic $pair"
    }

    $allSkip = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($n in @($Script:SkipRuleNamesInScript) + @($SkipRuleNames)) { if ($n) { [void]$allSkip.Add((Get-RuleNameKey -Name $n)) } }
    $onlyNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($n in $RuleName) { if ($n) { [void]$onlyNames.Add((Get-RuleNameKey -Name $n)) } }

    $ts = Get-Date -Format 'yyyyMMdd_HHmmss'
    if (-not (Test-Path -LiteralPath $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null }
    $OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
    $Script:JsonDir = Join-Path $OutputDir 'json'
    if (-not (Test-Path -LiteralPath $Script:JsonDir)) { New-Item -ItemType Directory -Path $Script:JsonDir -Force | Out-Null }
    if ($CacheDir) {
        foreach ($sub in @('dc', 'cloud')) {
            $d = Join-Path $CacheDir $sub
            if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
        }
        $CacheDir = (Resolve-Path -LiteralPath $CacheDir).Path
    }

    Initialize-CustomFieldMapping -CsvPath $CustomFieldsCsv -CsvDelimiter $Delimiter -MinLen $MinNameLength

    # ---- fetch
    $dcRules    = @(Get-DcRules)
    $cloudRules = @(Get-CloudRules)
    Write-Host ("DC rules: {0}, Cloud rules: {1}" -f $dcRules.Count, $cloudRules.Count)
    $Script:DcProjectKeys    = Get-DcProjectKeys
    $Script:CloudProjectKeys = Get-CloudProjectKeys

    # ---- index by name
    $dcByName = @{}; $cloudByName = @{}
    foreach ($r in $dcRules) {
        $k = Get-RuleNameKey -Name ([string](Get-Prop -Obj $r -Name 'name'))
        if (-not $dcByName.ContainsKey($k)) { $dcByName[$k] = New-Object System.Collections.Generic.List[object] }
        $dcByName[$k].Add($r)
    }
    foreach ($r in $cloudRules) {
        $k = Get-RuleNameKey -Name ([string](Get-Prop -Obj $r -Name 'name'))
        if (-not $cloudByName.ContainsKey($k)) { $cloudByName[$k] = New-Object System.Collections.Generic.List[object] }
        $cloudByName[$k].Add($r)
    }
    foreach ($k in $dcByName.Keys)    { if ($dcByName[$k].Count -gt 1)    { Write-Warning "DC has $($dcByName[$k].Count) rules named ""$(Get-Prop -Obj $dcByName[$k][0] -Name 'name')""." } }
    foreach ($k in $cloudByName.Keys) { if ($cloudByName[$k].Count -gt 1) { Write-Warning "Cloud has $($cloudByName[$k].Count) rules named ""$(Get-Prop -Obj $cloudByName[$k][0] -Name 'name')""." } }

    # ---- work list: DC rules sorted by name, filtered, offset/limit
    $work = @($dcRules | Sort-Object -Property @{ Expression = { [string](Get-Prop -Obj $_ -Name 'name') } })
    $work = @($work | Where-Object {
        $k = Get-RuleNameKey -Name ([string](Get-Prop -Obj $_ -Name 'name'))
        (-not $allSkip.Contains($k)) -and ($onlyNames.Count -eq 0 -or $onlyNames.Contains($k))
    })
    if ($Skip -gt 0)     { $work = @($work | Select-Object -Skip $Skip) }
    if ($MaxRules -gt 0) { $work = @($work | Select-Object -First $MaxRules) }
    Write-Host ("Processing {0} DC rule(s) (skip {1}, max {2}, {3} name(s) on skip list)" -f $work.Count, $Skip, $MaxRules, $allSkip.Count)

    # ---- compare
    $n = 0
    foreach ($dc in $work) {
        $n++
        $name = [string](Get-Prop -Obj $dc -Name 'name')
        $key  = Get-RuleNameKey -Name $name
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
            $row['Verdict']  = 'NotFoundInCloud'
            $row['DC Steps'] = @(Get-FlatSteps -Rule $dc).Count
        } else {
            Compare-Rule -Row $row -Dc $dc -Cloud $cloud
        }
        $Script:Summary.Add([pscustomobject]$row)
    }

    # ---- Cloud-only rules (no DC counterpart): only on a full run, or when asked
    if ($IncludeCloudOnlyRules -or ($MaxRules -eq 0 -and $Skip -eq 0 -and $onlyNames.Count -eq 0)) {
        foreach ($k in $cloudByName.Keys) {
            if ($dcByName.ContainsKey($k) -or $allSkip.Contains($k)) { continue }
            foreach ($cr in $cloudByName[$k]) {
                $row = New-SummaryRow -Name ([string](Get-Prop -Obj $cr -Name 'name')) -Dc $null -Cloud $cr -MatchStatus 'NotFoundInDC'
                $row['Verdict']     = 'NotFoundInDC'
                $row['Cloud Steps'] = @(Get-FlatSteps -Rule $cr).Count
                Write-RuleJsonFiles -Row $row -Dc $null -Cloud $cr
                $Script:Summary.Add([pscustomobject]$row)
            }
        }
    }

    # ---- output
    $paths = Export-Results -Dir $OutputDir -Stamp $ts -CsvDelimiter $Delimiter
    $ident = @($Script:Summary | Where-Object { $_.Verdict -eq 'Identical' }).Count
    $diff  = @($Script:Summary | Where-Object { $_.Verdict -like 'Different*' }).Count
    $noCl  = @($Script:Summary | Where-Object { $_.Verdict -eq 'NotFoundInCloud' }).Count
    $noDc  = @($Script:Summary | Where-Object { $_.Verdict -eq 'NotFoundInDC' }).Count
    Write-Host ("Done. Identical: {0}, Different: {1}, NotFoundInCloud: {2}, NotFoundInDC: {3}" -f $ident, $diff, $noCl, $noDc)
    Write-Host "Summary : $($paths.Summary)"
    Write-Host "Details : $($paths.Details)"
    Write-Host "JSON    : $Script:JsonDir"
}
catch {
    if ($_.FullyQualifiedErrorId -like 'FunctionError*') {
        # a function already produced the full report (message, Format-List, trace, parameters)
        $ErrorMessage = $_.Exception.Message
    } else {
        $ErrorMessage = "$($_.Exception.Message)`n$($_ | Format-List | Out-String)`nError Trace:`n$($_.ScriptStackTrace)"
    }
    Write-Error "$ErrorMessage" -ErrorAction Continue
    Exit 1
}
