/*
 * =============================================================================
 *  LOCATE AND DUMP EASYDROPDOWN (EPS) CONFIGURATION - READ ONLY
 * =============================================================================
 *
 *  The config UI lives at /plugins/servlets/eps-easydropdownmenu/configure,
 *  which tells us the app is EPS's EasyDropDown Menu. It does NOT tell us where
 *  the data is stored. This script finds that, then dumps it.
 *
 *  It runs three phases in one go:
 *
 *    PHASE 1  Identify the plugin from the servlet path via PluginAccessor.
 *             Prints the real plugin key, version, and its ActiveObjects and
 *             servlet modules. The AO module's namespace is what AO hashes into
 *             the AO_xxxxxx_ table prefix, so this narrows phase 2.
 *
 *    PHASE 2  Sweep every AO_* table and BANDANA for NEEDLES - values you
 *             already have from a page's storage format. Whatever echoes back
 *             is where the sets and options are stored. This is the step that
 *             actually locates the data, because AO table prefixes are hashed
 *             and cannot be guessed from the plugin key.
 *
 *    PHASE 3  Dump, in full, every table that produced a hit - all columns,
 *             read from ResultSetMetaData so nothing is assumed about the
 *             schema.
 *
 *  Re-runnable on any instance: nothing is hardcoded to a table name, only to
 *  values you read off a page.
 * =============================================================================
 */

import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

// ============================ CONFIG ============================
final String DB_RESOURCE = 'ConfluenceDB'

// Substrings used to spot the plugin in the installed list.
final List<String> PLUGIN_KEYWORDS = ['easydropdown', 'eps', 'dropdown']

// Values copied out of a page's storage format. The set-id and option-id are
// the useful ones; the label is a good cross-check. Replace with your real ones.
final List<String> NEEDLES = [
        'cd84552-7994-5a53-bec34a2434678ee',     // set-id
        'da44632-6712-6eae-556a434365a3733',     // option-id
        'Geschlossen',                            // visible label
]

// Tables to sweep. AO_* plus BANDANA covers both storage styles apps use.
final List<String> SCAN_PATTERNS = ['AO/_%', 'BANDANA']
// Page bodies obviously contain the ids - excluding them keeps the signal clean.
final List<String> SCAN_EXCLUDE = ['BODYCONTENT', 'CONTENT', 'JOURNALENTRY', 'AUDITRECORD']

final int DUMP_MAX_ROWS = 300
@Field int PREVIEW_CHARS = 400
// ================================================================

@Field String SCHEMA = 'public'

StringBuilder outp = new StringBuilder()

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String preview(Object v) {
    if (v == null) return '(null)'
    String s = v.toString().replace('\n', ' ').replace('\r', ' ')
    return s.length() <= PREVIEW_CHARS ? s : s.substring(0, PREVIEW_CHARS) + ' ...[' + s.length() + ']'
}

String qt(String table) { return SCHEMA + '."' + table.replace('"', '""') + '"' }

boolean isTextType(String type) {
    if (type == null) return false
    String t = type.toLowerCase()
    return t.contains('char') || t.contains('text') || t.contains('clob')
}

/** information_schema.columns has returned nothing on this instance, so fall
 *  back to ResultSetMetaData off a single row. */
List<List<String>> columnsOf(Sql sql, String table) {
    List<List<String>> cols = new ArrayList<List<String>>()
    try {
        sql.eachRow('''SELECT column_name, data_type FROM information_schema.columns
                       WHERE table_schema = :s AND table_name = :t ORDER BY ordinal_position''',
                    [s: SCHEMA, t: table]) { row ->
            cols.add([row['column_name'] as String, row['data_type'] as String])
        }
    } catch (Exception ignored) { }
    if (cols.isEmpty()) {
        try {
            sql.eachRow('SELECT * FROM ' + qt(table) + ' LIMIT 1') { row ->
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    cols.add([row.getMetaData().getColumnName(i), row.getMetaData().getColumnTypeName(i)])
                }
            }
        } catch (Exception ignored2) { }
    }
    return cols
}

// =============================================================================
//  PHASE 1 - identify the plugin
// =============================================================================
outp.append('PHASE 1 - PLUGIN IDENTIFICATION\n')
outp.append('================================================================\n')
try {
    PluginAccessor pa = ComponentLocator.getComponent(PluginAccessor)
    int shown = 0
    for (Plugin p : pa.getPlugins()) {
        String key = p.getKey() == null ? '' : p.getKey().toLowerCase()
        String name = p.getName() == null ? '' : p.getName().toLowerCase()
        boolean match = false
        for (String kw : PLUGIN_KEYWORDS) {
            if (key.contains(kw) || name.contains(kw)) { match = true; break }
        }
        if (!match) continue
        shown++
        outp.append('  key    : ').append(p.getKey()).append('\n')
        outp.append('  name   : ').append(p.getName()).append('\n')
        outp.append('  version: ').append(p.getPluginInformation()?.getVersion()).append('\n')
        outp.append('  modules:\n')
        p.getModuleDescriptors().each { md ->
            String cls = md.getClass().getSimpleName()
            if (cls.toLowerCase().contains('activeobjects') || cls.toLowerCase().contains('servlet')) {
                outp.append('    ').append(cls).append('  ').append(md.getCompleteKey()).append('\n')
            }
        }
        outp.append('\n')
    }
    if (shown == 0) outp.append('  no plugin matched ').append(PLUGIN_KEYWORDS).append('\n\n')
} catch (Throwable t) {
    outp.append('  plugin enumeration failed: ').append(t.getClass().getSimpleName())
        .append(' - ').append(t.getMessage()).append('\n\n')
}

