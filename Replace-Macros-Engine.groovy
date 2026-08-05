/*
 * =============================================================================
 *  CONFLUENCE DC MACRO REPLACEMENT ENGINE  (ScriptRunner Script Console)
 * =============================================================================
 *
 *  One script, many migrations. Writes go exclusively through the Confluence
 *  Java API (PageManager), so: no outage, no direct DB writes, no reindex, and
 *  historical versions are edited in place with last-modifier and
 *  last-modification-date preserved.
 *
 *  MODES
 *    INSPECT  - read only. Reports what would change, per macro, with a time
 *               estimate. Run this first, every time.
 *    APPLY    - performs the replacement, reports real measured timings.
 *    HARVEST  - read only. Scans existing usages of a TARGET macro and prints a
 *               paste-ready perValueParams map. Use this to learn EasyDropDown
 *               set-id / option-id values instead of typing them by hand.
 *
 *  DISCOVERY
 *    Pages are discovered from the database (read-only SELECT) per macro.
 *    Set PAGE_IDS_OVERRIDE to pin the run to specific pages instead.
 *
 *  PARAMETER DEFAULTS
 *    Parameters left at their default are NOT written into page storage.
 *    Resolution order per source parameter:
 *      1. value present in the page markup
 *      2. migration.paramDefaults        (explicit, wins over discovery)
 *      3. default read from the macro definition (reflective auto-discovery)
 *      4. onMissing policy: SKIP that occurrence, or FAIL the page
 *
 *  STRUCTURE
 *    - config block            (edit this)
 *    - model classes
 *    - parameter resolution
 *    - transformers            (MAP = generic macro->macro, QUALIFICATION = computed table)
 *    - storage rewriting
 *    - persistence
 *    - discovery
 *    - reporting
 * =============================================================================
 */

import com.atlassian.confluence.core.ContentEntityObject
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.core.Modification
import com.atlassian.confluence.core.VersionHistorySummary
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.spring.container.ContainerManager
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern

// =============================================================================
//  CONFIG
// =============================================================================

@Field String MODE = 'INSPECT'                 // INSPECT | APPLY | HARVEST

// Which migration ids below to run, in order. Empty = all of them.
@Field List<String> RUN = ['qualification-table']

@Field String DB_RESOURCE = 'ConfluenceDB'

// Restrict to these space keys. Empty = every space.
@Field List<String> SPACE_KEYS = []

// content_status values to include in discovery. 'current' excludes drafts
// and trashed content.
@Field List<String> INCLUDE_STATUSES = ['current']

// Pin the run to specific current-page ids. Empty = discover from the database.
@Field List<Long> PAGE_IDS_OVERRIDE = []

// Rewrite historical versions as well as the current one.
@Field boolean UPDATE_HISTORICAL_VERSIONS = true

// true  -> current version saved via saveNewVersion(): rollback through page
//          history, version count grows by one
// false -> current version saved in place, no new version
@Field boolean CURRENT_CREATES_NEW_VERSION = true

// Historical rows are never indexed by Confluence, so events are suppressed.
@Field boolean HISTORICAL_SUPPRESS_EVENTS = true

// Estimated write cost per version, used ONLY for the INSPECT time estimate.
// An APPLY run prints the real figure - feed it back here to sharpen estimates.
@Field int WRITE_MS_PER_VERSION = 120

// Cap on versions processed per run. 0 = no cap. Useful for a staged rollout.
@Field int MAX_VERSIONS = 0

// Print the pre-change storage of every version that is modified. This is the
// ONLY rollback available for historical versions - page history cannot undo
// them - so keep it on for the first APPLY of any migration. Turn it off for
// large bulk runs, where the output would be unmanageable.
@Field boolean PRINT_ORIGINAL_BODIES = true
@Field int MAX_BODY_DUMPS = 25

// After each write, re-read the entity and confirm the source macro is gone.
// This catches a save that silently did not persist. It does NOT validate that
// the replacement content is semantically correct - see Validate-QualificationRender.groovy.
@Field boolean VERIFY_AFTER_WRITE = true

// Historical rows normally have spaceid NULL by design. Leave both of these off
// unless a stack trace shows the save path needs the space.
//   SKIP_SPACELESS_HISTORICAL - skip such rows entirely (skips ALL history if
//                               every historical row is space-less, which is normal)
//   INHERIT_SPACE_FOR_HISTORICAL - set the page's space on the entity before
//                               saving; this persists spaceid onto the history row
@Field boolean SKIP_SPACELESS_HISTORICAL = false
@Field boolean INHERIT_SPACE_FOR_HISTORICAL = false

// Output listing the pages that were (or would be) changed.
//   TABLE - HTML table: page id, space, title, versions changed, link
//   CSV   - comma separated, quoted, one row per page, ready for Excel
//   LIST  - plain page URLs, one per line
//   NONE  - omit the section
@Field String RESULT_FORMAT = 'TABLE'

