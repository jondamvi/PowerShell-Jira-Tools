/*
 * SCRIPT A - DISCOVERY (READ ONLY)
 *
 * Finds every page whose CURRENT or HISTORICAL body still contains the macro.
 * Runs a single SELECT. No writes, no outage, nothing to restart.
 *
 * Why SQL and not CQL: cqlSearchService queries Lucene, and Confluence indexes
 * only current versions. A page already fixed in its current version but still
 * carrying the macro in history is invisible to CQL - that is exactly the
 * population this script exists to find.
 *
 * Prereq: ScriptRunner -> Resources -> Database Connection pointing at the
 * Confluence DB. Set DB_RESOURCE to its name.
 *
 * Feed the CURRENT PAGE IDs it prints into Script B.
 */

import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'
final String MACRO_NAME  = 'qualification-table'
// ================================================================

final String pattern = '%ac:name="' + MACRO_NAME + '"%'
String baseUrl = ComponentLocator.getComponent(SettingsManager).getGlobalSettings().getBaseUrl()

// prevver IS NULL  -> this row IS the current version
// prevver NOT NULL -> historical row; prevver holds the current version's id
final String QUERY = '''
    SELECT c.contentid        AS rowid,
           c.prevver          AS prevver,
           c.version          AS versionnum,
           c.title            AS title,
           c.contenttype      AS contenttype,
           c.content_status   AS statuscol,
           s.spacekey         AS spacekey
    FROM content c
    JOIN bodycontent bc ON bc.contentid = c.contentid
    LEFT JOIN spaces s  ON s.spaceid = c.spaceid
    WHERE c.contenttype IN ('PAGE','BLOGPOST')
      AND bc.body LIKE :pattern
    ORDER BY s.spacekey, c.title, c.version
'''

// group key = the current version's id, whichever row we are looking at
Map<Long, Map<String, Object>> pages = new LinkedHashMap<Long, Map<String, Object>>()
int rowCount = 0

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
    sql.eachRow(QUERY, [pattern: pattern]) { GroovyRowResult row ->
        rowCount++
        Long rowId   = (row['rowid'] as Number).longValue()
        Object prev  = row['prevver']
        boolean isCurrent = (prev == null)
        Long currentId = isCurrent ? rowId : (prev as Number).longValue()

        Map<String, Object> entry = pages.get(currentId)
        if (entry == null) {
            entry = new LinkedHashMap<String, Object>()
            entry.put('title', row['title'])
            entry.put('space', row['spacekey'])
            entry.put('type', row['contenttype'])
            entry.put('status', row['statuscol'])
            entry.put('currentHit', Boolean.FALSE)
            entry.put('histVersions', new ArrayList<String>())
            pages.put(currentId, entry)
        }
        if (isCurrent) {
            entry.put('currentHit', Boolean.TRUE)
            entry.put('title', row['title'])
            entry.put('space', row['spacekey'])
        } else {
            ((List<String>) entry.get('histVersions')).add(row['versionnum'] as String)
        }
    }
}

int currentOnly = 0, historyOnly = 0, both = 0
StringBuilder html = new StringBuilder()
StringBuilder ids  = new StringBuilder()

html.append('<p>Macro: <b>').append(MACRO_NAME).append('</b> &middot; matching bodycontent rows: ')
    .append(rowCount).append(' &middot; distinct pages: ').append(pages.size()).append('</p>')
html.append('<table border="1" cellpadding="4" cellspacing="0"><tr>')
    .append('<th>Current page id</th><th>Space</th><th>Type</th><th>Status</th><th>Title</th>')
    .append('<th>In current?</th><th>Historical versions with macro</th><th>Link</th></tr>')

for (Map.Entry<Long, Map<String, Object>> e : pages.entrySet()) {
    Long pid = e.getKey()
    Map<String, Object> v = e.getValue()
    boolean cur = (Boolean) v.get('currentHit')
    List<String> hist = (List<String>) v.get('histVersions')

    if (cur && hist.isEmpty()) currentOnly++
    else if (!cur && !hist.isEmpty()) historyOnly++
    else both++

    ids.append(pid).append('L, ')
    html.append('<tr><td>').append(pid)
        .append('</td><td>').append(v.get('space'))
        .append('</td><td>').append(v.get('type'))
        .append('</td><td>').append(v.get('status'))
        .append('</td><td>').append(((String) v.get('title'))?.replace('<', '&lt;'))
        .append('</td><td>').append(cur ? 'YES' : 'no (already flattened)')
        .append('</td><td>').append(hist.isEmpty() ? '-' : hist.join(', '))
        .append('</td><td><a href="').append(baseUrl).append('/pages/viewpage.action?pageId=').append(pid)
        .append('" target="_blank">open</a></td></tr>')
}
html.append('</table>')
html.append('<p>current only: ').append(currentOnly)
    .append(' &middot; history only: ').append(historyOnly)
    .append(' &middot; both: ').append(both).append('</p>')
html.append('<p>Paste into Script B PAGE_IDS:</p><pre>[')
    .append(ids.length() > 2 ? ids.substring(0, ids.length() - 2) : '')
    .append(']</pre>')

log.warn("Discovery ${MACRO_NAME}: ${rowCount} bodycontent rows across ${pages.size()} pages " +
         "(currentOnly=${currentOnly}, historyOnly=${historyOnly}, both=${both})")

return html.toString()
