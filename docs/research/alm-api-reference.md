# OpenText ALM / Quality Center REST API — Reference

Reconciled reference for how the ALM/QC REST API actually behaves, scoped to versions **24.1–26.1**
with our sandbox (**ALM 26.1**, internal `SiteVersion 20.0 (Build 20.00.0.143)`) as ground truth.

**Provenance tags** used throughout: `[probe]` = observed directly against our sandbox
(`docs/research/live-probe-log.md` or a `_raw/probe*` report); `[swagger]` = read from a per-instance
OpenAPI/Swagger fixture (`api-doc-v2-openapi.json` / `api-doc-sa-v2-openapi.json`); `[resource-list]` =
read from the `GET /qcbin/rest/resource-list` inventory fixture; `[docs-research]` = OpenText/Micro
Focus official documentation, found by a wave-1 research subagent, not independently probed; `UNVERIFIED`
= no direct evidence, with the experiment that would settle it. Where sources conflict, the higher
source in the priority order below wins and the conflict is stated explicitly.

**Source priority**: (1) `live-probe-log.md` empirical findings, (2) `_raw/probe3-*` … `_raw/probe6-*`
offline-mined/write-probe reports, (3) `_raw/wave1-*` documentation research, (4) `tests/fixtures/`
spot-checks. Nothing in this document was invented; every claim is traceable to one of these.

> Lead-reviewed 2026-08-12: folded in write rounds 2–3 (probe log Probes 5–6), corrected a false
> §6.1 discrepancy note, and updated §9 for findings resolved since the first draft.

---

## 1. Version & deployment context

- **Server = ALM 26.1** (marketing/external version). `GET /qcbin/v2/sa/api/site-version` →
  `{"site-version":{"full-version":"20.0 (Build 20.00.0.143)", "external-version":26.1, …}}`
  `[probe]` (live-probe-log.md, Probe 3). The `GET /qcbin/rest/sa/version` endpoint returns only the
  internal `SiteVersion "20.0 (Build 20.00.0.143)"` string — do not treat "20.0" as the marketing
  version; it is the internal site-version numbering of the same build `[probe]`.
- **Lineage confirmed**: the classic ALM/QC line runs `…12.50 → 12.60 → 15.0 → 15.5/15.5.1 → 16.0/16.0.1
  → 17.0/17.0.1 → 24.1 → 25.1 → 26.1`. **No 18.x–23.x versions ever existed** — convergent evidence
  from every OpenText doc-tree version-path segment and staff roadmap statements about a calendar-year
  renumbering (24=2024, 25=2025, 26=2026; the calendar-year rationale itself is `UNVERIFIED` —
  inferred, not vendor-stated) `[docs-research]` (wave1-09). Vendor chain: Mercury Interactive → HP →
  Micro Focus (Sep 2017) → OpenText (closed Jan 31 2023).
- **Product rename**: "OpenText Application Quality Management" announced 25.1, Jan 29 2025 — the
  product is still universally called "ALM/Quality Center" in URLs, UI strings, and most doc titles
  post-rebrand `[docs-research]` (wave1-09).
- **Doc-set split is structural, not cosmetic**: REST capability **introduced before 24.1** is
  documented in two static, evergreen doc trees — "**REST API Reference (Core)**"
  (`/qcbin/rest/...`, curly-brace query grammar) and "**REST API Reference (Deprecated)**"
  (`/qcbin/api/...`, symbol-only query grammar, no cross-filters). REST capability **introduced in
  24.1 or later is documented only in a live, per-instance Swagger/OpenAPI document** served at
  `/qcbin/api-doc/v2/qc.json` (project API) and `/qcbin/api-doc/sa/v2/qc.json` (Site Admin API) — no
  static page for these exists anywhere `[docs-research]` (wave1-02, confirmed reachable `[probe]`
  Probe 3: 14 ops / 178 ops respectively). Practically: **assume nothing about 24.1+ additions from
  static OpenText docs; verify against the target instance's own `/qcbin/api-doc/*` output.**
- **Deployment is SaaS-flavored**: the `customers/*` Site Admin endpoint family and a "Customer Admin"
  (role-id 2) SA role — our API key's user holds that role, giving full SA API access `[probe]`
  (Probe 3). 28 of 178 Site Admin operations carry a `SAAS_ONLY` extension flag in the Swagger doc and
  are unusable on-prem (`GET /audits`, `/audits/export`, `/audits/metadata`, `/permissions/metadata`,
  most of the `/customers/*` family, `/orphan-users`, `GET /roles/{roleId}`) `[swagger]`
  (probe3-mining-swagger §2).
- **Target-version range 24.1–26.1**: the Core/Deprecated static doc content has not changed
  meaningfully across this range (evergreen "15.5 and later" doc tree) `[docs-research]`; the delta
  that matters is entirely in the per-instance Swagger surface and site-parameter defaults, which
  must be re-read per target deployment, not assumed from this document.

---

## 2. Auth & session lifecycle

### 2.1 The one-step API-key flow (our target auth method)

`POST /qcbin/rest/oauth2/login`, `Content-Type: application/json`, body `{"clientId":"…","secret":"…"}`
→ **200**, and on our server this single call sets the **full cookie set in one step**:
`LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`, `ALM_USER`, `JSESSIONID` `[probe]` (Probe 1). This
simplifies the two-step `alm-authenticate` → `site-session` dance documented as the general case
(`alm-authenticate` sets only `LWSSO_COOKIE_KEY`; a separate `POST site-session` is needed for
`QCSession`/`XSRF-TOKEN`/`ALM_USER`) `[docs-research]` (wave1-01 §1–2) — `oauth2/login` is one of the
two other documented one-step flows (the other being `POST /qcbin/api/authentication/sign-in` with
HTTP Basic credentials, untested by us).

