/*
 * =============================================================================
 *  EASYDROPDOWN MAPPER v2 - emits v2 engine migration blocks   (READ ONLY)
 * =============================================================================
 *
 *  Produces migration entries in the nested source/target shape the v2 engine
 *  expects, with the option map read live from the database:
 *
 *      [
 *          id     : 'artikel-status',
 *          source : [name: 'artikel-status', type: MacroType.UserMacro,
 *                    sourceParam: 'Status'],
 *          target : [name: 'easy-dropdown-menu-status', type: MacroType.EddStatusMacro,
 *                    schemaVersion: '2',
 *                    setId  : '...',
 *                    setName: 'artikel-status-ed',
 *                    options: [ 'Offen': '...', ... ]],
 *      ],
 *
 *  It also reads the SOURCE macro's declared enum values and compares them
 *  against the target set - the same comparison the engine's Stage-0 performs.
 *  A block that would be rejected by Stage-0 is emitted commented out, with the
 *  reason above it, so you never paste a config that cannot run.
 *
 *  Searches both EasyDropDown families; a set whose options are numeric labels
 *  is often a text-dropdown set rather than a status-lozenge set.
 * =============================================================================
 */

import com.atlassian.spring.container.ContainerManager
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

/*
 * One entry per migration to generate. Empty list = inventory every set instead.
 *
 *   id                  the migration id you want in the engine config
 *   source.name         ac:name of the macro being replaced, as it appears in page storage
 *   source.type         MacroType member name - see TYPE_NAMES below
 *   source.sourceParam  the parameter on that macro holding the selected value
 *   target.setName      EXACT EasyDropDown set name, as shown in the configure UI
 *                       (this is the SET, not the macro - see target.name)
 *   target.type         MacroType member name, normally EddStatusMacro
 *   target.name         optional; ac:name of the replacement macro. Defaults to
 *                       DEFAULT_TARGET_MACRO below, which is the same for every set -
 *                       sets are distinguished by set-id, not by macro name.
 */
final List<Map<String, Object>> WANTED = [
//    [
//        id     : 'artikel-status',
//        source : [name: 'artikel-status', type: 'UserMacro', sourceParam: 'Status'],
//        target : [setName: 'artikel-status-ed', type: 'EddStatusMacro'],
//    ],
//    [
//        id     : 'it-artikel-status',
//        source : [name: 'it-artikel-status', type: 'UserMacro', sourceParam: 'Status'],
//        target : [setName: 'it-artikel-status-ed', type: 'EddStatusMacro'],
//    ],
//    [
//        id     : 'artikel-progress',
//        source : [name: 'artikel-progress', type: 'ScriptRunnerMacro', sourceParam: 'Progress'],
//        target : [setName: 'artikel-progress-ed', type: 'EddStatusMacro'],
//    ],
]

// The replacement macro is the same for every EasyDropDown set; the set-id
// parameter is what distinguishes them. Override per entry with target.name.
final String DEFAULT_TARGET_MACRO = 'easy-dropdown-menu-status'
final String TARGET_SCHEMA_VER    = '2'

/*
 * These MUST match the MacroType enum in the v2 replacement engine exactly,
 * character for character - the generated config emits MacroType.<name> and a
 * mismatch is a compile error there rather than a validation failure here.
 * Note the casing: EddStatusMacro, not EDDStatusMacro.
 */
final List<String> TYPE_NAMES = ['UserMacro', 'ScriptRunnerMacro', 'EddStatusMacro',
                                 'AuraLinkButton', 'Static_QualificationTable']

// Also require option ORDER to match the source enum order (engine default too).
final boolean VALIDATE_OPTION_ORDER = true

// Print every column of every option row - useful when an expected option is absent.
final boolean RAW_DUMP = false

// setTable, optTable, fkColumn
final List<List<String>> FAMILIES = [
    ['AO_1313EC_LOZENGE_SET',     'AO_1313EC_LOZENGE_OPTION',      'LOZENGE_SET_ENTITY_ID'],
    ['AO_1313EC_TEXT_SET_ENTITY', 'AO_1313EC_TEXT_OPTION_ENTITY',  'TEXT_SET_ENTITY_ID'],
]
// ================================================================

@Field Pattern P_VELOCITY_PARAM = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')

String qt(String t) { return 'public."' + t + '"' }
String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

Object beanOrNull(String name) {
    try { return ContainerManager.getComponent(name) } catch (Throwable t) { return null }
}

