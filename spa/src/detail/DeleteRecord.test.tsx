import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DeleteRecord } from './DeleteRecord.tsx'

/**
 * The delete confirmation.
 *
 * <h2>The two things worth pinning</h2>
 *
 * <ol>
 *   <li><strong>The cascade warning is unconditional.</strong> It is tempting to show it only when
 *       the record has children — and that version would be silent forever, because ALM's
 *       {@code children-count} reads 0 for every node on this version (probe 19). A warning that
 *       cannot fire reads as "Alt-ALM checked and there is nothing underneath". These tests assert
 *       it appears on a plain record, which is the case a well-meaning refactor would remove.
 *   <li><strong>An unknown outcome offers no retry.</strong> Same rule as every write, sharper
 *       consequence: a delete has no undo, and a second attempt against an already-deleted row
 *       reports failure for a delete that worked.
 * </ol>
 */

const PROPS = {
  project: 'PROJ',
  collection: 'requirements',
  id: '7001',
  name: 'A requirement',
  onDeleted: () => {},
  onCancel: () => {},
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
const UNKNOWN = { outcome: 'UNKNOWN', id: null, verified: false, detail: 'ALM 500' }

function button(name: string): HTMLButtonElement {
  return screen.getByRole('button', { name }) as HTMLButtonElement
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the confirmation', () => {
  it('names the record, and warns that the delete does not cascade', () => {
    respondWith(200, COMMITTED)
    render(<DeleteRecord {...PROPS} />)

    expect(screen.getByText('A requirement')).not.toBeNull()
    expect(screen.getByText('#7001')).not.toBeNull()

    // ⚠️ Unconditional, and asserted on a record with no stated children precisely because the
    // conditional version is the plausible "improvement" that would silence it permanently.
    const warning = screen.getByRole('alert')
    expect(warning.textContent).toContain('does not delete what is filed underneath')
    // The clause that stops the warning reading as a reassurance.
    expect(warning.textContent).toContain('has not checked')
  })

  it('mentions the cross-module case, which is the orphan that actually happened', () => {
    respondWith(200, COMMITTED)
    render(<DeleteRecord {...PROPS} collection="test-folders" />)

    // Probe 8 orphaned five TESTS by deleting a test FOLDER. A warning scoped to "children of this
    // record" would not have covered it.
    expect(screen.getByRole('alert').textContent).toContain('other modules')
  })

  it('does not write anything until Delete is pressed', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    const onCancel = vi.fn()
    render(<DeleteRecord {...PROPS} onCancel={onCancel} />)

    await userEvent.click(button('Cancel'))

    expect(fetchMock).not.toHaveBeenCalled()
    expect(onCancel).toHaveBeenCalled()
  })

  it('sends a DELETE to the record’s own route', async () => {
    const fetchMock = respondWith(200, COMMITTED)
    const onDeleted = vi.fn()
    render(<DeleteRecord {...PROPS} onDeleted={onDeleted} />)

    await userEvent.click(button('Delete'))

    await screen.findByText('Saved')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/records/requirements/7001')
    expect(init.method).toBe('DELETE')
    expect(onDeleted).toHaveBeenCalled()
  })
})

describe('an unknown outcome', () => {
  it('offers no retry — the row may already be gone', async () => {
    respondWith(502, UNKNOWN)
    render(<DeleteRecord {...PROPS} />)

    await userEvent.click(button('Delete'))
    await screen.findByText('It is not known whether this saved')

    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
    // The confirmation is replaced by the outcome, so there is no second Delete button either.
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
  })

  it('says a delete has no undo and that re-sending misreports', async () => {
    respondWith(502, UNKNOWN)
    render(<DeleteRecord {...PROPS} />)

    await userEvent.click(button('Delete'))

    // The shared wording is written for a create — "you might get a duplicate". A delete's failure
    // is the opposite shape, and inferring it from a banner about duplicates is asking too much.
    await screen.findByText(/cannot be undone from Alt-ALM/)
    expect(screen.getByText(/reports a failure for a delete that worked/)).not.toBeNull()
  })

  it('tells the host to stop trusting the list, exactly as a committed delete does', async () => {
    respondWith(502, UNKNOWN)
    const onDeleted = vi.fn()
    render(<DeleteRecord {...PROPS} onDeleted={onDeleted} />)

    await userEvent.click(button('Delete'))
    await userEvent.click(await screen.findByRole('button', { name: 'Reload the list' }))

    // The row may or may not exist. Either way the grid's copy of it is no longer evidence, and
    // that is the same requirement a successful delete makes.
    expect(onDeleted).toHaveBeenCalled()
  })
})

describe('a refusal', () => {
  it('is safe to retry, and says what ALM said', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'qccore.general-error', detail: 'in use' })
    render(<DeleteRecord {...PROPS} />)

    await userEvent.click(button('Delete'))

    await screen.findByText('ALM refused the change')
    expect(screen.getByText('in use')).not.toBeNull()
    expect(button('Try again').disabled).toBe(false)
  })

  it('does not report the record as deleted', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'x', detail: 'no' })
    const onDeleted = vi.fn()
    render(<DeleteRecord {...PROPS} onDeleted={onDeleted} />)

    await userEvent.click(button('Delete'))
    await screen.findByText('ALM refused the change')

    // Clearing the selection here would make a failed delete look like a successful one.
    expect(onDeleted).not.toHaveBeenCalled()
  })
})