`POST /qcbin/rest/site-session` → **201** after `oauth2/login` on our server — harmless, but whether
it is strictly *required* after `oauth2/login` (vs. purely idempotent confirmation) is still open
`[probe]` (Probe 1, open item #2).

### 2.2 Site-session and XSRF

- **XSRF header requirement — VERIFIED**: `POST requirements` with the `XSRF-TOKEN` cookie present but
  **no** `X-XSRF-TOKEN` header → **HTTP 401**, body
  `{"Id":"qccore.general-error","Title":"Unauthorized request. For more details see XSRF Token section
  in REST API documentation.","ExceptionProperties":null,"StackTrace":null}`, reproduced identically
  across every probe run `[probe]` (probe4-write-round-1.md). This resolves wave1-01's UNVERIFIED #2
  (docs only said "the REST API calls fail," no status code given) — **our probe is the only source
  for the exact status/body; treat as target-version-confirmed, not universally guaranteed on every
  ALM install.** Header value = the `XSRF-TOKEN` cookie, echoed back on every non-GET call, required
  since ALM 16.00 `[docs-research]` (wave1-01 §4).
- The request never reached entity-level processing — the XSRF gate runs before any business logic, so
  a missing-XSRF failure carries **no silent-commit risk** `[probe]`.
- **Keepalive**: `GET` or `PUT /qcbin/rest/site-session` resets the idle-timeout clock
  `[docs-research]` (wave1-01 §5).
- **Idle timeout**: `REST_SESSION_MAX_IDLE_TIME` site parameter, default **60 minutes**
  `[docs-research]` (wave1-01 §5). A second, SSO-specific `SSO_EXPIRATION_TIME` (reported default ~11
  min) is lower-confidence — sourced from an unverified page extraction, not a literal quote
  `UNVERIFIED`.
- **Logout — conflict, probe wins**: `GET /qcbin/authentication-point/logout` → **200**, session
  cookies dropped (only `JSESSIONID` remained) `[probe]` (Probe 1). This **conflicts** with
  `[docs-research]` (wave1-01 §5, source 12/23): *"Since ALM 24.1, GET is disabled by default"* on both
  logout-style endpoints, requiring `ENABLE_GET_LOGOUT_METHOD=Y` to re-enable. **Conflict adjudication:
  the probe wins for our target server** (per source-priority rule) — either our sandbox has
  `ENABLE_GET_LOGOUT_METHOD=Y` set, or the documented 24.1 default does not hold on this
  SaaS-flavored deployment. Do not assume GET-logout works on an arbitrary 24.1+ instance; prefer
  `POST` for portability and treat GET-logout success as instance-specific.
- **Licence**: **REST sessions consume no ALM licence seat** — primary doc: the `site-session` resource
  page's own defaults note says "No licenses consumed" for a POST-created session; corroborated
  independently by OpenText community staff `[docs-research]` (wave1-01 §8). Sessions are still
  **visible** under Site Administration → Site Connections, for monitoring only, not seat accounting.

### 2.3 Session-liveness checks

| | Legacy | Recommended |
|---|---|---|
| Path | `GET /qcbin/rest/is-authenticated` | `GET /qcbin/v2/rest/is-authenticated` |
| Formats | **XML only** — `Accept: application/json` → **406** `[probe]` (Probe 3) | XML and JSON |
| Response (JSON) | n/a | `{"AuthenticationInfo":{"Username":"…"}}` `[probe]` (Probe 3) |
| Opens a session? | No | No |

Both confirmed live: unauthenticated `GET is-authenticated` → 401; authenticated → 200 `[probe]`
(Probe 1). Use the v2 path for any JSON-consuming client — the Core path is a 406 trap.

### 2.4 API keys

Each key = Client ID + Secret, maps to a real user account, and **inherits that user's exact
project/permission scope** — deleting/deactivating the user deletes its keys `[docs-research]`
(wave1-01 §3). `APIKEY_MAX_NUM_PER_USER` default 10; `APIKEY_EXPIRE_DAYS` default -1 (never); managed
in Site Admin (on-prem: whole install; SaaS: tenant-scoped to the logged-in Customer Admin). Our
sandbox's key's user holds SA role **Customer Admin (role-id 2)**, giving full Site Admin API access
`[probe]` (Probe 3) — enough to automate sandbox user seeding (`POST /qcbin/v2/sa/api/site-users`,
`POST …/domains/{d}/projects/{p}/users`).

---

## 3. Request/response envelopes

### 3.1 XML vs JSON, and the Core `Fields` array shape

Both XML and JSON representations exist for every Core entity. The JSON write shape, confirmed by
direct probe against `POST requirements`, `POST tests`, `POST design-steps`, `POST defects`, etc.:

```json
{"Fields":[{"Name":"<field>","values":[{"value":"<v>"}]}, …],
 "Type":"<entity-type-singular>"}
```

`[probe]` (probe4-write-round-1.md) — matches the documented Core "Entity"/"Fields"/"Field"/"Value"
XML shape 1:1 (`<Entity Type="…"><Fields><Field Name="…"><Value>…</Value></Field></Fields></Entity>`)
`[docs-research]` (wave1-03, wave1-06). GET responses use the same `Fields` array but with every field
the entity's type carries, including calculated/read-only ones — **never round-trip a GET payload
directly into a POST/PUT body**; calculated fields (e.g. `id`, `last-modified`, `father-name`,
`hierarchical-path`, all `vc-*`) will be rejected `[docs-research]` (wave1-03 §1, explicit doc
warning).

Collection envelope (JSON): top-level `{"entities":[…], "TotalResults": n}` `[probe]` (Probe 2, `GET
…/defects?page-size=1`).

### 3.2 ⚠️ Deterministic field-order requirement — probe-verified

**The `Fields` array's JSON serialization ORDER affects server behaviour on write.** Sending the same
logical field set in different member orders produced different results for identical data:

| Fields array order | `parent-id=0` | `parent-id=-1` | `parent-id` omitted |
|---|---|---|---|
| `parent-id, name, type-id` | 500 `Cannot invoke "Object.hashCode()" because "key" is null` | 500 same | 400 `qccore.required-field-missing`, field `name` (misleading — `name` WAS present) |
| `type-id, parent-id, name` | 500 `...FieldEntry.getName() is null` | 500 same | 500 same |
| `name, parent-id, type-id` (deterministic) | **201 created** | — | — |

`[probe]` (probe4-write-round-1.md, "Finding: request JSON field ORDER affects server behavior"). Root
cause is `UNVERIFIED` (server internals not visible; plausibly positional/streaming processing of the
array rather than a name-keyed map lookup) — but the **actionable conclusion is a hard client
requirement, not a style preference**: any Alt-ALM writer must serialize entity-write JSON with a
fixed, deterministic field order (e.g. `name` → relational ids → type/subtype fields last) and must
never rely on hashmap/dict iteration order in whatever implementation language is used. This order was
stable and successful across three subsequent full probe sessions for every entity type tried
(requirement, test-folder, test, design-step, requirement-coverage, req-trace, defect, defect-link).

### 3.3 ⚠️ HTTP 500 may still commit the write — probe-verified

One 500-response `POST requirements` during probing had **silently committed a row server-side**
despite returning an error to the client — discovered via a `father-name` cross-reference in a later
successful response, confirmed by `GET requirements/1` → 200, then deleted `[probe]`
(probe4-write-round-1.md, "LEFTOVER RECORD FOUND"). **Conclusion, verified**: an HTTP 500 to a POST is
**not proof that no row was written**. Any client (including Alt-ALM's BFF) that retries after a 500
must be prepared for a duplicate, or must verify via GET before retrying. Reasoned-but-not-directly-
proven lower-risk distinction: well-formed business-validation 500s that explicitly name and reject
the bad value (e.g. `"Cannot create 'Test Folder'. Invalid owner specified: 0."`) did not show this
behaviour in the same session's checks — but that inference is not a guarantee; **treat every 5xx
write response as "unknown outcome — verify by query," never as "failed."**

### 3.4 Accept header and error envelope

`Accept` header selects the response/error format:
- No/invalid `Accept` → full branded **HTML** error page (default) `[docs-research]` (wave1-01 §9).
- `Accept: application/xml` → `<QCRestException><Id>…</Id><Title>…</Title></QCRestException>`.
- `Accept: application/json` → `{"Id":"…","Title":"…"}`; bulk operations extend this with
  `ExceptionProperties` containing a `BulkEntry`/`BulkOperationFailed` structure and `StackTrace`.
- If a binary media type is requested alongside another, the exception follows the **secondary**
  type's format, not HTML `[docs-research]` (wave1-01 §9).

This exact envelope was reproduced verbatim by probe on the XSRF-401 case (§2.2) — `[probe]`-confirmed
for at least that one error path. Documented `Id` catalogue: `qccore.bulk-operation-failed`,
`qccore.check-in-failure`, `qccore.check-out-failure`, `qccore.entity-not-found`,
`qccore.general-error`, `qccore.invalid-filter-expression`, `qccore.invalid-list-field-value`,
`qccore.invalid-value-type-for-field`, `qccore.lock-failure`, `qccore.operation-forbidden`,
`qccore.required-field-missing`, `qccore.session-has-expired`, `qccore.undo-check-out-failure`,
`qccore.unknown-field-name` `[docs-research]` (wave1-01 §9) — `qccore.general-error` and
`qccore.required-field-missing` both independently reproduced by probe `[probe]`.

Documented HTTP status catalogue: 200/201/400/401/403/404/405/406/409/415/500/501, with 400 and 403
explicitly documented as **catch-alls** covering several distinct failure modes each
`[docs-research]` (wave1-01 §9) — do not infer a single specific cause from the status code alone;
always parse `Id`/`Title`.

### 3.5 The `alm-web` dialect — UNVERIFIED shape

`application/json;schema=alm-web` is a distinct, narrower JSON media type advertised by only **42 of
1,111** operations in the resource-list inventory `[resource-list]` (probe3-mining-swagger §3c). It
clusters into (1) all `GET …/{collection}/groups/{groupsFields}` aggregation/"group by field"
endpoints, and (2) a handful of customization-metadata write/read endpoints (`PUT
customization/entities/{entity}/fields`, `PUT …/types`, `GET customization/groups`, avatar
upload/read) plus the `GET /resource-list` and `GET /sa/site-params/metadata` meta-endpoints
themselves. `INFERRED` (not directly probed): this is the schema dialect the stock Angular/GWT web
client itself consumes for its grids — a richer/denormalized shape distinct from plain
`application/json`. **The actual body shape difference is `UNVERIFIED`** — no probe has requested this
media type; worth a live probe before Alt-ALM considers piggy-backing on it for grid/aggregation views.

### 3.6 ⚠️ Field metadata `editable:false`/`required:false` does not describe write requirements — probe-verified

A second instance of the class of trap in §3.2, distinct from field *order*: field metadata can flatly
misstate what a write needs. `POST test-parameters` (§6.4) omitting `ref-count` fails with `HTTP 500
{"Id":"qccore.general-error","Title":"...request is missing required field TP_REF_COUNT"}`, even
though `customization/entities/test-parameter/fields` reports `ref-count` as `editable:false,
required:false` — implying it should be omittable. Sending it anyway succeeds `[probe]`
(live-probe-log.md Probe 9, §9.3). **Generalized rule for the BFF's write-safety component:**
`editable:false` does not imply "omit from the write body", and `required:false` does not imply
"optional on create" — the `Required` flag describes UI/validation semantics, not the server's own
FREC-conversion preconditions. Any `HTTP 500` naming a `missing required field <PHYSICAL_NAME>` should
be retried once with that physical field's logical name included, before being reported as a failure.

---

## 4. Query grammar, paging & bulk operations

Two coexisting, still-live REST generations with **different grammars**: **Core** (`/qcbin/rest/...`,
curly-brace grammar) and **Deprecated** (`/qcbin/api/...`, symbol-only grammar, no cross-filters)
`[docs-research]` (wave1-02). Everything below is Core unless marked Deprecated.

### 4.1 Core query grammar

`GET .../{entities}?query={query statement}`. Structural rules, verbatim from the primary doc
`[docs-research]` (wave1-02 §1):

- Filter in curly brackets `{}`; per-field expression in square brackets `[]`; fields delimited by
  `;`. **"The only operation supported between fields is AND"** — the AND is implicit, unspecified in
  syntax; this is a hard grammar limit, confirmed nowhere contradicted by probe.
- Statement: `<field>[condition]; <field>[condition]; …`, e.g.
  `tests?query={id[GT 1 AND NOT 5]; status[Ready or Design]}`.
- Operators: `GT`/`>` , `LT`/`<`, `EQ`/`=`, `GE`/`>=`, `LE`/`<=`. **Before ALM 24.1 P1, symbol-only**;
  from 24.1 P1, either form works (text form recommended for strict-security orgs).
- Logical operators inside one field's brackets: `AND`, `OR`, `NOT` (keywords). Quotes (single or
  double) for literals with spaces; `*` wildcard; parentheses nest freely.
- **No documented null-test syntax for Core** (Deprecated has `= null`) — genuine gap, `UNVERIFIED`
  (probe: `{detected-in-rel[null]}`, `{field[]}`).
- **No documented escaping rule for delimiter characters** (`'`, `"`, `;`, `[`, `]`, `(`, `)`, `,`) in
  Core literals — a real risk for a data generator whose free-text fields might contain any of these;
  `UNVERIFIED` (probe: `{name['O''Brien']}` variants).
- Date/time literals: `yyyy-MM-dd` (Date), `HH:mm:ss` (Time), `yyyy-MM-dd HH:mm:ss` (DateTime) —
  identical in both generations; "calls using other formats will fail." No documented timezone rule
  `UNVERIFIED`.

### 4.2 Cross-filters (Core only)

`<alias>.<logical field name>[condition]`, e.g. `tests?query={connected-to-defect.name["Widget
wobbles*"]}`. Qualifying pairs are governed by schema Relations — the alias must be **unique** among
relations connecting the two types. Ambiguity example (design-steps↔test has two relations): bare
`{test.name[e]}` fails; the disambiguating alias `{has-parts-test.name[e]}` works
`[docs-research]` (wave1-02 §3). `{alias}.inclusive-filter[false]` inverts that one alias's clauses
(exclusion). **Rule**: never use more than one alias for the same entity type in one
query/fields/order-by — violations silently produce wrong results, not errors.

### 4.3 Projection and sorting

- `fields=<name>[,<name>…]` (logical names, related-entity fields via alias) — **no effect on a
  single-entity GET** in Core (Deprecated's fields clause DOES apply to instances — a real behavioural
  difference). Discover filterable fields via
  `.../customization/entities/{entity}/fields?can-filter=true`.
- `order-by={field[,field…]}`, collections only (no-op on single GET); default sort = entity ID
  ascending; multi-field e.g. `order-by={status;name[DESC]}`; Reference fields sort by the referenced
  **value**, not the id (e.g. `parent-id[DESC]` sorts by folder name); `[…,CI]` forces case-insensitive
  sort (added ALM 17.0). No `can-sort` metadata flag found — `UNVERIFIED`.

### 4.4 Paging

| | Core | Deprecated |
|---|---|---|
| Size param | `page-size` | `limit` |
| Start param | `start-index` (**1-based**) | `offset` (0-based) |
| Default size | `REST_API_DEFAULT_PAGE_SIZE` = 100 | `REST_API_PAGINATION_DEFAULT_LIMIT` = 100 |
| Max size | `REST_API_MAX_PAGE_SIZE` = 2000, **silently capped** | `REST_API_PAGINATION_MAX_LIMIT` = 2000, **throws on overflow** |
| Total count | `TotalResults` (XML attr or JSON key) | (not captured this pass) |

`[docs-research]` (wave1-02 §6). Deep paging degrades on large collections — prefer query narrowing
over large `start-index` offsets.

### 4.5 Bulk operations

Same-entity-type only. DELETE: `?ids-to-delete=17,28,31,46` (Core; confirmed present on **58 of the 62
generic entity collections** per resource-list `[resource-list]`, probe3-mining-swagger §3b). POST/PUT:
body is the same JSON/XML shape as a single write but as an array, with
`Content-Type: application/json;type=collection` (or the XML equivalent) — a QC-specific convention
distinct from wrapping in `{collection:{entity:[…]}}`. `REST_API_MAX_BULK_SIZE` default 2000 (min 1).
**Non-transactional**: "the bulk operation executes for all of the entities even if there is one or
more failure." 200 = full success; 500 = all failed; **409 = partial**, with a
`BulkOperationFailed`/`BulkEntry[]` body where each entry carries `Successful`, `EntityId`,
`EntityType`, and (on failure) a nested `Id`/`Title`/`StackTrace` `[docs-research]` (wave1-02 §7,
verbatim XML schema captured). A client must always parse the 409 body per-item — never assume
all-or-nothing.

`MAX_REQUEST_LENGTH` (default 10000 KB, min 512 KB) caps total `/qcbin/v2/` request length, introduced
ALM 16.01 P1 `[docs-research]` (wave1-02 §8).

---

## 5. The generic entity contract

**62 top-level entity collections** share one common REST surface pattern — this is the definitive,
independently-derived project-entity catalog (matches neither Swagger doc; both undercount it)
`[resource-list]` (probe3-mining-swagger §3d, cross-checked against probe3-resource-list-basepath-
table.md):

```
analysis-item-file(s), analysis-item-folders, analysis-items, analysis-segments, attachments,
bpm-folders, branch-policy-links, build-artifacts, build-code-refs, build-contexts, build-instances,
build-servers, build-types, bv-hosts, changeset-files, changeset-link-associations, changesets,
dashboard-folders, dashboard-pages, defect-links, defects, design-steps, environments,
favorite-folders, favorites, host-groups, lab-runs-protocol-granularities, list-items, locks,
milestones, policy-items, release-cycles, release-folders, releases, req-traces,
requirement-coverages, requirement-target-cycles, requirement-target-releases, requirements,
resource-folders, resources, results, run-steps, runs, scm-branch-releases, scm-branchs,
scm-repositorys, step-parameters, test-config-coverages, test-configs, test-criterion-coverages,
test-criterions, test-executions, test-folders, test-instances, test-parameters, test-set-folders,
test-sets, tests, workspace-folders, workspaces
```

Of the 62, **each entity's generic sub-resource surface is drawn from the same small set of building
blocks**, present with varying coverage per collection `[resource-list]` (counted across 1,111 total
operations, probe3-mining-swagger §3b):

| Sub-resource | Coverage | Purpose |
|---|---|---|
| `.../{parent_id}/attachments` (+ `/{name}`) | most collections; **17 carry a `[DEPRECATED]`** flag on the plain collection-POST form (see below) | file attach, incl. `ref-subtype=1` rich-content linking |
| `.../{parent_id}/lock` (GET/POST/DELETE, `version` param) | 41 collections | optimistic-concurrency lock (not versioning) |
| `.../{id}/audits` (`readChunks` param) | 24 collections + project-level `GET …/audits` | change history read (⚠️ partial coverage — see §9) |
| `.../{parent_id}/mail` (POST) | 19 collections | "email about this record" (not mail-server admin) |
| `.../groups/{groupsFields}` (GET, `split-multi-value-groups` param) | 35 collections | group-by aggregation view; part of the `alm-web` dialect family |
| `.../copy` (POST, body `{IDs:[…], TargetParentId}`) | gated by per-entity `SupportsCopying` | subtree duplication, preserves attachments + co-copied links |
| Bulk `DELETE ?ids-to-delete=` | 58 collections | see §4.5 |
| `.../versions` (+ check-in/check-out/undo-check-out) | requirements, tests, resources (+ favorites/favorite-folders) only | version control (`[docs-research]` wave1-05; not yet write-probed) |

**17 deprecated operations**, all of the exact form `POST
.../{collection}/{parent_entity_id}/attachments` for 17 collections (bpm-folders, design-steps,
environments, defects, milestones, releases, release-cycles, release-folders, requirements, runs,
run-steps, test-configs, tests, test-folders, test-instances, test-sets, test-set-folders) — a
single-attachment-upload-by-POST-to-parent-collection form, superseded by the non-deprecated sibling of
the same path pattern `[resource-list]`. `INFERRED`: a content-type/multipart variant being phased out
— avoid these 17 in new client code.

**Two naming collisions to keep straight** (worth a note in the entity-model skill):
- `list-items` (a project-entity collection with its own lock/mail/groups/bulk-delete, listed above)
  is **unrelated** to `customization/used-lists/{id}/items` (the list-of-values editor from §1.2 of
  the v2 Swagger). Same word, different resources.
- `customization/entities/{e}/groups` doesn't exist; `.../{entity}/groups/{field}` is **query
  GroupBy**, not a permission-groups collection — the real (undocumented) group-membership surface is
  `customization/usergroups/{user-name}` (community-reported, `UNVERIFIED`).

Only ~4 of the 1,111 resource-list operations correspond to anything formally typed in either Swagger
fixture — **essentially none of the core entity CRUD that Alt-ALM and the generator are built around
is Swagger-documented.** Its request/response *body* shapes come from customization field-metadata
fixtures and live/write probing, not from either Swagger doc `[resource-list]` (probe3-mining-swagger
§4, "Bottom line for planning").

---

## 6. Per-domain call recipes

### 6.1 Requirements

**Create — VERIFIED.** Minimum fields: `name` (String, required) + `type-id` (Reference, required,
physical `RQ_FATHER_ID`… `RQ_TYPE_ID`) + `parent-id` (Number, physical `RQ_FATHER_ID`).

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"0"}]},
           {"Name":"type-id","values":[{"value":"3"}]}],
 "Type":"requirement"}
