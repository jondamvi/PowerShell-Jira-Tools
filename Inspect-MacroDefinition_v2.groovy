/*
 * SCRIPT C - MACRO DEFINITION INTROSPECTION (READ ONLY)
 *
 * Prints the parameter definitions and DEFAULT VALUES for a macro, so the
 * remediation script can fill in parameters that are absent from page storage
 * because the author left them at their default.
 *
 * Covers both populations you care about:
 *   - User macros      -> reads the macro template and parses its ## @param lines
 *   - App macros       -> reads Macro Browser metadata (ScriptRunner, EasyDropDown)
 *
 * Everything is done reflectively and every lookup is individually guarded, so
 * this cannot fail to compile against an API shape it doesn't recognise. It
 * dumps the method names it finds, which is how we learn the real API on your
 * Confluence version if one of the routes comes back empty.
 */

import com.atlassian.spring.container.ContainerManager

import java.lang.reflect.Method

// ============================ CONFIG ============================
final String MACRO_NAME = 'qualification-table'
final boolean DUMP_METHODS = false     // true = also list every method on each bean
// ================================================================

StringBuilder outp = new StringBuilder()

Object beanOrNull(String name) {
    try { return ContainerManager.getComponent(name) } catch (Throwable t) { return null }
}

Object callOrNull(Object target, String method, Class[] sig, Object[] args) {
    if (target == null) return null
    try {
        Method m = target.getClass().getMethod(method, sig)
        m.setAccessible(true)
        return m.invoke(target, args)
    } catch (Throwable t) { return null }
}

String methodList(Object target) {
    if (target == null) return '(null)'
    List<String> names = new ArrayList<String>()
    for (Method m : target.getClass().getMethods()) {
        if (m.getName().startsWith('get') || m.getName().startsWith('is')) names.add(m.getName())
    }
    Collections.sort(names)
    return names.unique().join(', ')
}

outp.append('MACRO: ').append(MACRO_NAME).append('\n')
outp.append('================================================================\n\n')

// ---------- ROUTE 1: Macro Browser metadata (app macros + user macros) ------
outp.append('ROUTE 1 - macroMetadataManager\n')
Object mmm = beanOrNull('macroMetadataManager')
if (mmm == null) {
    outp.append('  bean not found under name "macroMetadataManager"\n')
} else {
    outp.append('  bean class: ').append(mmm.getClass().getName()).append('\n')
    if (DUMP_METHODS) outp.append('  methods: ').append(methodList(mmm)).append('\n')

    Object md = callOrNull(mmm, 'getMacroMetadata', [String] as Class[], [MACRO_NAME] as Object[])
    if (md == null) {
        outp.append('  getMacroMetadata("').append(MACRO_NAME).append('") returned null\n')
    } else {
        outp.append('  metadata class: ').append(md.getClass().getName()).append('\n')
        if (DUMP_METHODS) outp.append('  methods: ').append(methodList(md)).append('\n')

        Object form = callOrNull(md, 'getFormDetails', new Class[0], new Object[0])
        if (form == null) {
            outp.append('  getFormDetails() returned null - dump methods with DUMP_METHODS=true\n')
        } else {
            Object params = callOrNull(form, 'getParameters', new Class[0], new Object[0])
            if (params == null) {
                outp.append('  getParameters() returned null\n')
                if (DUMP_METHODS) outp.append('  formDetails methods: ').append(methodList(form)).append('\n')
            } else {
                outp.append('\n  ').append(String.format('%-28s %-12s %-10s %s', 'PARAMETER', 'DEFAULT', 'REQUIRED', 'TYPE')).append('\n')
                for (Object p : (Collection) params) {
                    Object pn  = callOrNull(p, 'getName',        new Class[0], new Object[0])
                    Object pd  = callOrNull(p, 'getDefaultValue',new Class[0], new Object[0])
                    Object pr  = callOrNull(p, 'isRequired',     new Class[0], new Object[0])
                    Object pt  = callOrNull(p, 'getType',        new Class[0], new Object[0])
                    outp.append('  ').append(String.format('%-28s %-12s %-10s %s',
                            pn == null ? '(unnamed)' : pn.toString(),
                            pd == null ? '-' : pd.toString(),
                            pr == null ? '?' : pr.toString(),
                            pt == null ? '-' : pt.toString())).append('\n')
                }
                outp.append('\n  Paste-ready PARAM_DEFAULTS for Script B:\n  [')
                List<String> pairs = new ArrayList<String>()
                for (Object p : (Collection) params) {
                    Object pn = callOrNull(p, 'getName', new Class[0], new Object[0])
                    Object pd = callOrNull(p, 'getDefaultValue', new Class[0], new Object[0])
                    if (pn != null && pd != null && !pd.toString().isEmpty()) {
                        pairs.add("'" + pn.toString() + "':'" + pd.toString() + "'")
                    }
                }
                outp.append(pairs.join(', ')).append(']\n')
            }
        }
    }
}

// ---------- ROUTE 2: user macro template ------------------------------------
outp.append('\nROUTE 2 - userMacroLibrary (user macros only)\n')
Object lib = beanOrNull('userMacroLibrary')
if (lib == null) {
    outp.append('  bean not found under name "userMacroLibrary" - not a user macro, or different bean name\n')
} else {
    outp.append('  bean class: ').append(lib.getClass().getName()).append('\n')
    if (DUMP_METHODS) outp.append('  methods: ').append(methodList(lib)).append('\n')

    Object cfg = callOrNull(lib, 'get', [String] as Class[], [MACRO_NAME] as Object[])
    if (cfg == null) cfg = callOrNull(lib, 'getUserMacro', [String] as Class[], [MACRO_NAME] as Object[])
    if (cfg == null) {
        Object allCfg = callOrNull(lib, 'getAll', new Class[0], new Object[0])
        if (allCfg instanceof Collection) {
            outp.append('  user macros defined on this instance:\n')
            for (Object c : (Collection) allCfg) {
                Object nm = callOrNull(c, 'getName', new Class[0], new Object[0])
                outp.append('    ').append(nm).append('\n')
                if (nm != null && nm.toString() == MACRO_NAME) cfg = c
            }
        } else {
            outp.append('  could not enumerate user macros - run with DUMP_METHODS=true\n')
        }
    }
    if (cfg != null) {
        Object tpl = callOrNull(cfg, 'getTemplate', new Class[0], new Object[0])
        if (tpl == null) tpl = callOrNull(cfg, 'getBody', new Class[0], new Object[0])
        if (tpl == null) {
            outp.append('  found the macro config but no template accessor\n')
            outp.append('  config methods: ').append(methodList(cfg)).append('\n')
        } else {
            outp.append('\n  ## @param declarations found in the template:\n')
            for (String line : tpl.toString().readLines()) {
                String t = line.trim()
                if (t.startsWith('##') && t.contains('@param')) {
                    outp.append('    ').append(t).append('\n')
                }
            }
            outp.append('\n  (the "default=" segment of each @param line is the value that is\n')
            outp.append('   omitted from page storage when the author leaves it untouched)\n')
        }
    }
}

log.warn("Macro definition introspection for ${MACRO_NAME} completed")
return '<pre>' + outp.toString().replace('<', '&lt;') + '</pre>'
