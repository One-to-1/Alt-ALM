// Typed client for the Alt-ALM BFF. Mirrors the BFF's grid + project contract
// (see docs/plan/architecture.md and the alt-alm-ui skill). No ALM schema is
// hardcoded here — columns and their types arrive from the metadata-driven
// /api/grid/{collection} response at runtime.

/** Exactly the 8 ALM field types. There is deliberately no boolean type. */
export type FieldType =
  | 'STRING'
  | 'MEMO'
  | 'NUMBER'
  | 'DATE'
  | 'DATE_TIME'
  | 'LOOKUP_LIST'
  | 'USERS_LIST'
  | 'REFERENCE'

export interface Project {
  domain: string
  project: string
  writable: boolean
}

export interface GridColumn {
  name: string
  label: string
  type: FieldType
  listId: number
  multiValue: boolean
  /**
   * ALM's own Details form would probably render this field (`active && visibleInWebUI`).
   *
   * ⚠️ An approximation, not a derivation — ALM does not expose its form layout over any documented
   * API. Probe 21 checked it against the stock client for a real record: right count, 16 of 17
   * names, wrong in both directions. Good enough to choose a default field set; not something to
   * treat as authoritative.
   */
  onDetailsForm: boolean
  /** ALM's built-in Risk Analysis group (`active && !visibleInWebUI`) — 25 fields in every project probed. */
  riskGroup: boolean
  /**
   * ALM's own Groupable flag.
   *
   * The Group-by control offers exactly these fields rather than guessing from the type, so a
   * project that made a field ungroupable stops seeing it offered instead of seeing it offered and
   * getting an error on click.
   */
  groupable: boolean
  /**
   * Whether an editor may offer this field at all.
   *
   * ⚠️ Derived server-side from `virtual` ALONE, and that narrowness is the point. `required` and
   * `editable` are deliberately not in this contract: probe 9 found a field reported as neither,
   * which ALM nonetheless demands on create. A form that trusted them would grey out a field the
   * server requires, so the flags are withheld rather than shipped with a warning nobody reads.
   */
  writable: boolean
  /**
   * Which mechanism supplies this field's values: `LIST`, `ENTITY`, `SUBTYPE` or `NONE`.
   *
   * ⚠️ **Branch on this, never on `type`.** "A field with choices" is three unrelated routes and the
   * field type cannot tell them apart: `type-id` and `target-rel` are both `REFERENCE`, but the
   * first resolves through the subtype endpoint and the second by querying the `release`
   * collection. Anything but `NONE` means the field has choices — fetch them with `fetchChoices`.
   */
  choiceSource: 'LIST' | 'ENTITY' | 'SUBTYPE' | 'NONE'
}

export interface GridRow {
  id: string
  /** Field name -> values. Always an array on the wire, even for single-value fields. */
  values: Record<string, string[]>
  childCount: number
  /** Non-null when this individual row failed to load/parse fully; UNVERIFIED shape, don't pattern-match the text. */
  error: string | null
}

export interface GridPage {
  rowsReturned: number
  /**
   * NOT a collection count — describes the page only (e.g. reads 0 when
   * pageSize=0 for a non-empty collection). Never render this as "N results".
   */
  reportedTotal: number
  mayHaveMore: boolean
}

export interface GridResponse {
  collection: string
  writable: boolean
  columns: GridColumn[]
  rows: GridRow[]
  page: GridPage
}

export interface GridQuery {
  collection: string
  /** "<domain>/<project>" */
  project: string
  pageSize: number
  /** 1-based. */
  start: number
  sort?: string
  desc?: boolean
  /** Field name -> literal value, ANDed. Rejected server-side if the field is unknown for this project. */
  filters?: Record<string, string>
}

/** One node of an ALM folder tree. */
export interface TreeNode {
  id: string
  name: string
  parentId: string | null
  hasChildren: boolean
}

/** A tree's root, or the reason it could not be resolved for this project. */
export interface TreeRoot {
  collection: string
  root: TreeNode | null
  error: string | null
}

export interface TreeChildren {
  collection: string
  /** The parents actually queried, after the server dropped blanks and duplicates. */
  parentIds: string[]
  nodes: TreeNode[]
  /** False when a page hit the 2,000-row cap, so `hasChildren` fell back to optimistic. */
  exact: boolean
}

