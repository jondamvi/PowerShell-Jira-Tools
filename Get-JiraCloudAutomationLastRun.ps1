#requires -Version 5.1
<#
.SYNOPSIS
    Exports every Jira Cloud automation rule with its last execution status to CSV.

.DESCRIPTION
    Uses the Atlassian gateway "internal-api" that the Automation admin UI itself calls
    (there is no public REST endpoint for the automation audit log). Endpoints:
        GET /_edge/tenant_info                                            -> cloudId
        GET /gateway/api/automation/internal-api/jira/{cloudId}/pro/rest/GLOBAL/rule
        GET /gateway/api/automation/internal-api/jira/{cloudId}/pro/rest/GLOBAL/auditlog?limit=&offset=
        GET /gateway/api/automation/internal-api/jira/{cloudId}/pro/rest/GLOBAL/auditlog/item/{id}

    The global audit log is newest-first, so the first entry seen per ruleId is that rule's
    last execution. Paging stops when every rule has an entry or -MaxLogEntries is reached.
    Rules with no entry in the scanned window get status "NoExecutionFound".

    All HTTP bodies are decoded as UTF-8 explicitly and the CSV is written UTF-8 with BOM,
    so umlauts survive on Windows PowerShell 5.1 and open correctly in Excel.

.PARAMETER Site        Cloud site host, e.g. "mycompany.atlassian.net" (with or without https://).
.PARAMETER Email       Atlassian account email that owns the API token (Jira admin).
.PARAMETER ApiToken    Atlassian API token.
.PARAMETER Cookie      Optional. Browser "Cookie:" header of a logged-in admin session, used
                       INSTEAD of Email/ApiToken if the gateway rejects basic auth (401/403).
.PARAMETER OutputPath  CSV path. Default: .\JiraCloudAutomationLastRun_<timestamp>.csv
.PARAMETER Delimiter   CSV delimiter. Default ','. Use ';' for German Excel locale.
.PARAMETER MaxLogEntries  Upper bound of audit-log entries to scan (default 20000).
.PARAMETER PageSize    Audit-log page size (default 100).

.EXAMPLE
    .\Get-JiraCloudAutomationLastRun.ps1 -Site mycompany.atlassian.net -Email me@corp.de -ApiToken $tok -Delimiter ';'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Site,
    [string]$Email,
    [string]$ApiToken,
    [string]$Cookie,
    [string]$OutputPath,
    [string]$Delimiter = ',',
    [int]$MaxLogEntries = 20000,
    [int]$PageSize = 100
)

# ============================================================================
#  1. GLOBAL SETTINGS AND SCRIPT VARIABLES
# ============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference    = 'Stop'
$Script:ErrorAction       = 'Stop'
$PSDefaultParameterValues = @{ '*:ErrorAction' = $Script:ErrorAction }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Script:SecretParameterPattern = 'Token|Cookie|Password|Secret|ApiKey|Headers'
$Script:FailedCategories = @('FAILURE', 'SOME_ERRORS', 'ABORTED')
$Script:ErrorKeyRegex    = New-Object System.Text.RegularExpressions.Regex ('^(errors?|errorMessages?|message|messages|failureReason|reason)$')
$Script:Headers = @{}
$Script:Api     = ''

# ============================================================================
#  2. FUNCTIONS
# ============================================================================

function Format-FunctionError {
    # Error text for a function's catch block, with a report of that function's parameters
    # (bound values, defaults marked "(Default)", secrets masked). Must be called from the
    # failing function's own catch so that Get-Variable -Scope 1 is that function.
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
            if ($isBound) { $value = $BoundParameters[$name] }
            else { try { $value = (Get-Variable -Name $name -Scope 1 -ValueOnly) } catch { $value = $null } }
            $shown = Format-ParameterValue -Name $name -Value $value
            $suffix = ''
            if (-not $isBound) { $suffix = ' (Default)' }
            $lines.Add("${name}: ${shown}${suffix}")
        }
    } catch {
        $lines.Add("(could not build parameter report: $($_.Exception.Message))")
    }
    $Parameters = $lines -join "`n"
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

function Invoke-FunctionCatch {
    # Innermost failure produces the full report; outer functions pass the record through.
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$ErrorRecord, [Parameter(Mandatory = $true)]$Cmdlet, [Parameter(Mandatory = $true)]$Invocation, [Parameter(Mandatory = $true)]$BoundParameters, [string]$Report)
    if ($ErrorRecord.FullyQualifiedErrorId -like 'FunctionError*') { $Cmdlet.ThrowTerminatingError($ErrorRecord) }
    Write-Error -Message $Report -ErrorId 'FunctionError' -ErrorAction Stop
}

