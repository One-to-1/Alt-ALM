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

## Probe 7 — OTA/COM spike, 2026-08-12 — ⚠️ **ITS CONCLUSION IS SUPERSEDED BY PROBE 8**

> **DO NOT ACT ON THIS SECTION'S CONCLUSIONS.** Probe 7 concluded "OTA is unreachable on this SaaS
> instance". **That was wrong.** The cause was the *client*, not the server: Probe 7 ran against a
> hand-extracted 4-DLL payload from the TDConnect installer. Once the **properly deployed** ALM
> client was registered, **OTA connected on the first attempt** via `InitConnectionWithApiKeyEx`
> and full read/write works. See **Probe 8** below for the corrected, verified picture. The
> client-side environment facts recorded here (32-bit only, version matching, per-user registration,
> WOW64 and typelib traps) remain accurate and useful.

### Original Probe 7 record (`scripts/probe/probe-ota-{1,2,3}.ps1`; READ-ONLY, zero records created)

Purpose: settle whether the OTA/COM fallback — on which **23 feasibility-matrix features and the
step-parameter generator gap depend** — actually works against this instance. User supplied
`TDConnect/` clients (24.1 / 25.1 / 26.1 CE SAAS, git-ignored).

**HEADLINE: OTA is NOT usable against this SaaS instance.** The client works perfectly; the *server*
front door refuses the OTA handshake. Details below, because the distinction matters for on-prem.

**Client-side — everything VERIFIED WORKING:**

- **OTA is 32-bit only.** `New-Object -ComObject TDApiOle80.TDConnection` from 64-bit PowerShell 7
  fails `0x80040154 REGDB_E_CLASSNOTREG`; the same call from
  `C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe` succeeds. Any OTA bridge must run in a
  **32-bit host process**.
- **The machine-wide registration was stale**: `HP ALM Platform 12.53 (2017)` at
  `C:\Program Files (x86)\Common Files\Mercury Interactive\...\OTAClient.dll`. A 12.53 client against
  a 26.1 server returns **"Invalid server response"** on `InitConnectionEx`.
- **`TDConnect_26.1CE_SAAS.exe` needs elevation/GUI** — a silent install (`/s /v/qn`) was not
  honoured; it extracted its payload and blocked on a `QCConnectivity` dialog. We are not admin.
- **Workaround VERIFIED — the 26.1 client can be registered per-user with no admin rights:** copy the
  extracted payload (`OTAClient.dll`, `tdclient.dll`, `tdclntui.dll`, `WebClient.dll`, v20.00.0.174)
  to a user-writable folder and write the COM keys under `HKCU\Software\Classes`. **Two traps:**
  (1) the keys **must be written from a 32-bit process** — WOW64 redirects them to
  `HKCU\Software\Classes\Wow6432Node`, and keys written from a 64-bit process are invisible to the
  32-bit COM host (this silently kept the old 12.53 DLL loading); (2) `regsvr32 /i:user` fails
  (exit 4, no `DllInstall` export), and **the type library must be registered separately** via
  `RegisterTypeLibForUser` — otherwise the stale 12.53 typelib resolves method names against the new
  DLL and every call fails `0x8002802B TYPE_E_ELEMENTNOTFOUND`.
- **The 26.1 client exposes modern auth entry points** (absent from 12.53), confirming the research
  finding: `InitConnectionWithApiKey`, **`InitConnectionWithApiKeyEx`**, `InitConnectionWithCookies`,
  `InitConnectionWithCookiesEx`, `ApplyCookie`, `GetAuthenticationToken`,
  `LoginWithAuthenticationToken`, `LoginSessionId`.

**Server-side — the blocker, ROOT-CAUSED:**

- `GET /qcbin/servlet/tdservlet/TdServlet` **unauthenticated → 302** to
  `/authentication-point/discovery.jsp?redirect_uri=...` (the SaaS SSO front door). The OTA client
  performs its own HTTP handshake, receives an HTML redirect where it expects its binary protocol,
  and reports **"Invalid server response"**.
- **The endpoint itself is alive**: with an authenticated REST session the same URL returns
  **HTTP 200 to both GET and POST**. So OTA transport is *not* disabled on this deployment — the
  client simply cannot carry a session through the SSO redirect.
- **Every documented bridge was tried and failed** (all with a valid REST session in hand):
  `InitConnectionWithApiKeyEx(url, clientId, secret)` → "Invalid server response";
  `InitConnectionWithCookies`/`...Ex` across 4 cookie encodings (LWSSO only, LWSSO+QCSession, all
  five cookies, bare LWSSO value) → connection never establishes;
  `ApplyCookie(...)` **is accepted** but a following `InitConnectionEx` still fails identically;
  `GetAuthenticationToken` → `0x800403FD`. Subsequent `Connect()` always → "OTA server is not
  connected."
- `GET /v2/sa/api/site-params` → **403** with our Customer Admin key, so the OTA-related site
  parameters (e.g. a reported `OTA_ACCESS_APIKEY_ONLY`) could **not** be inspected — `UNVERIFIED`
  whether a server-side setting would change this.

**Conclusions (decision-grade):**

1. **On this SaaS instance, OTA is a dead end with the credentials we hold.** The 23 OTA-marked
   features in the feasibility matrix have **no working fallback here**, and Q18 (defining a test
   parameter) stays unresolved — REST cannot do it and OTA cannot connect.
2. **This does NOT prove OTA is dead generally.** The failure is entirely in the SSO handshake; an
   **on-prem** instance without the SSO front door would very likely work, since the client half is
   fully functional locally. Anyone re-testing needs: a real interactive username/password, an
   on-prem target, or a SaaS-admin site-parameter change.
3. **ADR 0003's OTA sidecar has no reachable target on this deployment** — it must stay strictly
   optional and capability-flagged, exactly as designed, and must not be scheduled as the answer to
   any gap until a reachable instance exists.

**Environment left behind (intentional, documented, reversible):** the 26.1 OTA client is registered
**per-user** and now shadows the broken machine-wide 12.53 client for this user only. It is strictly
newer than what it shadows and is required for any future OTA attempt. Files:
`%LOCALAPPDATA%\AltALM\ota-client-26.1\`. To undo, from a **32-bit** PowerShell:
`Remove-Item -Recurse 'HKCU:\Software\Classes\CLSID\{C5CBD7B2-490C-45F5-8C40-B8C3D108E6D7}','HKCU:\Software\Classes\TDApiOle80.TDConnection'`

## Probe 8 — OTA/COM WORKS, 2026-08-13 (`scripts/probe/probe-ota-{4,5,6}.ps1`; sandbox writes, all cleaned up)

**This supersedes Probe 7's conclusion.** After the user installed TDConnect 26.1, the machine-wide
registration was left pointing at a deleted file; COM was repointed at ALM's **own deployed client**
at `%LOCALAPPDATA%\HP\ALM-Client\20.00.0.0_952\OTAClient.dll` (v20.00.0.174 — the 26.1 line). With
that client, **OTA connects and works.** The Probe 7 failure was the hand-extracted 4-DLL payload,
not the server.

**CONNECTION — VERIFIED.** `InitConnectionWithApiKeyEx(url, clientId, secret)` →
`Connected=True, LoggedIn=True`; `Connect(domain, project)` → `ProjectConnected=True`.
**An API key authenticates OTA — no username/password needed**, and the SaaS SSO front door is not
an obstacle when the correct client is used.

**WRITES — VERIFIED.** Created and deleted, via OTA only: a test folder under `Subject`
(`TreeManager.NodeByPath("Subject").AddNode(name)` + `Post()`), and a test
(`folder.TestFactory.AddItem(...)`, set `Name`/`Type="MANUAL"`, `Post()`).

**⚠️ BPT IS REACHABLE AND WRITABLE VIA OTA — this reverses the REST-era conclusion.** REST returned
`403 qccore.operation-forbidden` on `GET /components`, which we had read as a licence gate. It is
**not** a licence gate: over OTA, `ComponentFactory` reads fine (4 component folders visible, root
`id=1 "Components"`) and a **business component was successfully created and deleted**. The two
errors along the way were structural, not permission-related: `"Invalid owner specified: 0"` (a
component needs an owner) and then `"Components cannot be added directly under COMPONENTS folder"`
(it needs a **sub-folder**). Working recipe: `ComponentFolderFactory` on the root folder →
`AddItem` a subfolder → that subfolder's `.ComponentFactory.AddItem(...)` → `Post()`.

**TEST PARAMETERS (Q18) — the mechanism is now understood.** `Test.Params` is **not** a factory: it
is a parameter *collection* exposing `AddParam, ClearParam, Count, DeleteParam, ParamExist,
ParamName, ParamType, ParamValue, BaseValue, Refresh, Save, Type` (there is **no**
`TestParameterFactory` on this object model — that name from doc research is wrong for 26.1).
Findings:
- `Params.AddParam("name","value")` is **accepted** and `Params.Save()` returns OK, but the
  parameter **does not persist** — `Count` stays 0 on the same handle, after `test.Post()`, and on a
  fresh re-read (`HasParam=False`). Declaring a parameter directly appears to be a no-op.
- **A design step containing a `<<<token>>>` DOES register the parameter**: after creating a design
  step with `StepDescription = "Use <<<altalm_param>>> here"`, the test's parameter
  `Count` went **0 → 1**. **The token in step text is the registration mechanism**, exactly as the
  ALM docs describe — not an explicit "define parameter" call.
- Setting a *value* on the now-registered parameter then failed with `"Invalid field type
  definition."` — `UNVERIFIED` how to set the default value; needs one more probe.

**Other OTA-only candidates — all factories ACQUIRED** (each `NewList("")` returned 0 items on this
empty sandbox, i.e. reachable, not blocked): `BaselineFactory`, `LibraryFactory`, `HostFactory`,
`HostGroupFactory`, `MilestoneFactory`, `KPIFactory`, `ScopeItemFactory`. Present on
`TDConnection`: `PurgeRuns`, `PurgeRuns2`, `SynchronizeFollowUps`, `AlertManager`,
`ExtendedStorage`. `DeniedFeatures` returns a per-licence-level denied-feature map (captured but not
yet decoded — `UNVERIFIED` which row applies to our key).

**Client-side environment (carried over from Probe 7, still true and now proven necessary):** OTA is
**32-bit only**; the client must be **version-matched** to the server; per-user COM registration
works without admin, but the keys **must be written from a 32-bit process** (WOW64 redirects to
`Wow6432Node`) and the **type library must be registered separately** (`RegisterTypeLibForUser`),
or every call fails `TYPE_E_ELEMENTNOTFOUND`. **Use ALM's own deployed client** under
`%LOCALAPPDATA%\HP\ALM-Client\<version>\`, not a payload extracted from the installer.

**PowerShell/COM gotchas:** `AddItem(Null)` must be passed as `[System.DBNull]::Value` — `$null` and
`[Reflection.Missing]::Value` both fail with "Value does not fall within the expected range".
`SysTreeNode.RemoveNode()` takes the **node object**, not its id (an id is read as a child index).
**COM connect/login calls return project-list objects that PowerShell prints to stdout, bypassing a
mask function — assign them to `$null`** (this leaked real project names into a console transcript
during this probe; scripts were fixed).

**⚠️ Cleanup caveat, learned the hard way:** deleting a test folder via OTA `RemoveNode` did **not**
delete the tests inside it — 5 orphaned `ALTALM-OTA-*` tests were left behind across runs and had to
be swept via REST `DELETE /tests/{id}`. **Always sweep by name prefix across `tests` *and*
`test-folders` after any OTA folder delete.** Final sweep: 0 remaining in `tests`, `test-folders`,
`design-steps`.

**Consequences:** the ~23 OTA-verdict features are **back in play** on this deployment, BPT included.
The Probe 7 write-off was wrong. ADR 0003's sidecar now has a **reachable target**, so the
implementation-language decision (.NET vs Python + pywin32) becomes live again.

**Next experiments:** ~~(1) whether writing an entity-encoded `&lt;&lt;&lt;name&gt;&gt;&gt;` token via
**REST** registers a parameter the same way; (2) how to set a parameter's default value~~
**BOTH ANSWERED — see Probe 9. Neither needs OTA.** Remaining: (3) decode `DeniedFeatures`.

## Probe 9 — the test-parameter gap is CLOSED over REST, 2026-08-13 (`scripts/probe/probe-write-4.ps1`, `-4b.ps1`, `probe-ota-7-paramcheck.ps1`)

⚠️ **This retracts the "no REST path to define a test parameter" conclusion carried since Probe 4.**
It was reported as "a genuine gap, not a shape bug" after 5 failed `POST step-parameters` attempts
across rounds 1–2. **It was a shape bug** — specifically a wrong `parent-id` — plus a missed
collection. Every claim below is HTTP-verified against the sandbox; all records cleaned up, orphan
sweep returned 0 in `tests` and `test-folders`.

### 9.1 The missed collection: `test-parameters` ≠ `step-parameters`

The per-instance resource-list has carried a **separate** collection all along, never probed because
documentation research asserted "no REST entity for test parameters at all":

```
/domains/{d}/projects/{p}/test-parameters                DELETE,GET,POST,PUT
/domains/{d}/projects/{p}/test-parameters/{id}           DELETE,GET,PUT
/domains/{d}/projects/{p}/tests/{test_id}/test-parameters    GET,POST
/domains/{d}/projects/{p}/tests/{test_id}/test-parameters/{id}  DELETE,GET,PUT
```

The two entities divide the job cleanly, which is why hammering one never worked:

| Entity | Physical | Role |
|---|---|---|
| `test-parameter` | `TP_*` | **Defines** the parameter on a test (name, default value, order) |
| `step-parameter` | `SP_*` | **Records a value** against an already-defined parameter |

`step-parameter.parent-id` (`SP_TEST_PARAM_ID`) is the **`test-parameter` id** — *not* the design-step
or test id. Rounds 1–2 passed the owner id there, which is exactly what produced the opaque
`HTTP 500 "Test parameter does not exist"` on all 5 attempts. The error was literally true.

### 9.2 `test-parameter` field set (runtime metadata, fixture `r4-test-parameter-fields.json`)

11 fields. `name` (`TP_NAME`, String) is the only **Required** one; `default-value` (`TP_DEFAULT_VALUE`,
Memo), `description` (`TP_DESCRIPTION`, Memo) and `order` (`TP_ORDER`, Number) are editable; the rest
(`id`, `parent-id`/`TP_TEST_ID`, `ref-count`, `is-mapped`, `vts`, `vc-user-name`, `ver-stamp`) are
metadata-read-only.

### 9.3 ⚠️ NEW WRITE HAZARD — a metadata-read-only field can be *required* on write

`POST test-parameters` with a correct body fails:

```
HTTP 500 {"Id":"qccore.general-error",
          "Title":"failed converting entity test-parameter to FREC, request is missing required field TP_REF_COUNT"}
