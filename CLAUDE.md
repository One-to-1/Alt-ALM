# Alt-ALM — Project Context

Durable context for Claude Code sessions in this repository. Keep this file short and high-signal;
depth belongs in `docs/`.

## What this project is

Two things, built against **one** external system:

1. **Alt-ALM** — a modern alternative web front end for OpenText ALM / Quality Center, implementing
   as much of the stock ALM UI's functionality as the documented REST API permits.
2. **The Record Generator ("ALM Faker")** — a seedable, datatype-aware synthetic data generator that
   fills an ALM project with realistic, interlinked dummy records (requirement hierarchies, releases
   and cycles, the full test-management chain, defects, and cross-entity links) for testing
   integrations against ALM.

## Target system — read before any research or design

The target is **OpenText Application Lifecycle Management (ALM) / Quality Center** — the classic
product line formerly **HP ALM**, then **Micro Focus ALM/QC**, versions ~12.x–17.x, on-prem and
SaaS. Its web client is served under a `/qcbin` context path and its REST API lives at
`/qcbin/rest/...` (with newer endpoints under `/qcbin/api/...`).

**It is NOT OpenText Core Software Delivery Platform / ALM Octane / ValueEdge.** That is a different
product with a different API (`/api/shared_spaces/{id}/workspaces/{id}/...`). If research turns up
`shared_spaces`, `workspaces`, "backlog items", or `octane` SDKs, it is the wrong product — re-scope.

Right-product signals: `qcbin`, `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`, Domain/Project,
Test Plan / Test Lab / Test Sets / Test Instances / Runs / Design Steps, Requirements coverage,
Release/Cycle, Business Process Testing, OTA/`TDConnection`, Site Administration, VBScript workflow
scripts.

## Hard constraints

- **Documented REST API only.** no direct database access, no scraping the stock web
  client, no undocumented internal endpoints in the mainline design. An undocumented endpoint that
  unlocks something valuable is a risk-register entry, not an implementation. COM/OTA is an allowed
  fallback: **TDConnect clients are now in `TDConnect/`** (git-ignored) — `TDConnect_26.1CE_SAAS.exe`
  matches the sandbox; 24.1 and 25.1 also present. OTA is COM/Windows-only and isolated in a sidecar
  (ADR 0003).
- **Never commit, print, log, or forward `Secrets/`.** It is git-ignored.
  `Secrets/ALM_API_credentials.json` holds **live working credentials** (keys: `alm_adress`,
  `api_key`, `api_secret`, `domain`, `project`). Reference it by path; read at runtime only; mask
  host/domain/project/keys/usernames in every output and fixture.
- **Never write to a live ALM project** unless the user has explicitly designated it a sandbox.
  The project in `Secrets/ALM_API_credentials.json` **was designated a disposable sandbox by the
  user on 2026-08-12** — writes allowed there, with `ALTALM-*` name prefixes and mandatory cleanup.
  No other project. The record generator is dry-run by default and must refuse any target not on an
  explicit allowlist.
- **Never invent API behaviour.** Unverified claims get labelled `UNVERIFIED` with the experiment
  that would confirm them. A marked unknown is fine; a confident fabrication is not.

## Repository layout

| Path | Contents |
|---|---|
| `CLAUDE.md` | This file — durable project context. |
| `bff/` | Spring Boot BFF (Java 25). Maven **wrapper** — use `./mvnw`, no local Maven needed. |
| `spa/` | React + TypeScript SPA (Vite). |
| `.github/workflows/` | CI: builds both halves + asserts `Secrets/` is never tracked. |
| `docs/prompts/` | Kickoff prompts for agent sessions. |
| `docs/research/` | Verified findings about the ALM API, UI, and data model. |
| `docs/plan/` | Architecture, implementation plan, generator spec, test strategy, risks. |
| `docs/adr/` | Architecture Decision Records. |
| `.claude/skills/` | Reusable skills for ALM work (see below). |
| `Secrets/` | Git-ignored credentials. Never read into a document. |

## Current status

**Phase: research and planning — COMPLETE (2026-08-12). Implementation not started; no application
code exists.**

Nine live probe rounds against the sandbox plus 20 subagent research reports produced the research
corpus; the plan set and ADRs are written. **`docs/research/SESSION-STATE.md` is the resume
point — read it first.** Next work is phase **P0** of
[docs/plan/implementation-plan.md](docs/plan/implementation-plan.md).

⚠️ **Toolchain**: Node 24 and git are present. **JDK 25 (LTS)** is being installed by the user
(2026-08-13) — required before the Spring Boot BFF can be built or run.

