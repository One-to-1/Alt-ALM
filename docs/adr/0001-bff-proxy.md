# ADR 0001 — Backend-for-frontend proxy, not direct browser access

- Status: Accepted
- Date: 2026-08-12

## Context

Alt-ALM is a new web front end for OpenText ALM/Quality Center's `/qcbin` REST API. The first
architectural fork is whether the SPA calls `/qcbin` directly from the browser, or whether a
server-side component sits between them.

ALM's session model, verified against our ALM 26.1 sandbox (`docs/research/alm-api-reference.md`
§2, hereafter api-ref):

- `POST /qcbin/rest/oauth2/login` with `{"clientId","secret"}` sets a **full cookie set in one call**
  — `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`, `ALM_USER`, `JSESSIONID` (api-ref §2.1). This is a
  server-held session, not a bearer token a browser could attach to cross-origin requests cleanly.
- Every non-GET call requires an `X-XSRF-TOKEN` header echoing the `XSRF-TOKEN` cookie value; a POST
  without it returns **401** with a documented `qccore.general-error` body, reproduced identically
  across every probe run (api-ref §2.2). This is a same-origin-style double-submit pattern that a
  cross-origin SPA cannot satisfy without either exposing the ALM host's cookies to browser JS
  (defeating `HttpOnly` protections the vendor may rely on) or re-implementing a cookie-jar proxy
  anyway.
- No CORS allowance for arbitrary third-party origins is documented or observed; ALM's web client is
  served from the same origin as `/qcbin` by design.
- API keys inherit the underlying user's full permission scope (api-ref §2.4) — embedding one in
  browser-reachable code would leak a credential with real write access to the target ALM project.

`CLAUDE.md` and `_lead-decision-brief.md` D1 both anticipate this: "A browser almost certainly cannot
call `/qcbin` directly" is stated as a known design problem to solve, not assume away, and D1 records
the BFF as the answer, with the specific probe-derived hazards it must own.

## Decision

Alt-ALM is a **backend-for-frontend (BFF) architecture**: SPA → Alt-ALM BFF (its own clean JSON API,
app-level auth) → ALM Core/Deprecated/v2/SA REST. The BFF is the **only** component that ever holds
ALM session cookies, the service-account API key, or constructs `/qcbin` requests. It is also the
single enforcement point for every probe-derived client hazard, restated from `architecture.md` §2.2:

- Deterministic `Fields`-array serialization order on every write (api-ref §3.2 — wrong order produces
  opaque NPE-style 500s on otherwise-identical data).
- 5xx-on-write = "unknown outcome, verify by query" — never "failed" (api-ref §3.3, a 500 was observed
  to have silently committed a row).
- XSRF header injection, session keepalive (`GET`/`PUT site-session` before the 60-minute idle
  timeout, api-ref §2.2), and `Accept`-header discipline (missing/wrong `Accept` returns a branded HTML
  error page instead of parseable JSON, api-ref §3.4).
- Bulk-write 409 partial-failure parsing, per-item (api-ref §4.5).
- Runtime root/List-Id discovery — never hardcoded (api-ref §6.1, §6.8).

None of this is enforceable client-side in a browser without duplicating the BFF's logic in every
consumer and still failing on the credential-exposure and same-origin/XSRF problems above.

## Consequences

- One extra network hop and one extra deployable component versus a "thin static SPA talking straight
  to ALM" design — accepted as the cost of the credential and session-cookie problem being otherwise
  unsolvable for a third-party browser client.
- The BFF becomes the sole owner of ALM sessions; its availability is now a dependency for every
  Alt-ALM feature, including read-only ones. This is judged acceptable because ALM itself requires a
  live session for reads too — there is no meaningfully more "direct" path being given up.
- Alt-ALM gets to define its own clean API contract for the SPA, decoupled from ALM's `Fields`/`values`
  envelope quirks (api-ref §3.1) and dual query-grammar generations (api-ref §4) — the SPA never has to
  learn ALM's wire format at all.
- The BFF is a single point of enforcement for the write-safety hazards above, which is the whole
  point of D7 (`0007` is folded into `architecture.md` §2.2 rather than getting its own ADR, since it
  is a direct consequence of this decision, not an independent fork).

## Alternatives considered

- **Direct browser → `/qcbin`.** Rejected: no CORS story, cookie-based session with `HttpOnly`-style
  protections a browser client cannot faithfully reproduce, and API-key credential exposure in
  client-shipped code. Would also push every write hazard in `architecture.md` §2.2 into the SPA,
  duplicated per client.
- **A thin reverse proxy (no business logic) instead of a full BFF.** Rejected: a reverse proxy that
  merely forwards cookies/headers still cannot own the deterministic-field-order and 5xx-verify
  requirements, which need request/response inspection and retry logic, not just header pass-through.
  It would also still expose the raw ALM `Fields`/`values` envelope to the SPA, undermining the "clean
  API" benefit above.
- **A serverless/edge-function layer instead of a long-lived server process.** Rejected at this stage:
  session pooling and keepalive scheduling (ADR 0002, ADR 0004) are naturally long-lived-process
  concerns; a stateless-per-invocation model would need external session storage for no offsetting
  benefit given the BFF's other duties (metadata caching, generator engine).
