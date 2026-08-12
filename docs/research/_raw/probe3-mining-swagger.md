# Probe 3 — Mining the Swagger Docs and Resource List

Source fixtures (all under `tests/fixtures/`, offline, zero server calls):
- `api-doc-v2-openapi.json` — OpenAPI **3.0.1** (despite the filename saying "openapi", it is not
  Swagger 2.0), `info.version: "26.1"`, 8 paths / 14 operations. "ALM Project REST API Reference" —
  covers only the newest (24.1+) additions to the project-level REST API.
- `api-doc-sa-v2-openapi.json` — OpenAPI **3.0.1**, `info.version: "26.1"`, 136 paths / 178
  operations, 206 component schemas. The full Site Administration REST API.
- `resource-list-site.json` — a flat JSON array of 319 `{BasePath, Resources[]}` groups, 1,111
  individual operations. This is a **resource-discovery dump of the project-level `/qcbin/v2/rest`
  API surface** (paths are relative, prefix stripped), not of the Site Admin API — see §4.

All claims below are traceable to these three files. Interpretations are marked `INFERRED`.

---

## 1. v2 project API (`api-doc-v2-openapi.json`)

### 1.1 Operation inventory (14 ops / 8 paths)

| # | Method | Path | operationId | Notes |
|---|---|---|---|---|
| 1 | POST | `/qcbin/authentication-point/alm-authenticate` | `alm-authenticate` | Username/password auth, sets `LWSSO_COOKIE_KEY` |
| 2 | POST | `/qcbin/rest/oauth2/login` | `login` | API-key auth, sets `LWSSO_COOKIE_KEY`+`ALM_USER`+`QCSession`+`XSRF-TOKEN` |
| 3 | GET | `/qcbin/v2/rest/is-authenticated` | `getAuthenticationInfo` | "Recommended" over the legacy one |
| 4 | GET | `/qcbin/rest/is-authenticated` | `isAuthenticated` | Marked "Legacy" |
| 5 | PUT | `/qcbin/rest/site-session` | `putExentdingSession` | Extend session (keepalive) |
| 6 | POST | `/qcbin/rest/site-session` | `openSession` | Open site session from LWSSO cookie |
| 7 | POST | `/qcbin/authentication-point/logout` | `logout` | |
| 8 | GET | `/qcbin/v2/rest/domains/{domain}/projects/{project}/customization/used-lists` | `getLists` | List collection |
| 9 | GET | `/qcbin/v2/rest/domains/{domain}/projects/{project}/customization/used-lists/{list-id}` | `getList` | Single list + items |
| 10 | POST | `/qcbin/v2/rest/.../used-lists/{list-id}/items` | `addItem` | Add top-level item |
| 11 | PUT | `/qcbin/v2/rest/.../used-lists/{list-id}/items/{item-id}` | `renameItem` | Rename item |
| 12 | POST | `/qcbin/v2/rest/.../used-lists/{list-id}/items/{item-id}` | `addSubItem` | Add **sub-item** under an existing item (same URL as rename, different method) |
| 13 | DELETE | `/qcbin/v2/rest/.../used-lists/{list-id}/items/{item-id}` | `deleteItem` | Delete item |
| 14 | DELETE | `/qcbin/v2/rest/domains/{domain}/projects/{project}/{entity-name}/versioningHistory` | `purgeVCHistories` | Purge VC history; `entity-name` path param — description says **"currently only 'test' is supported"** |

Auth header convention (from `info.description`): after auth, pass
`Cookie: LWSSO_COOKIE_KEY=…; ALM_USER=…; QCSession=…; XSRF-TOKEN=…` and echo `XSRF-TOKEN` back as
the `X-XSRF-TOKEN` header on every non-GET request. A `customer-id` header exists for SaaS
multi-tenant calls, default = caller's own customer.

### 1.2 Request/response body shapes

**`Item` schema** (used for used-list item add/rename/sub-item; body IS the bare object, not
wrapped):
```json
{
  "id": "integer(int32) — item id",
  "logicalName": "string",
  "value": "string — the item value",
  "items": "Item[] — sub-items/values nested under this item"
}
```
Example request bodies seen in the spec: `{"value": "a new item"}` (POST items), `{"value":
"another item value"}` (PUT rename), `{"value": "a sub item"}` (POST sub-item, same URL as PUT
with different verb). Response for all three: full `Item` object, 201/200 in JSON or XML.

