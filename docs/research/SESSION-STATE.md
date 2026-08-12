# Research Session State — updated 2026-08-12 (probe round 3 complete)

Working state of the Fable 5 research-and-planning session (kickoff:
[docs/prompts/fable-5-research-and-plan.md](../prompts/fable-5-research-and-plan.md)). Written
immediately before a context compact so the continuation loses nothing. **Read this first when
resuming.**

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

## Next actions (in order)

1. **Write-probe round 2 (sandbox writes still authorized)**: attachments + rich-text
   **image embed** round-trip (`ref-subtype=1` multipart, `<img src>` syntax discovery — the
   remaining #1 unknown); the Test Lab chain (test-set-folder → test-set → test-instance →
   run POST w/ `status=Not Completed` then PUT status; design-step auto-copy into run-steps;
   status propagation; Fast_Run behaviour); **step-parameters retry** (create param before
   referencing; try `<<<param>>>` insertion AFTER param exists); milestones POST; one `/mail`
   POST; `test-executions` POST semantics; comments append convention; cycle date validation.
2. **Synthesis**: `docs/research/alm-api-reference.md`, `alm-ui-feature-inventory.md`,
   `alm-data-model.md`, `feasibility-matrix.md` (reconcile agents + probes; probe log wins).
3. **Synthesis**: `docs/research/alm-api-reference.md`, `alm-ui-feature-inventory.md`, `alm-data-model.md`, `feasibility-matrix.md` (reconcile agents + probes; probe log wins conflicts).
4. **Plan set**: `docs/plan/architecture.md` + ADRs (BFF proxy vs direct; session model — note licence finding weakens the seat-consumption concern; **stack comparison Java vs TS vs Python vs .NET, user leans Java**; OTA-fallback isolation strategy given Windows-only COM), `implementation-plan.md`, `data-generator-spec.md`, `test-strategy.md`, `risks-and-open-questions.md`.
5. **Skills** under `.claude/skills/` (alm-api, alm-entity-model, alm-data-gen, alt-alm-ui, alm-live-probe) with verified content.
6. **Update CLAUDE.md** (durable facts: auth flow, 8 field types, query cheat-sheet, gaps list, Swagger discovery, workflow-bypass) and commit.
