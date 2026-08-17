import { useEffect, useMemo, useState } from 'react'
import type { GridColumn, GridResponse, RelatedTab } from '../api/client.ts'
import { ApiError, fetchDetail, fetchTabs } from '../api/client.ts'
import { htmlToPlainText, renderCell } from '../grid/renderers.tsx'
import { RelatedRows } from './RelatedRows.tsx'
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

/** ALM's Requirement Details dialog puts these three in its header, not in the two-column body. */
const HEADER_FIELDS = ['id', 'name', 'type-id']

/** Probe 21: the `active && !visibleInWebUI` group — ALM's own Risk Analysis tab. */
const RISK_TAB = 'risk'

/**
 * Related-entity tab ids are namespaced.
 *
 * Their keys come from ALM (`attachment`, `req-trace`) and memo tab ids are field names — nothing
 * stops a project defining a memo field called `attachment`, and the collision would silently show
 * the wrong panel.
 */
function tabKeyOf(tab: RelatedTab): string {
  return `rel:${tab.key}`
}

type Status = 'idle' | 'loading' | 'ready' | 'missing' | 'error'

export function DetailPane({ project, collection, entityId }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>(DETAILS)
  const [related, setRelated] = useState<RelatedTab[]>([])

  // The related-entity tab set is metadata, not record data: it depends on project + collection and
  // not on which record is open. Fetching it here rather than with the record means arrowing through
  // a hundred rows costs one strip request, not a hundred.
  useEffect(() => {
    let cancelled = false
    setRelated([])

    fetchTabs(project, collection)
      .then((strip) => {
        if (!cancelled) setRelated(strip.tabs)
      })
      .catch(() => {
        // A missing strip degrades to the field-backed tabs, which are the ones that matter most.
        // Failing the whole pane because Attachments could not be enumerated would be worse.
        if (!cancelled) setRelated([])
      })

    return () => {
      cancelled = true
    }
  }, [project, collection])

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

    // Memo fields the stock client actually tabs — probe 21: `active && visibleInWebUI`, which the
    // server sends as onDetailsForm. On the probed projects this is exactly Description, Comments
    // and Rich Text, and it correctly excludes the six ALM hides (PPM Request Note, the three RBQM
    // data blobs, and the two version-control comment fields). A project with custom MLT fields
    // gets them as extra tabs, which is the behaviour the reference screenshots show.
    //
    // Populated-or-not is deliberate: every such field gets a tab even when empty on this record,
    // because hiding it would say the field does not exist in this project — a different claim.
    const memos = data.columns.filter((c) => c.type === 'MEMO' && c.onDetailsForm)
    memos.sort((a, b) => {
      if (a.name === LEAD_MEMO) return -1
      if (b.name === LEAD_MEMO) return 1
      return 0
    })

    // The Details form: the fields ALM would probably show, minus the three it puts in the header.
    // Falls back to "everything populated" for a project where the flags select nothing, so a
    // metadata shape we have not seen degrades to the old behaviour instead of an empty pane.
    const formCandidates = data.columns.filter(
      (c) => c.type !== 'MEMO' && c.onDetailsForm && !HEADER_FIELDS.includes(c.name),
    )
    const scalars = (formCandidates.length > 0 ? formCandidates : populated).filter(
      (c) => c.type !== 'MEMO' && c.name !== 'name',
    )
    const rank = (c: GridColumn) => {
      const i = LEAD_FIELDS.indexOf(c.name)
      return i === -1 ? LEAD_FIELDS.length : i
    }
    const ordered = [...scalars].sort((a, b) => rank(a) - rank(b))
    const risk = data.columns.filter((c) => c.riskGroup)

    const memoText = (c: GridColumn) =>
      (row.values[c.name] ?? []).map(htmlToPlainText).filter(Boolean).join('\n\n')

    return { row, populated, memos, ordered, risk, memoText }
  }, [data])

  // Reset to Details when the record changes — but only if the open tab does not exist on the new
  // record's project. Staying on "Comments" while arrowing through records is the useful behaviour.
  const tabExists =
    tab === DETAILS ||
    tab === ALL ||
    (tab === RISK_TAB && (parts?.risk.length ?? 0) > 0) ||
    (parts?.memos.some((c) => c.name === tab) ?? false) ||
    related.some((t) => tabKeyOf(t) === tab)
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

  const { row, populated, memos, ordered, risk, memoText } = parts
  const title = row.values['name']?.[0] ?? `Record ${row.id}`
  const activeMemo = memos.find((c) => c.name === tab)
  const activeRelated = related.find((t) => tabKeyOf(t) === tab)

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
        {/* ALM's Risk Analysis tab: the fields that are active but hidden from the main form.
            Probe 21 measured this group at exactly 25 fields in all nine projects probed. */}
        {risk.length > 0 && (
          <Tab id={RISK_TAB} active={tab} onSelect={setTab} label="Risk Analysis" />
        )}
        {/* Related-entity tabs, enumerated from ALM's own relations for THIS project. No dot: a
            dot would have to claim whether the tab holds anything, and knowing that would cost one
            query per tab per record — which is exactly what ALM's own client declines to spend. */}
        {related.map((rel) => (
          <Tab
            key={rel.key}
            id={tabKeyOf(rel)}
            active={tab}
            onSelect={setTab}
            label={rel.label}
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
            // Plain text, deliberately. Memo bodies are full <html><body> documents authored by
            // other users of the ALM instance; rendering them as HTML without our own sanitiser is
            // stored-XSS by construction. Formatted rendering is a tracked requirement, not a
            // permanent state — see implementation-plan "rich text must actually render as rich
            // text". The banner exists so nobody mistakes a stripped document for an empty one.
            <>
              <p className="detail-memo-note">
                Formatting removed — shown as plain text. Lists, styling and images are not rendered
                yet.
              </p>
              <p className="detail-memo-body">{memoText(activeMemo)}</p>
            </>
          ))}

        {tab === RISK_TAB && <FieldTable columns={risk} row={row} showEmpty />}

        {activeRelated && entityId && (
          <RelatedRows
            // Remount on tab change so the loading state restarts rather than showing the previous
            // tab's rows while the next request is in flight.
            key={activeRelated.key}
            project={project}
            collection={collection}
            entityId={entityId}
            tab={activeRelated}
          />
        )}

        {tab === ALL && (
          <>
            <FieldTable columns={data.columns.filter((c) => c.type !== 'MEMO')} row={row} showEmpty />
            <p className="detail-note">
              {populated.length} of {data.columns.length} fields hold a value on this record.
              {' '}The Details tab shows the {ordered.length} ALM would probably render — an
              approximation, since ALM keeps its form layout in workflow scripts that no API serves.
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
