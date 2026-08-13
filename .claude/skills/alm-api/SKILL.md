---
name: alm-api
description: OpenText ALM/Quality Center REST API (Core/Deprecated/v2/Swagger) — auth, envelopes, query grammar, bulk ops, error codes, verified call recipes. Load first in any session that touches ALM.
---

Target: OpenText ALM/Quality Center classic (`/qcbin`), NOT Octane. Sandbox ground truth: **ALM 26.1**
(internal `SiteVersion 20.0`), SaaS-flavored. Full detail: `docs/research/alm-api-reference.md`,
`docs/research/live-probe-log.md` (probe log wins all conflicts).

## 1. Load-bearing hazards (read this before writing any code)

1. **Deterministic `Fields`-array order is a hard requirement, not style.** The JSON write shape is
   `{"Fields":[{"Name":..,"values":[{"value":..}]}],"Type":".."}`. Sending the *same logical fields* in
   different member order produced different results on identical data — e.g. `parent-id,name,type-id`
   → `500 Object.hashCode() key is null`; `name,parent-id,type-id` → `201 created`. Never build the
   array from a plain dict/hashmap whose iteration order is unspecified. Use an ordered structure
   (`[ordered]@{}` in PowerShell) with a fixed convention: `name` → relational ids → type/subtype fields
   last [api-ref §3.2].
2. **HTTP 5xx on a write is not proof of failure.** One 500 response during probing had silently
   committed the row server-side (discovered via a `father-name` cross-reference later, confirmed by
   GET, then deleted). Treat every 5xx write response as "unknown outcome — verify by GET before
   retrying," never as "failed." Well-formed business-validation 500s that name the bad value seem
   lower-risk but this is not proven [api-ref §3.3].
3. **`X-XSRF-TOKEN` header required on every non-GET, or 401.** Verified: POST with the `XSRF-TOKEN`
   cookie present but no header → `401 {"Id":"qccore.general-error","Title":"Unauthorized request..."}`,
   reproduced identically every time. The header value is the `XSRF-TOKEN` cookie, echoed back. No
   silent-commit risk here — the XSRF gate runs before business logic [api-ref §2.2].
4. **`Accept` header must be set or you get an HTML error page.** No/invalid `Accept` → full branded
   HTML error page. Use `Accept: application/json` (or `application/xml`) on every call [api-ref §3.4].
5. **Field metadata `editable:false`/`required:false` does NOT mean "omit from the write body."**
   `POST test-parameters` omitting `ref-count` 500s as `"...missing required field TP_REF_COUNT"` even
   though its metadata reports `editable:false, required:false`. Sending it anyway succeeds. **Rule: on
   any 500 naming `missing required field <PHYSICAL_NAME>`, retry once with that field included,
   regardless of what metadata claims.** Second instance of hazard #1's class of trap — metadata does
   not fully describe what a write needs [api-ref §3.6, Probe 9].

## 2. Auth handshake

- **One-step API-key login** (our target method): `POST /qcbin/rest/oauth2/login`,
  `Content-Type: application/json`, body `{"clientId":"…","secret":"…"}` → **200**, sets the full
  cookie set in one step on this server: `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`, `ALM_USER`,
  `JSESSIONID` [probe]. This is simpler than the documented general two-step
  `alm-authenticate`→`site-session` dance (that sets only `LWSSO_COOKIE_KEY` first).
- `POST /qcbin/rest/site-session` → 201 after `oauth2/login` — harmless but whether strictly required
  is still open (UNVERIFIED).
- **Session-liveness check — use the v2 path.** `GET /qcbin/rest/is-authenticated` is **XML-only**;
  `Accept: application/json` → **406 trap**. Use `GET /qcbin/v2/rest/is-authenticated` →
  `{"AuthenticationInfo":{"Username":"…"}}` for JSON, XML and JSON both supported, opens no session
  [api-ref §2.3].
- **Keepalive**: GET/PUT `/qcbin/rest/site-session` resets the idle-timeout clock. **Idle timeout**:
  `REST_SESSION_MAX_IDLE_TIME` site param, default 60 min [docs-research].