/** A tree node carrying its full field values — hierarchy and columns in one payload. */
export interface TreeRow {
  id: string
  parentId: string
  hasChildren: boolean
  values: Record<string, string[]>
  error: string | null
}

/** A level of tree rows plus this project's columns. */
export interface TreeRows {
  collection: string
  writable: boolean
  columns: GridColumn[]
  parentIds: string[]
  nodes: TreeRow[]
  exact: boolean
}

/** One server-side group-by bucket. `size` here IS a real count, unlike GridPage.reportedTotal. */
export interface GroupBucket {
  value: string
  label: string
  size: number
  /** The ALM filter expression selecting exactly this group — drill in without rebuilding a filter. */
  expression: string
}

type ErrorKind = 'access-denied' | 'bad-request' | 'alm-unavailable' | 'network' | 'unknown'

/** Thrown by every client function. `retryable` distinguishes a transient 502 from a definitive 400/403. */
export class ApiError extends Error {
  readonly kind: ErrorKind
  readonly retryable: boolean
  readonly status: number | null
  readonly detail: string | undefined
  readonly almStatus: number | undefined

  constructor(opts: {
    kind: ErrorKind
    message: string
    retryable: boolean
    status: number | null
    detail?: string
    almStatus?: number
  }) {
    super(opts.message)
    this.name = 'ApiError'
    this.kind = opts.kind
    this.retryable = opts.retryable
    this.status = opts.status
    this.detail = opts.detail
    this.almStatus = opts.almStatus
  }
}

interface AccessDeniedBody {
  error: 'access-denied'
  detail: string
}

interface BadRequestBody {
  error: 'bad-request'
  detail: string
}

interface AlmUnavailableBody {
  error: 'alm-unavailable'
  almStatus: number
}

type KnownErrorBody = AccessDeniedBody | BadRequestBody | AlmUnavailableBody

function isKnownErrorBody(value: unknown): value is KnownErrorBody {
  if (typeof value !== 'object' || value === null || !('error' in value)) {
    return false
  }
  const kind = (value as { error: unknown }).error
  return kind === 'access-denied' || kind === 'bad-request' || kind === 'alm-unavailable'
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    // Non-JSON error body (e.g. a proxy/500 HTML page) — fall through to the generic case below.
  }

  if (isKnownErrorBody(body)) {
    switch (body.error) {
      case 'access-denied':
        return new ApiError({
          kind: 'access-denied',
          message: body.detail || 'Access to this project or collection was denied.',
          retryable: false,
          status: response.status,
          detail: body.detail,
        })
      case 'bad-request':
        return new ApiError({
          kind: 'bad-request',
          message: body.detail || 'The request was invalid.',
          retryable: false,
          status: response.status,
          detail: body.detail,
        })
      case 'alm-unavailable':
        return new ApiError({
          kind: 'alm-unavailable',
          message: `ALM did not respond correctly (upstream status ${body.almStatus}). This may be transient.`,
          retryable: true,
          status: response.status,
          almStatus: body.almStatus,
        })
    }
  }

  return new ApiError({
    kind: 'unknown',
    message: `Request failed with status ${response.status}.`,
    retryable: response.status >= 500,
    status: response.status,
  })
}

async function apiGet<T>(path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, { headers: { Accept: 'application/json' } })
  } catch {
    throw new ApiError({
      kind: 'network',
      message: 'Could not reach the server. Check your connection and try again.',
      retryable: true,
      status: null,
    })
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  return (await response.json()) as T
}

export function fetchProjects(): Promise<Project[]> {
  return apiGet<Project[]>('/api/projects')
}

export function fetchGrid(query: GridQuery): Promise<GridResponse> {
  const params = new URLSearchParams({
    project: query.project,
    pageSize: String(query.pageSize),
    start: String(query.start),
  })
  if (query.sort) {
    params.set('sort', query.sort)
  }
  if (query.desc !== undefined) {
    params.set('desc', String(query.desc))
  }
  // Repeated `filter=field:value`. Only the first colon splits server-side, so values may
  // contain colons; field names cannot.
  for (const [field, value] of Object.entries(query.filters ?? {})) {
    if (value.trim() !== '') {
      params.append('filter', `${field}:${value}`)
    }
  }

  return apiGet<GridResponse>(`/api/grid/${encodeURIComponent(query.collection)}?${params.toString()}`)
}

