# ADR 0002 — BFF stack: Java 25 + Spring Boot; SPA: React + TypeScript

- Status: Accepted (JDK baseline revised 2026-08-13: **21 → 25**)
- Date: 2026-08-12 (addendum 2026-08-13)

## Addendum, 2026-08-13 — JDK baseline moves to 25 LTS

The original decision named **Java 21** simply because it was the prevailing LTS when this ADR was
written. **JDK 25 (September 2025) is now the current LTS**, and the user is installing it. The
baseline moves to **Java 25**. No part of the comparison below changes — the criteria that selected
Java over TypeScript/Python/.NET are language-ecosystem properties, not version-specific ones.

This is a strict improvement rather than a neutral bump, because the Spring side actively prefers it:

- **Spring Framework 7** fully tests and supports the JDK LTS line — **17, 21, and 25** — with
  intermediate releases (22/23/24) on a best-effort basis only, and **recommends JDK 25 or higher for
  production use**.
- **Spring Boot 4.0** requires Java 17 minimum and supports **up to Java 25**; **Spring Boot 4.1**
  extends that to Java 26.

So Java 25 + Spring Boot 4.x is a fully-tested, vendor-recommended pairing rather than a
best-effort one. **Pin Spring Boot 4.0.x or later** in P0 — Spring Boot 3.x predates JDK 25 and is
not the right baseline for a greenfield build here.

Consequences: none for architecture. P0 sets the Maven/Gradle toolchain to release 25. Language
features newer than 21 (and any preview features) are **not** to be adopted merely because they are
available — the `--enable-preview` flag stays off, so the build never depends on a feature that can
change between releases.

## Context

ADR 0001 establishes that a BFF is required. This ADR picks its implementation stack. Per
`_lead-decision-brief.md` D2 and `docs/research/SESSION-STATE.md`'s Phase-0 decision table ("Stack:
Compare all options seriously — user leans Java"), the comparison must be genuine: real trade-offs
across four realistic candidates, scored against criteria that matter for *this* BFF's actual job, not
a generic web-backend comparison.

What this BFF's job actually demands, per `architecture.md` §2.2 and the research corpus:

1. **Wire-contract control.** ALM's `Fields` array write order is behaviourally load-bearing — the
   same logical data in a different JSON member order produces different server outcomes, including
   opaque 500s (api-ref §3.2). The BFF's serialization layer must guarantee deterministic member order
   on every write, which rules out relying on a language/library's default map-iteration order.
2. **Multipart byte-level control.** The embedded-image upload path (`ref-subtype=1`) is
   probe-proven client-library-sensitive: a PowerShell `-Form`-built multipart body was rejected by
   the server with an opaque parse error, while a hand-built body (explicit boundary, CRLF discipline,
   `file` part last) succeeded 3/3 sessions (api-ref §6.6, data-model §6). Whatever HTTP client the BFF
   uses for multipart must be capable of exact byte-level control, or must be individually
   integration-tested and possibly bypassed with a hand-rolled body writer.
3. **Long-lived session pooling/keepalive.** The session manager (ADR 0004) holds a pooled session per
   target and must run a keepalive scheduler against `REST_SESSION_MAX_IDLE_TIME` (default 60 min,
   api-ref §2.2) for the life of the process.
4. **COM interop for the OTA bridge.** ADR 0003 isolates OTA/COM in a separate sidecar — but the choice
   of mainline stack still interacts with that decision, because a mainline stack with native COM
   support could in principle have absorbed the bridge in-process instead.
5. **Metadata-driven UI backend ecosystem.** The domain is metadata-heavy (D5): forms/grids/filters
   render from runtime-fetched field descriptors across 8 field types (api-ref §8), not fixed schemas.
   The backend needs comfortable JSON tooling for a schema that is discovered, not declared.
6. **Team preference.** The user has stated a leaning toward Java (`SESSION-STATE.md` Phase-0 table).
   This is treated as a legitimate tiebreaker among options that are otherwise close, not as an
   overriding factor that excuses a poor fit — the criteria below are scored honestly first.

## Decision

**BFF: Java 25 (LTS) + Spring Boot 4.x.** **SPA: React + TypeScript**, metadata-driven rendering (D5). Probe/ops
scripts remain PowerShell (matching the existing `scripts/probe/*.ps1` convention), independent of this
decision.