```

`ref-count` is reported `editable:false, required:false` by
`customization/entities/test-parameter/fields`. **Sending it anyway makes the create succeed** —
`{"name":"…","ref-count":"0"}` → **HTTP 201**, on both the nested and flat forms (4/4 shapes).

**Generalized rule for the BFF's write-safety component: field metadata `editable:false` does NOT
imply "omit from the write body", and `required:false` does NOT imply "optional on create".** The
`Required` flag describes UI/validation semantics, not the server's own FREC-conversion
preconditions. Any 500 naming a `missing required field <PHYSICAL_NAME>` should be retried once with
that physical field's logical name included, before being reported as a failure. This is a second
instance of the same class of trap as the load-bearing field-order hazard (§3.2) — both are cases
where the metadata does not fully describe what a write needs.

### 9.4 Two working creation routes — both verified

**Route A — direct create (preferred; deterministic, no text parsing):**
```
POST tests/{testId}/test-parameters
{"Fields":[{"Name":"name","values":[{"value":"my_param"}]},
           {"Name":"ref-count","values":[{"value":"0"}]}],
 "Type":"test-parameter"}                                          -> HTTP 201
```
`parent-id` is read-only, so the owning test comes from the **URL**. The flat
`POST test-parameters` form also works when `parent-id` **and** `ref-count` are both in the body.
`order` may be set explicitly; otherwise the server auto-assigns the next ordinal.

**Route B — token registration (matches the stock UI's authoring flow):** a design step whose
`description` contains an **entity-encoded** token registers the parameter as a side effect.
```
POST design-steps  description = "<html><body>Value is &lt;&lt;&lt;altalm_rt&gt;&gt;&gt;</body></html>"
  -> HTTP 201, has-params="Y"
GET tests/{testId}/test-parameters -> TotalResults=1, name="altalm_rt", ref-count=1
```
**This answers Q34: yes — the REST token registers a real parameter object, identically to OTA's.**
The entity-encoded form also **survives round-trip with the token name intact** (fixture
`r4b-designstep-roundtrip.txt`), unlike a raw `<<<name>>>`, which the sanitizer still mangles to
`<<>>` (§6.4). Registered parameters have **independent lifetime** — they are not cascade-deleted
when the step is removed.

### 9.5 `POST step-parameters` — WORKS (retracts the Probe 4/5 failure)

With `parent-id` = the **`test-parameter` id**, it returns **HTTP 201** for both owner types:
```
POST step-parameters
{"Fields":[{"Name":"used-by-owner-type","values":[{"value":"design-step"}]},
           {"Name":"used-by-owner-id","values":[{"value":"<design-step id>"}]},
           {"Name":"parent-id","values":[{"value":"<TEST-PARAMETER id>"}]},
           {"Name":"actual-value","values":[{"value":"<html><body>runtime-value</body></html>"}]}],
 "Type":"step-parameter"}                                          -> HTTP 201
