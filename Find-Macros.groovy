/*
 * =============================================================================
 *  FIND MACROS - discovery only                ScriptRunner Console, READ ONLY
 * =============================================================================
 *
 *  Finds every page version carrying one of the source macros from a MIGRATIONS
 *  block. Nothing is written. Nothing is loaded through the Confluence API - all
 *  answers come from three tables.
 *
 *  WHY THE QUERIES LOOK LIKE THIS
 *  ------------------------------
 *  A version row cannot be filtered by space. Confluence populates CONTENT.
 *  SPACEID on the current version but leaves it NULL on many historical rows, so
 *  "WHERE c.spaceid IN (...)" silently discards history. The space is a property
 *  of the PAGE, so the page set is resolved first and version rows are then
 *  matched BY PAGE ID:
 *
 *      current version of page P   ->  content.contentid = P
 *      historical version of P     ->  content.prevver   = P
 *
 *  Neither test looks at spaceid, so a NULL there changes nothing.
 *
 *  The one predicate that cannot use an index is "body contains this macro" -
 *  LIKE with a leading wildcard always reads the value. Everything else exists
 *  to make sure only the rows belonging to the pages in scope ever reach it.
 *
 *  Step 1  spaces          key -> spaceid                      (tiny, indexed)
 *  Step 2  content         spaceid -> current page ids         (indexed)
 *  Step 3  content+body    page ids -> macro-bearing versions  (chunked, by id)
 *
 *  With no SPACE_KEYS there is no page set to bound step 3, so it degrades to a
 *  full scan of bodycontent. That is stated in the output rather than hidden.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.util.regex.Matcher
import java.util.regex.Pattern

// =============================================================================
//  ENUM - same members as the replacement engine, so a MIGRATIONS block pasted
//  from there compiles here unchanged.
// =============================================================================
enum MacroType {
    UserMacro,
    ScriptRunnerMacro,
    EddStatusMacro,
    AuraLinkButton,
    Static_QualificationTable
}

// =============================================================================
//  CONFIG
// =============================================================================

@Field String DB_RESOURCE = 'ConfluenceDB'

// Spaces to search. EMPTY = whole instance, which cannot be bounded by page id
// and therefore scans every body. See the header note.
@Field List<String> SPACE_KEYS = ['TSTSP1']

// content_status of the PAGE. Historical rows are not tested - they inherit
// their page's status by definition.
@Field List<String> INCLUDE_STATUSES = ['current']

// false = current versions only.
@Field boolean INCLUDE_HISTORICAL = true

// Page ids per chunk in step 3. Postgres abandons an index once an IN list gets
// very large; a few hundred keeps every lookup on the index.
@Field int CHUNK_SIZE = 500

// Abort a query rather than let the HTTP request be dropped - a dropped request
// returns nothing at all, not even a Logs tab. 0 = no limit.
@Field int SQL_TIMEOUT_SECONDS = 300

// Print the column list of every table this script reads. Once per table, once
// per run, before anything else - so the schema being relied on is visible.
@Field boolean DUMP_TABLE_HEADERS = true

// TABLE | CSV
@Field String RESULT_FORMAT = 'TABLE'
@Field int RESULT_MAX_HEIGHT_PX = 600

// =============================================================================
//  MIGRATIONS - paste from the replacement engine, unchanged.
//  Only source.name is read here; everything else is ignored.
// =============================================================================
@Field List MIGRATIONS = [

    [
        id     : 'qualification-table',
        source : [name: 'qualification-table', type: MacroType.ScriptRunnerMacro],
        target : [name: null, type: MacroType.Static_QualificationTable],
    ],

]

// =============================================================================
//  SMALL HELPERS
// =============================================================================

/** HTML-escapes a value for display. */
String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

/** "1 page" / "2 pages". */
String plural(int n, String one) {
    return n as String + ' ' + (n == 1 ? one : one + 's')
}

/**
 * Escapes LIKE wildcards inside a macro name.
 * Values are bound as parameters so injection is not the concern, but inside a
 * LIKE pattern "_" still matches any single character and "%" any sequence - a
 * macro named "my_macro" would otherwise also match "myXmacro". Used together
 * with ESCAPE '\' on the predicate.
 */
