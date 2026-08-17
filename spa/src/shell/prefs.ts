/**
 * Small localStorage-backed preferences. No state library and no dependency — the app has three
 * durable settings and adding a store for them would cost more than it saves.
 *
 * Every read is defensive: localStorage throws in locked-down browsers and private modes, and a
 * layout preference is never worth failing a render over.
 */

const NS = 'alt-alm'

function read<T>(key: string, fallback: T, parse: (raw: string) => T | null): T {
  try {
    const raw = window.localStorage.getItem(`${NS}.${key}`)
    if (raw === null) return fallback
    const parsed = parse(raw)
    return parsed === null ? fallback : parsed
  } catch {
    return fallback
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(`${NS}.${key}`, value)
  } catch {
    // Preference just will not persist.
  }
}

export function readNumber(key: string, fallback: number, min: number, max: number): number {
  return read(key, fallback, (raw) => {
    const n = Number(raw)
    return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : null
  })
}

export function writeNumber(key: string, value: number): void {
  write(key, String(Math.round(value)))
}

export function readString<T extends string>(key: string, fallback: T, allowed: readonly T[]): T {
  return read(key, fallback, (raw) => (allowed.includes(raw as T) ? (raw as T) : null))
}

export function writeString(key: string, value: string): void {
  write(key, value)
}

export function readStringList(key: string): string[] | null {
  return read<string[] | null>(key, null, (raw) => {
    try {
      const parsed: unknown = JSON.parse(raw)
      if (Array.isArray(parsed) && parsed.every((v) => typeof v === 'string')) {
        return parsed as string[]
      }
      return null
    } catch {
      return null
    }
  })
}

export function writeStringList(key: string, value: string[]): void {
  write(key, JSON.stringify(value))
}

export function clearKey(key: string): void {
  try {
    window.localStorage.removeItem(`${NS}.${key}`)
  } catch {
    // no-op
  }
}

/**
 * The columns a grid shows before the user chooses.
 *
 * The head of this list is **ALM's own default Requirements column set**, taken from the stock
 * client: Name, Req ID, Direct Cover Status, Initiator, Modified.
 *
 * Those are matched by FIELD NAME, not by label, and the difference is not pedantic — checked
 * against live metadata, "Direct Cover Status" is the field `status` (there is no field named
 * anything like it), and "Initiator" is `owner`, which another project in the same tenant labels
 * "Author". Labels are per-project customization (ADR 0005), so pinning the label would show the
 * wrong header on the first project that renamed it, while pinning the field shows each project's
 * own wording.
 *
 * Everything after those is a top-up in metadata order, so entities that lack them still fill out.
 * Rendering all 76 columns of a requirement is unusable, and "the first N" would lead with
 * internal ids.
 */
const PREFERRED = [
  // ALM's stock Requirements columns, in its order.
  'id',
  'name',
  'status',
  'owner',
  'last-modified',
  // Reasonable next choices for the other modules, which share this function.
  'type-id',
  'priority',
  'severity',
  'req-priority',
  'target-rel',
  'target-rcyc',
  'creation-time',
  'detected-by',
  'assigned-to',
]

/** Five is ALM's own count for Requirements, and it fits without horizontal scrolling. */
export function defaultColumns(available: { name: string }[], limit = 5): string[] {
  const names = new Set(available.map((c) => c.name))
  const chosen = PREFERRED.filter((p) => names.has(p))
  for (const c of available) {
    if (chosen.length >= limit) break
    if (!chosen.includes(c.name)) chosen.push(c.name)
  }
  // PREFERRED order, NOT metadata order. ALM shows Req ID, Direct Cover Status, Initiator,
  // Modified in that sequence; metadata order would render them alphabetically-ish
  // (Author, Direct Cover Status, Modified, Req ID), which is not what anyone reads.
  return chosen.slice(0, limit)
}
