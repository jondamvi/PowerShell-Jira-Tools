/*
 * Reconcile-MacroUsage.groovy                                    (READ ONLY)
 * -----------------------------------------------------------------------------
 * Explains the leftover counts on the admin "Macro Usage" page after the
 * migration - WITHOUT pasting page ids and WITHOUT any search API or index.
 *
 * For each macro name in MACRO_NAMES it scans CURRENT + DRAFT page bodies in
 * the database, in ordered chunks of contentid (SCAN_CHUNK) so no single query
 * scans the whole table, stopping cleanly when TIME_BUDGET_SECONDS is reached
 * and printing a resume offset - the same budget/resume pattern as the engine's
 * SCOPE mode. Each matched page is classified:
 *
 *   current still has macro  - a genuine miss; re-run the engine on it
 *   draft holds macro        - macro lives in an unpublished shared draft
 *                              (invisible when you just view the page); the
 *                              draft owner must publish or discard it
 *
 * Space category (Details): personal ('~'), marked for deletion (x_y), or blank.
 * Everything found is reported and counted. There is no "stale index" row state
 * here (the DB scan IS the source of truth); instead the footer notes that
 * admin-count minus DB-found = the stale-index remainder a reindex would clear.
 *
 * USAGE
 *   1. set MACRO_NAMES to the macro names you see in Macro Usage
 *   2. set BASE_URL to your Confluence base URL
 *   3. run. If it prints "SCAN INCOMPLETE - set SCAN_OFFSET = N", put that N in
 *      SCAN_OFFSET and run again; repeat until it prints "SCAN COMPLETE".
 */
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field
import java.text.SimpleDateFormat

// ============================== CONFIG =======================================

@Field List<String> MACRO_NAMES = ['last-modified']       // names from Macro Usage
@Field String BASE_URL = 'https://confluencesite.local'   // your Confluence base URL
@Field String DB_RESOURCE = 'ConfluenceDB'

@Field int SCAN_CHUNK = 2000              // contentids examined per query
@Field int TIME_BUDGET_SECONDS = 90       // stop cleanly under the proxy ceiling
@Field int SCAN_OFFSET = 0                // resume point printed by the previous run
@Field int SCROLLBOX_MAX_HEIGHT_PX = 600

// =============================================================================

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String spaceCategory(String key) {
    if (key == null) return ''
    if (key.startsWith('~')) return 'personal space'
    if (key.matches('[a-zA-Z0-9]+_[a-zA-Z0-9]+')) return 'marked for deletion'
    return ''
}

