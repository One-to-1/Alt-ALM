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
- ⚠️ **The GET-only rule for other projects was LIFTED by the user on 2026-08-18.** `AlmAccessPolicy`
  now permits writes to **any project on the allowlist**, not just the sandbox; enrolling a project
  in `alt-alm.alm.readable-projects` grants write access to it. ⚠️ **CORRECTED 2026-08-19: this file
  previously claimed "nothing else is enrolled". That was wrong** — `Secrets/local.properties`
  enrols **8** projects, and has since before the rule was lifted, so lifting it silently converted
  eight read grants into write grants. **The user was asked and confirmed on 2026-08-19 that all
  projects should be readable AND writable**, so this is the intended configuration, not a drift.
  What it means in practice: a local run reports **9 of 9 projects writable**, and the SPA's Edit
  button is gated on exactly that flag, so Alt-ALM will offer editing on the other teams' live
  records. The only control is which projects are enrolled.
- **Other projects in the tenant** (user, 2026-08-14; write restriction since lifted, above). Eight
  were reachable with the same key; `PROJECT-5` (233 reqs / 129 tests / 80 defects / 227
  test-instances / 178 runs) was the P1 read target — real names in git-ignored
  `Secrets/alm-read-projects.json`. Still binding: **their data never enters the repo** — no names,
  text, owners or field values in fixtures, docs or logs, only counts and shapes, pseudonymized
  (`PROJECT-5`, not the real name); and while their data **may seed sandbox records**
  (user-authorized), a seeded record is sandbox state, not a committed artifact. These are other
  teams' live projects.
- **Write only where the user has said you may.** The project in
  `Secrets/ALM_API_credentials.json` **was designated a disposable sandbox by the user on
  2026-08-12** — writes allowed there, with `ALTALM-*` name prefixes and mandatory cleanup. Since
  2026-08-18 the *code* no longer restricts writes to it alone (above), so this is now a judgement
  the operator makes by enrolling projects rather than one the policy enforces for you. **Probe
  scripts still write to the sandbox and nowhere else.** The record generator is dry-run by default
  and must refuse any target not on an explicit allowlist.
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

**Phase: research and planning COMPLETE (2026-08-12). Implementation STARTED 2026-08-13 — P0 is
COMPLETE; P1 (read-only Alt-ALM) is next.**

Twelve live probe rounds against the sandbox plus 20 subagent research reports produced the research
corpus; the plan set and ADRs are written. **`docs/research/SESSION-STATE.md` is the resume
point — read it first.**

**P0 in the repo now — 57 tests green** (`./mvnw test` in `bff/`; no local Maven needed;
**69 with `-Pcontract`**):

- `bff/.../alm/write/` — `AlmEntityBody` (deterministic field order), `AlmWriteOutcome`
  (5xx→UNKNOWN, never REJECTED), `AlmWriteRetry` (single missing-required-field retry)
- `bff/.../alm/metadata/` — `AlmFieldType` (the 8 types), `FieldDescriptor`, `AlmMetadataParser`
  (**no HTTP dependency** — parses fixtures offline), `AlmMetadataClient` (the HTTP half),
  `AlmMetadataCache` (project-scoped, explicit invalidation, single-flight)
- `bff/.../config/` — `AlmProperties` (`alt-alm.alm.*`), `AlmConfiguration` (beans + keepalive
  schedule; no ALM contact at startup)
- `bff/.../alm/session/` — `AlmCredentials` (runtime-only; `toString` refuses to render itself),
  `AlmSession`, `AlmSessionPool` (bounded, idle-eviction, keepalive scheduling), `AlmAuthClient`
- `bff/src/test/.../alm/contract/` — `AlmSandbox` (credential discovery, the `@EnabledIf` gate, the
  masker), `AlmAuthClientContractTest` (live, tagged `contract`), `CredentialMaskingTest` (always on)
- `spa/` builds; CI runs both halves and **fails if `Secrets/` is ever tracked**
- Fixture harness parses **all 15** captured entities with no server and no credentials

