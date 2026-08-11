/*
 * =============================================================================
 *  MIGRATION CONFIG GENERATOR v2                  ScriptRunner Console, READ ONLY
 * =============================================================================
 *
 *  Give it a migration id, a source macro (name + type) and a target macro
 *  (name + type). It works out the rest and prints a ready-to-paste block for
 *  the v2 replacement engine.
 *
 *  WHAT IT DISCOVERS FOR YOU
 *    - every declared parameter of the source macro: name, type, required,
 *      default, enum values - so you no longer have to open the macro
 *      definition to find the parameter name
 *    - sourceParam, chosen automatically when it is unambiguous (see below)
 *    - for EasyDropDown targets: the set-id and the full option map, read live
 *      from the database, in configured order
 *    - for macro-to-macro targets: the target's parameters, with a suggested
 *      paramMap for names that differ and a warning for ones that cannot map
 *
 *  HOW sourceParam IS CHOSEN
 *    1. source.sourceParam if you set it explicitly - always wins
 *    2. otherwise the single parameter whose type is enum
 *    3. otherwise the single required parameter
 *    4. otherwise it cannot be decided: the entry is reported with the full
 *       parameter table and the block is commented out
 *
 *  Anything that would be rejected by the engine's Stage-0 is emitted COMMENTED
 *  OUT with the reasons above it, so the whole output can be pasted safely.
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
@Field String DB_RESOURCE = 'ConfluenceDB'

/*
 * One entry per migration. Empty list = inventory of every EasyDropDown set.
 *
 *   id                  migration id for the engine config
 *   source.name         ac:name of the macro being replaced
 *   source.type         MacroType member name (see TYPE_NAMES)
 *   source.sourceParam  OPTIONAL - omit to have it discovered
 *   target.type         MacroType member name
 *   target.name         ac:name of the replacement macro. For EddStatusMacro it
 *                       defaults to DEFAULT_EDD_MACRO, since every set uses the
 *                       same macro and is distinguished by set-id.
 *   target.setName      EddStatusMacro targets only: exact set name from the
 *                       configure UI. Not used by other target types.
 */
/*
 * Raw List on purpose: a map literal with mixed value types is inferred as
 * LinkedHashMap<String, Serializable>, which will not assign to
 * List<Map<String, Object>> under static type checking. Cast at the use site.
 */
final List WANTED = [
//    [
//        id     : 'artikel-status',
//        source : [name: 'artikel-status', type: 'UserMacro'],
//        target : [type: 'EddStatusMacro', setName: 'artikel-status-ed'],
//    ],
//    [
//        id     : 'last-modified',
//        source : [name: 'last-modified',    type: 'UserMacro'],
//        target : [name: 'last-modified-sr', type: 'ScriptRunnerMacro'],
//    ],
]

final String DEFAULT_EDD_MACRO = 'easy-dropdown-menu-status'
final String EDD_SCHEMA_VER    = '2'

final boolean VALIDATE_OPTION_ORDER = true
final boolean SHOW_PARAM_TABLES = true

/*
 * MUST match the MacroType enum in the v2 engine character for character - the
 * generated config emits MacroType.<name>, so a mismatch is a compile error
 * there. Note the casing: EddStatusMacro, not EDDStatusMacro.
 */
final List<String> TYPE_NAMES = ['UserMacro', 'ScriptRunnerMacro', 'EddStatusMacro',
                                 'AuraLinkButton', 'Static_QualificationTable']

/*
 * EasyDropDown tables. One family, explicitly named - no guessing, no fallback.
 * If a set lives in the text-dropdown tables instead, change these four values:
 *   AO_1313EC_TEXT_SET_ENTITY / AO_1313EC_TEXT_OPTION_ENTITY / TEXT_SET_ENTITY_ID
 */
@Field String T_SET    = 'AO_1313EC_LOZENGE_SET'
@Field String T_OPTION = 'AO_1313EC_LOZENGE_OPTION'
@Field String C_OPT_FK = 'LOZENGE_SET_ENTITY_ID'
// ================================================================

@Field Pattern P_VELOCITY_PARAM = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')

class ParamInfo {
    String name = '', type = '', defaultValue = ''
    boolean required
    List<String> enumValues = new ArrayList<String>()
}

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

