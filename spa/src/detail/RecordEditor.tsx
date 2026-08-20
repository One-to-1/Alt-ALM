import { useEffect, useMemo, useState } from 'react'
import type { GridColumn, GridRow, LookupList, WriteResult } from '../api/client.ts'
import { fetchLists, updateRecord } from '../api/client.ts'
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
   * The fields worth offering: writable, and of a type this form can honestly edit.
   *
   * `writable` here is `!virtual` and nothing more — see GridDto.Column. `required` and `editable`
   * are deliberately absent from the contract, so this form cannot accidentally grey out a field
   * ALM actually demands (probe 9).
   *
   * <h3>⚠️ Three kinds of "choose a value", not one</h3>
   *
   * A field that offers choices does so by one of three unrelated mechanisms, and only the first is
   * implemented here:
   *
   * <ol>
   *   <li><strong>LookupList + `listId`</strong> → `customization/used-lists`. 56 of the 58
   *       LookupList fields in the model are bound this way. These become dropdowns.
   *   <li><strong>Reference with `fieldRelationReferences`</strong> → a query against another
   *       entity collection. Target Release points at `release`, Target Cycle at `release-cycle`,
   *       and the stored value is an entity <em>id</em>, not a label.
   *   <li><strong>Reference with NO references</strong> → `customization/entities/{e}/types`.
   *       `type-id` (Requirement Type) is this: a subtype discriminator resolved by a third route
   *       again.
   * </ol>
   *
   * REFERENCE fields are therefore <strong>excluded</strong> rather than rendered. They were briefly
   * shown as text inputs pre-filled with a raw id, which is worse than omitting them: it invites
   * someone to type a number that silently re-points the record at a different release — or, for
   * `type-id`, re-types the requirement itself. Offering no control is honest; offering a text box
   * over an id is a trap. Resolving them is its own slice.
   *
   * Multi-value fields are excluded for the same reason and are, in this model, exactly the two
   * Reference fields above — there are only two in the entire model.
   */
  const editable = useMemo(
    () =>
      columns.filter(
        (c) =>
          c.writable &&
          c.type !== 'MEMO' &&
          c.type !== 'REFERENCE' &&
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
  /**
   * The project's lookup lists, or null while loading / if they could not be read.
   *
   * ⚠️ Null must behave as "no information", not "no choices": a field whose list cannot be read
   * falls back to a text input rather than to an empty dropdown the user cannot satisfy. Same rule
   * the BFF validator follows — when the evidence is absent, do not constrain.
   */
  const [lists, setLists] = useState<Record<string, LookupList> | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchLists(project)
      .then((loaded) => {
        if (!cancelled) setLists(loaded)
      })
      .catch(() => {
        // Degrade to text inputs. Blocking the edit because a dropdown could not be populated
        // would be a worse trade than letting ALM reject an unusual value.
        if (!cancelled) setLists(null)
      })
    return () => {
      cancelled = true
    }
  }, [project])
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

  /**
   * The values a field permits, or null to fall back to a text input.
   *
   * Null in every "cannot tell" case, mirroring the BFF validator exactly: lists not loaded, field
   * unbound (`listId === 0`), list unknown to this project, or list defined with no items. That
   * last one matters — three of the sandbox's 39 lists are empty, and rendering an empty dropdown
   * would make the field impossible to fill rather than merely unconstrained.
   */
  function choicesFor(column: GridColumn): string[] | null {
    if (column.type !== 'LOOKUP_LIST' || column.listId === 0 || lists === null) return null
    const values = lists[String(column.listId)]?.values
    return values && values.length > 0 ? values : null
  }

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
                {choicesFor(column) ? (
                  <select
                    id={`edit-${column.name}`}
                    className={problem ? 'has-problem' : undefined}
                    value={draft[column.name] ?? ''}
                    disabled={locked}
                    aria-invalid={problem ? true : undefined}
                    aria-describedby={problem ? `problem-${column.name}` : undefined}
                    onChange={(event) =>
                      setDraft({ ...draft, [column.name]: event.target.value })
                    }
                  >
                    {/* Clearing a field is a legitimate edit, and a select with no empty option
                        makes it impossible to undo a value once set. */}
                    <option value="">—</option>
                    {/* The record's CURRENT value, even when the list no longer offers it. A list
                        edited after this record was written would otherwise silently re-point the
                        dropdown at the first option and save that on the next Save. */}
                    {draft[column.name] &&
                      !choicesFor(column)?.includes(draft[column.name]) && (
                        <option value={draft[column.name]}>
                          {draft[column.name]} (not in list)
                        </option>
                      )}
                    {choicesFor(column)?.map((choice) => (
                      <option key={choice} value={choice}>
                        {choice}
                      </option>
                    ))}
                  </select>
                ) : (
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
                )}
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
        Memo fields are edited from their own tab. Fields that point at another record — Target
        Release, Requirement Type — are not editable here yet: their value is an id, and a text box
        over an id is a trap rather than a feature.
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
