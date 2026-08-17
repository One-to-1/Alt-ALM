import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { GridColumn, TreeRow } from '../api/client.ts'
import { ApiError, fetchTreeRoots, fetchTreeRows } from '../api/client.ts'
import { renderCell } from '../grid/renderers.tsx'
import { ChevronDown, ChevronRight, Doc, FolderIcon } from '../shell/icons.tsx'
import './TreeGrid.css'

interface Props {
  project: string
  /** Tree collection, e.g. "requirements". */
  collection: string
  selectedId: string | null
  onSelect: (row: TreeRow) => void
  /** Scope the flat grid to this node. Double-click, never a plain click. */
  onOpenInGrid?: (row: TreeRow) => void
  /**
   * Fired from an effect once this project's columns are known.
   *
   * It is a prop rather than something the parent derives inside `renderToolbar`, because doing it
   * there means updating the parent's state *during this component's render* — React 19 warns, and
   * the update is not guaranteed to be applied.
   */
  onColumnsLoaded?: (columns: GridColumn[]) => void
  /** Column names to render beside the tree column, in metadata order. */
  visibleColumns?: string[]
  renderToolbar?: (columns: GridColumn[]) => React.ReactNode
}

/** Children keyed by parent id; absent = not fetched, empty = fetched and childless. */
type ChildMap = Record<string, TreeRow[]>

/** One rendered line: a node plus how deep it sits. */
interface Line {
  row: TreeRow
  depth: number
}

/**
 * ALM's Requirements module rendered faithfully: ONE table whose first column indents and expands,
 * with the ordinary metadata-driven columns beside it.
 *
 * This is not a tree next to a grid. Treating those as alternatives was the wrong model — in ALM
 * you read a requirement's ID, cover status and owner *while* looking at where it sits in the
 * hierarchy, and a view that shows one or the other makes that impossible.
 */
