/*
 * Reconcile-MacroUsage.groovy                                    (READ ONLY)
 * -----------------------------------------------------------------------------
 * Explains the leftover counts on the admin "Macro Usage" page after the
 * migration. For each macro name you still see there, it finds the pages the
 * index counts (same CQL the admin page and dosearchsite use: macro="<name>",
 * which sees CURRENT + DRAFT content, not history), then classifies each page
 * against the database:
 *
 *   State:
 *     current still has macro  - a genuine miss; re-run the engine on it
 *     draft holds macro        - the macro lives in an unpublished shared
 *                                draft (invisible when you just view the page);
 *                                owner must publish or discard it
 *     stale index              - macro is in neither current nor draft row;
 *                                it was replaced but the search index lagged;
 *                                a content reindex clears the count
 *   Space Category (Details):
 *     personal space           - space key starts with '~'
 *     marked for deletion      - key matches [a-zA-Z0-9]+_[a-zA-Z0-9]+
 *     (blank)                  - normal, actionable space
 *
 * Every hit is reported (nothing excluded), so the row count reconciles with
 * the admin figure. A footer breaks the totals down.
 *
 * Input: MACRO_NAMES (what you see in Macro Usage). PAGE_IDS_OVERRIDE optional -
 * when non-empty, those pages are classified for every listed macro instead of
 * running the CQL search.
 */
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.searchfilter.SpacePermissionsSearchFilter
import com.atlassian.confluence.search.v2.query.ContentTypeQuery
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field
import java.text.SimpleDateFormat

// ============================== CONFIG =======================================

@Field List<String> MACRO_NAMES = []              // e.g. ['last-modified', 'priority-status']
@Field List<Long> PAGE_IDS_OVERRIDE = []          // optional: classify these instead of CQL
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field int SCROLLBOX_MAX_HEIGHT_PX = 600

// =============================================================================

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

/** Space category from the key: '~' => personal (wins), else x_y => to-be-deleted, else ''. */
String spaceCategory(String key) {
    if (key == null) return ''
    if (key.startsWith('~')) return 'personal space'
    if (key.matches('[a-zA-Z0-9]+_[a-zA-Z0-9]+')) return 'marked for deletion'
    return ''
}

/** CQL page ids for one macro name, via the index (same view as the admin page). */
List<Long> cqlPageIds(String macroName) {
    List<Long> ids = new ArrayList<Long>()
    try {
        // CQL search through the REST-equivalent service; macro = "<name>"
        def cqlSearch = ComponentLocator.getComponent(
                Class.forName('com.atlassian.confluence.api.service.search.CQLSearchService'))
        def limit = com.atlassian.confluence.api.model.pagination.LimitedRequestImpl.create(100, 100)
        String cql = 'macro = "' + macroName.replace('"', '') + '"'
        def resp = cqlSearch.searchContent(cql, limit, new com.atlassian.confluence.api.model.Expansion[0])
        boolean more = true
        while (more) {
            resp.getResults().each { c ->
                try { ids.add(Long.parseLong(c.getId().serialise())) } catch (Exception ignore) {}
            }
            if (resp.getPageResponse() != null && resp.getPageResponse().hasMore()) {
                def next = com.atlassian.confluence.api.model.pagination.LimitedRequestImpl.create(
                        (int) (ids.size()), 100)
                resp = cqlSearch.searchContent(cql, next, new com.atlassian.confluence.api.model.Expansion[0])
            } else {
                more = false
            }
        }
    } catch (Exception e) {
        throw new RuntimeException('CQL search failed for "' + macroName + '": ' + e.getMessage(), e)
    }
    return ids
}

