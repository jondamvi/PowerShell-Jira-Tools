/*
 * DC WORKFLOW INSPECTOR v2 (static-type-checker friendly)
 * -------------------------------------------------------
 * Run in: ScriptRunner Script Console on the Jira DC TEST instance.
 * Read-only: makes no changes. Output is compact/ASCII so it can be
 * re-typed or photographed.
 *
 * Output sections:
 *   A  Workflow inventory (active/inactive, drafts, projects, function counts per app)
 *   B  One line per app-provided workflow function (SR/JSU/JMWE/JWT/other)
 *   C  Token rollup: cloud-unsupported classes/objects found in SR scripts
 *   D  Summary + migration risk lists
 *
 * Retype priority if output is long: D first, then C, then B lines with sev=HARD.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.util.JiraHome
import com.atlassian.jira.project.Project
import com.atlassian.jira.workflow.JiraWorkflow
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ConditionDescriptor
import com.opensymphony.workflow.loader.ConditionsDescriptor
import com.opensymphony.workflow.loader.FunctionDescriptor
import com.opensymphony.workflow.loader.RestrictionDescriptor
import com.opensymphony.workflow.loader.ResultDescriptor
import com.opensymphony.workflow.loader.StepDescriptor
import com.opensymphony.workflow.loader.ValidatorDescriptor
import com.opensymphony.workflow.loader.WorkflowDescriptor
import groovy.json.JsonSlurper

// ---------------------------------------------------------------- config ---
final int MAX_TOKENS_PER_LINE = 6        // tokens shown per B line
final boolean SHOW_BUILTIN_IN_B = false  // true = also list built-in Jira functions in B

// regex -> [tokenName, severity, cloud hint]
// severity: HARD = no cloud equivalent / redesign; MED = rewrite (HAPI/REST); EASY = trivial
Map<String, List<String>> TOKEN_MAP = [
  'groovy\\.sql|java\\.sql|DriverManager|OfBizDelegator|DelegatorInterface' :
      ['SQL-DB',            'HARD', 'No DB access on cloud. Use REST/entity properties/external service'],
  'new\\s+File\\(|java\\.io\\.File|FileInputStream|FileWriter|Files\\.' :
      ['Filesystem',        'HARD', 'No filesystem on cloud. Use attachments/entity props/external storage'],
  'setLoggedInUser|JiraAuthenticationContext|SwitchUser|runAsUser' :
      ['Impersonation',     'HARD', 'Cannot impersonate on cloud (OAuth). Redesign as app/actor-user action'],
  'dispatchEvent|EventPublisher|IssueEventBundle|IssueEventManager|EventDispatchOption|FiresEvent' :
      ['FireEvents',        'HARD', 'Cannot fire arbitrary Jira events on cloud. Use Automation triggers/webhooks'],
  'ldap|LDAP|ActiveDirectory|CrowdService' :
      ['LDAP-Crowd',        'HARD', 'No directory access. Use org REST APIs / user props'],
  'SMTPMailServer|MailQueue|javax\\.mail|MailServerManager' :
      ['DirectMail',        'HARD', 'No mail server access. Use Automation "Send email" or external mail API'],
  'ServiceDeskManager|SlaInformation|servicedesk' :
      ['JSM-JavaAPI',       'HARD', 'JSM Java API absent. Use JSM cloud REST (limited SLA API)'],
  '@Grab|GroovyClassLoader' :
      ['ExternalJars',      'HARD', 'No external libs/classloader on cloud'],
  'ComponentAccessor|ComponentManager' :
      ['ComponentAccessor', 'MED',  'Java API entrypoint. Rewrite with SR Cloud HAPI (Issues.*, Users.*) or REST'],
  'MutableIssue|issue\\.set[A-Z]|\\.store\\(\\)' :
      ['MutableIssue',      'MED',  'In-transition field writes differ. Cloud: HAPI issue.update{} or REST PUT'],
  'IssueManager|IssueService|IssueFactory' :
      ['IssueManager',      'MED',  'HAPI Issues.getByKey/create/update or REST /issue'],
  'CustomFieldManager|getCustomFieldObject|customfield_' :
      ['CustomFields',      'MED',  'Cloud uses field IDs via REST; check field type support on cloud'],
  'OptionsManager|FieldConfig|CascadingSelect' :
      ['FieldOptions',      'MED',  'Option manipulation via REST /field/{id}/context/option'],
  'CommentManager' :
      ['Comments',          'EASY', 'HAPI issue.addComment or REST'],
  'UserManager|UserUtil|GroupManager|getGroupsForUser|UserSearchService' :
      ['Users-Groups',      'MED',  'Cloud users are accountIds (GDPR): REST user/group search'],
  'WatcherManager|VoteManager' :
      ['Watchers',          'EASY', 'REST watchers/votes endpoints'],
  'LabelManager' :
      ['Labels',            'EASY', 'issue.update { setLabels } / REST'],
  'SearchService|JqlQueryParser|PagerFilter|SearchProvider' :
      ['JQL-Search',        'MED',  'REST /search (paginated, async)'],
  'WorkflowTransitionUtil|WorkflowManager|transitionIssue' :
      ['Transitions',       'MED',  'HAPI issue.transition / REST transitions; or Automation'],
  'SubTaskManager|createSubTask' :
      ['SubTasks',          'MED',  'Automation "Create sub-task" or HAPI create'],
  'IssueLinkManager|RemoteIssueLink' :
      ['IssueLinks',        'MED',  'REST /issueLink; Automation branch on linked issues'],
  'VersionManager|ProjectComponentManager|ProjectManager' :
      ['ProjectData',       'MED',  'REST project/version/component endpoints'],
  'AttachmentManager' :
      ['Attachments',       'MED',  'REST attachments (multipart)'],
  'transientVars' :
      ['transientVars',     'MED',  'Different binding on cloud; limited transition context'],
  'HttpURLConnection|HttpClient|URLConnection|Unirest|RESTClient|HttpBuilder' :
      ['OutboundHTTP',      'MED',  'Allowed on cloud but async + 240s cap; check egress/auth'],
  'Thread\\.|Executors|sleep\\(' :
      ['Threads',           'MED',  'Async model on cloud, 240s max; no long sleeps'],
  'getLinkedIssues|getParentObject|getSubTaskObjects' :
      ['IssueNav',          'EASY', 'HAPI navigation or REST fields=parent,subtasks,issuelinks'],
] as Map<String, List<String>>

Map<String, Integer> SEV_RANK = [HARD: 3, MED: 2, EASY: 1] as Map<String, Integer>

// --------------------------------------------------------------- helpers ---
StringBuilder out = new StringBuilder()
def wfm = ComponentAccessor.workflowManager
def wsm = ComponentAccessor.workflowSchemeManager
def pm  = ComponentAccessor.projectManager

// map workflowName -> project keys using it
Map<String, List<String>> wfProjects = [:]
try {
  pm.projectObjects.each { Project p ->
    try {
      def scheme = wsm.getWorkflowSchemeObj(p)
      Set<String> names = [] as Set<String>
      if (scheme != null) {
        scheme.mappings.values().each { String n -> names << n }
        if (scheme.configuredDefaultWorkflow) names << scheme.configuredDefaultWorkflow
      } else { names << 'jira' }
      names.each { String n ->
        List<String> lst = wfProjects.get(n)
        if (lst == null) { lst = []; wfProjects.put(n, lst) }
        lst << p.key
      }
    } catch (Exception e) { /* scheme API differences - ignore project */ }
  }
} catch (Exception e) { out.append('WARN project mapping failed: ').append(e.message).append('\n') }