export function TreeGrid({
  project,
  collection,
  selectedId,
  onSelect,
  onOpenInGrid,
  onColumnsLoaded,
  visibleColumns,
  renderToolbar,
}: Props) {
  const [root, setRoot] = useState<TreeRow | null>(null)
  const [rootName, setRootName] = useState<string>('')
  const [columns, setColumns] = useState<GridColumn[]>([])
  const [children, setChildren] = useState<ChildMap>({})
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set())
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)

  // Parents already fetched or in flight. A ref so the look-ahead prefetch does not re-trigger
  // itself when it lands — that would be an endless fetch loop, one level spawning the next.
  const fetchedRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setRoot(null)
    setColumns([])
    setChildren({})
    setExpanded(new Set())
    setError(null)
    fetchedRef.current = new Set()

    fetchTreeRoots(project)
      .then((roots) => {
        if (cancelled) return
        const mine = roots.find((r) => r.collection === collection)
        if (!mine?.root) {
          setError(mine?.error ?? `${collection} is not a tree in this project.`)
          setStatus('error')
          return
        }
        setRootName(mine.root.name)
        setRoot({
          id: mine.root.id,
          parentId: '',
          hasChildren: true,
          values: { id: [mine.root.id], name: [mine.root.name] },
          error: null,
        })
        setStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof ApiError ? err.message : 'Could not load the tree.')
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [project, collection])

  const loadLevel = useCallback(
    (parentIds: string[], visible: boolean) => {
      const wanted = parentIds.filter((id) => !fetchedRef.current.has(id))
      if (wanted.length === 0) return
      for (const id of wanted) fetchedRef.current.add(id)

      if (visible) {
        setLoadingIds((prev) => {
          const next = new Set(prev)
          for (const id of wanted) next.add(id)
          return next
        })
      }

      fetchTreeRows(project, collection, wanted)
        .then((result) => {
          setColumns((prev) => (prev.length > 0 ? prev : result.columns))
          // Seed every requested parent with [] so "fetched and childless" is distinguishable
          // from "never fetched" — otherwise an empty folder retries forever.
          const grouped: ChildMap = {}
          for (const id of wanted) grouped[id] = []
          for (const node of result.nodes) {
            ;(grouped[node.parentId] ??= []).push(node)
          }
          setChildren((prev) => ({ ...prev, ...grouped }))

          // Look ahead one level so expanding is instant.
          const next = result.nodes.filter((n) => n.hasChildren).map((n) => n.id)
          if (next.length > 0) loadLevel(next, false)
        })
        .catch(() => {
          // Clearing the marks lets a later expand retry rather than failing permanently.
          for (const id of wanted) fetchedRef.current.delete(id)
          setChildren((prev) => {
            const next = { ...prev }
            for (const id of wanted) next[id] ??= []
            return next
          })
        })
        .finally(() => {
          if (!visible) return
          setLoadingIds((prev) => {
            const next = new Set(prev)
            for (const id of wanted) next.delete(id)
            return next
          })
        })
    },
    [project, collection],
  )

  // Open the root once, so the table is never a single collapsed line.
  useEffect(() => {
    if (root && !fetchedRef.current.has(root.id)) {
      setExpanded(new Set([root.id]))
      loadLevel([root.id], true)
    }
  }, [root, loadLevel])

  useEffect(() => {
    if (columns.length > 0) onColumnsLoaded?.(columns)
  }, [columns, onColumnsLoaded])

  const setOpen = useCallback(
    (row: TreeRow, open: boolean) => {
      setExpanded((prev) => {
        const next = new Set(prev)
        if (open) next.add(row.id)
        else next.delete(row.id)
        return next
      })
      if (open) loadLevel([row.id], true)
    },
    [loadLevel],
  )

  /** Selecting opens, and never closes: a row you are reading must not vanish under the click. */
  const handleSelect = useCallback(
    (row: TreeRow) => {
      onSelect(row)
      if (row.hasChildren && !expanded.has(row.id)) setOpen(row, true)
    },
    [onSelect, expanded, setOpen],
  )

  /** Flatten the open parts of the hierarchy into the row list the table renders. */
  const lines = useMemo(() => {
    if (!root) return []
    const out: Line[] = [{ row: root, depth: 0 }]
    const walk = (parentId: string, depth: number) => {
      if (!expanded.has(parentId)) return
      for (const child of children[parentId] ?? []) {
        out.push({ row: child, depth })
        walk(child.id, depth + 1)
      }
    }
    walk(root.id, 1)
    return out
  }, [root, children, expanded])

  const shownColumns = useMemo(() => {
    // The name column IS the tree column, so it never repeats as an ordinary column.
    const rest = columns.filter((c) => c.name !== 'name')
    if (!visibleColumns || visibleColumns.length === 0) return rest
    // Follow the CHOSEN order, not metadata order — ALM reads Req ID, Direct Cover Status,
    // Initiator, Modified in that sequence, and metadata order scrambles it.
    const byName = new Map(rest.map((c) => [c.name, c]))
    return visibleColumns
      .map((n) => byName.get(n))
      .filter((c): c is GridColumn => c !== undefined)
  }, [columns, visibleColumns])

  if (status === 'loading') {
    return (
      <div className="treegrid">
        <div className="treegrid-skeleton" role="status" aria-label="Loading tree">
          {Array.from({ length: 12 }, (_, i) => (
            <div key={i} className="treegrid-skeleton-row" />
          ))}
        </div>
      </div>
    )
  }

  if (status === 'error' || !root) {
    return (
      <div className="treegrid">
        <div className="treegrid-state" role="alert">
          <p className="treegrid-state-title">Tree unavailable</p>
          <p>{error}</p>
        </div>
      </div>
    )
  }

  return (
    <div className="treegrid">
      {renderToolbar && <div className="grid-toolbar">{renderToolbar(columns)}</div>}

      <div className="treegrid-scroll">
        <table>
          <caption className="sr-only">
            {collection} hierarchy under {rootName}
          </caption>
          <thead>
            <tr>
              <th scope="col" className="treegrid-namecol">
                Name
              </th>
              {shownColumns.map((c) => (
                <th scope="col" key={c.name}>
                  {c.label || c.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {lines.map(({ row, depth }) => {
              const isOpen = expanded.has(row.id)
              const isSelected = selectedId === row.id
              const isLoading = loadingIds.has(row.id)
              const name = row.values['name']?.[0] ?? `#${row.id}`

              return (
                <tr
                  key={row.id}
                  className={
                    [row.error ? 'row-degraded' : '', isSelected ? 'row-selected' : '']
                      .filter(Boolean)
                      .join(' ') || undefined
                  }
                  aria-selected={isSelected}
                  tabIndex={0}
                  onClick={() => handleSelect(row)}
                  onDoubleClick={() => onOpenInGrid?.(row)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      handleSelect(row)
                    } else if (e.key === 'ArrowRight' && row.hasChildren && !isOpen) {
                      setOpen(row, true)
                    } else if (e.key === 'ArrowLeft' && isOpen) {
                      setOpen(row, false)
                    }
                  }}
                >
                  {/* The flex row lives in a DIV inside the cell, not on the cell itself:
                      `display:flex` on a <td> drops it out of the table layout algorithm, so the
                      cell stops tracking its column's width and the row tint breaks mid-row. */}
                  <td className="treegrid-namecell">
                    <div className="treegrid-nameflex">
                      <span
                        className="treegrid-indent"
                        style={{ width: `${depth * 16}px` }}
                        aria-hidden="true"
                      />
                      {row.hasChildren ? (
                        <button
                          type="button"
                          className="treegrid-twisty"
                          aria-label={isOpen ? `Collapse ${name}` : `Expand ${name}`}
                          aria-expanded={isOpen}
                          onClick={(e) => {
                            e.stopPropagation()
                            setOpen(row, !isOpen)
                          }}
                        >
                          {isOpen ? <ChevronDown /> : <ChevronRight />}
                        </button>
                      ) : (
                        <span className="treegrid-twisty treegrid-twisty-leaf" aria-hidden="true" />
                      )}
                      <span
                        className={`treegrid-icon${row.hasChildren ? ' is-folder' : ''}`}
                        aria-hidden="true"
                      >
                        {row.hasChildren ? <FolderIcon open={isOpen} /> : <Doc />}
                      </span>
                      <span className="treegrid-name" title={name}>
                        {name}
                      </span>
                      {isLoading && <span className="treegrid-spinner" aria-hidden="true" />}
                    </div>
                  </td>

                  {shownColumns.map((c) => (
                    <td key={c.name} title={(row.values[c.name] ?? []).join(', ')}>
                      {renderCell(c, row.values[c.name] ?? [])}
                    </td>
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="treegrid-footer">
        <span>
          {lines.length - 1} {lines.length === 2 ? 'row' : 'rows'} shown
        </span>
      </div>
    </div>
  )
}
