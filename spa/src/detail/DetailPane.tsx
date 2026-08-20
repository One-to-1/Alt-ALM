import { useEffect, useMemo, useRef, useState } from 'react'
import type {
  GridColumn,
  GridResponse,
  History,
  LinkTarget,
  RelatedTab,
  RelatedTable,
} from '../api/client.ts'
import {
  ApiError,
  fetchCommentField,
  fetchDetail,
  fetchHistory,
  fetchTabs,
  fetchTabsPopulated,
} from '../api/client.ts'
import { renderCell } from '../grid/renderers.tsx'
import { memoToPlainText, sanitizeMemo } from './richText.ts'
import { RelatedRows } from './RelatedRows.tsx'
import { HistoryPanel } from './HistoryPanel.tsx'
import { RecordEditor } from './RecordEditor.tsx'
import { CommentBox } from './CommentBox.tsx'
import { DetailRail, type RailTab } from './DetailRail.tsx'
import {
  Beaker,
  Bug,
  Clock,
  Coverage,
  Info,
  Link,
  ListAll,
  Paperclip,
  Play,
  Text,
  Warning,
} from '../shell/icons.tsx'
import './DetailPane.css'

interface Props {
  project: string
  collection: string
  entityId: string | null
  /** Follow a linked record to its own module, revealed in place. */
  onNavigate: (target: LinkTarget) => void
  /**
   * Open a related table's rows as the main grid, scoped to this record — the Test Lab drill-down.
   *
   * Takes the collection the ROWS come from, which is the tab's own, never the far end a row links
   * to. Optional so a host with nowhere to put a grid simply does not offer it.
   */
  onDrillIn?: (
    rowsCollection: string,
    table: RelatedTable,
    parentId: string,
    parentLabel: string,
  ) => void
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
const HISTORY = 'history'

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

/**
 * The rail icon for a related-entity tab.
 *
 * Keyed off the collection the rows come from first, then off what a row reaches, and finally a
 * generic link. Three levels because the tab set is per-project: a project can define a relation to
 * something this build has never seen, and the rail must still draw it rather than leaving a gap
 * where an icon should be.
 */
function relatedIcon(tab: RelatedTab): React.ReactNode {
  switch (tab.collection) {
    case 'attachments':
      return <Paperclip />
    case 'defect-links':
      return <Bug />
    case 'req-traces':
      return <Link />
    case 'requirement-coverages':
      return <Coverage />
  }
  switch (tab.tables[0]?.targetEntity) {
    case 'test':
      return <Beaker />
    case 'run':
      return <Play />
    case 'defect':
      return <Bug />
    default:
      return <Link />
  }
}

type Status = 'idle' | 'loading' | 'ready' | 'missing' | 'error'

export function DetailPane({ project, collection, entityId, onNavigate, onDrillIn }: Props) {
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>(DETAILS)
  const [related, setRelated] = useState<RelatedTab[]>([])
  /**
   * Tab key → whether it holds rows on THIS record.
   *
   * A key that is absent means "not known", which is why this is a partial record rather than a
   * `Record<string, boolean>` with defaults — see {@link fetchTabsPopulated}.
   */
  const [populated, setPopulated] = useState<Record<string, boolean>>({})
  const [history, setHistory] = useState<History | null>(null)
  const [historyStatus, setHistoryStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  /**
   * Which memo field is this entity's comment field, or null for "none, or not known yet".
   *
   * ⚠️ Discovered, never assumed. The name differs per entity and does not track the physical
   * column — a requirement's is `comments` (`RQ_DEV_COMMENTS`), a defect's is `dev-comments`
   * (probe 30) — so hardcoding either would silently offer the box on the wrong tab, or on no tab.
   * Null keeps the memo read-only, which is the safe default: no comment box is a missing feature,
   * a comment box over the wrong field is a memo overwritten with someone's note.
   */
  const [commentField, setCommentField] = useState<string | null>(null)
  /**
   * Bumped to force a re-read of the record.
   *
   * A counter rather than a boolean because the same reload can be asked for twice in a row — after
   * an unknown outcome, then again after the user saves the same edit — and a boolean would make
   * the second request a no-op precisely when the screen is least trustworthy.
   */
  const [reloadToken, setReloadToken] = useState(0)

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

  // Which field takes comments is metadata, like the tab strip — per project and collection, not
  // per record. A 404 means this entity has none, which is a legitimate answer and not an error.
  useEffect(() => {
    let cancelled = false
    setCommentField(null)
    fetchCommentField(project, collection)
      .then((field) => {
        if (!cancelled) setCommentField(field)
      })
      .catch(() => {
        // Degrade to a read-only memo. Offering the box on a guess is the failure mode worth
        // avoiding; not offering it costs a feature, not data.
        if (!cancelled) setCommentField(null)
      })
    return () => {
      cancelled = true
    }
  }, [project, collection])

  useEffect(() => {
    // Arrowing to another record must not carry an open editor with it: the draft belongs to the
    // record it was typed against, and re-using it would save one record's values onto another.
    setEditing(false)
  }, [entityId, collection, project])

  /**
   * The record currently on screen, so a re-read of the SAME one can be told from a move to
   * another. Only ever read inside the effect below, which is why it is a ref and not state.
   */
  const shownRecord = useRef<string | null>(null)

  useEffect(() => {
    if (!entityId) {
      setStatus('idle')
      setData(null)
      shownRecord.current = null
      return
    }
    let cancelled = false
    const identity = `${project}/${collection}/${entityId}`

    // ⚠️ `reloadToken` belongs in these deps, and its absence was a real bug: after an unknown
    // write outcome the ONLY action offered is "Reload the record", and without this the record
    // was never re-read — the pane went on showing pre-write values while telling the user to go
    // and look at what ALM actually stored.
    //
    // Re-reading the same record keeps its current values on screen rather than flashing the
    // skeleton. The pane is not empty, it is out of date, and blanking it would throw away the
    // outcome banner that asked for the reload in the first place. Moving to a DIFFERENT record
    // still shows the skeleton, because leaving one record's fields under another's header is a
    // worse lie than a moment of loading.
    if (shownRecord.current !== identity) {
      setStatus('loading')
    }
    setError(null)

    fetchDetail(project, collection, entityId)
      .then((result) => {
        if (cancelled) return
        shownRecord.current = identity
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
  }, [project, collection, entityId, reloadToken])

  // Which related tabs hold rows on this record — the rail's blue marks. One request covering every
  // tab, because the server can issue the per-tab probes far more cheaply than six round trips can.
  //
  // A failure here is deliberately silent: the marks are an aid to scanning, and losing them should
  // leave a plain rail rather than an error where the record should be.
  useEffect(() => {
    if (!entityId) {
      setPopulated({})
      return
    }
    let cancelled = false
    setPopulated({})
    fetchTabsPopulated(project, collection, entityId)
      .then((marks) => {
        if (!cancelled) setPopulated(marks)
      })
      .catch(() => {
        if (!cancelled) setPopulated({})
      })
    return () => {
      cancelled = true
    }
  }, [project, collection, entityId, reloadToken])

  // History loads WITH the record rather than when its tab is opened.
  //
  // That is the opposite of the choice made for related rows, and for a measured reason: probe 24
  // found a record's audit trail averages under six entries, so the payload is small and fetching it
  // eagerly both marks the rail honestly and makes the tab open with no spinner. Related tabs can
  // return hundreds of rows, which is why those stay lazy.
  useEffect(() => {
    if (!entityId) {
      setHistory(null)
      return
    }
    let cancelled = false
    setHistoryStatus('loading')
    setHistoryError(null)
    fetchHistory(project, collection, entityId)
      .then((result) => {
        if (cancelled) return
        setHistory(result)
        setHistoryStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setHistoryError(err instanceof ApiError ? err.message : 'Could not load the history.')
        setHistoryStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [project, collection, entityId, reloadToken])

  const parts = useMemo(() => {
    if (!data || data.rows.length === 0) return null
    const row = data.rows[0]

    const isPopulated = (c: GridColumn) => {
      const values = row.values[c.name]
      return values !== undefined && values.some((v) => v !== '' && v !== null)
    }

    // Named for fields specifically: the pane also tracks which TABS are populated, and one
    // `populated` covering both is how the two get confused.
    const populatedFields = data.columns.filter(isPopulated)

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
    const scalars = (formCandidates.length > 0 ? formCandidates : populatedFields).filter(
      (c) => c.type !== 'MEMO' && c.name !== 'name',
    )
    const rank = (c: GridColumn) => {
      const i = LEAD_FIELDS.indexOf(c.name)
      return i === -1 ? LEAD_FIELDS.length : i
    }
    const ordered = [...scalars].sort((a, b) => rank(a) - rank(b))
    const risk = data.columns.filter((c) => c.riskGroup)

    const memoText = (c: GridColumn) =>
      (row.values[c.name] ?? []).map(memoToPlainText).filter(Boolean).join('\n\n')

    return { row, populatedFields, memos, ordered, risk, memoText }
  }, [data])

  // Reset to Details when the record changes — but only if the open tab does not exist on the new
  // record's project. Staying on "Comments" while arrowing through records is the useful behaviour.
  const tabExists =
    tab === DETAILS ||
    tab === ALL ||
    tab === HISTORY ||
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

  const { row, populatedFields, memos, ordered, risk, memoText } = parts
  const title = row.values['name']?.[0] ?? `Record ${row.id}`
  const activeMemo = memos.find((c) => c.name === tab)
  const activeRelated = related.find((t) => tabKeyOf(t) === tab)

  const hasValue = (c: GridColumn) =>
    (row.values[c.name] ?? []).some((v) => v !== '' && v !== null)

  // The rail's contents, in ALM's own reading order: the record, then its prose, then the analysis
  // and related records, then the escape hatches.
  const railTabs: RailTab[] = [
    { id: DETAILS, label: 'Details', icon: <Info /> },
    ...memos.map((col) => ({
      id: col.name,
      label: col.label || col.name,
      icon: <Text />,
      filled: memoText(col) !== '',
    })),
    ...(risk.length > 0
      ? [{ id: RISK_TAB, label: 'Risk Analysis', icon: <Warning />, filled: risk.some(hasValue) }]
      : []),
    ...related.map((rel) => ({
      id: tabKeyOf(rel),
      label: rel.label,
      icon: relatedIcon(rel),
      // Keyed on the tab's own key, not the namespaced rail id — the server knows nothing about
      // the `rel:` prefix, which exists only to stop a memo field called "attachment" colliding.
      filled: populated[rel.key],
    })),
    {
      id: HISTORY,
      label: 'History',
      icon: <Clock />,
      filled: historyStatus === 'ready' ? (history?.entries.length ?? 0) > 0 : undefined,
    },
    { id: ALL, label: `All fields (${data.columns.length})`, icon: <ListAll /> },
  ]

  return (
    <Shell>
      <header className="detail-head">
        <div className="detail-idline">
          <span className="detail-id">{row.id}</span>
          <span className="detail-entity">{singular(data.collection)}</span>
          {!data.writable && (
            <span
              className="badge badge-ro"
              title="This project is not enrolled for writes in this deployment"
            >
              Read only
            </span>
          )}
          {data.writable && !editing && tab === DETAILS && (
            <button
              type="button"
              className="detail-edit"
              onClick={() => setEditing(true)}
            >
              Edit
            </button>
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

      {/* The rail overlays this container when it is open-but-unpinned, which is what `relative`
          is for — see DetailRail.css. */}
      <div className="detail-main">
        <DetailRail tabs={railTabs} active={tab} onSelect={setTab} />

        <div className="detail-body" role="tabpanel" aria-labelledby={`detail-tab-${tab}`}>
        {tab === DETAILS &&
          (editing ? (
            <RecordEditor
              // Remount when the record changes so no draft can outlive the row it belongs to.
              key={`${collection}/${row.id}`}
              project={project}
              collection={collection}
              columns={ordered}
              row={row}
              onReload={() => setReloadToken((n) => n + 1)}
              onClose={() => setEditing(false)}
            />
          ) : (
            <FieldTable columns={ordered} row={row} />
          ))}

        {activeMemo && (
          <>
            <MemoBody
              // Remount per field so the plain-text toggle does not carry across tabs: it is a
              // decision about one document, not a preference about memos.
              key={activeMemo.name}
              label={activeMemo.label || activeMemo.name}
              values={row.values[activeMemo.name] ?? []}
            />
            {/* Only on the comment field, only where writes are permitted, and only ever BELOW the
                thread. The thread itself stays read-only: a memo PUT replaces the field, so an
                editable box holding the existing comments is one careless save away from deleting
                every comment on the record and getting HTTP 200 for it (probe 30). */}
            {activeMemo.name === commentField && data.writable && entityId && (
              <CommentBox
                // Remount per record so a half-typed comment cannot follow the arrow keys onto a
                // different row and be posted against it.
                key={`${collection}/${row.id}`}
                project={project}
                collection={collection}
                entityId={entityId}
                label={activeMemo.label || activeMemo.name}
                expectedVersion={row.values['ver-stamp']?.[0]}
                onPosted={() => setReloadToken((n) => n + 1)}
              />
            )}
          </>
        )}

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
            onNavigate={onNavigate}
            onDrillIn={
              onDrillIn && row
                ? (table) =>
                    onDrillIn(
                      activeRelated.collection,
                      table,
                      entityId,
                      // The record's own name is what the breadcrumb has to say. Falling back to
                      // the id keeps the crumb truthful for entities that have no name field —
                      // a test instance is exactly that case.
                      (row.values.name ?? [])[0] || `#${entityId}`,
                    )
                : undefined
            }
          />
        )}

        {tab === HISTORY && (
          <HistoryPanel history={history} status={historyStatus} error={historyError} />
        )}

        {tab === ALL && (
          <>
            <FieldTable columns={data.columns.filter((c) => c.type !== 'MEMO')} row={row} showEmpty />
            <p className="detail-note">
              {populatedFields.length} of {data.columns.length} fields hold a value on this record.
              {' '}The Details tab shows the {ordered.length} ALM would probably render — an
              approximation, since ALM keeps its form layout in workflow scripts that no API serves.
            </p>
          </>
        )}
        </div>
      </div>
    </Shell>
  )
}

interface MemoBodyProps {
  label: string
  values: string[]
}

/**
 * One memo field, rendered as the rich text it actually is.
 *
 * <h2>The two things this component owes the reader</h2>
 *
 * A memo is the only place in ALM where the record's content is a document rather than a value, and
 * for most of P1 Alt-ALM showed it stripped to plain text. That was the safe answer to a real
 * problem — see {@link sanitizeMemo}, which is where the problem is now actually solved — but it
 * was also a lie of omission: a table of test conditions and a paragraph became the same grey block,
 * and nothing said so.
 *
 * So this renders the formatting, and then owns the two ways that can still mislead:
 *
 * 1. **Images we cannot fetch.** Rather than a broken-image icon, the reader gets a count and a
 *    reason. "There is a diagram here that Alt-ALM is not showing you" and "there is no diagram"
 *    are different facts about the record.
 * 2. **Markup we removed.** Only reported when the document looked like it was carrying something
 *    executable, so the notice keeps meaning something.
 *
 * The plain-text toggle stays because sanitised is not the same as legible: ALM memos pasted out of
 * Word arrive with fixed pixel widths and colours that fight the pane's own theme, and the escape
 * hatch is cheaper than trying to normalise every one of them.
 */
function MemoBody({ label, values }: MemoBodyProps) {
  const [plain, setPlain] = useState(false)

  const docs = useMemo(
    () =>
      values
        .filter((v) => v && v.trim() !== '')
        .map((raw) => ({ ...sanitizeMemo(raw), text: memoToPlainText(raw) })),
    [values],
  )

  // A document that sanitises to nothing but held an image is not empty — it is unshowable, which
  // is the case the notice exists for.
  const blocked = docs.reduce((n, d) => n + d.blockedImages, 0)
  const hostile = docs.some((d) => d.hostile)
  if (docs.every((d) => d.text === '') && blocked === 0) {
    return <p className="detail-memo-empty">This record has no {label.toLowerCase()}.</p>
  }

  const notes = []
  if (blocked > 0) {
    notes.push(
      blocked === 1
        ? 'One image is stored in ALM and is not shown here — Alt-ALM cannot fetch attachments yet.'
        : `${blocked} images are stored in ALM and are not shown here — Alt-ALM cannot fetch `
          + 'attachments yet.',
    )
  }
  if (hostile) {
    notes.push('Some markup was removed because it could have run code.')
  }

  return (
    <div className="detail-memo">
      <div className="detail-memo-bar">
        <div className="detail-memo-notes">
          {notes.map((note) => (
            <p className="detail-memo-note" key={note}>
              {note}
            </p>
          ))}
        </div>
        <button
          type="button"
          className="detail-memo-toggle"
          aria-pressed={plain}
          onClick={() => setPlain((p) => !p)}
        >
          {plain ? 'Formatted' : 'Plain text'}
        </button>
      </div>

      {plain ? (
        <p className="detail-memo-body">{docs.map((d) => d.text).join('\n\n')}</p>
      ) : (
        docs.map((d, i) => (
          <div
            // The only dangerouslySetInnerHTML in the app. The string comes from sanitizeMemo
            // three lines up and from nowhere else; if a second one of these ever appears, it is a
            // bug until proven otherwise.
            className="detail-memo-rich"
            key={i}
            dangerouslySetInnerHTML={{ __html: d.html }}
          />
        ))
      )}
    </div>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <aside className="detail" aria-label="Record detail">
      {children}
    </aside>
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
