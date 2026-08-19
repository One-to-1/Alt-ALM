import { useMemo, useState } from 'react'
import type { GridColumn, GridRow, WriteResult } from '../api/client.ts'
import { updateRecord } from '../api/client.ts'
import { mayKeepEditing, outcomeMessage } from './writeOutcome.ts'
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
   * The fields worth offering: writable, not a memo, and not the server's own.
   *
   * `writable` here is `!virtual` and nothing more — see GridDto.Column. `required` and `editable`
   * are deliberately absent from the contract, so this form cannot accidentally grey out a field
   * ALM actually demands (probe 9).
   */
  const editable = useMemo(
    () =>
      columns.filter(
        (c) =>
          c.writable &&
          c.type !== 'MEMO' &&
          c.name !== 'id' &&
          c.name !== 'ver-stamp' &&
          !c.multiValue,
      ),
    [columns],
  )

  const initial = useMemo(() => {
    const values: Record<string, string> = {}
    for (const column of editable) {
      values[column.name] = row.values[column.name]?.[0] ?? ''
    }
    return values
  }, [editable, row])

  const [draft, setDraft] = useState<Record<string, string>>(initial)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<WriteResult | null>(null)

  /**
   * The `ver-stamp` this edit is based on.
   *
   * ⚠️ Sending it buys conflict *detection*, not locking — ALM accepts a stale stamp and lets the
   * write land, so the server cannot be asked to refuse. Omitting it would mean silently
   * overwriting whoever saved in between, so it is always sent when the record carries one.
   */
  const expectedVersion = row.values['ver-stamp']?.[0]

  const message = result ? outcomeMessage(result) : null
  const changed = Object.keys(draft).filter((name) => draft[name] !== initial[name])

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
      const fields: Record<string, string> = {}
      for (const name of changed) {
        fields[name] = draft[name]
      }
      setResult(await updateRecord(project, collection, row.id, fields, expectedVersion))
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
        {editable.map((column) => {
          const problem = message?.fieldProblems[column.name]
          return (
            <div className="detail-field" key={column.name}>
              <dt title={`${column.name} · ${column.type}`}>
                <label htmlFor={`edit-${column.name}`}>{column.label || column.name}</label>
              </dt>
              <dd>
                <input
                  id={`edit-${column.name}`}
                  className={problem ? 'has-problem' : undefined}
                  value={draft[column.name] ?? ''}
                  disabled={locked}
                  aria-invalid={problem ? true : undefined}
                  aria-describedby={problem ? `problem-${column.name}` : undefined}
                  onChange={(event) =>
                    setDraft({ ...draft, [column.name]: event.target.value })
                  }
                />
                {problem && (
                  <p className="record-editor-problem" id={`problem-${column.name}`}>
                    {problem}
                  </p>
                )}
              </dd>
            </div>
          )
        })}
      </dl>

      <p className="detail-note">
        Memo fields are edited from their own tab and are not shown here.
        {expectedVersion === undefined &&
          ' This record reports no version, so a change saved by someone else since you opened it cannot be detected.'}
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
