/*
 * Probe-WriteFailures.groovy
 * -----------------------------------------------------------------------------
 * Diagnostic dump for pages whose APPLY writes failed. For EACH page id it
 * prints the page identity, then one block per known failure class:
 *
 *   [1] writeHistoricalVersion NPE "Page.getSpace() is null"
 *       evidence: which version rows of this page have spaceid = NULL,
 *       and whether the CURRENT row itself has a space
 *   [2] "unreconciled page" / locked for writing (unpublished in-editor edit)
 *       evidence: draft-status rows linked to this page by the legacy
 *       draftpageid column, by same space+title, their contentproperties,
 *       and Confluence's API answer (ContentService, DRAFT status)
 *   [3] "A page already exists with the title ..." (duplicate title)
 *       evidence: every OTHER content row in the same space with this title
 *
 * Each block ends with a VERDICT line. Read-only.
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

@Field List<Long> PAGE_IDS = [88993388L]
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

/** API view: does ContentService see DRAFT-status content for this id? Tries both finder orders. */
String apiDraftView(long pid) {
    ContentService cs = ComponentLocator.getComponent(ContentService)
    ContentStatus[] draftOnly = [ContentStatus.DRAFT] as ContentStatus[]
    String firstError = null
    try {
        Object f = InvokerHelper.invokeMethod(cs, 'find', new Object[0])
        f = InvokerHelper.invokeMethod(f, 'withStatus', [draftOnly] as Object[])
        f = InvokerHelper.invokeMethod(f, 'withId', ContentId.of(pid))
        Object fetched = InvokerHelper.invokeMethod(f, 'fetch', new Object[0])
        Object present = InvokerHelper.invokeMethod(fetched, 'isPresent', new Object[0])
        return 'draft present = ' + present + '  (order: withStatus, withId)'
    } catch (Exception e1) {
        firstError = e1.getMessage()
    }
    try {
        Object f = InvokerHelper.invokeMethod(cs, 'find', new Object[0])
        f = InvokerHelper.invokeMethod(f, 'withId', ContentId.of(pid))
        f = InvokerHelper.invokeMethod(f, 'withStatus', [draftOnly] as Object[])
        Object fetched = InvokerHelper.invokeMethod(f, 'fetch', new Object[0])
        Object present = InvokerHelper.invokeMethod(fetched, 'isPresent', new Object[0])
        return 'draft present = ' + present + '  (order: withId, withStatus)'
    } catch (Exception e2) {
        return 'API ERROR (both orders): ' + cut(firstError, 160) + ' || ' + cut(e2.getMessage(), 160)
    }
}

