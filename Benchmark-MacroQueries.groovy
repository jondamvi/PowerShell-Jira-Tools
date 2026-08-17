/*
 * =============================================================================
 *  MACRO DISCOVERY QUERY BENCHMARK          ScriptRunner Console, READ ONLY
 * =============================================================================
 *
 *  Runs the candidate discovery queries against the SAME space, times them, and
 *  cross-checks that they return the SAME set of affected pages. Nothing is
 *  written and no Confluence API is touched.
 *
 *  It also reports the two facts that decide which shape can win, instead of
 *  assuming them:
 *      - which indexes exist on content and bodycontent (does prevver have one?)
 *      - how many bodytypeid values exist in bodycontent (is a filter worth it?)
 *
 *  VARIANTS
 *    A  rows,   LIKE   - current Find-Macros shape: page ids resolved first,
 *                        then two chunked queries returning EVERY matching
 *                        version row.
 *    B  exists, LIKE   - one query over the page rows, with two EXISTS. Answers
 *                        only "does this page have a macro anywhere", and the
 *                        outer OR short-circuits, so a page whose CURRENT
 *                        version matches never has its history read.
 *    C  exists, regex  - as B, with one regex pass per body instead of N LIKEs.
 *    D  rows,   regex  - as A, with the regex, to separate the effect of the
 *                        match style from the effect of the query shape.
 *
 *  CACHING SKEWS THIS. The first run of anything reads from disk, later runs
 *  from shared buffers, so run order alone can make a variant look faster. Each
 *  variant is therefore run RUNS times and every timing is reported - compare
 *  the last run of each, and re-run the whole script with the variant order
 *  reversed if two results are close.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

// =============================================================================
//  ENUM - so a MIGRATIONS block pasted from the engine compiles unchanged
// =============================================================================
enum MacroType {
    UserMacro, ScriptRunnerMacro, EddStatusMacro, AuraLinkButton, Static_QualificationTable
}

// ============================ CONFIG ============================
@Field String DB_RESOURCE = 'ConfluenceDB'

// ONE space is the right test: big enough to be meaningful, bounded enough to
// re-run several times without waiting.
@Field List<String> SPACE_KEYS = ['TSTSP1']

@Field List<String> INCLUDE_STATUSES = ['current']

// Page ids per chunk for the row-returning variants.
@Field int CHUNK_SIZE = 500

// Times each variant is executed. 2 is enough to see the caching effect.
@Field int RUNS = 2

// EXPLAIN (ANALYZE, BUFFERS) for each variant. Adds one extra execution each.
@Field boolean SHOW_EXPLAIN = true

@Field int SQL_TIMEOUT_SECONDS = 600

// Which variants to run.
@Field List<String> RUN_VARIANTS = ['A', 'B', 'C', 'D']
// ================================================================

@Field List MIGRATIONS = [

    [
        id     : 'qualification-table',
        source : [name: 'qualification-table', type: MacroType.ScriptRunnerMacro],
        target : [name: null, type: MacroType.Static_QualificationTable],
    ],

]

// =============================================================================
//  HELPERS
// =============================================================================

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String plural(int n, String one) { return n as String + ' ' + (n == 1 ? one : one + 's') }

/** LIKE wildcards inside a macro name would otherwise match anything. */
String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

/** POSIX regex metacharacters inside a macro name. */
String regexEscape(String v) {
    if (v == null) return ''
    StringBuilder b = new StringBuilder()
    for (int i = 0; i < v.length(); i++) {
        String c = v.charAt(i) as String
        if ('.^$*+?()[]{}|\\-'.contains(c)) b.append('\\')
        b.append(c)
    }
    return b.toString()
}

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

class Variant {
    String id = '', label = ''
    int affectedPages, versionRows
    List<Long> millis = new ArrayList<Long>()
    String explain = ''
    String error = ''
}