try {
    if (MACRO_NAMES.isEmpty()) return '<pre>Set MACRO_NAMES first.</pre>'

    String baseUrl = BASE_URL
    SimpleDateFormat fmt = new SimpleDateFormat('yyyy-MM-dd HH:mm')
    long runStart = System.currentTimeMillis()
    long budgetMs = TIME_BUDGET_SECONDS * 1000L

    List<String> needles = new ArrayList<String>()
    Map<String, String> needleOfMacro = new LinkedHashMap<String, String>()
    for (String m : MACRO_NAMES) {
        needles.add('%<ac:structured-macro ac:name="' + m.replace('%', '') + '"%')
        needleOfMacro.put(m, '<ac:structured-macro ac:name="' + m + '"')
    }

    String orClause = ''
    Map<String, Object> np = new LinkedHashMap<String, Object>()
    for (int i = 0; i < needles.size(); i++) {
        orClause += (i == 0 ? '' : ' OR ') + ("b.body LIKE :n" + i + " ESCAPE '\\'")
        np.put('n' + i, needles.get(i))
    }

    List<Long> currentHits = new ArrayList<Long>()
    List<Long> draftPageHits = new ArrayList<Long>()
    boolean complete = false
    int nextOffset = SCAN_OFFSET
    long maxId = 0

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.eachRow('SELECT max(contentid) AS mx FROM content') { r ->
            maxId = r['mx'] == null ? 0 : ((Number) r['mx']).longValue()
        }
        long lo = SCAN_OFFSET
        boolean stopped = false
        while (lo <= maxId) {
            if (System.currentTimeMillis() - runStart >= budgetMs) { nextOffset = (int) lo; stopped = true; break }
            long hi = lo + SCAN_CHUNK
            Map<String, Object> p = new LinkedHashMap<String, Object>(np)
            p.put('lo', lo); p.put('hi', hi)
            sql.eachRow('SELECT c.contentid AS cid FROM content c JOIN bodycontent b ON b.contentid = c.contentid ' +
                        "WHERE c.contenttype = 'PAGE' AND c.content_status = 'current' " +
                        'AND c.contentid >= :lo AND c.contentid < :hi AND (' + orClause + ')', p) { r ->
                currentHits.add(((Number) r['cid']).longValue())
            }
            sql.eachRow('SELECT c.draftpageid AS pid FROM content c JOIN bodycontent b ON b.contentid = c.contentid ' +
                        "WHERE c.contenttype = 'PAGE' AND c.content_status = 'draft' AND c.draftpageid IS NOT NULL " +
                        'AND c.contentid >= :lo AND c.contentid < :hi AND (' + orClause + ')', p) { r ->
                try { draftPageHits.add(Long.parseLong(r['pid'] as String)) } catch (Exception ignore) {}
            }
            lo = hi
        }
        if (!stopped) { complete = true; nextOffset = (int) lo }
    }

    LinkedHashSet<Long> pageIds = new LinkedHashSet<Long>()
    for (Long h : currentHits) pageIds.add(h)
    for (Long d : draftPageHits) pageIds.add(d)

    String currentQuery = 'SELECT c.title, c.version, c.content_status, ' +
        'COALESCE(sr.spacekey, sc.spacekey) AS spacekey, b.body ' +
        'FROM content c ' +
        'LEFT JOIN spaces sr ON sr.spaceid = c.spaceid ' +
        'LEFT JOIN content cc ON cc.contentid = COALESCE(c.prevver, c.contentid) ' +
        'LEFT JOIN spaces sc ON sc.spaceid = cc.spaceid ' +
        'LEFT JOIN bodycontent b ON b.contentid = c.contentid ' +
        'WHERE c.contentid = :pid'
    String draftQuery = 'SELECT COALESCE(um.username, d.creator) AS owner, d.creationdate AS created, ' +
        'd.lastmoddate AS lastmod, b.body AS body FROM content d ' +
        'LEFT JOIN user_mapping um ON um.user_key = d.creator LEFT JOIN bodycontent b ON b.contentid = d.contentid ' +
        "WHERE d.content_status = 'draft' AND d.draftpageid = :pidStr"

    StringBuilder table = new StringBuilder()
    table.append('<div style="max-height:').append(SCROLLBOX_MAX_HEIGHT_PX)
         .append('px;overflow:auto;border:1px solid #ccc">')
         .append('<table border="1" cellpadding="4" cellspacing="0" ')
         .append('style="border-collapse:collapse;font-size:90%;white-space:nowrap">')
         .append('<tr><th>Ref.Id</th><th>Space Key</th><th>Space Category</th><th>Page Title</th>')
         .append('<th>Page ID</th><th>Page V.</th><th>Macro</th><th>State</th><th>Draft Owner</th>')
         .append('<th>Draft Modified</th><th>Full URL</th><th>Details</th></tr>')

    int ref = 0, tCurrent = 0, tDraft = 0, tPersonal = 0, tDelete = 0
    Map<String, Integer> perMacroCount = new LinkedHashMap<String, Integer>()

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        for (Long pid : pageIds) {
            String title = '', spaceKey = '', curBody = null
            int pver = 0
            boolean found = false
            sql.eachRow(currentQuery, [pid: pid]) { row ->
                found = true
                title = row['title'] as String
                spaceKey = row['spacekey'] as String
                pver = row['version'] == null ? 0 : ((Number) row['version']).intValue()
                curBody = row['body'] as String
            }
            if (!found) continue

            for (String macro : MACRO_NAMES) {
                String needle = needleOfMacro.get(macro)
                boolean inCurrent = curBody != null && curBody.contains(needle)
                boolean inDraft = false
                String draftOwner = '', draftMod = ''
                sql.eachRow(draftQuery, [pidStr: String.valueOf(pid)]) { row ->
                    String dbody = row['body'] as String
                    if (dbody != null && dbody.contains(needle)) {
                        inDraft = true
                        draftOwner = row['owner'] as String
                        Object ts = row['lastmod'] ?: row['created']
                        if (ts instanceof java.util.Date) draftMod = fmt.format((java.util.Date) ts)
                    }
                }
                if (!inCurrent && !inDraft) continue

                String state = inCurrent ? 'current still has macro' : 'draft holds macro'
                if (inCurrent) { tCurrent++ } else { tDraft++ }
                perMacroCount.put(macro, (perMacroCount.get(macro) ?: 0) + 1)

                String cat = spaceCategory(spaceKey)
                if (cat == 'personal space') tPersonal++
                else if (cat == 'marked for deletion') tDelete++

                ref++
                String url = (spaceKey != null && !spaceKey.isEmpty())
                        ? baseUrl + '/display/' + spaceKey + '/' + pid
                        : baseUrl + '/pages/viewpage.action?pageId=' + pid
                table.append('<tr><td>').append(String.format('%04d', ref))
                     .append('</td><td>').append(htmlEsc(spaceKey))
                     .append('</td><td>').append(htmlEsc(cat))
                     .append('</td><td>').append(htmlEsc(title))
                     .append('</td><td>').append(pid)
                     .append('</td><td>').append(pver)
                     .append('</td><td>').append(htmlEsc(macro))
                     .append('</td><td>').append(htmlEsc(state))
                     .append('</td><td>').append(htmlEsc(draftOwner))
                     .append('</td><td>').append(htmlEsc(draftMod))
                     .append('</td><td><a href="').append(url).append('" target="_blank">')
                     .append(htmlEsc(url)).append('</a>')
                     .append('</td><td>').append(cat.isEmpty() ? '' : 'Excluded: ' + htmlEsc(cat))
                     .append('</td></tr>')
            }
        }
    }
    table.append('</table></div>')

    StringBuilder page = new StringBuilder()
    page.append('<h3>Macro Usage reconciliation (').append(htmlEsc(MACRO_NAMES.join(', '))).append(')</h3>')
    if (!complete) {
        page.append('<p style="font-size:110%;color:#a30"><b>SCAN INCOMPLETE</b> - time budget reached. ')
            .append('Set <b>SCAN_OFFSET = ').append(nextOffset).append('</b> and run again to continue ')
            .append('(results so far are shown below; each run is additive).</p>')
    } else {
        page.append('<p style="font-size:110%;color:#070"><b>SCAN COMPLETE</b> - whole instance covered.</p>')
    }
    page.append(table)
    page.append('<pre style="font-size:90%">SUMMARY (this run\'s window)\n')
    for (String macro : MACRO_NAMES) {
        page.append('  ').append(macro).append(':  ').append(perMacroCount.get(macro) ?: 0).append(' found\n')
    }
    page.append('  TOTAL: ').append(ref).append('  =  ').append(tCurrent)
        .append(' current-miss + ').append(tDraft).append(' draft')
        .append('   (of which ').append(tPersonal).append(' personal, ')
        .append(tDelete).append(' to-be-deleted)\n</pre>')
    page.append('<p style="font-size:85%;color:#666">State legend: ')
        .append('<b>current still has macro</b> = genuine miss, re-run the engine; ')
        .append('<b>draft holds macro</b> = unpublished shared draft (invisible on view), owner publishes/discards. ')
        .append('Anything the admin Macro Usage still counts but is NOT found here is a stale index entry a reindex clears. ')
        .append('Personal ("~") and to-be-deleted (x_y) spaces are reported and counted, not excluded.</p>')
    return page.toString()

} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