try {
    StringBuilder out = new StringBuilder()
    out.append('<pre style="font-size:90%">')

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        for (Long pid : PAGE_IDS) {
            out.append('==============================================================\n')
            out.append('PAGE ').append(pid).append('\n')
            out.append('==============================================================\n')

            // ---- identity ------------------------------------------------------
            String title = null
            Object spaceId = null
            String status = null
            sql.eachRow('''SELECT c.title, c.spaceid, c.content_status, c.version, c.contenttype,
                                  s.spacekey
                           FROM content c LEFT JOIN spaces s ON s.spaceid = c.spaceid
                           WHERE c.contentid = :pid''', [pid: pid]) { r ->
                title = r['title'] as String
                spaceId = r['spaceid']
                status = r['content_status'] as String
                out.append('type=').append(r['contenttype'])
                   .append(' status=').append(status)
                   .append(' version=').append(r['version'])
                   .append(' space=').append(r['spacekey']).append(' (spaceid=').append(spaceId).append(')')
                   .append('\ntitle="').append(htmlEsc(title)).append('"\n\n')
            }
            if (title == null) { out.append('   (no content row with this id)\n\n'); continue }

            // ---- [1] NULL-space version rows ------------------------------------
            out.append('[1] NULL-space versions (cause of writeHistoricalVersion NPE)\n')
            int versions = 0, nullSpace = 0
            StringBuilder nullList = new StringBuilder()
            sql.eachRow('''SELECT c.contentid, c.version, c.spaceid, c.content_status
                           FROM content c WHERE c.prevver = :pid ORDER BY c.version''', [pid: pid]) { r ->
                versions++
                if (r['spaceid'] == null) {
                    nullSpace++
                    nullList.append('v').append(r['version']).append('(id ').append(r['contentid']).append(') ')
                }
            }
            out.append('   historical versions: ').append(versions)
               .append('   with NULL spaceid: ').append(nullSpace).append('\n')
            if (nullSpace > 0) out.append('   NULL-space rows: ').append(nullList).append('\n')
            out.append('   current row has space: ').append(spaceId != null).append('\n')
            out.append('   VERDICT [1]: ').append(spaceId == null
                    ? 'CURRENT ROW HAS NO SPACE - hydration from the current page cannot work'
                    : (nullSpace > 0 ? nullSpace + ' historical rows lack a space (hydratable from current)'
                                     : 'no NULL-space rows - class [1] does not apply')).append('\n\n')

            // ---- [2] locked: unpublished in-editor edit ---------------------------
            out.append('[2] LOCKED - unpublished in-editor edit (unreconciled page)\n')
            int byLink = 0
            List<Long> draftIds = new ArrayList<Long>()
            sql.eachRow('''SELECT c.contentid, c.version, c.drafttype, c.title, c.creationdate, c.lastmoddate
                           FROM content c
                           WHERE c.content_status = 'draft' AND c.draftpageid = :pids''',
                        [pids: pid.toString()]) { r ->
                byLink++
                draftIds.add(((Number) r['contentid']).longValue())
                out.append('   linked by draftpageid: draft id=').append(r['contentid'])
                   .append(' type=').append(r['drafttype'])
                   .append(' title="').append(htmlEsc(cut(r['title'], 50))).append('"')
                   .append(' created=').append(r['creationdate'])
                   .append(' lastmod=').append(r['lastmoddate']).append('\n')
            }
            int byTitle = 0
            sql.eachRow('''SELECT c.contentid, c.version, c.drafttype, c.draftpageid, c.creationdate
                           FROM content c
                           WHERE c.content_status = 'draft' AND c.spaceid = :sid AND c.title = :t''',
                        [sid: spaceId, t: title]) { r ->
                byTitle++
                out.append('   same space+title draft: id=').append(r['contentid'])
                   .append(' type=').append(r['drafttype'])
                   .append(' draftpageid=').append(r['draftpageid'])
                   .append(' created=').append(r['creationdate']).append('\n')
            }
            if (byLink == 0 && byTitle == 0) out.append('   no draft rows linked to this page\n')
            // contentproperties of page + linked drafts, names + short values
            List<Long> propIds = new ArrayList<Long>(draftIds); propIds.add(pid)
            String inList = propIds.join(',')
            int props = 0
            sql.eachRow('SELECT cp.contentid, cp.propertyname, cp.stringval, cp.longval FROM contentproperties cp ' +
                        'WHERE cp.contentid IN (' + inList + ') ORDER BY cp.contentid, cp.propertyname') { r ->
                props++
                out.append('   property [').append(r['contentid']).append('] ')
                   .append(r['propertyname']).append(' = ').append(htmlEsc(cut(r['stringval'], 80)))
                if (r['longval'] != null) out.append(' long=').append(r['longval'])
                out.append('\n')
            }
            if (props == 0) out.append('   no contentproperties on page or linked drafts\n')
            out.append('   API: ').append(htmlEsc(apiDraftView(pid.longValue()))).append('\n')
            out.append('   VERDICT [2]: ').append(byLink > 0
                    ? byLink + ' draft(s) linked via draftpageid - page IS locked'
                    : (byTitle > 0 ? 'draft(s) found only by title match - weak signal, compare with API line'
                                   : 'no linked drafts - class [2] does not apply unless API says otherwise'))
               .append('\n\n')

            // ---- [3] duplicate title ------------------------------------------------
            out.append('[3] DUPLICATE TITLE in the same space\n')
            int others = 0, otherCurrent = 0
            sql.eachRow('''SELECT c.contentid, c.version, c.content_status, c.creationdate
                           FROM content c
                           WHERE c.spaceid = :sid AND c.title = :t AND c.contenttype = 'PAGE'
                             AND c.prevver IS NULL AND c.contentid <> :pid
                           ORDER BY c.contentid''', [sid: spaceId, t: title, pid: pid]) { r ->
                others++
                if (r['content_status'] == 'current') otherCurrent++
                out.append('   other page with this title: id=').append(r['contentid'])
                   .append(' status=').append(r['content_status'])
                   .append(' v=').append(r['version'])
                   .append(' created=').append(r['creationdate']).append('\n')
            }
            if (others == 0) out.append('   no other page carries this title\n')
            out.append('   VERDICT [3]: ').append(otherCurrent > 0
                    ? otherCurrent + ' other CURRENT page(s) with this title - retitle one of them'
                    : (others > 0 ? 'only non-current rows share the title - not a save blocker'
                                  : 'title unique - class [3] does not apply')).append('\n\n')
        }
    }

    out.append('</pre>')
    return out.toString()
} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
