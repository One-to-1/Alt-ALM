import { useEffect, useState } from 'react'
import type { GridColumn, GridResponse } from '../api/client.ts'
import { ApiError, fetchGrid } from '../api/client.ts'
import { SortAsc, SortDesc, SortNone } from '../shell/icons.tsx'
import { renderCell } from './renderers.tsx'
import './DataGrid.css'

const PAGE_SIZE = 50
const DEFAULT_SORT = 'id'

interface DataGridProps {
  /** "<domain>/<project>" */
  project: string
  collection: string
  /** Field -> literal value, ANDed. Unknown fields are rejected by the server with a 400. */
  filters?: Record<string, string>
  /** Currently selected row id, for the detail pane. */
  selectedId?: string | null
  onSelectRow?: (id: string) => void
  /**
   * Column names to render, in metadata order. Omitted = render every column, which is only
   * sensible for narrow entities — a requirement carries 76.
   */
  visibleColumns?: string[]
  /** Rendered into the grid toolbar; the picker lives here so it can see the loaded columns. */
  renderToolbar?: (columns: GridColumn[]) => React.ReactNode
  /** Shown in the empty state, so a filtered-to-nothing grid can offer the way out. */
  onClearFilters?: () => void
}

type LoadStatus = 'loading' | 'ready' | 'error'