```
`used-by-owner-type=test` also returns 201. `actual-value` is a Memo and round-trips through the
same sanitizer as any other memo field. Verified read-back via `GET step-parameters/{id}`.

### 9.6 Default values — REST can do what OTA cannot

`PUT test-parameters/{id}` with `default-value` → **HTTP 200**, value reads back intact. Probe 8 left
this `UNVERIFIED` because OTA's `Params` collection raises `Invalid field type definition` when
setting one. **The REST route simply works**, and is the one to use. (`value` is not a field name —
`qccore.unknown-field-name`; the field is `default-value`.)

### 9.7 OTA cross-check — the REST-created objects are real

`probe-ota-7-paramcheck.ps1` (32-bit host, ALM's deployed client) opened the same test over COM:
`HasParam=True`, `Params.Count = 5`, and the parameter names matched what REST created, including
the token-registered one. OTA's `ParamValue()` raised `Invalid field type definition` on read — so
**REST reads the default value that OTA cannot**. The two APIs agree on existence; REST is strictly
more capable here.

### 9.8 Observed, cause unconfirmed

`DELETE design-steps/{id}` returned **HTTP 500** for a step that had a `step-parameter` referencing
it (parameters remained afterwards; deleting the parent test then cleaned everything up, 200).
Likely a referential-integrity ordering constraint — delete `step-parameters` before their owning
design step. `UNVERIFIED` as a cause; the workaround (delete children first, or delete the parent
test) is verified.

### 9.9 Consequences

- **The single hardest gap in the feasibility matrix is closed over documented REST.** Test
  parameters were the only *generator-blocking* gap in `data-generator-spec.md`'s appendix; the
  generator can now author parameters, default values, and per-step values end-to-end.
- **ADR 0003's justification shrinks from three gaps to two.** Test-parameter definition was one of
  exactly three named REST gaps that justified the OTA sidecar. Only **BPT components** and
  **similar-defects** remain. The sidecar is still justified, but it is now a smaller, later thing.
- **A second metadata-doesn't-describe-writes hazard** (§9.3) belongs in the write-safety component
  alongside the field-order rule.

## Probe 10 — API-key session concurrency, 2026-08-13 (`scripts/probe/probe-sessions.ps1`)

**Question**: a username/password login to ALM is constrained by concurrent-user licensing — in
practice one active client at a time on a single-seat licence. Does the same limit apply to an
**API key over REST**?

**Answer: no. One API key held 50 simultaneous sessions with zero eviction.** Read-only with
respect to project data — sessions only, no records created.

| Measurement | Result |
|---|---|
| Sessions opened on one API key | **50 / 50** |
| Still alive after all 50 were opened (eviction check) | **50 / 50** |
| Simultaneous in-flight authenticated requests | **50 / 50 → HTTP 200** |
| Sessions evicted / logins refused | **0** |

Tested at 12 first, then 50. **No cap was reached** — 50 is a floor, not a ceiling. Each session was
an independent cookie jar (that is what makes them distinct sessions rather than one shared one).

**Cookie identity** (corrects an initial misread from a 6-char sample): of the five cookies,
`JSESSIONID`, `LWSSO_COOKIE_KEY`, `QCSession` and `XSRF-TOKEN` are **all unique per session**;
only `ALM_USER` is shared across sessions (it is the resolved username, not a session id). So there
is no evidence here of a shared node/affinity token, and no session-affinity requirement was
observed.

**This corroborates the no-licence-seat finding empirically.** Doc research had established that REST
sessions consume no licence; 50 concurrent sessions succeeding is strong independent confirmation,
since on a seat-consuming model that would require 50+ free seats.

**Not tested / `UNVERIFIED`:** all 50 sessions originated from **one machine and one IP**. This
measures the server's per-key session cap, not any per-IP or per-machine binding. Nothing observed
suggests ALM binds a session to a client IP, but multi-machine behaviour is not directly evidenced.
Experiment that would settle it: run the same probe simultaneously from two hosts on different IPs.

~~**Site Admin session visibility is not reachable on this deployment**~~ ⚠️ **RETRACTED within the
hour by Probe 11 — this was wrong, and wrong the same way as the other two retractions: I guessed
three endpoint paths, none of which exist, and concluded the capability was absent instead of
checking the Swagger inventory we already had on disk.** The real path is
**`GET /qcbin/v2/sa/api/site-connections` → HTTP 200**. What is genuinely true: `v2/sa/api/site-params`
→ 403 despite Customer Admin, and the guessed paths `v2/sa/api/connections` / `v2/sa/api/site-sessions`
→ 500, `rest/site-session/connections` → 404. No configured session *cap* was found, so the
50-session observation still stands on its own — but server-side session *visibility* exists.

**Consequence for ADR 0004**: the single-service-account pooled-session design is safe. Pool sizing
is bounded by politeness and `REST_SESSION_MAX_IDLE_TIME` (60 min default) keepalive cost, not by a
licence or session cap. Multiple Alt-ALM instances, multiple developer machines, and CI can all use
the same key concurrently.

## Probe 11 — re-checking the 21 `NO` verdicts, 2026-08-13 (`scripts/probe/probe-no-recheck.ps1`)

Driven by `_raw/no-verdict-recheck.md` (web-research pass over every `NO` row). **READ-ONLY — no
records created or modified.** Results below are the live half; the desk-research half is in that
report.

### 11.1 ⚠️ Server-side session visibility EXISTS — retracts Probe 10's claim

**`GET /qcbin/v2/sa/api/site-connections` → HTTP 200.** Returns `total-results` plus a
`site-connection[]` array with `login-session-id`, `project-session-id`, `domain`, `project`, `host`,
`username`, `last-ping`, `last-action`, `login-time`, `client-type`, `session-data`.

Probe 10 declared this unreachable after three *guessed* paths returned 500/404. The correct path was
in `tests/fixtures/api-doc-sa-v2-paths.txt` — already on disk — the whole time. **Third instance of
the same failure mode in three days: concluding absence from failed guesses instead of consulting the
inventory.** `v2/sa/api/site-params` → 403 is still genuinely true.

Useful properties: `client-type` distinguishes clients and **shows `OTAClient` for COM sessions**, so
the OTA bridge is observable in production. Sessions from the whole tenant are visible (the key holds
Customer Admin).

⚠️ **PRIVACY**: this endpoint returns **third-party identities** — usernames, client hostnames and
project names belonging to other projects in the same tenant. No credential-derived mask can
anticipate those values, so `probe-no-recheck.ps1` masks them **structurally by JSON key**
(`username`, `host`, `project`, `domain`, `session-data`). Any Alt-ALM feature or probe touching
this endpoint must do the same. Nothing was persisted to any file.

### 11.2 Data-hiding / permissions (matrix #205) — upgrade from `NO`

All 200: `v2/sa/api/permissions` (role + permission-name list), `v2/sa/api/permissions/metadata`
(category → sub-category → permission tree with display names), `v2/sa/api/roles`, and
`v2/sa/api/domains/{d}/projects/{p}/groups` (returned the five standard groups: TDAdmin, Project
Manager, QATester, Developer, Viewer). Group/role/permission structure is therefore REST-readable.
**Still `UNVERIFIED`**: whether per-group, per-module *data-hiding filter rules* specifically are
exposed — the SA permission tree is site-level, not the project data-hiding grid.

### 11.3 Testing Policy matrix (matrix #18, "Analyze") — partially located

`customization` root → 404, `customization/riskbasedqualitymanagement` → 404,
`customization/testing-policy` → 404, and the offline resource-list contains **zero** paths matching
`risk`, `rbt`, `testing-polic` or `assessment`. **But** `customization/entities/requirement/types`
→ 200 exposes a per-type **`risk-analysis-type`** field (values `0`/`2` observed). So RBQM
configuration is partly REST-visible, while the Testing Policy lookup grid itself is not.
Web research established Analyze is a documented table lookup, not a hidden algorithm — so the
remaining task is locating the grid (OTA `TDConnection.Customization`, or a one-time manual capture,
since it rarely changes) rather than reverse-engineering a formula.

`customization/extensions` → 200: Sprinter, Analysis Extension, Quality Center (all v20.00).

### 11.4 Per-attachment history (matrix #186) — inconclusive, not negative

The sandbox contains no attachment to test against, so `attachments/{id}/audits` was never exercised.
The offline resource-list has no `attachments/.../audits` path, but that inventory has known false
negatives. Re-run after P2 creates an attachment.

## Probe 12 — OTA re-check of the `NO` verdicts, 2026-08-13 (`scripts/probe/probe-ota-8.ps1`, `-9.ps1`)

**READ-ONLY — created and modified nothing.** Phase 1 acquired and enumerated the candidate COM
objects; phase 2 read real values out of them. Several `NO` verdicts fall.

### 12.1 #18 "Analyze" — SOLVED. The Testing Policy matrix is fully readable

`TDConnection.Customization.RBT` exposes the entire risk model. Read from the sandbox:

| Property | Value |
|---|---|
| `TestingPolicyMatrix[risk][complexity]` | rows `1 1 1` / `2 2 2` / `3 3 3` |
| `RiskCalculationMatrix[BI][FP]` | rows `1 1 2` / `1 2 3` / `2 3 3` |
| `TestingLevelPercentage[1..4]` | `100, 66, 33, 0` (Full / Partial / Basic / None) |
| `TestingEffortForFCLevel[1..3]` | `18, 15, 12` |
| `DisplayedTimeUnits` | `Hours` |
| `BIQuestions` / `FCQuestions` / `FPQuestions` | 4 / 4 / 4 |

Note this project's `TestingPolicyMatrix` is **risk-only** — the testing level tracks the row index
and ignores functional complexity — whereas `RiskCalculationMatrix` genuinely combines both axes.
Do not hardcode either; they are per-project admin config, and this is exactly one project's values.

Also present: `CalcBILevelByAnswersWeight`, `CalcFCLevelByAnswersWeight`, `CalcFPLevelByAnswersWeight`,
`Translate*Level`, and full question CRUD (`AddBIQuestion` / `DeleteFCQuestion` / …).

**Verdict: `NO` → `FULL*` (client-side).** The web research was right that Analyze is a documented
lookup, not a hidden algorithm — and the lookup table itself is now readable. The `rbt-*` fields are
already REST-writable, so Alt-ALM reads these matrices once per project (they are admin config and
change rarely) and computes Testing Level / Testing Time itself, exactly like Impact Analysis (#13)
and Traceability Matrix (#14). **Reading the matrix needs OTA**; the arithmetic and the writes do not.

⚠️ **Accessor shape**: these are *parameterized properties*. A plain read throws
`"Number of parameters specified does not match the expected number"` — the matrices need two
indices, `TestingLevelPercentage`/`TestingEffortForFCLevel` need one, and `AlertList` needs one. That
error means "you passed the wrong arity", **not** "unsupported".

### 12.2 Other rows that fall

| Row | Finding | New verdict |
|---|---|---|
| **#145** Scorecard/KPI | `Customization.KPITypes.KPITypes` → **11 types** readable by name (Automated Tests, Covered Requirements, Defects Fixed per Day, Passed Requirements, Passed Tests, …); `AddKPIType`/`RemoveKPIType` present; `KPIFactory.NewList('')` works (0 items — none defined here) | `NO` → **OTA** |
| **#132/#133** Report authoring | `Customization.ReportProjectTemplates` → **79 templates** enumerable, with Add/Get/Remove | `NO` → **OTA** (authoring new SQL still out of scope by hard constraint) |
| **#129** Business View graphs | `Customization.BusinessViews` → **37 views** enumerable (Components, Defects, Defects_Assigned_to_me, …); `GraphBuilder` exposes `BuildGraph`, `BuildMultipleGraphs`, `CreateGraphDefinition`, `GetGraphResultFromString` | `NO` → **OTA** |
| **#166/#109/#196/#197** Alerts | `AlertManager.AlertList(<filter>)` → OK, count 0 (none exist in the sandbox); `GetFilterText()` returns a real filter over `TableName:ALERT, ColumnName:AT_ALERT_TYPE`; `DeleteAlert`, `DeleteAlertsByFilter`, `CleanAllAlerts` present | `NO` → **OTA** |
| **#205** Data-hiding | `Customization.Modules` → 11 modules with `IsVisibleForGroup`/`VisibleForGroups`; `Customization.Permissions` exposes `CanModifyField`, `CanAddItem`, `CanRemoveItem`, `TransitionRules`; `Customization.UsersGroups` acquired | `NO` → **OTA** (per-module enumeration detail still `UNVERIFIED` — the per-item read needs the right accessor arity) |

### 12.3 A verdict that SURVIVES — #209/#210 workflow scripts

`Customization.Workflow` exposes **only two members**: `ProjectScriptsUpdated` and
`TemplateScriptsUpdated` — dirty flags, not script content. There is no route to read or evaluate the
VBScript, so Alt-ALM still cannot reproduce workflow-driven field visibility or dynamic `Required`
rendering. **`CONFIRMED NO` stands**, now on direct evidence rather than inference. The standing
limitation in `CLAUDE.md` is unchanged and correctly stated.

### 12.4 `OtaReport80.Reporter` is not registered

All three ProgIDs (`OtaReport80.Reporter`, `OtaReport80.ReportConfig`, `TDApiOle80.Reporter`) fail
`REGDB_E_CLASSNOTREG`. That component ships in a separate DLL not deployed by the ALM Client
Launcher. Report *templates* are reachable through `Customization.ReportProjectTemplates` regardless,
so the forum-sourced `Reporter` recipe is **not required** and is `UNVERIFIED` on this deployment.

### 12.5 Method note for future OTA work

Phase 1's first run reported `String` members (`PadLeft`, `Substring`, …) for every COM object. Cause:
the helper used `Write-Output` for status lines, so each function returned
`[status-string, real-object]` and the caller silently got an array. This is the trap already
documented in the `alm-live-probe` skill §4 — **use `Write-Host` in any helper that also returns a
value.** Worth flagging because the corrupted output was plausible enough to have been believed.

## Probe 13 — session teardown semantics, 2026-08-13 (`scripts/probe/probe-session-teardown.ps1` + the BFF contract test)

**Trigger**: the first live run of `AlmAuthClientContractTest` — P0's exit criterion — failed on its
logout assertion. Investigating that failure produced this probe. It is the first finding in the
project surfaced by *product code under test* rather than by a hand-written probe.

### The headline: `DELETE site-session` is only half a logout

| Step | Result |
|---|---|
| `DELETE /qcbin/rest/site-session` (+ XSRF) | **200** |
| …then a project-scoped read | **401** — the project session is gone |
| …then `GET /qcbin/v2/rest/is-authenticated` | **200** — the authentication is **not** gone |
| `POST /qcbin/authentication-point/logout` (+ XSRF) | **200 or 500** (varies; see below) |
| …then replaying the same cookies | **500** `TokenId is invalid because it has logged out` |

Stable across 3 scripted iterations plus 8 contract-suite runs.

**Consequence**: a client that logs out with `DELETE site-session` alone leaves the service account
authenticated server-side — **one leaked identity per session**, which for a pool is the whole pool.
`AlmAuthClient.logout()` was doing exactly that, and now issues both calls. This is the bug the
contract test was written to catch, caught on its first run.

### `POST authentication-point/logout` needs `X-XSRF-TOKEN` like any other non-GET

Without the header it returns **401 and silently does nothing** — the session stays fully usable
(is-authenticated 200, project read 200). This looked at first like an *ordering* constraint ("you
must delete the site session before you may log out"); it is not. It is the ordinary XSRF gate
(§2.2), and the reason case A appeared to make logout "work" is that after `DELETE site-session`
the gate no longer applies. **Stable, 3/3.**

`GET /qcbin/authentication-point/logout` also works here (200, full teardown, no XSRF needed since
it is a GET) — but OpenText disabled GET-logout by default in 24.1, so POST + XSRF is the portable
choice and is what the BFF does.

### ⚠️ A 5xx that means "your token is dead", not "your write may have committed"

Replaying a logged-out session's cookies returns **HTTP 500** with body
`{"Id":"qccore.general-error","Title":"TokenId is invalid because it has logged out",…}`, not a 401.

This collides with the standing write-safety rule (*every 5xx write is an unknown outcome, verify by
query*). Here the 5xx is definitive: the request never reached entity processing, so nothing can have
committed. `AlmWriteOutcome` classifies it `UNKNOWN` today, which is **wasteful but safe** — it
triggers a needless verify-by-query rather than a wrong answer — so it is deliberately left alone.
Narrowing it would mean asserting "this message implies no commit", which is an inference, not an
observation. `UNVERIFIED`; the experiment is a deliberate write on a logged-out session followed by a
re-authenticated query for the row.

The status of the `POST logout` call itself varied (200, 500, 500, 200 across four runs) with no code
change between them — the 500 carrying the same "already logged out" body. The *outcome* never
varied: the session was dead every time. `logout()` therefore ignores the status by design.

### Also settled: `POST site-session` is redundant after `oauth2/login` — **open item #2, closed**

A project-scoped read succeeds using the `oauth2/login` cookies alone, with **no** `POST site-session`
call at all → **200**. The call is kept in `AlmAuthClient.login()` because it is the documented flow
and costs one request at session open, but it is confirmed not load-bearing on this server.

Related bug found while chasing this: `login()` built the session from the login response and then
made the site-session call **without merging the cookies that response sets**, leaving us holding a
pre-site-session `QCSession`. Fixed by merging both responses. The symptom was nasty — every call
kept working, because project reads authenticate off LWSSO regardless, so the mismatch stayed
invisible until teardown targeted a different session than the one we held.

### ⚠️ Intermittency worth knowing about

Immediately after `DELETE site-session`, the project-scoped read returned **401 on 6 of 8 runs and
200 on 2**, with no code change between them — while the single-connection PowerShell client returned
401 on 3/3. `UNVERIFIED` hypothesis: this SaaS deployment load-balances across nodes that learn about
the teardown at different times. Experiment: capture the LB/routing response headers on the `DELETE`
and the following `GET` and check whether the 200s correlate with a node change. Tracked as **Q40**.
The contract test records this value but deliberately does **not** assert on it.

### Design consequence: `is-authenticated` is not a session-liveness check

It reports on the LWSSO token, not the site session, and there is a real state where it says 200
while every project-scoped call says 401. Pool liveness must use `GET /rest/site-session` (the
keepalive), which tests the thing that determines whether a request will actually work.

## Probe 14 — CORS: can a static SPA call `/qcbin` directly? 2026-08-13 (`scripts/probe/probe-cors.ps1`)

**Trigger**: a hosting question — *could the SPA live on GitHub Pages and talk to ALM directly, so no
server has to be paid for?* ADR 0001 rejected direct browser access partly on "no CORS allowance for
arbitrary third-party origins is documented or observed", which is an **absence of evidence, not a
test**. This is the test.

| Request (as a browser would send it) | Result |
|---|---|
| `OPTIONS /rest/oauth2/login` + `Origin` + `Access-Control-Request-Method: POST` | **501 Not Implemented**, no CORS headers |
| `GET /v2/rest/is-authenticated` + `Origin` | **302** to the login form, no CORS headers |
| `POST /rest/oauth2/login` + `Origin` (credentialed) | **200 — and still no `Access-Control-Allow-Origin`** |

**Verdict: a browser cannot call `/qcbin` cross-origin. Definitive.** Two independent failures — the
preflight is rejected outright (501), so the real request never leaves the browser; and even the call
that *does* succeed server-side returns no `Access-Control-Allow-Origin`, so the browser would refuse
to let JS read the response.

The third row is the one worth remembering: **the server processes the request fine.** Anyone testing
this with curl or Postman will see 200 and conclude it works. Only a real browser enforces CORS, and
only the missing response header reveals it.

**This upgrades ADR 0001's central premise from "not observed" to probe-verified**, and closes the
"static SPA, no server" option permanently — not on cost or preference, but on mechanism.

## Fixtures captured (redacted; under `tests/fixtures/`)

- `customization-fields-<entity>.json` × 15
- `customization-used-lists.json`, `customization-lists.json`
- `customization-requirement-types.txt`
- `api-doc-v2-openapi.json` + `api-doc-v2-paths.txt` (project-API Swagger, 24.1+ additions)
- `api-doc-sa-v2-openapi.json` + `api-doc-sa-v2-paths.txt` (Site Admin Swagger, 178 ops)
- `resource-list-site.json` (full endpoint inventory, 1,111 ops)

Redaction = host/domain/project/key strings replaced with `REDACTED` before write. User data and
entity data are not captured.

---

## Probe 15 — P1 phase-start: grids, tree roots, paging. 2026-08-14 (`scripts/probe/probe-grids.ps1` + `probe-grids-2.ps1`)

Read-only. The three questions P1 (read-only Alt-ALM) could not be designed around: the `alm-web`
dialect body shape (open item #10, R11, Q2), tree-root discovery including the UNVERIFIED
release-folder root (open item #10), and whether the page-size cap is silent.

Two of the three came back differently than the plan assumed, and one of those was a planned
implementation that would have shipped a visible bug.

### 15.1 ⚠️ `{parent-id[0]}` is NOT the universal root-discovery rule — and it silently returns the WRONG node

`implementation-plan.md` P1 and `alm-data-model.md` §2.1 both specify runtime root discovery via
`?query={parent-id[0]}`. That rule was generalized from the two trees it was tested on. Probed across
all six trees:

| Tree | `{parent-id[0]}` returns | `{parent-id[-1]}` returns | The **actual** root |
|---|---|---|---|
| `requirements` | *(none)* | id=0 `Requirements` | id=0, parent `-1` |
| `test-folders` | id=2 `Subject` ✅ | *(none)* | id=2, parent `0` |
| `test-set-folders` | id=1 **`Recycle Bin`** ❌ | id=0 `Root` | id=0, parent `-1` |
| `release-folders` | *(none)* | id=1 `Releases` | id=1, parent `-1` |
| `bpm-folders` | *(none)* | id=1 `Models` | id=1, parent `-1` |
| `resource-folders` | id=1 `Resources` ✅ | *(none)* | id=1, parent `0` |

**The rule works for 2 of 6 trees.** For three it returns nothing, which is a loud failure. For
`test-set-folders` it returns **`Recycle Bin`** — a real node, one row, HTTP 200, indistinguishable
from success. Alt-ALM's Test Lab tree would have rendered the recycle bin as the root of the tree and
nothing would have looked broken.

**Verified discovery rule — query `{parent-id[-1]}` first, fall back to `{parent-id[0]}`.** That
resolves the correct root for all six trees. Root *parents* are not consistent across trees (`-1` for
four, `0` for two) and root *ids* are not consistent either (`0`, `1`, `2`), which is exactly why
ADR 0005 forbids hardcoding them — but the previously-documented discovery query was not a safe
substitute for hardcoding, because it fails silently on one tree.

**This also closes the release-folder root question** (open item #10): root is id=1 `Releases`,
parent `-1` — and confirms that every past release probe using `parent-id=1` was accidentally
correct, having skipped the discovery query that would have returned nothing.

### 15.2 The `alm-web` dialect is real, materially different, and broader than advertised

**Q2/R11 answered.** `Accept: application/json;schema=alm-web` returns a genuinely different body,
not a cosmetic variant.

On `groups/{groupsFields}` the difference is a **rename and an unwrap** — same information:

```
plain  : {"subLevel":[{"subLevel":[],"Expression":"1","ReferenceValue":"Folder","Name":"type-id","Value":"1","size":1}]}
alm-web: [{"name":"type-id","value":"1","expression":"1","referenceValue":"Folder","size":1,"subGroups":[]}]
```

Both carry `size` (the group count) and `expression` (the filter expression that drills into the
group), so **server-side group-by is viable on either media type** — P1's client-side aggregation
fallback is no longer forced. The plain form is sufficient; the dialect is not required for grouping.

**The significant result is on a plain collection read**, which is not one of the 42 operations that
advertise the dialect:

```
plain  : {"entities":[{"Fields":[{"Name":"name","values":[{"value":"Requirements"}]},
                                 {"Name":"id","values":[{"value":"0"}]}],
                       "Type":"requirement","ErrorMessage":"","EntityStatus":"Success",
                       "children-count":0}],"TotalResults":1}
alm-web: {"results":[{"entity":{"$entityType":"requirement","name":"Requirements","id":"0"}}],
          "total-count":1}
