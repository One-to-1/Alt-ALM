/**
 * The icon set. Authored SVG, one geometry: 16-unit box, 1.5 stroke, round caps and joins.
 *
 * These replace the Unicode glyphs the first pass used (▸ ▾ ▲ ▼ ·). Those are font characters, not
 * icons — their weight, baseline and even presence differ per platform, so a twisty that looked
 * right on Windows could arrive as a box elsewhere and never matched the stroke weight of anything
 * around it.
 *
 * Every icon inherits `currentColor` and sizes from `em`, so one CSS rule controls colour and scale.
 */

interface IconProps {
  /** Multiplier on the 1em base size. */
  size?: number
  className?: string
}

function Svg({ size = 1, className, children }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      viewBox="0 0 16 16"
      width={`${size}em`}
      height={`${size}em`}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

/** Collapsed disclosure. Rotates to ChevronDown's position via CSS, so the two stay consistent. */
export function ChevronRight(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6 3.5 10.5 8 6 12.5" />
    </Svg>
  )
}

export function ChevronDown(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M3.5 6 8 10.5 12.5 6" />
    </Svg>
  )
}

export function ChevronLeft(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M10 3.5 5.5 8 10 12.5" />
    </Svg>
  )
}

/** Ascending sort. Points the way the values run: small at the tail, large at the head. */
export function SortAsc(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 12.5V3.5M4.5 7 8 3.5 11.5 7" />
    </Svg>
  )
}

export function SortDesc(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 3.5v9M4.5 9 8 12.5 11.5 9" />
    </Svg>
  )
}

/** Sortable but not currently sorted — shown on hover so a column advertises the affordance. */
export function SortNone(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M5 6.5 8 3.5l3 3M5 9.5l3 3 3-3" />
    </Svg>
  )
}

export function Folder(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2 4.5A1.5 1.5 0 0 1 3.5 3h2.2a1 1 0 0 1 .8.4l.8 1.1h4.7A1.5 1.5 0 0 1 13.5 6v5.5a1.5 1.5 0 0 1-1.5 1.5H3.5A1.5 1.5 0 0 1 2 11.5Z" />
    </Svg>
  )
}

/** A leaf record rather than a container. */
export function Doc(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 2.5h4.5L12 6v7.5H4Z" />
      <path d="M8.5 2.5V6H12" />
    </Svg>
  )
}

export function Columns(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="2.25" y="3.25" width="11.5" height="9.5" rx="1.25" />
      <path d="M6.5 3.25v9.5M10 3.25v9.5" />
    </Svg>
  )
}

export function Close(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 4l8 8M12 4l-8 8" />
    </Svg>
  )
}

export function Search(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="7.2" cy="7.2" r="3.9" />
      <path d="m10.2 10.2 2.6 2.6" />
    </Svg>
  )
}

export function TreeView(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M3 3.5v8.5a1 1 0 0 0 1 1h2M3 7.5h3.5" />
      <rect x="8.5" y="2" width="4.5" height="3" rx="0.75" />
      <rect x="8.5" y="6" width="4.5" height="3" rx="0.75" />
      <rect x="8.5" y="10" width="4.5" height="3" rx="0.75" />
    </Svg>
  )
}

export function GridView(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="2.25" y="3.25" width="11.5" height="9.5" rx="1.25" />
      <path d="M2.25 6.5h11.5M2.25 9.75h11.5M6.5 6.5v6.25" />
    </Svg>
  )
}
