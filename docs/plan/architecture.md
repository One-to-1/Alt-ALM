# Alt-ALM — Architecture

Status: Draft, lead-decision-bound. Elaborates decisions D1–D7 in
[`_lead-decision-brief.md`](_lead-decision-brief.md); does not overturn them. Every load-bearing claim
cites `docs/research/alm-api-reference.md` (api-ref) or `docs/research/alm-data-model.md` (data-model)
by section; `UNVERIFIED` items are labelled, never assumed. Constraints carried from `CLAUDE.md`:
documented REST (+ OTA/COM as an allowed fallback) only, undocumented endpoints go to the risk
register not the implementation, `Secrets/` is never read into a document or logged, writes are
sandbox-only behind an allowlist, unverified claims stay labelled.

---

## 1. System overview

Alt-ALM is a **backend-for-frontend (BFF) architecture** (D1). A browser cannot call `/qcbin` directly:
ALM's session model is cookie-based (`LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`, `ALM_USER`,
`JSESSIONID`, all set in one step by `POST oauth2/login` — api-ref §2.1) with mandatory
`X-XSRF-TOKEN` header echo on every non-GET call (api-ref §2.2, 401-verified) and no CORS story for a
third-party origin. The only workable shape is a server that owns the ALM session and exposes its own
clean JSON API to the SPA.

```
┌─────────────┐        HTTPS, Alt-ALM's own JSON API, app-level auth (cookie/JWT)
│   Browser    │ ───────────────────────────────────────────────────┐
│  React+TS    │                                                     │
│     SPA      │ ◄───────────────────────────────────────────────────┘
└─────────────┘
                                                                       ▼
                                                        ┌───────────────────────────┐
                                                        │      Alt-ALM BFF          │
                                                        │  Java 21 / Spring Boot     │
                                                        │  single deployable JAR     │
                                                        │                            │
                                                        │  session mgr │ metadata svc│
                                                        │  write-safety│ query xlate │
                                                        │  validation  │ generator   │
                                                        │  capability flags          │
                                                        └──────────────┬─────────────┘
                                     internal HTTP, localhost/LAN only │      │
                        ┌────────────────────────────────────────────┘      │ HTTPS, cookie session
                        ▼                                                   ▼
        ┌───────────────────────────────┐                    ┌───────────────────────────────┐
        │   OTA Bridge (optional)        │                    │   ALM / Quality Center         │
        │   Windows service, COM/OTA      │                    │   /qcbin  REST  (Core +        │
        │   .NET or Python+pywin32        │                    │   Deprecated + v2 + SA)         │
        │   tdconnect.exe-backed          │                    │   sandbox OR production target  │
        └───────────────────────────────┘                    └───────────────────────────────┘
                        ▲
                        │ present only where a REST gap is verified genuine (D3)
                        │ step-parameters · BPT components · similar-defects

        ┌───────────────────────────────┐
        │  Secrets/ (git-ignored)        │   read by BFF process only, at startup / per-call;
        │  ALM_API_credentials.json      │   never logged, never echoed, never forwarded to SPA
        └───────────────────────────────┘
```

**Targets.** The BFF is configured with one or more named ALM domain/project targets. Every target
carries an explicit `sandbox: true|false` flag. Generator writes and any "designated sandbox" UI
affordance check this flag; production targets are read-only unless a human has explicitly flipped it,
per `CLAUDE.md`'s hard constraint. The allowlist (D6) is a superset check on top of this: even a
sandbox-flagged target must also appear in the generator's explicit allowlist before writes proceed.

**Deployment shape** (detail in §4.4): SPA static assets + BFF ship as one Spring Boot JAR (embedded
Tomcat serves both the API and the built SPA bundle). The OTA bridge, when present, is a separate
Windows service — it cannot run where the BFF runs unless the BFF's own host is Windows, because COM
interop is Windows-only (ADR 0003).

---

## 2. Component breakdown

### 2.1 SPA (React + TypeScript)