/** Every tree's root for this project. Unused modules report an error rather than failing the call. */
export function fetchTreeRoots(project: string): Promise<TreeRoot[]> {
  return apiGet<TreeRoot[]>(`/api/tree/roots?project=${encodeURIComponent(project)}`)
}

/**
 * Children of one or more folders.
 *
 * Pass a whole level's ids to get it in one request. The server needs the batch anyway to answer
 * `hasChildren` exactly — ALM's own `children-count` is always 0 (probe 19) — so asking node by
 * node costs more requests *and* yields a worse answer.
 */
export function fetchTreeChildren(
  project: string,
  collection: string,
  parentIds: string | string[],
): Promise<TreeChildren> {
  const params = new URLSearchParams({ project })
  for (const id of Array.isArray(parentIds) ? parentIds : [parentIds]) {
    params.append('parentId', id)
  }
  return apiGet<TreeChildren>(
    `/api/tree/${encodeURIComponent(collection)}/children?${params.toString()}`,
  )
}

/**
 * One or more tree levels WITH field values and columns — the tree-grid's read.
 *
 * Same batching as fetchTreeChildren; this variant drops the id/name/parent-id projection so the
 * table beside the tree column has something to render.
 */
export function fetchTreeRows(
  project: string,
  collection: string,
  parentIds: string | string[],
): Promise<TreeRows> {
  const params = new URLSearchParams({ project })
  for (const id of Array.isArray(parentIds) ? parentIds : [parentIds]) {
    params.append('parentId', id)
  }
  return apiGet<TreeRows>(
    `/api/tree/${encodeURIComponent(collection)}/rows?${params.toString()}`,
  )
}

/** One entity by id. Throws ApiError with status 404 when it does not exist in this project. */
export function fetchDetail(
  project: string,
  collection: string,
  id: string,
): Promise<GridResponse> {
  const params = new URLSearchParams({ project })
  return apiGet<GridResponse>(
    `/api/detail/${encodeURIComponent(collection)}/${encodeURIComponent(id)}?${params.toString()}`,
  )
}

/**
 * One related-entity tab — Attachments, Linked Defects, Requirement Traceability…
 *
 * The set is derived from ALM's own `customization/entities/{e}/relations/` per project, never
 * hardcoded: requirement has 22 relations, test 27, defect 17, and the labels are per-project
 * customization.
 */
/**
 * One grid within a tab.
 *
 * ALM's Requirement Traceability tab holds two — "Trace From (Requirements that affect X)" and
 * "Trace To (Requirements affected by X)" — under a single heading, which is why a tab is a list of
 * tables rather than a single grid.
 */
export interface RelatedTable {
  key: string
  label: string
  /** The entity a row reaches — what following it opens. */
  targetEntity: string
  /** The module to open, or '' when this build has none for that entity. */
  targetCollection: string
  /**
   * The column on the related collection holding the open record's id.
   *
   * This is what turns "all test instances" into "this test set's instances", and it comes from the
   * relation's own storage descriptor rather than from anything this file knows — `cycle-id` is
   * right for a test instance in the probed project and is not a promise about the next one.
   */
  scopeField: string
  /** Further clauses independent of the open record, e.g. a polymorphic join's discriminator. */
  scopeFixed: Record<string, string>
  /**
   * Whether rows carry a far-end id at all.
   *
   * False for a plain-reference relation, which names only the column pointing back at the open
   * record. Those rows can be listed but not followed.
   */
  navigable: boolean
}

/**
 * Whether this table can be opened as a full grid.
 *
 * ⚠️ Computed here rather than read off the wire. The server has the same predicate
 * (`TabDto.Table.scopable()`), but Jackson serialises a record's *components*, and that is a method
 * — so it never arrives. Keep the two in step: without a scope column there is no filter that
 * selects these rows, and opening a grid anyway would draw the entire collection under a heading
 * naming one record.
 */
export function isScopable(table: RelatedTable): boolean {
  return table.scopeField !== '' && table.targetCollection !== ''
}

