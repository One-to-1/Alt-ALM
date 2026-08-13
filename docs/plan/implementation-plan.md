# Alt-ALM — Implementation Plan

Status: Draft, lead-decision-bound. Follows the phasing skeleton (P0–P6) in
[`_lead-decision-brief.md`](_lead-decision-brief.md) exactly — this document elaborates each phase, it
does not restructure the sequence. Feature scoping is sourced from
[`docs/research/feasibility-matrix.md`](../research/feasibility-matrix.md) (cited as `matrix #N`);
write-hazard and gap detail from [`alm-api-reference.md`](../research/alm-api-reference.md) (`api-ref
§N`) and [`alm-data-model.md`](../research/alm-data-model.md) (`data-model §N`); ground truth from
[`live-probe-log.md`](../research/live-probe-log.md) (`probe log`). Constraints carried from
`CLAUDE.md`: documented REST (+ OTA/COM fallback) only; `Secrets/` never read/logged; writes are
sandbox-only behind an explicit allowlist; `UNVERIFIED` claims stay labelled. No dates or estimates —
phases are ordered, not scheduled. Architecture detail lives in [`architecture.md`](architecture.md);
generator field/content detail in [`data-generator-spec.md`](data-generator-spec.md).

Every phase ends with the **sandbox contract-test gate**: writes made against the sandbox during that
phase's contract tests use the `ALTALM-*` name prefix (probe convention, `probe log`), cleanup runs in
a `finally`/equivalent block regardless of test outcome, and a post-suite orphan sweep (query by
prefix, assert zero unexpected survivors) is a required, not optional, CI/manual step before the phase
is considered closed.

---

## P0 — Foundations

**Objective**: stand up the BFF skeleton, session/auth handling, metadata service, and a fixtures-only
test harness — no UI, no live writes.

**In scope**: auth handshake (`oauth2/login` one-step flow, `api-ref §2.1`), XSRF header injection on
every non-GET (`api-ref §2.2`), session keepalive (`PUT/GET site-session`, `api-ref §2.2`), pooled
single-service-account session manager behind an interface (D4), per-project metadata caching layer
(`customization/entities/{entity}/fields`, `api-ref §6.8`) with explicit invalidation (D5), the
write-safety component's skeleton (D7) — ordered serialization and 5xx-verify-by-query are designed
here even though no entity CRUD exists yet.

**Out of scope**: any SPA screen, any entity CRUD, generator, OTA bridge.

**Key technical tasks**:
- Repo scaffolding (Spring Boot BFF, React+TS SPA shell, per D2) and CI pipeline running unit +
  fixture-based tests on every commit (see `test-strategy.md`).
- Session manager: one service-account API key from `Secrets/ALM_API_credentials.json` (path
  reference only, `CLAUDE.md`), pooled sessions, keepalive scheduling, idle-timeout awareness
  (`REST_SESSION_MAX_IDLE_TIME` default 60 min, `api-ref §2.2`).
- Metadata service: fetch + cache field descriptors, list bindings, requirement/test-subtype tables
  at runtime; never hardcode List-Ids or root ids (`data-model §2.1`, `api-ref §6.8`).
- Write-safety component skeleton: deterministic `Fields`-array ordering utility, XSRF header
  injector, 5xx→verify-by-query retry stub (`api-ref §3.2–3.3`) — unit-tested now, wired to real
  writes in P2.
- Fixtures-based harness reading `tests/fixtures/` (already captured, redacted) for
  metadata-parsing/envelope tests with no server needed.

**Dependencies**: none.

**Exit criteria — ALL MET (2026-08-13). P0 is complete.**

- ✅ **BFF authenticates against the sandbox and holds a keepalive session** — `AlmAuthClientContractTest`
  green against the live sandbox (probe 13).
