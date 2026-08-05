/*
 * =============================================================================
 *  BUILD THE value -> option-id MAP FOR AN EASYDROPDOWN SET   (READ ONLY)
 * =============================================================================
 *
 *  Input: three values copied from any page that already uses the target macro.
 *      set-id                (constant for the whole set)
 *      option-id + its value (one example pair, used to identify columns)
 *
 *  Output: a paste-ready perValueParams block covering EVERY option in the set,
 *  including values not currently used on any page.
 *
 *  HOW THE SCHEMA WORKS
 *  Active Objects relates tables by INTEGER primary key, not by the GUIDs that
 *  appear in page storage. So the set GUID is only present in the set table;
 *  the option table points back to it with an integer foreign key. The lookup
 *  is therefore two hops:
 *      1. set table   : row where <some column> = SET_ID  ->  its integer ID
 *      2. option table: rows where <fk column> = that ID   ->  option GUIDs
 *
 *  Tables whose name matches HISTORY_TABLE_HINT are excluded from the registry
 *  search: a change log contains the same GUIDs but only for options that were
 *  edited, so it looks like a match while being an incomplete answer.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

// Straight from a page's storage format.
final String SET_ID          = 'cd84552-7994-5a53-bec34a2434678ee'
final String KNOWN_OPTION_ID = 'da44632-6712-6eae-556a434365a3733'
final String KNOWN_VALUE     = 'Geschlossen'

// Escaped underscores: '/' is the LIKE escape char, so /_ means a literal _.
final String TABLE_PREFIX = 'AO/_1313EC/_%'

// Name fragments that mark a table as a change log rather than a registry.
final List<String> HISTORY_TABLE_HINT = ['HISTORY', 'AUDIT', 'CHANGE']

// The id of the entry in the engine's MIGRATIONS list, used in the printed hint.
final String MIGRATION_ID = 'blackboard-status'
// ================================================================

@Field String SCHEMA = 'public'

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}
String qt(String t) { return SCHEMA + '."' + t.replace('"', '""') + '"' }
String cut(Object v) {
    if (v == null) return '(null)'
    String s = v.toString().replace('\n', ' ')
    return s.length() <= 200 ? s : s.substring(0, 200) + '...'
}

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    // ---- phase 0: inventory, printed in full -------------------------------
    List<String> tables = new ArrayList<String>()
    sql.eachRow('''SELECT table_name FROM information_schema.tables
                   WHERE table_schema = :s AND table_type = 'BASE TABLE'
                     AND upper(table_name) LIKE upper(:p) ESCAPE '/'
                   ORDER BY table_name''', [s: SCHEMA, p: TABLE_PREFIX]) { row ->
        tables.add(row['table_name'] as String)
    }

    outp.append('PHASE 0 - tables in scope\n')
    outp.append('----------------------------------------------------------------\n')
    Map<String, List<List<String>>> colsByTable = new LinkedHashMap<String, List<List<String>>>()
    for (String t : tables) {
        long cnt = -1
        try {
            sql.eachRow('SELECT count(*) AS n FROM ' + qt(t)) { row -> cnt = ((Number) row['n']).longValue() }
        } catch (Exception ignored) { }
        List<List<String>> cols = new ArrayList<List<String>>()
        try {
            sql.eachRow('SELECT * FROM ' + qt(t) + ' LIMIT 1') { row ->
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    cols.add([row.getMetaData().getColumnName(i), row.getMetaData().getColumnTypeName(i)])
                }
            }
        } catch (Exception ignored) { }
        colsByTable.put(t, cols)

        boolean isHistory = false
        for (String h : HISTORY_TABLE_HINT) { if (t.toUpperCase().contains(h)) isHistory = true }
        outp.append(String.format('  %-38s %-8s cols: %s%s%n', t,
                cnt < 0 ? '?' : cnt as String,
                cols.isEmpty() ? '(unreadable)' : cols.collect { List<String> c -> c.get(0) }.join(', '),
                isHistory ? '   [excluded: change log]' : ''))
    }
    outp.append('\n')

    // ---- phase 1: locate the SET row ---------------------------------------
    outp.append('PHASE 1 - where does the set-id live?\n')
    outp.append('----------------------------------------------------------------\n')
    String setTable = null, setGuidCol = null
    for (String t : tables) {
        boolean isHistory = false
        for (String h : HISTORY_TABLE_HINT) { if (t.toUpperCase().contains(h)) isHistory = true }
        for (List<String> c : colsByTable.get(t)) {
            long cnt = 0
            try {
                sql.eachRow('SELECT count(*) AS n FROM ' + qt(t) +
                            ' WHERE "' + c.get(0) + '"::text = :v', [v: SET_ID]) { row ->
                    cnt = ((Number) row['n']).longValue()
                }
            } catch (Exception ignored) { }
            if (cnt > 0) {
                outp.append('  ').append(t).append('.').append(c.get(0)).append(' -> ').append(cnt)
                    .append(' row(s)').append(isHistory ? '   [change log, ignored]' : '').append('\n')
                if (!isHistory && setTable == null) { setTable = t; setGuidCol = c.get(0) }
            }
        }
    }
    if (setTable == null) {
        outp.append('\n  Set row not found outside change logs. Verify SET_ID is exact.\n')
        return
    }
    outp.append('\n  set table: ').append(setTable).append('.').append(setGuidCol).append('\n\n')

    // ---- phase 2: read the set row, capture its integer key ----------------
    outp.append('PHASE 2 - the set row\n')
    outp.append('----------------------------------------------------------------\n')
    Map<String, String> setRow = new LinkedHashMap<String, String>()
    sql.eachRow('SELECT * FROM ' + qt(setTable) + ' WHERE "' + setGuidCol + '"::text = :v', [v: SET_ID]) { row ->
        int n = row.getMetaData().getColumnCount()
        for (int i = 1; i <= n; i++) {
            String cn = row.getMetaData().getColumnName(i)
            setRow.put(cn, row.getObject(i) == null ? null : row.getObject(i).toString())
            outp.append('    ').append(String.format('%-24s', cn)).append(' = ').append(cut(row.getObject(i))).append('\n')
        }
    }
    outp.append('\n')

    // ---- phase 3: find the option table via the integer FK -----------------
    outp.append('PHASE 3 - option table, located by integer foreign key\n')
    outp.append('----------------------------------------------------------------\n')
    String optTable = null, fkCol = null, idCol = null, valCol = null

    for (Map.Entry<String, String> keyEntry : setRow.entrySet()) {
        String keyVal = keyEntry.getValue()
        if (keyVal == null || !(keyVal ==~ /\d+/)) continue     // integer keys only

        for (String t : tables) {
            if (t == setTable) continue
            boolean isHistory = false
            for (String h : HISTORY_TABLE_HINT) { if (t.toUpperCase().contains(h)) isHistory = true }
            if (isHistory) continue

            // does this table contain the known option-id at all?
            boolean hasKnown = false
            for (List<String> c : colsByTable.get(t)) {
                try {
                    sql.eachRow('SELECT count(*) AS n FROM ' + qt(t) +
                                ' WHERE "' + c.get(0) + '"::text = :v', [v: KNOWN_OPTION_ID]) { row ->
                        if (((Number) row['n']).longValue() > 0) hasKnown = true
                    }
                } catch (Exception ignored) { }
            }
            if (!hasKnown) continue

            for (List<String> c : colsByTable.get(t)) {
                long cnt = 0
                try {
                    sql.eachRow('SELECT count(*) AS n FROM ' + qt(t) +
                                ' WHERE "' + c.get(0) + '"::text = :v', [v: keyVal]) { row ->
                        cnt = ((Number) row['n']).longValue()
                    }
                } catch (Exception ignored) { }
                if (cnt > 0) {
                    outp.append('  ').append(t).append('.').append(c.get(0))
                        .append(' = ').append(setTable).append('.').append(keyEntry.getKey())
                        .append(' (').append(keyVal).append(')  -> ').append(cnt).append(' row(s)\n')
                    if (optTable == null) { optTable = t; fkCol = c.get(0) }
                }
            }
        }
    }

    if (optTable == null) {
        outp.append('\n  No option table found by integer FK.\n')
        outp.append('  Check PHASE 0: which table holds the known option-id ')
            .append(KNOWN_OPTION_ID).append('?\n')
        outp.append('  If it is only the change log, the registry may store options\n')
        outp.append('  differently - paste PHASE 0 output and we will read it directly.\n')
        return
    }
    outp.append('\n  option table: ').append(optTable).append(' via ').append(fkCol).append('\n\n')

    // ---- phase 4: identify columns and emit --------------------------------
    for (List<String> c : colsByTable.get(optTable)) {
        try {
            sql.eachRow('SELECT count(*) AS n FROM ' + qt(optTable) +
                        ' WHERE "' + c.get(0) + '"::text = :v', [v: KNOWN_OPTION_ID]) { row ->
                if (((Number) row['n']).longValue() > 0 && idCol == null) idCol = c.get(0)
            }
            sql.eachRow('SELECT count(*) AS n FROM ' + qt(optTable) +
                        ' WHERE "' + c.get(0) + '"::text = :v', [v: KNOWN_VALUE]) { row ->
                if (((Number) row['n']).longValue() > 0 && valCol == null) valCol = c.get(0)
            }
        } catch (Exception ignored) { }
    }
    outp.append('PHASE 4 - options in this set\n')
    outp.append('----------------------------------------------------------------\n')
    outp.append('  option-id column: ').append(idCol == null ? 'NOT FOUND' : idCol).append('\n')
    outp.append('  value column    : ').append(valCol == null ? 'NOT FOUND' : valCol).append('\n\n')

    List<List<String>> pairs = new ArrayList<List<String>>()
    sql.eachRow('SELECT * FROM ' + qt(optTable) + ' WHERE "' + fkCol + '"::text = :v',
                [v: setRow.get(setRow.keySet().find { String k -> setRow.get(k) ==~ /\d+/ })]) { row ->
        outp.append('  ---\n')
        int n = row.getMetaData().getColumnCount()
        for (int i = 1; i <= n; i++) {
            outp.append('    ').append(String.format('%-24s', row.getMetaData().getColumnName(i)))
                .append(' = ').append(cut(row.getObject(i))).append('\n')
        }
        if (idCol != null && valCol != null && row[valCol] != null && row[idCol] != null) {
            pairs.add([row[valCol].toString(), row[idCol].toString()])
        }
    }

    outp.append('\nPASTE INTO THE "').append(MIGRATION_ID).append('" MIGRATION\n')
    outp.append('----------------------------------------------------------------\n')
    if (pairs.isEmpty()) {
        outp.append('  No pairs built - resolve the NOT FOUND columns above first.\n')
    } else {
        outp.append('        staticParams   : [\'set-id\': \'').append(SET_ID).append('\'],\n')
        outp.append('        perValueParams : [\n')
        for (List<String> pr : pairs) {
            outp.append('            \'').append(pr.get(0)).append('\': [\'option-id\': \'')
                .append(pr.get(1)).append('\'],\n')
        }
        outp.append('        ],\n\n  ').append(pairs.size()).append(' option(s).\n')
    }
}

log.warn('EDM option mapping completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
