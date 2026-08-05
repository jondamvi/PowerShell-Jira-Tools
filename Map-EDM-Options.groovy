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

// One entry per migration you want generated:
//     [ exact EDM set name, migration id, source user macro name ]
// Empty = list every set instead of emitting config.
final List<List<String>> WANTED = [
//      ['artikel-status-ed',    'artikel-status',    'artikel-status'],
//      ['it-artikel-status-ed', 'it-artikel-status', 'it-artikel-status'],
]

// Constant across every EDM set - the set-id parameter is what distinguishes them.
final String TARGET_MACRO      = 'easy-dropdown-menu-status'
final String TARGET_SCHEMA_VER = '2'
// Parameter on the SOURCE user macro that carries the selected value.
final String SOURCE_PARAM      = 'Status'

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
    if (WANTED.isEmpty()) {
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
        outp.append('\n  Add entries to WANTED as [set name, migration id, source macro]\n')
        outp.append('  and re-run. Names are matched exactly, so a name that is a prefix\n')
        outp.append('  of another will not collide.\n')
        return
    }

    // ---- emit one config block per wanted set ------------------------------
    StringBuilder cfg = new StringBuilder()
    StringBuilder detail = new StringBuilder()
    int emitted = 0

    for (List<String> want : WANTED) {
        String setName = want.get(0)
        String migId   = want.get(1)
        String srcMacro= want.get(2)

        List<String> setRow = null
        sql.eachRow('SELECT s."' + C_SET_PK + '" AS pk, s."' + C_SET_GUID + '" AS guid FROM ' +
                    qt(T_SET) + ' s WHERE s."' + C_SET_NAME + '" = :n', [n: setName]) { row ->
            setRow = [row['pk'] as String, row['guid'] as String]
        }

        if (setRow == null) {
            detail.append('WARNING: no EDM set named "').append(setName)
                  .append('" - no config generated for migration "').append(migId).append('"\n\n')
            continue
        }

        List<List<String>> pairs = new ArrayList<List<String>>()
        sql.eachRow('SELECT o."' + C_OPT_NAME + '" AS onm, o."' + C_OPT_GUID + '" AS oguid FROM ' +
                    qt(T_OPTION) + ' o WHERE o."' + C_OPT_FK + '" = :pk ORDER BY o."' + C_OPT_PK + '"',
                    [pk: Integer.parseInt(setRow.get(0))]) { row ->
            pairs.add([row['onm'] as String, row['oguid'] as String])
        }

        detail.append('SET "').append(setName).append('"  pk=').append(setRow.get(0))
              .append('  options=').append(pairs.size()).append('\n')
        for (List<String> pr : pairs) {
            detail.append('    ').append(String.format('%-32s %s', pr.get(0), pr.get(1))).append('\n')
        }
        if (pairs.isEmpty()) {
            detail.append('    WARNING: this set has no options - the generated block is unusable\n')
        }
        detail.append('\n')

        // longest option name, so the generated map lines align
        int w = 0
        for (List<String> pr : pairs) { if (pr.get(0).length() > w) w = pr.get(0).length() }

        cfg.append('    [\n')
        cfg.append("        id                 : '").append(migId).append("',\n")
        cfg.append("        source             : '").append(srcMacro).append("',\n")
        cfg.append("        target             : '").append(TARGET_MACRO).append("',\n")
        cfg.append("        targetSchemaVersion: '").append(TARGET_SCHEMA_VER).append("',\n")
        cfg.append('        unwrapParagraph    : false,\n')
        cfg.append("        paramMap           : ['").append(SOURCE_PARAM)
           .append("': 'current-option-value'],\n")
        cfg.append("        staticParams       : ['set-id': '").append(setRow.get(1)).append("'],\n")
        cfg.append('        perValueParams     : [\n')
        for (List<String> pr : pairs) {
            String q = "'" + pr.get(0).replace("'", "\\'") + "'"
            cfg.append('            ').append(String.format('%-' + (w + 2) + 's', q))
               .append(": ['option-id': '").append(pr.get(1)).append("'],\n")
        }
        cfg.append('        ],\n')
        cfg.append("        requiredParams     : ['").append(SOURCE_PARAM).append("'],\n")
        cfg.append('        dropUnmapped       : true,\n')
        cfg.append("        harvestKeyParam    : 'current-option-value',\n")
        cfg.append('    ],\n')
        emitted++
    }

    outp.append('DISCOVERED SETS AND OPTIONS\n')
    outp.append('================================================================\n')
    outp.append(detail)
    outp.append('PASTE INTO THE ENGINE\'S MIGRATIONS LIST  (').append(emitted).append(' block(s))\n')
    outp.append('================================================================\n')
    outp.append(cfg)
    outp.append('\n  Cross-check each perValueParams key list against the source macro\'s\n')
    outp.append('  enumValues (Inspect-MacroDefinition.groovy). A source value with no\n')
    outp.append('  entry here is reported as a FAILED occurrence by the engine, not\n')
    outp.append('  silently skipped.\n')
}

log.warn('EDM set/option mapping completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
