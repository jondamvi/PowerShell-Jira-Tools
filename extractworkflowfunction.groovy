/*
 * EXTRACT WORKFLOW FUNCTION (decoded) - for per-function migration analysis
 * -------------------------------------------------------------------------
 * Run in: ScriptRunner Script Console on the Jira DC TEST instance. Read-only.
 *
 * Prints all post functions / validators / conditions of ONE transition,
 * with ScriptRunner args (inline script, notes, JSON config) base64-DECODED,
 * rendered as HTML so the code is readable and copy/paste-able.
 *
 * Configure below: ACTION_ID (transition id) and optionally WORKFLOW_NAME
 * (leave '' to search all workflows for that action id).
 */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.workflow.JiraWorkflow
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ConditionDescriptor
import com.opensymphony.workflow.loader.ConditionsDescriptor
import com.opensymphony.workflow.loader.FunctionDescriptor
import com.opensymphony.workflow.loader.RestrictionDescriptor
import com.opensymphony.workflow.loader.ResultDescriptor
import com.opensymphony.workflow.loader.ValidatorDescriptor
import com.opensymphony.workflow.loader.WorkflowDescriptor
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ---------------------------------------------------------------- config ---
final String WORKFLOW_NAME = ''   // exact workflow name, or '' = search all workflows
final int ACTION_ID = 221         // transition id (e.g. 221 for "Uebergabeprotokoll erstellen")

// --------------------------------------------------------------- helpers ---
Closure<String> esc = { Object o ->
  String s = o == null ? '' : String.valueOf(o)
  return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

// decode ScriptRunner's base64 arg encoding: "YCFg..." -> "`!`<content>"
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

// pretty-print a JSON string if it is one
Closure<String> prettyIfJson = { String v ->
  if (v == null) return null
  String t = v.trim()
  if (!(t.startsWith('{') || t.startsWith('['))) return v
  try { return JsonOutput.prettyPrint(t) } catch (Exception e) { return v }
}

StringBuilder out = new StringBuilder()
def wfm = ComponentAccessor.workflowManager

Closure dumpDescriptor = { String wfName, String kind, Map args0, String type ->
  Map args = (args0 != null ? args0 : [:]) as Map
  String mk = String.valueOf(args.get('full.module.key') ?: '')
  String cn = String.valueOf(args.get('class.name') ?: type ?: '')
  boolean isSR = (mk + cn).toLowerCase().contains('onresolve')
  out.append('<h3 style="margin:14px 0 4px 0;color:#205081">')
     .append(esc(kind)).append(' &mdash; ').append(esc(isSR ? 'ScriptRunner' : cn)).append('</h3>')
  out.append('<table style="border-collapse:collapse;font-family:Arial,sans-serif;font-size:12px">')
  List<String> argKeys = []
  args.each { Object k, Object v -> argKeys << String.valueOf(k) }
  argKeys.sort()
  argKeys.each { String k ->
    String rawVal = String.valueOf(args.get(k))
    String decoded = decodeMaybe(rawVal)
    boolean wasEncoded = !decoded.equals(rawVal)
    String display = prettyIfJson(decoded)
    boolean isCode = k.contains('SCRIPT') || k.contains('CONDITION') || k == 'script'
    out.append('<tr><td style="border:1px solid #bbb;padding:3px 8px;vertical-align:top;white-space:nowrap;background:#f0f0f0"><b>')
       .append(esc(k)).append(wasEncoded ? ' *' : '').append('</b></td>')
    out.append('<td style="border:1px solid #bbb;padding:3px 8px">')
    if (isCode || display.contains('\n')) {
      out.append('<pre style="margin:0;white-space:pre-wrap;font-family:monospace;font-size:12px">')
         .append(esc(display)).append('</pre>')
    } else {
      out.append(esc(display.length() > 2000 ? display.take(2000) + ' ...[cut]' : display))
    }
    out.append('</td></tr>')
  }
  out.append('</table>')
}

// ------------------------------------------------------------------ main ---
out.append('<div style="font-family:Arial,sans-serif">')
out.append('<p style="font-size:12px">* = value was stored base64-encoded and has been decoded here</p>')

int found = 0
wfm.workflows.each { JiraWorkflow wf ->
  if (WORKFLOW_NAME.length() > 0 && wf.name != WORKFLOW_NAME) return
  boolean isDraftCopy = false
  try { isDraftCopy = wf.isDraftWorkflow() } catch (Exception e) {}
  if (isDraftCopy) return
  try {
    WorkflowDescriptor wd = wf.descriptor
    ActionDescriptor a = wd.getAction(ACTION_ID)
    if (a == null) return
    found++
    out.append('<h2 style="margin:16px 0 4px 0;color:#000">Workflow: ').append(esc(wf.name))
       .append(' &mdash; Transition ').append(String.valueOf(a.id)).append(': ')
       .append(esc(a.name != null ? a.name : '?')).append('</h2>')

    a.unconditionalResult?.postFunctions?.each { Object o ->
      if (o instanceof FunctionDescriptor) dumpDescriptor(wf.name, 'POST FUNCTION', o.args, o.type)
    }
    a.conditionalResults?.each { Object cr ->
      if (cr instanceof ResultDescriptor) {
        cr.postFunctions?.each { Object o ->
          if (o instanceof FunctionDescriptor) dumpDescriptor(wf.name, 'POST FUNCTION (conditional result)', o.args, o.type)
        }
      }
    }
    a.validators?.each { Object o ->
      if (o instanceof ValidatorDescriptor) dumpDescriptor(wf.name, 'VALIDATOR', o.args, o.type)
    }
    RestrictionDescriptor rd = a.restriction
    if (rd != null && rd.conditionsDescriptor != null) {
      List<Object> stack = []
      stack << rd.conditionsDescriptor
      while (!stack.isEmpty()) {
        Object cur = stack.remove(stack.size() - 1)
        if (cur instanceof ConditionsDescriptor) {
          cur.conditions?.each { Object c -> stack << c }
        } else if (cur instanceof ConditionDescriptor) {
          dumpDescriptor(wf.name, 'CONDITION', cur.args, cur.type)
        }
      }
    }
  } catch (Exception e) {
    out.append('<p>ERROR in workflow ').append(esc(wf.name)).append(': ').append(esc(e.message)).append('</p>')
  }
}

if (found == 0) {
  out.append('<p><b>No workflow contains action id ').append(String.valueOf(ACTION_ID))
     .append(WORKFLOW_NAME.length() > 0 ? ' (searched only workflow "' + esc(WORKFLOW_NAME) + '")' : '')
     .append('.</b> Check the transition id in the workflow XML/text view.</p>')
} else {
  out.append('<p>Found in ').append(String.valueOf(found)).append(' workflow(s).</p>')
}
out.append('</div>')
return out.toString()
