import { describe, expect, it } from 'vitest'
import type { WriteResult } from '../api/client.ts'
import { mayKeepEditing, mayWriteAgain, outcomeMessage } from './writeOutcome.ts'

/**
 * What the user is told, and — the part that matters — what they are offered.
 *
 * The first block asserts an **absence**, which is why it is written explicitly rather than left to
 * a reviewer's eye: an `unknown` outcome must never offer "Retry". An ALM 5xx may have committed
 * the row, so the friendly, obvious design (red banner, Retry button) manufactures duplicate
 * records for exactly the writes that succeeded. Every other failure in this app is safe to retry.
 * This one is not, and it looks like the others.
 */

const UNRESOLVED: WriteResult = {
  kind: 'unknown',
  id: null,
  verified: false,
  detail: 'server error',
}

const VERIFIED: WriteResult = {
  kind: 'unknown',
  id: '7001',
  verified: true,
  detail: 'found afterwards',
}

describe('an unknown outcome never invites a retry', () => {
  it('offers reload and nothing else when the outcome is unresolved', () => {
    const message = outcomeMessage(UNRESOLVED)

    expect(message.actions).toEqual(['reload'])
    // Stated separately from the equality above on purpose: if someone later adds an action to
    // that list, this line is the one that explains why the addition was wrong.
    expect(message.actions).not.toContain('retry')
  })

  it('still does not offer a retry when the record WAS found afterwards', () => {
    // The tempting case: we know the row is there, so a retry feels harmless. It is not — the
    // write may have applied, and re-sending an update is not idempotent for a memo field.
    expect(outcomeMessage(VERIFIED).actions).not.toContain('retry')
  })

  it('uses a tone that is neither success nor error', () => {
    // A two-valued tone forces the third state into one of the other two at the moment it is least
    // true. Both branches keep it.
    expect(outcomeMessage(UNRESOLVED).tone).toBe('unknown')
    expect(outcomeMessage(VERIFIED).tone).toBe('unknown')
  })

  it('says it does not know, rather than claiming failure', () => {
    const message = outcomeMessage(UNRESOLVED)

    expect(message.title.toLowerCase()).not.toContain('failed')
    expect(message.body.toLowerCase()).toContain('may have been')
    // The user's actual next step has to be in the text, because the banner is all they get.
    expect(message.body.toLowerCase()).toContain('duplicate')
  })

  it('closes the editor rather than leaving a Save button over an unknown write', () => {
    // Leaving the form open with the text intact is the same trap as a Retry button in a different
    // hat: the natural next gesture is to press Save again.
    expect(mayKeepEditing(UNRESOLVED)).toBe(false)
    expect(mayKeepEditing(VERIFIED)).toBe(false)
  })

  it('marks the on-screen record stale, because it may no longer match ALM', () => {
    expect(outcomeMessage(UNRESOLVED).staleOnScreen).toBe(true)
    expect(outcomeMessage(VERIFIED).staleOnScreen).toBe(true)
  })
})

describe('the outcomes where retrying IS safe', () => {
  it('offers a retry when ALM refused it, since nothing was written', () => {
    const message = outcomeMessage({
      kind: 'rejected',
      errorId: 'qccore.required-field-missing',
      detail: 'Required field missing',
    })

    expect(message.tone).toBe('error')
    expect(message.actions).toContain('retry')
    // ALM's own message is shown: it is usually the only thing that says which field.
    expect(message.body).toContain('Required field missing')
    expect(mayKeepEditing({ kind: 'rejected', errorId: '', detail: '' })).toBe(true)
  })

  it('keeps the editor open on a validation refusal and pins problems to their fields', () => {
    const message = outcomeMessage({
      kind: 'invalid',
      problems: [
        { field: 'estimate', code: 'not-a-number', detail: 'not a number' },
        { field: 'target-date', code: 'not-a-date', detail: 'not yyyy-MM-dd' },
      ],
    })

    expect(message.title).toBe('2 fields need attention')
    expect(message.fieldProblems).toEqual({
      estimate: 'not a number',
      'target-date': 'not yyyy-MM-dd',
    })
    // Nothing left the BFF, so the user's text is still exactly what they should be fixing.
    expect(mayKeepEditing({ kind: 'invalid', problems: [] })).toBe(true)
    expect(message.staleOnScreen).toBe(false)
  })

  it('singularises one problem, because "1 fields" reads as a bug', () => {
    expect(
      outcomeMessage({
        kind: 'invalid',
        problems: [{ field: 'name', code: 'too-long', detail: 'too long' }],
      }).title,
    ).toBe('One field needs attention')
  })

  it('does not pin a whole-body problem to a field that does not exist', () => {
    const message = outcomeMessage({
      kind: 'invalid',
      problems: [{ field: '', code: 'empty-body', detail: 'send at least one field' }],
    })

    expect(message.fieldProblems).toEqual({})
  })
})

