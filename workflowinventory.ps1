<#
.SYNOPSIS
  Jira workflow inventory for DC -> Cloud migration comparison.

.DESCRIPTION
  Two modes:
    -Mode Cloud : full inventory of the cloud sandbox via REST v3 (run from ANY PC
                  with internet access; output files can be uploaded to the chat).
                  Lists every workflow, every transition, and every transition RULE
                  (conditions/validators/post functions), including app-provided
                  (connect) rules and whether SR rules are present/disabled.
    -Mode DC    : basic workflow list via REST v2 (names, steps, default flag) —
                  a fallback if the ScriptRunner console script cannot be used.
                  For deep DC inspection prefer dc-workflow-inspector.groovy.

.EXAMPLE
  # Cloud sandbox (API token from https://id.atlassian.com/manage-profile/security/api-tokens)
  .\workflow-inventory.ps1 -Mode Cloud -BaseUrl "https://yoursite.atlassian.net" `
      -User "you@example.com" -Token "<api-token>" -OutDir .\out

.EXAMPLE
  # DC test instance (use a Personal Access Token, Bearer auth)
  .\workflow-inventory.ps1 -Mode DC -BaseUrl "https://jira.example.local" `
      -Pat "<personal-access-token>" -OutDir .\out
#>
param(
  [Parameter(Mandatory=$true)][ValidateSet('Cloud','DC')][string]$Mode,
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [string]$User,
  [string]$Token,
  [string]$Pat,
  [string]$OutDir = "."
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$Headers = @{ Accept = 'application/json' }
if ($Mode -eq 'Cloud') {
  if (-not $User -or -not $Token) { throw "Cloud mode needs -User (email) and -Token (API token)." }
  $pair  = "{0}:{1}" -f $User, $Token
  $Headers.Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
} else {
  if ($Pat) { $Headers.Authorization = "Bearer $Pat" }
  elseif ($User -and $Token) {
    $pair  = "{0}:{1}" -f $User, $Token
    $Headers.Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
  } else { throw "DC mode needs -Pat, or -User and -Token (password)." }
}

function Invoke-Jira([string]$Path) {
  Invoke-RestMethod -Uri ($BaseUrl + $Path) -Headers $Headers -Method Get
}

if ($Mode -eq 'DC') {
  # ---- DC: basic inventory ------------------------------------------------
  $wfs = Invoke-Jira '/rest/api/2/workflow'
  $rows = $wfs | ForEach-Object {
    [pscustomobject]@{
      Name        = $_.name
      Description = $_.description
      Steps       = $_.steps
      Default     = $_.default
      LastMod     = $_.lastModifiedDate
      LastModUser = $_.lastModifiedUser
    }
  }
  $rows | Export-Csv -Path (Join-Path $OutDir 'dc_workflows.csv') -NoTypeInformation -Encoding UTF8
  $rows | Format-Table -AutoSize
  Write-Host "`nSaved: dc_workflows.csv ($($rows.Count) workflows)."
  Write-Host "NOTE: DC REST cannot expose transition rules - run dc-workflow-inspector.groovy in the ScriptRunner console for the deep scan."
  return
}

# ---- Cloud: full inventory with transition rules --------------------------
$all = @()
$startAt = 0
do {
  $page = Invoke-Jira ("/rest/api/3/workflow/search?startAt=$startAt&maxResults=50&expand=transitions,transitions.rules,statuses,default,schemes,projects")
  $all += $page.values
  $startAt += $page.maxResults
} while ($startAt -lt $page.total)

# raw JSON dump (best artifact to upload to the chat for analysis)
$all | ConvertTo-Json -Depth 20 | Out-File (Join-Path $OutDir 'cloud_workflows_full.json') -Encoding UTF8

# flat rule table
$ruleRows = foreach ($wf in $all) {
  $wfName = $wf.id.name
  foreach ($t in $wf.transitions) {
    $ruleSets = @(
      @{ Kind='COND'; Items = $t.rules.conditionsTree; Flat = $t.rules.conditions },
      @{ Kind='VAL';  Items = $null; Flat = $t.rules.validators },
      @{ Kind='PF';   Items = $null; Flat = $t.rules.postFunctions }
    )
    foreach ($rs in $ruleSets) {
      foreach ($r in @($rs.Flat)) {
        if ($null -eq $r) { continue }
        $cfg = if ($r.configuration) { ($r.configuration | ConvertTo-Json -Depth 10 -Compress) } else { '' }
        [pscustomobject]@{
          Workflow   = $wfName
          Transition = $t.name
          TransId    = $t.id
          Kind       = $rs.Kind
          Type       = $r.type
          IsApp      = ($r.type -like 'connect:*' -or $r.type -like 'forge:*')
          IsSR       = ($cfg -match 'onresolve|scriptrunner' -or $r.type -match 'onresolve|scriptrunner')
          Disabled   = ($cfg -match '"disabled"\s*:\s*true')
          ConfigLen  = $cfg.Length
          Config     = if ($cfg.Length -gt 500) { $cfg.Substring(0,500) + '...' } else { $cfg }
        }
      }
    }
  }
}
$ruleRows | Export-Csv -Path (Join-Path $OutDir 'cloud_workflow_rules.csv') -NoTypeInformation -Encoding UTF8

# workflow-level summary
$wfRows = foreach ($wf in $all) {
  $rules = @($ruleRows | Where-Object Workflow -eq $wf.id.name)
  [pscustomobject]@{
    Name        = $wf.id.name
    Transitions = @($wf.transitions).Count
    Statuses    = @($wf.statuses).Count
    Rules       = $rules.Count
    AppRules    = @($rules | Where-Object IsApp).Count
    SRRules     = @($rules | Where-Object IsSR).Count
    Disabled    = @($rules | Where-Object Disabled).Count
    IsDefault   = $wf.isDefault
    Projects    = (@($wf.projects) | ForEach-Object { $_.key }) -join ';'
  }
}
$wfRows | Export-Csv -Path (Join-Path $OutDir 'cloud_workflows.csv') -NoTypeInformation -Encoding UTF8
$wfRows | Format-Table -AutoSize

Write-Host "`nSaved to ${OutDir}:"
Write-Host "  cloud_workflows_full.json  (upload this to the chat if possible - richest data)"
Write-Host "  cloud_workflow_rules.csv   (one line per condition/validator/post function)"
Write-Host "  cloud_workflows.csv        (per-workflow summary for diff against DC section A)"
