# Kickoff Prompt — Claude Fable 5

> **How to use:** paste everything below the horizontal rule into a fresh Claude Code session running
> **Claude Fable 5**, with the working directory set to the root of this repository.
> Nothing above the rule is part of the prompt.

---

# ROLE

You are the lead architect and research engineer for **Alt-ALM**, a greenfield project in this
repository. You are running as Claude Fable 5 with full tool access. You will spend this session on
**research and planning only** — you will not build the application yet. Your output is a body of
verified research and a comprehensive, testable implementation plan that a follow-up session (or a
team of agents) can execute without re-deriving anything.

Work autonomously. Do not stop to ask permission for ordinary research steps. Only block on the
clarifying questions listed in Phase 0, and only if the answers would materially change the plan.

---

# 1. CRITICAL DISAMBIGUATION — READ THIS FIRST

The target product is **OpenText Application Lifecycle Management (ALM) / Quality Center**, the
classic on-premise/SaaS product formerly sold as **HP ALM**, then **Micro Focus ALM/Quality
Center**, now **OpenText ALM/Quality Center** (versions roughly 12.x through 17.x+). Its web client
is served from a `/qcbin` context path, its data lives in Domains → Projects, and it exposes a
**REST API rooted at `/qcbin/rest/...` and `/qcbin/api/...`**.

**It is NOT any of the following, and research about them is worse than useless here:**

- **OpenText Core Software Delivery Platform** (formerly **ALM Octane**, formerly ValueEdge) — a
  completely different product with a completely different REST API shaped like
  `/api/shared_spaces/{id}/workspaces/{id}/{entity}`. If you find yourself reading about
  `shared_spaces`, `workspaces`, "backlog items", "features/epics/user stories", or
  `octane`-branded SDKs, **you are in the wrong product**. Stop and re-scope.
- OpenText Core Performance Engineering / LoadRunner / UFT One (except where they integrate *into*
  ALM as test types — that integration surface is in scope, the products themselves are not).
- Any generic "ALM" tool (Jira, Azure DevOps, Polarion, codeBeamer).

Signals you are in the **right** product: `qcbin`, `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`,
`ALM_USER`, "Domain/Project", "Test Plan / Test Lab / Test Sets / Test Instances / Runs / Design
Steps", "Requirements coverage", "Release / Cycle", "Business Process Testing", "OTA / TDConnection"
(the legacy COM API), "Site Administration", "workflow scripts (VBScript)".

Every research artifact you produce must state the product name and version range it applies to. If
a source is ambiguous about which product it describes, treat it as unverified and find a better
source.

---

# 2. WHAT WE ARE BUILDING

Two deliverables live in this repo. This session plans both.

## 2.1 Deliverable A — "Alt-ALM": a modern alternative front end

A web application that talks **only to the documented OpenText ALM REST API** (no OTA/COM, no
database access, no scraping of the stock web client, no undocumented endpoints unless explicitly
flagged and isolated) and reimplements **as much of the stock ALM UI's functionality as the API
permits**, with a modern, fast, keyboard-friendly interface.

Guiding goals, in priority order:

1. **Coverage** — reach as much of the documented API surface and as many stock-UI workflows as
   possible. Where the API genuinely cannot support a stock-UI feature, that must be *recorded as a
   documented gap*, not silently dropped.
2. **Correctness** — respect ALM's real rules: required fields, read-only/system fields, per-project
   field customization, user-defined fields, lookup lists, permission groups, versioning
   (check-in/check-out), and workflow-imposed constraints.
3. **Speed and ergonomics** — grids that handle tens of thousands of rows, real filtering that maps
   to ALM query syntax, bulk edit, command palette, deep links, sane keyboard navigation.
4. **Modern look** — clean, dense-but-legible, light/dark aware, accessible (WCAG 2.2 AA).

## 2.2 Deliverable B — the Record Generator ("ALM Faker")

A seedable, datatype-aware synthetic data generator that populates an ALM project with realistic
volumes of interconnected dummy records, for testing integrations against ALM. Think Python's
`faker`, but ALM-aware: it must read the *target project's actual customization* and generate data
that project will actually accept.

Non-negotiable requirements (elaborated in Phase 7):

- **Every field is filled according to its real ALM datatype**, discovered at runtime from the
  project customization API — never hardcoded.
- **Rich text / memo fields get genuinely rich content**: tables, bulleted and numbered lists,
  **bold**, *italic*, <u>underline</u>, font and background colors, headings, mixed nesting. The
  content does not need to make semantic sense; it must exercise the formatting surface and survive
  a round trip through ALM.
- **Requirements are generated as deep, realistic hierarchies**, not flat lists.
- **Links are created between entities in realistic densities** — not uniform, not exhaustive: some
  requirements heavily covered, many barely, some not at all.
- **Releases and cycles** frame the data with coherent dates, and test execution and defect activity
  fall inside those windows.
- **Full test management chain**: test plan folders → tests → design steps → parameters → test
  configurations → test set folders → test sets → test instances → runs → run steps.
- **Defects** with realistic status/severity/priority distributions, real users, and links back to
  runs and requirements.

---

# 3. YOUR MISSION THIS SESSION

Produce, in this repository:

