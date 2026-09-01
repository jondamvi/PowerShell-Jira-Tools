/*
 * Explain-Scope.groovy                                          (READ ONLY)
 * -----------------------------------------------------------------------------
 * Answers "why is this page in scope" for the replacement engine, without
 * touching the engine. For each page id, every version body (current + history)
 * is tested with the SAME two tests discovery uses:
 *   - a source macro NAME matches when the body contains  ac:name="<name>"
 *   - an EDD SET-ID matches when the body contains the GUID as a substring
 * For every hit the raw <ac:structured-macro ...> element around it is printed
 * verbatim (name + all parameters) - exactly what Stage-1's parser is asked to
 * recognize. A hit that sits outside any structured-macro element is flagged.
 *
 * Fill PAGE_IDS from SCOPE's "Affected page ids" box. SOURCE_NAMES = the source
 * macro names of your Migrations (or just the ones you suspect); SET_IDS = the
 * set-id GUIDs of EDD-source migrations.
 */
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

// ============================== CONFIG =======================================

@Field List<Long> PAGE_IDS = []                   // e.g. [274932982L, 88993388L]
@Field List<String> SOURCE_NAMES = []             // e.g. ['process-status', 'easy-dropdown-menu-status']
@Field List<String> SET_IDS = []                  // e.g. ['0b7c...-guid']
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field int SNIPPET_MAX = 900

// =============================================================================

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

/** The structured-macro element enclosing offset 'at', or a window around it. */
String elementAround(String body, int at) {
    int start = body.lastIndexOf('<ac:structured-macro', at)
    int end = body.indexOf('</ac:structured-macro>', at)
    if (start >= 0 && end > start) {
        int stop = Math.min(end + '</ac:structured-macro>'.length(), start + SNIPPET_MAX)
        return body.substring(start, stop)
    }
    return '[NOT inside a structured-macro element] ...' +
           body.substring(Math.max(0, at - 200), Math.min(body.length(), at + 200)) + '...'
}

try {
    List<String> needles = new ArrayList<String>()     // parallel lists: needle text, label
    List<String> labels = new ArrayList<String>()
    for (String n : SOURCE_NAMES) { needles.add('ac:name="' + n + '"'); labels.add('macro ' + n) }
    for (String g : SET_IDS)      { needles.add(g);                       labels.add('set-id ' + g) }

    String versionsQuery = '''
        SELECT c.contentid, c.version, c.content_status, c.prevver, b.body
        FROM content c JOIN bodycontent b ON b.contentid = c.contentid
        WHERE c.contentid = :pid OR c.prevver = :pid
        ORDER BY (c.prevver IS NULL) DESC, c.version DESC
    '''

    StringBuilder out = new StringBuilder('<pre style="font-size:88%">')
    if (needles.isEmpty()) return '<pre>Fill SOURCE_NAMES and/or SET_IDS first.</pre>'

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        for (Long pid : PAGE_IDS) {
            out.append('================ PAGE ').append(pid).append(' ================\n')
            int hitsOnPage = 0
            sql.eachRow(versionsQuery, [pid: pid]) { row ->
                String body = row['body'] as String
                if (body == null) return
                String where = 'contentid ' + row['contentid'] + ' v' + row['version'] +
                               (row['prevver'] == null ? ' (current, ' : ' (history, ') + row['content_status'] + ')'
                for (int k = 0; k < needles.size(); k++) {
                    int from = 0
                    while (true) {
                        int at = body.indexOf(needles.get(k), from)
                        if (at < 0) break
                        hitsOnPage++
                        out.append('-- ').append(where).append('  matched by ').append(labels.get(k))
                           .append('  @').append(at).append('\n')
                           .append(htmlEsc(elementAround(body, at))).append('\n\n')
                        from = at + needles.get(k).length()
                    }
                }
            }
            if (hitsOnPage == 0) {
                out.append('   no configured name or set-id found in ANY version body\n')
                   .append('   -> discovery matched something not in SOURCE_NAMES/SET_IDS; add the rest of the Migrations names\n\n')
            }
        }
    }
    out.append('</pre>')
    return out.toString()
} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
