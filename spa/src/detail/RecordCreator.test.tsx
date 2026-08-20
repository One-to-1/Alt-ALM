import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { GridColumn } from '../api/client.ts'
import { RecordCreator } from './RecordCreator.tsx'

/**
 * The New Record form.
 *
 * <h2>The three things worth pinning</h2>
 *
 * <ol>
 *   <li><strong>It refuses to create at the tree root.</strong> A requirement's root
 *       {@code parent-id} of {@code -1} is a sentinel, not a row (probe 27) — posting against it
 *       returns a 500, which this app reports as an <em>unknown outcome</em>. So the plausible
 *       "just default the parent to -1" turns a knowable refusal into "we cannot tell whether a
 *       record was created".
 *   <li><strong>Empty fields are not sent.</strong> Posting blanks writes over whatever defaults
 *       ALM applies on create, and nothing afterwards distinguishes a deliberate blank from one the
 *       form supplied.
 *   <li><strong>Nothing is enforced as required.</strong> Metadata's {@code required} does not
 *       describe what a create needs (probe 9). A form that blocked on it would refuse writes ALM
 *       accepts.
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
  column('name'),
  column('priority'),
  column('status', { type: 'LOOKUP_LIST', listId: 194, choiceSource: 'LIST' }),
  column('description', { type: 'MEMO' }),
  column('parent-id'),
  column('father-name', { writable: false }),
  column('hidden-thing', { onDetailsForm: false }),
]

const PROPS = {
  project: 'PROJ',
  collection: 'requirements',
  columns: COLUMNS,
  parentId: '42',
  parentLabel: 'Specs',
  needsParent: true,
  onCreated: () => {},
  onCancel: () => {},
}

const CREATED = { outcome: 'COMMITTED', id: '9001', retried: false }

function respondWith(status: number, body: unknown) {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    Promise.resolve(
      String(url).includes('/api/choices')
        ? { ok: true, status: 200, json: async () => ({}) }
        : { ok: status >= 200 && status < 300, status, json: async () => body },
    ),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function button(name: string | RegExp): HTMLButtonElement {
  return screen.getByRole('button', { name }) as HTMLButtonElement
}

/** The create POST specifically — the form also reads /api/choices on mount. */
function createBody(fetchMock: ReturnType<typeof respondWith>) {
  const call = fetchMock.mock.calls.find((c) => (c[1] as RequestInit | undefined)?.method === 'POST')
  if (!call) throw new Error('no create request was sent')
  return JSON.parse(String((call[1] as RequestInit).body)) as {
    fields: Record<string, string | string[]>
  }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the form', () => {
  it('offers the writable non-memo fields, and says where the record is going', () => {
    respondWith(200, CREATED)
    render(<RecordCreator {...PROPS} />)

    expect(screen.getByLabelText('name')).not.toBeNull()
    expect(screen.getByLabelText('status')).not.toBeNull()
    // A memo is an HTML document; a text input would flatten it.
    expect(screen.queryByLabelText('description')).toBeNull()
    // Virtual fields cannot be written at all.
    expect(screen.queryByLabelText('father-name')).toBeNull()
    // parent-id comes from the scope, not from a text box someone can type an id into.
    expect(screen.queryByLabelText('parent-id')).toBeNull()
    // Not on ALM's own details form — a requirement carries 76 fields and a form of 40 is not one.
    expect(screen.queryByLabelText('hidden-thing')).toBeNull()

    expect(screen.getByText('Specs')).not.toBeNull()
  })

  it('lets an empty form be submitted, because required-ness is ALM’s to decide', async () => {
    const fetchMock = respondWith(200, CREATED)
    render(<RecordCreator {...PROPS} />)

    // ⚠️ Probe 9: a field that is `required:false` AND `editable:false` still makes the create 500
    // when omitted. Any client-side required rule is therefore guaranteed to be wrong in one
    // direction or the other, and refusing writes ALM would accept is the worse one.
    expect(button(/Create/).disabled).toBe(false)
    await userEvent.click(button(/Create/))

    await waitFor(() => expect(createBody(fetchMock)).toBeTruthy())
  })

  it('sends only the fields that were filled in, plus the parent', async () => {
    const fetchMock = respondWith(200, CREATED)
    render(<RecordCreator {...PROPS} />)

    await userEvent.type(screen.getByLabelText('name'), 'A new requirement')
    await userEvent.click(button(/Create/))

    await waitFor(() => expect(createBody(fetchMock)).toBeTruthy())
    const { fields } = createBody(fetchMock)
    expect(fields).toEqual({ name: 'A new requirement', 'parent-id': '42' })
    // `priority` and `status` were left alone: sending them empty would write blanks over whatever
    // ALM applies by default, and nothing afterwards tells the two apart.
    expect(fields).not.toHaveProperty('priority')
    expect(fields).not.toHaveProperty('status')
  })

  it('hands the new id back so the host can select it', async () => {
    respondWith(200, CREATED)
    const onCreated = vi.fn()
    render(<RecordCreator {...PROPS} onCreated={onCreated} />)

    await userEvent.type(screen.getByLabelText('name'), 'x')
    await userEvent.click(button(/Create/))

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('9001'))
  })
})