- **Logout**: `GET /qcbin/authentication-point/logout` → 200, drops session cookies — confirmed on our
  server, but conflicts with docs claiming GET-logout is disabled by default since 24.1
  (`ENABLE_GET_LOGOUT_METHOD=Y` needed). Probe wins for our target; prefer POST for portability
  elsewhere [api-ref §2.2].
- **API keys consume no licence seat.** Each key = Client ID + Secret, maps to a real user account and
  inherits that user's exact project/permission scope; deleting the user deletes its keys.
  `APIKEY_MAX_NUM_PER_USER` default 10, `APIKEY_EXPIRE_DAYS` default -1 (never) [docs-research §2.4].

## 3. Envelopes

- **Core write shape**: `{"Fields":[{"Name":"<field>","values":[{"value":"<v>"}]}, …],
  "Type":"<entity-type-singular>"}` — matches the XML `<Entity><Fields><Field><Value>` shape 1:1
  [api-ref §3.1].
- **Collection envelope**: `{"entities":[…], "TotalResults": n}` [probe].
- **Never round-trip a GET body into a POST/PUT.** GET responses include every field the type carries,
  including calculated/read-only ones (`id`, `last-modified`, `father-name`, `hierarchical-path`, all
  `vc-*`) — sending those back on write is rejected [api-ref §3.1].
- **Bulk envelope**: array of the same per-entity shape, `Content-Type: application/json;type=collection`
  (a QC-specific convention, not `{collection:{entity:[…]}}`).

## 4. Query grammar cheat-sheet (Core, `/qcbin/rest/...`)

`GET .../{collection}?query={field[cond]; field[cond]; …}` — curly braces = filter, square brackets =
per-field condition, `;` delimits fields. **Only AND between fields** (implicit, no OR-between-fields).
Inside one field's brackets: `AND`/`OR`/`NOT` keywords. Quotes for literals with spaces; `*` wildcard;
parens nest.

- Operators: `GT`/`>`, `LT`/`<`, `EQ`/`=`, `GE`/`>=`, `LE`/`<=`. Before ALM 24.1 P1, symbol-only forms
  required; from 24.1 P1 either form works.
- Cross-filters: `<alias>.<field>[cond]`, e.g. `{connected-to-defect.name["Widget wobbles*"]}`. Alias
  must be unique per relation; never use two aliases for the same entity type in one
  query/fields/order-by — silently wrong results, not an error [api-ref §4.2].
- Dates: `yyyy-MM-dd` (Date), `HH:mm:ss` (Time), `yyyy-MM-dd HH:mm:ss` (DateTime). Other formats fail.
- **Gaps, UNVERIFIED**: no documented null-test syntax for Core (Deprecated has `= null`); no documented
  escaping rule for `'`, `"`, `;`, `[`, `]`, `(`, `)`, `,` in literals — real risk for generator
  free-text fields.
- `fields=<name>[,…]` — **no effect on a single-entity GET** in Core (works on collections). Discover
  filterable fields via `.../customization/entities/{entity}/fields?can-filter=true`.
- `order-by={field[,field…]}` — collections only, no-op on single GET. Default sort = id ascending.
  Reference fields sort by referenced value, not id. `[…,CI]` = case-insensitive (ALM 17.0+).
- **Paging**: `page-size` (default 100, max 2000 **silently capped**), `start-index` (**1-based**).
  Deprecated API uses `limit`/`offset` (0-based) instead and **throws** on overflow instead of capping.
  `TotalResults` in the collection envelope gives the total count.

## 5. Bulk operations

Same-entity-type only. **DELETE**: `?ids-to-delete=17,28,31` (present on 58/62 generic collections).
**POST/PUT**: array body, `Content-Type: application/json;type=collection`. `REST_API_MAX_BULK_SIZE`
default 2000 (min 1). **Non-transactional** — executes for all entities even with partial failure.
200 = full success; 500 = all failed; **409 = partial**, body is `BulkOperationFailed`/`BulkEntry[]`,
each entry carrying `Successful`, `EntityId`, `EntityType`, and on failure a nested `Id`/`Title`/
`StackTrace`. **Always parse the 409 body per-item — never assume all-or-nothing** [api-ref §4.5].
`MAX_REQUEST_LENGTH` default 10000 KB caps total request length (ALM 16.01 P1+).

