import { useEffect, useMemo, useState } from 'react'
import type { Choice, GridColumn, WriteResult } from '../api/client.ts'
import { createRecord, fetchChoices } from '../api/client.ts'
import { mayKeepEditing, outcomeMessage } from './writeOutcome.ts'
import { FieldInput } from './FieldInput.tsx'
import { editableColumns } from './fieldRules.ts'
import './RecordEditor.css'
import './RecordCreator.css'

interface Props {
  project: string
  collection: string
  /** The project's columns for this collection, straight from the grid's own metadata read. */
  columns: GridColumn[]
  /**
   * Where the new record is filed, for entities that live in a hierarchy.
   *
   * ⚠️ Required when {@link needsParent} is set, and never defaulted to a root sentinel — see the
   * class comment.
   */
  parentId?: string
  /** The parent's display name, so the form says where the record is going. */
  parentLabel?: string
  /** True when this collection's records must be filed under something. */
  needsParent: boolean
  onCreated: (id: string | null) => void
  onCancel: () => void
}

/**
 * The New Record form.
 *
 * <h2>⚠️ Why this refuses to create at the tree root</h2>
 *
 * A requirement's root {@code parent-id} of {@code -1} is a <strong>sentinel, not a row</strong>
 * (probe 27): POSTing a child against it returns
 * {@code 500 Entity with key '-1' does not exist in table 'REQ'}. So "create with no parent" is not
 * a thing this API can do for a hierarchical entity, and defaulting to {@code -1} would produce a
 * 500 — which, being a 5xx write, would be reported as an <em>unknown outcome</em> rather than as
 * the plain refusal it is. Refusing up front, with the reason, is both truthful and cheaper.
 *
 * <h2>What the form offers, and what it deliberately does not enforce</h2>
 *
 * The fields ALM's own details form would show ({@code onDetailsForm}), filtered by the shared rule
 * in {@link FieldInput}. Nothing is marked required and nothing is blocked for being empty:
 * {@code required} in metadata is <strong>not</strong> a description of what a create needs — probe
 * 9 found a field that is {@code required:false} and {@code editable:false} and still makes the
 * create 500 when omitted. A form that enforced the flag would refuse writes ALM accepts, and the
 * failure would look like an ALM limitation rather than our own rule.
 *
 * The BFF handles the other half: on a 500 naming a missing required field it retries once with
 * that field included, and reports {@code retried} so the banner can say so.
 *
 * <p>{@code parent-id} is set from the current scope rather than offered as an input. It is an id,
 * and a text box holding one invites filing a record under a number somebody typed.
 */