export interface RelatedTab {
  /** Stable id for requesting this tab's rows. Derived from entities, not the label. */
  key: string
  label: string
  /** The collection the rows come from, so the UI can say where they are from. */
  collection: string
  /** Attachments render differently — they are files, not records. */
  attachment: boolean
  tables: RelatedTable[]
  /** The ALM relation names merged into this tab; shown when a tab's contents look surprising. */
  relations: string[]
}

/** Where following a related row leads. */
export interface LinkTarget {
  entity: string
  collection: string
  id: string
  /**
   * The far record's own name — ALM's "Defect: Summary" / "Req: Name" column.
   *
   * ⚠️ Resolved by the server with a second read, because it is NOT in the link row. From a
   * requirement's Linked Defects tab, `second-endpoint-name` on the join row names *the requirement
   * you are already looking at*, so rendering the join's own name column would show the wrong
   * record's name and look entirely plausible. Empty when the lookup failed.
   */
  name: string
}

export interface RelatedTableRows {
  tabKey: string
  tableKey: string
  label: string
  grid: GridResponse
  /** Row id → its far end. Absent for rows whose relation names no far-end column. */
  targets: Record<string, LinkTarget>
}

/**
 * The ancestor chain of a node, root first, ending with the node.
 *
 * ALM has no "ancestors of" query, so the BFF walks parent-id upward. `truncated` means the walk
 * stopped early (a dangling parent or a pathological depth) and the tree can select but not fully
 * expand to the node.
 */
export interface TreePath {
  collection: string
  id: string
  ids: string[]
  truncated: boolean
}

/** The ancestors a tree must expand to reveal one record. 404 when the id is not in this project. */
export function fetchTreePath(
  project: string,
  collection: string,
  id: string,
): Promise<TreePath> {
  const params = new URLSearchParams({ project })
  return apiGet<TreePath>(
    `/api/tree/${encodeURIComponent(collection)}/path/${encodeURIComponent(id)}?${params.toString()}`,
  )
}

export interface TabStrip {
  collection: string
  tabs: RelatedTab[]
  /**
   * Candidate relations that did NOT become tabs, each with the rule that discarded it.
   *
   * Worth surfacing rather than dropping on the floor: ALM shows a "Business Models Linkage" tab
   * and we cannot, because `bpm-links` 404s (probe 23). Without this the absence is inexplicable.
   */
  dropped: Record<string, string>
}

/** Which related-entity tabs this collection has in this project. Metadata only — reads no records. */
export function fetchTabs(project: string, collection: string): Promise<TabStrip> {
  const params = new URLSearchParams({ project })
  return apiGet<TabStrip>(
    `/api/tabs/${encodeURIComponent(collection)}?${params.toString()}`,
  )
}

/**
 * The tables behind one tab, for one record.
 *
 * Each table's rows come back shaped as a grid, so the same column/row rendering works. Throws
 * ApiError 404 when the tab key is not one this entity has — a real answer, since the strip is
 * per-project.
 */
export function fetchTabRows(
  project: string,
  collection: string,
  id: string,
  tabKey: string,
): Promise<RelatedTableRows[]> {
  const params = new URLSearchParams({ project })
  return apiGet<RelatedTableRows[]>(
    `/api/tabs/${encodeURIComponent(collection)}/${encodeURIComponent(id)}/` +
      `${encodeURIComponent(tabKey)}?${params.toString()}`,
  )
}

/**
 * Which of a record's related tabs hold rows — what colours the tab rail.
 *
 * ⚠️ A tab **missing from the map is unknown, not empty**: the server leaves out any tab whose probe
 * failed, because "empty" and "we could not tell" look identical to a reader and only one is true.
 * Render a missing key as unmarked, never as a hollow dot.
 */
export function fetchTabsPopulated(
  project: string,
  collection: string,
  id: string,
): Promise<Record<string, boolean>> {
  const params = new URLSearchParams({ project })
  return apiGet<Record<string, boolean>>(
    `/api/tabs/${encodeURIComponent(collection)}/${encodeURIComponent(id)}?${params.toString()}`,
  )
}

/** One recorded change to a record: which field, and what it went from and to. */
export interface HistoryChange {
  field: string
  label: string
  oldValue: string
  newValue: string
}

/** One change event. `changes` may be empty — ALM records some events without recording what altered. */
export interface HistoryEntry {
  id: string
  action: string
  /** `yyyy-MM-dd HH:mm:ss` as ALM sent it. No timezone offset, so it is NOT parsed into a Date. */
  time: string
  user: string
  changes: HistoryChange[]
}

