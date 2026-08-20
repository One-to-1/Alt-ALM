import type { WriteResult } from '../api/client.ts'

/**
 * Turns a write outcome into what the user is told and what they are offered.
 *
 * <h2>Why this is a pure module rather than JSX</h2>
 *
 * The same reason `richText.ts` is: the decision worth testing is not how it looks, it is what it
 * *says* and — far more important — **what it offers to do next**. Those are assertions about
 * strings and action lists, and putting them behind a component would mean testing them through a
 * renderer that has nothing to do with the risk.
 *
 * <h2>The decision this file exists for</h2>
 *
 * ⚠️ **An `unknown` outcome must never offer "Retry".**
 *
 * An ALM 5xx may have committed the row. The obvious, friendly, wrong design is a red banner
 * saying "Save failed" with a Retry button — which, for the write that actually landed, creates a
 * second record. Every other failure mode in this app is safe to retry; this one is not, and it is
 * the one that looks most like the others.
 *
 * So `unknown` gets its own tone (neither success nor error), language that does not claim to know,
 * and exactly one action: go and look. {@link outcomeMessage} is the single place that mapping
 * lives, and `writeOutcome.test.ts` asserts the absence of `retry` directly, because an absence is
 * not something a reviewer notices.
 */

/**
 * How the banner should read. Deliberately not `'success' | 'error'`: the third state is the whole
 * point, and a two-valued tone forces it into one of the other two at the moment it is least true.
 */
export type OutcomeTone = 'success' | 'error' | 'unknown' | 'conflict'

/**
 * What the user may do next.
 *
 * - `retry` — re-send the same write. Only offered when nothing was written.
 * - `reload` — re-read the record from ALM. The only action offered for `unknown`.
 * - `reloadAndReapply` — re-read, then re-open the editor with the user's text, for a conflict.
 * - `dismiss` — close the banner.
 */
export type OutcomeAction = 'retry' | 'reload' | 'reloadAndReapply' | 'dismiss'

export interface OutcomeMessage {
  tone: OutcomeTone
  title: string
  /** One or two sentences. Plain language: this is read by someone who just pressed Save. */
  body: string
  actions: OutcomeAction[]
  /** Field-level problems to show against the form's inputs, keyed by field name. */
  fieldProblems: Record<string, string>
  /** True when the record on screen is known to be stale and should be re-read. */
  staleOnScreen: boolean
}

export function outcomeMessage(result: WriteResult): OutcomeMessage {
  switch (result.kind) {
    case 'committed':
      return {
        tone: 'success',
        title: 'Saved',
        body: result.retried
          ? 'Saved. ALM required a field this project’s settings do not list as required, which Alt-ALM supplied automatically.'
          : 'Your changes were saved.',
        actions: ['dismiss'],
        fieldProblems: {},
        staleOnScreen: false,
      }

    case 'unknown':
      // ⚠️ Both branches are 'unknown' and neither offers 'retry'. See the file header.
      return result.verified
        ? {
            tone: 'unknown',
            title: 'Probably saved',
            body:
              'ALM reported a server error, but the record was found afterwards, so the change appears to have taken effect. ' +
              'Reload to see exactly what was stored.',
            actions: ['reload', 'dismiss'],
            fieldProblems: {},
            staleOnScreen: true,
          }
        : {
            tone: 'unknown',
            title: 'It is not known whether this saved',
            body:
              'ALM reported a server error, and a follow-up check could not tell whether the change was applied. ' +
              'It may have been. Reload the record and look before trying again — saving again could create a duplicate.',
            actions: ['reload'],
            fieldProblems: {},
            staleOnScreen: true,
          }

    case 'rejected':
      return {
        tone: 'error',
        title: 'ALM refused the change',
        body: result.detail || 'ALM rejected the change. Nothing was saved.',
        actions: ['retry', 'dismiss'],
        fieldProblems: {},
        staleOnScreen: false,
      }

    case 'invalid':
      return {
        tone: 'error',
        title:
          result.problems.length === 1
            ? 'One field needs attention'
            : `${result.problems.length} fields need attention`,
        body: 'Nothing was sent to ALM. Fix the fields marked below and save again.',
        actions: ['dismiss'],
        // Whole-body problems arrive with an empty field name; they are surfaced in the body text
        // by the component rather than pinned to an input that does not exist.
        fieldProblems: Object.fromEntries(
          result.problems.filter((p) => p.field !== '').map((p) => [p.field, p.detail]),
        ),
        staleOnScreen: false,
      }

    case 'conflict':
      return {
        tone: 'conflict',
        title: 'Someone else changed this record',
        body:
          'The record changed after you opened it, so your edit was not applied — saving would have overwritten their work. ' +
          'Reload to see the current version, then re-apply your change.',
        actions: ['reloadAndReapply', 'dismiss'],
        fieldProblems: {},
        staleOnScreen: true,
      }
  }
}

/**
 * Whether the editor may stay open with the user's text intact.
 *
 * True only when nothing was written and re-sending is safe. For `unknown` this is false — the
 * editor closes and the record is re-read, because leaving a Save button under an edit whose fate
 * is unknown is the same trap as offering Retry, wearing a different hat.
 */
export function mayKeepEditing(result: WriteResult): boolean {
  return result.kind === 'invalid' || result.kind === 'rejected'
}

/**
 * Whether it is safe to offer another write at all.
 *
 * ⚠️ **Not the same question as {@link mayKeepEditing}, and the difference is not cosmetic.** That
 * one asks whether the user's *draft* survives — true only when nothing was written, so a
 * `committed` outcome answers false and the editor closes. This asks whether a write button may be
 * on screen, which for a form that is used repeatedly (a comment box, where posting one comment is
 * naturally followed by posting another) is a different thing entirely.
 *
 * False for exactly one outcome: `unknown`. Every other outcome is a known state — written, or not
 * written — and offering another write from a known state is ordinary. From an unknown one it is
 * how a duplicate gets created.
 *
 * The two predicates exist separately rather than as one because collapsing them means one of the
 * two callers gets the wrong behaviour, and the failure is silent in both directions: a comment box
 * that vanishes after every successful comment, or an editor whose Save button outlives the write
 * it is unsure about.
 */
export function mayWriteAgain(result: WriteResult): boolean {
  return result.kind !== 'unknown'
}