// -----------------------------------------------------------------------------
//  MIGRATIONS
//
//  Per-entry keys:
//    id                   label used in RUN and in the report
//    source               ac:name of the macro being replaced            (required)
//    target               ac:name of the replacement macro               (MAP only)
//    handler              'MAP' (default) or 'QUALIFICATION'
//    targetSchemaVersion  ac:schema-version written on the target        (default '1')
//    unwrapParagraph      true  -> the enclosing <p> is consumed too.
//                         REQUIRED when the replacement emits a <table> or other
//                         block element, which may not sit inside a <p>.
//                         false -> only the macro element is swapped (inline).
//    paramMap             source param name -> target param name
//    valueMap             source value -> target value, applied after paramMap.
//                         Absent entries pass through unchanged.
//    paramDefaults        source param -> value, for params omitted from storage
//    staticParams         target param -> fixed value, written on every instance
//    perValueParams       source value -> extra target params for that value.
//                         This is where EasyDropDown option-id lives.
//                         Produce it with MODE = 'HARVEST'.
//    requiredParams       source params that must resolve or the occurrence is
//                         skipped. Empty = nothing is mandatory.
//    dropUnmapped         true = source params with no paramMap entry are dropped
//    reuseSourceMacroId   true = keep the source ac:macro-id, false = fresh UUID
//    harvestKeyParam      target param whose value groups a HARVEST run
// -----------------------------------------------------------------------------
@Field List<Map<String, Object>> MIGRATIONS = [

    // ---- Case 5: computed flatten, no target macro -------------------------
    [
        id              : 'qualification-table',
        source          : 'qualification-table',
        handler         : 'QUALIFICATION',
        unwrapParagraph : true,
        // Fill from the verification described in the notes. Leave empty to let
        // auto-discovery supply them; occurrences that still cannot resolve are
        // skipped and listed.
        // Confirmed empirically: a page with 5 impacts omitted rendered 17%, which
        // is only consistent with the omitted impacts defaulting to 0.
        // Backup only - auto-discovery now returns these from the macro definition.
        // Confirmed: every impact parameter defaults to 0, relevance defaults to 1.
        paramDefaults   : ['impactFinance': '0', 'impactSales': '0',
                           'impactProductmanagement': '0', 'impactMarketing': '0',
                           'impactOuS': '0', 'impactHR': '0', 'impactGF': '0',
                           'impactLR': '0', 'impactASUS': '0', 'relevance': '1'],
        requiredParams  : ['impactFinance', 'impactSales', 'impactProductmanagement',
                           'impactMarketing', 'impactOuS', 'impactHR', 'impactGF',
                           'impactLR', 'impactASUS', 'relevance'],
    ],

    // ---- Case 1: user status macro -> EasyDropDown status -------------------
    [
        id                 : 'blackboard-status',
        source             : 'blackboard-status',
        target             : 'easy-dropdown-menu-status',
        targetSchemaVersion: '2',
        unwrapParagraph    : false,
        paramMap           : ['Status': 'current-option-value'],
        staticParams       : ['set-id': 'PUT-SET-ID-HERE'],
        perValueParams     : [
            // 'Geschlossen': ['option-id': '...'],   <- from HARVEST
        ],
        requiredParams     : ['Status'],
        dropUnmapped       : true,
        harvestKeyParam    : 'current-option-value',
    ],

    // ---- Case 2: parameterless user macro -> ScriptRunner macro ------------
    [
        id             : 'test-last-modified',
        source         : 'test-last-modified',
        target         : 'sr-last-modified',
        unwrapParagraph: false,
        paramMap       : [:],
        requiredParams : [],
        dropUnmapped   : true,
    ],

    // ---- Case 3: link button -> Aura button --------------------------------
    // NOTE: the sample source macro exposes only "buttontext". The Aura target
    // needs both href and label. Confirm which source parameter carries the URL
    // before running this one; paramMap below is a placeholder.
    [
        id             : 'link-button',
        source         : 'test-link-button',
        target         : 'aura-button',
        unwrapParagraph: false,
        paramMap       : ['buttontext': 'label'],
        staticParams   : [
            'elevation'   : 'elevated',
            'outlined'    : 'regular',
            'borderRadius': '18',
            'color'       : '#000000',
            'size'        : 'medium',
            'background'  : '#b0e572',
            'iconPosition': 'left',
            'hrefType'    : 'link',
            'alignment'   : 'left',
        ],
        requiredParams : ['buttontext'],
        dropUnmapped   : true,
    ],

    // ---- Case 4: ScriptRunner -> ScriptRunner, pure rename -----------------
    [
        id             : 'sr-rename-example',
        source         : 'old-sr-macro',
        target         : 'new-sr-macro',
        unwrapParagraph: false,
        paramMap       : [:],       // empty + dropUnmapped=false = carry all params through
        dropUnmapped   : false,
        requiredParams : [],
    ],
]

// What to do when a required parameter cannot be resolved: 'SKIP' | 'FAIL'
@Field String ON_MISSING = 'SKIP'

@Field boolean AUTO_DISCOVER_DEFAULTS = true

// =============================================================================
//  QUALIFICATION handler constants
// =============================================================================
@Field List<List<String>> QM_COLUMNS = [
        ['KB'  , 'impactFinance'],
        ['V'   , 'impactSales'],
        ['PM'  , 'impactProductmanagement'],
        ['M'   , 'impactMarketing'],
        ['O&S' , 'impactOuS'],
        ['HR'  , 'impactHR'],
        ['GF'  , 'impactGF'],
        ['LEAS', 'impactLR'],
        ['ASUS', 'impactASUS'],
]

// =============================================================================
//  MODEL
// =============================================================================

class Migration {
    String id, source, target, handler, targetSchemaVersion, harvestKeyParam
    boolean unwrapParagraph, dropUnmapped, reuseSourceMacroId
    Map<String, String> paramMap, valueMap, paramDefaults, staticParams
    Map<String, Map<String, String>> perValueParams
    List<String> requiredParams
    Map<String, String> discoveredDefaults = new LinkedHashMap<String, String>()
    // counters
    int pagesFound, currentRows, histRows
    int versionsSeen, versionsChanged, occReplaced, occSkipped, occFailed, verifyFailed, noSpace
    long evalNanos, writeNanos
    List<String> failures = new ArrayList<String>()
    List<String> notes = new ArrayList<String>()
}

class VersionOutcome {
    long pageId, contentId
    int version
    boolean isCurrent
    int occurrences, replaced, skipped, failed
    String migId
    String status = ''
    String originalBody
    String newBody
    String error
}

/** One row of the results listing: everything changed on a single page. */
class PageResult {
    long pageId
    String spaceKey = ''
    String title = ''
    boolean currentChanged
    List<String> histVersions = new ArrayList<String>()
    int occurrences
}

// =============================================================================
//  CONFIG PARSING
// =============================================================================

Migration toMigration(Map<String, Object> cfg) {
    Migration m = new Migration()
    m.id                  = (String) cfg.get('id')
    m.source              = (String) cfg.get('source')
    m.target              = (String) cfg.get('target')
    m.handler             = cfg.get('handler') == null ? 'MAP' : (String) cfg.get('handler')
    m.targetSchemaVersion = cfg.get('targetSchemaVersion') == null ? '1' : (String) cfg.get('targetSchemaVersion')
    m.harvestKeyParam     = (String) cfg.get('harvestKeyParam')
    m.unwrapParagraph     = cfg.get('unwrapParagraph') == null ? false : (Boolean) cfg.get('unwrapParagraph')
    m.dropUnmapped        = cfg.get('dropUnmapped') == null ? true : (Boolean) cfg.get('dropUnmapped')
    m.reuseSourceMacroId  = cfg.get('reuseSourceMacroId') == null ? false : (Boolean) cfg.get('reuseSourceMacroId')
    m.paramMap        = (Map<String, String>) (cfg.get('paramMap')      ?: [:])
    m.valueMap        = (Map<String, String>) (cfg.get('valueMap')      ?: [:])
    m.paramDefaults   = (Map<String, String>) (cfg.get('paramDefaults') ?: [:])
    m.staticParams    = (Map<String, String>) (cfg.get('staticParams')  ?: [:])
    m.perValueParams  = (Map<String, Map<String, String>>) (cfg.get('perValueParams') ?: [:])
    m.requiredParams  = (List<String>) (cfg.get('requiredParams') ?: [])
    return m
}