describe('creating at the tree root', () => {
  it('refuses, rather than posting against the root sentinel', async () => {
    const fetchMock = respondWith(200, CREATED)
    render(<RecordCreator {...PROPS} parentId={undefined} parentLabel={undefined} />)

    // ⚠️ The regression that matters. Defaulting parent-id to -1 returns
    // `500 Entity with key '-1' does not exist in table 'REQ'`, and a 5xx on a write is reported as
    // an UNKNOWN outcome — so a knowable refusal would become "we cannot tell whether a record was
    // created", for a record that certainly was not.
    expect(screen.getByText('Nowhere to file this yet')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull()
    expect(screen.queryByLabelText('name')).toBeNull()

    // And nothing was sent — not even the choices read, since there is no form to populate.
    const posts = fetchMock.mock.calls.filter(
      (c) => (c[1] as RequestInit | undefined)?.method === 'POST',
    )
    expect(posts).toHaveLength(0)
  })

  it('still creates without a parent for a collection that has no hierarchy', async () => {
    const fetchMock = respondWith(200, CREATED)
    render(
      <RecordCreator
        {...PROPS}
        collection="defects"
        needsParent={false}
        parentId={undefined}
        parentLabel={undefined}
      />,
    )

    await userEvent.type(screen.getByLabelText('name'), 'A defect')
    await userEvent.click(button(/Create/))

    await waitFor(() => expect(createBody(fetchMock)).toBeTruthy())
    // No parent-id invented for an entity that does not file under anything.
    expect(createBody(fetchMock).fields).toEqual({ name: 'A defect' })
  })
})

describe('outcomes', () => {
  it('offers no retry and no Create button for an unknown outcome', async () => {
    respondWith(502, { outcome: 'UNKNOWN', id: null, verified: false, detail: 'ALM 500' })
    render(<RecordCreator {...PROPS} />)

    await userEvent.type(screen.getByLabelText('name'), 'maybe created')
    await userEvent.click(button(/Create/))

    await screen.findByText('It is not known whether this saved')
    // Re-sending a create that may have landed is precisely how a duplicate is made.
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull()
  })

  it('reports no id for an unknown outcome, rather than one the host would select', async () => {
    respondWith(502, { outcome: 'UNKNOWN', id: null, verified: false, detail: 'ALM 500' })
    const onCreated = vi.fn()
    render(<RecordCreator {...PROPS} onCreated={onCreated} />)

    await userEvent.type(screen.getByLabelText('name'), 'maybe created')
    await userEvent.click(button(/Create/))
    await userEvent.click(await screen.findByRole('button', { name: 'Reload the list' }))

    // Selecting a row that was never created answers 404 and reads as a bug rather than as the
    // uncertainty it actually is.
    expect(onCreated).toHaveBeenCalledWith(null)
  })

  it('keeps the typed values when ALM refuses the create', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'qccore.field-error', detail: 'bad value' })
    render(<RecordCreator {...PROPS} />)

    await userEvent.type(screen.getByLabelText('name'), 'kept')
    await userEvent.click(button(/Create/))

    await screen.findByText('ALM refused the change')
    // Nothing was written, so the work is still worth something.
    expect((screen.getByLabelText('name') as HTMLInputElement).value).toBe('kept')
    expect(button('Try again').disabled).toBe(false)
  })

  it('marks the input ALM blamed, using the message it actually sends', async () => {
    // Captured live: a create missing type-id returns 400 qccore.required-field-missing with this
    // sentence and an EMPTY problems array — ALM reports per request, never per field (probe 29).
    // Without the label match the user gets a correct banner attached to nothing.
    respondWith(400, {
      outcome: 'REJECTED',
      errorId: 'qccore.required-field-missing',
      detail: "The field 'Priority' is required.",
    })
    render(
      <RecordCreator
        {...PROPS}
        columns={[column('name'), column('priority', { label: 'Priority' })]}
      />,
    )

    await userEvent.click(button(/Create/))

    const input = (await screen.findByLabelText('Priority')) as HTMLInputElement
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect((screen.getByLabelText('name') as HTMLInputElement).getAttribute('aria-invalid'))
      .toBeNull()
  })

  it('leaves every input unmarked when the refusal names nothing it recognises', async () => {
    respondWith(400, { outcome: 'REJECTED', errorId: 'qccore.general-error', detail: 'Locked.' })
    render(<RecordCreator {...PROPS} />)

    await userEvent.click(button(/Create/))
    await screen.findByText('ALM refused the change')

    // Marking a field ALM did not complain about sends the user to edit the wrong thing.
    expect((screen.getByLabelText('name') as HTMLInputElement).getAttribute('aria-invalid'))
      .toBeNull()
  })

  it('says so when ALM demanded a field its own metadata never declared', async () => {
    respondWith(200, { outcome: 'COMMITTED', id: '9001', retried: true })
    render(<RecordCreator {...PROPS} />)

    await userEvent.type(screen.getByLabelText('name'), 'x')
    await userEvent.click(button(/Create/))

    // The BFF's single missing-required-field retry (probe 9). Silently succeeding would hide that
    // this project's metadata is not describing its own writes.
    await screen.findByText(/do not list as required/)
  })
})
