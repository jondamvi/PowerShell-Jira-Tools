import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
DatabaseUtil.withSql('ConfluenceDB') { Sql sql ->
    def out = new StringBuilder()
    String needle = 'ac:name="last-modified"'   // <-- your real target macro name
    sql.eachRow('''SELECT c.contentid, c.content_status,
                          position(:needle in b.body) AS at,
                          length(b.body) AS len
                   FROM content c JOIN bodycontent b ON b.contentid = c.contentid
                   WHERE c.contentid = :pid''', [pid: 123456L, needle: needle]) { r ->
        out.append('status=').append(r['content_status'])
           .append(' bodylen=').append(r['len'])
           .append(' needle_at=').append(r['at']).append('\n')
    }
    return "<pre>" + out.toString() + "</pre>"
}