/** Velocity template of a user macro, or null when it is not one. */
String userMacroTemplate(String name) {
    Object lib = beanOrNull('userMacroLibrary')
    Object cfg = reflectCall(lib, 'getMacro', [String] as Class[], [name] as Object[])
    if (cfg == null) return null
    Object tpl = reflectCall(cfg, 'getTemplate', new Class[0], new Object[0])
    if (tpl == null) tpl = reflectCall(cfg, 'getBody', new Class[0], new Object[0])
    return tpl == null ? null : tpl.toString()
}

/**
 * Every declared parameter of a macro, by type.
 * UserMacro          -> "## @param Name:title=X|type=enum|enumValues=A,B|required=true|default=A"
 * everything else    -> macro browser metadata. The accessor is getFromDetails;
 *                       that spelling is Atlassian's, getFormDetails does not exist.
 * Returns null when the macro is not found as that type.
 */
List<ParamInfo> macroParams(String macroName, String type) {
    try {
        if (type == 'UserMacro') {
            String tpl = userMacroTemplate(macroName)
            if (tpl == null) return null
            List<ParamInfo> out = new ArrayList<ParamInfo>()
            for (String line : tpl.readLines()) {
                Matcher m = P_VELOCITY_PARAM.matcher(line)
                if (!m.matches()) continue
                ParamInfo pi = new ParamInfo()
                pi.name = m.group(1).trim()
                String rest = m.group(2) == null ? '' : m.group(2).trim()
                for (String seg : rest.split('\\|')) {
                    int eq = seg.indexOf('=')
                    if (eq <= 0) continue
                    String k = seg.substring(0, eq).trim(), v = seg.substring(eq + 1).trim()
                    if (k == 'type') pi.type = v
                    else if (k == 'default') pi.defaultValue = v
                    else if (k == 'required') pi.required = (v.equalsIgnoreCase('true'))
                    else if (k == 'enumValues') { for (String ev : v.split(',')) pi.enumValues.add(ev.trim()) }
                }
                out.add(pi)
            }
            return out
        }

        Object mgr = beanOrNull('macroMetadataManager')
        Object md = reflectCall(mgr, 'getMacroMetadataByName', [String] as Class[], [macroName] as Object[])
        if (md == null) return null
        Object form = reflectCall(md, 'getFromDetails', new Class[0], new Object[0])
        if (form == null) form = reflectCall(md, 'getFormDetails', new Class[0], new Object[0])
        Object plist = reflectCall(form, 'getParameters', new Class[0], new Object[0])
        if (plist == null) plist = reflectCall(md, 'getParameters', new Class[0], new Object[0])
        if (!(plist instanceof Collection)) return new ArrayList<ParamInfo>()

        List<ParamInfo> out = new ArrayList<ParamInfo>()
        for (Object p : (Collection) plist) {
            ParamInfo pi = new ParamInfo()
            Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
            Object t = reflectCall(p, 'getType', new Class[0], new Object[0])
            Object d = reflectCall(p, 'getDefaultValue', new Class[0], new Object[0])
            Object r = reflectCall(p, 'isRequired', new Class[0], new Object[0])
            Object ev = reflectCall(p, 'getEnumValues', new Class[0], new Object[0])
            if (ev == null) ev = reflectCall(p, 'getValues', new Class[0], new Object[0])
            pi.name = n == null ? '' : n.toString()
            pi.type = t == null ? '' : t.toString()
            pi.defaultValue = d == null ? '' : d.toString()
            pi.required = (r instanceof Boolean) ? ((Boolean) r).booleanValue() : false
            if (ev instanceof Collection) { for (Object v : (Collection) ev) pi.enumValues.add(v.toString()) }
            out.add(pi)
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('macroParams(' + macroName + ', ' + type + ') failed: ' + e.getMessage(), e)
    }
}

String paramTable(String label, String macroName, List<ParamInfo> params) {
    StringBuilder b = new StringBuilder()
    b.append('  ').append(label).append(': ').append(macroName)
    if (params == null) { b.append('   NOT FOUND as this type\n'); return b.toString() }
    b.append('   ').append(params.size()).append(' parameter(s)\n')
    if (params.isEmpty()) return b.toString()
    b.append('    ').append(String.format('%-24s %-10s %-9s %-14s %s',
            'PARAMETER', 'TYPE', 'REQUIRED', 'DEFAULT', 'ENUM VALUES')).append('\n')
    for (ParamInfo p : params) {
        b.append('    ').append(String.format('%-24s %-10s %-9s %-14s %s',
                p.name, p.type.isEmpty() ? '-' : p.type, p.required ? 'yes' : '-',
                p.defaultValue.isEmpty() ? '-' : p.defaultValue,
                p.enumValues.isEmpty() ? '-' : p.enumValues.join(', '))).append('\n')
    }
    return b.toString()
}

/** [chosenName, explanation] - name is null when it cannot be decided. */
List<String> pickSourceParam(List<ParamInfo> params, String explicit) {
    if (explicit != null && !explicit.trim().isEmpty()) return [explicit, 'set explicitly in WANTED']
    if (params == null || params.isEmpty()) return [null, 'macro declares no parameters']
    List<ParamInfo> enums = new ArrayList<ParamInfo>()
    for (ParamInfo p : params) { if (!p.enumValues.isEmpty() || p.type == 'enum') enums.add(p) }
    if (enums.size() == 1) return [enums.get(0).name, 'only enum parameter']
    List<ParamInfo> req = new ArrayList<ParamInfo>()
    for (ParamInfo p : params) { if (p.required) req.add(p) }
    if (enums.size() > 1) {
        List<String> names = new ArrayList<String>()
        for (ParamInfo p : enums) names.add(p.name)
        return [null, 'several enum parameters ' + names + ' - set source.sourceParam explicitly']
    }
    if (req.size() == 1) return [req.get(0).name, 'only required parameter']
    if (req.size() > 1) {
        List<String> names = new ArrayList<String>()
        for (ParamInfo p : req) names.add(p.name)
        return [null, 'several required parameters ' + names + ' - set source.sourceParam explicitly']
    }
    if (params.size() == 1) return [params.get(0).name, 'only parameter']
    return [null, 'no enum and no required parameter - set source.sourceParam explicitly']
}

/*
 * DATABASE ACCESS
 *
 * Each helper opens its OWN short DatabaseUtil.withSql block and closes it
 * before returning. The connection handed to that closure is only valid inside
 * it, and calling Confluence components (macro introspection) from within one
 * starts a separate transaction that returns the pooled connection - after
 * which the next query fails with "Connection is closed". So database access
 * and Confluence component access are kept strictly apart.
 */

/** [pk, setId] for an exact set name, or null when there is no such set. */
List<String> lookupSet(String setName) {
    try {
        // Build the SQL BEFORE entering the closure. Inside a withSql closure,
        // property resolution goes to the Sql delegate first, so reading a
        // @Field there raises MissingPropertyException. Locals are captured fine.
        String query = 'SELECT s."ID" AS pk, s."SET_ID" AS guid FROM public."' + T_SET +
                       '" s WHERE s."SET_NAME" = :n'
        String resource = DB_RESOURCE
        List<String> found = null
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query, [n: setName]) { row ->
                found = [row['pk'] as String, row['guid'] as String]
            }
        }
        return found
    } catch (Exception e) {
        throw new RuntimeException('lookupSet("' + setName + '") failed: ' + e.getMessage(), e)
    }
}