```
→ **HTTP 201** `[probe]`.

**Root `parent-id` value — resolved, with a documented false start.** Probe round 1's first working
value was `parent-id=1`, but that run's id=1 was later discovered to be a **contaminated orphan
record** (a silently-committed 500, see §3.3) rather than the true root — the finding was flagged
`⚠️ CONTAMINATED` and queued for re-verification on clean state `[probe]` (probe4-write-round-1.md).
**Round 2, on clean state, confirms the true root is `parent-id=0`** ("Requirements" root, `father-name`
returned as `"Requirements"` on create) `[probe]` (live-probe-log.md Probe 5;
`tests/fixtures/write-probe/r2-req-create.json`).
**User-provided defaults table** (prefer runtime discovery over hardcoding, per CLAUDE.md): requirement
root = id 0 "Requirements"; test-plan folders: id 2 "Subject", id 1001 "Recycle Bin"; test-set
folders: id 0 "Root", id 1 "Recycle Bin" (SESSION-STATE.md, 2026-08-12 user input). Roots were
probe-confirmed on this project (`requirements/0`, `test-folders/2`, `test-set-folders/0`) `[probe]`
(Probe 5) — but always discover the root at runtime via `?query={parent-id[0]}` rather than
hardcoding. (An earlier draft of this section claimed the round-2 test-set fixture contradicted the
root table; it does not — `r2-test-set-create.json` shows `parent-id=5`, the probe's own freshly
created test-set-folder, not a root value.)

**Type-id table** (`GET .../customization/entities/requirement/types`, live, 8 types) `[probe]`
(probe3-mining-fieldtypes.md §6):

| ID | Name | Direct coverage | Risk analysis |
|---|---|---|---|
| 0 | Undefined | Y | 0 (none) |
| 1 | Folder | N | 2 (structural, none) |
| 2 | Group | N | 2 |
| 3 | Functional | Y | 1 (enabled) |
| 4 | Business | N | 1 |
| 5 | Testing | Y | 1 |
| 6 | Performance | Y | 1 |
| 66 | Business Model | Y | 1 |

This resolves wave1-03's "7 vs 8 types, is Performance real" open question — **confirmed live: 8
types including Performance (id 6)** `[probe]` beats `[docs-research]`.

**Field groups** (full set from live create response, `req-create-response.json`, ~70 fields)
`[probe]`: identity/hierarchy (`id, name, type-id, parent-id, father-name, no-of-sons, order-id,
hierarchical-path`); content (`description, has-rich-content, req-rich-content, comments`); 27
`rbt-*` risk fields (§8); full `vc-*` version-control set. **No plain `status` field** — closest is
`req-reviewed` (Reviewed/Not Reviewed) `[docs-research]` (wave1-03 §1).

**"Passing fields that don't belong to the requirement's type is an error"** — fields are type-scoped;
the generator must know each requirement's type before writing `[docs-research]` (wave1-03 §1, direct
doc quote).

Convert-requirement-to-test: **no REST evidence found**; the desktop UI's Convert-to-Tests wizard is a
composite client-side operation (create test + coverage link), not a single server endpoint
`[docs-research]` (wave1-03 §8, cross-checked against wave2 UI findings per SESSION-STATE.md).

### 6.2 Coverage (requirement ↔ test)

**`requirement-coverages` POST — VERIFIED, resolves a long-contested question.** Fields (from
`customization/entities/requirement-coverage/fields`): `requirement-id` (Number, required), `test-id`
(Number, required), `entity-type` (String — value `"test"` used), plus server-returned `coverage-mode`
(LookupList), `status` (String), `id`, `modified-count`, `last-modified`.

```json
{"Fields":[{"Name":"requirement-id","values":[{"value":"<req-id>"}]},
           {"Name":"test-id","values":[{"value":"<test-id>"}]},
           {"Name":"entity-type","values":[{"value":"test"}]}],
 "Type":"requirement-coverage"}