```

The dialect returns **flat, denormalized entities** — no `Fields`/`values` envelope at all. That is
the single most tedious piece of ALM's wire format, and this collapses it.

⚠️ **Do not adopt it in the mainline yet, for two reasons.** First, `children-count` is present in the
plain body and **absent** from the alm-web body — the dialect is not a strict superset, and a tree UI
needs `children-count`. Second and decisively: the collection GET does **not** advertise this media
type in the resource-list inventory, so relying on it here is **undocumented behaviour**, which
`CLAUDE.md` routes to the risk register rather than to an implementation. Recorded as **R15**.
`api-ref` §3.5's "42 of 1,111 operations" now reads as *what is advertised*, not *what responds*.

### 15.3 Paging — the 2,000 bound is real, `max` exists, and `page-size=0` is a trap

`page-size=-1` → **HTTP 404** `qccore.invalid-query-value`:

> *"Page size values can be either integer between 0 and 2,000 or `"max"` to get the maximum
> available page size."*

Three things fall out. The 2,000 bound is **server-enforced and stated by the server**, not merely a
`REST_API_MAX_PAGE_SIZE` site parameter we inferred. A **`page-size=max` keyword exists** — untested
in any previous probe, accepted here (HTTP 200) — which is a cleaner way to request a full page than
guessing 2000. And an out-of-range value returns **404**, not 400, so error handling must not key off
the status code (consistent with §3.4's "always parse `Id`/`Title`").

⚠️ **`page-size=0` returns `HTTP 200` with `TotalResults=0` on a collection that has 2 rows.** The
total is not a property of the collection — it reflects the page. A grid that reads `TotalResults` to
decide "this collection is empty" will be wrong whenever page-size is 0.

**Whether an over-cap `page-size` is silently clamped remains `UNVERIFIED`** — `2001`, `5000` and
`max` were all accepted with HTTP 200, but the sandbox's largest collection has **2 rows**, so no
value could be distinguished from any other. The experiment that would settle it: generate >2,000
rows in one collection, request `page-size=5000`, and check whether the returned entity count is
2,000 (silent clamp) or 5,000. **This is blocked on the record generator**, not on API access.

### 15.4 ⚠️ The sandbox is effectively empty — this blocks P1's exit criteria

Row counts observed: `requirements` **1** (the root node itself), `test-sets` **1**, `test-folders`
**2** (Subject + Recycle Bin), and **0** each for `tests`, `defects`, `test-instances`, `runs`,
`design-steps`, `releases`, `release-cycles`.

P1's exit criterion is *"grids render live for requirements/tests/defects"*. Against 0 tests and 0
defects a grid cannot be meaningfully demonstrated, paging cannot be exercised, and §15.3's clamp
question cannot be answered. This is a **sequencing finding, not an API finding**: the record
generator (P4) is a prerequisite for *validating* P1, even though P1 does not depend on it to be
built. Raised as **Q45**.

---

## Probe 16 — other projects are readable; Q45 dissolves. 2026-08-14 (`scripts/probe/probe-projects.ps1` + `-2.ps1`)

**Authorization**: the user granted read access to other projects on 2026-08-14 — *"you can use
other projects as a read ONLY"* — and separately allowed their data to seed sandbox records. Writes
remain restricted to the designated sandbox; this probe issues **GETs only**.

⚠️ **Disclosure rules applied here, and they are not optional.** These are other teams' projects in
the same tenant. Real domain/project names are **pseudonymized** (`PROJECT-5`) in all output and in
this log; the mapping is written to **`Secrets/alm-read-projects.json`**, which is git-ignored.
Foreign node names, requirement text, owners and every other field value are **counted, never
printed and never captured to a fixture**. The sandbox may be seeded from this data, but the **repo
never receives it** — the repo is the artifact that outlives the sandbox.

### 16.1 Eight readable projects, and one is properly populated

One domain, nine projects, all readable with the existing key:

| Project | requirements | tests | defects | test-sets | test-instances | runs |
|---|---|---|---|---|---|---|
| `PROJECT-5` | 233 | 129 | 80 | 22 | 227 | 178 |
| `PROJECT-3` | 69 | 1 | 343 | 2 | 1 | 1 |
| `PROJECT-1` | 15 | 34 | 28 | 3 | 2 | 1 |
| `PROJECT-8` | 12 | 15 | 9 | 15 | 9 | 14 |
| `PROJECT-7` | 18 | 6 | 2 | 5 | 7 | 11 |
| *(our sandbox)* | 1 | 0 | 0 | 1 | 0 | 0 |

**`PROJECT-5` (847 rows) is the P1 read target.** It has real trees, real coverage, real runs.

**Q45 dissolves.** P1's exit criterion — grids rendering live for requirements/tests/defects — is
satisfiable *now*, against live data, without the record generator. The generator returns to being a
P4 concern needed for **write** testing, which is where the plan always had it. No phase reordering
is required after all.

### 16.2 ⚠️ A plain GET returned HTTP 500 once and never again

Probe 16's first pass got `HTTP 500` on `GET test-sets?fields=id&page-size=1` for `PROJECT-5`, while
every other collection on that same project returned 200 in the same session.

Probe 16b tried to characterize it: **5 query variants, the parent tree, sibling collections, and
the same collection across all 9 projects — 13 requests, all HTTP 200.** `PROJECT-5/test-sets` reads
fine and holds 22 rows.

**Observed once, not reproduced. Cause `UNVERIFIED`.** Recorded because of what it implies, not
because it is understood: the project already treats a 5xx on a *write* as "unknown outcome, verify
by query" (§3.3), and this is the first evidence that a plain **read** can also fail transiently.
P1's grid needs a bounded retry on 5xx reads rather than surfacing a hard error on the first one.

Plausible link, not a claim: this matches the intermittency pattern behind **Q40** (a 6/8 vs 2/8
split on a project read after teardown, hypothesized to be SaaS load-balancing across nodes that
disagree). Two independent intermittency observations now point the same way. Tracked as **Q46**.

### 16.3 The corrected tree-root rule re-verified on a populated project — and now explained

Probe 15 derived the `{parent-id[-1]}` → `{parent-id[0]}` fallback rule from a **near-empty**
project, which is precisely the subset-generalization mistake probe 15 itself was about. Re-run
against `PROJECT-5`:

| Tree | `{parent-id[-1]}` | `{parent-id[0]}` |
|---|---|---|
| `requirements` | **1** (the root) | 4 |
| `test-folders` | 0 | **1** (the root) |
| `test-set-folders` | **1** (the root) | 6 |
| `release-folders` | **1** | 0 |
| `bpm-folders` | **1** | 0 |
| `resource-folders` | 0 | **1** |

**The rule holds** — 12 tree instances across 2 projects now.

More usefully, the populated project **explains the earlier bug**. `{parent-id[0]}` does not mean
"find the root". It means *"rows whose parent is node 0"* — i.e. **the children of node 0**. For
`requirements` and `test-set-folders`, whose root **is** id 0, it therefore returns the root's
children: here 4 and 6 rows, obviously not roots. In the empty sandbox `test-set-folders` had exactly
**one** child — `Recycle Bin` — so "children of the root" and "the root" were indistinguishable, and
the wrong rule looked correct. It only ever worked for `test-folders` and `resource-folders` by
coincidence: their roots happen to be parented to a node `0` that does not exist.

So the honest statement of the rule is a statement about the data model: **a tree root is parented to
`-1` or to `0`, varying by tree; query `{parent-id[-1]}` first because no real node has id `-1`, and
fall back to `{parent-id[0]}` only for the trees whose roots use `0`.**

---

## Probe 17 — the `order-by` separator, because our own reference contradicted itself. 2026-08-14 (`scripts/probe/probe-orderby.ps1`)

Read-only, against the populated read-only project (`PROJECT-5`). Ordering of opaque ids only — no
field content read or printed.

**Origin**: this probe was not planned. It came from building `AlmQuery`, whose author noticed that
`alm-api-reference.md` §4.3 wrote the multi-field `order-by` separator as a **comma** in its grammar
line and as a **semicolon** in its own worked example one line below. Rather than pick one, the
ambiguity was flagged `UNVERIFIED` and probed.

| Request | Result |
|---|---|
| `order-by={id}` | 200, ascending |
| `order-by={id[DESC]}` | 200, descending |
| `order-by={type-id;id}` | **200, correctly sorted** |
| `order-by={type-id,id}` | **404** `not existing field: "type-id,id"` |
| `order-by={type-id;id[DESC]}` | 200 — `[DESC]` works on a secondary key |
| `order-by={type-id,id[DESC]}` | 404, same message |
| `order-by={type-id\|id}` | 404, same message |
| `order-by={no-such-field}` | 404, same message |

**The separator is `;`.** §4.3 has been corrected.

⚠️ **The more useful finding is the failure mode.** A comma is not a *wrong separator* — it is not a
separator at all. The server reads `type-id,id` as a single field name and reports a **missing
field**, which is the *same* error a genuine typo produces. So a malformed multi-field sort is
indistinguishable from a misspelled column, and there is no syntax error to catch. Any client that
builds `order-by` from user input needs to validate field names against metadata itself; the server
will not tell it which of the two mistakes was made.

Also note the status: **404**, not 400 — a third instance of the §3.4 rule that error handling must
parse `Id`/`Title` and never branch on the HTTP status (the others being an out-of-range `page-size`
→ 404 in probe 15, and the logged-out-token 500 in probe 13).

**Process note.** This is the first defect found in the project's *own* reference documentation by
code written against it, as opposed to a finding about ALM. The reference has been treated as
settled since planning closed; it contained a self-contradiction on a line that had been read many
times. Worth remembering when the next "the docs say X" argument comes up.

---

## Probe 18 — does ALM accept percent-encoded grammar characters? 2026-08-14

Read-only, sandbox. Six requests.

**Origin**: a contract test failed with `IllegalArgumentException: Illegal character in query at
index 121`. Not a server error — the request never left the JVM. ALM's query grammar is built from
`{ } [ ] ;`, every one of which RFC 3986 forbids unencoded in a query, so `URI.create` rejects it.
Every filtered, sorted or paged read was broken, while the unit tests stayed green because they
asserted on the query *string* and never constructed a URI.

The two obvious escapes are both bad: handing the URL to `RestClient` as a `String` makes it treat
braces as URI template variables (and the grammar is made of braces), and hand-rolling a permissive
URI type is worse. That leaves percent-encoding — which is only viable if the server accepts it.

| Request | Result |
|---|---|
| `order-by={id}` | 200, `TotalResults=1` |
| `order-by=%7Bid%7D` | **200, `TotalResults=1`** |
| `order-by={type-id;id}` | 200, `TotalResults=1` |
| `order-by=%7Btype-id%3Bid%7D` | **200, `TotalResults=1`** |
| `query={parent-id[-1]}` | 200, `TotalResults=1` |
| `query=%7Bparent-id%5B-1%5D%7D` | **200, `TotalResults=1`** |

**Percent-encoded and raw are equivalent** — identical status and identical result sets, including a
filter containing brackets and a negative number. `AlmEntityClient.almUri` therefore encodes the
structural characters and leaves `? & =` alone.

**Worth noting for anyone porting this**: every probe script in this repo is PowerShell, and
`Invoke-WebRequest` happily sends raw braces. So thirteen probe rounds never encountered this, and a
grammar that "obviously works" turned out to be unusable from a standards-compliant HTTP client
without a translation step. A finding about the *client*, not the server — and one that only appeared
because product code was put under a live test.

---

## Probe 19 — `children-count` is always 0. 2026-08-14 (`scripts/probe/probe-childcount.ps1`)

Read-only, against the populated read-only project. Ids and counts only.

**Origin**: the P1 folder tree rendered with no expanders. Every folder reported
`hasChildren=false`, yet filtering the grid by one of those folders returned a row — so the folders
plainly had children.

| Read | `id:children-count` |
|---|---|
| `fields=id,name,parent-id` (what the BFF sends) | `133:0  265:0  318:0  339:0` |
| `fields=id` only | `133:0  265:0  318:0  339:0` |
| no `fields` projection at all | `133:0  265:0  318:0  339:0` |
| with an explicit `order-by` | `318:0  265:0  339:0  133:0` |

And the same folders, counted by querying their children directly:

| Folder | actual children |
|---|---|
| 133 | **9** |
| 265 | **6** |
| 318 | **1** |
| 339 | **1** |

`test-folders` behaves identically — nine child folders, every one reporting 0.

**`children-count` is present in the envelope and always zero.** Not a projection artifact, not
tree-specific, not fixed by asking differently. It is not a usable "does this node have children"
signal on this ALM version.

**Consequence for the UI**: the tree cannot know in advance which nodes expand. Alt-ALM's tree now
reports every node as *possibly* expandable and discovers the truth on expansion; a node that turns
out to be empty renders as empty. The cost is one wasted request per leaf, against the alternative
of a tree nobody can navigate.

⚠️ **This retracts half of R15's justification.** That row gave two reasons not to adopt the
`alm-web` dialect: it is undocumented on collection reads, *and* it omits `children-count` which
"tree UIs need". The second reason was wrong — the field is worthless in either shape, so its
absence costs nothing. R15 stands on the undocumented-behaviour reason alone, which was always the
stronger one. Recorded because a wrong reason attached to a right conclusion is exactly the kind of
thing that gets cited later as if it were verified.

---

## Probe 20 — `parent-id` accepts `OR`, so one query resolves many parents. 2026-08-14

Read-only, through the running BFF (so `AlmAccessPolicy` gated every call), against the populated
read-only project. Ids and counts only. Scripts: `scripts/probe/probe-batch-children.py`.

**Origin**: probe 19 left the tree unable to know which nodes expand. The question was whether the
children query — `requirements?query={parent-id[N]}`, which is how child requirements are already
fetched — can be asked for several parents at once.

**Hypothesis**: `OR` inside one field's brackets works. api-ref §4.3 documented it, but tagged
`[docs-research]` — never probed. Refuting observation: any non-200, or a row set that differs from
the same parents queried one at a time.

**Confirmed.** Ground truth was built independently, by reading all 233 requirements in one page and
grouping them by their own `parent-id` field, so the check does not depend on the mechanism it tests.

| OR terms | query length | rows | distinct parents | matches ground truth |
|---|---|---|---|---|
| 8 | 50 | 19 | 4 | ✅ |
| 16 | 106 | 29 | 7 | ✅ |
| 32 | 218 | 42 | 11 | ✅ |
| 63 | 435 | 74 | 22 | ✅ |
| 100 | 694 | 113 | 31 | ✅ |
| **233** (every id in the project) | **1,625** | 232 | 62 | ✅ |

Four parents queried individually returned `318:1  265:6  339:1  133:9`; the same four batched into
one request returned exactly those counts. `test-folders` batches the same way (20 terms → 22 rows).

**Two consequences.**

1. **The expander problem is solvable exactly, not optimistically.** One query per *level* — batching
   that level's ids — returns every child of every node on it, so `hasChildren` becomes a fact
   (a parent absent from the result has no children) instead of the always-true guess probe 19
   forced. Cost: one request per level rather than one per node.
2. **For a tree that fits one page, the whole hierarchy is one request.** 233 requirements are well
   under the 2,000 cap, and every row already carries its own `parent-id` — the ground-truth map
   above *was* the entire tree, built from a single call.

⚠️ **The upper bound is unprobed.** 1,625 characters is the longest query tested; it is not a limit,
just the largest project available. Servers and proxies cap URL length (commonly ~8 KB) and ALM's
own limit is unknown, so any implementation must **chunk** the id list rather than assume one request
always suffices. A tree of 2,000 nodes would produce a ~14 KB query. Untested, therefore chunk.

⚠️ **`OR` is a bare keyword inside the brackets, and `AlmQuery.filter` currently forbids `; [ ] }`
but not spaces or `OR`** — so a user typing `OR` into the grid's name filter already reaches ALM as
an operator, not as literal text. Not a new hole (it predates this probe), but this probe is what
makes it visible. Recorded as **Q47**.

---

## Probe 21 — which fields and tabs does ALM render, and can the API tell us? 2026-08-17

Read-only, GET only, all 9 projects. `scripts/probe/probe-field-visibility.py`. Field names, labels
and flags only — never a field value from a borrowed project.

**Origin**: the user supplied two stock-client screenshots (a Requirements grid with a bottom tab
strip, and the Requirement Details dialog) and asked whether the tab set and the form's field set
are discoverable from the API, doubting it was "just the memo fields".

### 21.1 The field-metadata flags — one is useless, two are load-bearing

`customization/entities/requirement/fields` returns **24 attributes per field**, of which our
`FieldDescriptor` was parsing 11. The three that matter here were all being discarded:

| Flag | Requirements, across all 9 projects |
|---|---|
| `visible` | **true for 100% of fields** — 74/74, 76/76, 82/82. Not a visibility signal at all |
| `visibleInWebUI` | 45–53 of 74–82 |
| `active` | 42–55 |
| **`active` ∧ `visibleInWebUI`** | **17–30**, tracking each project's customization |
| **`active` ∧ ¬`visibleInWebUI`** | **exactly 25 in every one of the 9 projects** |

⚠️ **`visible` looks like the obvious answer and is worthless.** Anyone reaching for a
"which fields does ALM show" flag will find it first and get all 74.

The invariant 25 are the `rbt-*` risk fields plus `req-type` — i.e. **ALM's Risk Analysis tab**.
That a customization-driven number is identical across nine independently-configured projects is
what identifies it as a built-in group rather than a coincidence.

### 21.2 Memo fields → tabs, and it is a filtered subset

Memo fields where `active` ∧ `visibleInWebUI`:

- **8 of 9 projects: exactly `Comments`, `Description`, `Rich Text`** — precisely the memo tabs in
  the stock client's bottom strip.
- **1 project also has two custom MLT fields**, both of which are `active` ∧ `visibleInWebUI`, so
  the rule predicts they get their own tabs too. This is the direct evidence that custom
  multi-line-text fields become tabs.
- The other memo fields (`request-note`, the three `rbt-*-data`, `vc-checkin/checkout-comments`)
  are **not** `active` ∧ `visibleInWebUI`, and the stock client does not tab them.

So Alt-ALM's first cut — one tab per memo field, all nine — was too generous by six.

### 21.3 The Details form set — a 16/17 approximation, NOT a rule

Requirement **605** was located (PROJECT-8, 80 fields). Predicted form set =
`active` ∧ `visibleInWebUI` ∧ not-Memo = **20 fields**. The stock dialog shows 3 in its header
(Req ID, Name, Requirement Type) and **17 in the two-column body**. 20 − 3 = 17. The counts agree,
and **16 of the 17 names agree**.

⚠️ **Two do not, one in each direction:**

| Field | Metadata says | Stock client does |
|---|---|---|
| `father-name` "Req Parent" | `active` ∧ `visibleInWebUI` | **not on the form** |
| `req-type` "Old Type (obsolete)" | `active` ∧ ¬`visibleInWebUI` | **on the form** |

**Therefore `active ∧ visibleInWebUI` is a good approximation of ALM's form, not a derivation of
it.** It gets the size right and 94% of the membership, and it is wrong in both directions. This is
exactly the failure shape this project keeps meeting — a rule that looks confirmed because the
counts line up. Label it an approximation wherever it is used.

### 21.4 ⚠️ RETRACTED — "the related-entity tabs are NOT enumerable" was WRONG

**This section originally concluded that the related-entity tabs cannot be discovered from the API.
That conclusion was wrong, and it was overturned within the same session — see 21.6.** The
observation below (that `resource-list` is absent from an entity instance) is accurate; the
*inference* drawn from it was not.

The mistake is the one `CLAUDE.md` names explicitly: **an unexamined assumption about the shape of
the question, not too few attempts.** Having found no per-instance resource list, I concluded the
information did not exist, without asking whether it lived somewhere else — and it does, at a
documented endpoint (`customization/entities/{entity}/relations/`) that this project had never
probed. Left in place, unedited, because a retracted verdict is more useful than a quietly
corrected one: this is now the **third** confident negative this project has had to overturn.

The accurate part follows.

### 21.4a `resource-list` is absent from an entity instance

The remaining tabs (Attachments, History, Linked Defects, Requirement Traceability, Test Coverage,
Business Models Linkage) are related entities, not fields. Asked whether an entity advertises them:

| Read | `resource-list` present? |
|---|---|
| `GET requirements/{id}`, `Accept: application/json` | **No** — keys are only `Fields`, `Type`, `children-count` |
| `GET requirements/{id}`, `Accept: application/xml` | **No** |
| `GET requirements`, `Accept: application/xml` | **No** |

Checked in XML explicitly because this project has twice recorded a confident negative that was
wrong, and because `CLAUDE.md` tells us to re-read the per-instance `resource-list` for sibling
collections — that guidance came from a **site-level** resource list
(`tests/fixtures/resource-list-site.json`), not a per-entity one. **A requirement instance does not
advertise its sub-resources.** That observation stands; see 21.6 for where the information actually
lives.

### 21.5 Per-subtype fields are real, but do NOT explain the 16/17 mismatch

Web research named `customization/entities/{entity}/types/{subtypeId}/fields` as the documented
mechanism that narrows fields per requirement type. Probed on record 605's project:

| Type | id | fields | active+web | non-memo |
|---|---|---|---|---|
| **Undefined** (record 605's type) | 0 | 78 | 23 | **20** |
| Folder | 1 | 70 | 16 | 13 |
| Group | 2 | 70 | 16 | 13 |
| Functional | 3 | 71 | 17 | 14 |
| Business | 4 | 70 | 16 | 13 |
| Testing | 5 | 71 | 17 | 14 |
| Performance | 6 | 71 | 17 | 14 |
| Business Model | 66 | 71 | 17 | 14 |

**Subtype filtering is real and should be used** — the per-type sets genuinely differ, and using the
entity-level `fields` for a typed record over-shows. But for record 605 (type 0, "Undefined") the
non-memo set is still **20, with `father-name` present and `req-type` absent** — identical to the
entity-level answer. So the two discrepancies in 21.3 are **not** a subtype artefact and remain
unexplained by any metadata we can read. Consistent with the web finding that the real layout lives
in workflow-script `PageNo`/`ViewOrder`, which REST does not serve.

### 21.6 ✅ The related-entity tabs ARE enumerable — `customization/entities/{e}/relations/`

`GET …/customization/entities/requirement/relations/` → **HTTP 200**, 22 relations, each carrying a
human-readable **`Label`** and a **`Features`** array whose members are `UI_HIERARCHY` and
`UI_LINKED_ENTITIES`. The labels are the stock client's tab names, verbatim:

| Relation `Label` | `TargetEntity` | Dialog tab in the reference screenshot |
|---|---|---|
| `Requirement Attachments` | `attachment` | **Attachments** |
| `Linked Defects` | `defect` / `defect-link` | **Linked Defects** |
| `Traced From Requirements` / `Traced To Requirements` | `req-trace` | **Requirement Traceability** |
| `Test Coverage` / `Requirement Coverage` | `requirement-coverage` | **Test Coverage** |
| `Business Models Linkage` | `requirement` via `bpm-link` | **Business Models Linkage** |
| `Comments` | `comment` | (the Comments memo tab) |
| `Requirement to Release` / `Requirement to Cycle` | `release`, `release-cycle` | — (field-backed) |

`test` returns 27 relations (Design Steps, Test Parameters, Test Configurations, Test Instances,
Run, Linked Defects…), `defect` returns 17. **This is a per-project, per-entity, API-derived source
for the related-entity tab set** — no hardcoded list needed, and it names the target collection to
read for each tab.

⚠️ **`UI_HIERARCHY` and `UI_LINKED_ENTITIES` are the only two feature names observed**, and both
appear on every relation returned, so they do **not** discriminate which relations become tabs. The
`Label` is the useful signal; the features are not a filter. Do not read more into them than that.

Not covered by relations: **History** (the `…/{id}/audits` sub-resource, coverage known-partial per
api-ref §9) and **Risk Analysis** (the `active` ∧ ¬`visibleInWebUI` field group from 21.1).

### 21.8 What the documentation says — why the form itself is unreachable

Web research (agent pass, sources below) settles *why* 21.3's two mismatches cannot be resolved from
metadata: **ALM does not store the form as data.**

- Which fields appear, in what order, **on which page/tab**, and for which user group is set at
  runtime by **workflow scripts** — `SetFieldApp(FieldName, Vis, Req, PNo, VOrder)` in the desktop
  client's VBScript, and the identical `field.IsVisible / IsRequired / PageNo / ViewOrder` quadruple
  in the Web Client's JavaScript
  ([Project script examples](https://admhelp.microfocus.com/alm/en/25.1/online_help/Content/Web_Runner/wf-examples.htm)).
  The Script Generators expose exactly three levers and nothing else: field visibility per user
  group, display order, and page/tab organisation
  ([Script Generators](https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/Project_Customization/cust_wkflw_scripts_toc.htm)).
- **REST does not serve script text**, and this matches probe 12's OTA finding that
  `Customization.Workflow` exposes only dirty flags.
- ⚠️ **OTA cannot recover it either.** `ICustomizationField4` has 47 documented properties including
  the per-group `VisibleForGroups` / `IsVisibleInNewBug` / `GrantModifyForGroup`, but **no `PageNo`,
  `ViewOrder`, `Tab` or `Page` property at all**
  ([ICustomizationField4](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic4367.html)).
  So the COM sidecar is not a route to the layout — worth knowing before anyone proposes it as one.
- The REST reference **defines none of** `Visible`, `VisibleInWebUI`, `System`, `Virtual`,
  `Editable`, `CanChangeRequired`. The only primary definition found is OTA's `IsActive` — *"Checks
  if the field can be displayed in the user interface"*
  ([IsActive](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic2167.html)) — which is
  consistent with what 21.1 measured.
- ⚠️ **The tempting reading that `visible` = desktop and `visibleInWebUI` = web client is
  UNVERIFIED.** No source states it. Our own measurement is only that `visible` is true for every
  field, which is equally consistent with other explanations.
- ⚠️ **Per-user-group data hiding is invisible to REST.** An admin can hide fields per group
  ([Hiding Data for a User Group](https://admhelp.microfocus.com/alm/en/15.5/online_help/Content/Admin/cust_groups_perms_hide_data.htm)),
  and none of it is reflected in the `fields` booleans. **A form built from this metadata will
  over-show fields for restricted groups.** That is a correctness limitation to state in the UI, not
  a rendering detail.

### 21.7 Consequences

- `FieldDescriptor` must carry `active` and `visibleInWebUI`. It currently drops both, so the BFF
  cannot express any of this.
- The detail pane's memo tabs must filter to `active` ∧ `visibleInWebUI` (3, not 9).
- A "Risk Analysis" grouping is available for free: `active` ∧ ¬`visibleInWebUI`.
- The form field set can be approximated but **not derived**; the two mismatches must be visible in
  the code as a known limitation, not smoothed over.

---

## Probe 22 — the relations payload, captured (2026-08-17)

Read-only, **sandbox only**, GET only. `scripts/probe/probe-relations.py`. Probe 21.6 read this
endpoint live and recorded counts; it saved no payload, so `AlmRelationParser` had nothing to be
built against. Fixtures now exist: `tests/fixtures/customization-relations-{requirement,test,defect}.json`.

**Sandbox only, deliberately.** A relations document is schema, not data — no record names, no
owners, no field values. It is still per-project customization, and the borrowed-project rule is
"counts and shapes only". Someone else's schema is their content; ours is not.

### 22.1 Shape

`{"Relation":[…],"TotalResults":22}` — singular key holding an array, and **PascalCase** property
names (`Label`, `TargetEntity`, `Type`), the opposite of the lowerCamel `fields` sibling uses. Also
⚠️ the path needs a **trailing slash**: `…/relations/`, where `…/fields` has none.

Counts confirm 21.6: requirement 22, test 27, defect 17.

### 22.2 ⚠️ Not every relation has a Label — this corrects 21.6

21.6 said "each carrying a human-readable `Label`". Measured on `requirement` alone, that was true.
**5 of `defect`'s 17 relations have no `Label` at all**, and every one is a field-backed reference:
`defectToTargetReleaseConnection`, `…DetectedInRelease…`, `…DetectedInReleaseCycle…`,
`…TargetReleaseCycle…`, `…EnvironmentConnection`. Those belong on the Details form, where they
already are. Absence of a label is therefore a usable signal, not a data defect.

### 22.3 ⚠️ `TargetEntity` is not what you read — the join entity is

Each relation's storage descriptor is either a `ReferenceStorage` (plain FK) or an
`AssociationStorage`, and the latter names its own `AssociationEntity`. So
`requirementToDefectConnection` has `TargetEntity: defect` but its rows live in **`defect-link`**.
Conflating the two yields a tab that queries the wrong collection and comes back plausibly empty.

### 22.4 ⚠️ `defect-link` and `assets-relation` are POLYMORPHIC join tables

The finding that shaped the implementation. **Nine of `defect`'s relations read `defect-link`**, to
nine different far ends — defect, requirement, run, run-step, test, test-config, test-instance,
test-set. Six of `test`'s read `assets-relation` similarly. Grouping tabs by "what gets read" —
the obvious rule, and the first one written — merges all nine into a single tab and shows linked
runs under the heading "Linked to Defects". Group by the **pair** (far end, entity read).

### 22.5 The tab set is an approximation, and cannot be better than one

With the pair rule plus "no label", "self-referential containment is the tree" and "a tab nothing
can fill is not shown", `requirement` reduces to **8** related tabs where the stock dialog has 5.
All three extras are the same shape — a join entity reachable both directly and through an
association (`req-trace`, `requirement-coverage`, `bpm-link` each appear twice).

⚠️ **The rule that would merge those is the rule that breaks `defect`.** Collapsing groups that
share a read entity fixes all three and simultaneously re-creates 22.4. **No rule derivable from
this payload gets both right**, because ALM's tab organisation is per-entity and lives in workflow
scripts no API serves (21.8). Documented limit, not a to-do.

The chosen error direction is over-showing: an extra tab is visible and dismissible, a wrong merge
silently shows one relation's rows under another's name. `AlmRelationSelector.Selection.dropped()`
records every discarded candidate with its reason, so the invisible direction stays auditable.

### 22.6 Relation `Type` values observed

Eight across the three captures: `link`, `connection`, `containment`, `composition`, `usage`,
`dependency`, `attachment`, `realization`. **Recorded, not enforced** — unlike field types, where
the closure over exactly 8 is a probe-verified invariant worth failing a parse on. An unseen ninth
relation type must not break an otherwise fine document.

## Probe 23 — how a tab's query is derived, and the trap in it (2026-08-17)

GET only. Sandbox for the existence checks; `PROJECT-5` (borrowed, read-only) for the shape counts.

### 23.1 The StorageDescriptor names the column to filter on

This is what makes the tab strip buildable without a per-entity special case:

| Storage shape | Read | Filter by |
|---|---|---|
| `ReferenceStorage` | `TargetEntity` | `ReferenceIdColumn` (+ `ReferenceTypeColumn` if present) |
| `AssociationStorage` | `AssociationEntity` | `AssociationSourceIdColumn` (+ a type column, see 23.3) |

So requirement→traceability is `req-traces?query={from-req-id[605]}`, and requirement→coverage is
`requirement-coverages?query={requirement-id[605]}`. **Verified live**: 29 coverage rows for one
`PROJECT-5` requirement, every row's `requirement-id` equal to the open record's id.

### 23.2 ⚠️ `defect-links` is one table serving SEVEN entity types

Distinct `second-endpoint-type` values across 74 rows in `PROJECT-5`:

```
defect 8 · requirement 2 · test 11 · run-step 34 · test-set 12 · run 6 · test-instance 1
```

The discriminator holds the **plain wire entity name**, not a short code — no lookup table needed.
Filtering `defect-links` by an endpoint id alone would show a *test's* linked defects on a
*requirement's* tab whenever the two share an id number.

### 23.3 ⚠️ The discriminator sits on EITHER endpoint, and that decides its value

The asymmetry that would be easy to get wrong, and silently:

- **From a requirement**, the open record occupies the polymorphic endpoint, so the relation carries
  `AssociationSourceTypeColumn` and the value is the **source** entity —
  `{second-endpoint-id[605];second-endpoint-type[requirement]}`.
- **From a defect**, the record is always `first-endpoint` and needs no proof, but the far end does.
  The relation carries `AssociationTargetTypeColumn` instead, and the value is the **target** —
  `second-endpoint-type[run]` is the only thing separating "Linked Runs" from "Linked Tests".

Reading the source column in both directions — the obvious symmetry — makes every defect tab list
every link the defect has, of every kind. `defect-links` also has **no `first-endpoint-type` field
at all** (asking for it is a 400), which is consistent: the first endpoint is always a defect.

### 23.4 Which related collections actually exist

| Collection | Status |
|---|---|
| `defect-links` | ✅ 200 |
| `req-traces` | ✅ 200 |
| `requirement-coverages` | ✅ 200 |
| `attachments` | ✅ 200 (also readable as a per-parent sub-resource) |
| `bpm-links` | ⚠️ **404** |

So **ALM's Business Models Linkage tab has no known REST read**. The obvious pluralisation is wrong
and guessing another name would ship a tab that fails on click, so the selector drops it and returns
the reason to the client. Settling the real name (or confirming there is none) is an open item.

## Probe 24 — the audits payload, and how partial the audit trail really is (2026-08-18)

GET only. `PROJECT-5` (borrowed, read-only), shapes and counts only — audit rows carry other teams'
edits and none of their values, users or labels entered this repo.

### 24.1 The envelope

```
{"Audits":{"TotalResults":3,"Audit":[
   {"Id":1,"ParentId":42,"ParentType":"REQ","Action":"UPDATE",
    "Time":"2026-01-02 09:15:00","User":"…",
    "Properties":{"Property":[{"Name":"status","Label":"Status",
                               "OldValue":"…","NewValue":"…"}]}}]}}
