import { useCallback, useEffect, useMemo, useState } from 'react'
import type { GridColumn, Project, TreeNode } from './api/client.ts'
import { ApiError, fetchProjects } from './api/client.ts'
import { DataGrid } from './grid/DataGrid.tsx'
import { ColumnPicker } from './grid/ColumnPicker.tsx'
import { FolderTree } from './tree/FolderTree.tsx'
import { DetailPane } from './detail/DetailPane.tsx'
import { Splitter } from './shell/Splitter.tsx'
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

const DETAIL_MIN = 260
const DETAIL_MAX = 900

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
  const [view, setView] = useState<View>(() => readString<View>('view', 'grid', VIEWS))
  const [detailWidth, setDetailWidth] = useState(() =>
    readNumber('detailWidth', 460, DETAIL_MIN, DETAIL_MAX),
  )

  const [folder, setFolder] = useState<TreeNode | null>(null)
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null)
  const [searchDraft, setSearchDraft] = useState('')
  const [search, setSearch] = useState('')
  const [columns, setColumns] = useState<string[] | null>(null)

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

  // Project or module change invalidates folder, row and the chosen columns: field sets are
  // per-project, so one project's column choice is meaningless in another.
  useEffect(() => {
    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
    setColumns(null)
  }, [selectedProjectKey, collection])

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

  // Resolve the column choice once the grid reports what this project actually has.
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

  if (projectsStatus === 'loading') return <div className="app-boot">Connecting to ALM…</div>

  if (projectsStatus === 'error') {
    return (
      <div className="app-boot app-boot-error">
        <strong>Could not reach the Alt-ALM server.</strong>
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
        <strong>No projects are configured.</strong>
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
              onClick={() => setCollection(c)}
            >
              {COLLECTION_LABELS[c]}
            </button>
          ))}
        </nav>

        <div className="app-bar-right">
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
                  {v === 'tree' ? 'Tree' : 'Grid'}
                </button>
              ))}
            </div>
          )}

          <label className="app-field">
            <span className="sr-only">Project</span>
            <select
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
            <span className="app-ro-badge" title="Writes are not implemented yet (P2)">
              read only
            </span>
          )}

          <label className="app-field">
            <span className="sr-only">Density</span>
            <select value={density} onChange={(e) => setDensity(e.target.value as Density)}>
              {DENSITIES.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>

      <div className="app-body">
        <section
          className="app-pane app-pane-main"
          aria-label={showTree ? 'Folders' : COLLECTION_LABELS[collection]}
        >
          <div className="pane-head">
            <span className="pane-title">
              {showTree ? 'Folders' : COLLECTION_LABELS[collection]}
              {!showTree && folder && <span className="pane-scope"> in {folder.name}</span>}
            </span>

            {showTree && folder && (
              <button type="button" className="pane-action" onClick={() => setFolder(null)}>
                clear scope
              </button>
            )}

            {!showTree && (
              <form
                className="pane-search"
                onSubmit={(e) => {
                  e.preventDefault()
                  setSearch(searchDraft)
                  setSelectedRowId(null)
                }}
              >
                <label className="sr-only" htmlFor="grid-search">
                  Filter by name
                </label>
                <input
                  id="grid-search"
                  type="search"
                  placeholder="Filter by name…"
                  value={searchDraft}
                  onChange={(e) => setSearchDraft(e.target.value)}
                />
                <button type="submit">Filter</button>
                {search !== '' && (
                  <button
                    type="button"
                    onClick={() => {
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

          {showTree && treeCollection ? (
            <FolderTree
              project={selectedProjectKey}
              collection={treeCollection}
              selectedId={folder?.id ?? null}
              onSelect={(node) => {
                setFolder(node)
                setSelectedRowId(null)
                // Choosing a folder is a request to see its contents.
                setView('grid')
              }}
            />
          ) : (
            <DataGrid
              project={selectedProjectKey}
              collection={collection}
              filters={filters}
              selectedId={selectedRowId}
              onSelectRow={setSelectedRowId}
              visibleColumns={columns ?? undefined}
              renderToolbar={(available) => {
                resolveColumns(available)
                return (
                  <>
                    {folder && (
                      <button
                        type="button"
                        className="pane-action"
                        onClick={() => setFolder(null)}
                      >
                        clear folder scope
                      </button>
                    )}
                    <div className="grid-toolbar-right">
                      <ColumnPicker
                        columns={available}
                        visible={columns ?? defaultColumns(available)}
                        onChange={changeColumns}
                        onReset={() => resetColumns(available)}
                      />
                    </div>
                  </>
                )
              }}
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

        <section className="app-pane app-pane-detail" style={{ width: `${detailWidth}px` }} aria-label="Detail">
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