// =============================================================================
//  UTILITIES
// =============================================================================

String xmlEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;')
}

String textEsc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

String humanTime(long millis) {
    if (millis < 1000) return millis + ' ms'
    long s = (long) (millis / 1000)
    long h = (long) (s / 3600), mnt = (long) ((s % 3600) / 60), sec = s % 60
    StringBuilder b = new StringBuilder()
    if (h > 0) b.append(h).append(' h ')
    if (h > 0 || mnt > 0) b.append(mnt).append(' m ')
    b.append(sec).append(' s')
    return b.toString()
}

/** Matches a macro element by ac:name, self-closing or not. */
Pattern macroPattern(String name) {
    String q = Pattern.quote(name)
    return Pattern.compile('(?s)<ac:structured-macro\\b(?=[^>]*ac:name="' + q + '")' +
            '(?:[^>]*/>|[^>]*>.*?</ac:structured-macro>)')
}

/** Same, but consuming a paragraph that contains nothing but the macro. */
Pattern macroInParagraphPattern(String name) {
    String q = Pattern.quote(name)
    return Pattern.compile('(?s)<p>\\s*(?:<ac:structured-macro\\b(?=[^>]*ac:name="' + q + '")' +
            '(?:[^>]*/>|[^>]*>.*?</ac:structured-macro>))\\s*</p>')
}

@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+ac:name="([^"]+)"\\s*>(.*?)</ac:parameter>')
@Field Pattern P_MACRO_ID = Pattern.compile('ac:macro-id="([^"]*)"')
@Field Pattern P_ANY_MACRO_OPEN = Pattern.compile('<ac:structured-macro\\b')

Map<String, String> parseParams(String macroXml) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    Matcher m = P_PARAM.matcher(macroXml)
    while (m.find()) found.put(m.group(1), m.group(2).trim())
    return found
}

String extractMacroId(String macroXml) {
    Matcher m = P_MACRO_ID.matcher(macroXml)
    return m.find() ? m.group(1) : ''
}

boolean containsNestedMacro(String macroXml) {
    Matcher m = P_ANY_MACRO_OPEN.matcher(macroXml)
    int n = 0
    while (m.find()) n++
    return n > 1
}

// =============================================================================
//  PARAMETER DEFAULT DISCOVERY  (reflective: survives API differences)
// =============================================================================

Object reflectCall(Object target, String method, Class[] sig, Object[] args) {
    if (target == null) return null
    try {
        Method m = target.getClass().getMethod(method, sig)
        m.setAccessible(true)
        return m.invoke(target, args)
    } catch (Throwable t) {
        return null
    }
}

/**
 * Route 1 - macro browser metadata. Correct method name is
 * getMacroMetadataByName; getMacroMetadata does not exist on this instance.
 */
Map<String, String> discoverFromMetadata(String macroName) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    Object mgr
    try { mgr = ContainerManager.getComponent('macroMetadataManager') } catch (Throwable t) { return found }
    Object md = reflectCall(mgr, 'getMacroMetadataByName', [String] as Class[], [macroName] as Object[])
    if (md == null) md = reflectCall(mgr, 'getMacroMetadata', [String] as Class[], [macroName] as Object[])
    // NOTE: the accessor is getFromDetails - that spelling is Atlassian's, not a
    // typo here. getFormDetails does not exist on MacroMetadata.
    Object form = reflectCall(md, 'getFromDetails', new Class[0], new Object[0])
    if (form == null) form = reflectCall(md, 'getFormDetails', new Class[0], new Object[0])
    Object plist = reflectCall(form, 'getParameters', new Class[0], new Object[0])
    if (plist == null) plist = reflectCall(md, 'getParameters', new Class[0], new Object[0])
    if (!(plist instanceof Collection)) return found
    for (Object p : (Collection) plist) {
        Object n = reflectCall(p, 'getName', new Class[0], new Object[0])
        Object d = reflectCall(p, 'getDefaultValue', new Class[0], new Object[0])
        if (n != null && d != null && !d.toString().trim().isEmpty()) {
            found.put(n.toString(), d.toString().trim())
        }
    }
    return found
}

/**
 * Route 2 - user macro Velocity template. This is where the ~50 user macros
 * keep their defaults: "## @param Name:title=X|type=enum|enumValues=A,B|default=C".
 * Correct accessor is userMacroLibrary.getMacro(name).
 */
Map<String, String> discoverFromUserMacro(String macroName) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    Object lib
    try { lib = ContainerManager.getComponent('userMacroLibrary') } catch (Throwable t) { return found }
    Object cfg = reflectCall(lib, 'getMacro', [String] as Class[], [macroName] as Object[])
    if (cfg == null) return found
    Object tpl = reflectCall(cfg, 'getTemplate', new Class[0], new Object[0])
    if (tpl == null) tpl = reflectCall(cfg, 'getBody', new Class[0], new Object[0])
    if (tpl == null) return found

    Pattern pp = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')
    for (String line : tpl.toString().readLines()) {
        Matcher m = pp.matcher(line)
        if (!m.matches()) continue
        String name = m.group(1).trim()
        String rest = m.group(2) == null ? '' : m.group(2).trim()
        for (String seg : rest.split('\\|')) {
            int eq = seg.indexOf('=')
            if (eq <= 0) continue
            String k = seg.substring(0, eq).trim()
            String v = seg.substring(eq + 1).trim()
            if (k == 'default' && !v.isEmpty()) found.put(name, v)
        }
    }
    return found
}

/** Metadata first, user macro template second; neither overwrites the other. */
Map<String, String> discoverDefaults(String macroName) {
    Map<String, String> found = new LinkedHashMap<String, String>()
    found.putAll(discoverFromUserMacro(macroName))
    Map<String, String> meta = discoverFromMetadata(macroName)
    for (Map.Entry<String, String> e : meta.entrySet()) {
        if (!found.containsKey(e.getKey())) found.put(e.getKey(), e.getValue())
    }
    return found
}

