/*
 * Probe-EddOccurrences.groovy                                    (READ ONLY)
 * -----------------------------------------------------------------------------
 * For each page id: every version body (current + history) is searched for
 * SET_ID; each hit is printed with the raw <ac:structured-macro ...> element
 * it sits in (name + all parameters, verbatim storage). Shows exactly what the
 * parser is being asked to match. Paste the six ids from SCOPE.
 */
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

@Field List<Long> PAGE_IDS = []                 // e.g. [123L, 456L]
@Field String SET_ID = ''                       // the GUID configured in v3
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field int SNIPPET_MAX = 700

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

try {
    StringBuilder out = new StringBuilder('<pre style="font-size:88%">')
    String versionsQuery = '''
        SELECT c.contentid, c.version, c.content_status, c.prevver, b.body
        FROM content c JOIN bodycontent b ON b.contentid = c.contentid
        WHERE c.contentid = :pid OR c.prevver = :pid
        ORDER BY c.version
    '''
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        for (Long pid : PAGE_IDS) {
            out.append('================ PAGE ').append(pid).append(' ================\n')
            int hitsOnPage = 0
            sql.eachRow(versionsQuery, [pid: pid]) { row ->
                String body = row['body'] as String
                if (body == null) return
                int from = 0
                while (true) {
                    int at = body.indexOf(SET_ID, from)
                    if (at < 0) break
                    hitsOnPage++
                    int start = body.lastIndexOf('<ac:structured-macro', at)
                    int end = body.indexOf('</ac:structured-macro>', at)
                    String snippet
                    if (start >= 0 && end > start) {
                        snippet = body.substring(start, Math.min(end + '</ac:structured-macro>'.length(), start + SNIPPET_MAX))
                    } else {
                        snippet = body.substring(Math.max(0, at - 250), Math.min(body.length(), at + 250))
                    }
                    out.append('-- contentid ').append(row['contentid'])
                       .append(' v').append(row['version'])
                       .append(' (').append(row['content_status']).append(row['prevver'] == null ? ', current' : ', history')
                       .append(') offset ').append(at)
                       .append(start >= 0 && end > start ? '' : '   [GUID NOT inside a structured-macro element]')
                       .append('\n').append(htmlEsc(snippet)).append('\n\n')
                    from = at + SET_ID.length()
                }
            }
            if (hitsOnPage == 0) out.append('   (SET_ID not found in any version body)\n\n')
        }
    }
    out.append('</pre>')
    return out.toString()
} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