Renders forms, grids, and filters entirely from runtime metadata fetched through the BFF — never from
a hardcoded field schema (D5, ADR 0005). One field-renderer registry keyed on the 8 ALM field types
(`String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`, `Reference` — api-ref §8,
data-model §4). No Boolean type exists in ALM; Y/N semantics render through the `LookupList`
renderer bound to list-id 1 (api-ref §8). UDFs (`user-NN` physical names) carry no special-cased
renderer — they arrive through the same `customization/entities/{e}/fields` shape as system fields and
render automatically (api-ref §6.8).

### 2.2 BFF (Java 21, Spring Boot)

**ALM session manager.** Owns one pooled session per configured target, built from the service-account
API key in `Secrets/` (D4). Responsibilities: `oauth2/login` handshake (api-ref §2.1), keepalive via
`GET`/`PUT site-session` before `REST_SESSION_MAX_IDLE_TIME` (default 60 min, api-ref §2.2) elapses,
XSRF header injection (the `XSRF-TOKEN` cookie value echoed as `X-XSRF-TOKEN` on every non-GET —
api-ref §2.2, 401-verified), and session-loss recovery (re-login on `qccore.session-has-expired` or a
liveness-check failure via `GET /qcbin/v2/rest/is-authenticated`, api-ref §2.3 — note the legacy Core
path is JSON-hostile, 406 on `Accept: application/json`). Sits behind an interface so a future
per-user-API-key mode can slot in without a redesign (D4, ADR 0004).

**Metadata service.** Fetches `customization/entities/{entity}/fields`, `.../types`,
`used-lists`/`lists`, and `customization/users` per project, caches with explicit invalidation (manual
refresh action + TTL fallback — exact TTL is a config value, not a verified server behaviour).
Discovers tree roots at runtime via `?query={parent-id[0]}` rather than hardcoding them (data-model
§2.1) — the user-supplied defaults (requirement root id 0, test-folder root id 2 "Subject", test-set
root id 0 "Root") are a documented **sanity check** the service asserts against post-discovery, not a
hardcoded fallback path, because test-folder and release-folder roots are stated to be project-specific
(data-model §2.1; release-folder root is explicitly `UNVERIFIED`, discover-before-hardcode). List-Ids
are instance-specific and must never be hardcoded either (api-ref §6.8).

**Write-safety client** (D7 — the single implementation every write, UI- or generator-originated,
flows through). Enumerated hazards, each with its probe citation:

| Hazard | Enforcement | Citation |
|---|---|---|
| `Fields` array JSON member order affects server behaviour (wrong order → NPE-style 500s on otherwise-identical data) | Serialize every entity write with a fixed, deterministic field order (name → relational ids → type/subtype fields last); never rely on map/dict iteration order | api-ref §3.2 |
| HTTP 5xx on a write is not proof the row wasn't committed (one 500 silently committed a row, found via `father-name` cross-reference) | Every 5xx write response is "unknown outcome" — verify by GET/query before any retry; retries are dedup-checked, never blind | api-ref §3.3 |
| XSRF header missing → 401, but the gate runs pre-business-logic so there is no silent-commit risk on that specific failure | Inject `X-XSRF-TOKEN` on every non-GET automatically; treat this 401 shape as retryable-after-refresh, not fatal | api-ref §2.2 |
| Bulk POST/PUT is non-transactional; 409 = partial failure with a per-item `BulkEntry` body | Always parse the 409 body per-item; never assume all-or-nothing on a bulk call | api-ref §4.5 |
| `page-size` silently caps at 2000 on Core (`REST_API_MAX_PAGE_SIZE`); Deprecated throws instead | Cap client-requested page sizes below 2000 and detect silent truncation by comparing returned count to `TotalResults` | api-ref §4.4 |
| Roots and List-Ids are project-specific | Never hardcode; always resolve through the metadata service's runtime discovery | api-ref §6.1, §6.8; data-model §2.1 |
| Multipart body construction is a library-specific compatibility risk (a PowerShell `-Form`-built body was rejected server-side; a hand-built body with explicit boundary/CRLF discipline and `file` part last succeeded 3/3) | Whatever HTTP client Java uses for multipart, integration-test it against the real server before relying on it in the generator's image-upload path | api-ref §6.6, data-model §6 |
| Design-step parameter tokens (`<<<name>>>`) are destroyed by the rich-text sanitizer unless HTML-entity-pre-encoded | Rich-text writer HTML-entity-encodes literal `<`/`>` in any free-text content it did not itself construct as valid markup | api-ref §6.4, data-model §6 |
| Release-cycle dates outside the parent release's window are rejected server-side | Validate cycle dates client-side against the parent release before sending, to fail fast with a clearer message than the server's | api-ref §6.7, data-model §6 |
| Direct `POST runs` is a dead end (8/8 failed across two rounds) | Route all run creation through Fast_Run synthesis (`PUT test-instances/{id}` status) — never attempt direct run POST in product code | data-model §2.9, §6 |

