import { useEffect, useState } from 'react'
import type { GridColumn, GridResponse } from '../api/client.ts'
import { ApiError, fetchGrid } from '../api/client.ts'
import { renderCell } from './renderers.tsx'
import './DataGrid.css'

const PAGE_SIZE = 50
const DEFAULT_SORT = 'id'

interface DataGridProps {
  /** "<domain>/<project>" */
  project: string
  collection: string
}

type LoadStatus = 'loading' | 'ready' | 'error'

export function DataGrid({ project, collection }: DataGridProps) {
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

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setError(null)

    fetchGrid({ collection, project, pageSize: PAGE_SIZE, start, sort, desc })
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
  }, [project, collection, start, sort, desc, retryToken])

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
      <div className="grid-status" role="status" aria-live="polite">
        Loading {collection}…
      </div>
    )
  }

  if (status === 'error' && !data) {
    return (
      <div className="grid-status grid-status-error" role="alert">
        <p>{error?.message}</p>
        {error?.retryable && (
          <button type="button" onClick={handleRetry}>
            Retry
          </button>
        )}
      </div>
    )
  }

  if (!data) {
    return null
  }

  if (data.rows.length === 0 && status === 'ready') {
    return (
      <div className="grid-status" role="status">
        No {collection} found in this project.
      </div>
    )
  }

  const rangeEnd = start + data.page.rowsReturned - 1

  return (
    <div className="data-grid">
      {!data.writable && (
        <p className="grid-note" role="status">
          This collection is read-only in Alt-ALM.
        </p>
      )}

      {status === 'loading' && (
        <div className="grid-refresh-banner" role="status" aria-live="polite">
          Refreshing…
        </div>
      )}

      {status === 'error' && (
        <div className="grid-inline-error" role="alert">
          <span>{error?.message}</span>
          {error?.retryable && (
            <button type="button" onClick={handleRetry}>
              Retry
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
              {data.columns.map((column) => (
                <GridHeaderCell key={column.name} column={column} sort={sort} desc={desc} onSort={handleSort} />
              ))}
              <th scope="col">Row status</th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row) => (
              <tr key={row.id} className={row.error ? 'row-degraded' : undefined}>
                {data.columns.map((column) => (
                  <td key={column.name}>{renderCell(column, row.values[column.name] ?? [])}</td>
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
          Showing rows {start}–{rangeEnd} ({data.page.rowsReturned} rows returned)
          {data.page.mayHaveMore && <span className="badge badge-more">more results may exist</span>}
        </span>
        <div className="grid-pager">
          <button type="button" onClick={handlePrevious} disabled={start <= 1}>
            Previous
          </button>
          <button type="button" onClick={handleNext} disabled={!data.page.mayHaveMore}>
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
  const ariaSort: 'ascending' | 'descending' | 'none' = isSorted ? (desc ? 'descending' : 'ascending') : 'none'

  return (
    <th scope="col" aria-sort={ariaSort}>
      <button type="button" className="sort-button" onClick={() => onSort(column.name)}>
        {column.label}
        {isSorted && (
          <span aria-hidden="true" className="sort-indicator">
            {desc ? '▼' : '▲'}
          </span>
        )}
      </button>
    </th>
  )
}