- ✅ **Metadata service returns cached field descriptors for all 15 probe-known entity types**
  (`data-model §1`) — `AlmMetadataCache` (project-scoped, explicit invalidation, single-flight, no TTL)
  over `AlmMetadataClient`. Verified live: **15 entities, 432 fields, all 8 types, no unknown type** —
  the 432 independently reproduces the original probe's count.
- ✅ Fixture test suite green in CI (20 cases over all 15 entities, no server, no credentials).
- ✅ Write-safety unit tests (field-order regression) green.
- ✅ Spring bean wiring via `@ConfigurationProperties` (`AlmProperties` + `AlmConfiguration`);
  the context starts with **no ALM contact** — the pool logs in lazily on first borrow.

**Sandbox contract-test gate**: ✅ **done** — session lifecycle contract test (login →
is-authenticated → project reach → keepalive → pool → teardown). No entity writes, so
`ALTALM-*`/cleanup are N/A — but the suite still runs the orphan sweep as an **assertion**, because
the one deliberately-rejected POST it makes (the XSRF-missing negative case) is only *argued* not to
commit. Tagged `contract`, excluded from CI via Surefire `excludedGroups`, opt in with `-Pcontract`,
skips when `Secrets/` is absent.

---

## P1 — Read-only Alt-ALM

**Objective**: metadata-driven grids/forms (read paths) for requirements, tests, defects; the Core
query builder; paging; tree navigation with runtime root discovery.

**In scope** (`matrix` rows): requirements tree/grid #8, #9, #27, #28, #31; test-plan tree/grid #37,
#38, #58, #59; defect grid/detail-read #97, #99 (read side), #113, #114, #116; cross-cutting grid/find
#154, #156, #158, #159, #161–164, #168–172; test-lab/runs read-only views #79, #84, #94, #95 (grid
reads only — no write flow yet); root discovery per `data-model §2.1` (requirements=0, test-folders=2
"Subject" project-specific, test-set-folders=0 "Root", release-folders UNVERIFIED — see deferred
probes below).

**Out of scope**: any write, rich-text editing (display renders canonicalized HTML only), Test Lab
execution flow, generator.

**Key technical tasks**:
- Core query-string builder: curly-brace grammar, field/condition escaping caveats (no documented
  Core null-test or delimiter-escaping rule — `api-ref §4.1`, flag as risk not silently "handled"),
  cross-filter alias disambiguation (`api-ref §4.2`), multi-field `order-by` (`api-ref §4.3`).
- Paging: `page-size`/`start-index` (1-based); `REST_API_MAX_PAGE_SIZE`=2000 is a **silent cap** on
  Core (`api-ref §4.4`) — grid must detect and surface a "more results than shown" state rather than
  trusting `TotalResults` against a capped page.
- Tree navigation: client-side walk over `parent-id` per entity (no server-side breadcrumb/path
  field — `matrix #27`); runtime root discovery via `?query={parent-id[0]}`, never hardcoded roots
  (D5, `data-model §2.1`).
- Metadata-driven grid/form renderer registry keyed off the 8-type system (D5, `api-ref §8`).
- Group-by view stub: `groups/{field}` (`alm-web` dialect) body shape is UNVERIFIED — client-side
  aggregation fallback first (`matrix #157/#164`); wire server-side only after the deferred probe
  below settles it.

**Dependencies**: P0 (session manager, metadata service).

**Exit criteria**: grids render live for requirements/tests/defects against sandbox metadata; query
builder has unit tests for filter/sort/cross-filter grammar; tree navigation works against sandbox
fixtures and a live smoke read; page-size-cap behavior has a regression test.

**Sandbox contract-test gate**: read-only smoke tests against the sandbox (no writes — `ALTALM-*`
prefix/cleanup/orphan-sweep N/A); verify query grammar returns match expected row sets for known
sandbox content.

