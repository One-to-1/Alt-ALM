import { useState } from 'react'
import type { WriteResult } from '../api/client.ts'
import { addComment } from '../api/client.ts'
import { readFreeText, writeString } from '../shell/prefs.ts'
import { mayWriteAgain, outcomeMessage } from './writeOutcome.ts'
// The outcome banner's four tones are defined there and shared deliberately — see CommentBox.css.
// Imported here rather than relied on through RecordEditor's own import, so this component keeps
// its styling if the editor is ever split out or lazily loaded.
import './RecordEditor.css'
import './CommentBox.css'

interface Props {
  project: string
  collection: string
  entityId: string
  /** The comment field's own label, so the box says "Comments" or whatever this project calls it. */
  label: string
  /** The `ver-stamp` the displayed record was read at, or undefined when it carries none. */
  expectedVersion?: string
  /** Re-read the record, so the newly-posted comment actually appears above the box. */
  onPosted: () => void
}

/** Where the typed author name is remembered between records and sessions. */
const AUTHOR_KEY = 'comment.author'

/**
 * Add a comment to a record, without destroying the ones already there.
 *
 * <h2>Why this is not a textarea bound to the memo field</h2>
 *
 * The obvious comment UI — show the field, let the user type at the bottom, PUT it back — is a
 * data-loss bug. A memo PUT <em>replaces</em> the field (probe 30): there is no server-side append,
 * so saving the box's contents would delete every earlier comment, including ones written by other
 * people in ALM's own client, and ALM would answer HTTP 200. Nothing in the response would say
 * anything was lost.
 *
 * So this box is deliberately <strong>write-only</strong>. It holds the new comment and nothing
 * else; the existing thread is rendered above it, read-only, by the memo body. The merge happens in
 * the BFF (`AlmCommentWriter`) so there is one implementation to be right rather than one per
 * caller, and this component never sees the field's current value at all — which is the point. A
 * component that held the whole thread in a textarea would be one careless save away from the bug
 * this design exists to avoid.
 *
 * <h2>What the author name is, and is not</h2>
 *
 * It is a claim, not an identity. Every write leaves under one service-account API key (ADR 0004),
 * so ALM's own record of who wrote this is the same for everyone and REST writes bypass the
 * workflow scripts that would otherwise stamp a name (probe 30). The field is offered because a
 * thread of comments all signed "Alt-ALM" is useless, and it is labelled honestly because a name
 * typed into a box is not authentication.
 */
export function CommentBox({
  project,
  collection,
  entityId,
  label,
  expectedVersion,
  onPosted,
}: Props) {
  const [text, setText] = useState('')
  const [author, setAuthor] = useState(() => readFreeText(AUTHOR_KEY, ''))
  const [posting, setPosting] = useState(false)
  const [result, setResult] = useState<WriteResult | null>(null)

  const message = result ? outcomeMessage(result) : null

  /**
   * True once an outcome arrived that must not be followed by another post.
   *
   * ⚠️ `mayWriteAgain`, not `mayKeepEditing` — the two answer different questions and this form
   * needs the other one. A committed comment is naturally followed by another comment, so the box
   * stays (emptied); a record editor closes on success and correctly uses the stricter predicate.
   *
   * The one outcome that locks it is `unknown`, and the reason is sharper here than for a create.
   * An unknown create leaves a row that may or may not exist. An unknown comment means a
   * read-modify-write over the whole field may or may not have landed — so what is unknown is the
   * state of the entire thread, not just of the sentence that was typed. Posting again could double
   * the comment, and could do it on top of a merge nobody has seen.
   */
  const locked = result !== null && !mayWriteAgain(result)

  async function post() {
    const comment = text.trim()
    // An empty comment would rewrite the whole field to add nothing — all of the risk of a memo
    // write for none of the value. The BFF refuses it too; this just avoids the round trip.
    if (comment === '') return

    setPosting(true)
    setResult(null)
    try {
      const outcome = await addComment(
        project,
        collection,
        entityId,
        comment,
        author.trim() || undefined,
        expectedVersion,
      )
      setResult(outcome)
      if (outcome.kind === 'committed') {
        setText('')
        if (author.trim()) writeString(AUTHOR_KEY, author.trim())
        // Re-read so the thread above shows what was actually stored — ALM re-serialises the
        // document on the way in (probe 27), so what comes back is not byte-identical to what
        // went out, and showing the sent text would be a guess.
        onPosted()
      }
    } catch (error) {
      // Reaching here means no outcome was produced at all — a transport or access failure. The
      // write client turns every real outcome, "nobody knows" included, into a value.
      setResult({
        kind: 'rejected',
        errorId: 'altalm.transport',
        detail: error instanceof Error ? error.message : 'The comment could not be posted.',
      })
    } finally {
      setPosting(false)
    }
  }

  function act(action: string) {
    switch (action) {
      case 'reload':
      case 'reloadAndReapply':
        onPosted()
        // ⚠️ For 'reload' the text is discarded along with the banner. That is deliberate for an
        // unknown outcome: keeping it with a live Post button underneath is the same duplicate
        // trap as a Retry button, reached by a different route. For a conflict the text is kept,
        // because nothing was written and re-applying it over the fresh record is the whole point.
        if (action === 'reload') setText('')
        setResult(null)
        break
      case 'retry':
        void post()
        break
      default:
        setResult(null)
    }
  }

  return (
    <form
      className="comment-box"
      onSubmit={(event) => {
        event.preventDefault()
        void post()
      }}
    >
      {message && (
        <div className={`record-editor-banner tone-${message.tone}`} role="status">
          <strong>{message.title}</strong>
          <p>{message.body}</p>
          {message.tone === 'unknown' && (
            // The generic wording covers "a duplicate record". Adding a comment is a
            // read-modify-write over one field, so the thing at risk is different and worth
            // naming: not a second row, the thread itself.
            <p>
              Adding a comment rewrites the whole {label.toLowerCase()} field, so what is uncertain
              is the state of every comment on this record, not just the one you typed. Read it
              before posting again.
            </p>
          )}
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

      {!locked && (
        <>
          <label className="comment-box-label" htmlFor="comment-text">
            Add a comment
          </label>
          <textarea
            id="comment-text"
            className="comment-box-text"
            rows={3}
            value={text}
            disabled={posting}
            placeholder={`Appended to the end of ${label}. Nothing already there is changed.`}
            onChange={(event) => setText(event.target.value)}
          />

          <div className="comment-box-actions">
            <label className="comment-box-author">
              <span>Signed</span>
              <input
                id="comment-author"
                value={author}
                disabled={posting}
                placeholder="Alt-ALM"
                // Honest about what this is: every write leaves under one service account, so the
                // name goes in the comment's text and nowhere ALM would treat as an identity.
                title="Written into the comment itself. Alt-ALM cannot verify it — every write reaches ALM under one shared account."
                onChange={(event) => setAuthor(event.target.value)}
              />
            </label>
            <button type="submit" disabled={posting || text.trim() === ''}>
              {posting ? 'Posting…' : 'Post comment'}
            </button>
          </div>

          <p className="comment-box-note">
            Added to the end of the existing comments — nothing already there is edited or removed.
            {expectedVersion === undefined &&
              ' This record reports no version, so a comment added by someone else since you opened it cannot be detected.'}
          </p>
        </>
      )}
    </form>
  )
}
