import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { GridColumn, Project, TreeNode, TreeRow, LinkTarget } from './api/client.ts'
import { ApiError, fetchProjects, fetchTreePath } from './api/client.ts'
import { DataGrid } from './grid/DataGrid.tsx'
import { ColumnPicker } from './grid/ColumnPicker.tsx'
import { GroupBar } from './grid/GroupBar.tsx'
import { TreeGrid } from './tree/TreeGrid.tsx'
import { DetailPane } from './detail/DetailPane.tsx'
import { Splitter } from './shell/Splitter.tsx'
import { ModuleRail } from './shell/ModuleRail.tsx'
import { ChevronLeft, Close, GridView, Moon, Monitor, Search, Sun, TreeView } from './shell/icons.tsx'
import { applyTheme, readTheme, THEMES, type Theme } from './shell/theme.ts'
import {
  clearKey,
  defaultColumns,
  readNumber,
  readString,
  readStringList,
  writeNumber,
  writeString,
  writeStringList,
} from './shell/prefs.ts'
import { readUrlState, writeUrlState } from './shell/urlState.ts'
import './App.css'

/**
 * Every collection the app can be pointed at, including ones that are not modules.
 *
 * `test-instances` and `runs` are in here so a link can open one — a test set's related records
 * reach its instances, and an instance's reach its runs — but they are deliberately NOT on
 * {@link MODULES}.
 */
const COLLECTIONS = [
  'requirements',
  'tests',
  'test-sets',
  'test-instances',
  'runs',
  'defects',
] as const
type Collection = (typeof COLLECTIONS)[number]

/**
 * The collections this build renders a screen for — half of what makes a rail entry navigable; the
 * server supplies the other half (whether the API can reach it at all).
 *
 * ⚠️ **Test instances are absent and runs are present**, which looks inconsistent and is not. The
 * reference is ALM's own left rail: it lists **Test Runs** under Testing as a module in its own
 * right, and it does not list test instances at all — an instance lives inside a test set, reachable
 * by drilling into one. So runs get a rail entry and instances stay a link target only.
 *
 * An earlier version excluded runs too. That was right for the header bar it was written for, where
 * the four entries read as peers; it is wrong against ALM's actual rail, which is now what this
 * mirrors.
 */
const MODULES = [
  'requirements',
  'tests',
  'test-sets',
  'runs',
  'defects',
] as const satisfies readonly Collection[]

/**
 * ALM's own module names, not the collection names.
 *
 * The distinction matters to anyone who has used the stock client: the module is **Test Plan** and
 * the records in it are *tests*; the module is **Test Lab** and the records in it are *test sets*.
 * Labelling the modules "Tests" and "Test Sets" named the rows instead of the place, which is not
 * how the product or its documentation talks — and this app exists to be recognisable to people who
 * know ALM.
 *
 * Test instances and runs sit *inside* Test Lab in the stock client rather than beside it. They are
 * top-level here because Alt-ALM has no in-module navigation yet; when Test Lab gains a test-set →
 * instances → runs drill-down, these two fold into it.
 */
const COLLECTION_LABELS: Record<Collection, string> = {
  requirements: 'Requirements',
  tests: 'Test Plan',
  'test-sets': 'Test Lab',
  'test-instances': 'Test Instances',
  runs: 'Runs',
  defects: 'Defects',
}

/** Which tree collection navigates each module. Absent = that module has no tree. */
const TREE_FOR: Partial<Record<Collection, string>> = {
  requirements: 'requirements',
  tests: 'test-folders',
  'test-sets': 'test-set-folders',
}

const DENSITIES = ['comfortable', 'compact', 'condensed'] as const
type Density = (typeof DENSITIES)[number]

const VIEWS = ['tree', 'grid'] as const
type View = (typeof VIEWS)[number]

const DETAIL_MIN = 280
const DETAIL_MAX = 900

/** Everything a Back step restores. */
interface NavPoint {
  view: View
  collection: Collection
  folder: TreeNode | null
  rowId: string | null
  search: string
  /** Group drill-in. Restored too, or Back out of a bucket leaves its filter silently applied. */
  groupField: string | null
  groupValue: string | null
}