| # | Artifact | Path |
|---|---|---|
| 1 | ALM REST API reference (verified, cited) | `docs/research/alm-api-reference.md` |
| 2 | Stock ALM UI feature inventory | `docs/research/alm-ui-feature-inventory.md` |
| 3 | ALM entity/data model and field-type catalogue | `docs/research/alm-data-model.md` |
| 4 | Feature → API feasibility & gap matrix | `docs/research/feasibility-matrix.md` |
| 5 | Alt-ALM architecture | `docs/plan/architecture.md` |
| 6 | Phased implementation plan | `docs/plan/implementation-plan.md` |
| 7 | Record generator specification | `docs/plan/data-generator-spec.md` |
| 8 | Test strategy | `docs/plan/test-strategy.md` |
| 9 | Risks, assumptions, open questions | `docs/plan/risks-and-open-questions.md` |
| 10 | Architecture Decision Records | `docs/adr/NNNN-*.md` |
| 11 | Reusable skills | `.claude/skills/<name>/SKILL.md` |
| 12 | Updated durable project context | `CLAUDE.md` (root, already exists — extend it) |

**Do not write application code this session.** The one exception: short, throwaway *probe scripts*
under `scripts/probe/` used to verify API behaviour against a live instance, and only if Phase 0
establishes that a live sandbox instance is available. Probe scripts are evidence-gathering tools,
not the beginnings of the app.

End the session at an explicit **"ready to implement"** gate: the plan should be complete enough
that the next session can start at Milestone 1 with zero re-research.

---

# 4. REPOSITORY AND ENVIRONMENT

- Repo root contains `README.md`, `LICENSE`, `.gitignore`, `CLAUDE.md`, and a **git-ignored**
  `Secrets/` directory.
- `Secrets/ALM_API_credentials.json` is the credential location. It currently holds
  **placeholder values** (`api_key` / `api_secret`). Treat its *shape* as provisional — part of your
  job is to determine what credentials the chosen auth method actually needs (API key client
  ID/secret vs. username/password vs. both, plus base URL, domain, project) and to specify the
  correct schema for that file in your plan.
- **`Secrets/` must never be committed, never be read into a document, and never be echoed into
  logs, docs, commit messages, or subagent prompts.** Reference it by path only.
- Platform is Windows 11; the shell is PowerShell 7. Any commands you put in docs must either be
  cross-platform or clearly labelled per-shell.
- Git: branch `main`, one commit of history. Commit your work at the end (Phase 10).

---

# 5. PHASE 0 — SET UP THE WORK

1. **Track the work.** Create a task list covering every phase below and keep it current. This is a
   long session; do not lose the thread.

2. **Ask the blocking questions, once, up front, then proceed.** Only these qualify:
   - Is there a **live ALM instance** available for probing (base URL, domain, project)? If yes, is
     there a **designated sandbox project** where writes are permitted?
   - Which **ALM version(s)** must be supported? (Behaviour differs meaningfully across 12.5x /
     15.x / 16.x / 17.x, and between on-prem and SaaS.)
   - Which **authentication method** is intended — API key (client ID + secret) or username +
     password?
   - Any **stack constraints** (must/must-not use a given language, framework, or hosting model)?

   If the user does not answer, proceed with documented assumptions: no live instance, support
   ALM 16.x–17.x on-prem with best-effort 15.x, API-key auth preferred with username/password
   fallback, stack free choice. Record the assumptions in
   `docs/plan/risks-and-open-questions.md` and continue — **do not stall**.

3. **Author your skills before the heavy research.** See Section 13. Skills you write early get used
   by every subagent you spawn afterwards, which is the whole point.

---

# 6. PHASE 1 — RESEARCH THE ALM REST API (EXHAUSTIVE)

Produce `docs/research/alm-api-reference.md`. This should read like a reference manual someone can
implement against without opening another tab. Organise by area; for each endpoint capture: method,
path, purpose, required headers, request body shape (XML **and** JSON where both are supported),
response shape, status codes, error format, permissions required, version availability, and a
worked example.

Cover at minimum:

**Authentication and session lifecycle**
- The authentication point and what it returns; the LWSSO cookie; the QC session cookie and why a
  second call is needed; sign-out; session expiry, keepalive, and re-auth behaviour.
- API-key authentication (client ID + secret) — which versions support it, how it differs, how keys
  are provisioned in Site Administration.
- CSRF/XSRF token handling — when it is required, where it comes from, what happens without it.
- SSO / SAML / CAC / external IdP implications for a programmatic client.
- Concurrent session limits, licence seat consumption, and what a long-lived integration must do to
  behave.

**Core plumbing**
- The difference between the `/qcbin/rest/...` API and the newer `/qcbin/api/...` endpoints — which
  entities and operations live where, and which is preferred per version.
- Domain and project discovery endpoints.
- Content negotiation: `Accept` / `Content-Type` for XML vs JSON, the `Entity`/`Entities` envelope,
  `Fields`/`Field`/`Value` structure, and the JSON equivalents.
- Any self-documenting endpoint the server exposes (e.g. a REST doc/help path) and how to harvest it
  from a live instance.

**Querying**
- The full query grammar: field filters, comparison operators, ranges, wildcards, negation,
  `AND`/`OR` semantics, quoting and escaping (especially for values containing `;`, `[`, `]`, `'`),
  null/empty tests, date and date-time literal formats and timezone behaviour.
- Cross-entity filters (filtering tests by linked requirement, defects by linked run, etc.).
- `fields` projection, `order-by`, pagination (`page-size` / `start-index`), server-side maxima, and
  how total counts are returned.
- Known pitfalls: fields that cannot be filtered, fields that cannot be sorted, silent truncation.

