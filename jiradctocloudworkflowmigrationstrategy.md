# Jira DC → Cloud: Workflow Functions Migration Strategy

*Scope: workflow conditions, validators and post functions (ScriptRunner + other apps), based on a JCMA test migration to a cloud sandbox. ScriptRunner for Jira Cloud is available on the target site.*

## 1. Diagnosis — why the sandbox looks the way it does

What you observed after the test migration is largely **expected JCMA/ScriptRunner behavior**, not corruption:

**Fewer workflows and functions than on DC.** JCMA migrates only the workflows that are referenced by the workflow schemes of the projects you selected for migration. Inactive workflows (not assigned to any project) and unpublished drafts are simply skipped. In addition, workflow rules provided by third-party apps only migrate if that app's vendor implements a JCMA "app migration path" (ScriptRunner, JSU and JMWE do; many others don't) — rules from apps without a path are dropped from the migrated workflow or arrive as inert placeholders.

**ScriptRunner functions disabled with code commented out.** This is deliberate and documented: during JCMA migration, ScriptRunner workflow functions are automatically copied but "deactivated, with any code commented out" so that you review and rewrite them before reactivation. The reason is architectural — DC scripts run inside Jira's JVM against the Java API (`ComponentAccessor`, `MutableIssue`, …), while ScriptRunner Cloud runs outside Jira, talks to it via REST, executes asynchronously, cannot impersonate users, and is capped at 240 seconds per execution. DC Groovy therefore cannot run unmodified on cloud, ever.

**Blank/empty rules.** ScriptRunner *built-in ("canned") script conditions and validators* from DC are not supported on cloud at all — they arrive as blank conditions/validators and must be deleted and recreated (on cloud these are built with **Jira expressions**, not Groovy).

**"Disabled on the DC server?"** Jira DC workflow functions have no enabled/disabled state — that concept doesn't exist in the DC workflow engine. What reads as "disabled" on DC is almost always an **inactive workflow** (not assigned to any scheme/project) or an unpublished **draft**. The inspection script reports exactly this per workflow.

## 2. Answers to your specific questions

**Which DC workflows are missing on cloud?** Run the Groovy inspector on DC (section A gives the full inventory with ACTIVE/INACTIVE, draft flag and project count) and the PowerShell script against the cloud sandbox (`cloud_workflows.csv`). The diff of the two name lists is your missing set; nearly all of it will be explained by INACTIVE/draft status or by projects that weren't in the migration scope.

**Should we enable them before migration?** Not blanket-enable. An inactive workflow is only migrated if you assign it to a workflow scheme used by a migrated project — doing that for genuinely needed workflows is correct; doing it for the stale copies that accumulate on every DC instance just imports garbage. The better pre-migration move is cleanup: decide per inactive workflow whether it is (a) needed → assign it to a scheme in scope, (b) historical only → leave it or delete it, (c) a draft → publish or discard it. And on the cloud side: do **not** re-enable migrated ScriptRunner functions until their code has been rewritten — enabling commented-out scripts does nothing useful, and enabling hastily "fixed" ones can corrupt data silently because cloud post functions run asynchronously.

**Which functions are not supported on cloud?** Think in three layers rather than a fixed list. First, *scripted conditions and validators*: supported on cloud only as Jira expressions, so every Groovy condition/validator needs a rewrite into an expression (or a native/Automation alternative) — Groovy is only available for post functions. Second, *Groovy post functions*: rewritable in SR Cloud (Groovy against REST, with the HAPI convenience layer) as long as they don't rely on DC-only capabilities. Third, the *genuinely unsupported capabilities* — no rewrite exists for: direct database access (SQL/OfBiz), filesystem access, running as/impersonating another user, firing arbitrary Jira events, direct mail-server/queue access, LDAP/Crowd calls, external JARs/`@Grab`, synchronous vetoing of events, and anything needing more than 240 s. The inspector's section C flags exactly these as `HARD` with per-token replacement hints.

**Can the code be changed to work on cloud?** For the `MED` category, yes — it is a rewrite, not a port (typically 30–120 min per script once you have the pattern library). The main translation pairs:

| DC (Java API) | Cloud replacement |
|---|---|
| `ComponentAccessor.*` managers | HAPI (`Issues.getByKey/create/update`, `Users.*`) or REST v3 |
| `MutableIssue.setX()` + store | `issue.update { setCustomFieldValue(...) }` / REST PUT |
| `CommentManager` | `issue.addComment(...)` |
| `SearchService` + `PagerFilter` | REST `/search` (paginated, async) |
| `WorkflowTransitionUtil` | `issue.transition(...)` / REST transitions |
| Users by username | Account IDs (GDPR) via user search REST |
| `transientVars` context | Limited cloud binding; pass data via entity properties |
| Scripted condition/validator | Jira expression (SR Cloud has an AI expression generator) |

