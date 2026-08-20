import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { GridColumn } from '../api/client.ts'
import { DetailPane } from './DetailPane.tsx'

/**
 * The detail pane's own behaviour — the parts no child component can be asked about.
 *
 * <h2>Why this suite exists</h2>
 *
 * The pane is mostly composition, and composition is usually not worth a test. Three of its
 * decisions are, because each one is an <em>absence or a routing choice</em> that looks correct
 * from the outside while being wrong:
 *
 * <ol>
 *   <li><strong>Reload has to actually reload.</strong> After an unknown write outcome the only
 *       action offered anywhere in the app is "reload the record". That was wired to a counter the
 *       record fetch did not depend on, so the pane went on showing pre-write values while telling
 *       the user to go and look at what ALM stored. Nothing on screen said so.
 *   <li><strong>The comment box belongs to one field.</strong> Which field takes comments is
 *       per-entity metadata (probe 30). Offering the box against the wrong memo would let a note be
 *       written over a description.
 *   <li><strong>Memo values must reach the sanitiser.</strong> The sanitiser is well tested; that it
 *       is on the path is not something the sanitiser's own tests can show.
 * </ol>
 */

function column(name: string, over: Partial<GridColumn> = {}): GridColumn {
  return {
    name,
    label: name,
    type: 'STRING',
    listId: 0,
    multiValue: false,
    onDetailsForm: true,
    riskGroup: false,
    groupable: false,
    writable: true,
    choiceSource: 'NONE',
    ...over,
  }
}

const COLUMNS: GridColumn[] = [
  column('id'),
  column('name'),
  column('status'),
  column('description', { type: 'MEMO', label: 'Description' }),
  column('comments', { type: 'MEMO', label: 'Comments' }),
]

/** The record, parameterised so a reload can return something visibly different. */
function detail(name: string, writable = true) {
  return {
    collection: 'requirements',
    writable,
    columns: COLUMNS,
    rows: [
      {
        id: '7001',
        values: {
          id: ['7001'],
          name: [name],
          status: ['Reviewed'],
          description: ['<html><body><p>plain enough</p></body></html>'],
          comments: ['<html><body><p>an earlier comment</p></body></html>'],
          'ver-stamp': ['3'],
        },
        childCount: 0,
        error: null,
      },
    ],
    total: 1,
  }
}

interface Routes {
  /** Successive detail responses; the last one repeats once exhausted. */
  details?: unknown[]
  commentField?: string | null
  /** Detail requests from this index on hang until `release()` — see the skeleton test. */
  holdDetailFrom?: number
}

function mockApi({
  details = [detail('First read')],
  commentField = 'comments',
  holdDetailFrom,
}: Routes = {}) {
  let call = 0
  /** Resolvers for detail responses being held open, so a test can observe the in-flight state. */
  const held: (() => void)[] = []

  const fetchMock = vi.fn().mockImplementation((url: string) => {
    const path = String(url)
    const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: async () => body })

    if (path.includes('/api/detail/')) {
      const index = call
      const body = details[Math.min(index, details.length - 1)]
      call += 1
      if (holdDetailFrom !== undefined && index >= holdDetailFrom) {
        // Left pending until the test releases it. Without this, "the skeleton never appeared" is
        // unfalsifiable: the request resolves within the same tick and any flash is over before an
        // assertion can run.
        return new Promise((resolve) => {
          held.push(() => resolve({ ok: true, status: 200, json: async () => body }))
        })
      }
      return ok(body)
    }
    if (path.includes('/comment-field')) {
      return commentField === null
        ? Promise.resolve({ ok: false, status: 404, json: async () => ({}) })
        : ok({ field: commentField })
    }
    // Posting a comment is how a test reaches the reload path; the write itself is the
    // CommentBox's business and is pinned by its own suite.
    if (path.includes('/comments')) return ok({ outcome: 'COMMITTED', id: '7001', retried: false })
    if (path.includes('/api/tabs/')) return ok({ tabs: [] })
    if (path.includes('/api/history/')) return ok({ entries: [], partial: false })
    return ok({})
  })
  vi.stubGlobal('fetch', fetchMock)
  return Object.assign(fetchMock, {
    /** Lets every held detail response complete. */
    release: () => held.splice(0).forEach((r) => r()),
  })
}

function pane(over: Partial<React.ComponentProps<typeof DetailPane>> = {}) {
  return (
    <DetailPane
      project="PROJ"
      collection="requirements"
      entityId="7001"
      onNavigate={() => {}}
      {...over}
    />
  )
}

