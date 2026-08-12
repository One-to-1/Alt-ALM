# Lead decision brief — plan-set drafting input (2026-08-12)

Authored by the Fable lead session. This file records the **decisions** the plan documents must be
written around. Drafting agents elaborate, structure, and justify these decisions against the
research corpus (`docs/research/`) — they do not overturn them. Where a decision says "compare
honestly," the comparison must be genuine, with real trade-offs, but the stated conclusion stands
unless the agent finds a verified fact that contradicts it (flag, don't silently change).

## D1 — BFF proxy: required, not optional

A browser cannot call `/qcbin` directly (CORS + cookie-session auth + XSRF on writes; probe-verified
auth mechanics in `alm-api-reference.md` §2–3). Alt-ALM is therefore a **backend-for-frontend
architecture**: SPA → Alt-ALM BFF (own clean JSON API) → ALM Core REST. The BFF is also the single
enforcement point for every probe-derived client hazard:

- deterministic `Fields`-array serialization order (§3.2 — hard requirement, NPE 500s otherwise)
- 5xx = "unknown outcome, verify by query" retry discipline (§3.3 — 500s can silently commit)
- XSRF header injection, session keepalive, Accept-header discipline
- bulk 409 per-item result parsing; page-size caps (2000 silent cap)
- runtime root discovery + per-project metadata caching (never hardcode roots or List-Ids)

## D2 — Stack: Java 21 + Spring Boot BFF; React + TypeScript SPA

ADR must compare Java vs TS/Node vs Python vs .NET honestly. Decision drivers, in order:
1. User preference is Java (stated, Phase-0 decision table) — a legitimate tiebreaker, stated as such.
2. The domain is metadata-heavy with a fussy wire contract: strong static typing + Jackson's
   explicit `JsonNode`/`LinkedHashMap`-based ordered serialization suit the deterministic-field-order
   requirement; Apache HttpClient 5 gives byte-level multipart control (probe-proven necessity: the
   server rejects some library-built multipart bodies — PS7 `-Form` failed, hand-built worked).
   **Whatever stack is chosen, multipart construction must be integration-tested against the real
   server** — this is a named risk, not an assumption.
3. Long-lived session pooling/keepalive scheduling is bread-and-butter Spring.
4. .NET's superior COM interop for OTA is real but does not outweigh 1–3 because OTA is isolated in
   a sidecar (D3) — the mainline stack never touches COM.
5. Node's one-language appeal is real (SPA is TS regardless) but loses on 1 and 2.
Frontend: React + TypeScript SPA, metadata-driven rendering (D5). Probe/ops scripts stay PowerShell.

## D3 — OTA fallback: isolated Windows-only sidecar, optional at runtime

OTA/COM (Windows-only) is confined to a small **"OTA bridge" sidecar service** exposing a minimal
internal HTTP API for the verified REST-unreachable operations only:
- test-parameter *definition* (step-parameters REST gap — verified genuine, 5 failed shapes)
- BPT components (403 license-gated / 404 on this server)
- similar-defects (OTA-only per docs)
Implementation language decided when the user supplies tdconnect.exe (candidates: .NET, best COM
interop; or Python + pywin32). The BFF treats the bridge as an optional capability: absent bridge →
features degrade gracefully behind capability flags, mainline works fully without it. This is an ADR.

## D4 — Session model: single service-account key, pooled sessions, app-level users

- BFF holds **one service-account API key** (from `Secrets/`), maintains a pooled ALM session with
  keepalive (`PUT site-session`; idle timeout default 60 min). REST sessions consume **no licence
  seat** (doc-verified) — the seat-consumption concern from the charter is retired; note it.
- Alt-ALM has its **own app-level user model** (own auth; SSO later). ALM-side attribution of writes
  is therefore the service account — an honest, documented limitation (ALM's history shows a
  client-type column; our writes are visibly REST-attributed anyway). Record fields like
  `detected-by`/`owner` are still set per app-user by the BFF, which restores most practical
  attribution.
- The session manager sits behind an interface so **per-user API keys** can be added later without
  redesign (ADR records this as the explicit evolution path).
- Workflow-script bypass (`CLIENT_TYPES_BYPASS_REST_WF` default): REST writes skip stock validation
  → the BFF adds its own validation layer built from runtime metadata (Required/Editable/List
  bindings), because the server won't catch what the stock client would have.

## D5 — Metadata-driven UI: no hardcoded schemas, ever

Forms, grids, filters render from runtime-fetched customization metadata (fields, 8 types, lists,
users, requirement types, subtypes), cached per project with explicit invalidation. Roots discovered
at runtime (`?query={parent-id[0]}`); the user-supplied default root table is a documented sanity
check only. The 8-type system (no Boolean; Y/N = LookupList list-id 1) drives one field-renderer
registry. UDFs (`user-NN`) render automatically from the same metadata path.

## D6 — Generator: integrated module, safety-first, DAG-ordered

One product (user decision): the generator is a module inside Alt-ALM (BFF-side engine + UI panel).
Non-negotiables for the spec:
- **Dry-run by default**; writes only against an explicit allowlist of domain/project pairs;
  refuse otherwise. Seedable PRNG → reproducible datasets.
- Creation order = the verified DAG (`alm-data-model.md` §2.11): releases/cycles/milestones →
  requirements (+traces) → test tree (tests, design-steps w/ entity-encoded `&lt;&lt;&lt;param&gt;&gt;&gt;`
  tokens) → coverage → test-lab chain → runs **via Fast_Run synthesis only** (direct POST runs is a
  verified dead end) → defects + links.
- Field-type→strategy matrix from the 8-type system; respect Required/Editable/System=191-read-only;
  cycle dates inside parent release window (server-enforced); only 2 multivalue fields exist.
- Rich text: full `<html><body>` docs; canonicalized-HTML comparison (sanitizer normalizes);
  embedded images via multipart `ref-subtype=1` + absolute-URL or `data:` URI `<img src>`.
- Provenance marking: configurable name prefix (default `ALTALM-GEN-<runid>`) on every record;
  cleanup command that sweeps by prefix query; generator refuses to delete non-prefixed records.
- User seeding via SA API (Customer Admin verified) so UsersList fields don't degenerate.
- step-parameters: skipped unless OTA bridge present (capability flag).

## D7 — One write-safety client component

All ALM writes (UI-originated and generator-originated) flow through a single client component
implementing: ordered serialization, XSRF, 5xx-verify-by-query, retry-with-dedup-check, bulk
partial-failure parsing, masking of secrets in logs. One implementation, one test suite.

## Phasing skeleton for implementation-plan.md

- **P0 Foundations**: repo scaffolding, CI, BFF skeleton, auth/session manager, metadata service,
  fixtures-based test harness (redacted fixtures already exist in `tests/fixtures/`).
- **P1 Read-only Alt-ALM**: metadata-driven grids/forms for requirements, tests, defects; query
  builder (Core grammar), paging; tree navigation with runtime root discovery.
- **P2 Write core**: write-safety component; CRUD for requirements/tests/design-steps/defects;
  coverage, traceability, defect-links; BFF validation layer.
- **P3 Test Lab + planning**: test-set tree, instances, Fast_Run execution flow, run-steps UI;
  releases/cycles/milestones.
- **P4 Generator MVP**: DAG engine, dry-run/allowlist/seed, provenance + cleanup, user seeding.
- **P5 Rich content**: rich-text editor with sanitizer-aware round-trip, embedded images,
  attachments UI; generator rich-text/image strategies.
- **P6 Optional + hardening**: OTA bridge, version-control (check-in/out) support, favorites,
  audits view (partial-coverage caveat), deferred-probe follow-ups.

Each phase ends with contract tests against the sandbox (probe conventions: `ALTALM-*` prefix,
cleanup in finally, orphan sweep).

## Constraints carried from CLAUDE.md (restate in every plan doc's header)

Documented REST only (undocumented → risk register); never touch `Secrets/` content; sandbox-only
writes with allowlist; UNVERIFIED labelling discipline; probe log wins conflicts.