// =============================================================================
//  PHASES 2 and 3 - locate, then dump
// =============================================================================
List<String> hitTables = new ArrayList<String>()

DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->

    outp.append('PHASE 2 - LOCATING THE DATA\n')
    outp.append('================================================================\n')
    outp.append('  needles: ').append(NEEDLES.join(' | ')).append('\n\n')

    List<String> candidates = new ArrayList<String>()
    for (String pat : SCAN_PATTERNS) {
        sql.eachRow('''SELECT table_name FROM information_schema.tables
                       WHERE table_schema = :s AND table_type = 'BASE TABLE'
                         AND upper(table_name) LIKE upper(:p) ESCAPE '/'
                       ORDER BY table_name''', [s: SCHEMA, p: pat]) { row ->
            String n = row['table_name'] as String
            if (!candidates.contains(n)) candidates.add(n)
        }
    }
    outp.append('  candidate tables: ').append(candidates.size()).append('\n\n')

    int scanned = 0, hits = 0
    for (String t : candidates) {
        if (SCAN_EXCLUDE.contains(t.toUpperCase())) continue
        List<List<String>> cols = columnsOf(sql, t)
        for (List<String> c : cols) {
            if (!isTextType(c.get(1))) continue
            scanned++
            for (String needle : NEEDLES) {
                try {
                    String q = 'SELECT ' + '"' + c.get(0) + '"' + ' AS v FROM ' + qt(t) +
                               ' WHERE ' + '"' + c.get(0) + '"' + '::text LIKE :p LIMIT 2'
                    sql.eachRow(q, [p: '%' + needle + '%']) { row ->
                        hits++
                        if (!hitTables.contains(t)) hitTables.add(t)
                        outp.append('  HIT ').append(t).append('.').append(c.get(0))
                            .append('  <- ').append(needle).append('\n')
                        outp.append('      ').append(preview(row['v'])).append('\n\n')
                    }
                } catch (Exception ignored) { }
            }
        }
    }
    outp.append('  text columns scanned: ').append(scanned)
        .append('   hits: ').append(hits)
        .append('   tables hit: ').append(hitTables.size()).append('\n\n')

    outp.append('PHASE 3 - FULL DUMP OF TABLES THAT MATCHED\n')
    outp.append('================================================================\n')
    if (hitTables.isEmpty()) {
        outp.append('  Nothing matched. Try, in order:\n')
        outp.append('   1. Confirm the needles are copied exactly from page storage.\n')
        outp.append('   2. Retry with only the first 8 characters of each id - the app may\n')
        outp.append('      store them without hyphens or inside a serialised blob.\n')
        outp.append('   3. Add the set NAME (as shown in the configure UI) as a needle.\n')
        outp.append('   4. Widen SCAN_PATTERNS to [\'%\'] - slower, scans every table.\n')
        outp.append('   5. If only BANDANA hits, the config is serialised XML - read the\n')
        outp.append('      dumped value below and parse it rather than expecting columns.\n')
        outp.append('   6. If nothing at all hits, the ids are generated per macro instance\n')
        outp.append('      and there is no lookup to build - harvest from pages instead.\n')
    }
    for (String t : hitTables) {
        outp.append('---- ').append(qt(t)).append('\n')
        List<List<String>> cols = columnsOf(sql, t)
        outp.append('     columns: ')
        for (List<String> c : cols) outp.append(c.get(0)).append(' [').append(c.get(1)).append('] ')
        outp.append('\n\n')
        try {
            sql.eachRow('SELECT * FROM ' + qt(t) + ' LIMIT ' + DUMP_MAX_ROWS) { row ->
                outp.append('     ---\n')
                int n = row.getMetaData().getColumnCount()
                for (int i = 1; i <= n; i++) {
                    outp.append('       ')
                        .append(String.format('%-26s', row.getMetaData().getColumnName(i)))
                        .append(' = ').append(preview(row.getObject(i))).append('\n')
                }
            }
        } catch (Exception e) {
            outp.append('     ERROR: ').append(e.getMessage()).append('\n')
        }
        outp.append('\n')
    }
}

log.warn("EDM config discovery completed, tables hit: ${hitTables.size()}")
return '<pre>' + esc(outp.toString()) + '</pre>'