```

**PascalCase**, like `customization/relations` and unlike the lowerCamel `fields` sibling. `Time` is
`yyyy-MM-dd HH:mm:ss` in **all 678 entries** — no timezone offset, so any conversion to local time
would be inventing the server's zone.

### 24.2 ⚠️ Single elements collapse to bare objects, and the collapsed form is the COMMON one

Counted across 119 records:

| Node | as an array | as a bare object | absent |
|---|---|---|---|
| `Audits.Audit` | 115 | **4** | 1 |
| `Properties.Property` | 129 | **464** | 85 |

`Property` arrives as an object nearly four times as often as it arrives as an array. A parser
written from one pretty sample would read as correct, pass review, and then return no changes for
the majority of real records — while still rendering a History tab that looked like it worked. The
`Properties`-absent case is a third shape again: an audit entry recording that something changed
without recording what.

### 24.3 The audit trail is thinner than "partial" suggests

**678 entries across 119 records, and every single one is `Action: UPDATE`.** No `CREATE`, no
`DELETE`. Only **12 distinct fields** ever appear: `detected-in-rcyc`, `detected-in-rel`,
`environment`, `exec-status`, `owner`, `req-priority`, `req-reviewed`, `severity`, `status`,
`type-id`, `user-01`, `user-02` — **not one memo field**.

This reproduces at scale what probe 4 round 1 saw once on a single probe record, and settles the
open "audit coverage isolation" question in the direction of the pessimistic reading: a record can be
created, have its description rewritten twice and gain a coverage link while producing an audit trail
of exactly nothing.

**Consequence for the UI**: an empty History tab must never render as "nothing happened to this
record". It means "ALM recorded no field changes", which is a much weaker claim.

### 24.4 Coverage by entity

`requirements`, `tests` and `defects` all return history (39–40 of 40 sampled records had some).
`test-sets` and `runs` returned an `Audits` envelope with **no `Audit` node at all** on the records
sampled — consistent with "no recorded changes" rather than with the endpoint being absent.

---

## Probe 25 — do per-type field sets differ? Much less than we wrote down (2026-08-18)

Metadata only, sandbox, GET only.

### 25.1 The claim being checked

SESSION-STATE gap 0a said: *"Use `types/{subtypeId}/fields`, not the entity-level `fields`, for a
typed record — the per-type sets genuinely differ (13–20 non-memo by type)."*

### 25.2 What is actually there

`requirement`, 8 subtypes, against an entity-level set of **74** fields:

| Type | Fields | Missing vs entity | On the Details form |
|---|---|---|---|
| Undefined | 72 | the two `*-varchar` mirrors | 17 |
| Folder | 70 | + `req-type`, `status` | 16 |
| Group | 70 | + `req-type`, `status` | 16 |
| Functional | 71 | + `req-type` | 17 |
| Business | 70 | + `req-type`, `status` | 16 |
| Testing / Performance / Business Model | 71 | + `req-type` | 17 |

- **A difference of 2–4 fields, not 13–20.** The claim was wrong.
- **Zero flag differences.** Across all eight types, not one field is re-described as active,
  visible or required differently from the entity level. A subtype only ever **omits**.
- The Details form moves by **exactly one field** — `status`, on Folder/Group/Business.

### 25.3 Which entities have subtypes at all

| Entity | `customization/entities/{e}/types` |
|---|---|
| `requirement` | 8 types |
| `test` | **0 types** |
| `test-set` | **0 types** |
| `run` | **0 types** |
| `defect` | ⚠️ **HTTP 500** (reproducible) |

Only requirements carry a `type-id` field at all, which is what makes the 500 harmless: gating the
per-type read on the record actually having a `type-id` means the defect endpoint is never called.
Without that gate — and because ADR 0005 deliberately does **not** cache a failed metadata load —
every defect opened would fire a fresh failing upstream request, forever.

### 25.4 ⚠️ Casing, again

`customization/.../fields` uses **lowerCamel** (`name`, `label`, `groupable`); `relations/` uses
**PascalCase** (`Name`, `Label`). Reading `Name` off a field yields `null` for every field and a
successful parse of an entirely empty set — which is exactly what happened on the first run of this
probe, reporting "1 field" for every entity.

---

## Probe 26 — an unquoted multi-word filter answers a different question (2026-08-18)

Read-only, via the BFF, against `PROJECT-5`. Found while building the Group By UI rather than by
looking for it.

### 26.1 The measurement

`groups/status` reports seven buckets with server-side counts. Filtering the collection by each
bucket's plain value and comparing:

| Value | group `size` | rows returned | |
|---|---|---|---|
| `Blocked` | 3 | 3 | ✓ |
| `Failed` | 48 | 48 | ✓ |
| `N/A` | 15 | 15 | ✓ |
| `Passed` | 16 | 16 | ✓ |
| `Not Completed` | 8 | **233** | ⚠️ the entire collection |
| `Not Covered` | 117 | **233** | ⚠️ the entire collection |
| `No Run` | 26 | **HTTP 400** | |

### 26.2 Why

**`NOT` is a grammar keyword.** `{status[Not Completed]}` parses as *"status is not Completed"* — a
perfectly valid query that returns almost everything, with HTTP 200 and nothing in the response to
suggest it answered a different question. This is the same failure mode as the tree-root bug (probe
15): a confidently wrong answer, not an error.

### 26.3 The fix, and where the rule comes from

ALM's own `groups` endpoint returns a drill-in `expression` per bucket, and it quotes exactly the
values that need it — `"No Run"`, `"Not Completed"` — leaving single tokens bare. So the rule is the
server's, not a guess: **quote a filter value containing whitespace**. With quoting, all seven
buckets reproduce their counts exactly.

### 26.4 Scope

This was never only a grouping bug. The grid's name search sent unquoted values too, so any two-word
search silently matched everything. `AlmQueryTest.encodesSpace` asserted the broken form **and
passed** — a test can pin a bug as firmly as it pins a behaviour.

---

## Probe 27 — ALM sanitises memo HTML on write, and that is not a reason to trust it (2026-08-18)

`scripts/probe/probe-richtext.py` — **the first write probe since P0**, against the sandbox only,
one requirement, prefixed `ALTALM-PROBE-`, swept afterwards.

**Why it was run.** P1's last open gap was rendering memo fields as the rich text they are. The
client-side sanitiser was tested against payloads we invented, which proves it does what we asked
and says nothing about what it will be asked. Between an attacker and our renderer sits ALM, which
re-formats every memo it stores, and nobody had looked at what comes out.

**Hypothesis:** ALM stores memo HTML largely intact and does **not** sanitise, because its own
client renders in an environment that trusts the server.
**Result: REFUTED as stated, then the refutation itself turned out to be half wrong** — see the
correction below. Sent 1,188 chars of memo, got 994 back.

| Sent | Stored |
|---|---|
| `<h2>`, `<b>`, `<i>`, `<ul>/<li>`, `<table border>`, `<font color size>`, `style="color:…"`, `<a href="https://…">`, `data:image/gif` `<img>` | **all kept** |
| `<script>…</script>` | **removed by ALM** |
| `<img src=x onerror=…>` | attribute **removed by ALM** (element kept) |
| `<a href="javascript:…">` | href **removed by ALM**, anchor kept |
| `style="background: url(https://…)"` | **removed by ALM** |
| `<img src="https://…/attachment.png">` | ⚠️ **stored verbatim** |

### ⚠️ Correction, same day — this is OUTPUT sanitisation, not write sanitisation

The table above says "removed by ALM", and the first draft of this entry read that as *removed on
write*. **It is not.** The probe cannot see the database; it sees what a GET returns, and those are
different claims. OpenText's own REST documentation settles it: **"REST API output sanitization
removes or encodes data returned by requests"**, and the "Do nothing" option is documented as
returning *"the value as it is stored in the database"* — which only means anything if the raw value
is still sitting there. It is.

This matters more than the original finding did, and it inverts its conclusion:

- **It is configuration, not behaviour, and not ours.** Sanitisation is set **per field**, in
  project customization, to one of three values: *Do nothing* / *Text encoding* / *HTML
  sanitization*. It is on by default and an administrator can turn it off — on a project where the
  Description field is set to *Do nothing*, everything in the payload table above comes back live.
- **The allowed set is a deployment-owned file.** `sanitizer-whitelist.xml`, under
  `qcbin/WEB-INF/classes/`, read at service start. Our sandbox's effective whitelist is far wider
  than the sample in the docs (`html, head, meta, body, a, b`) — `<h2>`, `<table>`, `<font>` and
  `<li>` all round-tripped here and would not have survived the sample list. So neither the strict
  nor the permissive end is safe to assume.
- Therefore the client-side sanitiser is **load-bearing, not defence in depth**. It is the only
  filter in the chain that does not depend on a server-side setting we cannot see, do not control,
  and would not be told about if it changed. The original entry had this backwards.

**What this does and does not license.**

1. It does **not** retire the client-side sanitiser — see the correction above for why the case is
   now stronger, not weaker. Even taking the stripping at face value it would be one instance at one
   version on **the REST path only**, and a memo can also be written over OTA and by ALM's own older
   clients.
2. The one thing ALM does **not** strip is the thing Alt-ALM has to handle itself: a remote `<img
   src>`. Nothing executes, but rendering it fetches from a host the memo's author chose. The
   renderer drops the `src` and replaces the element with a labelled placeholder — chosen over
   leaving it because a src-less `<img>` still draws the browser's broken-image glyph, which reads
   as a bug in Alt-ALM rather than as a fact about the record.
3. ALM keeping the **anchor** while removing a `javascript:` href produces markup that looks exactly
   like a link and goes nowhere. Alt-ALM renders `a:not([href])` as ordinary text — the links ALM
   strips are precisely the ones nobody should be invited to click.

**Two write mechanics re-confirmed on the way** (both cost a run):

- A requirement's `parent-id` of `-1` is the tree root's **sentinel, not a row**. POSTing a child
  against it returns `500 Entity with key '-1' does not exist in table 'REQ'`. Parent under an
  existing record's own `id`.
- The 500 above committed nothing, consistent with — but not proof of — the general rule. The
  prefix sweep in `finally` is what makes that survivable rather than a mystery orphan.

### What format does a memo actually accept? (`--formats`, same probe)

Asked because "rich text" does not say whether ALM has a markup **dialect** — markdown, wiki — or
whether HTML is simply the storage format. Five records, each sent a different flavour, each read
back:

| Sent | Stored |
|---|---|
| `The batch importer must reject malformed rows.` | `<html><body>` + the text + `</body></html>` |
| `# Heading` / `**bold**` / `- item` (markdown) | `<html><body>` + **the literal characters**, newlines gone |
| `== Heading ==` / `'''bold'''` (wiki) | `<html><body>` + **the literal characters**, newlines gone |
| `<p>A <b>fragment</b>…</p>` | wrapped in `<html><body>`, markup preserved |
| `if (a < b && c > d)` | `if (a &lt; b &amp;&amp; c &gt; d)` — escaped, not parsed |

