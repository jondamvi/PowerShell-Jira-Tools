/*
 * =============================================================================
 *  BUILD THE value -> option-id MAP FOR AN EASYDROPDOWN SET   (READ ONLY)
 * =============================================================================
 *
 *  Input: what you can read off any page that already uses the target macro.
 *      set-id                (constant for the whole set)
 *      option-id + its value (one example pair, used to identify the columns)
 *
 *  Output: a paste-ready perValueParams block for the replacement engine,
 *  covering EVERY option in the set - including values not currently used on
 *  any page, which is what harvesting from pages cannot give you.
 *
 *  How it works, without assuming any schema:
 *    1. find which AO_1313EC_* table has a text column containing the set-id
 *       in more than one row -> that is the option table
 *    2. within it, find the column holding the known option-id  -> id column
 *       and the column holding the known value                  -> value column
 *    3. read every row for that set-id and emit the pairs
 *
 *  Re-runnable on a fresh instance: the set-id and one example pair change,
 *  nothing else.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

// Straight from a page's storage format.
final String SET_ID           = 'cd84552-7994-5a53-bec34a2434678ee'
final String KNOWN_OPTION_ID  = 'da44632-6712-6eae-556a434365a3733'
final String KNOWN_VALUE      = 'Geschlossen'

// Table name prefix for the app. AO_1313EC_ is EasyDropDown on this instance.
final String TABLE_PREFIX = 'AO/_1313EC/_%'

// Emit the config block for this migration id.
final String MIGRATION_ID = 'blackboard-status'
// ================================================================

@Field String SCHEMA = 'public'
@Field int PREVIEW = 200

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}
String qt(String t) { return SCHEMA + '."' + t.replace('"', '""') + '"' }
String cut(Object v) {
    if (v == null) return '(null)'
    String s = v.toString().replace('\n', ' ')
    return s.length() <= PREVIEW ? s : s.substring(0, PREVIEW) + '...'
}
boolean isText(String type) {
    if (type == null) return false
    String t = type.toLowerCase()
    return t.contains('char') || t.contains('text') || t.contains('clob')
}

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    // ---- enumerate candidate tables and their columns ----------------------
    List<String> tables = new ArrayList<String>()
    sql.eachRow('''SELECT table_name FROM information_schema.tables
                   WHERE table_schema = :s AND table_type = 'BASE TABLE'
                     AND upper(table_name) LIKE upper(:p) ESCAPE '/'
                   ORDER BY table_name''', [s: SCHEMA, p: TABLE_PREFIX]) { row ->
        tables.add(row['table_name'] as String)
    }
    outp.append('Candidate tables: ').append(tables.size()).append('\n\n')

    Map<String, List<List<String>>> colsByTable = new LinkedHashMap<String, List<List<String>>>()
    for (String t : tables) {
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
    }

    // ---- step 1: which table holds the set-id, in how many rows ------------
    outp.append('STEP 1 - where does the set-id appear?\n')
    outp.append('----------------------------------------------------------------\n')
    String optionTable = null, setIdColumn = null
    long bestCount = 0

    for (String t : tables) {
        for (List<String> c : colsByTable.get(t)) {
            if (!isText(c.get(1))) continue
            long cnt = 0
            try {
                sql.eachRow('SELECT count(*) AS n FROM ' + qt(t) +
                            ' WHERE "' + c.get(0) + '"::text LIKE :p', [p: '%' + SET_ID + '%']) { row ->
                    cnt = ((Number) row['n']).longValue()
                }
            } catch (Exception ignored) { }
            if (cnt > 0) {
                outp.append('  ').append(t).append('.').append(c.get(0))
                    .append('  -> ').append(cnt).append(' row(s)\n')
                if (cnt > bestCount) { bestCount = cnt; optionTable = t; setIdColumn = c.get(0) }
            }
        }
    }

    if (optionTable == null) {
        outp.append('\n  set-id not found in any ').append(TABLE_PREFIX).append(' table.\n')
        outp.append('  Check the value is copied exactly, or widen TABLE_PREFIX.\n')
        return
    }
    outp.append('\n  Option table (most rows for this set): ').append(optionTable)
        .append('  via column ').append(setIdColumn).append('\n\n')

    // ---- step 2: identify the id column and the value column ---------------
    outp.append('STEP 2 - identify columns from the known example pair\n')
    outp.append('----------------------------------------------------------------\n')
    String idCol = null, valCol = null
    for (List<String> c : colsByTable.get(optionTable)) {
        if (!isText(c.get(1))) continue
        try {
            sql.eachRow('SELECT count(*) AS n FROM ' + qt(optionTable) +
                        ' WHERE "' + c.get(0) + '"::text = :v', [v: KNOWN_OPTION_ID]) { row ->
                if (((Number) row['n']).longValue() > 0 && idCol == null) idCol = c.get(0)
            }
            sql.eachRow('SELECT count(*) AS n FROM ' + qt(optionTable) +
                        ' WHERE "' + c.get(0) + '"::text = :v', [v: KNOWN_VALUE]) { row ->
                if (((Number) row['n']).longValue() > 0 && valCol == null) valCol = c.get(0)
            }
        } catch (Exception ignored) { }
    }
    outp.append('  option-id column: ').append(idCol == null ? 'NOT FOUND' : idCol).append('\n')
    outp.append('  value column    : ').append(valCol == null ? 'NOT FOUND' : valCol).append('\n\n')

    // ---- step 3: full row dump for this set --------------------------------
    outp.append('STEP 3 - every option in set ').append(SET_ID).append('\n')
    outp.append('----------------------------------------------------------------\n')
    List<List<String>> pairs = new ArrayList<List<String>>()
    try {
        sql.eachRow('SELECT * FROM ' + qt(optionTable) +
                    ' WHERE "' + setIdColumn + '"::text LIKE :p', [p: '%' + SET_ID + '%']) { row ->
            outp.append('  ---\n')
            int n = row.getMetaData().getColumnCount()
            for (int i = 1; i <= n; i++) {
                outp.append('    ').append(String.format('%-24s', row.getMetaData().getColumnName(i)))
                    .append(' = ').append(cut(row.getObject(i))).append('\n')
            }
            if (idCol != null && valCol != null) {
                Object v = row[valCol]
                Object id = row[idCol]
                if (v != null && id != null) pairs.add([v.toString(), id.toString()])
            }
        }
    } catch (Exception e) {
        outp.append('  ERROR: ').append(e.getMessage()).append('\n')
    }

    // ---- step 4: emit engine config ----------------------------------------
    outp.append('\nSTEP 4 - paste into the "').append(MIGRATION_ID).append('" migration\n')
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
        outp.append('        ],\n\n')
        outp.append('  ').append(pairs.size()).append(' option(s). Check this list against the values\n')
        outp.append('  the SOURCE macro can hold - any source value missing here has no\n')
        outp.append('  target option and will be skipped by the engine.\n')
    }
}

log.warn('EDM option mapping completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
