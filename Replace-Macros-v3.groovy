/*
 * =============================================================================
 *  CONFLUENCE DC MACRO REPLACEMENT ENGINE  v3      ScriptRunner Script Console
 *  (derived from accepted v2; adds EDD-as-SOURCE -> ScriptRunner-macro
 *   migrations, e.g. confidence-level-edd -> confidence-level-sr)
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

import com.atlassian.confluence.core.ContentEntityManager
import com.atlassian.confluence.core.ContentEntityObject
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.core.Modification
import com.atlassian.confluence.core.VersionHistorySummary
import com.atlassian.confluence.core.SpaceContentEntityObject
import com.atlassian.confluence.pages.AbstractPage
import com.atlassian.confluence.pages.BlogPost
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.confluence.spaces.Space
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.spring.container.ContainerManager
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

import java.lang.reflect.Method
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.net.URLDecoder
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

/*
 * SCOPE   - discovery only: WHICH pages are affected. One query, no page loads,
 *           no version scanning. Seconds even on a huge space. Use it to build
 *           the space/page inventory.
 * INSPECT - full read-only pass: loads every version of every affected page and
 *           reports per-occurrence detail. This is the expensive mode.
 * APPLY   - as INSPECT, and writes.
 */
@Field String MODE = 'INSPECT'                 // SCOPE | INSPECT | APPLY

/*
 * Process only a slice, ordered by page id so slices stay stable between runs.
 * Run 0/500, then 500/500, then 1000/500 - each run prints the next offset.
 * SCOPE_LIMIT = 0 means no limit. What is sliced depends on the mode:
 *
 *   SCOPE          slices CANDIDATE pages - every page in the configured
 *                  spaces - inside the discovery SQL itself, bounding the work
 *                  of a single run. For a space where even discovery exceeds
 *                  SQL_TIMEOUT_SECONDS, this is the way through: each slice
 *                  probes only its own pages' bodies. A slice may return few
 *                  or zero affected pages; that is not truncation.
 *   INSPECT/APPLY  slices AFFECTED pages, after full discovery. Discovery
 *                  runs whole every time; only version loading is sliced.
 *                  If discovery itself is the problem, run SCOPE (sliced) and
 *                  feed the ids into PAGE_IDS_OVERRIDE, which skips discovery.
 */
/*
 * Abort any discovery query after this many seconds instead of letting it run
 * until the HTTP request is dropped - a dropped request returns NOTHING: no
 * result, no error and no Logs tab, because the script never reaches its own
 * logging. A timeout at least comes back as a visible error. 0 = no limit.
 */
@Field int SQL_TIMEOUT_SECONDS = 300

@Field int SCOPE_OFFSET = 0
@Field int SCOPE_LIMIT = 0

/*
 * SCOPE mode processes its window in chunks of this many candidate pages, each
 * chunk one bounded SQL statement. A statement timeout kills one chunk, not
 * the run; everything already collected is printed together with the offset to
 * resume from. 0 = single statement for the whole window (NOT recommended for
 * big spaces: the query sorts before returning, so a timeout yields nothing).
 */
@Field int SCOPE_CHUNK_PAGES = 500

/*
 * Wall-clock budget for a SCOPE run, seconds. When the next chunk would start
 * past this budget, the run stops CLEANLY: collected results and a resume
 * offset are printed. This exists because the console request itself gets
 * killed by proxy/LB timeouts with ALL output lost - a self-imposed stop just
 * under that ceiling always beats a dead request. 0 = no budget. If you do not
 * know the proxy ceiling, 240 is a safe console default.
 */
@Field int SCOPE_TIME_BUDGET_SECONDS = 300

/*
 * Wall-clock budget for INSPECT and APPLY runs, seconds, measured from run
 * start (so it covers Stage-1 loading too). Checked BETWEEN PAGES in Stage-3:
 * a page's versions are never split - either all of a page's versions were
 * processed or none were, the same atomicity chunks give SCOPE. On stop, the
 * summary states how many pages were processed and the exact SCOPE_OFFSET to
 * resume from; pages past the stop produce no result rows and no writes.
 * 0 = no budget. Writes are also safe against ungraceful cutoffs: replaced
 * macros cannot match again, so re-running the same scope is a no-op for
 * everything already written - the budget stop saves re-inspection time and
 * keeps the output, it is not what protects the data.
 * This guards the HTTP REQUEST (proxy/LB ceiling), a different clock from
 * SQL_TIMEOUT_SECONDS, which bounds a single database statement. A console
 * run has survived 5 m 18 s on this instance, so the request ceiling is
 * above 300; raise this once the actual proxy limit is confirmed.
 */
@Field int RUN_TIME_BUDGET_SECONDS = 300

/*
 * Affected-version mapping, produced by an INSPECT run over the same scope
 * ("Affected versions mapping" section - paste it here verbatim). Entries are
 * 'pageId:contentId'. When set, Stage-1 loads ONLY these versions instead of
 * walking every version of every page - INSPECT already paid for finding
 * which versions matter, so APPLY does not pay again. Removes the largest
 * unknown from APPLY's runtime: version-history walking of unaffected rows.
 * Empty = walk full histories as before. Use with the SAME Migrations list
 * the INSPECT run used; a stale mapping cannot corrupt anything (versions
 * are re-matched before writing - an entry with no matches is reported and
 * skipped), it can only miss versions that a newer Migrations list would
 * have caught.
 */
@Field List<String> VERSION_MAP_OVERRIDE = []

/*
 * Emit the affected-versions mapping section from INSPECT runs. Entries are
 * 'pageId:contentId:vN' - the third token is the human-readable version
 * number as shown in the Confluence UI, metadata only; the script operates
 * on the first two and ignores everything after them when the mapping is
 * pasted back in.
 */
@Field boolean EMIT_VERSION_MAP = true

/*
 * SCOPE mode: emit the affected page ids as a paste-ready
 * PAGE_IDS_OVERRIDE = [...] block, one id per line (the console does not
 * wrap text). Select all in the box, copy, paste over the field.
 */
@Field boolean EMIT_PAGE_IDS = true

/*
 * Draft visibility (v3). Confluence's macro-usage admin page counts macros in
 * DRAFT rows too, so hiding drafts makes the engine's numbers impossible to
 * reconcile against it. INCLUDE_DRAFT_PAGES lists macro-bearing draft rows of
 * scoped pages in the results (PageType "... (draft)"); SKIP_DRAFT_PAGES keeps
 * them out of writes - Skipped rows with the reason, per the never-touch-
 * drafts policy. Excel: filter PageType = "(draft)" to explain the admin
 * count. Note: a page whose ONLY macro-bearing row is a shared draft still
 * cannot enter scope through it; version-chain drafts are discovered fully.
 */
@Field boolean INCLUDE_DRAFT_PAGES = true
@Field boolean SKIP_DRAFT_PAGES = true
/*
 * Body-match strategy for discovery.
 *   true  = one regex alternation:    body ~ 'ac:name="(a|b|...)"'
 *   false = one LIKE per macro name:  body LIKE '%ac:name="a"%' OR ...
 * SETTLED empirically on OKR (3931 pages, 39 macros, warm cache): regex ~1 s
 * vs LIKE 11 s for the identical full-space SCOPE, with identical affected
 * counts per chunk - one regex pass per body beats up to 39 sequential LIKE
 * scans. Both matchers are exact and interchangeable; LIKE is kept as a
 * cross-check (counts must always match between the two).
 */
@Field boolean USE_REGEX_MATCH = true

// Migration ids to run. Empty = all of them.
@Field List<String> RUN = []

@Field String DB_RESOURCE = 'ConfluenceDB'

// EasyDropDown tables, named explicitly. For a text-dropdown set instead:
//   AO_1313EC_TEXT_SET_ENTITY / AO_1313EC_TEXT_OPTION_ENTITY / TEXT_SET_ENTITY_ID
@Field String EDM_SET_TABLE    = 'AO_1313EC_LOZENGE_SET'
@Field String EDM_OPTION_TABLE = 'AO_1313EC_LOZENGE_OPTION'
@Field String EDM_OPTION_FK    = 'LOZENGE_SET_ENTITY_ID'

// Scope. Empty SPACE_KEYS = every space. PAGE_IDS_OVERRIDE bypasses discovery.
@Field List<String> SPACE_KEYS = []
@Field List<Long> PAGE_IDS_OVERRIDE = []
@Field List<String> INCLUDE_STATUSES = ['current']

/*
 * Supported configurations of page updates:
 *
 * 1. Create a new page version with updated content. Current version and
 *    version history left intact.
 *        UPDATE_HISTORICAL_VERSIONS  = false
 *        CURRENT_CREATES_NEW_VERSION = true
 *
 * 2. In-place update of the current version. Version history left intact.
 *    No new version created.
 *        UPDATE_HISTORICAL_VERSIONS  = false
 *        CURRENT_CREATES_NEW_VERSION = false
 *
 * 3. In-place update of the current version and every previous version.
 *    No new version created.
 *        UPDATE_HISTORICAL_VERSIONS  = true
 *        CURRENT_CREATES_NEW_VERSION = false
 *
 * Both true is NOT supported and terminates the run: saveNewVersion copies each
 * pre-change body into a new historical row during Stage-3, after Stage-1 has
 * frozen the findings, so that row would keep its macro and never be cleaned.
 */
@Field boolean UPDATE_HISTORICAL_VERSIONS = true
@Field boolean CURRENT_CREATES_NEW_VERSION = false

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

// false -> Stage-0 prints only validation ISSUES. true -> also prints the full
// per-migration parameter and option tables, useful when building a config.
@Field boolean VALIDATION_SHOW_DETAIL = false

// Results listing: TABLE | CSV | LIST | NONE
@Field String RESULT_FORMAT = 'TABLE'

/*
 * MACRO   - one row per macro OCCURRENCE: which macro, on which page version,
 *           what it became, and for anything not replaced, why. This is the
 *           only view that answers "what was skipped and why" - a version-level
 *           row reading "17 occurrences, 16 replaced, 1 skipped" identifies
 *           neither the macro nor the reason.
 * VERSION - one row per page version. Compact, for very large runs.
 */
@Field String RESULT_GRANULARITY = 'MACRO'

// The Migration column duplicates the source macro name whenever migration ids
// are named after their source macro, which is the usual case.
@Field boolean RESULT_SHOW_MIGRATION_COLUMN = false

// Legend table above the results, explaining columns and abbreviations.
@Field boolean RESULT_SHOW_LEGEND = true

/*
 * Cap on rows appended to the results output. Rows are HTML in memory and then
 * one page in a browser: at roughly 400 bytes per row, 100k rows is ~40 MB of
 * markup in a single table, which the browser will struggle to render even
 * though the script produced it happily. Beyond the cap rows are COUNTED but
 * not appended, and the table says so - the run itself is unaffected.
 * 0 = no cap.
 */
@Field int MAX_RESULT_ROWS = 0

/*
 * Above this many occurrences, an HTML results table is a bad idea: each row is
 * ~15 cells, so 26,000 occurrences is ~390,000 DOM nodes in one table and the
 * browser will fall over rendering it, however happily the script produced it.
 * CSV carries exactly the same rows as a single block of text - complete, no
 * truncation - and costs the browser almost nothing. This only warns; it never
 * changes the format for you.
 */
@Field int RESULT_TABLE_WARN_ROWS = 5000

/*
 * Split the HTML results table into chunks of this many rows, each in a
 * content-visibility:auto wrapper so the browser can skip layout and paint for
 * chunks that are off screen. Helps a huge table render, but the chunks are
 * separate tables - awkward to select and copy as one. 0 = one single table.
 * For large runs prefer RESULT_FORMAT = 'CSV' below.
 */
@Field int RESULT_TABLE_CHUNK_ROWS = 0
@Field int RESULT_ROW_HEIGHT_PX = 28

/*
 * With RESULT_FORMAT = 'CSV', put the rows in a <textarea> with a Copy button
 * rather than a <pre>.
 *
 * A textarea is ONE DOM node however many rows it holds - the browser manages
 * its content internally instead of laying out a node per cell - so 26,000 rows
 * costs about what 26 rows costs. It also solves selecting the lot: click in it
 * and Ctrl+A, or press the button.
 *
 * There is no copy button: Confluence sanitises inline event handlers, and
 * injected <script> blocks never execute, so no JavaScript emitted here can
 * reach the clipboard. Ctrl+A, Ctrl+C inside the box is the working route.
 */
@Field boolean RESULT_CSV_TEXTAREA = true
@Field int RESULT_TEXTAREA_ROWS = 24

/*
 * Wrap the HTML results table in a fixed-height scroll box, so the summary and
 * the rollback section stay reachable instead of sitting below thousands of
 * rows. Matches how the CSV textarea behaves.
 *
 * Note this makes the page navigable, not the table cheap: the browser still
 * lays out every cell, because table column widths depend on all rows. For
 * genuinely large runs use RESULT_FORMAT = 'CSV'.
 */
@Field boolean RESULT_TABLE_SCROLLBOX = true
@Field int RESULT_TABLE_MAX_HEIGHT_PX = 600

// Rollback copies. Compressed output is deflate + Base64; Base64 is required
// because the console returns a String and raw deflate bytes are not valid text.
@Field boolean EMIT_ROLLBACK_COPIES = true
@Field boolean COMPRESS_SOURCE_ON_SUCCESS = true
@Field boolean COMPRESS_REPLACED_ON_SUCCESS = true
@Field boolean COMPRESS_SOURCE_ON_FAILURE = false
@Field boolean COMPRESS_REPLACED_ON_FAILURE = false

/*
 * Rollback copies are page bodies - they dwarf everything else in the output.
 * Kept in their own fixed-height scroll box so the summary and the results
 * table stay reachable instead of sitting above thousands of lines of storage
 * format. 0 = render them inline as before.
 */
@Field int ROLLBACK_MAX_HEIGHT_PX = 400

// Same, for the trace section (TRACE_MAPPING). 0 = plain, unboxed output.
@Field int TRACE_MAX_HEIGHT_PX = 300
@Field boolean EMIT_REPLACED_ON_SUCCESS = false     // usually not needed
@Field int MAX_ROLLBACK_ENTRIES = 200

// Per-occurrence mapping trace in the notes. The results table already carries
// the same information per row, so this is off unless you are debugging.
@Field boolean TRACE_MAPPING = false

// =============================================================================
//  MIGRATIONS
//
//  source / target are objects: [name: ..., type: MacroType....]
//
//  Per type, the fields that matter:
//    UserMacro / ScriptRunnerMacro source
//        sourceParam    parameter carrying the value (validated to exist)
//        values         optional, explicit list of values the parameter can hold.
//                       Use when the macro declares no enumValues because its
//                       allowed values are constrained inside the macro body.
//        valueRange     optional, [from, to] integers expanded to a list, e.g.
//                       [0, 10]. Shorthand for values.
//        aliases        optional, historical spelling -> today's canonical value.
//                       For values RENAMED in the macro mid-life: pages saved
//                       while the old spelling was live still carry it in
//                       storage, and without a mapping those occurrences fail
//                       as unknown values. Example - the process-status macro
//                       once shipped the option "UnterVeranderung", later
//                       corrected to "Unter Veranderung"; pages from that era
//                       still say "UnterVeranderung":
//
//                           source: [name: 'process-status',
//                                    type: MacroType.UserMacro,
//                                    sourceParam: 'status',
//                                    aliases: ['UnterVeranderung': 'Unter Veranderung']],
//
//                       The alias resolves BEFORE the option lookup, and the
//                       TARGET macro is written with the canonical value - the
//                       migration also heals the stale spelling on the page.
//                       Every hit is logged in the trace (TRACE_MAPPING) as
//                       alias: "old" -> "new". Stage-0 rejects an alias whose
//                       canonical side is not a known option value, and an
//                       alias whose OLD side is itself a legitimate value
//                       (that would silently rewrite valid data). Alias only
//                       spellings you can attribute to the macro's edit
//                       history - do not add fuzzy/normalizing aliases; an
//                       unknown value failing loudly is the tool telling you
//                       a human needs to look.
//    EddStatusMacro target
//        setId, setName, options: [ '<source enum value>': '<option-id>', ... ]
//    Static_QualificationTable target
//        no target macro; output is computed storage
//    any target
//        staticParams   fixed parameters written on every instance
//        paramMap       source param name -> target param name
//        dropUnmapped   drop source params with no paramMap entry
// =============================================================================
/*
 * Declared as a raw List on purpose. A map literal with mixed value types is
 * inferred as LinkedHashMap<String, Serializable>, and because generics are
 * invariant that will not assign to List<Map<String, Object>> under
 * ScriptRunner's static type checking. Each entry is cast where it is used.
 */
