/*
 * SCRIPT B - REMEDIATION (ORM only, no SQL, no outage)
 *
 * Flattens the "qualification-table" macro into static storage format on the
 * current version and, optionally, on every historical version of each page.
 * All writes go through PageManager so Confluence handles persistence, cache
 * invalidation and (for the current version) re-indexing itself.
 *
 * RUN ORDER
 *   1. MODE = 'INSPECT'  -> reports only, writes nothing.
 *   2. MODE = 'APPLY'    -> on a throwaway page with several versions first.
 *   3. MODE = 'INSPECT'  -> again, in a SEPARATE console execution. Re-reading
 *      inside the same run returns the cached Hibernate entity and proves
 *      nothing about what was committed.
 *   4. Check page history in the UI: version count, dates and authors unchanged;
 *      each old version renders the static table.
 *
 * Historical rewriting has no documented API contract - it uses the public
 * saveContentEntity(entity, saveContext), which is specified as "saves an
 * existing entity without creating a new version". Step 3 and 4 are how you
 * confirm it behaves that way on your instance.
 *
 * ORDER TRAP: saveNewVersion() copies the current body into a NEW historical row
 * before applying the change, so fixing the current version re-introduces the
 * macro into history. This script does the current version first, re-reads the
 * page, then sweeps history - so that new row is caught in the same pass.
 */

import com.atlassian.confluence.core.ContentEntityObject
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.core.Modification
import com.atlassian.confluence.core.SaveContext
import com.atlassian.confluence.core.VersionHistorySummary
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.sal.api.component.ComponentLocator

import groovy.transform.Field

import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
// Current page ids. Script A prints a ready-to-paste list.
@Field List<Long> PAGE_IDS = [123456789L]

@Field String MODE = 'INSPECT'            // 'INSPECT' (no writes) | 'APPLY'

// <<< the toggle you asked for >>>
// true  -> also rewrite every historical version of each page
// false -> current version only, history left untouched
@Field boolean UPDATE_HISTORICAL_VERSIONS = true

@Field String MACRO_NAME = 'qualification-table'

// true  -> current version updated via saveNewVersion(): audit trail and
//          rollback through page history, version count grows by 1
// false -> current version updated in place: no new version, no rollback except
//          the ORIGINAL BODY dump this script prints
@Field boolean CURRENT_CREATES_NEW_VERSION = true

// Historical rows: last modifier and modification date are preserved, and
// events are suppressed - historical versions are not indexed by Confluence and
// firing update events for them can confuse listeners. The current version's
// write always fires events, so it is re-indexed normally.
@Field boolean HISTORICAL_SUPPRESS_EVENTS = true
// ================================================================

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
@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+ac:name="([^"]+)"\\s*>(.*?)</ac:parameter>')


class Occurrence {
    int n, relevance, sumImpact, pct
    String oldXml, newHtml
}

class VersionResult {
    long    pageId
    long    contentId
    int     version
    boolean isCurrent
    int     replaced
    String  originalBody
    String  newBody
    String  outcome
}

String xmlEsc(Object value) {
    if (value == null) return ''
    return value.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

Map<String, String> parseParams(String macroXml) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    Matcher m = P_PARAM.matcher(macroXml)
    while (m.find()) found.put(m.group(1), m.group(2).trim())
    return found
}