✅ **The contract test is in and runs green against the live sandbox** (2026-08-13) —
`AlmAuthClientContractTest`, 9 cases: one-step login, v2 is-authenticated, project reach, keepalive,
XSRF-missing → 401, a 3-session pool with distinct cookies, site-session redundancy, teardown
semantics, plus an orphan sweep that fails loudly. **P0's auth exit criterion is now met.** Tagged
`contract`, excluded from the default build and CI, opt in with `./mvnw test -Pcontract`; without
`Secrets/` it **skips**, never fake-passes. `CredentialMaskingTest` (5 cases) runs on *every* build
and scans the tracked tree for literal credential values. **43 tests default, 52 with `-Pcontract`.**

⚠️ **Its first run found two real bugs in `AlmAuthClient`** — logout leaked authentication, and
`login()` discarded the cookies `POST site-session` sets. Both fixed; see probe 13. This is the first
finding in the project surfaced by product code under test rather than a hand-written probe.

✅ **P0 IS COMPLETE** (2026-08-13) — all five exit criteria met. The last two landed together:

- **`AlmMetadataCache`** — project-scoped, **explicit invalidation only** (no TTL: ADR 0005 wants an
  operator lever, not luck), single-flight so N concurrent callers cause one fetch, and a failed load
  is **not** cached. Over `AlmMetadataClient`, which keeps the parser HTTP-free. Verified live:
  **15 entities, 432 fields, all 8 types, no unknown type** — independently reproducing the original
  probe's 432.
- **Spring wiring** — `AlmProperties` (`alt-alm.alm.*`) + `AlmConfiguration`. Credentials come from a
  file **or** inline properties, file wins, missing config fails fast naming the *property* never a
  value. The context starts with **zero ALM contact**: the pool logs in lazily on first borrow, which
  is what lets CI start it with no credentials. Actuator exposes **health only** — `/env` and
  `/configprops` would render the API secret.

🟡 **P1 STARTED 2026-08-14** — its phase-start deferred probe (probe 15) is done and changed the plan:

- ⚠️ **Tree-root discovery was wrong everywhere it was written down.** `{parent-id[0]}` resolves only
  2 of 6 trees, and for `test-set-folders` it silently returns **`Recycle Bin`** — HTTP 200, one row,
  indistinguishable from correct. **Correct rule: `{parent-id[-1]}` first, fall back to
  `{parent-id[0]}`.** All six roots now verified, closing the release-folder root (id 1 "Releases").
  A *discovery* query is not automatically safer than a hardcoded id — it can be confidently wrong.
- **`alm-web` dialect settled** (Q2/R11 closed). Group-by goes **server-side on plain JSON** —
  `groups/{field}` already returns `size` and `expression`, so P1's client-side aggregation fallback
  is dropped. The dialect *does* return flat, envelope-free entities on ordinary collection reads,
  but that op doesn't advertise it → **undocumented, so R15, not an implementation.**
- **Paging**: 2000 is server-stated, **`page-size=max` exists**, out-of-range is **404 not 400**, and
  ⚠️ `page-size=0` reports `TotalResults=0` on a non-empty collection.
- ✅ **P1 validation is unblocked** (probe 16). The sandbox is effectively empty — 0 tests, 0
  defects, 1 requirement — but the tenant's **other projects are readable** (see hard constraints),
  and `PROJECT-5` has 847 rows of real data. **Q45 dissolved: the generator is NOT a P1
  prerequisite** and stays a P4 write-testing concern.
- ⚠️ **A plain `GET` returned HTTP 500 once and never reproduced** (13 follow-up requests, all 200).
  Cause UNVERIFIED, possibly the same load-balancer intermittency as Q40. P1's grid needs a
  **bounded retry on 5xx reads** — separate from the 5xx-on-*write* verify-by-query rule (**Q46**).

