/*
 * Which version rows have no space, and what do their pages look like? READ ONLY.
 *
 * The previous version of this script listed only the rows with spaceid IS NULL,
 * which made it impossible to tell whether the space-less row was the current
 * version or one of its history rows. This one works page by page:
 *
 *   1. find every content row with spaceid IS NULL carrying the macro
 *   2. resolve each to its page (prevver, or itself when it is the current row)
 *   3. dump EVERY version row of that page - current and historical - showing
 *      contentid, version, prevver, spaceid and status side by side
 *
 * So a page whose current version is 29 but whose v1 row lost its space is
 * immediately visible, which the flat listing could not show.
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql

final String DB_RESOURCE = 'ConfluenceDB'
final String MACRO_NAME  = 'qualification-table'

// Optional: restrict to specific page ids taken from the engine's FAILED PAGES
// output. Empty = every page that has at least one space-less row.
final List<Long> PAGE_IDS = []

final int MAX_PAGES = 100

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return '(null)'
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

// ---- step 1 + 2: which pages are affected -----------------------------------
Set<Long> affected = new LinkedHashSet<Long>()
int nullRows = 0

Map<String, Object> p1 = new LinkedHashMap<String, Object>()
p1.put('pattern', '%ac:name="' + MACRO_NAME + '"%')

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
    sql.eachRow('''SELECT c.contentid AS cid, c.prevver AS prevver
                   FROM content c
                   JOIN bodycontent bc ON bc.contentid = c.contentid
                   WHERE bc.body LIKE :pattern AND c.spaceid IS NULL''', p1) { row ->
        nullRows++
        Object prev = row['prevver']
        long owner = (prev == null) ? ((Number) row['cid']).longValue() : ((Number) prev).longValue()
        affected.add(owner)
    }
}

List<Long> targets = new ArrayList<Long>()
for (Long a : affected) {
    if (PAGE_IDS.isEmpty() || PAGE_IDS.contains(a)) targets.add(a)
}

outp.append('Rows with spaceid IS NULL carrying "').append(MACRO_NAME).append('": ').append(nullRows).append('\n')
outp.append('Distinct pages affected: ').append(affected.size()).append('\n')
outp.append('Pages examined below: ').append(Math.min(targets.size(), MAX_PAGES)).append('\n')
outp.append('================================================================\n\n')

// ---- step 3: full version listing per affected page -------------------------
int shown = 0
DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
    for (Long pid : targets) {
        if (shown >= MAX_PAGES) break
        shown++

        Map<String, Object> p2 = new LinkedHashMap<String, Object>()
        p2.put('pid', pid)
        p2.put('pattern', '%ac:name="' + MACRO_NAME + '"%')

        outp.append('---------------- page ').append(pid).append('\n')
        outp.append(String.format('   %-14s %-7s %-14s %-10s %-9s %-6s %s%n',
                'CONTENTID', 'VERSION', 'PREVVER', 'SPACEID', 'STATUS', 'MACRO', 'TITLE'))

        sql.eachRow('''SELECT c.contentid AS cid, c.version AS ver, c.prevver AS prevver,
                              c.spaceid AS spaceid, c.content_status AS st, c.title AS title,
                              c.contenttype AS ctype, c.pageid AS parentid,
                              CASE WHEN bc.body LIKE :pattern THEN 1 ELSE 0 END AS hasmacro
                       FROM content c
                       LEFT JOIN bodycontent bc ON bc.contentid = c.contentid
                       WHERE c.contentid = :pid OR c.prevver = :pid
                       ORDER BY c.version''', p2) { row ->
            boolean isCurrent = (row['prevver'] == null)
            outp.append(String.format('   %-14s %-7s %-14s %-10s %-9s %-6s %s%n',
                    row['cid'] as String,
                    (row['ver'] as String) + (isCurrent ? '*' : ''),
                    row['prevver'] == null ? '(current)' : row['prevver'] as String,
                    row['spaceid'] == null ? 'NULL' : row['spaceid'] as String,
                    row['st'] as String,
                    ((row['hasmacro'] as Number).intValue() == 1) ? 'yes' : '-',
                    esc(row['title'])))
        }
        outp.append('\n')
    }
}

outp.append('================================================================\n')
outp.append('  * marks the current version.\n')
outp.append('  SPACEID = NULL is the row that makes Page.getSpace() return null and\n')
outp.append('  Confluence throw NPE on save. Compare which version numbers are NULL\n')
outp.append('  against the versions the engine reported as failing.\n\n')
outp.append('  If the NULL rows are historical only, the pages themselves are healthy\n')
outp.append('  and only those history rows are unreachable.\n')
outp.append('  If the current row is NULL, the page is genuinely orphaned - check\n')
outp.append('  whether it is reachable in the UI at all before deciding to remediate.\n')

log.warn("No-space diagnosis: ${nullRows} null-space rows across ${affected.size()} pages")
return '<pre>' + outp.toString() + '</pre>'