**Entity CRUD — for every entity type the API exposes**
Enumerate them all; the list below is a starting point, not a limit:
`requirement`, `requirement-coverage`, `requirement-traceability`, `test-folder`, `test`,
`design-step`, `test-config`, `test-parameter`, `test-set-folder`, `test-set`, `test-instance`,
`run`, `run-step`, `defect`, `defect-link`, `release-folder`, `release`, `release-cycle`,
`library`, `baseline`, `attachment`, `alert`, `audit`, `favorite`, `list`/`list-node`,
`users`, `groups`, `project-customization`, `resource`/`resource-folder`, `component`/BPT entities,
`analysis-item`/dashboard entities, `host`, `timeslot`/`reservation`, `site-admin` surfaces.

For each: which operations are supported (GET collection, GET single, POST, PUT, DELETE, bulk
POST/PUT), mandatory fields on create, immutable fields, parent/child relationships and how the
parent is specified, and any special sub-resources.

**Special mechanics**
- **Attachments**: upload (multipart and/or octet-stream), download, listing, size limits, naming.
- **Rich text / memo fields**: how ALM stores and returns them, what markup subset survives a round
  trip, whether they are returned inline or via a separate resource, and any size limits. This is
  load-bearing for Deliverable B — get it right and prove it.
- **History / audit**: how to read change history for an entity, what is captured, retention.
- **Versioning**: check-out / check-in / undo-checkout semantics in version-controlled projects, and
  how every write path changes when versioning is enabled.
- **Baselines and libraries**: creating, comparing, pinning test sets to baselines.
- **Test execution**: creating runs, setting run status, manual run step results, attaching results,
  linking a run to a defect, and what (if anything) the API allows for automated execution,
  execution flow, scheduling, hosts, and timeslots.
- **Coverage and traceability**: creating and reading requirement↔test coverage, requirement↔
  requirement traceability, defect↔entity links, and the direction/semantics of each link type.
- **Project customization**: reading entity field metadata (type, required, editable, system,
  searchable, size, default), user-defined fields, lookup lists and their nodes, users and groups,
  permissions. This API is what makes the generator datatype-aware — document it thoroughly.
- **Bulk operations**: batch create/update, transactional semantics, partial-failure reporting,
  practical batch sizes.
- **Rate limiting, throttling, timeouts, and payload size limits** — documented and observed.
- **Error model**: the standard error envelope, common error codes (`qccore.*`, workflow rejections,
  permission denials) and how a client should distinguish retryable from terminal failures.

**Version and edition differences**
A table of "feature × ALM version × on-prem/SaaS" for anything that differs. Call out what is
unavailable on SaaS.

**Explicitly out of scope, but note their existence and why we're not using them:** the OTA/COM
client-side API, direct database access, and the stock client's internal (undocumented) endpoints.

---

# 7. PHASE 2 — INVENTORY THE STOCK ALM UI

Produce `docs/research/alm-ui-feature-inventory.md`: a systematic walk through every module of the
stock ALM web client, so Alt-ALM can be measured against it. For each module list the views, the
per-view actions, and the cross-cutting behaviours.

Modules to cover:

- **Management** — Releases (release tree, cycles, milestones, progress/scorecard, release-scoped
  filtering), Libraries (library tree, baselines, baseline comparison, imported libraries and
  sync).
- **Requirements** — requirements tree, requirements grid, coverage analysis view, traceability
  matrix, requirement details tabs (details, rich-text description/comments, attachments, linked
  defects, requirement traceability, test coverage, business models, risk analysis), requirement
  types and their type-specific fields, risk-based quality management, converting requirements to
  tests.
- **Testing → Test Resources** — resource tree, resource files, dependencies.
- **Testing → Test Plan** — test plan tree, test grid, test details (details, design steps,
  parameters, test configurations, test script/automation, req coverage, linked defects,
  attachments, history), test types, copy/move, "generate test from requirement".
- **Testing → Test Lab** — test set tree, execution grid, execution flow, manual runner, automated
  runner, run results, "last run" propagation, run scheduling, host/host-group management,
  timeslots, linked defects from a run, pinned/baseline test sets.
- **Testing → Test Runs** — the runs grid, run details, run steps, purge.
- **Defects** — defects grid, defect details, linked entities, similar-defect search, defect
  workflow/status transitions, defect sharing/synchronisation.
- **Dashboard** — analysis view, analysis item types (graphs, project reports, Excel reports,
  standard reports), dashboard pages, KPIs, sharing.
- **Business Process Testing** — components, component steps, flows, BPT test composition.

Cross-cutting behaviours to inventory once and reference everywhere: filters and cross filters,
saved/favourite views (private vs public), column selection and layout, sorting, grouping, find and
replace, go-to-by-ID, history tab, attachments, rich-text fields, follow-up flags, alerts and
notification rules, email sending, "send by email", export to Excel/Word/PDF, text search, entity
version control, permission-driven UI states, workflow-script-driven field behaviour (dynamic
required/read-only/list filtering), required-field enforcement, and user-defined fields.

For **each** feature, record a **feasibility verdict** against the REST API:
`FULL` / `PARTIAL` / `NOT-VIA-API` / `UNKNOWN`, with a one-line justification and the endpoints
involved. Roll these up into `docs/research/feasibility-matrix.md` (Phase 4). Be honest: a plan that
pretends workflow scripts or the manual runner's full behaviour are reachable via REST is worse than
one that names the gap.