## 6. Error codes

`Accept` selects the error envelope: JSON → `{"Id":"…","Title":"…"}` (bulk adds `ExceptionProperties`
with `BulkEntry`/`StackTrace`); XML → `<QCRestException><Id>…</Id><Title>…</Title></QCRestException>`;
no/bad Accept → HTML page. **400 and 403 are documented catch-alls** covering several distinct failure
modes each — always parse `Id`/`Title`, never infer cause from status code alone.

`qccore.*` catalogue: `bulk-operation-failed`, `check-in-failure`, `check-out-failure`,
`entity-not-found`, `general-error`, `invalid-filter-expression`, `invalid-list-field-value`,
`invalid-value-type-for-field`, `lock-failure`, `operation-forbidden`, `required-field-missing`,
`session-has-expired`, `undo-check-out-failure`, `unknown-field-name`. `general-error` and
`required-field-missing` independently probe-confirmed. Status catalogue: 200/201/400/401/403/404/405/
406/409/415/500/501.

## 7. Call recipes (copy-pasteable, verified only)

**Requirement create** — root `parent-id=0` ("Requirements"), `type-id` required (8 types: 0 Undefined,
1 Folder, 2 Group, 3 Functional, 4 Business, 5 Testing, 6 Performance, 66 Business Model). **Always
discover the root at runtime** (`?query={parent-id[0]}`) — id values are project-specific.

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"0"}]},
           {"Name":"type-id","values":[{"value":"3"}]}],
 "Type":"requirement"}
```

**Test create** — root test-folder discovered at `parent-id=0` (was id=2 "Subject" on our project).
Subtype `MANUAL` etc., runtime-discoverable via `customization/entities/test/types`.

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"<folder-id>"}]},
           {"Name":"subtype-id","values":[{"value":"MANUAL"}]}],
 "Type":"test"}
```

**Design-step create** — works despite docs marking POST "Not applicable" on this page (disproved
on-server). Sibling-order field is `step-order`, not `order-id`.

```json
{"Fields":[{"Name":"name","values":[{"value":"Step 1"}]},
           {"Name":"parent-id","values":[{"value":"<test-id>"}]},
           {"Name":"description","values":[{"value":"<html><body>…</body></html>"}]},
           {"Name":"expected","values":[{"value":"<html><body>…</body></html>"}]}],
 "Type":"design-step"}
```
**Parameter tokens**: a raw `<<<name>>>` in step text is mangled by the sanitizer to `<<>>` (tag-name
stripped). **Must HTML-entity-pre-encode it**: send `&lt;&lt;&lt;name&gt;&gt;&gt;` — survives intact and
flips `has-params=Y`, and this token registers a real `test-parameter` object as a side effect. **Retracted**: the old note here ("`step-parameters` itself cannot be created via REST") was wrong — see
the `test-parameter`/`step-parameter` recipe below.

**`test-parameter` define + `step-parameter` value record** — retracts the earlier "genuine REST gap"
finding (Probes 4–5): a missed `test-parameters` collection defines the object, and the
`step-parameters` failure was a `parent-id` shape bug, not a real gap [live-probe-log.md Probe 9].
**Two entities**: `test-parameter` (physical `TP_*`) *defines* a parameter on a test; `step-parameter`
(physical `SP_*`) *records a value* against an already-defined one.