// =============================================================================
//  SCHEMA FACTS - the two things that decide which shape can win
// =============================================================================

/**
 * Indexes on content and bodycontent.
 * The historical branch of every variant rests on content.prevver: if it has no
 * index, that branch scans content and no query shape will rescue it.
 */
String indexReport() {
    try {
        StringBuilder b = new StringBuilder()
        String resource = DB_RESOURCE
        String query = "SELECT tablename, indexname, indexdef FROM pg_indexes " +
                       "WHERE tablename IN ('content','bodycontent','spaces') " +
                       "ORDER BY tablename, indexname"
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query) { row ->
                b.append('  ').append(row['tablename']).append('  ')
                 .append(row['indexname']).append('\n      ')
                 .append(row['indexdef']).append('\n')
            }
        }
        return b.toString()
    } catch (Exception e) {
        return '  index lookup failed: ' + e.getMessage() + '\n'
    }
}

/**
 * bodytypeid distribution.
 * If bodycontent holds more than one body type per version, adding
 * "AND bc.bodytypeid = <storage>" cuts the rows the match ever sees. If there
 * is only one value the filter is pointless.
 */
String bodyTypeReport() {
    try {
        StringBuilder b = new StringBuilder()
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow('SELECT bodytypeid, count(*) AS c FROM bodycontent GROUP BY bodytypeid ORDER BY bodytypeid') { row ->
                b.append('  bodytypeid ').append(row['bodytypeid'])
                 .append('  rows ').append(row['c']).append('\n')
            }
        }
        return b.toString()
    } catch (Exception e) {
        return '  bodytypeid lookup failed: ' + e.getMessage() + '\n'
    }
}

// =============================================================================
//  MATCH PREDICATES
//
//  LIKE form:   (bc.body LIKE :m0 ESCAPE '\' OR bc.body LIKE :m1 ESCAPE '\' ...)
//               OR short-circuits per row, so a row exits at its first match.
//               N names means up to N substring passes over a body.
//
//  regex form:  bc.body ~ 'ac:name="(name1|name2|...)"'
//               One pass over the body for all names. Slower per pass than a
//               substring search, so it loses at small N and should win at
//               large N - which is what this benchmark measures.
//
//  In both, the closing quote is part of the pattern, so "artikel-status"
//  cannot match a page using "artikel-status-ed".
// =============================================================================

String likeWhere(List<String> macroNames, Map<String, Object> params) {
    List<String> clauses = new ArrayList<String>()
    for (int i = 0; i < macroNames.size(); i++) {
        params.put('m' + i, '%ac:name="' + likeEscape(macroNames.get(i)) + '"%')
        clauses.add('bc.body LIKE :m' + i + " ESCAPE '\\'")
    }
    return '(' + clauses.join(' OR ') + ')'
}

String regexWhere(List<String> macroNames, Map<String, Object> params) {
    List<String> alts = new ArrayList<String>()
    for (String m : macroNames) alts.add(regexEscape(m))
    params.put('re', 'ac:name="(' + alts.join('|') + ')"')
    return '(bc.body ~ :re)'
}

// =============================================================================
//  PAGE SET - shared by every variant, so the comparison is like for like
//
//    SELECT contentid FROM content
//    WHERE spaceid IN (...)               <- indexed integer
//      AND prevver IS NULL                <- this row IS the current version
//      AND contenttype IN ('PAGE','BLOGPOST')
//      AND content_status IN (...)        <- status of the PAGE
//
//  Space and status are read from the page row ONLY. A version row may have
//  neither - historical rows often carry a NULL spaceid and do not carry
//  content_status = 'current' - so testing them there silently drops history.
// =============================================================================

