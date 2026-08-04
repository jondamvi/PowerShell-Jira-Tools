/*
 * =============================================================================
 *  MACRO DEFINITION INTROSPECTION  (ScriptRunner Script Console) - READ ONLY
 * =============================================================================
 *
 *  Prints a macro's parameters, their DEFAULT values and, where present, their
 *  allowed ENUM values - so the replacement engine can fill in parameters that
 *  are absent from page storage because the author left them at the default.
 *
 *  Method names below are the real ones, confirmed by reflection on this
 *  instance:
 *    macroMetadataManager : getMacroMetadataByName, getMacroMetadataByNameAndId,
 *                           getAllMacroMetadata, getAllMacroSummaries,
 *                           getParameters, getParameterTypes
 *    userMacroLibrary     : getMacro, getMacroNames, getMacros
 *
 *  (An earlier version called getMacroMetadata() and get()/getAll(), which do
 *  not exist - hence the silent nulls.)
 *
 *  Set DUMP_METHODS = true to list the accessors on whatever object each step
 *  returns, which is how to extend this to an API shape not handled here.
 * =============================================================================
 */

import com.atlassian.spring.container.ContainerManager

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================ CONFIG ============================
final String MACRO_NAME = 'qualification-table'
final boolean DUMP_METHODS = false
// List every macro name the instance knows about. Verbose, but it is the
// quickest way to find the exact ac:name of a user macro.
final boolean LIST_ALL_MACRO_NAMES = false
// ================================================================

StringBuilder outp = new StringBuilder()

Object beanOrNull(String name) {
    try { return ContainerManager.getComponent(name) } catch (Throwable t) { return null }
}

Object call(Object target, String method, Class[] sig, Object[] args) {
    if (target == null) return null
    try {
        Method m = target.getClass().getMethod(method, sig)
        m.setAccessible(true)
        return m.invoke(target, args)
    } catch (Throwable t) { return null }
}

Object call0(Object target, String method) { return call(target, method, new Class[0], new Object[0]) }
Object call1(Object target, String method, String arg) { return call(target, method, [String] as Class[], [arg] as Object[]) }

String methods(Object target) {
    if (target == null) return '(null)'
    List<String> names = new ArrayList<String>()
    for (Method m : target.getClass().getMethods()) {
        if (m.getName().startsWith('get') || m.getName().startsWith('is')) names.add(m.getName())
    }
    Collections.sort(names)
    return names.unique().join(', ')
}

/** Parses "## @param Name:title=X|type=enum|enumValues=A,B,C|default=D" lines. */
Map<String, Map<String, String>> parseVelocityParams(String template) {
    Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>()
    Pattern p = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')
    for (String line : template.readLines()) {
        Matcher m = p.matcher(line)
        if (!m.matches()) continue
        String name = m.group(1).trim()
        Map<String, String> attrs = new LinkedHashMap<String, String>()
        String rest = m.group(2) == null ? '' : m.group(2).trim()
        for (String seg : rest.split('\\|')) {
            int eq = seg.indexOf('=')
            if (eq > 0) attrs.put(seg.substring(0, eq).trim(), seg.substring(eq + 1).trim())
        }
        out.put(name, attrs)
    }
    return out
}

outp.append('MACRO: ').append(MACRO_NAME).append('\n')
outp.append('================================================================\n\n')