Set<String> activeNames = [] as Set<String>
wfm.activeWorkflows.each { JiraWorkflow w -> activeNames << w.name }

// provider detection from descriptor args/type
Closure<String> providerOf = { Map args, String type ->
  String mk = String.valueOf(args?.get('full.module.key') ?: '')
  String cn = String.valueOf(args?.get('class.name') ?: '')
  String s  = (mk + ' ' + cn + ' ' + (type ?: '')).toLowerCase()
  if (s.contains('onresolve') || s.contains('groovyrunner'))  return 'SR'
  if (s.contains('jsu') || s.contains('beecom'))              return 'JSU'
  if (s.contains('jmwe') || s.contains('innovalog'))          return 'JMWE'
  if (s.contains('jwt') || s.contains('decadis'))             return 'JWT'
  if (mk.length() > 0) return 'APP:' + mk.replaceFirst('^com\\.', '').take(35)
  return 'BUILTIN'
}

// extract SR script (inline code or file path) from args -> map with 'code'/'path'
Closure<Map<String, String>> extractScript = { Map args ->
  String code = null
  String path = null
  Object fs = args?.get('FIELD_SCRIPT_FILE_OR_SCRIPT')
  if (fs instanceof String && fs.trim().length() > 0) {
    try {
      Object parsed = new JsonSlurper().parseText(fs)
      if (parsed instanceof Map) {
        Object c  = parsed.get('script')
        Object sp = parsed.get('scriptPath')
        if (c != null)  code = String.valueOf(c)
        if (sp != null) path = String.valueOf(sp)
      }
    } catch (Exception e) { code = fs }
  }
  Object rawScript = args?.get('script')
  Object rawPath   = args?.get('scriptPath')
  if (code == null && rawScript instanceof String && rawScript.trim().length() > 0) code = rawScript
  if (path == null && rawPath instanceof String && rawPath.trim().length() > 0)     path = rawPath
  // scripted conditions store code in FIELD_CONDITION; extra scripts too
  ['FIELD_CONDITION', 'FIELD_ADDITIONAL_SCRIPT', 'FIELD_JQL_QUERY'].each { String k ->
    Object v = args?.get(k)
    if (v instanceof String && v.trim().length() > 0) code = (code != null ? code + '\n' + v : v)
  }
  Map<String, String> res = [:]
  if (code != null) res.put('code', code)
  if (path != null) res.put('path', path)
  return res
}