🟡 **P2 IN PROGRESS — the write core, the CRUD endpoints and the validation layer are in and
verified live (2026-08-19). 345 BFF tests (388 with `-Pcontract`) + 132 SPA tests green. **CRUD is
complete in the SPA (2026-08-20)** — read, create, edit, comment, delete — and the whole path is
verified end to end against the live sandbox (probe 32), in the SPA's own request shapes.**
`AlmWriteClient` is the single write path; `ApiIsReadOnlyTest` asserts writes *route through it*
rather than that none exist, and now has real endpoints to guard. See `SESSION-STATE.md`.

⚠️ **The BFF validation layer is the ONLY validation there is, and it is incomplete on purpose.**
`AlmWriteValidator` enforces what metadata states (unknown/virtual/server-owned fields, date and
datetime grammar, declared size, the memo-is-HTML trap). It deliberately does **not** enforce
`required` or `editable` — probe 9's field is both false and still required by the server — does not
check lookup-list membership (no list client yet; every Y/N flag is a list, so a wrong guess rejects
correct writes), and **accepts decimal Numbers because integer-ness is UNVERIFIED**. A validator that
helpfully enforced those would refuse writes ALM accepts, and the failure would look like an ALM
limitation rather than our own rule.

⚠️ **An unresolved `UNKNOWN` write is served as HTTP 502, and that status describes the UPSTREAM, not
the row.** The write may well have committed. `"outcome": "UNKNOWN"` in the body is the authority; a
client that treats 502 as "failed, retry" will create duplicates.

