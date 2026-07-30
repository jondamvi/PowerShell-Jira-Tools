/*
 * Flatten the "qualification-table" ScriptRunner macro into static Confluence
 * storage format, on ONE page (current version only).
 *
 * Where to run : Confluence DC -> ScriptRunner -> Script Console
 * Safety       : DRY_RUN = true prints the before/after body and writes nothing.
 *
 * Written to satisfy ScriptRunner's static type checker: no untyped maps in
 * property position, no closure coerced to Modification, no locals named
 * log/out (those are console bindings).
 *
 * Output produced per macro occurrence (matches the macro's rendered layout):
 *
 *   <p>Qualifikationsfaktor: NN%</p>
 *   <table> ... 3 rows, 10 columns, "Auswirkung" spanning 9 ... </table>
 *   <p>&nbsp;</p>          <- the blank line seen under the table
 *   <p>NN%</p>
 *
 * percentage = (int)(100 * sumOf9Impacts * relevance / 135)   [135 = 9*3*5]
 */

import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.core.Modification
import com.atlassian.confluence.core.SaveContext
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.sal.api.component.ComponentLocator

import groovy.transform.Field

import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
@Field long    PAGE_ID    = 123456789L     // <-- test page id
@Field boolean DRY_RUN    = true           // <-- flip to false to actually write
@Field String  MACRO_NAME = 'qualification-table'

// true  -> "Last modified by" stays as it was (recommended for bulk remediation)
// false -> the account running this script becomes last modifier
@Field boolean PRESERVE_LAST_MODIFIER = true
// ================================================================

// Column order in the rendered table: label -> macro parameter name
@Field List<List<String>> COLUMNS = [
        ['KB'  , 'impactFinance'],
        ['V'   , 'impactSales'],
        ['PM'  , 'impactProductmanagement'],
        ['M'   , 'impactMarketing'],
        ['O&S' , 'impactOuS'],
        ['HR'  , 'impactHR'],
        ['GF'  , 'impactGF'],
        ['LEAS', 'impactLR'],
        ['ASUS', 'impactASUS'],
]
@Field List<String> IMPACT_KEYS = COLUMNS.collect { List<String> c -> c.get(1) }
@Field List<String> REQUIRED    = IMPACT_KEYS + ['relevance']

@Field Pattern P_WRAPPED = Pattern.compile(
        '(?s)<p>\\s*(<ac:structured-macro\\b[^>]*ac:name="' + Pattern.quote(MACRO_NAME) + '"[^>]*>.*?</ac:structured-macro>)\\s*</p>')
@Field Pattern P_BARE = Pattern.compile(
        '(?s)(<ac:structured-macro\\b[^>]*ac:name="' + Pattern.quote(MACRO_NAME) + '"[^>]*>.*?</ac:structured-macro>)')
@Field Pattern P_SELFCLOSED = Pattern.compile(
        '(?s)<ac:structured-macro\\b[^>]*ac:name="' + Pattern.quote(MACRO_NAME) + '"[^>]*/>')
@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+ac:name="([^"]+)"\\s*>(.*?)</ac:parameter>')


/** One replaced macro instance, kept for the audit output. */
class Occurrence {
    int    n
    int    relevance
    int    sumImpact
    int    pct
    String oldXml
    String newHtml
}

String xmlEsc(Object value) {
    if (value == null) return ''
    return value.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

Map<String, String> parseParams(String macroXml) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    Matcher m = P_PARAM.matcher(macroXml)
    while (m.find()) {
        found.put(m.group(1), m.group(2).trim())
    }
    return found
}

Occurrence buildOccurrence(Map<String, String> params, int n) {
    List<String> missing = new ArrayList<String>()
    for (String key : REQUIRED) {
        String v = params.get(key)
        if (v == null || !(v ==~ /\d+/)) {
            missing.add(key)
        }
    }
    if (!missing.isEmpty()) {
        throw new IllegalStateException(
                "Occurrence #" + n + ": missing or non-numeric parameter(s): " + missing +
                " (found: " + params + ")")
    }

    int relevance = Integer.parseInt(params.get('relevance'))
    int sum = 0
    for (String key : IMPACT_KEYS) {
        sum += Integer.parseInt(params.get(key))
    }
    int pct = ((100 * sum * relevance).intdiv(135)) as int

    StringBuilder headers = new StringBuilder()
    StringBuilder values  = new StringBuilder()
    for (List<String> col : COLUMNS) {
        headers.append('<th>').append(xmlEsc(col.get(0))).append('</th>')
        values.append('<td>').append(params.get(col.get(1))).append('</td>')
    }

    StringBuilder html = new StringBuilder()
    html.append('<p>Qualifikationsfaktor: ').append(pct).append('%</p>')
    html.append('<table><tbody>')
    html.append('<tr><th>Bedeutung</th><th colspan="9">Auswirkung</th></tr>')
    html.append('<tr><th>&nbsp;</th>').append(headers).append('</tr>')
    html.append('<tr><td>').append(relevance).append('</td>').append(values).append('</tr>')
    html.append('</tbody></table>')
    html.append('<p>&nbsp;</p>')
    html.append('<p>').append(pct).append('%</p>')

    Occurrence occ = new Occurrence()
    occ.n = n
    occ.relevance = relevance
    occ.sumImpact = sum
    occ.pct = pct
    occ.newHtml = html.toString()
    return occ
}

