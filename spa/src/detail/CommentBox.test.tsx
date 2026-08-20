import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CommentBox } from './CommentBox.tsx'

/**
 * The comment box, driven the way a user drives it.
 *
 * <h2>What is actually at stake here</h2>
 *
 * Adding a comment is the most dangerous ordinary write in the app. There is no server-side append
 * (probe 30): the BFF reads the field, merges, and PUTs the whole thing back, so a comment write
 * rewrites every comment on the record. That makes two absences worth asserting directly, because
 * an absence is not something a reviewer notices:
 *
 * <ol>
 *   <li>An <strong>unknown</strong> outcome offers no way to post again — not a Retry button, and
 *       not a live Post button under the banner either. The record editor had exactly that second
 *       bug, and its first component-test run is what found it.
 *   <li>The box never renders the existing thread. It is write-only by construction; a textarea
 *       holding the current comments is the shape that deletes them.
 * </ol>
 */

const PROPS = {
  project: 'PROJ',
  collection: 'requirements',
  entityId: '7001',
  label: 'Comments',
  expectedThread: '<html><body>an earlier note</body></html>',
  onPosted: () => {},
}

function respondWith(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const COMMITTED = { outcome: 'COMMITTED', id: '7001', retried: false }

function textbox(): HTMLTextAreaElement {
  return screen.getByLabelText('Add a comment') as HTMLTextAreaElement
}

function signed(): HTMLInputElement {
  return screen.getByLabelText('Signed') as HTMLInputElement
}

/** jest-dom is not registered as a setup file here, so buttons are read directly. */
function button(name: string): HTMLButtonElement {
  return screen.getByRole('button', { name }) as HTMLButtonElement
}

async function type(text: string) {
  await userEvent.type(textbox(), text)
}

async function post() {
  await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))
}

beforeEach(() => {
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('posting', () => {
  it('sends the comment to the comments route, with the version it was read at', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    await type('Looks fine to me')
    await post()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/records/requirements/7001/comments')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toMatchObject({
      comment: 'Looks fine to me',
      // ⚠️ Sent so a comment added since this record was read is DETECTED. It is not a lock —
      // ALM accepts a stale write — but without it a concurrent comment is silently swallowed by
      // the merge, which is the whole failure this route exists to avoid.
      //
      // ⚠️ The THREAD, not a ver-stamp: a stamp also moves when someone files a child under
      // the record, which refused comments with nothing to conflict with (probe 34).
      expectedThread: '<html><body>an earlier note</body></html>',
    })
  })

  it('will not post an empty or whitespace-only comment', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    expect(button('Post comment').disabled).toBe(true)

    // Whitespace is not a comment, and posting one would rewrite the whole field to add nothing.
    await type('   ')
    expect(button('Post comment').disabled).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('trims the comment rather than storing the user’s trailing newlines', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    await type('  spaced out  ')
    await post()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body)).comment).toBe('spaced out')
  })

  it('clears the box and asks for a re-read once the comment is committed', async () => {
    respondWith(200, COMMITTED)
    const onPosted = vi.fn()
    render(<CommentBox {...PROPS} onPosted={onPosted} />)

    await type('A note')
    await post()

    await screen.findByText('Saved')
    // Re-read rather than optimistically appending: ALM re-serialises the document on the way in
    // (probe 27), so what is stored is not what was sent and rendering the sent text would be a
    // guess dressed up as the record.
    expect(onPosted).toHaveBeenCalled()
    expect(textbox().value).toBe('')
  })

  it('never renders the existing thread — the box is write-only by construction', () => {
    respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    // Nothing in the props can carry the current comments, and the component asks for nothing on
    // mount. This asserts the shape: a box that held the thread would be one save away from
    // replacing it.
    expect(textbox().value).toBe('')
    // `window.fetch`, not `global.fetch`: the SPA's tsconfig carries DOM lib and no Node types,
    // so the Node-only global fails the build even though vitest resolves it at run time.
    expect(window.fetch).not.toHaveBeenCalled()
  })
})