---

# 8. PHASE 3 — DATA MODEL AND FIELD TYPES

Produce `docs/research/alm-data-model.md`:

- An entity-relationship map of ALM (text diagram plus a Mermaid diagram), showing parents,
  children, and link entities across Requirements, Test Plan, Test Lab, Runs, Defects, Releases,
  Libraries, and Resources.
- The **complete ALM field-type catalogue** — every type the customization API can report. For each:
  its identifier as returned by the API, its wire representation, valid value space, size/precision
  limits, null semantics, and the rules a client must respect. Expect to cover at least: string,
  memo / rich text, number (integer), float, date, date-time, lookup list (single and multi-value),
  user list, reference/entity link, Y/N or boolean flag, and version/system stamps.
- How **required**, **read-only/system**, **editable**, **searchable**, **history-tracked**, and
  **user-defined** are expressed in field metadata, and how a client discovers them per entity per
  project at runtime.
- How **lookup lists** are modelled (lists, nodes, hierarchical lists) and retrieved.
- The **standard field name conventions** (e.g. system field names vs. user-defined field slots) and
  how display labels map to API field names — the generator and the UI both need this mapping.
- Which fields are **set by the server** and must never be sent on create.
- The correct **creation order** for a referentially consistent dataset, and which links can only be
  created after both endpoints exist.

---

# 9. PHASE 4 — FEASIBILITY MATRIX

`docs/research/feasibility-matrix.md`: one row per stock-UI feature, columns for module, feature,
verdict (`FULL`/`PARTIAL`/`NOT-VIA-API`/`UNKNOWN`), API endpoints involved, workaround if partial,
implementation cost (S/M/L/XL), and priority for Alt-ALM (P0/P1/P2/P3).

This matrix is the backbone of the implementation plan — the milestones in Phase 6 should be
derivable from it. Include a summary section: "% of stock UI reachable", the top gaps, and the
top-10 highest-value features.

---

# 10. PHASE 5 — ARCHITECTURE

`docs/plan/architecture.md`.

**Solve the hard constraints explicitly. Do not hand-wave these:**

1. **Browser → ALM is very unlikely to work directly.** Expect CORS to block it, expect cookie-based
   session auth (`LWSSO_COOKIE_KEY`, `QCSession`) to be hostile to a SPA on another origin, and
   expect XSRF token handling to be required on writes. Determine the truth empirically or from
   documentation, then design accordingly — most likely a **backend-for-frontend proxy** that owns
   the ALM session, holds credentials server-side, and exposes a clean API to the SPA. Justify the
   decision in an ADR.
2. **Session management** — one shared service session vs. per-user sessions, licence-seat impact,
   session pooling, keepalive, expiry recovery, and what happens under concurrent load.
3. **Credential handling** — where secrets live at runtime, how they get there, and how the browser
   never sees them.
4. **Per-project customization is dynamic** — field metadata, lists, and users must be fetched and
   cached per project, and the UI must render forms and grids *from that metadata*, not from
   hardcoded schemas. Design the metadata cache, its invalidation, and the dynamic form/grid
   renderer.
5. **Grid performance** — server-side pagination, sorting, and filtering mapped onto ALM query
   syntax; virtualised rendering; how to show accurate total counts.
6. **Filter model** — a UI filter builder that compiles to valid ALM query strings, including cross
   filters, with round-tripping to saved favourites.
7. **Rich text** — an editor whose output stays inside the markup subset ALM accepts and
   round-trips, plus sanitisation on both read and write.
8. **Attachments and long-running operations** — streaming, progress, cancellation.
9. **Error surfacing** — mapping ALM's error envelope (including workflow rejections and permission
   denials) into actionable UI messages.
10. **Offline/dev mode** — the mock ALM server from the test strategy should be usable to develop the
    UI without a live instance.

Also specify: chosen stack with justification and rejected alternatives, module boundaries, the
shared ALM client library (used by both the BFF and the generator), state management, routing and
deep-linking scheme, design system and theming (light/dark, density), accessibility approach,
observability (structured logs, request tracing, redaction of secrets), configuration, and
packaging/deployment.

Recommend a stack rather than surveying options at length. Record each significant choice as an ADR
in `docs/adr/`.

---

# 11. PHASE 6 — IMPLEMENTATION PLAN

`docs/plan/implementation-plan.md`. Phased and milestone-based. For every milestone give: goal,
scope in/out, the feasibility-matrix rows it satisfies, deliverables, **acceptance criteria that are
objectively checkable**, the tests that must pass, dependencies, risks, and a rough size estimate.

A sensible spine (adjust to what the research actually finds):

- **M0 — Foundations**: repo structure, tooling, CI, config, secret loading, the shared ALM client
  with auth/session/retry, the mock ALM server, contract-test harness.
- **M1 — Read-only shell**: login, domain/project picker, metadata fetch and cache, generic
  metadata-driven grid, generic detail view, navigation, theming.
- **M2 — Requirements**: tree + grid, CRUD, rich text, attachments, coverage and traceability tabs,
  history.
- **M3 — Test Plan**: folders, tests, design steps, parameters, configurations, coverage links.
- **M4 — Test Lab and execution**: test set folders, test sets, test instances, runs, run steps,
  manual run flow, linking defects from runs.
- **M5 — Defects**: grid, detail, links, similar defects, status transitions.
- **M6 — Releases, cycles, libraries, baselines**.
- **M7 — Cross-cutting power features**: filter builder, favourites, bulk edit, find/replace,
  export, command palette, deep links.
