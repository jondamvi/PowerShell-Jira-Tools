import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
DatabaseUtil.withSql('ConfluenceDB') { Sql sql ->
    def out = new StringBuilder()
    sql.eachRow('''SELECT c.contentid, c.content_status, c.contenttype,
                          position('ac:name' in b.body) AS has_acname,
                          substring(b.body from position('ac:name' in b.body) for 60) AS sample
                   FROM content c JOIN bodycontent b ON b.contentid = c.contentid
                   WHERE c.contentid = :pid''', [pid: 123456L]) { r ->
        out.append(r['content_status']).append(' / ').append(r['contenttype'])
           .append(' / acname@').append(r['has_acname'])
           .append(' / ').append(r['sample']).append('\n')
    }
    return "<pre>" + out.toString().replace('<','&lt;') + "</pre>"
}