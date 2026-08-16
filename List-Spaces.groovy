/*
 * SPACE INVENTORY WITH MACRO COUNTS       ScriptRunner Console, READ ONLY
 *
 * Lists every space and how much migration work it actually holds, so runs can
 * be batched by real workload rather than by however the space directory
 * happens to paginate.
 *
 * Source macro names are extracted from the engine's MIGRATIONS list, pasted in
 * below as-is - no retyping 50 names, and no chance of the two drifting apart.
 * Commented-out entries are ignored, exactly as the engine ignores them.
 *
 * Two queries only:
 *   1. all spaces                    (cheap - no body scan)
 *   2. ONE grouped scan of bodycontent counting matches per space
 * So counting across every space costs a single sequential scan in total, not
 * one scan per space and not one per macro.
 */

import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
@Field String DB_RESOURCE = 'ConfluenceDB'

/*
 * Paste the engine's MIGRATIONS list between the triple quotes - the whole
 * block, brackets and all. Triple-single-quotes do NOT interpolate, so $ and
 * quotes inside the pasted config are safe.
 *
 * Leave empty to use MACROS_MANUAL instead.
 */
@Field String MIGRATIONS_PASTE = '''

'''

// Used only when MIGRATIONS_PASTE is empty.
@Field List<String> MACROS_MANUAL = ['qualification-table']

// Spaces per generated batch. Ignored when BATCH_TARGET_ROWS is set.
@Field int BATCH_SIZE = 5

// >0 = build batches by cumulative version rows instead of a fixed space count,
// which keeps run times even when a few spaces hold most of the work.
@Field int BATCH_TARGET_ROWS = 0

// false = list only spaces containing at least one source macro.
@Field boolean SHOW_EMPTY_SPACES = false

// Include personal spaces (keys starting with ~).
@Field boolean INCLUDE_PERSONAL = true
// ================================================================

@Field Pattern P_SOURCE_NAME = Pattern.compile("source\\s*:\\s*\\[\\s*name\\s*:\\s*'([^']+)'")

class SpaceInfo {
    String key = '', name = '', type = '', status = ''
    long pagesWithMacros, macroRows, historicalRows
}

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

String plural(int n, String one) {
    return n as String + ' ' + (n == 1 ? one : one + 's')
}

/**
 * Source macro names from a pasted MIGRATIONS block.
 * Lines whose first non-space characters are // are dropped first, so a
 * commented-out migration contributes nothing - matching the engine.
 */
List<String> extractSourceNames(String pasted) {
    try {
        List<String> names = new ArrayList<String>()
        if (pasted == null || pasted.trim().isEmpty()) return names
        StringBuilder live = new StringBuilder()
        for (String line : pasted.readLines()) {
            if (line.trim().startsWith('//')) continue
            live.append(line).append('\n')
        }
        Matcher m = P_SOURCE_NAME.matcher(live.toString())
        while (m.find()) {
            String nm = m.group(1)
            if (!names.contains(nm)) names.add(nm)
        }
        return names
    } catch (Exception e) {
        throw new RuntimeException('extractSourceNames failed: ' + e.getMessage(), e)
    }
}

/** How many source entries were commented out - reported, not silently ignored. */
int countCommentedSources(String pasted) {
    try {
        if (pasted == null || pasted.trim().isEmpty()) return 0
        int n = 0
        for (String line : pasted.readLines()) {
            if (!line.trim().startsWith('//')) continue
            if (P_SOURCE_NAME.matcher(line).find()) n++
        }
        return n
    } catch (Exception e) {
        throw new RuntimeException('countCommentedSources failed: ' + e.getMessage(), e)
    }
}

Map<String, SpaceInfo> loadSpaces() {
    try {
        Map<String, SpaceInfo> out = new LinkedHashMap<String, SpaceInfo>()
        String resource = DB_RESOURCE
        String query = 'SELECT spacekey, spacename, spacetype, spacestatus FROM spaces ORDER BY spacekey'
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query) { row ->
                SpaceInfo si = new SpaceInfo()
                si.key = row['spacekey'] as String
                si.name = row['spacename'] as String
                si.type = row['spacetype'] as String
                si.status = row['spacestatus'] as String
                out.put(si.key, si)
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('loadSpaces failed: ' + e.getMessage(), e)
    }
}

