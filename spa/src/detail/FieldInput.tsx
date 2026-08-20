import type { Choice, GridColumn } from '../api/client.ts'
import { choicesFor, pointsAtARecord } from './fieldRules.ts'

/**
 * One editable field's control.
 *
 * The *rule* deciding which control it gets lives in {@link fieldRules}, not here — it is pure, it
 * is the part that was wrong once, and it is shared by the editor and the create form. This file is
 * only the rendering.
 */

interface Props {
  column: GridColumn
  value: string
  choices: Record<string, Choice[]> | null
  disabled?: boolean
  /** A validation problem to pin to this input, or undefined. */
  problem?: string
  onChange: (value: string) => void
  /** Namespaces the input id, so two forms can be on one page without colliding labels. */
  idPrefix: string
}

export function FieldInput({
  column,
  value,
  choices,
  disabled,
  problem,
  onChange,
  idPrefix,
}: Props) {
  const id = `${idPrefix}-${column.name}`
  const permitted = choicesFor(column, choices)

  if (!permitted && pointsAtARecord(column)) {
    // ⚠️ The fallback differs by MECHANISM, and one rule for all three was wrong.
    //
    // An unresolved LOOKUP falls through to a text box: its value is a literal string, so typing
    // one is legitimate and "let ALM decide" applies.
    //
    // An unresolved REFERENCE gets no control at all: its value is an ID. A text box pre-filled
    // with a raw id invites someone to type a number that silently re-points the record at a
    // different release, or re-types the requirement itself. Not constraining is right for a
    // string and a trap for an identifier.
    return (
      <span className="record-editor-unresolved">{(value || '—') + ' (not editable here)'}</span>
    )
  }

  if (permitted) {
    return (
      <>
        <select
          id={id}
          className={problem ? 'has-problem' : undefined}
          value={value}
          disabled={disabled}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `problem-${column.name}` : undefined}
          onChange={(event) => onChange(event.target.value)}
        >
          {/* Clearing a field is a legitimate edit, and a select with no empty option makes it
              impossible to undo a value once set. */}
          <option value="">—</option>
          {/* The record's CURRENT value, even when the list no longer offers it. A list edited
              after this record was written would otherwise silently re-point the dropdown at the
              first option and save that on the next Save. */}
          {value && !permitted.some((c) => c.value === value) && (
            <option value={value}>{value} (not in list)</option>
          )}
          {permitted.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
        <Problem column={column} problem={problem} />
      </>
    )
  }

  return (
    <>
      <input
        id={id}
        className={problem ? 'has-problem' : undefined}
        value={value}
        disabled={disabled}
        aria-invalid={problem ? true : undefined}
        aria-describedby={problem ? `problem-${column.name}` : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      <Problem column={column} problem={problem} />
    </>
  )
}

function Problem({ column, problem }: { column: GridColumn; problem?: string }) {
  if (!problem) return null
  return (
    <p className="record-editor-problem" id={`problem-${column.name}`}>
      {problem}
    </p>
  )
}