export function RecordCreator({
  project,
  collection,
  columns,
  parentId,
  parentLabel,
  needsParent,
  onCreated,
  onCancel,
}: Props) {
  /**
   * The fields on the form.
   *
   * Scoped to `onDetailsForm` — the flags ALM itself uses to lay out a record — rather than every
   * writable field, because a requirement carries 76 and a form of 40 inputs is not a form. Falls
   * back to all editable columns where the flags select nothing, so a metadata shape we have not
   * seen degrades to "too many fields" rather than to "no fields at all".
   *
   * `parent-id` is filtered out because the scope supplies it; see the class comment.
   */
  const fields = useMemo(() => {
    const all = editableColumns(columns).filter((c) => c.name !== 'parent-id')
    const onForm = all.filter((c) => c.onDetailsForm)
    return onForm.length > 0 ? onForm : all
  }, [columns])

  const [draft, setDraft] = useState<Record<string, string>>({})
  const [choices, setChoices] = useState<Record<string, Choice[]> | null>(null)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<WriteResult | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchChoices(project, collection)
      .then((loaded) => {
        if (!cancelled) setChoices(loaded)
      })
      .catch(() => {
        // Degrade to text inputs. Blocking a create because a dropdown could not be populated is a
        // worse trade than letting ALM reject an unusual value.
        if (!cancelled) setChoices(null)
      })
    return () => {
      cancelled = true
    }
  }, [project, collection])

  const message = result ? outcomeMessage(result) : null
  const locked = result !== null && !mayKeepEditing(result)
  const blocked = needsParent && !parentId

  async function create() {
    setSaving(true)
    setResult(null)
    try {
      // Only fields the user actually filled. Sending the empty ones would write blanks over
      // whatever defaults ALM applies on create, and there is no way to tell the two apart later.
      const filled: Record<string, string> = {}
      for (const [name, value] of Object.entries(draft)) {
        if (value !== '') filled[name] = value
      }
      if (parentId) filled['parent-id'] = parentId

      const outcome = await createRecord(project, collection, filled)
      setResult(outcome)
      if (outcome.kind === 'committed') onCreated(outcome.id)
    } catch (error) {
      setResult({
        kind: 'rejected',
        errorId: 'altalm.transport',
        detail: error instanceof Error ? error.message : 'The record could not be created.',
      })
    } finally {
      setSaving(false)
    }
  }

  function act(action: string) {
    switch (action) {
      case 'reload':
      case 'reloadAndReapply':
        // ⚠️ `null`, not an id. An unknown create returns no id, so the host is told "a record may
        // exist, go and look" rather than being handed one to select — selecting a row that was
        // never created would answer 404 and read as a bug rather than as the uncertainty it is.
        onCreated(null)
        break
      case 'retry':
        void create()
        break
      default:
        setResult(null)
        if (!mayKeepEditing(result as WriteResult)) onCancel()
    }
  }

  if (blocked) {
    return (
      <div className="record-creator record-creator-blocked">
        <h3 className="record-creator-title">Nowhere to file this yet</h3>
        <p>
          A new {singular(collection)} has to be created inside a folder — ALM has no way to add one
          at the top of the tree. Open a folder first, then try again.
        </p>
        <div className="record-editor-actions">
          <button type="button" onClick={onCancel}>
            Close
          </button>
        </div>
      </div>
    )
  }

  return (
    <form
      className="record-creator"
      onSubmit={(event) => {
        event.preventDefault()
        void create()
      }}
    >
      <h3 className="record-creator-title">New {singular(collection)}</h3>
      {parentLabel && (
        <p className="record-creator-scope">
          Filed under <strong>{parentLabel}</strong>
        </p>
      )}

      {message && (
        <div className={`record-editor-banner tone-${message.tone}`} role="status">
          <strong>{message.title}</strong>
          <p>{message.body}</p>
          <div className="record-editor-banner-actions">
            {message.actions.map((action) => (
              <button type="button" key={action} onClick={() => act(action)}>
                {action === 'reload'
                  ? 'Reload the list'
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
        {fields.map((column) => (
          <div className="detail-field" key={column.name}>
            <dt title={`${column.name} · ${column.type}`}>
              <label htmlFor={`new-${column.name}`}>{column.label || column.name}</label>
            </dt>
            <dd>
              <FieldInput
                column={column}
                value={draft[column.name] ?? ''}
                choices={choices}
                disabled={locked}
                problem={message?.fieldProblems[column.name]}
                onChange={(value) => setDraft({ ...draft, [column.name]: value })}
                idPrefix="new"
              />
            </dd>
          </div>
        ))}
      </dl>

      <p className="detail-note">
        Nothing here is marked required, and that is deliberate: ALM&rsquo;s own field metadata does
        not describe what a create needs. Fill in what you know and let ALM answer — if it asks for
        a field it never declared, Alt-ALM supplies it and says so.
      </p>

      <div className="record-editor-actions">
        {/* Absent rather than disabled after an unrepeatable outcome — a greyed-out Create still
            reads as "the thing to press once this clears", and for an unknown outcome nothing
            clears it except going and looking. */}
        {!locked && (
          <button type="submit" disabled={saving}>
            {saving ? 'Creating…' : `Create ${singular(collection)}`}
          </button>
        )}
        <button type="button" onClick={onCancel} disabled={saving}>
          {locked ? 'Close' : 'Cancel'}
        </button>
      </div>
    </form>
  )
}

/** "requirements" -> "requirement". Display only. */
function singular(collection: string): string {
  const base = collection.endsWith('s') ? collection.slice(0, -1) : collection
  return base.replace(/-/g, ' ')
}