// resolve file-based scripts from known script roots
List<String> scriptRoots = []
try {
  String sp = System.getProperty('plugin.script.roots')
  if (sp != null) sp.split(',').each { String r -> if (r.trim().length() > 0) scriptRoots << r.trim() }
} catch (Exception e) {}
try {
  JiraHome jh = ComponentAccessor.getComponent(JiraHome)
  scriptRoots << (jh.home.absolutePath + File.separator + 'scripts')
} catch (Exception e) {}

Closure<String> readScriptFile = { String p ->
  if (p == null || p.length() == 0) return null
  for (String r : scriptRoots) {
    try {
      File f = new File(r, p)
      if (f.exists()) return f.getText('UTF-8')
    } catch (Exception e) {}
  }
  return null
}

// token scan -> list of maps with keys: name, count, sev, hint
Map<String, Map<String, Object>> tokenRollup = [:]
Closure<List<Map<String, Object>>> scanTokens = { String code ->
  List<Map<String, Object>> found = []
  if (code == null || code.length() == 0) return found
  TOKEN_MAP.each { String pat, List<String> meta ->
    int c = (code =~ pat).count
    if (c > 0) {
      Map<String, Object> m = [:]
      m.put('name', meta.get(0)); m.put('count', c)
      m.put('sev',  meta.get(1)); m.put('hint',  meta.get(2))
      found << m
    }
  }
  return found
}

// collect all actions of a workflow descriptor (initial, global, common, step)
Closure<Set<ActionDescriptor>> actionsOf = { WorkflowDescriptor wd ->
  Set<ActionDescriptor> acts = new LinkedHashSet<ActionDescriptor>()
  Closure addFrom = { Collection c ->
    c?.each { Object o -> if (o instanceof ActionDescriptor) acts << o }
  }
  try { addFrom(wd.initialActions) } catch (Exception e) {}
  try { addFrom(wd.globalActions) } catch (Exception e) {}
  try { addFrom(wd.commonActions?.values()) } catch (Exception e) {}
  try {
    wd.steps?.each { Object st -> if (st instanceof StepDescriptor) addFrom(st.actions) }
  } catch (Exception e) {}
  return acts
}