**Query translation layer.** Maps Alt-ALM's own filter/sort UI model onto Core query grammar
(`?query={field[condition];field[condition]}`, api-ref §4.1) and documents its limits to the SPA rather
than hiding them:

- **AND-only between fields** — the grammar has no cross-field OR; a filter UI that lets a user
  combine fields with OR must issue multiple requests and merge client-side, or must disable the
  combinator (api-ref §4.1).
- **No documented null-test syntax** in Core (`UNVERIFIED`, api-ref §4.1) — "is empty" filters are not
  translatable to a single query clause today; the layer must reject or special-case them rather than
  emit a clause that silently returns wrong results.
- **No documented escaping rule** for delimiter characters (`'`, `"`, `;`, `[`, `]`, `(`, `)`, `,`) in
  literals (`UNVERIFIED`, api-ref §4.1) — free-text filter values (and generator-produced names/content
  containing these characters) are a real correctness risk; the layer must be conservative (reject or
  quote-and-flag) until this is settled by a live probe, not assume any particular escaping works.
- Cross-filters require a unique relation alias per entity type in one query — reusing an alias
  produces silently wrong results, not an error (api-ref §4.2); the query builder must track alias
  uniqueness itself since the server won't catch a violation.

**Validation layer.** REST writes bypass workflow-script validation by default
(`CLIENT_TYPES_BYPASS_REST_WF`, api-ref §6.8, "advanced project scripts apply to Web Client only" unless
the site param is changed) — Alt-ALM gets free status-setting but **no server-side auto-population of
derived values**. This layer re-derives, from the same runtime metadata the renderer registry uses
(Required / Editable / List bindings, System=read-only), the checks the stock client's workflow scripts
would otherwise have performed: required-field presence, list-membership, and editable/System field
protection before a write is dispatched. It is explicitly **not** a reimplementation of arbitrary
VBScript business logic — that is out of reach by design (§5) — only of the metadata-declared
constraints REST itself exposes.

**Generator engine.** DAG-ordered creation, dry-run by default, allowlist-gated writes, seedable PRNG
(D6). Detailed in the forthcoming `data-generator-spec.md`; the DAG order and its probe citations live
in data-model §2.11 and are restated in the phasing skeleton (`_lead-decision-brief.md` D6).

**Capability flags.** A small runtime registry the BFF exposes to the SPA and gates internally:

| Flag | Source of truth | Effect when absent/false |
|---|---|---|
| `otaBridgeAvailable` | Bridge health-check reachable at startup + periodic ping | step-parameter definition, BPT component operations, similar-defects degrade to "unavailable" UI state, not silent no-ops (ADR 0003) |
| `bptLicensed` | `GET /components` returns 403 (license/permission-gated) vs. 404 (absent) — api-ref §6.7b, data-model §2.9 | BPT authoring surfaces hidden entirely; a 404 target hides them permanently, a 403 target shows a "not licensed" state |
| `saasOnlyOpsAvailable` | Whether the target's Swagger doc's `SAAS_ONLY`-flagged Site Admin ops (28 of 178 — api-ref §1) respond, probed once per target at connect time | On-prem targets hide audits/customers/orphan-users/some `roles` SA screens rather than surfacing a hard error per click |
| `versionControlSupported(entity)` | Static: requirements, tests, resources only (data-model §5) | Check-in/out UI hidden for every other entity type |

### 2.3 OTA bridge sidecar (optional)

Detailed in ADR 0003. Owns exactly three verified-genuine REST gaps (test-parameter *definition*,
BPT components, similar-defects) and nothing else — it is not a general escape hatch for convenience.

> **⚠️ UPDATE 2026-08-13 — sidecar has a verified reachable target [probe8].** Probe 7's "no reachable
> target" conclusion is superseded: using ALM's own deployed client
> (`%LOCALAPPDATA%\HP\ALM-Client\<version>\OTAClient.dll`) rather than a hand-extracted payload,
> `InitConnectionWithApiKeyEx` connects and authenticates cleanly through the SaaS SSO front door, and
> writes succeed — a test folder/test and a business component were each created and deleted via OTA
> [probe8]. The capability-flag design above (`otaBridgeAvailable`) still stands unchanged: mainline BFF
> and SPA remain fully functional with the bridge absent, and the flag now gates a genuinely buildable
> feature rather than a permanently-degraded one. The **implementation-language decision (.NET vs
> Python + pywin32) is live again** and should be scheduled per the phased plan. Environment
> constraints carried forward and reconfirmed necessary: a **32-bit Windows host process**, a
> **version-matched client copied from ALM's own deployed install** (not an installer-extracted
> payload — this substitution was the actual Probe 7 failure cause), **per-user COM registration
> written from a 32-bit process**, and **separate type-library registration**
> (`RegisterTypeLibForUser`) [probe8].

---

## 3. Data flow walkthroughs

### 3.1 Render a defect grid

1. SPA requests the defects grid view; BFF's metadata service returns (from cache, or refreshes)
   `customization/entities/defect/fields` — 42 fields on the probed sandbox, 8 type identifiers,
   `Required`/`Editable`/`Filterable`/`List-Id` per field (api-ref §6.8, live-probe Probe 2). The SPA's
   field-renderer registry uses this to build column definitions and the filter-builder's field list.
2. User applies filters + sort; the query translation layer emits Core grammar, e.g.
   `defects?query={severity[GT 2]; status[Open or Reopen]}&order-by={status;name[DESC]}`
   (AND-only between `severity` and `status`, api-ref §4.1, §4.3) plus `page-size`/`start-index`
   (api-ref §4.4, 1-based `start-index`, default page size 100, hard cap 2000 silent on Core).
3. BFF issues the GET through the pooled session (keepalive already current), parses
   `{"entities":[…], "TotalResults": n}` (api-ref §3.1), and returns Alt-ALM's own paged JSON shape to
   the SPA — it never forwards the raw ALM envelope, so the query-grammar limits above are Alt-ALM's
   problem to hide or surface deliberately, not the SPA's.
4. If `TotalResults` exceeds the returned page and the requested `page-size` was ≥2000, the BFF flags
   a truncation warning rather than silently under-reporting (§2.2 hazard table).

### 3.2 Create a requirement with rich text + embedded image

1. SPA submits a requirement create with a rich-text `description` containing an inline image the user
   pasted/uploaded.
2. BFF's write-safety client first uploads the image as an attachment via **hand-built multipart**
   (explicit boundary, CRLF discipline, text parts before the `file` part, `file` part last,
   `ref-subtype=1`) to `.../requirements/{id}/attachments` — this requires the requirement to already
   exist, so a bare requirement (`name`, `parent-id`, `type-id` — the deterministic field order from
   §2.2) is created first, HTTP 201 (api-ref §6.1), then the attachment POST follows, HTTP 201
   (api-ref §6.6, data-model §6, "confirmed working via hand-built multipart 3/3 sessions").
