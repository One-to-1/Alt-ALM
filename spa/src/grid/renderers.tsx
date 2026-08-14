// The renderer registry — the *only* place type logic lives (alt-alm-ui skill, §2).
// Keyed on the 8 probe-confirmed ALM field types; no per-entity special-casing,
// no "unknown type" fallback. Values arrive as string[] on the wire even for
// single-value fields.

import type { ReactNode } from 'react'
import type { FieldType, GridColumn } from '../api/client.ts'

export type CellRenderer = (values: string[], column: GridColumn) => ReactNode

const EMPTY_MARKER = (
  <span className="cell-empty" aria-hidden="true">
    —
  </span>
)

const MEMO_PREVIEW_LENGTH = 140

/**
 * Extracts plain text from a Memo field's full <html><body> document via
 * DOMParser, which parses into a detached document and does not execute
 * scripts or insert anything into the live DOM. This is the sanctioned way
 * to preview rich text without dangerouslySetInnerHTML; real sanitized
 * rendering is a later phase.
 */
function htmlToPlainText(html: string): string {
  try {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    return (doc.body.textContent ?? '').trim().replace(/\s+/g, ' ')
  } catch {
    return html
      .replace(/<[^>]*>/g, ' ')
      .trim()
      .replace(/\s+/g, ' ')
  }
}

function truncate(text: string, max: number): { text: string; truncated: boolean } {
  if (text.length <= max) {
    return { text, truncated: false }
  }
  return { text: `${text.slice(0, max).trimEnd()}…`, truncated: true }
}

const stringRenderer: CellRenderer = (values) => {
  if (values.length === 0) return EMPTY_MARKER
  return <>{values.join(', ')}</>
}

const memoRenderer: CellRenderer = (values) => {
  if (values.length === 0) return EMPTY_MARKER
  const plain = values.map(htmlToPlainText).filter(Boolean).join(' / ')
  if (!plain) return EMPTY_MARKER
  const { text, truncated } = truncate(plain, MEMO_PREVIEW_LENGTH)
  return (
    <span className="cell-memo" title={truncated ? plain : undefined}>
      {text}
    </span>
  )
}

const numberRenderer: CellRenderer = (values) => {
  if (values.length === 0) return EMPTY_MARKER
  return <span className="cell-number">{values.join(', ')}</span>
}

const dateRenderer: CellRenderer = (values) => {
  if (values.length === 0) return EMPTY_MARKER
  return <span className="cell-date">{values.join(', ')}</span>
}

/** List-Id 1 is the Y/N list — the only boolean-ish control in the model (skill §2). */
const YES_NO_LIST_ID = 1

const lookupListRenderer: CellRenderer = (values, column) => {
  if (values.length === 0) return EMPTY_MARKER

  if (column.listId === YES_NO_LIST_ID) {
    return (
      <>
        {values.map((value, index) => {
          const isYes = value === 'Y' || value.toLowerCase() === 'yes'
          const isNo = value === 'N' || value.toLowerCase() === 'no'
          const label = isYes ? 'Yes' : isNo ? 'No' : value
          const badgeClass = isYes ? 'badge badge-yes' : isNo ? 'badge badge-no' : 'badge'
          return (
            <span className={badgeClass} key={`${column.name}-${index}`}>
              {label}
            </span>
          )
        })}
      </>
    )
  }

  return <>{values.join(', ')}</>
}

const usersListRenderer: CellRenderer = (values) => {
  // UsersList is single-value always (skill §2).
  if (values.length === 0) return EMPTY_MARKER
  return <span className="cell-user">{values[0]}</span>
}

const referenceRenderer: CellRenderer = (values) => {
  if (values.length === 0) return EMPTY_MARKER
  if (values.length === 1) return <>{values[0]}</>
  // Reference is the only type that is ever multivalue (skill §2).
  return (
    <ul className="cell-reference-list">
      {values.map((value, index) => (
        <li key={`${value}-${index}`}>{value}</li>
      ))}
    </ul>
  )
}

export const cellRenderers: Record<FieldType, CellRenderer> = {
  STRING: stringRenderer,
  MEMO: memoRenderer,
  NUMBER: numberRenderer,
  DATE: dateRenderer,
  DATE_TIME: dateRenderer,
  LOOKUP_LIST: lookupListRenderer,
  USERS_LIST: usersListRenderer,
  REFERENCE: referenceRenderer,
}

export function renderCell(column: GridColumn, values: string[]): ReactNode {
  return cellRenderers[column.type](values, column)
}
