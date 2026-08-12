# Live-Instance Probe Log

Empirical findings from read-only probes against the designated sandbox instance. Everything here
was **observed directly**, not taken from documentation — when documentation and this log disagree,
this log wins for our target server. Host, domain, project, and credentials are never recorded here;
probe scripts live in `scripts/probe/` and read `Secrets/ALM_API_credentials.json` at runtime,
masking sensitive values in all output.

| | |
|---|---|
| Probe date | 2026-08-11 |
| Server self-reported version | `SiteVersion "20.0 (Build 20.00.0.143)"` via `GET /qcbin/rest/sa/version` — internal site-version numbering; mapping to the marketing version (24.1 / 25.1 / 26.1) pending the version-lineage research |
| Auth method | API key (client ID + secret) |
| Sandbox state | Effectively empty (0 defects), **1 project user** |

## Probe 1 — auth handshake (`scripts/probe/probe-auth.ps1`)

**VERIFIED end to end:**

1. `GET /qcbin/rest/is-authenticated` unauthenticated → **401**.
2. `POST /qcbin/rest/oauth2/login` with body `{"clientId":"…","secret":"…"}` (Content-Type
   `application/json`) → **200**, and sets cookies `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`,
   `ALM_USER`, `JSESSIONID` **in one step** — on this server, the oauth2 login establishes the full
   session, not just the LWSSO token.
3. `POST /qcbin/rest/site-session` → **201** (idempotent-looking session confirmation; harmless
   after oauth2 login — whether it is strictly required after oauth2/login is still open).
4. `GET /qcbin/rest/is-authenticated` with cookies → **200**.
5. `GET /qcbin/rest/domains` (Accept: application/json) → **200**; configured domain present.
6. `GET /qcbin/rest/domains/{d}/projects` → **200**; configured project present.
7. `GET /qcbin/authentication-point/logout` → **200**; session cookies dropped (only `JSESSIONID`
   remains).

**Implications:** the kickoff prompt's §21 hypothesis about a two-step LWSSO/QCSession dance is
*simplified* by API-key login on this server — one `oauth2/login` call yields a working session.
Candidates `POST /qcbin/api/authentication/sign-in` and Basic-auth against
`/qcbin/authentication-point/authenticate` were not needed (untested beyond not being required).

## Probe 2 — customization metadata (`scripts/probe/probe-metadata.ps1`)

**Server version:** `GET /qcbin/rest/sa/version` → 200 (shape above). `rest/server/version`,
`api/server/version`, `rest/site/version` → 404.

**Field metadata:** `GET …/customization/entities/{entity}/fields` with `Accept: application/json`
returns JSON (`{"Fields":{"Field":[…]}}` shape) for **all 15 entity types probed**:

| entity | fields | type identifiers observed |
|---|---|---|
| requirement | 74 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| test | 57 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| design-step | 12 | DateTime, Memo, Number, String, UsersList |
| test-config | 15 | Date, DateTime, LookupList, Memo, Number, String, UsersList |
| test-folder | 14 | DateTime, Memo, Number, String |
| test-set-folder | 15 | DateTime, LookupList, Memo, Number, Reference, String |
| test-set | 29 | Date, DateTime, LookupList, Memo, Number, Reference, String |
| test-instance | 32 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| run | 51 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| run-step | 29 | Date, LookupList, Memo, Number, String, UsersList |
| defect | 42 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| release | 12 | Date, DateTime, Memo, Number, String |
| release-cycle | 11 | Date, DateTime, Memo, Number, String |
| release-folder | 7 | Memo, Number, String |
| resource | 32 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |

**The complete set of field-type identifiers observed on this server (8):**
`String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`, `Reference`.
Notably absent: any float/boolean/tree types — booleans presumably surface as `LookupList`
(e.g. Yes/No lists) or `String`; to be confirmed against field-level metadata in the fixtures.

**Lists:** `GET …/customization/used-lists` → 200, **39 lists**; `GET …/customization/lists` →
200, **43 lists**. Both exist; the 4-list delta = lists not bound to any field (to be confirmed
from documentation).

