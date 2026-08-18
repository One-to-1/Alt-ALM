import { useCallback, useEffect, useRef, useState } from 'react'
import { Pin } from '../shell/icons.tsx'
import { readString, writeString } from '../shell/prefs.ts'
import './DetailRail.css'

/** One entry in the rail. */
export interface RailTab {
  id: string
  label: string
  icon: React.ReactNode
  /**
   * Whether this tab holds anything on the open record.
   *
   * Three states, and the third is the point: `true` marks it, `false` leaves it plain, and
   * **`undefined` means we do not know** — a probe that failed, or a tab whose contents cost too
   * much to check. Unknown renders exactly like plain, because an unmarked tab claims nothing while
   * a tab marked empty claims something we cannot support.
   */
  filled?: boolean
}

interface Props {
  tabs: RailTab[]
  active: string
  onSelect: (id: string) => void
}

const PINNED_KEY = 'detailRailPinned'

/**
 * The detail pane's tab strip, as a collapsing icon rail.
 *
 * <h2>Why a rail and not a strip</h2>
 *
 * A record here can have fifteen tabs — nine memo fields in this project alone, plus Details, Risk
 * Analysis, History, All fields and however many related-entity tabs its relations produce. Laid out
 * horizontally that wraps to three rows and eats the top third of the pane, and the pane is already
 * competing with the grid for width. Vertically it costs 40px and never wraps however many tabs a
 * project defines — which matters, because the count is per-project and not ours to bound.
 *
 * <h2>Populated marks</h2>
 *
 * ALM's own dialog does this: it blues the tabs that hold something and leaves the rest plain, so a
 * long strip stays scannable without opening each one. Reproduced here, with the honest third state
 * above — see {@link RailTab.filled}.
 *
 * <h2>Open, and pinned</h2>
 *
 * Two different things, deliberately.
 *
 * - **Open** is transient: hover or click, and it overlays the body so nothing reflows underneath.
 * - **Pinned** is a preference: double-click, and it takes its own column, pushing the body aside.
 *
 * Pinning persists across records, projects and reloads, because a user who has said "keep this
 * open" has said it about the app, not about the requirement they happened to be reading.
 */
export function DetailRail({ tabs, active, onSelect }: Props) {
  const [pinned, setPinned] = useState(
    () => readString<'yes' | 'no'>(PINNED_KEY, 'no', ['yes', 'no']) === 'yes',
  )
  const [hovered, setHovered] = useState(false)

  useEffect(() => writeString(PINNED_KEY, pinned ? 'yes' : 'no'), [pinned])

  // Kept in a ref so both handlers stay referentially stable: they are attached to every button in
  // the rail, and re-creating them whenever the parent re-renders would churn the whole strip.
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  // Selection is immediate, NOT debounced against the double-click.
  //
  // The first version deferred it by 180ms so a dblclick could cancel the click pair. That bought
  // nothing and cost something: selecting a tab is idempotent, so the two clicks inside a
  // double-click simply select it twice, while the delay was perceptible on every single click.
  const handleClick = useCallback((id: string) => onSelectRef.current(id), [])

  const handleDoubleClick = useCallback((id: string) => {
    // Double-click still selects — pinning while looking at a different tab from the one you
    // pointed at would read as the app ignoring half the gesture.
    onSelectRef.current(id)
    setPinned((p) => !p)
  }, [])

  const open = pinned || hovered

  return (
    <div
      className={`rail${open ? ' is-open' : ''}${pinned ? ' is-pinned' : ''}`}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="rail-tabs" role="tablist" aria-orientation="vertical" aria-label="Record sections">
        {tabs.map((tab) => {
          const isActive = tab.id === active
          return (
            <button
              key={tab.id}
              type="button"
              id={`detail-tab-${tab.id}`}
              role="tab"
              aria-selected={isActive}
              // The title is the whole label when collapsed, and the reason a mouse user is never
              // stuck guessing what an icon means without waiting for the rail to open.
              title={tab.filled ? `${tab.label} — has content` : tab.label}
              className={`rail-tab${isActive ? ' is-active' : ''}${
                tab.filled ? ' is-filled' : ''
              }`}
              onClick={() => handleClick(tab.id)}
              onDoubleClick={() => handleDoubleClick(tab.id)}
            >
              <span className="rail-icon" aria-hidden="true">
                {tab.icon}
              </span>
              {/* Always in the DOM: clipping it visually keeps the accessible name intact, whereas
                  rendering it only when open would leave a screen reader with an unnamed button
                  for as long as the rail is collapsed — which is most of the time. */}
              <span className="rail-label">{tab.label}</span>
              {tab.filled && <span className="sr-only"> (has content)</span>}
            </button>
          )
        })}
      </div>

      <button
        type="button"
        className={`rail-pin${pinned ? ' is-pinned' : ''}`}
        aria-pressed={pinned}
        title={pinned ? 'Unpin the tab rail' : 'Pin the tab rail open (or double-click a tab)'}
        onClick={() => setPinned((p) => !p)}
      >
        <span className="rail-icon" aria-hidden="true">
          <Pin filled={pinned} />
        </span>
        <span className="rail-label">{pinned ? 'Unpin' : 'Pin open'}</span>
        <span className="sr-only">{pinned ? 'Unpin the tab rail' : 'Pin the tab rail open'}</span>
      </button>
    </div>
  )
}
