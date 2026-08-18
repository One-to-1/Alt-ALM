# Entity-page fixtures — HAND-AUTHORED, not captures

⚠️ **Read this before treating anything here as evidence.** Every other fixture directory in this
repo holds *redacted captures of real server responses*. This one does not. These files were written
by hand to exercise `AlmEntityParser`, and they are only as trustworthy as the reasoning below.

| Fixture | Envelope shape | Status |
|---|---|---|
| `entity-page-multi-row.json` | `entities[]` + `Fields[].values[].value`, `TotalResults` | **Probe-verified** (probe 15 §15.2 captured this exact shape from the live server) |
| `entity-page-empty.json` | `{"entities":[],"TotalResults":0}` | **Probe-verified** — empty collections were read repeatedly in probes 15–16 |
| `entity-page-null-value.json` | a JSON `null` inside `values` | **Shape verified, placement inferred.** Probe 15 observed `"referenceValue":null` in a sibling (group-by) response; a null inside an entity's `values` array is the analogous case and has not itself been captured |
| `entity-page-multivalue.json` | two entries in one `values` array | **Inferred.** `values` is unambiguously an array on the wire, and the data model documents exactly two multivalue fields in the whole product — but no captured response in this repo actually contains one |
| `entity-page-total-results-mismatch.json` | 2 rows alongside `TotalResults: 0` | **Probe-verified behaviour** (probe 15 §15.3: `page-size=0` returns `TotalResults=0` on a non-empty collection), reproduced here as a fixture |
| `entity-page-entity-status-error.json` | `EntityStatus: "Failure"` + `ErrorMessage` | ⚠️ **Still invented, and now known to be UNREACHABLE.** See below |
| `entity-write-single.xml` | `<Entity EntityStatus="Success" ErrorMessage="" …>` | **Captured** (probe 29) — a real single-entity write response from our own sandbox, kept because it is the only evidence in the repo of where `EntityStatus` lives in the XML media type |

## The one to be careful about

`entity-page-entity-status-error.json` is a **guess about a failure mode that probe 29 has now shown
this server does not produce.** It is still invented, and it is no longer merely unverified — it is
positively contradicted as a *reachable* state:

- the literal value `"Failure"` is assumed and remains assumed — the server has never emitted any
  token but `"Success"`, so there is nothing to check it against;
- the `ErrorMessage` text is invented and must never be pattern-matched against;
- ⚠️ **every failure ALM was provoked into is reported at the REQUEST level**, as a
  `QCRestException` with `Id`/`Title`/`ExceptionProperties` and no `entities` envelope at all — not
  as a row inside a 200. That covers ~25 broken reads plus single and bulk writes in both media
  types (probe 29).

**So this fixture pins OUR contract, not ALM's.** The two tests over it
(`nonSuccessEntityStatusIsSurfacedAsError`, `missingEntityStatusDefaultsToSuccess`) are regression
guards on the parser's own defaults — they say "if this ever arrives, here is what we do" — and they
are not, and must never be cited as, evidence about the server.

Deleting it was considered and rejected: the two defaults default in *opposite* directions on
purpose (see `AlmEntityPage.AlmEntity#isError`), and an untested pair of deliberate opposites is how
one of them quietly flips during a refactor.

⚠️ The earlier instruction here — *"do not build a per-row error state in the UI on top of this"* —
was overtaken: `DetailPane` does render `row.error`. That is now knowingly dead UI on this
deployment, kept for the same asymmetric-cost reason as `isError()` itself. Do not extend it, do not
give it a prominent affordance, and do not let a screenshot of it become documentation of a feature.

## Rules for adding to this directory

- Values must be obviously synthetic (`ALTALM-SAMPLE-N`, round ids like `1001`). Never copy content
  from a real project — most reachable projects belong to other teams, and their data must not enter
  this repo (`CLAUDE.md`).
- If you capture a *real* envelope, redact it and put it in `tests/fixtures/` proper, then update the
  row above from "inferred" to "probe-verified" and cite the probe number.
- A capture from our own **sandbox** may live here (see `entity-write-single.xml`) because its
  content is ours — probe-prefixed names, empty fields. A capture from any **borrowed** project may
  not, at any level of redaction: `CLAUDE.md` forbids their data entering the repo at all, and
  "redacted" is a judgement call that only has to be wrong once.
