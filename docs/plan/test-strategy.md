# Alt-ALM — Test Strategy

Status: Draft, lead-decision-bound. Elaborates D7 (single write-safety component, one test suite) and
the phase exit criteria in [`implementation-plan.md`](implementation-plan.md). Citations:
[`alm-api-reference.md`](../research/alm-api-reference.md) (`api-ref §N`),
[`alm-data-model.md`](../research/alm-data-model.md) (`data-model §N`),
[`live-probe-log.md`](../research/live-probe-log.md) (`probe log`). Constraints from `CLAUDE.md`:
`Secrets/` never read/logged/printed; sandbox writes only against an explicit allowlist; `UNVERIFIED`
claims stay labelled.

---

## 1. Test pyramid

```
                    ▲  fewer, slower, opt-in
        E2E UI (stubbed BFF; thin real-sandbox smoke set)
      Contract tests (live sandbox, credential-gated, opt-in)
   Fixture-based tests (tests/fixtures/, no server needed)
 Unit tests (pure logic — no I/O, no fixtures)
                    ▼  many, fast, run on every commit
```

Each layer exists because the layer above it cannot catch its class of bug cheaply: unit tests catch
logic errors in code Alt-ALM controls entirely (serialization order, DAG resolution); fixture tests
catch metadata-parsing/envelope regressions against real captured shapes without needing a server;
contract tests catch drift against ALM's actual (probe-verified, occasionally surprising) behavior;
E2E catches integration/regression across the whole SPA→BFF→ALM chain.

---

## 2. Layer 1 — Unit tests (every commit)

Pure logic, no network, no fixtures. Deterministic, fast, no credentials needed.

- **Query-string builder**: Core curly-brace grammar (`api-ref §4.1`) — filter/condition/field
  delimiting, cross-filter alias construction and the "never reuse an alias for the same type" rule
  (`api-ref §4.2`, silent-wrong-result risk if violated), multi-field `order-by` (`api-ref §4.3`).
- **Ordered serializer**: the `Fields`-array writer that enforces a fixed, deterministic member order
  (`name` → relational ids → type/subtype last). **This is the regression test for the NPE-500 class**
  (`api-ref §3.2`) — assert byte-identical JSON member order across repeated builds of the same logical
  entity, in whatever language's map/dict Alt-ALM uses internally (must not rely on iteration order).
- **HTML canonicalizer**: normalizes whitespace/pretty-printing, implicit `<tbody>` insertion, and
  `<script>`-stripping equivalence so round-trip tests compare canonicalized HTML, never raw bytes
  (`api-ref §7`).
- **DAG resolver** (generator): topological ordering of the creation-order DAG (`data-model §2.11`) —
  unit-test that release/cycle/milestone always precede requirement, test always precedes design-step
  and coverage, test-instance always precedes Fast_Run-triggering status writes, etc., independent of
  any live call.
- **Seedable PRNG wrapper**: same seed → identical output sequence.
- **Field-type→strategy dispatch** (generator): correct strategy selection for each of the 8 types
  (`api-ref §8`), correct refusal to generate a value for `Editable=false AND System=true` fields (191
  of 432 probed fields — `api-ref §8`).

## 3. Layer 2 — Fixture-based tests (every commit, no server)

Exercise real captured shapes from `tests/fixtures/` (redacted metadata dumps, write-probe response
fixtures) without any live call.

- Metadata parsing: all 15 `customization-fields-<entity>.json` fixtures parse into the internal field
  model with correct `Type`/`Required`/`Editable`/`System`/`List-Id` extraction (`data-model §1`).
- Envelope handling: `{"Fields":[...], "Type":"..."}` write shape and `{"entities":[...],
  "TotalResults": n}` collection-read shape (`api-ref §3.1`) parse/serialize correctly, including the
  rule that a GET payload must never be round-tripped directly into a POST/PUT body (calculated fields
  like `id`, `vc-*`, `father-name` would be rejected — `api-ref §3.1`).
- Bulk 409 body parsing against the captured `BulkOperationFailed`/`BulkEntry[]` shape (`api-ref §4.5`)
  — per-item `Successful`/`EntityId`/`EntityType` extraction, never assumed all-or-nothing.
- Legacy-naming-trap regression fixtures: `run.cycle-id` (test-set id, typed `String`) vs.
  `run.testcycl-id` (test-instance id, typed `Reference`) vs. `test-instance.cycle-id` (also test-set
  id) — parse the `r3-fastrun-full-entity.json` fixture and assert the internal model does not conflate
  these (`data-model §2.7`).
- Non-uniform `parent-id` typing: `test-set`/`test-set-folder` type `parent-id` as `LookupList`, not
  `Number` like the other 10 tree entities, and defects have no `parent-id` at all (`data-model §4`) —
  fixture test that tree-write logic branches correctly per entity rather than assuming one type.

## 4. Layer 3 — Contract tests (live sandbox, opt-in, credential-gated)

Run only when sandbox credentials are configured (never in a default CI run — see §6). Every write
uses the `ALTALM-*` name prefix (probe convention), cleanup runs in a `finally`/equivalent block
regardless of pass/fail, and a post-suite orphan sweep (query by prefix, assert zero unexpected
survivors) is a required assertion, not a manual cleanup step.

Must-have cases (minimum set — each traces to a probe-log finding):