**`CustomizationList` / `CustomizationLists`** (GET responses):
```json
CustomizationList:  { name: string, id: int32, logicalName: string, items: Item[] }
CustomizationLists: { lists: CustomizationList[], totalCount: int32 }
```
`getLists` query params: `name`, `logical-name`, `id` (all comma-separable per the description
example `?name=Status,Run State`), `skip-children`, `limit`, `offset`. `getList` (single) takes
`skip-children` only.

**`PurgeVCHistoriesEntity`** — exact body for `DELETE .../{entity-name}/versioningHistory`:
```json
{
  "purgeMode": "string — 'date' or 'version'",
  "offSet": "string — YYYY-MM-DD if purgeMode=date (purges older records, keeps ≥1); integer-as-string count of versions to KEEP if purgeMode=version"
}
```
Two worked examples in the doc: `{"purgeMode":"date","offSet":"2023-12-05"}` and
`{"purgeMode":"version","offSet":"3"}`. Response is bare 201, no body. Only path params are
`entity-name` and `project` (curiously `domain` is not listed as an explicit parameter object even
though it's in the URL template — `INFERRED`: likely an OpenAPI-authoring omission, still a real
path segment).

**Auth body shapes:**
```json
AuthenticationDataWrapper: { "alm-authentication": AuthenticationData }
AuthenticationData:        { user: string, password: string }        // POST alm-authenticate
AuthenticationApiKey:      { clientId: string, secret: string }       // POST oauth2/login
AuthenticationInfo:        { Username: string }                       // returned by is-authenticated (v2 uses lowercase `username`, legacy schema uses `Username` — inconsistent casing between AuthenticationInfo (2 variants defined) and Captcha exists as a schema (ck, captchaAnswer) but no operation in this doc references it — INFERRED: reserved for a CAPTCHA-gated login flow not exposed here.
```
`/qcbin/rest/is-authenticated`, `/qcbin/rest/site-session` (PUT/POST) declare only `Cookie` header
params, no typed request/response schemas (`content: {}`) — bodies are effectively opaque/empty in
this doc.

---

## 2. Site Admin API (`api-doc-sa-v2-openapi.json`)

178 operations / 136 unique path templates / 206 component schemas. **Zero operations are marked
`deprecated: true`** anywhere in this file. **28 operations carry a `SAAS_ONLY` extension flag**
(custom `x`-less vendor field literally named `SAAS_ONLY`, plus a `PERMISSIONS` extension block
describing the required role/permission in prose) — these are unusable against an on-prem instance:

```
DELETE /customers/{customerId}/recipients/{recipientName}      GET /customers
GET /audits, /audits/export, /audits/metadata                  GET /customers/{customer-id}
GET /customers/{customerId}/contracts|idps|ldaps|licenses|      GET /customers/global-search-users
    license/assignments|recipients                             GET /customers/idps, /customers/ldaps
GET /domains/{domain}/licenses                                 GET /orphan-users(+/{userName}/projects)
GET /permissions/metadata                                      GET /roles/{roleId}
POST /customers/{customerId}/recipients
PUT  /customers/{customer-id}/contracts, /customerId}/idps|ldaps|recipients/{n}|users
PUT  /domains/{domain}/licenses
PUT  /orphan-users
```
**Important for Alt-ALM:** this means `GET /audits`, `/audits/export`, `/audits/metadata`, and
`GET /permissions/metadata` are all **SaaS-only** in this doc despite looking like general admin
endpoints — audit-log UI and full permission-catalog UI cannot rely on them for an on-prem target.
`GET /permissions` (current user's role) is NOT flagged SaaS-only, so that one is safe on-prem.

### 2.1 Grouped inventory (by Swagger tag, all 178 ops)

```
Admin Report Resource (3): GET /admin-reports/{action-id} [x2, dup entry with trailing space],
  POST /collectors/report-collector/execute
Audit Logs (3): GET /audits, /audits/export, /audits/metadata                    [SaaS-only, all 3]
Authentication (7): POST alm-authenticate, POST logout, GET is-authenticated (legacy+v2),
  POST oauth2/login, PUT+POST site-session
Client Management (2): GET/PUT /client-management/webrunner
Customers (18): full CRUD for customers/contracts/idps/ldaps/licenses/recipients/users [mostly SaaS-only]
DB Servers (9): GET/POST /db-servers, GET/PUT/DELETE /db-servers/{id}, ping, collect-tablespace-info,
  /projects, /search-languages
Denied Features (1): GET /denied-features
Domains (7): full CRUD /domains, /domains/{d}, GET/PUT /domains/{d}/licenses [licenses = SaaS-only]
Event Logs (1): GET /event-logs/last
Extensions (2): GET /extensions, GET /extensions/{extension}
LDAP Servers (7): full CRUD /ldap-servers, /{id}/ldap-users, /{id}/ping
Licenses (12 incl. 4 dup "usage/export" entries): /license, /assignments, /customers/status,
  /datastore(+/status), /quotas, /status, /usage(+/export)
Mail Services (5): GET/PUT /mails/settings, POST /mails, /mails/discover, /mails/send-test-mail
Maintenance Logs (1): GET /domains/{d}/projects/{p}/maintenance-logs/project-{task}
Orphan Users (3): GET /orphan-users(+/{userName}/projects), PUT /orphan-users [SaaS-only]
Permissions (2): GET /permissions [OK on-prem], GET /permissions/metadata [SaaS-only]
Project Admin Users (3): GET/POST/DELETE /domains/{d}/projects/{p}/admin-users(/{userName})
Project Extensions (3, incl. 1 dup w/ trailing space): GET/POST /domains/{d}/projects/{p}/extensions
Project Groups (4): GET /groups, GET/POST/DELETE /groups/{group}/users
Project Links (3): GET/POST/DELETE /project-links
Project Maintenance (9): GET read-status; POST abort/align/convert-to-unicode/pause/repair/resume/
  upgrade/verify
Project Run Queries (2): POST /run-query, /run-query/export
Project Schemas (1): GET /domains/{d}/projects/{p}/tables
Project Update Priorities (2): GET/PUT /project-upgrade-priorities
Project Users (7, incl. 1 dup): GET/POST/DELETE /domains/{d}/projects/{p}/users(/{userName}),
  GET/PUT /users/{userName}/groups
Projects (21): full lifecycle — CRUD, activate/deactivate, enable/disable-versioning,
  enable/disable-quality-insight, export/import/restore, move, ping, copy-options,
  calculate-qpm-now, postpone/promote-repo-gc, rebuild-text-index, search-languages
Quality Insight (3): GET/PUT /quality-insight, POST /quality-insight/test-connection
Roles (2): GET /roles, GET /roles/{roleId} [roleId = SaaS-only]
Services (1): PUT /services/custom-test-types-service
Site Parameters (5): full CRUD /site-params(/{param})
Site Sessions (5, incl. 1 dup): GET/DELETE /site-connections, GET /groups/{groupedBy},
  POST /send-message
Site Users (15, incl. 3 dup POST + 1 dup DELETE): full CRUD /site-users(/{userName}), activate,
  deactivate, unlock, /projects, download-template, export, PUT /policy
Site Versions (1): GET /site-version
User Profiles (2): GET/PUT /user-profile
```
(Counts above match the 178 total; several paths have literal duplicate entries in the source JSON
— e.g. `POST /site-users` appears 3×, `GET /license/usage/export` appears 4× — differing only by
trailing whitespace in the path string. `INFERRED`: artifact of doc generation/merging, not
distinct operations.)

### 2.2 Detailed schemas for endpoints we'll actually use

**Site Users** — `GET/POST/PUT /site-users`, `GET/PUT/DELETE /site-users/{userName}`:
```
User (xml name "user", required: [name], name maxLength 60):
  name*, password, full-name, email, phone, description,
  role: RoleEntity { role-name*, role-id, customer-id, permission: Permission[] },
  policy: Policy { policy-id, policy-name, description, is-default, validator: PasswordValidator[] },
  group: Group[] { group-id, group-name },
  user-auth-data: UserAuthData { user-id, server-id, user-dn },   // LDAP/SSO binding
  is-active, is-locked, is-change-password-allowed, is-validate-only (password-check-only flag),
  identity-key (SSO, ≤255 chars), idp-name (SSO), preferred-language,
  expire-date, request-id, send-notification, old-password (for self password change)
UserWrapper: { user: User }        Users: { user: User[], total-results: int32 }
UsersWrapper: { users: Users }     UserArrayWrapper: { users: { user: User[] } }  // note: DIFFERENT shape than UsersWrapper — no total-results, used for bulk create/remove
```
- `POST /site-users` body = `UserWrapper` (single `user` object), example only sets
  `name/email/phone/description/full-name/request-id/send-notification/is-active/idp-name/
  identity-key/role`. Response 201 = `UserWrapper`.
- `PUT /site-users/{userName}` body = partial `UserWrapper` (only changed fields, e.g. just
  `email/phone/description/full-name`).
- `PUT /site-users` (bulk) = `updateLDAPUsers`, body = `UserArrayWrapper` with
  `content-type: application/json-collection` (non-standard media type) — for syncing LDAP-imported
  users, requires `user-auth-data.server-id`+`user-dn` per user.
- Both `GET /site-users` and project-user list endpoints share `start-index` (default 1) /
  `page-size` (default = site param `REST_API_DEFAULT_PAGE_SIZE`) pagination convention.

**Project users** — `/domains/{domain}/projects/{project}/users...`:
- `POST .../users` body = `UserWrapper` containing only `{name}` — adds an *existing site user* to
  the project (does not create a new site user).
- `DELETE .../users` (bulk, no path suffix) body = `UserArrayWrapper` listing `{name}` entries.
- `DELETE .../users/{userName}` — single removal, no body.
- `GET .../users` supports `extra-fields=group` query param to embed group membership.
- `.../users/{userName}/groups` GET/PUT use `GroupArrayWrapper: { groups: { group: Group[] } }`
  where `Group = { group-id, group-name }`.

**Project groups** — `.../groups` (GET only, list), `.../groups/{group}/users` GET/POST/DELETE, all
using `UserArrayWrapper` bodies keyed by `name` only. **No POST/PUT/DELETE for creating/editing a
group itself** in this doc — groups are managed elsewhere (stock UI only, presumably) and only
membership is API-editable.

**Site Params** — `SiteParameter (xml "site-parameter", required:[name])`:
```
{ name*, value, description, is-system: bool (readOnly), is-metadata: bool (readOnly),
  is-visible: bool (readOnly), is-encrypted: bool (readOnly) }
```
`GET /site-params` → `SiteParameterArrayWrapper`. `GET /site-params/{param}` takes
`accept-default-value: boolean` — if true and the param isn't in the DB yet, falls back to its
metadata-defined default. `POST` creates (name/value/description); `PUT /{param}` updates
value/description only; `DELETE /{param}` removes it.

**Site Version** — `GET /site-version` → `SiteVersionWrapper` wrapping:
```
{ patch-level, external-version, major-version, minor-version, minor-minor-version,
  build-version, full-version }
```
All fields are plain strings — useful for a version-gate check at BFF startup.

**User Profile** — `GET /user-profile` (query `show-preferred-language: boolean`) returns
`UserWrapper` (i.e. the **same `User` schema** as site-users, self-service view). `PUT /user-profile`
body = `UserWrapper`, response = `UserProfileWrapper` wrapping a `User`-shaped-but-separately-named
`UserProfile` schema (identical fields to `User` plus `is-pin-initialized: boolean`). Update example
shows changing `full-name/email/description/phone/expire-date/policy/role/is-locked/
is-change-password-allowed` on one's own profile — i.e. self-service profile edit can touch role and
lock state fields (`INFERRED`: probably actually permission-gated server-side despite being
technically settable in the schema).

**Permissions** — `GET /permissions` (not SaaS-gated) = `getCurrentUserRole` → `RoleEntityWrapper`
wrapping `RoleEntity { role-id, role-name*, customer-id, permission: Permission[] }` where
`Permission = { permission-name, display-name }`. This is the "get my own role + permission list"
call — the one the doc's own `customer-id` header docs point to for discovering the correct
customer-id. `GET /permissions/metadata` (SaaS-only) returns the full catalog:
`PermissionCategoryEntityArrayWrapper` → `PermissionCategoryEntity { category-name, permission,
permission-sub-category: [{ category-name, permissions: Permission[] }] }`.

**Audits** — `GET /audits` (**SaaS-only**). No formally-typed query params beyond a `customer-id`
header in the spec's `parameters` array, but the prose `description` documents **`page-size`** and
**`start-index`** query parameters informally (e.g. `?page-size=20`, `?start-index=2`, combinable).
Response `AuditLogsWrapper` → `AuditLogs { total-results, audit-log: AuditLog[] }`, `AuditLog = {
id, date (int64, epoch ms `INFERRED`), login-name, context, operation, details, status, src-ip,
dst-ip }`. `GET /audits/metadata` → `MetadataWrapper` → `Metadata { name, field: Field[] }`, `Field
= { name, items: Item[] }` (drives filter-value dropdowns). `GET /audits/export` — no schema, file
download presumably.

**Site Connections** (session management) — `GET /site-connections` → `ConnectedSessionsWrapper`:
```
ConnectedSession: { login-session-id, project-session-id, domain, project, host, username,
  last-ping, last-action, login-time, client-type, license-usage: LicenseUsage[] }
```
`DELETE /site-connections?query={...}` — filter syntax e.g.
`query={login-session.login-session-id[93808 OR 93666]}` or `query={project[p]}` (custom mini query
DSL, OR-lists in brackets). `GET /site-connections/groups/{groupedBy}` — `groupedBy` must be
`"username"` or `"project"`, returns recursive `GroupByHeader{Name,Value,size,GroupByHeader[]}`
tree. `POST /site-connections/send-message?query={...}` body = `SessionMessageWrapper{ session-message: { message-body } }` — broadcasts a message to matched sessions (licence-seat/session
management use case for Alt-ALM's session-pooling ADR).

---

## 3. resource-list inventory (`resource-list-site.json`)

319 `BasePath` groups, 1,111 total operations, 447 unique `(BasePath, Path)` combinations (i.e.
most BasePath groups fan out into several sibling `Resources[]` entries — e.g. an entity collection
plus its `/{id}`, `/groups/{field}`, `/{id}/attachments`, `/{id}/mail`, `/{id}/lock` sub-resources
all share one BasePath).

### 3(a). Full deduped table, grouped by BasePath

Written to a separate appendix for size: **[`probe3-resource-list-basepath-table.md`](probe3-resource-list-basepath-table.md)**
(766 lines: 319 `### BasePath` headers, 447 `path — METHODS` bullet lines, `**[DEPRECATED]**`
flagged inline). Headline stats pulled from it:

- **17 operations** are `Deprecated: true` — all of the form `POST
  /domains/{domain}/projects/{project}/{collection}/{parent_entity_id: [0-9]+}/attachments` for 17
  different collections (bpm-folders, design-steps, environments, defects, milestones, releases,
  release-cycles, release-folders, requirements, runs, run-steps, test-configs, tests,
  test-folders, test-instances, test-sets, test-set-folders). `INFERRED`: this specific
  single-attachment-upload-by-POST-to-the-parent-collection form is deprecated in favor of the
  non-deprecated `POST .../{id}/attachments` sibling (same path pattern appears without the
  deprecated flag too — likely a content-type/multipart variant being phased out; exact
  differentiator not visible in this file, would need the `Consumes` diff to confirm).
- No group has a non-null `AccessLevel` or `ClassName` — see 3(e).

### 3(b). QueryParams — undocumented query capabilities

278 of 1,111 operations (25%) declare `QueryParams`. **45 distinct query parameter names** appear
across the surface — none of these are documented in either Swagger file (the Swagger docs only
formally type params for their own 14+178 operations). Full name → endpoint-count table:

| Param | # endpoints | Where used (representative) |
|---|---|---|
| `by-id` | 51 | `.../attachments/{attachment_name}` GET/PUT/DELETE across every collection — switches lookup from name to numeric id |
| `ids-to-delete` | 58 | bulk `DELETE` on plain collections (`workspace-folders`, `list-items`, `favorite-folders`, `build-*`, etc.) — bulk delete by id list |
| `version` | 41 | `POST .../{id}/lock` across most collections — optimistic-concurrency version check on lock |
| `split-multi-value-groups` | 35 | `GET .../groups/{groupsFields}` aggregation endpoints |
| `readChunks` | 24 | `GET .../audits` and `.../{collection}/{id}/audits` — chunked audit-trail paging |
| `is-snapshot`, `alt`, `generation-mode`, `ALM-CLIENT-TYPE`, `bypass-cache` | 3-4 each | `.../reports/{id}` and `.../export/{entity-collection}` — report rendering/export options |
| `upstreamBuilds` | 2 | `POST scm/build-push-service/{buildId}/coverage`\|`test-results` — CI/CD push integration |
| `encrypted-field` | 4 | `POST scm/repository-check`\|`branch-check`\|`build-configuration-check`\|`build-server-check` — SCM credential validation |
| `domain`, `project`, `entity-type`, `since` | up to 4 | `/synchronization/*` — external sync/integration protocol |
| `show-user-groups`, `show-user-groups-names` | 2 each | `customization/users(/{user-name})` |
| `show-subtype-relations` | 2 | `customization/entities/{entity-name}/relations` |
| `show-owner-field-name` | 1 | `customization/entities/{entity-name}/permissions` |
| `include-shared-with-me` | 2 | `workspaces`, `favorites` |
| `is-template-project` | 1 | `GET /domains/{domain}/projects` — filters template vs. real projects |
| `searchable`, `include-projects-info`, `enabled-extensions` | 1 each | `GET /domains` |
| `file`, `filePath`, `fileRevision`, `commitRevision`, `branchName`, `repositoryId`, `raw`, `forceDiff` | 1 each | `scm/file-view`, `scm/file-diff` — SCM file browsing |
| `project-uid`, `task-id`, `file-name` | varies | `bvexcel/*`, `analysis-item-file` — Business Views Excel export pipeline |
| (remaining: `add-params`, `authKey`, `build-type-id`, `fields`, `layout-type`, `parent-id`, `projects`, `release-id`, `size`) | 1-2 each | assorted |

Full per-name endpoint lists were computed but are omitted here for length; re-derivable from the
fixture with the PowerShell snippet used for this probe if needed.

### 3(c). Media types

- **Distinct `Produces`:** `*/*`, `application/atom+xml`, `application/javascript`,
  `application/json`, **`application/json;schema=alm-web`**, `application/msword`,
  `application/octet-stream`, `application/pdf`,
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/xml`,
  `image/png`, `text/html`, `text/plain`, `text/xml`.
- **Distinct `Consumes`:** `*`, `*/*`, `application/javascript`, `application/json`,
  `application/json;schema=alm-web`, `application/json;type=collection`, `application/octet-stream`,
  `application/xml`, `application/xml;type=collection`, `multipart/form-data`, `text/plain`,
  `text/xml`.
- **`application/json;schema=alm-web`** is advertised (Produces) by only **42 of 1,111** operations
  — a distinct, narrower JSON dialect. It clusters into two families: (1) all `GET
  .../{collection}/groups/{groupsFields}` aggregation endpoints (build-*, changeset*, scm-*,
  list-items, policy-items, branch-policy-links — the "group by field" reporting views), and (2) a
  handful of customization-metadata write/read endpoints (`PUT
  customization/entities/{entity-name}/fields`, `PUT .../types`, `GET customization/groups`,
  `POST/GET customization/users/{user-name}/avatar`) plus the meta-endpoints `GET /resource-list`
  and `GET /sa/site-params/metadata` themselves. `INFERRED`: `alm-web` is the schema dialect the
  stock Angular/GWT web client itself consumes (richer/denormalized shape for its grids), separate
  from the plain `application/json` most collections use — worth probing live for the actual shape
  difference before Alt-ALM decides whether to piggy-back on it.
- `application/json;type=collection` / `application/xml;type=collection` (Consumes only) appear on
  bulk-write endpoints (e.g. bulk create/update across a collection) — a QC-specific convention for
  "the body is an array, not a single entity," distinct from wrapping in `{collection: {entity:
  [...]}}` as the SA API does.

### 3(d). Plain entity collections — the definitive entity catalog

Paths matching `/domains/{domain}/projects/{project}/{single-segment}` (no further nesting, no
regex-typed id) with **both GET and POST**, i.e. true creatable/listable entity collections: **62**
found (70 single-segment candidates total; 8 are GET-only or otherwise partial — `audits`,
`businessmodels`, `businessviews`, `dqldescriptor`, `host-in-group`, `internal-token` are GET-only;
`project-connection` is POST+DELETE only, no GET; `web-workflow-script` is GET+PUT, singleton not a
collection).

The 62 GET+POST collections (this is the authoritative list of top-level project entities exposed
by the REST surface, independent of either Swagger doc):

```
analysis-item-file, analysis-item-files, analysis-item-folders, analysis-items, analysis-segments,
attachments, bpm-folders, branch-policy-links, build-artifacts, build-code-refs, build-contexts,
build-instances, build-servers, build-types, bv-hosts, changeset-files,
changeset-link-associations, changesets, dashboard-folders, dashboard-pages, defect-links, defects,
design-steps, environments, favorite-folders, favorites, host-groups,
lab-runs-protocol-granularities, list-items, locks, milestones, policy-items, release-cycles,
release-folders, releases, req-traces, requirement-coverages, requirement-target-cycles,
requirement-target-releases, requirements, resource-folders, resources, results, run-steps, runs,
scm-branch-releases, scm-branchs, scm-repositorys, step-parameters, test-config-coverages,
test-configs, test-criterion-coverages, test-criterions, test-executions, test-folders,
test-instances, test-parameters, test-set-folders, test-sets, tests, workspace-folders, workspaces
```
Note `list-items` here is a **different resource from `customization/used-lists/{id}/items`** in
the v2 doc (§1) — `list-items` is a project-entity collection with its own `/lock`, `/mail`,
`/groups/{groupsFields}`, bulk-`ids-to-delete` sub-resources like every other collection, not the
customization-list-value editor. Two separate things sharing the word "list/items" — worth a naming
note for the entity-model skill so it isn't conflated.

Most of these 62 also carry a matching **`-folders` or nesting relationship** (test-folders↔tests,
release-folders↔releases↔release-cycles, requirement hierarchy via `req-traces`, defect via
`defect-links`, resource-folders↔resources) confirming the tree-shaped modules the project charter
already expects (Requirements, Test Plan/Lab, Releases, Defects).

### 3(e). AccessLevel / ClassName

Checked all 319 groups: **`AccessLevel` is `null` on every single group; `ClassName` is `null` on
every single group.** No non-null values of either field appear anywhere in the file. These fields
exist in the schema (presumably populated for other product variants or reserved for future use)
but carry no signal in this fixture — nothing to report beyond "present but always empty here."

---

## 4. Cross-check — undocumented-but-present surface

**Headline number:** of the 1,111 operations in `resource-list-site.json`, only about **4 total**
correspond to something formally typed in either Swagger fixture:
- `GET /domains/{domain}/projects/{project}/customization/used-lists` (matches v2 doc's `getLists`)
- `GET /is-authenticated` and `PUT`/`POST /site-session` (match v2 doc's auth/session ops)

That's it. **Over 99% of the resource-discovery surface has no formal OpenAPI description in either
file supplied.** This includes essentially every one of the 62 plain entity collections in §3(d)
and all of their sub-resources (attachments, mail, lock, audits, groups-by-field, versioning, etc.)
— i.e. the entire test-management, requirements, defects, and release surface that Alt-ALM and the
Record Generator actually need to operate against.

**Notable inversion:** the *reverse* direction also has gaps. The v2 doc's own newest operations —
used-list **item-level** CRUD (`addItem`/`renameItem`/`addSubItem`/`deleteItem`) and
`purgeVCHistories` (the versioningHistory DELETE) — **do not appear in `resource-list-site.json` at
all**, not even in a different path format. Only the plain `GET .../used-lists` collection call is
present. `INFERRED`: `resource-list` is a live introspection dump that may be generated from a
different/older mechanism than the newest hand-authored v2 endpoints, i.e. it undercounts the
newest API surface just as much as it overcounts relative to what's Swagger-documented — it is not
a reliable single source of truth for "does this endpoint exist," only for "this endpoint exists"
(false negatives possible, no evidence of false positives found).

**Known-per-task families confirmed present** (all fully undocumented in both Swagger files):
- `design-steps` — full CRUD + `copy`, + nested `attachments` and `step-parameters` sub-collections
  (both GET/POST/PUT/DELETE at the item level).
- `req-traces` — full CRUD (requirement traceability links).
- `milestones` — full CRUD + `mail` (send notification).
- `step-parameters` — full CRUD, both as its own top-level collection AND nested under
  `design-steps/{id}/step-parameters`, `runs/{id}/step-parameters`, `test-configs/{id}/step-parameters`,
  `test-instances/{id}/step-parameters` (parameterized-testing data model).
- `mail` — not a standalone collection; it's a `POST .../{collection}/{id}/mail` sub-resource
  present on **19 different collections** (defects, requirements, runs, tests, test-sets,
  test-executions, test-instances, milestones, changesets, environments, resources, host-groups,
  bv-hosts, build-instances, analysis-item-files, lab-runs-protocol-granularities,
  test-config-coverages, test-criterion-coverages) — a generic "email about this record" action, not
  mail-server administration (that's the separate, documented SA `/mails/*` group in §2).
- `audits` — present as `GET .../projects/{project}/audits` (project-level, **not** SaaS-gated
  unlike the SA `/audits` in §2) and per-entity `GET .../{collection}/{id}/audits` on ~24
  collections, all supporting the `readChunks` param from §3(b).

**Other notable undocumented families, grouped:**
- **SCM/build/CI integration** (large, ~20+ endpoints): `scm-branchs`, `scm-repositorys`,
  `scm-branch-releases`, `scm/file-view`, `scm/file-diff`, `scm/release-build-status`,
  `scm/repository-check`, `scm/branch-check`, `scm/build-configuration-check`,
  `scm/build-server-check`, `scm/build-push-service/{buildId}/coverage`\|`test-results`,
  `build-artifacts`, `build-code-refs`, `build-contexts`, `build-instances`, `build-servers`,
  `build-types`, `changesets`, `changeset-files`, `changeset-link-associations`,
  `branch-policy-links`, `policy-items` — an entire CI/SCM-integration data model (likely backing
  ALM Octane-bridge or Jenkins-plugin style integrations) with zero API documentation in either
  fixture.
- **Business Process/Lab-host management:** `bv-hosts`, `host-groups`, `host-in-group`,
  `businessmodels`, `businessviews`, `lab-runs-protocol-granularities` — Business Process Testing /
  lab resource-pool management, distinct from the documented `Resources` module.
- **Analysis/Business-Views reporting pipeline:** `analysis-item-file(s)`, `analysis-item-folders`,
  `analysis-items`, `analysis-segments`, `bvexcel/task-details`, `bvexcel/resultfile` — an Excel
  export/analysis feature entirely absent from both docs.
- **Personal workspace:** `workspace-folders`, `workspaces`, `favorites`, `favorite-folders`,
  `dashboard-folders`, `dashboard-pages`, `graphs/{id}/result`\|`/layouts/{name}`, `reports/{id}` —
  the "My Workspace"/dashboards module, plus a `public/...` mirror of graphs/dashboardpages/reports/
  attachments (anonymous/shared-link access, uses `authKey` query param instead of session cookies —
  `INFERRED`: token-based public-share links).
- **Extended requirement/test linking:** `defect-links`, `requirement-coverages`,
  `requirement-target-cycles`, `requirement-target-releases`, `test-config-coverages`,
  `test-criterion-coverages`, `test-criterions`, `test-executions`, `test-parameters` — finer-grained
  link/coverage entities beyond the basic requirement→test→defect chain the charter anticipates.
- **Customization, deeper than the v2 doc's used-lists:** `customization/entities` (+`/fields`,
  `/lists`, `/permissions`, `/relations`, `/types`(+`/{type_id}/fields`, `/{type_id}/icon`)),
  `customization/extensions` (+`/dev/`, `/dev/preferences`, `/dev/workflow`), `customization/groups`,
  `customization/relations`, `customization/project-access-data`, `customization/users`
  (+`/{user-name}`, `/{user-name}/avatar`), `customization/usergroups/{user-name}` — this is the
  **real runtime metadata-discovery surface** the charter's "forms must render from metadata fetched
  at runtime" constraint depends on, and none of it is in the v2 Swagger doc. High priority to probe
  live.
- **Integration/sync protocol:** `/synchronization/last-deleted-ids`, `/synchronization/synchronized-projects`,
  `/synchronization/start-tracking-times`, `/synchronization/check-entity-existence`,
  `/synchronization/entity-type-last-touch-time` — looks like a purpose-built delta-sync protocol
  for external integrations (ALM Octane bridge or similar), fully undocumented.
- **SSO / launcher / client plumbing:** `/sso/initiations(/{uniqueId}(/sso-confirmations|/sso-validations))`,
  `/launcher/install-tokens(/{uniqueId})`, `/ali/plugin-info`, `/ali/version-info` — client-launcher
  and SSO handshake support endpoints, not applicable to a browser-based BFF but good to know exist.
- **Misc utility:** `GET /server`, `GET /server/time` (no auth requirements implied by absence of
  path/security notes — `INFERRED`, would need live probe to confirm), `GET /resource-list` and
  `GET /resource-list/administrative` (this discovery mechanism describing itself), `GET
  /sa/site-params/metadata` and `GET /sa/version` (two stray site-level reads exposed through the
  *project* API's `/qcbin/v2/rest` base rather than the SA API's `/qcbin/v2/sa/api` base — distinct
  from and NOT the same paths as the documented SA `site-params`/`site-version` endpoints in §2;
  `INFERRED`: read-only project-side conveniences so a project-scoped client can learn site version
  without SA credentials).
- **Generic per-entity mechanics present on nearly every one of the 62 collections** (already
  counted in §3(b)'s param table, listed once here rather than per-collection): `attachments`
  sub-collection, `groups/{groupsFields}` aggregation view, `{id}/lock` (optimistic concurrency),
  `{id}/audits`, bulk `DELETE ?ids-to-delete=`, and (17 of them) the deprecated bulk-attachment POST
  noted in §3(a).

**Bottom line for planning:** the two Swagger fixtures document authentication, session management,
list-of-values (used-lists) editing, VC-history purge, and the entire Site Administration surface
(users/projects/domains/licenses/etc.) — but essentially **none of the core entity CRUD that both
Alt-ALM's UI and the Record Generator are built around**. That surface is real (resource-list proves
it exists, with real query-param and media-type contracts) but its request/response *body* shapes
are not in these fixtures at all and must come from another source (customization field-metadata
fixtures already in `tests/fixtures/customization-fields-*.json`, and/or live probing per the
`alm-live-probe` skill) — not from these two Swagger files.
