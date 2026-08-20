import { useState } from 'react'
import type { WriteResult } from '../api/client.ts'
import { deleteRecord } from '../api/client.ts'
import { outcomeMessage } from './writeOutcome.ts'
import './RecordEditor.css'
import './DeleteRecord.css'

interface Props {
  project: string
  collection: string
  id: string
  /** The record's own name, so the confirmation names what is about to go. */
  name: string
  /** Called once the row is known to be gone, so the grid and selection can catch up. */
  onDeleted: () => void
  onCancel: () => void
}

/**
 * The delete confirmation, and the write behind it.
 *
 * <h2>Why this is not `window.confirm`</h2>
 *
 * Because the sentence that matters cannot fit in one: <strong>ALM's delete does not cascade.</strong>
 * Deleting a folder leaves everything inside it in the project, still stored, with nothing listing
 * it — probe 8 left five orphaned tests behind exactly this way, and ALM reports nothing about it.
 * A generic "Are you sure?" is worse than no dialog, because it implies the only risk is the row
 * named in it.
 *
 * <h2>⚠️ Why this warns instead of counting</h2>
 *
 * The obvious version says "3 records are filed under this one". <strong>It would say 0, always.</strong>
 * ALM's own {@code children-count} reads 0 for every node on this version (probe 19) — which is why
 * {@code TreeService} has to establish {@code hasChildren} with a second query rather than trusting
 * the field. A dialog wired to it would render a warning that never appears, and a check that is
 * silent by construction is worse than no check: it reads as "Alt-ALM looked, and there is nothing
 * underneath".
 *
 * Counting properly is not one query either. What is filed under a record can live in a
 * <em>different collection</em> — tests under a test folder, which is exactly the orphan probe 8
 * created — so a correct count is per-entity work, not a field.
 *
 * So the warning is unconditional and says what is actually known: ALM will not cascade, the rule
 * reaches across modules, and <strong>Alt-ALM has not checked</strong>. That last clause is the one
 * that stops this being a reassurance.
 *
 * <h2>The outcome that has no undo</h2>
 *
 * ⚠️ An {@code unknown} outcome on a delete is the one place where "look and see" is the entire
 * remedy: there is no compensating action, and Alt-ALM cannot undo a delete that did land. The
 * banner therefore offers a reload and nothing else — the same rule as everywhere, reached for a
 * different reason. Re-sending a delete is not idempotent in the way it looks: the second attempt
 * against a row that is gone reports a failure for a delete that worked.
 */
export function DeleteRecord({
  project,
  collection,
  id,
  name,
  onDeleted,
  onCancel,
}: Props) {
  const [deleting, setDeleting] = useState(false)
  const [result, setResult] = useState<WriteResult | null>(null)

  const message = result ? outcomeMessage(result) : null

  // No `locked` flag here, unlike the editor and the comment box, and deliberately so: those keep
  // a form on screen next to the banner, so they need a rule about whether its submit button
  // survives. This dialog REPLACES the confirmation with the outcome, so the only route to a
  // second write is the banner's own action list — which already omits `retry` for `unknown`.

  async function remove() {
    setDeleting(true)
    setResult(null)
    try {
      const outcome = await deleteRecord(project, collection, id)
      setResult(outcome)
      if (outcome.kind === 'committed') onDeleted()
    } catch (error) {
      setResult({
        kind: 'rejected',
        errorId: 'altalm.transport',
        detail: error instanceof Error ? error.message : 'The record could not be deleted.',
      })
    } finally {
      setDeleting(false)
    }
  }

  function act(action: string) {
    switch (action) {
      case 'reload':
      case 'reloadAndReapply':
        // Whatever happened, the caller's view of this record is no longer trustworthy — it may be
        // gone. Handing back to the same callback the successful path uses is right: it re-reads
        // and clears the selection, which is exactly what "go and look" means here.
        onDeleted()
        break
      case 'retry':
        void remove()
        break
      default:
        setResult(null)
        onCancel()
    }
  }

  return (
    <div className="delete-record" role="alertdialog" aria-labelledby="delete-record-title">
      {message ? (
        <div className={`record-editor-banner tone-${message.tone}`} role="status">
          <strong>{message.title}</strong>
          <p>{message.body}</p>
          {message.tone === 'unknown' && (
            // The generic wording is about duplicates, which is a create's failure. A delete's is
            // the opposite and has no undo, so it is worth saying plainly rather than leaving the
            // user to infer it from a banner written for another operation.
            <p>
              A delete cannot be undone from Alt-ALM, and re-sending one is not safe to guess at:
              a second attempt against a row that is already gone reports a failure for a delete
              that worked. Reload and see which it was.
            </p>
          )}
          <div className="record-editor-banner-actions">
            {message.actions.map((action) => (
              <button type="button" key={action} onClick={() => act(action)}>
                {action === 'reload' || action === 'reloadAndReapply'
                  ? 'Reload the list'
                  : action === 'retry'
                    ? 'Try again'
                    : 'Close'}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <>
          <h3 className="delete-record-title" id="delete-record-title">
            Delete this {singular(collection)}?
          </h3>
          <p className="delete-record-target">
            <strong>{name}</strong> <span className="delete-record-id">#{id}</span>
          </p>

          {/* ⚠️ The sentence this dialog exists for, shown always — see the class comment for why
              it is not a count. */}
          <p className="delete-record-orphans" role="alert">
            ALM does not delete what is filed underneath. Anything under this record — including
            records in other modules, such as tests under a test folder — stays in the project with
            nothing listing it. <strong>Alt-ALM has not checked whether there is anything.</strong>
          </p>

          <p className="delete-record-note">This cannot be undone from Alt-ALM.</p>

          <div className="delete-record-actions">
            <button
              type="button"
              className="delete-record-confirm"
              disabled={deleting}
              onClick={() => void remove()}
            >
              {deleting ? 'Deleting…' : 'Delete'}
            </button>
            <button type="button" onClick={onCancel} disabled={deleting}>
              Cancel
            </button>
          </div>
        </>
      )}

    </div>
  )
}

/** "requirements" -> "requirement". Display only. */
function singular(collection: string): string {
  const base = collection.endsWith('s') ? collection.slice(0, -1) : collection
  return base.replace(/-/g, ' ')
}
