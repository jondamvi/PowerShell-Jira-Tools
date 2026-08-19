/*
 * =============================================================================
 *  DIAGNOSE SCOPE CHUNK                            ScriptRunner Script Console
 * =============================================================================
 *
 *  Read-only, CHEAP. Answers "why is this chunk slow?" without running it:
 *
 *    1. Shape of the window: version rows, body count, body BYTES (measured
 *       via pg_column_size - stored size, no detoasting, so this is fast even
 *       when the bodies are huge), largest bodies, fattest pages.
 *    2. EXPLAIN of the exact engine chunk query - detects a plan flip.
 *    3. A timed micro-probe over TIMED_PROBE_PAGES pages, extrapolated.
 *
 *  Interpretation:
 *    - huge bytes/page  -> data problem: bodies or histories are massive;
 *      lower SCOPE_CHUNK_PAGES proportionally, nothing is wrong with the query
 *    - plan shows Seq Scan on bodycontent or content -> plan problem: report it
 *    - micro-probe fast but real chunks slow -> report that too
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

// ---- CONFIG -----------------------------------------------------------------
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field List<String> SPACE_KEYS = ['OKR']
@Field List<String> MACRO_NAMES = [                // paste the 39 source names
        'qualification-table'
]
@Field List<String> INCLUDE_STATUSES = ['current']
@Field int WINDOW_OFFSET = 0        // the chunk that failed
@Field int WINDOW_PAGES = 500
@Field int TIMED_PROBE_PAGES = 25   // 0 disables the timed probe
@Field int SQL_TIMEOUT_SECONDS = 300
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

String candidateSql(List<Integer> sids, Map<String, Object> params, int off, int lim) {
    return 'SELECT cur.contentid AS pid FROM content cur ' +
           "WHERE cur.contenttype IN ('PAGE','BLOGPOST') AND cur.prevver IS NULL " +
           'AND cur.spaceid IN (' + inList('sid', sids, params) + ') ' +
           'AND cur.content_status IN (' + inList('st', INCLUDE_STATUSES, params) + ') ' +
           'ORDER BY cur.contentid LIMIT ' + lim + ' OFFSET ' + off
}

String existsQuery(List<Integer> sids, Map<String, Object> params, int off, int lim) {
    return 'SELECT c2.pid FROM (' + candidateSql(sids, params, off, lim) + ') c2 ' +
           'WHERE (EXISTS (SELECT 1 FROM bodycontent bcc ' +
           'WHERE bcc.contentid = c2.pid AND (' + likeClauses(MACRO_NAMES, 'bcc', 'c', params) + ')) ' +
           'OR EXISTS (SELECT 1 FROM content v ' +
           'JOIN bodycontent bch ON bch.contentid = v.contentid ' +
           'WHERE v.prevver = c2.pid AND (' + likeClauses(MACRO_NAMES, 'bch', 'h', params) + ')))'
}

StringBuilder out = new StringBuilder()

try {
    List<String> explain = new ArrayList<String>()

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.execute('SET statement_timeout = ' + (SQL_TIMEOUT_SECONDS * 1000))

        List<Integer> sids = new ArrayList<Integer>()
        Map<String, Object> pk = new LinkedHashMap<String, Object>()
        sql.eachRow('SELECT spaceid FROM spaces WHERE spacekey IN (' +
                inList('k', SPACE_KEYS, pk) + ')', pk) { row ->
            sids.add(((Number) row['spaceid']).intValue())
        }
        if (sids.isEmpty()) throw new IllegalStateException('No spaceids for ' + SPACE_KEYS)

        // ---- window shape: counts and stored bytes, NO detoasting -----------
        Map<String, Object> p1 = new LinkedHashMap<String, Object>()
        String shape = 'SELECT COUNT(*) AS bodies, ' +
                'COALESCE(SUM(pg_column_size(bc.body)), 0) AS bytes, ' +
                'COALESCE(MAX(pg_column_size(bc.body)), 0) AS maxb, ' +
                'SUM(CASE WHEN pg_column_size(bc.body) > 1048576 THEN 1 ELSE 0 END) AS over1mb ' +
                'FROM (' + candidateSql(sids, p1, WINDOW_OFFSET, WINDOW_PAGES) + ') c2 ' +
                'JOIN content v ON (v.contentid = c2.pid OR v.prevver = c2.pid) ' +
                'JOIN bodycontent bc ON bc.contentid = v.contentid'
        long bodies = 0, bytes = 0, maxb = 0, over1mb = 0
        sql.eachRow(shape, p1) { r ->
            bodies = ((Number) r['bodies']).longValue()
            bytes = ((Number) r['bytes']).longValue()
            maxb = ((Number) r['maxb']).longValue()
            over1mb = r['over1mb'] == null ? 0 : ((Number) r['over1mb']).longValue()
        }

        out.append('scope: ').append(SPACE_KEYS.toString())
           .append('   window: offsets ').append(WINDOW_OFFSET).append('..')
           .append(WINDOW_OFFSET + WINDOW_PAGES - 1).append('\n\n')
        out.append('WINDOW SHAPE (stored sizes, no detoasting)\n')
        out.append('  bodies (current + historical): ').append(bodies).append('\n')
        out.append('  total stored body bytes: ').append(String.format('%,d', bytes))
           .append('  (').append(String.format('%.1f', bytes / 1048576.0d)).append(' MB)\n')
        out.append('  avg per body: ').append(bodies > 0 ? String.format('%,d', (long) (bytes / bodies)) : '-')
           .append('   max body: ').append(String.format('%,d', maxb))
           .append('   bodies over 1 MB: ').append(over1mb).append('\n')

        // ---- fattest pages in window -----------------------------------------
        Map<String, Object> p2 = new LinkedHashMap<String, Object>()
        String fat = 'SELECT c2.pid AS pid, COUNT(*) AS versions, ' +
                'SUM(pg_column_size(bc.body)) AS pbytes ' +
                'FROM (' + candidateSql(sids, p2, WINDOW_OFFSET, WINDOW_PAGES) + ') c2 ' +
                'JOIN content v ON (v.contentid = c2.pid OR v.prevver = c2.pid) ' +
                'JOIN bodycontent bc ON bc.contentid = v.contentid ' +
                'GROUP BY c2.pid ORDER BY SUM(pg_column_size(bc.body)) DESC LIMIT 10'
        out.append('\nTOP 10 PAGES BY STORED BODY BYTES\n')
        out.append(String.format('  %-14s %-10s %s%n', 'pageid', 'versions', 'total bytes'))
        sql.eachRow(fat, p2) { r ->
            out.append(String.format('  %-14d %-10d %,d%n',
                    ((Number) r['pid']).longValue(),
                    ((Number) r['versions']).longValue(),
                    ((Number) r['pbytes']).longValue()))
        }

        // ---- EXPLAIN the engine chunk query ----------------------------------
        Map<String, Object> p3 = new LinkedHashMap<String, Object>()
        String qe = existsQuery(sids, p3, WINDOW_OFFSET, WINDOW_PAGES)
        sql.eachRow('EXPLAIN ' + qe, p3) { r -> explain.add(r[0] as String) }

        // ---- timed micro-probe ------------------------------------------------
        if (TIMED_PROBE_PAGES > 0) {
            Map<String, Object> p4 = new LinkedHashMap<String, Object>()
            String qp = existsQuery(sids, p4, WINDOW_OFFSET, TIMED_PROBE_PAGES)
            long t0 = System.currentTimeMillis()
            int hits = 0
            sql.eachRow(qp, p4) { r -> hits++ }
            long ms = System.currentTimeMillis() - t0
            out.append('\nTIMED MICRO-PROBE\n')
            out.append('  ').append(TIMED_PROBE_PAGES).append(' pages from offset ')
               .append(WINDOW_OFFSET).append(': ').append(hits).append(' affected, ')
               .append(ms).append(' ms')
               .append('   -> ~').append(String.format('%,d', (long) (ms / (double) TIMED_PROBE_PAGES)))
               .append(' ms/page; a ').append(WINDOW_PAGES).append('-page chunk extrapolates to ~')
               .append(String.format('%,d', (long) (ms / (double) TIMED_PROBE_PAGES * WINDOW_PAGES / 1000)))
               .append(' s\n')
            out.append('  (extrapolation assumes uniform density; the shape table above says\n')
               .append('   whether that assumption holds)\n')
        }
    }

    StringBuilder page = new StringBuilder()
    page.append('<h3>Scope chunk diagnosis</h3>')
    page.append('<pre>').append(htmlEsc(out.toString())).append('</pre>')
    page.append('<h3>EXPLAIN - engine chunk query</h3>')
    page.append('<p style="font-size:90%">Good: index scans inside SubPlans. ')
        .append('Bad: <b>Seq Scan</b> on bodycontent or content anywhere in the plan.</p>')
    page.append('<pre>')
    for (String line : explain) page.append(htmlEsc(line)).append('\n')
    page.append('</pre>')
    return page.toString()

} catch (Exception e) {
    return '<pre>FAILED: ' + htmlEsc(e.getMessage()) + '</pre>'
}