Key artifacts: [live-probe-log.md](docs/research/live-probe-log.md) (empirical ground truth — **wins
every conflict**), [alm-api-reference.md](docs/research/alm-api-reference.md),
[alm-data-model.md](docs/research/alm-data-model.md),
[feasibility-matrix.md](docs/research/feasibility-matrix.md) (218 features scored),
[architecture.md](docs/plan/architecture.md) + `docs/adr/0001–0005`,
[data-generator-spec.md](docs/plan/data-generator-spec.md),
[risks-and-open-questions.md](docs/plan/risks-and-open-questions.md) (R1–R16, Q1–Q31).

## Verified facts (sandbox = ALM 26.1, SaaS-flavored; depth in `docs/research/`)

**Load the `alm-api` skill before any ALM work — these are the headlines only.**

- **Auth**: `POST /qcbin/rest/oauth2/login` with `{clientId, secret}` sets the full cookie set in one
  call. `X-XSRF-TOKEN` header required on every non-GET (missing → 401). REST sessions consume **no
  licence seat**. Use `/qcbin/v2/rest/is-authenticated` for JSON (the Core path is XML-only, 406).
- **One API key holds many concurrent sessions** — **50/50 opened, zero evicted, all usable
  simultaneously** (probe 10); no cap was reached, so 50 is a floor. Unlike a username/password
  login, there is **no one-machine-at-a-time constraint**. `JSESSIONID`, `LWSSO_COOKIE_KEY`,
  `QCSession`, `XSRF-TOKEN` are each unique per session; only `ALM_USER` is shared. Multi-machine
  (different IP) behaviour is `UNVERIFIED` — all 50 came from one host.
- **Write hazards (cause real bugs)**: entity-write JSON **field order is load-bearing** — wrong
  order yields opaque NPE-style 500s, so serialize deterministically. **An HTTP 5xx may still have
  committed the row** — treat every 5xx write as "unknown outcome, verify by query", never "failed".
  **Field metadata does not fully describe writes**: `editable:false` does *not* mean "omit from the
  body" and `required:false` does *not* mean "optional on create" — `test-parameter.ref-count` is
  read-only per metadata yet the create 500s without it. On a 500 naming
  `missing required field <PHYSICAL_NAME>`, retry once with that field included (probe 9).
- **Field-type system**: exactly 8 types (String, Memo, Number, Date, DateTime, LookupList,
  UsersList, Reference). **No Boolean** — Y/N is a LookupList bound to list-id 1. Only 2 multivalue
  fields exist in the whole model. Metadata is per project — discover roots, lists, and subtypes at
  runtime, never hardcode.
- **Runs cannot be created directly.** `POST runs` fails definitively (8 attempts). The only working
  route is `PUT test-instances/{id}` with a status, which makes the server synthesize a `Fast_Run`
  (run-steps auto-copy from design steps; the run name is server-generated and not overridable).
- **Workflow scripts are bypassed on REST writes** by default (`CLIENT_TYPES_BYPASS_REST_WF`) — no
  server-side validation or auto-population; our BFF must supply both.
- **Rich text**: memo fields store a full `<html><body>` document and are sanitized/re-formatted —
  compare canonicalized HTML, never bytes. Embedded images work: hand-built multipart upload with
  `ref-subtype=1`, then an absolute REST URL or `data:` URI as `<img src>` (bare/relative src is
  silently stripped). Parameter tokens must be entity-pre-encoded (`&lt;&lt;&lt;name&gt;&gt;&gt;`).
- **Verified working**: design-steps CRUD, requirement-coverages, req-traces (requirement↔requirement
  traceability), defect-links, milestones (parented under a **release**), release-cycle date
  validation, Site Admin user seeding (the API key holds Customer Admin).