Route A — direct create (preferred; deterministic, no text-parsing dependency):
```json
POST tests/{testId}/test-parameters
{"Fields":[{"Name":"name","values":[{"value":"my_param"}]},
           {"Name":"ref-count","values":[{"value":"0"}]}],
 "Type":"test-parameter"}                                          -> HTTP 201
```
`parent-id` is read-only — the owning test comes from the **URL**, not the body. ⚠️ **`ref-count` looks
optional (metadata `editable:false, required:false`) but is NOT — omitting it 500s with `"missing
required field TP_REF_COUNT"`. Always send it** (hazard #5 above). The flat `POST test-parameters` form
also works if both `parent-id` and `ref-count` are in the body.

Once a `test-parameter` exists, record a value against it — `parent-id` here must be the
**`test-parameter`'s id**, NOT the design-step/test id (passing the owner id there is exactly what
produced the old, misleading "Test parameter does not exist" 500):
```json
POST step-parameters
{"Fields":[{"Name":"used-by-owner-type","values":[{"value":"design-step"}]},
           {"Name":"used-by-owner-id","values":[{"value":"<design-step id>"}]},
           {"Name":"parent-id","values":[{"value":"<TEST-PARAMETER id>"}]},
           {"Name":"actual-value","values":[{"value":"<html><body>runtime-value</body></html>"}]}],
 "Type":"step-parameter"}                                          -> HTTP 201
```
`used-by-owner-type=test` also works. Set a default value with `PUT test-parameters/{id}` and a
`default-value` field (Memo) → HTTP 200 — there is no `value` field
(`qccore.unknown-field-name` if tried). OTA cannot set this (`"Invalid field type definition"`); REST
can — use REST for this operation even if the OTA bridge is present.

**Requirement coverage** (req↔test):
```json
{"Fields":[{"Name":"requirement-id","values":[{"value":"<req-id>"}]},
           {"Name":"test-id","values":[{"value":"<test-id>"}]},
           {"Name":"entity-type","values":[{"value":"test"}]}],
 "Type":"requirement-coverage"}
```
Side effect: creates exactly one `test-config-coverages` row, queryable by `first-endpoint-id` = the
coverage row's own id (NOT by `requirement-id`, which isn't filterable there).

**Req-trace** (req↔req traceability):
```json
{"Fields":[{"Name":"from-req-id","values":[{"value":"<req1-id>"}]},
           {"Name":"to-req-id","values":[{"value":"<req2-id>"}]}],
 "Type":"req-trace"}
```

**Defect create** — `severity` must be a real list value (e.g. `"1-Low"`); empty string produces a
500/400, not graceful validation:
```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"detected-by","values":[{"value":"<current-user>"}]},
           {"Name":"creation-time","values":[{"value":"2026-08-12"}]},
           {"Name":"severity","values":[{"value":"1-Low"}]}],
 "Type":"defect"}
```
**No REST-native state machine** — `CLIENT_TYPES_BYPASS_REST_WF` means REST writes bypass workflow
scripts by default; a client can set any `status` out of the box, but gets no server-side derived-value
population either.