### Criteria comparison

Scored 1 (poor fit) – 5 (strong fit) per criterion, with notes grounded in the demands above, not
generic language reputation.

| Criterion | Java + Spring Boot | TypeScript + Node | Python | .NET (C#) |
|---|---|---|---|---|
| Wire-contract / deterministic ordering | **5** — Jackson's `JsonNode`/`ObjectNode` (backed by `LinkedHashMap`) gives explicit, guaranteed insertion-order serialization; the deterministic-field-order requirement maps directly onto an idiomatic Jackson pattern | 4 — `JSON.stringify` over a plain object preserves insertion order in modern V8/spec-defined semantics, workable but relies on object-literal construction discipline rather than an explicit ordered-map API | 3 — `dict` is insertion-ordered since 3.7 and `json.dumps` respects it, but nothing in the standard serialization path makes "ordered write" an explicit, self-documenting API the way `LinkedHashMap`/`ObjectNode` does | 4 — `System.Text.Json` with an explicit property-order-respecting model or `JsonObject` gives similar control to Jackson; broadly comparable to Java here |
| Multipart byte-level control | **5** — Apache HttpClient 5 (or raw socket writing) gives full control over boundary/CRLF/part-order construction, needed after the PowerShell `-Form` failure mode observed in probing | 4 — `form-data`/raw stream construction in Node is capable of the same control, well-trodden but requires bypassing higher-level libraries that make the same simplifying assumptions PowerShell's `-Form` did | 3 — `requests`/`httpx` multipart helpers are convenient but, like PowerShell's, are exactly the kind of "helper builds the body for you" abstraction that failed once already; achievable but needs deliberate low-level fallback | 4 — `HttpClient`/`MultipartFormDataContent` in .NET gives comparable control to Java's; a solid second choice here |
| Long-lived session pooling / keepalive scheduling | **5** — Spring's task scheduling (`@Scheduled`), connection pooling (Apache HttpClient 5's `PoolingHttpClientConnectionManager`), and bean-lifecycle-managed singletons are exactly this problem's idiomatic shape | 4 — Node's event loop and `setInterval`/cron libraries handle scheduling fine; connection pooling is available via `undici`/`keep-alive` agents, slightly more assembly required than Spring's batteries-included model | 3 — achievable (`APScheduler`, connection-pooled `httpx.Client`), but "long-lived stateful service with pooled resources and a scheduler" is not Python's most idiomatic deployment shape compared to a WSGI/ASGI request-per-call mental model | 5 — `IHostedService`/`BackgroundService` plus `HttpClientFactory`'s pooling is Spring-equivalent in ergonomics and maturity |
| COM interop for OTA (relevant even though isolated in a sidecar, ADR 0003) | 2 — JNI/JACOB-style bridges exist but are a known-fragile, low-adoption path (this is *why* ADR 0003 isolates OTA rather than embedding it) | 2 — Node COM interop is similarly indirect/unusual; not a natural fit | 2 — `pywin32` is a real, moderately common COM path, better than Java/Node but still a secondary ecosystem concern | **5** — .NET has first-class, mature COM interop (`Type.GetTypeFromProgID`, RCW marshaling) — the clear best fit for OTA specifically |
| Ecosystem fit for a metadata-driven, JSON-heavy backend | **5** — Jackson, Spring MVC/WebFlux, and a large validation/serialization ecosystem are a strong match for "render forms from a discovered schema, not a fixed one" | 5 — TypeScript's structural typing and the SPA already being TS gives strong end-to-end type-sharing potential for a metadata-driven contract | 4 — Pydantic and FastAPI are genuinely excellent for this exact shape of problem (dynamic/discovered schemas validated at the boundary) — a real strength, not a weak spot | 4 — `System.Text.Json` plus strong typing is comparable to Java; slightly less battle-tested than Jackson specifically for "arbitrary discovered JSON shape" workloads but a solid fit |
| Stated team preference | **5** — explicit user leaning (`SESSION-STATE.md` Phase-0) | 2 | 2 | 2 |

### Weighted read

