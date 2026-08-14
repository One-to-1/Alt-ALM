# ADR 0004 — Session model: single service-account key, pooled sessions, app-level users

- Status: Accepted
- Date: 2026-08-12

## Context

`CLAUDE.md` names the session model as a real architectural decision, originally framed around a
licence-seat concern: "Sessions consume licence seats. The session model (shared vs. per-user, pooling,
keepalive) is a real architectural decision." Two things settle most of that framing:

1. **Auth method is fixed at Phase-0**: API key only (client ID + secret), confirmed in
   `SESSION-STATE.md`'s Phase-0 decision table — not username/password, not SSO.
2. **The licence-seat premise is retired by a doc-verified finding**: "REST sessions consume no ALM
   licence seat" — sourced from the `site-session` resource page's own defaults note ("No licenses
   consumed" for a POST-created session) and independently corroborated by OpenText community staff
   (api-ref §2.2). Sessions remain **visible** under Site Administration → Site Connections, for
   monitoring only, not seat accounting (api-ref §2.2). This means the original worry — that a
   per-user session model might burn through a limited licence pool — does not apply to REST sessions
   at all; the charter's stated concern is real *for the desktop/web client*, but does not transfer to
   Alt-ALM's REST-only session model.

What remains a genuine open question, once the licence angle is closed: **whose identity does a write
carry, and does that matter?** Two more research findings bear on this:

- API keys map to a real ALM user account and inherit that user's exact permission scope; deleting or
  deactivating the underlying user deletes its keys (api-ref §2.4). A single service-account key is
  therefore a single, real, revocable identity — not an anonymous credential.
- The stock client's History tab has a client-source column (`SHOW_CLIENT_SOURCE=Y`) whose values
  include "Web Client UI"/"Desktop Client UI"/"Unknown Client" (`alm-ui-feature-inventory.md`) — ALM
  already visibly attributes writes by *client type*, not just by user. A REST-attributed write from
  Alt-ALM is already going to look different from a Web/Desktop Client write regardless of how many ALM
  users Alt-ALM's BFF authenticates as.
- Workflow-script validation is bypassed for REST writes by default
  (`CLIENT_TYPES_BYPASS_REST_WF`, api-ref §6.8) — an orthogonal but adjacent consequence of the auth/
  session model choice being REST-based at all, restated here because it drives the validation-layer
  consequence below.

## Decision

- The BFF holds **one service-account API key** (read from `Secrets/`, never logged or forwarded per
  `CLAUDE.md`) and maintains **one pooled ALM session per configured target**, kept alive via
  `GET`/`PUT site-session` before the idle timeout (`REST_SESSION_MAX_IDLE_TIME`, default 60 minutes,
  api-ref §2.2) elapses.
- **The licence-seat concern from the charter is retired** for this model, citing api-ref §2.2 above —
  recorded here explicitly so a future reader does not reintroduce per-user sessions purely to solve a
  problem that does not exist for REST.
- Alt-ALM has its **own, independent app-level user model** (its own authentication; SSO is a later
  concern, out of scope for this ADR). Alt-ALM users are not ALM users and do not each get their own ALM
  session or API key in this iteration.
- **ALM-side attribution of writes is therefore the single service account**, honestly documented as a
  limitation, not hidden: every Alt-ALM-originated write is visibly REST-attributed to one ALM identity.
  This is judged an acceptable, *already-partially-true* limitation given ALM's own client-type history
  column (§ Context) — Alt-ALM writes were never going to be indistinguishable from Web/Desktop Client
  writes anyway.
- **Practical attribution is restored at the Alt-ALM layer**: record fields with real semantic meaning
  in the ALM data model — `detected-by` on defects, `owner`-style fields elsewhere — are still set
  per-Alt-ALM-user by the BFF on every write, even though the underlying ALM session/API-key identity is
  shared. This is not full identity federation, but it recovers the field-level attribution that most
  ALM workflows (defect ownership, requirement authorship display) actually depend on day-to-day.
- **The session manager sits behind an interface** (`architecture.md` §2.2) specifically so a
  **per-user API-key mode can be added later without a redesign** — this is the named evolution path,
  not a hypothetical: if a future requirement demands true ALM-side per-user attribution (e.g. an
  enterprise customer whose compliance policy requires it), the session manager's pooling/keepalive
  logic is reused unchanged, only the "one key" assumption is relaxed to "one key per Alt-ALM user,"
  each with its own pooled session.
- **Workflow-script bypass drives a mandatory consequence, not a mere note**: because
  `CLIENT_TYPES_BYPASS_REST_WF` means REST writes skip the stock client's validation scripts by default
  (api-ref §6.8), the BFF's validation layer (`architecture.md` §2.2) must independently enforce the
  Required/Editable/List-binding constraints those scripts would otherwise have caught, built from the
  same runtime metadata the renderer registry uses. This is a direct consequence of choosing a
  REST-only session model at all (any session model built on REST inherits this bypass), restated here
  because the session-model decision is where a reader would look for "what does the server no longer
  protect us from."

