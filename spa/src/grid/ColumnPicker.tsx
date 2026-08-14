import { useEffect, useMemo, useRef, useState } from 'react'
import type { GridColumn } from '../api/client.ts'
import './ColumnPicker.css'

interface Props {
  columns: GridColumn[]
  visible: string[]
  onChange: (next: string[]) => void
  onReset: () => void
}

/**
 * Chooses which of an entity's columns the grid renders.
 *
 * <p>This is not a nicety. A requirement in a real project carries 76 columns and a defect 47, so
 * an unfiltered grid is horizontally unreadable and most of it is empty. ALM's own client solves
 * the same problem the same way.
 */
export function ColumnPicker({ columns, visible, onChange, onReset }: Props) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const ref = useRef<HTMLDivElement>(null)

  // Close on outside click / Escape — a panel that traps the user is worse than no panel.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const visibleSet = useMemo(() => new Set(visible), [visible])

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (needle === '') return columns
    return columns.filter(
      (c) =>
        (c.label || '').toLowerCase().includes(needle) || c.name.toLowerCase().includes(needle),
    )
  }, [columns, filter])

  const toggle = (name: string) => {
    if (visibleSet.has(name)) {
      // Never allow zero columns — an empty grid reads as a failed load.
      if (visible.length === 1) return
      onChange(visible.filter((n) => n !== name))
    } else {
      // Keep metadata order rather than click order, so the grid stays stable.
      const next = columns.filter((c) => visibleSet.has(c.name) || c.name === name).map((c) => c.name)
      onChange(next)
    }
  }

  return (
    <div className="colpick" ref={ref}>
      <button
        type="button"
        className="colpick-trigger"
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((o) => !o)}
      >
        Columns
        <span className="colpick-count">
          {visible.length}/{columns.length}
        </span>
      </button>

      {open && (
        <div className="colpick-panel" role="dialog" aria-label="Choose columns">
          <div className="colpick-head">
            <label className="sr-only" htmlFor="colpick-filter">
              Find a column
            </label>
            <input
              id="colpick-filter"
              type="search"
              placeholder="Find a column…"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              autoFocus
            />
          </div>

          <div className="colpick-actions">
            <button type="button" onClick={() => onChange(columns.map((c) => c.name))}>
              All
            </button>
            <button type="button" onClick={onReset}>
              Reset
            </button>
            <span className="colpick-hint">{visible.length} shown</span>
          </div>

          <ul className="colpick-list">
            {shown.map((c) => {
              const checked = visibleSet.has(c.name)
              return (
                <li key={c.name}>
                  <label className="colpick-item" title={`${c.name} · ${c.type}`}>
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggle(c.name)}
                      disabled={checked && visible.length === 1}
                    />
                    <span className="colpick-label">{c.label || c.name}</span>
                    <span className="colpick-type">{c.type.toLowerCase().replace('_', ' ')}</span>
                  </label>
                </li>
              )
            })}
            {shown.length === 0 && <li className="colpick-empty">No column matches that.</li>}
          </ul>
        </div>
      )}
    </div>
  )
}
