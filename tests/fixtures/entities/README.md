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
| `entity-page-entity-status-error.json` | `EntityStatus: "Failure"` + `ErrorMessage` | ⚠️ **UNVERIFIED — invented.** See below |

## The one to be careful about

`entity-page-entity-status-error.json` is a **guess about a failure mode nobody has observed.** Every
envelope captured from this server carries `EntityStatus:"Success"` explicitly; no probe has produced
a row that failed, so:

- the literal value `"Failure"` is assumed — the server may use a different token entirely;
- the `ErrorMessage` text is invented and should never be pattern-matched against;
- the parser's rule "absent `EntityStatus` means success" is a defensible default, not a fact.

`AlmEntityParser` treats any non-`Success` value as an error rather than matching `"Failure"`
specifically, which is what keeps this guess from becoming load-bearing. **Do not build a per-row
error state in the UI on top of this** until open item #12 in
[`live-probe-log.md`](../../../docs/research/live-probe-log.md) is settled with a real captured
failure row.

## Rules for adding to this directory

- Values must be obviously synthetic (`ALTALM-SAMPLE-N`, round ids like `1001`). Never copy content
  from a real project — most reachable projects belong to other teams, and their data must not enter
  this repo (`CLAUDE.md`).
- If you capture a *real* envelope, redact it and put it in `tests/fixtures/` proper, then update the
  row above from "inferred" to "probe-verified" and cite the probe number.