- **Test parameters are fully REST-writable** (probe 9 — this **retracts** the long-held "no REST
  path defines a test parameter"). Two entities, not one: **`test-parameters`** (`TP_*`) *defines*
  a parameter; **`step-parameters`** (`SP_*`) *records a value* against one, and its `parent-id`
  is the **test-parameter id** — passing the design-step/test id there was the actual bug behind 5
  failed attempts. Create via `POST tests/{id}/test-parameters` with `name` + `ref-count` (parent
  comes from the URL), or let an entity-encoded `&lt;&lt;&lt;name&gt;&gt;&gt;` token in a design
  step register it. `PUT test-parameters/{id}` sets `default-value` — **which OTA cannot do**.
- **Unreachable via REST** (both reachable via the OTA sidecar): BPT/components (403) and
  similar-defects. Also absent: timeslots, libraries/baselines, alerts, follow-up flags, purge-runs.
  Audit history is **partial** — only some field changes are recorded.
- **OTA/COM WORKS against this sandbox** (probe 8; probe 7's "unreachable" verdict was wrong — it
  used a hand-extracted client). `InitConnectionWithApiKeyEx(url, clientId, secret)` authenticates
  with the **API key** — no username/password — and reads *and writes* fine. Requirements: a
  **32-bit host process**, a **version-matched client** (use ALM's own deployed client under
  `%LOCALAPPDATA%\HP\ALM-Client\<version>\`, not an installer payload), per-user COM keys written
  **from a 32-bit process** (WOW64), and a **separately registered typelib**.
- **BPT is writable via OTA** (probe 8) — REST's `403` on `/components` was **not** a licence gate.
  Recipe: component folder → subfolder → subfolder's `ComponentFactory` (components cannot sit
  directly under the root "Components" folder).
- **OTA test parameters** (superseded for practical purposes by the REST route above): `Test.Params`
  is a collection, not a factory; declaring directly does not persist, and both setting and *reading*
  a default value raise `Invalid field type definition`. **Use REST for parameters, not OTA.**
- ⚠️ **OTA folder deletes do not cascade to tests** — sweep by name prefix across `tests` *and*
  `test-folders` after any OTA cleanup (5 orphans were left behind during probing).
- **58% of the stock UI is reachable** via documented REST (feasibility matrix); 23 features are
  OTA-only, 21 are honest impossibilities.

## Skills — authored, in `.claude/skills/`

- `alm-api` — auth, session/XSRF, envelopes, query grammar, error codes, call recipes.
  **Load first in any session that touches ALM.**
- `alm-entity-model` — entities, field types, required/read-only rules, creation-order DAG,
  naming traps, runtime customization discovery.
- `alm-data-gen` — generator conventions: safety checklist, field-type→strategy matrix, rich-text
  grammar, provenance marking.
- `alt-alm-ui` — front-end conventions: metadata-driven form/grid patterns, renderer registry,
  design tokens, density, accessibility.
- `alm-live-probe` — safe live probing: read-only default, sandbox-only writes, masking, cleanup.

## Design decisions (see `docs/adr/`)

- **BFF is required**, not optional — browsers cannot call `/qcbin` (CORS + cookie session + XSRF).
  The BFF is the single enforcement point for the write hazards above (ADR 0001).
- **Stack: Java 25 (LTS) + Spring Boot 4.1.0** (BFF) and **React + TypeScript / Vite** (SPA); probe
  scripts stay PowerShell (ADR 0002). Spring Framework 7 recommends JDK 25+ for production; Boot 3.x
  predates JDK 25. No `--enable-preview`. **Boot 4 gotchas already hit** (all verified by building):
  the starter is `spring-boot-starter-webmvc` (not `-web`), test deps are **per-starter**
  (`spring-boot-starter-webmvc-test`) rather than one `spring-boot-starter-test`, **Jackson is not
  transitive** — add `spring-boot-starter-json` — and it is **Jackson 3**, whose package is
  **`tools.jackson.*`** (not `com.fasterxml.jackson.*`) with **unchecked** exceptions. Spring
  Initializr's `/metadata/client` reports legacy ids like `4.1.0.RELEASE`; the real artifact version
  is plain `4.1.0`.
- **OTA/COM is isolated in an optional Windows-only sidecar** — mainline never touches COM; features
  degrade behind capability flags when the bridge is absent (ADR 0003). The bridge has a **verified
  reachable target** (probe 8), but probe 9 cut its scope from three gaps to **two** (BPT components,
  similar-defects) — it no longer blocks the generator, so P6 is genuinely optional. Language
  decision (.NET vs Python + pywin32) is still open.
- **One service-account API key with pooled sessions**, plus Alt-ALM's own app-level user model; the
  licence finding retires the seat-consumption concern (ADR 0004).
- **Metadata-driven rendering** — no hardcoded schemas, list values, or root IDs anywhere (ADR 0005).

## Standing problems (not solved, just honestly scoped)

- Workflow-script bypass means Alt-ALM's own validation layer is the *only* validation — necessarily
  incomplete against arbitrary VBScript. Permanent, documented limitation.
- ⚠️ **Two confident negative verdicts have been overturned** (probe 7's "OTA unreachable" — a client
  artifact; probes 4–5's "no REST path defines a test parameter" — a wrong `parent-id` plus an
  unprobed sibling collection). Both were multiply-attempted and well-argued, and both failed the
  same way: **an unexamined assumption about the shape of the question, not too few attempts.**
  Before writing down any "X is impossible" verdict, re-read the per-instance `resource-list` for
  sibling collections and confirm every id in the body means what you assume it means.
- The whole evidence base is **one sandbox, one version**. Re-verify before trusting any probe
  finding on a different instance, especially on-prem.
- The sanitizer's allowed-HTML set is deployment-specific — re-verify per target.
- Fast_Run is a side effect, not a designed API; a future ALM release could change it. Isolate it
  behind one component.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
