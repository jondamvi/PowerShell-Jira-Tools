import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

@Field String DB_RESOURCE = 'ConfluenceDB'   // same resource name v2 uses
@Field String SPACE_KEY = 'SPACEKEY'

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

StringBuilder out = new StringBuilder()
out.append('<h3>Duplicate page titles in ').append(htmlEsc(SPACE_KEY)).append('</h3>')
out.append('<table border="1" cellpadding="4" cellspacing="0">')
out.append('<tr><th>Title</th><th>Count</th><th>Content IDs (status)</th></tr>')

int dupTitles = 0
try {
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        String q = '''
            SELECT c.title AS title,
                   COUNT(*) AS n,
                   string_agg(c.contentid || ' (' || c.content_status || ')', ', '
                              ORDER BY c.contentid) AS ids
            FROM content c
            JOIN spaces s ON s.spaceid = c.spaceid
            WHERE s.spacekey = :sk
              AND c.contenttype = 'PAGE'
              AND c.prevver IS NULL
              AND c.content_status IN ('current', 'draft')
            GROUP BY c.title
            HAVING COUNT(*) > 1
            ORDER BY COUNT(*) DESC, c.title
        '''
        sql.eachRow(q, [sk: SPACE_KEY]) { row ->
            dupTitles++
            out.append('<tr><td>').append(htmlEsc(row['title']))
               .append('</td><td>').append(row['n'])
               .append('</td><td>').append(htmlEsc(row['ids'])).append('</td></tr>')
        }
    }
} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}

out.append('</table>')
out.append('<p><b>').append(dupTitles).append('</b> duplicated title(s). ')
out.append(dupTitles > 0
    ? 'Each needs one page retitled - these also block the Cloud migration itself.'
    : 'No duplicates - the diagnosis is wrong; report this back.')
return out.toString()