# Write-probe round 3 — ALM/QC sandbox (2026-08-12)

Executed via `scripts/probe/probe-write-3.ps1` against the sandbox project in
`Secrets/ALM_API_credentials.json` (confirmed disposable by user 2026-08-12, writes authorized).
All host/domain/project/API-key/api-secret/username values are masked as `REDACTED` throughout.
The script was run 3 times (3 sign-in sessions total, well under the ~8 budget), editing the B3
JSON-POST field set between runs to isolate which fields shift the server's error behaviour, per
the task's "iterate intelligently" instruction. Every run executed the full `try`/`finally`
cleanup + orphan sweep.

## ⚠️ Cleanup status — READ FIRST

**Nothing was left behind. Confirmed clean.**

- All 3 runs: every record created (test-folder, test, design-step(s), test-set-folder, test-set,
  test-instance, the synthetic Fast_Run, the attachment-bearing requirement) was deleted in
  reverse order in `finally`, and **every DELETE returned HTTP 200** in all 3 runs, including
  `DELETE runs/{fastRunId}` for the server-created Fast_Run each time (explicitly in scope per the
  task's exception for the synthetic run our probe chain triggers).
- The **name-prefix orphan sweep** (`query={name[ALTALM-PROBE*]}` across requirements,
  test-folders, tests, design-steps, test-set-folders, test-sets, test-instances, runs,
  milestones, releases, release-cycles, test-executions, defects) reported **no `!! ORPHANS` line
  in any of the 3 runs** — `TotalResults=0` everywhere the query itself succeeded.
  (`test-instances` and `test-executions` return HTTP 400/404 on this name-query shape — a
  pre-existing collection quirk noted in round 2 already, not a leak; neither collection had any
  surviving record regardless, since every test-instance created was deleted in `finally` and no
  test-execution was ever created this round.)
- One transient anomaly, unrelated to cleanup: in run 2, the *first* of two `design-steps` POSTs
  returned `HTTP 500 "General Error"` (no further message) while the identical second POST
  succeeded — a one-off server hiccup, not reproduced in runs 1 or 3, and not chased further (out
  of scope for this round's questions). Because the failed POST never returned an `id`, it was
  never added to the cleanup list and there is nothing to leak from it; the orphan sweep for that
  run also came back clean.
- `SkipCleanup` was never used, per the hard rule.
- **Masking verified programmatically** post-hoc: every `r3-*` fixture file was checked against
  the raw secret values (`alm_adress`, derived host, `api_key`, `api_secret`, `domain`,
  `project`) loaded in-memory from `Secrets/ALM_API_credentials.json` — **0 leaks across all 4
  saved fixtures.**

**Net result: sandbox is in the same state at the end of this session as at the start.**

## Fixtures saved (`tests/fixtures/write-probe/`, `r3-` prefix, all masked)

- `r3-fastrun-full-entity.json` — full field dump of a server-generated `Fast_Run` (from run 3;
  runs 1/2 produced byte-identical shapes for their own ids, values below are consistent across
  all 3)
- `r3-run-steps.json` — the 2 auto-copied `run-steps` under that Fast_Run, showing `desstep-id`
  linkage back to the source design-steps
- `r3-attach-multipart-refsubtype1.json` — the successful hand-built multipart `ref-subtype=1`
  attachment create response
- `r3-attachments-list.json` — attachment collection listing confirming the readback

Not saved (because no attempt ever returned 200/201): any `r3-run-create-*.json` variant — every
direct `POST runs` attempt (XML and 5 JSON field-set variants, across all 3 runs) failed; the
exact failure bodies are quoted below instead, captured from console output.

---

## 1. Components / business-components — READ-ONLY GET, settled

```
GET components?page-size=1            -> HTTP 403 {"Id":"qccore.operation-forbidden","Title":"Access to this resource has been denied", ...}
GET business-components?page-size=1   -> HTTP 404 {"Id":"qccore.general-error","Title":"Not Found", ...}
```

Reproduced identically in all 3 runs. **VERIFIED, settles the question:** `components` is a real
REST resource on this server (it returns a structured `qccore.operation-forbidden` business error,
not a generic 404) but this API key/user has **no permission/license to read it** — most likely a
BPT-module license gate, not a code-path gap. `business-components` genuinely **does not exist**
as an endpoint on this server/version (plain "Not Found", same shape as any unmapped route). This
resolves the earlier inventory's "absent" flag: `components` is present-but-forbidden,
`business-components` is truly absent.

## 2. Run creation via `POST runs` — still FAILED, but now precisely diagnosed with two distinct, reproducible failure signatures

**Route B1 (indirect, via test-instance status PUT) is the only way to get a `run` on this server,
and it works every time:**

```
PUT test-instances/{id} {status: Passed}  -> HTTP 200
GET runs?query={testcycl-id[{instanceId}]}  -> TotalResults=1  (a "Fast_Run_MM-DD_HH-MM-SS" appears)
```
Reproduced in all 3 runs (Fast_Run ids 8, 11, 16). This is a genuine, VERIFIED, REST-reachable path
to get a run into existence — just not via a direct entity `POST`.

**Route B2 (legacy XML `Entity/Fields/Field/Value` body) and the baseline JSON shape both fail
identically, reproduced 3/3 runs:**
```
{"Id":"qccore.general-error","Title":"Fail to get a must number attribute 'TESTSET'","ExceptionProperties":[],"StackTrace":null}
```
This is unchanged from round 2 even with content-type switched to `application/xml` — rules out
"JSON parser bug" as the cause.

**Fast_Run's full field dump (VERIFIED, `r3-fastrun-full-entity.json`) — every populated field:**
```
test-id, test-name, has-linkage=N, cycle-id, draft=N, id, test-config-id, ver-stamp,
name=Fast_Run_..., testcycl-name="<test-name> [1]", status=Passed, duration=0, execution-date,
last-modified, subtype-id=hp.qc.run.MANUAL, owner, test-instance=1, cycle-name="<test-set-name>",
execution-time, testcycl-id
```
Cross-referencing `cycle-name` (= the test-set's own name) against `cycle-id` and `testcycl-name`
(= "`<test-name>` [1]", the test-instance display pattern) against `testcycl-id` **disambiguates
round 2's open question: `cycle-id` = the test-**set** id, `testcycl-id` = the test-**instance**
id** — confirming the mapping the round-2/round-3 scripts already assumed.

**Systematically isolated which additional fields change the server's behaviour (round 3's key new
finding), using 5 JSON field-set variants across runs 1–3, all built from Fast_Run's own values:**

| Variant | Extra fields vs. baseline | Result |
|---|---|---|
| B3 (baseline + `test-config-id`) | `test-config-id` from the test-instance | same `TESTSET` error |
| B3b / B3e | + `test-instance=1` (the ordinal field) | same `TESTSET` error — **ordinal field alone changes nothing** |
| B3c | + `test-instance=1` **and** `test-name`/`testcycl-name`/`cycle-name` | **different error: `"Failed to post step"`** |
| B3d | + `test-name`/`testcycl-name`/`cycle-name` **only** (no ordinal) | **same new error: `"Failed to post step"`** |

**VERIFIED, cleanly isolated across independent runs (2 and 3):** supplying the three denormalized
display-name fields (`test-name`, `testcycl-name`, `cycle-name`) — not the `test-instance` ordinal,
not `test-config-id` — is what shifts the server past the `TESTSET` check into a different,
later-stage business error, `"Failed to post step"`. Both are well-formed `qccore.general-error`
business rejections (not opaque parse errors), so the request is reaching real server logic in
both cases; it never proceeds to actually create a `run` row via `POST runs` under any field
combination tried across both probe rounds (round 2: 3 attempts; round 3: 5 attempts; 8 total,
zero successes).

**Conclusion (documented failure, now well-characterized, not just a body-shape guess):**
`POST runs` on this ALM build appears to require internal denormalized state (`TESTSET`, and then
whatever backs `"post step"` — plausibly the same code path that auto-copies design-steps into
run-steps, since that only happens automatically for the instance-status-PUT route) that a
minimal, spec-compliant REST create cannot supply — the two-stage error progression (structural
lookup fails → then, once you feed it the display-name fields, a *different* internal step-copy
operation fails) is strong evidence this is a genuine server-side gap in the direct-POST code path,
not a discoverable field. **Practical guidance for Alt-ALM / the record generator: create runs
exclusively via the test-instance status-PUT route (B1), never via `POST runs` directly.**

## 3. Section C — run-steps auto-copy, status PUT, instance mirror, aggregation (all run against the Fast_Run from B1, since it's the only run obtainable)

All reproduced in runs 1 and 3 (run 2 had only 1 design-step surviving due to the transient
design-step POST failure noted above, which is itself a confirming data point — see below).

- **Run-steps auto-copy: VERIFIED, count matches the design-step count exactly, not a fixed
  number.** Run 1 and run 3 each had 2 design-steps -> `run-steps` `TotalResults=2`. Run 2 had
  only 1 surviving design-step (the other POST 500'd) -> `TotalResults=1`. `r3-run-steps.json`
  shows each `run-step`'s `desstep-id` field pointing back at the source design-step id, with
  `name`/`description`/`expected` copied verbatim and `step-order` preserved (1, 2); each
  individual step's own `status` reads **`"No Run"`** even though the parent run already shows
  `status=Passed` — i.e., the run-level status set by the Fast_Run mechanism is independent of
  (not aggregated from) the individual step statuses.
- **Run PUT status=Passed: VERIFIED, HTTP 200** in both runs (redundant with the Fast_Run's
  already-Passed status, but confirms the field is writable).
- **Instance status mirror: VERIFIED.** `GET test-instances/{id}?fields=status` after the run PUT
  reads **`"Passed"`** — confirms the instance status tracks/mirrors the run status.
- **Run-step Failed -> run status aggregation: VERIFIED absent (no automatic recompute
  observed).** `PUT run-steps/{id} {status: Failed}` returns HTTP 200, but the immediately
  following `GET runs/{id}?fields=status` still reads **`"Passed"`**, unchanged, in both runs
  where this was tested. **Caveat:** the run's status had already been explicitly set to `Passed`
  (both by the Fast_Run mechanism itself and by our own PUT in the same step) before the run-step
  was flipped to Failed, so this shows the server does **not** eagerly recompute run status from
  run-step statuses on a simple field PUT — it does not rule out a separate, unexercised
  recompute/rollup endpoint or a UI-side aggregation. Treat as VERIFIED for "no automatic
  server-side aggregation via REST PUT," UNVERIFIED beyond that.

## 4. Multipart `ref-subtype=1` attachment upload — RESOLVED, VERIFIED working with a hand-built body

Round 2 hypothesized the opaque `"begin 0, end -1, length 1"` 500 was likely a byte-level defect in
PowerShell's built-in `-Form` multipart constructor rather than a genuine server limitation. Round
3 replaced it with a hand-built `multipart/form-data` body (explicit boundary, `\r\n` discipline
throughout, text parts `filename`/`description`/`ref-subtype=1` first, binary `file` part last with
`Content-Type: image/png`) and it **succeeded on all 3 runs**:

```
POST requirements/{id}/attachments  (hand-built multipart, ref-subtype=1)  -> HTTP 201
readback: name="probe-embed.png", ref-subtype="1", ref-type="File", file-size="70"
```

**VERIFIED: hypothesis (a) from round 2 was correct** — the server accepts `ref-subtype=1`
multipart image attachments fine; the round-2 failure was purely an artifact of PowerShell's
`-Form` parameter not producing a byte-exact multipart body for this endpoint. The exact
byte-layout that works: boundary line, then for each of `filename`, `description`, `ref-subtype`
a `Content-Disposition: form-data; name="<field>"` header + blank line + value + CRLF, then the
`file` part with `Content-Disposition: form-data; name="file"; filename="probe-embed.png"` +
`Content-Type: image/png` + blank line + raw PNG bytes, then the closing boundary. No further
variation was needed — first hand-built attempt worked, so no "omit description" / field-order
fallback was required.

## 5. Design-step transient 500 (anomaly, not a probe question)

Run 2's first of two `design-steps` POSTs returned `HTTP 500 "General Error"` with no further
detail; the byte-identical second POST (same run) succeeded. Not reproduced in runs 1 or 3 (both
2-for-2). Logged here for completeness only — insufficient signal to characterize further within
this round's budget, and it self-corrected on the very next identical call.

---

## Script changes made this session (for future reference)

`scripts/probe/probe-write-3.ps1` was extended in place across the 3 iterations (no new endpoint
families touched):
1. Ran as-shipped first (session 1): got Fast_Run (B1), reproduced the `TESTSET` error on both XML
   (B2) and the Fast_Run-informed JSON (B3, added `test-config-id`), completed section C, and
   solved the multipart problem (D) on the first hand-built attempt.
2. Added `B3b` (+ `test-instance=1` ordinal) and `B3c` (+ ordinal **and** the three denormalized
   name fields `test-name`/`testcycl-name`/`cycle-name`) for session 2 — `B3c` was the first
   variant to produce a *different* error (`"Failed to post step"`), an important signal.
3. Added `B3d` (name fields only, isolating out the ordinal) and `B3e` (ordinal only, re-confirming
   B3b) for session 3, to cleanly attribute the error-shift to the name fields specifically rather
   than the ordinal or some combination — confirmed: name fields alone reproduce `"Failed to post
   step"`; ordinal alone reproduces the original `TESTSET` error.

No endpoint families outside the authorized list were touched; no `-SkipCleanup`; no `/sa/` calls.