**HTML, and nothing else.** There is no markdown, no wiki, no plain-text mode. Everything is parsed
as HTML and re-serialised into a full document: real tags survive, a stray `<` becomes `&lt;`, and
anything that is not markup is text. This matches the documentation's instruction to keep content
*"inside the `<html>` and `<body>` tags of a valid HTML document"* to avoid unintended sanitisation —
ALM adds the wrapper for you if you leave it off.

⚠️ **The trap is newlines, and it is P2's problem.** Sent `\n`, stored nothing — the line breaks are
not converted to `<br>`, they are **collapsed to spaces**. A user typing three paragraphs into a
plain-text box and a naive write path would silently store one run-on line, and the data loss is not
visible until someone reads the record. When P2 builds memo editing it must emit `<br>`/`<p>` itself.

**Sandbox state after:** swept, zero `ALTALM-PROBE-*` requirements remaining.

---

## Probe 28 — Test Lab seeding, and the sweep that cannot see what it swept (2026-08-18)

*(recorded with probe 29 below; the seeding itself is described in the Test Lab section of
`SESSION-STATE.md`.)*

⚠️ **A test instance has no `name` field**, so the documented orphan sweep
(`?query={name[ALTALM-PROBE*]}`) against `test-instances` answers **HTTP 404, not an empty list** — a
sweep that includes it prints one 404 and then reports "no orphans" while the instance is still
there. Sweep instances **through their parent test set** (`{cycle-id[<set-id>]}`) *before* deleting
the set, or deleting the set orphans them. The `alm-live-probe` skill has been corrected.