**Defect-link** — non-directional for defect↔defect; `second-endpoint-type` also verified for
`requirement` (denormalizes that entity's display fields regardless of kind):
```json
{"Fields":[{"Name":"first-endpoint-id","values":[{"value":"<defect1-id>"}]},
           {"Name":"second-endpoint-id","values":[{"value":"<defect2-id>"}]},
           {"Name":"second-endpoint-type","values":[{"value":"defect"}]}],
 "Type":"defect-link"}
```

**Release + cycle** — field names are literally `start-date`/`end-date`. Cycle dates outside the parent
release's window are rejected server-side (500, well-formed message) — derive cycle dates from the
release, never independently.
```json
{"Fields":[{"Name":"name","values":[{"value":"…"}]},
           {"Name":"start-date","values":[{"value":"2026-01-01"}]},
           {"Name":"end-date","values":[{"value":"2026-03-31"}]},
           {"Name":"parent-id","values":[{"value":"1"}]}],
 "Type":"release"}
```

**Milestone** — physical parent field is `MS_RELEASE_ID`: **a milestone is parented under a release**,
not a folder.

**Attachment upload** — two forms: `multipart/form-data` (parts `filename`, `file` **last**, optional
`description`/`override-existing-attachment`/`ref-subtype`) or `application/octet-stream` +
`Slug: <filename>` header (raw bytes, cannot carry `ref-subtype`/description). `ref-subtype=1` = rich
content (image-embed). **17 collections have a `[DEPRECATED]` plain-POST-to-parent-collection form** —
avoid in new code (bpm-folders, design-steps, environments, defects, milestones, releases,
release-cycles, release-folders, requirements, runs, run-steps, test-configs, tests, test-folders,
test-instances, test-sets, test-set-folders). Hand-built multipart with explicit boundary/CRLF and file
part last is required — PS7 `-Form` gets rejected by this server (client artifact, not a server limit).
**Image embed**: `<img src>` must be a full absolute REST attachment URL or a `data:` URI — a bare
filename or relative path gets its `src` attribute silently stripped (tag survives, attribute doesn't).

**Run creation — Fast_Run route ONLY.** `POST runs` directly **fails definitively** (8 attempts, XML +
5 JSON variants — `"Fail to get a must number attribute 'TESTSET'"` or `"Failed to post step"`). The
working route: `PUT test-instances/{id}` with a `status` value → server synthesizes a `Fast_Run`
(`subtype-id=hp.qc.run.MANUAL`). Run-steps auto-copy from design steps (count matches exactly); instance
status mirrors run status; no eager run-step→run status aggregation on flipping a step to Failed. Legacy
naming trap: `cycle-id` = test-set id, `testcycl-id` = test-instance id.

## 8. API surface map

- **Core** (`/qcbin/rest/...`, curly-brace grammar) and **Deprecated** (`/qcbin/api/...`, symbol-only,
  no cross-filters) — both static, evergreen doc trees, capability introduced before 24.1.
- Capability introduced **24.1+** is documented only in per-instance Swagger:
  `/qcbin/api-doc/v2/qc.json` (project API, 14 ops — list-item CRUD, purge-versioning, auth) and
  `/qcbin/api-doc/sa/v2/qc.json` (Site Admin, 178 ops). **Assume nothing about 24.1+ additions from
  static docs — verify against the target instance's own Swagger.**
- `GET /qcbin/rest/resource-list` — 1,111-op inventory, the authoritative per-instance endpoint list,
  but **has known false negatives** (newest Swagger ops like used-list item CRUD don't appear in it at
  all despite being real). Presence = necessary-but-not-sufficient; absence = suggestive-but-not-proof.
- **62 generic entity collections** share a common sub-resource surface: `attachments`, `lock` (41
  collections), `audits` (24 + project-level, ⚠️ partial coverage — creates/memo-PUTs often produce no
  audit entry), `mail` (19 collections, POST body shape UNVERIFIED — 4 shapes tried, all failed),
  `groups/{groupsFields}` (35, aggregation view, `alm-web` dialect), `copy` (gated per-entity), bulk
  delete (58), `versions`+check-in/out (requirements/tests/resources only, not write-probed).
- **Site Admin essentials**: `site-users` full CRUD (sandbox user seeding), `POST
  domains/{d}/projects/{p}/users {"name":"…"}` adds an *existing* site user to a project (does not
  create one), `site-params` full CRUD, `site-version`. 28/178 SA ops are `SAAS_ONLY`-flagged
  (`/audits*`, `/permissions/metadata`, most `/customers/*`, `/orphan-users`, `GET /roles/{roleId}`).
  `run-query` = raw SQL — risk-register only, never an implementation path (hard constraint).
- **`alm-web` dialect** (`application/json;schema=alm-web`, 42 ops): body shape UNVERIFIED, never
  requested by probe.

## 9. Confirmed absent — don't go looking

Zero hits across the 1,111-op resource-list AND doc-host 404s: **timeslots, libraries/baselines,
alerts/alert rules, follow-up flags, purge-runs** (only per-id `DELETE runs/{id}` exists). **BPT**:
`GET /components` → 403 license-gated, `GET /business-components` → 404 — effectively OTA-only here.
**Mail body shape**: genuinely undocumented, all attempted shapes fail. **RETRACTED (Probe 9)**:
`step-parameters`/test-parameter creation is **not** absent — it was a missed `test-parameters`
collection plus a `parent-id` shape bug; see the recipe in §7. Do not re-list it here.

See also: `docs/research/alm-api-reference.md` (full detail, §1–9), `docs/research/live-probe-log.md`
(raw probe results), `docs/research/alm-data-model.md` (entity relationships/field types — load
`alm-entity-model` skill for that).