**Deferred probe executed at phase start**: `alm-web` dialect body shape (`live-probe-log.md` §Open
items #10) — request `groups/{field}` with `Accept: application/json;schema=alm-web` and diff against
plain JSON before committing to a server-side group-by implementation.

---

## P2 — Write core

**Objective**: the single write-safety client component (D7) fully wired to live writes; CRUD for
requirements/tests/design-steps/defects; coverage, traceability, defect-links; the BFF validation
layer that substitutes for bypassed workflow scripts.

**In scope** (`matrix` rows): requirement create/delete/rename #1–#3; requirement traceability #12;
add-to-coverage #15/#16; **Risk Assessment `rbt-*` field writes and Testing Level/Time computation
#17–#19** (Analyze/Analyze and Apply to Children moved here 2026-08-13 — see below, retracted from
`NO` by `live-probe-log.md` Probe 12); test/design-step create #33, #36, #39; **test-parameter
definition and step-parameter value recording #45** (moved here 2026-08-13 — see below, this was
previously believed REST-unreachable and deferred to OTA/P6, retracted by `live-probe-log.md` Probe 9);
defect create/detail/delete #96, #99, #103; defect-links #122; bulk update #106 (PARTIAL — bulk
endpoint itself unverified until its own contract test passes); basic non-embedded-image attachments
#25, #180–185; filter-state persistence #154–156, #158.

**Out of scope**: Test Lab/runs, releases/cycles/milestones (P3), generator, rich-text editor UI and
embedded images (P5 — memo fields are writable here as plain/pre-canonicalized HTML only, no editor
UX).

**Key technical tasks**:
- Write-safety component, fully wired: fixed deterministic field order (`name` → relational ids →
  type/subtype last, `api-ref §3.2` — wrong order produces opaque NPE-style 500s); 5xx = "verify by
  query, never assume failure" retry discipline (`api-ref §3.3` — one 500 silently committed a row in
  probing); bulk 409 per-item result parsing (`api-ref §4.5`); secret masking in all logs.
- BFF validation layer from runtime metadata (`Required`/`Editable`/`List` bindings) — REST writes
  bypass workflow-script validation by default (`CLIENT_TYPES_BYPASS_REST_WF`, `api-ref §6.8`), so
  the BFF independently enforces what the stock client's scripts would have (D4).
- CRUD endpoints: `POST/PUT/DELETE requirements`, `tests`, `design-steps`, `defects`
  (`api-ref §6.1/§6.4/§6.5`); `requirement-coverages` (auto-creates one `test-config-coverages` row
  per link — never POST that side table directly, `api-ref §6.2`); `req-traces` (`api-ref §6.3`);
  `defect-links` (`second-endpoint-type` confirmed only for `defect`/`requirement` — do not assume
  other target types work, `api-ref §6.5`).