Object reflectCall(Object target, String name, Class[] sig, Object[] args) {
    if (target == null) return null
    try {
        Method m = target.getClass().getMethod(name, sig)
        m.setAccessible(true)
        return m.invoke(target, args)
    } catch (Throwable t) { return null }
}

/** Declared enum values of one parameter, in declaration order, by macro type. */
List<String> sourceEnumValues(String macroName, String type, String paramName) {
    List<String> out = new ArrayList<String>()
    try {
        if (type == 'UserMacro') {
            Object lib = beanOrNull('userMacroLibrary')
            Object cfg = reflectCall(lib, 'getMacro', [String] as Class[], [macroName] as Object[])
            if (cfg == null) return out
            Object tpl = reflectCall(cfg, 'getTemplate', new Class[0], new Object[0])
            if (tpl == null) tpl = reflectCall(cfg, 'getBody', new Class[0], new Object[0])
            if (tpl == null) return out
            for (String line : tpl.toString().readLines()) {
                Matcher m = P_VELOCITY_PARAM.matcher(line)
                if (!m.matches()) continue
                if (m.group(1).trim() != paramName) continue
                String rest = m.group(2) == null ? '' : m.group(2).trim()
                for (String seg : rest.split('\\|')) {
                    int eq = seg.indexOf('=')
                    if (eq <= 0) continue
                    if (seg.substring(0, eq).trim() != 'enumValues') continue
                    for (String v : seg.substring(eq + 1).split(',')) out.add(v.trim())
                }
            }
            return out
        }
        // ScriptRunner / app macro: macro browser metadata.
        // The accessor is getFromDetails - Atlassian's spelling, not a typo here.
        Object mgr = beanOrNull('macroMetadataManager')
        Object md = reflectCall(mgr, 'getMacroMetadataByName', [String] as Class[], [macroName] as Object[])
        Object form = reflectCall(md, 'getFromDetails', new Class[0], new Object[0])
        if (form == null) form = reflectCall(md, 'getFormDetails', new Class[0], new Object[0])
        Object plist = reflectCall(form, 'getParameters', new Class[0], new Object[0])
        if (plist == null) plist = reflectCall(md, 'getParameters', new Class[0], new Object[0])
        if (!(plist instanceof Collection)) return out
        for (Object p : (Collection) plist) {
            Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
            if (n == null || n.toString() != paramName) continue
            Object ev = reflectCall(p, 'getEnumValues', new Class[0], new Object[0])
            if (ev == null) ev = reflectCall(p, 'getValues', new Class[0], new Object[0])
            if (ev instanceof Collection) { for (Object v : (Collection) ev) out.add(v.toString()) }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('sourceEnumValues(' + macroName + ') failed: ' + e.getMessage(), e)
    }
}

StringBuilder outp = new StringBuilder()

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    // ---------- inventory ---------------------------------------------------
    if (WANTED.isEmpty()) {
        outp.append('ALL EASYDROPDOWN SETS (both families)\n')
        outp.append('================================================================\n')
        outp.append(String.format('  %-22s %-6s %-40s %-8s %s%n',
                'FAMILY', 'PK', 'SET_NAME', 'OPTIONS', 'SET_ID'))
        for (List<String> f : FAMILIES) {
            try {
                sql.eachRow('SELECT s."ID" AS pk, s."SET_NAME" AS nm, s."SET_ID" AS guid, ' +
                            '(SELECT count(*) FROM ' + qt(f.get(1)) + ' o WHERE o."' + f.get(2) +
                            '" = s."ID") AS n FROM ' + qt(f.get(0)) + ' s ORDER BY s."SET_NAME"') { row ->
                    outp.append(String.format('  %-22s %-6s %-40s %-8s %s%n',
                            f.get(0).replace('AO_1313EC_', ''), row['pk'] as String,
                            row['nm'] as String, row['n'] as String, row['guid'] as String))
                }
            } catch (Exception e) {
                outp.append('  ').append(f.get(0)).append(' - not readable: ').append(e.getMessage()).append('\n')
            }
        }
        outp.append('\n  Add entries to WANTED as\n')
        outp.append('    [set name, migration id, source macro name, source type, sourceParam]\n')
        outp.append('  and re-run to generate engine config. Set names match exactly.\n')
        return
    }

    // ---------- generate per wanted set -------------------------------------
    StringBuilder cfg = new StringBuilder()
    StringBuilder detail = new StringBuilder()
    int emitted = 0, blocked = 0

    for (Map<String, Object> want : WANTED) {
        Map<String, Object> srcCfg = (Map<String, Object>) want.get('source')
        Map<String, Object> tgtCfg = (Map<String, Object>) want.get('target')
        String migId    = (String) want.get('id')
        String srcMacro = srcCfg == null ? null : (String) srcCfg.get('name')
        String srcType  = srcCfg == null ? null : (String) srcCfg.get('type')
        String srcParam = srcCfg == null ? null : (String) srcCfg.get('sourceParam')
        String setName  = tgtCfg == null ? null : (String) tgtCfg.get('setName')
        String tgtType  = tgtCfg == null ? null : (String) tgtCfg.get('type')
        String tgtMacro = (tgtCfg != null && tgtCfg.get('name') != null)
                ? (String) tgtCfg.get('name') : DEFAULT_TARGET_MACRO

        // fail loudly on a malformed entry rather than generating a broken block
        List<String> cfgErrors = new ArrayList<String>()
        if (migId == null || migId.trim().isEmpty()) cfgErrors.add('id is missing')
        if (srcCfg == null) cfgErrors.add('source block is missing')
        if (tgtCfg == null) cfgErrors.add('target block is missing')
        if (srcMacro == null) cfgErrors.add('source.name is missing')
        if (srcParam == null) cfgErrors.add('source.sourceParam is missing')
        if (setName == null) cfgErrors.add('target.setName is missing')
        if (srcType != null && !TYPE_NAMES.contains(srcType)) {
            cfgErrors.add('source.type "' + srcType + '" is not a MacroType member. Valid: ' + TYPE_NAMES)
        }
        if (tgtType != null && !TYPE_NAMES.contains(tgtType)) {
            cfgErrors.add('target.type "' + tgtType + '" is not a MacroType member. Valid: ' + TYPE_NAMES)
        }
        if (srcType == null) cfgErrors.add('source.type is missing')
        if (tgtType == null) cfgErrors.add('target.type is missing')
        if (!cfgErrors.isEmpty()) {
            detail.append('================================================================\n')
            detail.append('ENTRY "').append(migId == null ? '(no id)' : migId).append('"\n')
            detail.append('  BLOCKED - malformed WANTED entry:\n')
            for (String ce : cfgErrors) detail.append('    - ').append(ce).append('\n')
            detail.append('\n')
            blocked++
            continue
        }

        // resolve the set in either family
        List<String> fam = null
        String setPk = null, setGuid = null
        for (List<String> f : FAMILIES) {
            if (fam != null) break
            try {
                sql.eachRow('SELECT s."ID" AS pk, s."SET_ID" AS guid FROM ' + qt(f.get(0)) +
                            ' s WHERE s."SET_NAME" = :n', [n: setName]) { row ->
                    fam = f; setPk = row['pk'] as String; setGuid = row['guid'] as String
                }
            } catch (Exception ignored) { }
        }

        detail.append('================================================================\n')
        detail.append('MIGRATION "').append(migId).append('"\n')

        if (fam == null) {
            detail.append('  BLOCKED: no EasyDropDown set named "').append(setName)
                  .append('" in either family\n\n')
            cfg.append('//  "').append(migId).append('" not generated: set "').append(setName)
               .append('" not found\n')
            blocked++
            continue
        }

        // live options, in configured order
        List<List<String>> options = new ArrayList<List<String>>()
        int rowCount = 0
        sql.eachRow('SELECT * FROM ' + qt(fam.get(1)) + ' WHERE "' + fam.get(2) +
                    '" = :pk ORDER BY "ID"', [pk: Integer.parseInt(setPk)]) { row ->
            rowCount++
            if (RAW_DUMP) {
                detail.append('    --- row ').append(rowCount).append('\n')
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    detail.append('      ').append(String.format('%-22s', row.getMetaData().getColumnName(i)))
                          .append(' = ').append(row.getObject(i) == null ? '(null)' : row.getObject(i).toString())
                          .append('\n')
                }
            }
            Object nm = row['OPTION_NAME'], gid = row['OPTION_ID']
            if (gid != null) options.add([nm == null ? '' : nm.toString(), gid.toString()])
        }

        List<String> srcVals = sourceEnumValues(srcMacro, srcType, srcParam)
        List<String> optNames = new ArrayList<String>()
        for (List<String> o : options) optNames.add(o.get(0))

        detail.append('  family : ').append(fam.get(0).replace('AO_1313EC_', ''))
              .append('   set pk ').append(setPk).append('\n')
        detail.append('  set-id : ').append(setGuid).append('\n')
        detail.append('  source : ').append(srcMacro).append(' (').append(srcType)
              .append(') parameter "').append(srcParam).append('"\n')
        if (rowCount != options.size()) {
            detail.append('  WARNING: ').append(rowCount - options.size())
                  .append(' option row(s) had no OPTION_ID and were dropped\n')
        }

        // ---- comparison, mirroring the engine's Stage-0 -------------------
        List<String> problems = new ArrayList<String>()
        if (srcVals.isEmpty()) {
            problems.add('source macro declares no enum values for parameter "' + srcParam +
                         '" - check the macro type and the parameter name')
        }
        if (!srcVals.isEmpty() && srcVals.size() != optNames.size()) {
            problems.add('COUNT MISMATCH: ' + srcVals.size() + ' source enum value(s) vs ' +
                         optNames.size() + ' target option(s)')
        }
        for (String v : srcVals) {
            if (!optNames.contains(v)) problems.add('source value "' + v + '" has no target option')
        }
        for (String o : optNames) {
            if (!srcVals.contains(o)) problems.add('target option "' + o + '" has no source value')
        }
        if (VALIDATE_OPTION_ORDER && srcVals.size() == optNames.size()) {
            for (int i = 0; i < srcVals.size(); i++) {
                if (srcVals.get(i) != optNames.get(i)) {
                    problems.add('ORDER mismatch at position ' + (i + 1) + ': source "' +
                                 srcVals.get(i) + '" vs target "' + optNames.get(i) + '"')
                }
            }
        }

        // side-by-side, longer side drives the row count
        int rows = Math.max(srcVals.size(), optNames.size())
        detail.append('\n  ').append(String.format('%-34s %-34s %s',
                'SOURCE (' + srcMacro + ')', 'TARGET (' + setName + ')', 'OPTION-ID')).append('\n')
        for (int i = 0; i < rows; i++) {
            String sv = i < srcVals.size() ? srcVals.get(i) : ''
            String tv = i < optNames.size() ? optNames.get(i) : ''
            String gid = i < options.size() ? options.get(i).get(1) : ''
            detail.append('  ').append(String.format('%-34s %-34s %s', sv, tv, gid)).append('\n')
        }

        if (problems.isEmpty()) {
            detail.append('\n  validation: OK\n\n')
        } else {
            detail.append('\n  validation: ').append(problems.size()).append(' problem(s)\n')
            for (String p : problems) detail.append('    - ').append(p).append('\n')
            detail.append('\n')
        }

        // ---- emit the block ----------------------------------------------
        int w = 0
        for (List<String> o : options) { int l = o.get(0).length(); if (l > w) w = l }
        String pfx = problems.isEmpty() ? '' : '//  '
        if (!problems.isEmpty()) {
            cfg.append('//  ').append(migId).append(' - COMMENTED OUT, Stage-0 would reject it:\n')
            for (String p : problems) cfg.append('//      ').append(p).append('\n')
            blocked++
        } else {
            emitted++
        }

        cfg.append(pfx).append('    [\n')
        cfg.append(pfx).append("        id     : '").append(migId).append("',\n")
        cfg.append(pfx).append("        source : [name: '").append(srcMacro)
           .append("', type: MacroType.").append(srcType)
           .append(", sourceParam: '").append(srcParam).append("'],\n")
        cfg.append(pfx).append("        target : [name: '").append(tgtMacro)
           .append("', type: MacroType.").append(tgtType).append(",\n")
        cfg.append(pfx).append("                  schemaVersion: '").append(TARGET_SCHEMA_VER).append("',\n")
        cfg.append(pfx).append("                  setId  : '").append(setGuid).append("',\n")
        cfg.append(pfx).append("                  setName: '").append(setName).append("',\n")
        cfg.append(pfx).append('                  options: [\n')
        for (List<String> o : options) {
            String key = "'" + o.get(0).replace("'", "\\'") + "'"
            cfg.append(pfx).append('                      ')
               .append(String.format('%-' + (w + 2) + 's', key))
               .append(": '").append(o.get(1)).append("',\n")
        }
        cfg.append(pfx).append('                  ]],\n')
        cfg.append(pfx).append('    ],\n')
    }

    outp.append('SETS, SOURCE MACROS AND VALIDATION\n')
    outp.append(detail)
    outp.append('================================================================\n')
    outp.append('PASTE INTO THE v2 ENGINE MIGRATIONS LIST\n')
    outp.append('  ready: ').append(emitted).append('   commented out: ').append(blocked).append('\n')
    outp.append('================================================================\n')
    outp.append(cfg)
}

log.warn('EDM mapper v2 completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