/** page value -> explicit default -> discovered default -> null */
String resolveParam(Migration mig, Map<String, String> onPage, String key) {
    String v = onPage.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    v = mig.paramDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    v = mig.discoveredDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    return null
}

// =============================================================================
//  TRANSFORMER: generic macro -> macro
// =============================================================================

String buildMacroElement(String name, String schemaVersion, String macroId,
                         Map<String, String> params) {
    StringBuilder b = new StringBuilder()
    b.append('<ac:structured-macro ac:name="').append(xmlEsc(name))
     .append('" ac:schema-version="').append(xmlEsc(schemaVersion))
     .append('" ac:macro-id="').append(xmlEsc(macroId)).append('"')
    if (params.isEmpty()) {
        b.append(' />')
        return b.toString()
    }
    b.append('>')
    for (Map.Entry<String, String> e : params.entrySet()) {
        b.append('<ac:parameter ac:name="').append(xmlEsc(e.getKey())).append('">')
         .append(textEsc(e.getValue()))
         .append('</ac:parameter>')
    }
    b.append('</ac:structured-macro>')
    return b.toString()
}

/** Returns replacement XML, or null when the occurrence must be skipped. */
String transformMap(Migration mig, String sourceXml, List<String> notes) {
    Map<String, String> onPage = parseParams(sourceXml)

    List<String> unresolved = new ArrayList<String>()
    List<String> defaulted = new ArrayList<String>()
    Map<String, String> resolved = new LinkedHashMap<String, String>()

    Set<String> interesting = new LinkedHashSet<String>()
    interesting.addAll(onPage.keySet())
    interesting.addAll(mig.requiredParams)
    if (!mig.paramMap.isEmpty()) interesting.addAll(mig.paramMap.keySet())

    for (String key : interesting) {
        String v = resolveParam(mig, onPage, key)
        if (v == null) {
            if (mig.requiredParams.contains(key)) unresolved.add(key)
            continue
        }
        if (!onPage.containsKey(key)) defaulted.add(key + '=' + v)
        resolved.put(key, v)
    }

    if (!unresolved.isEmpty()) {
        String msg = 'unresolved parameter(s) ' + unresolved + ' (on page: ' + onPage.keySet() + ')'
        if (ON_MISSING == 'FAIL') throw new IllegalStateException(msg)
        notes.add('skipped occurrence - ' + msg)
        return null
    }
    if (!defaulted.isEmpty()) notes.add('defaults applied: ' + defaulted.join(', '))

    // build target parameters
    Map<String, String> outParams = new LinkedHashMap<String, String>()
    outParams.putAll(mig.staticParams)

    for (Map.Entry<String, String> e : resolved.entrySet()) {
        String srcKey = e.getKey()
        String srcVal = e.getValue()
        String tgtKey = mig.paramMap.get(srcKey)
        if (tgtKey == null) {
            if (mig.dropUnmapped) continue
            tgtKey = srcKey
        }
        String tgtVal = mig.valueMap.get(srcVal)
        if (tgtVal == null) tgtVal = srcVal
        outParams.put(tgtKey, tgtVal)

        Map<String, String> extra = mig.perValueParams.get(srcVal)
        if (extra != null) {
            outParams.putAll(extra)
        } else if (!mig.perValueParams.isEmpty() && mig.paramMap.containsKey(srcKey)) {
            // An unmapped source value is an ERROR, not a policy skip: the target
            // macro would be written without the parameters that value needs.
            // Thrown either way - rewritePass counts it as a failed occurrence
            // under ON_MISSING=SKIP, and aborts the version under FAIL.
            throw new IllegalStateException('no perValueParams entry for source value "' +
                    srcVal + '" (parameter ' + srcKey + '). Known values: ' +
                    mig.perValueParams.keySet())
        }
    }

    String macroId = mig.reuseSourceMacroId ? extractMacroId(sourceXml) : UUID.randomUUID().toString()
    return buildMacroElement(mig.target, mig.targetSchemaVersion, macroId, outParams)
}

// =============================================================================
//  TRANSFORMER: qualification-table -> static table
// =============================================================================

