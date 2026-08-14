import { useEffect, useState } from 'react'
import type { Project } from './api/client.ts'
import { ApiError, fetchProjects } from './api/client.ts'
import { DataGrid } from './grid/DataGrid.tsx'
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

const DENSITIES = ['comfortable', 'compact', 'condensed'] as const
type Density = (typeof DENSITIES)[number]
const DENSITY_STORAGE_KEY = 'alt-alm.density'

function isDensity(value: string | null): value is Density {
  return value !== null && (DENSITIES as readonly string[]).includes(value)
}

function loadStoredDensity(): Density {
  try {
    const stored = window.localStorage.getItem(DENSITY_STORAGE_KEY)
    return isDensity(stored) ? stored : 'comfortable'
  } catch {
    // localStorage can throw in locked-down environments; fall back quietly.
    return 'comfortable'
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
        setProjectsError(err instanceof ApiError ? err : null)
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
      // Non-fatal: density just won't persist across reloads.
    }
  }, [density])

  const selectedProject = projects.find((p) => projectKey(p) === selectedProjectKey) ?? null

  return (
    <div className="app-shell" data-density={density}>
      <header className="app-header">
        <h1>Alt-ALM</h1>
        <div className="app-controls">
          <label className="field">
            <span>Project</span>
            <select
              value={selectedProjectKey ?? ''}
              onChange={(e) => setSelectedProjectKey(e.target.value)}
              disabled={projectsStatus !== 'ready'}
            >
              {projects.map((p) => (
                <option key={projectKey(p)} value={projectKey(p)}>
                  {projectKey(p)}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Collection</span>
            <select value={collection} onChange={(e) => setCollection(e.target.value as Collection)}>
              {COLLECTIONS.map((c) => (
                <option key={c} value={c}>
                  {COLLECTION_LABELS[c]}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Density</span>
            <select value={density} onChange={(e) => setDensity(e.target.value as Density)}>
              <option value="comfortable">Comfortable</option>
              <option value="compact">Compact</option>
              <option value="condensed">Condensed</option>
            </select>
          </label>

          {selectedProject && !selectedProject.writable && (
            <span className="badge badge-readonly" role="status">
              Read-only project
            </span>
          )}
        </div>
      </header>

      <main className="app-main">
        {projectsStatus === 'loading' && (
          <div className="grid-status" role="status" aria-live="polite">
            Loading projects…
          </div>
        )}

        {projectsStatus === 'error' && (
          <div className="grid-status grid-status-error" role="alert">
            {projectsError?.message ?? 'Could not load projects.'}
          </div>
        )}

        {projectsStatus === 'empty' && (
          <div className="grid-status" role="status">
            No projects are configured.
          </div>
        )}

        {projectsStatus === 'ready' && selectedProject && (
          <DataGrid project={projectKey(selectedProject)} collection={collection} />
        )}
      </main>
    </div>
  )
}

export default App