- Attachments: octet-stream+`Slug` upload (`ref-subtype=0` only) and a basic multipart client
  (full `ref-subtype=1` embed flow deferred to P5, but multipart must be integration-tested against
  the real server now per D2's named risk).
- Risk-Based Testing (RBT) matrix (`matrix #17–#19`, `live-probe-log.md` Probe 12 §12.1): Testing
  Level/Time computation ("Analyze"/"Analyze and Apply to Children") is a client-side lookup over the
  already-REST-writable `rbt-*` fields (`api-ref §8`) — `TestingPolicyMatrix`, `RiskCalculationMatrix`,
  `TestingLevelPercentage`, `TestingEffortForFCLevel` are the lookup tables. **The tables themselves
  need a one-time OTA read per project** (`TDConnection.Customization.RBT`) — since they are per-project
  admin config that rarely changes, capture them manually (or via the OTA sidecar once it exists in P6)
  rather than making this feature depend on the sidecar being deployed at runtime. **Never hardcode the
  captured values** — they are project-specific (this sandbox's own `TestingPolicyMatrix` happens to be
  risk-only, ignoring functional complexity, which will not generalize to another project).
- Test-parameter/step-parameter CRUD (`api-ref §6.4`, `live-probe-log.md` Probe 9): `POST
  tests/{testId}/test-parameters` to **define** a parameter (`name` + `ref-count` — `parent-id` comes
  from the URL, read-only in the body); `POST step-parameters` to **record a value**, with
  `parent-id` = the **`test-parameter`'s** id, not the test/design-step id (this was the shape bug
  behind the original "no REST path" finding); `PUT test-parameters/{id}` with `default-value` to set
  a default. **Write hazard**: `ref-count` is metadata `editable:false, required:false` but 500s as
  `"missing required field TP_REF_COUNT"` if omitted on create — always send it (`api-ref §3.6`, a
  second instance of the field-order hazard's class of trap). No OTA dependency for any of this.

**Dependencies**: P1 (metadata, query builder, tree UI to navigate to records).

**Exit criteria**: create/edit/delete flows work end-to-end against the sandbox for requirements,
tests, design-steps, defects; write-safety unit tests cover the field-order regression and a mocked
"500 that committed" case; BFF validation layer rejects a write missing a metadata-Required field
before it reaches ALM.

**Sandbox contract-test gate**: full gate active — every contract-test write uses `ALTALM-*` prefix,
cleanup in `finally`, orphan sweep asserts zero survivors post-suite.

**Deferred probe executed at phase start**: comments-append banner convention (`live-probe-log.md`
§Open items #10) — determine whether Alt-ALM's comment-field writes should append with a
banner/timestamp convention matching the stock client, before the comment-write UX is built.

---

## P3 — Test Lab + planning

**Objective**: test-set tree, test instances, the Fast_Run execution flow, run-steps UI; releases,
cycles, milestones.

**In scope** (`matrix` rows): test-set/folder create #60, #62 (partial); test-instance create #68,
#69, #70; manual-run execution via Fast_Run synthesis #72, #87, #88, #89; run continuation #74, #90;
run-details composite flows #91, #92, #93 (partial); execution grid #79, #85, #94, #95; releases
#140; cycles #141; milestones #142; Master Plan client-side timeline #144.

**Out of scope**: BPT/Business Components (OTA, `matrix #54–57` — P6), automatic runner/lab
infrastructure (`matrix #75`, out of practical REST-only reach), timeslots (`matrix #81` — OTA, P6),
scope items/KPIs (`matrix #143/#145` — NO, absent from REST). *(Test-parameter/step-parameter value
recording, previously listed here as an OTA-gated gap, moved to P2 — retracted by `live-probe-log.md`
Probe 9, see P2.)*

**Key technical tasks**:
- Test-set binding chain: `test-set-folders`→`test-sets`(`subtype-id=hp.qc.test-set.default`)→
  `test-instances` — **legacy naming trap**: `cycle-id` on a test-instance means *test-set* id, not a
  release cycle (`data-model §2.7`). Use an explicit named field mapping in code, not a literal
  `cycle-id` variable, to keep the trap from propagating into the UI/generator.
- Run creation: **direct `POST runs` fails definitively (8/8 probe attempts)** — the only confirmed
  path is `PUT test-instances/{id}.status`, server-synthesizing a `Fast_Run_<...>` run with
  auto-copied run-steps (`data-model §2.9`, `probe log` Probe 6). Run name is server-generated,
  non-overridable — no "name this run" field. Run-steps do not auto-aggregate status to/from the
  parent run (verified absent in one directional case only — not an exhaustive matrix).
- Releases/cycles/milestones CRUD: cycle dates are **server-enforced** inside the parent release's
  window (`api-ref §6.7`) — pre-validate client-side for an inline error instead of a raw 500.
  Milestones parent under a **release** (`MS_RELEASE_ID`), not a folder (`data-model §2.8`).

**Dependencies**: P2 (write-safety component, test/design-step CRUD to have something to instance).

**Exit criteria**: full manual-run flow (create test-set → instance → Fast_Run via status PUT → verify
run-steps auto-copied) succeeds reliably against the sandbox; release/cycle/milestone CRUD works with
client-side date pre-validation; deferred probes below are resolved or the phase explicitly documents
the fallback taken if not.

**Sandbox contract-test gate**: full gate; note that Fast_Run names are server-generated (not
`ALTALM-*`-prefixable directly) — cleanup/orphan-sweep for this phase must additionally sweep runs by
parent test-instance/test-set ancestry, not by name pattern alone.

**Deferred probes executed at phase start**: release-folder root id (`live-probe-log.md` §Open items
#10 — `data-model §2.1` flags this UNVERIFIED; probe `release-folders?query={parent-id[0]}` before any
hardcoded release-folder creation); test-set→release/cycle assignment field (`matrix #61`).

---

## P4 — Generator MVP

**Objective**: the DAG-ordered generator engine (D6) — dry-run by default, allowlist-gated writes,
seedable, with provenance marking and cleanup; user seeding so `UsersList` fields don't degenerate.

**In scope**: the full verified creation-order DAG (`data-model §2.11`, feasibility-matrix
"Generator-impact appendix"): release/cycle/milestone → requirement (+ traces) → test → design-step
(plain text only, no `<<<param>>>` tokens yet — P5) → **test-parameter (Route A direct create) →
step-parameter (value record)** → test-set/instance → Fast_Run → defect + links. Test-parameter/
step-parameter generation moved here (was believed OTA-gated; retracted by `live-probe-log.md` Probe 9
— `data-generator-spec.md` §4/§9); Route A (`POST tests/{testId}/test-parameters`) is preferred over
token-authoring because it is deterministic and does not depend on rich-text/sanitizer machinery,
which is why it belongs in the DAG engine itself rather than waiting on P5. Field-type→strategy matrix
for the 8 types (`api-ref §8`); Required/Editable/System=read-only respect (191 of 432 probed fields
are non-negotiable read-only — `api-ref §8`); the two genuinely multivalue fields
(`requirement.target-rel`/`target-rcyc`) handled per whatever the P3 deferred probe resolved, or left
unimplemented with an explicit `UNVERIFIED` flag if unresolved. User seeding via SA API
(`POST site-users` + `POST .../projects/{p}/users`, Customer Admin role verified — `api-ref §6.9`).

**Out of scope**: rich-text/memo content generation and embedded images (P5); the `<<<param>>>`
entity-encoded step-text token (Route B, cosmetic UI-authenticity marker) is deferred to P5 alongside
the rest of rich-text authoring — but this no longer blocks parameter *data* generation, which uses
Route A above and needs no rich-text infrastructure.

**Key technical tasks**:
- DAG engine executing the creation order above via P2/P3's write-safety-wrapped CRUD; refuses to run
  against any domain/project off the explicit allowlist (D6, `CLAUDE.md`); dry-run is default and
  must produce a deterministic plan issuing no write.
- Seedable PRNG: identical seed → byte-identical dry-run plan (test-strategy.md reproducibility test).
- Provenance: configurable name prefix, default `ALTALM-GEN-<runid>`, stamped on every generated
  record; cleanup sweeps strictly by this prefix query and refuses to delete non-prefixed records (D6).
- Coverage generation must not double-write — one `requirement-coverage` POST auto-creates the
  config-coverage row (`api-ref §6.2`); treat as a side effect, not a separate DAG node.
- Run generation goes through the P3 Fast_Run route exclusively — never attempts direct `POST runs`.

**Dependencies**: P2 (write-safety, entity CRUD), P3 (test-lab chain, Fast_Run route).

**Exit criteria**: seed reproducibility test passes; dry-run golden-file test passes; allowlist
refusal test passes (attempted write against a non-allowlisted project/domain is refused, zero calls
made); a full happy-path DAG run against the sandbox creates a realistic interlinked dataset end-to-end
and the cleanup sweep afterward returns zero orphans.

**Sandbox contract-test gate**: full gate, generator-specific prefix (`ALTALM-GEN-<runid>` rather than
bare `ALTALM-*`) — cleanup in `finally`, orphan sweep is itself a named contract test (not just an
ops step).

---

## P5 — Rich content

**Objective**: rich-text editor with sanitizer-aware round-trip, embedded images, attachments UI;
extend the generator with rich-text/image content strategies.

**In scope** (`matrix` rows): rich-text description/comments editing #26, #191; attachment types/upload/download #180–190, including the full embedded-image flow; row #45's **Route B only** — parameter-token authoring, the entity-encoded `<<<name>>>` step-text marker (must be HTML-entity-pre-encoded — `&lt;&lt;&lt;name&gt;&gt;&gt;` — or the sanitizer mangles it to `<<>>`, `data-model §6`) — for UI-authenticity; the parameter *object* itself (definition, default value, step-value recording) is P2/P4 work, not gated on this phase.

**Out of scope**: *(nothing parameter-related remains out of scope here — `step-parameters` value
recording moved to P2, retracted from "REST-unreachable" by `live-probe-log.md` Probe 9.)*

**Key technical tasks**:
- Canonicalized-HTML comparator: memo storage is a full `<html><body>` document, not byte-identical
  on round-trip — `<script>` stripped, implicit `<tbody>` inserted, whitespace re-pretty-printed
  (`api-ref §7`). Round-trip tests canonicalize before comparing, never compare raw bytes.
- Rich-text editor writes HTML-entity-encode any literal `<`/`>` in free text not itself constructed
  as valid markup (general mitigation, not just parameter tokens — `data-model §6`).
- Embedded images: two confirmed working paths — (1) octet-stream+`Slug` upload (`ref-subtype=0`) +
  `<img src>` as a **full absolute REST URL** or `data:` URI (bare filename/relative path silently
  strips `src` — never generate those, `api-ref §6.6`); (2) hand-built multipart upload with
  `ref-subtype=1` (works, but is a client-library compatibility risk — integration-test per stack,
  `api-ref §6.6`, D2).
- Generator: rich-text block grammar, image-embed strategy using the same two paths, entity-encoding
  of any generated `<<<param>>>`-shaped free text.

**Dependencies**: P2 (write path for memo fields), P4 (generator engine to extend).

**Exit criteria**: canonicalized round-trip test passes for a torture-HTML block; embedded-image
end-to-end test passes (upload → PUT memo with `<img src>` → GET → image renders) for both confirmed
upload paths; generator-produced rich-text content survives the sanitizer without token mangling.

**Sandbox contract-test gate**: full gate.

**Deferred probe executed at phase start**: `IMAGE_COMPRESSION_LEVEL` round-trip (`live-probe-log.md`
§Open items #10 — new in 25.1, untested whether the server re-encodes uploaded image bytes) — probe
before finalizing the generator's image-strategy byte-comparison tests.

---

## P6 — Optional + hardening

**Objective**: the OTA bridge sidecar (D3); version-control (check-in/out) support; favorites; the
audits/history view (with its partial-coverage caveat made explicit in the UI); remaining
deferred-probe follow-ups.

**In scope** (`matrix` rows): baselines #23, #148–151 (OTA); Business Components/BPT #42, #54–57
(OTA, license-gated — `GET /components` 403, `GET /business-components` 404, `data-model §6.7b`);
pinned test sets #63/#64 (OTA); purge runs #66 (OTA — per-id delete already works as a REST substitute
per-item); automatic runner infra #75 (out of reach without lab hosts); timeslots #81 (OTA); similar
defects #108 (OTA); alerts/follow-up flags #109/#110/#166/#195–197 (OTA — #166 aligned with its
siblings 2026-08-13, `live-probe-log.md` Probe 12 §12.2); favorites #117/#160 (PARTIAL, full
CRUD/permissions to confirm); libraries #147/#153 (OTA); Business View graphs #129 (OTA — added
2026-08-13, Probe 12; `Customization.BusinessViews`/`GraphBuilder`, read-verified only); Scorecard/KPI
#145 (OTA — added 2026-08-13, Probe 12; `Customization.KPITypes`/`KPIFactory`, read-verified only);
Report template authoring/execution #132/#133 (OTA — added 2026-08-13, Probe 12;
`Customization.ReportProjectTemplates`, read-verified only; authoring *new raw SQL* stays out of scope
by hard constraint regardless); data-hiding per group per module #205 (OTA — added 2026-08-13,
Probe 12; `Customization.Modules`/`Permissions`/`UsersGroups`, read-verified only, per-module accessor
arity still open — `risks-and-open-questions.md` Q37); history/audit views #173/#176 (PARTIAL —
UI must state the partial-coverage caveat, not imply full history); versions/VC UI #174/#177
(PARTIAL — check-out/in write sequence unprobed until this phase). *(Step-parameters via OTA
[`StepFactory`] removed from this list 2026-08-13 — retracted by `live-probe-log.md` Probe 9, which
closed parameter definition and values over documented REST; see P2. Analyze/Analyze and Apply to
Children [`matrix #18`] does NOT belong in this phase either — it needs only a one-time OTA capture of
the RBT matrix, not a live sidecar dependency, and its client-side computation lands in P2; see P2's
Key technical tasks. ADR 0003's OTA justification now rests on eight named surfaces, not two — see
ADR 0003 Addendum 3 for the full current scope and the read-vs-write distinction.)*