// -----------------------------------------------------------------------------
//  ROUTE 1 - macro browser metadata (app macros, ScriptRunner macros)
// -----------------------------------------------------------------------------
outp.append('ROUTE 1 - macroMetadataManager\n')
Object mmm = beanOrNull('macroMetadataManager')
if (mmm == null) {
    outp.append('  bean not found\n')
} else {
    if (DUMP_METHODS) outp.append('  manager methods: ').append(methods(mmm)).append('\n')

    Object md = call1(mmm, 'getMacroMetadataByName', MACRO_NAME)
    if (md == null) md = call1(mmm, 'getMacroMetadata', MACRO_NAME)   // older signature

    if (md == null && LIST_ALL_MACRO_NAMES) {
        Object all = call0(mmm, 'getAllMacroSummaries')
        if (all instanceof Collection) {
            outp.append('  getMacroMetadataByName returned null. Known macro names:\n')
            for (Object s : (Collection) all) {
                Object nm = call0(s, 'getMacroName')
                if (nm == null) nm = call0(s, 'getName')
                outp.append('    ').append(nm).append('\n')
            }
        }
    }

    if (md == null) {
        outp.append('  getMacroMetadataByName("').append(MACRO_NAME).append('") returned null.\n')
        outp.append('  Set LIST_ALL_MACRO_NAMES = true to check the exact registered name.\n')
    } else {
        outp.append('  metadata class: ').append(md.getClass().getName()).append('\n')
        if (DUMP_METHODS) outp.append('  metadata methods: ').append(methods(md)).append('\n')

        Object form = call0(md, 'getFormDetails')
        Object plist = form == null ? null : call0(form, 'getParameters')
        if (plist == null) plist = call0(md, 'getParameters')

        if (!(plist instanceof Collection)) {
            outp.append('  no parameter collection reachable\n')
            if (form != null && DUMP_METHODS) outp.append('  formDetails methods: ').append(methods(form)).append('\n')
        } else {
            outp.append('\n  ').append(String.format('%-28s %-12s %-9s %-10s %s',
                    'PARAMETER', 'DEFAULT', 'REQUIRED', 'TYPE', 'ENUM VALUES')).append('\n')
            List<String> pairs = new ArrayList<String>()
            for (Object p : (Collection) plist) {
                Object pn = call0(p, 'getName')
                Object pd = call0(p, 'getDefaultValue')
                Object pr = call0(p, 'isRequired')
                Object pt = call0(p, 'getType')
                Object pe = call0(p, 'getEnumValues')
                if (pe == null) pe = call0(p, 'getValues')
                outp.append('  ').append(String.format('%-28s %-12s %-9s %-10s %s',
                        pn == null ? '(unnamed)' : pn.toString(),
                        pd == null ? '-' : pd.toString(),
                        pr == null ? '?' : pr.toString(),
                        pt == null ? '-' : pt.toString(),
                        pe == null ? '-' : pe.toString())).append('\n')
                if (pn != null && pd != null && !pd.toString().isEmpty()) {
                    pairs.add("'" + pn.toString() + "':'" + pd.toString() + "'")
                }
            }
            outp.append('\n  Paste-ready paramDefaults:\n  [').append(pairs.join(', ')).append(']\n')
        }
    }
}

// -----------------------------------------------------------------------------
//  ROUTE 2 - user macro Velocity template
// -----------------------------------------------------------------------------
outp.append('\nROUTE 2 - userMacroLibrary\n')
Object lib = beanOrNull('userMacroLibrary')
if (lib == null) {
    outp.append('  bean not found\n')
} else {
    if (DUMP_METHODS) outp.append('  library methods: ').append(methods(lib)).append('\n')

    if (LIST_ALL_MACRO_NAMES) {
        Object names = call0(lib, 'getMacroNames')
        if (names instanceof Collection) {
            outp.append('  user macros on this instance (').append(((Collection) names).size()).append('):\n')
            for (Object n : (Collection) names) outp.append('    ').append(n).append('\n')
        }
    }

    Object cfg = call1(lib, 'getMacro', MACRO_NAME)
    if (cfg == null) {
        outp.append('  getMacro("').append(MACRO_NAME).append('") returned null')
        outp.append(' - not a user macro (expected for ScriptRunner script macros).\n')
        outp.append('  Set LIST_ALL_MACRO_NAMES = true to list every user macro name.\n')
    } else {
        if (DUMP_METHODS) outp.append('  config methods: ').append(methods(cfg)).append('\n')
        Object tpl = call0(cfg, 'getTemplate')
        if (tpl == null) tpl = call0(cfg, 'getBody')
        if (tpl == null) {
            outp.append('  found the macro but no template accessor\n')
        } else {
            Map<String, Map<String, String>> params = parseVelocityParams(tpl.toString())
            if (params.isEmpty()) {
                outp.append('  template has no ## @param declarations\n')
            } else {
                outp.append('\n  ').append(String.format('%-28s %-12s %-10s %s',
                        'PARAMETER', 'DEFAULT', 'TYPE', 'ENUM VALUES')).append('\n')
                List<String> pairs = new ArrayList<String>()
                for (Map.Entry<String, Map<String, String>> e : params.entrySet()) {
                    Map<String, String> a = e.getValue()
                    outp.append('  ').append(String.format('%-28s %-12s %-10s %s',
                            e.getKey(),
                            a.get('default') == null ? '-' : a.get('default'),
                            a.get('type') == null ? '-' : a.get('type'),
                            a.get('enumValues') == null ? '-' : a.get('enumValues'))).append('\n')
                    if (a.get('default') != null && !a.get('default').isEmpty()) {
                        pairs.add("'" + e.getKey() + "':'" + a.get('default') + "'")
                    }
                }
                outp.append('\n  Paste-ready paramDefaults:\n  [').append(pairs.join(', ')).append(']\n')
                outp.append('\n  enumValues above is the authoritative list of values the macro\n')
                outp.append('  accepts - use it to check perValueParams covers every one.\n')
            }
        }
    }
}

log.warn("Macro definition introspection for ${MACRO_NAME} completed")
return '<pre>' + outp.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') + '</pre>'