Two semantic traps survive even a correct rewrite: cloud post functions run **after** the transition completes (async), so "block the transition" logic must move into a validator/expression, and results may appear seconds after the screen refreshes; and execution order between rules is not guaranteed the way DC's synchronous chain was.

**Can unsupported ones be replaced with Automation?** Often, and for simple cases Automation should be *preferred* even where SR Cloud could do it, because rules are no-code and vendor-maintained. Good Automation targets: set/copy/clear fields, assign (including round-robin), comment, create sub-tasks, clone/link issues, transition this or related issues, send email/Slack notifications, simple cross-issue sync. Keep in SR Cloud: multi-step logic, heavy JQL processing, external system calls with complex payloads, anything needing Groovy-level expressiveness. Redesign externally (ScriptRunner Connect / Forge app / webhook to your own service) what neither can do. Mind Automation service limits — cloud plans cap monthly rule executions for global/multi-project rules (limits differ by plan; check current Atlassian documentation for your plan before committing dozens of rules to it).

## 3. Recommended sequence

| Phase | What | Tool |
|---|---|---|
| 0 | Inventory DC (workflows, functions, script scan) + cloud sandbox | `dc-workflow-inspector.groovy`, `workflow-inventory.ps1` |
| 1 | Diff DC↔cloud; classify every function EASY / MED / HARD | script output, analysis here in chat |
| 2 | Per function, pick target: Jira expression / Automation rule / SR Cloud Groovy / redesign / drop | decision rules above |
| 3 | DC cleanup: delete obsolete workflows, publish/discard drafts, assign needed inactive workflows to in-scope schemes | Jira admin |
| 4 | Re-run JCMA test migration into a reset sandbox | JCMA |
| 5 | Rewrite + enable functions on sandbox, transition by transition; recreate blank canned conditions/validators | SR Cloud, Automation |
| 6 | UAT incl. German-language checks (see below), then production migration with a change freeze on workflows | — |

## 4. Notes for this instance

**German-language configuration.** German names, descriptions and code comments are harmless at runtime. Two places do deserve care: rewritten scripts and Automation rules should reference custom fields by **field ID** (`customfield_12345`), never by German display name — name lookups are fragile with umlauts and duplicated names; and Jira expressions/smart values that compare status or option *names* ("Erledigt", "In Arbeit") must match the cloud values exactly, which JCMA occasionally re-creates with different translations. The inspector output only contains names and class tokens, so it stays safely re-typeable.

**What to send back to the chat.** Minimum: sections **D** then **C** (a dozen short ASCII lines). Better: photos of all four sections — and from the cloud side, `cloud_workflows_full.json` or the two CSVs if you can upload files from the PC you chat from. With that I can produce the per-function solution proposals (stage 2).

## Sources

- [ScriptRunner: Migrate from Jira Server/DC to Cloud (JCMA checklist)](https://docs.adaptavist.com/sr4jc/latest/scriptrunner-migration-to-cloud/migrate-from-scriptrunner-for-jira-server-dc-to-cloud)
- [ScriptRunner: Platform differences Server/DC vs Cloud](https://docs.adaptavist.com/sr4jc/latest/scriptrunner-migration-to-cloud/platform-differences-between-scriptrunner-for-jira-server-dc-and-jira-cloud)
- [ScriptRunner: Feature parity and script alternatives](https://docs.adaptavist.com/sr4jc/latest/scriptrunner-migration-to-cloud/feature-parity-and-script-alternatives)
- [ScriptRunner migration cheat sheet](https://www.scriptrunnerhq.com/help/migration/jiracheatsheet)
- [ScriptRunner: Rewrite scripts for cloud — hints and tips](https://docs.adaptavist.com/sr4jc/current/scriptrunner-migration-to-cloud/rewrite-scripts-for-cloud-hints-and-tips)
- [Atlassian: Troubleshooting non-migrated entities with JCMA](https://confluence.atlassian.com/display/JIRAKB/Troubleshooting+errors+and+non-migrated+entities+with+the+Jira+Cloud+Migration+Assistant)
- [Atlassian: Workflow triggers and properties before JCMA migration](https://support.atlassian.com/jira/kb/how-to-get-a-list-of-the-workflow-triggers-and-properties-in-jira-to-migrate-to-cloud-jcma/)
