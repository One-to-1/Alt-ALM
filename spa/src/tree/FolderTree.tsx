import { useCallback, useEffect, useState } from 'react'
import type { TreeNode } from '../api/client.ts'
import { ApiError, fetchTreeChildren, fetchTreeRoots } from '../api/client.ts'
import './FolderTree.css'

interface Props {
  project: string
  /** Tree collection, e.g. "requirements". */
  collection: string
  selectedId: string | null
  onSelect: (node: TreeNode | null) => void
}

/** Children keyed by parent id; absent = never expanded, empty = expanded and childless. */
type ChildMap = Record<string, TreeNode[]>

export function FolderTree({ project, collection, selectedId, onSelect }: Props) {
  const [root, setRoot] = useState<TreeNode | null>(null)
  const [rootError, setRootError] = useState<string | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [children, setChildren] = useState<ChildMap>({})
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set())

  // Resolve the root whenever the project or collection changes. The root rule itself lives
  // server-side (AlmTreeRoots) — the client deliberately does not reimplement it.
  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setRoot(null)
    setRootError(null)
    setChildren({})
    setExpanded(new Set())

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

  const loadChildren = useCallback(
    (parentId: string) => {
      setLoadingIds((prev) => new Set(prev).add(parentId))
      fetchTreeChildren(project, collection, parentId)
        .then((result) => {
          setChildren((prev) => ({ ...prev, [parentId]: result.nodes }))
        })
        .catch(() => {
          // A folder that fails to expand shows as empty rather than taking down the tree.
          setChildren((prev) => ({ ...prev, [parentId]: [] }))
        })
        .finally(() => {
          setLoadingIds((prev) => {
            const next = new Set(prev)
            next.delete(parentId)
            return next
          })
        })
    },
    [project, collection],
  )

  const toggle = useCallback(
    (node: TreeNode) => {
      const isOpen = expanded.has(node.id)
      setExpanded((prev) => {
        const next = new Set(prev)
        if (isOpen) next.delete(node.id)
        else next.add(node.id)
        return next
      })
      if (!isOpen && children[node.id] === undefined) {
        loadChildren(node.id)
      }
    },
    [expanded, children, loadChildren],
  )

  // Auto-expand the root once, so the tree is never a single collapsed line.
  useEffect(() => {
    if (root && !expanded.has(root.id) && children[root.id] === undefined) {
      setExpanded(new Set([root.id]))
      loadChildren(root.id)
    }
  }, [root, expanded, children, loadChildren])

  if (status === 'loading') {
    return <div className="tree-state">Loading folders…</div>
  }
  if (rootError && !root) {
    return (
      <div className="tree-state tree-state-error">
        <strong>Tree unavailable</strong>
        <span>{rootError}</span>
      </div>
    )
  }
  if (!root) {
    return <div className="tree-state">No folders.</div>
  }

  return (
    <nav className="tree" aria-label={`${collection} folders`}>
      <ul role="tree" className="tree-list">
        <TreeItem
          node={root}
          depth={0}
          expanded={expanded}
          children_={children}
          loadingIds={loadingIds}
          selectedId={selectedId}
          onToggle={toggle}
          onSelect={onSelect}
        />
      </ul>
    </nav>
  )
}

interface ItemProps {
  node: TreeNode
  depth: number
  expanded: Set<string>
  children_: ChildMap
  loadingIds: Set<string>
  selectedId: string | null
  onToggle: (node: TreeNode) => void
  onSelect: (node: TreeNode | null) => void
}

function TreeItem({
  node,
  depth,
  expanded,
  children_,
  loadingIds,
  selectedId,
  onToggle,
  onSelect,
}: ItemProps) {
  const isOpen = expanded.has(node.id)
  const kids = children_[node.id]
  const isLoading = loadingIds.has(node.id)
  const isSelected = selectedId === node.id

  return (
    <li role="none" className="tree-item">
      <div
        role="treeitem"
        aria-expanded={node.hasChildren ? isOpen : undefined}
        aria-selected={isSelected}
        aria-level={depth + 1}
        tabIndex={0}
        className={`tree-row${isSelected ? ' is-selected' : ''}`}
        style={{ paddingInlineStart: `${depth * 14 + 8}px` }}
        onClick={() => onSelect(node)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            onSelect(node)
          } else if (e.key === 'ArrowRight' && node.hasChildren && !isOpen) {
            onToggle(node)
          } else if (e.key === 'ArrowLeft' && isOpen) {
            onToggle(node)
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
              onToggle(node)
            }}
          >
            {isLoading ? '·' : isOpen ? '▾' : '▸'}
          </button>
        ) : (
          <span className="tree-twisty tree-twisty-empty" aria-hidden="true" />
        )}
        <span className="tree-name" title={node.name}>
          {node.name}
        </span>
      </div>

      {isOpen && kids && kids.length > 0 && (
        <ul role="group" className="tree-list">
          {kids.map((child) => (
            <TreeItem
              key={child.id}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              children_={children_}
              loadingIds={loadingIds}
              selectedId={selectedId}
              onToggle={onToggle}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
      {isOpen && kids && kids.length === 0 && !isLoading && (
        <div className="tree-empty" style={{ paddingInlineStart: `${(depth + 1) * 14 + 22}px` }}>
          empty
        </div>
      )}
    </li>
  )
}
