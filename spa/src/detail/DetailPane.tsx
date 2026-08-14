import { useEffect, useState } from 'react'
import type { GridColumn, GridResponse } from '../api/client.ts'
import { ApiError, fetchDetail } from '../api/client.ts'
import { renderCell } from '../grid/renderers.tsx'
import './DetailPane.css'

interface Props {
  project: string
  collection: string
  entityId: string | null
}

/** Fields worth showing first; everything else follows in metadata order. */
const LEAD_FIELDS = ['id', 'name', 'status', 'type-id', 'owner', 'priority']

export function DetailPane({ project, collection, entityId }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'missing' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [showAll, setShowAll] = useState(false)

  useEffect(() => {
    if (!entityId) {
      setStatus('idle')
      setData(null)
      return
    }
    let cancelled = false
    setStatus('loading')
    setError(null)

    fetchDetail(project, collection, entityId)
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
        setError(err instanceof ApiError ? err.message : 'Could not load this record.')
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [project, collection, entityId])

  if (status === 'idle') {
    return (
      <aside className="detail" aria-label="Record detail">
        <div className="detail-placeholder">Select a row to see its detail.</div>
      </aside>
    )
  }
  if (status === 'loading') {
    return (
      <aside className="detail" aria-label="Record detail">
        <div className="detail-placeholder">Loading…</div>
      </aside>
    )
  }
  if (status === 'missing') {
    return (
      <aside className="detail" aria-label="Record detail">
        <div className="detail-placeholder">
          No record with id {entityId} in this project.
        </div>
      </aside>
    )
  }
  if (status === 'error' || !data || data.rows.length === 0) {
    return (
      <aside className="detail" aria-label="Record detail">
        <div className="detail-placeholder detail-error">{error ?? 'Could not load this record.'}</div>
      </aside>
    )
  }

  const row = data.rows[0]
  const byName = new Map<string, GridColumn>(data.columns.map((c) => [c.name, c]))

  // Only fields the server actually returned a value for. A 74-column entity with 12 populated
  // fields is the normal case, and rendering 62 empty rows buries the 12 that matter.
  const populated = data.columns.filter((c) => {
    const values = row.values[c.name]
    return values !== undefined && values.some((v) => v !== '' && v !== null)
  })

  const lead = LEAD_FIELDS.map((n) => byName.get(n)).filter(
    (c): c is GridColumn => c !== undefined && populated.includes(c),
  )
  const rest = populated.filter((c) => !lead.includes(c))
  const shown = showAll ? [...lead, ...rest] : [...lead, ...rest.slice(0, 12)]
  const hidden = rest.length - (showAll ? rest.length : Math.min(rest.length, 12))

  const title = row.values['name']?.[0] ?? `Record ${row.id}`

  return (
    <aside className="detail" aria-label="Record detail">
      <header className="detail-header">
        <div className="detail-eyebrow">
          {data.collection} · {row.id}
          {!data.writable && <span className="detail-ro">read only</span>}
        </div>
        <h2 className="detail-title" title={title}>
          {title}
        </h2>
      </header>

      {row.error && (
        <div className="detail-row-error" role="status">
          ALM flagged this row: {row.error}
        </div>
      )}

      <dl className="detail-fields">
        {shown.map((col) => (
          <div className="detail-field" key={col.name}>
            <dt title={col.name}>{col.label || col.name}</dt>
            <dd>{renderCell(col, row.values[col.name] ?? [])}</dd>
          </div>
        ))}
      </dl>

      {hidden > 0 && (
        <button type="button" className="detail-more" onClick={() => setShowAll(true)}>
          Show {hidden} more populated {hidden === 1 ? 'field' : 'fields'}
        </button>
      )}
      {showAll && rest.length > 12 && (
        <button type="button" className="detail-more" onClick={() => setShowAll(false)}>
          Show fewer
        </button>
      )}

      <p className="detail-note">
        {populated.length} of {data.columns.length} fields have values in this project.
      </p>
    </aside>
  )
}