**Requirement types:** `GET …/customization/entities/requirement/types` → **200** (fixture saved).

**Users:** `GET …/customization/users` → 200, **1 user** (count only; user data is never saved to
fixtures). ⚠️ Generator implication: `UsersList` fields can only reference real project users —
with one user, user-distribution realism collapses. Ask the admin to add a handful of dummy users
to the sandbox.

**Entity envelope (JSON):** `GET …/defects?page-size=1` → 200 with top-level keys
`entities`, `TotalResults` (= 0; project empty).

## Probe 3 — Swagger/OpenAPI discovery + resource inventory (`scripts/probe/probe-swagger.ps1`, 2026-08-12)

**Server version SOLVED:** `GET /qcbin/v2/sa/api/site-version` → 200 with
`{"site-version":{"full-version":"20.0 (Build 20.00.0.143)", "external-version":26.1, …}}`.
**The sandbox is ALM 26.1** (marketing version; the current release, GA 2026-04). The `sa/version`
"SiteVersion 20.0" seen in probe 2 is the internal numbering of the same thing.

**Swagger/OpenAPI (24.1+ per-instance docs) — found:**

- `GET /qcbin/api-doc/v2/` → 200 HTML shell referencing spec at **`/qcbin/api-doc/v2/qc.json`**
  → 200, 32.8 KB, Swagger 2.0, **14 operations**. It documents only the *additions*: list-item
  writes (`POST …/customization/used-lists/{list-id}/items`, `PUT`/`POST`/`DELETE` on
  `…/items/{item-id}`), `DELETE …/{entity-name}/versioningHistory` (purge-versioning),
  `GET /qcbin/v2/rest/is-authenticated`, and the canonical auth endpoints
  (`alm-authenticate`, `oauth2/login`, `site-session` PUT/POST, `logout`).
- `GET /qcbin/api-doc/sa/v2/qc.json` → 200, 564 KB, **178 operations** — the full Site Admin API
  (`/qcbin/v2/sa/api/…`): site-users and project-users CRUD, group membership, site-params
  GET/POST/PUT/DELETE, site audits, permissions/roles, extensions, license usage, project
  create/copy/export/maintenance, site-connections, `run-query` (raw SQL — **risk-register only**,
  out of mainline scope by hard constraint).
- `GET /qcbin/api-doc/` (root) → 403.

**`GET /qcbin/rest/resource-list` → 200 JSON — the authoritative per-instance endpoint inventory:
319 resource groups, 1,111 operations**, each with path, HTTP method, deprecation flag, and
media types (incl. an `application/json;schema=alm-web` variant). Fixture:
`resource-list-site.json`. Project-scoped `…/projects/{p}/resource-list` → 404 (site-level only).

**Gap-list findings from the inventory** (caveat: *presence in resource-list ≠ verified working
writes* — official docs say undocumented = unsupported; each of these stays UNVERIFIED until the
write-probe round):

| Previously believed | Inventory says |
|---|---|
| `design-steps` documented GET-only | **POST/PUT/DELETE on collection + by-id, plus `design-steps/copy`** |
| No REST surface for test parameters | **`step-parameters` full CRUD** — standalone and nested under `design-steps/{id}`, `runs/{id}`, `test-configs/{id}`, `test-instances/{id}` |
| `requirement-coverages` POST contested | **POST/PUT/DELETE present** (+ `test-config-coverages`, `test-criterion-coverages` full CRUD) |
| Requirement↔requirement traceability: no REST | **`req-traces` full CRUD** |
| Milestones: no REST | **`milestones` full CRUD** (+ `/audits`, attachments, lock) |
| Hosts: no REST | **`bv-hosts` + `host-groups` full CRUD**, `host-in-group` GET |
| Entity history/audit read: no REST | **`GET …/{entity}/{id}/audits` on 24 entity types** (defects, requirements, tests, runs, test-instances, …) + project-level `GET …/audits` |
| Send-by-email server-side UI-only | **`POST …/{entity}/{id}/mail` on 19 entity types** |
| `test-executions` XML-only, unclear | **full CRUD present** (dispatch vs. ingest semantics still unknown) |
| — | `requirement-target-releases` CRUD (req↔release assignment entity) |
| Favorites REST exists | Confirmed: `favorites` + `favorite-folders` CRUD |