// flatten nested condition tree (iterative, no recursion)
Closure<List<ConditionDescriptor>> collectConds = { RestrictionDescriptor rd ->
  List<ConditionDescriptor> res = []
  List<Object> stack = []
  if (rd != null && rd.conditionsDescriptor != null) stack << rd.conditionsDescriptor
  while (!stack.isEmpty()) {
    Object cur = stack.remove(stack.size() - 1)
    if (cur instanceof ConditionsDescriptor) {
      cur.conditions?.each { Object c -> stack << c }
    } else if (cur instanceof ConditionDescriptor) {
      res << cur
    }
  }
  return res
}

Closure<String> shortCanned = { Map args ->
  String cs = String.valueOf(args?.get('canned-script') ?: args?.get('class.name') ?: '')
  if (cs.length() == 0 || cs == 'null') return '-'
  List<String> parts = cs.tokenize('.')
  return parts.isEmpty() ? '-' : parts.last()
}

// ------------------------------------------------------------------ main ---
List<String> bLines = []
List<String> aLines = []
int wfIdx = 0
int totalSR = 0
int srInline = 0
int srFile = 0
int srFileMissing = 0
Map<String, Integer> sevCount = [EASY: 0, MED: 0, HARD: 0] as Map<String, Integer>
List<String> hardRefs = []
Map<String, Integer> blankRiskCanned = [:]   // SR canned conds/vals -> blank on cloud
List<String> inactiveList = []
List<String> draftList = []

List<JiraWorkflow> workflows = new ArrayList<JiraWorkflow>(wfm.workflows)
workflows.sort { JiraWorkflow w -> w.name.toLowerCase() }