- **M8 — Dashboard/analysis** to whatever depth the API allows.
- **M9 — Record generator** (may run in parallel from M1, since it depends only on the shared client
  and the metadata layer).
- **M10 — Hardening**: performance, accessibility, error handling, docs, packaging.

Include a dependency graph, a suggested parallelisation across agents/developers, and a definition
of done that applies to every milestone.

---

# 12. PHASE 7 — RECORD GENERATOR SPECIFICATION

`docs/plan/data-generator-spec.md`. This is a first-class deliverable, not an appendix. Specify it
to the point where implementation is mechanical.

## 12.1 Principles

- **Metadata-driven.** Before generating anything, read the target project's customization: entity
  field metadata, lookup lists, users, groups, requirement types, test types, and any user-defined
  fields. Generate strictly within what the project accepts. Never hardcode a field list.
- **Seedable and deterministic.** A given `(seed, config)` pair produces the same dataset every
  time. Every random draw goes through the seeded RNG.
- **Safe by construction.** See 12.8.
- **Composable.** Each entity generator is independently usable; the orchestrator composes them in
  dependency order.

## 12.2 Field-type → generator strategy matrix

Define, for **every** field type in the Phase 3 catalogue, how a value is produced. At minimum:

| Field type | Strategy |
|---|---|
| String | Type-aware from the field's *name and semantics* (name, summary, path, email, phone, URL, version, component…), truncated to the field's declared size. Never overflow. |
| Memo / rich text | Rich HTML — see 12.3. |
| Number / integer | Plausible ranges per semantic (estimate hours, story points, counts), honouring any min/max. |
| Float | As above with realistic precision. |
| Date | Coherent with the surrounding timeline (release windows, cycle windows, detection→closure ordering). Never random-uniform across all time. |
| Date-time | As dates, plus working-hours-weighted times of day. |
| Lookup list (single) | Drawn from the **project's actual list nodes**, using a weighted distribution rather than uniform (see 12.6). |
| Lookup list (multi) | 0–N nodes, realistic cardinality. |
| User list | Drawn from **real project users**, with a persona weighting (a few heavy users, a long tail). |
| Reference / entity link | Resolved to an actually-created entity ID, in dependency order. |
| Y/N flag / boolean | Skewed, not 50/50, matching the field's semantics. |
| System / read-only / server-set | **Never sent.** |
| Required | **Always** populated, including required user-defined fields. |
| Optional | Populated at a configurable fill rate (default ~65%), so nulls exist in the data. |

Include the rule for unknown/unrecognised types: fail loudly in strict mode, fall back to a safe
string in lenient mode, and always log it as a gap to close.

## 12.3 Rich-text generation

The generator must emit genuinely rich content into memo/rich-text fields, exercising:

- Headings at multiple levels
- Paragraphs of varying length
- **Bold**, *italic*, <u>underline</u>, strikethrough, and combinations
- Foreground **font colours** and background/highlight colours
- Bulleted lists and numbered lists, including nested lists
- **Tables** with header rows, multiple columns, varied cell content, and some cells containing
  formatted text or lists
- Horizontal rules, block quotes, monospace/code-ish spans
- Hyperlinks
- Mixed-content blocks combining several of the above

Requirements on the implementation:

- Composition is **template + block-assembly driven**: a document is assembled from randomly chosen
  block types in random order, with configurable weights and a configurable size (small / medium /
  large / "torture test").
- Output must stay within the **markup subset ALM actually accepts and round-trips** — this is the
  Phase 1 research item; the spec must state the subset explicitly and the generator must have a
  validation step that rejects out-of-subset markup before sending.
- Include a **round-trip verification mode**: create an entity, read it back, and diff the returned
  markup against what was sent, reporting what ALM normalised, stripped, or mangled. Bake the
  findings back into the accepted subset.
- Content need not be semantically meaningful, but should *look* like real ALM content — realistic
  vocabulary for the domain (requirements, test steps, defect reproduction steps), not lorem ipsum.
- Provide a deliberate **"formatting torture" profile** used by tests, which packs every supported
  construct into one document.

## 12.4 Requirement hierarchies

- Generate trees with configurable depth (default 4–6 levels) and branching factor that **varies by
  depth** (wide near the root, narrow at the leaves) rather than a uniform fan-out.
- Respect the project's real **requirement types** and any type-specific field rules, with a
  realistic type mix (folders near the root, functional/business requirements in the middle,
  testable leaves).
- Realistic naming that reflects position in the tree (a child's name should look like it belongs
  under its parent).
- Handle ALM's rules about which types may parent which, and about name uniqueness among siblings.
- Cross-tree **requirement→requirement traceability** links at a realistic density, guaranteed
  acyclic.

## 12.5 Test management, releases, and cycles

- **Releases** in release folders, with coherent start/end dates spanning a configurable programme
  window; **cycles** strictly inside their release's window, non-overlapping or slightly
  overlapping per config; milestones where supported.
- **Test plan folder tree** mirroring the shape (not the exact structure) of the requirements tree.
- **Tests** of the project's real test types, each with a realistic number of **design steps**
  (description + expected result, both rich text), some with **parameters**, some with multiple
  **test configurations**.
- **Test set folders** organised by cycle/purpose; **test sets** assigned to cycles; **test
  instances** linking tests (and configurations) into test sets.