**General rule:** check that a collection actually *has* the field you are sweeping on before
trusting the sweep's silence.

---

## Probe 29 — `EntityStatus` is unreachable, and that is the answer (2026-08-18)

`scripts/probe/probe-entity-status.py` (reads, ~25 cases) and
`scripts/probe/probe-entity-status-bulk.py` (writes, sandbox, prefixed + swept).

**Open item #12, closed.** Every envelope this project had ever captured sent
`EntityStatus:"Success"` explicitly, and two decisions in `bff/.../alm/read/` rested on that absence
while failing in *opposite* directions: `AlmEntityParser` reads a **missing** key as `"Success"`,
`AlmEntityPage.AlmEntity.isError()` treats **any** other string as a failure. The detail pane had
started rendering `row.error`, so a code path that had never seen its real input had a UI attached.

**Hypothesis:** `EntityStatus` is a per-row error channel, and a partially-satisfiable read produces
a row with a non-`Success` status.

### What was tried

| Class | Cases | Result |
|---|---|---|
| Non-existent field | `fields=id,no-such-field`, alone, and a real field belonging to another entity | **HTTP 400**, `QCRestException`, no envelope |
| Non-existent ids | `{id[999999]}`, `{id[1 Or 999999]}`, `{id[>999999]}` | **200, 0 rows.** A missing id narrows the set; it does not produce a failed row |
| Single missing id | `GET requirements/999999` | **404** `qccore.entity-not-found` |
| Virtual / inactive fields | `has-linkage`, `father-name`, `no-of-sons`, `request-note`, `istemplate`, `rbt-analysis-result-data`, `has-rich-content` | **200, all `Success`** |
| Per-subtype fields | all 8 requirement types' field sets diffed; the 2 fields unique to one type requested across a mixed read | **200, all `Success`** |
| Forbidden / absent collections | `components`, `component-folders` → **403**; `libraries`, `timeslots` → **404** | request-level, no envelope |
| Degenerate paging | `page-size=0`, `start-index=99999` | **200, 0 rows** |
| Failing single write | bad `parent-id` (XML **and** JSON) | **500** `qccore.general-error` "Invalid parent requirement" |
| Failing single write | required field omitted | **400** `qccore.required-field-missing` |

**Not one row, in any case, carried a status other than `"Success"`, and not one row ever omitted
the key.**

### Where `EntityStatus` actually lives

It is a property of an **entity representation**, present on every entity the server returns — a JSON
member on reads, an **XML attribute** on writes:

```xml
<Entity EntityStatus="Success" ErrorMessage="" Type="requirement">
  <ChildrenCount><Value>0</Value></ChildrenCount>
  <Fields>…</Fields>
</Entity>
```

Captured at `tests/fixtures/entities/entity-write-single.xml` — our own sandbox record, probe-named,
all fields empty.

### ⚠️ There is no bulk write on this deployment

Which matters, because a bulk write is the one operation that *would* need a per-row status:

- `POST {collection}` with `{"entities":[…]}` → **500**, `Cannot invoke "String.equals(Object)"
  because the return value of "org.hp.qc.web.restapi.entities.Fi…"` — the server parses a JSON body
  as **one** entity and NPEs on the missing top-level `Fields`. Same opaque-NPE class as the
  field-order trap (§1.1).
- `POST {collection}` with `<Entities><Entity/><Entity/></Entities>` → **400 Bad Request**.
- The **same builder's** single `<Entity>` → **201 Created**. That sanity write is what makes the 400
  a statement about the wrapper rather than about our XML.
- Bulk `PUT` fails identically.

Nothing committed from any bulk attempt; the sweep returned zero.

### Verdict

**`EntityStatus != "Success"` is unreachable through every operation available to us.** ALM reports
failure at the **request** level — `QCRestException` with `Id`/`Title`/`ExceptionProperties` and no
`entities` envelope — and the per-row channel is vestigial on this deployment.

This is a real answer, not "we could not make it fail": the failure modes were enumerated by class
(schema, referential, validation, permission, paging, bulk) and every class landed in the same place.

**What changed in the code:** nothing behavioural. Both defaults are kept, and both javadocs now
state the evidence and say why they default in opposite directions — an unknown *value* is evidence
of something, an absent *key* is evidence of nothing. `tests/fixtures/entities/README.md` records
that the invented fixture now pins **our** contract rather than ALM's, and that `DetailPane`'s
`row.error` is knowingly dead UI on this deployment.

⚠️ **Re-verify per deployment.** This is one SaaS instance at ALM 26.1. An on-prem instance, an older
version, or an operation not exercised here could still produce a failed row — which is exactly why
`isError()` survives rather than being deleted.

**Sandbox state after:** 3 runs, all swept, zero `ALTALM-PROBE-*` rows remaining.

---

## Probe 30 - a comment write destroys every earlier comment (2026-08-18)

`scripts/probe/probe-comments.py`. **P2's phase-start deferred probe** (open items #10, "comments
append banner convention"), run before any comment-write UX exists - which is the point of running it
at phase start.

The plan framed this as a formatting question: should Alt-ALM append with a banner matching the stock
client? The formatting is the *last* of four questions and the only one that is a matter of taste.
The first three are behaviour, and one of them is a data-loss bug.

### 1. The field is not called the same thing twice

| Entity | Field | Type | Physical |
|---|---|---|---|
| requirement | `comments` | Memo | `RQ_DEV_COMMENTS` |
| defect | `dev-comments` | Memo | `BG_DEV_COMMENTS` |
| test | `dev-comments` | Memo | `TS_DEV_COMMENTS` |
| run | `comments` | Memo | `RN_COMMENTS` |

