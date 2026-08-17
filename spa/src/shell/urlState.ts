/**
 * The app's location in the URL, so a reload lands where you were and a link points at a record.
 *
 * Before this, every bit of "where am I" lived in React state only: refreshing threw you back to the
 * default project's Requirements, and there was no way to send anyone a record.
 *
 * Deliberately NOT in here:
 * - **The open detail tab.** It is DetailPane's state and the tab set is being reworked; lifting it
 *   now would be rewritten immediately.
 * - **The folder scope.** A folder is a tree node, not an id we can restore without re-reading the
 *   tree, so restoring it would need a fetch that could 404 into a broken-looking empty grid. Worth
 *   doing, but it is its own piece of work rather than something to smuggle in here.
 * - **Preferences** — density, theme, column choice, pane width. Those belong to the person, not to
 *   the address: sending someone a link should not also set their theme.
 */

export interface UrlState {
  /** "DOMAIN/PROJECT", or null to use the server's first readable project. */
  project: string | null
  collection: string | null
  view: 'tree' | 'grid' | null
  /** Selected record id. */
  id: string | null
  search: string | null
}

const EMPTY: UrlState = { project: null, collection: null, view: null, id: null, search: null }

function clean(value: string | null): string | null {
  if (value === null) return null
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/** Reads the current address. Never throws — a hand-edited URL degrades to defaults. */
export function readUrlState(): UrlState {
  try {
    const params = new URLSearchParams(window.location.search)
    const view = clean(params.get('view'))
    return {
      project: clean(params.get('project')),
      collection: clean(params.get('module')),
      // Anything other than the two known views is treated as absent rather than trusted.
      view: view === 'tree' || view === 'grid' ? view : null,
      id: clean(params.get('id')),
      search: clean(params.get('q')),
    }
  } catch {
    return EMPTY
  }
}

/**
 * Writes the address without adding a history entry.
 *
 * `replaceState`, not `pushState`, and that is a decision: the app has its own Back button over its
 * own stack (a module switch keeps history, a project switch clears it, because those nodes do not
 * exist in the new project). Pushing here would give the browser a second, differently-shaped
 * history for the same actions, and the two would disagree on what Back means.
 */
export function writeUrlState(state: UrlState): void {
  try {
    const params = new URLSearchParams()
    if (state.project) params.set('project', state.project)
    if (state.collection) params.set('module', state.collection)
    if (state.view) params.set('view', state.view)
    if (state.id) params.set('id', state.id)
    if (state.search) params.set('q', state.search)

    const query = params.toString()
    const next = query ? `${window.location.pathname}?${query}` : window.location.pathname
    if (next !== window.location.pathname + window.location.search) {
      window.history.replaceState(null, '', next)
    }
  } catch {
    // A locked-down browser just will not get shareable URLs.
  }
}
