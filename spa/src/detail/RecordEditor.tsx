import { useEffect, useMemo, useState } from 'react'
import type { Choice, FieldValue, GridColumn, GridRow, WriteResult } from '../api/client.ts'
import { fetchChoices, updateRecord } from '../api/client.ts'
import { fieldBlamedBy, mayKeepEditing, outcomeMessage } from './writeOutcome.ts'
import { FieldInput } from './FieldInput.tsx'
import { editableColumns } from './fieldRules.ts'
import './RecordEditor.css'

interface Props {
  project: string
  collection: string
  columns: GridColumn[]
  row: GridRow
  /** Re-read the record. Called for every outcome that leaves the screen out of date. */
  onReload: () => void
  onClose: () => void
}

/**
 * ALM's Details form, in edit mode.
 *
 * <h2>Two rules this form follows that a generic one would not</h2>
 *
 * <p><strong>1. Only changed fields are sent.</strong> ALM's update is partial by field but total by
 * value — an included field is replaced outright. Posting the whole form back would therefore
 * rewrite every field with what the browser happened to be showing, which for a memo means
 * replacing a document the user never opened. Sending the diff keeps the blast radius to what was
 * actually typed.
 *
 * <p><strong>2. Memo fields are not editable here.</strong> They are HTML documents, and this is a
 * plain-text form; typing a paragraph into a text input and sending it would flatten the newlines
 * (ALM collapses them to spaces) and strip whatever markup the field already held. The BFF's
 * validator refuses that write, which is the correct outcome — but the better UI is not to offer
 * it. Rich-text authoring is its own slice.
 */
