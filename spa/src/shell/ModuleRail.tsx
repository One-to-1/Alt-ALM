import { useEffect, useState } from 'react'
import type { ModuleItem, ModuleRail as Rail } from '../api/client.ts'
import { fetchModules } from '../api/client.ts'
import './ModuleRail.css'

interface Props {
  /** The collection currently open. */
  active: string
  /** Collections this build actually renders a screen for. */
  rendered: readonly string[]
  onSelect: (collection: string) => void
}

/**
 * ALM's left navigation rail — Dashboard / Management / Requirements / Testing / Defects.
 *
 * <h2>Nothing here is a dead link, and that is the whole design</h2>
 *
 * The stock rail lists modules Alt-ALM cannot open, and they are not all unavailable for the same
 * reason. An entry is disabled for one of three reasons, and they are rendered differently because
 * they mean different things to whoever is reading:
 *
 * - **Not built** — a documented read path exists and nobody has written the screen. It will arrive.
 * - **Needs the OTA sidecar** — reachable only over COM, so it stays unavailable in any deployment
 *   without the Windows sidecar, however much of Alt-ALM gets built.
 * - **No API** — no documented endpoint reaches it at all. Libraries is this. It is not waiting on
 *   anyone.
 *
 * Showing all three as a generic greyed-out item would promise, of the third kind, something the
 * documented API cannot deliver. Every disabled entry carries the evidence for its verdict in its
 * tooltip, so "why can't I click this" is answerable without reading a probe log.
 *
 * <h2>Two conditions to be navigable</h2>
 *
 * The server says whether the API can reach an entry; this component knows whether the SPA has a
 * screen for it. Both must hold. Neither side can answer alone, which is why `rendered` is a prop
 * rather than something the server tries to guess.
 */
export function ModuleRail({ active, rendered, onSelect }: Props) {
  const [rail, setRail] = useState<Rail | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchModules()
      .then((r) => {
        if (!cancelled) setRail(r)
      })
      .catch(() => {
        // A rail that failed to load leaves the module bar as the only navigation, which still
        // works. Failing the whole app over the nav would be worse than losing it.
        if (!cancelled) setRail(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (!rail) return null

  return (
    <nav className="rail-modules" aria-label="Modules">
      {rail.groups.map((group, i) => (
        <div className="rail-modules-group" key={group.name || `ungrouped-${i}`}>
          {group.name && <h2 className="rail-modules-heading">{group.name}</h2>}
          <ul className="rail-modules-list">
            {group.items.map((item) => (
              <ModuleEntry
                key={item.key}
                item={item}
                active={active}
                rendered={rendered}
                onSelect={onSelect}
              />
            ))}
          </ul>
        </div>
      ))}
    </nav>
  )
}

interface EntryProps {
  item: ModuleItem
  active: string
  rendered: readonly string[]
  onSelect: (collection: string) => void
}

function ModuleEntry({ item, active, rendered, onSelect }: EntryProps) {
  const navigable = item.reach === 'READABLE' && rendered.includes(item.collection)
  const isActive = navigable && item.collection === active

  if (navigable) {
    return (
      <li>
        <button
          type="button"
          className={`rail-module${isActive ? ' is-active' : ''}`}
          aria-current={isActive ? 'page' : undefined}
          onClick={() => onSelect(item.collection)}
        >
          {item.label}
        </button>
      </li>
    )
  }

  // The API can reach it but this build has no screen — a different sentence from the server's.
  const noScreen = item.reach === 'READABLE'
  const state = noScreen ? 'BUILDABLE' : item.reach
  const reason = noScreen
    ? 'The read path works; this build has no screen for it yet.'
    : item.reason

  return (
    <li>
      <span
        className={`rail-module is-disabled state-${state.toLowerCase()}`}
        aria-disabled="true"
        title={`${item.label} — ${BADGE[state]}. ${reason}`}
      >
        {item.label}
        <span className="rail-module-badge">{BADGE[state]}</span>
      </span>
    </li>
  )
}

/**
 * The short form of each verdict.
 *
 * Worded so the three are not interchangeable at a glance: "not yet" is a schedule, "sidecar" is a
 * deployment requirement, "no API" is a permanent property of the product.
 */
const BADGE: Record<string, string> = {
  BUILDABLE: 'not yet',
  NEEDS_SIDECAR: 'sidecar',
  NO_API: 'no API',
}
