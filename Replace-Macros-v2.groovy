/*
 * =============================================================================
 *  CONFLUENCE DC MACRO REPLACEMENT ENGINE  v2      ScriptRunner Script Console
 * =============================================================================
 *
 *  Staged, page-version-atomic macro replacement. All writes go through the
 *  Confluence Java API: no outage, no direct DB writes, no reindex needed.
 *
 *  STAGES
 *    Stage-0  Validate. Scope, macro definitions, parameter names, enum values
 *             and EasyDropDown option maps are checked against what is actually
 *             installed and what is actually in the database. Any failure stops
 *             the run before a single page is read.
 *    Stage-1  ScanMacroUsage. Read-only sweep building findings made of PLAIN
 *             VALUES only - no Confluence entities are retained, so the findings
 *             survive session flushes between batches.
 *    Stage-2  Plan. Annotate each matched macro with what it will become.
 *    Stage-3  Execute. Per page version: re-read, apply every planned change to
 *             the body in memory, write ONCE, re-read to confirm.
 *
 *  WHY ONE WRITE PER VERSION
 *  v1 looped migrations on the outside, so a page carrying three different
 *  source macros was loaded and saved three times in one run. The second save
 *  worked from a stale HIBERNATEVERSION and raised
 *  HibernateOptimisticLockingFailureException. v2 loops page versions on the
 *  outside and applies all migrations to one body, so the collision cannot occur.
 *
 *  ASSUMPTIONS (deliberate, agreed)
 *    - No nested macros. Every in-scope macro is a parameter-only
 *      <ac:structured-macro>; ac:rich-text-body is out of scope.
 *    - ac:macro-id is NOT globally unique (copying a page duplicates it).
 *      Identity within a version is (ac:macro-id, occurrence order).
 *    - A page's versions are not transactional as a group. If v5 succeeds and
 *      v6 fails the page is partially migrated; re-running is safe because
 *      Stage-3 re-reads and re-matches before writing.
 * =============================================================================
 */

import com.atlassian.confluence.core.ContentEntityObject
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.core.Modification
import com.atlassian.confluence.core.VersionHistorySummary
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.spring.container.ContainerManager
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.Deflater

// =============================================================================
//  ENUMS
// =============================================================================

enum MacroType {
    UserMacro,                    // Velocity user macro, defined in userMacroLibrary
    ScriptRunnerMacro,            // ScriptRunner script macro, in macroMetadataManager
    EddStatusMacro,               // EasyDropDown status macro (set-id / option-id)
    AuraLinkButton,               // Aura button
    Static_QualificationTable     // not a macro: computed static storage output
}

enum ReplacementStatus {
    Unknown, Success, Failed, Skipped
}

// =============================================================================
//  CONFIG
// =============================================================================

@Field String MODE = 'INSPECT'                 // INSPECT | APPLY

// Migration ids to run. Empty = all of them.
@Field List<String> RUN = []

@Field String DB_RESOURCE = 'ConfluenceDB'

// Scope. Empty SPACE_KEYS = every space. PAGE_IDS_OVERRIDE bypasses discovery.
@Field List<String> SPACE_KEYS = []
@Field List<Long> PAGE_IDS_OVERRIDE = []
@Field List<String> INCLUDE_STATUSES = ['current']

@Field boolean UPDATE_HISTORICAL_VERSIONS = true

// true  -> current version saved with saveNewVersion(): page history gives you
//          an undo, version count grows by one
// false -> saved in place, no new version
@Field boolean CURRENT_CREATES_NEW_VERSION = true

// Historical rows are never indexed by Confluence, so events are suppressed.
@Field boolean HISTORICAL_SUPPRESS_EVENTS = true

@Field boolean VERIFY_AFTER_WRITE = true
@Field int WRITE_RETRIES = 2

// SKIP -> an occurrence that cannot be resolved is left in place and reported
// FAIL -> it aborts that page version
@Field String ON_MISSING = 'SKIP'

// Batching. FLUSH_AFTER_BATCH=false makes BATCH_MAX_PAGES inert.
@Field int BATCH_MAX_PAGES = 20
@Field boolean FLUSH_AFTER_BATCH = true

// Stage-0: also require target option ORDER to match the source enum order.
@Field boolean VALIDATE_OPTION_ORDER = true

// Results listing: TABLE | CSV | LIST | NONE
@Field String RESULT_FORMAT = 'TABLE'

// Rollback copies. Compressed output is deflate + Base64; Base64 is required
// because the console returns a String and raw deflate bytes are not valid text.
@Field boolean EMIT_ROLLBACK_COPIES = true
@Field boolean COMPRESS_SOURCE_ON_SUCCESS = true
@Field boolean COMPRESS_REPLACED_ON_SUCCESS = true
@Field boolean COMPRESS_SOURCE_ON_FAILURE = false
@Field boolean COMPRESS_REPLACED_ON_FAILURE = false
@Field boolean EMIT_REPLACED_ON_SUCCESS = false     // usually not needed
@Field int MAX_ROLLBACK_ENTRIES = 200

// Per-occurrence mapping trace in the notes.
@Field boolean TRACE_MAPPING = true

// =============================================================================
//  MIGRATIONS
//
//  source / target are objects: [name: ..., type: MacroType....]
//
//  Per type, the fields that matter:
//    UserMacro / ScriptRunnerMacro source
//        sourceParam    parameter carrying the value (validated to exist)
//    EddStatusMacro target
//        setId, setName, options: [ '<source enum value>': '<option-id>', ... ]
//    Static_QualificationTable target
//        no target macro; output is computed storage
//    any target
//        staticParams   fixed parameters written on every instance
//        paramMap       source param name -> target param name
//        dropUnmapped   drop source params with no paramMap entry
// =============================================================================
@Field List<Map<String, Object>> MIGRATIONS = [

    [
        id     : 'qualification-table',
        source : [name: 'qualification-table', type: MacroType.ScriptRunnerMacro],
        target : [name: null,                  type: MacroType.Static_QualificationTable],
        unwrapParagraph: true,      // output is a <table>, which cannot sit in a <p>
    ],

    [
        id     : 'artikel-status',
        source : [name: 'artikel-status', type: MacroType.UserMacro, sourceParam: 'Status'],
        target : [name: 'easy-dropdown-menu-status', type: MacroType.EddStatusMacro,
                  schemaVersion: '2',
                  setId  : 'PUT-SET-ID-HERE',
                  setName: 'artikel-status-ed',
                  options: [
                      // 'Offen': '<option-id>',      <- generated by the EDM mapper
                  ]],
    ],

//  TODO - Aura button. Blocked: the sample source macro exposes only
//  buttontext="google.de" while the target needs href and label, and neither
//  is derivable from it. Send the full source storage and this can be filled in.
//    [
//        id     : 'link-button',
//        source : [name: 'test-link-button', type: MacroType.UserMacro, sourceParam: 'buttontext'],
//        target : [name: 'aura-button', type: MacroType.AuraLinkButton,
//                  paramMap: ['buttontext': 'label'],
//                  staticParams: ['elevation': 'elevated', 'hrefType': 'link']],
//    ],
]

// =============================================================================
//  MODEL - plain values only, safe across session flushes
// =============================================================================

class MigrationDef {
    String id
    String sourceName;  MacroType sourceType;  String sourceParam
    String targetName;  MacroType targetType;  String targetSchemaVersion = '1'
    String setId, setName
    Map<String, String> options      = new LinkedHashMap<String, String>()
    Map<String, String> paramMap     = new LinkedHashMap<String, String>()
    Map<String, String> staticParams = new LinkedHashMap<String, String>()
    Map<String, String> paramDefaults= new LinkedHashMap<String, String>()
    boolean unwrapParagraph, dropUnmapped = true
    // filled by Stage-0
    List<String> sourceEnumValues = new ArrayList<String>()
    Map<String, String> discoveredDefaults = new LinkedHashMap<String, String>()
    // counters
    int occFound, occReplaced, occSkipped, occFailed
}

class MatchedMacro {
    String migrationId, sourceName, macroId
    MacroType sourceType, targetType
    String targetName
    int macroIndex          // position among ALL macros in this version's body
    int matchedIndex        // position among matched macros only
    Map<String, String> params = new LinkedHashMap<String, String>()
    ReplacementStatus status = ReplacementStatus.Unknown
    String message = ''
}

