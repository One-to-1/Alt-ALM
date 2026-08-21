import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RelatedTab } from '../api/client.ts'
import { RelatedRows } from './RelatedRows.tsx'

/**
 * The attachments tab, which is the one related table that does something other than navigate.
 *
 * ⚠️ **Every attachment downloads.** Alt-ALM is one deployable on one origin (ADR 0001), so an
 * attachment rendered inline runs with the app's own session — an uploaded `.html` or `.svg` served
 * that way is stored XSS, not a preview. The user chose one rule over an allowlist split on
 * 2026-08-20, and it is also the version with no allowlist to get wrong.
 *
 * The assertions here are about the *link*: that it points at the download route, and that the page
 * offers no second affordance that would open the file instead. The response headers that make the
 * browser save rather than render are the BFF's half, pinned by `AttachmentControllerTest`.
 */

const TAB: RelatedTab = {
  key: 'attachment',
  label: 'Attachments',
  collection: 'attachments',
  attachment: true,
  relations: ['attachment'],
  tables: [
    {
      key: 'attachments',
      label: 'Attachments',
      targetEntity: 'attachment',
      // Empty on purpose: an attachment is a file, not a record this build can open as one. That
      // is exactly why its name is a download rather than a navigation.
      targetCollection: '',
      scopeField: 'parent-id',
      scopeFixed: {},
      navigable: false,
    },
  ],
}

/**
 * ⚠️ `fetchTabRows` returns a bare ARRAY of tables, not an object wrapping one. Getting that wrong
 * rendered nothing at all and reported only "unable to find role=link", which reads as a component
 * bug rather than as a fixture that never reached the component.
 */
const ROWS = [
  {
    tabKey: 'attachment',
    tableKey: 'attachments',
    label: 'Attachments',
    targets: {},
    grid: {
      collection: 'attachments',
      writable: false,
      columns: [
        { name: 'name', label: 'Name', type: 'STRING', listId: 0, multiValue: false },
        { name: 'file-size', label: 'Size', type: 'NUMBER', listId: 0, multiValue: false },
      ],
      rows: [
        { id: '42', values: { name: ['spec.pdf'], 'file-size': ['2048'] } },
        // ⚠️ The row that matters. If anything ever renders an attachment inline, this is the one
        // that executes.
        { id: '43', values: { name: ['payload.html'], 'file-size': ['120'] } },
      ],
      page: { rowsReturned: 2, reportedTotal: 2, mayHaveMore: false },
    },
  },
]

function respondWith(body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body }),
  )
}

function renderTab() {
  render(
    <RelatedRows
      project="PROJ"
      collection="requirements"
      entityId="7001"
      tab={TAB}
      onNavigate={() => {}}
    />,
  )
}

afterEach(() => {
  // Not automatic here: this suite has no setup file, so a render survives into the next test and
  // turns a single-element query into "found multiple elements".
  cleanup()
  vi.unstubAllGlobals()
})

describe('the attachments tab', () => {
  it("makes each attachment's name a link to the download route", async () => {
    respondWith(ROWS)
    renderTab()

    const link = (await screen.findByRole('link', { name: /spec\.pdf/ })) as HTMLAnchorElement
    // Scoped to the OWNING record: an attachment is a sub-resource, not a collection with ids of
    // its own to look up.
    expect(link.getAttribute('href')).toContain('/api/attachments/requirements/7001/42/file')
    expect(link.getAttribute('href')).toContain('project=PROJ')
  })

  it('⚠️ routes an uploaded .html to the same download link as everything else', async () => {
    respondWith(ROWS)
    renderTab()

    const link = (await screen.findByRole('link', { name: /payload\.html/ })) as HTMLAnchorElement
    // The whole point of the single rule. A branch on file type here is what would eventually
    // serve this one inline.
    expect(link.getAttribute('href')).toContain('/43/file')
    expect(link.getAttribute('href')).not.toContain('/image')
  })

  it('offers no second affordance that could open a file instead of saving it', async () => {
    respondWith(ROWS)
    renderTab()

    await screen.findByRole('link', { name: /spec\.pdf/ })

    // ⚠️ `target="_blank"` would flash an empty tab for a download, and `download` would let the
    // page name a file it never read — the server names it, from the name ALM holds.
    for (const link of screen.getAllByRole('link')) {
      expect(link.getAttribute('target')).toBeNull()
      expect(link.hasAttribute('download')).toBe(false)
    }
  })

  it('names a link for an attachment whose name ALM did not report', async () => {
    respondWith([
      {
        ...ROWS[0],
        grid: {
          ...ROWS[0].grid,
          rows: [{ id: '44', values: { name: [], 'file-size': ['10'] } }],
        },
      },
    ])
    renderTab()

    // An unnamed link is unreachable by keyboard description and unclickable with confidence.
    await waitFor(() => expect(screen.getByRole('link', { name: /attachment 44/ })).toBeTruthy())
  })
})