```
→ **HTTP 201** on the first attempt `[probe]` (probe4-write-round-1.md §6). This resolves community
reports that had been contradictory since 2013 (HP staff: unsupported; ~2017 community: working;
~2022: unsupported again) `[docs-research]` (wave1-03 §4) — **on ALM 26.1, it works.**

**Side effect confirmed**: creating one `requirement-coverage` row **automatically creates exactly
one `test-config-coverage` row** (query it by `first-endpoint-id` = the coverage row's own id — NOT
by `requirement-id`, which doesn't exist as a filterable field on `test-config-coverages`) `[probe]`.
`test-config-coverages` itself is fully **documented** (`first-endpoint-id → requirement-coverages
row`, `second-endpoint-id → test-configs row`) `[docs-research]` (wave1-04 §5).

### 6.3 Traceability (requirement ↔ requirement)

**`req-traces` POST — VERIFIED**, resolving wave1-03's "no REST surface found" gap entirely. Fields:
`from-req-id` (Number, required), `to-req-id` (Number, required), plus auto-populated `owner`
(current user), `creation-date`, `comment`, `id`.

```json
{"Fields":[{"Name":"from-req-id","values":[{"value":"<req1-id>"}]},
           {"Name":"to-req-id","values":[{"value":"<req2-id>"}]}],
 "Type":"req-trace"}
```
→ **HTTP 201** `[probe]` (probe4-write-round-1.md §7). Full CRUD is also present in resource-list
`[resource-list]`.

### 6.4 Tests & design-steps

**Test create — VERIFIED.** Root discovery: `test-folders?query={parent-id[0]}&fields=id,name,parent-
id` returned exactly one top-level folder on our project, **id=2, name="Subject"** — confirming the
"Subject" tree root is project-specific and must be discovered at runtime, never hardcoded `[probe]`
(probe4-write-round-1.md §3). Create:

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"<folder-id>"}]},
           {"Name":"subtype-id","values":[{"value":"MANUAL"}]}],
 "Type":"test"}
```
→ **HTTP 201**, ~44 fields returned incl. `steps=0`, `exec-status="No Run"`, `configurations-count=1`.
Subtype enumeration is runtime-discoverable via `.../customization/entities/test/types` (18 types
observed: MANUAL, QUICKTEST_TEST, BUSINESS-PROCESS, …) `[probe]` (probe3-mining-fieldtypes.md).

**`design-steps` POST — VERIFIED, contradicts prior doc reading.** The Core static doc marks
POST/PUT/DELETE "Not applicable" on the `design-steps` page `[docs-research]` (wave1-04 §3) — **this
is disproved on-server**: `POST .../design-steps` with `parent-id` = the owning test's id → **HTTP
201**. No nested `tests/{id}/design-steps` path is needed `[probe]` (probe4-write-round-1.md §4).
Returned fields: `step-order, vts, ver-stamp, attachment, has-params, expected, vc-user-name, name,
description, id, link-test, parent-id`. Note the sibling-order field is `step-order`, not `order-id`
(different from test-folder/requirement).

```json
{"Fields":[{"Name":"name","values":[{"value":"Step 1"}]},
           {"Name":"parent-id","values":[{"value":"<test-id>"}]},
           {"Name":"description","values":[{"value":"<html><body>…</body></html>"}]},
           {"Name":"expected","values":[{"value":"<html><body>…</body></html>"}]}],
 "Type":"design-step"}
```

**⚠️ Sanitizer token-mangling caveat — VERIFIED, reconfirmed on a second probe.** An embedded
`<<<probe_param>>>` parameter placeholder token is **not preserved**: the HTML sanitizer parses
`<probe_param>` as a tag and strips its tag-name content, leaving only `<<>>` (HTML-entity-encoded on
readback: `&lt;&lt;&gt;&gt;`) `[probe]` (probe4-write-round-1.md §4, re-observed identically in
`tests/fixtures/write-probe/r2-designstep-token-roundtrip.txt` — "Uses &lt;&lt;&gt;&gt; here"). The
design-step's `has-params` flag still returns `"Y"`, suggesting server-side parameter registration
happens on the raw pre-sanitized input, but the stored/readable description text loses the parameter
name — **naive string concatenation of `<<<name>>>` tokens into a memo field via REST does not
work as a parameter-authoring mechanism.**