export interface History {
  collection: string
  id: string
  /** Newest first. */
  entries: HistoryEntry[]
  /**
   * Always true so far, and the reason an empty list must never render as "nothing happened".
   *
   * Probe 24 read 678 audit entries across 119 records of a live project: every one an `UPDATE`,
   * spanning 12 fields, none of them a memo. Creates and rich-text edits leave no trace at all.
   */
  partial: boolean
}

/** A record's change history — the History tab's Audit Log. */
export function fetchHistory(
  project: string,
  collection: string,
  id: string,
): Promise<History> {
  const params = new URLSearchParams({ project })
  return apiGet<History>(
    `/api/history/${encodeURIComponent(collection)}/${encodeURIComponent(id)}?${params.toString()}`,
  )
}

/**
 * What stands between the user and a module.
 *
 * Three kinds of "no", kept apart because they are different promises: `BUILDABLE` will arrive,
 * `NEEDS_SIDECAR` needs a Windows deployment, `NO_API` is a permanent property of the product.
 */
export type ModuleReach = 'READABLE' | 'BUILDABLE' | 'NEEDS_SIDECAR' | 'NO_API'

export interface ModuleItem {
  key: string
  label: string
  /** The collection to open, or '' when there is nothing to open. */
  collection: string
  reach: ModuleReach
  /** One sentence naming the evidence. Empty when READABLE. */
  reason: string
}

export interface ModuleGroup {
  /** ALM's own grouping. Empty for an ungrouped entry. */
  name: string
  items: ModuleItem[]
}

export interface ModuleRail {
  groups: ModuleGroup[]
}

/**
 * ALM's navigation rail with a reachability verdict per entry.
 *
 * Not project-scoped: the rail is ALM's product structure, and the verdicts describe this build's
 * capabilities rather than any project's data.
 */
export function fetchModules(): Promise<ModuleRail> {
  return apiGet<ModuleRail>('/api/modules')
}

/** One permitted value. `value` is what a write sends — for a reference that is an **id**. */
export interface Choice {
  value: string
  label: string
}

/**
 * Every field's permitted values for a collection, in one request.
 *
 * Keyed by field name. ⚠️ A field with no choices is **absent from the map**, covering both "there
 * are none" and "they could not be read" — both mean *do not constrain this field*. Rendering an
 * empty dropdown for either makes the field impossible to fill.
 *
 * One call rather than one per field: a requirement has 27 lookup fields plus 3 references, so
 * per-field fetching would cost 30 requests to open one editor.
 */
export function fetchChoices(
  project: string,
  collection: string,
): Promise<Record<string, Choice[]>> {
  return apiGet<Record<string, Choice[]>>(
    `/api/choices/${encodeURIComponent(collection)}?project=${encodeURIComponent(project)}`,
  )
}

/** One lookup list: a display name and the values ALM permits. */
export interface LookupList {
  name: string
  values: string[]
}

/**
 * Every lookup list in a project, keyed by list id as a string.
 *
 * ⚠️ **List ids are instance-specific** (ADR 0005) — a list id means nothing outside the project it
 * came from, so this must be re-fetched on a project switch and never cached across one. Resolve a
 * column's `listId` against it rather than assuming any id is stable.
 *
 * A list with an EMPTY `values` is a real answer, not a missing one: three of the sandbox's 39 have
 * no items, and a field bound to one permits nothing. Treat empty as "no choices", never as
 * "unbound, so free text".
 */
/**
 * ⚠️ Not used by the record editor — that uses {@link fetchChoices}, which covers all three
 * mechanisms. This stays for bulk work (rendering lookup labels across a whole grid) where one
 * request beats one per field. Do not resolve an editor's dropdown from here: two code paths for
 * the same values is how they drift apart.
 */
export function fetchLists(project: string): Promise<Record<string, LookupList>> {
  return apiGet<Record<string, LookupList>>(`/api/lists?project=${encodeURIComponent(project)}`)
}

/** Server-side group-by counts for one field. */
export function fetchGroups(
  project: string,
  collection: string,
  field: string,
): Promise<GroupBucket[]> {
  const params = new URLSearchParams({ project })
  return apiGet<GroupBucket[]>(
    `/api/groups/${encodeURIComponent(collection)}/${encodeURIComponent(field)}?${params.toString()}`,
  )
}