String transformQualification(Migration mig, String sourceXml, List<String> notes) {
    Map<String, String> onPage = parseParams(sourceXml)
    Map<String, String> resolved = new LinkedHashMap<String, String>()
    List<String> unresolved = new ArrayList<String>()
    List<String> defaulted = new ArrayList<String>()

    for (String key : mig.requiredParams) {
        String v = resolveParam(mig, onPage, key)
        if (v == null || !(v ==~ /\d+/)) { unresolved.add(key); continue }
        if (!onPage.containsKey(key)) defaulted.add(key + '=' + v)
        resolved.put(key, v)
    }

    if (!unresolved.isEmpty()) {
        String msg = 'unresolved/non-numeric parameter(s) ' + unresolved + ' (on page: ' + onPage.keySet() + ')'
        if (ON_MISSING == 'FAIL') throw new IllegalStateException(msg)
        notes.add('skipped occurrence - ' + msg)
        return null
    }
    if (!defaulted.isEmpty()) notes.add('defaults applied: ' + defaulted.join(', '))

    int relevance = Integer.parseInt(resolved.get('relevance'))
    int sum = 0
    for (List<String> col : QM_COLUMNS) sum += Integer.parseInt(resolved.get(col.get(1)))
    int pct = ((100 * sum * relevance).intdiv(135)) as int

    StringBuilder headers = new StringBuilder()
    StringBuilder values = new StringBuilder()
    for (List<String> col : QM_COLUMNS) {
        headers.append('<th>').append(textEsc(col.get(0))).append('</th>')
        values.append('<td>').append(textEsc(resolved.get(col.get(1)))).append('</td>')
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
    return h.toString()
}

String transform(Migration mig, String sourceXml, List<String> notes) {
    if (mig.handler == 'QUALIFICATION') return transformQualification(mig, sourceXml, notes)
    return transformMap(mig, sourceXml, notes)
}

// =============================================================================
//  STORAGE REWRITING
// =============================================================================

/**
 * result: [newBody, replacedCount, skippedCount, failedCount]
 *
 * skipped = deliberately left alone (unresolved parameters under ON_MISSING=SKIP)
 * failed  = could not be processed (nested macro, or the transform threw)
 * Both leave the original macro untouched in the body; the distinction is
 * whether it was a policy decision or a problem.
 */
List<Object> rewritePass(Migration mig, String body, Pattern pattern, List<String> notes) {
    StringBuffer buf = new StringBuffer()
    Matcher m = pattern.matcher(body)
    int replaced = 0, skipped = 0, failed = 0
    while (m.find()) {
        String whole = m.group(0)
        String macroXml = whole
        if (containsNestedMacro(macroXml)) {
            failed++
            notes.add('failed occurrence - contains a nested macro, cannot be matched safely')
            m.appendReplacement(buf, Matcher.quoteReplacement(whole))
            continue
        }
        String out
        try {
            out = transform(mig, macroXml, notes)
        } catch (IllegalStateException ise) {
            if (ON_MISSING == 'FAIL') throw ise      // FAIL aborts the whole version
            failed++
            notes.add('failed occurrence - ' + ise.getMessage())
            m.appendReplacement(buf, Matcher.quoteReplacement(whole))
            continue
        } catch (Exception ex) {
            failed++
            notes.add('failed occurrence - ' + ex.getClass().getSimpleName() + ': ' + ex.getMessage())
            m.appendReplacement(buf, Matcher.quoteReplacement(whole))
            continue
        }
        if (out == null) {
            skipped++
            m.appendReplacement(buf, Matcher.quoteReplacement(whole))
        } else {
            replaced++
            m.appendReplacement(buf, Matcher.quoteReplacement(out))
        }
    }
    m.appendTail(buf)
    return [buf.toString(), replaced as Integer, skipped as Integer, failed as Integer]
}

VersionOutcome evaluateVersion(Migration mig, ContentEntityObject ceo, long pageId, boolean isCurrent) {
    VersionOutcome vo = new VersionOutcome()
    vo.pageId = pageId
    vo.contentId = ceo.getId()
    vo.version = ceo.getVersion()
    vo.isCurrent = isCurrent
    vo.migId = mig.id

    String body = ceo.getBodyAsString()
    vo.originalBody = body
    List<String> notes = new ArrayList<String>()
    int replaced = 0, skipped = 0, failed = 0

    if (mig.unwrapParagraph) {
        List<Object> r = rewritePass(mig, body, macroInParagraphPattern(mig.source), notes)
        body = (String) r.get(0)
        replaced += (Integer) r.get(1); skipped += (Integer) r.get(2); failed += (Integer) r.get(3)
    }
    List<Object> r2 = rewritePass(mig, body, macroPattern(mig.source), notes)
    body = (String) r2.get(0)
    replaced += (Integer) r2.get(1); skipped += (Integer) r2.get(2); failed += (Integer) r2.get(3)

    vo.newBody = body
    vo.replaced = replaced
    vo.skipped = skipped
    vo.failed = failed
    vo.occurrences = replaced + skipped + failed
    for (String n : notes) {
        if (mig.notes.size() < 200) mig.notes.add('page ' + pageId + ' v' + vo.version + ': ' + n)
    }
    return vo
}

// =============================================================================
//  PERSISTENCE
// =============================================================================

/**
 * success       - every occurrence on this version was replaced
 * partial-failed- some replaced, some failed
 * failed        - nothing replaced, or the write did not persist
 * "skipped" occurrences are a policy decision and do not by themselves make a
 * version failed; they are reported in their own column.
 */
String statusFor(int replaced, int failed, boolean writeOk) {
    if (!writeOk) return 'failed'
    if (failed > 0 && replaced > 0) return 'partial-failed'
    if (failed > 0) return 'failed'
    if (replaced > 0) return 'success'
    return 'success'
}

/** true when the source macro is no longer present in the persisted body. */
boolean verifyGone(PageManager pm, long contentId, String macroName) {
    ContentEntityObject fresh = pm.getPage(contentId)
    if (fresh == null) return false
    return !fresh.getBodyAsString().contains('ac:name="' + macroName + '"')
}

void writeCurrent(PageManager pm, Page page, String newBody) {
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
}

void writeHistorical(PageManager pm, ContentEntityObject hist, String newBody) {
    Date keep = hist.getLastModificationDate()
    hist.setBodyAsString(newBody)
    hist.setLastModificationDate(keep)
    pm.saveContentEntity(hist, new DefaultSaveContext(true, false, HISTORICAL_SUPPRESS_EVENTS))
}

// =============================================================================
//  DISCOVERY  (read-only SELECT)
// =============================================================================

class Discovery {
    List<Long> pageIds = new ArrayList<Long>()
    int currentRows, histRows
}

Discovery discoverPages(String macroName) {
    Discovery d = new Discovery()
    Map<String, Object> params = new LinkedHashMap<String, Object>()
    params.put('pattern', '%ac:name="' + macroName + '"%')

    StringBuilder q = new StringBuilder()
    q.append('SELECT c.contentid AS rowid, c.prevver AS prevver ')
     .append('FROM content c JOIN bodycontent bc ON bc.contentid = c.contentid ')
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

    Set<Long> ids = new LinkedHashSet<Long>()
    int cur = 0, hist = 0
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.eachRow(q.toString(), params) { row ->
            Object prev = row['prevver']
            long rowId = ((Number) row['rowid']).longValue()
            if (prev == null) { cur++; ids.add(rowId) }
            else { hist++; ids.add(((Number) prev).longValue()) }
        }
    }
    d.pageIds.addAll(ids)
    d.currentRows = cur
    d.histRows = hist
    return d
}

// =============================================================================
//  HARVEST
// =============================================================================

String harvest(Migration mig) {
    StringBuilder b = new StringBuilder()
    b.append('HARVEST for target macro: ').append(mig.target).append('\n')
    if (mig.target == null) { b.append('  migration has no target macro\n'); return b.toString() }

    Map<String, Object> params = new LinkedHashMap<String, Object>()
    params.put('pattern', '%ac:name="' + mig.target + '"%')
    String sql = 'SELECT bc.body AS body FROM content c ' +
                 'JOIN bodycontent bc ON bc.contentid = c.contentid ' +
                 "WHERE c.contenttype IN ('PAGE','BLOGPOST') AND bc.body LIKE :pattern"

    Pattern pat = macroPattern(mig.target)
    Map<String, Set<String>> byValue = new LinkedHashMap<String, Set<String>>()
    int instances = 0

    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql2 ->
        sql2.eachRow(sql, params) { row ->
            String body = row['body'] as String
            Matcher m = pat.matcher(body)
            while (m.find()) {
                instances++
                Map<String, String> p = parseParams(m.group(0))
                String key = mig.harvestKeyParam == null ? '(no harvestKeyParam)' : p.get(mig.harvestKeyParam)
                if (key == null) key = '(absent)'
                Map<String, String> rest = new LinkedHashMap<String, String>(p)
                rest.remove(mig.harvestKeyParam)
                Set<String> s = byValue.get(key)
                if (s == null) { s = new LinkedHashSet<String>(); byValue.put(key, s) }
                s.add(rest.toString())
            }
        }
    }

    b.append('  instances found: ').append(instances)
      .append(', distinct values: ').append(byValue.size()).append('\n\n')
    boolean ambiguous = false
    for (Map.Entry<String, Set<String>> e : byValue.entrySet()) {
        b.append('  "').append(e.getKey()).append('"\n')
        for (String variant : e.getValue()) b.append('      ').append(variant).append('\n')
        if (e.getValue().size() > 1) { ambiguous = true; b.append('      ^^ MORE THAN ONE PARAMETER SET FOR THIS VALUE\n') }
    }
    if (ambiguous) {
        b.append('\n  Values above with more than one parameter set are not stable across\n')
        b.append('  pages. Decide which set is canonical before using perValueParams.\n')
    }
    return b.toString()
}