**RESOLVED (round 2): HTML-entity-pre-encode the token.** Sending
`&lt;&lt;&lt;name&gt;&gt;&gt;` instead of the raw token passes the sanitizer intact — the token
survives round-trip and flips `has-params=Y` `[probe]` (live-probe-log.md Probe 5). This is the
required client-side authoring convention for parameter tokens in step text.

**⚠️ RETRACTED 2026-08-13 (Probe 9) — the "genuine gap" conclusion below was wrong.** It was a shape
bug (`step-parameters.parent-id` was passed the design-step/test id instead of a `test-parameter` id)
compounded by a missed second collection, `test-parameters`, that earlier rounds never probed because
documentation research had asserted no REST entity for test parameters existed at all. The original
finding is kept below for the record; the corrected model and working recipes follow immediately after.

**`step-parameters` — FAILED after 2 informed attempts, documented failure not a shape bug** *(superseded — see corrected model below)*. Real
fields (from `customization/entities/step-parameter/fields`): `actual-value`(Memo),
`ignore-test-instance-parameters`(String), `origin-test`(Number), `id`(Number),
`used-by-owner-id`(Number), `key`(String), `used-by-owner-type`(String, REQUIRED), `parent-id`(Number),
`vc-user-name`(UsersList). Both attempts (`used-by-owner-type=design-step` and `=test`) returned
**HTTP 500** `{"Id":"qccore.general-error","Title":"Test parameter does not exist"}` `[probe]`
(probe4-write-round-1.md §5). **Conclusion**: `step-parameters` is a "record a value against an
already-registered parameter" endpoint, not a "define a new parameter" endpoint. **Round 2 confirmed
this is a genuine gap, not a shape bug**: every attempted shape — including after entity-encoded
tokens successfully flipped `has-params=Y` — fails with the same "Test parameter does not exist"
`[probe]` (Probe 5). **There is no REST path to *define* a test parameter object** (full CRUD exists
per resource-list `[resource-list]` in design-steps/{id}, runs/{id}, test-configs/{id},
test-instances/{id} nested contexts, but presence ≠ working create). ~~**OTA-fallback candidate**
(OTA `TestParameterFactory`); see §9.~~ **Not needed — see below.**

**Corrected model (Probe 9): two entities, not one.** `test-parameter` (physical `TP_*`, collections
`test-parameters` and `tests/{testId}/test-parameters`) **defines** the parameter on a test — 11
fields; `name` (String) is the only Required one, `default-value` (Memo), `description` (Memo) and
`order` (Number) are editable, and `id`, `parent-id` (`TP_TEST_ID`), `ref-count`, `is-mapped`, `vts`,
`vc-user-name`, `ver-stamp` are metadata-read-only. `step-parameter` (physical `SP_*`, field set as
above) **records a value** against an already-defined `test-parameter` — its `parent-id`
(`SP_TEST_PARAM_ID`) must be the **`test-parameter` id**, not the design-step/test id; passing the
owner id there is exactly what produced the "Test parameter does not exist" 500 above `[probe]`
(live-probe-log.md Probe 9, §9.1).

**Two working creation routes for `test-parameter`:**

*Route A — direct create (preferred; deterministic, no text parsing):*

```json
POST tests/{testId}/test-parameters
{"Fields":[{"Name":"name","values":[{"value":"my_param"}]},
           {"Name":"ref-count","values":[{"value":"0"}]}],
 "Type":"test-parameter"}                                          -> HTTP 201
```
`parent-id` is read-only, so the owning test comes from the **URL**. The flat `POST test-parameters`
form also works when both `parent-id` and `ref-count` are in the body. `order` may be set explicitly;
otherwise the server auto-assigns the next ordinal.

