/*
 * Check-DuplicateTitles.groovy
 * -----------------------------------------------------------------------------
 * Write-blocker audit for the macro migration: lists, per space, every title
 * that exists on TWO OR MORE CURRENT pages - the condition that makes
 * Confluence's save validator reject writeCurrentVersion with "A page already
 * exists with the title ... in the space ...". These same pages also block the
 * Cloud migration itself.
 *
 * Scope rules (deliberate):
 *   - NULL / empty titles are OUT of scope (abandoned untitled drafts - noise);
 *     they are excluded from the table and reported only as a count.
 *   - A title qualifies only when >= 2 CURRENT rows carry it. Draft or other
 *     rows OF A QUALIFYING TITLE are listed too, for context, but do not by
 *     themselves make a title qualify (draft-only duplicates never blocked a
 *     write).
 *   - Read-only. No writes anywhere.
 *
 * Output: one scrollable listbox per run with a single table over all
 * configured spaces, then a stats block: per space - duplicated titles,
 * current pages in conflict (= write-blocking page versions), minimum
 * retitles needed, context rows, excluded empty-title rows.
 */
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field
import java.text.SimpleDateFormat

// ============================== CONFIG =======================================

@Field List<String> SPACE_KEYS = ['JVERKO']     // audit these spaces
@Field String DB_RESOURCE = 'ConfluenceDB'      // ScriptRunner DB resource name
@Field int SCROLLBOX_MAX_HEIGHT_PX = 600

// =============================================================================

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String statusLabel(String raw) {
    if (raw == 'current') return 'Current'
    if (raw == 'draft') return 'Draft'
    return 'Empty'
}

String urlFor(String baseUrl, String status, long contentId) {
    if (status == 'draft') return baseUrl + '/pages/resumedraft.action?draftId=' + contentId
    return baseUrl + '/pages/viewpage.action?pageId=' + contentId
}