// =============================================================================
//  MAIN
// =============================================================================

PageManager pageManager = ComponentLocator.getComponent(PageManager)
long runStart = System.currentTimeMillis()

if (MODE != 'INSPECT' && MODE != 'APPLY' && MODE != 'HARVEST') {
    return '<pre>ABORT: MODE must be INSPECT, APPLY or HARVEST.</pre>'
}
if (ON_MISSING != 'SKIP' && ON_MISSING != 'FAIL') {
    return '<pre>ABORT: ON_MISSING must be SKIP or FAIL.</pre>'
}

List<VersionOutcome> changed = new ArrayList<VersionOutcome>()
Map<Long, PageResult> pageMeta = new LinkedHashMap<Long, PageResult>()
List<Migration> selected = new ArrayList<Migration>()
for (Map<String, Object> cfg : MIGRATIONS) {
    Migration m = toMigration(cfg)
    if (RUN.isEmpty() || RUN.contains(m.id)) selected.add(m)
}
if (selected.isEmpty()) return '<pre>ABORT: RUN matched no migration ids.</pre>'

StringBuilder outp = new StringBuilder()
outp.append('MODE: ').append(MODE).append(MODE == 'APPLY' ? '   *** WRITES ENABLED ***' : '   (read only)').append('\n')
outp.append('Spaces: ').append(SPACE_KEYS.isEmpty() ? 'all' : SPACE_KEYS.join(', '))
    .append('   Statuses: ').append(INCLUDE_STATUSES.isEmpty() ? 'all' : INCLUDE_STATUSES.join(', '))
    .append('   History: ').append(UPDATE_HISTORICAL_VERSIONS ? 'included' : 'skipped').append('\n')
outp.append('Migrations: ').append(selected.collect { Migration mm -> mm.id }.join(', ')).append('\n')
outp.append('================================================================\n\n')

if (MODE == 'HARVEST') {
    for (Migration mig : selected) outp.append(harvest(mig)).append('\n')
    log.warn("Macro engine HARVEST completed for ${selected.size()} migration(s)")
    return '<pre>' + textEsc(outp.toString()) + '</pre>'
}

boolean apply = (MODE == 'APPLY')