List<Long> pageIdsInScope(Map<String, Object> outParams) {
    try {
        List<Long> out = new ArrayList<Long>()
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        List<Integer> spaceIds = new ArrayList<Integer>()
        String resource = DB_RESOURCE

        if (!SPACE_KEYS.isEmpty()) {
            List<String> ph = new ArrayList<String>()
            Map<String, Object> sp = new LinkedHashMap<String, Object>()
            for (int i = 0; i < SPACE_KEYS.size(); i++) { ph.add(':k' + i); sp.put('k' + i, SPACE_KEYS.get(i)) }
            String q = 'SELECT spaceid FROM spaces WHERE spacekey IN (' + ph.join(', ') + ')'
            DatabaseUtil.withSql(resource) { Sql sql ->
                sql.eachRow(q, sp) { row -> spaceIds.add(((Number) row['spaceid']).intValue()) }
            }
            if (spaceIds.isEmpty()) return out
        }

        StringBuilder q = new StringBuilder()
        q.append('SELECT contentid FROM content ')
         .append("WHERE contenttype IN ('PAGE','BLOGPOST') AND prevver IS NULL ")
        if (!spaceIds.isEmpty()) {
            List<String> ph = new ArrayList<String>()
            for (int i = 0; i < spaceIds.size(); i++) { ph.add(':s' + i); params.put('s' + i, spaceIds.get(i)) }
            q.append('AND spaceid IN (').append(ph.join(', ')).append(') ')
        }
        if (!INCLUDE_STATUSES.isEmpty()) {
            List<String> ph = new ArrayList<String>()
            for (int i = 0; i < INCLUDE_STATUSES.size(); i++) {
                ph.add(':st' + i); params.put('st' + i, INCLUDE_STATUSES.get(i))
            }
            q.append('AND content_status IN (').append(ph.join(', ')).append(') ')
        }
        String query = q.toString()
        int timeoutMs = SQL_TIMEOUT_SECONDS * 1000
        DatabaseUtil.withSql(resource) { Sql sql ->
            if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)
            sql.eachRow(query, params) { row -> out.add(((Number) row['contentid']).longValue()) }
        }
        outParams.put('pageQuery', query)
        outParams.put('pageParams', params)
        return out
    } catch (Exception e) {
        throw new RuntimeException('pageIdsInScope failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  VARIANTS A and D - return every matching version row
//
//  Two separate queries per chunk, never one with an OR across two columns:
//    current    WHERE c.contentid IN (chunk)   <- primary key
//    historical WHERE c.prevver   IN (chunk)   <- prevver holds the page id
//  An OR spanning contentid and prevver stops Postgres using either index.
//
//  Neither query mentions spaceid or content_status. Version rows may have
//  neither, and testing them here is what drops historical rows.
// =============================================================================

Map<String, Object> runRowVariant(List<Long> pageIds, List<String> macroNames, boolean useRegex,
                                  boolean wantExplain) {
    Set<Long> affected = new LinkedHashSet<Long>()
    int rows = 0
    String explain = ''
    String resource = DB_RESOURCE
    int timeoutMs = SQL_TIMEOUT_SECONDS * 1000

    for (int from = 0; from < pageIds.size(); from += CHUNK_SIZE) {
        int to = Math.min(from + CHUNK_SIZE, pageIds.size())
        List<Long> slice = pageIds.subList(from, to)

        Map<String, Object> params = new LinkedHashMap<String, Object>()
        String matchWhere = useRegex ? regexWhere(macroNames, params) : likeWhere(macroNames, params)
        List<String> ph = new ArrayList<String>()
        for (int i = 0; i < slice.size(); i++) { ph.add(':p' + i); params.put('p' + i, slice.get(i)) }
        String inList = ph.join(', ')

        String qCur = 'SELECT c.contentid AS cid FROM content c ' +
                      'JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                      'WHERE c.contentid IN (' + inList + ') AND ' + matchWhere
        String qHist = 'SELECT c.prevver AS pid FROM content c ' +
                       'JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                       'WHERE c.prevver IN (' + inList + ') AND ' + matchWhere

        DatabaseUtil.withSql(resource) { Sql sql ->
            if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)
            sql.eachRow(qCur, params) { row ->
                rows++; affected.add(((Number) row['cid']).longValue())
            }
            sql.eachRow(qHist, params) { row ->
                rows++; affected.add(((Number) row['pid']).longValue())
            }
        }

        if (wantExplain && explain.isEmpty()) {
            StringBuilder e = new StringBuilder()
            DatabaseUtil.withSql(resource) { Sql sql ->
                e.append('--- current-version query, first chunk ---\n')
                sql.eachRow('EXPLAIN (ANALYZE, BUFFERS) ' + qCur, params) { row ->
                    e.append(row[0] as String).append('\n')
                }
                e.append('\n--- historical-version query, first chunk ---\n')
                sql.eachRow('EXPLAIN (ANALYZE, BUFFERS) ' + qHist, params) { row ->
                    e.append(row[0] as String).append('\n')
                }
            }
            explain = e.toString()
        }
    }
    Map<String, Object> out = new LinkedHashMap<String, Object>()
    out.put('affected', affected)
    out.put('rows', rows)
    out.put('explain', explain)
    return out
}

// =============================================================================
//  VARIANTS B and C - return affected PAGE IDS only
//
//    SELECT p.contentid FROM content p
//    WHERE <page filters>
//      AND ( EXISTS (current version of p matches)
//         OR EXISTS (any historical version of p matches) )
//
//  Two things stop work early here that the row variants cannot:
//    - each EXISTS stops at the FIRST matching row instead of returning all
//    - the outer OR short-circuits, so a page whose CURRENT version matches
//      never has its history read at all
//  Answers the SCOPE question exactly: is this page affected, yes or no.
// =============================================================================

Map<String, Object> runExistsVariant(List<Long> pageIds, List<String> macroNames, boolean useRegex,
                                     boolean wantExplain) {
    Set<Long> affected = new LinkedHashSet<Long>()
    String explain = ''
    String resource = DB_RESOURCE
    int timeoutMs = SQL_TIMEOUT_SECONDS * 1000

    for (int from = 0; from < pageIds.size(); from += CHUNK_SIZE) {
        int to = Math.min(from + CHUNK_SIZE, pageIds.size())
        List<Long> slice = pageIds.subList(from, to)

        Map<String, Object> params = new LinkedHashMap<String, Object>()
        String matchWhere = useRegex ? regexWhere(macroNames, params) : likeWhere(macroNames, params)
        List<String> ph = new ArrayList<String>()
        for (int i = 0; i < slice.size(); i++) { ph.add(':p' + i); params.put('p' + i, slice.get(i)) }
        String inList = ph.join(', ')

        String q = 'SELECT p.contentid AS pid FROM content p ' +
                   'WHERE p.contentid IN (' + inList + ') AND (' +
                   ' EXISTS (SELECT 1 FROM bodycontent bc WHERE bc.contentid = p.contentid AND ' +
                   matchWhere + ')' +
                   ' OR EXISTS (SELECT 1 FROM content v ' +
                   ' JOIN bodycontent bc ON bc.contentid = v.contentid ' +
                   ' WHERE v.prevver = p.contentid AND ' + matchWhere + ')' +
                   ')'

        DatabaseUtil.withSql(resource) { Sql sql ->
            if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)
            sql.eachRow(q, params) { row -> affected.add(((Number) row['pid']).longValue()) }
        }

        if (wantExplain && explain.isEmpty()) {
            StringBuilder e = new StringBuilder()
            DatabaseUtil.withSql(resource) { Sql sql ->
                e.append('--- exists query, first chunk ---\n')
                sql.eachRow('EXPLAIN (ANALYZE, BUFFERS) ' + q, params) { row ->
                    e.append(row[0] as String).append('\n')
                }
            }
            explain = e.toString()
        }
    }
    Map<String, Object> out = new LinkedHashMap<String, Object>()
    out.put('affected', affected)
    out.put('rows', 0)          // this shape returns one row per page, by design
    out.put('explain', explain)
    return out
}

