/*
 * Flatten the "qualification-table" ScriptRunner macro into static Confluence
 * storage format, on ONE page (current version only).
 *
 * Where to run : Confluence DC -> ScriptRunner -> Script Console
 * Safety       : DRY_RUN = true prints the before/after body and writes nothing.
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
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.sal.api.component.ComponentLocator

import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
final long    PAGE_ID   = 123456789L      // <-- test page id
final boolean DRY_RUN   = true            // <-- flip to false to actually write
final String  MACRO_NAME = 'qualification-table'

// true  -> "Last modified by" stays as it was (recommended for bulk remediation)
// false -> the account running this script becomes last modifier
final boolean PRESERVE_LAST_MODIFIER = true
// ================================================================

// Column order in the rendered table: label -> macro parameter name
final List<List<String>> COLUMNS = [
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
final List<String> IMPACT_KEYS = COLUMNS.collect { it[1] }
final List<String> REQUIRED    = IMPACT_KEYS + ['relevance']

final String Q = Pattern.quote(MACRO_NAME)
final Pattern P_WRAPPED = Pattern.compile(
        '(?s)<p>\\s*(<ac:structured-macro\\b[^>]*ac:name="' + Q + '"[^>]*>.*?</ac:structured-macro>)\\s*</p>')
final Pattern P_BARE = Pattern.compile(
        '(?s)(<ac:structured-macro\\b[^>]*ac:name="' + Q + '"[^>]*>.*?</ac:structured-macro>)')
final Pattern P_SELFCLOSED = Pattern.compile(
        '(?s)<ac:structured-macro\\b[^>]*ac:name="' + Q + '"[^>]*/>')
final Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+ac:name="([^"]+)"\\s*>(.*?)</ac:parameter>')

def xmlEsc = { Object o ->
    o.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

def parseParams = { String macroXml ->
    def map = [:]
    def m = P_PARAM.matcher(macroXml)
    while (m.find()) { map[m.group(1)] = m.group(2).trim() }
    map
}

def buildReplacement = { Map params, int occurrence ->
    def missing = REQUIRED.findAll { !params.containsKey(it) || !(params[it] ==~ /\d+/) }
    if (missing) {
        throw new IllegalStateException(
                "Occurrence #${occurrence}: missing or non-numeric parameter(s): ${missing} " +
                "(found: ${params})")
    }
    int relevance = params.relevance as int
    int sum = IMPACT_KEYS.sum { params[it] as int } as int
    int pct = (100 * sum * relevance).intdiv(135)

    def sb = new StringBuilder()
    sb << "<p>Qualifikationsfaktor: ${pct}%</p>"
    sb << '<table><tbody>'
    sb << '<tr><th>Bedeutung</th><th colspan="9">Auswirkung</th></tr>'
    sb << '<tr><th>&nbsp;</th>' << COLUMNS.collect { "<th>${xmlEsc(it[0])}</th>" }.join('') << '</tr>'
    sb << "<tr><td>${relevance}</td>" << COLUMNS.collect { "<td>${params[it[1]]}</td>" }.join('') << '</tr>'
    sb << '</tbody></table>'
    sb << '<p>&nbsp;</p>'
    sb << "<p>${pct}%</p>"
    [html: sb.toString(), pct: pct, sum: sum, relevance: relevance]
}

// ---------------------------------------------------------------
def pageManager = ComponentLocator.getComponent(PageManager)
def log = new StringBuilder()
def page = pageManager.getPage(PAGE_ID)

if (page == null) {
    return "<pre>ABORT: no page with id ${PAGE_ID} (blog post? use BlogPost / ContentEntityManager)</pre>"
}
if (page.getOriginalVersion() != null) {
    return "<pre>ABORT: id ${PAGE_ID} is a HISTORICAL version, not the current one.</pre>"
}

String original = page.getBodyAsString()
def counter = 0
def details = []

def rewritePass = { String body, Pattern pattern, int macroGroup ->
    StringBuffer out = new StringBuffer()
    Matcher m = pattern.matcher(body)
    while (m.find()) {
        counter++
        def macroXml = m.group(macroGroup)
        def params = parseParams(macroXml)
        def r = buildReplacement(params, counter)
        details << [n: counter, params: params, r: r, old: m.group(0)]
        m.appendReplacement(out, Matcher.quoteReplacement(r.html))
    }
    m.appendTail(out)
    out.toString()
}

String updated
try {
    // pass 1: macro alone inside its own <p> -> swallow the <p> too
    //         (a <table> may not live inside a <p>)
    updated = rewritePass(original, P_WRAPPED, 1)
    // pass 2: any remaining occurrences not wrapped that way
    updated = rewritePass(updated, P_BARE, 1)
} catch (IllegalStateException e) {
    return "<pre>ABORT, nothing written.\n${xmlEsc(e.message)}</pre>"
}

if (P_SELFCLOSED.matcher(original).find()) {
    log << "WARNING: a self-closing (parameterless) ${MACRO_NAME} macro is present and was NOT touched.\n"
}

if (counter == 0) {
    return "<pre>No '${MACRO_NAME}' macro found on page ${PAGE_ID} ('${xmlEsc(page.title)}').\n\n" +
           "--- STORAGE BODY ---\n${xmlEsc(original)}</pre>"
}

// ---------------------------------------------------------------
log << "Page      : ${page.title} (id ${PAGE_ID}, space ${page.spaceKey}, version ${page.version})\n"
log << "Occurrences replaced: ${counter}\n"
log << "Mode      : ${DRY_RUN ? 'DRY RUN - nothing written' : 'APPLY'}\n\n"
details.each {
    log << "--- occurrence #${it.n}: relevance=${it.r.relevance} sumImpact=${it.r.sum} -> ${it.r.pct}%\n"
    log << "OLD:\n${xmlEsc(it.old)}\n"
    log << "NEW:\n${xmlEsc(it.r.html)}\n\n"
}
log << "=== ORIGINAL FULL BODY (keep this - it is your rollback copy) ===\n"
log << xmlEsc(original) << "\n\n"
log << "=== NEW FULL BODY ===\n"
log << xmlEsc(updated) << "\n"

if (!DRY_RUN) {
    // DefaultSaveContext(suppressNotifications, updateLastModifier, suppressEvents)
    // suppressEvents MUST stay false, otherwise the page is not re-indexed.
    def saveContext = new DefaultSaveContext(true, !PRESERVE_LAST_MODIFIER, false)
    pageManager.saveNewVersion(page, { Page p -> p.setBodyAsString(updated) } as Modification, saveContext)
    def after = pageManager.getPage(PAGE_ID)
    log << "\nSAVED. New version = ${after.version}. Macro still present in body: " +
           "${after.getBodyAsString().contains('ac:name="' + MACRO_NAME + '"')}\n"
}

return "<pre>${log}</pre>"