/** [[optionName, optionId], ...] in configured order. */
List<List<String>> lookupOptions(String setPk) {
    try {
        String query = 'SELECT o."OPTION_NAME" AS nm, o."OPTION_ID" AS gid FROM public."' + T_OPTION +
                       '" o WHERE o."' + C_OPT_FK + '" = :pk ORDER BY o."ID"'
        String resource = DB_RESOURCE
        int pkValue = Integer.parseInt(setPk)
        List<List<String>> out = new ArrayList<List<String>>()
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query, [pk: pkValue]) { row ->
                if (row['gid'] != null) {
                    out.add([row['nm'] == null ? '' : row['nm'] as String, row['gid'] as String])
                }
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('lookupOptions(' + setPk + ') failed: ' + e.getMessage(), e)
    }
}

/** Every set with its option count. */
String inventoryText() {
    try {
        StringBuilder b = new StringBuilder()
        b.append('ALL EASYDROPDOWN SETS in ').append(T_SET).append('\n')
        b.append('================================================================\n')
        b.append(String.format('  %-6s %-40s %-8s %s%n', 'PK', 'SET_NAME', 'OPTIONS', 'SET_ID'))
        String query = 'SELECT s."ID" AS pk, s."SET_NAME" AS nm, s."SET_ID" AS guid, ' +
                       '(SELECT count(*) FROM public."' + T_OPTION + '" o WHERE o."' + C_OPT_FK +
                       '" = s."ID") AS n FROM public."' + T_SET + '" s ORDER BY s."SET_NAME"'
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query) { row ->
                b.append(String.format('  %-6s %-40s %-8s %s%n', row['pk'] as String,
                        row['nm'] as String, row['n'] as String, row['guid'] as String))
            }
        }
        b.append('\n  Add entries to WANTED and re-run to generate engine config.\n')
        return b.toString()
    } catch (Exception e) {
        throw new RuntimeException('inventoryText failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MAIN
// =============================================================================

StringBuilder outp = new StringBuilder()

try {
    if (WANTED.isEmpty()) {
        // ---------- inventory when nothing is requested ------------------
        outp.append(inventoryText())
    } else {
        StringBuilder cfg = new StringBuilder()
        StringBuilder detail = new StringBuilder()
        int ready = 0, blockedCount = 0

        for (Object wantObj : WANTED) {
            Map<String, Object> want = (Map<String, Object>) wantObj
            Map<String, Object> srcCfg = (Map<String, Object>) want.get('source')
            Map<String, Object> tgtCfg = (Map<String, Object>) want.get('target')
            String migId    = (String) want.get('id')
            String srcMacro = srcCfg == null ? null : (String) srcCfg.get('name')
            String srcType  = srcCfg == null ? null : (String) srcCfg.get('type')
            String srcParamExplicit = srcCfg == null ? null : (String) srcCfg.get('sourceParam')
            String tgtType  = tgtCfg == null ? null : (String) tgtCfg.get('type')
            String setName  = tgtCfg == null ? null : (String) tgtCfg.get('setName')
            String tgtMacro = tgtCfg == null ? null : (String) tgtCfg.get('name')
            if (tgtMacro == null && tgtType == 'EddStatusMacro') tgtMacro = DEFAULT_EDD_MACRO

            detail.append('================================================================\n')
            detail.append('MIGRATION "').append(migId == null ? '(no id)' : migId).append('"\n')

            List<String> problems = new ArrayList<String>()
            if (migId == null || migId.trim().isEmpty()) problems.add('id is missing')
            if (srcCfg == null) problems.add('source block is missing')
            if (tgtCfg == null) problems.add('target block is missing')
            if (srcMacro == null) problems.add('source.name is missing')
            if (srcType == null) problems.add('source.type is missing')
            else if (!TYPE_NAMES.contains(srcType)) problems.add('source.type "' + srcType + '" is not a MacroType member. Valid: ' + TYPE_NAMES)
            if (tgtType == null) problems.add('target.type is missing')
            else if (!TYPE_NAMES.contains(tgtType)) problems.add('target.type "' + tgtType + '" is not a MacroType member. Valid: ' + TYPE_NAMES)
            if (tgtType == 'EddStatusMacro' && (setName == null || setName.trim().isEmpty())) {
                problems.add('target.setName is required for EddStatusMacro targets')
            }
            if (tgtType != null && tgtType != 'EddStatusMacro' && tgtType != 'Static_QualificationTable'
                    && (tgtMacro == null || tgtMacro.trim().isEmpty())) {
                problems.add('target.name is required for a ' + tgtType + ' target')
            }
            if (!problems.isEmpty()) {
                detail.append('  BLOCKED - malformed entry:\n')
                for (String p : problems) detail.append('    - ').append(p).append('\n')
                detail.append('\n')
                cfg.append('//  "').append(migId).append('" not generated - malformed entry\n')
                blockedCount++
                continue
            }

            // ---- source parameters ----------------------------------------
            List<ParamInfo> srcParams = macroParams(srcMacro, srcType)
            if (SHOW_PARAM_TABLES) detail.append(paramTable('SOURCE', srcMacro, srcParams))
            if (srcParams == null) {
                problems.add('source macro "' + srcMacro + '" not found as ' + srcType +
                             ' - wrong type, or the macro is not installed')
            }

            List<String> picked = pickSourceParam(srcParams, srcParamExplicit)
            String srcParam = picked.get(0)
            detail.append('  sourceParam: ').append(srcParam == null ? '(undecided)' : srcParam)
                  .append('   [').append(picked.get(1)).append(']\n')

            List<String> srcEnum = new ArrayList<String>()
            if (srcParams != null && srcParam != null) {
                boolean found = false
                for (ParamInfo p : srcParams) {
                    if (p.name != srcParam) continue
                    found = true
                    srcEnum.addAll(p.enumValues)
                }
                if (!found) problems.add('sourceParam "' + srcParam + '" is not declared on ' + srcMacro)
            }

            // =========================================================
            //  EASYDROPDOWN TARGET
            // =========================================================
            if (tgtType == 'EddStatusMacro') {
                if (srcParam == null) problems.add('sourceParam could not be decided: ' + picked.get(1))

                // Each helper opens and closes its own connection - exceptions
                // propagate, so a failing query is never reported as "not found".
                List<String> setRow = lookupSet(setName)
                String setPk = setRow == null ? null : setRow.get(0)
                String setGuid = setRow == null ? null : setRow.get(1)

                List<List<String>> options = new ArrayList<List<String>>()
                if (setPk == null) {
                    problems.add('no set named "' + setName + '" in ' + T_SET +
                                 ' (exact, case-sensitive match)')
                } else {
                    options = lookupOptions(setPk)
                    detail.append('  set "').append(setName).append('"  pk=').append(setPk)
                          .append('  set-id=').append(setGuid).append('\n')
                }

                List<String> optNames = new ArrayList<String>()
                for (List<String> o : options) optNames.add(o.get(0))

                if (srcEnum.isEmpty() && srcParam != null) {
                    problems.add('source parameter "' + srcParam + '" declares no enum values')
                }
                if (!srcEnum.isEmpty() && srcEnum.size() != optNames.size()) {
                    problems.add('COUNT MISMATCH: ' + srcEnum.size() + ' source enum value(s) vs ' +
                                 optNames.size() + ' target option(s)')
                }
                for (String v : srcEnum) { if (!optNames.contains(v)) problems.add('source value "' + v + '" has no target option') }
                for (String o : optNames) { if (!srcEnum.contains(o)) problems.add('target option "' + o + '" has no source value') }
                if (VALIDATE_OPTION_ORDER && srcEnum.size() == optNames.size()) {
                    for (int i = 0; i < srcEnum.size(); i++) {
                        if (srcEnum.get(i) != optNames.get(i)) {
                            problems.add('ORDER mismatch at position ' + (i + 1) + ': source "' +
                                         srcEnum.get(i) + '" vs target "' + optNames.get(i) + '"')
                        }
                    }
                }

                int rows = Math.max(srcEnum.size(), optNames.size())
                if (rows > 0) {
                    detail.append('\n  ').append(String.format('%-34s %-34s %s',
                            'SOURCE (' + srcMacro + ')', 'TARGET (' + setName + ')', 'OPTION-ID')).append('\n')
                    for (int i = 0; i < rows; i++) {
                        detail.append('  ').append(String.format('%-34s %-34s %s',
                                i < srcEnum.size() ? srcEnum.get(i) : '',
                                i < optNames.size() ? optNames.get(i) : '',
                                i < options.size() ? options.get(i).get(1) : '')).append('\n')
                    }
                }

                boolean ok = problems.isEmpty()
                String pfx = ok ? '' : '//  '
                if (ok) { ready++; detail.append('\n  validation: OK\n\n') }
                else {
                    blockedCount++
                    detail.append('\n  validation: ').append(problems.size()).append(' problem(s)\n')
                    for (String p : problems) detail.append('    - ').append(p).append('\n')
                    detail.append('\n')
                    cfg.append('//  ').append(migId).append(' - COMMENTED OUT, Stage-0 would reject it:\n')
                    for (String p : problems) cfg.append('//      ').append(p).append('\n')
                }

                int w = 0
                for (List<String> o : options) { int l = o.get(0).length(); if (l > w) w = l }
                cfg.append(pfx).append('    [\n')
                cfg.append(pfx).append("        id     : '").append(migId).append("',\n")
                cfg.append(pfx).append("        source : [name: '").append(srcMacro)
                   .append("', type: MacroType.").append(srcType)
                   .append(", sourceParam: '").append(srcParam == null ? 'UNRESOLVED' : srcParam).append("'],\n")
                cfg.append(pfx).append("        target : [name: '").append(tgtMacro)
                   .append("', type: MacroType.EddStatusMacro,\n")
                cfg.append(pfx).append("                  schemaVersion: '").append(EDD_SCHEMA_VER).append("',\n")
                cfg.append(pfx).append("                  setId  : '").append(setGuid == null ? 'UNRESOLVED' : setGuid).append("',\n")
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
                continue
            }

            // =========================================================
            //  MACRO -> MACRO TARGET  (ScriptRunner, UserMacro, Aura)
            // =========================================================
            List<ParamInfo> tgtParams = macroParams(tgtMacro, tgtType)
            if (SHOW_PARAM_TABLES) detail.append(paramTable('TARGET', tgtMacro, tgtParams))
            if (tgtParams == null) {
                problems.add('target macro "' + tgtMacro + '" not found as ' + tgtType +
                             ' - it must be installed before replacing')
            }

            // suggest a paramMap for names that do not line up
            Map<String, String> suggested = new LinkedHashMap<String, String>()
            List<String> unmappable = new ArrayList<String>()
            if (srcParams != null && tgtParams != null) {
                List<String> tgtNames = new ArrayList<String>()
                for (ParamInfo p : tgtParams) tgtNames.add(p.name)
                for (ParamInfo p : srcParams) {
                    if (tgtNames.contains(p.name)) continue          // same name, no entry needed
                    unmappable.add(p.name)
                }
                for (ParamInfo tp : tgtParams) {
                    if (!tp.required) continue
                    boolean covered = false
                    for (ParamInfo sp : srcParams) { if (sp.name == tp.name) covered = true }
                    if (!covered) {
                        problems.add('target parameter "' + tp.name + '" is required but no source ' +
                                     'parameter of that name exists - add a paramMap entry or a staticParams value')
                    }
                }
            }
            if (!unmappable.isEmpty()) {
                detail.append('  source parameters with no same-named target parameter: ')
                      .append(unmappable).append('\n')
                detail.append('  -> they are DROPPED unless you add paramMap entries (dropUnmapped defaults to true)\n')
            }

            boolean ok2 = problems.isEmpty()
            String pfx2 = ok2 ? '' : '//  '
            if (ok2) { ready++; detail.append('\n  validation: OK\n\n') }
            else {
                blockedCount++
                detail.append('\n  validation: ').append(problems.size()).append(' problem(s)\n')
                for (String p : problems) detail.append('    - ').append(p).append('\n')
                detail.append('\n')
                cfg.append('//  ').append(migId).append(' - COMMENTED OUT, Stage-0 would reject it:\n')
                for (String p : problems) cfg.append('//      ').append(p).append('\n')
            }

            cfg.append(pfx2).append('    [\n')
            cfg.append(pfx2).append("        id     : '").append(migId).append("',\n")
            cfg.append(pfx2).append("        source : [name: '").append(srcMacro)
               .append("', type: MacroType.").append(srcType)
            if (srcParam != null && srcParams != null && !srcParams.isEmpty()) {
                cfg.append(", sourceParam: '").append(srcParam).append("'")
            }
            cfg.append('],\n')
            cfg.append(pfx2).append("        target : [name: '").append(tgtMacro)
               .append("', type: MacroType.").append(tgtType)
            if (!unmappable.isEmpty()) {
                cfg.append(",\n").append(pfx2).append('                  // parameters below have no same-named target parameter\n')
                cfg.append(pfx2).append('                  paramMap: [')
                List<String> parts = new ArrayList<String>()
                for (String u : unmappable) parts.add("'" + u + "': 'TARGET-PARAM-NAME'")
                cfg.append(parts.join(', ')).append('],\n')
                cfg.append(pfx2).append('                  dropUnmapped: true')
            }
            cfg.append('],\n')
            cfg.append(pfx2).append('    ],\n')
        }

        outp.append('DISCOVERY AND VALIDATION\n')
        outp.append(detail)
        outp.append('================================================================\n')
        outp.append('PASTE INTO THE v2 ENGINE MIGRATIONS LIST\n')
        outp.append('  ready: ').append(ready).append('   commented out: ').append(blockedCount).append('\n')
        outp.append('================================================================\n')
        outp.append(cfg)
    }
} catch (Throwable fatal) {
    log.error('Migration config generator failed', fatal)
    outp.append('\nTERMINATED: ').append(fatal.getClass().getName()).append(': ').append(fatal.getMessage()).append('\n')
    Throwable c = fatal.getCause()
    while (c != null) {
        outp.append('  caused by ').append(c.getClass().getName()).append(': ').append(c.getMessage()).append('\n')
        c = c.getCause()
    }
}

log.warn('Migration config generator completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