@Field List MIGRATIONS = [

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

    [
        id     : 'link-button',
        source : [name: 'link-button', type: MacroType.ScriptRunnerMacro,
                  // link-button declares url with a real default, so an absent
                  // url parameter still resolves. Listed explicitly so the run
                  // does not depend on metadata discovery succeeding.
                  paramDefaults: ['url': 'http://www.google.de']],
        target : [name: 'aura-button', type: MacroType.AuraLinkButton,
                  schemaVersion: '1',
                  // per-instance values
                  paramMap: ['url': 'href', 'buttontext': 'label'],
                  // standard look applied to every replaced button - adjust here
                  staticParams: [
                      'elevation'   : 'flat',
                      'outlined'    : 'regular',
                      'borderRadius': '28',
                      'color'       : '#000000',
                      'size'        : 'medium',
                      'background'  : '#b0e572',
                      'iconPosition': 'left',
                      'hrefTarget'  : '_blank',   // _blank opens in a new tab
                      'alignment'   : 'left',
                      // hrefType is NOT set here - it is derived per instance
                      // (link / page / attachment) from the source URL
                  ],
                  dropUnmapped: true],
    ],
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
    /*
     * Historical spellings of source values -> today's canonical value.
     * For values that were RENAMED in the user macro mid-life: pages saved
     * while the old spelling was live still carry it in storage, and without
     * a mapping those occurrences fail as unknown values. Declared on the
     * source block:  aliases: ['UnterVeranderung': 'Unter Veranderung']
     * The alias is resolved BEFORE the option lookup, and the TARGET macro is
     * written with the canonical value - so migration also heals the stale
     * spelling on the page.
     */
    Map<String, String> valueAliases = new LinkedHashMap<String, String>()
    /*
     * v3: source values must parse as numbers (comma or dot decimals). For
     * EDD -> ScriptRunner migrations where the SR macro takes a numeric
     * parameter but declares no enum - the only validation possible without
     * heuristically parsing the SR macro body, which we deliberately do not do.
     */
    boolean sourceValuesNumeric = false
    // filled by Stage-0
    List<String> sourceEnumValues = new ArrayList<String>()
    // set from config when the macro declares no enumValues because its allowed
    // values are constrained inside the macro body rather than in @param
    List<String> sourceValuesOverride = new ArrayList<String>()
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
    String message = ''      // why it was skipped or failed
    String detail = ''       // what the replacement actually did, when it succeeded
}

class VersionFinding {
    long contentId
    int versionNumber
    boolean isCurrent
    boolean isDraft
    boolean hasMatchedMacros
    List<MatchedMacro> matchedMacros = new ArrayList<MatchedMacro>()
    // execution results
    ReplacementStatus status = ReplacementStatus.Unknown
    String message = ''
    String bodyBefore, bodyAfter
}