**Still zero hits in the inventory (gaps confirmed):** `timeslot`, `librar*` (libraries),
`baseline`, `alert`, follow-up flags, `purge` (runs). These remain OTA-fallback candidates.

**Site Admin API access level:** all three SA probes returned 200 with our API key —
`site-version`, `user-profile`, `permissions`. The key's user holds SA role
**`Customer Admin` (role-id 2)** → SA API is fully usable, so **sandbox dummy-user creation is
automatable** (`POST /qcbin/v2/sa/api/site-users`, `POST …/domains/{d}/projects/{p}/users`).
The `customers/*` endpoint family and "Customer Admin" role naming indicate a **SaaS-flavored
deployment**.

**Misc:** `GET /qcbin/v2/rest/is-authenticated` → 200 JSON
`{"AuthenticationInfo":{"Username":"…"}}`. Core `GET /qcbin/rest/is-authenticated` with
`Accept: application/json` → **406** — the Core variant is XML-only; use the v2 endpoint for JSON
session checks.

## Probe 4 — write round 1 (sandbox, user-approved 2026-08-12; full detail in `_raw/probe4-write-round-1.md`)

Executed by a Sonnet subagent running `scripts/probe/probe-write-1.ps1`; all records carried the
`ALTALM-PROBE` prefix and **all were deleted (every DELETE → 200, verified)**.

**VERIFIED (observed on ALM 26.1):**

- **XSRF:** POST without `X-XSRF-TOKEN` → **401**. With header → works.
- **Requirement create:** 201 with `name` + `type-id=3` + `parent-id=1` — **⚠️ CONTAMINATED
  FINDING**: the silently-committed orphan record (below) had id 1, so `parent-id=1` almost
  certainly parented to the orphan, not a root. User-provided default roots (2026-08-12, prefer
  runtime discovery over hardcoding): requirement root = **id 0 "Requirements"**; test-plan
  folders: **id 2 "Subject"**, id 1001 "Recycle Bin"; test-set folders: **id 0 "Root"**,
  id 1 "Recycle Bin". Re-verify requirement `parent-id=0` in write round 2 on clean state.
- **⚠️ Field order matters:** the JSON `Fields` array serialization ORDER changed server behaviour
  on POST — wrong orders produced opaque NPE-style 500s. **Entity-write JSON must use a fixed,
  deterministic field order** (client-design requirement).
- **⚠️ HTTP 500 ≠ rollback:** one 500-response POST had silently committed a row server-side
  (found via `father-name` cross-ref, deleted). Client must treat 5xx writes as
  "unknown outcome — verify by query", not "failed".
- **Rich-text round-trip (`description`, `req-rich-content`):** PUT 200 but readback DIFFERS:
  `<script>` stripped entirely; `<table>` gains implicit `<tbody>`; whitespace re-pretty-printed;
  `<font>`, inline `style=`, `href` survive. `has-rich-content` flips N→Y on write. Generator must
  compare *canonicalized* HTML, not byte-for-byte.
- **Test tree:** root test-folder is **id=2 ("Subject")** on this project — discover at runtime,
  never hardcode. Test create 201 with `parent-id` + `name` + `subtype-id=MANUAL`.