export function DataGrid({
  project,
  collection,
  filters,
  selectedId,
  onSelectRow,
  visibleColumns,
  renderToolbar,
  onClearFilters,
}: DataGridProps) {
  const [start, setStart] = useState(1)
  const [sort, setSort] = useState(DEFAULT_SORT)
  const [desc, setDesc] = useState(false)
  const [data, setData] = useState<GridResponse | null>(null)
  const [status, setStatus] = useState<LoadStatus>('loading')
  const [error, setError] = useState<ApiError | null>(null)
  const [retryToken, setRetryToken] = useState(0)

  // Switching project or collection starts a fresh view: back to page 1, default sort.
  useEffect(() => {
    setStart(1)
    setSort(DEFAULT_SORT)
    setDesc(false)
    setData(null)
  }, [project, collection])

  // A filter change is a new result set, so page 1 again — otherwise you land on page 4 of a
  // 2-row result and see an empty grid that looks like a failure.
  const filterKey = JSON.stringify(filters ?? {})
  useEffect(() => {
    setStart(1)
  }, [filterKey])

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setError(null)

    fetchGrid({ collection, project, pageSize: PAGE_SIZE, start, sort, desc, filters })
      .then((response) => {
        if (cancelled) return
        setData(response)
        setStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(
          err instanceof ApiError
            ? err
            : new ApiError({
                kind: 'unknown',
                message: 'Something went wrong loading this grid.',
                retryable: false,
                status: null,
              }),
        )
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
    // `filters` is listed for correctness; callers must memoize it (App does) or every render
    // would refetch. filterKey is kept so a value change re-runs even if identity is stable.
  }, [project, collection, start, sort, desc, retryToken, filterKey, filters])

  const handleSort = (columnName: string) => {
    setStart(1)
    if (sort === columnName) {
      setDesc((prev) => !prev)
    } else {
      setSort(columnName)
      setDesc(false)
    }
  }

  const handleRetry = () => setRetryToken((t) => t + 1)
  const handlePrevious = () => setStart((s) => Math.max(1, s - PAGE_SIZE))
  const handleNext = () => setStart((s) => s + PAGE_SIZE)

  if (status === 'loading' && !data) {
    return (
      <div className="data-grid">
        <div className="grid-skeleton" role="status" aria-label={`Loading ${collection}`}>
          {Array.from({ length: 14 }, (_, i) => (
            <div key={i} className="grid-skeleton-row" />
          ))}
        </div>
      </div>
    )
  }

  if (status === 'error' && !data) {
    return (
      <div className="data-grid">
        <div className="grid-status grid-status-error" role="alert">
          <p className="grid-status-title">Could not load {collection}</p>
          <p>{error?.message}</p>
          {error?.retryable && (
            <button type="button" className="btn" onClick={handleRetry}>
              Try again
            </button>
          )}
        </div>
      </div>
    )
  }

  if (!data) {
    return null
  }

  const hasFilters = filters !== undefined && Object.keys(filters).length > 0

  if (data.rows.length === 0 && status === 'ready') {
    return (
      <div className="data-grid">
        {renderToolbar && <div className="grid-toolbar">{renderToolbar(data.columns)}</div>}
        <div className="grid-status" role="status">
          <p className="grid-status-title">
            {hasFilters ? `No ${collection} match this filter` : `No ${collection} in this project`}
          </p>
          <p>
            {hasFilters
              ? 'Widen the filter or clear the folder scope to see more.'
              : 'Nothing has been created here yet.'}
          </p>
          {hasFilters && onClearFilters && (
            <button type="button" className="btn" onClick={onClearFilters}>
              Clear filters
            </button>
          )}
        </div>
      </div>
    )
  }

  const rangeEnd = start + data.page.rowsReturned - 1

  // Metadata order is preserved regardless of the order names arrive in, so toggling a column
  // on never reshuffles the grid under the user.
  const shownColumns =
    visibleColumns && visibleColumns.length > 0
      ? data.columns.filter((c) => visibleColumns.includes(c.name))
      : data.columns

  return (
    <div className="data-grid">
      {renderToolbar && <div className="grid-toolbar">{renderToolbar(data.columns)}</div>}

      {status === 'loading' && (
        <div className="grid-refresh-banner" role="status" aria-live="polite">
          Refreshing…
        </div>
      )}

      {status === 'error' && (
        <div className="grid-inline-error" role="alert">
          <span>{error?.message}</span>
          {error?.retryable && (
            <button type="button" className="btn" onClick={handleRetry}>
              Try again
            </button>
          )}
        </div>
      )}

      <div className="data-grid-scroll">
        <table>
          <caption className="sr-only">
            {collection} — page starting at row {start}
          </caption>
          <thead>
            <tr>
              {shownColumns.map((column) => (
                <GridHeaderCell
                  key={column.name}
                  column={column}
                  sort={sort}
                  desc={desc}
                  onSort={handleSort}
                />
              ))}
              <th scope="col">
                <span className="sr-only">Row status</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row) => (
              <tr
                key={row.id}
                className={
                  [row.error ? 'row-degraded' : '', selectedId === row.id ? 'row-selected' : '']
                    .filter(Boolean)
                    .join(' ') || undefined
                }
                aria-selected={onSelectRow ? selectedId === row.id : undefined}
                tabIndex={onSelectRow ? 0 : undefined}
                onClick={onSelectRow ? () => onSelectRow(row.id) : undefined}
                onKeyDown={
                  onSelectRow
                    ? (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault()
                          onSelectRow(row.id)
                        }
                      }
                    : undefined
                }
              >
                {shownColumns.map((column) => (
                  <td key={column.name} title={(row.values[column.name] ?? []).join(', ')}>
                    {renderCell(column, row.values[column.name] ?? [])}
                  </td>
                ))}
                <td className="cell-row-status">
                  {row.error ? (
                    <span className="badge badge-error" title={row.error}>
                      Error
                    </span>
                  ) : (
                    <span className="sr-only">OK</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid-footer">
        <span>
          Rows {start}–{rangeEnd}
          {data.page.mayHaveMore && <span className="badge badge-more">more may exist</span>}
        </span>
        <div className="grid-pager">
          <button type="button" className="btn" onClick={handlePrevious} disabled={start <= 1}>
            Previous
          </button>
          <button
            type="button"
            className="btn"
            onClick={handleNext}
            disabled={!data.page.mayHaveMore}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  )
}

interface GridHeaderCellProps {
  column: GridColumn
  sort: string
  desc: boolean
  onSort: (name: string) => void
}

function GridHeaderCell({ column, sort, desc, onSort }: GridHeaderCellProps) {
  const isSorted = sort === column.name
  const ariaSort: 'ascending' | 'descending' | 'none' = isSorted
    ? desc
      ? 'descending'
      : 'ascending'
    : 'none'

  return (
    <th scope="col" aria-sort={ariaSort}>
      <button type="button" className="sort-button" onClick={() => onSort(column.name)}>
        {column.label}
        {isSorted ? (
          <span className="sort-indicator">{desc ? <SortDesc /> : <SortAsc />}</span>
        ) : (
          <span className="sort-indicator sort-indicator-idle">
            <SortNone />
          </span>
        )}
      </button>
    </th>
  )
}
