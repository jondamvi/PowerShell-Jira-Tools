/*
 * =============================================================================
 *  EASYDROPDOWN: list sets and build value -> option-id maps   (READ ONLY)
 * =============================================================================
 *
 *  Schema (confirmed on this instance):
 *      AO_1313EC_LOZENGE_SET      ID (pk), SET_ID (guid), SET_NAME
 *      AO_1313EC_LOZENGE_OPTION   ID (pk), LOZENGE_SET_ENTITY_ID (fk -> SET.ID),
 *                                 OPTION_ID (guid), OPTION_NAME
 *
 *  The GUIDs are what appear in page storage; the tables relate by integer key.
 *
 *  USAGE
 *    Leave SET_NAMES and SET_ID empty -> lists every set with its option count.
 *      Use that to find the exact set names, then:
 *    SET_NAMES = ['artikel-status-ed']  -> emits the engine config for that set.
 *      Matching is exact and case-sensitive, so 'artikel-status-ed' never
 *      matches 'it-artikel-status-ed'.
 *    Several names at once is fine - one config block per set.
 *
 *  If the new instance uses the text-dropdown variant instead, switch the four
 *  table/column constants to the TEXT_SET_ENTITY / TEXT_OPTION_ENTITY pair.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

// Exact set names. Empty = list every set instead of emitting config.
final List<String> SET_NAMES = []

// Alternative lookup by GUID from a page's storage. Ignored if SET_NAMES is set.
final String SET_ID = ''

final String T_SET     = 'AO_1313EC_LOZENGE_SET'
final String T_OPTION  = 'AO_1313EC_LOZENGE_OPTION'
final String C_SET_PK  = 'ID'
final String C_SET_GUID= 'SET_ID'
final String C_SET_NAME= 'SET_NAME'
final String C_OPT_FK  = 'LOZENGE_SET_ENTITY_ID'
final String C_OPT_GUID= 'OPTION_ID'
final String C_OPT_NAME= 'OPTION_NAME'
final String C_OPT_PK  = 'ID'
// ================================================================

final String SCHEMA = 'public'
String qt(String t) { return 'public."' + t + '"' }

StringBuilder outp = new StringBuilder()
String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    // ---- no filter: inventory of every set ---------------------------------
    if (SET_NAMES.isEmpty() && SET_ID.trim().isEmpty()) {
        outp.append('ALL EASYDROPDOWN SETS\n')
        outp.append('================================================================\n')
        outp.append(String.format('  %-8s %-40s %-8s %s%n', 'PK', 'SET_NAME', 'OPTIONS', 'SET_ID'))
        sql.eachRow('''SELECT s."''' + C_SET_PK + '''" AS pk,
                              s."''' + C_SET_NAME + '''" AS nm,
                              s."''' + C_SET_GUID + '''" AS guid,
                              (SELECT count(*) FROM ''' + qt(T_OPTION) + ''' o
                                WHERE o."''' + C_OPT_FK + '''" = s."''' + C_SET_PK + '''") AS optcount
                       FROM ''' + qt(T_SET) + ''' s
                       ORDER BY s."''' + C_SET_NAME + '''"''') { row ->
            outp.append(String.format('  %-8s %-40s %-8s %s%n',
                    row['pk'] as String, row['nm'] as String,
                    row['optcount'] as String, row['guid'] as String))
        }
        outp.append('\n  Put the exact SET_NAME(s) into SET_NAMES and re-run to get the\n')
        outp.append('  engine config. Names are matched exactly - a name that is a prefix\n')
        outp.append('  of another will not collide.\n')
        return
    }

    // ---- resolve the requested sets ----------------------------------------
    List<List<String>> sets = new ArrayList<List<String>>()   // [pk, name, guid]
    Map<String, Object> params = new LinkedHashMap<String, Object>()
    String where
    if (!SET_NAMES.isEmpty()) {
        List<String> ph = new ArrayList<String>()
        for (int i = 0; i < SET_NAMES.size(); i++) { ph.add(':n' + i); params.put('n' + i, SET_NAMES.get(i)) }
        where = 's."' + C_SET_NAME + '" IN (' + ph.join(', ') + ')'
    } else {
        where = 's."' + C_SET_GUID + '" = :g'
        params.put('g', SET_ID.trim())
    }

    sql.eachRow('SELECT s."' + C_SET_PK + '" AS pk, s."' + C_SET_NAME + '" AS nm, s."' +
                C_SET_GUID + '" AS guid FROM ' + qt(T_SET) + ' s WHERE ' + where +
                ' ORDER BY s."' + C_SET_NAME + '"', params) { row ->
        sets.add([row['pk'] as String, row['nm'] as String, row['guid'] as String])
    }

    if (sets.isEmpty()) {
        outp.append('No set matched. Run with SET_NAMES and SET_ID both empty to list them.\n')
        return
    }
    if (!SET_NAMES.isEmpty() && sets.size() != SET_NAMES.size()) {
        List<String> found = sets.collect { List<String> r -> r.get(1) }
        for (String want : SET_NAMES) {
            if (!found.contains(want)) outp.append('WARNING: no set named "').append(want).append('"\n')
        }
        outp.append('\n')
    }

    // ---- emit per set ------------------------------------------------------
    for (List<String> st : sets) {
        String pk = st.get(0), name = st.get(1), guid = st.get(2)
        outp.append('================================================================\n')
        outp.append('SET "').append(name).append('"   pk=').append(pk).append('\n')
        outp.append('set-id: ').append(guid).append('\n\n')

        List<List<String>> pairs = new ArrayList<List<String>>()
        sql.eachRow('SELECT o."' + C_OPT_PK + '" AS opk, o."' + C_OPT_NAME + '" AS onm, o."' +
                    C_OPT_GUID + '" AS oguid FROM ' + qt(T_OPTION) + ' o WHERE o."' +
                    C_OPT_FK + '" = :pk ORDER BY o."' + C_OPT_PK + '"',
                    [pk: Integer.parseInt(pk)]) { row ->
            pairs.add([row['onm'] as String, row['oguid'] as String, row['opk'] as String])
        }

        outp.append(String.format('  %-8s %-32s %s%n', 'PK', 'OPTION_NAME', 'OPTION_ID'))
        for (List<String> pr : pairs) {
            outp.append(String.format('  %-8s %-32s %s%n', pr.get(2), pr.get(0), pr.get(1)))
        }
        outp.append('\n  ').append(pairs.size()).append(' option(s)\n\n')

        outp.append('  ENGINE CONFIG\n')
        outp.append('        staticParams   : [\'set-id\': \'').append(guid).append('\'],\n')
        outp.append('        perValueParams : [\n')
        for (List<String> pr : pairs) {
            outp.append('            \'').append(pr.get(0)).append('\': [\'option-id\': \'')
                .append(pr.get(1)).append('\'],\n')
        }
        outp.append('        ],\n\n')
        outp.append('  Check these OPTION_NAMEs against the source macro\'s enumValues\n')
        outp.append('  (Inspect-MacroDefinition.groovy). Any source value missing here has\n')
        outp.append('  no target option and the engine will skip it.\n\n')
    }
}

log.warn('EDM set/option mapping completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
