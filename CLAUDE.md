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

- **Documented REST API only.** No OTA/COM API, no direct database access, no scraping the stock web
  client, no undocumented internal endpoints in the mainline design. An undocumented endpoint that
  unlocks something valuable is a risk-register entry, not an implementation.
- **Never commit, print, log, or forward `Secrets/`.** It is git-ignored.
  `Secrets/ALM_API_credentials.json` holds the ALM credentials and currently contains placeholders
  only; its schema is provisional until the auth method is settled. Reference it by path.
- **Never write to a live ALM project** unless the user has explicitly designated it a sandbox.
  Default to read-only probing. The record generator is dry-run by default and must refuse any
  target not on an explicit allowlist.
- **Never invent API behaviour.** Unverified claims get labelled `UNVERIFIED` with the experiment
  that would confirm them. A marked unknown is fine; a confident fabrication is not.

## Repository layout

| Path | Contents |
|---|---|
| `CLAUDE.md` | This file — durable project context. |
| `docs/prompts/` | Kickoff prompts for agent sessions. |
| `docs/research/` | Verified findings about the ALM API, UI, and data model. |
| `docs/plan/` | Architecture, implementation plan, generator spec, test strategy, risks. |
| `docs/adr/` | Architecture Decision Records. |
| `.claude/skills/` | Reusable skills for ALM work (see below). |
| `Secrets/` | Git-ignored credentials. Never read into a document. |

## Current status

**Phase: research and planning — not started.**

The research and planning session is driven by
[docs/prompts/fable-5-research-and-plan.md](docs/prompts/fable-5-research-and-plan.md) — a kickoff
prompt for a Claude Fable 5 session. It directs that session to research the ALM REST API and stock
UI exhaustively (fanning out to Sonnet subagents), produce a feature→API feasibility matrix, and
write the architecture, phased implementation plan, record-generator specification, and test
strategy. It also directs that session to author the skills listed below and to extend this file
with what it verifies.

Nothing below the planning artifacts exists yet. There is no application code.

## Skills (to be authored during the planning session)

- `alm-api` — auth handshake, session/XSRF handling, request envelopes, query syntax, error codes,
  call recipes. Load this first in any session that touches ALM.
- `alm-entity-model` — entity relationships, field types, required/read-only rules, creation order,
  runtime customization discovery.
- `alm-data-gen` — generator conventions: field-type→strategy matrix, rich-text block grammar and
  accepted markup subset, distribution defaults, provenance marking, pre-write safety checklist.
- `alt-alm-ui` — front-end conventions: design tokens, density, metadata-driven form/grid patterns,
  accessibility.
- `alm-live-probe` — safe live-instance probing (read-only default, sandbox-only writes, capturing
  redacted fixtures).

## Known design problems to solve, not assume away

- A browser almost certainly cannot call `/qcbin` directly — expect CORS plus cookie-based session
  auth plus XSRF on writes. A backend-for-frontend proxy that owns the ALM session is the likely
  answer; it needs an ADR either way.
- ALM field metadata is **per project and customizable**. Forms and grids must render from metadata
  fetched at runtime, never from hardcoded schemas.
- Workflow scripts (VBScript) change field behaviour in the stock client in ways the REST API does
  not expose. Some stock-UI behaviour is genuinely unreachable; record those gaps honestly.
- Sessions consume licence seats. The session model (shared vs. per-user, pooling, keepalive) is a
  real architectural decision.
- Rich-text/memo fidelity through the API is load-bearing for the generator and must be verified by
  round-trip against a real instance.