class VersionFinding {
    long contentId
    int versionNumber
    boolean isCurrent
    boolean hasMatchedMacros
    List<MatchedMacro> matchedMacros = new ArrayList<MatchedMacro>()
    // execution results
    ReplacementStatus status = ReplacementStatus.Unknown
    String message = ''
    String bodyBefore, bodyAfter
}

class PageFinding {
    long pageId
    String pageName = '', spaceKey = '', url = ''
    List<VersionFinding> versions = new ArrayList<VersionFinding>()
}

class ValidationIssue {
    String migrationId, sourceLabel, targetLabel, description
}

// =============================================================================
//  PATTERNS
// =============================================================================

/*
 * The macro body uses a TEMPERED expression rather than a lazy .*? :
 *     (?:(?!</ac:structured-macro>).)*
 * A lazy quantifier backtracks and will extend past the nearest closing tag if
 * that lets the rest of the pattern match - which merged two macros in one
 * paragraph into a single replacement in v1. The tempered form cannot cross a
 * closing tag, so every macro is always its own match. Correct here because
 * nested macros are out of scope.
 */
@Field String MACRO_BODY = '(?:[^>]*/>|[^>]*>(?:(?!</ac:structured-macro>).)*</ac:structured-macro>)'

/** Every structured macro, whatever its name, in document order. */
@Field Pattern P_ANY_MACRO = Pattern.compile('(?s)<ac:structured-macro\\b' + MACRO_BODY)

@Field Pattern P_NAME     = Pattern.compile('ac:name="([^"]*)"')
@Field Pattern P_MACRO_ID = Pattern.compile('ac:macro-id="([^"]*)"')

/* Tolerant: either quote style, self-closing empty parameters, attributes in
 * any order. The VALUE is captured with .*? under DOTALL so umlauts, entities
 * and newlines pass through byte-transparent. */
@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+[^>]*?ac:name=(?:"([^"]*)"|\'([^\']*)\')\\s*' +
        '(?:/>|>(.*?)</ac:parameter>)')

@Field Pattern P_VELOCITY_PARAM = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')

// qualification-table column order: label -> parameter
@Field List<List<String>> QM_COLUMNS = [
        ['KB'  , 'impactFinance'],   ['V'   , 'impactSales'],
        ['PM'  , 'impactProductmanagement'], ['M', 'impactMarketing'],
        ['O&S' , 'impactOuS'],       ['HR'  , 'impactHR'],
        ['GF'  , 'impactGF'],        ['LEAS', 'impactLR'],
        ['ASUS', 'impactASUS'],
]

@Field List<String> RUN_LOG = new ArrayList<String>()
@Field boolean FLUSH_WORKED = false
@Field String FLUSH_NOTE = 'not attempted'

// =============================================================================
//  SMALL UTILITIES
// =============================================================================

String xmlAttr(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;')
}

String xmlText(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String htmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String humanTime(long ms) {
    if (ms < 1000) return ms + ' ms'
    long s = (long) (ms / 1000)
    long h = (long) (s / 3600), m = (long) ((s % 3600) / 60), sec = s % 60
    StringBuilder b = new StringBuilder()
    if (h > 0) b.append(h).append(' h ')
    if (h > 0 || m > 0) b.append(m).append(' m ')
    b.append(sec).append(' s')
    return b.toString()
}

/** deflate + Base64, wrapped. Base64 is unavoidable: the console returns a
 *  String and raw deflate bytes are not valid text in any encoding. */
String compressToText(String input) {
    try {
        byte[] raw = input.getBytes('UTF-8')
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw); d.finish()
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length)
        byte[] buf = new byte[8192]
        while (!d.finished()) { int n = d.deflate(buf); bos.write(buf, 0, n) }
        d.end()
        String b64 = Base64.getEncoder().encodeToString(bos.toByteArray())
        StringBuilder wrapped = new StringBuilder()
        for (int i = 0; i < b64.length(); i += 120) {
            wrapped.append(b64.substring(i, Math.min(i + 120, b64.length()))).append('\n')
        }
        return 'deflate+base64 ' + raw.length + ' -> ' + b64.length() + ' chars\n' + wrapped
    } catch (Exception e) {
        throw new RuntimeException('compressToText failed: ' + e.getMessage(), e)
    }
}

Object reflectCall(Object target, String name, Class[] sig, Object[] args) {
    if (target == null) return null
    try {
        Method m = target.getClass().getMethod(name, sig)
        m.setAccessible(true)
        return m.invoke(target, args)
    } catch (Throwable t) {
        return null
    }
}

Object beanOrNull(String name) {
    try { return ContainerManager.getComponent(name) } catch (Throwable t) { return null }
}

/** Attempted reflectively; reports honestly whether it actually ran. */
void flushSession() {
    if (!FLUSH_AFTER_BATCH) { FLUSH_NOTE = 'disabled by FLUSH_AFTER_BATCH'; return }
    try {
        Object sf = beanOrNull('sessionFactory')
        if (sf == null) { FLUSH_NOTE = 'no sessionFactory bean - batching without flush'; return }
        Object session = reflectCall(sf, 'getCurrentSession', new Class[0], new Object[0])
        if (session == null) { FLUSH_NOTE = 'getCurrentSession unavailable - batching without flush'; return }
        reflectCall(session, 'flush', new Class[0], new Object[0])
        reflectCall(session, 'clear', new Class[0], new Object[0])
        FLUSH_WORKED = true
        FLUSH_NOTE = 'flush + clear succeeded'
    } catch (Exception e) {
        FLUSH_NOTE = 'flush failed: ' + e.getClass().getSimpleName() + ': ' + e.getMessage()
    }
}

// =============================================================================
//  STORAGE PARSING
// =============================================================================

String attrOf(Pattern p, String xml) {
    try {
        Matcher m = p.matcher(xml)
        return m.find() ? m.group(1) : ''
    } catch (Exception e) {
        throw new RuntimeException('attrOf failed: ' + e.getMessage(), e)
    }
}

Map<String, String> parseParams(String macroXml) {
    try {
        Map<String, String> found = new LinkedHashMap<String, String>()
        Matcher m = P_PARAM.matcher(macroXml)
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2)
            String value = m.group(3) == null ? '' : m.group(3).trim()
            if (name != null) found.put(name, value)
        }
        return found
    } catch (Exception e) {
        throw new RuntimeException('parseParams failed: ' + e.getMessage(), e)
    }
}