describe('an unknown outcome', () => {
  /** 502 with an UNKNOWN body: the status describes the upstream, the body describes the row. */
  const UNKNOWN = { outcome: 'UNKNOWN', id: null, verified: false, detail: 'ALM 500' }

  it('offers no way at all to post again', async () => {
    respondWith(502, UNKNOWN)
    render(<CommentBox {...PROPS} />)

    await type('Might have landed')
    await post()

    await screen.findByText('It is not known whether this saved')

    // Both routes to a second write, asserted separately — suppressing one and leaving the other
    // is exactly the bug the record editor shipped with.
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Post comment' })).toBeNull()
    expect(screen.queryByLabelText('Add a comment')).toBeNull()
  })

  it('says the whole thread is what is uncertain, not just the new comment', async () => {
    respondWith(502, UNKNOWN)
    render(<CommentBox {...PROPS} />)

    await type('Might have landed')
    await post()

    // The generic wording warns about a duplicate record. For a read-modify-write over one memo
    // field the thing at risk is different, and a user told "you might get a duplicate" would
    // reasonably conclude the worst case is tidy.
    await screen.findByText(/state of every comment on this record/)
  })

  it('re-reads the record when asked to', async () => {
    respondWith(502, UNKNOWN)
    const onPosted = vi.fn()
    render(<CommentBox {...PROPS} onPosted={onPosted} />)

    await type('Might have landed')
    await post()
    await userEvent.click(await screen.findByRole('button', { name: 'Reload the record' }))

    expect(onPosted).toHaveBeenCalled()
  })
})

describe('outcomes that are safe to re-send', () => {
  it('keeps the text and offers Try again when ALM refused it', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'qccore.field-error', detail: 'no' })
    render(<CommentBox {...PROPS} />)

    await type('rejected text')
    await post()

    await screen.findByText('ALM refused the change')
    // Nothing was written, so the text is still worth something and re-sending is safe.
    expect(textbox().value).toBe('rejected text')
    expect(button('Try again').disabled).toBe(false)
  })

  it('keeps the text on a conflict, so it can be re-applied over the fresh record', async () => {
    respondWith(409, { outcome: 'CONFLICT', detail: 'moved on' })
    render(<CommentBox {...PROPS} />)

    await type('my note')
    await post()

    await screen.findByText('Someone else changed this record')
    // Nothing was written, so the box stays open with the text in it — and it survives the
    // reload, which is the whole point of offering "re-apply" rather than "reload".
    expect(textbox().value).toBe('my note')
    await userEvent.click(button('Reload and re-apply'))
    expect(textbox().value).toBe('my note')
    expect(button('Post comment').disabled).toBe(false)
  })

  it('turns a transport failure into a rejection rather than an unknown', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    render(<CommentBox {...PROPS} />)

    await type('never left')
    await post()

    // A request that never produced a response produced no outcome either — treating it as
    // unknown would lock the box over a write that demonstrably did not happen.
    await screen.findByText('ALM refused the change')
    expect(button('Try again').disabled).toBe(false)
  })
})

describe('the author name', () => {
  it('is sent as typed, and remembered for the next record', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    const { unmount } = render(<CommentBox {...PROPS} />)

    await userEvent.type(signed(), 'A. Reviewer')
    await type('signed note')
    await post()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body)).author).toBe('A. Reviewer')

    unmount()
    render(<CommentBox {...PROPS} />)
    expect(signed().value).toBe('A. Reviewer')
  })

  it('sends nothing when left blank, so the BFF’s own honest default applies', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    await type('unsigned')
    await post()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    // Null, not an empty string and not a borrowed service-account name: the server substitutes
    // "Alt-ALM", which claims nothing about who typed this.
    expect(JSON.parse(String(init.body)).author).toBeNull()
  })

  it('is not remembered when the comment did not commit', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'x', detail: 'no' })
    const { unmount } = render(<CommentBox {...PROPS} />)

    await userEvent.type(signed(), 'Someone')
    await type('rejected')
    await post()
    await screen.findByText('ALM refused the change')

    unmount()
    render(<CommentBox {...PROPS} />)
    expect(signed().value).toBe('')
  })
})

describe('the baseline note', () => {
  it('says so when the existing thread could not be read', () => {
    respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} expectedThread={undefined} />)

    // Silence here would be the misleading option: with no baseline the merge can swallow somebody
    // else's comment and nothing will have noticed.
    expect(screen.getByText(/cannot be detected/)).not.toBeNull()
  })

  it('stays quiet for a record whose thread is empty but WAS read', () => {
    // An empty string is a baseline. Treating it as "no baseline" would put the warning on every
    // record that has not been commented on yet, which is most of them.
    respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} expectedThread="" />)

    expect(screen.queryByText(/cannot be detected/)).toBeNull()
  })

  it('stays quiet when there is a baseline', () => {
    respondWith(200, COMMITTED)
    render(<CommentBox {...PROPS} />)

    expect(screen.queryByText(/cannot be detected/)).toBeNull()
  })
})