function projectKey(project: Project): string {
  return `${project.domain}/${project.project}`
}

/** Narrows a URL-supplied module name to one this build actually has. */
function isCollection(value: string | null): value is Collection {
  return value !== null && (COLLECTIONS as readonly string[]).includes(value)
}

type ProjectsStatus = 'loading' | 'ready' | 'error' | 'empty'

function App() {
  // Read once, at startup. Later reads would fight the effect that writes it.
  const initialUrl = useRef(readUrlState()).current

  const [projects, setProjects] = useState<Project[]>([])
  const [projectsStatus, setProjectsStatus] = useState<ProjectsStatus>('loading')
  const [projectsError, setProjectsError] = useState<ApiError | null>(null)
  const [selectedProjectKey, setSelectedProjectKey] = useState<string | null>(null)
  const [collection, setCollection] = useState<Collection>(() =>
    isCollection(initialUrl.collection) ? initialUrl.collection : 'requirements',
  )

  const [density, setDensity] = useState<Density>(() =>
    readString<Density>('density', 'compact', DENSITIES),
  )
  // The address wins over the stored preference: a link that says grid must open a grid, even for
  // someone whose last session ended in tree view.
  const [view, setView] = useState<View>(
    () => initialUrl.view ?? readString<View>('view', 'tree', VIEWS),
  )
  const [theme, setTheme] = useState<Theme>(readTheme)
  const [detailWidth, setDetailWidth] = useState(() =>
    readNumber('detailWidth', 460, DETAIL_MIN, DETAIL_MAX),
  )

  useEffect(() => applyTheme(theme), [theme])

  const [folder, setFolder] = useState<TreeNode | null>(null)
  const [selectedRowId, setSelectedRowId] = useState<string | null>(initialUrl.id)
  /** A record to expand the tree down to. Set by a link, cleared once the tree has consumed it. */
  const [revealId, setRevealId] = useState<string | null>(null)
  const [revealIds, setRevealIds] = useState<string[]>([])
  /** A cross-module link, parked until the module switch has cleared the old selection. */
  const pendingReveal = useRef<{ collection: Collection; id: string } | null>(null)
  const [searchDraft, setSearchDraft] = useState(initialUrl.search ?? '')
  const [search, setSearch] = useState(initialUrl.search ?? '')
  const [columns, setColumns] = useState<string[] | null>(null)
  /** Group-by: the field, and the bucket drilled into. Grid view only — see the render. */
  const [groupField, setGroupField] = useState<string | null>(null)
  const [groupValue, setGroupValue] = useState<string | null>(null)
  /** Columns of the current grid, lifted so the group control can offer the groupable ones. */
  const [availableColumns, setAvailableColumns] = useState<GridColumn[]>([])

  // Back history. A ref, not state, because pushing must never itself trigger a render — the push
  // happens inside the same handler that changes what it is recording.
  const historyRef = useRef<NavPoint[]>([])
  const [canGoBack, setCanGoBack] = useState(false)

  const pushHistory = useCallback(() => {
    historyRef.current.push({
      view, collection, folder, rowId: selectedRowId, search, groupField, groupValue,
    })
    // 50 steps is far more than anyone walks back through, and bounds the memory.
    if (historyRef.current.length > 50) historyRef.current.shift()
    setCanGoBack(true)
  }, [view, collection, folder, selectedRowId, search, groupField, groupValue])

  const goBack = useCallback(() => {
    const previous = historyRef.current.pop()
    if (!previous) return
    setView(previous.view)
    setCollection(previous.collection)
    setFolder(previous.folder)
    setSelectedRowId(previous.rowId)
    setSearch(previous.search)
    setSearchDraft(previous.search)
    setGroupField(previous.groupField)
    setGroupValue(previous.groupValue)
    setCanGoBack(historyRef.current.length > 0)
  }, [])

  useEffect(() => {
    let cancelled = false
    fetchProjects()
      .then((result) => {
        if (cancelled) return
        setProjects(result)
        setProjectsStatus(result.length === 0 ? 'empty' : 'ready')
        if (result.length > 0) {
          // Honour the address, but only for a project the server says is readable — a stale or
          // hand-edited link must not put the app in a state every request 403s from.
          const requested = result.find((p) => projectKey(p) === initialUrl.project)
          setSelectedProjectKey(projectKey(requested ?? result[0]))
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setProjectsError(
          err instanceof ApiError
            ? err
            : new ApiError({
                kind: 'unknown',
                message: 'Could not load the project list.',
                retryable: false,
                status: null,
              }),
        )
        setProjectsStatus('error')
      })
    return () => {
      cancelled = true
    }
    // Read once at startup and never reassigned, so this list is stable; it is spelled out to keep
    // the exhaustive-deps rule honest rather than silenced.
  }, [initialUrl.project])

  useEffect(() => writeString('density', density), [density])
  useEffect(() => writeString('view', view), [view])
  useEffect(() => writeNumber('detailWidth', detailWidth), [detailWidth])

  // A project switch invalidates everything, history included: those nodes and rows do not exist
  // in the new project, so a Back into them would 404.
  //
  // Clears only on an ACTUAL change from one project to another, compared against the previous
  // value rather than counting runs.
  //
  // ⚠️ A "skip the first run" flag does not work here, and fails in a way that only shows up in dev:
  // StrictMode double-invokes effects, so the second invocation finds the flag already set and
  // clears the very selection the address asked for. Arriving at a link would drop you on an empty
  // pane in `npm run dev` and work in the built app — a difference nobody wants to debug twice.
  const previousProject = useRef<string | null>(null)
  useEffect(() => {
    const previous = previousProject.current
    previousProject.current = selectedProjectKey
    // null -> first project is the initial load, not a switch.
    if (previous === null || previous === selectedProjectKey) return

    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
    setColumns(null)
    setGroupField(null)
    setGroupValue(null)
    historyRef.current = []
    setCanGoBack(false)
  }, [selectedProjectKey])

  // Apply a cross-module link once the module switch has finished clearing.
  useEffect(() => {
    const pending = pendingReveal.current
    if (!pending || pending.collection !== collection) return
    pendingReveal.current = null
    setSelectedRowId(pending.id)
    setRevealId(pending.id)
  }, [collection])

  // Ask the server which ancestors the tree must open to show the record. One round trip, because
  // ALM has no "ancestors of" query and the walk is one read per level.
  useEffect(() => {
    if (!revealId || !selectedProjectKey) return
    const treeCollection = TREE_FOR[collection]
    if (!treeCollection) {
      // A module with no tree (Defects, Runs) reveals by selection alone, which is all there is.
      setRevealId(null)
      return
    }
    let cancelled = false
    fetchTreePath(selectedProjectKey, treeCollection, revealId)
      .then((path) => {
        if (cancelled) return
        // Every ancestor except the record itself needs opening; opening the record too would
        // expand a leaf's (empty) children for no reason.
        setRevealIds(path.ids.slice(0, -1))
      })
      .catch(() => {
        // The record may not be in this tree at all — a test-set instance, say. Selection still
        // works; only the expansion is lost, so this is not worth an error state.
        if (!cancelled) setRevealIds([])
      })
      .finally(() => {
        if (!cancelled) setRevealId(null)
      })
    return () => {
      cancelled = true
    }
  }, [revealId, selectedProjectKey, collection])

  // Keep the address in step with where the app is, so a reload returns here and the URL can be
  // sent to someone else. Preferences stay out of it: a link should not restyle the recipient.
  useEffect(() => {
    if (projectsStatus !== 'ready') return
    writeUrlState({
      project: selectedProjectKey,
      collection,
      view,
      id: selectedRowId,
      search: search === '' ? null : search,
    })
  }, [projectsStatus, selectedProjectKey, collection, view, selectedRowId, search])

  // A module switch keeps history (Back to the previous module is useful) but drops the scope,
  // since a requirements folder means nothing in Defects.
  //
  // Same previous-value comparison as the project effect, and for the same StrictMode reason: the
  // module named in the address is not a switch away from anything.
  const previousCollection = useRef(collection)
  useEffect(() => {
    const previous = previousCollection.current
    previousCollection.current = collection
    if (previous === collection) return

    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
    setColumns(null)
    setGroupField(null)
    setGroupValue(null)
  }, [collection])

  const activeProject = useMemo(
    () => projects.find((p) => projectKey(p) === selectedProjectKey) ?? null,
    [projects, selectedProjectKey],
  )

  const treeCollection = TREE_FOR[collection]
  const columnsKey = `columns.${selectedProjectKey ?? ''}.${collection}`

  const filters = useMemo(() => {
    const f: Record<string, string> = {}
    if (folder) f['parent-id'] = folder.id
    if (search.trim() !== '') f['name'] = search.trim()
    // Drilling into a group re-queries rather than filtering the loaded page: the grid holds one
    // page, so filtering locally would show only the part of a 117-row bucket that happened to be
    // loaded, beside a count that says 117.
    if (groupField && groupValue !== null) f[groupField] = groupValue
    return f
  }, [folder, search, groupField, groupValue])

  const resolveColumns = useCallback(
    (available: GridColumn[]) => {
      setAvailableColumns(available)
      if (columns !== null) return
      const stored = readStringList(columnsKey)
      const names = new Set(available.map((c) => c.name))
      // Drop stored names the project no longer has, rather than rendering empty columns.
      const valid = stored?.filter((n) => names.has(n)) ?? null
      setColumns(valid && valid.length > 0 ? valid : defaultColumns(available))
    },
    [columns, columnsKey],
  )

  const changeColumns = useCallback(
    (next: string[]) => {
      setColumns(next)
      writeStringList(columnsKey, next)
    },
    [columnsKey],
  )

  const resetColumns = useCallback(
    (available: GridColumn[]) => {
      clearKey(columnsKey)
      setColumns(defaultColumns(available))
    },
    [columnsKey],
  )

  /** Tree click: show the record, never navigate away from the tree. */
  const handleTreeSelect = useCallback(
    (row: TreeRow) => {
      pushHistory()
      setSelectedRowId(row.id)
    },
    [pushHistory],
  )

  /** Double-click: scope the flat grid to this node and switch to it. */
  const handleOpenInGrid = useCallback(
    (row: TreeRow) => {
      pushHistory()
      setFolder({
        id: row.id,
        name: row.values['name']?.[0] ?? `#${row.id}`,
        parentId: row.parentId,
        hasChildren: row.hasChildren,
      })
      setSelectedRowId(null)
      setView('grid')
    },
    [pushHistory],
  )

  const handleSelectRow = useCallback(
    (id: string) => {
      pushHistory()
      setSelectedRowId(id)
    },
    [pushHistory],
  )

  const clearScope = useCallback(() => {
    pushHistory()
    setFolder(null)
    setSearch('')
    setSearchDraft('')
  }, [pushHistory])

  /**
   * Follow a link from a related-records tab to the record it points at.
   *
   * ALM opens the linked record *where it lives* — the right module, revealed in the hierarchy —
   * not just its fields in a pane, and that is the behaviour worth copying: an id you cannot locate
   * is barely more useful than an id you cannot click.
   *
   * The reveal is a two-step because the module switch deliberately clears the selection (a
   * requirements folder means nothing in Defects). Setting the id in the same tick would be wiped by
   * that effect, so it is parked here and applied once the switch has settled.
   */
  const revealRecord = useCallback(
    (target: LinkTarget) => {
      if (!isCollection(target.collection)) return
      pushHistory()
      if (target.collection === collection) {
        setSelectedRowId(target.id)
        setRevealId(target.id)
        return
      }
      pendingReveal.current = { collection: target.collection, id: target.id }
      setCollection(target.collection)
    },
    [collection, pushHistory],
  )

  if (projectsStatus === 'loading') {
    return (
      <div className="app-boot" role="status">
        <strong>Connecting to ALM…</strong>
      </div>
    )
  }

  if (projectsStatus === 'error') {
    return (
      <div className="app-boot app-boot-error" role="alert">
        <strong>Could not reach the Alt-ALM server</strong>
        <span>{projectsError?.message}</span>
        <span className="app-boot-hint">
          Is the BFF running on :8080? Start it with <code>./mvnw spring-boot:run</code>.
        </span>
      </div>
    )
  }

  if (projectsStatus === 'empty' || !activeProject || !selectedProjectKey) {
    return (
      <div className="app-boot">
        <strong>No projects are configured</strong>
        <span className="app-boot-hint">
          Set <code>alt-alm.alm.readable-projects</code> to enrol one.
        </span>
      </div>
    )
  }

  const showTree = view === 'tree' && treeCollection !== undefined

  return (
    <div className="app" data-density={density}>
      <header className="app-bar">
        <div className="app-brand">
          Alt<span className="app-brand-dim">-ALM</span>
        </div>

        <div className="app-bar-right">
          {/* Three states, not two: "system" stamps no attribute and lets the OS decide, so a
              machine that switches at sunset carries the app with it. */}
          <div className="app-themetoggle" role="group" aria-label="Colour theme">
            {THEMES.map((t) => (
              <button
                key={t}
                type="button"
                className={`app-themebtn${theme === t ? ' is-active' : ''}`}
                aria-pressed={theme === t}
                title={t === 'system' ? 'Follow the system theme' : `Always ${t}`}
                onClick={() => setTheme(t)}
              >
                {t === 'light' ? <Sun /> : t === 'dark' ? <Moon /> : <Monitor />}
                <span className="sr-only">
                  {t === 'system' ? 'Follow the system theme' : `Always ${t}`}
                </span>
              </button>
            ))}
          </div>

          <label className="app-field">
            <span className="sr-only">Project</span>
            <select
              className="field"
              value={selectedProjectKey}
              onChange={(e) => setSelectedProjectKey(e.target.value)}
            >
              {projects.map((p) => (
                <option key={projectKey(p)} value={projectKey(p)}>
                  {p.project}
                  {p.writable ? '' : ' (read only)'}
                </option>
              ))}
            </select>
          </label>

          {!activeProject.writable && (
            <span className="badge badge-ro" title="Alt-ALM has no write path yet (P2)">
              Read only
            </span>
          )}

          <label className="app-field">
            <span className="sr-only">Row density</span>
            <select
              className="field"
              value={density}
              onChange={(e) => setDensity(e.target.value as Density)}
            >
              {DENSITIES.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>

      {/* Second bar: where you are and how you got here, separate from what module you are in. */}
      <div className="app-subbar">
        <button
          type="button"
          className="btn btn-quiet"
          onClick={goBack}
          disabled={!canGoBack}
          title={canGoBack ? 'Back to the previous view' : 'Nothing to go back to'}
        >
          <ChevronLeft />
          Back
        </button>

        {treeCollection && (
          <div className="app-viewtoggle" role="group" aria-label="Main view">
            {VIEWS.map((v) => (
              <button
                key={v}
                type="button"
                className={`app-viewbtn${view === v ? ' is-active' : ''}`}
                aria-pressed={view === v}
                onClick={() => setView(v)}
              >
                {v === 'tree' ? <TreeView /> : <GridView />}
                {v === 'tree' ? 'Tree' : 'Grid'}
              </button>
            ))}
          </div>
        )}

        <nav className="app-crumbs" aria-label="Scope">
          <span className="app-crumb-module">{COLLECTION_LABELS[collection]}</span>
          {folder && (
            <>
              <span className="app-crumb-sep" aria-hidden="true">
                /
              </span>
              <span className="app-crumb" title={folder.name}>
                {folder.name}
              </span>
              <button
                type="button"
                className="app-crumb-clear"
                onClick={clearScope}
                aria-label={`Clear folder scope ${folder.name}`}
                title="Clear folder scope"
              >
                <Close />
              </button>
            </>
          )}
        </nav>

        {!showTree && (
          <form
            className="app-search"
            onSubmit={(e) => {
              e.preventDefault()
              pushHistory()
              setSearch(searchDraft)
              setSelectedRowId(null)
            }}
          >
            <span className="app-search-icon" aria-hidden="true">
              <Search />
            </span>
            <label className="sr-only" htmlFor="grid-search">
              Filter by name
            </label>
            <input
              id="grid-search"
              className="field app-search-input"
              type="search"
              placeholder="Filter by name…"
              value={searchDraft}
              onChange={(e) => setSearchDraft(e.target.value)}
            />
            {search !== '' && (
              <button
                type="button"
                className="btn btn-quiet"
                onClick={() => {
                  pushHistory()
                  setSearch('')
                  setSearchDraft('')
                }}
              >
                Clear
              </button>
            )}
          </form>
        )}
      </div>

      <div className="app-body">
        {/* ALM's own left navigation. It replaced the header module bar rather than joining it:
            ALM has one nav, and two would have to agree about what "current module" means. */}
        <ModuleRail
          active={collection}
          rendered={MODULES}
          onSelect={(c) => {
            if (!isCollection(c) || c === collection) return
            pushHistory()
            setCollection(c)
          }}
        />

        <section
          className="app-pane app-pane-main"
          aria-label={showTree ? 'Folders' : COLLECTION_LABELS[collection]}
        >
          {/* Group-by is grid-only. In a tree the rows already have a structure — ALM's own Group By
              applies to the flat grid too, and stacking a second grouping on a hierarchy would
              mean one of the two orderings quietly winning. */}
          {!showTree && (
            <GroupBar
              project={selectedProjectKey}
              collection={collection}
              columns={availableColumns}
              field={groupField}
              onField={setGroupField}
              value={groupValue}
              onValue={(v) => {
                pushHistory()
                setGroupValue(v)
                setSelectedRowId(null)
              }}
            />
          )}

          {showTree && treeCollection ? (
            <TreeGrid
              project={selectedProjectKey}
              collection={treeCollection}
              selectedId={selectedRowId}
              onSelect={handleTreeSelect}
              onOpenInGrid={handleOpenInGrid}
              onColumnsLoaded={resolveColumns}
              visibleColumns={columns ?? undefined}
              revealIds={revealIds}
              renderToolbar={(available) => (
                <>
                  <span className="grid-toolbar-label">
                    {COLLECTION_LABELS[collection]} hierarchy
                  </span>
                  <div className="grid-toolbar-right">
                    <ColumnPicker
                      columns={available}
                      visible={columns ?? defaultColumns(available)}
                      onChange={changeColumns}
                      onReset={() => resetColumns(available)}
                    />
                  </div>
                </>
              )}
            />
          ) : (
            <DataGrid
              project={selectedProjectKey}
              collection={collection}
              filters={filters}
              selectedId={selectedRowId}
              onSelectRow={handleSelectRow}
              onClearFilters={clearScope}
              onColumnsLoaded={resolveColumns}
              visibleColumns={columns ?? undefined}
              renderToolbar={(available) => (
                <>
                  <span className="grid-toolbar-label">
                    {COLLECTION_LABELS[collection]}
                    {folder && <span className="grid-toolbar-scope"> in {folder.name}</span>}
                  </span>
                  <div className="grid-toolbar-right">
                    <ColumnPicker
                      columns={available}
                      visible={columns ?? defaultColumns(available)}
                      onChange={changeColumns}
                      onReset={() => resetColumns(available)}
                    />
                  </div>
                </>
              )}
            />
          )}
        </section>

        <Splitter
          value={detailWidth}
          onChange={setDetailWidth}
          min={DETAIL_MIN}
          max={DETAIL_MAX}
          side="right"
          label="Resize detail pane"
        />

        <section
          className="app-pane app-pane-detail"
          style={{ width: `${detailWidth}px` }}
          aria-label="Detail"
        >
          <DetailPane
            project={selectedProjectKey}
            collection={collection}
            entityId={selectedRowId}
            onNavigate={revealRecord}
          />
        </section>
      </div>
    </div>
  )
}

export default App
