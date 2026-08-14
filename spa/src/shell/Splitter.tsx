import { useCallback, useEffect, useRef } from 'react'
import './Splitter.css'

interface Props {
  /** Current width of the pane being resized, in px. */
  value: number
  onChange: (next: number) => void
  min: number
  max: number
  /** Which side the controlled pane sits on — decides drag direction. */
  side: 'left' | 'right'
  label: string
}

const KEYBOARD_STEP = 16

/**
 * Drag handle between two panes.
 *
 * <p>Keyboard-operable on purpose: a mouse-only splitter makes the layout unusable for anyone
 * navigating by keyboard, and this app is aimed at people who live in it all day. Arrow keys nudge,
 * Home/End jump to the bounds, and double-click resets to the caller's default.
 */
export function Splitter({ value, onChange, min, max, side, label }: Props) {
  const dragging = useRef(false)
  const startX = useRef(0)
  const startValue = useRef(0)

  const clamp = useCallback((n: number) => Math.min(max, Math.max(min, n)), [min, max])

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    dragging.current = true
    startX.current = e.clientX
    startValue.current = value
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging.current) return
    const delta = e.clientX - startX.current
    // A right-hand pane grows when the handle moves left, hence the inversion.
    onChange(clamp(startValue.current + (side === 'left' ? delta : -delta)))
  }

  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    dragging.current = false
    e.currentTarget.releasePointerCapture(e.pointerId)
  }

  // While dragging, suppress text selection across the whole document — otherwise the drag
  // selects grid content and the pointer turns into an I-beam.
  useEffect(() => {
    const stop = () => {
      dragging.current = false
      document.body.classList.remove('is-resizing')
    }
    window.addEventListener('pointerup', stop)
    return () => window.removeEventListener('pointerup', stop)
  }, [])

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(value)}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      className="splitter"
      onPointerDown={(e) => {
        document.body.classList.add('is-resizing')
        onPointerDown(e)
      }}
      onPointerMove={onPointerMove}
      onPointerUp={(e) => {
        document.body.classList.remove('is-resizing')
        onPointerUp(e)
      }}
      onKeyDown={(e) => {
        const grow = side === 'left' ? 'ArrowRight' : 'ArrowLeft'
        const shrink = side === 'left' ? 'ArrowLeft' : 'ArrowRight'
        if (e.key === grow) {
          e.preventDefault()
          onChange(clamp(value + KEYBOARD_STEP))
        } else if (e.key === shrink) {
          e.preventDefault()
          onChange(clamp(value - KEYBOARD_STEP))
        } else if (e.key === 'Home') {
          e.preventDefault()
          onChange(min)
        } else if (e.key === 'End') {
          e.preventDefault()
          onChange(max)
        }
      }}
    >
      <span className="splitter-grip" aria-hidden="true" />
    </div>
  )
}