3. The BFF rewrites the rich-text HTML's `<img src>` to the attachment's **full absolute REST URL**
   (`https://…/qcbin/rest/domains/…/requirements/{id}/attachments/{name}`) or a `data:` URI — a bare
   filename or relative path has its `src` attribute silently stripped by the sanitizer (tag survives,
   attribute doesn't), so the BFF must never emit either form (api-ref §6.6, "Result on readback"
   table).
4. The BFF PUTs the finished `<html><body>…</body></html>` document to `description`/`req-rich-content`
   (api-ref §7). Any literal `<`/`>` sequences in user-typed free text that look like a tag (most
   sharply, parameter-style tokens) are HTML-entity-pre-encoded before send, or the sanitizer destroys
   them (api-ref §6.4). The response is treated as authoritative and re-rendered to the SPA — round-trip
   is **not** byte-identical (whitespace pretty-printing, implicit `<tbody>`, `<script>` stripped,
   api-ref §7), so the SPA's editor must diff against the canonicalized server copy, not the pre-submit
   local copy.

### 3.3 Execute a manual test (Fast_Run synthesis)

Direct `POST runs` is a **verified dead end** — 8 attempts across two probe rounds, both failure modes
reproducible (`"Fail to get a must number attribute 'TESTSET'"` on baseline shapes; `"Failed to post
step"` once denormalized name fields are added — data-model §2.9). The only confirmed creation path:

1. Preconditions already exist from the DAG (data-model §2.11): a `test-instance` under a `test-set`
   under a `test-set-folder`, with `cycle-id` = the **test-set** id (legacy-naming trap — `cycle-id`
   never means "release cycle" here, data-model §2.7) and `test-id` = the design test.
2. BFF issues `PUT test-instances/{id}` with a `status` value (e.g. `"Passed"`). The server
   synthesizes a full `run` entity server-side — `subtype-id="hp.qc.run.MANUAL"`, an
   **auto-generated, non-overridable name** `Fast_Run_<M>-<D>_<HH-MM-SS>` (data-model §2.9, §6).
3. Verified side effects the BFF relies on and surfaces to the UI, not re-derives itself: run-steps
   auto-copy from the test's design-steps (count matches exactly), and the test-instance's own status
   mirrors the run's status on readback (data-model §2.9, Probe 6).
4. Verified non-effects the BFF must not assume: **no eager run-step→run status aggregation** —
   flipping an individual run-step to `Failed` does not recompute the parent run's status (observed
   after a force-set `Passed`, not an exhaustive matrix — data-model §2.9). If Alt-ALM's UI wants a
   run's overall status to reflect its steps, the BFF (not the server) must compute and PUT that
   rollup explicitly.
5. Individual run-steps are then PUT-able (`runs/{id}/run-steps/{sid}`) as the user marks each step
   Pass/Fail during execution — this is the "manual runner" UI's actual write path; there is no
   separate "start run" server call beyond the initiating instance-status PUT.

---

## 4. Cross-cutting concerns

### 4.1 Error taxonomy

ALM's documented `Id` catalogue (api-ref §3.4) maps to Alt-ALM user-facing categories:

| `qccore.*` id | HTTP | Alt-ALM category | UI treatment |
|---|---|---|---|
| `general-error` | varies (400/500 seen) | Generic server error | Show server `Title` verbatim; log full body server-side |
| `required-field-missing` | 400 | Validation | Highlight field, show label from metadata |
| `invalid-list-field-value`, `invalid-value-type-for-field` | 400 | Validation | Highlight field; re-fetch metadata (list may have changed) |
| `invalid-filter-expression` | 400 | Query-builder bug | Surface to developer console, not raw to end user |
| `unknown-field-name` | 400 | Metadata drift | Trigger metadata cache invalidation + retry once |
| `entity-not-found` | 404 | Not found | Standard 404 UI state |
| `lock-failure`, `check-in-failure`, `check-out-failure`, `undo-check-out-failure` | 409/other | Concurrency/VC conflict | "Someone else is editing this" UI state |
| `operation-forbidden` | 403 | Permission | Standard 403 UI state; also the BPT-license-gate signal (§2.2 capability flags) |
| `session-has-expired` | 401 | Session | Silent re-login + retry once, transparent to user |
| `bulk-operation-failed` | 409 | Partial bulk failure | Per-item result table, not a single error banner |

`400` and `403` are explicitly documented **catch-alls** covering multiple distinct causes each
(api-ref §3.4) — the BFF must never infer a specific cause from status code alone; it always parses
`Id`/`Title`. Any HTTP status **not** in this table (**5xx-verify**: every 5xx on a write is
"unknown outcome," per the write-safety client's §2.2 hazard entry) triggers the verify-by-query flow
before any user-facing error is shown, so the user is never told "failed" when the row may in fact
exist.

### 4.2 Logging and masking

`Secrets/` content (API key, client secret, and any values read from `ALM_API_credentials.json`) is
never logged, printed, or forwarded — per `CLAUDE.md`'s hard constraint, restated here because the BFF
is the one process with access to it. Concretely: the session manager references the secrets file by
path at startup, holds the parsed key in memory only, and the write-safety client's request/response
logging redacts the `Authorization`/cookie headers and any field whose name matches a
configurable secrets-pattern list before it reaches disk or an observability sink. Probe-log discipline
mirrors this: host/domain/project/key strings are masked the same way in any diagnostic capture, per
the convention already established in `live-probe-log.md`.

### 4.3 Configuration

Per-target config (domain, project, base URL, `sandbox: true|false`, generator allowlist membership)
lives outside `Secrets/` in ordinary application config; only the API key/secret pair is
secret-classified. Capability flags (§2.2) are probed at connect time and cached per target, not
globally, since SaaS-only op availability and BPT licensing are properties of the target, not of
Alt-ALM itself.

### 4.4 Deployment shape

Single Spring Boot JAR serves the API and the built SPA static bundle — one deployable artifact, one
process, matching D2's stack decision and keeping the "BFF is the only thing that talks to `/qcbin`"
invariant simple to operate. The OTA bridge (ADR 0003), when present, is a **separate optional Windows
service** — COM/OTA is Windows-only, so it cannot be folded into the mainline JAR without forcing the
whole BFF onto Windows; isolating it keeps the mainline deployable on any OS the JVM runs on and makes
"bridge absent" a normal, first-class runtime state (capability flags, §2.2) rather than a startup
failure.

---

## 5. Honest gaps — what Alt-ALM will NOT do

| Capability | Reason |
|---|---|
| Raw-SQL / Excel-style Project Reports authoring, `run-query` | `run-query`/`run-query/export` is literal SQL execution — out of scope by `CLAUDE.md`'s documented-REST-only hard constraint; a risk-register entry, never an implementation path (api-ref §6.9) |
| Timeslots, host/lab scheduling beyond `bv-hosts`/`host-groups` CRUD | Timeslot resources confirmed absent from the 1,111-op resource-list inventory; no REST surface found (api-ref §9) |
| Libraries and baselines (create/compare/pin) | No REST surface found across two research waves and the write-probe rounds; UI-inventory marks every library/baseline operation NOT-VIA-API (alm-ui-feature-inventory.md; api-ref §9) |
| Alert rules and follow-up flags | Confirmed absent from the resource-list inventory; zero REST hits (api-ref §9) |
| Full BPT (Business Process Testing) authoring via REST | `GET /components` → 403 or 404 depending on target; `GET /business-components` → 404. Effectively out of REST reach — the REST API itself has no BPT surface, regardless of OTA. **As of 2026-08-13, BPT authoring is confirmed reachable and writable via the OTA bridge** (`ComponentFactory` → subfolder → `ComponentFactory.AddItem`; a component was created and deleted — `live-probe-log.md` Probe 8), reversing the earlier "REST 403 = licence gate" read: the 403 was structural (owner/subfolder requirements), not permission-related. Scoped as an OTA-bridge feature per ADR 0003, not a general BPT client (data-model §2.9, `_lead-decision-brief.md` D3) |
| Dashboard/graph **authoring** (Alt-ALM will read existing dashboards) | Report/graph design is desktop-UI/client-side only; no REST creation surface. Alt-ALM's fallback is rendering its own charts client-side from raw entity queries, which is a different feature, not dashboard-authoring parity (alm-ui-feature-inventory.md) |
| Test-parameter *definition* via REST | Every REST shape fails with `"Test parameter does not exist"` — the underlying "Test parameter" object has no REST creation path (api-ref §9, data-model §6). **As of 2026-08-13, the OTA bridge closes the registration half of this gap**: a design step containing a `<<<token>>>` registers the parameter over OTA (`Count` 0→1 verified, `live-probe-log.md` Probe 8) — declaring one directly (`Test.Params.AddParam`) is a no-op. Setting the registered parameter's *default value* still fails (`"Invalid field type definition."`) and remains `UNVERIFIED`. With the OTA bridge absent, the generator still skips parameter definition per its capability flag (D6) |
| All 23 `OTA`-verdict features in the feasibility matrix (Baselines, Libraries, Alerts, Timeslots, Follow-up flags, Purge-runs, etc. — see `feasibility-matrix.md`) | **As of 2026-08-13, these are confirmed implementable via the OTA sidecar** — Probe 8 (`live-probe-log.md`) shows OTA connects, authenticates, and writes against this SaaS deployment using ALM's own deployed client; every candidate factory checked (Baseline, Library, Host, HostGroup, Milestone, KPI, ScopeItem, plus PurgeRuns/PurgeRuns2/SynchronizeFollowUps/AlertManager/ExtendedStorage on the connection) was reachable. They remain gated on building the Windows-only 32-bit OTA sidecar (ADR 0003), not on target reachability; REST itself still cannot reach any of them |
| PPT scope items, KPIs, Scorecard | No REST surface found; UI-inventory marks these NOT-VIA-API (alm-ui-feature-inventory.md) |
| Send-by-email (matching ALM's own templated send) | `POST .../{entity}/{id}/mail` body shape is undocumented and failed every probed shape (3 JSON + 1 XML, api-ref §9); Alt-ALM sends its own mail instead of replicating ALM's send-by-email feature |
| Arbitrary VBScript workflow-script behaviour | REST writes bypass workflow scripts by default (`CLIENT_TYPES_BYPASS_REST_WF`, api-ref §6.8); Alt-ALM's validation layer (§2.2) re-derives metadata-declared constraints only, not custom script logic, which is fundamentally unreachable over REST |

---

## Open items carried forward (not resolved by this document)

- Core query grammar's null-test and delimiter-escaping gaps remain `UNVERIFIED` (api-ref §4.1) — the
  query translation layer's conservative stance (§2.2) is a design mitigation, not a confirmed-safe
  workaround; a live probe should settle both before the query builder ships filter types that depend
  on them.
- `alm-web` dialect body shape (api-ref §3.5) is unprobed; Alt-ALM's grid/aggregation views do not
  currently plan to consume it, but if a future performance need arises, it needs its own probe first.
- Whether `IMAGE_COMPRESSION_LEVEL` (25.1+) re-encodes uploaded image bytes server-side is
  `UNVERIFIED` (api-ref §6.6) — relevant to the generator's byte-comparable image round-trip tests.
- Release-folder root id is `UNVERIFIED` (data-model §2.1, §7) — the metadata service's runtime
  discovery (§2.2) covers this without needing the value pinned in this document.