- **`design-steps` POST → 201. The write path EXISTS** (docs' "not applicable" disproved on-server).
  But the `<<<param>>>` token in step text was **mangled by the sanitizer to `<<>>`** (name lost).
- **`step-parameters` POST FAILED** (both informed attempts): real fields discovered
  (`key`, `used-by-owner-type` [required], `used-by-owner-id`, `parent-id`, `actual-value`) but
  server answers "Test parameter does not exist" — likely coupled to the mangled-token issue.
  Open gap; needs a dedicated probe (create parameter FIRST, then reference in step text?).
- **`requirement-coverages` POST → 201** (contested question resolved). Side effect confirmed:
  one `test-config-coverages` row auto-created per link.
- **`req-traces` POST → 201** with `from-req-id`/`to-req-id` — REST traceability CONFIRMED.
- **Defect create 201** (`name`, `detected-by`, `creation-time`, `severity` e.g. `"1-Low"`);
  **defect-links 201 for both** `second-endpoint-type=defect` and `=requirement`.
- **Audits are partial:** `GET requirements/{id}/audits` → 200 but only the two `status` field
  changes appeared; creates and rich-text PUTs produced no audit entries. History UI cannot assume
  full coverage — follow-up item.

Fixtures: `tests/fixtures/write-probe/` (15 files, redacted; masking verified programmatically
against raw secret values — clean).

## Probe 5 — write round 2 (sandbox; detail in `_raw/probe5-write-round-2.md`; fixtures `write-probe/r2-*`)

Cleanup CLEAN (all DELETEs 200; orphan sweep zero across all 4 runs). **VERIFIED:**

- **Roots (user defaults confirmed):** `requirements/0`="Requirements", `test-folders/2`="Subject",
  `test-set-folders/0`="Root". **Requirement create `parent-id=0` → 201** (round-1 `parent-id=1`
  finding corrected — it was the orphan).
- **Image-embed sanitizer rules:** bare/relative `<img src>` → src STRIPPED (`<img />`);
  **absolute `https://` URL and `data:` URI both survive intact.** Attachment upload via
  octet-stream+`Slug` → 201 (`ref-subtype=0`). **Multipart `ref-subtype=1` (embedded-image
  subtype) FAILED** with an opaque parse error — client-vs-server cause undetermined; retry with
  hand-built multipart in round 3. Full UI-style embed flow therefore still not proven end-to-end.
- **`<<<param>>>` tokens CAN survive**: HTML-entity-pre-encoding the token
  (`&lt;&lt;&lt;name&gt;&gt;&gt;`) passes the sanitizer intact and flips `has-params=Y`.
  Raw tokens get mangled to `<<>>` (round-1 confirmed twice).
- **step-parameters POST: genuine gap.** All shapes fail with "Test parameter does not exist" —
  there appears to be **no REST way to create the underlying test-parameter object** (only
  reference/value rows). OTA-fallback candidate.
- **Test Lab chain:** test-set-folder / test-set (`subtype-id=hp.qc.test-set.default`) /
  test-instance creates VERIFIED (instance initial status "No Run").
  **`run` POST FAILS: `"Fail to get a must number attribute 'TESTSET'"`** — no run field maps to
  that physical name (48-field dump checked). **Run creation shape unresolved**; run-steps
  auto-copy / status mirror / Fast_Run / aggregation all blocked on it. Round-3 idea: trigger a
  synthetic Fast_Run via instance-status PUT and read the server-created run's fields.
- **Milestones:** create VERIFIED once parented correctly — `parent-id` physical name is
  `MS_RELEASE_ID`: **milestones live under a release**, not a folder.
- **Mail:** `POST …/{id}/mail` FAILED across 3 JSON shapes (identical opaque NPE) + 1 XML shape
  (different 400) — body format genuinely undocumented; unresolved.
- **test-executions POST = DISPATCH** (verified): reaches execution logic and answers
  "There is no agent configured…" — it schedules execution, it does not ingest results.
- **Release-cycle date validation:** cycle outside release range **rejected** (500 with
  well-formed message); in-range cycle created fine. Release create field names
  `start-date`/`end-date` confirmed.
- **BPT (offline inventory check):** no `components` collection in resource-list — only
  `components/{id}/snapshot` (GET/POST/DELETE) and GET-only `businessmodels`/`businessviews`.
  Inventory has known false negatives → one read-only `GET /components?page-size=1` queued;
  otherwise BPT = OTA-only candidate.

## Probe 6 — write round 3, targeted (detail in `_raw/probe6-write-round-3.md`; fixtures `write-probe/r3-*`)

Cleanup CLEAN (all DELETEs 200 incl. Fast_Runs; orphan sweep zero, all 3 sessions). **VERIFIED:**

- **Direct `POST runs` does NOT work on this server** — 8 attempts across rounds 2–3 (XML + 5
  JSON field-set variants). Failure is bimodal and reproducible: baseline variants →
  `"Fail to get a must number attribute 'TESTSET'"`; adding denormalized name fields
  (`test-name`/`testcycl-name`/`cycle-name`) → `"Failed to post step"`.
  **The working route is indirect: `PUT test-instances/{id}` with a status triggers a
  server-synthesized `Fast_Run` — reliably, 3/3 sessions.** Generator/Alt-ALM must create runs
  via this route. Full Fast_Run field dump: `r3-fastrun-full-entity.json` (also disambiguates
  `cycle-id`=test-set id, `testcycl-id`=test-instance id via the name fields).
- **Run-steps auto-copy from design steps: VERIFIED** on synthesized runs (count matches
  design-step count exactly — 2↔2, 1↔1).
- **Instance status mirrors run status: VERIFIED** (instance reads "Passed" after run PUT).
- **No eager run-step→run status aggregation**: flipping a run-step to Failed leaves the parent
  run's status unchanged (caveat: run had been force-set Passed first — shows no auto-recompute,
  not an exhaustive matrix).