⚠️ **Five of the newly-added rows above (#129, #132/#133, #145, #166/#109/#196/#197, #205) are
confirmed read-reachable only** — Probe 12 was a read-only pass that verified the underlying COM
objects exist and documented Add/Remove/Delete methods are present, but never called one. Write
capability is `UNVERIFIED` for all of them (`risks-and-open-questions.md` Q39); a dedicated write probe
against each is a prerequisite before this phase implements anything beyond a read-only view for them.

**Out of scope**: anything requiring a REST surface this project has confirmed absent, with no OTA
path either (text search/global search #107/#119/#170/#214, risk export to Word #20, Live Analysis
#83/#134 — all `NO` verdicts reconfirmed 2026-08-13, Probes 11–12, `_raw/no-verdict-recheck.md`).
**Business-view graphs, Scorecard/KPI, and report authoring are no longer out of scope** — corrected
2026-08-13; they were reclassified `NO`→`OTA` by Probe 12 and are listed in-scope above.

**Key technical tasks**:
- OTA bridge sidecar (D3): small internal HTTP API for REST-unreachable operations. Scope narrowed
  from three to two named gaps on 2026-08-13 (step-parameters definition dropped, closed over REST by
  `live-probe-log.md` Probe 9: BPT components if license allows, similar-defects) — then **grew to
  eight named surfaces the same day** (Probes 11–12 NO-verdict recheck: RBT/Testing-Policy matrix
  one-time read for #18, KPI types, report templates, business views, alerts, permissions/data-hiding —
  see ADR 0003 Addendum 3 for the full current scope). The sidecar is now load-bearing for the
  product's feature surface, though still off the generator's critical path. **Settle the
  implementation-language decision (.NET vs Python + `pywin32`) at this phase's kickoff, before writing
  sidecar code** — ADR 0003 Addendum 3 argues the eight-surface scope makes this worth resolving early
  rather than deferring further; .NET scored 5/5 on COM interop in ADR 0002 against Python's 3/5.
  Implementation language decided when `tdconnect.exe` is supplied (candidates: .NET COM interop, or
  Python + pywin32 — see architecture.md's ADR). BFF treats the bridge as optional: absent bridge →
  features degrade behind capability flags; mainline works fully without it.
- Version-control UI: `.../{id}/lock` and `.../{id}/versions` sub-resources exist and are readable;
  check-out/check-in/undo-check-out write sequence and two-version compare are probed and implemented
  here for the first time (`api-ref §5`, restricted to requirements/tests/resources).
- Audits/history view: `GET .../{id}/audits` exists on 24 entity types but coverage is confirmed
  incomplete (status-field changes only; creates and memo PUTs invisible — `probe log` Probe 4 §10).
  UI carries this caveat visibly, not presented as a complete history.
- Mail: capture the stock web client's `POST .../{id}/mail` body shape (4/4 attempted shapes failed —
  `api-ref §9`) if pursued; otherwise Alt-ALM sends its own mail (fallback plan, `matrix #198`).

**Dependencies**: all prior phases; OTA bridge work specifically depends on the user supplying
`tdconnect.exe` (blocking precondition — the phase's OTA-dependent tasks are gated behind that, not
the phase as a whole).

**Exit criteria**: OTA bridge capability flag correctly reports absent/present and features degrade
gracefully when absent; VC check-in/check-out flow works against the sandbox; favorites full CRUD
confirmed; audits view ships with the partial-coverage caveat visible in the UI copy itself.

**Sandbox contract-test gate**: full gate for all REST-reachable work in this phase; OTA-bridge
operations are tested separately (COM/Windows-only, not a sandbox REST contract test) once the bridge
exists.

**Deferred probes executed at phase start**: mail body shape (`live-probe-log.md` §Open items #10);
audit coverage isolation — plain-field PUT vs. memo PUT (`live-probe-log.md` §Open items #10); versions
check-in/check-out write probe (`live-probe-log.md` §Open items #10). *(The "step-parameters via OTA"
deferred probe formerly listed here is retracted — resolved directly over REST by Probe 9, no OTA
probe needed; see P2. A new, unrelated open item from Probe 9 — why `DELETE design-steps/{id}` 500s
when a `step-parameter` still references it — is tracked as Q35 in `risks-and-open-questions.md`,
scoped to P4/P5 generator cleanup-path work, not this phase.)*

---

## Deferred probes — summary map

From `live-probe-log.md` §Open items #10, mapped to the phase executing each (usually the start of
the phase that needs the answer):

| Deferred probe | Executed in |
|---|---|
| `alm-web` dialect body shape | P1 (grouping/aggregation views) |
| Comments-append banner convention | P2 (write core, memo-field writes) |
| Release-folder root id | P3 (releases/cycles/milestones) |
| Test-set → release/cycle assignment field (`matrix #61`) | P3 |
| `IMAGE_COMPRESSION_LEVEL` round-trip | P5 (rich content) |
| Mail POST body shape | P6 (hardening) |
| ~~Step-parameters via OTA (`tdconnect.exe`-gated)~~ | **Retracted — resolved directly over REST, Probe 9. No OTA probe needed; see P2.** |
| Audit coverage isolation (plain-field vs. memo PUT) | P6 (audits view) |
| Versions check-in/check-out write probe | P6 (VC UI) |

---

## Milestone / sequencing view

```
P0 Foundations
  └─▶ P1 Read-only Alt-ALM
        └─▶ P2 Write core
              ├─▶ P3 Test Lab + planning
              │     └─▶ P4 Generator MVP
              │           └─▶ P5 Rich content  ◀── also depends on P2 directly
              └─▶ (P5 rich-text write path depends on P2's memo-field CRUD)
                    └─▶ P6 Optional + hardening  ◀── depends on all prior phases;
                                                      OTA-bridge tasks additionally
                                                      gated on tdconnect.exe supply
```

Strictly ordered: P0 → P1 → P2. From P2, P3 and P5's editor-independent groundwork can proceed in
parallel, but P5's generator-integration needs P4, and P4 needs P3's Fast_Run route — so the practical
linear path is P0 → P1 → P2 → P3 → P4 → P5 → P6. P6 is the only phase also gated on an external
precondition (`tdconnect.exe`), not purely on earlier phases.
