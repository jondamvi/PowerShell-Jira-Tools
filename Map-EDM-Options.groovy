/*
 * =============================================================================
 *  EASYDROPDOWN: list sets and build value -> option-id maps   (READ ONLY)
 * =============================================================================
 *
 *  Searches BOTH entity families the app uses. A set with numeric option labels
 *  may well be a text-dropdown set rather than a status-lozenge set, so looking
 *  in only one pair silently loses it:
 *      AO_1313EC_LOZENGE_SET      / AO_1313EC_LOZENGE_OPTION
 *      AO_1313EC_TEXT_SET_ENTITY  / AO_1313EC_TEXT_OPTION_ENTITY
 *
 *  Nothing filters or interprets option labels - '1', '2', 'Offen' and ''
 *  are all carried through as-is. Every option row is dumped in full (RAW_DUMP)
 *  so a missing option is visible as missing data rather than as an absence in
 *  the generated config.
 *
 *  USAGE
 *    WANTED empty -> inventory of every set in both families, with option counts.
 *    WANTED set   -> [exact set name, migration id, source user macro] per entry,
 *                    emits a paste-ready engine config block for each.
 * =============================================================================
 */

import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

final List<List<String>> WANTED = [
//      ['artikel-status-ed',   'artikel-status',   'artikel-status'],
//      ['artikel-progress-ed', 'artikel-progress', 'artikel-progress'],
]

final String TARGET_MACRO      = 'easy-dropdown-menu-status'
final String TARGET_SCHEMA_VER = '2'
final String SOURCE_PARAM      = 'Status'

// Print every column of every option row. Leave on until the option counts
// agree with what the configure UI shows.
final boolean RAW_DUMP = true

// setTable, optTable, setPk, setGuid, setName, optFk, optGuid, optName, optPk
final List<List<String>> FAMILIES = [
    ['AO_1313EC_LOZENGE_SET',     'AO_1313EC_LOZENGE_OPTION',
     'ID', 'SET_ID', 'SET_NAME', 'LOZENGE_SET_ENTITY_ID', 'OPTION_ID', 'OPTION_NAME', 'ID'],
    ['AO_1313EC_TEXT_SET_ENTITY', 'AO_1313EC_TEXT_OPTION_ENTITY',
     'ID', 'SET_ID', 'SET_NAME', 'TEXT_SET_ENTITY_ID',    'OPTION_ID', 'OPTION_NAME', 'ID'],
]
// ================================================================

String qt(String t) { return 'public."' + t + '"' }
String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