// ============================================================================================
// Writes.
//
// ⚠️ The shape of this half is dictated by one fact: an ALM 5xx MAY HAVE COMMITTED THE ROW. So a
// write has three outcomes, not two, and the third one is not an error — it is an answer the user
// has to be shown. Modelling it as a thrown ApiError with `retryable: true` (the shape reads use)
// would be actively harmful here: it invites the automatic retry that turns one uncertain write
// into two records.
//
// Hence a discriminated union rather than a promise that resolves or throws. There is deliberately
// no `ok` boolean and no `success` field, for the same reason the BFF's AlmWriteResult has no
// isSuccess(): a convenience boolean is exactly how 'unknown' gets quietly bucketed with one of
// the other two.

/** One thing wrong with a write body, refused before it reached ALM. */
export interface WriteProblem {
  /** The field it concerns; empty for a whole-body problem. */
  field: string
  /** Stable machine-readable code — branch on this, never on `detail`. */
  code: string
  detail: string
}

export type WriteResult =
  /** The server confirmed it. */
  | { kind: 'committed'; id: string | null; retried: boolean }
  /**
   * ⚠️ ALM returned a server error and the row's fate is genuinely unknown.
   *
   * `verified: true` means a follow-up query found the record, so the caller can proceed — but this
   * is still not `committed`, because "the row exists" and "the write succeeded" are different
   * claims and only the first has evidence. `verified: false` means nobody knows: re-read before
   * retrying, because retrying blind is how duplicates get made.
   */
  | { kind: 'unknown'; id: string | null; verified: boolean; detail: string }
  /** ALM refused it outright. Nothing was written. */
  | { kind: 'rejected'; errorId: string; detail: string }
  /** Refused by the BFF's validation before ALM was contacted. Nothing was written. */
  | { kind: 'invalid'; problems: WriteProblem[] }
  /**
   * The record changed since it was read.
   *
   * ⚠️ A normal outcome of editing, not an exception — hence a union member the caller must handle
   * rather than a throw it can forget. Note this is *detection*: ALM has no optimistic locking, so
   * a write landing in the instant between the check and the request is still lost.
   */
  | { kind: 'conflict'; detail: string }

/** The wire shape of the BFF's write response. Not exported: callers get a `WriteResult`. */
interface WriteResponseBody {
  outcome: string
  id: string | null
  verified: boolean
  retried: boolean
  errorId: string
  detail: string
  problems: WriteProblem[]
}

function isWriteResponseBody(value: unknown): value is WriteResponseBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { outcome?: unknown }).outcome === 'string'
  )
}

function toWriteResult(body: WriteResponseBody): WriteResult {
  switch (body.outcome) {
    case 'COMMITTED':
      return { kind: 'committed', id: body.id, retried: body.retried }
    case 'UNKNOWN':
      return { kind: 'unknown', id: body.id, verified: body.verified, detail: body.detail }
    case 'REJECTED':
      return { kind: 'rejected', errorId: body.errorId, detail: body.detail }
    case 'INVALID':
      return { kind: 'invalid', problems: body.problems ?? [] }
    default:
      // An outcome this build does not know. Treated as unknown rather than as success: the safe
      // direction for an unrecognised write outcome is "go and look", never "it worked".
      return {
        kind: 'unknown',
        id: body.id,
        verified: false,
        detail: `The server reported an outcome this app does not recognise (${body.outcome}). Re-read the record before retrying.`,
      }
  }
}