function Invoke-JiraGet {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Url)
    try {
        $resp  = Invoke-WebRequest -Uri $Url -Headers $Script:Headers -Method Get -UseBasicParsing
        $bytes = $resp.RawContentStream.ToArray()
        $text  = [System.Text.Encoding]::UTF8.GetString($bytes)
        if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }
        $obj = ConvertFrom-Json -InputObject $text
        return ,$obj
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-ListPayload {
    # Unwraps list envelopes ({data:[..]}, {values:[..]}, ...) to a plain array.
    [CmdletBinding()]
    param($Obj)
    try {
        if ($null -eq $Obj) { return ,@() }
        if ($Obj -is [System.Array]) { return ,$Obj }
        foreach ($k in 'data', 'items', 'values', 'rules', 'results') {
            $p = $Obj.PSObject.Properties[$k]
            if ($null -ne $p -and $p.Value -is [System.Array]) { return ,$p.Value }
        }
        return ,@($Obj)
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-Prop {
    [CmdletBinding()]
    param($Obj, [string]$Name)
    try {
        if ($null -eq $Obj) { return $null }
        $p = $Obj.PSObject.Properties[$Name]
        if ($null -eq $p) { return $null }
        return $p.Value
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Add-ErrorText {
    # Walks an arbitrary JSON object and collects anything that looks like an error/message.
    [CmdletBinding()]
    param($Node, $Acc, [int]$Depth = 0)
    try {
        if ($null -eq $Node -or $Depth -gt 12) { return }
        if ($Node -is [string]) { if ($Node.Trim()) { $Acc.Add($Node.Trim()) }; return }
        if ($Node -is [System.Array]) { foreach ($n in $Node) { Add-ErrorText -Node $n -Acc $Acc -Depth ($Depth + 1) }; return }
        if ($Node -is [System.Management.Automation.PSCustomObject]) {
            foreach ($p in $Node.PSObject.Properties) {
                if ($Script:ErrorKeyRegex.IsMatch($p.Name)) {
                    Add-ErrorText -Node $p.Value -Acc $Acc -Depth ($Depth + 1)
                } elseif ($p.Value -isnot [string]) {
                    Add-ErrorText -Node $p.Value -Acc $Acc -Depth ($Depth + 1)
                }
            }
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function ConvertFrom-EpochMs {
    [CmdletBinding()]
    param($Ms)
    try {
        if ($null -eq $Ms -or [string]$Ms -eq '' -or [string]$Ms -eq '0') { return '' }
        $ms64 = [long]$Ms
        return ([DateTimeOffset]::FromUnixTimeMilliseconds($ms64)).ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss')
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-Rules {
    # ruleId -> { Id, Name, State }
    [CmdletBinding()]
    param()
    try {
        $rules = @{}
        $offset = 0; $limit = 100
        do {
            $page  = Invoke-JiraGet -Url "$Script:Api/rule?limit=$limit&offset=$offset"
            $batch = @(Get-ListPayload -Obj $page)
            foreach ($r in $batch) {
                $id = [string](Get-Prop -Obj $r -Name 'id')
                if (-not $id) { continue }
                $rules[$id] = [pscustomobject]@{
                    Id    = $id
                    Name  = [string](Get-Prop -Obj $r -Name 'name')
                    State = [string](Get-Prop -Obj $r -Name 'state')
                }
            }
            $offset += $limit
        } while ($batch.Count -ge $limit)
        return $rules
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-LastRuns {
    # Scans the global audit log (newest first). Returns ruleId -> first (= latest) audit item.
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][hashtable]$Rules, [int]$Limit = 100, [int]$MaxEntries = 20000)
    try {
        $lastRun   = @{}
        $scanned   = 0
        $offset    = 0
        $remaining = [int]$Rules.Count
        do {
            $page  = Invoke-JiraGet -Url "$Script:Api/auditlog?limit=$Limit&offset=$offset"
            $batch = @(Get-ListPayload -Obj $page)
            foreach ($item in $batch) {
                $scanned++
                $cat = [string](Get-Prop -Obj $item -Name 'category')
                if ($cat -eq 'CONFIG_CHANGE') { continue }         # rule edit, not an execution
                $rid = [string](Get-Prop -Obj $item -Name 'ruleId')
                if (-not $rid -or $lastRun.ContainsKey($rid)) { continue }
                $lastRun[$rid] = $item
                if ($Rules.ContainsKey($rid)) { $remaining-- }
            }
            $offset += $Limit
            Write-Host ("  scanned {0} entries, {1} rules still without a run" -f $scanned, $remaining)
        } while ($batch.Count -ge $Limit -and $remaining -gt 0 -and $scanned -lt $MaxEntries)
        return $lastRun
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Get-ErrorMessageForItem {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Item)
    try {
        $acc = New-Object 'System.Collections.Generic.List[string]'
        $id  = [string](Get-Prop -Obj $Item -Name 'id')
        try {
            $detail = Invoke-JiraGet -Url "$Script:Api/auditlog/item/$id"
            Add-ErrorText -Node $detail -Acc $acc
        } catch {
            $acc.Add("(could not load audit item ${id}: $($_.Exception.Message))")
        }
        if ($acc.Count -eq 0) { Add-ErrorText -Node $Item -Acc $acc }
        return (@($acc | Select-Object -Unique) -join "`n")
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function New-ResultRow {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Rule, $Item)
    try {
        $status = 'NoExecutionFound'; $time = ''; $cat = ''; $errText = ''
        if ($null -ne $Item) {
            $cat = [string](Get-Prop -Obj $Item -Name 'category')
            if ($cat -eq 'SUCCESS')                         { $status = 'Success' }
            elseif ($cat -eq 'NO_ACTIONS_PERFORMED')        { $status = 'Success (no actions performed)' }
            elseif ($Script:FailedCategories -contains $cat) { $status = 'Failed' }
            else                                            { $status = $cat }
            $time = ConvertFrom-EpochMs -Ms (Get-Prop -Obj $Item -Name 'startTime')
            if ($status -eq 'Failed') { $errText = Get-ErrorMessageForItem -Item $Item }
        }
        return [pscustomobject]@{
            'Automation Name'       = $Rule.Name
            'Rule ID'               = $Rule.Id
            'Rule State'            = $Rule.State
            'Last Execution Status' = $status
            'Last Execution Time'   = $time
            'Raw Category'          = $cat
            'Error Message'         = $errText
        }
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

function Export-Rows {
    # UTF-8 with BOM so Excel shows umlauts correctly.
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Rows, [Parameter(Mandatory = $true)][string]$Path, [string]$CsvDelimiter = ',')
    try {
        $lines = @($Rows | ConvertTo-Csv -NoTypeInformation -Delimiter $CsvDelimiter)
        [System.IO.File]::WriteAllLines($Path, [string[]]$lines, (New-Object System.Text.UTF8Encoding $true))
    } catch {
        Invoke-FunctionCatch -ErrorRecord $_ -Cmdlet $PSCmdlet -Invocation $MyInvocation -BoundParameters $PSBoundParameters -Report (Format-FunctionError -ErrorRecord $_ -Invocation $MyInvocation -BoundParameters $PSBoundParameters)
    }
}

# ============================================================================
#  3. BUSINESS LOGIC
# ============================================================================

try {
    if (-not $Cookie -and (-not $Email -or -not $ApiToken)) { throw 'Provide -Email and -ApiToken, or -Cookie.' }

    $Site = $Site -replace '^https?://', '' -replace '/$', ''
    $baseUrl = "https://$Site"

    $Script:Headers = @{ 'Accept' = 'application/json'; 'X-Atlassian-Token' = 'no-check' }
    if ($Cookie) {
        $Script:Headers['Cookie'] = $Cookie
    } else {
        $pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Email}:${ApiToken}"))
        $Script:Headers['Authorization'] = "Basic $pair"
    }

    Write-Host "Resolving cloudId for $Site ..."
    $tenant  = Invoke-JiraGet -Url "$baseUrl/_edge/tenant_info"
    $cloudId = [string](Get-Prop -Obj $tenant -Name 'cloudId')
    if (-not $cloudId) { throw 'Could not read cloudId from /_edge/tenant_info' }
    $Script:Api = "$baseUrl/gateway/api/automation/internal-api/jira/$cloudId/pro/rest/GLOBAL"

    Write-Host 'Loading automation rules ...'
    $rules = Get-Rules
    Write-Host ("  {0} rules found." -f $rules.Count)
    if ($rules.Count -eq 0) { throw 'No rules returned - check permissions / auth (try -Cookie).' }

    Write-Host 'Scanning audit log ...'
    $lastRun = Get-LastRuns -Rules $rules -Limit $PageSize -MaxEntries $MaxLogEntries

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($rule in @($rules.Values | Sort-Object -Property Name)) {
        $item = $null
        if ($lastRun.ContainsKey($rule.Id)) { $item = $lastRun[$rule.Id] }
        $rows.Add((New-ResultRow -Rule $rule -Item $item))
    }

    if (-not $OutputPath) { $OutputPath = ".\JiraCloudAutomationLastRun_{0:yyyyMMdd_HHmmss}.csv" -f (Get-Date) }
    if (-not [System.IO.Path]::IsPathRooted($OutputPath)) { $OutputPath = Join-Path (Get-Location).Path $OutputPath }
    Export-Rows -Rows $rows -Path $OutputPath -CsvDelimiter $Delimiter

    $failedCount = @($rows | Where-Object { $_.'Last Execution Status' -eq 'Failed' }).Count
    $noRunCount  = @($rows | Where-Object { $_.'Last Execution Status' -eq 'NoExecutionFound' }).Count
    Write-Host ("Done. {0} rules, {1} failed, {2} without execution. -> {3}" -f $rows.Count, $failedCount, $noRunCount, $OutputPath)
}
catch {
    $ErrorMessage = "$($_.Exception.Message)`n$($_ | Format-List | Out-String)`nError Trace:`n$($_.ScriptStackTrace)"
    Write-Error "$ErrorMessage" -ErrorAction Continue
    Exit 1
}