String buildMacroElement(String name, String schemaVersion, String macroId, Map<String, String> params) {
    try {
        StringBuilder b = new StringBuilder()
        b.append('<ac:structured-macro ac:name="').append(xmlAttr(name))
         .append('" ac:schema-version="').append(xmlAttr(schemaVersion))
         .append('" ac:macro-id="').append(xmlAttr(macroId)).append('"')
        if (params.isEmpty()) { b.append(' />'); return b.toString() }
        b.append('>')
        for (Map.Entry<String, String> e : params.entrySet()) {
            b.append('<ac:parameter ac:name="').append(xmlAttr(e.getKey())).append('">')
             .append(xmlText(e.getValue())).append('</ac:parameter>')
        }
        b.append('</ac:structured-macro>')
        return b.toString()
    } catch (Exception e) {
        throw new RuntimeException('buildMacroElement failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MACRO DEFINITION LOOKUP - by declared type, no guessing
// =============================================================================

/** Velocity template of a user macro, or null. */
String userMacroTemplate(String name) {
    try {
        Object lib = beanOrNull('userMacroLibrary')
        Object cfg = reflectCall(lib, 'getMacro', [String] as Class[], [name] as Object[])
        if (cfg == null) return null
        Object tpl = reflectCall(cfg, 'getTemplate', new Class[0], new Object[0])
        if (tpl == null) tpl = reflectCall(cfg, 'getBody', new Class[0], new Object[0])
        return tpl == null ? null : tpl.toString()
    } catch (Exception e) {
        throw new RuntimeException('userMacroTemplate(' + name + ') failed: ' + e.getMessage(), e)
    }
}

/** name -> [default: x, enumValues: 'A,B,C', type: enum] from ## @param lines. */
Map<String, Map<String, String>> userMacroParams(String template) {
    try {
        Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>()
        if (template == null) return out
        for (String line : template.readLines()) {
            Matcher m = P_VELOCITY_PARAM.matcher(line)
            if (!m.matches()) continue
            Map<String, String> attrs = new LinkedHashMap<String, String>()
            String rest = m.group(2) == null ? '' : m.group(2).trim()
            for (String seg : rest.split('\\|')) {
                int eq = seg.indexOf('=')
                if (eq > 0) attrs.put(seg.substring(0, eq).trim(), seg.substring(eq + 1).trim())
            }
            out.put(m.group(1).trim(), attrs)
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('userMacroParams failed: ' + e.getMessage(), e)
    }
}

/** Macro-browser metadata parameter objects, or null. NOTE: the accessor is
 *  getFromDetails - that spelling is Atlassian's; getFormDetails does not exist. */
Collection metadataParams(String name) {
    try {
        Object mgr = beanOrNull('macroMetadataManager')
        Object md = reflectCall(mgr, 'getMacroMetadataByName', [String] as Class[], [name] as Object[])
        if (md == null) return null
        Object form = reflectCall(md, 'getFromDetails', new Class[0], new Object[0])
        if (form == null) form = reflectCall(md, 'getFormDetails', new Class[0], new Object[0])
        Object plist = reflectCall(form, 'getParameters', new Class[0], new Object[0])
        if (plist == null) plist = reflectCall(md, 'getParameters', new Class[0], new Object[0])
        return (plist instanceof Collection) ? (Collection) plist : null
    } catch (Exception e) {
        throw new RuntimeException('metadataParams(' + name + ') failed: ' + e.getMessage(), e)
    }
}

boolean macroExists(String name, MacroType type) {
    try {
        if (type == MacroType.UserMacro) return userMacroTemplate(name) != null
        return metadataParams(name) != null
    } catch (Exception e) {
        throw new RuntimeException('macroExists failed: ' + e.getMessage(), e)
    }
}

/** Declared parameter names for a macro, by type. */
List<String> declaredParamNames(String name, MacroType type) {
    try {
        List<String> out = new ArrayList<String>()
        if (type == MacroType.UserMacro) {
            out.addAll(userMacroParams(userMacroTemplate(name)).keySet())
            return out
        }
        Collection ps = metadataParams(name)
        if (ps == null) return out
        for (Object p : ps) {
            Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
            if (n != null) out.add(n.toString())
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('declaredParamNames failed: ' + e.getMessage(), e)
    }
}

/** Enum values declared for one parameter, in declaration order. */
List<String> declaredEnumValues(String macroName, MacroType type, String paramName) {
    try {
        List<String> out = new ArrayList<String>()
        if (type == MacroType.UserMacro) {
            Map<String, String> attrs = userMacroParams(userMacroTemplate(macroName)).get(paramName)
            if (attrs == null) return out
            String ev = attrs.get('enumValues')
            if (ev == null || ev.trim().isEmpty()) return out
            for (String v : ev.split(',')) out.add(v.trim())
            return out
        }
        Collection ps = metadataParams(macroName)
        if (ps == null) return out
        for (Object p : ps) {
            Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
            if (n == null || n.toString() != paramName) continue
            Object ev = reflectCall(p, 'getEnumValues', new Class[0], new Object[0])
            if (ev == null) ev = reflectCall(p, 'getValues', new Class[0], new Object[0])
            if (ev instanceof Collection) { for (Object v : (Collection) ev) out.add(v.toString()) }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('declaredEnumValues failed: ' + e.getMessage(), e)
    }
}

/** Declared defaults, by type. */
Map<String, String> declaredDefaults(String macroName, MacroType type) {
    try {
        Map<String, String> out = new LinkedHashMap<String, String>()
        if (type == MacroType.UserMacro) {
            Map<String, Map<String, String>> ps = userMacroParams(userMacroTemplate(macroName))
            for (Map.Entry<String, Map<String, String>> e : ps.entrySet()) {
                String d = e.getValue().get('default')
                if (d != null && !d.trim().isEmpty()) out.put(e.getKey(), d.trim())
            }
            return out
        }
        Collection cps = metadataParams(macroName)
        if (cps == null) return out
        for (Object p : cps) {
            Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
            Object d = reflectCall(p, 'getDefaultValue', new Class[0], new Object[0])
            if (n != null && d != null && !d.toString().trim().isEmpty()) {
                out.put(n.toString(), d.toString().trim())
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('declaredDefaults failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  EASYDROPDOWN - live configuration read
// =============================================================================

/** Ordered option name -> option-id for a set, read fresh from the database. */
Map<String, String> edmOptionsForSet(String setId, String setName) {
    try {
        Map<String, String> out = new LinkedHashMap<String, String>()
        List<List<String>> families = [
            ['AO_1313EC_LOZENGE_SET', 'AO_1313EC_LOZENGE_OPTION', 'LOZENGE_SET_ENTITY_ID'],
            ['AO_1313EC_TEXT_SET_ENTITY', 'AO_1313EC_TEXT_OPTION_ENTITY', 'TEXT_SET_ENTITY_ID'],
        ]
        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            for (List<String> f : families) {
                if (!out.isEmpty()) break
                String pk = null
                try {
                    String where = (setId != null && !setId.trim().isEmpty() && !setId.startsWith('PUT-'))
                            ? 's."SET_ID" = :v' : 's."SET_NAME" = :v'
                    String val = (setId != null && !setId.trim().isEmpty() && !setId.startsWith('PUT-'))
                            ? setId : setName
                    sql.eachRow('SELECT s."ID" AS pk FROM public."' + f.get(0) + '" s WHERE ' + where,
                                [v: val]) { row -> pk = row['pk'] as String }
                } catch (Exception ignored) { }
                if (pk == null) continue
                try {
                    sql.eachRow('SELECT o."OPTION_NAME" AS nm, o."OPTION_ID" AS gid FROM public."' +
                                f.get(1) + '" o WHERE o."' + f.get(2) + '" = :pk ORDER BY o."ID"',
                                [pk: Integer.parseInt(pk)]) { row ->
                        out.put(row['nm'] == null ? '' : row['nm'] as String, row['gid'] as String)
                    }
                } catch (Exception ignored2) { }
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('edmOptionsForSet failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  CONFIG PARSING
// =============================================================================

MigrationDef toMigration(Map<String, Object> cfg) {
    try {
        MigrationDef m = new MigrationDef()
        m.id = (String) cfg.get('id')
        Map<String, Object> src = (Map<String, Object>) cfg.get('source')
        Map<String, Object> tgt = (Map<String, Object>) cfg.get('target')
        if (src == null) throw new IllegalStateException('migration "' + m.id + '" has no source block')
        if (tgt == null) throw new IllegalStateException('migration "' + m.id + '" has no target block')

        m.sourceName  = (String) src.get('name')
        m.sourceType  = (MacroType) src.get('type')
        m.sourceParam = (String) src.get('sourceParam')

        m.targetName = (String) tgt.get('name')
        m.targetType = (MacroType) tgt.get('type')
        if (tgt.get('schemaVersion') != null) m.targetSchemaVersion = (String) tgt.get('schemaVersion')
        m.setId   = (String) tgt.get('setId')
        m.setName = (String) tgt.get('setName')
        if (tgt.get('options') != null)      m.options.putAll((Map<String, String>) tgt.get('options'))
        if (tgt.get('paramMap') != null)     m.paramMap.putAll((Map<String, String>) tgt.get('paramMap'))
        if (tgt.get('staticParams') != null) m.staticParams.putAll((Map<String, String>) tgt.get('staticParams'))
        if (src.get('paramDefaults') != null) m.paramDefaults.putAll((Map<String, String>) src.get('paramDefaults'))

        m.unwrapParagraph = cfg.get('unwrapParagraph') == null ? false : (Boolean) cfg.get('unwrapParagraph')
        if (tgt.get('dropUnmapped') != null) m.dropUnmapped = (Boolean) tgt.get('dropUnmapped')
        return m
    } catch (Exception e) {
        throw new RuntimeException('toMigration failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STAGE-0  VALIDATION
// =============================================================================

List<ValidationIssue> validateScope(SpaceManager spaceManager, PageManager pageManager) {
    try {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>()
        for (String key : SPACE_KEYS) {
            if (spaceManager.getSpace(key) == null) {
                ValidationIssue i = new ValidationIssue()
                i.migrationId = '(scope)'; i.sourceLabel = key; i.targetLabel = ''
                i.description = 'SPACE_KEYS names a space that does not exist'
                issues.add(i)
            }
        }
        for (Long pid : PAGE_IDS_OVERRIDE) {
            Page p = pageManager.getPage(pid.longValue())
            ValidationIssue i = new ValidationIssue()
            i.migrationId = '(scope)'; i.sourceLabel = pid as String; i.targetLabel = ''
            if (p == null) {
                i.description = 'PAGE_IDS_OVERRIDE names a page id that does not exist'
                issues.add(i)
            } else if (p.getOriginalVersionId() != null) {
                i.description = 'PAGE_IDS_OVERRIDE names a HISTORICAL version id; pass the current page id'
                issues.add(i)
            }
        }
        return issues
    } catch (Exception e) {
        throw new RuntimeException('validateScope failed: ' + e.getMessage(), e)
    }
}

ValidationIssue issue(String mid, String s, String t, String d) {
    ValidationIssue i = new ValidationIssue()
    i.migrationId = mid; i.sourceLabel = s; i.targetLabel = t; i.description = d
    return i
}

List<ValidationIssue> validateMigration(MigrationDef m, StringBuilder detail) {
    try {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>()

        // --- source macro must exist -------------------------------------
        if (m.sourceName == null || m.sourceName.trim().isEmpty()) {
            issues.add(issue(m.id, '(none)', '', 'source name is empty'))
            return issues
        }
        boolean srcExists = macroExists(m.sourceName, m.sourceType)
        if (!srcExists) {
            issues.add(issue(m.id, m.sourceName, '',
                    'source macro not found as ' + m.sourceType + '. Either the type is wrong or the ' +
                    'macro is uninstalled. If it is genuinely uninstalled its usages still need ' +
                    'replacing - set the correct type or remove this migration.'))
        } else {
            m.discoveredDefaults.putAll(declaredDefaults(m.sourceName, m.sourceType))
        }

        // --- source parameter must exist ---------------------------------
        if (m.sourceParam != null && srcExists) {
            List<String> declared = declaredParamNames(m.sourceName, m.sourceType)
            if (!declared.contains(m.sourceParam)) {
                issues.add(issue(m.id, m.sourceName, '',
                        'sourceParam "' + m.sourceParam + '" is not declared on the macro. Declared: ' + declared))
            } else {
                m.sourceEnumValues = declaredEnumValues(m.sourceName, m.sourceType, m.sourceParam)
            }
        }

        // --- target ------------------------------------------------------
        if (m.targetType == MacroType.Static_QualificationTable) {
            detail.append('  ').append(m.id).append(': target is computed static storage, no target macro to check\n')
            return issues
        }

        if (m.targetName == null || m.targetName.trim().isEmpty()) {
            issues.add(issue(m.id, m.sourceName, '(none)', 'target name is empty'))
            return issues
        }
        if (!macroExists(m.targetName, m.targetType)) {
            issues.add(issue(m.id, m.sourceName, m.targetName,
                    'target macro not found as ' + m.targetType + ' - it must be installed before replacing'))
        }

        if (m.targetType != MacroType.EddStatusMacro) return issues

        // --- EasyDropDown: compare config against the live database -------
        Map<String, String> live = edmOptionsForSet(m.setId, m.setName)
        if (live.isEmpty()) {
            issues.add(issue(m.id, m.sourceName, m.targetName,
                    'no EasyDropDown set resolved from setId="' + m.setId + '" setName="' + m.setName + '"'))
            return issues
        }

        List<String> srcVals = m.sourceEnumValues
        List<String> liveNames = new ArrayList<String>(live.keySet())
        List<String> cfgNames  = new ArrayList<String>(m.options.keySet())

        if (srcVals.size() != liveNames.size()) {
            issues.add(issue(m.id, m.sourceName + ' (' + srcVals.size() + ' enum values)',
                    m.targetName + ' (' + liveNames.size() + ' options)',
                    'COUNT MISMATCH between source enum values and target set options'))
        }

        for (String v : srcVals) {
            if (!liveNames.contains(v)) {
                issues.add(issue(m.id, v, '(missing)', 'source enum value has no option in the target set'))
            }
            if (!cfgNames.contains(v)) {
                issues.add(issue(m.id, v, '(missing)', 'source enum value has no entry in target options map'))
            }
        }
        for (String o : liveNames) {
            if (!srcVals.contains(o)) {
                issues.add(issue(m.id, '(missing)', o, 'target set option has no matching source enum value'))
            }
        }
        for (Map.Entry<String, String> e : m.options.entrySet()) {
            String liveId = live.get(e.getKey())
            if (liveId == null) {
                issues.add(issue(m.id, '(none)', e.getKey(),
                        'configured option name does not exist in the live set'))
            } else if (liveId != e.getValue()) {
                issues.add(issue(m.id, '(none)', e.getKey(),
                        'configured option-id ' + e.getValue() + ' is stale; live value is ' + liveId))
            }
        }

        if (VALIDATE_OPTION_ORDER && srcVals.size() == liveNames.size()) {
            for (int i = 0; i < srcVals.size(); i++) {
                if (srcVals.get(i) != liveNames.get(i)) {
                    issues.add(issue(m.id, srcVals.get(i), liveNames.get(i),
                            'option ORDER mismatch at position ' + (i + 1)))
                }
            }
        }

        // side-by-side table, longer side first
        int rows = Math.max(srcVals.size(), liveNames.size())
        detail.append('  ').append(m.id).append('  source enum values vs live target options\n')
        detail.append('    ').append(String.format('%-34s %-34s %s', 'SOURCE (' + m.sourceName + ')',
                'TARGET (' + m.targetName + ')', 'OPTION-ID')).append('\n')
        for (int i = 0; i < rows; i++) {
            String sv = i < srcVals.size() ? srcVals.get(i) : ''
            String tv = i < liveNames.size() ? liveNames.get(i) : ''
            String id = tv.isEmpty() ? '' : live.get(tv)
            detail.append('    ').append(String.format('%-34s %-34s %s', sv, tv, id == null ? '' : id)).append('\n')
        }
        detail.append('\n')
        return issues
    } catch (Exception e) {
        throw new RuntimeException('validateMigration(' + m.id + ') failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STAGE-1  SCAN
// =============================================================================

/** Distinct current-page ids in scope, from the database (or the override). */
List<Long> resolveScope(Set<String> sourceNames) {
    try {
        if (!PAGE_IDS_OVERRIDE.isEmpty()) return new ArrayList<Long>(PAGE_IDS_OVERRIDE)
        Set<Long> ids = new LinkedHashSet<Long>()
        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            for (String name : sourceNames) {
                Map<String, Object> params = new LinkedHashMap<String, Object>()
                params.put('pattern', '%ac:name="' + name + '"%')
                StringBuilder q = new StringBuilder()
                q.append('SELECT c.contentid AS rowid, c.prevver AS prevver FROM content c ')
                 .append('JOIN bodycontent bc ON bc.contentid = c.contentid ')
                 .append('LEFT JOIN spaces s ON s.spaceid = c.spaceid ')
                 .append("WHERE c.contenttype IN ('PAGE','BLOGPOST') AND bc.body LIKE :pattern ")
                if (!SPACE_KEYS.isEmpty()) {
                    List<String> ph = new ArrayList<String>()
                    for (int i = 0; i < SPACE_KEYS.size(); i++) { ph.add(':sk' + i); params.put('sk' + i, SPACE_KEYS.get(i)) }
                    q.append('AND s.spacekey IN (').append(ph.join(', ')).append(') ')
                }
                if (!INCLUDE_STATUSES.isEmpty()) {
                    List<String> ph = new ArrayList<String>()
                    for (int i = 0; i < INCLUDE_STATUSES.size(); i++) { ph.add(':st' + i); params.put('st' + i, INCLUDE_STATUSES.get(i)) }
                    q.append('AND c.content_status IN (').append(ph.join(', ')).append(') ')
                }
                sql.eachRow(q.toString(), params) { row ->
                    Object prev = row['prevver']
                    ids.add(prev == null ? ((Number) row['rowid']).longValue() : ((Number) prev).longValue())
                }
            }
        }
        return new ArrayList<Long>(ids)
    } catch (Exception e) {
        throw new RuntimeException('resolveScope failed: ' + e.getMessage(), e)
    }
}

/**
 * Scans one body. Counts EVERY macro for macroIndex; records only those whose
 * ac:name matches a migration source. Returns plain-value findings.
 */
List<MatchedMacro> scanBody(String body, Map<String, MigrationDef> bySource) {
    try {
        List<MatchedMacro> out = new ArrayList<MatchedMacro>()
        if (body == null) return out
        Matcher m = P_ANY_MACRO.matcher(body)
        int macroIndex = 0, matchedIndex = 0
        while (m.find()) {
            macroIndex++
            String xml = m.group(0)
            String name = attrOf(P_NAME, xml)
            MigrationDef mig = bySource.get(name)
            if (mig == null) continue
            matchedIndex++
            MatchedMacro mm = new MatchedMacro()
            mm.migrationId = mig.id
            mm.sourceName = name
            mm.sourceType = mig.sourceType
            mm.targetName = mig.targetName
            mm.targetType = mig.targetType
            mm.macroId = attrOf(P_MACRO_ID, xml)
            mm.macroIndex = macroIndex
            mm.matchedIndex = matchedIndex
            mm.params.putAll(parseParams(xml))
            out.add(mm)
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('scanBody failed: ' + e.getMessage(), e)
    }
}

/** Version ids of a page: current first, then history (when enabled). */
List<Long> versionContentIds(PageManager pageManager, Page page) {
    try {
        List<Long> ids = new ArrayList<Long>()
        ids.add(page.getId())
        if (!UPDATE_HISTORICAL_VERSIONS) return ids
        for (VersionHistorySummary vhs : pageManager.getVersionHistorySummaries(page)) {
            long hid = vhs.getId()
            if (hid != page.getId()) ids.add(hid)
        }
        return ids
    } catch (Exception e) {
        throw new RuntimeException('versionContentIds failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STAGE-2/3  TRANSFORMS - one per macro type
// =============================================================================

String resolveValue(MigrationDef mig, Map<String, String> onPage, String key) {
    String v = onPage.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    v = mig.paramDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    v = mig.discoveredDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    return null
}

String replaceEddStatus(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        String srcVal = resolveValue(mig, mm.params, mig.sourceParam)
        if (srcVal == null) {
            throw new IllegalStateException('parameter "' + mig.sourceParam +
                    '" not resolved; parsed from this macro: ' + mm.params)
        }
        String optionId = mig.options.get(srcVal)
        if (optionId == null) {
            throw new IllegalStateException('value "' + srcVal + '" has no option-id in the migration ' +
                    'options map. Known: ' + mig.options.keySet())
        }
        Map<String, String> out = new LinkedHashMap<String, String>()
        out.putAll(mig.staticParams)
        out.put('set-id', mig.setId)
        out.put('current-option-value', srcVal)
        out.put('option-id', optionId)
        String macroId = UUID.randomUUID().toString()
        if (TRACE_MAPPING) {
            notes.add('  ' + mig.sourceParam + '="' + srcVal + '"' +
                      (mm.params.containsKey(mig.sourceParam) ? ' [from page]' : ' [FROM DEFAULT]') +
                      ' -> option-id=' + optionId + ' macro-id=' + macroId)
        }
        return buildMacroElement(mig.targetName, mig.targetSchemaVersion, macroId, out)
    } catch (IllegalStateException ise) {
        throw ise
    } catch (Exception e) {
        throw new RuntimeException('replaceEddStatus failed: ' + e.getMessage(), e)
    }
}

/** Generic macro -> macro: rename, optional parameter remap, optional passthrough. */
String replaceGenericMacro(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        Map<String, String> out = new LinkedHashMap<String, String>()
        out.putAll(mig.staticParams)
        for (Map.Entry<String, String> e : mm.params.entrySet()) {
            String tgtKey = mig.paramMap.get(e.getKey())
            if (tgtKey == null) {
                if (mig.dropUnmapped) continue
                tgtKey = e.getKey()
            }
            out.put(tgtKey, e.getValue())
        }
        String macroId = UUID.randomUUID().toString()
        if (TRACE_MAPPING) notes.add('  ' + mm.sourceName + ' -> ' + mig.targetName + ' params=' + out)
        return buildMacroElement(mig.targetName, mig.targetSchemaVersion, macroId, out)
    } catch (Exception e) {
        throw new RuntimeException('replaceGenericMacro failed: ' + e.getMessage(), e)
    }
}

/** qualification-table -> static storage. percentage = 100*sum*relevance/135 */
String replaceQualificationTable(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        Map<String, String> resolved = new LinkedHashMap<String, String>()
        List<String> missing = new ArrayList<String>()
        List<String> required = new ArrayList<String>()
        for (List<String> c : QM_COLUMNS) required.add(c.get(1))
        required.add('relevance')

        for (String key : required) {
            String v = resolveValue(mig, mm.params, key)
            if (v == null || !(v ==~ /\d+/)) { missing.add(key); continue }
            resolved.put(key, v)
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException('unresolved/non-numeric parameter(s) ' + missing +
                    '; parsed from this macro: ' + mm.params)
        }

        int relevance = Integer.parseInt(resolved.get('relevance'))
        int sum = 0
        for (List<String> c : QM_COLUMNS) sum += Integer.parseInt(resolved.get(c.get(1)))
        int pct = ((100 * sum * relevance).intdiv(135)) as int

        StringBuilder headers = new StringBuilder(), values = new StringBuilder()
        for (List<String> c : QM_COLUMNS) {
            headers.append('<th>').append(xmlText(c.get(0))).append('</th>')
            values.append('<td>').append(xmlText(resolved.get(c.get(1)))).append('</td>')
        }
        StringBuilder h = new StringBuilder()
        h.append('<p>Qualifikationsfaktor: ').append(pct).append('%</p>')
        h.append('<table><tbody>')
        h.append('<tr><th>Bedeutung</th><th colspan="9">Auswirkung</th></tr>')
        h.append('<tr><th>&nbsp;</th>').append(headers).append('</tr>')
        h.append('<tr><td>').append(relevance).append('</td>').append(values).append('</tr>')
        h.append('</tbody></table>')
        h.append('<p>&nbsp;</p>')
        h.append('<p>').append(pct).append('%</p>')
        if (TRACE_MAPPING) notes.add('  relevance=' + relevance + ' sum=' + sum + ' -> ' + pct + '%')
        return h.toString()
    } catch (IllegalStateException ise) {
        throw ise
    } catch (Exception e) {
        throw new RuntimeException('replaceQualificationTable failed: ' + e.getMessage(), e)
    }
}

/** Generic entry point: branches by target type. Add a type -> add a branch. */
String replaceMacro(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        if (mig.targetType == MacroType.EddStatusMacro)            return replaceEddStatus(mig, mm, notes)
        if (mig.targetType == MacroType.Static_QualificationTable) return replaceQualificationTable(mig, mm, notes)
        if (mig.targetType == MacroType.ScriptRunnerMacro ||
            mig.targetType == MacroType.UserMacro ||
            mig.targetType == MacroType.AuraLinkButton)            return replaceGenericMacro(mig, mm, notes)
        throw new IllegalStateException('no replace implementation for target type ' + mig.targetType)
    } catch (IllegalStateException ise) {
        throw ise
    } catch (Exception e) {
        throw new RuntimeException('replaceMacro failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  STAGE-3  APPLY TO ONE VERSION BODY
// =============================================================================

/**
 * Rebuilds a version body, replacing every planned macro in one pass.
 *
 * Matching is re-done against the FRESH body and keyed on (ac:macro-id, order),
 * so a macro that moved or was already replaced since Stage-1 is handled: a
 * planned entry that no longer matches is marked Skipped with a warning rather
 * than silently dropped.
 *
 * Paragraph unwrapping is done by inspecting the text either side of the match,
 * so a <p> holding ONLY the macro is consumed (needed when the replacement is a
 * <table>), and a <p> holding anything else is left intact.
 */
String applyToBody(String body, VersionFinding vf, Map<String, MigrationDef> byId,
                   Map<String, MigrationDef> bySource, List<String> notes) {
    try {
        // planned entries grouped by macro-id, consumed in document order
        Map<String, List<MatchedMacro>> pending = new LinkedHashMap<String, List<MatchedMacro>>()
        for (MatchedMacro mm : vf.matchedMacros) {
            List<MatchedMacro> l = pending.get(mm.macroId)
            if (l == null) { l = new ArrayList<MatchedMacro>(); pending.put(mm.macroId, l) }
            l.add(mm)
        }
        for (Map.Entry<String, List<MatchedMacro>> e : pending.entrySet()) {
            if (e.getValue().size() > 1) {
                notes.add('WARNING duplicate ac:macro-id "' + e.getKey() + '" appears ' +
                          e.getValue().size() + ' times in this version; each occurrence is ' +
                          'replaced with its own parameter values, paired in document order')
            }
        }

        StringBuilder outBody = new StringBuilder()
        Matcher m = P_ANY_MACRO.matcher(body)
        int cursor = 0

        while (m.find()) {
            String xml = m.group(0)
            String name = attrOf(P_NAME, xml)
            MigrationDef mig = bySource.get(name)
            if (mig == null) continue

            String mid = attrOf(P_MACRO_ID, xml)
            List<MatchedMacro> queue = pending.get(mid)
            if (queue == null || queue.isEmpty()) continue      // not planned
            MatchedMacro mm = queue.remove(0)

            String replacement
            try {
                // use the parameters as they are NOW, not as scanned
                MatchedMacro fresh = new MatchedMacro()
                fresh.migrationId = mm.migrationId; fresh.sourceName = name; fresh.macroId = mid
                fresh.sourceType = mm.sourceType;   fresh.targetType = mm.targetType
                fresh.targetName = mm.targetName
                fresh.params.putAll(parseParams(xml))
                replacement = replaceMacro(mig, fresh, notes)
            } catch (IllegalStateException ise) {
                if (ON_MISSING == 'FAIL') throw ise
                mm.status = ReplacementStatus.Skipped
                mm.message = ise.getMessage()
                mig.occSkipped++
                notes.add('  SKIPPED macro-id=' + mid + ': ' + ise.getMessage())
                continue                                        // leave untouched
            }

            int start = m.start(), end = m.end()
            String pre = body.substring(cursor, start)

            if (mig.unwrapParagraph) {
                String preTrimmed = pre.replaceAll('\\s+$', '')
                boolean opensP = preTrimmed.endsWith('<p>')
                Matcher closes = Pattern.compile('^\\s*</p>').matcher(body.substring(end))
                if (opensP && closes.find()) {
                    pre = preTrimmed.substring(0, preTrimmed.length() - 3)
                    end = end + closes.end()
                }
            }

            outBody.append(pre).append(replacement)
            cursor = end
            mm.status = ReplacementStatus.Success       // provisional until the write lands
            mig.occReplaced++
        }
        outBody.append(body.substring(cursor))

        for (Map.Entry<String, List<MatchedMacro>> e : pending.entrySet()) {
            for (MatchedMacro left : e.getValue()) {
                left.status = ReplacementStatus.Skipped
                left.message = 'macro-id no longer present in the body at execution time'
                MigrationDef mig = byId.get(left.migrationId)
                if (mig != null) mig.occSkipped++
                notes.add('  SKIPPED ' + left.sourceName + ' macro-id=' + e.getKey() +
                          ' - not found on re-read; another process may have changed the page')
            }
        }
        return outBody.toString()
    } catch (IllegalStateException ise) {
        throw ise
    } catch (Exception e) {
        throw new RuntimeException('applyToBody failed: ' + e.getMessage(), e)
    }
}

void writeCurrentVersion(PageManager pm, Page page, String newBody) {
    try {
        if (CURRENT_CREATES_NEW_VERSION) {
            final String b = newBody
            Modification<Page> mod = new Modification<Page>() {
                @Override void modify(Page target) { target.setBodyAsString(b) }
            }
            pm.saveNewVersion(page, mod, new DefaultSaveContext(true, false, false))
        } else {
            page.setBodyAsString(newBody)
            pm.saveContentEntity(page, new DefaultSaveContext(true, false, false))
        }
    } catch (Exception e) {
        throw new RuntimeException('writeCurrentVersion failed: ' + e.getMessage(), e)
    }
}

void writeHistoricalVersion(PageManager pm, ContentEntityObject hist, String newBody) {
    try {
        Date keep = hist.getLastModificationDate()
        hist.setBodyAsString(newBody)
        hist.setLastModificationDate(keep)
        pm.saveContentEntity(hist, new DefaultSaveContext(true, false, HISTORICAL_SUPPRESS_EVENTS))
    } catch (Exception e) {
        throw new RuntimeException('writeHistoricalVersion failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MAIN
// =============================================================================

long runStart = System.currentTimeMillis()
StringBuilder outp = new StringBuilder()
StringBuilder rollback = new StringBuilder()
StringBuilder results = new StringBuilder()

try {
    if (MODE != 'INSPECT' && MODE != 'APPLY') throw new IllegalStateException('MODE must be INSPECT or APPLY')
    if (ON_MISSING != 'SKIP' && ON_MISSING != 'FAIL') throw new IllegalStateException('ON_MISSING must be SKIP or FAIL')

    boolean apply = (MODE == 'APPLY')
    PageManager pageManager = ComponentLocator.getComponent(PageManager)
    SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager)
    String baseUrl = ''
    try { baseUrl = ComponentLocator.getComponent(SettingsManager).getGlobalSettings().getBaseUrl() }
    catch (Throwable t) { baseUrl = '' }

    // ---- select migrations ------------------------------------------------
    List<MigrationDef> migrations = new ArrayList<MigrationDef>()
    List<String> knownIds = new ArrayList<String>()
    for (Map<String, Object> cfg : MIGRATIONS) {
        MigrationDef md = toMigration(cfg)
        knownIds.add(md.id)
        if (RUN.isEmpty() || RUN.contains(md.id)) migrations.add(md)
    }
    if (migrations.isEmpty()) throw new IllegalStateException('RUN matched no migration ids. Known: ' + knownIds)

    outp.append('MODE: ').append(MODE).append(apply ? '   *** WRITES ENABLED ***' : '   (read only)').append('\n')
    outp.append('Migrations: ').append(migrations.collect { MigrationDef d -> d.id }.join(', ')).append('\n')
    for (String r : RUN) { if (!knownIds.contains(r)) outp.append('WARNING: RUN id "').append(r).append('" is not defined\n') }
    if (!PAGE_IDS_OVERRIDE.isEmpty()) {
        outp.append('WARNING: PAGE_IDS_OVERRIDE set - database discovery is bypassed for every migration; ')
            .append(PAGE_IDS_OVERRIDE.size()).append(' page(s) will be examined\n')
    }
    if (!SPACE_KEYS.isEmpty()) outp.append('Spaces: ').append(SPACE_KEYS.join(', ')).append('\n')
    outp.append('History: ').append(UPDATE_HISTORICAL_VERSIONS ? 'included' : 'skipped')
        .append('   Batch: ').append(FLUSH_AFTER_BATCH ? BATCH_MAX_PAGES + ' pages' : 'no flushing').append('\n')
    outp.append('================================================================\n\n')

    // ---- STAGE 0 ----------------------------------------------------------
    outp.append('STAGE-0  VALIDATION\n----------------------------------------------------------------\n')
    StringBuilder valDetail = new StringBuilder()
    List<ValidationIssue> issues = new ArrayList<ValidationIssue>()
    issues.addAll(validateScope(spaceManager, pageManager))
    for (MigrationDef md : migrations) issues.addAll(validateMigration(md, valDetail))

    if (valDetail.length() > 0) outp.append(valDetail).append('\n')
    if (!issues.isEmpty()) {
        outp.append(String.format('  %-22s %-34s %-34s %s%n', 'MIGRATION', 'SOURCE', 'TARGET', 'ISSUE'))
        for (ValidationIssue vi : issues) {
            outp.append(String.format('  %-22s %-34s %-34s %s%n',
                    vi.migrationId, vi.sourceLabel, vi.targetLabel, vi.description))
        }
        outp.append('\n  ').append(issues.size()).append(' validation issue(s). Nothing was read or written.\n')
        return '<pre>' + htmlEsc(outp.toString()) + '</pre>'
    }
    outp.append('  passed\n\n')

    // ---- STAGE 1 ----------------------------------------------------------
    Map<String, MigrationDef> bySource = new LinkedHashMap<String, MigrationDef>()
    Map<String, MigrationDef> byId = new LinkedHashMap<String, MigrationDef>()
    for (MigrationDef md : migrations) { bySource.put(md.sourceName, md); byId.put(md.id, md) }

    outp.append('STAGE-1  SCAN\n----------------------------------------------------------------\n')
    List<Long> scope = resolveScope(bySource.keySet())
    outp.append('  pages in scope: ').append(scope.size()).append('\n')

    List<PageFinding> findings = new ArrayList<PageFinding>()
    int batchCount = 0, pagesDone = 0
    for (Long pid : scope) {
        Page page = pageManager.getPage(pid.longValue())
        if (page == null) { RUN_LOG.add('page ' + pid + ' not found during scan'); continue }
        if (page.getOriginalVersionId() != null) { RUN_LOG.add('page ' + pid + ' is a historical id; skipped'); continue }

        PageFinding pf = new PageFinding()
        pf.pageId = pid.longValue()
        pf.pageName = page.getTitle() == null ? '' : page.getTitle()
        // space is read ONCE from the current version; version rows never carry it
        pf.spaceKey = (page.getSpace() == null) ? '' : page.getSpace().getKey()
        pf.url = baseUrl + '/pages/viewpage.action?pageId=' + pf.pageId

        if (!SPACE_KEYS.isEmpty() && !SPACE_KEYS.contains(pf.spaceKey)) continue

        for (Long cid : versionContentIds(pageManager, page)) {
            ContentEntityObject ceo = pageManager.getPage(cid.longValue())
            if (ceo == null) continue
            VersionFinding vf = new VersionFinding()
            vf.contentId = cid.longValue()
            vf.versionNumber = ceo.getVersion()
            vf.isCurrent = (cid.longValue() == pf.pageId)
            vf.matchedMacros = scanBody(ceo.getBodyAsString(), bySource)
            vf.hasMatchedMacros = !vf.matchedMacros.isEmpty()
            if (vf.hasMatchedMacros) pf.versions.add(vf)
        }
        if (!pf.versions.isEmpty()) findings.add(pf)

        pagesDone++; batchCount++
        if (FLUSH_AFTER_BATCH && batchCount >= BATCH_MAX_PAGES) { flushSession(); batchCount = 0 }
    }
    if (FLUSH_AFTER_BATCH && batchCount > 0) flushSession()

    int totalVersions = 0, totalOcc = 0
    for (PageFinding pf : findings) {
        totalVersions += pf.versions.size()
        for (VersionFinding vf : pf.versions) totalOcc += vf.matchedMacros.size()
    }
    for (PageFinding pf : findings) {
        for (VersionFinding vf : pf.versions) {
            for (MatchedMacro mm : vf.matchedMacros) {
                MigrationDef md = byId.get(mm.migrationId)
                if (md != null) md.occFound++
            }
        }
    }
    outp.append('  pages with matches: ').append(findings.size())
        .append('   versions with matches: ').append(totalVersions)
        .append('   occurrences: ').append(totalOcc).append('\n')
    outp.append('  session flush: ').append(FLUSH_NOTE).append('\n\n')

    // ---- STAGE 2 ----------------------------------------------------------
    outp.append('STAGE-2  PLAN\n----------------------------------------------------------------\n')
    for (PageFinding pf : findings) {
        for (VersionFinding vf : pf.versions) {
            for (MatchedMacro mm : vf.matchedMacros) {
                MigrationDef md = byId.get(mm.migrationId)
                if (md == null) { mm.status = ReplacementStatus.Skipped; mm.message = 'no migration'; continue }
                mm.targetName = md.targetName
                mm.targetType = md.targetType
                mm.status = ReplacementStatus.Unknown
            }
        }
    }
    outp.append('  planned: ').append(totalOcc).append(' occurrence(s)\n\n')

    // ---- STAGE 3 ----------------------------------------------------------
    outp.append('STAGE-3  ').append(apply ? 'EXECUTE' : 'DRY RUN').append('\n')
    outp.append('----------------------------------------------------------------\n')
    List<String> notes = new ArrayList<String>()
    batchCount = 0
    int rollbackEmitted = 0

    for (PageFinding pf : findings) {
        for (VersionFinding vf : pf.versions) {
            try {
                ContentEntityObject ceo = pageManager.getPage(vf.contentId)
                if (ceo == null) {
                    vf.status = ReplacementStatus.Failed
                    vf.message = 'version disappeared before execution'
                    continue
                }
                String before = ceo.getBodyAsString()
                notes.add('page ' + pf.pageId + ' v' + vf.versionNumber + (vf.isCurrent ? ' (current)' : ''))
                String after = applyToBody(before, vf, byId, bySource, notes)

                int done = 0, skipped = 0
                for (MatchedMacro mm : vf.matchedMacros) {
                    if (mm.status == ReplacementStatus.Success) done++
                    if (mm.status == ReplacementStatus.Skipped) skipped++
                }

                if (done == 0) {
                    vf.status = skipped > 0 ? ReplacementStatus.Skipped : ReplacementStatus.Failed
                    vf.message = skipped > 0 ? 'all occurrences skipped' : 'no occurrence replaced'
                    continue
                }

                if (!apply) {
                    vf.status = ReplacementStatus.Success
                    vf.message = 'would change'
                    vf.bodyBefore = before; vf.bodyAfter = after
                    continue
                }

                // ---- write, with retry on stale entity -------------------
                String werr = null
                int attempt = 0
                String bodyToWrite = after
                while (true) {
                    try {
                        if (vf.isCurrent) {
                            Page target = pageManager.getPage(pf.pageId)
                            if (target == null) { werr = 'page disappeared before write'; break }
                            writeCurrentVersion(pageManager, target, bodyToWrite)
                        } else {
                            ContentEntityObject target = pageManager.getPage(vf.contentId)
                            if (target == null) { werr = 'version disappeared before write'; break }
                            writeHistoricalVersion(pageManager, target, bodyToWrite)
                        }
                        werr = null
                        break
                    } catch (Exception we) {
                        String cn = we.getClass().getName()
                        String msg = we.getMessage() == null ? '' : we.getMessage()
                        boolean stale = cn.contains('OptimisticLocking') || cn.contains('StaleObject') ||
                                        msg.contains('unexpected row count')
                        attempt++
                        if (!stale || attempt > WRITE_RETRIES) {
                            werr = we.getClass().getSimpleName() + ': ' + msg
                            break
                        }
                        ContentEntityObject reread = pageManager.getPage(vf.contentId)
                        if (reread == null) { werr = 'version disappeared during retry'; break }
                        bodyToWrite = applyToBody(reread.getBodyAsString(), vf, byId, bySource, notes)
                    }
                }

                if (werr != null) {
                    vf.status = ReplacementStatus.Failed
                    vf.message = werr
                    for (MatchedMacro mm : vf.matchedMacros) {
                        if (mm.status == ReplacementStatus.Success) {
                            mm.status = ReplacementStatus.Failed
                            mm.message = werr
                            MigrationDef md = byId.get(mm.migrationId)
                            if (md != null) { md.occReplaced--; md.occFailed++ }
                        }
                    }
                } else if (VERIFY_AFTER_WRITE) {
                    ContentEntityObject check = pageManager.getPage(vf.contentId)
                    String freshBody = check == null ? '' : check.getBodyAsString()
                    int stillThere = 0
                    for (MatchedMacro mm : vf.matchedMacros) {
                        if (mm.status != ReplacementStatus.Success) continue
                        if (freshBody.contains('ac:macro-id="' + mm.macroId + '"')) {
                            stillThere++
                            mm.status = ReplacementStatus.Failed
                            mm.message = 'still present after write'
                            MigrationDef md = byId.get(mm.migrationId)
                            if (md != null) { md.occReplaced--; md.occFailed++ }
                        }
                    }
                    vf.status = (stillThere == 0) ? ReplacementStatus.Success : ReplacementStatus.Failed
                    vf.message = (stillThere == 0) ? 'written and verified'
                                                   : stillThere + ' macro(s) still present after write'
                } else {
                    vf.status = ReplacementStatus.Success
                    vf.message = 'written (not verified)'
                }
                vf.bodyBefore = before; vf.bodyAfter = after
            } catch (Exception ve) {
                vf.status = ReplacementStatus.Failed
                vf.message = ve.getClass().getSimpleName() + ': ' + ve.getMessage()
                RUN_LOG.add('page ' + pf.pageId + ' v' + vf.versionNumber + ' - ' + vf.message)
            }

            // ---- rollback copies, emitted per version then dropped ------
            if (EMIT_ROLLBACK_COPIES && apply && vf.bodyBefore != null && rollbackEmitted < MAX_ROLLBACK_ENTRIES) {
                boolean ok = (vf.status == ReplacementStatus.Success)
                rollbackEmitted++
                rollback.append('---- page ').append(pf.pageId).append(' contentid ').append(vf.contentId)
                        .append(' v').append(vf.versionNumber).append('  ').append(vf.status).append('\n')
                boolean cSrc = ok ? COMPRESS_SOURCE_ON_SUCCESS : COMPRESS_SOURCE_ON_FAILURE
                rollback.append('BEFORE:\n')
                        .append(cSrc ? compressToText(vf.bodyBefore) : htmlEsc(vf.bodyBefore)).append('\n')
                if (!ok || EMIT_REPLACED_ON_SUCCESS) {
                    boolean cRep = ok ? COMPRESS_REPLACED_ON_SUCCESS : COMPRESS_REPLACED_ON_FAILURE
                    rollback.append('AFTER:\n')
                            .append(cRep ? compressToText(vf.bodyAfter) : htmlEsc(vf.bodyAfter)).append('\n')
                }
                rollback.append('\n')
            }
            vf.bodyBefore = null; vf.bodyAfter = null      // do not accumulate bodies
        }
        batchCount++
        if (FLUSH_AFTER_BATCH && batchCount >= BATCH_MAX_PAGES) { flushSession(); batchCount = 0 }
    }
    if (FLUSH_AFTER_BATCH && batchCount > 0) flushSession()

    // ---- SUMMARY ----------------------------------------------------------
    outp.append('\n  RESULTS BY MIGRATION\n')
    outp.append(String.format('  %-24s %-9s %-10s %-9s %-8s%n', 'MIGRATION', 'FOUND', 'REPLACED', 'SKIPPED', 'FAILED'))
    for (MigrationDef md : migrations) {
        outp.append(String.format('  %-24s %-9s %-10s %-9s %-8s%n', md.id,
                md.occFound as String, md.occReplaced as String,
                md.occSkipped as String, md.occFailed as String))
    }

    List<String> failedVersions = new ArrayList<String>()
    for (PageFinding pf : findings) {
        for (VersionFinding vf : pf.versions) {
            if (vf.status == ReplacementStatus.Failed) {
                failedVersions.add(pf.url + '  v' + vf.versionNumber + '  ' + vf.message)
            }
        }
    }
    if (!failedVersions.isEmpty()) {
        outp.append('\n  FAILED VERSIONS\n')
        for (String f : failedVersions) outp.append('    ').append(f).append('\n')
    }
    if (!RUN_LOG.isEmpty()) {
        outp.append('\n  RUN LOG\n')
        for (String l : RUN_LOG) outp.append('    ').append(l).append('\n')
    }
    if (TRACE_MAPPING && !notes.isEmpty()) {
        outp.append('\n  TRACE\n')
        for (String n : notes) outp.append('    ').append(n).append('\n')
    }
    outp.append('\nTOTAL ELAPSED: ').append(humanTime(System.currentTimeMillis() - runStart)).append('\n')

    // ---- RESULTS LISTING --------------------------------------------------
    if (RESULT_FORMAT == 'TABLE') {
        results.append('<h3>').append(apply ? 'Result' : 'Detected').append('</h3>')
        results.append('<table border="1" cellpadding="4" cellspacing="0"><tr>')
               .append('<th>Page ID</th><th>Page Name</th><th>Page URL</th><th>Version</th><th>Current</th>')
               .append('<th>Occurrences</th><th>Replaced</th><th>Skipped</th><th>Failed</th><th>Status</th></tr>')
        for (PageFinding pf : findings) {
            for (VersionFinding vf : pf.versions) {
                int r = 0, s = 0, f = 0
                for (MatchedMacro mm : vf.matchedMacros) {
                    if (mm.status == ReplacementStatus.Success) r++
                    else if (mm.status == ReplacementStatus.Skipped) s++
                    else if (mm.status == ReplacementStatus.Failed) f++
                }
                results.append('<tr><td>').append(pf.pageId)
                       .append('</td><td>').append(htmlEsc(pf.pageName))
                       .append('</td><td><a href="').append(pf.url).append('" target="_blank">')
                       .append(htmlEsc(pf.url)).append('</a></td><td>').append(vf.versionNumber)
                       .append('</td><td>').append(vf.isCurrent ? 'yes' : '-')
                       .append('</td><td>').append(vf.matchedMacros.size())
                       .append('</td><td>').append(r).append('</td><td>').append(s)
                       .append('</td><td>').append(f)
                       .append('</td><td>').append(vf.status).append('</td></tr>')
            }
        }
        results.append('</table>')
    } else if (RESULT_FORMAT == 'CSV' || RESULT_FORMAT == 'LIST') {
        StringBuilder t = new StringBuilder()
        if (RESULT_FORMAT == 'CSV') t.append('page_id,page_name,page_url,version,current,occurrences,replaced,skipped,failed,status\n')
        Set<String> seenUrls = new LinkedHashSet<String>()
        for (PageFinding pf : findings) {
            if (RESULT_FORMAT == 'LIST') { seenUrls.add(pf.url); continue }
            for (VersionFinding vf : pf.versions) {
                int r = 0, s = 0, f = 0
                for (MatchedMacro mm : vf.matchedMacros) {
                    if (mm.status == ReplacementStatus.Success) r++
                    else if (mm.status == ReplacementStatus.Skipped) s++
                    else if (mm.status == ReplacementStatus.Failed) f++
                }
                List<String> fields = [pf.pageId as String, pf.pageName, pf.url, vf.versionNumber as String,
                                       vf.isCurrent ? 'yes' : 'no', vf.matchedMacros.size() as String,
                                       r as String, s as String, f as String, vf.status as String]
                List<String> q = new ArrayList<String>()
                for (String v : fields) q.add('"' + (v == null ? '' : v.replace('"', '""')) + '"')
                t.append(q.join(',')).append('\n')
            }
        }
        for (String u : seenUrls) t.append(u).append('\n')
        results.append('<h3>').append(RESULT_FORMAT).append('</h3><pre>').append(htmlEsc(t.toString())).append('</pre>')
    }

    if (rollback.length() > 0) {
        results.append('<h3>Rollback copies (').append(rollbackEmitted).append(')</h3>')
               .append('<p>Console output is not a backup - take a database backup before bulk runs. ')
               .append('These are for surgical single-version restores.</p>')
               .append('<pre>').append(htmlEsc(rollback.toString())).append('</pre>')
    }

    log.warn("Macro engine v2: mode=${MODE}, pages=${findings.size()}, elapsed=${System.currentTimeMillis() - runStart} ms")
    return '<pre>' + htmlEsc(outp.toString()) + '</pre>' + results.toString()

} catch (Throwable fatal) {
    log.error('Macro engine v2 terminated', fatal)
    StringBuilder err = new StringBuilder()
    err.append(outp)
    err.append('\n================================================================\n')
    err.append('RUN TERMINATED\n')
    err.append(fatal.getClass().getName()).append(': ').append(fatal.getMessage()).append('\n')
    Throwable cause = fatal.getCause()
    while (cause != null) {
        err.append('  caused by ').append(cause.getClass().getName()).append(': ').append(cause.getMessage()).append('\n')
        cause = cause.getCause()
    }
    err.append('\nFull stack trace is in atlassian-confluence.log\n')
    return '<pre>' + htmlEsc(err.toString()) + '</pre>'
}
