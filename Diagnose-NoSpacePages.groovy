/*
 * What are the pages with no space? READ ONLY.
 *
 * The replacement engine skips these because Confluence's own save and
 * version-history paths dereference the space and throw NullPointerException.
 * This tells you what they actually are, so you can decide whether they need
 * remediating at all.
 *
 * Usual culprits, in rough order of likelihood:
 *   - content whose space was deleted, leaving orphaned rows
 *   - drafts (content_status = 'draft'), which have no space until first save
 *   - content in the trash of a purged space
 *   - personal-space content where the user was removed
 *
 * If they are orphaned or trashed, they will not migrate to Cloud and can be
 * left alone. If they are live pages, they need fixing before remediation.
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql

final String DB_RESOURCE = 'ConfluenceDB'
final String MACRO_NAME = 'qualification-table'

// Optional: paste the failing page ids from the engine's FAILED PAGES output to
// examine exactly those. Empty = every space-less page carrying the macro.
final List<Long> PAGE_IDS = []

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return '(null)'
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

Map<String, Object> params = new LinkedHashMap<String, Object>()
params.put('pattern', '%ac:name="' + MACRO_NAME + '"%')

StringBuilder q = new StringBuilder()
q.append('''
    SELECT c.contentid      AS contentid,
           c.prevver        AS prevver,
           c.version        AS version,
           c.title          AS title,
           c.contenttype    AS contenttype,
           c.content_status AS statuscol,
           c.spaceid        AS spaceid,
           c.pageid         AS parentid,
           c.creator        AS creator,
           c.creationdate   AS created,
           c.lastmoddate    AS modified
    FROM content c
    JOIN bodycontent bc ON bc.contentid = c.contentid
    WHERE bc.body LIKE :pattern
      AND c.spaceid IS NULL
''')

if (!PAGE_IDS.isEmpty()) {
    List<String> ph = new ArrayList<String>()
    for (int i = 0; i < PAGE_IDS.size(); i++) { ph.add(':p' + i); params.put('p' + i, PAGE_IDS.get(i)) }
    q.append('      AND (c.contentid IN (').append(ph.join(', '))
     .append(') OR c.prevver IN (').append(ph.join(', ')).append(')) ')
}
q.append(' ORDER BY c.content_status, c.contenttype, c.contentid')

Map<String, Integer> byStatus = new LinkedHashMap<String, Integer>()
int rows = 0

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
    sql.eachRow(q.toString(), params) { row ->
        rows++
        String key = (row['contenttype'] as String) + ' / ' + (row['statuscol'] as String)
        byStatus.put(key, (byStatus.get(key) == null ? 0 : byStatus.get(key)) + 1)

        outp.append('  contentid=').append(row['contentid'])
            .append('  v').append(row['version'])
            .append('  ').append(row['prevver'] == null ? 'CURRENT' : 'historical of ' + row['prevver'])
            .append('\n    type=').append(row['contenttype'])
            .append('  status=').append(row['statuscol'])
            .append('  parent=').append(row['parentid'])
            .append('\n    title=').append(esc(row['title']))
            .append('\n    created=').append(row['created'])
            .append('  modified=').append(row['modified'])
            .append('\n\n')
    }
}

StringBuilder head = new StringBuilder()
head.append('Space-less content containing "').append(MACRO_NAME).append('"\n')
head.append('================================================================\n')
head.append('  rows: ').append(rows).append('\n')
for (Map.Entry<String, Integer> e : byStatus.entrySet()) {
    head.append('    ').append(String.format('%-30s %s', e.getKey(), e.getValue() as String)).append('\n')
}
head.append('\n')
head.append('  content_status meanings: current = live, draft = unsaved editor draft,\n')
head.append('  deleted = in trash. Anything not "current" does not need remediation.\n\n')

log.warn("No-space diagnosis: ${rows} row(s) carrying ${MACRO_NAME}")
return '<pre>' + esc(head.toString() + outp.toString()) + '</pre>'
