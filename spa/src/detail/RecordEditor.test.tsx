import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { GridColumn, GridRow } from '../api/client.ts'
import { RecordEditor } from './RecordEditor.tsx'

/**
 * The editor, driven the way a user drives it.
 *
 * The pure modules already pin what a write outcome *means* (`writeOutcome.test.ts`) and what the
 * client concludes from a response (`writeClient.test.ts`). What neither can show is whether the
 * component actually honours them — whether the Retry button that must not exist for an unknown
 * outcome really does not get rendered, and whether the form really sends only what changed.
 *
 * Those are the two cases here that are worth their weight. The rest is the ordinary contract.
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
    ...over,
  }
}

const COLUMNS: GridColumn[] = [
  column('name'),
  column('priority'),
  column('description', { type: 'MEMO' }),
  column('father-name', { writable: false }),
]

const ROW: GridRow = {
  id: '7001',
  values: {
    id: ['7001'],
    name: ['Original name'],
    priority: ['2'],
    description: ['<html><body>a memo</body></html>'],
    'father-name': ['Parent'],
    'ver-stamp': ['3'],
  },
  childCount: 0,
  error: null,
}

function respondWith(status: number, body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body }),
  )
}

function writeBody(over: Record<string, unknown> = {}) {
  return {
    outcome: 'COMMITTED',
    id: '7001',
    verified: false,
    retried: false,
    errorId: '',
    detail: '',
    problems: [],
    ...over,
  }
}

function renderEditor(over: Partial<Parameters<typeof RecordEditor>[0]> = {}) {
  const onReload = vi.fn()
  const onClose = vi.fn()
  render(
    <RecordEditor
      project="DOM/PROJ"
      collection="requirements"
      columns={COLUMNS}
      row={ROW}
      onReload={onReload}
      onClose={onClose}
      {...over}
    />,
  )
  return { onReload, onClose }
}

function sentBody(): Record<string, unknown> {
  const mock = globalThis.fetch as unknown as { mock: { calls: [string, RequestInit][] } }
  const [, init] = mock.mock.calls[mock.mock.calls.length - 1]
  return JSON.parse(String(init.body)) as Record<string, unknown>
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

// ==============================================================================================

describe('an unknown outcome offers no way to save again', () => {
  it('renders no Retry button, and no live Save, when the outcome is unresolved', async () => {
    respondWith(502, writeBody({ outcome: 'UNKNOWN', id: null, detail: 'server error' }))
    const user = userEvent.setup()
    renderEditor()

    await user.clear(screen.getByLabelText('name'))
    await user.type(screen.getByLabelText('name'), 'Edited')
    await user.click(screen.getByRole('button', { name: /save/i }))

    await screen.findByText(/not known whether this saved/i)

    // ⚠️ The assertion this file exists for. An ALM 5xx may have committed the row, so any control
    // that re-sends the write manufactures a duplicate for exactly the writes that worked.
    expect(screen.queryByRole('button', { name: /try again/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^save/i })).toBeNull()
    // The one thing it does offer.
    expect(screen.getByRole('button', { name: /reload the record/i })).toBeTruthy()
  })

  it('reloads and closes rather than leaving the draft under a Save button', async () => {
    respondWith(502, writeBody({ outcome: 'UNKNOWN', id: null, detail: 'server error' }))
    const user = userEvent.setup()
    const { onReload, onClose } = renderEditor()

    await user.clear(screen.getByLabelText('priority'))
    await user.type(screen.getByLabelText('priority'), '5')
    await user.click(screen.getByRole('button', { name: /save/i }))
    await user.click(await screen.findByRole('button', { name: /reload the record/i }))

    expect(onReload).toHaveBeenCalled()
    // Closing discards the draft on purpose: a form left open with the text intact invites the
    // same second Save that a Retry button would.
    expect(onClose).toHaveBeenCalled()
  })
})

describe('only what changed is sent', () => {
  it('sends the edited field and nothing else', async () => {
    respondWith(200, writeBody())
    const user = userEvent.setup()
    renderEditor()

    await user.clear(screen.getByLabelText('name'))
    await user.type(screen.getByLabelText('name'), 'Edited')
    await user.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    // `priority` was untouched. Including it would rewrite it with whatever the browser was
    // showing - harmless here, destructive for a memo.
    expect(sentBody().fields).toEqual({ name: 'Edited' })
  })

  it('sends the ver-stamp the edit was based on', async () => {
    respondWith(200, writeBody())
    const user = userEvent.setup()
    renderEditor()

    await user.clear(screen.getByLabelText('priority'))
    await user.type(screen.getByLabelText('priority'), '4')
    await user.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(sentBody().expectedVersion).toBe('3')
  })

  it('cannot be saved when nothing was typed', () => {
    renderEditor()

    expect(screen.getByRole('button', { name: /no changes/i }).hasAttribute('disabled')).toBe(true)
  })
})

describe('the fields it offers', () => {
  it('omits memo fields, which are HTML documents rather than form values', () => {
    renderEditor()

    expect(screen.queryByLabelText('description')).toBeNull()
    expect(screen.getByText(/memo fields are edited from their own tab/i)).toBeTruthy()
  })

  it('omits fields ALM computes server-side', () => {
    // `writable` is `!virtual` and nothing else - a field ALM merely calls non-editable is still
    // offered, because probe 9 showed that flag cannot be trusted.
    expect(screen.queryByLabelText.call(screen, 'father-name')).toBeNull()
  })

  it('warns when the record carries no version to detect a conflict with', () => {
    const noStamp: GridRow = { ...ROW, values: { ...ROW.values, 'ver-stamp': [] } }
    renderEditor({ row: noStamp })

    expect(screen.getByText(/cannot be detected/i)).toBeTruthy()
  })
})

describe('the outcomes where the user can carry on', () => {
  it('keeps the form and pins a validation problem to its field', async () => {
    respondWith(422, writeBody({
      outcome: 'INVALID',
      problems: [{ field: 'priority', code: 'not-a-number', detail: 'not a number' }],
    }))
    const user = userEvent.setup()
    renderEditor()

    await user.clear(screen.getByLabelText('priority'))
    await user.type(screen.getByLabelText('priority'), 'high')
    await user.click(screen.getByRole('button', { name: /save/i }))

    expect(await screen.findByText('not a number')).toBeTruthy()
    // Nothing was sent to ALM, so the user's text is still exactly what they should be fixing.
    expect((screen.getByLabelText('priority') as HTMLInputElement).value).toBe('high')
    expect(screen.getByLabelText('priority').getAttribute('aria-invalid')).toBe('true')
  })

  it('offers a retry when ALM refused it, because nothing was written', async () => {
    respondWith(400, writeBody({ outcome: 'REJECTED', errorId: 'qccore.x', detail: 'Refused' }))
    const user = userEvent.setup()
    renderEditor()

    await user.clear(screen.getByLabelText('name'))
    await user.type(screen.getByLabelText('name'), 'Edited')
    await user.click(screen.getByRole('button', { name: /save/i }))

    expect(await screen.findByRole('button', { name: /try again/i })).toBeTruthy()
  })

  it('keeps the draft on a conflict so it can be re-applied over the fresh record', async () => {
    respondWith(409, { error: 'version-conflict', detail: 'the record changed' })
    const user = userEvent.setup()
    const { onReload, onClose } = renderEditor()

    await user.clear(screen.getByLabelText('name'))
    await user.type(screen.getByLabelText('name'), 'Mine')
    await user.click(screen.getByRole('button', { name: /save/i }))
    await user.click(await screen.findByRole('button', { name: /reload and re-apply/i }))

    expect(onReload).toHaveBeenCalled()
    // Unlike the unknown case, the editor stays open: nothing was written, so the user's text is
    // still worth something.
    expect(onClose).not.toHaveBeenCalled()
    expect((screen.getByLabelText('name') as HTMLInputElement).value).toBe('Mine')
  })
})