String rewritePass(String body, Pattern pattern, List<Occurrence> details) {
    StringBuffer buf = new StringBuffer()
    Matcher m = pattern.matcher(body)
    while (m.find()) {
        Occurrence occ = buildOccurrence(parseParams(m.group(1)), details.size() + 1)
        occ.oldXml = m.group(0)
        details.add(occ)
        m.appendReplacement(buf, Matcher.quoteReplacement(occ.newHtml))
    }
    m.appendTail(buf)
    return buf.toString()
}

// ---------------------------------------------------------------
PageManager pageManager = ComponentLocator.getComponent(PageManager)
Page page = pageManager.getPage(PAGE_ID)

if (page == null) {
    return '<pre>ABORT: no page with id ' + PAGE_ID + ' (blog post? use BlogPost / ContentEntityManager)</pre>'
}
// A historical version carries a pointer back to the current one; current versions return null.
if (page.getOriginalVersionId() != null) {
    return '<pre>ABORT: id ' + PAGE_ID + ' is a HISTORICAL version (current version id = ' +
           page.getOriginalVersionId() + '), not the current one.</pre>'
}

String original = page.getBodyAsString()
List<Occurrence> details = new ArrayList<Occurrence>()
String updated

try {
    // pass 1: macro alone inside its own <p> -> swallow the <p> too
    //         (a <table> may not live inside a <p>)
    updated = rewritePass(original, P_WRAPPED, details)
    // pass 2: any remaining occurrences not wrapped that way
    updated = rewritePass(updated, P_BARE, details)
} catch (IllegalStateException e) {
    log.warn("Flatten ${MACRO_NAME}: page ${PAGE_ID} aborted - ${e.message}")
    return '<pre>ABORT, nothing written.\n' + xmlEsc(e.message) + '</pre>'
}

if (details.isEmpty()) {
    return '<pre>No "' + MACRO_NAME + '" macro found on page ' + PAGE_ID +
           ' ("' + xmlEsc(page.getTitle()) + '").\n\n--- STORAGE BODY ---\n' + xmlEsc(original) + '</pre>'
}

StringBuilder report = new StringBuilder()
if (P_SELFCLOSED.matcher(original).find()) {
    report.append('WARNING: a self-closing (parameterless) ').append(MACRO_NAME)
          .append(' macro is present and was NOT touched.\n\n')
}
report.append('Page      : ').append(page.getTitle())
      .append(' (id ').append(PAGE_ID)
      .append(', space ').append(page.getSpace()?.getKey())
      .append(', version ').append(page.getVersion()).append(')\n')
report.append('Occurrences replaced: ').append(details.size()).append('\n')
report.append('Mode      : ').append(DRY_RUN ? 'DRY RUN - nothing written' : 'APPLY').append('\n\n')

for (Occurrence occ : details) {
    report.append('--- occurrence #').append(occ.n)
          .append(': relevance=').append(occ.relevance)
          .append(' sumImpact=').append(occ.sumImpact)
          .append(' -> ').append(occ.pct).append('%\n')
    report.append('OLD:\n').append(xmlEsc(occ.oldXml)).append('\n')
    report.append('NEW:\n').append(xmlEsc(occ.newHtml)).append('\n\n')
}
report.append('=== ORIGINAL FULL BODY (keep this - it is your rollback copy) ===\n')
report.append(xmlEsc(original)).append('\n\n')
report.append('=== NEW FULL BODY ===\n')
report.append(xmlEsc(updated)).append('\n')

log.warn("Flatten ${MACRO_NAME}: page ${PAGE_ID}, ${details.size()} occurrence(s), dryRun=${DRY_RUN}")

if (!DRY_RUN) {
    final String newBody = updated
    // DefaultSaveContext(suppressNotifications, updateLastModifier, suppressEvents)
    // suppressEvents MUST stay false, otherwise the page is not re-indexed.
    SaveContext saveContext = new DefaultSaveContext(true, !PRESERVE_LAST_MODIFIER, false)
    Modification<Page> modification = new Modification<Page>() {
        @Override
        void modify(Page target) {
            target.setBodyAsString(newBody)
        }
    }
    pageManager.saveNewVersion(page, modification, saveContext)

    Page after = pageManager.getPage(PAGE_ID)
    report.append('\nSAVED. New version = ').append(after.getVersion())
          .append('. Macro still present in body: ')
          .append(after.getBodyAsString().contains('ac:name="' + MACRO_NAME + '"')).append('\n')
}

return '<pre>' + report.toString() + '</pre>'