String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

/** Source macro names from MIGRATIONS. Commented-out entries never reach here. */
List<String> sourceMacroNames() {
    List<String> names = new ArrayList<String>()
    for (Object entryObj : MIGRATIONS) {
        Map<String, Object> entry = (Map<String, Object>) entryObj
        Map<String, Object> src = (Map<String, Object>) entry.get('source')
        if (src == null) continue
        String nm = (String) src.get('name')
        if (nm != null && !nm.trim().isEmpty() && !names.contains(nm)) names.add(nm)
    }
    return names
}

// =============================================================================
//  TABLE HEADERS
//
//  Printed once per table, once per run. information_schema.columns has come
//  back empty on this instance, so the column list is taken from
//  ResultSetMetaData of a single row - which always works.
// =============================================================================

String dumpTableHeader(String table) {
    try {
        StringBuilder b = new StringBuilder()
        String resource = DB_RESOURCE
        String query = 'SELECT * FROM ' + table + ' LIMIT 1'
        List<String> cols = new ArrayList<String>()
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query) { row ->
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    cols.add(row.getMetaData().getColumnName(i) + ' ' +
                             row.getMetaData().getColumnTypeName(i))
                }
            }
        }
        b.append('  ').append(table).append('\n')
        b.append('    ').append(cols.isEmpty() ? '(empty table or unreadable)' : cols.join(', '))
         .append('\n')
        return b.toString()
    } catch (Exception e) {
        return '  ' + table + '\n    ERROR: ' + e.getMessage() + '\n'
    }
}

// =============================================================================
//  STEP 1 - spaces:  key -> spaceid
//
//    SELECT spaceid, spacekey FROM spaces WHERE spacekey IN (:k0, :k1, ...)
//
//  spacekey is unique and indexed. Returns at most one row per configured key;
//  a key that resolves to nothing is reported rather than silently ignored.
// =============================================================================