- **Runs** distributed over the cycle windows with realistic status mixes (mostly Passed, a
  meaningful minority Failed/Blocked/Not Completed, some No Run), multiple runs per instance over
  time showing progression, and **run steps** matching the test's design steps with per-step
  statuses consistent with the run's overall status.
- **Requirement↔test coverage** links created at realistic density (see 12.6).

## 12.6 Realistic link density

Do **not** link uniformly. Specify explicit target distributions, and make them configurable:

- Coverage per requirement: heavily skewed (power-law-ish) — a small number of requirements covered
  by many tests, a long tail with one or two, and a deliberate uncovered fraction (default ~15–25%)
  because real projects have coverage gaps.
- Tests per test set, instances per test, runs per instance: skewed similarly.
- Defects per run: most runs produce none; failed runs usually produce one; a few produce several.
- Defects linked to requirements: a realistic minority.
- Requirement traceability: sparse, clustered within subtrees more often than across them.

State the default parameters numerically in the spec, and require the implementation to report the
achieved distributions after a run so they can be checked against the targets.

## 12.7 Defects

- Realistic distributions for status, severity, priority, and detected-by/assigned-to (drawn from
  real project users with persona weighting).
- Dates that make sense: detected inside a cycle window, closed after detected, status consistent
  with whether a closing date exists.
- Rich-text description and comments, including reproduction steps as numbered lists and
  environment tables.
- Links to runs (the failure that found them), to requirements, and duplicate/related links between
  defects.
- Reproducibility flags, detected-in-release/cycle/version fields populated coherently.

## 12.8 Safety, provenance, and cleanup

This generator writes to real systems. Design it defensively:

- **Dry-run by default.** Writing requires an explicit flag.
- **Target allowlist**: refuse to run unless the `{server, domain, project}` triple appears in an
  explicit allowlist config, and refuse outright on any target whose name matches production
  patterns. Require a typed confirmation of the project name for any write run.
- **Provenance marking**: every generated record carries an identifiable marker (a name prefix
  and/or a dedicated user-defined field and/or a run ID recorded in a memo field) so generated data
  is always distinguishable from real data.
- **Cleanup command**: find and delete everything from a given generation run, in reverse dependency
  order, with a dry-run preview and a report. Verify the project is clean afterwards.
- **Run manifest**: every run writes a local manifest (seed, config, target, timestamps, every
  created entity type+ID, achieved distributions, failures) enabling exact cleanup, resume, and
  reproduction.

## 12.9 Execution mechanics

- Dependency-ordered orchestration with a clear phase list.
- Bulk create where the API supports it; configurable concurrency with a global rate limiter;
  exponential backoff with jitter on retryable errors; a circuit breaker.
- Idempotency and **resumability** from the manifest after an interruption.
- Progress reporting, a final summary report (created counts per entity, timings, throughput,
  failures with reasons), and non-zero exit on partial failure.
- Scale targets: define what "small" (~hundreds), "medium" (~thousands), and "large" (~tens of
  thousands of records) runs mean, with expected runtimes.
- A **CLI** (documented flags, config file, profiles) and a **library API** so the Alt-ALM UI could
  expose generation from a "seed test data" screen.

---

# 13. PHASE 8 — TEST STRATEGY

`docs/plan/test-strategy.md`. The user explicitly asked for a plan **with testing**; treat this as a
headline deliverable, not boilerplate.

Cover:

**Layers**
- **Unit** — query-string builder, field-metadata interpretation, value generators, rich-text
  assembler, distribution samplers, date-coherence logic, error mapping.
- **Contract** — record real ALM responses as fixtures/cassettes and assert the client parses every
  one; re-run against a live instance periodically to detect drift. Include fixtures for error
  responses, empty collections, pagination boundaries, and version-specific variants.
- **Mock ALM server** — a local fake implementing the REST subset (auth handshake, XSRF, entity
  CRUD, query parsing, pagination, customization metadata, attachments, error cases). This unblocks
  UI development and CI without a live instance. Specify its fidelity boundaries explicitly.
- **Integration** — against a real sandbox project, gated behind an env var so it never runs by
  accident; must create everything it needs and clean up after itself.
- **End-to-end** — browser tests (Playwright or equivalent) over the mock server for the critical
  journeys: log in, pick project, browse and filter each module's grid, create/edit/delete each
  entity type, run a manual test, file a defect from a failed run, edit rich text and verify it
  round-trips, upload/download an attachment, build and save a filter.
- **Visual/accessibility** — snapshot key screens in light and dark; automated a11y checks (axe) on
  every route; keyboard-only navigation tests.
- **Performance** — grid with 10k+ rows, large trees, large rich-text documents, generator
  throughput; define budgets and fail CI on regressions.

**Generator-specific testing**
- **Property-based tests**: for any project metadata, every generated record satisfies all required
  fields, violates no size limit, uses only valid list nodes and real users, and sends no read-only
  field.
- Generated hierarchies are acyclic and respect depth/branching config.
- Achieved link densities fall inside the configured tolerance bands.
- Rich text validates against the accepted-subset grammar and survives a **round-trip test** against
  the mock server and (when available) a live sandbox.
- Determinism: identical `(seed, config)` yields identical payloads, byte for byte.
- Cleanup completeness: after generate-then-clean, the project contains no provenance-marked
  records.
- Safety: writes are refused against a non-allowlisted target; dry-run creates nothing.