// =============================================================================
//  MAIN
// =============================================================================

StringBuilder outp = new StringBuilder()
StringBuilder html = new StringBuilder()

try {
    List<String> macroNames = sourceMacroNames()
    if (macroNames.isEmpty()) {
        return '<pre>MIGRATIONS has no source macro names - nothing to benchmark.</pre>'
    }

    outp.append('BENCHMARK - macro discovery query shapes\n')
    outp.append('================================================================\n')
    outp.append('  spaces        : ').append(SPACE_KEYS.isEmpty() ? 'ALL (unbounded)' : SPACE_KEYS.join(', ')).append('\n')
    outp.append('  source macros : ').append(macroNames.size()).append('\n')
    outp.append('  chunk size    : ').append(CHUNK_SIZE).append('\n')
    outp.append('  runs each     : ').append(RUNS).append('\n\n')

    outp.append('INDEXES\n')
    outp.append('================================================================\n')
    outp.append(indexReport())
    outp.append('  The historical branch of every variant depends on content.prevver.\n')
    outp.append('  If no index covers it, that branch scans content and no query\n')
    outp.append('  shape will help - that is the first thing to check above.\n\n')

    outp.append('BODY TYPES\n')
    outp.append('================================================================\n')
    outp.append(bodyTypeReport())
    outp.append('  More than one bodytypeid means "AND bc.bodytypeid = <storage>"\n')
    outp.append('  would cut the rows the match ever sees. One value means it is\n')
    outp.append('  pointless.\n\n')

    // ---- shared page set ---------------------------------------------------
    Map<String, Object> pageInfo = new LinkedHashMap<String, Object>()
    long t0 = System.currentTimeMillis()
    List<Long> pageIds = pageIdsInScope(pageInfo)
    long pageMs = System.currentTimeMillis() - t0
    outp.append('PAGE SET (shared by every variant)\n')
    outp.append('================================================================\n')
    outp.append('  pages in scope: ').append(pageIds.size())
        .append('   resolved in ').append(pageMs).append(' ms\n\n')
    if (pageIds.isEmpty()) {
        return '<pre>' + esc(outp.toString()) + '</pre>'
    }

    // ---- variants ----------------------------------------------------------
    List<Variant> variants = new ArrayList<Variant>()
    Map<String, String> labels = new LinkedHashMap<String, String>()
    labels.put('A', 'rows,   LIKE   (current Find-Macros shape)')
    labels.put('B', 'exists, LIKE   (page-level, short-circuits)')
    labels.put('C', 'exists, regex  (page-level, one pass per body)')
    labels.put('D', 'rows,   regex')

    for (String id : RUN_VARIANTS) {
        Variant v = new Variant()
        v.id = id
        v.label = labels.get(id) == null ? id : labels.get(id)
        try {
            for (int r = 0; r < RUNS; r++) {
                boolean wantExplain = (SHOW_EXPLAIN && r == RUNS - 1)
                long start = System.nanoTime()
                Map<String, Object> res
                if (id == 'A')      res = runRowVariant(pageIds, macroNames, false, wantExplain)
                else if (id == 'D') res = runRowVariant(pageIds, macroNames, true,  wantExplain)
                else if (id == 'B') res = runExistsVariant(pageIds, macroNames, false, wantExplain)
                else if (id == 'C') res = runExistsVariant(pageIds, macroNames, true,  wantExplain)
                else break
                long ms = (long) ((System.nanoTime() - start) / 1000000L)
                v.millis.add(ms)
                Set<Long> aff = (Set<Long>) res.get('affected')
                v.affectedPages = aff.size()
                v.versionRows = ((Integer) res.get('rows')).intValue()
                String ex = (String) res.get('explain')
                if (ex != null && !ex.isEmpty()) v.explain = ex
            }
        } catch (Exception e) {
            v.error = e.getClass().getSimpleName() + ': ' + e.getMessage()
        }
        variants.add(v)
    }

    // ---- results -----------------------------------------------------------
    outp.append('RESULTS\n')
    outp.append('================================================================\n')
    outp.append(String.format('  %-4s %-46s %-10s %-12s %s%n',
            'VAR', 'SHAPE', 'PAGES', 'VERSION ROWS', 'MILLISECONDS PER RUN'))
    for (Variant v : variants) {
        if (!v.error.isEmpty()) {
            outp.append(String.format('  %-4s %-46s %s%n', v.id, v.label, 'FAILED: ' + v.error))
            continue
        }
        outp.append(String.format('  %-4s %-46s %-10s %-12s %s%n', v.id, v.label,
                v.affectedPages as String,
                v.versionRows == 0 ? '-' : (v.versionRows as String),
                v.millis.join(', ')))
    }

    // ---- correctness cross-check ------------------------------------------
    outp.append('\nCROSS-CHECK\n')
    outp.append('================================================================\n')
    Integer expected = null
    boolean mismatch = false
    for (Variant v : variants) {
        if (!v.error.isEmpty()) continue
        if (expected == null) expected = v.affectedPages
        else if (expected.intValue() != v.affectedPages) mismatch = true
    }
    if (expected == null) {
        outp.append('  every variant failed - nothing to compare\n')
    } else if (mismatch) {
        outp.append('  *** VARIANTS DISAGREE on the number of affected pages ***\n')
        outp.append('  A faster query that finds fewer pages is not faster, it is wrong.\n')
        outp.append('  Do not adopt a shape until the counts match.\n')
    } else {
        outp.append('  all variants agree: ').append(plural(expected.intValue(), 'affected page'))
            .append('\n  Timings are therefore comparable.\n')
    }

    outp.append('\nREADING THE TIMINGS\n')
    outp.append('================================================================\n')
    outp.append('  The FIRST run of the first variant reads from disk; later runs read\n')
    outp.append('  from shared buffers. Compare the LAST run of each variant, and if\n')
    outp.append('  two are close, re-run with RUN_VARIANTS reversed to confirm the\n')
    outp.append('  ordering is not what produced the difference.\n')

    // ---- explain plans -----------------------------------------------------
    if (SHOW_EXPLAIN) {
        for (Variant v : variants) {
            if (v.explain == null || v.explain.isEmpty()) continue
            html.append('<h3>EXPLAIN (ANALYZE, BUFFERS) - variant ').append(v.id).append('</h3>')
                .append('<div style="max-height:320px;overflow:auto;border:1px solid #ccc;resize:vertical">')
                .append('<pre style="margin:0">').append(esc(v.explain)).append('</pre></div>')
        }
        html.append('<p style="font-size:90%">Look for: <b>Seq Scan on bodycontent</b> (the match ')
            .append('read the whole table), <b>Index Scan using ... on content</b> for prevver ')
            .append('(the historical branch used an index), and the <b>shared read</b> counts - ')
            .append('fewer buffers read is the durable measure, less sensitive to caching than time.</p>')
    }

    log.warn("Macro query benchmark: ${variants.size()} variant(s) over ${pageIds.size()} page(s)")
    return '<pre>' + esc(outp.toString()) + '</pre>' + html.toString()

} catch (Throwable fatal) {
    log.error('Benchmark failed', fatal)
    outp.append('\nTERMINATED: ').append(fatal.getClass().getName()).append(': ')
        .append(fatal.getMessage()).append('\n')
    Throwable c = fatal.getCause()
    while (c != null) {
        outp.append('  caused by ').append(c.getClass().getName()).append(': ')
            .append(c.getMessage()).append('\n')
        c = c.getCause()
    }
    return '<pre>' + esc(outp.toString()) + '</pre>'
}