try {
    SettingsManager settingsManager = ComponentLocator.getComponent(SettingsManager)
    String baseUrl = settingsManager.getGlobalSettings().getBaseUrl()
    SimpleDateFormat fmt = new SimpleDateFormat('yyyy-MM-dd HH:mm')

    StringBuilder table = new StringBuilder()
    table.append('<div style="max-height:').append(SCROLLBOX_MAX_HEIGHT_PX)
         .append('px;overflow:auto;border:1px solid #ccc">')
         .append('<table border="1" cellpadding="4" cellspacing="0" ')
         .append('style="border-collapse:collapse;font-size:90%;white-space:nowrap">')
         .append('<tr><th>Ref.Id</th><th>SpaceKey</th><th>Page Title</th><th>Page ID</th>')
         .append('<th>ContentID</th><th>Version</th><th>Status</th><th>URL</th>')
         .append('<th>CreatedAt</th><th>Author</th><th>Comments</th></tr>')

    StringBuilder stats = new StringBuilder()
    int totalGroups = 0, totalBlocking = 0, totalRetitles = 0, totalContext = 0, totalExcluded = 0

    String mainQuery = '''
        SELECT c.title AS title,
               c.contentid AS contentid,
               c.version AS version,
               c.content_status AS status,
               c.creationdate AS createdat,
               COALESCE(um.username, c.creator) AS author
        FROM content c
        JOIN spaces s ON s.spaceid = c.spaceid
        LEFT JOIN user_mapping um ON um.user_key = c.creator
        WHERE s.spacekey = :sk
          AND c.contenttype = 'PAGE'
          AND c.prevver IS NULL
          AND c.title IS NOT NULL AND btrim(c.title) <> ''
          AND c.title IN (
              SELECT c2.title
              FROM content c2
              JOIN spaces s2 ON s2.spaceid = c2.spaceid
              WHERE s2.spacekey = :sk
                AND c2.contenttype = 'PAGE'
                AND c2.prevver IS NULL
                AND c2.content_status = 'current'
                AND c2.title IS NOT NULL AND btrim(c2.title) <> ''
              GROUP BY c2.title
              HAVING COUNT(*) > 1)
        ORDER BY c.title,
                 CASE WHEN c.content_status = 'current' THEN 0
                      WHEN c.content_status = 'draft' THEN 1 ELSE 2 END,
                 c.contentid
    '''
    /*
     * Section 2: LOCKED pages - current pages holding an unpublished shared
     * (collaborative-editing) draft, the state that rejects writeCurrentVersion
     * with "unreconciled page". Detection: a draft-status row carrying the
     * SAME title in the SAME space as a current page (shared drafts inherit
     * the page title; the untitled draft groups are new-page drafts and
     * correctly never match). Heuristic but checkable: the per-space locked
     * count must reconcile with the APPLY run's locked-page failures
     * (occurrences > pages when a page carries several macros).
     */
    String lockedQuery = '''
        SELECT cur.title AS title,
               cur.contentid AS pageid,
               d.contentid AS draftid,
               d.version AS version,
               d.creationdate AS createdat,
               COALESCE(um.username, d.creator) AS author
        FROM content cur
        JOIN spaces s ON s.spaceid = cur.spaceid
        JOIN content d ON d.spaceid = cur.spaceid
                      AND d.title = cur.title
                      AND d.contenttype = 'PAGE'
                      AND d.content_status = 'draft'
                      AND d.prevver IS NULL
        LEFT JOIN user_mapping um ON um.user_key = d.creator
        WHERE s.spacekey = :sk
          AND cur.contenttype = 'PAGE'
          AND cur.prevver IS NULL
          AND cur.content_status = 'current'
          AND cur.title IS NOT NULL AND btrim(cur.title) <> ''
        ORDER BY cur.title, d.contentid
    '''
    String excludedQuery = '''
        SELECT COUNT(*) AS n
        FROM content c
        JOIN spaces s ON s.spaceid = c.spaceid
        WHERE s.spacekey = :sk
          AND c.contenttype = 'PAGE'
          AND c.prevver IS NULL
          AND (c.title IS NULL OR btrim(c.title) = '')
    '''

    StringBuilder lockedTable = new StringBuilder()
    lockedTable.append('<div style="max-height:').append(SCROLLBOX_MAX_HEIGHT_PX)
         .append('px;overflow:auto;border:1px solid #ccc">')
         .append('<table border="1" cellpadding="4" cellspacing="0" ')
         .append('style="border-collapse:collapse;font-size:90%;white-space:nowrap">')
         .append('<tr><th>Ref.Id</th><th>SpaceKey</th><th>Page Title</th><th>Page ID</th>')
         .append('<th>ContentID</th><th>Version</th><th>Status</th><th>URL</th>')
         .append('<th>CreatedAt</th><th>Author</th><th>Comments</th></tr>')
    int totalLocked = 0

    for (String sk : SPACE_KEYS) {
        int refCounter = 0
        int lockedCounter = 0
        int blocking = 0, contextRows = 0
        Map<String, Integer> currentPerTitle = new LinkedHashMap<String, Integer>()
        int excluded = 0

        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            sql.eachRow(mainQuery, [sk: sk]) { row ->
                refCounter++
                String title = row['title'] as String
                long cid = ((Number) row['contentid']).longValue()
                String status = row['status'] as String
                boolean isCurrent = (status == 'current')
                if (isCurrent) {
                    blocking++
                    Integer n = currentPerTitle.get(title)
                    currentPerTitle.put(title, n == null ? 1 : n + 1)
                } else {
                    contextRows++
                }
                String createdAt = ''
                Object ts = row['createdat']
                if (ts instanceof java.util.Date) createdAt = fmt.format((java.util.Date) ts)
                String url = urlFor(baseUrl, status, cid)

                table.append('<tr><td>').append(sk).append(String.format('%03d', refCounter))
                     .append('</td><td>').append(htmlEsc(sk))
                     .append('</td><td>').append(htmlEsc(title))
                     .append('</td><td>').append(isCurrent ? cid.toString() : '')
                     .append('</td><td>').append(cid)
                     .append('</td><td>').append(row['version'])
                     .append('</td><td>').append(statusLabel(status))
                     .append('</td><td><a href="').append(url).append('" target="_blank">')
                     .append(htmlEsc(url)).append('</a>')
                     .append('</td><td>').append(createdAt)
                     .append('</td><td>').append(htmlEsc(row['author']))
                     .append('</td><td></td></tr>')
            }
            sql.eachRow(excludedQuery, [sk: sk]) { row ->
                excluded = ((Number) row['n']).intValue()
            }
            sql.eachRow(lockedQuery, [sk: sk]) { row ->
                lockedCounter++
                long pageId = ((Number) row['pageid']).longValue()
                long draftId = ((Number) row['draftid']).longValue()
                String createdAt = ''
                Object ts = row['createdat']
                if (ts instanceof java.util.Date) createdAt = fmt.format((java.util.Date) ts)
                String url = baseUrl + '/pages/viewpage.action?pageId=' + pageId
                lockedTable.append('<tr><td>').append(sk).append('-L').append(String.format('%03d', lockedCounter))
                     .append('</td><td>').append(htmlEsc(sk))
                     .append('</td><td>').append(htmlEsc(row['title']))
                     .append('</td><td>').append(pageId)
                     .append('</td><td>').append(draftId)
                     .append('</td><td>').append(row['version'])
                     .append('</td><td>Draft')
                     .append('</td><td><a href="').append(url).append('" target="_blank">')
                     .append(htmlEsc(url)).append('</a>')
                     .append('</td><td>').append(createdAt)
                     .append('</td><td>').append(htmlEsc(row['author']))
                     .append('</td><td></td></tr>')
            }
        }

        int groups = currentPerTitle.size()
        int retitles = 0
        for (Integer n : currentPerTitle.values()) retitles += (n - 1)

        stats.append('  ').append(sk).append(':  duplicated titles: ').append(groups)
             .append('   blocking current pages: ').append(blocking)
             .append('   minimum retitles: ').append(retitles)
             .append('   context rows (drafts etc.): ').append(contextRows)
             .append('   excluded empty/NULL-title rows: ').append(excluded)
             .append('   LOCKED pages (unpublished drafts): ').append(lockedCounter).append('\n')

        totalGroups += groups; totalBlocking += blocking; totalRetitles += retitles
        totalContext += contextRows; totalExcluded += excluded; totalLocked += lockedCounter
    }

    table.append('</table></div>')
    lockedTable.append('</table></div>')

    StringBuilder page = new StringBuilder()
    page.append('<h3>Duplicate-title write blockers (').append(SPACE_KEYS.join(', ')).append(')</h3>')
    page.append(table)
    page.append('<h3>Locked pages - unpublished in-editor changes (').append(totalLocked).append(')</h3>')
    page.append('<p style="font-size:90%">Current pages holding a shared collaborative-editing draft - ')
        .append('the state that rejects writes with "unreconciled page". Author and CreatedAt are the ')
        .append('DRAFT\'s - who left the unpublished edit and when. Publish or discard the draft in the ')
        .append('editor, then re-run APPLY.</p>')
    page.append(lockedTable)
    page.append('<pre style="font-size:90%">')
    page.append('SUMMARY\n')
    page.append(htmlEsc(stats.toString()))
    page.append('  TOTAL: duplicated titles: ').append(totalGroups)
        .append('   blocking current pages: ').append(totalBlocking)
        .append('   minimum retitles: ').append(totalRetitles)
        .append('   context rows: ').append(totalContext)
        .append('   excluded empty/NULL-title rows: ').append(totalExcluded)
        .append('   LOCKED pages: ').append(totalLocked).append('\n')
    page.append('</pre>')
    page.append('<p style="font-size:85%;color:#666">Legend: a title qualifies when two or more ')
        .append('CURRENT pages in the same space carry it - exactly the condition that rejects ')
        .append('writes with "A page already exists with the title...". Draft/Empty rows of a ')
        .append('qualifying title are listed for context only. Retitle one page per pair, then ')
        .append('re-run APPLY - already-replaced versions no-op. Empty/NULL-title rows are ')
        .append('abandoned drafts, out of scope, excluded from the table.</p>')
    return page.toString()

} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