- **Multipart `ref-subtype=1` upload WORKS** — hand-built multipart body (explicit boundary,
  CRLF discipline, text parts first, file part LAST with `Content-Type: image/png`) → 201, 3/3.
  Round-2's failure was a PowerShell `-Form` constructor artifact, not a server limitation.
  **The full embedded-image flow (upload subtype-1 attachment + `data:`/absolute-URL `img src`)
  is end-to-end viable.**
- **BPT:** `GET /components` → **403 `qccore.operation-forbidden`** (endpoint exists,
  license/permission-gated); `GET /business-components` → **404** (absent). BPT remains
  effectively out of REST reach here → OTA-fallback candidate.

## Fixtures captured (redacted; under `tests/fixtures/`)

- `customization-fields-<entity>.json` × 15
- `customization-used-lists.json`, `customization-lists.json`
- `customization-requirement-types.txt`
- `api-doc-v2-openapi.json` + `api-doc-v2-paths.txt` (project-API Swagger, 24.1+ additions)
- `api-doc-sa-v2-openapi.json` + `api-doc-sa-v2-paths.txt` (Site Admin Swagger, 178 ops)
- `resource-list-site.json` (full endpoint inventory, 1,111 ops)

Redaction = host/domain/project/key strings replaced with `REDACTED` before write. User data and
entity data are not captured.

## Open items for the next probe round

1. ~~Map `SiteVersion 20.0 (20.00.0.143)` → marketing version~~ **DONE: ALM 26.1** (probe 3).
2. Is `site-session` required after `oauth2/login`, or fully redundant? (Skip it, observe.)
3. ~~XSRF header requirement~~ **DONE: 401 without header** (probe 4).
4. ~~Rich-text round-trip fidelity~~ **DONE** (probes 4–5: sanitizer rules, img-src forms, token
   encoding).
5. Whether `Accept: application/json` works on every collection or only some (observed: yes on all
   probed so far; exception found: Core `is-authenticated` is 406/XML-only — use v2).
6. ~~Booleans~~ **DONE: no Boolean type; Y/N = LookupList list-id 1** (probe 3 offline mining).
7. ~~Write-probe every "inventory says yes" row~~ **DONE** (probes 4–6) — outcomes: design-steps ✓,
   req-traces ✓, requirement-coverages ✓, milestones ✓, runs ✗ (Fast_Run route instead),
   step-parameters ✗ (OTA candidate), mail ✗ (body undocumented).
8. ~~`test-executions` semantics~~ **DONE: dispatch, not ingest** (probe 5).
9. ~~Offline fixture mining~~ **DONE** (probe 3 mining reports).
10. **Deferred to post-planning**: mail body shape (capture stock-UI traffic); step-parameters via
    OTA (needs tdconnect.exe); release-folder root id; `alm-web` dialect body shape; comments
    append banner convention; audit coverage isolation (plain-field PUT vs memo PUT); versions
    check-in/check-out write probe; `IMAGE_COMPRESSION_LEVEL` round-trip.
