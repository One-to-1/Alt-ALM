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