try {
    if (MACRO_NAMES.isEmpty()) return '<pre>Set MACRO_NAMES first.</pre>'

    SettingsManager settingsManager = ComponentLocator.getComponent(SettingsManager)
    String baseUrl = settingsManager.getGlobalSettings().getBaseUrl()
    SimpleDateFormat fmt = new SimpleDateFormat('yyyy-MM-dd HH:mm')

    // per-page classification queries (built outside the withSql closure)
    String currentQuery = '''
        SELECT c.contentid, c.title, c.content_status, s.spacekey, b.body
        FROM content c
        LEFT JOIN spaces s ON s.spaceid = c.spaceid
        LEFT JOIN bodycontent b ON b.contentid = c.contentid
        WHERE c.contentid = :pid
    '''
    String draftQuery = '''
        SELECT d.contentid AS did, d.creationdate AS created, d.lastmoddate AS lastmod,
               COALESCE(um.username, d.creator) AS owner, b.body AS body
        FROM content d
        LEFT JOIN user_mapping um ON um.user_key = d.creator
        LEFT JOIN bodycontent b ON b.contentid = d.contentid
        WHERE d.content_status = 'draft' AND d.draftpageid = :pidStr
    '''

    StringBuilder table = new StringBuilder()
    table.append('<div style="max-height:').append(SCROLLBOX_MAX_HEIGHT_PX)
         .append('px;overflow:auto;border:1px solid #ccc">')
         .append('<table border="1" cellpadding="4" cellspacing="0" ')
         .append('style="border-collapse:collapse;font-size:90%;white-space:nowrap">')
         .append('<tr><th>Ref.Id</th><th>Space Key</th><th>Space Category</th><th>Page Title</th>')
         .append('<th>Page ID</th><th>Macro</th><th>State</th><th>Draft Owner</th>')
         .append('<th>Draft Modified</th><th>Full URL</th><th>Details</th></tr>')

    StringBuilder footer = new StringBuilder()
    int gTotal = 0, gCurrent = 0, gDraft = 0, gStale = 0, gPersonal = 0, gDelete = 0

    for (String macro : MACRO_NAMES) {
        String needle = 'ac:name="' + macro + '"'
        List<Long> pageIds = PAGE_IDS_OVERRIDE.isEmpty() ? cqlPageIds(macro) : PAGE_IDS_OVERRIDE
        int refCounter = 0
        int mTotal = 0, mCurrent = 0, mDraft = 0, mStale = 0, mPersonal = 0, mDelete = 0

        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            for (Long pid : pageIds) {
                String title = '', spaceKey = '', curBody = null
                boolean found = false
                sql.eachRow(currentQuery, [pid: pid]) { row ->
                    found = true
                    title = row['title'] as String
                    spaceKey = row['spacekey'] as String
                    curBody = row['body'] as String
                }
                if (!found) continue

                boolean inCurrent = curBody != null && curBody.contains(needle)
                // draft check
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

                String state
                if (inCurrent)   { state = 'current still has macro'; mCurrent++ }
                else if (inDraft){ state = 'draft holds macro';       mDraft++ }
                else             { state = 'stale index';             mStale++ }

                String cat = spaceCategory(spaceKey)
                if (cat == 'personal space') mPersonal++
                else if (cat == 'marked for deletion') mDelete++

                refCounter++
                mTotal++
                String url = baseUrl + '/pages/viewpage.action?pageId=' + pid
                table.append('<tr><td>').append(macro).append('-').append(String.format('%03d', refCounter))
                     .append('</td><td>').append(htmlEsc(spaceKey))
                     .append('</td><td>').append(htmlEsc(cat))
                     .append('</td><td>').append(htmlEsc(title))
                     .append('</td><td>').append(pid)
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

        footer.append('  ').append(macro).append(':  total ').append(mTotal)
              .append('  =  ').append(mCurrent).append(' current-miss + ')
              .append(mDraft).append(' draft + ').append(mStale).append(' stale-index')
              .append('   (of which ').append(mPersonal).append(' personal, ')
              .append(mDelete).append(' to-be-deleted)\n')
        gTotal += mTotal; gCurrent += mCurrent; gDraft += mDraft; gStale += mStale
        gPersonal += mPersonal; gDelete += mDelete
    }

    table.append('</table></div>')

    StringBuilder page = new StringBuilder()
    page.append('<h3>Macro Usage reconciliation (').append(htmlEsc(MACRO_NAMES.join(', '))).append(')</h3>')
    if (!PAGE_IDS_OVERRIDE.isEmpty()) {
        page.append('<p style="font-size:90%">Using PAGE_IDS_OVERRIDE (')
            .append(PAGE_IDS_OVERRIDE.size()).append(' ids) instead of CQL search.</p>')
    }
    page.append(table)
    page.append('<pre style="font-size:90%">SUMMARY\n').append(htmlEsc(footer.toString()))
        .append('  TOTAL:  ').append(gTotal).append('  =  ').append(gCurrent)
        .append(' current-miss + ').append(gDraft).append(' draft + ').append(gStale)
        .append(' stale-index   (of which ').append(gPersonal).append(' personal, ')
        .append(gDelete).append(' to-be-deleted)\n</pre>')
    page.append('<p style="font-size:85%;color:#666">State legend: ')
        .append('<b>current still has macro</b> = genuine miss, re-run the engine; ')
        .append('<b>draft holds macro</b> = unpublished shared draft (invisible on view), owner publishes/discards; ')
        .append('<b>stale index</b> = replaced but the index lagged, a content reindex clears the count. ')
        .append('Personal ("~") and to-be-deleted (x_y) spaces are reported and counted, not excluded.</p>')
    return page.toString()

} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