workflows.each { JiraWorkflow wf ->
  wfIdx++
  String id = String.format('A%02d', wfIdx)
  try {
    WorkflowDescriptor wd = wf.descriptor
    Set<ActionDescriptor> acts = actionsOf(wd)
    boolean isActive = activeNames.contains(wf.name)
    boolean hasDraft = false
    try { hasDraft = wfm.getDraftWorkflow(wf.name) != null } catch (Exception e) {}
    if (!isActive) inactiveList << wf.name
    if (hasDraft) draftList << wf.name
    Map<String, Integer> provCount = [:]

    acts.each { ActionDescriptor a ->
      // gather function/validator/condition entries as maps: kind, args, type
      List<Map<String, Object>> items = []
      Closure addItem = { String kind, Map fargs, String ftype ->
        Map<String, Object> entry = [:]
        entry.put('kind', kind)
        entry.put('args', fargs != null ? fargs : [:])
        entry.put('type', ftype != null ? ftype : '')
        items << entry
      }
      try {
        a.unconditionalResult?.postFunctions?.each { Object o ->
          if (o instanceof FunctionDescriptor) addItem('PF', o.args, o.type)
        }
        a.conditionalResults?.each { Object cr ->
          if (cr instanceof ResultDescriptor) {
            cr.postFunctions?.each { Object o ->
              if (o instanceof FunctionDescriptor) addItem('PF', o.args, o.type)
            }
          }
        }
        a.validators?.each { Object o ->
          if (o instanceof ValidatorDescriptor) addItem('VAL', o.args, o.type)
        }
        collectConds(a.restriction).each { ConditionDescriptor c ->
          addItem('COND', c.args, c.type)
        }
      } catch (Exception e) { return }

      items.each { Map<String, Object> item ->
        String kind = String.valueOf(item.get('kind'))
        Map args    = item.get('args') as Map
        String type = String.valueOf(item.get('type'))
        String prov = providerOf(args, type)
        provCount.put(prov, (provCount.get(prov) ?: 0) + 1)
        if (prov == 'BUILTIN' && !SHOW_BUILTIN_IN_B) return

        String canned = shortCanned(args)
        String detail = '-'
        String lenInfo = ''
        String hashInfo = ''
        String sevInfo = ''
        String tokStr = ''

        if (prov == 'SR') {
          totalSR++
          Map<String, String> ex = extractScript(args)
          String code = ex.get('code')
          String path = ex.get('path')
          if (path != null) {
            String fileCode = readScriptFile(path)
            if (fileCode != null) {
              code = ((code != null ? code + '\n' : '') + fileCode).trim()
              srFile++
              detail = 'file:' + path
            } else {
              srFileMissing++
              detail = 'FILE?:' + path
            }
          } else if (code != null) {
            detail = 'inline'
            srInline++
          } else {
            detail = 'nocode'
          }

          boolean isCustomScript = canned.toLowerCase().contains('customscript')
          if (!isCustomScript && (kind == 'COND' || kind == 'VAL')) {
            String bk = canned + '(' + kind + ')'
            blankRiskCanned.put(bk, (blankRiskCanned.get(bk) ?: 0) + 1)
          }

          if (code != null && code.length() > 0) {
            lenInfo = 'len=' + code.length()
            hashInfo = 'h=' + Integer.toHexString(code.trim().hashCode())
            List<Map<String, Object>> toks = scanTokens(code)
            String worst = 'EASY'
            toks.each { Map<String, Object> t ->
              String tName = String.valueOf(t.get('name'))
              String tSev  = String.valueOf(t.get('sev'))
              int tCount   = t.get('count') as int
              Map<String, Object> e = tokenRollup.get(tName)
              if (e == null) {
                e = [:] as Map<String, Object>
                e.put('hits', 0); e.put('scripts', 0); e.put('sev', 'EASY'); e.put('hint', '')
                tokenRollup.put(tName, e)
              }
              e.put('hits',    (e.get('hits') as int) + tCount)
              e.put('scripts', (e.get('scripts') as int) + 1)
              e.put('hint',    t.get('hint'))
              int rNew = SEV_RANK.get(tSev) ?: 0
              int rOld = SEV_RANK.get(String.valueOf(e.get('sev'))) ?: 0
              if (rNew > rOld) e.put('sev', tSev)
              int rWorst = SEV_RANK.get(worst) ?: 0
              if (rNew > rWorst) worst = tSev
            }
            // scripted conditions/validators always need a Jira-expression rewrite on cloud
            if ((kind == 'COND' || kind == 'VAL') && (SEV_RANK.get(worst) ?: 0) < 2) worst = 'MED'
            sevInfo = 'sev=' + worst
            sevCount.put(worst, (sevCount.get(worst) ?: 0) + 1)
            List<Map<String, Object>> sortedToks = new ArrayList<Map<String, Object>>(toks)
            sortedToks.sort { Map<String, Object> t -> -(t.get('count') as int) }
            List<String> topToks = []
            sortedToks.take(MAX_TOKENS_PER_LINE).each { Map<String, Object> t ->
              topToks << String.valueOf(t.get('name'))
            }
            tokStr = 'tok=' + (topToks.isEmpty() ? '-' : topToks.join(','))
            if (worst == 'HARD') hardRefs << (id + ' t' + a.id + ' ' + kind + ' ' + canned)
          }
        }

        List<String> extras = []
        [lenInfo, hashInfo, sevInfo, tokStr].each { String x -> if (x.length() > 0) extras << x }
        bLines << ('B ' + id + ' t' + a.id + ' "' + (a.name != null ? a.name : '?') + '" ' + kind + ' ' +
                   prov + ' ' + canned + ' ' + detail +
                   (extras.isEmpty() ? '' : ' ' + extras.join(' ')))
      }
    }

    List<Map.Entry<String, Integer>> provEntries =
        new ArrayList<Map.Entry<String, Integer>>(provCount.entrySet())
    provEntries.sort { Map.Entry<String, Integer> en -> -en.value }
    List<String> provParts = []
    provEntries.each { Map.Entry<String, Integer> en -> provParts << (en.key + ':' + en.value) }
    int prjCount = wfProjects.get(wf.name)?.size() ?: 0
    aLines << (id + ' | ' + wf.name + ' | ' + (isActive ? 'ACTIVE' : 'INACTIVE') +
               ' | draft=' + (hasDraft ? 'Y' : 'N') +
               ' | prj=' + prjCount +
               ' | trans=' + acts.size() + ' | ' +
               (provParts.isEmpty() ? 'none' : provParts.join(' ')))
  } catch (Exception e) {
    aLines << (id + ' | ' + wf.name + ' | ERROR: ' + e.message)
  }
}

