import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
DatabaseUtil.withSql('ConfluenceDB') { Sql sql ->
    def out = new StringBuilder()
    sql.eachRow('''SELECT c.content_status, c.contenttype,
                     (length(b.body) - length(replace(b.body, 'ac:name="last-modified"', ''))) / length('ac:name="last-modified"') AS exact_hits,
                     (length(b.body) - length(replace(b.body, 'last-modified', ''))) / length('last-modified') AS loose_hits
                   FROM content c JOIN bodycontent b ON b.contentid = c.contentid
                   WHERE c.contentid = :pid''', [pid: 123456L]) { r ->
        out.append('status=').append(r['content_status']).append(' type=').append(r['contenttype'])
           .append(' exact=').append(r['exact_hits']).append(' loose=').append(r['loose_hits']).append('\n')
    }
    return "<pre>"+out.toString()+"</pre>"
}