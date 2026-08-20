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
  column('status', { type: 'LOOKUP_LIST', listId: 194 }),
  column('unbound', { type: 'LOOKUP_LIST', listId: 0 }),
  column('type-id', { type: 'REFERENCE' }),
  column('target-rel', { type: 'REFERENCE', multiValue: true }),
  column('description', { type: 'MEMO' }),
  column('father-name', { writable: false }),
]

const ROW: GridRow = {
  id: '7001',
  values: {
    id: ['7001'],
    name: ['Original name'],
    priority: ['2'],
    status: ['Passed'],
    unbound: ['free text'],
    'type-id': ['1'],
    'target-rel': ['12'],
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
    vi.fn().mockImplementation((url: string) =>
      // The editor loads lookup lists on mount. Answering that separately keeps every existing
      // case unchanged while letting the lookup tests supply real choices.
      Promise.resolve(
        String(url).includes('/api/lists')
          ? { ok: true, status: 200, json: async () => LISTS }
          : { ok: status >= 200 && status < 300, status, json: async () => body },
      ),
    ),
  )
}

/** Empty by default, so the existing cases keep rendering text inputs. */
let LISTS: Record<string, { name: string; values: string[] }> = {}

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

/** The field's control once the lookup lists have loaded and swapped it to a dropdown. */
async function findSelect(label: string): Promise<HTMLSelectElement> {
  await waitFor(() => expect(screen.getByLabelText(label).tagName).toBe('SELECT'))
  return screen.getByLabelText(label) as HTMLSelectElement
}

afterEach(() => {
  LISTS = {}
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

  it('omits REFERENCE fields rather than offering a text box over a raw id', () => {
    renderEditor()

    // type-id is a single-value Reference whose stored value is a subtype id. Rendered as a text
    // input it invites someone to type a number that silently re-types the requirement. Target
    // Release is the multi-value kind, pointing at the `release` collection. Both need a resolver,
    // and until there is one, offering no control is more honest than offering a trap.
    expect(screen.queryByLabelText('type-id')).toBeNull()
    expect(screen.queryByLabelText('target-rel')).toBeNull()
    expect(screen.getByText(/point at another record/i)).toBeTruthy()
  })

  it('warns when the record carries no version to detect a conflict with', () => {
    const noStamp: GridRow = { ...ROW, values: { ...ROW.values, 'ver-stamp': [] } }
    renderEditor({ row: noStamp })

    expect(screen.getByText(/cannot be detected/i)).toBeTruthy()
  })
})

describe('lookup fields become dropdowns, and every "cannot tell" stays a text box', () => {
  it('offers the values this project defines for a bound field', async () => {
    LISTS = { '194': { name: 'Status', values: ['Not Covered', 'Passed', 'Failed'] } }
    respondWith(200, writeBody())
    renderEditor()

    // findByLabelText resolves against the INPUT the field renders before the lists arrive, so
    // waiting on the label alone would assert against the pre-load element. Wait for the swap.
    const select = await findSelect('status')
    expect(Array.from(select.options).map((o) => o.value)).toEqual([
      // An empty option, because clearing a field is a legitimate edit and a select without one
      // makes a value impossible to undo once set.
      '',
      'Not Covered',
      'Passed',
      'Failed',
    ])
  })

  it('keeps a stored value the list no longer offers, rather than silently re-pointing', async () => {
    // A list edited after this record was written. Without this, the select would fall back to its
    // first option and save THAT on the next Save - a value the user never chose.
    LISTS = { '194': { name: 'Status', values: ['Not Covered', 'Failed'] } }
    respondWith(200, writeBody())
    renderEditor()

    const select = await findSelect('status')
    expect(select.value).toBe('Passed')
    expect(Array.from(select.options).map((o) => o.textContent)).toContain('Passed (not in list)')
  })

  it('falls back to a text box when the lists cannot be read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) =>
        String(url).includes('/api/lists')
          ? Promise.reject(new TypeError('down'))
          : Promise.resolve({ ok: true, status: 200, json: async () => writeBody() }),
      ),
    )
    renderEditor()

    // "Cannot tell" must not become "no choices": an empty dropdown would make the field
    // impossible to fill. Same rule the BFF validator follows.
    await waitFor(() => expect((screen.getByLabelText('status') as HTMLElement).tagName).toBe('INPUT'))
  })

  it('falls back to a text box for an EMPTY list', async () => {
    // Three of the sandbox's 39 lists have no items.
    LISTS = { '194': { name: 'Status', values: [] } }
    respondWith(200, writeBody())
    renderEditor()

    await waitFor(() => expect((screen.getByLabelText('status') as HTMLElement).tagName).toBe('INPUT'))
  })

  it('leaves an UNBOUND list field as a text box', async () => {
    LISTS = { '194': { name: 'Status', values: ['Passed'] } }
    respondWith(200, writeBody())
    renderEditor()

    // listId 0 names no list, so there is nothing to constrain against.
    await waitFor(() => expect((screen.getByLabelText('unbound') as HTMLElement).tagName).toBe('INPUT'))
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
