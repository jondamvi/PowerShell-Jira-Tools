/*
 * What is the REAL space key? READ ONLY.
 *
 * SpaceManager.getSpace() accepts a personal space addressed as ~username, but
 * the spaces table stores the actual key - which for a personal space is often
 * "~" + the user key, not the username. Anything filtering SQL on the typed
 * value then matches nothing.
 *
 * Put the keys you intend to use in KEYS and run. It shows, per key: whether
 * the API resolves it, the stored key, the space type, and how many pages in it
 * carry each of the macros you are migrating.
 */

import com.atlassian.confluence.spaces.Space
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.db.DatabaseUtil

import groovy.sql.Sql
import groovy.transform.Field

// ============================ CONFIG ============================
@Field String DB_RESOURCE = 'ConfluenceDB'

final List<String> KEYS = [
        '~alicia.rodriguez',
        '~alexandra.botez@company.org',
]

// Source macro names to count per space. Leave empty to skip counting.
final List<String> MACROS = ['qualification-table']

// Also list every personal space in the instance, with its stored key.
final boolean LIST_ALL_PERSONAL_SPACES = true
// ================================================================

String esc(Object v) {
    if (v == null) return ''
    return v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
}

/** Pages in a space whose current body contains the macro. */
int countPages(String storedKey, String macroName) {
    int n = 0
    String query = '''SELECT count(*) AS c
                      FROM content c
                      JOIN bodycontent bc ON bc.contentid = c.contentid
                      JOIN spaces s ON s.spaceid = c.spaceid
                      WHERE c.contenttype IN ('PAGE','BLOGPOST')
                        AND c.prevver IS NULL
                        AND s.spacekey = :sk
                        AND bc.body LIKE :pat'''
    String resource = DB_RESOURCE
    DatabaseUtil.withSql(resource) { Sql sql ->
        sql.eachRow(query, [sk: storedKey, pat: '%ac:name="' + macroName + '"%']) { row ->
            n = ((Number) row['c']).intValue()
        }
    }
    return n
}

StringBuilder outp = new StringBuilder()
SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager)

outp.append('SPACE KEY RESOLUTION\n')
outp.append('================================================================\n')
for (String key : KEYS) {
    Space sp = spaceManager.getSpace(key)
    outp.append('  typed      : ').append(key).append('\n')
    if (sp == null) {
        outp.append('  resolved   : NOT FOUND by SpaceManager\n\n')
        continue
    }
    String stored = sp.getKey()
    outp.append('  stored key : ').append(stored)
       .append(stored == key ? '   (same)' : '   *** DIFFERENT - filter on this ***').append('\n')
    outp.append('  name       : ').append(sp.getName()).append('\n')
    outp.append('  type       : ').append(sp.getSpaceType()).append('\n')
    for (String m : MACROS) {
        outp.append('  pages with "').append(m).append('": ').append(countPages(stored, m)).append('\n')
    }
    outp.append('\n')
}

if (LIST_ALL_PERSONAL_SPACES) {
    outp.append('ALL PERSONAL SPACES (stored key -> name)\n')
    outp.append('================================================================\n')
    String query = '''SELECT s.spacekey AS sk, s.spacename AS nm, s.spacetype AS st
                      FROM spaces s
                      WHERE s.spacekey LIKE '~%'
                      ORDER BY s.spacekey'''
    String resource = DB_RESOURCE
    int n = 0
    DatabaseUtil.withSql(resource) { Sql sql ->
        sql.eachRow(query) { row ->
            n++
            outp.append(String.format('  %-40s %-10s %s%n',
                    row['sk'] as String, row['st'] as String, row['nm'] as String))
        }
    }
    outp.append('\n  ').append(n).append(' personal space(s)\n')
    outp.append('  Use the stored key column in SPACE_KEYS if it differs from ~username.\n')
}

log.warn('Space key diagnosis completed')
return '<pre>' + esc(outp.toString()) + '</pre>'
