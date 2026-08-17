/**
 * Theme choice: light, dark, or follow the OS.
 *
 * "system" is a real third state, not a synonym for one of the other two — it stamps no attribute
 * at all and lets `prefers-color-scheme` decide, so a user who changes their OS at sunset sees the
 * app follow without touching a control.
 */

export const THEMES = ['system', 'light', 'dark'] as const
export type Theme = (typeof THEMES)[number]

const KEY = 'alt-alm.theme'

export function readTheme(): Theme {
  try {
    const raw = window.localStorage.getItem(KEY)
    return THEMES.includes(raw as Theme) ? (raw as Theme) : 'system'
  } catch {
    return 'system'
  }
}

export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  if (theme === 'system') {
    root.removeAttribute('data-theme')
  } else {
    root.setAttribute('data-theme', theme)
  }
  try {
    window.localStorage.setItem(KEY, theme)
  } catch {
    // Preference just will not persist.
  }
}

/** What the user would actually see right now — needed to label a toggle honestly. */
export function effectiveTheme(theme: Theme): 'light' | 'dark' {
  if (theme !== 'system') return theme
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}
