import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { GridColumn, Project, TreeNode, TreeRow } from './api/client.ts'
import { ApiError, fetchProjects } from './api/client.ts'
import { DataGrid } from './grid/DataGrid.tsx'
import { ColumnPicker } from './grid/ColumnPicker.tsx'
import { TreeGrid } from './tree/TreeGrid.tsx'
import { DetailPane } from './detail/DetailPane.tsx'
import { Splitter } from './shell/Splitter.tsx'
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
import './App.css'

const COLLECTIONS = ['requirements', 'tests', 'defects', 'test-sets', 'runs'] as const
type Collection = (typeof COLLECTIONS)[number]

const COLLECTION_LABELS: Record<Collection, string> = {
  requirements: 'Requirements',
  tests: 'Tests',
  defects: 'Defects',
  'test-sets': 'Test Sets',
  runs: 'Runs',
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
}

function projectKey(project: Project): string {
  return `${project.domain}/${project.project}`
}

type ProjectsStatus = 'loading' | 'ready' | 'error' | 'empty'

function App() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectsStatus, setProjectsStatus] = useState<ProjectsStatus>('loading')
  const [projectsError, setProjectsError] = useState<ApiError | null>(null)
  const [selectedProjectKey, setSelectedProjectKey] = useState<string | null>(null)
  const [collection, setCollection] = useState<Collection>('requirements')

  const [density, setDensity] = useState<Density>(() =>
    readString<Density>('density', 'compact', DENSITIES),
  )
  const [view, setView] = useState<View>(() => readString<View>('view', 'tree', VIEWS))
  const [theme, setTheme] = useState<Theme>(readTheme)
  const [detailWidth, setDetailWidth] = useState(() =>
    readNumber('detailWidth', 460, DETAIL_MIN, DETAIL_MAX),
  )

  useEffect(() => applyTheme(theme), [theme])

  const [folder, setFolder] = useState<TreeNode | null>(null)
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null)
  const [searchDraft, setSearchDraft] = useState('')
  const [search, setSearch] = useState('')
  const [columns, setColumns] = useState<string[] | null>(null)

  // Back history. A ref, not state, because pushing must never itself trigger a render — the push
  // happens inside the same handler that changes what it is recording.
  const historyRef = useRef<NavPoint[]>([])
  const [canGoBack, setCanGoBack] = useState(false)

  const pushHistory = useCallback(() => {
    historyRef.current.push({ view, collection, folder, rowId: selectedRowId, search })
    // 50 steps is far more than anyone walks back through, and bounds the memory.
    if (historyRef.current.length > 50) historyRef.current.shift()
    setCanGoBack(true)
  }, [view, collection, folder, selectedRowId, search])

  const goBack = useCallback(() => {
    const previous = historyRef.current.pop()
    if (!previous) return
    setView(previous.view)
    setCollection(previous.collection)
    setFolder(previous.folder)
    setSelectedRowId(previous.rowId)
    setSearch(previous.search)
    setSearchDraft(previous.search)
    setCanGoBack(historyRef.current.length > 0)
  }, [])

  useEffect(() => {
    let cancelled = false
    fetchProjects()
      .then((result) => {
        if (cancelled) return
        setProjects(result)
        setProjectsStatus(result.length === 0 ? 'empty' : 'ready')
        if (result.length > 0) setSelectedProjectKey(projectKey(result[0]))
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
  }, [])

  useEffect(() => writeString('density', density), [density])
  useEffect(() => writeString('view', view), [view])
  useEffect(() => writeNumber('detailWidth', detailWidth), [detailWidth])

  // A project switch invalidates everything, history included: those nodes and rows do not exist
  // in the new project, so a Back into them would 404.
  useEffect(() => {
    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
    setColumns(null)
    historyRef.current = []
    setCanGoBack(false)
  }, [selectedProjectKey])

  // A module switch keeps history (Back to the previous module is useful) but drops the scope,
  // since a requirements folder means nothing in Defects.
  useEffect(() => {
    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
    setColumns(null)
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
    return f
  }, [folder, search])

  const resolveColumns = useCallback(
    (available: GridColumn[]) => {
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

        <nav className="app-modules" aria-label="Modules">
          {COLLECTIONS.map((c) => (
            <button
              key={c}
              type="button"
              className={`app-module${c === collection ? ' is-active' : ''}`}
              aria-current={c === collection ? 'page' : undefined}
              onClick={() => {
                if (c === collection) return
                pushHistory()
                setCollection(c)
              }}
            >
              {COLLECTION_LABELS[c]}
            </button>
          ))}
        </nav>

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
        <section
          className="app-pane app-pane-main"
          aria-label={showTree ? 'Folders' : COLLECTION_LABELS[collection]}
        >
          {showTree && treeCollection ? (
            <TreeGrid
              project={selectedProjectKey}
              collection={treeCollection}
              selectedId={selectedRowId}
              onSelect={handleTreeSelect}
              onOpenInGrid={handleOpenInGrid}
              onColumnsLoaded={resolveColumns}
              visibleColumns={columns ?? undefined}
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
          />
        </section>
      </div>
    </div>
  )
}

export default App