Map<String, Integer> resolveSpaceIds(List<String> spaceKeys) {
    try {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>()
        if (spaceKeys.isEmpty()) return out
        List<String> ph = new ArrayList<String>()
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        for (int i = 0; i < spaceKeys.size(); i++) {
            ph.add(':k' + i)
            params.put('k' + i, spaceKeys.get(i))
        }
        String query = 'SELECT spaceid, spacekey FROM spaces WHERE spacekey IN (' + ph.join(', ') + ')'
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query, params) { row ->
                out.put(row['spacekey'] as String, ((Number) row['spaceid']).intValue())
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('resolveSpaceIds failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STEP 2 - content:  spaceid -> current page ids
//
//    SELECT contentid, title, spaceid
//    FROM content
//    WHERE spaceid IN (:s0, ...)          <- indexed integer column
//      AND prevver IS NULL                <- current versions only
//      AND contenttype IN ('PAGE','BLOGPOST')
//      AND content_status IN (:st0, ...)  <- status of the PAGE
//
//  prevver IS NULL is what makes a row the current version of its page: a
//  historical row stores the current version's id there.
//
//  This is the ONLY place space and status are tested. Both are properties of
//  the page, and both are read from the page's own row - never from a version
//  row, which may legitimately have neither.
// =============================================================================

class PageRow {
    long pageId
    String title = ''
    String spaceKey = ''
}

List<PageRow> pagesInScope(Map<String, Integer> spaceIds) {
    try {
        List<PageRow> out = new ArrayList<PageRow>()
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        Map<Integer, String> keyById = new LinkedHashMap<Integer, String>()

        StringBuilder q = new StringBuilder()
        q.append('SELECT contentid, title, spaceid FROM content ')
         .append("WHERE contenttype IN ('PAGE','BLOGPOST') AND prevver IS NULL ")

        if (!spaceIds.isEmpty()) {
            List<String> ph = new ArrayList<String>()
            int i = 0
            for (Map.Entry<String, Integer> e : spaceIds.entrySet()) {
                ph.add(':s' + i)
                params.put('s' + i, e.getValue())
                keyById.put(e.getValue(), e.getKey())
                i++
            }
            q.append('AND spaceid IN (').append(ph.join(', ')).append(') ')
        }
        if (!INCLUDE_STATUSES.isEmpty()) {
            List<String> ph = new ArrayList<String>()
            for (int i = 0; i < INCLUDE_STATUSES.size(); i++) {
                ph.add(':st' + i)
                params.put('st' + i, INCLUDE_STATUSES.get(i))
            }
            q.append('AND content_status IN (').append(ph.join(', ')).append(') ')
        }

        String query = q.toString()
        String resource = DB_RESOURCE
        int timeoutMs = SQL_TIMEOUT_SECONDS * 1000
        DatabaseUtil.withSql(resource) { Sql sql ->
            if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)
            sql.eachRow(query, params) { row ->
                PageRow pr = new PageRow()
                pr.pageId = ((Number) row['contentid']).longValue()
                pr.title = row['title'] as String
                Object sid = row['spaceid']
                pr.spaceKey = (sid == null) ? '' : (keyById.get(((Number) sid).intValue()) ?: '')
                out.add(pr)
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('pagesInScope failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STEP 3 - THE DISCOVERY  (the one function that finds macros)
//
//  For a chunk of page ids, both queries below are run:
//
//    CURRENT versions
//      SELECT c.contentid, c.version
//      FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid
//      WHERE c.contentid IN (:p0, ...)            <- primary key
//        AND (bc.body LIKE :m0 ESCAPE '\' OR ...)
//
//    HISTORICAL versions
//      SELECT c.contentid, c.version, c.prevver
//      FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid
//      WHERE c.prevver IN (:p0, ...)              <- points at the current row
//        AND (bc.body LIKE :m0 ESCAPE '\' OR ...)
//
//  Why two queries rather than one with OR: an OR across two different columns
//  stops Postgres using either index and turns the whole thing into a scan.
//  Two single-column predicates each stay on their index.
//
//  Why bodycontent is joined on contentid: it is the primary key there, so each
//  row is a direct lookup. Only the bodies of versions belonging to the pages in
//  scope are read - the LIKE never sees the rest of the table.
//
//  spaceid and content_status appear NOWHERE in this step. Version rows may have
//  neither, and testing them here is what previously dropped historical rows.
// =============================================================================

class MacroVersion {
    long pageId
    long contentId
    int versionNumber
    boolean historical
}

/**
 * Every version carrying a source macro, for the given pages.
 * Pages are processed in chunks so each IN list stays small enough for Postgres
 * to keep using the index.
 */
List<MacroVersion> findMacroVersions(List<Long> pageIds, List<String> macroNames,
                                     StringBuilder progress) {
    try {
        List<MacroVersion> out = new ArrayList<MacroVersion>()
        if (pageIds.isEmpty() || macroNames.isEmpty()) return out

        // the macro predicate, identical in both queries
        Map<String, Object> macroParams = new LinkedHashMap<String, Object>()
        List<String> macroClauses = new ArrayList<String>()
        for (int i = 0; i < macroNames.size(); i++) {
            // the closing quote is inside the pattern, so "artikel-status"
            // cannot match a page using "artikel-status-ed"
            macroParams.put('m' + i, '%ac:name="' + likeEscape(macroNames.get(i)) + '"%')
            macroClauses.add('bc.body LIKE :m' + i + " ESCAPE '\\'")
        }
        String macroWhere = '(' + macroClauses.join(' OR ') + ')'

        String resource = DB_RESOURCE
        int timeoutMs = SQL_TIMEOUT_SECONDS * 1000
        int chunks = 0

        for (int from = 0; from < pageIds.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, pageIds.size())
            List<Long> slice = pageIds.subList(from, to)
            chunks++

            List<String> ph = new ArrayList<String>()
            Map<String, Object> params = new LinkedHashMap<String, Object>(macroParams)
            for (int i = 0; i < slice.size(); i++) {
                ph.add(':p' + i)
                params.put('p' + i, slice.get(i))
            }
            String inList = ph.join(', ')

            // --- current versions: matched on the primary key ---------------
            String qCurrent = 'SELECT c.contentid AS cid, c.version AS ver ' +
                              'FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                              'WHERE c.contentid IN (' + inList + ') AND ' + macroWhere

            // --- historical versions: matched on prevver --------------------
            // prevver holds the id of the page's CURRENT row, which is exactly
            // the id in the chunk. No spaceid, no content_status.
            String qHistorical = 'SELECT c.contentid AS cid, c.version AS ver, c.prevver AS pid ' +
                                 'FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                                 'WHERE c.prevver IN (' + inList + ') AND ' + macroWhere

            DatabaseUtil.withSql(resource) { Sql sql ->
                if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)

                sql.eachRow(qCurrent, params) { row ->
                    MacroVersion mv = new MacroVersion()
                    mv.contentId = ((Number) row['cid']).longValue()
                    mv.pageId = mv.contentId          // a current row IS the page
                    mv.versionNumber = ((Number) row['ver']).intValue()
                    mv.historical = false
                    out.add(mv)
                }

                if (INCLUDE_HISTORICAL) {
                    sql.eachRow(qHistorical, params) { row ->
                        MacroVersion mv = new MacroVersion()
                        mv.contentId = ((Number) row['cid']).longValue()
                        mv.pageId = ((Number) row['pid']).longValue()
                        mv.versionNumber = ((Number) row['ver']).intValue()
                        mv.historical = true
                        out.add(mv)
                    }
                }
            }
        }
        progress.append('  chunks executed: ').append(chunks)
               .append(' of ').append(CHUNK_SIZE).append(' page ids each\n')
        return out
    } catch (Exception e) {
        throw new RuntimeException('findMacroVersions failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MAIN
// =============================================================================

StringBuilder outp = new StringBuilder()
StringBuilder html = new StringBuilder()

try {
    long started = System.currentTimeMillis()

    // ---- table headers, once per table, before anything else ---------------
    if (DUMP_TABLE_HEADERS) {
        outp.append('TABLES READ BY THIS SCRIPT\n')
        outp.append('================================================================\n')
        outp.append(dumpTableHeader('spaces'))
        outp.append(dumpTableHeader('content'))
        outp.append(dumpTableHeader('bodycontent'))
        outp.append('\n')
    }

    // ---- source macros -----------------------------------------------------
    List<String> macroNames = sourceMacroNames()
    outp.append('SOURCE MACROS FROM MIGRATIONS\n')
    outp.append('================================================================\n')
    for (String m : macroNames) outp.append('  ').append(m).append('\n')
    if (macroNames.isEmpty()) {
        outp.append('  (none - MIGRATIONS has no source names)\n')
        return '<pre>' + esc(outp.toString()) + '</pre>'
    }
    outp.append('\n')

    // ---- step 1 ------------------------------------------------------------
    outp.append('STEP 1  spaces: key -> spaceid\n')
    outp.append('================================================================\n')
    Map<String, Integer> spaceIds = resolveSpaceIds(SPACE_KEYS)
    for (String k : SPACE_KEYS) {
        outp.append('  ').append(k).append(' -> ')
            .append(spaceIds.containsKey(k) ? (spaceIds.get(k) as String) : 'NOT FOUND').append('\n')
    }
    if (SPACE_KEYS.isEmpty()) {
        outp.append('  SPACE_KEYS is empty - the whole instance is in scope, so step 3\n')
        outp.append('  cannot be bounded by page id and every body will be read.\n')
    }
    outp.append('\n')

    // ---- step 2 ------------------------------------------------------------
    outp.append('STEP 2  content: spaceid -> current page ids\n')
    outp.append('================================================================\n')
    List<PageRow> pages = pagesInScope(spaceIds)
    Map<Long, PageRow> pageById = new LinkedHashMap<Long, PageRow>()
    List<Long> pageIds = new ArrayList<Long>()
    for (PageRow pr : pages) { pageById.put(pr.pageId, pr); pageIds.add(pr.pageId) }
    outp.append('  pages in scope: ').append(pages.size())
        .append('   (all pages, not only those with macros)\n\n')

    // ---- step 3 ------------------------------------------------------------
    outp.append('STEP 3  content + bodycontent: page ids -> macro-bearing versions\n')
    outp.append('================================================================\n')
    outp.append('  historical versions: ').append(INCLUDE_HISTORICAL ? 'INCLUDED' : 'excluded').append('\n')
    StringBuilder progress = new StringBuilder()
    List<MacroVersion> found = findMacroVersions(pageIds, macroNames, progress)
    outp.append(progress)

    int currentCount = 0, historicalCount = 0
    Set<Long> affectedPages = new LinkedHashSet<Long>()
    for (MacroVersion mv : found) {
        if (mv.historical) historicalCount++ else currentCount++
        affectedPages.add(mv.pageId)
    }
    outp.append('  versions with macros: ').append(found.size())
        .append('   current: ').append(currentCount)
        .append('   historical: ').append(historicalCount).append('\n')
    outp.append('  affected pages: ').append(affectedPages.size()).append('\n')
    outp.append('\n  elapsed: ').append((System.currentTimeMillis() - started) / 1000).append(' s\n')

    // ---- output ------------------------------------------------------------
    List<String> headers = ['Space Key', 'Page ID', 'Page Title', 'Version Content ID',
                            'Version', 'Historical']
    if (RESULT_FORMAT == 'TABLE') {
        html.append('<h3>Macro-bearing versions (').append(plural(found.size(), 'row')).append(')</h3>')
        html.append('<div style="max-height:').append(RESULT_MAX_HEIGHT_PX)
            .append('px;overflow:auto;border:1px solid #ccc;resize:vertical">')
            .append('<table border="1" cellpadding="4" cellspacing="0" style="font-size:90%"><tr>')
        for (String h : headers) html.append('<th>').append(esc(h)).append('</th>')
        html.append('</tr>')
        for (MacroVersion mv : found) {
            PageRow pr = pageById.get(mv.pageId)
            html.append('<tr><td>').append(esc(pr == null ? '' : pr.spaceKey))
                .append('</td><td>').append(mv.pageId)
                .append('</td><td>').append(esc(pr == null ? '' : pr.title))
                .append('</td><td>').append(mv.contentId)
                .append('</td><td>').append(mv.versionNumber)
                .append('</td><td>').append(mv.historical ? 'yes' : '')
                .append('</td></tr>')
        }
        html.append('</table></div>')
    } else {
        StringBuilder csv = new StringBuilder()
        csv.append('space_key,page_id,page_title,version_content_id,version,historical\n')
        for (MacroVersion mv : found) {
            PageRow pr = pageById.get(mv.pageId)
            List<String> f = [pr == null ? '' : pr.spaceKey, mv.pageId as String,
                              pr == null ? '' : pr.title, mv.contentId as String,
                              mv.versionNumber as String, mv.historical ? 'yes' : 'no']
            List<String> q = new ArrayList<String>()
            for (String v : f) q.add('"' + (v == null ? '' : v.replace('"', '""')) + '"')
            csv.append(q.join(',')).append('\n')
        }
        html.append('<h3>Macro-bearing versions (').append(plural(found.size(), 'row')).append(')</h3>')
            .append('<p style="font-size:90%">Click inside, Ctrl+A, Ctrl+C.</p>')
            .append('<textarea readonly rows="20" style="width:100%;font-family:monospace;font-size:85%">')
            .append(esc(csv.toString())).append('</textarea>')
    }

    log.warn("Find-Macros: ${found.size()} version(s), ${affectedPages.size()} page(s)")
    return html.toString() + '<pre>' + esc(outp.toString()) + '</pre>'

} catch (Throwable fatal) {
    log.error('Find-Macros failed', fatal)
    outp.append('\nTERMINATED: ').append(fatal.getClass().getName()).append(': ')
        .append(fatal.getMessage()).append('\n')
    Throwable c = fatal.getCause()
    while (c != null) {
        outp.append('  caused by ').append(c.getClass().getName()).append(': ')
            .append(c.getMessage()).append('\n')
        c = c.getCause()
    }
    return html.toString() + '<pre>' + esc(outp.toString()) + '</pre>'
}
