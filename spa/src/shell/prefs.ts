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
 * Rendering all 76 columns of a requirement is unusable, and picking "the first N" would lead
 * with internal ids. This prefers the fields a person actually scans, then tops up in metadata
 * order so narrow entities still fill out.
 */
const PREFERRED = [
  'id',
  'name',
  'status',
  'type-id',
  'owner',
  'priority',
  'severity',
  'target-rel',
  'target-rcyc',
  'req-priority',
  'creation-time',
  'last-modified',
  'detected-by',
  'assigned-to',
]

export function defaultColumns(available: { name: string }[], limit = 8): string[] {
  const names = available.map((c) => c.name)
  const chosen = PREFERRED.filter((p) => names.includes(p))
  for (const n of names) {
    if (chosen.length >= limit) break
    if (!chosen.includes(n)) chosen.push(n)
  }
  // Return in metadata order so the grid matches the picker's ordering.
  return names.filter((n) => chosen.slice(0, limit).includes(n))
}
