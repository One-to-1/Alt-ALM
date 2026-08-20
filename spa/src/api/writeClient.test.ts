import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addComment,
  ApiError,
  createRecord,
  deleteRecord,
  fetchCommentField,
  updateRecord,
  type WriteResult,
} from './client.ts'

/**
 * The write client, whose entire shape exists to keep one mistake unmakeable.
 *
 * An ALM 5xx may have committed the row. The BFF serves that as HTTP 502 with
 * `"outcome": "UNKNOWN"` in the body — and 502 is precisely the status a client library treats as
 * "transient, safe to retry". Retrying a write that already landed makes a duplicate record.
 *
 * So the assertions that matter here are about **what the client refuses to conclude**: it never
 * turns an UNKNOWN into an error, never marks a failed write retryable, and never upgrades a
 * verified-unknown to committed. The rest of the suite is ordinary plumbing.
 */

const PROJECT = 'DOM/PROJ'

function respondWith(status: number, body: unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    }),
  )
}

/** A server that returns something that is not JSON at all — a proxy error page, typically. */
function respondWithNonJson(status: number): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => {
        throw new Error('not json')
      },
    }),
  )
}

function lastCall(): [string, RequestInit] {
  const mock = globalThis.fetch as unknown as { mock: { calls: [string, RequestInit][] } }
  return mock.mock.calls[mock.mock.calls.length - 1]
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

// ==============================================================================================

describe('an UNKNOWN outcome is an answer, not an error', () => {
  it('does NOT throw on the 502 the BFF uses for an unresolved unknown', async () => {
    respondWith(502, {
      outcome: 'UNKNOWN',
      id: null,
      verified: false,
      retried: false,
      errorId: 'qccore.general-error',
      detail: 'the create may still have taken effect',
      problems: [],
    })

    // If this ever starts throwing, the UI loses its only chance to tell the user to go and look,
    // and whatever generic error handler catches it will almost certainly offer "Retry".
    const result = await createRecord(PROJECT, 'requirements', { name: 'x' })

    expect(result.kind).toBe('unknown')
    expect(result).toMatchObject({ verified: false })
  })

  it('keeps a VERIFIED unknown as unknown rather than upgrading it to committed', async () => {
    respondWith(201, {
      outcome: 'UNKNOWN',
      id: '7001',
      verified: true,
      retried: false,
      errorId: 'qccore.general-error',
      detail: 'a follow-up query found the record',
      problems: [],
    })

    const result = await createRecord(PROJECT, 'requirements', { name: 'x' })

    // "The row exists" and "the write succeeded" are different claims; only the first has evidence.
    // The caller may proceed — it has an id — but the record of what happened stays honest.
    expect(result.kind).toBe('unknown')
    expect(result).toMatchObject({ verified: true, id: '7001' })
  })

  it('treats an outcome it does not recognise as unknown, never as success', async () => {
    respondWith(200, {
      outcome: 'SOMETHING_NEW',
      id: '7001',
      verified: false,
      retried: false,
      errorId: '',
      detail: '',
      problems: [],
    })

    const result = await createRecord(PROJECT, 'requirements', { name: 'x' })

    // The safe direction for an unfamiliar write outcome is "go and look".
    expect(result.kind).toBe('unknown')
  })

  it('does not mark a failed write retryable, even when the read path would', async () => {
    // The same `alm-unavailable` body is retryable on a read. On a write it is not: the request
    // may have committed, and "retryable" is what an automatic retry looks for.
    respondWith(502, { error: 'alm-unavailable', almStatus: 500 })

    await expect(createRecord(PROJECT, 'requirements', { name: 'x' })).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.retryable === false,
    )
  })

  it('reports a dropped connection as "may or may not have applied", not as retryable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))

    await expect(updateRecord(PROJECT, 'requirements', '7001', { name: 'x' })).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        error.retryable === false &&
        /may or may not/i.test(error.message),
    )
  })
})

