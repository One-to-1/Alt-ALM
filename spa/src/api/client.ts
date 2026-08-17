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