for (Migration mig : selected) {
    if (AUTO_DISCOVER_DEFAULTS) {
        mig.discoveredDefaults.putAll(discoverDefaults(mig.source))
    }

    List<Long> pageIds
    if (!PAGE_IDS_OVERRIDE.isEmpty()) {
        pageIds = PAGE_IDS_OVERRIDE
        mig.currentRows = -1; mig.histRows = -1
    } else {
        Discovery d = discoverPages(mig.source)
        pageIds = d.pageIds
        mig.currentRows = d.currentRows
        mig.histRows = d.histRows
    }
    mig.pagesFound = pageIds.size()

    outp.append('Macro "').append(mig.source).append('"')
    if (mig.target != null) outp.append(' -> "').append(mig.target).append('"')
    outp.append('\n  found on ').append(mig.pagesFound).append(' pages')
    if (mig.currentRows >= 0) {
        outp.append(' (').append(mig.currentRows).append(' current bodies, ')
            .append(mig.histRows).append(' historical bodies)')
    } else {
        outp.append(' (from PAGE_IDS_OVERRIDE)')
    }
    outp.append('\n  discovered defaults: ')
        .append(mig.discoveredDefaults.isEmpty()
                ? 'NONE - macro metadata exposed no defaults for "' + mig.source +
                  '". Fill paramDefaults in the migration config.'
                : mig.discoveredDefaults.toString()).append('\n')

    for (Long pid : pageIds) {
        if (MAX_VERSIONS > 0 && mig.versionsSeen >= MAX_VERSIONS) break

        Page page = pageManager.getPage(pid.longValue())
        if (page == null) { mig.failures.add(pid + ' - page not found'); continue }
        if (page.getOriginalVersionId() != null) {
            mig.failures.add(pid + ' - historical id supplied, expected a current page id'); continue
        }
        // Content with no space cannot be processed: Confluence's own version-history
        // and save paths dereference the space (permission checks, event publishing)
        // and throw NPE. Space-less content is reported, never attempted.
        if (page.getSpace() == null) {
            mig.noSpace++
            mig.failures.add(pid + ' v' + page.getVersion() +
                    ' - SKIPPED: page has no space (orphaned content). Investigate before remediating.')
            continue
        }

        if (!SPACE_KEYS.isEmpty() && !SPACE_KEYS.contains(page.getSpace().getKey())) {
            continue
        }

        if (!pageMeta.containsKey(pid)) {
            PageResult pr = new PageResult()
            pr.pageId = pid.longValue()
            pr.spaceKey = page.getSpace().getKey()
            pr.title = page.getTitle() == null ? '' : page.getTitle()
            pageMeta.put(pid, pr)
        }

        // Each version is guarded on its own, so ON_MISSING='FAIL' on one version
        // no longer prevents the remaining versions of the page being inspected.
        try {
            long t0 = System.nanoTime()
            VersionOutcome cur = evaluateVersion(mig, page, pid.longValue(), true)
            mig.evalNanos += System.nanoTime() - t0
            mig.versionsSeen++
            mig.occReplaced += cur.replaced
            mig.occSkipped += cur.skipped
            mig.occFailed += cur.failed

            boolean writeOk = true
            if (cur.replaced > 0) {
                mig.versionsChanged++
                if (apply) {
                    long w0 = System.nanoTime()
                    writeCurrent(pageManager, page, cur.newBody)
                    mig.writeNanos += System.nanoTime() - w0
                    if (VERIFY_AFTER_WRITE && !verifyGone(pageManager, cur.contentId, mig.source)) {
                        mig.verifyFailed++
                        writeOk = false
                        mig.failures.add(pid + ' v(current) - WRITE NOT PERSISTED: source macro still present after save')
                    }
                }
            }
            cur.status = statusFor(cur.replaced, cur.failed, writeOk)
            if (cur.occurrences > 0) changed.add(cur)
        } catch (Exception e) {
            log.error("Macro engine ${mig.id}: page ${pid} current version failed", e)
            mig.versionsSeen++
            mig.failures.add(pid + ' v(current) - ' + e.getClass().getSimpleName() + ': ' + e.getMessage())
            VersionOutcome stub = new VersionOutcome()
            stub.pageId = pid.longValue(); stub.contentId = pid.longValue()
            stub.version = page.getVersion(); stub.isCurrent = true; stub.migId = mig.id
            stub.status = 'failed'
            stub.occurrences = 0
            changed.add(stub)
        }

        try {
            if (UPDATE_HISTORICAL_VERSIONS) {
                Page refreshed = pageManager.getPage(pid.longValue())
                List<VersionHistorySummary> history = pageManager.getVersionHistorySummaries(refreshed)
                for (VersionHistorySummary vhs : history) {
                    if (MAX_VERSIONS > 0 && mig.versionsSeen >= MAX_VERSIONS) break
                    long hid = vhs.getId()
                    if (hid == pid.longValue()) continue
                    Page hist = pageManager.getPage(hid)
                    if (hist == null) continue

                    // NOTE: historical rows normally carry spaceid NULL - that is the
                    // Confluence data model, not corruption. Skipping every space-less
                    // historical row would skip ALL history, so this is opt-in only.
                    if (SKIP_SPACELESS_HISTORICAL && hist.getSpace() == null) {
                        mig.noSpace++
                        mig.failures.add(pid + ' v' + hist.getVersion() +
                                ' - SKIPPED: no space (contentid ' + hid + ')')
                        continue
                    }
                    // Workaround candidate: lend the historical row the page's space in
                    // memory before saving, for rows where Confluence's save path
                    // dereferences it. This DOES persist spaceid onto the history row,
                    // which Confluence normally leaves NULL - verify before enabling.
                    if (INHERIT_SPACE_FOR_HISTORICAL && hist.getSpace() == null) {
                        hist.setSpace(page.getSpace())
                    }

                    try {
                        long t1 = System.nanoTime()
                        VersionOutcome vo = evaluateVersion(mig, hist, pid.longValue(), false)
                        mig.evalNanos += System.nanoTime() - t1
                        mig.versionsSeen++
                        mig.occReplaced += vo.replaced
                        mig.occSkipped += vo.skipped
                        mig.occFailed += vo.failed

                        boolean hWriteOk = true
                        if (vo.replaced > 0) {
                            mig.versionsChanged++
                            if (apply) {
                                long w1 = System.nanoTime()
                                writeHistorical(pageManager, hist, vo.newBody)
                                mig.writeNanos += System.nanoTime() - w1
                                if (VERIFY_AFTER_WRITE && !verifyGone(pageManager, vo.contentId, mig.source)) {
                                    mig.verifyFailed++
                                    hWriteOk = false
                                    mig.failures.add(pid + ' v' + vo.version +
                                            ' - WRITE NOT PERSISTED: source macro still present after save')
                                }
                            }
                        }
                        vo.status = statusFor(vo.replaced, vo.failed, hWriteOk)
                        if (vo.occurrences > 0) changed.add(vo)
                    } catch (Exception ve) {
                        log.error("Macro engine ${mig.id}: page ${pid} v${hist.getVersion()} failed", ve)
                        mig.versionsSeen++
                        mig.failures.add(pid + ' v' + hist.getVersion() + ' - ' +
                                ve.getClass().getSimpleName() + ': ' + ve.getMessage())
                        VersionOutcome stub = new VersionOutcome()
                        stub.pageId = pid.longValue(); stub.contentId = hid
                        stub.version = hist.getVersion(); stub.isCurrent = false; stub.migId = mig.id
                        stub.status = 'failed'
                        stub.occurrences = 0
                        changed.add(stub)
                    }
                }
            }
        } catch (Exception e) {
            log.error("Macro engine ${mig.id}: page ${pid} failed", e)
            mig.failures.add(pid + ' - ' + e.getClass().getSimpleName() + ': ' + e.getMessage())
        }
    }

    long evalMs = (long) (mig.evalNanos / 1000000L)
    long writeMs = (long) (mig.writeNanos / 1000000L)
    outp.append('  versions inspected: ').append(mig.versionsSeen)
        .append(', versions to change: ').append(mig.versionsChanged)
        .append(', occurrences replaced: ').append(mig.occReplaced)
        .append(', occurrences skipped: ').append(mig.occSkipped).append('\n')

    if (apply) {
        outp.append('  measured: parse ').append(humanTime(evalMs))
            .append(', writes ').append(humanTime(writeMs))
        if (mig.versionsChanged > 0) {
            outp.append('  (').append((long) (writeMs / mig.versionsChanged)).append(' ms per written version')
                .append(' - feed this into WRITE_MS_PER_VERSION)')
        }
        outp.append('\n')
    } else {
        long est = evalMs + (long) mig.versionsChanged * (long) WRITE_MS_PER_VERSION
        outp.append('  estimated APPLY time: ').append(humanTime(est))
            .append('   (parse ').append(humanTime(evalMs)).append(' measured + ')
            .append(mig.versionsChanged).append(' writes x ').append(WRITE_MS_PER_VERSION).append(' ms assumed)\n')
    }

    outp.append('  RESULT "').append(mig.id).append('" - replaced: ').append(mig.occReplaced)
        .append(', skipped: ').append(mig.occSkipped)
        .append(', failed occurrences: ').append(mig.occFailed)
        .append(', failed versions: ').append(mig.failures.size())
        .append(', write-verify failures: ').append(mig.verifyFailed)
        .append(', skipped for no space: ').append(mig.noSpace).append('\n')
    if (mig.noSpace > 0) {
        outp.append('  NOTE: ').append(mig.noSpace).append(' page(s) had no space and were NOT touched.\n')
        outp.append('        Their macros are still in place. Run Diagnose-NoSpacePages.groovy\n')
        outp.append('        to see what they are before deciding whether to remediate them.\n')
    }

    if (!mig.failures.isEmpty()) {
        outp.append('\n  FAILED PAGES\n')
        for (String f : mig.failures) outp.append('    ').append(f).append('\n')
    }
    if (!mig.notes.isEmpty()) {
        outp.append('\n  NOTES (first ').append(mig.notes.size()).append(')\n')
        for (String n : mig.notes) outp.append('    ').append(n).append('\n')
    }
    outp.append('\n----------------------------------------------------------------\n\n')
}