describe('a conflict asks for a re-read, not a re-send', () => {
  it('offers reload-and-reapply rather than a plain retry', () => {
    const message = outcomeMessage({ kind: 'conflict', detail: 'the record changed' })

    expect(message.tone).toBe('conflict')
    expect(message.actions).toContain('reloadAndReapply')
    // A plain retry here would re-send the same expected version and simply conflict again, or
    // worse, be re-sent without one and overwrite the other person's work.
    expect(message.actions).not.toContain('retry')
  })

  it('says whose problem it is in terms the user can act on', () => {
    const body = outcomeMessage({ kind: 'conflict', detail: '' }).body.toLowerCase()

    expect(body).toContain('changed after you opened it')
    expect(body).toContain('re-apply')
  })
})

describe('a committed write', () => {
  it('is plainly a success', () => {
    const message = outcomeMessage({ kind: 'committed', id: '7001', retried: false })

    expect(message.tone).toBe('success')
    expect(message.actions).toEqual(['dismiss'])
    expect(message.staleOnScreen).toBe(false)
  })

  it('mentions the automatic retry, because it means project metadata is wrong', () => {
    // Not cosmetic: `retried` is the signal that this project reports a field as neither required
    // nor editable while the server demands it. Someone should eventually look.
    const message = outcomeMessage({ kind: 'committed', id: '7001', retried: true })

    expect(message.body).toContain('required')
    expect(message.tone).toBe('success')
  })
})

describe('mayWriteAgain, and how it differs from mayKeepEditing', () => {
  it('refuses only the unknown outcome', () => {
    // The single rule: a write button may be on screen from any KNOWN state — written or not
    // written — and from an unknown one it is how a duplicate is made.
    expect(mayWriteAgain(UNRESOLVED)).toBe(false)
    expect(mayWriteAgain(VERIFIED)).toBe(false)

    expect(mayWriteAgain({ kind: 'committed', id: '1', retried: false })).toBe(true)
    expect(mayWriteAgain({ kind: 'rejected', errorId: '', detail: '' })).toBe(true)
    expect(mayWriteAgain({ kind: 'invalid', problems: [] })).toBe(true)
    expect(mayWriteAgain({ kind: 'conflict', detail: '' })).toBe(true)
  })

  it('disagrees with mayKeepEditing on exactly the two outcomes where a form must not be reused', () => {
    // ⚠️ This is the assertion that stops the two being collapsed into one predicate later.
    //
    // A COMMITTED write invalidates the draft (it has been saved) but not the form — a comment box
    // is used again immediately. A CONFLICT invalidates the draft's basis but wrote nothing, so
    // re-applying over a fresh read is the offered path. Merging the two predicates gives one
    // caller the wrong behaviour, silently, in whichever direction the merge went.
    const committed = { kind: 'committed', id: '1', retried: false } as const
    const conflict = { kind: 'conflict', detail: '' } as const

    expect(mayKeepEditing(committed)).toBe(false)
    expect(mayWriteAgain(committed)).toBe(true)

    expect(mayKeepEditing(conflict)).toBe(false)
    expect(mayWriteAgain(conflict)).toBe(true)
  })
})
