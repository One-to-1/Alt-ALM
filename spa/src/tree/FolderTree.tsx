import { useCallback, useEffect, useRef, useState } from 'react'
import type { TreeNode } from '../api/client.ts'
import { ApiError, fetchTreeChildren, fetchTreeRoots } from '../api/client.ts'
import { ChevronDown, ChevronRight, Doc, Folder } from '../shell/icons.tsx'
import './FolderTree.css'

interface Props {
  project: string
  /** Tree collection, e.g. "requirements". */
  collection: string
  selectedId: string | null
  /** Fires on every node click — the detail pane follows the tree cursor. */
  onSelect: (node: TreeNode | null) => void
  /** Fires when the user asks to see a node's rows in the grid. Never on plain selection. */
  onOpenInGrid?: (node: TreeNode) => void
}

/** Children keyed by parent id; absent = not fetched, empty = fetched and childless. */
type ChildMap = Record<string, TreeNode[]>

export function FolderTree({ project, collection, selectedId, onSelect, onOpenInGrid }: Props) {
  const [root, setRoot] = useState<TreeNode | null>(null)
  const [rootError, setRootError] = useState<string | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [children, setChildren] = useState<ChildMap>({})
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set())

  // Parents already fetched or in flight. A ref, not state, because the prefetch must not re-run
  // when it lands — that would be a fetch loop, each level triggering the next forever.
  const fetchedRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setRoot(null)
    setRootError(null)
    setChildren({})
    setExpanded(new Set())
    fetchedRef.current = new Set()

    fetchTreeRoots(project)
      .then((roots) => {
        if (cancelled) return
        const mine = roots.find((r) => r.collection === collection)
        if (!mine) {
          setRootError(`${collection} is not a tree in this project.`)
          setStatus('ready')
          return
        }
        if (!mine.root) {
          setRootError(mine.error ?? 'This tree could not be resolved.')
          setStatus('ready')
          return
        }
        setRoot(mine.root)
        setStatus('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setRootError(err instanceof ApiError ? err.message : 'Could not load the folder tree.')
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [project, collection])

  /**
   * Fetch one whole level at a time.
   *
   * `visible` drives the spinner; a prefetch passes false so a background look-ahead never makes
   * rows flicker into a loading state the user did not ask for.
   */
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

      fetchTreeChildren(project, collection, wanted)
        .then((result) => {
          // Seed every requested parent with [] first, so a parent that came back with no rows is
          // recorded as "fetched and childless" rather than staying indistinguishable from unfetched.
          const grouped: ChildMap = {}
          for (const id of wanted) grouped[id] = []
          for (const node of result.nodes) {
            const key = node.parentId ?? ''
            ;(grouped[key] ??= []).push(node)
          }
          setChildren((prev) => ({ ...prev, ...grouped }))

          // Look ahead one level: fetch the children of everything just revealed that can expand.
          // By the time the user clicks a twisty the rows are already here, so expanding is instant.
          const next = result.nodes.filter((n) => n.hasChildren).map((n) => n.id)
          if (next.length > 0) loadLevel(next, false)
        })
        .catch(() => {
          // A level that fails renders as empty rather than taking down the tree. Clearing the
          // fetched marks lets a later expand retry instead of the failure being permanent.
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

  // Open the root once so the tree is never a single collapsed line.
  useEffect(() => {
    if (root && !fetchedRef.current.has(root.id)) {
      setExpanded(new Set([root.id]))
      loadLevel([root.id], true)
    }
  }, [root, loadLevel])

  const setOpen = useCallback(
    (node: TreeNode, open: boolean) => {
      setExpanded((prev) => {
        const next = new Set(prev)
        if (open) next.add(node.id)
        else next.delete(node.id)
        return next
      })
      if (open) loadLevel([node.id], true)
    },
    [loadLevel],
  )

  /**
   * A click selects, and opens the node if it was closed.
   *
   * It deliberately does not toggle: clicking a row you are reading should never collapse it out
   * from under you. Closing is the twisty's job, which is also the only control whose label says so.
   */
  const handleSelect = useCallback(
    (node: TreeNode) => {
      onSelect(node)
      if (node.hasChildren && !expanded.has(node.id)) setOpen(node, true)
    },
    [onSelect, expanded, setOpen],
  )

  if (status === 'loading') {
    return (
      <div className="tree-skeleton" role="status" aria-label="Loading folders">
        {Array.from({ length: 7 }, (_, i) => (
          <div key={i} className="tree-skeleton-row" style={{ marginInlineStart: `${(i % 3) * 14}px` }} />
        ))}
      </div>
    )
  }
  if (rootError && !root) {
    return (
      <div className="tree-state tree-state-error" role="alert">
        <strong>Tree unavailable</strong>
        <span>{rootError}</span>
      </div>
    )
  }
  if (!root) {
    return <div className="tree-state">This project has no {collection} tree.</div>
  }

  return (
    <nav className="tree" aria-label={`${collection} folders`}>
      <ul role="tree" className="tree-list">
        <TreeItem
          node={root}
          depth={0}
          expanded={expanded}
          childMap={children}
          loadingIds={loadingIds}
          selectedId={selectedId}
          onSetOpen={setOpen}
          onSelect={handleSelect}
          onOpenInGrid={onOpenInGrid}
        />
      </ul>
    </nav>
  )
}

interface ItemProps {
  node: TreeNode
  depth: number
  expanded: Set<string>
  childMap: ChildMap
  loadingIds: Set<string>
  selectedId: string | null
  onSetOpen: (node: TreeNode, open: boolean) => void
  onSelect: (node: TreeNode) => void
  onOpenInGrid?: (node: TreeNode) => void
}

function TreeItem({
  node,
  depth,
  expanded,
  childMap,
  loadingIds,
  selectedId,
  onSetOpen,
  onSelect,
  onOpenInGrid,
}: ItemProps) {
  const isOpen = expanded.has(node.id)
  const kids = childMap[node.id]
  const isLoading = loadingIds.has(node.id)
  const isSelected = selectedId === node.id

  return (
    <li role="none" className="tree-item">
      <div
        role="treeitem"
        aria-expanded={node.hasChildren ? isOpen : undefined}
        aria-selected={isSelected}
        aria-level={depth + 1}
        tabIndex={isSelected ? 0 : -1}
        className={`tree-row${isSelected ? ' is-selected' : ''}`}
        style={{ paddingInlineStart: `${depth * 15 + 6}px` }}
        onClick={() => onSelect(node)}
        onDoubleClick={() => onOpenInGrid?.(node)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            onSelect(node)
          } else if (e.key === 'ArrowRight' && node.hasChildren && !isOpen) {
            onSetOpen(node, true)
          } else if (e.key === 'ArrowLeft' && isOpen) {
            onSetOpen(node, false)
          }
        }}
      >
        {node.hasChildren ? (
          <button
            type="button"
            className="tree-twisty"
            aria-label={isOpen ? `Collapse ${node.name}` : `Expand ${node.name}`}
            onClick={(e) => {
              e.stopPropagation()
              onSetOpen(node, !isOpen)
            }}
          >
            {isOpen ? <ChevronDown /> : <ChevronRight />}
          </button>
        ) : (
          // Leaves keep the twisty's width so names stay on one vertical rule.
          <span className="tree-twisty tree-twisty-leaf" aria-hidden="true" />
        )}

        <span className="tree-icon" aria-hidden="true">
          {node.hasChildren ? <Folder /> : <Doc />}
        </span>

        <span className="tree-name" title={node.name}>
          {node.name}
        </span>

        {isLoading && <span className="tree-spinner" aria-hidden="true" />}
      </div>

      {isOpen && kids && kids.length > 0 && (
        <ul role="group" className="tree-list">
          {kids.map((child) => (
            <TreeItem
              key={child.id}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              childMap={childMap}
              loadingIds={loadingIds}
              selectedId={selectedId}
              onSetOpen={onSetOpen}
              onSelect={onSelect}
              onOpenInGrid={onOpenInGrid}
            />
          ))}
        </ul>
      )}

      {isOpen && kids && kids.length === 0 && !isLoading && (
        <div className="tree-empty" style={{ paddingInlineStart: `${(depth + 1) * 15 + 27}px` }}>
          No child records
        </div>
      )}
    </li>
  )
}
