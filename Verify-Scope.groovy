/*
 * =============================================================================
 *  VERIFY SCOPE DISCOVERY  v2                      ScriptRunner Script Console
 * =============================================================================
 *
 *  Read-only. Benchmarks three discovery methods on the SAME scope and proves
 *  they agree where they must:
 *
 *    scope stats  total current pages and total historical version rows in the
 *                 configured spaces - the denominator every number below is
 *                 measured against.
 *    A   blind flat scan     the old query: version rows filtered by their OWN
 *                            spaceid. Kept as reference; known blind to
 *                            NULL-spaceid history. All matching bodies read.
 *    A2  fair flat scan      same exhaustive all-versions read, but versions
 *                            reach the scope through OWNERSHIP (contentid or
 *                            prevver = current page), so nothing is skipped.
 *                            This is "process all versions" done correctly,
 *                            with NO early exit - the honest baseline.
 *    B   EXISTS discovery    what the engine now runs: same ownership scoping,
 *                            but probing stops at the first matching version
 *                            per page.
 *
 *  A2 and B MUST return identical sets - they implement the same predicate,
 *  differing only in exhaustive vs early-exit evaluation. Any difference is a
 *  bug and is printed. A vs B differences are classified (blind spots).
 *
 *  BENCH_PER_MACRO simulates per-macro discovery ("Macro Usage" style): the B
 *  query once per macro name, ids unioned. The union must equal B; the summed
 *  time is the DB-side floor for any one-macro-at-a-time approach - a REST
 *  round-trip per macro per page batch only adds to it.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

// ---- CONFIG -----------------------------------------------------------------
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field List<String> SPACE_KEYS = ['TEST']          // REAL keys as in spaces.spacekey
@Field List<String> MACRO_NAMES = [                // source macro ac:name values
        'qualification-table'
]
@Field List<String> INCLUDE_STATUSES = ['current'] // must match the engine
@Field int SQL_TIMEOUT_SECONDS = 300
@Field boolean SHOW_EXPLAIN = true
@Field boolean BENCH_PER_MACRO = false             // adds one B-query per macro
// -----------------------------------------------------------------------------

String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String likeClauses(List<String> names, String alias, String tag, Map<String, Object> params) {
    List<String> clauses = new ArrayList<String>()
    int n = 0
    for (String name : names) {
        params.put('m' + tag + n, '%ac:name="' + likeEscape(name) + '"%')
        clauses.add(alias + '.body LIKE :m' + tag + n + " ESCAPE '\\'")
        n++
    }
    return clauses.join(' OR ')
}

String inList(String prefix, List<?> vals, Map<String, Object> params) {
    List<String> ph = new ArrayList<String>()
    for (int i = 0; i < vals.size(); i++) { ph.add(':' + prefix + i); params.put(prefix + i, vals.get(i)) }
    return ph.join(', ')
}

/** Scope filter on the CURRENT page row - shared by A2 and B. */
String currentPageScope(List<Integer> sids, Map<String, Object> params) {
    return "cur.contenttype IN ('PAGE','BLOGPOST') AND cur.prevver IS NULL " +
           'AND cur.spaceid IN (' + inList('sid', sids, params) + ') ' +
           'AND cur.content_status IN (' + inList('st', INCLUDE_STATUSES, params) + ')'
}

/** B: the engine's discovery. Early exit per page via EXISTS. */
String existsQuery(List<String> names, List<Integer> sids, Map<String, Object> params) {
    return 'SELECT c2.pid FROM (' +
           'SELECT cur.contentid AS pid FROM content cur WHERE ' +
           currentPageScope(sids, params) + ') c2 ' +
           'WHERE (EXISTS (SELECT 1 FROM bodycontent bcc ' +
           'WHERE bcc.contentid = c2.pid AND (' + likeClauses(names, 'bcc', 'c', params) + ')) ' +
           'OR EXISTS (SELECT 1 FROM content v ' +
           'JOIN bodycontent bch ON bch.contentid = v.contentid ' +
           'WHERE v.prevver = c2.pid AND (' + likeClauses(names, 'bch', 'h', params) + ')))'
}

StringBuilder out = new StringBuilder()

