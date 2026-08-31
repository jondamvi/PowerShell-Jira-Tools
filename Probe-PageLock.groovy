/*
 * Probe-PageLock.groovy
 * -----------------------------------------------------------------------------
 * Diagnostic: for each configured page id, dump every signal that could
 * indicate "locked for writing / unreconciled":
 *   A. the page's own content row (key columns)
 *   B. draft rows linked via the legacy columns: draftpageid = pageId
 *      (drafttype included - 'shared' = collaborative draft)
 *   C. draft rows linked by same (spaceid, title) - the heuristic the audit
 *      script currently uses, shown for comparison
 *   D. contentproperties of the page row and of every draft row found
 *   E. Confluence API's own answer: does ContentService see DRAFT-status
 *      content for this id (dynamic dispatch - analyzer-proof)
 *
 * Run with a few KNOWN-LOCKED ids (from the APPLY FAILED list) and a few
 * KNOWN-CLEAN ids. The signal that differs between the two groups is the
 * accurate detector; the audit script gets upgraded to use it.
 * Read-only.
 */
import com.atlassian.confluence.api.model.content.ContentStatus
import com.atlassian.confluence.api.model.content.id.ContentId
import com.atlassian.confluence.api.service.content.ContentService
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field
import org.codehaus.groovy.runtime.InvokerHelper

// ============================== CONFIG =======================================

@Field List<Long> PAGE_IDS = [88993388L]        // known-locked and known-clean ids
@Field String DB_RESOURCE = 'ConfluenceDB'

// =============================================================================

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String cut(Object v, int max) {
    String s = v == null ? '' : v.toString()
    return s.length() <= max ? s : s.substring(0, max) + '...'
}

try {
    StringBuilder out = new StringBuilder()
    out.append('<pre style="font-size:90%">')

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        for (Long pid : PAGE_IDS) {
            out.append('==============================================================\n')
            out.append('PAGE ').append(pid).append('\n')
            out.append('==============================================================\n')

            // ---- A: the page's own row -------------------------------------
            out.append('A. own content row:\n')
            sql.eachRow('''SELECT c.contentid, c.title, c.version, c.content_status,
                                  c.prevver, c.spaceid, c.draftpageid, c.drafttype,
                                  c.draftspacekey, c.creationdate, c.lastmoddate
                           FROM content c WHERE c.contentid = :pid''', [pid: pid]) { r ->
                out.append('   status=').append(r['content_status'])
                   .append(' v=').append(r['version'])
                   .append(' spaceid=').append(r['spaceid'])
                   .append(' prevver=').append(r['prevver'])
                   .append(' draftpageid=').append(r['draftpageid'])
                   .append(' drafttype=').append(r['drafttype'])
                   .append(' title=').append(htmlEsc(cut(r['title'], 60)))
                   .append(' lastmod=').append(r['lastmoddate']).append('\n')
            }

            // ---- B: drafts linked via draftpageid ---------------------------
            out.append('B. draft rows with draftpageid = ').append(pid).append(':\n')
            int b = 0
            sql.eachRow('''SELECT c.contentid, c.title, c.version, c.content_status,
                                  c.drafttype, c.draftspacekey, c.creationdate, c.lastmoddate
                           FROM content c
                           WHERE c.content_status = 'draft'
                             AND c.draftpageid = :pids''', [pids: pid.toString()]) { r ->
                b++
                out.append('   draftid=').append(r['contentid'])
                   .append(' v=').append(r['version'])
                   .append(' drafttype=').append(r['drafttype'])
                   .append(' title=').append(htmlEsc(cut(r['title'], 60)))
                   .append(' created=').append(r['creationdate'])
                   .append(' lastmod=').append(r['lastmoddate']).append('\n')
            }
            if (b == 0) out.append('   (none)\n')

            // ---- C: drafts linked by same (spaceid, title) ------------------
            out.append('C. draft rows with same spaceid+title (current heuristic):\n')
            int cCount = 0
            sql.eachRow('''SELECT d.contentid, d.version, d.drafttype, d.creationdate
                           FROM content cur
                           JOIN content d ON d.spaceid = cur.spaceid
                                         AND d.title = cur.title
                                         AND d.content_status = 'draft'
                           WHERE cur.contentid = :pid''', [pid: pid]) { r ->
                cCount++
                out.append('   draftid=').append(r['contentid'])
                   .append(' v=').append(r['version'])
                   .append(' drafttype=').append(r['drafttype'])
                   .append(' created=').append(r['creationdate']).append('\n')
            }
            if (cCount == 0) out.append('   (none)\n')

            // ---- D: contentproperties of page + linked drafts ---------------
            out.append('D. contentproperties (page + draftpageid-linked drafts):\n')
            int d = 0
            sql.eachRow('''SELECT cp.contentid, cp.propertyname, cp.stringval, cp.longval, cp.dateval
                           FROM contentproperties cp
                           WHERE cp.contentid = :pid
                              OR cp.contentid IN (SELECT c.contentid FROM content c
                                                  WHERE c.content_status = 'draft'
                                                    AND c.draftpageid = :pids)
                           ORDER BY cp.contentid, cp.propertyname''',
                        [pid: pid, pids: pid.toString()]) { r ->
                d++
                out.append('   [').append(r['contentid']).append('] ')
                   .append(r['propertyname']).append(' = ')
                   .append(htmlEsc(cut(r['stringval'], 120)))
                if (r['longval'] != null) out.append(' long=').append(r['longval'])
                if (r['dateval'] != null) out.append(' date=').append(r['dateval'])
                out.append('\n')
            }
            if (d == 0) out.append('   (none)\n')

            // ---- E: the API's own view --------------------------------------
            out.append('E. ContentService sees DRAFT-status content for this id: ')
            try {
                ContentService cs = ComponentLocator.getComponent(ContentService)
                Object finder = InvokerHelper.invokeMethod(cs, 'find', new Object[0])
                finder = InvokerHelper.invokeMethod(finder, 'withStatus', ContentStatus.DRAFT)
                finder = InvokerHelper.invokeMethod(finder, 'withId', ContentId.of(pid.longValue()))
                Object fetched = InvokerHelper.invokeMethod(finder, 'fetch', new Object[0])
                Object present = fetched == null ? Boolean.FALSE
                        : InvokerHelper.invokeMethod(fetched, 'isPresent', new Object[0])
                out.append(String.valueOf(present)).append('\n')
            } catch (Exception apiEx) {
                out.append('API ERROR: ').append(htmlEsc(apiEx.getMessage())).append('\n')
            }
            out.append('\n')
        }
    }

    out.append('</pre>')
    return out.toString()
} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
