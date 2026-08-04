/*
 * Dump the EasyDropDown mapping tables in full. READ ONLY.
 * ScriptRunner -> Script Console.
 *
 * Schema-agnostic: column names are read from ResultSetMetaData, so nothing is
 * assumed about the table layout. Output is a plain table plus a per-row dump.
 *
 * PostgreSQL notes carried over from earlier sessions:
 *   - AO table names are uppercase and MUST be double-quoted, or PG folds them
 *     to lowercase and reports "does not exist"
 *   - the public. schema prefix is required for AO tables
 *   - information_schema.columns has returned nothing on this instance, hence
 *     the metadata approach below
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql

final String DB_RESOURCE = 'ConfluenceDB'
final int MAX_ROWS = 500
final int MAX_VALUE_CHARS = 300

final List<String> TABLES = [
        'AO_1313EC_SET_ID_MAPPING',
        'AO_1313EC_OPTION_ID_MAPPING',
]

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String cell(Object v) {
    if (v == null) return '(null)'
    String s = v.toString().replace('\n', ' ').replace('\r', ' ')
    return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + ' ...'
}

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
    for (String t : TABLES) {
        String qualified = 'public."' + t + '"'
        outp.append('================================================================\n')
        outp.append('TABLE ').append(qualified).append('\n')

        long total = -1
        try {
            sql.eachRow('SELECT count(*) AS c FROM ' + qualified) { row -> total = ((Number) row['c']).longValue() }
        } catch (Exception e) {
            outp.append('  count failed: ').append(e.getMessage()).append('\n')
        }
        outp.append('  rows: ').append(total < 0 ? '?' : total as String).append('\n\n')

        List<String> cols = new ArrayList<String>()
        try {
            sql.eachRow('SELECT * FROM ' + qualified + ' LIMIT ' + MAX_ROWS) { row ->
                if (cols.isEmpty()) {
                    int n = row.getMetaData().getColumnCount()
                    for (int i = 1; i <= n; i++) {
                        cols.add(row.getMetaData().getColumnName(i) + ' [' +
                                 row.getMetaData().getColumnTypeName(i) + ']')
                    }
                    outp.append('  COLUMNS: ').append(cols.join(', ')).append('\n\n')
                }
                outp.append('  ---\n')
                int n2 = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n2; i++) {
                    outp.append('    ')
                        .append(String.format('%-28s', row.getMetaData().getColumnName(i)))
                        .append(' = ').append(cell(row.getObject(i))).append('\n')
                }
            }
        } catch (Exception e) {
            outp.append('  ERROR: ').append(e.getClass().getSimpleName())
                .append(': ').append(e.getMessage()).append('\n')
        }
        outp.append('\n')
    }
}

log.warn('EDM mapping dump completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