All four: `required=False`, `editable=True`, `active=True`. ⚠️ **`comments` and `dev-comments` are
the same concept under two names** - and the logical name does not track the physical one
(a requirement's `comments` *is* `RQ_DEV_COMMENTS`). Discover it from metadata per entity; a constant
that is right for a defect is wrong for a requirement.

### 2. ⚠️ A PUT REPLACES the memo. This is the finding.

```
write #1 -> stored: <html><body>\nFIRST comment from the probe.\n</body></html>
write #2 -> stored: <html><body>\nSECOND comment from the probe.\n</body></html>
```

Write #2 **destroyed** write #1. There is no server-side append.

**So the obvious "add a comment" UI - a box, a button, PUT the new text - silently deletes the
entire comment history of the record, including comments written by other people in the stock
client.** It returns HTTP 200. Nothing in the response says anything was lost. This is the single
most destructive thing P2 could ship, and it is one line of plausible code away.

**Alt-ALM must read-modify-write**: GET the current value, append, PUT the whole thing. That belongs
in the BFF, not the SPA, so there is one enforcement point (D7) - and it inherits the lost-update
problem: two people commenting at once, last writer wins, silently. ⚠️ ALM exposes `ver-stamp`
(observed incrementing on the created row); whether it can be used as an optimistic-concurrency
token is **UNVERIFIED** and is P2's to settle before the comment UX ships.

### 3. The server adds nothing of its own

Sent `SECOND comment from the probe.`, stored the same modulo probe 27's memo wrapper. Delta was
exactly `<html><body>\n\n</body></html>`. **No banner, no username, no timestamp** - consistent with
workflow scripts being bypassed on REST writes (`CLIENT_TYPES_BYPASS_REST_WF`). Every part of the
convention is ours to write and ours to get right.

### 4. Read-modify-write with a banner: works

```
<html><body>
SECOND comment from the probe. <b>____________________</b>
<br /><b>ALTALM-PROBE-USER &lt;probe&gt;, 18/08/2026:</b>
<br />
THIRD comment, appended client-side.
</body></html>
```

Both pre-existing comments survived. The `<b>` rule survives sanitisation, and the entity-encoded
`&lt;probe&gt;` round-trips intact. Note ALM **canonicalised `<br>` to `<br />` and inserted its own
newlines** - compare canonicalized HTML, never bytes (as already established for memo fields).

### ⚠️ What this probe CANNOT answer

**The stock client's exact banner format.** That can only be read off a record a human commented on
through ALM's own UI; the sandbox has none, and the borrowed projects that did are no longer
reachable (user, 2026-08-18). The format above is a *reconstruction* - it is what the field permits,
not what ALM emits. **UNVERIFIED, and it stays that way until someone opens the stock client and adds
one comment.** Isolate it behind one function so correcting it later is a one-line change; do not
scatter separator strings through the write path.

**Sandbox state after:** 2 runs, swept, zero `ALTALM-PROBE-*` rows remaining.

⚠️ Run 1 died mid-probe on a `UnicodeEncodeError` printing a warning glyph to a cp1252 console -
the **second** time this has cost a run. `finally` still cleaned up. The `alm-live-probe` skill
already says PowerShell probe scripts must be ASCII-only; **that rule is not PowerShell-specific**,
it is a Windows-console rule and applies to the Python probes too.

---

## Probe 31 - `ver-stamp` is a counter, not a concurrency token (2026-08-18)

`scripts/probe/probe-verstamp.py`. Run before building the comment path, because the answer decides
that path's shape.

Probe 30 forced comment writes to be read-modify-write, which immediately inherits the **lost
update**: two people open a record, both append, the second write is built on a value read before the
first landed, and the first comment vanishes. HTTP 200, no warning - the same silent data loss
read-modify-write was introduced to fix, just harder to notice.

`ver-stamp` was the obvious candidate for optimistic concurrency. Four questions, asked in an order
where the last one is what makes the others mean anything:

| # | Question | Answer |
|---|---|---|
| 1 | What does metadata say? | `ver-stamp` = Number, `editable:false`, ⚠️ **`active:false`**, physical `RQ_REQ_VER_STAMP`. (`last-modified` = DateTime, `RQ_VTS`.) |
| 2 | Does it move on every write? | ✅ **Yes** - 1 → 2 → 3, and it moved on the **memo** write too |
| 3 | Is a CURRENT one accepted in a body? | **HTTP 200** - so ALM does not refuse the field outright |
| 4 | Is a STALE one rejected? | ❌ **HTTP 200, and the write landed** |

Question 3 exists because without it, a rejection in (4) would be indistinguishable from "ALM does
not accept this field in a write body" - a rejection would have looked like concurrency control and
been nothing of the sort.

### Verdict

**There is no optimistic locking on this route.** `ver-stamp` is a monotonic counter the server
maintains and ignores on input: a PUT carrying `ver-stamp=1` against a row at 4 succeeds and
overwrites. Last-writer-wins is the server's behaviour and cannot be configured away from here.

### ⚠️ But it is a reliable CHANGE DETECTOR, and that is worth having

It increments on **every** write including memo writes, which is exactly the case a token that missed
memos would have failed to guard. So Alt-ALM can do client-side conflict *detection* even though it
cannot get server-side conflict *prevention*:

1. read the memo **and** its `ver-stamp` together;
2. immediately before the PUT, re-read the `ver-stamp`;
3. if it moved, someone else wrote - surface a conflict instead of clobbering.

⚠️ **This narrows the race, it does not close it.** A write landing between step 2 and the PUT is
still lost, and no amount of client-side care fixes that without server support. The honest framing:
this converts "silent data loss, always" into "detected in all but a millisecond-wide window", and
the remaining window must be documented rather than described as safe.

**Sandbox state after:** swept, zero `ALTALM-PROBE-*` rows remaining.

---

## Probe 32 - CRUD end to end through the running BFF, in the SPA's own shapes (2026-08-20)

`scripts/probe/probe-crud-e2e.py`. Not a question about ALM - a question about **us**. The SPA's
component tests assert its requests against a mocked `fetch`, and the BFF's contract tests assert its
service layer against ALM. Neither shows the two agree **on the wire between them**, and that seam
had never been exercised. This sends exactly what `spa/src/api/client.ts` sends, to the running app,
against the sandbox.

Writes go to the BFF's **default** project only - no `project` parameter is sent anywhere in the
script, so no project name appears in it, its output, or the process list. Records carry an
`ALTALM-E2E-<timestamp>` prefix; cleanup is id-tracked in a `finally`, then swept by prefix.

**Everything agreed.** create `201 COMMITTED`; comment field discovered as `comments`; update with a
`ver-stamp` `200 COMMITTED`; the **stale** stamp refused `409`; delete `200`; sweep found **0**
orphans across requirements, tests, test-folders and defects.

The path worth the run passed for the right reason: **two comments written in sequence, and the
second did not destroy the first.** That is probe 30's data-loss trap verified through the real
read-modify-write rather than through a mock of it.

### ⚠️ The finding: ALM names the field, and attaches it to nothing

A create missing `type-id` returns:

```
HTTP 400
{"outcome":"REJECTED","errorId":"qccore.required-field-missing",
 "detail":"The field 'Requirement Type' is required.","problems":[]}
```

Three things in that, each worth keeping:

1. **`problems` is empty**, as probe 29 established it always will be - ALM reports errors per
   *request*, never per field. So a refusal arrives as a correct sentence pinned to no input, and the
   user is told which field and then left to find it.
2. **The field is named by its DISPLAY LABEL** (`Requirement Type`), not its logical name
   (`type-id`). Labels are per-project customization, so nothing can be hardcoded - matching has to
   read the project's own columns. `fieldBlamedBy` in `spa/src/detail/writeOutcome.ts` does that, as
   an explicit heuristic over prose: quoted labels only, null when two match, null when none do, and
   it only ever *adds* a mark. Marking the wrong input is worse than marking none.
3. ⚠️ **It is a clean 400, not a 500** - so the BFF's single missing-required-field retry does **not**
   fire, and should not. That retry exists for probe 9's case, where metadata *fails to declare* a
   field ALM demands and the failure surfaces as an opaque 500 naming a physical column. This is the
   other thing entirely: ALM stating a requirement plainly, in a refusal that committed nothing.
   Conflating them would make the client retry a write ALM has already answered.

**Also confirmed incidentally:** the requirements tree root is id `0` and is a **real row** that
accepts children - distinct from the `-1` sentinel, which is what `RecordCreator` refuses to post
against (probe 27).

## Probe 33 - the multi-value write grammar, and a 5xx that did not reproduce (2026-08-20)

`scripts/probe/probe-multivalue.py`. Run because the last unbuilt piece of P2's editor - the model's
only two multi-value fields, `target-rel` and `target-rcyc` - needed a fact nobody had: **how a
multi-value write is spelled**. The read shape was known (one object per value in `values`), but a
read shape is not a write shape, and guessing one would have been exactly the invention CLAUDE.md
forbids.

Three candidates, each judged **only on the read-back**. A 200 proves nothing here: probe 30
destroyed a comment for a 200.

| candidate | sent | status | stored | verdict |
|---|---|---|---|---|
| **A. repeated entries** | `[{"value":"1005"},{"value":"1006"}]` | 200 | `['1005','1006']` | **both stored** |
| **B. semicolon-joined** | `[{"value":"1005;1006"}]` | 200 | `['1005','1006']` | **both stored** |
| C. comma-joined | `[{"value":"1005,1006"}]` | **500** | `[]` | refused |

**Both A and B work, and that is worth knowing rather than just picking one.** ALM splits a
semicolon-joined string into separate values, so the two spellings are *indistinguishable in
outcome*. **A is what Alt-ALM sends**: structure carrying structure, no string convention to get
wrong, and nothing that breaks if a value ever contains a separator. ⚠️ But B working means the
server treats `;` as a separator **inside a value** - so any future single-value write of a string
that legitimately contains a semicolon is a hazard, and this probe did not test that case.

Settled at the same time:

- `target-rel` / `target-rcyc`: `supportsMultivalue=true`, `editable=true`, `required=false`, types
  `Reference`, physical `RQ_TARGET_REL` / `RQ_TARGET_RCYC`. Metadata and behaviour agree here, which
  is not something to assume (probe 9).
- **Clearing works**: `values:[{"value":""}]` empties the field, read-back returns zero values.
- A read returns **one entry per value**, never a joined string.

### ⚠️ Not every 500 is opaque - and one of them did not reproduce

C's refusal came back **500** with a genuinely useful title:
`target-rel field should contain only numbers, given '1005,1006'`. That is worth recording against
the habit of treating 5xx as uniformly meaningless: some carry a diagnosis, some carry
`qccore.general-error` with `"Title":"General Error"` and nothing else.

And the run before it produced the other kind. `POST release-folders` with `name` + `parent-id`
returned **500 `qccore.general-error`, no title** - then **succeeded on the next run with an
identical body**. The sweep confirmed the failed attempt committed nothing, which is the only reason
that is knowable at all. This is a live reproduction of the intermittency behind Q40 and the P1
grid's one-off 500, and it is the whole justification for the verify-by-query rule: had that create
committed, id-tracked cleanup could not have reached it, because a 5xx returns no id.


## Probe 34 - creating a child moves the PARENT's ver-stamp (2026-08-20)

Found by the before/after diff that replaced delete-everything cleanup, on its **first run** — and
found *because* of the replacement. The old prefix sweep could only see rows whose name matched
`ALTALM-*`; this row is the project's root requirement, named `Requirements`, and no sweep would ever
have looked at it.

The diff reported `~ requirements/0 'Requirements' ver 410 -> 411` among changes the probe could not
account for. Confirmed with a three-call read-create-read:

| action | root `ver-stamp` |
|---|---|
| before creating a child under the root | 411 |
| **after** creating a child | **412** |
| after editing that child | 412 (unchanged) |

So: **a create moves the parent; an edit to the child does not.** This is parent bookkeeping, not a
cascade — only the immediate parent's stamp was checked, and whether it propagates to grandparents is
**UNVERIFIED** (the experiment: create under a two-deep child and read all three stamps).

### ⚠️ What it means for conflict detection

`AlmVersionGuard` refuses a write when `ver-stamp` moved since the caller read it. That now has a
**false-conflict** case with nothing wrong in it: open a folder or a parent requirement in Alt-ALM,
have anyone add a child under it, and the next save of the *parent* is refused with "Someone else
changed this record" — when nobody changed a single field on it.

It fails safe (refuse rather than overwrite) and the recovery works (reload, re-apply, save), so this
is a wrong *message* rather than lost data. But the message is confidently wrong, which is worse than
vague: the user reloads, sees identical values, and is told to re-apply a change to something that
never differed.

**The sharper rule, not yet implemented:** ALM's update replaces only the fields present in the body,
so a concurrent edit is harmful *only when it touched a field this write is also sending*. Comparing
those fields' values against the pre-write read would refuse strictly less often than `ver-stamp`
while protecting exactly as much. `ver-stamp` is the cheap proxy, and it is proxying for the wrong
thing. Deliberately left as a finding rather than a change: it is the safety-critical path and
deserves its own slice.


## Probe 35 - attachments ARE readable, and the obvious way to read them is wrong (2026-08-20)

`scripts/probe/probe-attachment-read.py`. The write side was settled long ago (multipart, `file` part
last, `ref-subtype=1`). The read side never was — which is the only reason every image in a memo
renders "Alt-ALM cannot fetch attachments". It can.

**Upload** (verified again here): `POST .../requirements/{id}/attachments`, hand-built multipart,
**201**.

**The collection** `GET .../attachments` is an ordinary entity envelope — `{TotalResults, entities}` —
with these fields per file: `id`, `name`, `file-size`, `ref-subtype`, `description`, `parent-id`,
`parent-type`, `ref-type`, `last-modified`, `vc-cur-ver`, `vc-user-name`. ⚠️ `description` comes back
as a **full HTML document** (`<html><body>
probe 35
</body></html>`) — it is a memo like any other,
so it needs the same sanitiser on the way out, not `textContent`.

### ⚠️ The member URL returns METADATA unless you ask correctly

| `Accept` | result |
|---|---|
| `*/*` | **entity XML** (866 bytes) — not the file |
| `application/json` | **entity JSON** (642 bytes) — not the file |
| **`application/octet-stream`** | **THE BYTES**, byte-identical (sha256 matched the upload) |
| `image/png` — the file's actual type | **HTTP 406** |

Two traps in one table. A client that fetches an attachment with the default `Accept` gets a
**200 and a document that is not the file** — success-shaped, wrong content, and only a byte
comparison notices. And asking for the type you actually want is *refused*: the generic type is the
one that works, which is the opposite of how content negotiation reads.

**ALM returns the real mime type on the way back** — `Content-Type: image/png;charset=utf-8` — so
Alt-ALM does **not** have to infer a type from the filename extension. ⚠️ The `;charset=utf-8` is
present on binary content and is meaningless; do not propagate it.

`?by-id=true` with the numeric id returns identical bytes. **Prefer it over the name**: a filename in
a path needs escaping, can collide, and is user-controlled.

### ⚠️ Serving these to a browser is a same-origin XSS decision, not a plumbing one

Alt-ALM is deliberately **one deployable on one origin** (ADR 0001) — which means an attachment
served inline is served *from the SPA's own origin*. An uploaded `.html`, `.svg`, or anything sniffed
as one then runs with the app's session. "Open the attachment in a new tab" is cheap for an image or
a PDF and is a stored-XSS hole for markup. The split to implement: inline (`Content-Disposition:
inline`) for an allowlist of safe types with `X-Content-Type-Options: nosniff`, and forced download
for everything else. Never echo ALM's `Content-Type` unfiltered into an inline response.

## Probe 36 - ALM sends NO filename with attachment bytes (2026-08-21)

`scripts/probe/probe-attachment-serve.py`, run against the BFF's own endpoints rather than against
ALM. Found by running the new download route live, **not** by a test.

The byte response carries a media type and **no `Content-Disposition` at all**, so a caller that
takes the filename from the response headers has none. Alt-ALM's synthesised fallback fired on every
download and files landed as `attachment-8` - no extension, which on Windows means no application
associated with them either.

⚠️ **A unit test could not have caught this.** `attachment-8` is a perfectly valid filename and
nothing about the response is malformed. Only comparing it against the name ALM reports in the
**list** shows the problem, and only a live run has both.

Fix: `AlmAttachmentClient.content` looks the name up from `list()` when the headers are silent -
which is always - at the cost of one extra GET per download.

Also confirmed live, against the sandbox, through the BFF:

| check | result |
|---|---|
| `Content-Type` on the download route | `application/octet-stream`, never ALM's own |
| `Content-Disposition` | `attachment`, for every file type |
| `X-Content-Type-Options` | `nosniff` |
| body | the **file**, not the entity envelope a wrong `Accept` returns with HTTP 200 (probe 35) |
| `/image` for a real PNG | 200, `image/png`, `inline`, byte-identical to the download |
| a nonexistent attachment id | 400, not a 200 carrying an error page |

---

## Probe 37 - a memo's image `src` survives verbatim, and the first run was a false negative (2026-08-21)

`scripts/probe/probe-memo-image-src.py`. Alt-ALM's memo-image path rests on one assumption: that the
`<img src>` ALM stores ends in `/attachments/<filename>`, so the filename can be read off the URL and
matched against the record's attachment list. Nothing had verified it, and the failure mode is
silent - every image would stay a placeholder, which looks exactly like a record with no images.

**Verified:**

1. An **absolute REST URL** as `<img src>` survives a memo write **unchanged**.
2. Its last path segment, percent-decoded, is **exactly** the name the attachments list reports.
3. A **relative** src has its `<img>` element kept and its `src` **stripped** - confirming
   `api-ref`. Such an image is unresolvable by anyone, us included.
4. Six spellings all survive with `src` intact: self-closing and open tag, with and without `alt`,
   with `width`/`height`, a remote `http://` src, and a `data:` URI.

### ⚠️ The first run said every `<img>` was stripped, and it was wrong

Six variants, all reporting the memo came back **empty**. That reads as "this project's output
sanitiser removes images" and contradicts probe 27, which is exactly the kind of contradiction worth
stopping at.

The bug was the **read**. `GET requirements/{id}` returns a **bare Entity object**, not an
`{entities: [...]}` envelope. A parser written for the envelope finds no `entities` key, falls back
to an empty dict, and reports every field as absent - so a perfectly successful write looks like a
memo that stored nothing. **HTTP 200 throughout**, no error anywhere.

The collection form, `GET requirements?query={id[217]}&fields=id,description`, returns the envelope
and shows all six srcs intact.

⚠️ **This is the standing lesson repeating itself**: not too few attempts, an unexamined assumption
about the shape of the question - and this time the wrong answer was a *negative* about ALM's
behaviour, arrived at through our own parser. Two response shapes exist for the same entity and only
one of them is what every other probe in this log reads.

---

## Open items for the next probe round

1. ~~Map `SiteVersion 20.0 (20.00.0.143)` → marketing version~~ **DONE: ALM 26.1** (probe 3).
2. ~~Is `site-session` required after `oauth2/login`, or fully redundant?~~ **DONE: fully redundant**
   — a project read with the login cookies alone returns 200 (probe 13). Kept in `login()` anyway as
   the documented flow.
3. ~~XSRF header requirement~~ **DONE: 401 without header** (probe 4).
4. ~~Rich-text round-trip fidelity~~ **DONE** (probes 4–5: sanitizer rules, img-src forms, token
   encoding; probe 27 adds what ALM strips on write and what it does not).
5. Whether `Accept: application/json` works on every collection or only some (observed: yes on all
   probed so far; exception found: Core `is-authenticated` is 406/XML-only — use v2).
6. ~~Booleans~~ **DONE: no Boolean type; Y/N = LookupList list-id 1** (probe 3 offline mining).
7. ~~Write-probe every "inventory says yes" row~~ **DONE** (probes 4–6) — outcomes: design-steps ✓,
   req-traces ✓, requirement-coverages ✓, milestones ✓, runs ✗ (Fast_Run route instead),
   step-parameters ✗ (OTA candidate), mail ✗ (body undocumented).
8. ~~`test-executions` semantics~~ **DONE: dispatch, not ingest** (probe 5).
9. ~~Offline fixture mining~~ **DONE** (probe 3 mining reports).
10. **Deferred to post-planning**: mail body shape (capture stock-UI traffic); step-parameters via
    OTA (needs tdconnect.exe); ~~release-folder root id~~ **DONE: id=1 `Releases`, parent `-1`**
    (probe 15); ~~`alm-web` dialect body shape~~ **DONE: flat/denormalized, and it responds on ops
    that do not advertise it — see R14** (probe 15); ~~comments append banner convention~~
    **DONE (probe 30) — and it was not a formatting question: a PUT REPLACES the memo, so the
    obvious comment UI deletes the record's whole comment history. The banner format itself stays
    UNVERIFIED (needs one stock-client comment to read).**; audit
    coverage isolation (plain-field PUT vs memo PUT); versions check-in/check-out write probe;
    `IMAGE_COMPRESSION_LEVEL` round-trip.
11. **NEW (probe 15), blocked on the record generator**: is an over-cap `page-size` silently clamped
    to 2,000? Untestable while the sandbox's largest collection holds 2 rows. See Q45.
12. ~~What does a collection read return for an entity whose `EntityStatus` is not `"Success"`?~~
    **DONE — it cannot happen** (probe 29). ~25 broken reads plus single and bulk writes in both
    media types: every failure is request-level `QCRestException`, no row ever carried another
    status, no row ever omitted the key, and there is **no bulk write** on this deployment to
    produce a mixed page. Both parser defaults kept and now documented as deliberate opposites.
    ⚠️ One instance, one version — re-verify on-prem.
13. **NEW (probe 29)**: is the missing bulk write a *deployment* limitation or a product one? The
    JSON body 500s inside `org.hp.qc.web.restapi.entities` and the XML `<Entities>` wrapper is
    refused 400, but neither error says "unsupported". Worth one probe against a different ALM
    instance before P2 assumes single-entity writes are the only shape — a bulk path would change
    the write-safety design, since a partially-committed bulk is a worse version of the 5xx problem
    (§1.2).