class PageFinding {
    long pageId
    String kind = 'page'          // 'page' | 'blogpost' ('template' reserved)
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
 * NESTING IS REAL. Container macros such as aura-panel wrap in-scope macros in
 * an <ac:rich-text-body>, so a single regex for a whole element cannot work:
 *   - a lazy .*? backtracks past the nearest closing tag and merges siblings
 *   - a tempered body stops at the FIRST </ac:structured-macro>, which for a
 *     container is the INNER macro's closing tag - the match then swallows the
 *     inner macro and the scan resumes past it, so the inner macro is never
 *     seen. That is why a qualification-table inside an aura-panel was missed.
 *
 * Instead the body is tokenised into open and close tags and walked with a
 * depth stack, which yields every macro at every nesting level in document
 * order, with exact element bounds.
 */
@Field Pattern P_MACRO_TOKEN = Pattern.compile('(?s)<ac:structured-macro\\b([^>]*)>|</ac:structured-macro>')

@Field Pattern P_NAME     = Pattern.compile('ac:name="([^"]*)"')
@Field Pattern P_MACRO_ID = Pattern.compile('ac:macro-id="([^"]*)"')

/* Tolerant: either quote style, self-closing empty parameters, attributes in
 * any order. The VALUE is captured with .*? under DOTALL so umlauts, entities
 * and newlines pass through byte-transparent. */
@Field Pattern P_PARAM = Pattern.compile(
        '(?s)<ac:parameter\\s+[^>]*?ac:name=(?:"([^"]*)"|\'([^\']*)\')\\s*' +
        '(?:/>|>(.*?)</ac:parameter>)')

@Field Pattern P_VELOCITY_PARAM = Pattern.compile('^\\s*##\\s*@param\\s+([^:\\s]+)\\s*:?(.*)$')

/*
 * Emit Confluence's own table classes on the flattened table, as the original
 * macro did: class="confluenceTable" / confluenceTh / confluenceTd.
 *
 * Confluence's table borders and cell backgrounds - including the dark-theme
 * variants - are attached to these classes, not to bare <table>/<td>. An
 * unclassed table therefore picks up whatever the surrounding container
 * supplies, which is why cells can lose their borders and their theme
 * background inside a panel macro while the header row still looks right.
 *
 * Set false to emit a plain table and compare.
 */
@Field boolean QM_CONFLUENCE_TABLE_CLASSES = true

// qualification-table column order: label -> parameter
@Field List<List<String>> QM_COLUMNS = [
        ['KB'  , 'impactFinance'],   ['V'   , 'impactSales'],
        ['PM'  , 'impactProductmanagement'], ['M', 'impactMarketing'],
        ['O&S' , 'impactOuS'],       ['HR'  , 'impactHR'],
        ['GF'  , 'impactGF'],        ['LEAS', 'impactLR'],
        ['ASUS', 'impactASUS'],
]

@Field List<String> RUN_LOG = new ArrayList<String>()
@Field String BASE_URL = ''
/*
 * SPACE_KEYS as the API resolves them.
 *
 * SpaceManager.getSpace() accepts aliases - notably a personal space addressed
 * as ~username - but the spaces table stores the real key, which for a personal
 * space is often "~" + the user key rather than the username. Filtering SQL on
 * what was typed then matches nothing while Stage-0 happily validates. Every
 * space filter uses the resolved keys instead.
 */
@Field List<String> RESOLVED_SPACE_KEYS = new ArrayList<String>()
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

/**
 * A heading plus its own scrollable box around escaped text. Every bulk
 * section (trace, rollback copies) gets one, so each can be skimmed or
 * skipped independently and none can dominate the page. Escaping happens
 * HERE and only here - the builders hold raw text.
 */
String bulkBox(String heading, String bodyText, int maxPx) {
    StringBuilder b = new StringBuilder()
    b.append('<h3>').append(heading).append('</h3>')
    if (maxPx > 0) {
        b.append('<div style="max-height:').append(maxPx)
         .append('px;overflow:auto;border:1px solid #ccc;resize:vertical">')
         .append('<pre style="margin:0">').append(htmlEsc(bodyText)).append('</pre></div>')
    } else {
        b.append('<pre>').append(htmlEsc(bodyText)).append('</pre>')
    }
    return b.toString()
}

/*
 * Escapes LIKE wildcards in a literal.
 *
 * Values are bound as parameters, so injection is not the issue - but inside a
 * LIKE pattern "_" still matches ANY single character and "%" any sequence, in
 * the bound value as much as in literal SQL. A macro named "my_macro" would
 * therefore also match "myXmacro". Paired with ESCAPE '\\' on the predicate.
 */
String likeEscape(String v) {
    if (v == null) return ''
    return v.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
}

/** Escapes POSIX regex metacharacters in a literal, for USE_REGEX_MATCH. */
String regexEscape(String v) {
    if (v == null) return ''
    StringBuilder b = new StringBuilder()
    for (int i = 0; i < v.length(); i++) {
        char c = v.charAt(i)
        if ('.^$*+?()[]{}|\\-'.indexOf(c as int) >= 0) b.append('\\')
        b.append(c)
    }
    return b.toString()
}

/** "1 problem" / "2 problems" - avoids the "(s)" that reads as unfinished. */
String plural(int n, String one) {
    return n as String + ' ' + (n == 1 ? one : one + 's')
}

String plural(int n, String one, String many) {
    return n as String + ' ' + (n == 1 ? one : many)
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

/** One <ac:structured-macro> element, located exactly, at any nesting depth. */
class MacroSpan {
    int start, openEnd, end, depth
    String name = '', macroId = ''
    boolean selfClosing
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

/**
 * Every macro element in the body, any depth, in document order.
 * Unbalanced markup is tolerated: an unmatched open tag is dropped rather than
 * throwing, so one malformed page cannot abort a run.
 */
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

/**
 * Parameters belonging to this element only. Content is cut at the first nested
 * <ac:structured-macro> so a container's own parameters can never absorb an
 * inner macro's - in-scope source macros hold no nested macros, so this only
 * ever guards against malformed input.
 */
Map<String, String> paramsOfSpan(String body, MacroSpan sp) {
    try {
        if (sp.selfClosing) return new LinkedHashMap<String, String>()
        int contentEnd = sp.end - '</ac:structured-macro>'.length()
        if (contentEnd <= sp.openEnd) return new LinkedHashMap<String, String>()
        String inner = body.substring(sp.openEnd, contentEnd)
        int nested = inner.indexOf('<ac:structured-macro')
        if (nested >= 0) inner = inner.substring(0, nested)
        return parseParams(inner)
    } catch (Exception e) {
        throw new RuntimeException('paramsOfSpan failed: ' + e.getMessage(), e)
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
/**
 * Ordered option name -> option-id for one EasyDropDown set, read live.
 * Single explicitly named table pair - no family guessing, no swallowed
 * exceptions: a failing query must never be reported as "set not found".
 */
Map<String, String> edmOptionsForSet(String setId, String setName) {
    try {
        String resource = DB_RESOURCE
        String tSet = EDM_SET_TABLE, tOpt = EDM_OPTION_TABLE, fk = EDM_OPTION_FK
        boolean byGuid = (setId != null && !setId.trim().isEmpty() && !setId.startsWith('PUT-'))
        String lookupValue = byGuid ? setId : setName
        String setQuery = 'SELECT s."ID" AS pk FROM public."' + tSet + '" s WHERE s."' +
                          (byGuid ? 'SET_ID' : 'SET_NAME') + '" = :v'

        String pk = null
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(setQuery, [v: lookupValue]) { row -> pk = row['pk'] as String }
        }
        Map<String, String> out = new LinkedHashMap<String, String>()
        if (pk == null) return out

        String optQuery = 'SELECT o."OPTION_NAME" AS nm, o."OPTION_ID" AS gid FROM public."' +
                          tOpt + '" o WHERE o."' + fk + '" = :pk ORDER BY o."ID"'
        int pkValue = Integer.parseInt(pk)
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(optQuery, [pk: pkValue]) { row ->
                if (row['gid'] != null) {
                    out.put(row['nm'] == null ? '' : row['nm'] as String, row['gid'] as String)
                }
            }
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('edmOptionsForSet("' + setName + '") failed: ' + e.getMessage(), e)
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
        if (src.get('values') != null) {
            for (Object v : (List) src.get('values')) m.sourceValuesOverride.add(v as String)
        } else if (src.get('valueRange') != null) {
            List r = (List) src.get('valueRange')
            int from = (r.get(0) as Integer).intValue()
            int to = (r.get(1) as Integer).intValue()
            for (int i = from; i <= to; i++) m.sourceValuesOverride.add(i as String)
        }

        if (src.get('aliases') != null) {
            for (Map.Entry<Object, Object> ae : ((Map<Object, Object>) src.get('aliases')).entrySet()) {
                m.valueAliases.put(ae.getKey() as String, ae.getValue() as String)
            }
        }

        // v3: EDD as SOURCE - the set identifies OUR occurrences (every EDD
        // set shares one ac:name); the value parameter defaults to the one
        // every EDD occurrence carries
        if (m.sourceType == MacroType.EddStatusMacro) {
            if (src.get('setId') != null)   m.setId   = src.get('setId') as String
            if (src.get('setName') != null) m.setName = src.get('setName') as String
            if (m.sourceParam == null || m.sourceParam.trim().isEmpty()) {
                m.sourceParam = 'current-option-value'
            }
        }
        if (src.get('valuesNumeric') != null) m.sourceValuesNumeric = (Boolean) src.get('valuesNumeric')

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
            Space sp = spaceManager.getSpace(key)
            if (sp == null) {
                ValidationIssue i = new ValidationIssue()
                i.migrationId = '(scope)'; i.sourceLabel = key; i.targetLabel = ''
                i.description = 'SPACE_KEYS names a space that does not exist'
                issues.add(i)
            } else {
                RESOLVED_SPACE_KEYS.add(sp.getKey())
            }
        }
        for (Long pid : PAGE_IDS_OVERRIDE) {
            AbstractPage p = pageManager.getAbstractPage(pid.longValue())
            ValidationIssue i = new ValidationIssue()
            i.migrationId = '(scope)'; i.sourceLabel = pid as String; i.targetLabel = ''
            if (p == null) {
                i.description = 'PAGE_IDS_OVERRIDE names an id that is neither a page nor a blog post'
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
        // v3: an EDD source lives in no macro library - it is identified by
        // its set, so setId is the thing to validate instead
        if (m.sourceType == MacroType.EddStatusMacro) {
            if (m.setId == null || m.setId.trim().isEmpty()) {
                issues.add(issue(m.id, m.sourceName, '',
                        'EDD source requires setId on the source block - the set-id GUID is the ' +
                        'only thing distinguishing OUR occurrences (all EDD sets share one macro name)'))
            }
            detail.append('  ').append(m.id).append(': EDD source, set-id ').append(m.setId)
                  .append(m.sourceValuesNumeric ? ', values must be numeric' : '').append('\n')
        }
        boolean srcExists = (m.sourceType == MacroType.EddStatusMacro)
                ? true : macroExists(m.sourceName, m.sourceType)
        if (!srcExists) {
            issues.add(issue(m.id, m.sourceName, '',
                    'source macro not found as ' + m.sourceType + '. Either the type is wrong or the ' +
                    'macro is uninstalled. If it is genuinely uninstalled its usages still need ' +
                    'replacing - set the correct type or remove this migration.'))
        } else {
            m.discoveredDefaults.putAll(declaredDefaults(m.sourceName, m.sourceType))
        }

        // --- source parameter must exist ---------------------------------
        if (m.sourceParam != null && srcExists && m.sourceType != MacroType.EddStatusMacro) {
            List<String> declared = declaredParamNames(m.sourceName, m.sourceType)
            if (!declared.contains(m.sourceParam)) {
                issues.add(issue(m.id, m.sourceName, '',
                        'sourceParam "' + m.sourceParam + '" is not declared on the macro. Declared: ' + declared))
            } else {
                m.sourceEnumValues = declaredEnumValues(m.sourceName, m.sourceType, m.sourceParam)
            }
        }

        // Explicit config wins: a parameter can be constrained inside the macro
        // body rather than in its @param line, where introspection cannot see it.
        for (Map.Entry<String, String> ae : m.valueAliases.entrySet()) {
            if (!m.options.containsKey(ae.getValue())) {
                issues.add(issue(m.id, m.sourceName, m.targetName,
                        'alias "' + ae.getKey() + '" points at "' + ae.getValue() +
                        '", which is not a known option value of set "' + m.setName +
                        '". Known: ' + m.options.keySet()))
            }
            if (m.options.containsKey(ae.getKey())) {
                issues.add(issue(m.id, m.sourceName, m.targetName,
                        'alias key "' + ae.getKey() + '" is ITSELF a known option value - ' +
                        'an alias would silently rewrite a legitimate value; remove one of the two'))
            }
        }
        if (!m.sourceValuesOverride.isEmpty()) {
            m.sourceEnumValues = new ArrayList<String>(m.sourceValuesOverride)
            detail.append('  ').append(m.id).append(': source values taken from config (')
                  .append(m.sourceEnumValues.size()).append('), not from the macro definition\n')
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
        if (srcVals.isEmpty()) {
            issues.add(issue(m.id, m.sourceName, m.targetName,
                    'source parameter "' + m.sourceParam + '" declares no enum values. If its ' +
                    'allowed values are constrained inside the macro body, declare them with ' +
                    'values: [...] or valueRange: [from, to] on the source block.'))
        }
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
/*
 * DISCOVERY
 *
 * The only expensive predicate is "body contains this macro" - it cannot use an
 * index, so whatever rows reach it are read in full. Everything here exists to
 * make that set as small as possible BEFORE the body is touched:
 *
 *   - filter on content.spaceid, an indexed integer resolved up front, NOT on
 *     spaces.spacekey. Joining spaces to test a key means the space is unknown
 *     until after the join, so every body in the instance gets read.
 *   - select two columns, contentid and prevver. Nothing else is needed to
 *     identify a page, and content is a wide table.
 *   - no self-join to the parent row, no CTE, no COALESCE. The current page id
 *     is prevver when set and contentid otherwise - trivial in Groovy, and it
 *     keeps the SQL to one indexed filter plus one primary-key join.
 *
 * Historical rows normally carry their own spaceid, so filtering on it keeps
 * them. The handful that do not are the orphaned rows seen earlier; they are
 * reported by Diagnose-NoSpacePages rather than being chased here at the cost
 * of every query in the script.
 */

/** spaceids for the configured space keys. One tiny indexed lookup. */
List<Integer> spaceIdsFor(List<String> spaceKeys) {
    try {
        List<Integer> ids = new ArrayList<Integer>()
        if (spaceKeys.isEmpty()) return ids
        List<String> ph = new ArrayList<String>()
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        for (int i = 0; i < spaceKeys.size(); i++) {
            ph.add(':k' + i)
            params.put('k' + i, spaceKeys.get(i))
        }
        String query = 'SELECT spaceid FROM spaces WHERE spacekey IN (' + ph.join(', ') + ')'
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow(query, params) { row -> ids.add(((Number) row['spaceid']).intValue()) }
        }
        return ids
    } catch (Exception e) {
        throw new RuntimeException('spaceIdsFor failed: ' + e.getMessage(), e)
    }
}

/**
 * Current page ids that have at least one version carrying a source macro.
 * Two columns out of content; the page id is derived in Groovy.
 */
/**
 * Body-match predicate over one bodycontent alias. Fills params and returns
 * the SQL fragment. The clause is used twice per query (current-body probe and
 * history probe), so parameter names carry a tag to stay distinct.
 */
/**
 * v3: macro-bearing DRAFT rows of one scoped page - version-chain drafts
 * (prevver) and the shared collaborative draft (draftpageid). Bodies come
 * from SQL because the page API does not serve draft rows. Matched drafts
 * join pf.versions like any version; Stage-3 skips them from writes when
 * SKIP_DRAFT_PAGES is set.
 */
void addDraftVersions(PageFinding pf, Map<String, MigrationDef> bySource) {
    String q = '''
        SELECT DISTINCT c.contentid AS cid, c.version AS v, b.body AS body
        FROM content c JOIN bodycontent b ON b.contentid = c.contentid
        WHERE c.content_status = 'draft'
          AND (c.prevver = :pid OR c.draftpageid = :pidStr)
    '''
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        sql.eachRow(q, [pid: pf.pageId, pidStr: String.valueOf(pf.pageId)]) { row ->
            long cid = ((Number) row['cid']).longValue()
            boolean seen = false
            for (VersionFinding known : pf.versions) if (known.contentId == cid) seen = true
            if (seen) return
            VersionFinding vf = new VersionFinding()
            vf.contentId = cid
            vf.versionNumber = row['v'] == null ? 0 : ((Number) row['v']).intValue()
            vf.isCurrent = false
            vf.isDraft = true
            vf.matchedMacros = scanBody(row['body'] as String, bySource)
            vf.hasMatchedMacros = !vf.matchedMacros.isEmpty()
            if (vf.hasMatchedMacros) pf.versions.add(vf)
        }
    }
}

/** Draft version in Stage-3: never written; everything found is Skipped. */
void markDraftSkipped(VersionFinding vf, Map<String, MigrationDef> byId) {
    vf.status = ReplacementStatus.Skipped
    vf.message = 'page draft - not replaced by policy (SKIP_DRAFT_PAGES)'
    for (MatchedMacro mm : vf.matchedMacros) {
        mm.status = ReplacementStatus.Skipped
        mm.message = vf.message
        MigrationDef md = byId.get(mm.migrationId)
        if (md != null) md.occSkipped++
    }
}

/**
 * v3: discovery tokens per migration. Ordinary sources are found by macro
 * NAME (matched as ac:name="..."); an EDD source is found by its SET-ID GUID,
 * matched as a literal substring - globally unique, present verbatim in the
 * set-id parameter of exactly OUR occurrences, so SCOPE never sweeps in pages
 * that only carry other EDD sets. Literal tokens are prefixed so the match
 * clause can tell the two kinds apart.
 */
Set<String> discoveryTokens(List<MigrationDef> migrations) {
    Set<String> t = new LinkedHashSet<String>()
    for (MigrationDef md : migrations) {
        t.add(md.sourceType == MacroType.EddStatusMacro ? ('literal:' + md.setId) : md.sourceName)
    }
    return t
}

String macroMatchClause(Set<String> sourceNames, String alias, String tag, Map<String, Object> params) {
    List<String> names = new ArrayList<String>()
    List<String> literals = new ArrayList<String>()
    for (String s : sourceNames) {
        if (s.startsWith('literal:')) literals.add(s.substring('literal:'.length()))
        else names.add(s)
    }
    if (USE_REGEX_MATCH) {
        List<String> alts = new ArrayList<String>()
        if (!names.isEmpty()) {
            List<String> nameAlts = new ArrayList<String>()
            for (String name : names) nameAlts.add(regexEscape(name))
            alts.add('ac:name="(' + nameAlts.join('|') + ')"')
        }
        for (String lit : literals) alts.add(regexEscape(lit))
        params.put('re' + tag, alts.size() == 1 ? alts.get(0) : '(' + alts.join('|') + ')')
        return alias + '.body ~ :re' + tag
    }
    List<String> clauses = new ArrayList<String>()
    int n = 0
    for (String name : names) {
        params.put('m' + tag + n, '%ac:name="' + likeEscape(name) + '"%')
        clauses.add(alias + '.body LIKE :m' + tag + n + " ESCAPE '\\'")
        n++
    }
    for (String lit : literals) {
        params.put('m' + tag + n, '%' + likeEscape(lit) + '%')
        clauses.add(alias + '.body LIKE :m' + tag + n + " ESCAPE '\\'")
        n++
    }
    return clauses.join(' OR ')
}

/**
 * Candidate pages: CURRENT rows (prevver IS NULL) in the configured scope.
 * The space and status filters live here and ONLY here - historical rows may
 * carry NULL spaceid and never carry content_status = 'current', so filtering
 * them by their own columns silently drops history. History is reached per
 * candidate through prevver = <current id> instead: filtered by ownership.
 * Ordered by contentid when sliced, so a slice means the same pages every run.
 */
/** The scope filter over a current-page alias `cur` - shared by candidate
 *  queries and the per-space totals. Returns the WHERE conditions only. */
String currentPageScopeClause(List<Integer> spaceIds, Map<String, Object> params) {
    StringBuilder q = new StringBuilder()
    q.append("cur.contenttype IN ('PAGE','BLOGPOST') AND cur.prevver IS NULL ")
    if (!spaceIds.isEmpty()) {
        List<String> ph = new ArrayList<String>()
        for (int i = 0; i < spaceIds.size(); i++) {
            ph.add(':sid' + i)
            params.put('sid' + i, spaceIds.get(i))
        }
        q.append('AND cur.spaceid IN (').append(ph.join(', ')).append(') ')
    }
    if (!INCLUDE_STATUSES.isEmpty()) {
        List<String> ph = new ArrayList<String>()
        for (int i = 0; i < INCLUDE_STATUSES.size(); i++) {
            ph.add(':st' + i)
            params.put('st' + i, INCLUDE_STATUSES.get(i))
        }
        q.append('AND cur.content_status IN (').append(ph.join(', ')).append(') ')
    }
    return q.toString()
}

String candidatePageSql(List<Integer> spaceIds, Map<String, Object> params,
                        int candidateOffset, int candidateLimit) {
    StringBuilder q = new StringBuilder()
    q.append('SELECT cur.contentid AS pid FROM content cur ')
     .append('WHERE ').append(currentPageScopeClause(spaceIds, params))
    if (candidateOffset > 0 || candidateLimit > 0) {
        q.append('ORDER BY cur.contentid ')
        if (candidateLimit > 0) q.append('LIMIT ').append(candidateLimit).append(' ')
        if (candidateOffset > 0) q.append('OFFSET ').append(candidateOffset).append(' ')
    }
    return q.toString()
}

/**
 * How many candidate pages the scope holds BEFORE the body probe - the number
 * SCOPE-mode slices are measured against. Cheap: content rows only, no bodies.
 */
int candidatePageCount() {
    try {
        List<String> spaceKeys = RESOLVED_SPACE_KEYS.isEmpty()
                ? new ArrayList<String>(SPACE_KEYS) : new ArrayList<String>(RESOLVED_SPACE_KEYS)
        List<Integer> spaceIds = spaceIdsFor(spaceKeys)
        if (!spaceKeys.isEmpty() && spaceIds.isEmpty()) return 0
        Map<String, Object> params = new LinkedHashMap<String, Object>()
        String inner = candidatePageSql(spaceIds, params, 0, 0)
        int count = 0
        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            sql.eachRow('SELECT COUNT(*) AS n FROM (' + inner + ') t', params) { row ->
                count = ((Number) row['n']).intValue()
            }
        }
        return count
    } catch (Exception e) {
        throw new RuntimeException('candidatePageCount failed: ' + e.getMessage(), e)
    }
}

/** Candidate page ids for a window - ids only, no bodies touched. Cheap. */
List<Long> fetchCandidateIds(List<Integer> spaceIds, int candidateOffset, int candidateLimit) {
    Map<String, Object> params = new LinkedHashMap<String, Object>()
    String q = candidatePageSql(spaceIds, params, candidateOffset, candidateLimit)
    List<Long> ids = new ArrayList<Long>()
    DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
        if (SQL_TIMEOUT_SECONDS > 0) sql.execute('SET statement_timeout = ' + (SQL_TIMEOUT_SECONDS * 1000))
        sql.eachRow(q, params) { row -> ids.add(((Number) row['pid']).longValue()) }
    }
    return ids
}

/**
 * Ids as a SQL literal list. Safe by construction: every value is a long that
 * came out of our own candidate query - there is nothing to inject. Literals
 * are used instead of bind parameters because a chunk carries hundreds of ids
 * and, more importantly, explicit values let the planner see exact cardinality.
 */
String joinIds(List<Long> ids) {
    StringBuilder b = new StringBuilder()
    for (int i = 0; i < ids.size(); i++) {
        if (i > 0) b.append(',')
        b.append(ids.get(i).longValue())
    }
    return b.toString()
}

/** Full scope, no candidate slicing - what Stage-1 uses. */
List<Long> resolveScope(Set<String> sourceNames) {
    return resolveScope(sourceNames, 0, 0)
}

/**
 * Current page ids having at least one version that carries a source macro.
 *
 * TWO-PHASE, deliberately plain SQL - no subqueries, no joins with body
 * filters:
 *
 *   phase 0  fetch the candidate page ids for the window (ids only)
 *   phase 1  probe each candidate's CURRENT body:
 *              SELECT contentid FROM bodycontent WHERE contentid IN (ids)
 *              AND (macro match) - one indexed body read per page; decides
 *            every page whose live version still carries a macro
 *   phase 2  only for candidates phase 1 did not hit, and only when
 *            UPDATE_HISTORICAL_VERSIONS, in two join-free steps:
 *            (a) SELECT contentid, prevver FROM content WHERE prevver IN (ids)
 *                - the pages' historical version ids, no bodies touched
 *            (b) SELECT contentid FROM bodycontent WHERE contentid IN (ids)
 *                AND (macro match) - history bodies probed by primary key,
 *                matches mapped back to their page via (a)
 *
 * WHY this exact shape: every statement is either content-metadata-only or a
 * PRIMARY-KEY id-list probe into bodycontent. Postgres planners rewrite
 * subqueries (observed: EXISTS -> hashed SubPlan seq-scanning ALL of
 * bodycontent on OKR) and can satisfy join+filter statements by scanning
 * bodycontent instance-wide (observed: flat ~2.5 min/chunk on BC from the
 * joined phase-2 shape). Cost-based flips are input-dependent and cannot be
 * ruled out by testing on other spaces; these statements admit no plan that
 * scans bodycontent, so their behavior is the same at every scale, on every
 * space, on every ANALYZE state.
 *
 * Early exit note: phase 1 is exactly one body per page. Phase 2 reads all
 * matching history rows of the pages it probes instead of stopping at the
 * first - those are pages whose current version is clean, and for the (many)
 * fully clean ones every version must be read anyway to prove absence, so the
 * difference is a few extra rows on the (few) history-only-affected pages.
 *
 * candidateOffset/candidateLimit bound the window as before. Ids are embedded
 * in chunks of ID_LIST_MAX per statement.
 */
List<Long> resolveScope(Set<String> sourceNames, int candidateOffset, int candidateLimit) {
    try {
        if (!PAGE_IDS_OVERRIDE.isEmpty()) {
            // sorted so SCOPE_OFFSET means the same pages on every run,
            // whatever order the ids were pasted in
            List<Long> ov = new ArrayList<Long>(PAGE_IDS_OVERRIDE)
            Collections.sort(ov)
            return ov
        }
        if (sourceNames.isEmpty()) return new ArrayList<Long>()

        List<String> spaceKeys = RESOLVED_SPACE_KEYS.isEmpty()
                ? new ArrayList<String>(SPACE_KEYS) : new ArrayList<String>(RESOLVED_SPACE_KEYS)
        List<Integer> spaceIds = spaceIdsFor(spaceKeys)
        if (!spaceKeys.isEmpty() && spaceIds.isEmpty()) return new ArrayList<Long>()

        List<Long> cand = fetchCandidateIds(spaceIds, candidateOffset, candidateLimit)
        if (cand.isEmpty()) return new ArrayList<Long>()

        final int ID_LIST_MAX = 1000
        Set<Long> hits = new LinkedHashSet<Long>()

        DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
            if (SQL_TIMEOUT_SECONDS > 0) sql.execute('SET statement_timeout = ' + (SQL_TIMEOUT_SECONDS * 1000))

            // ---- phase 1: current bodies, one indexed probe per page --------
            for (int i = 0; i < cand.size(); i += ID_LIST_MAX) {
                List<Long> part = cand.subList(i, Math.min(i + ID_LIST_MAX, cand.size()))
                Map<String, Object> p = new LinkedHashMap<String, Object>()
                String q = 'SELECT bcc.contentid AS pid FROM bodycontent bcc ' +
                           'WHERE bcc.contentid IN (' + joinIds(part) + ') ' +
                           'AND (' + macroMatchClause(sourceNames, 'bcc', 'c', p) + ')'
                sql.eachRow(q, p) { row -> hits.add(((Number) row['pid']).longValue()) }
            }

            // ---- phase 2: history of the pages phase 1 did not decide -------
            // Join-free ON PURPOSE. The earlier shape joined content to
            // bodycontent inside one statement; that gives the planner the
            // option of an instance-wide bodycontent scan + hash join, and on
            // some spaces' statistics it takes it (observed: flat ~2.5 min per
            // chunk on BC while OKR ran the same statement in under a second).
            // Split into (a) history version ids from content alone - no
            // bodies, nothing worth flipping for - and (b) body probes by
            // PRIMARY KEY id list, the same provably stable shape as phase 1.
            // I/O is identical to the well-planned join: every history body of
            // every undecided page is read exactly once.
            if (UPDATE_HISTORICAL_VERSIONS) {
                List<Long> rest = new ArrayList<Long>()
                for (Long id : cand) if (!hits.contains(id)) rest.add(id)

                // (a) which content rows are these pages' historical versions
                Map<Long, Long> versionToPage = new LinkedHashMap<Long, Long>()
                for (int i = 0; i < rest.size(); i += ID_LIST_MAX) {
                    List<Long> part = rest.subList(i, Math.min(i + ID_LIST_MAX, rest.size()))
                    String q = 'SELECT v.contentid AS vid, v.prevver AS pid FROM content v ' +
                               'WHERE v.prevver IN (' + joinIds(part) + ') ' +
                               (INCLUDE_DRAFT_PAGES ? '' : "AND v.content_status <> 'draft'")
                    sql.eachRow(q, [:]) { row ->
                        versionToPage.put(((Number) row['vid']).longValue(),
                                ((Number) row['pid']).longValue())
                    }
                }

                // (b) probe those versions' bodies by primary key
                List<Long> histIds = new ArrayList<Long>(versionToPage.keySet())
                for (int i = 0; i < histIds.size(); i += ID_LIST_MAX) {
                    List<Long> part = histIds.subList(i, Math.min(i + ID_LIST_MAX, histIds.size()))
                    Map<String, Object> p = new LinkedHashMap<String, Object>()
                    String q = 'SELECT bch.contentid AS vid FROM bodycontent bch ' +
                               'WHERE bch.contentid IN (' + joinIds(part) + ') ' +
                               'AND (' + macroMatchClause(sourceNames, 'bch', 'h', p) + ')'
                    sql.eachRow(q, p) { row ->
                        Long pid = versionToPage.get(((Number) row['vid']).longValue())
                        if (pid != null) hits.add(pid)
                    }
                }
            }
        }

        List<Long> ids = new ArrayList<Long>(hits)
        Collections.sort(ids)
        return ids
    } catch (Exception e) {
        throw new RuntimeException('resolveScope failed: ' + e.getMessage(), e)
    }
}

/**
 * SCOPE mode: the affected pages, from the database alone.
 *
 * No Page objects are loaded - discovery decides per page from body probes
 * that stop at the first matching version (see resolveScope), then only title
 * and space are fetched for display. Its cost is proportional to the bodies
 * that must be read before each page is decided, NOT to what INSPECT does
 * afterwards: INSPECT additionally loads and parses every version of every
 * affected page through the API.
 *
 * candidateOffset/candidateLimit slice the candidate pages inside discovery -
 * the escape hatch for spaces where even discovery exceeds the SQL timeout.
 *
 * Rows: [spaceKey, pageId, title]
 */
List<List<String>> scopeReport(Set<String> sourceNames, int candidateOffset, int candidateLimit) {
    try {
        // reuse discovery, then fetch only the three columns needed for display
        List<Long> pageIds = resolveScope(sourceNames, candidateOffset, candidateLimit)
        List<List<String>> out = new ArrayList<List<String>>()
        if (pageIds.isEmpty()) return out

        Map<Integer, String> spaceKeyById = new LinkedHashMap<Integer, String>()
        String resource = DB_RESOURCE
        DatabaseUtil.withSql(resource) { Sql sql ->
            sql.eachRow('SELECT spaceid, spacekey FROM spaces') { row ->
                spaceKeyById.put(((Number) row['spaceid']).intValue(), row['spacekey'] as String)
            }
        }

        // chunked IN list: one query per 1000 ids, each a primary-key lookup.
        // A single IN with 20k values makes the planner give up on the index.
        int chunk = 1000
        int timeoutMs = SQL_TIMEOUT_SECONDS * 1000
        for (int from = 0; from < pageIds.size(); from += chunk) {
            int to = Math.min(from + chunk, pageIds.size())
            List<Long> slice = pageIds.subList(from, to)
            List<String> ph = new ArrayList<String>()
            Map<String, Object> params = new LinkedHashMap<String, Object>()
            for (int i = 0; i < slice.size(); i++) {
                ph.add(':p' + i)
                params.put('p' + i, slice.get(i))
            }
            String query = 'SELECT contentid, title, spaceid FROM content WHERE contentid IN (' +
                           ph.join(', ') + ')'
            DatabaseUtil.withSql(resource) { Sql sql ->
                if (timeoutMs > 0) sql.execute('SET statement_timeout = ' + timeoutMs)
                sql.eachRow(query, params) { row ->
                    Object sid = row['spaceid']
                    String sk = (sid == null) ? '' : spaceKeyById.get(((Number) sid).intValue())
                    out.add([sk == null ? '' : sk, row['contentid'] as String, row['title'] as String])
                }
            }
        }
        Collections.sort(out, new Comparator<List<String>>() {
            @Override int compare(List<String> a, List<String> b) {
                int c = (a.get(0) <=> b.get(0))
                return c != 0 ? c : (a.get(2) <=> b.get(2))
            }
        })
        return out
    } catch (Exception e) {
        throw new RuntimeException('scopeReport failed: ' + e.getMessage(), e)
    }
}

/**
 * Scans one body. Counts EVERY macro for macroIndex - containers and macros not
 * being migrated included - and records only those whose ac:name matches a
 * migration source. Returns plain-value findings that survive a session flush.
 *
 * One pass: findMacroSpans tokenises the body once and yields every macro at
 * every nesting depth; there is no re-scan per migration.
 */
List<MatchedMacro> scanBody(String body, Map<String, MigrationDef> bySource) {
    try {
        List<MatchedMacro> out = new ArrayList<MatchedMacro>()
        if (body == null) return out
        int macroIndex = 0, matchedIndex = 0
        List<MacroSpan> spans = findMacroSpans(body)
        for (MacroSpan sp : spans) {
            macroIndex++
            MigrationDef mig = bySource.get(sp.name)
            if (mig == null) continue
            Map<String, String> spParams = paramsOfSpan(body, sp)
            // v3: EDD source - one ac:name serves every EDD set; only OUR
            // set's occurrences belong to this migration
            if (mig.sourceType == MacroType.EddStatusMacro
                    && !mig.setId.equals(spParams.get('set-id'))) continue
            matchedIndex++
            MatchedMacro mm = new MatchedMacro()
            mm.migrationId = mig.id
            mm.sourceName = sp.name
            mm.sourceType = mig.sourceType
            mm.targetName = mig.targetName
            mm.targetType = mig.targetType
            mm.macroId = sp.macroId
            mm.macroIndex = macroIndex
            mm.matchedIndex = matchedIndex
            mm.params.putAll(spParams)
            out.add(mm)
        }
        return out
    } catch (Exception e) {
        throw new RuntimeException('scanBody failed: ' + e.getMessage(), e)
    }
}

/** Version content ids of a page: current first, then history when enabled. */
List<Long> versionContentIds(PageManager pageManager, AbstractPage page) {
    try {
        List<Long> ids = new ArrayList<Long>()
        ids.add(page.getId())
        if (!UPDATE_HISTORICAL_VERSIONS) return ids
        List<VersionHistorySummary> history = pageManager.getVersionHistorySummaries(page)
        for (VersionHistorySummary vhs : history) {
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

/*
 * Returns null only when the parameter is ABSENT from the page and has no
 * default. A parameter present but empty - Confluence folds a whitespace-only
 * value into <ac:parameter ac:name="x"/> - is an explicit empty value, not a
 * missing one, so it resolves to "" rather than falling through to a default.
 */
String resolveValue(MigrationDef mig, Map<String, String> onPage, String key) {
    if (onPage.containsKey(key)) {
        String onPageVal = onPage.get(key)
        if (onPageVal != null && !onPageVal.trim().isEmpty()) return onPageVal.trim()
        return ''                       // explicitly empty on the page
    }
    String v = mig.paramDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    v = mig.discoveredDefaults.get(key)
    if (v != null && !v.trim().isEmpty()) return v.trim()
    return null
}

/** "a=1, b=2" - used in the Details column. */
String renderKeyValues(Map<String, String> m) {
    if (m == null || m.isEmpty()) return ''
    List<String> parts = new ArrayList<String>()
    for (Map.Entry<String, String> e : m.entrySet()) parts.add(e.getKey() + '=' + e.getValue())
    return parts.join(', ')
}

/** Readable rendering of parsed parameters for error messages. */
String describeParams(Map<String, String> params) {
    if (params == null || params.isEmpty()) return '(none)'
    List<String> parts = new ArrayList<String>()
    for (Map.Entry<String, String> e : params.entrySet()) {
        String v = e.getValue()
        parts.add(e.getKey() + '=' + (v == null ? '(null)' : (v.isEmpty() ? '(empty)' : '"' + v + '"')))
    }
    return parts.join(', ')
}

String replaceEddStatus(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        String srcVal = resolveValue(mig, mm.params, mig.sourceParam)
        if (srcVal == null) {
            throw new IllegalStateException('parameter "' + mig.sourceParam +
                    '" is absent from the page and has no declared default; parsed from this macro: ' +
                    describeParams(mm.params))
        }
        // stale spellings first: pages saved before a value was renamed in the
        // macro still carry the old text - map it to today's canonical value
        String canonVal = srcVal
        if (mig.valueAliases.containsKey(srcVal)) {
            canonVal = mig.valueAliases.get(srcVal)
            notes.add('alias: "' + srcVal + '" -> "' + canonVal + '" (' + mig.id + ')')
        }
        String optionId = mig.options.get(canonVal)
        if (optionId == null) {
            throw new IllegalStateException('value ' + (canonVal.isEmpty() ? '(empty)' : '"' + canonVal + '"') +
                    (canonVal == srcVal ? '' : ' (via alias from "' + srcVal + '")') +
                    ' has no option-id in set "' + mig.setName + '". Known values: ' + mig.options.keySet() +
                    '. Aliases checked: ' + mig.valueAliases.keySet())
        }
        Map<String, String> out = new LinkedHashMap<String, String>()
        out.putAll(mig.staticParams)
        out.put('set-id', mig.setId)
        out.put('current-option-value', canonVal)
        out.put('option-id', optionId)
        String macroId = UUID.randomUUID().toString()
        // the matched key IS the target option name, so showing it in braces
        // makes a wrong or renamed mapping visible at a glance
        String optionName = null
        for (String k : mig.options.keySet()) { if (k == canonVal) optionName = k }
        /*
         * Source enum values are stored as their text, so the value needs no
         * lookup to be readable. A required parameter ABSENT from page storage
         * means the author left it at the macro default - marked, because the
         * value shown then comes from the definition and not from the page.
         */
        mm.detail = mig.sourceParam + '="' + srcVal + '"' +
                    (mm.params.containsKey(mig.sourceParam) ? '' : ' [macro default]') +
                    ' -> option-id=' + optionId + ' (' + (optionName == null ? '?' : optionName) + ')' +
                    ' in set "' + mig.setName + '"'
        if (TRACE_MAPPING) notes.add('  ' + mm.detail + ' macro-id=' + macroId)
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

        // Mapped parameters go through default resolution, so a parameter the
        // author left at its default - and which is therefore absent from page
        // storage - still reaches the target instead of silently disappearing.
        for (Map.Entry<String, String> e : mig.paramMap.entrySet()) {
            String v = resolveValue(mig, mm.params, e.getKey())
            // v3: numeric contract - the SR target takes a number and declares
            // no enum, so this is the only pre-write validation possible
            if (v != null && mig.sourceValuesNumeric && e.getKey().equals(mig.sourceParam)) {
                try {
                    new BigDecimal(v.trim().replace(',', '.'))
                } catch (NumberFormatException nfe) {
                    throw new IllegalStateException('value "' + v + '" of "' + e.getKey() +
                            '" is not numeric (source declares valuesNumeric: true)')
                }
            }
            if (v != null) out.put(e.getValue(), v)
        }
        for (Map.Entry<String, String> e : mm.params.entrySet()) {
            if (mig.paramMap.containsKey(e.getKey())) continue      // already handled
            if (mig.dropUnmapped) continue
            out.put(e.getKey(), e.getValue())
        }
        String macroId = UUID.randomUUID().toString()
        // "params=[:]" said nothing; name what happened, and list parameters
        // only when there are any
        // Source and target names, and their types, are already their own columns -
        // repeating them here only crowds the cell. A parameterless replacement
        // has nothing to report, so its Details cell stays empty.
        mm.detail = out.isEmpty() ? ''
                : 'Replaced with ' + mig.targetName + '. Parameters: ' + renderKeyValues(out) + '.'
        if (TRACE_MAPPING) notes.add('  ' + mm.sourceName + ' -> ' + mig.targetName + ' ' + mm.detail)
        return buildMacroElement(mig.targetName, mig.targetSchemaVersion, macroId, out)
    } catch (Exception e) {
        throw new RuntimeException('replaceGenericMacro failed: ' + e.getMessage(), e)
    }
}

/**
 * Works out aura-button's hrefType/href pair from a link-button URL.
 * Returns [hrefType, href].
 *
 *   /download/attachments/<id>/...      -> ['attachment', <id>]
 *   ...pageId=<id>                      -> ['page', <id>]
 *   <base>/display/<SPACE>/<Title>      -> ['page', <resolved id>] when the page
 *                                          resolves, otherwise ['link', url]
 *   anything else                       -> ['link', url]
 *
 * Only URLs on this instance are treated as internal; an external URL that
 * happens to contain /display/ stays a plain link.
 */
/*
 * ALWAYS ['link', <original url>].
 *
 * aura-button also supports hrefType=page/attachment with an id in href, and an
 * earlier version derived those from the URL. That is deliberately NOT done:
 * Atlassian's Cloud migration rewrites URLs to point at Cloud resources, but a
 * stored page id or attachment id would keep pointing at the old DC resource
 * and silently break after migration. Preserving the full URL keeps the link
 * inside the machinery that fixes it.
 */
List<String> resolveAuraHref(String url, List<String> notes) {
    try {
        if (url == null) return ['link', '']
        return ['link', url.trim()]
    } catch (Exception e) {
        throw new RuntimeException('resolveAuraHref("' + url + '") failed: ' + e.getMessage(), e)
    }
}

/**
 * link-button -> aura-button.
 *
 * The style parameters come from the migration's staticParams, so every
 * replaced button gets one standard look that can be adjusted in config before
 * a run. Only href and label are per-instance, taken from the source macro -
 * falling back to its declared defaults when the author left them untouched
 * (link-button's "url" defaults to a real URL, so an absent parameter is
 * meaningful rather than empty).
 *
 * Every paramMap entry is treated as required: a button written without an
 * href or a label is broken output, so an unresolvable value fails the
 * occurrence rather than producing one.
 */
String replaceAuraButton(MigrationDef mig, MatchedMacro mm, List<String> notes) {
    try {
        Map<String, String> out = new LinkedHashMap<String, String>()
        out.putAll(mig.staticParams)

        List<String> missing = new ArrayList<String>()
        String rawHref = null
        boolean emptyLabel = false
        for (Map.Entry<String, String> e : mig.paramMap.entrySet()) {
            String v = resolveValue(mig, mm.params, e.getKey())
            if (v == null) { missing.add(e.getKey()); continue }
            if (e.getValue() == 'href') {
                // an empty href would produce a button that links nowhere
                if (v.trim().isEmpty()) { missing.add(e.getKey()); continue }
                rawHref = v
            } else {
                // an empty label is legitimate - a whitespace-only button text
                // is stored as <ac:parameter ac:name="buttontext"/>
                if (e.getValue() == 'label' && v.isEmpty()) emptyLabel = true
                out.put(e.getValue(), v)
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(plural(missing.size(), 'parameter') + ' ' + missing + ' absent from the page ' +
                    'and have no declared default; parsed from this macro: ' + describeParams(mm.params))
        }

        // hrefType is derived per instance and must NOT come from staticParams:
        // an external URL, a Confluence page and an attachment need different
        // values, and href itself becomes an id for the latter two.
        List<String> resolved = resolveAuraHref(rawHref, notes)
        out.put('hrefType', resolved.get(0))
        out.put('href', resolved.get(1))

        String macroId = UUID.randomUUID().toString()
        mm.detail = 'Replaced with ' + mig.targetName + '. hrefType=' + resolved.get(0) +
                    ', href=' + resolved.get(1) + ', label="' + out.get('label') + '"' +
                    (emptyLabel ? ' (empty label preserved)' : '') + '.'
        if (TRACE_MAPPING) notes.add('  aura-button ' + mm.detail + ' macro-id=' + macroId)
        return buildMacroElement(mig.targetName, mig.targetSchemaVersion, macroId, out)
    } catch (IllegalStateException ise) {
        throw ise
    } catch (Exception e) {
        throw new RuntimeException('replaceAuraButton failed: ' + e.getMessage(), e)
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

        List<String> defaulted = new ArrayList<String>()
        for (String key : required) {
            String v = resolveValue(mig, mm.params, key)
            if (v == null || !(v ==~ /\d+/)) { missing.add(key); continue }
            if (!mm.params.containsKey(key)) defaulted.add(key)
            resolved.put(key, v)
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException('unresolved or non-numeric ' + plural(missing.size(), 'parameter') + ' ' + missing +
                    '; parsed from this macro: ' + mm.params)
        }

        int relevance = Integer.parseInt(resolved.get('relevance'))
        int sum = 0
        for (List<String> c : QM_COLUMNS) sum += Integer.parseInt(resolved.get(c.get(1)))
        int pct = ((100 * sum * relevance).intdiv(135)) as int

        String tblAttr = QM_CONFLUENCE_TABLE_CLASSES ? ' class="confluenceTable"' : ''
        String thAttr  = QM_CONFLUENCE_TABLE_CLASSES ? ' class="confluenceTh"' : ''
        String tdAttr  = QM_CONFLUENCE_TABLE_CLASSES ? ' class="confluenceTd"' : ''

        StringBuilder headers = new StringBuilder(), values = new StringBuilder()
        for (List<String> c : QM_COLUMNS) {
            headers.append('<th').append(thAttr).append('>').append(xmlText(c.get(0))).append('</th>')
            values.append('<td').append(tdAttr).append('>').append(xmlText(resolved.get(c.get(1)))).append('</td>')
        }
        StringBuilder h = new StringBuilder()
        h.append('<p>Qualifikationsfaktor: ').append(pct).append('%</p>')
        h.append('<table').append(tblAttr).append('><tbody>')
        h.append('<tr><th').append(thAttr).append('>Bedeutung</th>')
         .append('<th').append(thAttr).append(' colspan="9">Auswirkung</th></tr>')
        h.append('<tr><th').append(thAttr).append('>&nbsp;</th>').append(headers).append('</tr>')
        // Row 3 first cell holds relevance, under the "Bedeutung" header - this
        // matches the original macro's rendering. The EMPTY cell under Bedeutung
        // is the one in the second header row above it.
        h.append('<tr><td').append(tdAttr).append('>').append(relevance).append('</td>')
         .append(values).append('</tr>')
        h.append('</tbody></table>')
        h.append('<p>&nbsp;</p>')
        h.append('<p>').append(pct).append('%</p>')
        // every parameter, including ones absent from page storage that were
        // resolved from the macro definition's defaults - those carry *
        List<String> shown = new ArrayList<String>()
        for (String key : required) {
            shown.add(key + '=' + resolved.get(key) + (defaulted.contains(key) ? '*' : ''))
        }
        mm.detail = 'Replaced with static table. Parameters: ' + shown.join(', ') +
                    (defaulted.isEmpty() ? '' : ' (* = absent from page, taken from macro default)') +
                    '. Qualifikationsfaktor: ' + pct + '%.'
        if (TRACE_MAPPING) notes.add('  ' + mm.detail)
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
        if (mig.targetType == MacroType.AuraLinkButton)            return replaceAuraButton(mig, mm, notes)
        if (mig.targetType == MacroType.ScriptRunnerMacro ||
            mig.targetType == MacroType.UserMacro)                 return replaceGenericMacro(mig, mm, notes)
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
        int cursor = 0
        int skipUntil = -1

        List<MacroSpan> spans = findMacroSpans(body)
        for (MacroSpan sp : spans) {
            // a macro nested inside one already replaced no longer exists
            if (sp.start < skipUntil) continue
            if (sp.start < cursor) continue

            MigrationDef mig = bySource.get(sp.name)
            if (mig == null) continue                       // container or unrelated macro
            List<MatchedMacro> queue = pending.get(sp.macroId)
            if (queue == null || queue.isEmpty()) continue   // not planned
            MatchedMacro mm = queue.remove(0)

            String replacement
            // Declared OUTSIDE the try: it is read again after the catch, and a
            // variable declared inside a try block is not in scope after it.
            MatchedMacro fresh = new MatchedMacro()
            try {
                // parameters as they are NOW, not as scanned in Stage-1
                fresh.migrationId = mm.migrationId; fresh.sourceName = sp.name; fresh.macroId = sp.macroId
                fresh.sourceType = mm.sourceType;   fresh.targetType = mm.targetType
                fresh.targetName = mm.targetName
                fresh.params.putAll(paramsOfSpan(body, sp))
                replacement = replaceMacro(mig, fresh, notes)
            } catch (IllegalStateException ise) {
                if (ON_MISSING == 'FAIL') throw ise
                mm.status = ReplacementStatus.Skipped
                mm.message = ise.getMessage()
                mig.occSkipped++
                notes.add('  SKIPPED macro-id=' + sp.macroId + ': ' + ise.getMessage())
                continue                                     // leave untouched
            }

            int elemStart = sp.start, elemEnd = sp.end
            String pre = body.substring(cursor, elemStart)

            /*
             * Paragraph unwrapping, needed when the replacement is a block
             * element such as a <table>, which may not sit inside a <p>.
             * Only consumed when the paragraph holds this macro and nothing
             * else - so a macro sharing a paragraph with text, or with another
             * macro, leaves the paragraph intact. Works at any nesting depth,
             * including a <p> inside an <ac:rich-text-body>.
             */
            if (mig.unwrapParagraph) {
                String preTrimmed = pre.replaceAll('\\s+$', '')
                boolean opensP = preTrimmed.endsWith('<p>')
                Matcher closes = Pattern.compile('^\\s*</p>').matcher(body.substring(elemEnd))
                if (opensP && closes.find()) {
                    pre = preTrimmed.substring(0, preTrimmed.length() - 3)
                    elemEnd = elemEnd + closes.end()
                }
            }

            outBody.append(pre).append(replacement)
            cursor = elemEnd
            skipUntil = sp.end
            mm.detail = fresh.detail                    // what the transform did
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

/*
 * Error classes that do NOT terminate the run - the version is recorded as
 * failed and the run continues. Every other exception is unexpected and
 * terminates immediately: discovering a systematic fault on the last
 * iteration of a long run is worse than failing on the first.
 *
 * 1. Space-dereference NPE: some page versions have no space row, and
 *    Confluence's own save path dereferences the space - the NPE comes from
 *    inside the product, not from this script.
 * 2. ExternalChangesException ("unreconciled page"): the collaborative-editing
 *    guard. The page has a Synchrony shared draft that was never published or
 *    discarded (common on production copies), and Confluence refuses
 *    programmatic saves to its CURRENT version until the draft is resolved -
 *    deliberately, so a script cannot clobber someone's in-flight edit.
 *    Retries cannot fix it (the draft persists). POLICY: drafts are never
 *    touched by this migration - such pages are reported as Failed with the
 *    reason in the Details column and the run continues. Detected by class
 *    name/message, not import, so the script compiles on versions where the
 *    class moved packages.
 */
boolean isTolerableError(Throwable t) {
    Throwable c = t
    while (c != null) {
        if (c instanceof NullPointerException) {
            String m = c.getMessage() == null ? '' : c.getMessage()
            if (m.contains('confluence.spaces.Space') || m.contains('getSpace()')) return true
        }
        String cls = c.getClass().getName()
        String msg = c.getMessage() == null ? '' : c.getMessage()
        if (cls.contains('ExternalChangesException') || msg.contains('unreconciled page')) return true
        // 3. duplicate title: two CURRENT pages share a title in the space -
        //    pre-existing data defect; Confluence refuses to save either
        //    until one is retitled. Expected on production copies.
        if (msg.contains('already exists with the title')) return true
        c = c.getCause()
    }
    return false
}

/** Actionable failure text for the unreconciled-page case. */
String tolerableErrorHint(Throwable t) {
    Throwable c = t
    while (c != null) {
        String cls = c.getClass().getName()
        String msg = c.getMessage() == null ? '' : c.getMessage()
        if (msg.contains('already exists with the title')) {
            return ' | CAUSE: duplicate page title - another current page in this space has the ' +
                   'same title, so Confluence refuses to save this one. Retitle one of the pair, ' +
                   'then re-run.'
        }
        if (cls.contains('ExternalChangesException') || msg.contains('unreconciled page')) {
            return ' | CAUSE: page is locked for writing - it holds unpublished in-editor ' +
                   'changes (a collaborative-editing draft). By policy drafts are never touched; ' +
                   'the current version was left as is and will migrate on a re-run once the ' +
                   'draft is published or discarded.'
        }
        c = c.getCause()
    }
    return ''
}

void writeCurrentVersion(PageManager pm, AbstractPage page, String newBody) {
    try {
        if (CURRENT_CREATES_NEW_VERSION) {
            final String b = newBody
            Modification<AbstractPage> mod = new Modification<AbstractPage>() {
                @Override void modify(AbstractPage target) { target.setBodyAsString(b) }
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

void writeHistoricalVersion(PageManager pm, ContentEntityObject hist, String newBody, long currentPageId) {
    try {
        /*
         * Historical rows on this instance may carry NULL spaceid - the same
         * inconsistency that blinded the old discovery. Confluence's own save
         * path dereferences entity.getSpace(), so such rows NPE from inside
         * the product. Hydrate the space from the CURRENT page (ownership,
         * the only reliable source) before saving. Side effect: the save then
         * persists the spaceid onto the row - the exact value Confluence
         * itself would have written - quietly repairing the inconsistency on
         * every version this migration touches anyway.
         */
        if (hist instanceof SpaceContentEntityObject && ((SpaceContentEntityObject) hist).getSpace() == null) {
            AbstractPage cur = pm.getAbstractPage(currentPageId)
            Space owner = (cur == null) ? null : cur.getSpace()
            if (owner != null) ((SpaceContentEntityObject) hist).setSpace(owner)
        }
        Date keep = hist.getLastModificationDate()
        hist.setBodyAsString(newBody)
        hist.setLastModificationDate(keep)
        /*
         * Saved through the BASE ContentEntityManager, not PageManager.
         * PageManager.saveContentEntity() runs CURRENT-page validations on
         * whatever it is given; on a historical row those are wrong twice
         * over - with NULL space the validation chain NPEs (the v1 crashes),
         * and with the space hydrated it proceeds to the duplicate-title
         * check and rejects the row because its old title exists on a live
         * page ("A page already exists with the title..."). History is not a
         * page being created; the base manager persists the entity without
         * page-level validation, events still suppressed via the SaveContext.
         */
        ContentEntityManager cem =
                (ContentEntityManager) ContainerManager.getComponent('contentEntityManager')
        cem.saveContentEntity(hist, new DefaultSaveContext(true, false, HISTORICAL_SUPPRESS_EVENTS))
    } catch (Exception e) {
        throw new RuntimeException('writeHistoricalVersion failed: ' + e.getMessage(), e)
    }
}

/*
 * COLUMNS ARE DEFINED ONCE, HERE.
 *
 * TABLE and CSV previously each carried their own hand-written column list, so
 * every column change had to be made in four places and they drifted apart -
 * different names, different order, missing columns. Both renderers now read
 * these two functions, so the two outputs cannot disagree.
 *
 * The only presentation difference: in TABLE the Page URL cell renders as a
 * link labelled "open"; CSV carries the full URL. Same column, same position.
 */
@Field int URL_COLUMN_INDEX = 3

List<String> resultHeaders(boolean perMacro, boolean showMigration) {
    List<String> h = new ArrayList<String>()
    h.addAll(['Space Key', 'Page ID', 'Page Name', 'Page URL', 'Page V.', 'PageType'])
    if (perMacro) {
        h.add('Macro #')
        if (showMigration) h.add('Migration')
        h.addAll(['Source', 'Source Type', 'Target', 'Target Type', 'ac:macro-id',
                  'Status', 'Details', 'Comments'])
    } else {
        h.addAll(['Occurrences', 'Replaced', 'Skipped', 'Failed', 'Status'])
    }
    return h
}

/** One row, in the same order as resultHeaders. mm is null for VERSION rows. */
List<String> resultRow(boolean perMacro, boolean showMigration, boolean apply,
                       PageFinding pf, VersionFinding vf, MatchedMacro mm,
                       int rc, int skc, int flc, Map<String, MigrationDef> byId) {
    List<String> c = new ArrayList<String>()
    c.add(pf.spaceKey)
    c.add(pf.pageId as String)
    c.add(pf.pageName)
    c.add(pf.url)
    c.add(vf.versionNumber as String)
    c.add(pf.kind + ' (' + (vf.isDraft ? 'draft' : (vf.isCurrent ? 'current' : 'history')) + ')')
    if (perMacro) {
        c.add(mm.macroIndex as String)
        if (showMigration) c.add(mm.migrationId)
        c.add(mm.sourceName)
        c.add(shortType(mm.sourceType))
        c.add(targetLabelFor(byId, mm))
        c.add(shortType(mm.targetType))
        c.add(mm.macroId)
        c.add((!apply && mm.status == ReplacementStatus.Success)
                ? 'Would replace' : (mm.status as String))
        c.add((mm.status == ReplacementStatus.Success) ? mm.detail : mm.message)
        c.add('')                              // Comments - for your own notes
    } else {
        c.add(vf.matchedMacros.size() as String)
        c.add(rc as String)
        c.add(skc as String)
        c.add(flc as String)
        c.add(vf.status as String)
    }
    return c
}

String csvLine(List<String> cells) {
    List<String> q = new ArrayList<String>()
    for (String v : cells) q.add('"' + (v == null ? '' : v.replace('"', '""')) + '"')
    return q.join(',') + '\n'
}

/** CSV header derived from the SAME list the table uses - never hand-written. */
String csvHeaderLine(List<String> headers) {
    List<String> q = new ArrayList<String>()
    for (String h : headers) {
        String n = h.toLowerCase().replace('ac:macro-id', 'macro_id')
        n = n.replace(' ', '_').replace('.', '').replace('#', 'index')
        q.add(n)
    }
    return q.join(',') + '\n'
}

/** Compact type label for the results table. */
String shortType(MacroType t) {
    if (t == null) return '-'
    if (t == MacroType.ScriptRunnerMacro) return 'SR'
    if (t == MacroType.UserMacro) return 'UM'
    if (t == MacroType.EddStatusMacro) return 'EDD'
    if (t == MacroType.AuraLinkButton) return 'AB'
    if (t == MacroType.Static_QualificationTable) return 'Static'
    return t as String
}

/** User-facing type name for the legend. */
String longType(MacroType t) {
    if (t == MacroType.ScriptRunnerMacro) return 'ScriptRunner Macro'
    if (t == MacroType.UserMacro) return 'User Macro'
    if (t == MacroType.EddStatusMacro) return 'EasyDropDown Status Macro'
    if (t == MacroType.AuraLinkButton) return 'Aura Link Button'
    if (t == MacroType.Static_QualificationTable) return 'Static Qualification Table (computed static data table object)'
    return t as String
}

String typeList(List<MacroType> types) {
    List<String> parts = new ArrayList<String>()
    for (MacroType t : types) parts.add('<b>' + shortType(t) + '</b> - ' + longType(t))
    return parts.join(', ')
}

/**
 * Column legend, printed once above the results table. Rows follow the exact
 * column order of that table, so the two can be read side by side.
 */
String legendHtml(boolean showMigration, boolean apply) {
    // only the types this engine can actually replace FROM and TO
    List<MacroType> sourceTypes = [MacroType.UserMacro, MacroType.ScriptRunnerMacro]
    List<MacroType> targetTypes = [MacroType.ScriptRunnerMacro, MacroType.EddStatusMacro,
                                   MacroType.AuraLinkButton, MacroType.Static_QualificationTable]

    StringBuilder b = new StringBuilder()
    b.append('<h3>Legend Table:</h3>')
    b.append('<table border="1" cellpadding="4" cellspacing="0" style="font-size:90%">')
    b.append('<tr><th>Column</th><th>Meaning</th></tr>')
    b.append('<tr><td>Space Key</td><td>Key of the space the page belongs to. Read once from the ')
     .append('current version - historical version rows do not carry a space.</td></tr>')
    b.append('<tr><td>Page ID</td><td>Page id in scope of macro replacement where particular page ')
     .append('versions contain macros planned for replacement.</td></tr>')
    b.append('<tr><td>Page Name</td><td>Title of the page.</td></tr>')
    b.append('<tr><td>Page URL</td><td>Link to the page. Shown as "open" in the table; CSV export ')
     .append('carries the full URL.</td></tr>')
    b.append('<tr><td>Page V.</td><td>Page Version which contains macros planned for replacement.</td></tr>')
    b.append('<tr><td>Current</td><td>Indicates whether particular page version is current (latest) ')
     .append('version.</td></tr>')
    b.append('<tr><td>Macro #</td><td>Position of macro occurrence within all macros on particular ')
     .append('page version (including rich-text formatting macros and other macros not planned for ')
     .append('migration).</td></tr>')
    b.append('<tr><td>Migration</td><td>Id of the migration configuration entry that matched this ')
     .append('occurrence. Optional column, shown when RESULT_SHOW_MIGRATION_COLUMN is set to true')
     .append(showMigration ? '' : ' (currently disabled)').append('.</td></tr>')
    b.append('<tr><td>Source</td><td>Macro being replaced.</td></tr>')
    b.append('<tr><td>Source Type</td><td>Type of the macro being replaced. ')
     .append(typeList(sourceTypes)).append('.</td></tr>')
    b.append('<tr><td>Target</td><td>Replacement macro. For EasyDropDown the configured set name is ')
     .append('shown, because the macro name is identical for every set.</td></tr>')
    b.append('<tr><td>Target Type</td><td>Type of the replacement. ')
     .append(typeList(targetTypes)).append('.</td></tr>')
    b.append('<tr><td>ac:macro-id</td><td>Guid of the source macro occurrence as stored in the page ')
     .append('source. Not unique across pages - copying a page duplicates it.</td></tr>')
    b.append('<tr><td>Status</td><td>Macro occurrence replacement status. ')
    if (apply) b.append('<b>Success</b> - replacement successful, ')
    else b.append('<b>Would replace</b> - estimated successful replacement in INSPECT mode, ')
    b.append('<b>Skipped</b> - untouched on purpose or unhandled configuration detected (reason in ')
     .append('Details column), <b>Failed</b> - error during replacement attempts or write did not ')
     .append('persist.</td></tr>')
    b.append('<tr><td>Details</td><td>Replaced macro details or reason of skip or failure.</td></tr>')
    b.append('<tr><td>Comments</td><td>Admin work notes.</td></tr>')
    b.append('</table>')
    return b.toString()
}

/** What to show in the Target column: the EDD set, or the target macro name. */
String targetLabelFor(Map<String, MigrationDef> byId, MatchedMacro mm) {
    try {
        MigrationDef md = byId.get(mm.migrationId)
        if (md != null && md.targetType == MacroType.EddStatusMacro &&
            md.setName != null && !md.setName.trim().isEmpty()) {
            return md.setName
        }
        if (mm.targetName == null || mm.targetName.trim().isEmpty()) return '(static)'
        return mm.targetName
    } catch (Exception e) {
        throw new RuntimeException('targetLabelFor failed: ' + e.getMessage(), e)
    }
}

// =============================================================================
//  MAIN
// =============================================================================

long runStart = System.currentTimeMillis()
StringBuilder outp = new StringBuilder()
StringBuilder rollbackPlain = new StringBuilder()      // entries with uncompressed BEFORE body
StringBuilder rollbackComp = new StringBuilder()       // entries with compressed BEFORE body
// declared here, not inside the try: the fatal catch prints them too
int rollbackPlainCount = 0
int rollbackCompCount = 0
StringBuilder results = new StringBuilder()
// Declared out here so the fatal handler can close and emit whatever the run
// produced before it stopped: partial results are the record of what completed.
StringBuilder csvBody = new StringBuilder()
boolean resultsTableOpen = false
int resultRowCount = 0
int resultRowsTruncated = 0
int rowsInChunk = 0
boolean legendEmitted = false

try {
    if (MODE != 'SCOPE' && MODE != 'INSPECT' && MODE != 'APPLY') {
        throw new IllegalStateException('MODE must be SCOPE, INSPECT or APPLY')
    }
    if (ON_MISSING != 'SKIP' && ON_MISSING != 'FAIL') throw new IllegalStateException('ON_MISSING must be SKIP or FAIL')

    boolean apply = (MODE == 'APPLY')
    PageManager pageManager = ComponentLocator.getComponent(PageManager)
    SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager)
    String baseUrl = ''
    try { baseUrl = ComponentLocator.getComponent(SettingsManager).getGlobalSettings().getBaseUrl() }
    catch (Throwable t) { baseUrl = '' }
    BASE_URL = baseUrl

    // ---- select migrations ------------------------------------------------
    List<MigrationDef> migrations = new ArrayList<MigrationDef>()
    List<String> knownIds = new ArrayList<String>()
    for (Object cfgObj : MIGRATIONS) {
        Map<String, Object> cfg = (Map<String, Object>) cfgObj
        MigrationDef md = toMigration(cfg)
        knownIds.add(md.id)
        if (RUN.isEmpty() || RUN.contains(md.id)) migrations.add(md)
    }
    if (migrations.isEmpty()) throw new IllegalStateException('RUN matched no migration ids. Known: ' + knownIds)

    outp.append('MODE: ').append(MODE).append(apply ? '   *** WRITES ENABLED ***' : '   (read only)').append('\n')
    outp.append('Migrations (').append(migrations.size()).append('):\n')
    int migIdW = 0
    for (MigrationDef d : migrations) {
        int l = ('[' + d.id + ']').length()
        if (l > migIdW) migIdW = l
    }
    for (MigrationDef d : migrations) {
        outp.append('    ')
            .append(String.format('%-' + (migIdW + 2) + 's', '[' + d.id + ']'))
            .append(d.sourceName).append(' (').append(shortType(d.sourceType)).append(')')
            .append('  ->  ')
            .append(d.targetName == null ? '(computed)' : d.targetName)
            .append(' (').append(shortType(d.targetType)).append(')\n')
    }
    outp.append('\n')
    for (String r : RUN) { if (!knownIds.contains(r)) outp.append('WARNING: RUN id "').append(r).append('" is not defined\n') }
    if (!PAGE_IDS_OVERRIDE.isEmpty()) {
        outp.append('WARNING: PAGE_IDS_OVERRIDE set - database discovery is bypassed for every migration; ')
            .append(plural(PAGE_IDS_OVERRIDE.size(), 'page')).append(' will be examined\n')
    }
    if (CURRENT_CREATES_NEW_VERSION && UPDATE_HISTORICAL_VERSIONS) {
        throw new IllegalStateException(
                'Unsupported configuration: CURRENT_CREATES_NEW_VERSION and ' +
                'UPDATE_HISTORICAL_VERSIONS are both true. saveNewVersion copies each ' +
                'pre-change body into a NEW historical row during Stage-3, after Stage-1 ' +
                'froze the findings, so that row would keep its macro and never be cleaned. ' +
                'Use one of the three supported combinations documented at the config block.')
    }
    if (!SPACE_KEYS.isEmpty()) {
        outp.append('Spaces (').append(SPACE_KEYS.size()).append('):\n')
        for (int i = 0; i < SPACE_KEYS.size(); i++) {
            String typed = SPACE_KEYS.get(i)
            outp.append('    ').append(typed)
            // personal spaces resolve to a different stored key - show both, or
            // a nothing-will-match run looks identical to a nothing-found one
            String resolved = (i < RESOLVED_SPACE_KEYS.size()) ? RESOLVED_SPACE_KEYS.get(i) : null
            if (resolved != null && resolved != typed) {
                outp.append('   -> stored key: ').append(resolved)
            }
            outp.append('\n')
        }
    }
    outp.append('History: ').append(UPDATE_HISTORICAL_VERSIONS ? 'included' : 'skipped')
        .append('   Batch: ').append(FLUSH_AFTER_BATCH ? BATCH_MAX_PAGES + ' pages' : 'no flushing').append('\n')
    outp.append('================================================================\n\n')

    // ---- STAGE 0 ----------------------------------------------------------
    outp.append('STAGE-0  VALIDATION\n----------------------------------------------------------------\n')
    StringBuilder valDetail = new StringBuilder()
    List<ValidationIssue> issues = new ArrayList<ValidationIssue>()
    issues.addAll(validateScope(spaceManager, pageManager))
    for (MigrationDef md : migrations) issues.addAll(validateMigration(md, valDetail))

    if (VALIDATION_SHOW_DETAIL && valDetail.length() > 0) outp.append(valDetail).append('\n')
    if (!issues.isEmpty()) {
        outp.append(String.format('  %-22s %-34s %-34s %s%n', 'MIGRATION', 'SOURCE', 'TARGET', 'ISSUE'))
        for (ValidationIssue vi : issues) {
            outp.append(String.format('  %-22s %-34s %-34s %s%n',
                    vi.migrationId, vi.sourceLabel, vi.targetLabel, vi.description))
        }
        outp.append('\n  ').append(plural(issues.size(), 'validation issue')).append('. Nothing was read or written.\n')
        return '<pre>' + htmlEsc(outp.toString()) + '</pre>'
    }
    outp.append('  passed (').append(plural(migrations.size(), 'migration')).append(' validated)\n')
    if (!VALIDATION_SHOW_DETAIL) {
        outp.append('  set VALIDATION_SHOW_DETAIL = true to see the parameter and option tables\n')
    }
    outp.append('\n')

    // ---- SCOPE MODE: affected pages only, then stop -------------------------
    if (MODE == 'SCOPE') {
        Set<String> names = discoveryTokens(migrations)
        long t0 = System.currentTimeMillis()

        /*
         * Chunked discovery. The window [SCOPE_OFFSET, SCOPE_OFFSET+SCOPE_LIMIT)
         * of candidate pages (whole scope when SCOPE_LIMIT = 0) is processed in
         * chunks of SCOPE_CHUNK_PAGES, each its own bounded SQL statement, so:
         *   - statement_timeout can only kill one chunk, never the whole run
         *   - everything collected before a failure is still printed
         *   - the run stops itself before SCOPE_TIME_BUDGET_SECONDS and prints
         *     the offset to resume from - a controlled stop instead of the
         *     proxy killing the request with all output lost
         */
        int candidates = candidatePageCount()
        int windowFrom = Math.min(SCOPE_OFFSET, candidates)
        int windowTo = (SCOPE_LIMIT > 0) ? Math.min(windowFrom + SCOPE_LIMIT, candidates) : candidates
        int chunk = (SCOPE_CHUNK_PAGES > 0) ? SCOPE_CHUNK_PAGES : Math.max(1, windowTo - windowFrom)
        long budgetMs = SCOPE_TIME_BUDGET_SECONDS * 1000L

        List<List<String>> scopeRows = new ArrayList<List<String>>()
        StringBuilder chunkLog = new StringBuilder()
        int resumeAt = -1
        String failure = null

        int off = windowFrom
        while (off < windowTo) {
            int take = Math.min(chunk, windowTo - off)
            if (budgetMs > 0 && off > windowFrom
                    && (System.currentTimeMillis() - t0) >= budgetMs) {
                resumeAt = off
                break
            }
            long ct0 = System.currentTimeMillis()
            try {
                List<List<String>> rows = scopeReport(names, off, take)
                scopeRows.addAll(rows)
                chunkLog.append('  chunk ').append(off).append('..').append(off + take - 1)
                        .append('   affected: ').append(String.format('%5d', rows.size()))
                        .append('   ').append(humanTime(System.currentTimeMillis() - ct0)).append('\n')
                off += take
            } catch (Exception e) {
                failure = e.getMessage()
                resumeAt = off
                chunkLog.append('  chunk ').append(off).append('..').append(off + take - 1)
                        .append('   FAILED after ').append(humanTime(System.currentTimeMillis() - ct0)).append('\n')
                break
            }
        }
        int doneTo = (resumeAt >= 0) ? resumeAt : windowTo   // offsets < doneTo are fully probed

        outp.append('SCOPE  (discovery only - no page loads; per page, body probing stops\n')
        outp.append('        at the first version carrying any source macro)\n')
        outp.append('----------------------------------------------------------------\n')
        outp.append('  candidate pages in scope: ').append(candidates)
            .append('   window: offsets ').append(windowFrom).append('..').append(Math.max(windowFrom, windowTo - 1))
            .append(' (').append(plural(windowTo - windowFrom, 'page')).append(')')
            .append('   chunk size: ').append(chunk).append('\n')
        outp.append(chunkLog)
        outp.append('  affected pages collected: ').append(scopeRows.size())
            .append('   in ').append(humanTime(System.currentTimeMillis() - t0)).append('\n')

        if (failure != null) {
            outp.append('\n  CHUNK FAILED: ').append(failure).append('\n')
            if (doneTo > windowFrom) {
                outp.append('  Results are COMPLETE for offsets ').append(windowFrom)
                    .append('..').append(doneTo - 1).append('; the failed chunk was not probed.\n')
            } else {
                outp.append('  No chunk completed - nothing was collected.\n')
            }
            outp.append('  RESUME: set SCOPE_OFFSET = ').append(resumeAt)
                .append('. If the failure is a statement timeout, also lower SCOPE_CHUNK_PAGES.\n')
        } else if (resumeAt >= 0) {
            outp.append('\n  TIME BUDGET REACHED - stopped cleanly before the request could be killed.\n')
                .append('  Results are COMPLETE for offsets ').append(windowFrom)
                .append('..').append(doneTo - 1).append('.\n')
                .append('  RESUME: set SCOPE_OFFSET = ').append(resumeAt)
                .append('   (').append(candidates - resumeAt).append(' candidates remaining)\n')
        } else if (windowTo < candidates) {
            outp.append('\n  window complete. NEXT RUN: set SCOPE_OFFSET = ').append(windowTo)
                .append('   (').append(candidates - windowTo).append(' candidates still to probe)\n')
        } else {
            outp.append('\n  scope COMPLETE - every candidate page probed.\n')
        }
        outp.append('  NOTE: offsets count CANDIDATE pages (every page in scope), not affected\n')
            .append('        ones - a chunk with few or zero affected pages is not truncation.\n')

        Map<String, Integer> perSpace = new LinkedHashMap<String, Integer>()
        for (List<String> r : scopeRows) {
            Integer c = perSpace.get(r.get(0))
            perSpace.put(r.get(0), c == null ? 1 : c + 1)
        }
        // per-space totals: how many candidate pages each space holds, so the
        // affected count has its denominator right next to it
        Map<String, Integer> totalPerSpace = new LinkedHashMap<String, Integer>()
        try {
            List<String> tk = RESOLVED_SPACE_KEYS.isEmpty()
                    ? new ArrayList<String>(SPACE_KEYS) : new ArrayList<String>(RESOLVED_SPACE_KEYS)
            List<Integer> tids = spaceIdsFor(tk)
            if (!tids.isEmpty()) {
                Map<String, Object> tp = new LinkedHashMap<String, Object>()
                String tq = 'SELECT s.spacekey AS sk, COUNT(*) AS n ' +
                        'FROM content cur JOIN spaces s ON s.spaceid = cur.spaceid WHERE ' +
                        currentPageScopeClause(tids, tp) + ' GROUP BY s.spacekey'
                DatabaseUtil.withSql(DB_RESOURCE) { Sql sql ->
                    sql.eachRow(tq, tp) { row ->
                        totalPerSpace.put(row['sk'] as String, ((Number) row['n']).intValue())
                    }
                }
            }
        } catch (Exception e) {
            RUN_LOG.add('per-space totals unavailable: ' + e.getMessage())
        }
        outp.append('\n  ').append(String.format('%-30s %-16s %s', 'SPACE', 'TOTAL PAGES', 'AFFECTED PAGES')).append('\n')
        Set<String> allKeys = new LinkedHashSet<String>()
        allKeys.addAll(totalPerSpace.keySet())
        allKeys.addAll(perSpace.keySet())
        for (String k : allKeys) {
            Integer tot = totalPerSpace.get(k)
            Integer aff = perSpace.get(k)
            outp.append('  ').append(String.format('%-30s %-16s %s', k,
                    tot == null ? '?' : tot.toString(),
                    aff == null ? '0' : aff.toString())).append('\n')
        }

        // RESULT_FORMAT applies in every mode - SCOPE used to force CSV
        // Assembly order: run log FIRST (mode, migrations, validation, chunk log),
        // THEN the results block - the results describe what the log explains.
        List<String> scopeHeaders = ['Space Key', 'Page ID', 'Page URL', 'Title']
        StringBuilder page = new StringBuilder()
        page.append('<pre>').append(htmlEsc(outp.toString())).append('</pre>')

        boolean partial = (doneTo < candidates)
        page.append('<h3>Affected pages (').append(plural(scopeRows.size(), 'page')).append(') - ')
        if (doneTo > windowFrom) {
            page.append('candidate offsets ').append(windowFrom).append('..').append(doneTo - 1)
        } else {
            page.append('no offsets completed')
        }
        page.append(partial ? ' - PARTIAL, resume at SCOPE_OFFSET = ' + Math.max(doneTo, windowFrom) : ' - scope complete')
        page.append('</h3>')

        if (RESULT_FORMAT == 'TABLE') {
            page.append('<div style="max-height:').append(RESULT_TABLE_MAX_HEIGHT_PX)
                .append('px;overflow:auto;border:1px solid #ccc;resize:vertical">')
                .append('<table border="1" cellpadding="4" cellspacing="0" style="font-size:90%"><tr>')
            for (String h : scopeHeaders) page.append('<th>').append(htmlEsc(h)).append('</th>')
            page.append('</tr>')
            for (List<String> r : scopeRows) {
                String url = baseUrl + '/pages/viewpage.action?pageId=' + r.get(1)
                page.append('<tr><td>').append(htmlEsc(r.get(0)))
                    .append('</td><td>').append(htmlEsc(r.get(1)))
                    .append('</td><td><a href="').append(url).append('" target="_blank">open</a>')
                    .append('</td><td>').append(htmlEsc(r.get(2))).append('</td></tr>')
            }
            page.append('</table></div>')

        } else if (RESULT_FORMAT == 'LIST') {
            StringBuilder urls = new StringBuilder()
            for (List<String> r : scopeRows) {
                urls.append(baseUrl).append('/pages/viewpage.action?pageId=').append(r.get(1)).append('\n')
            }
            page.append('<p style="font-size:90%">Click inside, Ctrl+A, Ctrl+C.</p>')
                .append('<textarea readonly rows="20" style="width:100%;font-family:monospace;font-size:85%">')
                .append(htmlEsc(urls.toString())).append('</textarea>')

        } else if (RESULT_FORMAT == 'CSV') {
            StringBuilder csv = new StringBuilder()
            csv.append('space_key,page_id,page_url,title\n')
            for (List<String> r : scopeRows) {
                List<String> f = [r.get(0), r.get(1),
                                  baseUrl + '/pages/viewpage.action?pageId=' + r.get(1), r.get(2)]
                List<String> q = new ArrayList<String>()
                for (String v : f) q.add('"' + (v == null ? '' : v.replace('"', '""')) + '"')
                csv.append(q.join(',')).append('\n')
            }
            page.append('<p style="font-size:90%">Click inside, Ctrl+A, Ctrl+C.</p>')
                .append('<textarea readonly rows="20" style="width:100%;font-family:monospace;font-size:85%">')
                .append(htmlEsc(csv.toString())).append('</textarea>')
        }
        if (EMIT_PAGE_IDS && !scopeRows.isEmpty()) {
            StringBuilder ids = new StringBuilder()
            ids.append('PAGE_IDS_OVERRIDE = [\n')
            for (List<String> r : scopeRows) {
                ids.append(r.get(1)).append('L,\n')
            }
            ids.append(']\n')
            page.append('<h3>Affected page ids (').append(plural(scopeRows.size(), 'page'))
                .append(') - paste over PAGE_IDS_OVERRIDE</h3>')
                .append('<p style="font-size:90%">Click inside, <b>Ctrl+A</b>, <b>Ctrl+C</b>.</p>')
                .append('<textarea readonly rows="14" style="width:100%;font-family:monospace;')
                .append('font-size:85%;white-space:pre;resize:vertical">')
                .append(htmlEsc(ids.toString()))
                .append('</textarea>')
        }
        page.append('<p style="font-size:85%;color:#666">Legend: one row per affected page; ')
            .append('a page is affected when any of its versions carries any source macro.</p>')
        log.warn("Macro engine v2 SCOPE: ${scopeRows.size()} affected page(s)")
        return page.toString()
    }

    // ---- STAGE 1 ----------------------------------------------------------
    Map<String, MigrationDef> bySource = new LinkedHashMap<String, MigrationDef>()
    Map<String, MigrationDef> byId = new LinkedHashMap<String, MigrationDef>()
    for (MigrationDef md : migrations) { bySource.put(md.sourceName, md); byId.put(md.id, md) }

    outp.append('STAGE-1  SCAN\n----------------------------------------------------------------\n')
    List<Long> scope = resolveScope(discoveryTokens(migrations))
    int totalInScope = scope.size()
    // sorted so a slice means the same thing on every run
    Collections.sort(scope)
    if (SCOPE_OFFSET > 0 || SCOPE_LIMIT > 0) {
        int from = Math.min(SCOPE_OFFSET, totalInScope)
        int to = (SCOPE_LIMIT > 0) ? Math.min(from + SCOPE_LIMIT, totalInScope) : totalInScope
        scope = new ArrayList<Long>(scope.subList(from, to))
        outp.append('  pages in scope: ').append(totalInScope)
            .append('   processing slice: offsets ').append(from).append('..').append(Math.max(from, to - 1)).append('  = ')
            .append(plural(scope.size(), 'page')).append('\n')
        if (to < totalInScope) {
            outp.append('  NEXT RUN: set SCOPE_OFFSET = ').append(to)
                .append('   (').append(totalInScope - to).append(' still to process)\n')
        } else {
            outp.append('  this is the LAST slice\n')
        }
    } else {
        outp.append('  pages in scope: ').append(totalInScope).append('\n')
    }

    // pageId -> affected version contentids, from VERSION_MAP_OVERRIDE
    Map<Long, List<Long>> versionMap = new LinkedHashMap<Long, List<Long>>()
    for (String entry : VERSION_MAP_OVERRIDE) {
        String[] parts = entry.trim().split(':')
        if (parts.length < 2) { RUN_LOG.add('VERSION_MAP_OVERRIDE entry ignored (want pageId:contentId[:vN]): ' + entry); continue }
        try {
            Long pgId = Long.parseLong(parts[0].trim())
            Long ctId = Long.parseLong(parts[1].trim())
            List<Long> lst = versionMap.get(pgId)
            if (lst == null) { lst = new ArrayList<Long>(); versionMap.put(pgId, lst) }
            lst.add(ctId)
        } catch (NumberFormatException nfe) {
            RUN_LOG.add('VERSION_MAP_OVERRIDE entry ignored (not numeric): ' + entry)
        }
    }
    if (!versionMap.isEmpty()) {
        outp.append('  version mapping: ').append(plural(VERSION_MAP_OVERRIDE.size(), 'affected version'))
            .append(' across ').append(plural(versionMap.size(), 'page'))
            .append(' - unaffected versions will not be loaded\n')
    }

    List<PageFinding> findings = new ArrayList<PageFinding>()
    int batchCount = 0, pagesDone = 0
    for (Long pid : scope) {
        // getAbstractPage, not getPage: discovery scope includes BLOGPOST rows
        // on purpose, and getPage() returns null for a blog post id - which
        // silently dropped affected blog posts from INSPECT and APPLY
        AbstractPage page = pageManager.getAbstractPage(pid.longValue())
        if (page == null) { RUN_LOG.add('page ' + pid + ' not found during scan (not a page or blog post)'); continue }
        if (page.getOriginalVersionId() != null) { RUN_LOG.add('page ' + pid + ' is a historical id; skipped'); continue }

        PageFinding pf = new PageFinding()
        pf.pageId = pid.longValue()
        pf.kind = (page instanceof BlogPost) ? 'blogpost' : 'page'
        pf.pageName = page.getTitle() == null ? '' : page.getTitle()
        // space is read ONCE from the current version; version rows never carry it
        pf.spaceKey = (page.getSpace() == null) ? '' : page.getSpace().getKey()
        pf.url = baseUrl + '/pages/viewpage.action?pageId=' + pf.pageId

        List<String> filterKeys = RESOLVED_SPACE_KEYS.isEmpty() ? SPACE_KEYS : RESOLVED_SPACE_KEYS
        if (!filterKeys.isEmpty() && !filterKeys.contains(pf.spaceKey)) continue

        // typed local first: the checker cannot always infer the element type
        // of a method call used directly as the loop source
        List<Long> versionIds
        if (!versionMap.isEmpty()) {
            List<Long> mapped = versionMap.get(pid.longValue())
            if (mapped == null) continue      // page has no affected versions per mapping
            versionIds = mapped
        } else {
            versionIds = versionContentIds(pageManager, page)
        }
        for (Long cid : versionIds) {
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
        if (INCLUDE_DRAFT_PAGES) addDraftVersions(pf, bySource)
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
    outp.append('  session flush: ').append(FLUSH_NOTE).append('\n')
    if (RESULT_FORMAT == 'TABLE' && RESULT_GRANULARITY == 'MACRO' &&
        RESULT_TABLE_WARN_ROWS > 0 && totalOcc > RESULT_TABLE_WARN_ROWS) {
        outp.append('\n  WARNING: ').append(plural(totalOcc, 'occurrence'))
            .append(' will produce that many HTML table rows (~').append((int) (totalOcc * 15))
            .append(' cells). Browsers struggle well below this. Set RESULT_FORMAT = \'CSV\'\n')
            .append('           for the same rows as text, or narrow the scope to one space per run.\n')
    }
    outp.append('\n')

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
    outp.append('  planned: ').append(plural(totalOcc, 'occurrence')).append('\n\n')

    // ---- STAGE 3 ----------------------------------------------------------
    /*
     * The results table is opened BEFORE Stage-3 and rows are appended as each
     * version completes, rather than being built from findings afterwards. A
     * run that terminates part way then still shows every version it finished,
     * which is the record of what was actually changed.
     */
    boolean perMacro = (RESULT_GRANULARITY == 'MACRO')

    outp.append('STAGE-3  ').append(apply ? 'EXECUTE' : 'DRY RUN').append('\n')
    outp.append('----------------------------------------------------------------\n')
    List<String> notes = new ArrayList<String>()
    batchCount = 0
    int rollbackEmitted = 0

    int pagesProcessed = 0
    int budgetStopIndex = -1          // index of the first UNPROCESSED page
    boolean stoppedMidPage = false    // predictive guard fired inside a page
    long budgetMs3 = RUN_TIME_BUDGET_SECONDS * 1000L

    // measured write cost, fed back into the pre-write guard
    int writeCount = 0
    long writeMsTotal = 0, writeMsMax = 0
    long verifyMsTotal = 0, verifyMsMax = 0
    // until measured: assume a write costs this much (generous on purpose)
    long writeMsEstimate = 3000L

    for (int pfIdx = 0; pfIdx < findings.size(); pfIdx++) {
        PageFinding pf = findings.get(pfIdx)
        // page-atomic budget stop: never split a page's versions
        if (budgetMs3 > 0 && pfIdx > 0
                && (System.currentTimeMillis() - runStart) >= budgetMs3) {
            budgetStopIndex = pfIdx
            break
        }
        for (VersionFinding vf : pf.versions) {
            try {
                if (SKIP_DRAFT_PAGES && vf.isDraft) { markDraftSkipped(vf, byId); continue }
                ContentEntityObject ceo = pageManager.getAbstractPage(vf.contentId)
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

                // ---- predictive budget guard, checked BEFORE each write --
                // A write started must be allowed to finish; so the question
                // is asked before starting: does the remaining budget still
                // fit one write plus its verify, at the cost measured so far?
                // Guard estimate: the WORST write seen (or the initial
                // assumption before any measurement), not the average.
                if (budgetMs3 > 0 && writeCount + pagesProcessed > 0) {
                    long est = Math.max(writeMsEstimate, writeMsMax + verifyMsMax)
                    if ((System.currentTimeMillis() - runStart) + est >= budgetMs3) {
                        stoppedMidPage = true
                        budgetStopIndex = pfIdx
                        vf.status = ReplacementStatus.Skipped
                        vf.message = 'not written - time budget'
                        for (MatchedMacro mm : vf.matchedMacros) {
                            if (mm.status == ReplacementStatus.Success || mm.status == ReplacementStatus.Unknown) {
                                mm.status = ReplacementStatus.Skipped
                                mm.message = 'not written - time budget'
                                MigrationDef mdb = byId.get(mm.migrationId)
                                if (mdb != null) { mdb.occReplaced--; mdb.occSkipped++ }
                            }
                        }
                        continue      // finally still emits this version's rows
                    }
                }

                // ---- write, with retry on stale entity -------------------
                String werr = null
                Exception lastWriteEx = null
                int attempt = 0
                String bodyToWrite = after
                long writeT0 = System.currentTimeMillis()
                while (true) {
                    try {
                        if (vf.isCurrent) {
                            AbstractPage target = pageManager.getAbstractPage(pf.pageId)
                            if (target == null) { werr = 'page disappeared before write'; break }
                            writeCurrentVersion(pageManager, target, bodyToWrite)
                        } else {
                            ContentEntityObject target = pageManager.getAbstractPage(vf.contentId)
                            if (target == null) { werr = 'version disappeared before write'; break }
                            writeHistoricalVersion(pageManager, target, bodyToWrite, pf.pageId)
                        }
                        werr = null
                        break
                    } catch (Exception we) {
                        lastWriteEx = we
                        String cn = we.getClass().getName()
                        String msg = we.getMessage() == null ? '' : we.getMessage()
                        boolean stale = cn.contains('OptimisticLocking') || cn.contains('StaleObject') ||
                                        msg.contains('unexpected row count')
                        attempt++
                        if (!stale || attempt > WRITE_RETRIES) {
                            werr = we.getClass().getSimpleName() + ': ' + msg
                            break
                        }
                        ContentEntityObject reread = pageManager.getAbstractPage(vf.contentId)
                        if (reread == null) { werr = 'version disappeared during retry'; break }
                        bodyToWrite = applyToBody(reread.getBodyAsString(), vf, byId, bySource, notes)
                    }
                }

                long writeT1 = System.currentTimeMillis()
                writeCount++
                writeMsTotal += (writeT1 - writeT0)
                if (writeT1 - writeT0 > writeMsMax) writeMsMax = writeT1 - writeT0

                if (werr != null && lastWriteEx != null && !isTolerableError(lastWriteEx)) {
                    throw new RuntimeException('write failed for page ' + pf.pageId + ' (' + pf.url +
                            ') v' + vf.versionNumber + ' after ' + WRITE_RETRIES + ' retries: ' +
                            werr, lastWriteEx)
                }
                if (werr != null) {
                    vf.status = ReplacementStatus.Failed
                    String failText = werr + (lastWriteEx == null ? '' : tolerableErrorHint(lastWriteEx))
                    vf.message = failText
                    for (MatchedMacro mm : vf.matchedMacros) {
                        if (mm.status == ReplacementStatus.Success) {
                            mm.status = ReplacementStatus.Failed
                            mm.message = failText
                            MigrationDef md = byId.get(mm.migrationId)
                            if (md != null) { md.occReplaced--; md.occFailed++ }
                        }
                    }
                } else if (VERIFY_AFTER_WRITE) {
                    long verifyT0 = System.currentTimeMillis()
                    ContentEntityObject check = pageManager.getAbstractPage(vf.contentId)
                    String freshBody = check == null ? '' : check.getBodyAsString()
                    long verifyT1 = System.currentTimeMillis()
                    verifyMsTotal += (verifyT1 - verifyT0)
                    if (verifyT1 - verifyT0 > verifyMsMax) verifyMsMax = verifyT1 - verifyT0
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
                                                   : plural(stillThere, 'macro') + ' still present after write'
                } else {
                    vf.status = ReplacementStatus.Success
                    vf.message = 'written (not verified)'
                }
                vf.bodyBefore = before; vf.bodyAfter = after
            } catch (Exception ve) {
                if (!isTolerableError(ve)) {
                    throw new RuntimeException('page ' + pf.pageId + ' (' + pf.url + ') v' +
                            vf.versionNumber + ' contentid ' + vf.contentId + ': ' + ve.getMessage(), ve)
                }
                vf.status = ReplacementStatus.Failed
                vf.message = 'no space on this version (tolerated): ' + ve.getMessage()
                RUN_LOG.add('page ' + pf.pageId + ' v' + vf.versionNumber + ' - ' + vf.message)
            } finally {
            /*
             * finally, not straight-line code: the block above exits early with
             * continue in several cases - notably INSPECT mode, which returns as
             * soon as the new body is computed. Rows emitted after it were
             * therefore never reached in a dry run. finally also runs while an
             * exception is propagating, so the terminating version still appears.
             */

            // ---- results rows for this version, emitted immediately ------
            if (RESULT_FORMAT == 'TABLE' || RESULT_FORMAT == 'CSV') {
                int rc = 0, skc = 0, flc = 0
                for (MatchedMacro mm : vf.matchedMacros) {
                    if (mm.status == ReplacementStatus.Success) rc++
                    else if (mm.status == ReplacementStatus.Skipped) skc++
                    else if (mm.status == ReplacementStatus.Failed) flc++
                }
                List<String> headers = resultHeaders(perMacro, RESULT_SHOW_MIGRATION_COLUMN)

                // header written lazily, with the first row, so an empty run
                // leaves no dangling table and no header without rows under it
                if (RESULT_FORMAT == 'TABLE' && !resultsTableOpen && !vf.matchedMacros.isEmpty()) {
                    if (!legendEmitted) {
                        if (RESULT_SHOW_LEGEND && perMacro) {
                            results.append(legendHtml(RESULT_SHOW_MIGRATION_COLUMN, apply))
                            results.append('<div style="height:16px"></div>')
                        }
                        results.append('<h3>Results Table:</h3>')
                        if (RESULT_TABLE_SCROLLBOX) {
                            results.append('<div style="max-height:')
                                   .append(RESULT_TABLE_MAX_HEIGHT_PX)
                                   .append('px;overflow:auto;border:1px solid #ccc;resize:vertical">')
                        }
                        legendEmitted = true
                    }
                    if (RESULT_TABLE_CHUNK_ROWS > 0) {
                        results.append('<div style="content-visibility:auto;contain-intrinsic-size:auto ')
                               .append(RESULT_TABLE_CHUNK_ROWS * RESULT_ROW_HEIGHT_PX).append('px">')
                    }
                    results.append('<table border="1" cellpadding="4" cellspacing="0" style="font-size:90%"><tr>')
                    for (String h : headers) results.append('<th>').append(htmlEsc(h)).append('</th>')
                    results.append('</tr>')
                    resultsTableOpen = true
                    rowsInChunk = 0
                }
                if (RESULT_FORMAT == 'CSV' && !legendEmitted) {
                    csvBody.append(csvHeaderLine(headers))
                    legendEmitted = true
                }

                List<List<String>> rows = new ArrayList<List<String>>()
                if (perMacro) {
                    for (MatchedMacro mm : vf.matchedMacros) {
                        rows.add(resultRow(true, RESULT_SHOW_MIGRATION_COLUMN, apply,
                                           pf, vf, mm, rc, skc, flc, byId))
                    }
                } else {
                    rows.add(resultRow(false, RESULT_SHOW_MIGRATION_COLUMN, apply,
                                       pf, vf, null, rc, skc, flc, byId))
                }

                int statusIdx = headers.indexOf('Status')
                for (List<String> cells : rows) {
                    resultRowCount++
                    rowsInChunk++
                    if (MAX_RESULT_ROWS > 0 && resultRowCount > MAX_RESULT_ROWS) {
                        resultRowsTruncated++
                        continue
                    }
                    if (RESULT_FORMAT == 'CSV') { csvBody.append(csvLine(cells)); continue }

                    String st = statusIdx >= 0 ? cells.get(statusIdx) : ''
                    String colour = (st == 'Skipped') ? ' style="background:#fff4e5"'
                                  : ((st == 'Failed') ? ' style="background:#ffecec"' : '')
                    results.append('<tr').append(colour).append('>')
                    for (int i = 0; i < cells.size(); i++) {
                        String v = cells.get(i)
                        if (i == URL_COLUMN_INDEX) {
                            results.append('<td><a href="').append(v).append('" target="_blank">open</a></td>')
                        } else {
                            results.append('<td>').append(htmlEsc(v)).append('</td>')
                        }
                    }
                    results.append('</tr>')
                }
            }

            // ---- close a full chunk so the next rows start a new table ---
            if (RESULT_FORMAT == 'TABLE' && resultsTableOpen && RESULT_TABLE_CHUNK_ROWS > 0 &&
                rowsInChunk >= RESULT_TABLE_CHUNK_ROWS) {
                results.append('</table></div>')
                resultsTableOpen = false        // next row reopens with headers
            }

            // ---- rollback copies, emitted per version then dropped ------
            // Routed whole into the plain or the compressed section by the
            // form of the BEFORE body (the actual rollback material). Text is
            // stored RAW here; bulkBox escapes exactly once at assembly -
            // the old emission-time htmlEsc double-escaped bodies.
            if (EMIT_ROLLBACK_COPIES && apply && vf.bodyBefore != null && rollbackEmitted < MAX_ROLLBACK_ENTRIES) {
                boolean ok = (vf.status == ReplacementStatus.Success)
                rollbackEmitted++
                boolean cSrc = ok ? COMPRESS_SOURCE_ON_SUCCESS : COMPRESS_SOURCE_ON_FAILURE
                StringBuilder rb = cSrc ? rollbackComp : rollbackPlain
                if (cSrc) rollbackCompCount++ else rollbackPlainCount++
                rb.append('---- page ').append(pf.pageId).append(' contentid ').append(vf.contentId)
                  .append(' v').append(vf.versionNumber).append('  ').append(vf.status).append('\n')
                rb.append('BEFORE:\n')
                  .append(cSrc ? compressToText(vf.bodyBefore) : vf.bodyBefore).append('\n')
                if (!ok || EMIT_REPLACED_ON_SUCCESS) {
                    boolean cRep = ok ? COMPRESS_REPLACED_ON_SUCCESS : COMPRESS_REPLACED_ON_FAILURE
                    rb.append('AFTER:\n')
                      .append(cRep ? compressToText(vf.bodyAfter) : vf.bodyAfter).append('\n')
                }
                rb.append('\n')
            }
            vf.bodyBefore = null; vf.bodyAfter = null      // do not accumulate bodies
            }   // end finally
        }
        if (stoppedMidPage) {
            // versions written before the guard fired are real and were
            // flushed with their batch; the page itself is NOT counted as
            // processed, so the resume offset repeats it - already-written
            // versions re-run as no-ops
            batchCount++
            if (FLUSH_AFTER_BATCH) { flushSession(); batchCount = 0 }
            break
        }
        pagesProcessed++
        batchCount++
        if (FLUSH_AFTER_BATCH && batchCount >= BATCH_MAX_PAGES) { flushSession(); batchCount = 0 }
    }
    if (FLUSH_AFTER_BATCH && batchCount > 0) flushSession()

    // ---- close the results output -------------------------------------------
    if (resultsTableOpen) {
        results.append('</table>')
        if (RESULT_TABLE_CHUNK_ROWS > 0) results.append('</div>')
        resultsTableOpen = false
    }
    // the scroll box wraps ALL chunks, so it closes once, after the last table
    if (legendEmitted && RESULT_FORMAT == 'TABLE' && RESULT_TABLE_SCROLLBOX) {
        results.append('</div>')
    }
    // outside the scroll box, so it stays visible without scrolling to the end
    if (resultRowsTruncated > 0) {
        results.append('<p><b>').append(plural(resultRowsTruncated, 'further row'))
               .append(' not shown</b> - MAX_RESULT_ROWS is ').append(MAX_RESULT_ROWS)
               .append('. The run processed everything; only the listing is capped. ')
               .append('Use RESULT_FORMAT = \'CSV\' or RESULT_GRANULARITY = \'VERSION\' for large runs.</p>')
    }
    if (RESULT_FORMAT == 'CSV') {
        results.append('<h3>Results CSV (').append(plural(resultRowCount, 'row')).append(')</h3>')
        if (RESULT_CSV_TEXTAREA) {
            /*
             * No copy button: Confluence sanitises inline event handlers and
             * injected <script> blocks never execute, so no JavaScript we emit
             * can reach the clipboard. A button that does nothing is worse than
             * none - the textarea plus Ctrl+A, Ctrl+C is the working route.
             */
            results.append('<p style="font-size:90%">Click inside the box, then <b>Ctrl+A</b>, ')
                   .append('<b>Ctrl+C</b> to copy all ').append(plural(resultRowCount, 'row'))
                   .append('. Paste into a .csv file or straight into Excel.</p>')
            results.append('<textarea readonly rows="').append(RESULT_TEXTAREA_ROWS)
                   .append('" style="width:100%;font-family:monospace;font-size:85%;white-space:pre">')
                   .append(htmlEsc(csvBody.toString()))
                   .append('</textarea>')
        } else {
            results.append('<pre>').append(htmlEsc(csvBody.toString())).append('</pre>')
        }
    } else if (RESULT_FORMAT == 'LIST') {
        StringBuilder urls = new StringBuilder()
        Set<String> seenUrls = new LinkedHashSet<String>()
        for (PageFinding pf : findings) seenUrls.add(pf.url)
        for (String u : seenUrls) urls.append(u).append('\n')
        results.append('<h3>').append(seenUrls.size()).append(' page URLs</h3><pre>')
               .append(htmlEsc(urls.toString())).append('</pre>')
    }

    // ---- SUMMARY ----------------------------------------------------------
    if (apply && writeCount > 0) {
        outp.append('  WRITE TIMING  writes: ').append(writeCount)
            .append('   avg: ').append(writeMsTotal / writeCount).append(' ms')
            .append('   max: ').append(writeMsMax).append(' ms')
        if (VERIFY_AFTER_WRITE) {
            outp.append('   verify avg: ').append(verifyMsTotal / writeCount).append(' ms')
                .append('   max: ').append(verifyMsMax).append(' ms')
        }
        outp.append('\n  (the pre-write guard reserves worst-observed write + verify time)\n\n')
    }
    if (budgetStopIndex >= 0) {
        int resumeOffset = SCOPE_OFFSET + pagesProcessed
        outp.append('  TIME BUDGET REACHED - stopped cleanly at a ')
            .append(stoppedMidPage ? 'version boundary (pre-write guard).' : 'page boundary.').append('\n')
            .append('  pages processed: ').append(pagesProcessed).append(' of ').append(findings.size())
            .append(' in this window; every processed page is fully done.\n')
            .append(stoppedMidPage
                ? '  The stop page has its written versions marked Success and the rest\n' +
                  '  Skipped (time budget); resuming repeats that page - already-written\n' +
                  '  versions are no-ops.\n'
                : '  No version of any later page was touched or listed.\n')
            .append('  RESUME: set SCOPE_OFFSET = ').append(resumeOffset)
            .append('   (keep SPACE_KEYS / PAGE_IDS_OVERRIDE and their order unchanged)\n')
            .append('  NOTE: FOUND counts below cover ALL pages loaded in Stage-1;\n')
            .append('        REPLACED / SKIPPED / FAILED cover processed pages only.\n\n')
    }
    outp.append('  RESULTS BY MIGRATION\n')
    // width driven by the longest id actually present, not a fixed guess
    int idW = 'MIGRATION'.length()
    for (MigrationDef md : migrations) { if (md.id.length() > idW) idW = md.id.length() }
    String rowFmt = '  %-' + (idW + 2) + 's %-9s %-10s %-9s %-8s%n'
    outp.append(String.format(rowFmt, 'MIGRATION', 'FOUND', 'REPLACED', 'SKIPPED', 'FAILED'))
    for (MigrationDef md : migrations) {
        outp.append(String.format(rowFmt, md.id,
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
    StringBuilder traceOut = new StringBuilder()
    if (TRACE_MAPPING && !notes.isEmpty()) {
        for (String n : notes) traceOut.append('  ').append(n).append('\n')
    }
    outp.append('\nTOTAL ELAPSED: ').append(humanTime(System.currentTimeMillis() - runStart)).append('\n')

    // ---- ASSEMBLE OUTPUT ---------------------------------------------------
    log.warn("Macro engine v2: mode=${MODE}, pages=${findings.size()}, elapsed=${System.currentTimeMillis() - runStart} ms")

    /*
     * Assembly order, same convention as SCOPE: the RUN LOG leads (stage
     * summaries, migration table, failures - always small now that TRACE is
     * split out), the results block follows it, and the genuinely bulky
     * sections - trace and rollback copies, which can run to megabytes and
     * fall past the console's truncation point - go last, where losing the
     * tail costs nothing.
     */
    StringBuilder page = new StringBuilder()
    page.append('<pre>').append(htmlEsc(outp.toString())).append('</pre>')
    page.append(results)
    if (!apply && EMIT_VERSION_MAP && !findings.isEmpty()) {
        StringBuilder vmap = new StringBuilder()
        int vmapCount = 0
        vmap.append('VERSION_MAP_OVERRIDE = [\n')
        for (PageFinding pf : findings) {
            for (VersionFinding vf : pf.versions) {
                vmap.append("    '").append(pf.pageId).append(':').append(vf.contentId)
                    .append(':v').append(vf.versionNumber)
                if (vf.isCurrent) vmap.append('(current)')
                vmap.append("',\n")
                vmapCount++
            }
        }
        vmap.append(']\n')
        page.append('<h3>Affected versions mapping (').append(plural(vmapCount, 'version'))
            .append(' across ').append(plural(findings.size(), 'page')).append(')</h3>')
            .append('<p style="font-size:90%">Paste over the VERSION_MAP_OVERRIDE field for the APPLY run ')
            .append('of this same scope and Migrations list - Stage-1 then loads only these versions ')
            .append('instead of walking full page histories. The :vN tail is the version number as shown ')
            .append('in the Confluence UI (metadata - ignored on paste-back). ')
            .append('Click inside, <b>Ctrl+A</b>, <b>Ctrl+C</b>.</p>')
            .append('<textarea readonly rows="14" style="width:100%;font-family:monospace;font-size:85%;')
            .append('white-space:pre;resize:vertical">')
            .append(htmlEsc(vmap.toString()))
            .append('</textarea>')
    }
    if (traceOut.length() > 0) {
        page.append(bulkBox('Trace (' + plural(notes.size(), 'note') + ')',
                traceOut.toString(), TRACE_MAX_HEIGHT_PX))
    }
    if (rollbackPlain.length() > 0 || rollbackComp.length() > 0) {
        page.append('<p><b>Rollback copies (').append(rollbackEmitted).append(' total).</b> ')
            .append('Console output is not a backup - take a database backup before bulk runs. ')
            .append('These are for surgical single-version restores. Sections are split by the ')
            .append('form of the BEFORE body.</p>')
        if (rollbackPlain.length() > 0) {
            page.append(bulkBox('Rollback copies - plain (' + rollbackPlainCount + ')',
                    rollbackPlain.toString(), ROLLBACK_MAX_HEIGHT_PX))
        }
        if (rollbackComp.length() > 0) {
            page.append(bulkBox('Rollback copies - compressed (' + rollbackCompCount + ')',
                    rollbackComp.toString(), ROLLBACK_MAX_HEIGHT_PX))
        }
    }
    return page.toString()

} catch (Throwable fatal) {
    log.error('Macro engine v2 terminated', fatal)

    // Close and label whatever the run produced before it stopped - those rows
    // are the record of what actually completed, and are worth keeping.
    if (resultsTableOpen) {
        results.append('</table>')
        if (RESULT_TABLE_CHUNK_ROWS > 0) results.append('</div>')
        if (RESULT_TABLE_SCROLLBOX) results.append('</div>')
        results.append('<p><b>PARTIAL RESULTS</b> - the run terminated after ')
               .append(plural(resultRowCount, 'row')).append('. Everything above completed; ')
               .append('anything not listed was never reached.</p>')
    }
    if (RESULT_FORMAT == 'CSV' && csvBody.length() > 0) {
        results.append('<h3>CSV - PARTIAL (').append(resultRowCount).append(' rows)</h3><pre>')
               .append(csvBody.toString().replace('&', '&amp;').replace('<', '&lt;')).append('</pre>')
    }

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
    StringBuilder tail = new StringBuilder()
    tail.append(results).append('<pre>').append(htmlEsc(err.toString())).append('</pre>')
    // rollback copies collected before the crash are the record of what was
    // already written - on a terminated APPLY they matter more, not less
    if (rollbackPlain.length() > 0) {
        tail.append(bulkBox('Rollback copies - plain (' + rollbackPlainCount + ', PARTIAL)',
                rollbackPlain.toString(), ROLLBACK_MAX_HEIGHT_PX))
    }
    if (rollbackComp.length() > 0) {
        tail.append(bulkBox('Rollback copies - compressed (' + rollbackCompCount + ', PARTIAL)',
                rollbackComp.toString(), ROLLBACK_MAX_HEIGHT_PX))
    }
    return tail.toString()
}
