import { useEffect, useMemo, useState } from 'react'
import type { Project, TreeNode } from './api/client.ts'
import { ApiError, fetchProjects } from './api/client.ts'
import { DataGrid } from './grid/DataGrid.tsx'
import { FolderTree } from './tree/FolderTree.tsx'
import { DetailPane } from './detail/DetailPane.tsx'
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

/** Which tree collection navigates each grid collection. Absent = no tree for that module. */
const TREE_FOR: Partial<Record<Collection, string>> = {
  requirements: 'requirements',
  tests: 'test-folders',
  'test-sets': 'test-set-folders',
}

const DENSITIES = ['comfortable', 'compact', 'condensed'] as const
type Density = (typeof DENSITIES)[number]
const DENSITY_STORAGE_KEY = 'alt-alm.density'

function isDensity(value: string | null): value is Density {
  return value !== null && (DENSITIES as readonly string[]).includes(value)
}

function loadStoredDensity(): Density {
  try {
    const stored = window.localStorage.getItem(DENSITY_STORAGE_KEY)
    return isDensity(stored) ? stored : 'compact'
  } catch {
    return 'compact'
  }
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
  const [density, setDensity] = useState<Density>(loadStoredDensity)

  const [folder, setFolder] = useState<TreeNode | null>(null)
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null)
  const [searchDraft, setSearchDraft] = useState('')
  const [search, setSearch] = useState('')

  useEffect(() => {
    let cancelled = false
    setProjectsStatus('loading')

    fetchProjects()
      .then((result) => {
        if (cancelled) return
        setProjects(result)
        setProjectsStatus(result.length === 0 ? 'empty' : 'ready')
        if (result.length > 0) {
          setSelectedProjectKey(projectKey(result[0]))
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
  }, [])

  useEffect(() => {
    try {
      window.localStorage.setItem(DENSITY_STORAGE_KEY, density)
    } catch {
      // Non-fatal: density just will not persist.
    }
  }, [density])

  // Changing project or module invalidates both the folder and the selected row.
  useEffect(() => {
    setFolder(null)
    setSelectedRowId(null)
    setSearch('')
    setSearchDraft('')
  }, [selectedProjectKey, collection])

  const activeProject = useMemo(
    () => projects.find((p) => projectKey(p) === selectedProjectKey) ?? null,
    [projects, selectedProjectKey],
  )

  const treeCollection = TREE_FOR[collection]

  // The tree filters the grid by parent-id; the search box filters by name. Both are ordinary
  // ALM filters — the server validates the field names against this project's metadata.
  const filters = useMemo(() => {
    const f: Record<string, string> = {}
    if (folder) f['parent-id'] = folder.id
    if (search.trim() !== '') f['name'] = search.trim()
    return f
  }, [folder, search])

  if (projectsStatus === 'loading') {
    return <div className="app-boot">Connecting to ALM…</div>
  }

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
            <span className="app-ro-badge" title="Writes are disabled for this project">
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

      <div className={`app-body${treeCollection ? '' : ' no-tree'}`}>
        {treeCollection && (
          <section className="app-pane app-pane-tree" aria-label="Folders">
            <div className="pane-head">
              <span className="pane-title">Folders</span>
              {folder && (
                <button type="button" className="pane-action" onClick={() => setFolder(null)}>
                  clear
                </button>
              )}
            </div>
            <FolderTree
              project={selectedProjectKey}
              collection={treeCollection}
              selectedId={folder?.id ?? null}
              onSelect={(node) => {
                setFolder(node)
                setSelectedRowId(null)
              }}
            />
          </section>
        )}

        <section className="app-pane app-pane-grid" aria-label={COLLECTION_LABELS[collection]}>
          <div className="pane-head">
            <span className="pane-title">
              {COLLECTION_LABELS[collection]}
              {folder && <span className="pane-scope"> in {folder.name}</span>}
            </span>
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
          </div>

          <DataGrid
            project={selectedProjectKey}
            collection={collection}
            filters={filters}
            selectedId={selectedRowId}
            onSelectRow={setSelectedRowId}
          />
        </section>

        <section className="app-pane app-pane-detail" aria-label="Detail">
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
