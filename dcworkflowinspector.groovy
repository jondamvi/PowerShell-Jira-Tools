/*
 * DC WORKFLOW INSPECTOR v9 (HTML tables + decoded scripts + action XML)
 * --------------------------------------------
 * Run in: ScriptRunner Script Console on the Jira DC TEST instance.
 * Read-only: makes no changes.
 *
 * Output: 4 HTML tables rendered directly in the console result pane
 * (A workflows, B functions, C tokens, D summary). Select a table and
 * copy/paste into Excel - it splits into cells automatically.
 *
 * Table B is the working sheet: one row per app-provided workflow function,
 * with Severity, Tokens, SuggestedTarget and empty Decision/Notes columns.
 * Row colors: red = HARD/ERROR, orange = MED, green = EASY, grey = INACTIVE.
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
final boolean SHOW_BUILTIN_IN_B = false  // true = also list built-in Jira functions in table B
final int MAX_TOKENS_PER_CELL = 8        // tokens listed in the Tokens column

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

// HTML-escape one cell value (also strips line breaks/tabs)
Closure<String> esc = { Object o ->
  String s = o == null ? '' : String.valueOf(o)
  return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
          .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
}

// escape for code cells: line breaks become <br> with mso-data-placement:same-cell,
// which makes Excel keep the whole script inside ONE cell (in-cell Alt+Enter breaks)
Closure<String> escCode = { Object o ->
  String s = o == null ? '' : String.valueOf(o)
  s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
  s = s.replace('\r\n', '\n').replace('\r', '\n').replace('\t', '  ')
  return s.replace('\n', '<br style="mso-data-placement:same-cell;">')
}

// render one HTML table; markCol = column index used for row coloring (-1 = none);
// codeCols = column indexes rendered as monospace code with line breaks preserved
Closure<String> htmlTable = { String title, List<String> header, List<List<String>> rows,
                              int markCol, List<Integer> codeCols ->
  StringBuilder sb = new StringBuilder()
  sb.append('<h3 style="font-family:Arial,sans-serif;margin:18px 0 6px 0;color:#205081">')
    .append(esc(title)).append('</h3>')
  sb.append('<table style="border-collapse:collapse;font-family:Arial,sans-serif;font-size:12px">')
  sb.append('<tr>')
  header.each { String h ->
    sb.append('<th style="border:1px solid #888;background:#205081;color:#ffffff;')
      .append('padding:4px 8px;text-align:left">')
      .append(esc(h)).append('</th>')
  }
  sb.append('</tr>')
  int n = header.size()
  rows.each { List<String> r ->
    String bg = '#ffffff'
    if (markCol >= 0 && markCol < r.size()) {
      String mv = String.valueOf(r.get(markCol))
      if (mv == 'HARD' || mv == 'ERROR')  bg = '#ffd6d6'
      else if (mv == 'MED')               bg = '#ffe9c6'
      else if (mv == 'EASY')              bg = '#dcf0dc'
      else if (mv == 'INACTIVE')          bg = '#e8e8e8'
    }
    sb.append('<tr style="background:').append(bg).append('">')
    for (int i = 0; i < n; i++) {
      String v = i < r.size() ? String.valueOf(r.get(i)) : ''
      if (codeCols.contains(i)) {
        sb.append('<td style="border:1px solid #bbb;padding:3px 8px;vertical-align:top;')
          .append('font-family:monospace;font-size:11px;text-align:left">')
          .append(escCode(v)).append('</td>')
      } else {
        sb.append('<td style="border:1px solid #bbb;padding:3px 8px;vertical-align:top">')
          .append(esc(v)).append('</td>')
      }
    }
    sb.append('</tr>')
  }
  sb.append('</table>')
  return sb.toString()
}

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

// resolve the transition name as the UI displays it (German translation via
// jira.i18n.title meta); internal XML name is appended in [brackets] if different
def i18nHelper = ComponentAccessor.jiraAuthenticationContext.i18nHelper
Closure<String> actionDisplayName = { ActionDescriptor ad ->
  String nm = ad.name != null ? ad.name : '?'
  try {
    Object key = ad.metaAttributes?.get('jira.i18n.title')
    if (key != null) {
      String t = i18nHelper.getText(String.valueOf(key))
      if (t != null && t.length() > 0 && t != String.valueOf(key) && t != nm) {
        nm = t + ' [' + nm + ']'
      }
    }
  } catch (Exception e) {}
  return nm
}

// provider detection from descriptor args/type
Closure<String> providerOf = { Map args, String type ->
  String mk = String.valueOf(args?.get('full.module.key') ?: '')
  String cn = String.valueOf(args?.get('class.name') ?: '')
  String s  = (mk + ' ' + cn + ' ' + (type ?: '')).toLowerCase()
  if (s.contains('onresolve') || s.contains('groovyrunner'))  return 'SR'
  if (s.contains('jsu') || s.contains('beecom'))              return 'JSU'
  if (s.contains('jmwe') || s.contains('innovalog'))          return 'JMWE'
  if (s.contains('jwt') || s.contains('decadis'))             return 'JWT'
  if (s.contains('com.atlassian.jira'))                       return 'BUILTIN'
  if (mk.length() > 0) return 'APP:' + mk.replaceFirst('^com\\.', '').take(35)
  return 'BUILTIN'
}

// enable/disable flag stored in the function config (app-specific; '-' = no flag = enabled)
Closure<String> enabledStatus = { Map args ->
  String verdict = '-'
  args?.each { Object k, Object v ->
    String ks = String.valueOf(k).toLowerCase()
    String vs = String.valueOf(v).toLowerCase()
    if (ks.contains('disabled')) {
      verdict = (vs == 'true') ? 'DISABLED' : 'enabled'
    } else if (ks.contains('enabled')) {
      verdict = (vs == 'false') ? 'DISABLED' : 'enabled'
    }
  }
  if (verdict == '-') {
    String all = String.valueOf(args).toLowerCase()
    if (all.contains('"disabled":true') || all.contains('"enabled":false')) verdict = 'DISABLED?'
  }
  return verdict
}

// Newer ScriptRunner versions store workflow-function args base64-encoded with
// a `!` marker: raw arg "YCFg..." -> base64-decode -> "`!`<real content>". Decode those.
Closure<String> decodeMaybe = { String v ->
  if (v == null) return null
  String t = v.trim()
  if (t.length() < 8) return v
  if (!(t ==~ '[A-Za-z0-9+/=\\r\\n]+')) return v
  try {
    byte[] b = Base64.decoder.decode(t.replaceAll('\\s', ''))
    String d = new String(b, 'UTF-8')
    if (d.startsWith('`!`')) {
      d = d.substring(3)
      if (d.endsWith('`!`')) d = d.substring(0, d.length() - 3)
      return d
    }
  } catch (Exception e) {}
  return v
}

// extract SR script (inline code or file path) from args -> map with 'code'/'path'
Closure<Map<String, String>> extractScript = { Map args ->
  String code = null
  String path = null
  Object fs0 = args?.get('FIELD_SCRIPT_FILE_OR_SCRIPT')
  if (fs0 instanceof String && fs0.trim().length() > 0) {
    String fs = decodeMaybe(fs0)
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
  Object inline0 = args?.get('FIELD_INLINE_SCRIPT')
  if (code == null && inline0 instanceof String && inline0.trim().length() > 0) {
    code = decodeMaybe(inline0)
  }
  Object rawScript = args?.get('script')
  Object rawPath   = args?.get('scriptPath')
  if (code == null && rawScript instanceof String && rawScript.trim().length() > 0) code = decodeMaybe(rawScript)
  if (path == null && rawPath instanceof String && rawPath.trim().length() > 0)     path = decodeMaybe(rawPath)
  ['FIELD_CONDITION', 'FIELD_ADDITIONAL_SCRIPT', 'FIELD_JQL_QUERY'].each { String k ->
    Object v = args?.get(k)
    if (v instanceof String && v.trim().length() > 0) {
      String dv = decodeMaybe(v)
      code = (code != null ? code + '\n' + dv : dv)
    }
  }
  Map<String, String> res = [:]
  if (code != null) res.put('code', code)
  if (path != null) res.put('path', path)
  return res
}

// full <action id=".." name=".."> ... </action> XML of a transition;
// base64-encoded arg values are decoded inline and marked with [decoded]
Closure<String> actionXmlOf = { ActionDescriptor ad ->
  String xml = ''
  try {
    StringWriter sw = new StringWriter()
    PrintWriter pw = new PrintWriter(sw)
    ad.writeXML(pw, 0)
    pw.flush()
    xml = sw.toString()
  } catch (Exception e) { return 'XML unavailable: ' + e.message }
  try {
    def m = (xml =~ '[A-Za-z0-9+/=]{24,}')
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String orig = m.group()
      String d = decodeMaybe(orig)
      String rep = (d == orig) ? orig :
          ('[decoded] ' + d.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep))
    }
    m.appendTail(sb)
    xml = sb.toString()
  } catch (Exception e) {}
  return xml
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

// suggested cloud target for a function row
Closure<String> suggestTarget = { String prov, String kind, String canned, String sev ->
  if (prov == 'JSU' || prov == 'JMWE' || prov == 'JWT')
    return 'Vendor cloud app or Automation'
  if (prov == 'BUILTIN') return 'Native Jira function - JCMA migrates automatically (verify on cloud)'
  if (prov != 'SR') return 'Check app cloud availability'
  boolean isCustom = canned.toLowerCase().contains('customscript')
  if (kind == 'COND' || kind == 'VAL') {
    return isCustom ? 'Rewrite as Jira expression (SR Cloud)' :
                      'RECREATE: canned cond/val arrives blank on cloud - Jira expression or native rule'
  }
  if (sev == 'HARD') return 'Redesign: Automation / external service (capability not on cloud)'
  if (!isCustom)     return 'SR Cloud equivalent or Automation (canned function)'
  if (sev == 'EASY') return 'Automation if simple, else SR Cloud Groovy (HAPI)'
  return 'SR Cloud Groovy rewrite (HAPI/REST)'
}

// ------------------------------------------------------------------ main ---
List<List<String>> aRows = []
List<List<String>> bRows = []
int wfIdx = 0
int bIdx = 0
int totalSR = 0
int srInline = 0
int srFile = 0
int srFileMissing = 0
Map<String, Integer> sevCount = [EASY: 0, MED: 0, HARD: 0] as Map<String, Integer>
List<String> hardRefs = []
Map<String, Integer> blankRiskCanned = [:]
List<String> inactiveList = []
List<String> draftList = []

Map<String, Map<String, Object>> scriptGroups = [:]   // CodeHash -> occurrence info (table E)

// exclude draft copies from the main list (drafts are reported via the Draft column)
List<JiraWorkflow> workflows = []
wfm.workflows.each { JiraWorkflow w ->
  boolean isDraftCopy = false
  try { isDraftCopy = w.isDraftWorkflow() } catch (Exception e) {}
  if (!isDraftCopy) workflows << w
}
workflows.sort { JiraWorkflow w -> w.name.toLowerCase() }

workflows.each { JiraWorkflow wf ->
  wfIdx++
  String id = String.format('A%02d', wfIdx)
  try {
    WorkflowDescriptor wd = wf.descriptor
    Set<ActionDescriptor> acts = actionsOf(wd)

    // map action id -> where it hangs in THIS workflow (create/global/step names)
    Map<Integer, List<String>> actionOrigin = [:]
    Closure addOrigin = { int aid, String label ->
      List<String> l = actionOrigin.get(aid)
      if (l == null) { l = []; actionOrigin.put(aid, l) }
      if (!l.contains(label)) l << label
    }
    try {
      wd.initialActions?.each { Object o -> if (o instanceof ActionDescriptor) addOrigin(o.id, 'CREATE') }
    } catch (Exception e) {}
    try {
      wd.globalActions?.each { Object o -> if (o instanceof ActionDescriptor) addOrigin(o.id, 'GLOBAL') }
    } catch (Exception e) {}
    try {
      wd.steps?.each { Object stO ->
        if (stO instanceof StepDescriptor) {
          StepDescriptor st = (StepDescriptor) stO
          st.actions?.each { Object o ->
            if (o instanceof ActionDescriptor) addOrigin(o.id, 'Step: ' + String.valueOf(st.name))
          }
        }
      }
    } catch (Exception e) {}
    boolean isActive = false
    try { isActive = wf.isActive() } catch (Exception e) { isActive = activeNames.contains(wf.name) }
    boolean hasDraft = false
    try { hasDraft = wf.hasDraftWorkflow() } catch (Exception e) {}
    if (!isActive) inactiveList << wf.name
    if (hasDraft) draftList << wf.name
    Map<String, Integer> provCount = [:]

    acts.each { ActionDescriptor a ->
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

      String transDisabled = ''
      try {
        Object md = a.metaAttributes?.get('jira.disabled')
        if (String.valueOf(md) == 'true') transDisabled = 'TRANS-DISABLED'
      } catch (Exception e) {}
      String actionXmlText = actionXmlOf(a)

      items.each { Map<String, Object> item ->
        String kind = String.valueOf(item.get('kind'))
        Map args    = item.get('args') as Map
        String type = String.valueOf(item.get('type'))
        String prov = providerOf(args, type)
        provCount.put(prov, (provCount.get(prov) ?: 0) + 1)
        if (prov == 'BUILTIN' && !SHOW_BUILTIN_IN_B) return
        String enab = enabledStatus(args)
        if (transDisabled.length() > 0) enab = transDisabled

        String canned = shortCanned(args)
        String source = '-'
        String path = ''
        String lenS = ''
        String hashS = ''
        String sev = ''
        String tokS = ''
        String funcId = String.valueOf(args.get('FIELD_FUNCTION_ID') ?: '')
        String codeFull = ''

        if (prov == 'SR') {
          totalSR++
          Map<String, String> ex = extractScript(args)
          String code = ex.get('code')
          String exPath = ex.get('path')
          if (exPath != null) {
            path = exPath
            String fileCode = readScriptFile(exPath)
            if (fileCode != null) {
              code = ((code != null ? code + '\n' : '') + fileCode).trim()
              srFile++
              source = 'file'
            } else {
              srFileMissing++
              source = 'file-NOT-FOUND'
            }
          } else if (code != null) {
            source = 'inline'
            srInline++
          } else {
            source = 'nocode'
          }
          codeFull = (code != null ? code : '')

          boolean isCustomScript = canned.toLowerCase().contains('customscript')
          if (!isCustomScript && (kind == 'COND' || kind == 'VAL')) {
            String bk = canned + '(' + kind + ')'
            blankRiskCanned.put(bk, (blankRiskCanned.get(bk) ?: 0) + 1)
          }

          if (code != null && code.length() > 0) {
            lenS = String.valueOf(code.length())
            hashS = Integer.toHexString(code.trim().hashCode())
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
            if ((kind == 'COND' || kind == 'VAL') && (SEV_RANK.get(worst) ?: 0) < 2) worst = 'MED'
            sev = worst
            sevCount.put(worst, (sevCount.get(worst) ?: 0) + 1)
            List<Map<String, Object>> sortedToks = new ArrayList<Map<String, Object>>(toks)
            sortedToks.sort { Map<String, Object> t -> -(t.get('count') as int) }
            List<String> topToks = []
            sortedToks.take(MAX_TOKENS_PER_CELL).each { Map<String, Object> t ->
              topToks << (String.valueOf(t.get('name')) + '(' + String.valueOf(t.get('count')) + ')')
            }
            tokS = topToks.join(' ')
            if (worst == 'HARD') hardRefs << ('B' + String.format('%03d', bIdx + 1))
          }
        }

        bIdx++
        String bRef = 'B' + String.format('%03d', bIdx)
        String target = suggestTarget(prov, kind, canned, sev)
        List<String> originList = actionOrigin.get(a.id)
        String fromStep = originList == null ? '?' : originList.join(', ')
        bRows << ([bRef, id, wf.name, String.valueOf(a.id), actionDisplayName(a), fromStep,
                   kind, enab, prov, canned, source, path, lenS, hashS, funcId, sev, tokS,
                   target, '', '', codeFull, actionXmlText] as List<String>)

        // group identical SR scripts by hash for table E
        if (prov == 'SR' && hashS.length() > 0) {
          Map<String, Object> g = scriptGroups.get(hashS)
          if (g == null) {
            g = [:] as Map<String, Object>
            g.put('count', 0); g.put('refs', [] as List<String>)
            g.put('len', lenS); g.put('sev', sev); g.put('tok', tokS); g.put('canned', canned)
            g.put('code', codeFull)
            scriptGroups.put(hashS, g)
          }
          g.put('count', (g.get('count') as int) + 1)
          (g.get('refs') as List<String>) << bRef
        }
      }
    }

    int srN   = provCount.get('SR') ?: 0
    int jsuN  = provCount.get('JSU') ?: 0
    int jmweN = provCount.get('JMWE') ?: 0
    int jwtN  = provCount.get('JWT') ?: 0
    int biN   = provCount.get('BUILTIN') ?: 0
    int otherN = 0
    provCount.each { String k, Integer v -> if (k.startsWith('APP:')) otherN += v }
    List<String> prjKeys = wfProjects.get(wf.name) ?: ([] as List<String>)
    aRows << ([id, wf.name, (isActive ? 'ACTIVE' : 'INACTIVE'), (hasDraft ? 'Y' : 'N'),
               String.valueOf(prjKeys.size()), prjKeys.join(', '),
               String.valueOf(acts.size()), String.valueOf(srN), String.valueOf(jsuN),
               String.valueOf(jmweN), String.valueOf(jwtN), String.valueOf(otherN),
               String.valueOf(biN)] as List<String>)
  } catch (Exception e) {
    aRows << ([id, wf.name, 'ERROR', '', '', '', '', '', '', '', '', '',
               String.valueOf(e.message)] as List<String>)
  }
}

// ---------------------------------------------------------------- output ---
out.append('<div style="font-family:Arial,sans-serif">')
out.append('<h2 style="margin:0 0 4px 0;color:#205081">DC Workflow Inspector (read-only)</h2>')
out.append('<p style="font-size:12px;margin:0">Row colors: ')
out.append('<span style="background:#ffd6d6">&nbsp;HARD/ERROR&nbsp;</span> ')
out.append('<span style="background:#ffe9c6">&nbsp;MED&nbsp;</span> ')
out.append('<span style="background:#dcf0dc">&nbsp;EASY&nbsp;</span> ')
out.append('<span style="background:#e8e8e8">&nbsp;INACTIVE&nbsp;</span>')
out.append(' &mdash; select a table, copy, paste into Excel.</p>')

// legend renderer: small two-column table
Closure<String> legendFor = { String title, List<List<String>> defs ->
  return htmlTable(title, ['Column', 'Meaning'] as List<String>, defs, -1, [] as List<Integer>)
}

// ---- Table A ----
out.append(legendFor('Legend: Table A - Workflows', [
  ['ID', 'Workflow reference id, used as "WF id" in Table B'],
  ['Status', 'ACTIVE = assigned to a workflow scheme used by a project. INACTIVE = unused; JCMA will NOT migrate it'],
  ['Draft', 'Y = an unpublished draft exists for this workflow (drafts are not migrated; publish or discard)'],
  ['Proj Count / ProjectKeys', 'How many projects use this workflow, and their project keys'],
  ['Trans', 'Number of transitions (workflow actions), incl. create and global transitions'],
  ['SR', 'Count of ScriptRunner workflow functions in this workflow'],
  ['JSU', 'Count of functions from the JSU Automation Suite app'],
  ['JMWE', 'Count of functions from the Jira Misc Workflow Extensions app'],
  ['JWT', 'Count of functions from the Jira Workflow Toolbox app'],
  ['OtherApps', 'Functions from other third-party apps (cloud availability must be checked per app)'],
  ['Builtin', 'Native Jira functions - JCMA migrates these automatically'],
] as List<List<String>>))
// Status is column index 2 in table A
out.append(htmlTable('Table A - Workflows',
    ['ID', 'Workflow', 'Status', 'Draft', 'Proj Count', 'ProjectKeys', 'Trans',
     'SR', 'JSU', 'JMWE', 'JWT', 'OtherApps', 'Builtin'] as List<String>,
    aRows, 2, [] as List<Integer>))

// ---- Table B ----
out.append(legendFor('Legend: Table B - App workflow functions', [
  ['Ref', 'Unique row reference (B001...) for discussing individual functions'],
  ['WF id', 'Workflow reference - see ID in Table A'],
  ['TransId / Transition', 'Internal transition id and transition name as displayed in the UI (German translation where Jira uses i18n); the internal XML name follows in [brackets] when different'],
  ['FromStep', 'Where the transition hangs: CREATE = initial (issue creation), GLOBAL = available from every status, Step: <name> = outgoing transition of that step. Compare this against the workflow Steps view'],
  ['Kind', 'PF = post function, VAL = validator, COND = condition'],
  ['Enabled', 'Enable/disable flag stored in the function config, if the app supports one. "-" = no flag stored (function is effectively enabled). DISABLED = flagged off. TRANS-DISABLED = whole transition flagged'],
  ['Provider', 'App providing the function: SR = ScriptRunner, JSU/JMWE/JWT = workflow apps, BUILTIN = native Jira, APP:... = other app'],
  ['CannedScript', 'Function class. CustomScriptFunction/-Validator/-Condition = free Groovy; other names = SR built-in (canned) function'],
  ['Source', 'inline = Groovy stored inside the workflow; file = script file on the server (resolved); file-NOT-FOUND = referenced file missing from script roots; nocode = SR canned function configured only via UI fields, no custom Groovy'],
  ['CodeLen / CodeHash', 'Script size and fingerprint. Same hash = identical script reused on several transitions (rewrite once - see Table E)'],
  ['FunctionId', 'ScriptRunner internal function instance id (FIELD_FUNCTION_ID hex from the workflow XML) - stable unique identifier of this function'],
  ['Severity', 'Cloud rewrite effort: EASY = trivial, MED = rewrite against REST/HAPI, HARD = capability does not exist on cloud (redesign)'],
  ['Tokens', 'DC-only API classes found in the code, with occurrence count'],
  ['SuggestedTarget', 'Proposed cloud replacement; Decision/Notes are empty columns for your work in Excel'],
  ['Script', 'Full decoded ScriptRunner Groovy code (base64 decoded). Enable text wrap in Excel; copy cell content into a script editor to inspect'],
  ['ActionXML', 'Complete <action> XML of the transition with ALL its functions and configuration parameters; base64 arg values are decoded inline and marked [decoded]'],
] as List<List<String>>))
// Severity is column index 15 in table B; Script=20 and ActionXML=21 are code columns
out.append(htmlTable('Table B - App workflow functions (working sheet)',
    ['Ref', 'WF id', 'Workflow', 'TransId', 'Transition', 'FromStep', 'Kind', 'Enabled', 'Provider',
     'CannedScript', 'Source', 'ScriptPath', 'CodeLen', 'CodeHash', 'FunctionId', 'Severity', 'Tokens',
     'SuggestedTarget', 'Decision', 'Notes', 'Script', 'ActionXML'] as List<String>,
    bRows, 15, [20, 21] as List<Integer>))

// ---- Table E: distinct SR scripts ----
out.append(legendFor('Legend: Table E - Distinct SR scripts', [
  ['CodeHash', 'Script fingerprint - same value in Table B means the identical script'],
  ['Uses', 'How many workflow functions use this exact script'],
  ['Refs', 'Table B rows using it - one rewrite covers all of them'],
  ['Script', 'Full decoded Groovy code of this distinct script'],
] as List<List<String>>))
List<List<String>> eRows = []
List<Map.Entry<String, Map<String, Object>>> grpEntries =
    new ArrayList<Map.Entry<String, Map<String, Object>>>(scriptGroups.entrySet())
grpEntries.sort { Map.Entry<String, Map<String, Object>> en -> -(en.value.get('count') as int) }
grpEntries.each { Map.Entry<String, Map<String, Object>> en ->
  List<String> refs = en.value.get('refs') as List<String>
  eRows << ([en.key, String.valueOf(en.value.get('count')), String.valueOf(en.value.get('canned')),
             String.valueOf(en.value.get('len')), String.valueOf(en.value.get('sev')),
             String.valueOf(en.value.get('tok')), refs.join(', '),
             String.valueOf(en.value.get('code') ?: '')] as List<String>)
}
if (eRows.isEmpty()) eRows << (['(no SR scripts with code found)', '', '', '', '', '', '', ''] as List<String>)
// Severity is column index 4 in table E; Script=7 is a code column
out.append(htmlTable('Table E - Distinct SR scripts (rewrite once, apply to all Refs)',
    ['CodeHash', 'Uses', 'CannedScript', 'CodeLen', 'Severity', 'Tokens', 'Refs', 'Script'] as List<String>,
    eRows, 4, [7] as List<Integer>))
List<List<String>> cRows = []
List<Map.Entry<String, Map<String, Object>>> tokenEntries =
    new ArrayList<Map.Entry<String, Map<String, Object>>>(tokenRollup.entrySet())
tokenEntries.sort { Map.Entry<String, Map<String, Object>> en ->
  int sevRank = SEV_RANK.get(String.valueOf(en.value.get('sev'))) ?: 0
  int scripts = en.value.get('scripts') as int
  return -(sevRank * 1000 + scripts)
}
tokenEntries.each { Map.Entry<String, Map<String, Object>> en ->
  cRows << ([en.key, String.valueOf(en.value.get('hits')), String.valueOf(en.value.get('scripts')),
             String.valueOf(en.value.get('sev')), String.valueOf(en.value.get('hint'))] as List<String>)
}
if (cRows.isEmpty()) cRows << (['(none found)', '', '', '', ''] as List<String>)
out.append(legendFor('Legend: Table C - Cloud-blocker tokens', [
  ['Token', 'DC-only API/class pattern found in SR script code'],
  ['Hits', 'Total occurrences across all scripts'],
  ['Scripts', 'Number of distinct workflow functions containing it'],
  ['Severity', 'HARD = no cloud equivalent (redesign), MED = rewrite, EASY = trivial'],
  ['CloudReplacementHint', 'How to replace it on cloud (SR Cloud HAPI, REST, Automation...)'],
] as List<List<String>>))
// Severity is column index 3 in table C
out.append(htmlTable('Table C - Cloud-blocker tokens',
    ['Token', 'Hits', 'Scripts', 'Severity', 'CloudReplacementHint'] as List<String>,
    cRows, 3, [] as List<Integer>))

List<List<String>> dRows = []
dRows << (['Workflows total', String.valueOf(wfIdx)] as List<String>)
dRows << (['Workflows active', String.valueOf(wfIdx - inactiveList.size())] as List<String>)
dRows << (['Workflows inactive (JCMA will NOT migrate unless assigned to migrated scheme)',
           String.valueOf(inactiveList.size())] as List<String>)
dRows << (['Inactive names', inactiveList.isEmpty() ? '-' : inactiveList.join(' | ')] as List<String>)
dRows << (['Drafts (not migrated - publish or discard)',
           draftList.isEmpty() ? '-' : draftList.join(' | ')] as List<String>)
dRows << (['SR functions total', String.valueOf(totalSR)] as List<String>)
dRows << (['SR inline scripts', String.valueOf(srInline)] as List<String>)
dRows << (['SR file scripts resolved', String.valueOf(srFile)] as List<String>)
dRows << (['SR file scripts NOT resolved', String.valueOf(srFileMissing)] as List<String>)
dRows << (['SR severity EASY', String.valueOf(sevCount.get('EASY'))] as List<String>)
dRows << (['SR severity MED', String.valueOf(sevCount.get('MED'))] as List<String>)
dRows << (['SR severity HARD (redesign needed)', String.valueOf(sevCount.get('HARD'))] as List<String>)
dRows << (['HARD refs (see Table B)', hardRefs.isEmpty() ? '-' : hardRefs.join(' ')] as List<String>)
List<String> blankParts = []
blankRiskCanned.each { String k, Integer v -> blankParts << (k + ' x' + v) }
dRows << (['SR canned COND/VAL (arrive BLANK on cloud - must recreate)',
           blankParts.isEmpty() ? '-' : blankParts.join(' | ')] as List<String>)
dRows << (['Script roots checked',
           scriptRoots.isEmpty() ? 'none found' : scriptRoots.join(' | ')] as List<String>)
out.append(legendFor('Legend: Table D - Summary', [
  ['Metric / Value', 'Instance-wide totals: workflow counts, SR function counts by source and severity, canned conditions/validators that arrive blank on cloud, and the script roots searched for file-based scripts'],
] as List<List<String>>))
out.append(htmlTable('Table D - Summary', ['Metric', 'Value'] as List<String>, dRows, -1, [] as List<Integer>))
out.append('</div>')

return out.toString()