Criteria 1–3 (wire-contract control, multipart control, session pooling) are the three that are
*specific to this project's verified server behaviour*, not generic language merits — every one of
them traces to a probe finding, not a preference. Java scores at or near the top on all three. Criterion
4 (COM) is real but is explicitly not the mainline stack's problem once ADR 0003 isolates OTA in a
sidecar — .NET's advantage there does not transfer into an advantage for the *mainline* BFF, because
the mainline BFF never touches COM. Criterion 5 is a near-tie across all four candidates with Python's
Pydantic/FastAPI genuinely worth naming as a strength, not dismissing. That leaves criterion 6, the
stated preference, as a legitimate tiebreaker among options that are otherwise close on the criteria
that matter most — which is exactly the role D2 assigns it, not an overriding factor invoked to skip
the comparison.

**Decision drivers, in the stated order (D2):**

1. User preference is Java — a legitimate tiebreaker, named as such, not disguised as a technical
   requirement.
2. The domain's fussy, probe-verified wire contract (deterministic field order, hand-built multipart)
   is best served by Jackson's explicit ordered-serialization APIs and Apache HttpClient 5's low-level
   multipart control.
3. Long-lived session pooling/keepalive scheduling is bread-and-butter Spring (`@Scheduled` +
   `PoolingHttpClientConnectionManager`).
4. .NET's superior COM interop is real (scored 5 vs. Java's 2 above) but does not outweigh 1–3 because
   OTA is isolated in a sidecar (ADR 0003) — the mainline stack never touches COM, so this advantage
   is real but irrelevant to the component being chosen here.
5. Node's one-language appeal (the SPA is TypeScript regardless) is real and scores well on criteria
   3 and 5, but loses on criteria 1 and 2 relative to Java's more explicit ordered-serialization and
   lower-level HTTP-client story.

**Frontend:** React + TypeScript SPA, metadata-driven rendering per D5/ADR 0005 — uncontested; no
serious alternative was raised in Phase-0 discussion, and the SPA's job (render from a discovered
schema, call Alt-ALM's own clean API) does not carry the ALM-wire-contract sensitivities that drove the
backend comparison above.

## Consequences

- **Named, stack-agnostic integration-test requirement**: multipart construction for the
  `ref-subtype=1` embedded-image path must be integration-tested against the real ALM server before
  shipping, regardless of stack — this is not a Java-specific risk, it is a probe-proven fact about the
  server's tolerance for how a multipart body is built (api-ref §6.6: "some HTTP client libraries build
  multipart bodies this server rejects"). Java's Apache HttpClient 5 choice reduces but does not
  eliminate this risk; the test must exist either way.
- Committing to Jackson's ordered-map APIs for every entity-write path means the write-safety client
  (`architecture.md` §2.2, D7) has one canonical serialization utility to build and unit-test once,
  rather than trusting default behaviour anywhere in the write path.
- The team takes on JVM operational overhead (build, packaging, startup time) versus a lighter Node or
  Python deployment — accepted given criteria 1–3 above and the stated preference.
- Because OTA is isolated (ADR 0003), the mainline BFF gains no COM capability from this choice — any
  future decision to bring OTA in-process would need to revisit this ADR, not assume Java can absorb it
  cheaply (Java scored 2 on COM interop above).

## Alternatives considered

- **TypeScript/Node.** Strong second choice — good multipart and wire-contract control, appealing
  one-language story with the TS SPA, and Pydantic-caliber ergonomics were not needed since the domain
  fits Node/TS fine. Not chosen because it loses to Java on the two most server-behaviour-sensitive
  criteria (1–2) and does not benefit from the stated preference.
- **Python.** Named as genuinely strong on ecosystem fit for discovered/dynamic schemas (Pydantic,
  FastAPI) — this is a real strength worth recording, not a weak spot invented to justify rejecting it.
  Not chosen because its default serialization and multipart-construction idioms are the least
  explicit of the four regarding the exact hazards probing surfaced (dict ordering is a language
  guarantee but not an "ordered-write API" the way Jackson's is; multipart helpers are convenience
  wrappers of the same shape that failed once already in PowerShell).
- **.NET (C#).** The best-scoring option for COM interop by a wide margin (5 vs. Java's 2) and a close
  second on wire-contract and session-pooling criteria. Would have been the stronger pick if OTA needed
  to run in-process. Not chosen because ADR 0003 isolates OTA in its own sidecar specifically so the
  mainline stack's COM story stops being a deciding factor — and once it's removed from the comparison,
  Java's edge on criteria 1–2 plus the stated preference (D2) wins.