describe('the ordinary outcomes', () => {
  it('reads a committed create', async () => {
    respondWith(201, {
      outcome: 'COMMITTED',
      id: '7001',
      verified: false,
      retried: false,
      errorId: '',
      detail: 'committed',
      problems: [],
    })

    const result: WriteResult = await createRecord(PROJECT, 'requirements', { name: 'x' })

    expect(result).toEqual({ kind: 'committed', id: '7001', retried: false })
  })

  it('surfaces `retried`, which means this project’s metadata lied about a field', async () => {
    respondWith(201, {
      outcome: 'COMMITTED',
      id: '7001',
      verified: false,
      retried: true,
      errorId: '',
      detail: 'committed on the second attempt',
      problems: [],
    })

    expect(await createRecord(PROJECT, 'requirements', { name: 'x' })).toMatchObject({
      retried: true,
    })
  })

  it('reads a validation refusal with every problem, not just the first', async () => {
    respondWith(422, {
      outcome: 'INVALID',
      id: null,
      verified: false,
      retried: false,
      errorId: 'altalm.validation',
      detail: 'refused before it reached ALM',
      problems: [
        { field: 'nmae', code: 'unknown-field', detail: 'no such field' },
        { field: 'estimate', code: 'not-a-number', detail: 'not a number' },
      ],
    })

    const result = await createRecord(PROJECT, 'requirements', { nmae: 'x' })

    expect(result.kind).toBe('invalid')
    if (result.kind === 'invalid') {
      // A form fixed one field per round trip is a form nobody finishes.
      expect(result.problems.map((p) => p.code)).toEqual(['unknown-field', 'not-a-number'])
    }
  })

  it('reads an ALM refusal, keeping it distinct from our own', async () => {
    respondWith(400, {
      outcome: 'REJECTED',
      id: null,
      verified: false,
      retried: false,
      errorId: 'qccore.required-field-missing',
      detail: 'Required field missing',
      problems: [],
    })

    const result = await createRecord(PROJECT, 'requirements', { name: 'x' })

    expect(result.kind).toBe('rejected')
    expect(result).toMatchObject({ errorId: 'qccore.required-field-missing' })
  })

  it('reads a version conflict as an outcome the caller must handle', async () => {
    respondWith(409, { error: 'version-conflict', detail: 'the record changed since it was read' })

    const result = await updateRecord(PROJECT, 'requirements', '7001', { name: 'x' }, {
      name: 'what I loaded',
    })

    // A union member rather than a throw: editing a record someone else touched is a normal
    // Tuesday, and a throw is the kind of thing a caller forgets to catch.
    expect(result.kind).toBe('conflict')
    expect(result).toMatchObject({ detail: expect.stringContaining('changed') })
  })

  it('does not mistake a non-JSON error page for a write outcome', async () => {
    respondWithNonJson(500)

    await expect(deleteRecord(PROJECT, 'requirements', '7001')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('the requests it sends', () => {
  it('sends the caller\'s baseline values on an update', async () => {
    respondWith(200, {
      outcome: 'COMMITTED',
      id: '7001',
      verified: false,
      retried: false,
      errorId: '',
      detail: '',
      problems: [],
    })

    await updateRecord(PROJECT, 'requirements', '7001', { name: 'new' }, { name: 'old' })

    const [url, init] = lastCall()
    expect(url).toContain('/api/records/requirements/7001')
    expect(init.method).toBe('PUT')
    // Dropping this would silently downgrade every edit to last-writer-wins while the API still
    // looked like it took a baseline.
    expect(JSON.parse(String(init.body))).toEqual({
      fields: { name: 'new' },
      expectedValues: { name: 'old' },
    })
  })

  it('sends a comment to the comments route, never as a field update', async () => {
    respondWith(200, {
      outcome: 'COMMITTED',
      id: '7001',
      verified: false,
      retried: false,
      errorId: '',
      detail: '',
      problems: [],
    })

    await addComment(PROJECT, 'requirements', '7001', 'a note', 'Alice')

    const [url, init] = lastCall()
    // A PUT of the comment field would REPLACE it and destroy every earlier comment, with a 200
    // and nothing to notice (probe 30). The route is the guard.
    expect(url).toContain('/api/records/requirements/7001/comments')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toMatchObject({ comment: 'a note', author: 'Alice' })
  })

  it('percent-encodes ids and collections rather than interpolating them raw', async () => {
    respondWith(200, {
      outcome: 'COMMITTED',
      id: '1',
      verified: false,
      retried: false,
      errorId: '',
      detail: '',
      problems: [],
    })

    await deleteRecord(PROJECT, 'test-sets', 'a/b')

    expect(lastCall()[0]).toContain('/api/records/test-sets/a%2Fb')
  })
})

describe('the comment field is discovered, not assumed', () => {
  it('returns the field name when the entity has one', async () => {
    respondWith(200, { field: 'dev-comments' })

    expect(await fetchCommentField(PROJECT, 'defects')).toBe('dev-comments')
  })

  it('returns null on a 404 so the UI can omit the comment box entirely', async () => {
    respondWith(404, {})

    // Null is a real answer, not a failure: some entities have no comment field, and offering a
    // box that cannot save is worse than offering none.
    expect(await fetchCommentField(PROJECT, 'run-steps')).toBeNull()
  })
})