Occurrence buildOccurrence(Map<String, String> params, int n) {
    List<String> missing = new ArrayList<String>()
    for (String key : REQUIRED) {
        String v = params.get(key)
        if (v == null || !(v ==~ /\d+/)) missing.add(key)
    }
    if (!missing.isEmpty()) {
        throw new IllegalStateException('Occurrence #' + n + ': missing or non-numeric parameter(s): ' +
                missing + ' (found: ' + params + ')')
    }

    int relevance = Integer.parseInt(params.get('relevance'))
    int sum = 0
    for (String key : IMPACT_KEYS) sum += Integer.parseInt(params.get(key))
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
    occ.n = n; occ.relevance = relevance; occ.sumImpact = sum; occ.pct = pct
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

VersionResult evaluate(ContentEntityObject ceo, long pageId, boolean isCurrent) {
    VersionResult vr = new VersionResult()
    vr.pageId = pageId
    vr.contentId = ceo.getId()
    vr.version = ceo.getVersion()
    vr.isCurrent = isCurrent
    vr.originalBody = ceo.getBodyAsString()
    List<Occurrence> details = new ArrayList<Occurrence>()
    String body = rewritePass(vr.originalBody, P_WRAPPED, details)
    body = rewritePass(body, P_BARE, details)
    vr.newBody = body
    vr.replaced = details.size()
    vr.outcome = details.isEmpty() ? 'no macro - skipped' : 'pending'
    return vr
}

// ---------------------------------------------------------------
PageManager pageManager = ComponentLocator.getComponent(PageManager)
boolean apply = (MODE == 'APPLY')

if (MODE != 'INSPECT' && MODE != 'APPLY') {
    return '<pre>ABORT: MODE must be INSPECT or APPLY.</pre>'
}

List<VersionResult> all = new ArrayList<VersionResult>()
StringBuilder detail = new StringBuilder()
StringBuilder problems = new StringBuilder()

for (Long pid : PAGE_IDS) {
    Page page = pageManager.getPage(pid.longValue())
    if (page == null) {
        problems.append('id ').append(pid).append(': not found\n'); continue
    }
    if (page.getOriginalVersionId() != null) {
        problems.append('id ').append(pid).append(': is a historical version, pass the CURRENT id\n'); continue
    }

    try {
        // ---- current version ----
        VersionResult cur = evaluate(page, pid.longValue(), true)
        if (cur.replaced > 0) {
            if (apply) {
                final String newBody = cur.newBody
                if (CURRENT_CREATES_NEW_VERSION) {
                    Modification<Page> mod = new Modification<Page>() {
                        @Override void modify(Page target) { target.setBodyAsString(newBody) }
                    }
                    pageManager.saveNewVersion(page, mod, new DefaultSaveContext(true, false, false))
                    cur.outcome = 'rewritten (new version created)'
                } else {
                    page.setBodyAsString(newBody)
                    pageManager.saveContentEntity(page, new DefaultSaveContext(true, false, false))
                    cur.outcome = 'rewritten in place'
                }
            } else {
                cur.outcome = 'would rewrite'
            }
        }
        all.add(cur)

        // ---- historical versions ----
        if (UPDATE_HISTORICAL_VERSIONS) {
            // re-read: if saveNewVersion ran, history now holds one more row
            Page refreshed = pageManager.getPage(pid.longValue())
            List<VersionHistorySummary> history = pageManager.getVersionHistorySummaries(refreshed)
            for (VersionHistorySummary summary : history) {
                long histId = summary.getId()
                if (histId == pid.longValue()) continue
                ContentEntityObject hist = pageManager.getPage(histId)
                if (hist == null) continue

                VersionResult vr = evaluate(hist, pid.longValue(), false)
                if (vr.replaced > 0) {
                    if (apply) {
                        Date keepModDate = hist.getLastModificationDate()
                        hist.setBodyAsString(vr.newBody)
                        hist.setLastModificationDate(keepModDate)
                        SaveContext ctx = new DefaultSaveContext(true, false, HISTORICAL_SUPPRESS_EVENTS)
                        pageManager.saveContentEntity(hist, ctx)
                        vr.outcome = 'rewritten in place (verify in a fresh run)'
                    } else {
                        vr.outcome = 'would rewrite'
                    }
                }
                all.add(vr)
            }
        }
    } catch (Exception e) {
        log.error("Flatten ${MACRO_NAME}: page ${pid} failed", e)
        problems.append('id ').append(pid).append(': ').append(e.toString()).append('\n')
    }
}

// ---- report ---------------------------------------------------
int touched = 0
StringBuilder report = new StringBuilder()
report.append('Mode : ').append(MODE).append(apply ? '  *** WRITES ENABLED ***' : '  (read only)')
      .append('   historical versions: ').append(UPDATE_HISTORICAL_VERSIONS ? 'INCLUDED' : 'skipped')
      .append('\nPages: ').append(PAGE_IDS.size()).append('\n\n')

report.append(String.format('%-13s %-13s %-8s %-8s %-7s %s%n',
        'PAGEID', 'CONTENTID', 'VERSION', 'CURRENT', 'MACROS', 'OUTCOME'))
for (VersionResult vr : all) {
    if (vr.replaced > 0) touched++
    report.append(String.format('%-13s %-13s %-8s %-8s %-7s %s%n',
            vr.pageId as String, vr.contentId as String, vr.version as String,
            vr.isCurrent ? 'yes' : '-', vr.replaced as String, vr.outcome))
}
report.append('\nVersions holding the macro: ').append(touched)
      .append(' of ').append(all.size()).append(' inspected\n')
if (problems.length() > 0) report.append('\nPROBLEMS\n').append(problems).append('\n')

for (VersionResult vr : all) {
    if (vr.replaced == 0) continue
    detail.append('======== page ').append(vr.pageId).append(' / contentid ').append(vr.contentId)
          .append(' (v').append(vr.version).append(vr.isCurrent ? ', CURRENT' : '').append(') ========\n')
    detail.append('--- ORIGINAL BODY (rollback copy) ---\n').append(xmlEsc(vr.originalBody)).append('\n')
    detail.append('--- NEW BODY ---\n').append(xmlEsc(vr.newBody)).append('\n\n')
}

log.warn("Flatten ${MACRO_NAME}: mode=${MODE}, history=${UPDATE_HISTORICAL_VERSIONS}, " +
         "pages=${PAGE_IDS.size()}, versions touched=${touched}")

return '<pre>' + report.toString() + '\n' + detail.toString() + '</pre>'
