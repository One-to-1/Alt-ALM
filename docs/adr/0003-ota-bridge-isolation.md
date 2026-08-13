# ADR 0003 — OTA/COM fallback isolated in an optional Windows sidecar

- Status: **Accepted — validated by a live spike; the bridge has a verified reachable target**
- Date: 2026-08-12 (addendum 2026-08-13)

## Addendum, 2026-08-13 — OTA is reachable; the sidecar is viable

Two spikes ran after this ADR was accepted. **The first one's negative verdict was wrong and is
retracted**; the second is authoritative (`live-probe-log.md`, **Probe 8**).

- **Probe 7 (2026-08-12) — RETRACTED.** It concluded "OTA cannot connect to our SaaS instance",
  blaming a 302 redirect to the SSO front door. The real cause was the **client**: the probe ran
  against a hand-extracted 4-DLL payload from the TDConnect installer.
- **Probe 8 (2026-08-13) — AUTHORITATIVE.** Using ALM's **own deployed client**
  (`%LOCALAPPDATA%\HP\ALM-Client\<version>\`), `InitConnectionWithApiKeyEx(url, clientId, secret)`
  returns `Connected/LoggedIn=True` and `Connect(domain, project)` returns `ProjectConnected=True`.
  **The API key authenticates OTA — no username/password, and SSO is not an obstacle.** Reads and
  writes both work (a test folder and test were created and deleted through OTA).

**What this buys, verified:**

1. **BPT is reachable AND writable over OTA** — a business component was created and deleted.
   REST's `403` on `/components` was **not** a licence gate; the OTA-side errors were structural
   ("Invalid owner specified", then "Components cannot be added directly under COMPONENTS folder").
   Recipe: component folder → subfolder → that subfolder's `ComponentFactory`.
2. **Test parameters**: `Test.Params` is a *collection* (`AddParam`/`Save`/`ParamName`/`Count`), not
   the `TestParameterFactory` that doc research predicted. Declaring a parameter directly does not
   persist; a **`<<<token>>>` in a design step registers it** (verified 0→1). Setting its default
   value still fails (`Invalid field type definition`) — `UNVERIFIED`.
3. **Every other OTA-only candidate factory is reachable**: Baseline, Library, Host, HostGroup,
   Milestone, KPI, ScopeItem, plus `PurgeRuns`, `SynchronizeFollowUps`, `AlertManager`,
   `ExtendedStorage`.

**The decision stands unchanged and is reinforced.** Isolation in an optional, capability-flagged
sidecar was right both when OTA looked dead and now that it works — the mainline never depends on
COM either way. Updated consequences:

1. **Bridge implementation is now schedulable** (phase P6 as planned), and the deferred
   **language decision (.NET vs Python + pywin32) is live again** — there is a real target to
   evaluate against. .NET's first-class COM interop (ADR 0002, criterion 4) is the leading candidate.
2. **The ~23 OTA-verdict features are back in scope**, gated only on building the sidecar.
3. **Hard environment constraints for the bridge** (all probe-verified): it MUST run as a **32-bit
   host process**; the client MUST be **version-matched** to the server; use ALM's **deployed
   client**, not an installer payload; per-user COM keys MUST be written from a 32-bit process
   (WOW64 redirection); and the **type library must be registered separately**, or every call fails
   `TYPE_E_ELEMENTNOTFOUND`.
4. **Cleanup semantics differ from REST**: an OTA folder delete does **not** cascade to the tests
   inside it. Any bridge operation that deletes containers must sweep children explicitly.
5. **Still `UNVERIFIED`**: how to set a test parameter's default value; whether a SaaS site parameter
   governs OTA access (`GET /v2/sa/api/site-params` → 403 even with Customer Admin).

## Context

`CLAUDE.md`'s hard constraints state the mainline design is documented-REST-only, but explicitly allow
COM/OTA as a fallback: "Can use COM/OTA APIs, i'll provide a tdconnect.exe if needed." `SESSION-STATE.md`
Phase-0 confirms this was elevated from "maybe" to an explicit scope note: "OTA/COM is now an allowed
fallback where REST has gaps... OTA is COM/Windows-only — architectural weight."

Research and write-probing narrowed this from a general concern to three **specific, verified-genuine**
REST gaps, each with a documented negative-result trail rather than an assumption:

1. **Test-parameter definition.** `step-parameters` POST fails identically across every attempted
   shape — 2 attempts in round 1, 3 more in round 2 (5 total, both nested and standalone paths, both
   `used-by-owner-type` values) — all returning `HTTP 500 "Test parameter does not exist"` (api-ref
   §6.4, data-model §6, §9). The physical field name `SP_TEST_PARAM_ID` on `step-parameters.parent-id`
   indicates this endpoint records a *value against an already-registered parameter*, not a "define a
   new parameter" operation — and **no REST-exposed entity or endpoint anywhere in this project's Core
   API surface creates that underlying object** (data-model §6). This is confirmed a genuine gap, not
   a shape bug: even after HTML-entity-encoded `<<<name>>>` tokens successfully flipped
   `has-params="Y"` on a design-step, referencing that same parameter via `step-parameters` still
   failed with the identical error (api-ref §6.4).
2. **BPT components.** `GET /components` → **403 `qccore.operation-forbidden`** (endpoint exists,
   license/permission-gated on this target); `GET /business-components` → **404** (data-model §2.9,
   api-ref §6.7b). BPT composition, component steps/parameters, and Flows have no REST creation surface
   at all in the UI-inventory research either (`alm-ui-feature-inventory.md`: "zero REST collection
   pages found despite targeted search").
3. **Similar-defects.** Explicitly disclaimed as unsupported/undocumented by ALM's own
   `resource-list.html` — the vendor's own resource-list page states undocumented resources are not
   supported (api-ref §6.5) — and independently corroborated as OTA-only by documentation research
   (`SESSION-STATE.md`).

OTA/COM is Windows-only by nature — `TDConnection`/COM automation is a Windows API family with no
cross-platform equivalent — which is why `_lead-decision-brief.md` D3 flags it as "architectural
weight" rather than a free capability to reach for broadly. ADR 0002 separately establishes that the
mainline BFF stack is chosen partly *because* it does not need to carry COM interop (Java scored 2/5 on
that criterion there, deliberately) — this ADR is the reason that tradeoff is safe to make.

## Decision

OTA/COM capability is confined to a small, optional **"OTA bridge" sidecar service** — a separate
Windows-only process exposing a minimal internal HTTP API, reachable only from the BFF (never from the
SPA or the public network — `architecture.md` §1 diagram: internal HTTP, localhost/LAN only). It covers
**exactly the three gaps above and nothing else**:

- test-parameter *definition* (OTA `TestParameterFactory`, per `_lead-decision-brief.md` D3)
- BPT components (OTA `BusinessComponentFactory`-family objects)
- similar-defects (OTA-only per `resource-list.html`'s own disclaimer)

Implementation language is deferred until the user supplies `tdconnect.exe` (candidate languages named
in D3: .NET, for the strongest COM interop per ADR 0002's own comparison table where .NET scored 5/5 on
that criterion; or Python + `pywin32`, scored 3/5 there — a workable second choice, not a placeholder).
This is a small, isolated decision independent of the mainline BFF's Java choice — the bridge is a
separate deployable with its own stack, chosen for COM fit, not for consistency with the BFF.

The BFF treats bridge presence as a **runtime capability flag**
(`architecture.md` §2.2, `otaBridgeAvailable`), probed via a health-check at startup and periodically
thereafter — never assumed present. Absent bridge → the three covered features degrade to an explicit
"unavailable" UI state (§ Consequences), never a silent no-op or a crash; the mainline product (Alt-ALM
minus these three features) works fully without it.

## Consequences

- **Mainline stack never touches COM.** This is the direct payoff of isolation: ADR 0002's Java choice
  does not need to defend its COM story, because it never has one to defend. If OTA needed to be
  in-process, .NET would likely have won ADR 0002 outright (it scored highest there specifically on
  COM interop) — isolating OTA is what keeps that from being a forcing function on the mainline stack.
- **Windows becomes a soft dependency, not a hard one.** The BFF can run wherever the JVM runs; only
  the bridge (and, by extension, the three features it covers) requires Windows. This matches D3's
  explicit intent: mainline works fully without the bridge.
- **Three named, narrow surfaces — not a general escape hatch.** Any future "REST can't do X, let's use
  OTA" request must clear the same bar these three did: a genuine, multiply-attempted, documented
  negative result (§ Context), not convenience. This keeps the sidecar small and keeps `CLAUDE.md`'s
  "documented REST API only... an undocumented endpoint that unlocks something valuable is a
  risk-register entry, not an implementation" discipline intact for the OTA boundary too — OTA is an
  explicitly *allowed* exception per `CLAUDE.md`, but the exception is scoped, not open-ended.
- **Operational cost**: a second deployable, a second process to keep alive, and — because it is
  Windows/COM-specific — likely a different release/ops story (Windows service install, `tdconnect.exe`
  provisioning) than the mainline JAR. Accepted because the alternative (no OTA at all) means shipping
  Alt-ALM with three permanently-broken features that the user has explicitly said should be reachable.
- **Capability-flag UI discipline is now load-bearing.** Every surface touching these three features
  must check `otaBridgeAvailable` (or the more specific `bptLicensed` flag, since BPT is additionally
  gated by license/permission independent of bridge presence — `architecture.md` §2.2) and degrade
  gracefully. A feature silently failing because the bridge happens to be down is exactly the kind of
  gap `CLAUDE.md` asks to be recorded honestly, not hidden.

## Alternatives considered

- **In-process JNI or JACOB-style COM bridge inside the mainline JVM.** Rejected: ADR 0002 scored this
  approach 2/5 on COM interop specifically because JNI/JACOB bridges are a known-fragile, low-adoption
  path relative to native COM interop in .NET. Embedding it would also force the entire mainline BFF
  onto Windows to support three narrow features, contradicting the "mainline works fully without OTA"
  requirement in D3.
- **No OTA at all — accept the three gaps as permanent product limitations.** Rejected: `CLAUDE.md`
  explicitly allows COM/OTA as a fallback and the user has committed to providing `tdconnect.exe`; three
  verified, non-shape-bug REST gaps (test-parameter definition, BPT components, similar-defects) are a
  real, named product cost if left unaddressed, and the isolation strategy here removes the strongest
  objection (COM contaminating the mainline stack) at an acceptable operational cost.
- **A broader OTA bridge covering more than the three named gaps** (e.g., using OTA as a general
  alternative to REST wherever REST is awkward, not just where it is *absent*). Rejected: this would
  reintroduce the undocumented-endpoint risk `CLAUDE.md` warns against in a different guise — OTA
  capability creeping in as a convenience rather than a documented, multiply-verified gap-filler. The
  three-gap scope is deliberately narrow and revisited only when a future gap clears the same
  multiply-attempted-negative-result bar.
