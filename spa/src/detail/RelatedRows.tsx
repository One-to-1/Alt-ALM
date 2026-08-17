import { useEffect, useState } from 'react'
import type { GridColumn, GridResponse, RelatedTab } from '../api/client.ts'
import { ApiError, fetchTabRows } from '../api/client.ts'
import { renderCell } from '../grid/renderers.tsx'
import './RelatedRows.css'

interface Props {
  project: string
  collection: string
  entityId: string
  tab: RelatedTab
}

type Status = 'loading' | 'ready' | 'missing' | 'error'

/**
 * Columns worth showing in a link table, most useful first.
 *
 * A `defect-link` row has 11 fields and a `requirement-coverage` row 9, most of them plumbing —
 * endpoint ids, modified counts. These are the ones that say *what* the linked thing is. Matched by
 * field NAME, never by label: labels are per-project customization, so a project that renames
 * "Entity Name" would silently lose its most useful column.
 */
const PREFERRED = [
  'entity-name',
  'second-endpoint-name',
  'name',
  'entity-type',
  'status',
  'second-endpoint-status',
  'coverage-mode',
  'link-type',
  'comment',
  'owner',
  'last-modified',
]

const MAX_COLUMNS = 5

function chooseColumns(columns: GridColumn[]): GridColumn[] {
  const byName = new Map(columns.map((c) => [c.name, c]))
  const chosen: GridColumn[] = []

  for (const name of PREFERRED) {
    const col = byName.get(name)
    if (col && !chosen.includes(col)) chosen.push(col)
    if (chosen.length >= MAX_COLUMNS) return chosen
  }
  // Top up in metadata order, skipping the ids — a column of raw foreign keys reads as noise.
  for (const col of columns) {
    if (chosen.includes(col)) continue
    if (col.name === 'id' || col.name.endsWith('-id')) continue
    chosen.push(col)
    if (chosen.length >= MAX_COLUMNS) break
  }
  return chosen
}

/**
 * One related-entity tab's contents.
 *
 * Loads on open rather than with the record: ALM's own dialog does the same, and a record with six
 * tabs would otherwise fire six queries nobody asked for.
 */
export function RelatedRows({ project, collection, entityId, tab }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setError(null)

    fetchTabRows(project, collection, entityId, tab.key)
      .then((result) => {
        if (cancelled) return
        setData(result)
        setStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) {
          setStatus('missing')
          return
        }
        setError(err instanceof ApiError ? err.message : 'Could not load these records.')
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [project, collection, entityId, tab.key])

  if (status === 'loading') {
    return (
      <div className="related-skeleton" role="status" aria-label={`Loading ${tab.label}`}>
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="related-skeleton-row" />
        ))}
      </div>
    )
  }

  if (status === 'missing') {
    return (
      <p className="related-empty">
        This project no longer has a “{tab.label}” tab for {collection}.
      </p>
    )
  }

  if (status === 'error' || !data) {
    return (
      <p className="related-empty related-error" role="alert">
        {error ?? 'The server did not return these records.'}
      </p>
    )
  }

  if (data.rows.length === 0) {
    return (
      <>
        <p className="related-empty">Nothing linked here yet.</p>
        <Provenance tab={tab} />
      </>
    )
  }

  const columns = chooseColumns(data.columns)

  return (
    <>
      <div className="related-scroll">
        <table className="related-table">
          <thead>
            <tr>
              {columns.map((col) => (
                <th key={col.name} scope="col" title={`${col.name} · ${col.type}`}>
                  {col.label || col.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row) => (
              <tr key={row.id}>
                {columns.map((col) => (
                  <td key={col.name}>{renderCell(col, row.values[col.name] ?? [])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="related-count">
        {data.rows.length} {data.rows.length === 1 ? 'record' : 'records'}
        {/* Never "of N": the server's TotalResults describes the page, not the collection. */}
        {data.page.mayHaveMore && ' — more may exist'}
      </p>
      <Provenance tab={tab} />
    </>
  )
}

/**
 * Where these rows came from.
 *
 * Present because the tab set is a reduction of ALM's relation list, not a copy of ALM's dialog: a
 * tab can legitimately show something unexpected, and the relation names are the first thing anyone
 * debugging that will want.
 */
function Provenance({ tab }: { tab: RelatedTab }) {
  return (
    <p className="related-provenance" title={tab.relations.join(', ')}>
      from <code>{tab.collection}</code> · {tab.relations.length}{' '}
      {tab.relations.length === 1 ? 'relation' : 'relations'}
    </p>
  )
}