if (PRINT_ORIGINAL_BODIES && !changed.isEmpty()) {
    outp.append('ROLLBACK COPIES - storage BEFORE the change\n')
    outp.append('  Historical versions cannot be restored from page history. If you need\n')
    outp.append('  to undo one, this is the only copy. Save this output.\n')
    outp.append('================================================================\n')
    int shown = 0
    for (VersionOutcome vo : changed) {
        if (shown >= MAX_BODY_DUMPS) {
            outp.append('  ... ').append(changed.size() - shown)
                .append(' more not shown (raise MAX_BODY_DUMPS)\n')
            break
        }
        shown++
        outp.append('\n---- page ').append(vo.pageId).append(' / contentid ').append(vo.contentId)
            .append(' / v').append(vo.version).append(vo.isCurrent ? ' / CURRENT' : '').append('\n')
        outp.append('BEFORE:\n').append(vo.originalBody).append('\n')
        outp.append('AFTER:\n').append(vo.newBody).append('\n')
    }
    outp.append('\n----------------------------------------------------------------\n\n')
}

long totalMs = System.currentTimeMillis() - runStart
outp.append('TOTAL ELAPSED: ').append(humanTime(totalMs)).append('\n')

// =============================================================================
//  RESULTS LISTING - pages that were, or would be, changed
// =============================================================================
StringBuilder results = new StringBuilder()

if (RESULT_FORMAT != 'NONE' && !changed.isEmpty()) {
    String baseUrl = ''
    try {
        baseUrl = ComponentLocator.getComponent(SettingsManager).getGlobalSettings().getBaseUrl()
    } catch (Throwable t) { baseUrl = '' }

    for (Migration mig : selected) {
        List<VersionOutcome> rows = new ArrayList<VersionOutcome>()
        for (VersionOutcome vo : changed) { if (vo.migId == mig.id) rows.add(vo) }
        if (rows.isEmpty()) continue

        // page-level metadata, looked up once per page
        Set<Long> distinctPages = new LinkedHashSet<Long>()
        for (VersionOutcome vo : rows) distinctPages.add(vo.pageId as Long)

        String heading = textEsc(mig.source) + ' &mdash; ' +
                (MODE == 'APPLY' ? 'RESULT' : 'DETECTED') + ': ' +
                rows.size() + ' versions across ' + distinctPages.size() + ' pages'

        if (RESULT_FORMAT == 'TABLE') {
            results.append('<h3>').append(heading).append('</h3>')
            results.append('<table border="1" cellpadding="4" cellspacing="0">')
            results.append('<tr><th>Page ID</th><th>Page Name</th><th>Page URL</th><th>Version</th>')
                   .append('<th>Current</th><th>Occurrences</th><th>Replaced</th><th>Skipped</th>')
                   .append('<th>Failed</th><th>Status</th></tr>')
            for (VersionOutcome vo : rows) {
                PageResult meta = pageMeta.get(vo.pageId as Long)
                String url = baseUrl + '/pages/viewpage.action?pageId=' + vo.pageId
                results.append('<tr><td>').append(vo.pageId)
                       .append('</td><td>').append(textEsc(meta == null ? '' : meta.title))
                       .append('</td><td><a href="').append(url).append('" target="_blank">')
                       .append(textEsc(url)).append('</a>')
                       .append('</td><td>').append(vo.version)
                       .append('</td><td>').append(vo.isCurrent ? 'yes' : '-')
                       .append('</td><td>').append(vo.occurrences)
                       .append('</td><td>').append(vo.replaced)
                       .append('</td><td>').append(vo.skipped)
                       .append('</td><td>').append(vo.failed)
                       .append('</td><td>').append(textEsc(vo.status))
                       .append('</td></tr>')
            }
            results.append('</table>')

        } else if (RESULT_FORMAT == 'CSV') {
            StringBuilder csv = new StringBuilder()
            csv.append('page_id,page_name,page_url,version,current,occurrences,replaced,skipped,failed,status\n')
            for (VersionOutcome vo : rows) {
                PageResult meta = pageMeta.get(vo.pageId as Long)
                List<String> f = new ArrayList<String>()
                f.add(vo.pageId as String)
                f.add(meta == null ? '' : meta.title)
                f.add(baseUrl + '/pages/viewpage.action?pageId=' + vo.pageId)
                f.add(vo.version as String)
                f.add(vo.isCurrent ? 'yes' : 'no')
                f.add(vo.occurrences as String)
                f.add(vo.replaced as String)
                f.add(vo.skipped as String)
                f.add(vo.failed as String)
                f.add(vo.status)
                List<String> quoted = new ArrayList<String>()
                for (String v : f) quoted.add('"' + (v == null ? '' : v.replace('"', '""')) + '"')
                csv.append(quoted.join(',')).append('\n')
            }
            results.append('<h3>').append(heading).append('</h3>')
            results.append('<pre>').append(textEsc(csv.toString())).append('</pre>')

        } else if (RESULT_FORMAT == 'LIST') {
            StringBuilder list = new StringBuilder()
            for (Long pgid : distinctPages) {
                list.append(baseUrl).append('/pages/viewpage.action?pageId=').append(pgid).append('\n')
            }
            results.append('<h3>').append(textEsc(mig.source)).append(' &mdash; ')
                   .append(distinctPages.size()).append(' page URLs</h3>')
            results.append('<pre>').append(textEsc(list.toString())).append('</pre>')

        } else {
            results.append('<pre>RESULT_FORMAT must be TABLE, CSV, LIST or NONE.</pre>')
        }
    }
}

log.warn("Macro engine: mode=${MODE}, migrations=${selected.size()}, elapsed=${totalMs} ms")
return '<pre>' + textEsc(outp.toString()) + '</pre>' + results.toString()