try {
    if (SPACE_KEYS.isEmpty() || MACRO_NAMES.isEmpty()) {
        return '<pre>Set SPACE_KEYS and MACRO_NAMES first.</pre>'
    }

    Set<Long> setA = new LinkedHashSet<Long>()
    Set<Long> setA2 = new LinkedHashSet<Long>()
    Set<Long> setB = new LinkedHashSet<Long>()
    Set<Long> setPM = new LinkedHashSet<Long>()
    List<String> explain = new ArrayList<String>()
    long tA = 0, tA2 = 0, tB = 0, tPM = 0
    long totalPages = 0, totalHistRows = 0

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.execute('SET statement_timeout = ' + (SQL_TIMEOUT_SECONDS * 1000))

        List<Integer> sids = new ArrayList<Integer>()
        Map<String, Object> pk = new LinkedHashMap<String, Object>()
        sql.eachRow('SELECT spaceid FROM spaces WHERE spacekey IN (' +
                inList('k', SPACE_KEYS, pk) + ')', pk) { row ->
            sids.add(((Number) row['spaceid']).intValue())
        }
        if (sids.isEmpty()) throw new IllegalStateException('No spaceids for ' + SPACE_KEYS)

        // ---- scope stats: sizes everything below is measured against --------
        Map<String, Object> ps = new LinkedHashMap<String, Object>()
        sql.eachRow('SELECT COUNT(*) AS n FROM content cur WHERE ' +
                currentPageScope(sids, ps), ps) { row ->
            totalPages = ((Number) row['n']).longValue()
        }
        Map<String, Object> ph = new LinkedHashMap<String, Object>()
        sql.eachRow('SELECT COUNT(*) AS n FROM content v WHERE v.prevver IN (' +
                'SELECT cur.contentid FROM content cur WHERE ' +
                currentPageScope(sids, ph) + ')', ph) { row ->
            totalHistRows = ((Number) row['n']).longValue()
        }

        // ---- A: blind flat scan (old predicate, reference) ------------------
        Map<String, Object> pa = new LinkedHashMap<String, Object>()
        String qa = 'SELECT c.contentid AS cid, c.prevver AS prevver ' +
                    'FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                    "WHERE c.contenttype IN ('PAGE','BLOGPOST') " +
                    'AND c.spaceid IN (' + inList('sid', sids, pa) + ') ' +
                    'AND (c.prevver IS NOT NULL OR c.content_status IN (' +
                    inList('st', INCLUDE_STATUSES, pa) + ')) ' +
                    'AND (' + likeClauses(MACRO_NAMES, 'bc', 'a', pa) + ')'
        long t0 = System.currentTimeMillis()
        sql.eachRow(qa, pa) { row ->
            Object prev = row['prevver']
            setA.add(prev == null ? ((Number) row['cid']).longValue() : ((Number) prev).longValue())
        }
        tA = System.currentTimeMillis() - t0

        // ---- A2: fair flat scan - ownership scoping, ALL versions read ------
        Map<String, Object> pa2 = new LinkedHashMap<String, Object>()
        String qa2 = 'SELECT DISTINCT cur.contentid AS pid ' +
                     'FROM content cur ' +
                     'JOIN content v ON (v.contentid = cur.contentid OR v.prevver = cur.contentid) ' +
                     'JOIN bodycontent bc ON bc.contentid = v.contentid ' +
                     'WHERE ' + currentPageScope(sids, pa2) + ' ' +
                     'AND (' + likeClauses(MACRO_NAMES, 'bc', 'f', pa2) + ')'
        t0 = System.currentTimeMillis()
        sql.eachRow(qa2, pa2) { row -> setA2.add(((Number) row['pid']).longValue()) }
        tA2 = System.currentTimeMillis() - t0

        // ---- B: EXISTS discovery (the engine's query) -----------------------
        Map<String, Object> pb = new LinkedHashMap<String, Object>()
        String qb = existsQuery(MACRO_NAMES, sids, pb)
        if (SHOW_EXPLAIN) {
            sql.eachRow('EXPLAIN ' + qb, pb) { row -> explain.add(row[0] as String) }
        }
        t0 = System.currentTimeMillis()
        sql.eachRow(qb, pb) { row -> setB.add(((Number) row['pid']).longValue()) }
        tB = System.currentTimeMillis() - t0

        // ---- per-macro simulation -------------------------------------------
        if (BENCH_PER_MACRO) {
            t0 = System.currentTimeMillis()
            for (String name : MACRO_NAMES) {
                Map<String, Object> pp = new LinkedHashMap<String, Object>()
                String qp = existsQuery([name], sids, pp)
                sql.eachRow(qp, pp) { row -> setPM.add(((Number) row['pid']).longValue()) }
            }
            tPM = System.currentTimeMillis() - t0
        }

        // ---- report ---------------------------------------------------------
        out.append('scope: ').append(SPACE_KEYS.toString())
           .append('   macros: ').append(MACRO_NAMES.size())
           .append('   statuses: ').append(INCLUDE_STATUSES.toString()).append('\n')
        out.append('pages in scope: ').append(totalPages)
           .append('   historical version rows: ').append(totalHistRows).append('\n\n')
        out.append(String.format('%-52s %8d pages  %7d ms%n', 'A   blind flat scan (old, reference)', setA.size(), tA))
        out.append(String.format('%-52s %8d pages  %7d ms%n', 'A2  fair flat scan (all versions, ownership)', setA2.size(), tA2))
        out.append(String.format('%-52s %8d pages  %7d ms%n', 'B   EXISTS discovery (early exit)', setB.size(), tB))
        if (BENCH_PER_MACRO) {
            out.append(String.format('%-52s %8d pages  %7d ms%n',
                    'PM  per-macro (' + MACRO_NAMES.size() + ' x B, union)', setPM.size(), tPM))
        }

        // ---- A2 vs B: MUST be identical -------------------------------------
        Set<Long> a2NotB = new LinkedHashSet<Long>(setA2); a2NotB.removeAll(setB)
        Set<Long> bNotA2 = new LinkedHashSet<Long>(setB); bNotA2.removeAll(setA2)
        if (a2NotB.isEmpty() && bNotA2.isEmpty()) {
            out.append('\nA2 == B: IDENTICAL, as required. Timing difference is pure\n')
               .append('exhaustive-vs-early-exit; both read the same universe of pages.\n')
        } else {
            out.append('\nA2 vs B MISMATCH - BUG, INVESTIGATE:\n')
            out.append('  in A2 not B: ').append(a2NotB.toString()).append('\n')
            out.append('  in B not A2: ').append(bNotA2.toString()).append('\n')
        }
        if (BENCH_PER_MACRO) {
            Set<Long> pmDiff = new LinkedHashSet<Long>(setPM)
            pmDiff.removeAll(setB)
            Set<Long> bDiff = new LinkedHashSet<Long>(setB)
            bDiff.removeAll(setPM)
            if (pmDiff.isEmpty() && bDiff.isEmpty()) {
                out.append('PM union == B: per-macro finds the same pages, at ')
                   .append(tB > 0 ? String.format('%.1fx', tPM / (double) tB) : '?')
                   .append(' the cost.\n')
            } else {
                out.append('PM vs B MISMATCH - BUG, INVESTIGATE: only-PM ')
                   .append(pmDiff.toString()).append(' only-B ').append(bDiff.toString()).append('\n')
            }
        }

        // ---- A vs B: blind-spot classification ------------------------------
        Set<Long> onlyA = new LinkedHashSet<Long>(setA); onlyA.removeAll(setB)
        Set<Long> onlyB = new LinkedHashSet<Long>(setB); onlyB.removeAll(setA)
        out.append('\nold blind scan vs B:   only in A: ').append(onlyA.size())
           .append('    only in B: ').append(onlyB.size()).append('\n')

        if (!onlyA.isEmpty()) {
            out.append('\nONLY IN A - old scan counted these, correct discovery excludes them:\n')
            out.append(String.format('  %-12s %-10s %-10s %-8s %s%n',
                    'pageid', 'type', 'status', 'space', 'classification'))
            for (Long pid : onlyA) {
                Map<String, Object> p1 = new LinkedHashMap<String, Object>(); p1.put('id', pid)
                String type = null, status = null; Object sid = null; boolean found = false
                sql.eachRow('SELECT contenttype, content_status, spaceid FROM content WHERE contentid = :id', p1) { r ->
                    found = true; type = r['contenttype'] as String
                    status = r['content_status'] as String; sid = r['spaceid']
                }
                String cls
                if (!found)                                        cls = 'live row GONE (orphaned history)'
                else if (!INCLUDE_STATUSES.contains(status))       cls = 'live status excluded'
                else if (sid == null || !sids.contains(((Number) sid).intValue()))
                                                                   cls = 'live row outside scope spaces'
                else                                               cls = 'OTHER - INVESTIGATE'
                out.append(String.format('  %-12d %-10s %-10s %-8s %s%n',
                        pid, type == null ? '-' : type, status == null ? '-' : status,
                        sid == null ? 'NULL' : sid.toString(), cls))
            }
        }

        if (!onlyB.isEmpty()) {
            out.append('\nONLY IN B - found only through NULL-spaceid history if expected:\n')
            out.append(String.format('  %-12s %-12s %-14s %s%n',
                    'pageid', 'hist match', 'null-sid hist', 'classification'))
            for (Long pid : onlyB) {
                Map<String, Object> p2 = new LinkedHashMap<String, Object>(); p2.put('id', pid)
                int histMatches = 0, nullSidMatches = 0
                String qd = 'SELECT v.spaceid AS sid FROM content v ' +
                            'JOIN bodycontent bch ON bch.contentid = v.contentid ' +
                            'WHERE v.prevver = :id AND (' +
                            likeClauses(MACRO_NAMES, 'bch', 'd', p2) + ')'
                sql.eachRow(qd, p2) { r ->
                    histMatches++
                    if (r['sid'] == null) nullSidMatches++
                }
                String cls = (histMatches > 0 && histMatches == nullSidMatches)
                        ? 'macro only in NULL-spaceid history (expected)'
                        : 'OTHER - INVESTIGATE'
                out.append(String.format('  %-12d %-12d %-14d %s%n', pid, histMatches, nullSidMatches, cls))
            }
        }
    }

    StringBuilder page = new StringBuilder()
    page.append('<h3>Scope discovery verification v2</h3>')
    page.append('<pre>').append(htmlEsc(out.toString())).append('</pre>')
    if (SHOW_EXPLAIN) {
        page.append('<h3>EXPLAIN - B (engine discovery)</h3>')
        page.append('<p style="font-size:90%">Good: index scans on content/bodycontent inside ')
            .append('the subplans. Bad: <b>Seq Scan on bodycontent</b> or a hashed subplan over ')
            .append('all of bodycontent - if present, report it before trusting any timing.</p>')
        page.append('<pre>')
        for (String line : explain) page.append(htmlEsc(line)).append('\n')
        page.append('</pre>')
    }
    return page.toString()

} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
