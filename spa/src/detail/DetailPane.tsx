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

/**
 * Tab identity. `details` and `all` are fixed; everything else is a MEMO field's own name.
 *
 * ALM does not have a fixed tab list — its Description / Comments / Rich Text /
 * Draft-Rejection Reason / RTM Addl Info tabs ARE that project's memo fields, one per tab. This
 * project defines nine of them.
 */
type Tab = string

const DETAILS = 'details'
const ALL = 'all'

/** ALM leads with Description; the rest follow in metadata order. */
const LEAD_MEMO = 'description'

type Status = 'idle' | 'loading' | 'ready' | 'missing' | 'error'

export function DetailPane({ project, collection, entityId }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>(DETAILS)

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

  const parts = useMemo(() => {
    if (!data || data.rows.length === 0) return null
    const row = data.rows[0]

    const isPopulated = (c: GridColumn) => {
      const values = row.values[c.name]
      return values !== undefined && values.some((v) => v !== '' && v !== null)
    }

    const populated = data.columns.filter(isPopulated)

    // EVERY memo field this project defines gets a tab, populated or not — that is what ALM does,
    // and it is the difference between "this record has no description" and "this project has no
    // description field". The previous version only showed memo content when the selected record
    // happened to have some, so on most records the fields were invisible entirely.
    const memos = data.columns.filter((c) => c.type === 'MEMO')
    memos.sort((a, b) => {
      if (a.name === LEAD_MEMO) return -1
      if (b.name === LEAD_MEMO) return 1
      return 0
    })

    const scalars = populated.filter((c) => c.type !== 'MEMO' && c.name !== 'name')
    const rank = (c: GridColumn) => {
      const i = LEAD_FIELDS.indexOf(c.name)
      return i === -1 ? LEAD_FIELDS.length : i
    }
    const ordered = [...scalars].sort((a, b) => rank(a) - rank(b))

    const memoText = (c: GridColumn) =>
      (row.values[c.name] ?? []).map(htmlToPlainText).filter(Boolean).join('\n\n')

    return { row, populated, memos, ordered, memoText }
  }, [data])

  // Reset to Details when the record changes — but only if the open tab does not exist on the new
  // record's project. Staying on "Comments" while arrowing through records is the useful behaviour.
  const tabExists =
    tab === DETAILS || tab === ALL || (parts?.memos.some((c) => c.name === tab) ?? false)
  useEffect(() => {
    if (!tabExists) setTab(DETAILS)
  }, [tabExists])

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

  const { row, populated, memos, ordered, memoText } = parts
  const title = row.values['name']?.[0] ?? `Record ${row.id}`
  const activeMemo = memos.find((c) => c.name === tab)

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
        <Tab id={DETAILS} active={tab} onSelect={setTab} label="Details" />
        {/* One tab per memo field. The dot marks which hold content on THIS record, so an
            eleven-tab strip is still scannable without opening each one. */}
        {memos.map((col) => (
          <Tab
            key={col.name}
            id={col.name}
            active={tab}
            onSelect={setTab}
            label={col.label || col.name}
            hasContent={memoText(col) !== ''}
          />
        ))}
        <Tab id={ALL} active={tab} onSelect={setTab} label={`All fields (${data.columns.length})`} />
      </div>

      <div className="detail-body" role="tabpanel" aria-labelledby={`detail-tab-${tab}`}>
        {tab === DETAILS && <FieldTable columns={ordered} row={row} />}

        {activeMemo &&
          (memoText(activeMemo) === '' ? (
            <p className="detail-memo-empty">
              This record has no {(activeMemo.label || activeMemo.name).toLowerCase()}.
            </p>
          ) : (
            // Plain text, deliberately. Memo bodies are full <html><body> documents and the
            // sanitiser's allowed set is deployment-specific, so rendering them as HTML is a P5
            // decision with a security dimension — not something to slip in here.
            <p className="detail-memo-body">{memoText(activeMemo)}</p>
          ))}

        {tab === ALL && (
          <>
            <FieldTable columns={data.columns.filter((c) => c.type !== 'MEMO')} row={row} showEmpty />
            <p className="detail-note">
              {populated.length} of {data.columns.length} fields hold a value on this record.
              {memos.length > 0 && ` ${memos.length} memo fields are shown as tabs above.`}
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
  /** Undefined for the fixed tabs; true/false marks a memo field as holding content or not. */
  hasContent?: boolean
}

function Tab({ id, active, label, onSelect, hasContent }: TabProps) {
  const isActive = active === id
  return (
    <button
      type="button"
      id={`detail-tab-${id}`}
      role="tab"
      aria-selected={isActive}
      className={`detail-tab${isActive ? ' is-active' : ''}${
        hasContent === false ? ' is-empty' : ''
      }`}
      onClick={() => onSelect(id)}
    >
      {hasContent !== undefined && (
        <span
          className={`detail-tab-dot${hasContent ? ' is-filled' : ''}`}
          aria-hidden="true"
        />
      )}
      {label}
      {/* The dot is decorative; screen readers get the state as words. */}
      {hasContent === false && <span className="sr-only"> (empty)</span>}
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
