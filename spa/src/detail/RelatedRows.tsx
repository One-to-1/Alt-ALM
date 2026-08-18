import { useEffect, useState } from 'react'
import type {
  GridColumn,
  LinkTarget,
  RelatedTab,
  RelatedTableRows,
} from '../api/client.ts'
import { ApiError, fetchTabRows } from '../api/client.ts'
import { renderCell } from '../grid/renderers.tsx'
import './RelatedRows.css'

interface Props {
  project: string
  collection: string
  entityId: string
  tab: RelatedTab
  /** Follow a row to the record it links to, in its own module and in its place in the tree. */
  onNavigate: (target: LinkTarget) => void
}

type Status = 'loading' | 'ready' | 'missing' | 'error'

/**
 * ALM's own column set per link table, pinned by FIELD NAME.
 *
 * Read off the stock client's grids and then checked against live field metadata, because the two
 * do not line up the way the captions suggest:
 *
 * | ALM's caption            | actual field            |
 * |--------------------------|-------------------------|
 * | Coverage Type            | `entity-type`           |
 * | Coverage Status          | `status`                |
 * | Linked By Status         | `second-endpoint-status`|
 * | Linked Entity Type       | `second-endpoint-type`  |
 * | Trace Comment            | `comment`               |
 * | Size                     | `file-size`             |
 *
 * Matched by name and never by label, for the reason ADR 0005 exists: labels are per-project
 * customization, so pinning "Entity Name" would silently drop the column on the first project that
 * renamed it, while pinning `entity-name` shows each project's own wording as its header.
 *
 * ⚠️ Two of ALM's columns are deliberately absent here — "Defect: Summary" and "Req: Name". They
 * are not columns of the join table at all; they come from the far-end record, and the server
 * resolves them separately into {@link LinkTarget.name}, which this component renders as its own
 * Name column. Taking the join's `second-endpoint-name` instead would have been the obvious move and
 * would have shown, on a requirement's Linked Defects tab, the requirement's own name in every row.
 */
const COLUMNS_BY_COLLECTION: Record<string, string[]> = {
  'requirement-coverages': ['entity-type', 'entity-name', 'status', 'coverage-mode'],
  'defect-links': ['second-endpoint-type', 'second-endpoint-status', 'link-type', 'comment'],
  'req-traces': ['comment', 'owner', 'last-modified'],
  attachments: ['name', 'file-size', 'last-modified'],
}

/** Fallback ordering for a link table this build has no pinned set for — a project may define one. */
const PREFERRED = [
  'entity-name',
  'name',
  'entity-type',
  'coverage-mode',
  'status',
  'second-endpoint-status',
  'second-endpoint-type',
  'link-type',
  'comment',
  'owner',
  'last-modified',
]

const MAX_COLUMNS = 5

function chooseColumns(columns: GridColumn[], collection: string): GridColumn[] {
  const byName = new Map(columns.map((c) => [c.name, c]))

  const pinned = COLUMNS_BY_COLLECTION[collection]
  if (pinned) {
    // Only the ones this project actually has: a project that deactivated a field must lose the
    // column, not render an empty one.
    const chosen = pinned.map((n) => byName.get(n)).filter((c): c is GridColumn => c !== undefined)
    if (chosen.length > 0) return chosen
  }

  const chosen: GridColumn[] = []
  for (const name of PREFERRED) {
    const col = byName.get(name)
    if (col && !chosen.includes(col)) chosen.push(col)
    if (chosen.length >= MAX_COLUMNS) return chosen
  }
  // Top up in metadata order, skipping ids — the linked record's id gets its own leading column,
  // and a row of raw foreign keys reads as noise.
  for (const col of columns) {
    if (chosen.includes(col)) continue
    if (col.name === 'id' || col.name.endsWith('-id')) continue
    chosen.push(col)
    if (chosen.length >= MAX_COLUMNS) break
  }
  return chosen
}

/**
 * One related-entity tab's contents: one table per far end.
 *
 * Loads on open rather than with the record — ALM's own dialog does the same, and a record with six
 * tabs would otherwise fire six queries nobody asked for.
 */
export function RelatedRows({ project, collection, entityId, tab, onNavigate }: Props) {
  const [tables, setTables] = useState<RelatedTableRows[] | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setError(null)

    fetchTabRows(project, collection, entityId, tab.key)
      .then((result) => {
        if (cancelled) return
        setTables(result)
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

  if (status === 'error' || !tables) {
    return (
      <p className="related-empty related-error" role="alert">
        {error ?? 'The server did not return these records.'}
      </p>
    )
  }

  return (
    <>
      {tables.map((table) => (
        <LinkTable
          key={table.tableKey}
          table={table}
          // A single-table tab does not need a caption repeating the tab's own name; two or more do,
          // because "Trace From" and "Trace To" are only distinguishable by theirs.
          showCaption={tables.length > 1}
          onNavigate={onNavigate}
        />
      ))}
      <Provenance tab={tab} />
    </>
  )
}

interface LinkTableProps {
  table: RelatedTableRows
  showCaption: boolean
  onNavigate: (target: LinkTarget) => void
}

function LinkTable({ table, showCaption, onNavigate }: LinkTableProps) {
  const columns = chooseColumns(table.grid.columns, table.grid.collection)
  const targets = Object.values(table.targets)
  const hasTargets = targets.length > 0
  // Only give the far-end name a column when something is actually in it: attachments have no far
  // end at all, and an always-present column of dashes is worse than no column.
  const hasNames = targets.some((t) => t.name !== '')

  return (
    <section className="related-section">
      {showCaption && <h3 className="related-caption">{table.label}</h3>}

      {table.grid.rows.length === 0 ? (
        <p className="related-empty">Nothing linked here yet.</p>
      ) : (
        <>
          <div className="related-scroll">
            <table className="related-table">
              <thead>
                <tr>
                  {/* The linked record's OWN id, not the link row's. This is the column ALM leads
                      with ("Defect ID") and the one that makes a row followable. */}
                  {hasTargets && <th scope="col">ID</th>}
                  {/* ALM's "Defect: Summary" / "Req: Name" — the far record's own name, not the
                      join row's. See COLUMNS_BY_COLLECTION. */}
                  {hasNames && <th scope="col">Name</th>}
                  {columns.map((col) => (
                    <th key={col.name} scope="col" title={`${col.name} · ${col.type}`}>
                      {col.label || col.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {table.grid.rows.map((row) => {
                  const target = table.targets[row.id]
                  return (
                    <tr key={row.id}>
                      {hasTargets && (
                        <td className="related-idcell">
                          {target ? (
                            <button
                              type="button"
                              className="related-link"
                              onClick={() => onNavigate(target)}
                              title={`Open ${target.entity} ${target.id}`}
                            >
                              {target.id}
                            </button>
                          ) : (
                            // A row whose far end this build cannot open shows a plain id rather
                            // than a link that goes nowhere.
                            <span className="related-idmissing">—</span>
                          )}
                        </td>
                      )}
                      {hasNames && (
                        <td className="related-namecell" title={target?.name}>
                          {target?.name || <span className="related-idmissing">—</span>}
                        </td>
                      )}
                      {columns.map((col) => (
                        <td key={col.name}>{renderCell(col, row.values[col.name] ?? [])}</td>
                      ))}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <p className="related-count">
            {table.grid.rows.length} {table.grid.rows.length === 1 ? 'record' : 'records'}
            {/* Never "of N": the server's TotalResults describes the page, not the collection. */}
            {table.grid.page.mayHaveMore && ' — more may exist'}
          </p>
        </>
      )}
    </section>
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
