# Session State — updated 2026-08-19 (**P1 COMPLETE**; P2 has CRUD endpoints and a validation layer)

Working state, written immediately before a context compact so the continuation loses nothing.
Original kickoff: [docs/prompts/fable-5-research-and-plan.md](../prompts/fable-5-research-and-plan.md).

## ⚠️ How to read this file

It is **append-mostly and chronological**, so the early sections describe a world that no longer
exists. Jump straight to **"Where P1 stands after 2026-08-18 (read this first)"**, then the
**"Known residual issues"** and **"What P1 does NOT have"** lists directly under it. Everything above
those is history, kept for the reasoning it records rather than as a plan — where a stale block could
be mistaken for one, it is marked.

**Current state in one paragraph:** P0 and P1 are done bar rich-text rendering, which the user
deferred. 221 tests green (`./mvnw test` in `bff/`), SPA builds and lints clean, nothing running,
5 commits unpushed on `main`. The app reads Requirements / Test Plan / Test Lab / Test Runs /
Defects against live ALM with a tree-grid, group-by, a detail pane with a collapsing tab rail,
History, related-record tabs with cross-module navigation, and ALM's module rail. **There is still
no write path**, enforced in four independent places.

## Phase 0 decisions (user-confirmed)

| Question | Answer |
|---|---|
| Live instance | **Yes, with sandbox project — writes allowed** for named verification purposes. Credentials live in `Secrets/ALM_API_credentials.json` (keys: `alm_adress`, `api_key`, `api_secret`, `domain`, `project`, `_comments` — all populated, never echo values). Confirm the sandbox project with the user before the FIRST write probe. |
| Versions | **24.1, 25.1, 26.1** — confirmed to be the direct continuation of the classic ALM/QC line after 17.0.x (no 18–23 ever existed; OpenText calendar renumbering; renamed "OpenText Application Quality Management" at 25.1, Jan 29 2025; 26.1 GA Apr 13 2026). |
| Auth | **API key only** (clientId + secret). |
| Stack | **Compare all options seriously — user leans Java.** Architecture phase must weigh Java (Spring) vs TS/Node vs Python vs .NET, honestly, with ADR. |
| Scope note | The generator is **integrated into the Alt-ALM UI** (one product). Rich text must include **embedded images**. **OTA/COM is now an allowed fallback** where REST has gaps (user will supply tdconnect.exe; OTA is COM/Windows-only — architectural weight). |

## Live-probe verified (ground truth for OUR server — details in [live-probe-log.md](live-probe-log.md))

- `POST /qcbin/rest/oauth2/login` with `{"clientId","secret"}` → 200 + full cookie set in one call (`LWSSO_COOKIE_KEY, QCSession, XSRF-TOKEN, ALM_USER, JSESSIONID`). `POST /rest/site-session` → 201. Logout via GET worked (note: docs say GET-logout disabled by default since 24.1 — our server accepted it; version signal or param).
- `GET /qcbin/rest/sa/version` → `SiteVersion "20.0 (Build 20.00.0.143)"` (internal numbering; mapping to marketing 24.1/25.1/26.1 still to pin).
- Customization metadata all JSON: **exactly 8 field-type identifiers** on 15 entity types: `String, Memo, Number, Date, DateTime, LookupList, UsersList, Reference` (matches doc research exactly). Fixtures (redacted) in `tests/fixtures/`.
- `used-lists` = 39, `lists` = 43; requirement types endpoint 200 (fixture saved); entity envelope = `{entities, TotalResults}`.
- Sandbox is empty (0 defects) and has **only 1 user** → user asked (non-blocking) to add ~4–6 dummy users; until then UsersList generation degenerates.

## Cross-cutting research digest (from 8 completed wave-1 reports, all persisted in `_raw/`)

**Auth/session/plumbing**
- REST sessions consume **no licence** (primary-doc confirmed). Visible in Site Connections for monitoring only.
- `X-XSRF-TOKEN` header required on **all non-GET** calls since ALM 16.00 (value = XSRF-TOKEN cookie).
- Recommended auth pair: `alm-authenticate` (JSON since 17.0) and `oauth2/login`; `authenticate`(GET) + `api/authentication/sign-in` deprecated since 17.0. `/qcbin/v2/rest/is-authenticated` (17.0.1+) = JSON-capable session check that doesn't open a session.
- Idle timeout: `REST_SESSION_MAX_IDLE_TIME` default 60 min; keepalive = GET/PUT `site-session`. Error envelope: `{Id, Title}` / `<QCRestException>`; **Accept header must be set or you get HTML error pages**. Full `qccore.*` id catalogue captured. No documented rate limits (genuine gap); login-lockout params exist.
- API keys map to a real user and inherit its permissions; managed in Site Admin; `APIKEY_*` site params.

**The API landscape is fragmented (critical):** Core (`/qcbin/rest/`, evergreen docs, pre-24.1 APIs) · Deprecated (`/qcbin/api/`, older generation, different query grammar, no cross-filters) · `/qcbin/v2/` (some endpoints) · **24.1+ additions documented ONLY in per-instance Swagger at `/qcbin/api-doc/v2/`** (+ `/qcbin/api-doc/sa/v2/` for Site Admin) · SA REST (`/qcbin/v2/sa/api/`, some ops "SaaS only"). **Top queued probe: fetch the sandbox's `/qcbin/api-doc/v2/` OpenAPI and harvest the authoritative endpoint list.**

**Query grammar (Core)**: `?query={field[condition];field[condition]}` — **between-field operator is AND only** (no cross-field OR; issue multiple requests). Operators `> < = >= <=` (+ GT/LT/EQ/GE/LE aliases from 24.1 P1); AND/OR/NOT inside a field's brackets; quotes for spaced literals; `*` wildcard; **no documented null-test or escaping rules for Core** (probes queued). Date literals `yyyy-MM-dd [HH:mm:ss]`. Cross-filters via relation alias (`{defect.owner[joe]}`, disambiguated aliases like `has-parts-test`, `inclusive-filter[false]` for exclusion). `fields=`/`order-by={f[DESC,CI]}` collections-only. Paging: `page-size` (default 100, max 2000 silently capped), `start-index` 1-based, `TotalResults`. Bulk: same-type only, `;type=collection` media type, `REST_API_MAX_BULK_SIZE` 2000, non-transactional, **409 = partial failure with per-item `BulkEntry` results**. `MAX_REQUEST_LENGTH` 10000 KB for `/qcbin/v2/`.

**Customization**: `customization/entities/{e}/fields` full descriptor set (Name, PhysicalName, Label, Size, History, Required, System, Type, isTime, Verify, Virtual, Active, Editable, Filterable, Groupable, SupportsMultivalue, Visible, Searchable, VersionControlled, VisibleInWebUI, CanChangeRequired, List-Id). UDFs = `user-NN`/`XX_USER_NN`, ≤99 per entity, memo UDFs 5 (15 with `EXTENDED_MEMO_FIELDS=Y`). Lists via `used-lists`/`lists` (+ per-entity `lists`); List-Ids instance-specific. Test subtypes enumerated (MANUAL, QUICKTEST_TEST, …). `customization/entities/{e}/permissions` exists (shape unknown — probe). `customization/extensions` for enabled add-ins. `SupportsVC` on entity descriptor = versioning enabled. Users: `customization/users` (+ 25.1 `show-user-groups-names`). Customization is ~all GET-only (exceptions: users/{name} PUT; 24.1 list-item writes, Swagger-only).

**WORKFLOW BOMBSHELL**: `CLIENT_TYPES_BYPASS_REST_WF` — **REST writes bypass workflow-script validation by default** (advanced project scripts apply to Web Client only unless the site param is set to None). Generator: free status setting, but no server-side auto-population — must synthesize realistic derived values itself.

**Rich text**: memo fields store a **full `<html><body>…</body></html>` document**; XML entity-encoded (no CDATA), JSON plain string. Output sanitization per-field (Do nothing / Text encode / HTML sanitize) against a **deployment-specific `sanitizer-whitelist.xml`** (tags/attributes/protocols); `ENABLE_OUTPUT_SANITIZATION` default Y. **Images**: upload attachment with `ref-subtype=1` (multipart only) then reference from memo HTML — **exact `<img src>` syntax undocumented → #1 write probe**. `IMAGE_COMPRESSION_LEVEL` (25.1) may re-encode images server-side. `MEMO_FIELD_ADD_IMAGE_MODE` (Y/N/AS_LINK).

**Attachments**: `.../{entity}/{id}/attachments`; multipart (filename, file LAST, description, override-existing-attachment, ref-subtype) or octet-stream+`Slug` (no ref-subtype). Member GET metadata/bytes by Accept; `?by-id=true` (Core only). Parent lock/checkout preconditions apply.

**Version control**: versionable = requirements, tests, resources, favorites(+folders). `POST .../{id}/versions/check-out|check-in|undo-check-out`; `GET .../{id}/versions`. Lockable = requirement/test/defect via `.../{id}/lock` (POST/GET/DELETE). 25.1 purge-versioning API is Swagger-only. Test Lab entities need no locking.

**Requirements**: create = `name` + `type-id` minimum; root `parent-id=-1`; fields incl. `req-rich-content`+`has-rich-content` (vs `description` — which backs the editor: probe), 27 `rbt-*` risk fields, vc-* set. Types control fields/coverage/RBQM/rich-text-template. **`requirement-coverages` collection works for GET (staff-verified) but is undocumented; POST contested → probe.** **Requirement↔requirement traceability has NO known REST surface → probe (capture web-client traffic).** Convert-req-to-test: no evidence. Bulk ≤2000, 409-partial.

**Test Lab**: `cycle-id` = **test set**, `testcycl-id` = **test instance** (legacy naming!); `test-instance` field is an ordinal. Instance `status` mirrors last run; direct instance-status PUT spawns a synthetic `Fast_Run_*` (avoid; drive `runs`). Run creation: POST with binding fields + `status=Not Completed`, then PUT final status (propagation pitfall, verify). Run-steps under `runs/{id}/run-steps`; `desstep-id` links to design step; auto-copy-on-create unknown (probe); step→run status aggregation unknown (probe). `test-executions` (XML-only) exists — dispatch vs ingest unclear. **Hosts/host-groups/timeslots, purge-runs: no REST** (check Swagger). Execution-flow dependencies live in an opaque test-set description blob.

**Defects & links**: per-project required fields; `defect-links` = `first-endpoint-id`/`second-endpoint-id`/`second-endpoint-type`, defect↔defect non-directional, no link-type field; which second-endpoint-types are valid → probe. Similar-defects = OTA-only. Comments append convention (user/date banner) → probe; PUT replaces wholesale.

**NO documented REST surface (feasibility gaps, OTA-fallback candidates)**: milestones/PPT/KPIs, libraries & baselines, alert rules, follow-up flags (probably), entity history/audit read, similar defects, hosts/timeslots, purge runs, requirement traceability (tentative). Favorites DOES have REST (GET/POST).

**OTA status**: alive in 26.1, COM/Windows-only, no documented sunset; separate Site Admin COM API also ships. 64-bit registration workaround exists.

## File map

- `docs/research/live-probe-log.md` — empirical probe results (ground truth).
- `docs/research/_raw/wave1-0{1,2,3,5,6,7,8,9}-*.md` — 8 completed wave-1 reports, verbatim.
- `scripts/probe/probe-auth.ps1`, `probe-metadata.ps1` — reusable read-only probes (masked output).
- `tests/fixtures/customization-*.{json,xml,txt}` — redacted metadata fixtures from the sandbox.
- `docs/prompts/fable-5-research-and-plan.md` — kickoff prompt (user-amended: 1 UI, images, OTA fallback).

## Research fan-out: COMPLETE

**All 14 reports received and persisted to `_raw/` (wave 1: 9/9 API-domain, wave 2: 5/5 UI-module).
No agents in flight.** Next work is probes + synthesis (see Next actions).

### Late-arriving key findings (fold into synthesis)

- **`test-configs` collection EXISTS** (`GET|POST .../test-configs`, Core-documented) — resolves the
  earlier gap; config-level requirement coverage via `test-config-coverages`
  (first-endpoint→requirement-coverages, second-endpoint→test-configs).
- **`design-steps` collection is documented GET-only** (POST/PUT/DELETE "not applicable"). Either a
  doc gap or writes go through a nested `POST /tests/{id}/design-steps` pattern —
  **top write-probe: without a design-step write path the generator's test chain breaks** (OTA
  DesignStep object is the fallback).
- **No REST entity for test parameters at all** (hasTestParams never populated; community+staff
  corroborated). Values live in `test-configs.data-obj` XML; `<<<param>>>` token syntax in step text
  auto-registers in UI (REST behaviour unknown).