/** One grouped scan: per-space counts for all macros at once. */
void countMacrosPerSpace(List<String> macros, Map<String, SpaceInfo> spaces) {
    try {
        if (macros.isEmpty()) return
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        List<String> clauses = new ArrayList<String>()
        for (int i = 0; i < macros.size(); i++) {
            params.put('mp' + i, '%ac:name="' + likeEscape(macros.get(i)) + '"%')
            clauses.add('bc.body LIKE :mp' + i + " ESCAPE '\\\\'")
        }
        // pages_with counts DISTINCT pages: a historical hit maps back to its
        // current page through prevver, so a page already clean in its current
        // version but still carrying macros in history is counted once
        String query = 'SELECT s.spacekey AS sk, ' +
                       'count(*) AS macro_rows, ' +
                       'count(*) FILTER (WHERE c.prevver IS NOT NULL) AS hist_rows, ' +
                       'count(DISTINCT COALESCE(c.prevver, c.contentid)) AS pages_with ' +
                       'FROM content c ' +
                       'JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                       'JOIN spaces s ON s.spaceid = c.spaceid ' +
                       "WHERE c.contenttype IN ('PAGE','BLOGPOST') AND (" + clauses.join(' OR ') + ') ' +
                       'GROUP BY s.spacekey'
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query, params) { row ->
                SpaceInfo si = spaces.get(row['sk'] as String)
                if (si == null) return
                si.macroRows = ((Number) row['macro_rows']).longValue()
                si.historicalRows = ((Number) row['hist_rows']).longValue()
                si.pagesWithMacros = ((Number) row['pages_with']).longValue()
            }
        }
    } catch (Exception e) {
        throw new RuntimeException('countMacrosPerSpace failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MAIN
// =============================================================================

StringBuilder head = new StringBuilder()
StringBuilder html = new StringBuilder()

try {
    List<String> macros = extractSourceNames(MIGRATIONS_PASTE)
    int commented = countCommentedSources(MIGRATIONS_PASTE)
    boolean fromPaste = !macros.isEmpty()
    if (!fromPaste) macros = new ArrayList<String>(MACROS_MANUAL)

    head.append('Source macros: ').append(plural(macros.size(), 'name'))
        .append(fromPaste ? ' extracted from MIGRATIONS_PASTE' : ' from MACROS_MANUAL').append('\n')
    if (commented > 0) {
        head.append('  ').append(plural(commented, 'commented-out source entry'))
            .append(' ignored, as the engine would ignore them\n')
    }
    for (String m : macros) head.append('    ').append(m).append('\n')
    if (macros.isEmpty()) {
        head.append('\nNothing to count - paste the MIGRATIONS list or fill MACROS_MANUAL.\n')
        return '<pre>' + esc(head.toString()) + '</pre>'
    }

    Map<String, SpaceInfo> spaces = loadSpaces()
    countMacrosPerSpace(macros, spaces)

    List<SpaceInfo> rows = new ArrayList<SpaceInfo>()
    for (SpaceInfo si : spaces.values()) {
        if (!INCLUDE_PERSONAL && si.key.startsWith('~')) continue
        if (!SHOW_EMPTY_SPACES && si.pagesWithMacros == 0) continue
        rows.add(si)
    }
    Collections.sort(rows, new Comparator<SpaceInfo>() {
        @Override int compare(SpaceInfo a, SpaceInfo b) {
            int c = (b.macroRows <=> a.macroRows)
            return c != 0 ? c : (a.key <=> b.key)
        }
    })

    long totalRows = 0, totalPages = 0
    for (SpaceInfo si : rows) { totalRows += si.macroRows; totalPages += si.pagesWithMacros }

    head.append('\nSpaces in instance: ').append(spaces.size())
        .append('   with source macros: ').append(rows.size())
        .append('\nPages affected: ').append(totalPages)
        .append('   macro-bearing version rows: ').append(totalRows).append('\n')

    html.append('<h3>Spaces by workload</h3>')
    html.append('<div style="max-height:500px;overflow:auto;border:1px solid #ccc;resize:vertical">')
    html.append('<table border="1" cellpadding="4" cellspacing="0" style="font-size:90%"><tr>')
        .append('<th>Space Key</th><th>Name</th><th>Type</th><th>Status</th>')
        .append('<th>Pages with macros</th><th>Version rows</th><th>of which historical</th></tr>')
    for (SpaceInfo si : rows) {
        html.append('<tr><td>').append(esc(si.key))
            .append('</td><td>').append(esc(si.name))
            .append('</td><td>').append(esc(si.type))
            .append('</td><td>').append(esc(si.status))
            .append('</td><td>').append(si.pagesWithMacros)
            .append('</td><td>').append(si.macroRows)
            .append('</td><td>').append(si.historicalRows)
            .append('</td></tr>')
    }
    html.append('</table></div>')

    // ---- paste-ready batches ----------------------------------------------
    StringBuilder batches = new StringBuilder()
    int batchNo = 0
    List<String> keys = new ArrayList<String>()
    long bRows = 0, bPages = 0
    for (int i = 0; i < rows.size(); i++) {
        SpaceInfo si = rows.get(i)
        keys.add("'" + si.key + "'")
        bRows += si.macroRows
        bPages += si.pagesWithMacros
        boolean full = (BATCH_TARGET_ROWS > 0)
                ? (bRows >= BATCH_TARGET_ROWS)
                : (keys.size() >= BATCH_SIZE)
        if (full || i == rows.size() - 1) {
            batchNo++
            batches.append('// batch ').append(batchNo).append(' - ').append(bPages)
                   .append(' pages, ').append(bRows).append(' version rows\n')
                   .append('@Field List<String> SPACE_KEYS = [').append(keys.join(', ')).append(']\n\n')
            keys = new ArrayList<String>()
            bRows = 0; bPages = 0
        }
    }

    html.append('<h3>Paste-ready SPACE_KEYS batches (').append(batchNo).append(')</h3>')
        .append('<p style="font-size:90%">Only spaces that contain source macros. ')
        .append('Click inside, Ctrl+A, Ctrl+C.</p>')
        .append('<textarea readonly rows="18" style="width:100%;font-family:monospace;font-size:85%">')
        .append(esc(batches.toString())).append('</textarea>')

    log.warn("Space inventory: ${rows.size()} space(s) with macros, ${totalRows} version rows")
    return '<pre>' + esc(head.toString()) + '</pre>' + html.toString()

} catch (Throwable fatal) {
    log.error('Space inventory failed', fatal)
    StringBuilder err = new StringBuilder()
    err.append(head)
    err.append('\nTERMINATED: ').append(fatal.getClass().getName()).append(': ')
       .append(fatal.getMessage()).append('\n')
    Throwable c = fatal.getCause()
    while (c != null) {
        err.append('  caused by ').append(c.getClass().getName()).append(': ')
           .append(c.getMessage()).append('\n')
        c = c.getCause()
    }
    return '<pre>' + esc(err.toString()) + '</pre>'
}