async function apiWrite(
  path: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body?: unknown,
): Promise<WriteResult> {
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers:
        body === undefined
          ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // ⚠️ A network failure on a WRITE is not the same as one on a read. The request may have
    // reached the server and committed before the connection dropped, so this is explicitly NOT
    // retryable — the one case where a fetch that never returned still means "go and look".
    throw new ApiError({
      kind: 'network',
      message:
        'The connection failed while saving. The change may or may not have been applied — re-read the record before trying again.',
      retryable: false,
      status: null,
    })
  }

  let parsed: unknown = null
  try {
    parsed = await response.json()
  } catch {
    // Non-JSON body; handled below.
  }

  // A version conflict has its own status and its own body shape.
  if (response.status === 409) {
    const detail =
      typeof parsed === 'object' && parsed !== null && 'detail' in parsed
        ? String((parsed as { detail: unknown }).detail)
        : 'The record changed since you opened it.'
    return { kind: 'conflict', detail }
  }

  // ⚠️ This branch must come BEFORE the generic !response.ok handling, and that ordering is the
  // whole point. The BFF serves an unresolved UNKNOWN as 502; a 502 falling through to the error
  // path would become `retryable: true` — an invitation to re-send a write that may already have
  // landed. Whenever the body carries a write outcome, the BODY is the authority, not the status.
  if (isWriteResponseBody(parsed)) {
    return toWriteResult(parsed)
  }

  if (!response.ok) {
    throw toApiErrorFrom(response, parsed)
  }

  throw new ApiError({
    kind: 'unknown',
    message: 'The server returned a response this app could not interpret.',
    retryable: false,
    status: response.status,
  })
}

/** `toApiError` for a body already consumed — a Response body can only be read once. */
function toApiErrorFrom(response: Response, body: unknown): ApiError {
  if (isKnownErrorBody(body)) {
    switch (body.error) {
      case 'access-denied':
        return new ApiError({
          kind: 'access-denied',
          message: body.detail || 'Access to this project was denied.',
          retryable: false,
          status: response.status,
          detail: body.detail,
        })
      case 'bad-request':
        return new ApiError({
          kind: 'bad-request',
          message: body.detail || 'The request was invalid.',
          retryable: false,
          status: response.status,
          detail: body.detail,
        })
      case 'alm-unavailable':
        return new ApiError({
          kind: 'alm-unavailable',
          message: `ALM did not respond correctly (upstream status ${body.almStatus}).`,
          // Not retryable, unlike the read path's handling of the same body: this is a write.
          retryable: false,
          status: response.status,
          almStatus: body.almStatus,
        })
    }
  }
  return new ApiError({
    kind: 'unknown',
    message: `The save failed with status ${response.status}. The change may not have been applied — re-read the record.`,
    retryable: false,
    status: response.status,
  })
}

function projectQuery(project: string): string {
  return `?project=${encodeURIComponent(project)}`
}

/**
 * Where to point a link so an attachment downloads.
 *
 * ⚠️ **A plain URL, not a fetch.** The BFF answers with `Content-Disposition: attachment`, so an
 * ordinary `<a href>` is the whole implementation — the browser saves the file, shows its own
 * progress, and streams it without Alt-ALM ever holding the bytes in memory. Fetching into a blob
 * and synthesising a click would buy nothing and would break on anything large.
 *
 * ⚠️ **Every attachment downloads; there is no preview route and no `download` attribute here.**
 * Alt-ALM is one deployable on one origin (ADR 0001), so an attachment rendered inline runs with
 * the app's own session — an uploaded `.html` or `.svg` would be stored XSS rather than a preview.
 * The single rule lives in the BFF's response headers, where a caller cannot opt out of it; this
 * function only decides where to point.
 */
export function attachmentFileUrl(
  project: string,
  collection: string,
  entityId: string,
  attachmentId: string,
): string {
  return (
    `/api/attachments/${encodeURIComponent(collection)}/${encodeURIComponent(entityId)}` +
    `/${encodeURIComponent(attachmentId)}/file${projectQuery(project)}`
  )
}

/** One attachment filed against a record. No media type — see the BFF's `AttachmentDto`. */
export interface Attachment {
  id: string
  name: string
  description: string
  size: number
}

/**
 * The attachments filed against one record. Metadata only; no bytes are fetched.
 *
 * Used for two different things, and it is worth knowing both: the Attachments tab lists them, and
 * a memo needs the name→id map to point an embedded `<img>` at {@link attachmentImageUrl} — ALM
 * writes the image's *filename* into the memo's `src`, never its id.
 */
export async function fetchAttachments(
  project: string,
  collection: string,
  id: string,
): Promise<Attachment[]> {
  const body = await apiGet<{ items: Attachment[] }>(
    `/api/attachments/${encodeURIComponent(collection)}/${encodeURIComponent(id)}` +
      projectQuery(project),
  )
  return body.items ?? []
}

