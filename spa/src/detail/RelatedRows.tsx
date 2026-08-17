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
 * Columns worth showing in a link table, most useful first, taken from the stock client.
 *
 * ALM's own grids are the reference: Test Coverage shows Coverage Type / Entity Name / Coverage
 * Status / Coverage Mode; Linked Defects shows Defect ID / Defect: Summary / Linked Entity Type /
 * Linked By Status / Link Comment; Requirement Traceability shows Req: Name / Trace Comment.
 *
 * Matched by field NAME, never by label: labels are per-project customization, so a project that
 * renamed "Entity Name" would silently lose its most useful column.
 */
const PREFERRED = [
  'entity-name',
  'second-endpoint-name',
  'name',
  'entity-type',
  'coverage-mode',
  'status',
  'second-endpoint-status',
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
  const columns = chooseColumns(table.grid.columns)
  const hasTargets = Object.keys(table.targets).length > 0

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