export function RecordEditor({ project, collection, columns, row, onReload, onClose }: Props) {
  /**
   * The fields worth offering.
   *
   * ⚠️ The rule — including the three unrelated "field with choices" mechanisms and the fallback
   * that differs by mechanism — lives in {@link FieldInput}, shared with the create form. It was
   * corrected once already (one fallback applied to all three was wrong), and two copies of it
   * would drift.
   *
   * Multi-value fields remain excluded here as there: exactly the two Reference fields in the whole
   * model. A multi-select is a different control, and faking one with a single-value dropdown would
   * silently drop the other values on save.
   */
  const editable = useMemo(() => editableColumns(columns), [columns])

  /**
   * The record's current values, one list per field.
   *
   * ⚠️ The whole list, not `[0]`. Taking only the first would silently truncate the model's two
   * multi-value fields to one value, and then saving them back would delete the rest — which is
   * exactly why they were not editable at all until probe 33 settled the write grammar.
   */
  const initial = useMemo(() => {
    const values: Record<string, string[]> = {}
    for (const column of editable) {
      values[column.name] = row.values[column.name] ?? []
    }
    return values
  }, [editable, row])

  const [draft, setDraft] = useState<Record<string, string[]>>(initial)
  /**
   * The project's lookup lists, or null while loading / if they could not be read.
   *
   * ⚠️ Null must behave as "no information", not "no choices": a field whose list cannot be read
   * falls back to a text input rather than to an empty dropdown the user cannot satisfy. Same rule
   * the BFF validator follows — when the evidence is absent, do not constrain.
   */
  const [choices, setChoices] = useState<Record<string, Choice[]> | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchChoices(project, collection)
      .then((loaded) => {
        if (!cancelled) setChoices(loaded)
      })
      .catch(() => {
        // Degrade to text inputs. Blocking the edit because a dropdown could not be populated is a
        // worse trade than letting ALM reject an unusual value.
        if (!cancelled) setChoices(null)
      })
    return () => {
      cancelled = true
    }
  }, [project, collection])
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<WriteResult | null>(null)

  /**
   * What this edit is based on: the loaded values of the fields it changes.
   *
   * ⚠️ Sending it buys conflict *detection*, not locking — ALM accepts a stale write and lets it
   * land, so the server cannot be asked to refuse. Omitting it would mean silently overwriting
   * whoever saved in between.
   *
   * ⚠️ It used to send `ver-stamp`, and that refused saves that were fine: filing a child under a
   * record moves that record's stamp with nothing on it changing (probe 34), so opening a
   * requirement and having anyone add a sub-requirement made the next save fail with "someone else
   * changed this record". `initial` is the same map the draft started from, so the baseline is
   * exactly what the user was shown. Built in `save`, over the changed fields only.
   */

  const base = result ? outcomeMessage(result) : null

  /**
   * The outcome message, with ALM's own refusal pinned to an input where it can be.
   *
   * ⚠️ Only ever ADDS a mark, and only when {@link fieldBlamedBy} is confident. ALM reports errors
   * per request and never per field (probe 29), so a rejection arrives with an empty problems array
   * and a sentence naming the field by its display label — readable, but attached to nothing.
   */
  const message = useMemo(() => {
    if (!base || !result || result.kind !== 'rejected') return base
    const blamed = fieldBlamedBy(result.detail, editable)
    if (!blamed || base.fieldProblems[blamed]) return base
    return { ...base, fieldProblems: { ...base.fieldProblems, [blamed]: result.detail } }
  }, [base, result, editable])
  const changed = Object.keys(draft).filter((name) => !sameValues(draft[name], initial[name]))

  /**
   * True once an outcome has arrived that must not be re-sent — an unknown one.
   *
   * ⚠️ This exists because the banner alone is not enough, and a component test proved it: the
   * banner correctly offered only "Reload", while the form's own Save button sat live underneath
   * it. Suppressing one route to a second write and leaving the other open is the same duplicate,
   * reached by a different button. `mayKeepEditing` is the single rule; this is where the form
   * obeys it.
   */
  const locked = result !== null && !mayKeepEditing(result)

  async function save() {
    if (changed.length === 0) {
      onClose()
      return
    }
    setSaving(true)
    setResult(null)
    try {
      const fields: Record<string, FieldValue> = {}
      for (const name of changed) {
        const column = editable.find((c) => c.name === name)
        // An array only for the fields that are actually multi-value. Sending a one-element array
        // for every field would work — the BFF normalises both — but it would make the wire
        // contract say "arrays everywhere" when the truth is "two fields".
        fields[name] = column?.multiValue ? draft[name] : (draft[name][0] ?? '')
      }
      // Baselines for the changed fields only — the BFF ignores the rest, but sending the whole
      // form would put every memo on the record into the request body for nothing.
      const expectedValues: Record<string, FieldValue> = {}
      for (const name of changed) {
        const column = editable.find((c) => c.name === name)
        expectedValues[name] = column?.multiValue ? initial[name] : (initial[name][0] ?? '')
      }
      setResult(await updateRecord(project, collection, row.id, fields, expectedValues))
    } catch (error) {
      // A thrown error here is a transport or access failure, never an outcome. The write client
      // turns every outcome - including "nobody knows" - into a value, so anything that reaches
      // this branch genuinely did not produce one.
      setResult({
        kind: 'rejected',
        errorId: 'altalm.transport',
        detail: error instanceof Error ? error.message : 'The save could not be completed.',
      })
    } finally {
      setSaving(false)
    }
  }

  function act(action: string) {
    switch (action) {
      case 'reload':
      case 'reloadAndReapply':
        onReload()
        // ⚠️ For 'reload' the editor closes and the draft is discarded. That is deliberate for an
        // unknown outcome: leaving the form open with a live Save button is the same trap as a
        // Retry button. For a conflict the draft is kept so the user can re-apply it over the
        // freshly-read record.
        if (action === 'reload') onClose()
        else setResult(null)
        break
      case 'retry':
        void save()
        break
      default:
        setResult(null)
        if (!mayKeepEditing(result as WriteResult)) onClose()
    }
  }

  return (
    <form
      className="record-editor"
      onSubmit={(event) => {
        event.preventDefault()
        void save()
      }}
    >
      {message && (
        <div className={`record-editor-banner tone-${message.tone}`} role="status">
          <strong>{message.title}</strong>
          <p>{message.body}</p>
          <div className="record-editor-banner-actions">
            {message.actions.map((action) => (
              <button type="button" key={action} onClick={() => act(action)}>
                {action === 'reload'
                  ? 'Reload the record'
                  : action === 'reloadAndReapply'
                    ? 'Reload and re-apply'
                    : action === 'retry'
                      ? 'Try again'
                      : 'Dismiss'}
              </button>
            ))}
          </div>
        </div>
      )}

      <dl className="detail-fields">
        {editable.map((column) => (
          <div className="detail-field" key={column.name}>
            <dt title={`${column.name} · ${column.type}`}>
              <label htmlFor={`edit-${column.name}`}>{column.label || column.name}</label>
            </dt>
            <dd>
              <FieldInput
                column={column}
                values={draft[column.name] ?? []}
                choices={choices}
                disabled={locked}
                problem={message?.fieldProblems[column.name]}
                onChange={(values) => setDraft({ ...draft, [column.name]: values })}
                idPrefix="edit"
              />
            </dd>
          </div>
        ))}
      </dl>

      <p className="detail-note">
        Memo fields are edited from their own tab. A change saved by someone else since this record
        was opened is detected on the fields being changed, and only on those.
      </p>

      <div className="record-editor-actions">
        {/* Not merely disabled — absent. A greyed-out Save still reads as "the thing to press once
            this clears", and for an unknown outcome there is nothing that will clear it except
            re-reading the record. */}
        {!locked && (
          <button type="submit" disabled={saving || changed.length === 0}>
            {saving
              ? 'Saving…'
              : changed.length === 0
                ? 'No changes'
                : `Save ${changed.length} change${changed.length === 1 ? '' : 's'}`}
          </button>
        )}
        <button type="button" onClick={onClose} disabled={saving}>
          {locked ? 'Close' : 'Cancel'}
        </button>
      </div>
    </form>
  )
}

/** Order matters for a multi-value field: ALM stores it as sent, so a reorder is a real change. */
function sameValues(a: string[] = [], b: string[] = []): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i])
}