/** Detail requests only — the pane also fetches tabs, history and the comment field. */
function detailCalls(fetchMock: ReturnType<typeof mockApi>) {
  return fetchMock.mock.calls.filter((c) => String(c[0]).includes('/api/detail/'))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the comment box', () => {
  it('is offered on the comment field’s tab and nowhere else', async () => {
    mockApi()
    render(pane())

    await screen.findByText('First read')

    // Description is a memo too, and it is not where comments go. A box here would let a note be
    // written over the requirement's description — same control, different field, silent damage.
    await userEvent.click(screen.getByRole('tab', { name: /Description/ }))
    expect(screen.queryByLabelText('Add a comment')).toBeNull()

    await userEvent.click(screen.getByRole('tab', { name: /Comments/ }))
    expect(await screen.findByLabelText('Add a comment')).not.toBeNull()
  })

  it('is not offered when the entity has no comment field at all', async () => {
    // A 404 from the comment-field route is a legitimate answer, not an error: some entities have
    // none. The pane must degrade to a read-only memo rather than guessing which field to use.
    mockApi({ commentField: null })
    render(pane())

    await screen.findByText('First read')
    await userEvent.click(screen.getByRole('tab', { name: /Comments/ }))

    expect(screen.queryByLabelText('Add a comment')).toBeNull()
  })

  it('is not offered on a project that is not writable', async () => {
    mockApi({ details: [detail('First read', false)] })
    render(pane())

    await screen.findByText('First read')
    await userEvent.click(screen.getByRole('tab', { name: /Comments/ }))

    expect(screen.queryByLabelText('Add a comment')).toBeNull()
    expect(screen.getByText('Read only')).not.toBeNull()
  })
})

describe('reloading', () => {
  it('re-reads the record when a child asks it to, and shows the new values', async () => {
    // ⚠️ The regression this suite was written for. `reloadToken` drove the tab marks but not the
    // record fetch, so "Reload the record" — the single action offered after an unknown write —
    // did nothing visible and left stale values on screen under a banner saying they were stale.
    const fetchMock = mockApi({ details: [detail('First read'), detail('Second read')] })
    render(pane())

    await screen.findByText('First read')
    expect(detailCalls(fetchMock)).toHaveLength(1)

    // Posting a comment is the reload path a user can actually reach from here.
    await userEvent.click(screen.getByRole('tab', { name: /Comments/ }))
    await userEvent.type(await screen.findByLabelText('Add a comment'), 'a note')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    await screen.findByText('Second read')
    await waitFor(() => expect(detailCalls(fetchMock).length).toBeGreaterThan(1))
  })

  it('keeps the record on screen WHILE re-reading it, rather than flashing the skeleton', async () => {
    // The second detail request is held open, so the assertions below run against the genuine
    // in-flight state rather than after it has already resolved.
    const fetchMock = mockApi({
      details: [detail('First read'), detail('Second read')],
      holdDetailFrom: 1,
    })
    render(pane())
    await screen.findByText('First read')

    await userEvent.click(screen.getByRole('tab', { name: /Comments/ }))
    await userEvent.type(await screen.findByLabelText('Add a comment'), 'a note')
    await userEvent.click(screen.getByRole('button', { name: 'Post comment' }))

    await waitFor(() => expect(detailCalls(fetchMock).length).toBeGreaterThan(1))

    // The pane is out of date, not empty. Blanking it would throw away the outcome banner that
    // asked for the reload — which after an unknown outcome is the only thing telling the user
    // what happened.
    expect(screen.queryByLabelText('Loading record')).toBeNull()
    expect(screen.getByText('First read')).not.toBeNull()

    fetchMock.release()
    await screen.findByText('Second read')
  })

  it('does show the skeleton when moving to a different record', async () => {
    mockApi({ details: [detail('First read')] })
    const { rerender } = render(pane())
    await screen.findByText('First read')

    rerender(pane({ entityId: '7002' }))

    // A different record must not borrow this one's fields under its own header, even briefly.
    expect(screen.getByLabelText('Loading record')).not.toBeNull()
  })
})

describe('memo rendering', () => {
  it('routes memo values through the sanitiser rather than to innerHTML raw', async () => {
    const hostile = detail('First read')
    hostile.rows[0].values.description = [
      '<html><body><p>safe text</p><script>window.pwned = 1</script>' +
        '<img src="x" onerror="window.pwned = 1"></body></html>',
    ]
    mockApi({ details: [hostile] })
    render(pane())

    await screen.findByText('First read')
    await userEvent.click(screen.getByRole('tab', { name: /Description/ }))

    const body = await screen.findByText(/safe text/)
    // The sanitiser has its own thorough suite; what that suite cannot show is whether the pane
    // actually calls it. This asserts the wiring, on the payload the wiring exists for.
    expect(body.closest('.detail-memo')?.innerHTML ?? '').not.toContain('<script')
    expect(body.closest('.detail-memo')?.innerHTML ?? '').not.toContain('onerror')
    expect((window as unknown as { pwned?: number }).pwned).toBeUndefined()
  })
})