⚠️ **"A field with choices" is THREE mechanisms** (2026-08-20): `LookupList`+`listId` →
`used-lists` (**56 of 58** fields, done); `Reference` with `fieldRelationReferences` → query that
**entity collection**, value is an **id** (`target-rel`→`release`, `target-rcyc`→`release-cycle` —
also the model's **only two multi-value fields**); `Reference` with **empty** references →
`customization/entities/{e}/types` (`type-id`). All three resolve through **one** endpoint,
`GET /api/choices/{collection}` (collection-level: per-field would cost 30 requests to open one
requirement editor). **Branch on `choiceSource`, never on the field type** — `type-id` and
`target-rel` are both `REFERENCE` and resolve differently. ⚠️ `req-type` (LookupList) and `type-id`
(Reference) are different fields by different routes. ⚠️ **The unresolved-fallback differs by
mechanism**: a LOOKUP degrades to free text (value is a string), a REFERENCE gets **no control**
(value is an **id**, and a text box over one invites re-pointing the record). Multi-value fields
(the model's only two, both References) still have no control.

⚠️ **Lookup lists: `GET customization/used-lists` returns all 39 WITH items inline** (39 lists / 125
items / 3 empty, live-verified 2026-08-20) — one request, no per-list fetch. Casing is mixed *within*
one object (PascalCase list, lowerCamel items). **When the evidence is absent, let ALM decide**: an
unreadable list, an unknown list, an **empty** list, and `listId == 0` all validate nothing and
render free text — a wrong rejection makes a field unfillable and blames the user for it.

⚠️ **In the SPA, an `unknown` write outcome must never offer "Retry"** (`spa/src/detail/
writeOutcome.ts`). ⚠️ **That module exports TWO predicates and they are not interchangeable**:
`mayKeepEditing` asks whether the user's draft survives (false for `COMMITTED` — an editor closes);
`mayWriteAgain` asks whether a write button may be on screen (true for `COMMITTED` — a comment box is
used again immediately). They disagree on `COMMITTED` and `CONFLICT`, a test pins the disagreement,
and merging them silently breaks one caller in whichever direction the merge went. An ALM 5xx may have committed the row, so the obvious red-banner-plus-Retry
design creates duplicates for precisely the writes that worked. It gets its own tone, one action
(reload), and the editor closes. Likewise the write client returns a **union, not an ok/throw**, and
treats a dropped connection as *not* retryable — unlike every read.

⚠️ **Creating at a tree root is impossible, and the SPA refuses rather than trying** — a
requirement's root `parent-id` of `-1` is a sentinel, not a row (probe 27). Because a 5xx write is
reported as `UNKNOWN`, defaulting the parent to `-1` would turn a knowable refusal into "we cannot
tell whether a record was created". The root row itself (id **0** for requirements) *does* accept
children; it is the sentinel that does not.

⚠️ **ALM names a refused field by its DISPLAY LABEL, and attaches it to nothing** (probe 32).
`qccore.required-field-missing` returns `The field 'Requirement Type' is required.` with
**`problems: []`** — errors are per request, never per field (probe 29). `fieldBlamedBy`
(`spa/src/detail/writeOutcome.ts`) matches the quoted label back to a column so the input can be
marked; it is an explicit heuristic over prose and returns null rather than guessing. ⚠️ Match on the
**label**, never the logical name — labels are per-project customization. ⚠️ It is a clean **400**,
so the BFF's missing-required-field retry does **not** fire and must not: that retry is for probe 9's
case, where metadata fails to *declare* a field and ALM answers an opaque **500**.

⚠️ **`runs` and `attachments` are not writable through the API, by design** — `POST runs` fails
definitively (the only route is a status `PUT` on a test-instance that makes ALM synthesize a
`Fast_Run`), and an attachment needs a hand-built multipart body, not a JSON entity. Both are refused
as endpoints rather than offered and failed.

🟢 **P1 IS FEATURE-COMPLETE (2026-08-18).** Grid (metadata-driven
columns, sort, filter, paging, **group-by with real counts**), folder tree, detail pane with a
**collapsing icon rail** (blue when a tab holds rows), **History/Audit Log**, related-record tabs
with cross-module navigation, **ALM's module rail** rendering three distinct kinds of "unavailable",
per-subtype field sets, column picker, Tree|Grid toggle, resizable detail pane.

✅ **Rich text renders (gap 0d closed 2026-08-18)** — `spa/src/detail/richText.ts`, DOMPurify **in
the browser, not the BFF**: a server-side sanitiser parses with a different HTML parser than the one
that renders, and that gap *is* the mutation-XSS class. ⚠️ **`USE_PROFILES` overrides `ALLOWED_TAGS`
rather than intersecting with it** — it silently let `<form>`/`<input>` through a deliberately narrow
list, caught only because the tests assert on output, not on configuration. **DOMPurify does not
sanitise CSS**, so `url(…)` declarations are filtered separately. Remote `<img src>` is replaced with
a labelled placeholder: Alt-ALM cannot fetch attachments, and rendering one would beacon to a host
the memo's author chose. **The SPA now has vitest + jsdom** (`npm test`, gated in CI) — added for
this suite, which is a payload suite.

⚠️ **Memo fields are HTML and only HTML** (probe 27). No markdown, no wiki: everything is parsed
as HTML and re-serialised into a full `<html><body>` document — a bare fragment gets wrapped, a stray
`<` becomes `&lt;`, and markdown/wiki characters are stored literally. **Newlines are collapsed to
spaces, not converted to `<br>`** — a plain-text write path would silently flatten paragraphs, which
is P2's trap to avoid.

⚠️ **A hostile memo does not survive a REST round trip — but that is OUTPUT sanitisation, and it is
configurable** (probe 27 + OpenText REST docs). `<script>`, `onerror`, `javascript:` hrefs and
`url()` in styles all come back stripped, and the first reading of that ("ALM sanitises on write")
was **wrong**: the docs say output sanitisation *"removes or encodes data returned by requests"* and
the raw value stays in the database. It is set **per field** in project customization (*Do nothing* /
*Text encoding* / *HTML sanitization*) against a deployment-owned `sanitizer-whitelist.xml`, so a
project configured *Do nothing* returns the payload live. Our client-side sanitiser is therefore
**load-bearing, not defence in depth** — the only filter that does not depend on a server setting we
cannot see. ALM **keeps** remote `<img src>` verbatim regardless. Also re-confirmed: a requirement's `parent-id` of `-1` is the root **sentinel, not a row**
— POSTing a child against it returns `500 Entity with key '-1' does not exist in table 'REQ'`.

⚠️ **An unquoted multi-word filter value silently returns the whole collection** (probe 26): `NOT` is
a grammar keyword, so `{status[Not Completed]}` means "status is not Completed" and answers with 233
rows against a group count of 8. `AlmQuery` quotes values containing whitespace. Never hand ALM a
bare multi-word literal.

⚠️ **Records CAN now be created, edited and deleted** (P2, 2026-08-19) — the long-standing "there is
no write path" note is retired. What replaced it, and what still holds:
`AlmEntityClient` still has no write method; every write in the `api` package goes through
`AlmWriteClient`, and **`ApiIsReadOnlyTest` fails the build if a write mapping appears that cannot
reach it** (re-verified in both directions with a temporary violating controller). That test was
**changed, never deleted**, exactly as this file instructed in advance. `AlmAccessPolicy.checkWrite`
no longer restricts to the sandbox (see the lifted rule above), so **enrolment is the only remaining
control** — which makes the routing guard, not the policy, the thing standing between a stray
endpoint and someone else's data.

⚠️ **Stop the local BFF before running `-Pcontract`** (2026-08-20). With the app serving, creates
come back `UNKNOWN` (ALM 5xx) every time; 24/24 pass the moment it is stopped. Probable cause: both
share one API key and `authentication-point/logout` ends the **authentication**, not one session
(probe 13) — so a pool closing at the end of a test class invalidates the running app's sessions.
Does *not* contradict probe 10's 50 concurrent sessions: none of those logged out mid-flight.
⚠️ It also leaked a row — an `UNKNOWN` create returns **no id**, so id-tracking cleanup cannot delete
it. **The `ALTALM-*` prefix sweep is not redundancy; it is the only cleanup that covers a 5xx.**

⚠️ **`spring-boot:run` forks a child JVM.** Kill the **port holder**, not the Maven parent, or the
old build keeps serving :8080 and answering health checks while the new one fails to bind.

See [docs/plan/implementation-plan.md](docs/plan/implementation-plan.md) for P1's scope and
[docs/research/SESSION-STATE.md](docs/research/SESSION-STATE.md) for the P1 status section
(what works, the UI decisions not to re-litigate, and the known gaps).

✅ **Toolchain ready**: Node 24.13.1, git 2.54, **JDK 25.0.4 Temurin** (machine-level `JAVA_HOME`
set by its installer — do NOT add user-level Java env vars, they shadow it). No local Maven/Gradle
needed; the wrapper handles it. ⚠️ The repo sits in a **OneDrive-synced folder**, which locks
`bff/target` and breaks `mvnw clean` — run without `clean`, or exclude `bff/target` and
`spa/node_modules` from sync.

Key artifacts: [live-probe-log.md](docs/research/live-probe-log.md) (empirical ground truth — **wins
every conflict**; probes 1–15), [alm-api-reference.md](docs/research/alm-api-reference.md),
[alm-data-model.md](docs/research/alm-data-model.md),
[feasibility-matrix.md](docs/research/feasibility-matrix.md) (218 features scored),
[architecture.md](docs/plan/architecture.md) + `docs/adr/0001–0005`,
[data-generator-spec.md](docs/plan/data-generator-spec.md),
[risks-and-open-questions.md](docs/plan/risks-and-open-questions.md) (risk + open-question register),
[_raw/no-verdict-recheck.md](docs/research/_raw/no-verdict-recheck.md) (the NO-verdict re-audit).

## Verified facts (sandbox = ALM 26.1, SaaS-flavored; depth in `docs/research/`)

**Load the `alm-api` skill before any ALM work — these are the headlines only.**

- **Auth**: `POST /qcbin/rest/oauth2/login` with `{clientId, secret}` sets the full cookie set in one
  call; the follow-up `POST site-session` is **redundant** (probe 13) but kept — merge the cookies it
  sets if you issue it. `X-XSRF-TOKEN` header required on every non-GET (missing → 401). REST sessions
  consume **no licence seat**. Use `/qcbin/v2/rest/is-authenticated` for JSON (the Core path is
  XML-only, 406).
- ⚠️ **Logout is two calls, both needing XSRF** (probe 13): `DELETE rest/site-session` ends only the
  **project** session — LWSSO survives it, so stopping there leaks one authenticated identity per
  session. `POST authentication-point/logout` ends the authentication. Its status varies (200/500);
  the outcome does not, so treat logout as best-effort and ignore the status. **`is-authenticated` is
  not a liveness check** — it returns 200 while every project call returns 401; use the
  `GET site-session` keepalive. Replaying a logged-out session's cookies gives **500
  `TokenId is invalid because it has logged out`**, a 5xx that emphatically did *not* commit anything.
- **One API key holds many concurrent sessions** — **50/50 opened, zero evicted, all usable
  simultaneously** (probe 10); no cap was reached, so 50 is a floor. Unlike a username/password
  login, there is **no one-machine-at-a-time constraint**. `JSESSIONID`, `LWSSO_COOKIE_KEY`,
  `QCSession`, `XSRF-TOKEN` are each unique per session; only `ALM_USER` is shared. Multi-machine
  (different IP) behaviour is `UNVERIFIED` — all 50 came from one host.
- ⚠️ **No optimistic locking: `ver-stamp` is a counter, not a token** (probe 31). It increments on
  every write *including memo writes*, but ALM **accepts a stale one and lets the write land**, so
  last-writer-wins is the server's behaviour. It is still a reliable change *detector* — re-read it
  immediately before a PUT and refuse on a change — which narrows the lost-update race without
  closing it. Never describe that as safe.
- ⚠️ **A memo PUT REPLACES the field — a comment write destroys every earlier comment** (probe 30).
The SPA's answer is a **write-only** `CommentBox`: it holds the new comment and never the thread, so
the shape that would delete the history cannot be typed into. The thread renders above it, read-only.
  There is no server-side append and no banner, user, or timestamp added by ALM (workflow bypass).
  The obvious comment UI deletes the record's whole history and answers **HTTP 200**. Comment writes
  must be **read-modify-write in the BFF**. The field name differs per entity and does not track the
  physical name — requirement `comments` (`RQ_DEV_COMMENTS`), defect/test `dev-comments`, run
  `comments` (`RN_COMMENTS`) — so discover it from metadata. The stock client's banner format is
  **UNVERIFIED** (needs one comment written in ALM's own UI) — isolate it behind one function.
- **Errors are per-REQUEST, never per-row** (probe 29). `EntityStatus` sits on every entity ALM
  returns (JSON member on reads, XML attribute on writes) and has only ever held `"Success"`. ~25
  deliberately broken reads plus failing writes all came back as a `QCRestException`
  (`Id`/`Title`/`ExceptionProperties`) with **no entities envelope at all**. ⚠️ **Writes are
  single-entity only**: a multi-entity JSON body is parsed as one entity and 500s, the XML
  `<Entities>` wrapper is refused 400, while the same builder's single `<Entity>` commits 201. Do
  not design a batch write API on the assumption an endpoint exists to back it.
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
- **58% of the stock UI is achievable** (feasibility matrix: FULL 54 / FULL* 23 / PARTIAL 50 =
  **127 of 218**). After the 2026-08-13 NO-verdict re-audit (probes 11–12): **OTA 29, NO 13,
  UNVERIFIED 32, N/A 17**. `NO` dropped 21→13 — eight rows were wrong.
- **The Testing Policy matrix is readable** (`Customization.RBT`): `TestingPolicyMatrix`,
  `RiskCalculationMatrix`, `TestingLevelPercentage`, `TestingEffortForFCLevel`. "Analyze" is a
  documented lookup table, not a hidden algorithm — read it once per project over OTA and compute
  client-side; the `rbt-*` writes are pure REST. **Per-project admin config — never hardcode.**
- Also OTA-readable (probe 12, **reads only — writes UNVERIFIED**): `Customization.KPITypes` (11),
  `ReportProjectTemplates` (79), `BusinessViews` (37) + `GraphBuilder`, `AlertManager.AlertList`,
  `Customization.Modules/Permissions/UsersGroups` (data-hiding).
- ⚠️ **OTA parameterized properties**: `"Number of parameters specified does not match the expected
  number"` means **wrong arity**, NOT unsupported. The matrices take 2 indices, `AlertList` takes 1.
- **Workflow scripts stay genuinely unreachable**: `Customization.Workflow` exposes only
  `ProjectScriptsUpdated`/`TemplateScriptsUpdated` — dirty flags, no script content. Confirmed by
  direct evidence, not inference.
- **Site Admin session visibility**: `GET /qcbin/v2/sa/api/site-connections` → 200 (session ids,
  host, username, `client-type` — shows `OTAClient` for COM sessions). ⚠️ It returns **third-party
  identities from other tenant projects** — mask structurally by JSON key, not by credential terms.

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

- **BFF is required**, not optional — browsers cannot call `/qcbin`. **Probe-verified 2026-08-13**
  (probe 14): the CORS preflight returns **501** and *even a successful* `POST oauth2/login` carries
  **no `Access-Control-Allow-Origin`**. ⚠️ The trap: the server processes cross-origin requests fine,
  so curl/Postman show 200 and look like proof it works — only a real browser enforces CORS. A
  static-SPA-only deployment (GitHub Pages etc.) is closed off **by mechanism**. Cheapest correct
  shape is therefore **one deployable on one origin** — Spring Boot serving the built SPA as static
  resources, no CORS config needed. Running both on localhost is the same shape and free (Q42).
  The BFF is also the single enforcement point for the write hazards above (ADR 0001).
- **Stack: Java 25 (LTS) + Spring Boot 4.1.0** (BFF) and **React + TypeScript / Vite** (SPA); probe
  scripts stay PowerShell (ADR 0002). Spring Framework 7 recommends JDK 25+ for production; Boot 3.x
  predates JDK 25. No `--enable-preview`. **Boot 4 gotchas already hit** (all verified by building):
  the starter is `spring-boot-starter-webmvc` (not `-web`), test deps are **per-starter**
  (`spring-boot-starter-webmvc-test`) rather than one `spring-boot-starter-test`, **Jackson is not
  transitive** — add `spring-boot-starter-json` — and it is **Jackson 3**, whose package is
  **`tools.jackson.*`** (not `com.fasterxml.jackson.*`) with **unchecked** exceptions. ⚠️ **The test
  slice annotations moved too**: `@WebMvcTest` is `org.springframework.boot.webmvc.test.autoconfigure`,
  **not** `org.springframework.boot.test.autoconfigure.web.servlet` — the old package does not exist,
  so the failure is a bare `symbol: class WebMvcTest` that looks like a missing dependency and is a
  rename. Use `@MockitoBean`, not the removed `@MockBean`. Spring
  Initializr's `/metadata/client` reports legacy ids like `4.1.0.RELEASE`; the real artifact version
  is plain `4.1.0`.
- **OTA/COM is isolated in an optional Windows-only sidecar** — mainline never touches COM; features
  degrade behind capability flags when the bridge is absent (ADR 0003). The bridge has a **verified
  reachable target** (probe 8), but probe 9 cut its scope from three gaps to **two** (BPT components,
  similar-defects) — it no longer blocks the generator, so P6 is genuinely optional. Language
  decision (.NET vs Python + pywin32) is still open.
- **One service-account API key with pooled sessions**, plus Alt-ALM's own app-level user model; the
  licence finding retires the seat-consumption concern (ADR 0004). ⚠️ **Only ONE ALM user seat is
  available for testing** (user, 2026-08-13) — so per-user credentials are **untestable**, not merely
  unscoped, and multi-identity code paths must not be built until a second seat exists (Q44). This
  does *not* contradict probe 10: seats and REST sessions are different resources, and one key still
  holds 50+ concurrent sessions. If revisited, the ALM secret goes to the BFF **once** over TLS and
  lives in memory only — the browser gets the BFF's own `HttpOnly` cookie, never the ALM key.
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
