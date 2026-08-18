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

/**
 * Folder that opens with its node. The lid tilting back is the same affordance as the twisty,
 * read a second way — useful when a row is scanned rather than read.
 */
export function FolderIcon({ open = false, ...props }: IconProps & { open?: boolean }) {
  if (!open) return <Folder {...props} />
  return (
    <Svg {...props}>
      <path d="M2 11.5V4.5A1.5 1.5 0 0 1 3.5 3h2.2a1 1 0 0 1 .8.4l.8 1.1h4.2A1.5 1.5 0 0 1 13 6v1" />
      <path d="M2 11.5 3.7 7.4A1 1 0 0 1 4.6 6.8h9.1a.6.6 0 0 1 .56.83l-1.6 4.1a1 1 0 0 1-.93.63H3.5A1.5 1.5 0 0 1 2 11.5Z" />
    </Svg>
  )
}

/** Explicit light choice. */
export function Sun(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="3" />
      <path d="M8 1.5v1.2M8 13.3v1.2M14.5 8h-1.2M2.7 8H1.5M12.6 3.4l-.85.85M4.25 11.75l-.85.85M12.6 12.6l-.85-.85M4.25 4.25l-.85-.85" />
    </Svg>
  )
}

/** Explicit dark choice. */
export function Moon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M13 9.6A5.6 5.6 0 0 1 6.4 3a5.6 5.6 0 1 0 6.6 6.6Z" />
    </Svg>
  )
}

/** Follow the OS — a monitor, because the choice is "whatever this machine says". */
export function Monitor(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="2" y="3" width="12" height="8" rx="1.25" />
      <path d="M6 13.5h4M8 11v2.5" />
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

/* ------------------------------------------------------------------------------------------------
 * Detail-pane rail icons.
 *
 * Keyed off the entity a tab reaches, with {@link Link} as the fallback — a project can define a
 * relation to something this build has never heard of, and the rail must still draw it.
 * ---------------------------------------------------------------------------------------------- */

/** The Details form: a record's own fields. */
export function Info(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M8 7.25v3.75M8 5.1v.9" />
    </Svg>
  )
}

/** A memo field — prose rather than a value. */
export function Text(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M3.25 4h9.5M3.25 7.25h9.5M3.25 10.5h6" />
    </Svg>
  )
}

/** Attachments: a paperclip, the one icon everyone reads without a label. */
export function Paperclip(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M11.4 7.3 7.1 11.6a2.4 2.4 0 0 1-3.4-3.4l4.8-4.8a1.6 1.6 0 0 1 2.3 2.3l-4.8 4.8a.8.8 0 0 1-1.1-1.1l4.3-4.3" />
    </Svg>
  )
}

/** A defect. */
export function Bug(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="5.25" y="5.5" width="5.5" height="7.25" rx="2.75" />
      <path d="M6.2 4.4a1.8 1.8 0 0 1 3.6 0M2.5 7.5h2.75M10.75 7.5h2.75M2.9 11h2.35M10.75 11h2.35" />
    </Svg>
  )
}

/** Traceability: two records pointing at each other. */
export function Link(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6.6 9.4 9.4 6.6" />
      <path d="M7.6 4.6 9 3.2a2.55 2.55 0 0 1 3.6 3.6l-1.4 1.4" />
      <path d="M8.4 11.4 7 12.8a2.55 2.55 0 0 1-3.6-3.6l1.4-1.4" />
    </Svg>
  )
}

/** Coverage: a check against a requirement. */
export function Coverage(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 2.5h4.5L12 6v7.5H4Z" />
      <path d="m6 8.5 1.6 1.6L10.2 7" />
    </Svg>
  )
}

/** A test. */
export function Beaker(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6.5 2.5v4L3.4 11.8a1 1 0 0 0 .86 1.5h7.48a1 1 0 0 0 .86-1.5L9.5 6.5v-4" />
      <path d="M5.75 2.5h4.5M5.1 9.5h5.8" />
    </Svg>
  )
}

/** A run. */
export function Play(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M6.75 5.75 10.5 8l-3.75 2.25Z" />
    </Svg>
  )
}

/** Risk analysis. */
export function Warning(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 2.75 14 13H2Z" />
      <path d="M8 6.75v3M8 11.1v.4" />
    </Svg>
  )
}

/** History: a clock winding back. */
export function Clock(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M8 4.75V8l2.25 1.5" />
    </Svg>
  )
}

/** Every field, including the ones the Details form leaves out. */
export function ListAll(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6 4h7M6 8h7M6 12h7M3.25 4h.01M3.25 8h.01M3.25 12h.01" />
    </Svg>
  )
}

/** Pin the rail open. */
export function Pin({ filled = false, ...props }: IconProps & { filled?: boolean }) {
  return (
    <svg
      viewBox="0 0 16 16"
      width={`${props.size ?? 1}em`}
      height={`${props.size ?? 1}em`}
      fill={filled ? 'currentColor' : 'none'}
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={props.className}
      aria-hidden="true"
      focusable="false"
    >
      <path d="M9.5 2.2 13.8 6.5l-1.6 1.6-.9-.2-3 3 .3 1.6-1.3 1.3-5.1-5.1L3.5 7.4l1.6.3 3-3-.2-.9Z" />
      <path d="m5.4 10.6-2.9 2.9" />
    </svg>
  )
}