- **Generic `copy` resource**: `POST .../{collection}/copy` with `{IDs, TargetParentId}`, gated by
  per-entity `SupportsCopying`; copies subtrees + attachments, preserves co-copied links.
- **`resources`/`resource-folders` collections exist, but resource FILE CONTENT was
  staff-confirmed REST-unsupported (2017)** — re-check on 24.1+ Swagger.
- Desktop "Subject" path field is not returned by REST — rebuild breadcrumbs by walking parent-id.
- **Manual runner is REST-buildable**: POST /runs + POST/PUT runs/{id}/run-steps all confirmed →
  OTA likely unnecessary for manual execution; reserve fallback for execution-flow/hosts/BPT.
- **Convert-to-Tests wizard EXISTS in desktop UI** (auto-creates coverage) — contradicts wave-1
  requirements agent's "no evidence"; treat as composite client-side op (create tests + coverage).
- Web Client deltas: no Execution Flow / Automation / Analysis tabs; read-only under version
  control (requirements); BPT + Test Resources absent from Web module list.
- Dashboard: `reports/{ID}?alt={mime}` + `?authKey` shared-URL read path exist; graph/dashboard
  authoring NOT-VIA-API (client-side rendering from raw queries is the fallback);
  **standard Excel reports are raw-SQL → structurally out of scope by hard constraint**.
- Pin-to-baseline **deletes all existing runs** on pin (UI fact for the matrix).
- From wave2-05 (management/cross-cutting): PPT scope items/KPIs/milestones lean NOT-VIA-API
  (matches wave-1); filter dialog grammar incl. Cross Filter tab and `"" / not ""` empty tests;
  favorites capture filter+sort+view-type; **data-hiding trap: default Viewer group bypasses ALL
  data-hiding rules** (permission mirroring must special-case it); history client-source column
  means Alt-ALM's writes are visibly attributed by client type; four alert rules enumerated;
  send-by-email is server-side UI-only (Alt-ALM sends its own); no one-click grid export exists in
  classic ALM (Project Reports is the heavy path — Alt-ALM exports client-side); **ADR note: the
  stock WEB client's grid (3 modules, no grouping/freeze/inline-edit/bulk) is thinner than
  desktop — desktop parity claims need per-feature API verification, not web-parity inference**;
  rich-text editor toolbar inventory remains unverified in docs → the sandbox round-trip probe is
  the definitive source anyway.

## Probe round 3 — DONE 2026-08-12 (details in live-probe-log.md, which wins conflicts)

**Server = ALM 26.1** (`external-version` from `/qcbin/v2/sa/api/site-version`; internal 20.0).
**API key = SA role `Customer Admin`** → full Site Admin API works (site-users/project-users CRUD →
dummy-user creation automatable; site-params R/W; audits; run-query = raw SQL → risk-register only).
Deployment is SaaS-flavored (`customers/*` family). Swagger harvested: `api-doc/v2/qc.json` (14 ops,
24.1+ additions: list-item writes, versioningHistory DELETE) + `api-doc/sa/v2/qc.json` (178 ops).
**`rest/resource-list` = per-instance inventory of 1,111 operations** (fixture
`resource-list-site.json`). Gap-list flips (all UNVERIFIED-until-write-probe, but present in
inventory): **design-steps POST/PUT/DELETE+copy; step-parameters full CRUD (4 contexts);
requirement-coverages POST; test-config/test-criterion-coverages CRUD; req-traces CRUD
(traceability!); milestones CRUD; bv-hosts+host-groups CRUD; per-entity `/audits` GET (24 types);
`/mail` POST (19 types); test-executions CRUD; requirement-target-releases CRUD.**
Confirmed still absent: timeslots, libraries, baselines, alerts, follow-ups, purge-runs.
Core `is-authenticated` is XML-only (406 on JSON) — use `v2/rest/is-authenticated` for JSON.

## Probe 4 (write round 1) — DONE 2026-08-12 via Sonnet subagent (detail: `_raw/probe4-write-round-1.md`; log updated)

User approved sandbox writes. All probe records deleted. **VERIFIED:** XSRF-missing → 401;
requirement create `parent-id=1`; **entity-write JSON needs deterministic field order** (wrong
order → NPE 500s); **HTTP 500 can still commit the write** (verify-by-query on 5xx);
rich-text sanitizer strips `<script>`, adds `<tbody>`, reformats whitespace (font/style/href
survive; `has-rich-content` auto-flips); root test-folder = "Subject" (id 2 here, discover at
runtime); **design-steps POST 201 = write path CONFIRMED** (but `<<<param>>>` tokens sanitized to
`<<>>`); **requirement-coverages POST 201** + auto test-config-coverages row; **req-traces POST
201** (`from-req-id`/`to-req-id`); defect + defect-links (defect/requirement endpoints) 201;
audits partial — only status changes logged, creates/memo-PUTs invisible.
**FAILED/open: step-parameters POST** ("Test parameter does not exist"; fields: key,
used-by-owner-type req, used-by-owner-id, parent-id, actual-value).

## Offline mining — DONE 2026-08-12 (Sonnet + Haiku subagents)

- `_raw/probe3-mining-swagger.md` (+ `_raw/probe3-resource-list-basepath-table.md`): **62 entity
  collections share one generic contract** (attachments, `{id}/lock` w/ `version` param on 41,
  `{id}/audits` w/ `readChunks` on 24, bulk `DELETE ?ids-to-delete=` on 58) — model once.
  ~99% of the 1,111 ops have no formal schema anywhere. resource-list has false negatives (v2
  Swagger ops missing from it). SA audits/permissions-metadata are SaaS-only-flagged.
  `application/json;schema=alm-web` = separate narrow dialect (42 ops) — probe later. 17
  deprecated ops = all the legacy bulk-attachment POST form (avoid). `list-items` entity ≠
  `used-lists/{id}/items` (unrelated, confusable). Out-of-scope families noted (SCM/CI,
  business-views, `/synchronization/*`).
- `_raw/probe3-mining-fieldtypes.md`: **no Boolean type** — Y/N fields are LookupList (list id 1,
  Y/N) or String/Number flags; 80 flag-like fields mostly read-only/virtual; only 2 multivalue
  fields in the model (`requirement.target-rel`, `.target-rcyc`); 191 System+read-only fields;
  unused-list delta = Activity Status, VC Status, Resource Type, TestType; memo Size=-1
  (unlimited), virtual path fields 99999.

## Write round 2 + first synthesis — DONE 2026-08-12 (3 subagents; log Probe 5 section has detail)

