/*
 * DC WORKFLOW INSPECTOR for Jira DC -> Cloud migration analysis
 * ------------------------------------------------------------
 * Run in: ScriptRunner Script Console on the Jira DC TEST instance.
 * Read-only: makes no changes. Output is designed to be compact and
 * ASCII-friendly so it can be re-typed or photographed.
 *
 * Output sections:
 *   A  Workflow inventory (active/inactive, drafts, projects, function counts per app)
 *   B  One line per app-provided workflow function (SR/JSU/JMWE/JWT/other)
 *   C  Token rollup: cloud-unsupported classes/objects found in SR scripts
 *   D  Summary + migration risk lists
 *
 * If output is too long to re-type: sections C and D are the minimum;
 * B lines with sev=HARD are next most valuable.
 */

import com.atlassian.jira.component.ComponentAccessor
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ConditionDescriptor
import com.opensymphony.workflow.loader.ConditionsDescriptor
import groovy.json.JsonSlurper

// ---------------------------------------------------------------- config ---
final int MAX_TOKENS_PER_LINE = 6      // tokens shown per B line
final boolean SHOW_BUILTIN_IN_B = false // set true to also list built-in Jira functions in B

// DC-only API tokens -> [severity, cloud hint]
// severity: HARD = no cloud equivalent / redesign; MED = rewrite (HAPI/REST); EASY = trivial rewrite
def TOKEN_MAP = [
  (/groovy\.sql|java\.sql|DriverManager|OfBizDelegator|DelegatorInterface/) :
      ['SQL-DB',            'HARD', 'No DB access on cloud. Use REST/entity properties/external service'],
  (/new\s+File\(|java\.io\.File|FileInputStream|FileWriter|Files\./) :
      ['Filesystem',        'HARD', 'No filesystem on cloud. Use attachments/entity props/external storage'],
  (/setLoggedInUser|JiraAuthenticationContext|SwitchUser|runAsUser/) :
      ['Impersonation',     'HARD', 'Cannot impersonate on cloud (OAuth). Redesign as app/actor-user action'],
  (/dispatchEvent|EventPublisher|IssueEventBundle|IssueEventManager|EventDispatchOption\.ISSUE_UPDATED|FiresEvent/) :
      ['FireEvents',        'HARD', 'Cannot fire arbitrary Jira events on cloud. Use Automation triggers/webhooks'],
  (/ldap|LDAP|ActiveDirectory|CrowdService/) :
      ['LDAP-Crowd',        'HARD', 'No directory access. Use org REST APIs / user props'],
  (/SMTPMailServer|MailQueue|javax\.mail|Email\(|MailServerManager/) :
      ['DirectMail',        'HARD', 'No mail server access. Use Automation "Send email" or external mail API'],
  (/ServiceDeskManager|SlaInformation|@WithPlugin\("com\.atlassian\.servicedesk/) :
      ['JSM-JavaAPI',       'HARD', 'JSM Java API absent. Use JSM cloud REST (limited SLA API)'],
  (/ComponentAccessor|ComponentManager/) :
      ['ComponentAccessor', 'MED',  'Java API entrypoint. Rewrite with SR Cloud HAPI (Issues.*, Users.*) or REST'],
  (/MutableIssue|issue\.setC|issue\.setS|issue\.setA|issue\.setD|issue\.setR|issue\.setF|\.store\(\)/) :
      ['MutableIssue',      'MED',  'In-transition field writes differ. Cloud: HAPI issue.update{} or REST PUT'],
  (/IssueManager|IssueService|IssueFactory/) :
      ['IssueManager',      'MED',  'HAPI Issues.getByKey/create/update or REST /issue'],
  (/CustomFieldManager|getCustomFieldObject|customfield_/) :
      ['CustomFields',      'MED',  'Cloud uses field IDs via REST; check field type support on cloud'],
  (/OptionsManager|FieldConfig|CascadingSelect/) :
      ['FieldOptions',      'MED',  'Option manipulation via REST /field/{id}/context/option'],
  (/CommentManager/) :
      ['Comments',          'EASY', 'HAPI issue.addComment or REST'],
  (/UserManager|UserUtil|GroupManager|getGroupsForUser|UserSearchService/) :
      ['Users-Groups',      'MED',  'Cloud users are accountIds (GDPR): REST user/group search'],
  (/WatcherManager|VoteManager/) :
      ['Watchers',          'EASY', 'REST watchers/votes endpoints'],
  (/LabelManager/) :
      ['Labels',            'EASY', 'issue.update { setLabels } / REST'],
  (/SearchService|JqlQueryParser|PagerFilter|SearchProvider/) :
      ['JQL-Search',        'MED',  'REST /search (paginated!, async)'],
  (/WorkflowTransitionUtil|WorkflowManager|getActionsWithTransition|transitionIssue/) :
      ['Transitions',       'MED',  'HAPI issue.transition / REST transitions; or Automation'],
  (/SubTaskManager|createSubTask/) :
      ['SubTasks',          'MED',  'Automation "Create sub-task" or HAPI create'],
  (/IssueLinkManager|RemoteIssueLink/) :
      ['IssueLinks',        'MED',  'REST /issueLink; Automation branch on linked issues'],
  (/VersionManager|ProjectComponentManager|ProjectManager/) :
      ['ProjectData',       'MED',  'REST project/version/component endpoints'],
  (/AttachmentManager/) :
      ['Attachments',       'MED',  'REST attachments (multipart)'],
  (/transientVars/) :
      ['transientVars',     'MED',  'Different binding on cloud; limited transition context'],
  (/HttpURLConnection|HttpClient|URLConnection|Unirest|RESTClient|HttpBuilder/) :
      ['OutboundHTTP',      'MED',  'Allowed on cloud but async + 240s cap; check egress/auth'],
  (/Thread\.|Executors|sleep\(/) :
      ['Threads',           'MED',  'Async model on cloud, 240s max; no long sleeps'],
  (/@Grab|classpath|GroovyClassLoader/) :
      ['ExternalJars',      'HARD', 'No external libs/classloader on cloud'],
  (/getLinkedIssues|getParentObject|getSubTaskObjects/) :
      ['IssueNav',          'EASY', 'HAPI navigation or REST fields=parent,subtasks,issuelinks'],
]

def SEV_RANK = [HARD: 3, MED: 2, EASY: 1]

// --------------------------------------------------------------- helpers ---
def out = new StringBuilder()
def wfm  = ComponentAccessor.workflowManager
def wsm  = ComponentAccessor.workflowSchemeManager
def pm   = ComponentAccessor.projectManager

// map workflowName -> project keys using it
def wfProjects = [:].withDefault { [] }
try {
  pm.projectObjects.each { p ->
    try {
      def scheme = wsm.getWorkflowSchemeObj(p)
      def names = [] as Set
      if (scheme) {
        names.addAll(scheme.mappings.values())
        if (scheme.configuredDefaultWorkflow) names << scheme.configuredDefaultWorkflow
      } else { names << 'jira' }
      names.each { n -> wfProjects[n] << p.key }
    } catch (Exception e) { /* scheme API differences - ignore project */ }
  }
} catch (Exception e) { out.append("WARN project mapping failed: ${e.message}\n") }

def activeNames = wfm.activeWorkflows*.name as Set

// provider detection from descriptor args/type
def providerOf = { Map args, String type ->
  def mk = (args?.get('full.module.key') ?: '') as String
  def cn = (args?.get('class.name') ?: '') as String
  def s  = (mk + ' ' + cn + ' ' + (type ?: '')).toLowerCase()
  if (s.contains('onresolve') || s.contains('groovyrunner'))  return 'SR'
  if (s.contains('jsu') || s.contains('beecom'))              return 'JSU'
  if (s.contains('jmwe') || s.contains('innovalog'))          return 'JMWE'
  if (s.contains('jwt') || s.contains('decadis'))             return 'JWT'
  if (mk)                                                     return 'APP:' + mk.replaceAll(/^com\./,'').take(35)
  return 'BUILTIN'
}

// extract SR script (inline code or file path) from args
def extractScript = { Map args ->
  String code = null, path = null
  def fs = args?.get('FIELD_SCRIPT_FILE_OR_SCRIPT')
  if (fs instanceof String && fs.trim()) {
    try {
      def j = new JsonSlurper().parseText(fs)
      code = j?.script ?: null
      path = j?.scriptPath ?: null
    } catch (Exception e) { code = fs }
  }
  if (!code && args?.get('script'))     code = args.get('script') as String
  if (!path && args?.get('scriptPath')) path = args.get('scriptPath') as String
  // conditions store code in FIELD_CONDITION; extra scripts too
  ['FIELD_CONDITION', 'FIELD_ADDITIONAL_SCRIPT', 'FIELD_JQL_QUERY'].each { k ->
    def v = args?.get(k)
    if (v instanceof String && v.trim()) code = (code ? code + '\n' : '') + v
  }
  [code: code, path: path]
}

// try to resolve script files from known roots
def scriptRoots = []
try {
  def sp = System.getProperty('plugin.script.roots')
  if (sp) scriptRoots.addAll(sp.split(',')*.trim())
} catch (Exception e) {}
try {
  def home = ComponentAccessor.getComponent(com.atlassian.jira.config.util.JiraHome).home.absolutePath
  scriptRoots << (home + File.separator + 'scripts')
} catch (Exception e) {}
def readScriptFile = { String p ->
  if (!p) return null
  for (r in scriptRoots) {
    try {
      def f = new File(r, p)
      if (f.exists()) return f.getText('UTF-8')
    } catch (Exception e) {}
  }
  return null
}

// token scan
def tokenRollup = [:].withDefault { [hits: 0, scripts: 0, sev: 'EASY', hint: ''] }
def scanTokens = { String code ->
  def found = [] // list of [name, count, sev, hint]
  if (!code) return found
  TOKEN_MAP.each { pat, meta ->
    def m = (code =~ pat)
    int c = m.count
    if (c > 0) found << [name: meta[0], count: c, sev: meta[1], hint: meta[2]]
  }
  found
}

// collect all actions of a workflow descriptor (initial, global, common, step)
def actionsOf = { wd ->
  def acts = new LinkedHashSet<ActionDescriptor>()
  try { acts.addAll(wd.initialActions) } catch (Exception e) {}
  try { acts.addAll(wd.globalActions) } catch (Exception e) {}
  try { wd.commonActions?.values()?.each { acts << it } } catch (Exception e) {}
  try { wd.steps.each { s -> acts.addAll(s.actions) } } catch (Exception e) {}
  acts
}

def collectConds
collectConds = { cd, List into ->
  if (cd == null) return
  cd.conditions?.each { c ->
    if (c instanceof ConditionsDescriptor) collectConds(c, into)
    else if (c instanceof ConditionDescriptor) into << c
  }
}

def shortCanned = { Map args ->
  def cs = (args?.get('canned-script') ?: args?.get('class.name') ?: '') as String
  cs ? cs.tokenize('.').last() : '-'
}

// ------------------------------------------------------------------ main ---
def bLines = []
def aLines = []
int wfIdx = 0
int totalSR = 0, srInline = 0, srFile = 0, srFileMissing = 0
def sevCount = [EASY: 0, MED: 0, HARD: 0]
def hardRefs = []
def blankRiskCanned = [:].withDefault { 0 }   // built-in SR canned conds/vals -> blank on cloud
def inactiveList = []
def draftList = []

wfm.workflows.sort { it.name.toLowerCase() }.each { wf ->
  wfIdx++
  def id = String.format('A%02d', wfIdx)
  try {
    def wd = wf.descriptor
    def acts = actionsOf(wd)
    boolean isActive = activeNames.contains(wf.name)
    boolean hasDraft = false
    try { hasDraft = wfm.getDraftWorkflow(wf.name) != null } catch (Exception e) {}
    if (!isActive) inactiveList << wf.name
    if (hasDraft) draftList << wf.name
    def provCount = [:].withDefault { 0 }

    acts.each { a ->
      def items = [] // [kind, descriptorArgs, type]
      try {
        a.unconditionalResult?.postFunctions?.each { items << ['PF', it.args, it.type] }
        a.conditionalResults?.each { cr -> cr.postFunctions?.each { items << ['PF', it.args, it.type] } }
        a.validators?.each { items << ['VAL', it.args, it.type] }
        def conds = []; collectConds(a.restriction?.conditionsDescriptor, conds)
        conds.each { items << ['COND', it.args, it.type] }
      } catch (Exception e) { return }

      items.each { kind, args0, type ->
        Map args = (args0 ?: [:]) as Map
        def prov = providerOf(args, type as String)
        provCount[prov]++
        if (prov == 'BUILTIN' && !SHOW_BUILTIN_IN_B) return

        def canned = shortCanned(args)
        String detail = '-', lenInfo = '', hash = '', sev = '', tokStr = ''
        if (prov == 'SR') {
          totalSR++
          def ex = extractScript(args)
          String code = ex.code
          if (ex.path) {
            def fileCode = readScriptFile(ex.path)
            if (fileCode != null) { code = ((code ?: '') + '\n' + fileCode).trim(); srFile++ }
            else { srFileMissing++; detail = 'FILE?:' + ex.path }
            if (detail == '-') detail = 'file:' + ex.path
          } else if (code) { detail = 'inline'; srInline++ }
          else { detail = 'nocode' }

          boolean isCustomScript = canned.toLowerCase().contains('customscript')
          if (!isCustomScript && (kind == 'COND' || kind == 'VAL'))
            blankRiskCanned[canned + '(' + kind + ')']++

          if (code) {
            lenInfo = 'len=' + code.length()
            hash = 'h=' + Integer.toHexString(code.trim().hashCode())
            def toks = scanTokens(code)
            def worst = 'EASY'
            toks.each { t ->
              def e = tokenRollup[t.name]
              e.hits += t.count; e.scripts += 1; e.hint = t.hint
              if (SEV_RANK[t.sev] > SEV_RANK[e.sev]) e.sev = t.sev
              if (SEV_RANK[t.sev] > SEV_RANK[worst]) worst = t.sev
            }
            // scripted conditions/validators always need Jira-expression rewrite on cloud
            if ((kind == 'COND' || kind == 'VAL') && SEV_RANK[worst] < SEV_RANK['MED']) worst = 'MED'
            sev = 'sev=' + worst
            sevCount[worst] = sevCount[worst] + 1
            tokStr = 'tok=' + (toks ? toks.sort { -it.count }.take(MAX_TOKENS_PER_LINE)*.name.join(',') : '-')
            if (worst == 'HARD') hardRefs << (id + ' t' + a.id + ' ' + kind + ' ' + canned)
          }
        }
        bLines << ('B ' + id + ' t' + a.id + ' "' + (a.name ?: '?') + '" ' + kind + ' ' + prov + ' ' +
                   canned + ' ' + detail + ' ' + [lenInfo, hash, sev, tokStr].findAll { it }.join(' '))
      }
    }

    def provStr = provCount.sort { -it.value }.collect { k, v -> k + ':' + v }.join(' ')
    aLines << (id + ' | ' + wf.name + ' | ' + (isActive ? 'ACTIVE' : 'INACTIVE') +
               ' | draft=' + (hasDraft ? 'Y' : 'N') +
               ' | prj=' + (wfProjects[wf.name]?.size() ?: 0) +
               ' | trans=' + acts.size() + ' | ' + (provStr ?: 'none'))
  } catch (Exception e) {
    aLines << (id + ' | ' + wf.name + ' | ERROR: ' + e.message)
  }
}

// ---------------------------------------------------------------- output ---
out.append('==== DC WORKFLOW INSPECTOR (read-only) ====\n')
out.append('Retype priority: D first, then C, then B lines with sev=HARD. Photos of all sections are ideal.\n\n')

out.append('=== A. WORKFLOWS (id | name | status | draft | #projects | #transitions | functions by provider) ===\n')
aLines.each { out.append(it).append('\n') }

out.append('\n=== B. APP-PROVIDED WORKFLOW FUNCTIONS (B id transition kind provider canned source len hash sev tokens) ===\n')
bLines.each { out.append(it).append('\n') }

out.append('\n=== C. CLOUD-BLOCKER TOKEN ROLLUP (token | total hits | #scripts | severity | cloud replacement hint) ===\n')
tokenRollup.sort { -SEV_RANK[it.value.sev] * 1000 - it.value.scripts }.each { name, e ->
  out.append('C ' + name.padRight(18) + ' | ' + e.hits + ' | ' + e.scripts + ' | ' + e.sev + ' | ' + e.hint + '\n')
}
if (!tokenRollup) out.append('C (no DC-only tokens found in SR scripts)\n')

out.append('\n=== D. SUMMARY ===\n')
out.append('D workflows=' + wfIdx + ' active=' + (wfIdx - inactiveList.size()) + ' inactive=' + inactiveList.size() + '\n')
out.append('D INACTIVE (JCMA will NOT migrate unless assigned to a migrated project scheme): ' +
           (inactiveList ?: '-') + '\n')
out.append('D DRAFTS (not migrated; publish or discard before migration): ' + (draftList ?: '-') + '\n')
out.append('D SR functions=' + totalSR + ' inline=' + srInline + ' file=' + srFile +
           ' fileNotResolved=' + srFileMissing + '\n')
out.append('D SR script severity: EASY=' + sevCount.EASY + ' MED=' + sevCount.MED + ' HARD=' + sevCount.HARD + '\n')
out.append('D HARD (redesign needed, cannot be 1:1 rewritten): ' + (hardRefs ?: '-') + '\n')
out.append('D SR built-in (canned) CONDITIONS/VALIDATORS -> arrive as BLANK unsupported rules on cloud, must be recreated: ' +
           (blankRiskCanned ? blankRiskCanned.collect { k, v -> k + ' x' + v }.join(', ') : '-') + '\n')
out.append('D Script roots checked for file scripts: ' + (scriptRoots ?: 'none found') + '\n')

return out.toString()