## Consequences

- One ALM session (per target) for the whole Alt-ALM deployment to manage — simpler pooling/keepalive
  code than a per-user model, and no seat-budget capacity planning needed (retired concern, above).
- A single credential is a single blast radius: if the service-account key is compromised, every
  Alt-ALM user's write capability is compromised together. Mitigated by `Secrets/` never being logged/
  forwarded (`CLAUDE.md`) and by the key being independently revocable/rotatable at the ALM Site Admin
  layer without redeploying Alt-ALM (api-ref §2.4 — keys are Site-Admin-managed).
- ALM's own audit/history views will show one actor for all Alt-ALM writes, which is an honest,
  documented gap versus per-user desktop/web attribution — explicitly called out in
  `architecture.md` §5 rather than glossed over.
- The BFF's validation layer becomes non-optional, not a nice-to-have: without it, REST writes from
  Alt-ALM would bypass exactly the checks the stock client relies on workflow scripts for
  (api-ref §6.8), and there is no server-side backstop to catch the gap.
- Future per-user-key evolution is possible without redesigning the session manager, but is explicitly
  **not** committed to in this iteration — this ADR records the interface boundary that makes it
  possible, not a roadmap promise.

## Alternatives considered

- **Per-user ALM sessions/API keys from day one** (one key per Alt-ALM user, each independently
  authenticated against ALM). Rejected for this iteration: requires either provisioning an ALM API key
  per Alt-ALM user up front (an ALM Site Admin operation per user, `APIKEY_MAX_NUM_PER_USER` default 10
  per user per api-ref §2.4) or building an Alt-ALM-to-ALM identity-mapping layer neither requested nor
  scoped at Phase-0. Now that the licence-seat objection is retired, the *cost* argument against
  per-user sessions weakens, but the *complexity* argument (credential provisioning, key lifecycle
  management per Alt-ALM user) still favors starting with one service account and evolving later — the
  explicit named path above.
- **Shared session with no distinct Alt-ALM-side user model at all** (Alt-ALM as a thin, unauthenticated
  pass-through to a single ALM identity). Rejected: this would lose all practical attribution, including
  the field-level `detected-by`/`owner` recovery this ADR relies on, and would remove the ability to
  scope generator allowlist checks or UI permissions per human operator — a strictly worse position than
  the decision above at no offsetting benefit.
- **SSO-federated identity from day one.** Deferred, not rejected outright: `SESSION-STATE.md` Phase-0
  explicitly notes "own auth; SSO later," and SSO federation is a materially larger scope addition
  (identity-provider integration, ALM-side SSO configuration which itself has separately-documented,
  lower-confidence session-timeout behaviour — api-ref §2.2's `SSO_EXPIRATION_TIME` note is `UNVERIFIED`)
  that does not belong in the same decision as "should Alt-ALM pool one service-account session."

## Addendum 1 — one test seat confirms the single-service-account decision (2026-08-13)

**New constraint from the user: the ALM instance has only ONE user seat available for testing.**

This is recorded because it settles the "evolve to per-user sessions later" path's near-term status,
and because it arrived alongside a hosting question that briefly made per-user credentials look
attractive (each browser supplying its own ALM key, so no shared secret sits on a server).

- **The decision above is unchanged and now better supported.** With one seat there is exactly one ALM
  identity to pool sessions for, so the service-account model is not merely the simpler choice — it is
  the only one that can be exercised at all.
- **Per-user credentials are now UNTESTABLE, not merely unscoped.** Any multi-identity behaviour —
  per-credential pool keying, per-user attribution in `detected-by`/`owner`, permission differences
  between users — cannot be verified on this instance. Building it would mean shipping unexercised
  code paths, which this project's standing rule treats as a fabrication risk, not a head start.
- **What does NOT change**: probe 10's finding that one API key holds 50+ concurrent sessions with no
  licence seat consumed. Seats and REST sessions are different resources — one seat does not mean one
  session, and the pool's bound remains our own politeness limit.

**If per-user credentials are revisited** (the natural trigger is a second seat, or a real multi-user
deployment), the shape is already known and should be written down before it is built: the ALM secret
is posted **once** over TLS to the BFF, held **in memory only**, and the browser receives the BFF's
own `HttpOnly; Secure; SameSite=Strict` session cookie — never the ALM credential itself. Storing an
ALM key in browser-readable storage is rejected outright on three independent grounds: cookies are
origin-scoped so ALM would never receive it anyway; a JS-readable cookie is exposed to any XSS or
compromised dependency; and an ALM API key inherits its user's full permission scope rather than being
a scoped token (api-ref §2.4).