// ---------------------------------------------------------------- output ---
out.append('==== DC WORKFLOW INSPECTOR (read-only) ====\n')
out.append('Retype priority: D first, then C, then B lines with sev=HARD. Photos of all sections are ideal.\n\n')

out.append('=== A. WORKFLOWS (id | name | status | draft | #projects | #transitions | functions by provider) ===\n')
aLines.each { String l -> out.append(l).append('\n') }

out.append('\n=== B. APP-PROVIDED WORKFLOW FUNCTIONS (B id transition kind provider canned source len hash sev tokens) ===\n')
bLines.each { String l -> out.append(l).append('\n') }

out.append('\n=== C. CLOUD-BLOCKER TOKEN ROLLUP (token | total hits | #scripts | severity | cloud replacement hint) ===\n')
List<Map.Entry<String, Map<String, Object>>> tokenEntries =
    new ArrayList<Map.Entry<String, Map<String, Object>>>(tokenRollup.entrySet())
tokenEntries.sort { Map.Entry<String, Map<String, Object>> en ->
  int sevRank = SEV_RANK.get(String.valueOf(en.value.get('sev'))) ?: 0
  int scripts = en.value.get('scripts') as int
  return -(sevRank * 1000 + scripts)
}
tokenEntries.each { Map.Entry<String, Map<String, Object>> en ->
  out.append('C ' + en.key.padRight(18) + ' | ' + en.value.get('hits') + ' | ' + en.value.get('scripts') +
             ' | ' + en.value.get('sev') + ' | ' + en.value.get('hint') + '\n')
}
if (tokenEntries.isEmpty()) out.append('C (no DC-only tokens found in SR scripts)\n')

out.append('\n=== D. SUMMARY ===\n')
out.append('D workflows=' + wfIdx + ' active=' + (wfIdx - inactiveList.size()) +
           ' inactive=' + inactiveList.size() + '\n')
out.append('D INACTIVE (JCMA will NOT migrate unless assigned to a migrated project scheme): ' +
           (inactiveList.isEmpty() ? '-' : inactiveList.join('; ')) + '\n')
out.append('D DRAFTS (not migrated; publish or discard before migration): ' +
           (draftList.isEmpty() ? '-' : draftList.join('; ')) + '\n')
out.append('D SR functions=' + totalSR + ' inline=' + srInline + ' file=' + srFile +
           ' fileNotResolved=' + srFileMissing + '\n')
out.append('D SR script severity: EASY=' + sevCount.get('EASY') + ' MED=' + sevCount.get('MED') +
           ' HARD=' + sevCount.get('HARD') + '\n')
out.append('D HARD (redesign needed, cannot be 1:1 rewritten): ' +
           (hardRefs.isEmpty() ? '-' : hardRefs.join('; ')) + '\n')
List<String> blankParts = []
blankRiskCanned.each { String k, Integer v -> blankParts << (k + ' x' + v) }
out.append('D SR built-in (canned) CONDITIONS/VALIDATORS -> arrive as BLANK unsupported rules on cloud, must be recreated: ' +
           (blankParts.isEmpty() ? '-' : blankParts.join(', ')) + '\n')
out.append('D Script roots checked for file scripts: ' +
           (scriptRoots.isEmpty() ? 'none found' : scriptRoots.join(' ; ')) + '\n')

return out.toString()
