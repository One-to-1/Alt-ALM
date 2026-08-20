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
  /**
   * Every value of the field, in order.
   *
   * A list on every field rather than a string on most, mirroring ALM's own model where `values` is
   * an array throughout. Storing a bare string for single-value fields would make multi-value the
   * special case, which is backwards and is how a second code path gets added later.
   */
  values: string[]
  choices: Record<string, Choice[]> | null
  disabled?: boolean
  /** A validation problem to pin to this input, or undefined. */
  problem?: string
  onChange: (values: string[]) => void
  /** Namespaces the input id, so two forms can be on one page without colliding labels. */
  idPrefix: string
}

export function FieldInput({
  column,
  values,
  choices,
  disabled,
  problem,
  onChange,
  idPrefix,
}: Props) {
  const id = `${idPrefix}-${column.name}`
  const permitted = choicesFor(column, choices)
  const value = values[0] ?? ''

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

  // ⚠️ A multi-value field gets a real multi-select or NO control — never a single-value dropdown.
  // The model has exactly two, both References, and a single-value control over one would silently
  // drop the other values on the next save. The unresolved case is already handled above: a
  // Reference with no resolved choices renders no control at all, which covers a multi-value one
  // too, because its value is a list of ids.
  if (permitted && column.multiValue) {
    return (
      <>
        <select
          id={id}
          multiple
          size={Math.min(6, Math.max(3, permitted.length))}
          className={problem ? 'has-problem' : undefined}
          value={values}
          disabled={disabled}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `problem-${column.name}` : undefined}
          onChange={(event) =>
            onChange(Array.from(event.target.selectedOptions, (option) => option.value))
          }
        >
          {/* No empty option: in a multi-select, "none" is expressed by selecting nothing, and an
              empty entry would be a selectable value that means the same thing. */}
          {/* Stored values the list no longer offers are kept and marked, same as the single-value
              case — a list edited after the record was written must not silently drop a value. */}
          {values
            .filter((v) => v !== '' && !permitted.some((c) => c.value === v))
            .map((v) => (
              <option key={v} value={v}>
                {v} (not in list)
              </option>
            ))}
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
          onChange={(event) => onChange([event.target.value])}
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
        onChange={(event) => onChange([event.target.value])}
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
