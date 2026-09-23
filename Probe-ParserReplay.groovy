/*
 * Probe-ParserReplay.groovy                                      (READ ONLY)
 * -----------------------------------------------------------------------------
 * Replays the ENGINE'S OWN storage parser (patterns and span logic copied
 * verbatim from Replace-Macros-v2.2) on one version body and reports, for every
 * span of MACRO_NAME (or the one TARGET_MACRO_ID), exactly which BYTES became
 * its parameters: span offsets, self-closing flag, the raw open tag, and each
 * parameter with the absolute offset its value was sliced from plus the raw
 * body context around that offset. If a parameter's value came from outside
 * the element you see in storage, the offsets prove it and show whose bytes
 * they really are.
 */
import com.onresolve.scriptrunner.db.DatabaseUtil
import groovy.sql.Sql
import groovy.transform.Field
import java.util.regex.Matcher
import java.util.regex.Pattern

// ============================== CONFIG =======================================

@Field long CONTENT_ID = 0L               // the failing version's contentid
@Field String MACRO_NAME = 'erreichung'   // spans to report
@Field String TARGET_MACRO_ID = ''        // optional: only this macro-id
@Field String DB_RESOURCE = 'ConfluenceDB'
@Field int CONTEXT = 100                  // bytes of raw context around a value

// ============== ENGINE PARSER, COPIED VERBATIM FROM v2.2 =====================

@Field Pattern P_MACRO_TOKEN = Pattern.compile('(?s)<ac:structured-macro\\b([^>]*)>|</ac:structured-macro>')

@Field Pattern P_NAME     = Pattern.compile('ac:name="([^"]*)"')

@Field Pattern P_MACRO_ID = Pattern.compile('ac:macro-id="([^"]*)"')

@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+[^>]*?ac:name=(?:"([^"]*)"|\'([^\']*)\')\\s*' +
        '(?:/>|>(.*?)</ac:parameter>)')

class MacroSpan {
    int start, openEnd, end, depth
    String name = '', macroId = ''
    boolean selfClosing
}

String attrOf(Pattern p, String xml) {
    try {
        Matcher m = p.matcher(xml)
        return m.find() ? m.group(1) : ''
    } catch (Exception e) {
        throw new RuntimeException('attrOf failed: ' + e.getMessage(), e)
    }
}

List<MacroSpan> findMacroSpans(String body) {
    try {
        List<MacroSpan> found = new ArrayList<MacroSpan>()
        if (body == null) return found
        List<MacroSpan> stack = new ArrayList<MacroSpan>()
        Matcher m = P_MACRO_TOKEN.matcher(body)
        while (m.find()) {
            if (m.group(0).startsWith('</')) {
                if (!stack.isEmpty()) {
                    MacroSpan open = stack.remove(stack.size() - 1)
                    open.end = m.end()
                    found.add(open)
                }
                continue
            }
            String attrs = m.group(1) == null ? '' : m.group(1)
            MacroSpan sp = new MacroSpan()
            sp.start = m.start()
            sp.openEnd = m.end()
            sp.depth = stack.size()
            sp.name = attrOf(P_NAME, attrs)
            sp.macroId = attrOf(P_MACRO_ID, attrs)
            sp.selfClosing = attrs.trim().endsWith('/')
            if (sp.selfClosing) { sp.end = m.end(); found.add(sp) }
            else stack.add(sp)
        }
        Collections.sort(found, new Comparator<MacroSpan>() {
            @Override int compare(MacroSpan a, MacroSpan b) { return a.start <=> b.start }
        })
        return found
    } catch (Exception e) {
        throw new RuntimeException('findMacroSpans failed: ' + e.getMessage(), e)
    }
}


// ============================== REPLAY =======================================

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

try {
    if (CONTENT_ID == 0L) return '<pre>Set CONTENT_ID first.</pre>'
    String body = null
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.eachRow('SELECT b.body FROM bodycontent b WHERE b.contentid = :cid', [cid: CONTENT_ID]) { r ->
            body = r['body'] as String
        }
    }
    if (body == null) return '<pre>No bodycontent row for contentid ' + CONTENT_ID + '</pre>'

    StringBuilder out = new StringBuilder('<pre style="font-size:88%">')
    out.append('body length: ').append(body.length()).append('\n\n')
    List<MacroSpan> spans = findMacroSpans(body)
    out.append('total spans in body: ').append(spans.size()).append('\n\n')

    for (MacroSpan sp : spans) {
        if (!MACRO_NAME.isEmpty() && sp.name != MACRO_NAME) continue
        if (!TARGET_MACRO_ID.isEmpty() && sp.macroId != TARGET_MACRO_ID) continue

        out.append('================ span ').append(esc(sp.name))
           .append(' macro-id=').append(esc(sp.macroId)).append(' ================\n')
        out.append('  start=').append(sp.start).append('  openEnd=').append(sp.openEnd)
           .append('  end=').append(sp.end).append('  depth=').append(sp.depth)
           .append('  selfClosing=').append(sp.selfClosing)
           .append('  spanLength=').append(sp.end - sp.start)
           .append(sp.end - sp.start > 2000 ? '   << SUSPICIOUSLY LARGE - possible mis-paired close tag' : '')
           .append('\n')
        out.append('  open tag raw: ').append(esc(body.substring(sp.start, Math.min(sp.openEnd, sp.start + 400)))).append('\n')

        if (sp.selfClosing) {
            out.append('  params: NONE (self-closing) - the engine sees an empty params map here\n\n')
            continue
        }
        int contentEnd = sp.end - '</ac:structured-macro>'.length()
        if (contentEnd <= sp.openEnd) {
            out.append('  params: NONE (empty element)\n\n')
            continue
        }
        String inner = body.substring(sp.openEnd, contentEnd)
        int nested = inner.indexOf('<ac:structured-macro')
        int innerEnd = nested >= 0 ? sp.openEnd + nested : contentEnd
        if (nested >= 0) inner = inner.substring(0, nested)
        out.append('  params sliced from body[').append(sp.openEnd).append('..').append(innerEnd).append(']')
           .append(nested >= 0 ? ' (truncated at nested macro)' : '').append('\n')

        Matcher pm = P_PARAM.matcher(inner)
        boolean any = false
        while (pm.find()) {
            any = true
            String pname = pm.group(1) != null ? pm.group(1) : pm.group(2)
            String pval = pm.group(3) == null ? '' : pm.group(3)
            int valAbs = pm.group(3) == null ? (sp.openEnd + pm.start()) : (sp.openEnd + pm.start(3))
            out.append('  param "').append(esc(pname)).append('" = "').append(esc(pval))
               .append('"   value at body offset ').append(valAbs).append('\n')
            int c0 = Math.max(0, valAbs - CONTEXT)
            int c1 = Math.min(body.length(), valAbs + CONTEXT)
            out.append('    raw context: ...').append(esc(body.substring(c0, c1))).append('...\n')
        }
        if (!any) out.append('  params: none matched by P_PARAM in that slice\n')
        out.append('\n')
    }
    out.append('</pre>')
    return out.toString()
} catch (Exception e) {
    return '<pre>FAILED: ' + esc(e.getMessage()) + '</pre>'
}