StringBuilder outp = new StringBuilder()

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    // ---------- inventory ---------------------------------------------------
    if (WANTED.isEmpty()) {
        outp.append('ALL EASYDROPDOWN SETS (both families)\n')
        outp.append('================================================================\n')
        outp.append(String.format('  %-30s %-8s %-40s %-8s %s%n',
                'FAMILY', 'PK', 'SET_NAME', 'OPTIONS', 'SET_ID'))
        for (List<String> f : FAMILIES) {
            try {
                sql.eachRow('SELECT s."' + f.get(2) + '" AS pk, s."' + f.get(4) + '" AS nm, s."' +
                            f.get(3) + '" AS guid, (SELECT count(*) FROM ' + qt(f.get(1)) +
                            ' o WHERE o."' + f.get(5) + '" = s."' + f.get(2) + '") AS n FROM ' +
                            qt(f.get(0)) + ' s ORDER BY s."' + f.get(4) + '"') { row ->
                    outp.append(String.format('  %-30s %-8s %-40s %-8s %s%n',
                            f.get(0).replace('AO_1313EC_', ''), row['pk'] as String,
                            row['nm'] as String, row['n'] as String, row['guid'] as String))
                }
            } catch (Exception e) {
                outp.append('  ').append(f.get(0)).append(' - not readable: ').append(e.getMessage()).append('\n')
            }
        }
        outp.append('\n  Compare the OPTIONS counts against the configure UI. A mismatch\n')
        outp.append('  means options live somewhere this script is not looking.\n')
        return
    }

    // ---------- per requested set -------------------------------------------
    StringBuilder cfg = new StringBuilder()
    StringBuilder detail = new StringBuilder()
    int emitted = 0

    for (List<String> want : WANTED) {
        String setName = want.get(0), migId = want.get(1), srcMacro = want.get(2)

        List<String> fam = null
        String setPk = null, setGuid = null
        for (List<String> f : FAMILIES) {
            if (fam != null) break
            try {
                sql.eachRow('SELECT s."' + f.get(2) + '" AS pk, s."' + f.get(3) + '" AS guid FROM ' +
                            qt(f.get(0)) + ' s WHERE s."' + f.get(4) + '" = :n', [n: setName]) { row ->
                    fam = f; setPk = row['pk'] as String; setGuid = row['guid'] as String
                }
            } catch (Exception ignored) { }
        }

        if (fam == null) {
            detail.append('WARNING: no set named "').append(setName)
                  .append('" in either family - nothing generated for "').append(migId).append('"\n\n')
            continue
        }

        // every option row, every column, no filtering of any kind
        List<List<String>> pairs = new ArrayList<List<String>>()
        int rowCount = 0, blankNames = 0
        detail.append('SET "').append(setName).append('"  family=').append(fam.get(0).replace('AO_1313EC_', ''))
              .append('  pk=').append(setPk).append('\n')

        sql.eachRow('SELECT * FROM ' + qt(fam.get(1)) + ' WHERE "' + fam.get(5) + '" = :pk ORDER BY "' +
                    fam.get(8) + '"', [pk: Integer.parseInt(setPk)]) { row ->
            rowCount++
            if (RAW_DUMP) {
                detail.append('    --- row ').append(rowCount).append('\n')
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    Object val = row.getObject(i)
                    detail.append('      ').append(String.format('%-24s', row.getMetaData().getColumnName(i)))
                          .append(' = ').append(val == null ? '(null)' : val.toString()).append('\n')
                }
            }
            Object nm = row[fam.get(7)]
            Object gid = row[fam.get(6)]
            String label = (nm == null) ? '' : nm.toString()
            if (label.trim().isEmpty()) blankNames++
            if (gid != null) pairs.add([label, gid.toString()])
        }

        detail.append('    option rows: ').append(rowCount)
              .append('   usable pairs: ').append(pairs.size())
        if (blankNames > 0) detail.append('   blank labels: ').append(blankNames)
        detail.append('\n')
        if (rowCount != pairs.size()) {
            detail.append('    WARNING: ').append(rowCount - pairs.size())
                  .append(' row(s) had no ').append(fam.get(6)).append(' and were dropped\n')
        }
        detail.append('\n')

        int w = 0
        for (List<String> pr : pairs) { int l = pr.get(0).length(); if (l > w) w = l }

        cfg.append('    [\n')
        cfg.append("        id                 : '").append(migId).append("',\n")
        cfg.append("        source             : '").append(srcMacro).append("',\n")
        cfg.append("        target             : '").append(TARGET_MACRO).append("',\n")
        cfg.append("        targetSchemaVersion: '").append(TARGET_SCHEMA_VER).append("',\n")
        cfg.append('        unwrapParagraph    : false,\n')
        cfg.append("        paramMap           : ['").append(SOURCE_PARAM).append("': 'current-option-value'],\n")
        cfg.append("        staticParams       : ['set-id': '").append(setGuid).append("'],\n")
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

    outp.append('SETS AND OPTION ROWS\n')
    outp.append('================================================================\n')
    outp.append(detail)
    outp.append('PASTE INTO THE ENGINE MIGRATIONS LIST  (').append(emitted).append(' block(s))\n')
    outp.append('================================================================\n')
    outp.append(cfg)
    outp.append('\n  Numeric labels such as 1 or 2 are quoted as map keys, which is what\n')
    outp.append('  the engine compares against the source macro parameter value.\n')
}

log.warn('EDM set/option mapping completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