**Process**
- CI pipeline stages, what runs on PR vs. nightly vs. on-demand, and how live-instance tests are
  gated.
- Coverage targets per layer, and what is deliberately not covered.
- Test data management, fixture refresh procedure, flake policy.
- A manual QA checklist for what automation cannot reach.

---

# 14. PHASE 9 — RISKS, ASSUMPTIONS, OPEN QUESTIONS, ADRs

`docs/plan/risks-and-open-questions.md`:

- Every assumption made because a question went unanswered, with its blast radius if wrong.
- Every `UNVERIFIED` claim carried over from research, with the exact experiment that would verify
  it against a live instance.
- Technical risks (CORS/session model, workflow scripts invisible to REST, per-version API drift,
  licence-seat consumption, rate limits, rich-text fidelity, performance at scale) with likelihood,
  impact, and mitigation.
- Product risks (feature gaps that make Alt-ALM unusable as a replacement for some role).
- A prioritised list of questions for the user/ALM administrator.

Write an ADR in `docs/adr/` for each significant decision (proxy vs. direct, stack, session model,
metadata-driven rendering, rich-text editor and sanitisation, generator determinism approach, mock
server fidelity). Use a consistent template: context, decision, alternatives considered,
consequences, status.

---

# 15. PHASE 10 — PERSIST AND COMMIT

1. **Extend `CLAUDE.md`** at the repo root with the durable facts a future session needs and cannot
   cheaply re-derive: the product disambiguation, the confirmed auth flow and session model, the
   base URL/domain/project conventions, the entity and field-type cheat sheet, the query-syntax
   summary, the accepted rich-text subset, key gotchas, the repo layout, the skills you created and
   when to use them, and pointers into `docs/`. Keep it tight — it loads into every session. Detail
   belongs in `docs/`, not here.
2. **Verify** no secret material appears in any tracked file, and that `Secrets/` is still ignored.
3. **Commit** everything with a clear message summarising the research and plan. Do not push unless
   asked.

---

# 16. SUBAGENT STRATEGY — USE SONNET

Fan out aggressively with **Claude Sonnet subagents** wherever the work parallelises. You are the
integrator; they are the researchers. Suggested decomposition (adapt as you learn more):

**Research wave 1 — API (parallel Sonnet agents)**
1. Authentication, sessions, XSRF, API keys, SSO implications, licence/seat behaviour.
2. Query grammar, pagination, projection, sorting, cross-filters, escaping edge cases.
3. Requirements domain: requirements, types, coverage, traceability, risk.
4. Test Plan domain: test folders, tests, design steps, parameters, configurations, resources.
5. Test Lab domain: test set folders, test sets, instances, runs, run steps, execution, hosts,
   timeslots.
6. Defects domain: defects, links, similar defects, sharing/sync.
7. Releases, cycles, milestones, libraries, baselines.
8. Project customization: field metadata, field types, lists, users, groups, permissions, UDFs.
9. Attachments, rich text/memo storage and markup fidelity, history/audit, versioning/check-in-out.
10. Cross-version differences (12.5x/15.x/16.x/17.x, on-prem vs SaaS), rate limits, error model.

**Research wave 2 — UI (parallel Sonnet agents)**
One agent per stock-UI module from Phase 2, each returning the view/action inventory plus a
first-pass feasibility verdict per feature.

**Synthesis (you, Fable)**
Merge, de-duplicate, resolve contradictions between agents, chase down anything two agents disagree
on, and write the final artifacts yourself. Do not paste subagent output verbatim into deliverables.

**Rules every subagent prompt must include:**
- The Section 1 disambiguation, verbatim. Wrong-product research is the single most likely failure
  mode of this session.
- "Cite a URL for every factual claim. If you cannot cite it, label it `UNVERIFIED` and say what
  would verify it."
- "**Never invent endpoints, field names, parameters, or response shapes.** A gap in the docs is a
  finding to report, not a hole to fill with plausible-looking API design."
- The required output format (structured markdown with a fixed section skeleton) so merging is
  mechanical.
- The scope boundary: what this agent owns and what belongs to a sibling agent.
- "Do not write to `docs/` — return your findings; the lead agent writes the files." (Prevents write
  conflicts.)
- Never include credentials or `Secrets/` content in a subagent prompt.

Run agents in parallel batches rather than one at a time. Where an agent returns thin results, send
it back with a sharper scope rather than accepting the gap.

---

# 17. SKILLS — CREATE THEM AND USE THEM

Author reusable skills under `.claude/skills/<skill-name>/SKILL.md`, each with valid frontmatter
(`name`, `description` written so a future session knows exactly when to load it) and supporting
files under the skill directory. Create these early — before the research fan-out — so subagents and
future sessions benefit.

At minimum:

1. **`alm-api`** — the working reference for calling ALM: auth handshake, session and XSRF handling,
   request/response envelopes, the query-syntax cheat sheet, pagination, error codes, and copy-paste
   recipes for the most common calls. This is the skill every future coding session loads first.
2. **`alm-entity-model`** — entity relationships, field types, required/read-only rules, creation
   order, and how to discover project customization at runtime.
3. **`alm-data-gen`** — conventions for the record generator: field-type→strategy matrix, the
   rich-text block grammar and accepted-markup subset, distribution defaults, provenance marking,
   and the safety checklist that must run before any write.
4. **`alt-alm-ui`** — front-end conventions for this project: design tokens, layout and density
   rules, the metadata-driven form/grid patterns, accessibility requirements, and component
   conventions.
