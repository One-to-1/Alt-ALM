import { useEffect, useMemo, useState } from 'react'
import type { GridColumn, GridResponse } from '../api/client.ts'
import { ApiError, fetchDetail } from '../api/client.ts'
import { htmlToPlainText, renderCell } from '../grid/renderers.tsx'
import './DetailPane.css'

interface Props {
  project: string
  collection: string
  entityId: string | null
}

/**
 * Field order, following how ALM lays a record out: identity, then classification, then
 * assignment, then dates. Anything unlisted keeps metadata order after these.
 *
 * This is presentation order only — the field *set* still comes entirely from the project's own
 * metadata (ADR 0005). A project that lacks any of these simply skips it.
 */
const LEAD_FIELDS = [
  'id',
  'name',
  'req-type',
  'type-id',
  'status',
  'req-priority',
  'priority',
  'severity',
  'owner',
  'assigned-to',
  'detected-by',
  'target-rel',
  'target-rcyc',
  'creation-time',
  'last-modified',
]

type Tab = 'details' | 'description' | 'all'

type Status = 'idle' | 'loading' | 'ready' | 'missing' | 'error'

export function DetailPane({ project, collection, entityId }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('details')

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

  // Reset to Details when the record changes: the previous record's open tab may not even exist
  // on this one.
  useEffect(() => setTab('details'), [entityId])

  const parts = useMemo(() => {
    if (!data || data.rows.length === 0) return null
    const row = data.rows[0]

    const isPopulated = (c: GridColumn) => {
      const values = row.values[c.name]
      return values !== undefined && values.some((v) => v !== '' && v !== null)
    }

    const populated = data.columns.filter(isPopulated)
    const memos = populated.filter((c) => c.type === 'MEMO')
    const scalars = populated.filter((c) => c.type !== 'MEMO' && c.name !== 'name')

    const rank = (c: GridColumn) => {
      const i = LEAD_FIELDS.indexOf(c.name)
      return i === -1 ? LEAD_FIELDS.length : i
    }
    const ordered = [...scalars].sort((a, b) => rank(a) - rank(b))

    return { row, populated, memos, ordered }
  }, [data])

  if (status === 'idle') {
    return (
      <Shell>
        <div className="detail-empty">
          <p className="detail-empty-title">No record selected</p>
          <p className="detail-empty-hint">
            Pick a row in the grid, or a node in the tree, to see its fields here.
          </p>
        </div>
      </Shell>
    )
  }

  if (status === 'loading') {
    return (
      <Shell>
        <div className="detail-skeleton" role="status" aria-label="Loading record">
          <div className="detail-skeleton-title" />
          {Array.from({ length: 8 }, (_, i) => (
            <div key={i} className="detail-skeleton-row" />
          ))}
        </div>
      </Shell>
    )
  }

  if (status === 'missing') {
    return (
      <Shell>
        <div className="detail-empty">
          <p className="detail-empty-title">Record {entityId} not found</p>
          <p className="detail-empty-hint">
            It may have been deleted, or it belongs to a different project.
          </p>
        </div>
      </Shell>
    )
  }

  if (status === 'error' || !data || !parts) {
    return (
      <Shell>
        <div className="detail-empty detail-empty-error" role="alert">
          <p className="detail-empty-title">Could not load this record</p>
          <p className="detail-empty-hint">{error ?? 'The server did not return the record.'}</p>
        </div>
      </Shell>
    )
  }

  const { row, populated, memos, ordered } = parts
  const title = row.values['name']?.[0] ?? `Record ${row.id}`
  const hasDescription = memos.length > 0

  return (
    <Shell>
      <header className="detail-head">
        <div className="detail-idline">
          <span className="detail-id">{row.id}</span>
          <span className="detail-entity">{singular(data.collection)}</span>
          {!data.writable && (
            <span className="badge badge-ro" title="Alt-ALM has no write path yet">
              Read only
            </span>
          )}
        </div>
        <h2 className="detail-title" title={title}>
          {title}
        </h2>
      </header>

      {row.error && (
        <div className="detail-alert" role="status">
          ALM flagged this row: {row.error}
        </div>
      )}

      <div className="detail-tabs" role="tablist" aria-label="Record sections">
        <Tab id="details" active={tab} onSelect={setTab} label="Details" />
        {hasDescription && (
          <Tab id="description" active={tab} onSelect={setTab} label="Description" />
        )}
        <Tab id="all" active={tab} onSelect={setTab} label={`All fields (${data.columns.length})`} />
      </div>

      <div className="detail-body" role="tabpanel" aria-labelledby={`detail-tab-${tab}`}>
        {tab === 'details' && <FieldTable columns={ordered} row={row} />}

        {tab === 'description' &&
          memos.map((col) => (
            <section className="detail-memo" key={col.name}>
              <h3 className="detail-memo-title">{col.label || col.name}</h3>
              <p className="detail-memo-body">
                {(row.values[col.name] ?? []).map(htmlToPlainText).filter(Boolean).join('\n\n')}
              </p>
            </section>
          ))}

        {tab === 'all' && (
          <>
            <FieldTable columns={data.columns.filter((c) => c.type !== 'MEMO')} row={row} showEmpty />
            <p className="detail-note">
              {populated.length} of {data.columns.length} fields hold a value in this project.
            </p>
          </>
        )}
      </div>
    </Shell>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <aside className="detail" aria-label="Record detail">
      {children}
    </aside>
  )
}

interface TabProps {
  id: Tab
  active: Tab
  label: string
  onSelect: (t: Tab) => void
}

function Tab({ id, active, label, onSelect }: TabProps) {
  return (
    <button
      type="button"
      id={`detail-tab-${id}`}
      role="tab"
      aria-selected={active === id}
      className={`detail-tab${active === id ? ' is-active' : ''}`}
      onClick={() => onSelect(id)}
    >
      {label}
    </button>
  )
}

interface FieldTableProps {
  columns: GridColumn[]
  row: GridResponse['rows'][number]
  showEmpty?: boolean
}

/**
 * ALM's own record layout: a two-column label/value list, label on the left against a tinted
 * column, value on the right. Dense enough that 20 fields fit without scrolling.
 */
function FieldTable({ columns, row, showEmpty = false }: FieldTableProps) {
  const shown = showEmpty
    ? columns
    : columns.filter((c) => {
        const values = row.values[c.name]
        return values !== undefined && values.some((v) => v !== '' && v !== null)
      })

  if (shown.length === 0) {
    return <p className="detail-note">No fields to show.</p>
  }

  return (
    <dl className="detail-fields">
      {shown.map((col) => (
        <div className="detail-field" key={col.name}>
          <dt title={`${col.name} · ${col.type}`}>{col.label || col.name}</dt>
          <dd>{renderCell(col, row.values[col.name] ?? [])}</dd>
        </div>
      ))}
    </dl>
  )
}

/** "requirements" -> "Requirement". Display only. */
function singular(collection: string): string {
  const base = collection.endsWith('s') ? collection.slice(0, -1) : collection
  return base.charAt(0).toUpperCase() + base.slice(1).replace(/-/g, ' ')
}