*Route B — token registration (matches the stock UI's authoring flow):* a design step whose
`description` contains an entity-encoded `&lt;&lt;&lt;name&gt;&gt;&gt;` token registers the parameter
as a side effect, identically to OTA: `GET tests/{testId}/test-parameters` then shows
`TotalResults=1`, `name` matching the token, `ref-count=1`. **This answers Q34: yes — the REST token
registers a real parameter object.** The entity-encoded form survives round-trip with the token name
intact; a **raw** `<<<name>>>` token is still mangled to `<<>>` by the sanitizer — that caveat above
stands unchanged. Registered parameters have **independent lifetime**: they are not cascade-deleted
when the step that registered them is removed.

**⚠️ NEW WRITE HAZARD — metadata `editable:false`/`required:false` does not mean "omit from write
body".** `POST test-parameters` without `ref-count` fails: `HTTP 500 {"Id":"qccore.general-error",
"Title":"failed converting entity test-parameter to FREC, request is missing required field
TP_REF_COUNT"}` — even though `customization/entities/test-parameter/fields` reports `ref-count` as
`editable:false, required:false`. Sending it anyway (`{"name":"…","ref-count":"0"}`) succeeds, 4/4
shapes tried. **Rule for the BFF's write-safety component: on a 500 naming `missing required field
<PHYSICAL_NAME>`, retry once with that field included regardless of what metadata says.** This is a
second instance of the class of trap in §3.2 (metadata does not fully describe what a write needs) —
see §3.6 `[probe]` (live-probe-log.md Probe 9, §9.3).

**`step-parameters` create — WORKS**, retracting the failure above, once `parent-id` is the
`test-parameter` id, for both `used-by-owner-type=design-step` and `=test`:

```json
POST step-parameters
{"Fields":[{"Name":"used-by-owner-type","values":[{"value":"design-step"}]},
           {"Name":"used-by-owner-id","values":[{"value":"<design-step id>"}]},
           {"Name":"parent-id","values":[{"value":"<TEST-PARAMETER id>"}]},
           {"Name":"actual-value","values":[{"value":"<html><body>runtime-value</body></html>"}]}],
 "Type":"step-parameter"}                                          -> HTTP 201
```
`actual-value` is a Memo and round-trips through the same sanitizer as any other memo field. Verified
read-back via `GET step-parameters/{id}`.

**Default values — REST can do what OTA cannot.** `PUT test-parameters/{id}` with `default-value` →
**HTTP 200**, value reads back intact. OTA's `Params` collection raises `Invalid field type
definition` on the equivalent operation (Probe 8 left this UNVERIFIED) — the REST route simply works
and is the one to use. The field is `default-value`; there is no `value` field
(`qccore.unknown-field-name` if tried).

**Observed, cause unconfirmed:** `DELETE design-steps/{id}` returned HTTP 500 for a step that had a
`step-parameter` referencing it (parameters remained afterwards; deleting the parent test then cleaned
everything up, 200). Likely a referential-integrity ordering constraint. `UNVERIFIED` as a cause; the
workaround (delete `step-parameters` before their owning design step, or delete the parent test) is
verified `[probe]` (live-probe-log.md Probe 9, §9.8).

`[probe]` (live-probe-log.md Probe 9, §9.1–9.7; `scripts/probe/probe-write-4.ps1`, `-4b.ps1`,
`probe-ota-7-paramcheck.ps1`). An OTA cross-check (`Params.Count`, names) confirmed the REST-created
objects are real. **OTA is no longer needed for parameter definition or values** — see the corrected
§9 gap-table entry.

### 6.5 Defects & defect-links

**Defect create — VERIFIED** (after fixing a local probe-script bug unrelated to the API — resolving
severity-list lookup). Minimum: `name`, `detected-by`, `creation-time`, `severity` (a real list value,
e.g. `"1-Low"` — an empty-string severity produces a 500/400 depending on how it's empty, not a
graceful validation):

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"detected-by","values":[{"value":"<current-user>"}]},
           {"Name":"creation-time","values":[{"value":"2026-08-12"}]},
           {"Name":"severity","values":[{"value":"1-Low"}]}],
 "Type":"defect"}
```
→ **HTTP 201** `[probe]` (probe4-write-round-1.md §8). Standard field set (Status: Closed/Fixed/New/
Open/Rejected/Reopen, default New; Severity 1–5; Priority 1–5) per `[docs-research]` (wave1-06 §1) —
**no REST-native state machine**; status transitions are enforced only by project workflow scripts,
which **REST writes bypass by default** (`CLIENT_TYPES_BYPASS_REST_WF`, see §6.11) — a REST client can
set any `status` value out of the box.

**`defect-links` — VERIFIED for both endpoint-type variants.** Fields: `first-endpoint-id`,
`second-endpoint-id`, `second-endpoint-type`. Defect↔defect is **non-directional** (doc: "no
importance to which defect is first vs second") `[docs-research]` (wave1-06 §2).

```json
{"Fields":[{"Name":"first-endpoint-id","values":[{"value":"<defect1-id>"}]},
           {"Name":"second-endpoint-id","values":[{"value":"<defect2-id>"}]},
           {"Name":"second-endpoint-type","values":[{"value":"defect"}]}],
 "Type":"defect-link"}
```
→ **HTTP 201**, with `second-endpoint-name`/`owner`/`creation-time` auto-populated;
`second-endpoint-status` empty for a defect target. Repeating with
`second-endpoint-type":"requirement"` → **HTTP 201**, and here `second-endpoint-status` returned the
requirement's coverage status ("No Run") and `second-endpoint-name` the requirement's name — confirming
`defect-links` denormalizes the second endpoint's display fields per `second-endpoint-type` regardless
of which entity kind it points at `[probe]` (probe4-write-round-1.md §9). Which other
`second-endpoint-type` values are valid (test? run? test-instance?) is `UNVERIFIED` beyond `defect` and
`requirement`.

Similar-defects is **OTA-only, no REST resource** (`resource-list.html` explicitly disclaims
undocumented resources as unsupported) `[docs-research]` (wave1-06 §3).

### 6.6 Attachments (including the rich-text image-embed answer)

**Resource shape**: `.../{entity-collection}/{entity-id}/attachments` (collection),
`.../attachments/{name-or-id}` (member, `?by-id=true` switches lookup to numeric id — **Core only**,
not valid on the Deprecated API) `[docs-research]` (wave1-08 §1).

**Two documented POST forms**: `multipart/form-data` (parts `filename`, `file` — **must be last part**
—, optional `description`/`override-existing-attachment`/`ref-subtype`) or `application/octet-stream`
+ `Slug: <filename>` header (raw bytes, **cannot carry `ref-subtype` or description**) `[docs-research]`
(wave1-08 §1). `ref-subtype`: 0 = not rich content; **1 = "rich content," the documented mechanism for
linking from a `req-rich-content`-style field.**

**17 of the per-collection attachment-POST forms are `[DEPRECATED]`** (§5) — the plain
`POST .../{collection}/{id}/attachments` form for bpm-folders, design-steps, environments, defects,
milestones, releases, release-cycles, release-folders, requirements, runs, run-steps, test-configs,
tests, test-folders, test-instances, test-sets, test-set-folders `[resource-list]`.

**Image-embed `<img src>` syntax — RESOLVED by write rounds 2–3** `[probe]` (live-probe-log.md
Probes 5–6; fixtures `tests/fixtures/write-probe/r2-imgsrc-*.txt`, `r3-*`). In round 2 an attachment
was uploaded via octet-stream+`Slug` (`ref-subtype=0`); four `<img src>` forms were then PUT into a
requirement's rich-text field and read back:

| `src=` form sent | Result on readback |
|---|---|
| Plain filename (`probe-img-multi.png`) | **`src` attribute stripped entirely** — `<img />` |
| Relative path (`attachments/probe-img-multi.png`) | **`src` attribute stripped entirely** — `<img />` |
| Full absolute REST URL (`https://…/qcbin/rest/domains/…/requirements/7/attachments/probe-img-multi.png`) | **Survives intact**, byte-identical |
| `data:image/png;base64,…` URI | **Survives intact**, byte-identical |

`[probe]`. This is consistent with the sanitizer's documented protocol-allowlist behaviour (wave1-08
§2 pitfall #4: "non-http(s) `img src` gets the attribute stripped, not the tag rejected") — a
plain filename or relative path has no recognizable protocol and is treated as disallowed, so the
`src` attribute is dropped outright while the `<img>` tag itself survives.

**Multipart `ref-subtype=1` upload — WORKS (round 3)**: a hand-built multipart body (explicit
boundary, CRLF discipline, text parts first, `file` part LAST with `Content-Type: image/png`) →
**201, 3/3 sessions** `[probe]` (Probe 6). Round 2's failure was a PowerShell `-Form` constructor
artifact, not a server limitation — some HTTP client libraries build multipart bodies this server
rejects, so treat multipart construction as a compatibility risk to integration-test per stack.

**Actionable conclusion for the generator/UI**: the full embedded-image flow is **end-to-end viable**
— upload the image as a multipart attachment with `ref-subtype=1`, then PUT the memo HTML with
`<img src>` set to either (a) the **full absolute REST attachment URL**, or (b) a `data:` URI.
**Still open**: whether `IMAGE_COMPRESSION_LEVEL` (new 25.1) re-encodes the bytes server-side, and
whether the stock UI's own generated `src` differs from either tested form. `UNVERIFIED`, see §9.

**Size-limit site parameters** (not independently probed): `ATTACH_MAX_SIZE`/`ATTACH_TOTAL_MAX_SIZE`
govern **emailed** attachments only, not uploads (do not conflate) `[docs-research]`;
`UPLOAD_ATTACH_MAX_SIZE`/`UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE` govern general/memo-image uploads
respectively, exact defaults `UNVERIFIED`; `DAYS_TO_KEEP_IMAGE_FILES` default 30 (client cache
retention); `IMAGE_COMPRESSION_LEVEL` new 25.1.

### 6.7 Releases & cycles

**Release create — VERIFIED**, resolving wave1-06's field-name uncertainty. Field names `start-date`
and `end-date` confirmed literally (not `start_date`/`begin-date`/other guesses):

```json
{"Fields":[{"Name":"name","values":[{"value":"…"}]},
           {"Name":"start-date","values":[{"value":"2026-01-01"}]},
           {"Name":"end-date","values":[{"value":"2026-03-31"}]},
           {"Name":"parent-id","values":[{"value":"1"}]}],
 "Type":"release"}
```
→ **HTTP 201**, response includes `req-count`, `scope-items-count`, `milestones-count` (all 0 on a
fresh release), `has-attachments`, `ver-stamp` `[probe]` (Probe 5;
`tests/fixtures/write-probe/r2-release-create.json`). `release-folders`/`releases`/`release-cycles`
all follow the standard GET/POST-collection + GET/PUT/DELETE-member pattern with
`force-delete-children=y|n` on folder deletes (default `n` relocates children to "Unattached")
`[docs-research]` (wave1-06 §4–5).

**Cycle date validation — VERIFIED**: a `release-cycle` whose dates fall outside the parent
release's window is **rejected server-side** (500 with a well-formed message); an in-range cycle
creates fine `[probe]` (Probe 5). The generator must derive cycle dates from the parent release's
window, never independently.

**Milestones — create VERIFIED**: `milestones` full CRUD is real, but the `parent-id` field's
physical name is `MS_RELEASE_ID` — **a milestone is parented under a release**, not a folder
`[probe]` (Probe 5; `r2-milestone-create.json`). This partially flips the earlier "PPT/milestones
NOT-VIA-API" belief; PPT scope items and KPIs remain absent from REST.

### 6.7b Test Lab: test-sets, test-instances, runs — write rounds 2–3 verified

**Creation chain (all 201, VERIFIED)** `[probe]` (Probes 5–6): `test-set-folder` → `test-set`
(`subtype-id=hp.qc.test-set.default`) → `test-instance` (initial `status` "No Run",
`subtype-id=hp.qc.test-instance.MANUAL`). Legacy naming trap throughout: **`cycle-id` = test-set
id, `testcycl-id` = test-instance id** (disambiguated live via the Fast_Run entity's name fields,
fixture `r3-fastrun-full-entity.json`).

**⚠️ Direct `POST runs` does NOT work on this server — definitive negative.** 8 attempts across
rounds 2–3 (XML + 5 JSON field-set variants); failure is bimodal and reproducible: baseline
variants → `"Fail to get a must number attribute 'TESTSET'"` (no run field maps to that physical
name — 48-field dump checked); adding denormalized name fields (`test-name`/`testcycl-name`/
`cycle-name`) → `"Failed to post step"` `[probe]` (Probes 5–6).

**The working route is indirect (Fast_Run synthesis) — VERIFIED 3/3 sessions**: `PUT
test-instances/{id}` with a `status` value makes the server synthesize a **`Fast_Run`** run record
(`subtype-id=hp.qc.run.MANUAL`, name `Fast_Run_…`). Alt-ALM and the generator must create runs via
this route. Verified properties of synthesized runs `[probe]` (Probe 6):

- **Run-steps auto-copy from design steps** (count matches design-step count exactly).
- **Instance status mirrors run status** (instance reads "Passed" after the run PUT).
- **No eager run-step→run status aggregation**: flipping a run-step to Failed leaves the parent
  run's status unchanged (caveat: observed after a force-set Passed — shows no auto-recompute, not
  an exhaustive matrix).
- Run-steps on the synthesized run are PUT-able (`runs/{id}/run-steps/{sid}`).

**`test-executions` POST = DISPATCH, not ingest — VERIFIED**: the POST reaches execution scheduling
logic and answers "There is no agent configured…" — it schedules execution against lab
infrastructure; it does not create/ingest run records `[probe]` (Probe 5).

**BPT is out of REST reach here**: `GET /components` → **403 `qccore.operation-forbidden`**
(endpoint exists, license/permission-gated); `GET /business-components` → **404** `[probe]`
(Probe 6). OTA-fallback candidate.

### 6.8 Customization/metadata endpoints

All GET-only except `customization/users/{name}` (PUT) and the 24.1 list-item write endpoints
(Swagger-only) `[docs-research]` (wave1-07). Confirmed live on 15 entity types
(`customization/entities/{entity}/fields` → `{"Fields":{"Field":[…]}}`) `[probe]` (Probe 2). Full
descriptor attribute set: `Name`/`name`, `PhysicalName`/`physicalName`, `Label`/`label`, `Size`/`size`,
`History`/`history`, `Required`/`required`, `System`/`system`, `Type`/`type`, `isTime`/`time`,
`Verify`/`verify`, `Virtual`/`virtual`, `Active`/`active`, `Editable`/`editable`,
`Filterable`/`filterable`, `Groupable`/`groupable`, `SupportsMultivalue`/`supportsMultivalue`,
`Visible`/`visible`, `Searchable`/`searchable`, `VersionControlled`/`versionControlled`,
`VisibleInWebUI`/`visibleInWebUI`, `Description`/`description`, `CanChangeRequired`/
`canChangeRequired`, `List-Id` (if list-bound) `[docs-research]` (wave1-07 §1). UDFs: `user-NN`/
`XX_USER_NN` naming, ≤99 per entity, memo UDFs capped at 5 (15 with `EXTENDED_MEMO_FIELDS=Y`)
`[docs-research]` (wave1-07 §3). Lists via `used-lists` (39 on our project) / `lists` (43) — the 4-list
delta is lists defined but not bound to any field (Activity Status id 255, VC Status id 82, Resource
Type id 285, TestType id 320) `[probe]` (probe3-mining-fieldtypes.md §3). **List-Ids are
instance-specific — never hardcode.**

**24.1+ list-item write endpoints** (only formally documented v2-Swagger surface for project
customization): `POST/PUT/DELETE .../customization/used-lists/{list-id}/items(/{item-id})`, body is
the bare `Item` object (`{"value": "…"}` — id/logicalName server-assigned) `[swagger]`
(probe3-mining-swagger §1.2). `DELETE .../{entity-name}/versioningHistory` (purge VC history) — body
`{"purgeMode": "date"|"version", "offSet": "<date-or-count>"}`; the doc's own description says
**"currently only 'test' is supported"** for `entity-name` `[swagger]`.

**Workflow bypass — critical, doc-quoted**: *"By default, advanced project scripts apply to Web Client
only. If you want to apply them to all your applications that use ALM REST API, change the
`CLIENT_TYPES_BYPASS_REST_WF` site parameter to None."* `[docs-research]` (wave1-07 §9). **REST writes
bypass workflow-script validation by default** on this instance family — the generator gets free status
setting but receives **no server-side auto-population of derived values**; it must synthesize
realistic derived values itself.

### 6.9 Site Admin API essentials

Root `/qcbin/v2/sa/api/...`, live Swagger at `/qcbin/api-doc/sa/v2/qc.json` (178 ops, confirmed
reachable `[probe]` Probe 3). Essentials for Alt-ALM/generator sandbox seeding:

- **`site-users`** full CRUD (`GET/POST/PUT/DELETE /site-users(/{userName})`, activate/deactivate/
  unlock/policy) — enables **automated dummy-user creation** for the sandbox, which currently has only
  1 project user (generator UsersList realism blocker) `[probe]` (Probe 3) `[swagger]`.
- **Project users**: `POST .../domains/{d}/projects/{p}/users` body `{"name":"…"}` adds an *existing*
  site user to the project (does not create one) `[swagger]` (probe3-mining-swagger §2.2).
- **`site-params`** full CRUD (`GET/POST/PUT/DELETE /site-params(/{param})`); `GET
  /site-params/{param}?accept-default-value=true` falls back to the metadata default if unset
  `[swagger]`.
- **`site-version`** → `{patch-level, external-version, major-version, minor-version,
  minor-minor-version, build-version, full-version}` — the source of the 26.1 confirmation in §1
  `[probe]` `[swagger]`.
- **28 of 178 SA operations are `SAAS_ONLY`-flagged** (§1) — most notably `GET /audits`,
  `/audits/export`, `/audits/metadata`, `/permissions/metadata`, most of `/customers/*`,
  `/orphan-users`, `GET /roles/{roleId}` `[swagger]` (probe3-mining-swagger §2). `GET /permissions`
  (current user's own role) is **not** SaaS-gated.
- `run-query`/`run-query/export` = **raw SQL execution** — explicitly out of mainline scope per
  CLAUDE.md's documented-REST-only hard constraint; risk-register entry only, never an implementation
  path `[swagger]`.

---

## 7. Rich text

**Storage format**: memo fields store a **complete HTML document**, `<html><body>…</body></html>`, not
a fragment — confirmed both by a primary doc worked example and directly by probe. XML carries it
entity-encoded inside `<Value>` (standard XML escaping, not CDATA); JSON carries it as a plain string
`[docs-research]` (wave1-08 §2) `[probe]` (probe4-write-round-1.md §2).

**Sanitizer behaviour, observed directly (VERIFIED)** — sending a torture HTML block and reading it
back on a requirement's `description`/`req-rich-content` fields:

- `<script>alert(1)</script>` is **stripped entirely**.
- `<table>` gains an **implicit `<tbody>`** wrapper around `<tr>` that wasn't in the input
  (HTML-tidy-style normalization).
- **Whitespace/newlines are inserted** around block-level tags (`<body>`, `<ul>`, `<li>`, `<table>`,
  `<tr>`, `<td>`, `<div>`) — pretty-printing, not byte-for-byte storage. Inline tags (`<b>`, `<i>`,
  `<u>`, `<font>`, `<a>`, `<span>`) are **not** reformatted internally.
- `<font color>`, inline `style=`, and `href` **survive intact**.
- Already-double-escaped entity text is preserved as literally typed, not re-decoded/re-encoded.
- `has-rich-content` **flips N→Y** on write.

`[probe]` (probe4-write-round-1.md §2). **Conclusion for the generator: rich-text fields are not a
byte-for-byte store.** Round-trip fidelity tests must tolerate whitespace/pretty-print normalization,
implicit `<tbody>` insertion, and `<script>` removal — compare *canonicalized* HTML, not raw bytes.
`<style>` as a bare top-level tag was **not tested** (only inline `style=` attributes, which survived)
— `UNVERIFIED`. Inline event-handler attributes (`onclick=`) were **not tested** — `UNVERIFIED`.

**Sanitization mechanism** (doc-level, corroborating the probe): global switch
`ENABLE_OUTPUT_SANITIZATION` (default Y); per-field mode (Do nothing / Text encoding / HTML
sanitization) set in Project Customization; whitelist file `sanitizer-whitelist.xml`, **deployment-
specific — no universal allowed-HTML-subset exists** `[docs-research]` (wave1-08 §2). Whether
sanitization happens at write time, read time, or both is not distinguishable from a round-trip test
alone — `UNVERIFIED`.

**Image embed — see §6.6 for the full resolved answer.** Summary: upload via hand-built multipart
with `ref-subtype=1`; `<img src>` must be either a full absolute REST attachment URL or a `data:`
URI; a bare filename or relative path silently loses its `src` attribute (tag survives, attribute
doesn't) `[probe]` (live-probe-log.md Probes 5–6).

---

## 8. Field-type system

**Exactly 8 field-type identifiers exist, confirmed on 15 entity types (432 total fields probed)**:
`String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`, `Reference` `[probe]`
(Probe 2, cross-checked against a doc-research finding of the identical 8 identifiers from an
independently-fetched worked example — `[docs-research]` wave1-07 §2 explicitly notes "matches our
live probe exactly"). **No Float, TreeNode, or Boolean type exists anywhere.**

**No-Boolean finding**: yes/no semantics are encoded three ways `[probe]` (probe3-mining-fieldtypes.md
§1):
- `LookupList` bound to list id **1** ("YesNo": `Y`/`N`) — used for editable flags like
  `rbt-ignore-in-analysis`, `rbt-use-custom-*`.
- Plain `String` with flag-like naming (`attachment`, `has-rich-content`, `has-linkage`) — almost
  always **read-only/system/virtual**, not user-editable.
- `Number` for computed counters (`istemplate`, `no-of-sons`).

80 such flag-like fields were catalogued across the 15 entities; the large majority are
read-only+system — the generator must treat each flag field individually, not via a uniform Boolean
strategy.

**List bindings**: 77 LookupList/UsersList fields across 11 entities (none in release, release-cycle,
release-folder, test-folder, test-set-folder, test-config). Heaviest: requirement (31, incl. the full
`rbt-*` risk-level lattice), test (11), defect (11). **List IDs are per-instance — never hardcode**
`[probe]` (probe3-mining-fieldtypes.md §2).

**Multivalue rarity**: only **2 fields in the entire probed model support `SupportsMultivalue=true`**
— both `Reference` type, both on `requirement`: `target-rel` and `target-rcyc`. **No LookupList or
UsersList field supports multivalue** (all 77 are single-value) `[probe]` (probe3-mining-fieldtypes.md
§5) — a real constraint on generator logic: a requirement can multi-link releases/cycles, but no
other multi-select-from-list pattern exists anywhere in the probed model.

**System/read-only counts**: **191 fields across all 15 entities are `Editable=false` AND
`System=true`** — concentrated in `test` (38) and `requirement`/`run` (36/25) `[probe]`
(probe3-mining-fieldtypes.md §4). These are non-negotiable: `id`, all `vc-*`, all `has-*`,
`last-modified`, and the "virtual" computed-path fields (`father-name`, `tree-path`, `folder-name`,
denormalized `*-name` fields on `run`) — the latter carry a distinctive **`Size=99999`** sentinel
(virtual-truncation flag), while all Memo fields uniformly carry **`Size=-1`** (unlimited).

---

## 9. Gaps & unverified table

### Resolved since the first draft (write rounds 2–3, live-probe-log.md Probes 5–6)

- **Run creation**: direct `POST runs` definitively fails; the Fast_Run synthesis route via
  `PUT test-instances/{id}` is the working method → §6.7b.
- **Run-step auto-copy**: VERIFIED (design steps copy into run-steps on run synthesis) → §6.7b.
- **Run-status aggregation**: VERIFIED absent (no eager step→run recompute) → §6.7b.
- **Fast_Run synthesis on instance-status PUT**: VERIFIED — and promoted from "pitfall to avoid"
  to "the only run-creation route" → §6.7b.
- **Milestones**: create VERIFIED, parented under a release (`MS_RELEASE_ID`) → §6.7.
- **`test-executions` POST**: VERIFIED dispatch, not ingest → §6.7b.
- **Multipart `ref-subtype=1` upload**: WORKS with a hand-built body; embedded-image flow
  end-to-end viable → §6.6.
- **Release-cycle date validation**: VERIFIED server-enforced against the parent release window → §6.7.
- **`<<<param>>>` tokens in step text**: survive when HTML-entity-pre-encoded → §6.4.
- **BPT**: `components` 403 license-gated, `business-components` 404 — OTA-only here → §6.7b.
- **`test-parameters`/`step-parameters` create (Probe 9, 2026-08-13)**: RETRACTS the "genuine gap, no
  REST path to define a test parameter" conclusion carried since Probes 4–5. A separate, never-probed
  `test-parameters` collection defines the parameter object; the `step-parameters` failure was a shape
  bug (`parent-id` needed the `test-parameter` id, not the design-step/test id). Both entities, both
  creation routes, and `PUT .../default-value` are now VERIFIED working over REST → §6.4.

### Still open

Everything still `UNVERIFIED` or confirmed **absent**, each with the experiment that would settle it.
"Absent" rows are confirmed by zero hits across a 1,111-operation resource-list inventory — a strong
but not airtight negative (the inventory has known false negatives elsewhere, §9 footnote).

| Area | Status | Experiment to settle it |
|---|---|---|
| **Timeslots** | Confirmed absent from resource-list `[resource-list]`; OTA-only per doc | Search live Swagger + resource-list again on a future OpenText release; if still absent, OTA `Host`/`HostTimeOut` objects are the only path |
| **Libraries / baselines** | Confirmed absent (doc-host probes for `libraries`/`baselines`/`vc-*` variants all 404; zero resource-list hits) `[docs-research]` `[resource-list]` | Re-check resource-list after any future upgrade; 26.1 What's New mentions "Libraries and Baselines" as a *product* feature (wave1-09) with no REST surface identified — re-probe after clarifying whether that's UI-only |
| **Alerts / alert rules** | Confirmed absent (`alerts.html`/`alert-rules.html` 404; zero resource-list hits) `[docs-research]` | Capture stock-UI network traffic while creating/triggering an alert rule |
| **Follow-up flags** | No dedicated source located; may be UI decoration on alert state only | Same UI-traffic capture as alerts |
| **Purge-runs** | Confirmed absent as a REST endpoint; only per-id `DELETE runs/{id}` exists | Re-check resource-list/Swagger; UI-only `PurgeRunsTask` background job has no documented trigger endpoint |
| **`step-parameters` create** | ~~FAILED live, twice (rounds 1–2) — every shape returns 500 `"Test parameter does not exist"`, even after entity-encoded tokens registered `has-params=Y`; no REST path to define the parameter object exists `[probe]` (Probes 4–5)~~ **RESOLVED, Probe 9 (2026-08-13):** the "Test parameter does not exist" error was literally true — a `test-parameter` had to exist first, via the missed `test-parameters` collection, before `step-parameters.parent-id` (which needed the *parameter's* id, not the owner's) would resolve. Both entities now VERIFIED working over REST → §6.4 | **Done — no further experiment needed.** OTA fallback is not required for parameter definition or values. |
| **Audit/history partial coverage** | **VERIFIED partial**: `GET requirements/{id}/audits` returned only 2 entries (both `status` field changes) for a requirement that had a create + 2 rich-text PUTs + a coverage link — creates and memo PUTs produced **no** audit entry `[probe]` (probe4-write-round-1.md §10) | Isolate per-field: PUT a plain (non-memo) editable field and check for an audit entry, to determine if the gap is memo-specific or coverage extends only to derived/computed fields like `status` |
| **`alm-web` dialect body shape** | Media type identified on 42 ops (§3.5), never requested | `GET` one `groups/{groupsFields}` endpoint with `Accept: application/json;schema=alm-web` and diff against the plain-JSON response |
| **`/mail` POST (19 entity types)** | **Probed, FAILED** — 3 JSON shapes → identical opaque NPE; 1 XML shape → different 400. Body format genuinely undocumented `[probe]` (Probe 5) | Capture the stock UI's send-by-email request body, or accept Alt-ALM sending its own mail (already the plan per wave2-05) |
| **`test-config`/`test-criterion`-coverages full CRUD** | GET/POST-config-coverage side effect confirmed (§6.2); PUT/DELETE and criterion-level never probed | Direct CRUD probe |
| **`bv-hosts`/`host-groups` CRUD** | Present per resource-list, zero probes | Direct CRUD probe; establishes whether host/lab management is REST-reachable at all despite timeslots being absent |
| **Image pipeline residuals** | Upload + embed both VERIFIED (§6.6); untested: server-side re-encoding via `IMAGE_COMPRESSION_LEVEL` (25.1+), and whether the stock UI generates a different `src` form | Round-trip a byte-comparable PNG with `IMAGE_COMPRESSION_LEVEL` set; capture a stock-UI image embed and diff its `src` |
| **Cross-project / cross-instance consistency of every probe finding above** | Everything in this document is single-sandbox, single-version evidence | Re-run the write-probe script against a second ALM 26.1 project/instance if one becomes available, before hardening any probe-only finding into a permanent client assumption |

**Footnote on resource-list reliability**: the 1,111-operation `resource-list` inventory has **known
false negatives** — the v2 Swagger doc's own newest operations (used-list item-level CRUD,
`purgeVCHistories`) do not appear in it at all, despite being real and independently confirmed
reachable `[resource-list]` (probe3-mining-swagger §4, "Notable inversion"). Treat resource-list
presence as **necessary-but-not-sufficient** evidence an endpoint exists, and absence as
**suggestive-but-not-proof** that it doesn't — no false positives were found, only false negatives.

---

## Provenance-flagged loose ends carried from source documents (not resolved here)

- Exact header/body-element mechanics for the "client type" Base64-suffixed `QCSession` — two
  primary doc pages describe it inconsistently (header vs. XML body element) `[docs-research]`
  (wave1-01 UNVERIFIED #1).
- Exact name of the site parameter that bypasses XSRF validation for specific client types —
  alluded to, never named, in primary docs `[docs-research]` (wave1-01 UNVERIFIED #3).
- Any documented rate limit / request timeout / payload-size ceiling beyond `MAX_REQUEST_LENGTH` and
  login-lockout parameters — genuine documentation gap across every source consulted
  `[docs-research]` (wave1-01 §10, wave1-02 handoff).
- Whether SaaS vs on-prem differ in session-timeout defaults, XSRF enforcement, or error envelope
  shape — absence of evidence either way, not confirmed identical `[docs-research]` (wave1-01
  UNVERIFIED #8).
