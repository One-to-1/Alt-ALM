import { useEffect, useState } from 'react'
import type { GridColumn, GroupBucket } from '../api/client.ts'
import { ApiError, fetchGroups } from '../api/client.ts'
import './GroupBar.css'

interface Props {
  project: string
  collection: string
  columns: GridColumn[]
  /** The field currently grouped on, or null for no grouping. */
  field: string | null
  onField: (field: string | null) => void
  /** The bucket drilled into, or null for "all groups". */
  value: string | null
  onValue: (value: string | null) => void
}

type Status = 'idle' | 'loading' | 'ready' | 'error'

/**
 * ALM's Group By, as a counted bucket strip.
 *
 * <h2>Why counts here are trustworthy when the grid's are not</h2>
 *
 * Everywhere else this app refuses to print a total, because ALM's `TotalResults` describes the page
 * rather than the collection (probe 15 measured it reporting 0 for a populated collection). The
 * group endpoint is the exception: `groups/{field}` returns a real `size` per bucket, server-side.
 * So this is the only place in Alt-ALM that can honestly say how many records match something — and
 * it is worth having for exactly that reason.
 *
 * <h2>Drill-in re-queries, it does not filter the loaded page</h2>
 *
 * Clicking a bucket adds a filter and re-reads. Filtering the rows already on screen would have been
 * cheaper and wrong: the grid holds one page, so a 117-row bucket would show only the part of itself
 * that happened to be loaded, with a count beside it saying 117.
 */
export function GroupBar({
  project,
  collection,
  columns,
  field,
  onField,
  value,
  onValue,
}: Props) {
  const [buckets, setBuckets] = useState<GroupBucket[]>([])
  const [status, setStatus] = useState<Status>('idle')
  const [error, setError] = useState<string | null>(null)

  // ALM's own Groupable flag decides what is offered — not the field type. A project can turn
  // grouping off for a field, and offering it anyway would produce a control that errors on use.
  const groupable = columns.filter((c) => c.groupable)

  useEffect(() => {
    if (!field) {
      setBuckets([])
      setStatus('idle')
      return
    }
    let cancelled = false
    setStatus('loading')
    setError(null)
    fetchGroups(project, collection, field)
      .then((result) => {
        if (cancelled) return
        // Biggest first: the useful question is almost always "what is most of this?"
        setBuckets([...result].sort((a, b) => b.size - a.size))
        setStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof ApiError ? err.message : 'Could not group by this field.')
        setStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [project, collection, field])

  if (groupable.length === 0) return null

  const total = buckets.reduce((sum, b) => sum + b.size, 0)

  return (
    <div className="groupbar">
      <label className="groupbar-field">
        <span className="groupbar-label">Group by</span>
        <select
          className="field"
          value={field ?? ''}
          onChange={(e) => {
            onField(e.target.value === '' ? null : e.target.value)
            onValue(null)
          }}
        >
          <option value="">None</option>
          {groupable.map((c) => (
            <option key={c.name} value={c.name}>
              {c.label || c.name}
            </option>
          ))}
        </select>
      </label>

      {status === 'loading' && <span className="groupbar-note">Counting…</span>}

      {status === 'error' && (
        <span className="groupbar-note groupbar-error" role="alert">
          {error}
        </span>
      )}

      {status === 'ready' && buckets.length > 0 && (
        <div className="groupbar-chips" role="group" aria-label="Groups">
          <button
            type="button"
            className={`groupbar-chip${value === null ? ' is-active' : ''}`}
            aria-pressed={value === null}
            onClick={() => onValue(null)}
          >
            All
            {/* A real count, unlike anything else in this app — see the component comment. */}
            <span className="groupbar-count">{total}</span>
          </button>
          {buckets.map((b) => (
            <button
              key={b.value}
              type="button"
              className={`groupbar-chip${value === b.value ? ' is-active' : ''}`}
              aria-pressed={value === b.value}
              onClick={() => onValue(value === b.value ? null : b.value)}
              title={`${b.label} — ${b.size} ${b.size === 1 ? 'record' : 'records'}`}
            >
              {b.label || '(empty)'}
              <span className="groupbar-count">{b.size}</span>
            </button>
          ))}
        </div>
      )}

      {status === 'ready' && buckets.length === 0 && (
        <span className="groupbar-note">No values to group by on this field.</span>
      )}
    </div>
  )
}