Round 2 verified: roots (req parent-id=0 fixed), img-src sanitizer rules (bare→stripped;
https:// and data: URIs survive), entity-encoded `<<<param>>>` tokens survive (raw ones mangled),
milestones parent under RELEASE (MS_RELEASE_ID), test-set/test-instance creates, test-executions
POST = dispatch, cycle date validation enforced. **Open failures:** run POST ("must number
attribute 'TESTSET'" — blocks run-steps/mirror/Fast_Run/aggregation questions), multipart
`ref-subtype=1` upload (opaque parse error), mail POST (undocumented body), step-parameters
(no REST create for the parameter object — OTA candidate). BPT: no `components` collection in
inventory (only `{id}/snapshot`); GET-probe queued; likely OTA-only.
**Synthesis docs now exist (drafted by subagents, NOT yet lead-reviewed):**
`docs/research/alm-api-reference.md` (provenance-tagged) and
`docs/research/alm-ui-feature-inventory.md` (231 features; its "conflicts" partly superseded by
probes — reconcile in feasibility matrix).

## Write round 3 + data model — DONE 2026-08-12 (log Probe 6 section has detail)

**Direct `POST runs` does not work (8 attempts, bimodal errors); runs must be created via
`PUT test-instances/{id}` status → server-synthesized Fast_Run (3/3 reliable).** Run-steps
auto-copy VERIFIED; instance↔run status mirror VERIFIED; no eager step→run aggregation.
**Multipart `ref-subtype=1` WORKS** (hand-built body; round-2 failure was a PS `-Form` artifact)
→ embedded-image flow end-to-end viable. `GET /components` 403 (license-gated),
`business-components` 404 → BPT = OTA candidate. `docs/research/alm-data-model.md` drafted
(entity catalog, relationship map, creation-order DAG, per-entity notes, conflicts adjudicated).
⚠️ Review debt: `alm-api-reference.md` §6.1 has a corrected-by-data-model discrepancy note and
§9 still lists multipart as an open failure — fix both during lead review; also fold Probe 6
into it. Data-model agent corrections are authoritative where they cite r3-* fixtures.

## Feasibility matrix + lead review + plan set — DONE 2026-08-12 (commits fc2270d…0c05039)

1. **`docs/research/feasibility-matrix.md`** DONE (Sonnet draft, lead-spot-checked): 218 features ×
   verified API; 58% achievable (FULL 53 / FULL* 22 / PARTIAL 51); OTA 23, NO 21, N/A 17,
   UNVERIFIED 31; all 10 UI-inventory conflict rows resolved; generator-impact appendix (hard gap:
   step-parameters).
2. **Lead review pass** DONE: api-reference §6.1 false discrepancy corrected (fixture shows
   parent-id=5), Probe 5/6 folded in (new §6.7b Test Lab / Fast_Run section), §9 split into
   resolved-vs-open; data-model r3-narration + step-aggregation notes updated; probe-log open-items
   list refreshed (item 10 = deferred probes).
3. **Plan set** DONE (lead decision brief `docs/plan/_lead-decision-brief.md` D1–D7 → 3 Sonnet
   drafting agents, lead-reviewed):
   - `docs/plan/architecture.md` + ADRs 0001–0005 (BFF required; **Java 25 + Spring Boot** BFF +
     React/TS SPA — scored 4-way comparison, user preference named as tiebreaker; OTA Windows-only
     sidecar; service-account pooled sessions + app-level users, per-user-key evolution path;
     metadata-driven rendering).
   - `docs/plan/data-generator-spec.md` (normative safety model: dry-run default, allowlist hard
     stop, ALTALM-GEN provenance incl. transitive provenance for name-less entities, manifest-replay
     + prefix-sweep cleanup; seeded PRNG; DAG with parallel branches; 8-type strategy matrix).
   - `docs/plan/implementation-plan.md` (P0–P6 + deferred-probe map), `test-strategy.md`
     (4-level pyramid, probe-derived must-have cases), `risks-and-open-questions.md`
     (16 risks R1–R16, 31 open questions Q1–Q31 each with experiment + phase).

## Skills + CLAUDE.md — DONE 2026-08-12

All five skills authored under `.claude/skills/` (verified content only, provenance-cited):
`alm-api` (258 lines, load first), `alm-entity-model` (215), `alm-live-probe` (217),
`alm-data-gen` (192), `alt-alm-ui` (163, lead-authored). CLAUDE.md rewritten with the durable
verified-facts section, the design decisions, the sandbox designation, and the TDConnect note.

**TDConnect is available** at `TDConnect/` (git-ignored): 24.1 / 25.1 / 26.1 CE SAAS.

## OTA spike — DONE, RESULT POSITIVE (live-probe-log.md, **Probe 8**, 2026-08-13)

⚠️ **Probe 7's negative verdict is RETRACTED.** It blamed the SaaS SSO front door; the real cause was
a hand-extracted OTA client. **Probe 8 is authoritative: OTA WORKS.**

Using ALM's **own deployed client** (`%LOCALAPPDATA%\HP\ALM-Client\20.00.0.0_952\`, v20.00.0.174):
`InitConnectionWithApiKeyEx(url, clientId, secret)` → Connected/LoggedIn=True;
`Connect(domain, project)` → ProjectConnected=True. **The API key authenticates OTA — no
username/password, SSO is not an obstacle.** Reads and writes both verified (test folder + test
created and deleted via OTA).

- **BPT is writable via OTA** — component created and deleted. REST's 403 was **not** a licence gate;
  the OTA errors were structural. Recipe: component folder → subfolder → subfolder's
  `ComponentFactory`.
- **Test parameters (Q18)**: `Test.Params` is a collection, not a factory. Declaring directly does
  not persist; a **`<<<token>>>` in a design step registers the parameter** (0→1 verified). Setting
  its default value fails — `UNVERIFIED`.
- **All other OTA-only factories reachable**: Baseline, Library, Host, HostGroup, Milestone, KPI,
  ScopeItem, + PurgeRuns/SynchronizeFollowUps/AlertManager/ExtendedStorage.
- **Environment (hard constraints)**: 32-bit host process; version-matched client; use ALM's
  deployed client (NOT an installer payload); per-user COM keys must be written from a 32-bit
  process (WOW64); typelib must be registered separately or everything fails
  `TYPE_E_ELEMENTNOTFOUND`.
- ⚠️ **OTA folder delete does not cascade to tests** — 5 orphans were left behind and swept via REST.
  Always sweep `tests` *and* `test-folders` by prefix after OTA cleanup.

**Consequences:** the ~23 OTA-verdict features are **back in scope**, gated on building the sidecar
(P6). ADR 0003's language decision (.NET vs Python + pywin32) is **live again**. Feasibility matrix,
risks (R9/R10/R17/R18, Q18/Q32/Q33 + new Q34) and architecture.md all corrected.

## Probe 9 — the test-parameter gap is CLOSED over REST (2026-08-13)

⚠️ **This retracts "there is no REST path to define a test parameter", held since Probe 4.** Full
detail in [live-probe-log.md](live-probe-log.md) §Probe 9; scripts `probe-write-4.ps1`, `-4b.ps1`,
`probe-ota-7-paramcheck.ps1`. All records cleaned up; orphan sweep 0.

**Two entities, not one** — this is why 5 attempts at one endpoint never worked:

| Entity | Physical | Role |
|---|---|---|
| `test-parameter` | `TP_*` | **defines** a parameter on a test |
| `step-parameter` | `SP_*` | **records a value** against a defined one |

`step-parameter.parent-id` = the **test-parameter id**, not the design-step/test id. Earlier probes
passed the owner id, so `"Test parameter does not exist"` was literally true.

- **Create**: `POST tests/{id}/test-parameters` with `name` + `ref-count` → **201**. (`parent-id` is
  read-only; the owner comes from the URL. The flat form works with `parent-id` + `ref-count`.)
- **Q34 = YES**: an entity-encoded `&lt;&lt;&lt;name&gt;&gt;&gt;` token in a REST-written design step
  registers a real parameter, and the token name **survives round-trip** (a raw `<<<name>>>` is still
  mangled to `<<>>`). Parameters have independent lifetime — not cascade-deleted with the step.
- **`POST step-parameters` → 201** for owner types `design-step` and `test`.
- **`PUT test-parameters/{id}` `default-value` → 200** — **OTA cannot do this**. Use REST.
- **OTA cross-check confirms** the REST-created objects are real (`Params.Count=5`, names match).
- ⚠️ **NEW WRITE HAZARD**: `ref-count` is metadata `editable:false, required:false`, yet the create
  500s without it (`missing required field TP_REF_COUNT`) and succeeds with it. **Metadata does not
  fully describe writes.** On a 500 naming a missing physical field, retry once including it.
- `UNVERIFIED`: `DELETE design-steps/{id}` 500s when a `step-parameter` references it (workaround —
  delete children first, or delete the parent test — is verified).

**Consequences:** the only *generator-blocking* gap is gone; ADR 0003's sidecar drops from **three
gaps to two** (BPT components, similar-defects) and no longer blocks the generator.
⚠️ **"so P6 is genuinely optional" was written here and is now WRONG** — probes 11–12 grew the
sidecar back to **eight named surfaces** the same day. It remains off the *generator's* critical
path, but it is load-bearing for the product's feature surface. See ADR 0003 Addendum 3.

## Probe 10 — API-key session concurrency (2026-08-13)

**One API key held 50 simultaneous sessions, zero evicted, all usable at the same instant.** Tested
at 12 then 50; **no cap reached — 50 is a floor.** Unlike a username/password login (bound by
concurrent-user licensing, in practice one active client per seat), an API key over REST has **no
one-machine-at-a-time constraint**. This also corroborates the no-licence-seat finding empirically.
`JSESSIONID`, `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN` are each unique per session; only
`ALM_USER` is shared. **ADR 0004's pooled-session design is safe** — pool size is bounded by
politeness and keepalive cost, not a cap. `UNVERIFIED`: all 50 came from one machine/IP, so per-IP
binding is untested.

## Probes 11–12 — the 21 `NO` verdicts re-audited (2026-08-13)

Web research (`_raw/no-verdict-recheck.md`) plus a read-only REST probe (11) and read-only OTA
probe (12). **`NO` dropped 21 → 13; eight rows were wrong.**

- **#18 "Analyze" SOLVED → `FULL*`.** `Customization.RBT` exposes `TestingPolicyMatrix`,
  `RiskCalculationMatrix`, `TestingLevelPercentage`, `TestingEffortForFCLevel`. It is a documented
  lookup table, not a hidden algorithm. Read once per project over OTA, compute client-side; the
  `rbt-*` writes are already REST. **Per-project admin config — never hardcode.**
- **Six rows `NO` → `OTA`**: #129 BusinessViews + `GraphBuilder`, #132/#133
  `ReportProjectTemplates` (79), #145 `KPITypes` (11), #166 (+#109/#196/#197) `AlertManager`,
  #205 `Modules.IsVisibleForGroup` / `Permissions`.
- ⚠️ **Probe 12 was READ-ONLY.** It verified the objects exist, are readable, and carry Add/Remove
  methods — it never called one. **Write capability on all six is `UNVERIFIED`.**
- **#209/#210 workflow scripts survive as `CONFIRMED NO`** — `Customization.Workflow` has only
  `ProjectScriptsUpdated`/`TemplateScriptsUpdated`, no script content.
- **RETRACTED probe 10's claim** that SA session visibility was unreachable:
  `GET /qcbin/v2/sa/api/site-connections` → 200. I had guessed three non-existent paths while the
  real one sat in `tests/fixtures/api-doc-sa-v2-paths.txt`. ⚠️ It returns **third-party identities** —
  mask structurally by JSON key.
- **ADR 0003 Addendum 3**: sidecar scope went three gaps → two (probe 9) → **eight named surfaces**
  the same day. It is now **load-bearing for the feature surface**, though still off the generator's
  critical path. **Settle .NET vs Python + pywin32 at P6 kickoff**, not during.

Matrix now: FULL 54 / FULL* 23 / PARTIAL 50 / UNVERIFIED 32 / NO 13 / OTA 29 / N/A 17 = 218;
achievable **127/218 (58.3%)**.

## P0 — ✅ COMPLETE (2026-08-13), all five exit criteria met

Monorepo, Maven — both chosen by the user.

| Path | State |
|---|---|
| `bff/` | **Builds, 11 tests green.** Spring Boot 4.1.0, JDK 25, Maven **wrapper** (`./mvnw test` — no local Maven) |
| `spa/` | **Builds.** Vite + React + TypeScript |
| `.github/workflows/ci.yml` | Both halves + a check that fails if `Secrets/` is ever tracked |
| `bff/.../alm/write/` | Write-safety core: `AlmEntityBody` (deterministic field order), `AlmWriteOutcome` (5xx→UNKNOWN), `AlmWriteRetry` (single missing-required-field retry) |
| `bff/.../alm/metadata/` | `AlmFieldType` (the 8 verified types, no Boolean), `FieldDescriptor`, `AlmMetadataParser` (no HTTP dependency — parses offline) |
| `bff/.../alm/session/` | `AlmCredentials` (runtime-only load, refuses to render itself), `AlmSession`, `AlmSessionPool` (bounded, idle-eviction, keepalive scheduling), `AlmAuthClient` (one-step login, XSRF, keepalive, two-call logout) |
| `bff/.../alm/metadata/` (cont.) | `AlmMetadataClient` (the HTTP half), `AlmMetadataCache` (**project-scoped, explicit invalidation only, single-flight, failures not cached** — ADR 0005) |
| `bff/.../config/` | `AlmProperties` (`alt-alm.alm.*`), `AlmConfiguration` (beans + keepalive schedule). **No ALM contact at startup** — the pool logs in lazily on first borrow, which is what lets CI start the context with no credentials |
| `bff/src/test/.../alm/contract/` | `AlmSandbox` (credential discovery, `@EnabledIf` gate, masker), `AlmAuthClientContractTest` + `AlmMetadataContractTest` (**live**, tagged `contract`), `CredentialMaskingTest` (runs on every build) |

**57 tests green by default; 69 with `-Pcontract`; 12 skipped and green with no credentials.**
20 of them are the fixture harness: it parses **all 15** captured
`customization-fields-*.json` entities with **no server and no credentials**, and asserts the model
facts in executable form (exactly 8 field types, no Boolean, `rbt-*` family present on requirement,
multivalue is rare, memo size −1, malformed payload fails loudly rather than looking like an empty
entity). 7 more cover pool behaviour (reuse, bound, idle-eviction, failed-login slot release,
logout-on-close, keepalive timing, and that `toString` never leaks cookie values).

**The live contract test is in and green** (probe 13). `AlmAuthClientContractTest` — 9 ordered cases
tracing one session's lifecycle: one-step login, v2 is-authenticated, project reach, keepalive,
XSRF-missing → 401, a 3-session pool with distinct `QCSession` cookies, site-session redundancy,
teardown semantics, and an `@AfterAll` orphan sweep that **asserts** zero `ALTALM-CONTRACT*` rows
rather than merely cleaning up. Gating: tagged `contract` → excluded from the default build and CI
via Surefire `excludedGroups`; `./mvnw test -Pcontract` opts in; absent `Secrets/` it **skips**
(9 skipped, build green), never fake-passes.

⚠️ **Its first run found two real bugs in `AlmAuthClient`**: `logout()` issued only
`DELETE site-session`, which leaves the LWSSO authentication alive — one leaked identity per pooled
session; and `login()` built the session from the login response then discarded the cookies
`POST site-session` sets. Both fixed. **This is the first finding in the project surfaced by product
code under test rather than a hand-written probe** — the argument for contract tests, made by the
contract test.

**Boot 4 gotchas already paid for** (all found by building): starter is `spring-boot-starter-webmvc`
not `-web`; test deps are **per-starter**; **Jackson is not transitive** (add
`spring-boot-starter-json`); it is **Jackson 3** — package **`tools.jackson.*`**, unchecked
exceptions; Initializr metadata says `4.1.0.RELEASE` but the real artifact is **`4.1.0`**.

**Environment**: JDK 25.0.4 Temurin, machine-level `JAVA_HOME` set by its installer — **do not add
user-level Java env vars, they shadow it** (this bit us once). ⚠️ Repo is in a **OneDrive-synced
folder**: it locks `bff/target` and breaks `mvnw clean`. Run without `clean`.

## Next actions (in order)

1. ~~**Finish P0**~~ ✅ **DONE 2026-08-13 — nothing outstanding.** All five exit criteria met:
   live auth + keepalive (probe 13), the metadata cache (verified live: **15 entities, 432 fields,
   all 8 types**, independently reproducing the original probe's count), the fixture suite,
   write-safety unit tests, and `@ConfigurationProperties` bean wiring.
2. ~~P1's phase-start deferred probe~~ ✅ **DONE 2026-08-14 (probe 15)** — see §15.1–15.4 in the
   probe log. It settled the `alm-web` dialect, **corrected a wrong tree-root rule that was written
   into the plan, the data model and three skills**, and surfaced a sequencing problem (Q45).
3. ~~**START HERE — P1 implementation**~~ ✅ **DONE 2026-08-18, except rich text (0d).** ⚠️ This
   block is history; the current state is "Where P1 stands after 2026-08-18" further down, which is
   the section to read first. The text below is kept for the reasoning it records, not as a plan.
   **P1 implementation** (read-only Alt-ALM) per
   [../plan/implementation-plan.md](../plan/implementation-plan.md). This is the first phase with
   **visible output**: metadata-driven grids for requirements/tests/defects, the Core query builder,
   tree navigation with runtime root discovery.

   **Suggested shape** (raised with the user, not yet decided): take a **thin vertical slice first**
   — one entity, one grid, live data end-to-end through BFF → SPA — before building out the query
   builder, tree nav, and the other two entities. It de-risks the BFF↔SPA seam early and puts a real
   screen up far sooner, since nothing has been visible for the whole of P0. **Probe 15 sharpens
   this**: the slice should be **requirements**, because it is the only entity in the sandbox with
   any rows at all.

   ✅ **Q45 resolved by probe 16 — no decision needed.** The user granted **read-only access to the
   tenant's other projects** (2026-08-14) and allowed their data to seed sandbox records. Eight are
   readable; **`PROJECT-5` has 233 reqs / 129 tests / 80 defects / 227 test-instances / 178 runs**.
   Point P1's read paths there and the phase validates against live data with no generator. Real
   names live in git-ignored `Secrets/alm-read-projects.json` — **never paste one into a doc,
   fixture, commit or log**; use the `PROJECT-N` pseudonyms.

   ⚠️ **Read-only is a code constraint, not a habit**: the BFF must refuse every non-GET to any
   project but the sandbox, via the same explicit allowlist the generator uses. Other teams' live
   projects are on the other end of it.

   Traps waiting in P1, now with probe-15 detail:
   - **Tree roots**: use `{parent-id[-1]}` then fall back to `{parent-id[0]}`. The old
     `{parent-id[0]}`-only rule returns **`Recycle Bin`** as the `test-set-folders` root and looks
     completely successful doing it (R16).
   - **Paging**: `page-size=max` exists; out-of-range is a **404**; `page-size=0` reports
     `TotalResults=0` on a non-empty collection. Whether over-cap silently clamps is still
     UNVERIFIED — the grid must still surface "more results than shown" rather than trusting
     `TotalResults`.
   - **Group-by is server-side after all** — plain JSON carries `size` and `expression`; the
     client-side aggregation fallback is dropped.
   - **Do not reach for `schema=alm-web`** even though it returns much nicer flat entities — it is
     undocumented on collection reads and drops `children-count` (R15).
   - The Core query grammar's **undocumented null-test/escaping rules** — flag as a risk, do not
     silently "handle" them.

### Deployment + credentials — asked and answered 2026-08-13, decision: **no change**

The user asked whether Alt-ALM could be hosted on **GitHub Pages** with ALM credentials kept in a
browser cookie, motivated by **not wanting to pay for hosting**. Probed rather than inferred:

- **Probe 14 closed it by mechanism.** CORS preflight → **501**; even a *successful* credentialed
  `POST oauth2/login` returns **no `Access-Control-Allow-Origin`**. A browser cannot call `/qcbin`
  cross-origin. ⚠️ The trap: the server handles these requests fine, so curl/Postman "prove" it works
  — only a browser enforces CORS. This upgraded ADR 0001's premise from *not observed* to verified.
- **The cookie idea fails on three independent counts**, recorded in ADR 0004 Addendum 1: cookies are
  origin-scoped so ALM would never receive it; a JS-readable cookie is exposed to any XSS or
  compromised dependency; and an ALM API key inherits its user's **full permission scope** — it is not
  a scoped token.
- **New hard constraint: only ONE ALM user seat for testing.** This makes per-user credentials
  untestable and confirms ADR 0004's single-service-account model (Q44).

**User's decision: keep the current implementation; hosting is deferred (Q42).** Local-first (BFF +
SPA on `localhost`, GitHub hosting source only) costs nothing and is what P1 needs anyway. When it
does need to be reachable, prefer the **single-deployable / single-origin** shape — Spring Boot
serving the built SPA as static resources — which needs no CORS config and no cross-site cookies.
Worth settling **before** any cloud choice: **Q43, whether the tenant IP-restricts API access** — never
tested, every probe so far came from one host on one network.

Worth doing soon, cheap now that the harness exists:

- **OTA write probes** for the six newly-flipped rows — converts `UNVERIFIED` writes into fact and
  de-risks P6's scope *before* the sidecar language is chosen.
- Re-probe `attachments/{id}/audits` (#186) once P2 creates an attachment — currently inconclusive,
  not negative.
- Remaining open items are **Q**-numbers in
  [../plan/risks-and-open-questions.md](../plan/risks-and-open-questions.md); risks are **R**-numbers.

## ✅ THE POC IS DONE — 2026-08-17 (user). Next session: complete P1 properly.

The proof-of-concept phase is closed. The Requirements module runs end to end against live data with
a tree-grid, metadata-driven columns, working detail tabs, a theme toggle and a screenshot harness.
**Both servers were shut down at the user's request** — nothing is running; see "Running it" below.

### Where P1 stands after 2026-08-18 (read this first)

**Every gap on the 2026-08-17 list is now closed. P1 is feature-complete.** 221 BFF tests plus
**27 SPA tests** green; SPA builds and lints clean; all 35 referenced CSS tokens resolve.

⚠️ **The SPA now has a test runner** (vitest + jsdom, `npm test` in `spa/`, gated in CI). It was
added for one reason — the memo sanitiser is a security boundary and shipping one with no tests was
not defensible — and its suite is a payload suite, not an example suite. If you add SPA tests, that
is the bar the existing file sets.

Closed today, in order:

1. ✅ **Gap 0e — the project-switch tree race.** The old project's prefetch resolved *after* the
   reset and marked the new project's ids as already-fetched, so every real request was skipped as
   redundant and the tree stayed empty. Fixed with a request epoch. ⚠️ A `cancelled` flag could not
   have fixed this: the damage was a stale write to a **ref**, not a stale `setState`.
2. ✅ **The collapsing tab rail** (user request). Icons at 40px, hover overlays at 190px, double-click
   pins, pin persists in `localStorage` across records/projects/reloads. Tabs are blued when they
   hold rows, from one `GET /api/tabs/{c}/{id}`. ⚠️ A tab whose probe **fails** is absent from that
   map rather than reported empty — unknown and empty look identical to a reader.
3. ✅ **History (Audit Log)** — probe 24. **Baselines deliberately absent** (OTA-only, probe 12);
   the panel says so rather than showing a permanently empty sub-tab.
4. ✅ **ALM's per-tab column sets**, pinned by field NAME, plus the far-end record's Name.
5. ✅ **Group By** (gap 1) — field selector over ALM's own `groupable` flag, bucket chips with real
   counts, drill-in re-queries.
6. ✅ **The module left rail** (gap 0) — with three distinct verdicts, not one greyed-out state.
7. ✅ **Per-subtype field sets** (gap 0a) — and the measurement that shrank the claim; see below.

**Corrections to things previously written down here — read these before trusting the old text:**

- ⚠️ **Gap 0a overstated the per-type difference by an order of magnitude.** It said the sets
  "genuinely differ (13–20 non-memo by type)". Probe 25 measured **70–72 fields against 74**, with
  **zero** flag differences, moving the Details form by **exactly one field**. A subtype only ever
  omits. Worth doing, done, but not the correctness hole it was described as.
- ⚠️ **`test`, `test-set` and `run` have no subtypes at all**, and `defect`'s types endpoint returns
  **HTTP 500**. The per-type read is therefore gated on the record carrying a `type-id`.
- ⚠️ **The tab-strip "trade-off" recorded on 2026-08-17 was not the last word either.** Test Coverage
  was still rendering the same 29 rows twice; a discriminated query beside its own superset is a
  refinement and folds. But **one** narrow group beside a broad one is a refinement while **nine**
  are a fan-out — the first version of that rule collapsed a defect's nine `defect-link` tables into
  one and the existing regression test caught it.
- ⚠️ **`Set.copyOf` randomises iteration order per JVM run.** The project dropdown reshuffled itself
  on every restart. Anything selecting a project by index across restarts was selecting at random.

**The one genuinely new API finding — and it was silent (probe 26).** An unquoted multi-word filter
value does not fail, it **answers a different question**: `{status[Not Completed]}` parses `NOT` as a
grammar keyword and returned **233 rows against a group count of 8**. `AlmQuery` now quotes values
containing whitespace, which is what ALM's own group `expression` does. This affected the grid's name
search too, so any two-word search had been silently matching everything.

**Rich text (0d) closed last, 2026-08-18.** The decision it was gated on, and the reasoning, live
in `spa/src/detail/richText.ts`; the short version:

- **DOMPurify, in the browser, not in the BFF.** Sanitising server-side was the tempting symmetry
  with ADR 0001, and it is the weaker answer: a server-side sanitiser parses with a *different* HTML
  parser than the one that finally renders, and that gap is the mutation-XSS class. Sanitising in
  the engine that renders removes the gap instead of arguing about its width.
- ⚠️ **`USE_PROFILES` overrides `ALLOWED_TAGS`, it does not intersect with it.** With
  `USE_PROFILES: { html: true }` set alongside a deliberately narrow tag list, `<form>` and
  `<input>` sailed through. Caught only because the tests assert on **output** rather than on
  configuration — a test written against the config would have passed.
- **DOMPurify does not sanitise CSS.** Defensible on its part (`expression()` is dead, `url(js:)`
  does not execute), but `url(https://…)` in a style attribute is a beacon, so declarations are
  filtered separately.
- **Probe 27: a hostile memo does not survive a REST round trip — because of OUTPUT sanitisation,
  which is per-field project configuration over a deployment-owned whitelist file.** The first
  reading ("ALM sanitises on write") was wrong; the raw value stays in the database and a project
  set to *Do nothing* returns it live. That makes the client-side sanitiser **load-bearing rather
  than defence in depth**. ALM does *not* strip remote `<img src>` in any configuration.
- **Memo fields are HTML and only HTML** — no markdown, no wiki, and ⚠️ **newlines are collapsed to
  spaces rather than becoming `<br>`**, which is a data-loss trap waiting for P2's write path.

✅ **Test Lab's drill-down landed 2026-08-18.** Selecting a test set and choosing "Open as grid" on
its Test Instances tab makes the main grid that set's instances, with a breadcrumb back to the set.
Nothing in it is about test sets: the scoping filter arrives on the related table (`scopeField` +
`scopeFixed`), derived from the relation's storage descriptor, so any related tab naming a scope
column drills the same way. Three findings on the way, all of them bugs that looked like features:

- ⚠️ **The instances tab did not exist**, and the reason was ours: `canRead` consulted only the
  *related-collection* map, so `test-instance` — a module entity the BFF has served all along —
  answered "nothing can read this" and ALM's own containment relation was dropped. Fixing it also
  gave Test Plan its **Runs**, **Test Instances** and **Test Configurations** tabs.
- ⚠️ **A mirrored containment reference points at the record's own container and lies about it.**
  `filterIdField` is documented as a column on the *read* entity; on this one direction ALM hands
  back the column from the *source* side, so "the folder this test set is in" became "the folders
  whose parent is test set 301" — HTTP 200, zero rows, a tab saying the set is in no folder. The
  field-exists validation passes because folders really do have a `parent-id`.
  `AlmRelation.pointsAtOwnContainer()` drops these.
- ⚠️ **The drill-in switched module with the old record still selected**, so one render asked for
  `detail/test-instances/<a test set's id>`. Same shape as gap 0e. Cleared synchronously now.

⚠️ **Verified against seeded data, not borrowed data** (probe 28). The sandbox had 0 test sets, so
two full chains were seeded — folder → test → set-folder → set → instance — and the drill-in shown
to return 1 of the project's 2 instances. Re-seed with
`python scripts/probe/probe-testlab-seed.py --keep` and sweep after.

**Next, in order:**

1. ~~**Test Lab's drill-down**~~ — test set → instances → runs. Runs is now a rail module (ALM lists it
   as one); instances remain a link target only, as in ALM.
2. ~~**The `EntityStatus` question**~~ **ANSWERED — probe 29, 2026-08-18.** Summary below; the full
   run is in the probe log.
3. 🟡 **P2 — the write path. STARTED 2026-08-18.** Its phase-start deferred probe is done
   (probe 30, below) and the write core has landed — see the P2 section immediately below.

## ⚠️ DO NOT run the contract suite while the app is running (found 2026-08-20)

**Creates return `UNKNOWN` (ALM 5xx) when a local BFF is serving at the same time.** Reproduced
three times with the server up, and **24/24 pass the moment it is stopped** — so this is a real
interaction, not a flaky test.

Most likely mechanism: probe 13 established `POST authentication-point/logout` ends the
**authentication**, not just the project session. Both processes share one API key, so a pool
closing at the end of a test class plausibly invalidates sessions the running app still holds, and
vice versa. ⚠️ This does **not** contradict probe 10's 50 concurrent sessions — those were all
opened by one process and none logged out while others were live. The new variable is a **logout
while another holder is active**. Mechanism is `UNVERIFIED`; the *interaction* is reproduced.

**Operational rule: stop the BFF before `./mvnw test -Pcontract`.** Kill the port holder on 8080,
not the Maven parent.

⚠️ **It also leaked a row, and that is the more instructive half.** An `UNKNOWN` create returns **no
id**, so `RecordServiceContractTest` never tracked it — and the tracked-id cleanup could not delete
what it had never recorded. The prefix sweep caught it on a later run and failed the build, exactly
as designed. **The lesson: cleanup that tracks ids cannot cover the one outcome where there is no
id.** The prefix sweep is not redundancy, it is the only thing covering the 5xx case.

## P2 status (started 2026-08-18; CRUD endpoints landed 2026-08-19)

**345 BFF tests default, 388 with `-Pcontract`. 76 SPA tests.**

✅ **The write core is in and verified live.** `bff/.../alm/write/`:

- **`AlmWriteClient`** — the one place Alt-ALM writes. Enforces, in one place, every hazard probing
  found: the allowlist check **before any I/O**, `AlmEntityBody`'s canonical field order (probe 4),
  5xx → `UNKNOWN` never `REJECTED` and never retried blind (probe 4), the single missing-field retry
  (probe 9), `X-XSRF-TOKEN` on every write (probe 13), and `id -1` refused as the tree root sentinel
  (probe 27).
- **`AlmWriteResult`** — deliberately has **no `isSuccess()`**. `UNKNOWN` is neither, and a
  convenience boolean is how it would get bucketed with one of them. `verify()` resolves it against a
  caller-supplied query and **still leaves the outcome `UNKNOWN`**: "the row exists" is not "the
  write succeeded", and only the first has evidence.
- **`AlmFieldResolver` / `AlmMetadataFieldResolver`** — maps the physical column in an ALM error back
  to a logical field *and chooses its value*, since metadata calls that column neither required nor
  editable so there is no user intent to consult. With no metadata it resolves nothing and the retry
  **switches off rather than guessing**.
- ⚠️ **Single-entity only** — the server's limit, not a simplification (probe 29).

✅ **`ApiIsReadOnlyTest` was REWRITTEN, not deleted**, exactly as CLAUDE.md instructed for the moment
writes arrived. It now asserts every write mapping's controller can reach `AlmWriteClient`
(transitively), and that no `api` class holds an HTTP client of its own. Verified in **both**
directions with a temporary violating controller before being trusted.

✅ **`AlmWriteClientContractTest`** — 10 cases against the live sandbox, passed first run. Full gate:
`ALTALM-CONTRACT-*` names, reverse-order unwind, and the sweep **asserts** zero survivors (a tracked
delete is not the same claim as nothing surviving). It pins the memo-replace behaviour live, with a
note that a failure there would mean ALM started appending — at which point the comment path would be
*doubling* every comment rather than merely needing read-modify-write.

⚠️ **The sandbox-only write rule was LIFTED by the user on 2026-08-18.** `AlmAccessPolicy` now
permits writes to **any enrolled project**; enrolling a project in `alt-alm.alm.readable-projects` is
now a **write** grant. Only the sandbox is currently reachable, so nothing else is enrolled — but
that setting is load-bearing in a way it was not before. Every test that pinned the old rule was
rewritten rather than deleted; the suite caught one that had not been anticipated (`GridServiceTest`
pinned `writable=false` for a read-only project, and that flag drives the SPA's edit affordances).

✅ **CRUD endpoints are in, and validated live** (2026-08-19). `bff/.../api/`:

- **`RecordController`** — `POST/PUT/DELETE /api/records/{collection}`, plus a **separate**
  `POST .../{id}/comments` route. Its own controller rather than mappings on `GridController`,
  so that class's "every mapping here is a GET, and that is load-bearing" javadoc stays true
  instead of becoming a description of how things used to be.
- **`RecordService`** — where an `UNKNOWN` write stops being a shrug. `AlmWriteClient` returns
  `UNKNOWN` and refuses to guess because it does not know what identifies the row; this layer does,
  so it writes the `verify()` finder: a create asks whether **one** row carries the name it sent,
  an update asks whether the values it sent landed, a delete asks for **absence**.
- **`AlmWriteValidator`** — the stand-in for the workflow scripts REST bypasses. See below.
- **`AlmVersionGuard`** — the probe-31 conflict check, **extracted** so the comment path and the
  CRUD path share one implementation. A probe-derived safety rule written twice is one that will
  eventually be written differently, and the copy that drifts is the one nobody is watching.

⚠️ **`ApiIsReadOnlyTest` now has real write endpoints to guard, and was re-verified in both
directions** — it passes `RecordController` (which reaches `AlmWriteClient` transitively) and fails
a deliberately-broken controller, which was then deleted. This is the first time the rewritten guard
has had anything to catch.

### The validation layer, and what it deliberately does NOT do

`AlmWriteValidator` exists because **ALM's own validation is switched off for us**: workflow scripts
are bypassed on REST writes, so a record Alt-ALM writes skips every rule a record made in the stock
client passes through. What it enforces is what metadata actually states — unknown fields, virtual
fields, server-owned `id`/`ver-stamp`, date and datetime grammar, declared string size, and the
memo-is-HTML trap. What it **refuses** to enforce is the more important half:

- **Required-on-create is not checked.** `required:false, editable:false` yet required by the server
  is probe 9's actual case. Enforcing either flag would refuse writes ALM accepts.
- **Lookup-list membership is not checked** — there is no list client yet, every Y/N flag is a
  LookupList too, and a wrong guess rejects correct writes. ALM decides.
- **Numbers accept decimals.** Integer-ness is **UNVERIFIED**; parsing as `Long` would have been
  this layer inventing a constraint. Settling experiment noted in the code.

⚠️ It is **necessarily incomplete** against arbitrary VBScript, and CLAUDE.md carries that as a
permanent limitation. Do not let its existence read as "writes are validated".

⚠️ **One real defect it shipped with, caught by a test**: when the per-type metadata read returned an
**empty** set, every field of a valid body came back as `unknown-field` — a wall of confident,
specific, wrong errors aimed at the caller rather than at the metadata read that actually failed.
`AlmMetadataCatalog` already falls back when that fetch *throws*; nothing covered a fetch that
succeeds and returns nothing. Now guarded, with a test.

✅ **`RecordServiceContractTest`** — 10 cases live, passed first run, sweep clean. Two of them exist
because a mock cannot answer them: **validation against this project's real field set** (a stub
always agrees with the test that wrote it), and **a conflict against a stamp the server moved
itself**. The second one asserts the current-stamp write proceeds *first*, so the stale-write refusal
cannot pass for the trivial reason that the guard refuses everything.

✅ **`RecordControllerTest`** — 19 cases over the HTTP layer (`@WebMvcTest`), which the contract
suite does not reach: it proves the service is right against a real ALM but says nothing about what a
browser receives. The mapping below is where this API is easiest to get quietly wrong, so it is
tested rather than commented.

**HTTP status mapping, and the one that needs care:** committed → 201/200; validator refusal → 422
with every problem; ALM refusal → 400; conflict → 409; **unresolved `UNKNOWN` → 502**. ⚠️ That 502
describes what the *upstream* did, **not** what happened to the row — the write may well have
committed, and a client treating it as "failed, retry" will create duplicates. The body's
`"outcome": "UNKNOWN"` is the authority.

**Not writable by design:** `runs` (POST fails definitively; the only route is a status PUT on a
test-instance that makes ALM synthesize a `Fast_Run`) and `attachments` (multipart, not a JSON
entity). Both refused as endpoints rather than offered and failed.

✅ **The SPA's write layer is in — client and outcome logic, tested (2026-08-19).** No form yet;
see Next below.

- **`client.ts` write half** — returns a **discriminated union**, not a promise that resolves or
  throws. ⚠️ The reason is one branch ordering: the BFF serves an unresolved `UNKNOWN` as **502**,
  and a 502 falling through to the read path's error handling becomes `retryable: true` — an
  invitation to re-send a write that may already have landed. **Whenever the body carries a write
  outcome, the body is the authority and the status is not.** There is no `ok` boolean, for the same
  reason `AlmWriteResult` has no `isSuccess()`.
- ⚠️ **A dropped connection on a write is `retryable: false`**, unlike every read. The request may
  have reached the server and committed before the socket died, so "the connection failed" still
  means *go and look*.
- **`writeOutcome.ts`** — the outcome→message mapping, kept pure so what it *offers* can be
  asserted, the way `richText.ts` is.

⚠️ **The design decision worth not re-litigating: an `unknown` outcome NEVER offers "Retry".**
The friendly, obvious design — red banner, Retry button — manufactures duplicate records for exactly
the writes that succeeded. Every other failure in this app is safe to retry; this one is not, and it
looks like the others. `unknown` gets its own tone (neither success nor error), language that does
not claim to know, and one action: reload. The editor also **closes** rather than staying open with
the text intact, because a live Save button over an unknown write is the same trap wearing a hat.
`writeOutcome.test.ts` asserts that absence directly — an absence is not something a reviewer
notices.

✅ **`RecordEditor` is in and wired into `DetailPane`** (2026-08-19) — an Edit button on the record
header, gated on `data.writable`, swapping the Details field table for the same layout in inputs so
the row does not jump. Two rules it follows that a generic form would not:

- **Only CHANGED fields are sent.** ALM's update is partial by field but total by value, so posting
  the whole form back would rewrite every field with whatever the browser was showing — for a memo,
  replacing a document the user never opened.
- **Memo fields are not offered.** They are HTML documents and this is a plain-text form; the BFF's
  validator would refuse the write (correctly), but the better answer is not to offer it. Rich-text
  authoring is its own slice.

The editor **remounts per record** and clears on navigation, so a draft can never outlive the row it
was typed against and be saved onto a different one.

⚠️ **`GridDto.Column` gained `writable`, derived from `virtual` ALONE.** `required` and `editable`
are deliberately **withheld from the SPA contract**: probe 9's field is reported as neither and ALM
demands it on create, so a form trusting them would grey out the one field the server insists on.
Withholding is cheaper than documenting in every consumer why they must be ignored — and
`GridServiceTest.writableIsVirtualOnly` pins both directions.

✅ **Component tests are in** (`@testing-library/react`, added 2026-08-19) — and they earned their
keep on the first run.

⚠️ **They immediately found the exact bug the design exists to prevent.** `writeOutcome.ts` says
`mayKeepEditing(unknown) === false`, and the banner correctly offered only "Reload" — but
`RecordEditor` never *acted* on that, so **the form's own Save button sat live underneath the
banner**. A user could press Save again on a write that may already have committed. Suppressing one
route to a second write while leaving another open is the same duplicate reached by a different
button.

The fix locks the form once an unrepeatable outcome arrives: inputs disabled, Cancel becomes Close,
and Save is **removed rather than greyed out** — a disabled Save still reads as "the thing to press
once this clears", and nothing will clear it except re-reading the record.

**The lesson worth keeping:** a pure module can encode a safety rule perfectly and the component can
still not obey it. Testing only the pure layer proves the rule is *stated*, never that it is
*followed*.

✅ **Lookup lists are read, cached, validated and rendered as dropdowns** (2026-08-20) — closing
the validator's largest deliberate omission.

- **`GET customization/used-lists` returns every list WITH its items inline** — one request for all
  of them. Verified live: **39 lists, 125 items, 3 of them empty**, matching the captured fixture
  exactly. No per-list fetch, no N+1, and the natural cache unit is the whole set.
- ⚠️ **Mixed casing inside one object**: the list carries PascalCase `Name`/`Id`/`Items`, each item
  inside carries lowerCamel `value`/`logicalName`. `AlmListParser` is tested against the real
  fixture rather than hand-written JSON, because hand-written JSON is exactly what would quietly
  normalise that and pass while failing on the server.
- Cached on **`AlmMetadataCatalog`**, not `AlmMetadataCache` — lists are *project* scoped and that
  cache is keyed by entity. Single-flight, cleared by the same `invalidate` lever.

⚠️ **The rule that governs this whole feature: when the evidence is absent, let ALM decide.** Four
paths validate nothing and render a text box rather than a dropdown — lists unreadable, list unknown
to the project, list defined **with no items** (3 of 39 are), and `listId == 0` (bound to nothing).
A wrong rejection here makes a field *unfillable* while blaming the user's input, so every
"cannot tell" softens rather than refuses. The BFF validator and the SPA apply the identical rule.

⚠️ **A stored value the list no longer offers is KEPT**, shown as `(not in list)`. Without that, a
list edited after the record was written would silently re-point the dropdown at its first option
and save that value on the next Save — a value the user never chose.

### ⚠️ "A field with choices" is THREE unrelated mechanisms, not one (2026-08-20)

Raised by the user, verified against fixtures and the live server. The lookup slice above covers
**only the first**:

| Mechanism | How it resolves | Example | Status |
|---|---|---|---|
| `LookupList` + `listId` | `customization/used-lists` | `status` → list 309 | ✅ done |
| `Reference` **with** `fieldRelationReferences` | query the referenced **entity collection**; the stored value is an **id** | `target-rel` → `release` via `requirementToReleaseConnection`; `target-rcyc` → `release-cycle` | ✅ done |
| `Reference` with an **empty** `references` array | `customization/entities/{e}/types` | `type-id` (Requirement Type) | ✅ done |

- **56 of 58** `LookupList` fields across the model carry a `listId`, so mechanism 1 genuinely
  covers most of them. Requirement is **27 of 27**.
- **Multi-value is exactly two fields in the entire model**, and both are mechanism 2:
  `requirement.target-rel` and `.target-rcyc`.
- ⚠️ `req-type` ("Old Type (obsolete)") is a **LookupList** and is unrelated to `type-id`
  ("Requirement Type"), which is a **Reference**. Similar names, different mechanisms.
- ⚠️ **`fieldRelationReferences` is not exposed by the BFF at all** — `FieldDescriptor` drops it — so
  the SPA currently cannot tell what a Reference points at. That is the first thing mechanism 2
  needs.

✅ **All three now resolve, through ONE endpoint** (2026-08-20): `GET /api/choices/{collection}`
returns `{field: [{value,label}]}` for every field that offers anything. Verified live —
**27 fields**, with `status` → 7 (value==label, a literal), `type-id` → 8 (`value:'0'`,
`label:'Undefined'` — **id stored, name shown**), `req-type` → 2.

- **`FieldDescriptor.choiceSource()`** is the single decision point: `LIST` / `ENTITY` / `SUBTYPE` /
  `NONE`. ⚠️ **The UI branches on this and never on the field type** — `type-id` and `target-rel`
  are both `REFERENCE` and resolve completely differently, so the type alone cannot tell them apart.
- **Collection-level, not per-field.** A requirement has 27 lookup fields plus 3 references; a
  per-field route would cost 30 requests to open one editor. Lists and subtypes are cached; entity
  reads are memoized per request, so two fields on one target cost one query.
- **Reference dropdowns are capped at 200** — deliberately far below the server's 2,000. A select
  with 2,000 options is not a control; hitting the cap means "build a search field", not "raise the
  number".
- ⚠️ **The ENTITY route could not be verified in the sandbox**, which has **0 releases** — and "no
  rows" is indistinguishable from "my query is broken". Verified instead against an enrolled project
  that has releases: 3 choices, numeric-id values, non-empty labels. Counts and shapes only; no
  third-party data was read into the repo or logs.

⚠️ **The fallback differs by MECHANISM, and one rule for all three was wrong** — caught by the
component tests after I applied a single rule. An unresolved **LOOKUP** degrades to a **text box**
(its value is a literal string, so "let ALM decide" applies). An unresolved **REFERENCE** gets **no
control at all** (its value is an **id**, and a text box pre-filled with one invites typing a number
that silently re-points the record). Not constraining is right for a string and a trap for an
identifier.

**Still not editable:** the two multi-value fields (`target-rel`, `target-rcyc`). A multi-select is a
different control, and faking one with a single-value dropdown would silently drop the other values
on save.

**Earlier fix, superseded:** `RecordEditor` had been rendering single-value References as **text
inputs pre-filled with a raw id**. For `type-id` that invites someone to type a number that silently
re-types the requirement. References are now excluded like memos — offering no control is honest;
offering a text box over an id is a trap.

⚠️ **A documentation bug that cost a wrong conclusion:** `alm-api-reference.md` recorded the wire key
as `List-Id`; the server sends lowerCamel **`listId`**. An analysis keyed on the documented spelling
reports **0 of 58** fields as list-bound — "dropdowns are impossible here" — when the answer is 56.
Corrected in the api reference and both skills. The doc's `[docs-research]` tag was the tell: that
line was never probe-verified.

**Next in P2:** ~~edit forms~~, ~~the comment box~~, ~~a lookup-list client~~, ~~create and delete
affordances~~ — all landed. **CRUD is complete in the SPA and verified live end to end (probe 32).**
~~multi-value editing~~ — done, see below. Remaining: **attachments**, which need the hand-built
multipart body and are their own slice rather than a field on an existing one.

### Multi-value fields are editable (2026-08-20) — after the probe that unblocked them

They had been excluded from the editor with a note saying a multi-select was "a different control".
That was only half the reason. The real blocker was that **nobody knew how a multi-value write was
spelled** — the read shape was known, a read shape is not a write shape, and guessing one is the
invention CLAUDE.md forbids. Probe 33 settled it, and the control followed in an afternoon.

**One `values` entry per value.** A semicolon-joined string *also* works — ALM splits it, so the two
are indistinguishable in outcome — and Alt-ALM sends the repeated-entry form deliberately: structure
carrying the structure, nothing that breaks if a value ever contains a separator. Comma-joined is
refused outright. An empty array clears the field.

⚠️ **`supportsMultivalue` is the ONE metadata flag `AlmWriteValidator` enforces.** That is a
deliberate exception to its own rule — `required` and `editable` are ignored because probe 9 showed
they do not describe what a write needs — and it is earned two ways: probe 33 checked this flag
against behaviour, and *not* enforcing it has no clean failure mode. ALM does not refuse a second
value on a single-value field; it stores something, and which value survives is not a question to
answer by experiment in production.

**Both halves now model a field as a list**, not as a string with a multi-value special case:
`AlmEntityBody` keeps `Map<String, List<String>>`, and the SPA's draft is `Record<string, string[]>`.
ALM's own model is a list on every field — `values` is an array throughout — and making single-value
the "real" shape is how a second, string-joined code path gets added later. ⚠️ The **wire** stays a
plain string for ordinary fields: sending one-element arrays everywhere would work, but would make
the API contract say "arrays everywhere" when the truth is "two fields".

⚠️ `RecordService.valuesLanded` now compares **every** value when verifying an UNKNOWN write. A
multi-value write that landed only its first value would otherwise verify as successful — precisely
the corrupted outcome probe 33 was run to rule out.

Verified end to end through the running BFF (`scripts/probe/probe-multivalue-e2e.py`): both values
stored in order, a second value on a single-value field refused **422** by the validator before
anything left the BFF, clearing works, and a numeric array element is refused **400** rather than
coerced — `toString()` would turn `1005.0` into a value ALM rejects with a message about the field
rather than about the JSON.

### Create and delete are in — and each refuses one thing on purpose (2026-08-20)

**Delete** (`spa/src/detail/DeleteRecord.tsx`). ⚠️ I wrote the obvious warning first — *"N records
are filed under this one"* — and **it would have said 0, always**: ALM's `children-count` reads 0 for
every node on this version (probe 19), which is why `TreeService` already establishes `hasChildren`
with a second query. A warning that cannot fire is worse than none, because it reads as *"Alt-ALM
looked and there is nothing underneath"*. Counting properly is not one query either — what is filed
under a record can live in a **different collection** (tests under a test folder, the orphan probe 8
actually created). So the warning is **unconditional**, says the rule reaches across modules, and
ends with the clause that stops it being a reassurance: **Alt-ALM has not checked.**

**Create** (`spa/src/detail/RecordCreator.tsx`). ⚠️ It **refuses to create at a tree root**, and the
refusal is the design: `parent-id` `-1` is a sentinel, not a row (probe 27), and since a 5xx write is
reported as `UNKNOWN`, defaulting to it would convert a knowable refusal into "we cannot tell whether
a record was created" — for one that certainly was not. `parent-id` comes from the current scope, not
from an input: it is an id, and a text box holding one invites filing under a typed number. Nothing
is enforced as required (probe 9), and the footer says so rather than leaving it looking like an
oversight.

**The three-mechanism control rule now lives in `spa/src/detail/fieldRules.ts`**, shared by both
forms. It was already wrong once — one fallback applied to all three mechanisms — and a second copy
would drift. The editor was moved onto it with **no behaviour change**, evidenced by its 19 tests
being unmodified and still green.

**`App` gained `listReloadToken`**, threaded to `DataGrid` and `TreeGrid`. ⚠️ The tree **discards its
cached children** rather than only re-reading the root: a deleted node lives in some parent's cached
child list, and a tree that kept it would draw a row that is gone and open a 404 on click.

### The comment box is in — and it is write-only on purpose (2026-08-20)

`spa/src/detail/CommentBox.tsx`, under the comment field's own memo tab, gated on `data.writable`.
**101 SPA tests** (was 76); BFF unchanged, since the route and its live contract coverage already
existed.

**The shape is the whole design.** The box holds the *new* comment and never the thread. The
existing comments stay above it, read-only, rendered by `MemoBody`. That is not a layout preference:
a memo PUT replaces the field (probe 30), so a textarea pre-filled with the current comments is one
careless save away from deleting every one of them and getting HTTP 200 for it. The merge stays in
the BFF (`AlmCommentWriter`), so this component never sees the field's current value at all.

⚠️ **Which field takes comments is discovered, never assumed** — `GET .../comment-field`, per entity
and not tracking the physical column. A 404 means the entity has none, which is a legitimate answer:
the pane degrades to a read-only memo. Guessing would offer the box over a *description*.

⚠️ **`mayWriteAgain` joins `mayKeepEditing` rather than replacing it**, and the test asserting they
*disagree* is the one that stops a later merge. `mayKeepEditing` asks whether the user's draft
survives — false for `COMMITTED`, so an editor closes. `mayWriteAgain` asks whether a write button
may be on screen — true for `COMMITTED`, because a comment box is used again immediately. They also
differ on `CONFLICT`. Collapsing them into one predicate silently breaks one caller in whichever
direction the merge goes: a box that vanishes after every successful comment, or a Save button that
outlives a write nobody is sure about.

Only `unknown` locks the box, and its banner carries an extra sentence the generic wording cannot:
the generic text warns about **a duplicate record**, but a comment is a read-modify-write over one
memo field, so what is uncertain is **the state of the entire thread**. A user told "you might get a
duplicate" would reasonably conclude the worst case is tidy.

**The author name is a claim, and is labelled as one.** Every write leaves under one service-account
key (ADR 0004) and REST bypasses the workflow scripts that would stamp a name, so the typed name goes
into the comment's *text* and nowhere ALM treats as an identity. Blank sends `null`, and the BFF
substitutes `Alt-ALM` rather than borrowing the service account's name. Remembered in `localStorage`
only on a committed write.

### ⚠️ "Reload the record" was a no-op, and that is P1 code the comment box exposed

`reloadToken` drove the related-tab marks but was **not in the record fetch's dependencies**. So the
single action offered after an `unknown` write outcome — the one the whole banner design funnels the
user into — re-read nothing. The pane went on displaying pre-write values underneath a banner telling
the user to go and look at what ALM actually stored. Nothing on screen said otherwise.

Fixed, along with the history fetch (a write adds an audit entry). A same-record reload now **keeps
the values on screen** rather than flashing the skeleton: the pane is *out of date*, not empty, and
blanking it discards the banner that asked for the reload. Moving to a **different** record still
shows the skeleton — one record's fields under another's header is the worse lie.

**`DetailPane` gets its first tests** (`DetailPane.test.tsx`), and both regressions were verified to
fail them before the fixes were restored. They cover the three things composition gets wrong
invisibly: reload really re-reads, the box appears on the comment field and no other memo, and memo
values reach the sanitiser — the last closing a gap named twice in earlier sessions.

### P2's phase-start probe is done, and it found a data-loss trap

**⚠️ A memo PUT REPLACES the field.** Probe 30 wrote two comments to a sandbox requirement; the
second destroyed the first, HTTP 200, no warning. So the obvious "add a comment" box **deletes the
record's entire comment history**, including comments other people wrote in the stock client. It is
one line of plausible code away and it is the most destructive thing P2 could ship.

- Comment writes are **read-modify-write, in the BFF** (one enforcement point), never in the SPA.
- That inherits **lost updates** — two commenters, last writer wins, silently. `ver-stamp` increments
  on write; whether it works as an optimistic-concurrency token is **UNVERIFIED** and must be settled
  before the comment UX ships.
- ⚠️ The field name differs per entity *and does not track the physical name*: requirement `comments`
  = `RQ_DEV_COMMENTS`; defect/test `dev-comments`; run `comments` = `RN_COMMENTS`. Discover it.
- The server adds **nothing** — no banner, no user, no timestamp. The convention is entirely ours.
- ⚠️ The stock client's banner format is **UNVERIFIED and unprobeable right now**: it needs one
  comment written through ALM's own UI, and the projects that had them are gone. Keep the format in
  **one function**.

**Also rescoped by probe 29:** bulk update (#106) has no endpoint behind it — it is a client-side
loop over single-entity writes, with no transaction and per-row outcomes that P2 must report itself.
Do not expose a BFF batch endpoint implying atomicity it cannot deliver.

### `EntityStatus`, answered: it is unreachable

**The answer is "that state cannot happen here", and that is a real answer.**
`scripts/probe/probe-entity-status.py` + `probe-entity-status-bulk.py` threw ~25 deliberately broken
reads at the server — non-existent fields, non-existent ids, virtual and inactive fields, per-subtype
fields across all 8 requirement types, forbidden collections, degenerate paging — plus single and
multi-entity writes in **both** media types.

**Every failure is reported at the REQUEST level**, as a `QCRestException` (`Id`/`Title`/
`ExceptionProperties`) with no `entities` envelope at all. Not one row ever carried a status other
than `"Success"`; not one row ever omitted the key.

⚠️ **There is no bulk write on this deployment** — the one operation that would *need* a per-row
status. A multi-entity JSON body is parsed as **one** entity and 500s on the missing top-level
`Fields`; the XML `<Entities>` wrapper is refused 400 while the *same builder's* single `<Entity>`
commits 201. That sanity write is what makes the 400 a statement about the wrapper and not about our
XML.

`EntityStatus` is a property of an entity **representation** — a JSON member on reads, an XML
attribute on writes (`<Entity EntityStatus="Success" ErrorMessage="" …>`, captured at
`tests/fixtures/entities/entity-write-single.xml`).

**Nothing behavioural changed.** Both defaults are kept and now documented as deliberate opposites:
an unknown *value* is evidence of something (`isError()` flags it), an absent *key* is evidence of
nothing (`AlmEntityParser` defaults to `Success`, so a page stays renderable). `DetailPane`'s
`row.error` is knowingly **dead UI** on this deployment — kept for the asymmetric cost, not to be
extended.

⚠️ **One instance, one version.** Re-verify on-prem before trusting it; that is precisely why
`isError()` was documented rather than deleted.

**A real bug fell out of it:** the test named `missingEntityStatusDefaultsToSuccess` read
`entity-page-multi-row.json`, a fixture that **carries** `EntityStatus`. The default it claimed to
pin had never once been executed. Now `absentEntityStatusDefaultsToSuccess`, built inline. 226 BFF
tests.

**What P2 inherits:** the write hazards are already built and unit-tested but wired to nothing —
`AlmEntityBody` (deterministic field order), `AlmWriteOutcome` (5xx → UNKNOWN, never REJECTED),
`AlmWriteRetry` (the single missing-required-field retry). Probe 29 adds one constraint to that list:
**writes are single-entity only**, so P2 must not design a batch API on the assumption a bulk
endpoint exists to back it.

**⚠️ The dependency-ordered list that used to sit here is gone because every item on it is done**
(tab strip 0c, per-type fields 0a, the module rail 0, group-by 1). It also repeated gap 0a's wrong
"per-type field sets genuinely differ" claim, which probe 25 has since measured. The list below is
what is actually left.

### Known residual issues (2026-08-18) — small, real, and none of them blocking

1. **A defect's "Defect Links" tab shows a table captioned "Defect to Defect_link".** That is ALM's
   own relation name and it reads as machinery. It is the *undiscriminated superset* relation sitting
   beside the nine typed slices. **Deliberately kept**: the nine cover eight entity types, so the
   superset can legitimately hold link rows of a type no slice covers, and dropping it to tidy the
   caption would silently hide those. Fixing the caption is cosmetic; dropping the table is not.
2. **Attachments render as an ordinary grid — there is no download.** `TabDto.Tab.attachment` is set
   and shipped, and nothing consumes it. The columns are right (Name / Size / Modified) but the rows
   are inert. Attachment *content* is a separate REST read this build has never made.
3. **The pinned rail squeezes the detail pane.** At the default 460px width, pinning leaves ~270px
   for the field table. The splitter already fixes it per-user; the rail could instead widen the pane
   by its own width when pinned.
4. **`defect`'s `customization/entities/defect/types` returns HTTP 500**, reproducibly. Harmless
   today only because defects carry no `type-id`, so nothing asks. If a future ALM adds one, the
   per-type read starts firing a failing request per defect opened — a failed metadata load is
   deliberately not cached (ADR 0005).
5. **`test-sets` and `runs` returned an `Audits` envelope with no `Audit` node** on every record
   sampled. Read as "no recorded changes" rather than "endpoint absent", but not distinguished from
   it — the History tab shows the same empty state for both.

### What P1 does NOT have, and is not pretending to

- **No write path.** Unchanged and still enforced in four places — see the section below, which is
  current.
- **No attachment fetching.** This is the visible edge of rich text: a memo's images are stored in
  ALM, the browser cannot fetch them (different origin, no session cookie, no proxy), so they render
  as labelled placeholders and the pane says how many. Inline `data:` images do render.
- **No memo editing.** Rich text renders; it is not authored here. That is P2.
- **No Test Lab drill-down.** A test set does not open its instances; instances and runs are
  reachable only by following a link or the Test Runs rail entry.
- **Only Requirements has been exercised end to end.** Test Plan, Test Lab, Test Runs and Defects
  render from the same metadata-driven code and are believed to work; they have not been walked
  through the way Requirements has.

## P1 status — 2026-08-14 (read this before touching the UI or claiming a feature works)

### ⚠️ NO WRITE PATH EXISTS. Records cannot be created or edited.

User asked directly, 2026-08-14. The answer is no, and it is enforced in four independent places,
none of which should be relaxed casually:

1. `AlmEntityClient` has **no write method** — there is nothing to call.
2. The `api` package has **no non-GET mapping**, and `ApiIsReadOnlyTest` **fails the build** if one
   appears. When P2 legitimately adds writes, that test is *changed deliberately* to assert they
   route through the write-safety component — **not deleted**. The failure is the prompt for that
   conversation.
3. `AlmAccessPolicy.checkWrite` refuses every project but the designated sandbox, with no override
   parameter, no env var, and no test bypass.
4. The 8 borrowed projects are read-only by the user's grant anyway.

The write hazards P2 must honour are already built and unit-tested (`AlmEntityBody` deterministic
field order, `AlmWriteOutcome` 5xx→UNKNOWN, `AlmWriteRetry` missing-field retry) — they are simply
not wired to anything.

### What P1 has, working live

- **Grid** — metadata-driven columns (76 for a requirement), sort, name filter, folder scope, paging.
- **Tree** — root discovery via the corrected rule, lazy expansion, drill-down.
- **Detail pane** — one record, showing only **populated** fields (23 of 76 on a real record).
- **Group-by** — endpoint returns real counts with drill-in `expression`. ✅ **UI landed 2026-08-18.**
- **Column picker, view toggle (Tree|Grid), resizable detail pane** — all persisted to
  `localStorage`, per project+collection for columns.

**162 tests green** (`./mvnw test`), plus 7 live contract tests with `-Pcontract`.

### UI decisions worth not re-litigating

- **No total row count anywhere.** ALM's `TotalResults` describes the *page*, not the collection
  (probe 15: reports 0 for a populated collection at `page-size=0`). The DTO deliberately has no
  `total` field; the UI shows "rows returned" and a "more results may exist" flag.
- **Grid defaults to ~8 columns, not all of them.** 76 columns is unreadable and mostly empty.
  `defaultColumns()` prefers the fields people scan (`id, name, status, owner…`) and tops up in
  metadata order.
- ~~**Tree nodes always claim to be expandable**~~ **SUPERSEDED 2026-08-14 (probe 20).** ALM's
  `children-count` is still uselessly 0, but `hasChildren` no longer depends on it: the server asks
  which of a level's ids appear as a `parent-id` one level down, batched into one query by
  `{parent-id[a OR b …]}`. Verified over the whole 232-node tree — **6 levels, zero mismatches**.
  It degrades to the old optimistic answer only when a page fills to the 2,000 cap, and says so via
  `Children.exact`.
- **One request per tree LEVEL, not per node.** `GET /api/tree/{collection}/children` takes a
  repeated `parentId`. The client also prefetches one level ahead, so expanding is usually instant.
  Chunked at 120 ids per query (Q48: no probed ceiling on query length).
- **The tree IS a grid.** ALM's Requirements module is one table whose first column indents and
  expands, with Req ID / Direct Cover Status / Initiator / Modified beside it — not a tree *or* a
  grid. `TreeGrid.tsx` reproduces that; `GET /api/tree/{collection}/rows` returns hierarchy and full
  field values together. The old `FolderTree` is deleted.
- **Default columns are ALM's own set, matched by FIELD NAME not label.** Checked against live
  metadata: "Direct Cover Status" is the field `status` (nothing is named like the label), and
  "Initiator" is `owner`, which another tenant project labels **"Author"**. Labels are per-project
  customization (ADR 0005), so pinning a label would show the wrong header on the next project.
- **Columns render in the CHOSEN order, not metadata order.** Metadata order produced
  "Author, Direct Cover Status, Modified, Req ID" — alphabetical-ish and not what anyone reads.
- **Theme is three states**: light, dark, and system. `system` stamps no attribute and lets
  `prefers-color-scheme` decide; the explicit choices set `data-theme` and must override the OS in
  *both* directions, which is why the dark palette is defined under two selectors. Verified in all
  three combinations, not just the easy one.
- **A tree click selects, it does not navigate.** Clicking a node shows it in the detail pane and
  opens it; it never swaps the main pane to the grid. Double-click (or the crumb) scopes the grid to
  that folder. Clicking a row never *collapses* it — closing is the twisty's job.
- **Memo fields render as truncated plain text, never HTML.** No `dangerouslySetInnerHTML` anywhere;
  sanitisation is a later phase and the data belongs to other teams.
- **One CSS token vocabulary, defined in `index.css`.** An earlier pass had two — `--border`/`--surface`
  (defined) and `--color-border`/`--color-focus` (never defined, silently falling back to inline
  literals), so the app rendered two palettes at once and unfallbacked rules rendered unstyled. If a
  component needs a colour that is not a token, the token is missing, not the component. A scan
  asserting every `var(--x)` resolves is cheap: 36 defined, 36 referenced.
- **Icons are authored SVG** (`spa/src/shell/icons.tsx`), one 16-unit geometry at 1.5 stroke. The
  first pass used Unicode glyphs (`▸ ▾ ▲ ▼`), whose weight and availability vary per platform.

### Known gaps / next steps

0. ✅ **DONE 2026-08-18. The module left rail** — added to P1 scope 2026-08-17 (user request); see
   `implementation-plan.md` "Added to P1 scope — the module left rail". Reproduces ALM's grouped
   nav (My Homepage / Dashboard / Management / Requirements / Testing / Defects). ⚠️ **Most of it is
   not backed**: Libraries and the Dashboard views are **not reachable over REST at all** (OTA-only,
   probe 12), so the rail must render capability state rather than dead links — an item that is
   unbuilt and an item that needs the P6 sidecar are different things and must look different.
0a. ✅ **DONE 2026-08-18** (the `types/{subtypeId}/fields` half — ⚠️ and probe 25 found the claim
   below overstated: 70–72 fields against 74, not 13–20, with zero flag differences).
   **What ALM renders IS partly discoverable — probe 21 (2026-08-17).** Three results worth not
   re-deriving:
   - **`visible` is worthless** — true for every field of every entity in all 9 projects. The real
     discriminators are **`active`** and **`visibleInWebUI`**, which `FieldDescriptor` now carries.
     `active ∧ visibleInWebUI` ≈ the Details form (16/17 on a real record — an **approximation**,
     see below). `active ∧ ¬visibleInWebUI` = **exactly 25 fields in all 9 projects** = the Risk
     Analysis tab.
   - **Memo tabs are a filtered subset**: `MEMO ∧ active ∧ visibleInWebUI` gives exactly
     Description / Comments / Rich Text, and custom MLT fields join them. Not all 9 memo fields.
   - ⚠️ **`customization/entities/{e}/relations/` enumerates the related-entity tabs** — each
     relation carries a `Label` that IS the stock tab name ("Linked Defects", "Test Coverage",
     "Business Models Linkage", "Traced From/To Requirements", "Requirement Attachments") plus its
     target collection. **This retracts an in-session conclusion of mine that they were not
     enumerable** — I inferred it from the absence of a per-instance `resource-list` without trying
     the documented endpoint. Third overturned negative in this project.
   - ⚠️ **The form layout itself is genuinely unreachable.** ALM keeps it in *workflow scripts*
     (`PageNo`/`ViewOrder`), which REST does not serve and which OTA's `ICustomizationField4` has no
     property for either. Also: **per-user-group data hiding is invisible to REST**, so any form we
     build over-shows fields for restricted groups. Sources in probe 21.8.
   - ~~**Use `types/{subtypeId}/fields`, not the entity-level `fields`**, for a typed record — the
     per-type sets genuinely differ (13–20 non-memo by type). Not yet implemented.~~
     ⚠️ **CORRECTED and IMPLEMENTED 2026-08-18 (probe 25).** The endpoint is right and is now used,
     but "13–20 non-memo by type" was wrong: the real difference is **2–4 fields out of 74**, with
     **zero** flag differences, moving the Details form by **exactly one field** (`status`, on
     Folder/Group/Business). A subtype only omits. Also: `test`/`test-set`/`run` have **no subtypes**
     and `defect`'s types endpoint **500s**, so the read is gated on the record having a `type-id`.
0b. **One tab per Memo field in the detail pane** — added to P1 scope 2026-08-17 (user request).
   ALM's `Description` / `Comments` / `Rich Text` / `Draft-Rejection Reason` / `RTM Addl Info` tabs
   are not a fixed list; they are that project's **memo fields, one per tab**. Alt-ALM currently
   stacks them all under a single "Description" tab. Metadata-driven, labels from the project, empty
   ones shown-but-marked. Still plain text — real rich-text rendering stays P5.
0f. ✅ **Cross-record navigation + ALM module names** (user request 2026-08-17).
   - A related row now shows the **linked record's own id**, and clicking it opens that record in
     its module, revealed in the tree at its place in the hierarchy — `GET
     /api/tree/{collection}/path/{id}` walks `parent-id` upward (ALM has no "ancestors of" query)
     and `TreeGrid` expands and scrolls to it.
   - ⚠️ **Only the association form of a relation can be followed.** A `ReferenceStorage` relation
     names one column — the one pointing back at the open record — and says nothing about the far
     end, so `requirementToDefectLinkLink` can list link rows but cannot say which defect each
     reaches. `TabService.primary()` therefore prefers a **navigable** relation over a merely
     readable one; that preference is the difference between a tab you can read and one you can
     navigate from.
   - **Module names are ALM's**: Requirements · **Test Plan** · **Test Lab** · Defects. ⚠️ Test
     instances and runs are **deliberately not modules** — in ALM a test set lives in Test Lab, an
     instance inside a test set, a run inside an instance. They remain valid *navigation targets* so
     links can open them, and fold into Test Lab when it gets its drill-down.
0e. 🔴 **BUG, OPEN — switching project from the dropdown leaves the tree empty** (found 2026-08-17).
   The tree loads its root and then no children; the grid path is unaffected. **Not** a server bug:
   `/api/tree/{c}/rows` returns 4 nodes for the same parent when called directly.
   - ✅ **Loading the same project from the URL works** — 5 rows, look-ahead prefetch running. So
     the fault is specifically the *switch*, not the project or the tree.
   - Evidence: after a switch only `tree/roots` is requested; no `tree/{c}/rows` follows. `TreeGrid`
     does reset `fetchedRef`/`children`/`expanded` when `project` changes, so the reset is not
     missing — the likely cause is the previous project's **in-flight** `fetchTreeRows` resolving
     *after* that reset and re-populating `fetchedRef` through its look-ahead prefetch, which then
     makes `loadLevel` think the new root is already fetched.
   - ⚠️ It was **latent before today** — the screenshot harness used to switch projects before the
     first tree had loaded, so the race never ran. Adding URL state slowed first paint enough to
     expose it. Do not treat it as caused by the URL work.
   - Fix direction: give the root-load effect a request generation/epoch and ignore any response
     from a superseded one, the same cancellation discipline the other panes already use.
0c. ✅ **The entity detail tab strip — BUILT AND RENDERING 2026-08-17** (BFF + SPA; 193 tests).
   Verified in the browser against a borrowed project: 6 related tabs beside the field-backed ones,
   Test Coverage showing real rows, Linked Defects showing its empty state, both themes, no console
   errors. ⚠️ **Two things to decide, not bugs:**
   - **The strip now wraps to three rows** — 11 tabs for a requirement. That is the documented
     over-showing made visible: "Traced To Requirements" sits next to "Trace", and "Test Coverage"
     next to "Requirement to Tests that cover Requirement". Both pairs are real ALM relations
     reaching the same rows two ways. Merging them is the rule that re-breaks defect (probe 22.5).
   - **History is still missing** — it is the `/audits` sub-resource, not a relation, so `relations`
     never enumerates it. Separate work, and its coverage is known-partial (api-ref §9).
   Details below from when the BFF half landed: `AlmRelation`,
   `AlmRelationParser` (offline, fixture-tested) and `AlmRelationSelector` are in, wired through
   `AlmMetadataClient.fetchRelations` → `AlmMetadataCache.relations` → `AlmMetadataCatalog`.
   **183 tests green.** Probe 22 captured the fixtures and corrected three things worth not
   re-deriving:
   - ⚠️ **Not every relation has a `Label`** — 5 of defect's 17 have none, and all 5 are
     field-backed references. This corrects probe 21.6, which measured `requirement` only.
   - ⚠️ **`TargetEntity` is not the collection to read.** An `AssociationStorage` names its own
     join entity: `requirementToDefectConnection` targets `defect` but its rows are in `defect-link`.
   - ⚠️ **`defect-link` and `assets-relation` are polymorphic** — 9 of defect's relations read
     `defect-link` to 9 different far ends. Group tabs by the **pair** (far end, entity read); the
     obvious "group by what gets read" rule shows linked runs under "Linked to Defects".
   **The reduction over-shows and cannot do better**: requirement gives 8 tabs where ALM shows 5,
   and the rule that would merge the 3 duplicates is the rule that re-breaks defect. Chosen error
   direction, documented on the class. **Still to do: the `/api/tabs` endpoint, the per-tab reads,
   and the SPA rendering.**
   Original scope note, still accurate — ALM's left rail
   inside Requirement Details: Details · Rich Text · Attachments · Linked Defects · Requirement
   Traceability · Test Coverage · Risk Analysis · Business Models Linkage · History. **Enumerate
   from `customization/entities/{e}/relations/`, never hardcode** — probe 21.6 proved the `Label`
   is the tab name and the relation names its backing collection. History is separate (`/audits`,
   coverage known-partial). Field-backed tabs (Details, the memo tabs, Risk Analysis) are already
   done. Read-only in P1; editing links is P2.
0d. **Rich text must render AS rich text** — added to P1/P5 scope 2026-08-17 (user request). Bold,
   italic, underline, bullet/numbered lists, tables, embedded images. Today `DetailPane` flattens
   every memo through `htmlToPlainText`, and the UI now says so on each memo tab rather than
   letting a stripped document look empty.
   ⚠️ **The blocker is security, not effort.** Memo bodies are raw HTML authored by other users of
   the ALM instance, and the server's allowed-tag set is per-deployment. `dangerouslySetInnerHTML`
   over that is stored-XSS by construction — sanitise client-side against **our own** allowlist,
   derived from the formatting we intend to support. Embedded images need proxying through the BFF:
   the probe-verified `<img src>` form is an absolute REST URL, which the browser will fetch
   unauthenticated and get a 401 on, while working fine in curl.
1. ✅ **DONE 2026-08-18. Group-by UI** — a field selector over ALM's own `groupable` flag (21 of a
   requirement's 76 fields), bucket chips carrying real server-side counts, drill-in that re-queries.
   ⚠️ Building it surfaced probe 26: an unquoted multi-word filter value silently returned the whole
   collection.
2. **Cross-filter grammar** (`api-ref §4.2`) — `AlmQuery` has no cross-entity filter support.
3. **Filter UX is one name box** — no per-column filters, no operators (`>`, `NOT`, wildcards).
   `AlmQuery.filterRaw` exists for that but is unused by the API.
4. **`AlmQuery.filter` refuses values containing `; [ ] }`** — ALM documents no escaping rule
   (api-ref §4.1), so it fails loudly rather than mangling. A user searching for `foo;bar` gets a
   400. Correct, but the UI should explain it.
   ⚠️ **Related, and now fixed — but read probe 26 before touching this code.** Whitespace was a
   *different* and far worse case: it did not fail, it silently returned the whole collection,
   because `NOT`/`AND`/`OR` are grammar keywords. `AlmQuery.filter` now quotes any value containing
   whitespace. **Do not add a second filter path that bypasses it**, and note that `filterRaw` (item
   3 above) deliberately does bypass it — anything wired to `filterRaw` from user input inherits the
   original bug.
5. **Tests/Defects/Test Sets/Runs modules** share the grid and detail but are untested beyond
   requirements.
6. **Design pass: first round done 2026-08-14, not finished.** One token system, an authored icon
   set, computed-contrast text (every colour ≥4.5:1 on every surface it lands on, both schemes),
   skeleton loading, real empty states, themed scrollbars/selection/focus. PRODUCT.md / DESIGN.md
   are still **not** written, so there is no recorded visual world to check future work against —
   that is the next design step, not more component polish.
7. ~~**The UI has not been looked at in a browser**~~ **SOLVED 2026-08-14.** `spa/scripts/shot.mjs`
   (Playwright, a devDependency) screenshots the running app in both themes and widths, reports
   console errors / failed requests / 4xx-5xx, and can switch project, expand levels and select a
   row. It found two real bugs immediately: a `setState`-during-render between App and TreeGrid,
   and a Name column that stopped short of its header because `display:flex` on a `<td>` drops the
   cell out of table layout.
   - ⚠️ **`.shots/` is git-ignored and must stay so** — a screenshot of the running app renders a
     borrowed project's real requirement names and owners. The script blanks the project selector
     before capturing, and `--mask` blurs value cells.
   - ⚠️ **Wait for content, never a fixed delay.** The first version slept 2.5s and captured a
     skeleton, which looks exactly like a broken render — it sent me chasing a bug that did not
     exist. It now waits for a row selector.
   - CI sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`, since `npm ci` would otherwise pull a ~115 MB
     browser the build never launches.
8. **`Children.exact=false` is untested against real data.** It needs a folder with >2,000 direct
   children, which no reachable project has. The fallback path is unit-tested only.

### Running it

**PowerShell — one line each, no continuations.** (A trailing `\` is bash; PowerShell passes it to
Maven as a lifecycle phase and the build fails before compiling. Use a backtick if you must wrap.)

```powershell
# Terminal 1 — BFF (needs credentials; tracked config has none so CI can start clean)
cd 'D:\OneDrive - SurgeONE.ai\Documents\GitHub\Alt-ALM\bff'; ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=file:../Secrets/local.properties"

# Terminal 2 — SPA
cd 'D:\OneDrive - SurgeONE.ai\Documents\GitHub\Alt-ALM\spa'; npm run dev     # http://localhost:5173
```

Quote the whole `-D...` argument: PowerShell otherwise splits it at the `=` and Maven never sees the
config location. Use absolute `cd` paths — after the first `cd bff`, a relative `cd spa` fails.

`Secrets/local.properties` is git-ignored and holds the credentials path plus the 8 read-only
project enrolments. Regenerate it with `scripts/probe/probe-projects-2.ps1` if lost.

⚠️ **`spring-boot:run` forks a child JVM.** Killing the Maven process leaves the child holding
:8080, so the next start fails while `/actuator/health` still answers **from the old build**. Kill
the **port holder**, not the parent. This cost a debugging cycle.

**Screenshots**: `node scripts/shot.mjs` in `spa/` — see its header for flags. Useful additions
2026-08-18: `--pin-rail` (the tab rail is 40px of icons in every capture otherwise), and the harness
now waits after a project switch and parks the pointer off the rail before capturing.

⚠️ **`--project-index` is only meaningful because the project list is now deterministically
ordered.** It was not: `AlmAccessPolicy` used `Set.copyOf`, whose iteration order is randomised per
JVM run, so the dropdown reshuffled on every restart and the same index meant a different project
between two runs. That produced one wrong diagnosis — an empty tree read as a bug when the harness
had simply selected a project with an empty Requirements root. Resolve the index at runtime from
`/api/projects` rather than hardcoding it, and **never put a project name in a script**.

---

## ⚠️ Standing lesson — read before writing any "X is impossible"

**Four confident negative verdicts have been overturned in three days**: OTA unreachable (stale
client); no REST path defines a test parameter (wrong `parent-id` + an unprobed sibling collection);
SA session visibility absent (three guessed paths); and six `NO` rows that simply never had OTA's
`Customization` subtree enumerated. **None failed for too few attempts — every one failed on an
unexamined assumption about the shape of the question.** Before recording an impossibility: grep the
per-instance `resource-list` and the on-disk Swagger fixtures for sibling collections, confirm every
id in a body means what you assume, and check whether an error is about *arity* rather than support.

**Probe 15 (2026-08-14) adds the mirror-image failure — a confident *positive*.** The tree-root rule
`?query={parent-id[0]}` was verified on two trees, written down as general, and copied into the
implementation plan, the data model and three skills. It is wrong for four of six trees, and for
`test-set-folders` it returns **`Recycle Bin`** — HTTP 200, exactly one row, structurally identical
to a correct answer. Nothing would have looked broken.

So the lesson generalizes past impossibility claims: **a rule verified on a subset is a rule about
that subset.** Two specific habits fall out. Verify a rule against *every* instance it claims to
cover, not the first two that work — the marginal probe is cheap and this one was six queries. And
distrust discovery queries that can return a plausible wrong answer as loudly as ones that error:
`{parent-id[0]}` failing loudly on three trees is what made it *look* trustworthy on the fourth.