5. **`alm-live-probe`** *(only if a live instance exists)* — the safe procedure for probing a live
   ALM instance: read-only by default, the sandbox-only write rule, how to capture responses as test
   fixtures, and how to redact before saving.

Keep each skill focused and actionable — instructions and reference material a future agent can act
on, not prose. Reference them from `CLAUDE.md`.

---

# 18. RESEARCH RULES

- **Prefer primary sources**: official OpenText/Micro Focus/HP ALM documentation, the ALM REST API
  reference shipped with the product, the self-documenting REST help path on a live server, and
  official OpenText developer/community material. Treat blog posts, Stack Overflow, and GitHub
  wrappers as leads to be confirmed against primary sources — useful for discovering that something
  exists, insufficient for documenting how it behaves.
- **Cite everything.** Every endpoint, parameter, and behavioural claim gets a source URL (and
  version, where the source states one).
- **Label uncertainty.** Anything you could not confirm is `UNVERIFIED`, with the experiment that
  would confirm it. A well-marked unknown is a good outcome; a confident fabrication is a
  session-ruining one.
- **Version-stamp findings.** Behaviour that differs across ALM versions must say which version it
  was observed or documented for.
- **Verify against the live instance when one is available** — for anything load-bearing (auth flow,
  rich-text round-trip fidelity, field metadata shape, query escaping, bulk limits), a probe beats a
  document. Save captured responses as redacted fixtures under `tests/fixtures/` for the contract
  tests.
- **Old documentation is still useful.** This product's REST API has been stable for years; HP-era
  and Micro Focus-era docs are often the most complete source. Note the era and check for drift.

---

# 19. GUARDRAILS

- **Never** commit, print, log, or forward the contents of `Secrets/`.
- **Never** write to a live ALM project unless the user has explicitly designated it as a sandbox,
  and even then only for a named verification purpose. Default to read-only probing.
- **Never** invent API behaviour to make the plan look complete.
- **Do not** use the OTA/COM API, direct DB access, or undocumented internal endpoints in the plan.
  If an undocumented endpoint is the only path to a valuable feature, record it as a flagged option
  in the risks document with its downsides — do not build it into the mainline design.
- **Do not** write application code this session (probe scripts under `scripts/probe/` excepted).
- Keep `CLAUDE.md` short and high-signal; put depth in `docs/`.

---

# 20. DEFINITION OF DONE

The session is complete when all of the following are true:

- [ ] Every artifact in Section 3's table exists, is substantive, and is internally consistent.
- [ ] The API reference covers auth, querying, and every discoverable entity type, with citations.
- [ ] The UI inventory covers every stock module, with a feasibility verdict per feature.
- [ ] The feasibility matrix is complete and has a roll-up summary, including honest gaps.
- [ ] The architecture resolves the CORS/session/credential question with an ADR behind it.
- [ ] The implementation plan is milestone-based with objectively checkable acceptance criteria.
- [ ] The generator spec covers every field type, the rich-text block grammar and accepted subset,
      hierarchies, link-density targets with numbers, releases/cycles, the full test chain, defects,
      determinism, provenance, cleanup, and safety.
- [ ] The test strategy covers all layers including the mock ALM server and generator
      property-based tests.
- [ ] Risks, assumptions, and open questions are documented, with verification experiments for every
      `UNVERIFIED` claim.
- [ ] Skills exist under `.claude/skills/` and are referenced from `CLAUDE.md`.
- [ ] `CLAUDE.md` is updated with durable context and pointers.
- [ ] No secrets in tracked files; everything is committed on `main`.
- [ ] You end with a written summary: what you verified, what you assumed, the biggest risks, and
      the exact first three tasks the implementation session should pick up.

---

# 21. STARTING LEADS — TREAT AS HYPOTHESES, NOT FACTS

These are search seeds gathered from prior familiarity with the product. **Every one of them is
`UNVERIFIED` and must be confirmed against primary documentation or a live instance before it enters
any deliverable.** Some may be wrong, outdated, or version-specific. Do not copy them into your
research documents as findings.

- The REST API is rooted at `/qcbin/rest/` with project resources under
  `/qcbin/rest/domains/{domain}/projects/{project}/{entity-collection}`; newer endpoints may live
  under `/qcbin/api/`.
- Authentication historically involved an authentication-point call establishing an `LWSSO` cookie,
  followed by a session call establishing a `QCSession` cookie; later versions added API-key
  (client ID + secret) sign-in and require an XSRF token header on writes.
- Query syntax is roughly `?query={field[value];other-field[>=value]}` with `order-by`, `fields`,
  `page-size`, and `start-index` parameters.
- Entity payloads use an `Entity`/`Entities` envelope containing `Fields` → `Field` → `Value`, with
  a JSON equivalent.
- Entity collection names are hyphenated and plural-ish (`requirements`, `tests`, `test-sets`,
  `test-instances`, `runs`, `run-steps`, `design-steps`, `defects`, `defect-links`, `releases`,
  `release-cycles`, `release-folders`, `test-folders`, `test-set-folders`, `requirement-coverage`).
- Project field metadata is exposed under a customization path, and lookup lists under a related
  path.
- Version-controlled projects require check-out before modifying an entity.

Confirm the true shape of all of the above. Where reality differs, say so explicitly in the research
documents — knowing which of these is wrong is itself valuable.

---

**Begin with Phase 0.**