/**
 * Where to point an `<img src>` for an image whose bytes live in ALM.
 *
 * A separate route from {@link attachmentFileUrl}, deliberately: "this may render in the browser"
 * is then a property of the URL the page asked for rather than of a header somebody has to get
 * right. The BFF serves it inline only when the media type is a raster image **and** the bytes
 * start the way that format does — ALM derives its type from the file extension, so the claim on
 * its own is not evidence. Anything else answers 415 and the image does not appear.
 */
export function attachmentImageUrl(
  project: string,
  collection: string,
  entityId: string,
  attachmentId: string,
): string {
  return (
    `/api/attachments/${encodeURIComponent(collection)}/${encodeURIComponent(entityId)}` +
    `/${encodeURIComponent(attachmentId)}/image${projectQuery(project)}`
  )
}

/**
 * A field's value on the wire: a string, or an array of strings for a multi-value field.
 *
 * ⚠️ The model has exactly two multi-value fields, `target-rel` and `target-rcyc`, both References.
 * The array spelling is what probe 33 verified ALM accepts — one entry per value. A plain string
 * stays a plain string for every other field, so the common case reads as it always did.
 */
export type FieldValue = string | string[]

/** Creates one record. `fields` is logical field name → value; order is irrelevant. */
export function createRecord(
  project: string,
  collection: string,
  fields: Record<string, FieldValue>,
): Promise<WriteResult> {
  return apiWrite(`/api/records/${encodeURIComponent(collection)}${projectQuery(project)}`, 'POST', {
    fields,
  })
}

/**
 * Updates one record.
 *
 * ⚠️ Fields omitted from `fields` are left alone, but a field that IS present is replaced outright.
 * For a comment field that means every earlier comment is destroyed — use {@link addComment}, which
 * exists precisely because this function would do that and report success.
 *
 * @param expectedValues what this edit was based on, for the fields it is changing. Omitting it
 *   is "I accept overwriting a concurrent edit", not "there is no concurrency".
 *   ⚠️ NOT a `ver-stamp`. A stamp also moves when someone files a child under the record, so
 *   guarding on one refused saves where no field the user touched had changed (probe 34). Baselines
 *   for fields absent from `fields` are ignored by the BFF rather than refused.
 */
export function updateRecord(
  project: string,
  collection: string,
  id: string,
  fields: Record<string, FieldValue>,
  expectedValues?: Record<string, FieldValue>,
): Promise<WriteResult> {
  return apiWrite(
    `/api/records/${encodeURIComponent(collection)}/${encodeURIComponent(id)}${projectQuery(project)}`,
    'PUT',
    { fields, expectedValues: expectedValues ?? null },
  )
}

/** Deletes one record. ⚠️ No cascade: deleting a folder does not delete what is inside it. */
export function deleteRecord(
  project: string,
  collection: string,
  id: string,
): Promise<WriteResult> {
  return apiWrite(
    `/api/records/${encodeURIComponent(collection)}/${encodeURIComponent(id)}${projectQuery(project)}`,
    'DELETE',
  )
}

/**
 * Adds a comment, preserving the ones already there. The merge happens server-side.
 *
 * @param expectedThread the comment field's value as this view rendered it. The concurrency
 *   baseline for a comment is the thread itself: it is the only thing a comment write can destroy,
 *   and it is what the reader was looking at.
 */
export function addComment(
  project: string,
  collection: string,
  id: string,
  comment: string,
  author?: string,
  expectedThread?: string,
): Promise<WriteResult> {
  return apiWrite(
    `/api/records/${encodeURIComponent(collection)}/${encodeURIComponent(id)}/comments${projectQuery(project)}`,
    'POST',
    { comment, author: author ?? null, expectedThread: expectedThread ?? null },
  )
}

/**
 * The comment field's name for a collection, or null when it has none.
 *
 * Discovered rather than assumed: a requirement's is `comments`, a defect's is `dev-comments`, and
 * neither tracks the physical column name. A null means do not offer a comment box at all.
 */
export async function fetchCommentField(
  project: string,
  collection: string,
): Promise<string | null> {
  try {
    const body = await apiGet<{ field: string }>(
      `/api/records/${encodeURIComponent(collection)}/comment-field${projectQuery(project)}`,
    )
    return body.field
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null
    }
    throw error
  }
}
