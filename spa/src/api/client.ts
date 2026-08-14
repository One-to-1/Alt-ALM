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

  return apiGet<GridResponse>(`/api/grid/${encodeURIComponent(query.collection)}?${params.toString()}`)
}