1. **Deterministic field-order serialization** — live regression for the NPE-500 class: POST the same
   logical requirement with the verified-good order (`name, parent-id, type-id`) and confirm 201; a
   negative variant (documented for awareness, not run against the live server routinely, since it's
   known to 500) stays as a unit-test-level fixture, not a live contract test (`api-ref §3.2`).
2. **5xx-verify-by-query behaviour**: mock a 500-that-committed at the HTTP-client layer (contract-test
   infra, not live-server) and assert the write-safety component issues a verify-by-GET before
   surfacing any failure to the caller, per the "one 500 silently committed a row" finding
   (`api-ref §3.3`).
3. **Multipart body byte-format**: integration-test the chosen multipart client library against the
   real sandbox server for `ref-subtype=1` attachment upload — this is a named stack risk (D2, `api-ref
   §6.6`: a PowerShell `-Form` constructor produced a body the server rejected; a hand-built body with
   explicit boundary/CRLF discipline and file-part-last worked). Must run against the live server, not
   just a mock, because the failure mode is server-specific body parsing.
4. **Canonicalized rich-text round-trip**: PUT a torture-HTML block, GET it back, canonicalize both
   sides, assert equivalence tolerating implicit `<tbody>` insertion, whitespace pretty-printing, and
   `<script>` stripping (`api-ref §7`).
5. **Entity-encoded param tokens**: PUT a design-step with `&lt;&lt;&lt;name&gt;&gt;&gt;` in its
   description, GET it back, assert the token survives and `has-params` flips to `Y`; a companion test
   confirms a *raw* `<<<name>>>` token is mangled to `<<>>` on this server, documenting the hazard
   rather than silently avoiding it (`data-model §6`).
6. **Fast_Run synthesis flow**: create test-set → test-instance → `PUT test-instances/{id}.status` →
   assert a `Fast_Run_...` run is synthesized with run-steps auto-copied matching the design-step count
   exactly, and that a direct `POST runs` is *not* attempted anywhere in the write path
   (`data-model §2.9`, `probe log` Probe 6).
7. **Bulk 409 per-item parsing**: live bulk write against the sandbox with a mix of valid/invalid
   entities, assert the client correctly separates per-item success/failure from the 409 body rather
   than treating the whole batch as failed (`api-ref §4.5`).
8. **XSRF-missing 401 handling**: a contract test that deliberately omits `X-XSRF-TOKEN` on a POST and
   asserts 401 with the documented `qccore.general-error` envelope, and — separately — asserts the
   client's normal path always includes the header (`api-ref §2.2`).
9. **Page-size silent-cap behaviour**: request `page-size` above 2000 against a collection with more
   than 2000 rows (or assert the mechanism against a smaller documented cap in a scoped-down test) and
   confirm the client detects the silent cap rather than trusting `TotalResults` naively (`api-ref
   §4.4`).
10. **Masking tests**: programmatic scan of every contract-test log/fixture output for the literal
    client-id/secret values loaded from `Secrets/ALM_API_credentials.json` — asserts zero occurrences,
    mirroring the probe scripts' own masking verification (`probe log` fixtures section: "masking
    verified programmatically against raw secret values — clean"). This test must run even when the
    rest of the contract suite is skipped, wherever any live-credentialed code path exists.

## 5. Layer 4 — E2E UI tests

- **Primary**: SPA driven against a stubbed BFF (fixture-backed fake responses) — covers grid
  rendering, form validation, tree navigation, generator wizard flows, without touching the network or
  requiring credentials. Runs on every commit alongside layers 1–2.
- **Secondary, thin**: a small smoke set against the real sandbox (login → view a requirement → create
  an `ALTALM-*`-prefixed requirement → delete it → orphan sweep) — opt-in/credential-gated like layer
  3, exists to catch BFF↔real-ALM integration breaks that a stubbed BFF cannot.

---

## 6. Generator-specific tests

- **Seed reproducibility**: identical seed → identical dry-run plan, byte-for-byte (unit-level, no
  server).
- **Dry-run golden files**: a fixed seed's dry-run output is checked against a committed golden file;
  a deliberate plan-shape change requires an explicit golden-file update in the same PR, catching
  accidental DAG-order or strategy drift.
- **Allowlist refusal tests**: attempting a write against any domain/project not on the explicit
  allowlist must be refused with zero HTTP calls made — verified by asserting the mock HTTP layer saw
  no requests, not just that the response was an error (D6, `CLAUDE.md` hard constraint).
- **Cleanup completeness**: after a full generator run against the sandbox, the cleanup sweep by
  `ALTALM-GEN-<runid>` prefix must return zero orphans — this is itself a contract test, not a manual
  verification step, and must fail loudly (not just log) if any prefixed record survives.

---

## 7. CI shape

| Runs on every commit | Runs nightly / manual only |
|---|---|
| Layer 1 (unit) | Layer 3 (sandbox contract tests) — credential-gated, opt-in |
| Layer 2 (fixture-based) | E2E secondary smoke set against real sandbox — credential-gated |
| E2E primary (stubbed BFF) | Cross-instance consistency re-runs, if a second sandbox becomes available (`data-model §7`, single-sandbox evidence caveat) |
| Masking scan (layer 3 item #10) — runs whenever any credentialed path is exercised, including nightly | |

Every-commit layers never require `Secrets/` to be present or configured — a fork/contributor without
sandbox access can run the full every-commit suite. Nightly/manual layers fail closed (skip, not
fake-pass) when credentials are absent, and must never print or log credential values regardless of
pass/fail (masking scan enforces this as a standing check, not a one-time audit).
