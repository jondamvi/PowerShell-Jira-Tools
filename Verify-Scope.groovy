/*
 * =============================================================================
 *  VERIFY SCOPE DISCOVERY                          ScriptRunner Script Console
 * =============================================================================
 *
 *  Read-only. Confirms the engine's rewritten discovery (EXISTS probe driven
 *  from current pages) against the old flat content-JOIN-bodycontent scan, on
 *  the SAME scope, in one run:
 *
 *    1. EXPLAIN of the new query - shows whether the planner uses index
 *       probes per page (good) or flips to scanning bodycontent (bad).
 *    2. Old predicate  -> page-id set A
 *    3. New predicate  -> page-id set B
 *    4. A\B and B\A, every id classified by WHY it differs. Expected classes:
 *          only in A: live row is trashed/deleted/draft, or gone entirely
 *                     (old scan counted orphaned/excluded-status history)
 *          only in B: macros found ONLY in NULL-spaceid history
 *                     (old scan's spaceid filter was blind to those rows)
 *       Anything classified OTHER is a bug in one of the predicates.
 *
 *  Run on the small test space first (expected pages known), then the big one.
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
// -----------------------------------------------------------------------------

String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String likeClauses(String alias, String tag, Map<String, Object> params) {
    List<String> clauses = new ArrayList<String>()
    int n = 0
    for (String name : MACRO_NAMES) {
        params.put('m' + tag + n, '%ac:name="' + likeEscape(name) + '"%')
        clauses.add(alias + '.body LIKE :m' + tag + n + " ESCAPE '\\'")
        n++
    }
    return clauses.join(' OR ')
}

List<Integer> spaceIds(Sql sql) {
    List<Integer> ids = new ArrayList<Integer>()
    List<String> ph = new ArrayList<String>()
    Map<String, Object> p = new LinkedHashMap<String, Object>()
    for (int i = 0; i < SPACE_KEYS.size(); i++) { ph.add(':k' + i); p.put('k' + i, SPACE_KEYS.get(i)) }
    sql.eachRow('SELECT spaceid FROM spaces WHERE spacekey IN (' + ph.join(', ') + ')', p) { row ->
        ids.add(((Number) row['spaceid']).intValue())
    }
    return ids
}

String inList(String prefix, List<?> vals, Map<String, Object> params) {
    List<String> ph = new ArrayList<String>()
    for (int i = 0; i < vals.size(); i++) { ph.add(':' + prefix + i); params.put(prefix + i, vals.get(i)) }
    return ph.join(', ')
}

StringBuilder out = new StringBuilder()

try {
    if (SPACE_KEYS.isEmpty() || MACRO_NAMES.isEmpty()) {
        return '<pre>Set SPACE_KEYS and MACRO_NAMES first.</pre>'
    }

    Set<Long> setA = new LinkedHashSet<Long>()   // old flat-scan predicate
    Set<Long> setB = new LinkedHashSet<Long>()   // new EXISTS predicate
    List<String> explain = new ArrayList<String>()
    long tA = 0, tB = 0

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.execute('SET statement_timeout = ' + (SQL_TIMEOUT_SECONDS * 1000))

        List<Integer> sids = spaceIds(sql)
        if (sids.isEmpty()) throw new IllegalStateException('No spaceids for ' + SPACE_KEYS)

        // ---- A: old flat scan (engine + List-Spaces shape) ------------------
        Map<String, Object> pa = new LinkedHashMap<String, Object>()
        String qa = 'SELECT c.contentid AS cid, c.prevver AS prevver ' +
                    'FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                    "WHERE c.contenttype IN ('PAGE','BLOGPOST') " +
                    'AND c.spaceid IN (' + inList('sid', sids, pa) + ') ' +
                    'AND (c.prevver IS NOT NULL OR c.content_status IN (' +
                    inList('st', INCLUDE_STATUSES, pa) + ')) ' +
                    'AND (' + likeClauses('bc', 'a', pa) + ')'
        long t0 = System.currentTimeMillis()
        sql.eachRow(qa, pa) { row ->
            Object prev = row['prevver']
            setA.add(prev == null ? ((Number) row['cid']).longValue() : ((Number) prev).longValue())
        }
        tA = System.currentTimeMillis() - t0

        // ---- B: new discovery (current-page driven, EXISTS probes) ----------
        Map<String, Object> pb = new LinkedHashMap<String, Object>()
        String qb = 'SELECT c2.pid FROM (' +
                    'SELECT cur.contentid AS pid FROM content cur ' +
                    "WHERE cur.contenttype IN ('PAGE','BLOGPOST') AND cur.prevver IS NULL " +
                    'AND cur.spaceid IN (' + inList('sid', sids, pb) + ') ' +
                    'AND cur.content_status IN (' + inList('st', INCLUDE_STATUSES, pb) + ')' +
                    ') c2 ' +
                    'WHERE (EXISTS (SELECT 1 FROM bodycontent bcc ' +
                    'WHERE bcc.contentid = c2.pid AND (' + likeClauses('bcc', 'c', pb) + ')) ' +
                    'OR EXISTS (SELECT 1 FROM content v ' +
                    'JOIN bodycontent bch ON bch.contentid = v.contentid ' +
                    'WHERE v.prevver = c2.pid AND (' + likeClauses('bch', 'h', pb) + ')))'
        if (SHOW_EXPLAIN) {
            sql.eachRow('EXPLAIN ' + qb, pb) { row -> explain.add(row[0] as String) }
        }
        t0 = System.currentTimeMillis()
        sql.eachRow(qb, pb) { row -> setB.add(((Number) row['pid']).longValue()) }
        tB = System.currentTimeMillis() - t0

        // ---- diff + classification ------------------------------------------
        out.append('scope: ').append(SPACE_KEYS.toString())
           .append('   macros: ').append(MACRO_NAMES.size())
           .append('   statuses: ').append(INCLUDE_STATUSES.toString()).append('\n\n')
        out.append(String.format('%-46s %8d pages  %6d ms%n', 'A  old flat scan', setA.size(), tA))
        out.append(String.format('%-46s %8d pages  %6d ms%n', 'B  new EXISTS discovery', setB.size(), tB))

        Set<Long> onlyA = new LinkedHashSet<Long>(setA); onlyA.removeAll(setB)
        Set<Long> onlyB = new LinkedHashSet<Long>(setB); onlyB.removeAll(setA)
        out.append('\nonly in A: ').append(onlyA.size())
           .append('    only in B: ').append(onlyB.size()).append('\n')

        // classify A\B: what does the live row look like?
        if (!onlyA.isEmpty()) {
            out.append('\nONLY IN A - old scan counted these, new discovery excludes them:\n')
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

        // classify B\A: is the macro only in NULL-spaceid history?
        if (!onlyB.isEmpty()) {
            out.append('\nONLY IN B - new discovery found these, old scan was blind to them:\n')
            out.append(String.format('  %-12s %-14s %-14s %s%n',
                    'pageid', 'hist match', 'null-sid hist', 'classification'))
            for (Long pid : onlyB) {
                Map<String, Object> p2 = new LinkedHashMap<String, Object>(); p2.put('id', pid)
                int histMatches = 0, nullSidMatches = 0
                String qd = 'SELECT v.spaceid AS sid FROM content v ' +
                            'JOIN bodycontent bch ON bch.contentid = v.contentid ' +
                            'WHERE v.prevver = :id AND (' + likeClauses('bch', 'd', p2) + ')'
                sql.eachRow(qd, p2) { r ->
                    histMatches++
                    if (r['sid'] == null) nullSidMatches++
                }
                String cls = (histMatches > 0 && histMatches == nullSidMatches)
                        ? 'macro only in NULL-spaceid history (expected)'
                        : 'OTHER - INVESTIGATE'
                out.append(String.format('  %-12d %-14d %-14d %s%n', pid, histMatches, nullSidMatches, cls))
            }
        }

        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            out.append('\nSets IDENTICAL. On this scope the old blind spots do not occur;\n')
            out.append('the rewrite changes performance only.\n')
        }
    }

    StringBuilder page = new StringBuilder()
    page.append('<h3>Scope discovery verification</h3>')
    page.append('<pre>').append(htmlEsc(out.toString())).append('</pre>')
    if (SHOW_EXPLAIN) {
        page.append('<h3>EXPLAIN - new discovery query</h3>')
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
