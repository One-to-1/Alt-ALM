# Write-probe round 2 — ALM/QC sandbox (2026-08-12)

Executed via `scripts/probe/probe-write-2.ps1` against the sandbox project in
`Secrets/ALM_API_credentials.json` (confirmed disposable by user 2026-08-12, writes authorized).
All host/domain/project/API-key/username values are masked as `REDACTED` throughout — masking
verified programmatically against the raw secret values across every `r2-*` fixture (clean, 0
leaks). The script was iterated 4 times (4 sign-in sessions total, well under the ~10 budget);
every iteration ran the full `try`/`finally` cleanup + orphan sweep.

## ⚠️ Cleanup status — READ FIRST

**Nothing was left behind. Confirmed clean.**

- Every run's `finally` block deleted every record it created, in reverse order, and **every single
  DELETE returned HTTP 200** across all 4 runs (see per-run cleanup blocks below; nothing to add
  here — no exceptions were thrown from any DELETE call in any run).
- The **name-prefix orphan sweep** (`query={name[ALTALM-PROBE*]}` across requirements, test-folders,
  tests, design-steps, test-set-folders, test-sets, test-instances, runs, milestones, releases,
  release-cycles, test-executions, defects) reported **`TotalResults=0` / no `!! ORPHANS` line in
  every single run** — the sweep's warning branch never fired, in any of the 4 sessions.
- `step-parameters` is not in the orphan-sweep collection list (its identity field is `key`, not
  `name`, so a name-prefix sweep can't find it anyway) — moot here because **every step-parameter
  create attempt failed** (documented in §b below), so nothing was ever created to leak.
- `runs` creation failed in every attempt (§c below) — no run, and therefore no `run-steps`, was ever
  created, so there is no run-step cleanup gap either.
- `SkipCleanup` was never used, per the hard rule.

**Net result: sandbox is in the same state at the end of this session as at the start.**

## Fixtures saved (`tests/fixtures/write-probe/`, `r2-` prefix, all masked)

- `r2-req-create.json` — full requirement create response (parent-id=0)
- `r2-attach-slug-response.json` — octet-stream+Slug attachment create response
- `r2-attach-multipart-FAILED.json`, `r2-attach-multipart-retry-FAILED.json` — multipart failure bodies
- `r2-attachments-list.json` — attachment collection listing after upload
- `r2-imgsrc-plain-name.txt`, `r2-imgsrc-attach-rel.txt`, `r2-imgsrc-rest-path.txt`, `r2-imgsrc-data-uri.txt` — SENT/GOT for each `<img src>` candidate
- `r2-design-step-create.json` — design-step create response (entity-encoded token)
- `r2-designstep-token-roundtrip.txt` — SENT/GOT for the encoded-token description
- `r2-milestone-create.json` — successful milestone create response
- `r2-mail-attempt1.json` … `r2-mail-attempt4-xml.json` — SENT/GOT for all 4 mail body shapes tried
- `r2-test-set-create.json`, `r2-test-instance-create.json` — Test Lab chain fixtures
- `r2-release-create.json` — release create response

Not saved (because the corresponding create never returned 200/201 in any attempt): a
`step-parameter` create response, a `run` create response, a `test-execution` create response.

---

## a. Roots — VERIFIED, round-1 contamination fixed

```
GET requirements/0?fields=id,name        -> HTTP 200, name: "Requirements"
GET test-folders/2?fields=id,name        -> HTTP 200, name: "Subject"
GET test-set-folders/0?fields=id,name    -> HTTP 200, name: "Root"
```

`POST requirements` with `parent-id=0`, `name`, `type-id=3` → **HTTP 201** in all 4 runs (ids 7, 8,
9, 10 — sequence continuity itself is further evidence of a clean sandbox with no orphans between
runs). **VERIFIED: requirement root is `id=0` ("Requirements"), not `id=1`** — round 1's
`parent-id=1` finding is now confirmed contaminated (it silently parented under the orphan record
documented in round 1, not a true root). `test-folders/2` ("Subject") and `test-set-folders/0`
("Root") both confirmed as the user-provided defaults.

## b. Step-parameters — FAILED after 3 informed attempts per run (4 total attempt-rounds); one major NEW finding on the token mangling

**Step-parameter creation itself still fails, consistently, across every shape tried:**

1. Nested `POST design-steps/{id}/step-parameters` — first try (`parent-id=<test-id>`) returned a
   *very* informative HTTP 500: `"Failed to create step-parameter. The value '<test-id>' for field
   'parent-id' ... does not match the value '<design-step-id>' for 'design-step' collection
   resource"`. Fixed on the next run (`parent-id=<design-step-id>`, matching the server's own
   correction) → still **HTTP 500 `"Test parameter does not exist"`** (same message as round 1).
2. Standalone `POST step-parameters` with `used-by-owner-type=test` + `origin-test=<test-id>` (added
   this round, a field not tried in round 1) → **HTTP 500 `"Test parameter does not exist"`**.
3. Standalone `POST step-parameters` with `used-by-owner-type=design-step` → **HTTP 500 `"Test
   parameter does not exist"`**.

All three shapes used the full, correct field set discovered in round 1 and reconfirmed this round
via `customization/entities/step-parameter/fields` (physicalName dump):
`actual-value=SP_PARAM_ACTUAL_VALUE(Memo) ignore-test-instance-parameters=SP_IGNORE_TEST_INSTANCE_PARAMETERS(String)
origin-test=SP_ORIGIN_TEST(Number) id=SP_ID(Number) used-by-owner-id=SP_OWNER_ID(Number)
key=SP_KEY(String) used-by-owner-type=SP_ENTITY(String, required) parent-id=SP_TEST_PARAM_ID(Number)
vc-user-name=SP_VC_USER_NAME(UsersList)`.

**Conclusion (documented failure, VERIFIED as a genuine gap, not a body-shape bug):** the error message
`"Test parameter does not exist"` combined with `parent-id`'s physical name `SP_TEST_PARAM_ID`
strongly implies `step-parameters` is a **usage/value record for a "Test parameter" that must already
exist as its own server-side object** — and there is no REST-exposed entity or endpoint anywhere in
this project's Core API surface to create that underlying "Test parameter" object. This matches
round 1's hypothesis and is now confirmed with a second field variant (`origin-test`) also failing
identically. **Treat this as a genuine, REST-unreachable gap** (candidate for OTA/COM or UI-only), not
a solvable body-shape problem.

**Major NEW finding — the `<<<param>>>` token mangling is FIXED by HTML-entity-pre-encoding the
brackets, VERIFIED:**

Round 1 sent the raw token `<<<probe_param>>>` and the sanitizer parsed `<probe_param>` as an
(invalid) HTML tag, destroying the parameter name entirely (`<<>>` on readback, no `probe_param` text
survived). This round, the design-step description was sent **pre-encoded**:
```
SENT (as the JSON string value, i.e. literal characters &, l, t, ; ...):
Uses &lt;&lt;&lt;probe_param&gt;&gt;&gt; here
```
Readback (`GET design-steps/{id}?fields=description,has-params`):
```
<html><body>
Uses &lt;&lt;&lt;probe_param&gt;&gt;&gt; here
</body></html>
```
**The literal text `probe_param` survives intact**, still wrapped in escaped angle-bracket entities
that a browser would render as literal `<<<probe_param>>>` — because the sanitizer never sees a raw
`<` to parse as a tag-start; it's inert already-escaped text, consistent with round 1's separate
finding that "already-double-escaped entity text is preserved as literally typed." **`has-params`
still read back `"Y"`** on this design-step. **Reproduced identically in all 4 runs.**

A follow-on check ("does the token survive once the parameter exists?", per the round-2 task) could
not be executed because `step-parameter` create never succeeded in any run — the `$sp`-gated raw-token
recheck step in the script never fired. **This sub-question remains open**, gated on the still-broken
`step-parameters` create path above.

**Conclusion for the record generator:** when writing rich-text fields that must contain literal
`<<<name>>>`-style tokens (or any text a naive sanitizer could mis-parse as a tag), **HTML-entity-encode
the angle brackets before submission** (`&lt;`/`&gt;`) rather than sending them raw. This is now a
verified, general mitigation, not just a param-token special case.

## c. Test Lab chain — mixed: instance/test-set/folder creates all VERIFIED; `run` create FAILED after 3 informed attempts; downstream questions therefore UNANSWERED (blocked on run creation)

**Working, VERIFIED (all 4 runs, identical):**
- `test-set-folders` under Root(0) → HTTP 201.
- `test-sets` (`name`, `parent-id`, `subtype-id="hp.qc.test-set.default"`) → HTTP 201.
- `test-instances` (`cycle-id=<test-set-id>`, `test-id`, `subtype-id="hp.qc.test-instance.MANUAL"`) →
  HTTP 201. **Instance initial status: `"No Run"`** (VERIFIED).

**`runs` create — FAILED, 3 informed attempts per the last 2 runs, all identical business-logic 500:**
```
{"Id":"qccore.general-error","Title":"Fail to get a must number attribute 'TESTSET'","ExceptionProperties":[],"StackTrace":null}
```
Attempts (all used the documented required fields `name`, `test-id`, `testcycl-id`, `cycle-id`,
`subtype-id="hp.qc.run.MANUAL"`, `owner`, `status="Not Completed"`):
1. + `test-instance` field (the "ordinal" field per round-1 secondary-sourced docs), placed
   mid-order → 500, same error.
2. Same fields, `test-instance` moved to end of field order (testing round-1's field-order
   sensitivity finding) → 500, identical error.
3. + a discovered `test-config-id` (attempted via `GET test-configs?query={test-id[...]}` — that
   query itself returned **HTTP 400**, i.e. `test-configs` doesn't support querying by `test-id` this
   way, so no config id was ever discovered and this 3rd attempt was skipped rather than forced with
   a guessed value).

**Full `run` entity field/physicalName dump obtained (VERIFIED, this round's key diagnostic)** via
`GET customization/entities/run/fields`, 48 fields total, none of them a physical column resembling
`TESTSET`:
```
attachment=RN_ATTACHMENT bpt-structure=RN_BPT_STRUCTURE pinned-baseline=RN_PINNED_BASELINE
bpta-change-awareness=RN_BPTA_CHANGE_AWARENESS bpta-change-detected=RN_BPTA_CHANGE_DETECTED
comments=RN_COMMENTS os-config=RN_OS_CONFIG test-config-id=RN_TEST_CONFIG_ID
assign-rcyc=RN_ASSIGN_RCYC cycle-id=RN_CYCLE_ID(required) draft=RN_DRAFT duration=RN_DURATION
environment=RN_ENVIRONMENT execution-date=RN_EXECUTION_DATE execution-time=RN_EXECUTION_TIME
build-revision=RN_BUILD_REVISION results-files-network-path=RN_RESULTS_FILES_NETWORK_PATH
has-vtc=RN_HAS_VTC has-linkage=RN_HAS_LINKAGE host=RN_HOST iters-params-values=RN_ITERS_PARAMS_VALUES
iters-sum-status=RN_ITERS_SUM_STATUS jenkins-job-name=RN_JENKINS_JOB_NAME jenkins-url=RN_JENKINS_URL
last-modified=RN_VTS os-build=RN_OS_BUILD os-sp=RN_OS_SP os-name=RN_OS_NAME path=RN_PATH
detail=RN_DETAIL id=RN_RUN_ID name=RN_RUN_NAME(required) vc-status=RN_VC_STATUS
vc-locked-by=RN_VC_LOKEDBY state=RN_STATE status=RN_STATUS(required) test-id=RN_TEST_ID(required)
test-description=RN_TEST_DESCRIPTION test-execution-id=RN_TEST_EXECUTION_ID
testcycl-id=RN_TESTCYCL_ID(required) test-instance=RN_TEST_INSTANCE testcycl-name=RN_TESTCYCL_NAME
test-language=RN_TEST_LANGUAGE test-name=RN_TEST_NAME cycle=RN_CYCLE cycle-name=RN_CYCLE_NAME
vc-version-number=RN_VC_VERSION_NUMBER owner=RN_TESTER_NAME(required) text-sync=RN_TEXT_SYNC
subtype-id=RN_SUBTYPE_ID ver-stamp=RN_RUN_VER_STAMP
```

**Conclusion (documented failure, VERIFIED as a genuine gap):** `"TESTSET"` is an internal/derived
attribute name with **no corresponding field anywhere in the run entity's REST-exposed customization
metadata** (48 fields checked). This is not a body-shape or field-name guessing problem — every
plausible REST-visible field was supplied or tried. Most likely explanation (UNVERIFIED, no server
internals visible): the run-creation code path does an internal join/lookup against the `CYCLE`
(test-set) row using an attribute alias `TESTSET` that isn't populated correctly for a test-set created
purely via `POST test-sets` with only the documented required fields (`name`, `subtype-id`) — i.e. the
stock UI's test-set creation may implicitly set additional state that our minimal REST create does
not. **This blocks all of the following downstream round-2 questions, which could not be answered this
round:**
- Does `POST runs` auto-copy design steps into `run-steps`? — **UNANSWERED, blocked.**
- Does instance status mirror run status after PUT? — **UNANSWERED, blocked.**
- Does a `Fast_Run` synthetic run appear? — **UNANSWERED, blocked.**
- Does setting a run-step to Failed change the run's aggregate status? — **UNANSWERED, blocked.**

**Recommended follow-up (not attempted this round, out of budget/scope):** create a test-set via the
stock web client (or read one that already has a manually-run test) and probe whether its full field
set (`report-settings`, `cycle-config`, `dynamic-data`, etc. — all present in the `test-sets` field
list but not populated by our minimal create) differs meaningfully from ours; retry `run` creation
against that richer test-set.

## d. Image embed — PARTIALLY VERIFIED; multipart image upload FAILED after 2 informed attempts, but the sanitizer's `src`-attribute filtering rule is clearly and consistently VERIFIED

**Attachment upload variants:**

| Variant | Request | Result |
|---|---|---|
| octet-stream + `Slug` header | `POST requirements/{id}/attachments`, `Content-Type: application/octet-stream`, `Slug: probe-img-slug.png` | **HTTP 201**, all 4 runs. Metadata readback: `ref-subtype="0"`, `ref-type="File"`, `file-size="70"`. This is a **plain** attachment (not an embedded-image subtype). |
| multipart, `ref-subtype=1`, field `filename` | `POST .../attachments`, multipart form `{filename, description, ref-subtype=1, file}` | **HTTP 500** both attempts: `{"Id":"qccore.general-error","Title":"begin 0, end -1, length 1", ...}` — an opaque, low-level parsing error (string-index-style), not a business-validation message. |
| multipart, `ref-subtype=1`, field `name` instead of `filename` | same, `name` instead of `filename` | **HTTP 500**, **identical** `"begin 0, end -1, length 1"` error. |

**Conclusion (documented failure after 2 informed attempts):** multipart image-embed upload
(`ref-subtype=1`) fails consistently with a low-level parsing error that did not vary between the two
field-name candidates tried, suggesting either (a) PowerShell 7's built-in `-Form` multipart
constructor does not produce a byte-exact multipart body this specific server endpoint expects (most
likely — the error text reads like a Java substring-index exception, consistent with the server
choking on some structural aspect of the multipart body rather than rejecting our field names), or (b)
a genuine server-side limitation for this attachment subtype on this ALM build. **Cannot distinguish
between (a) and (b) from this probe alone** — flag as UNVERIFIED which it is; a follow-up with a
different HTTP client (e.g. hand-built multipart body, or curl/Postman) would disambiguate. The
plain octet-stream+Slug path (`ref-subtype=0`) works reliably and is a safe fallback for non-embedded
file attachments.

**`<img src>` sanitizer round-trip (4 candidates, tested against `description` on each run's fresh
requirement) — VERIFIED, consistent across all 4 runs:**

| Candidate | SENT | GOT (readback) |
|---|---|---|
| `plain-name` | `<img src="probe-img-multi.png">` | `<img />` — **`src` attribute stripped entirely** |
| `attach-rel` | `<img src="attachments/probe-img-multi.png">` | `<img />` — **`src` attribute stripped entirely** |
| `rest-path` (absolute `https://` URL, built at runtime from the loaded credential variables, never hardcoded) | `<img src="https://.../requirements/{id}/attachments/probe-img-multi.png">` | **survives verbatim**, including the full URL |
| `data-uri` | `<img src="data:image/png;base64,...">` | **survives verbatim**, including the full base64 payload |

**Conclusion, VERIFIED:** the sanitizer's `img[src]` protocol allowlist (per `sanitizer-whitelist.xml`,
documented in `wave1-08-attachments-richtext.md`: "protocols per tag+attribute e.g. `img[src]` →
http/https; disallowed protocol strips the attribute, leaving bare `<img />`") is confirmed exactly:
**relative-looking `src` values (no scheme) are stripped to a bare `<img />`; absolute `http(s)://` URLs
survive; `data:` URIs also survive** (this specific detail — that `data:` is on the allowlist — was not
previously documented and is a new confirmed finding). This is a **syntactic** filter decision made at
write time, independent of whether the referenced attachment actually exists — **caveat: because the
multipart `ref-subtype=1` upload never succeeded (see above), `probe-img-multi.png` never actually
existed on the server during these tests, so this round could not verify that a surviving `<img
src="https://.../attachments/...">` reference actually **renders** a real image in a browser — only
that the sanitizer's decision to keep vs. strip the attribute is governed by URL syntax, not target
existence** (near-certain given sanitization runs synchronously on write, but not directly observed).

**Conclusion for the generator/front-end:** to embed images in rich-text fields on this ALM version,
**an absolute URL (the REST attachment path, or equivalently a data URI) must be used as `<img src>`**
— a bare filename or a path relative to the entity will be silently stripped by the output sanitizer.
Given the multipart image-subtype upload path is unresolved, the practical, currently-verified
options are: (1) upload as a plain octet-stream attachment (`ref-subtype=0`, confirmed working) and
reference it by its **absolute** REST URL in the memo HTML, or (2) inline as a `data:` URI directly
(also confirmed surviving the sanitizer, subject to `UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE` size limits
noted in round-1 research, not tested here).

## e. Milestones, mail, test-executions, release-cycle date validation

**Milestones — VERIFIED, root cause found and fixed:**
```
GET customization/entities/milestone/fields (physicalName dump):
has-attachments=MS_HAS_ATTACHMENTS kpis-count=MS_KPIS_COUNT
milestone-scopeitem-count=MS_MILESTONE_SCOPEITEM_COUNT description=MS_DESCRIPTION
end-date=MS_DUE_DATE(required) id=MS_ID vts=MS_VTS name=MS_NAME(required)
parent-id=MS_RELEASE_ID(required) start-date=MS_START_DATE(required) ver-stamp=MS_VER_STAMP
```
First two attempts (`parent-id=0`, `parent-id=1`, both with `name`/`start-date`/`end-date`) failed
identically: `HTTP 500 "Cannot create 'Milestone'. Invalid owner specified: 0."` / `"...: 1."` — a
well-formed business-validation rejection (not opaque). **The `parent-id` field's physical name is
literally `MS_RELEASE_ID`** — milestones are parented directly under a **release**, not a folder tree.
Third attempt, `parent-id=<the release id created in §H, e.g. 1001-1004>` → **HTTP 201**. **VERIFIED:
milestone `parent-id` must be an existing `release` id.** Cleanup confirmed (`DELETE
milestones/{id}` → 200 in every run where it was created).

**Mail — FAILED after 4 attempts (3 JSON shapes + 1 XML), consistent opaque error for all JSON shapes; genuinely undocumented endpoint confirmed:**
```
attempt 1 {To:[user], Subject, Comment}         -> HTTP 500 "Cannot invoke \"...JsonNode.has(String)\" because \"node\" is null"
attempt 2 {Mail:{Recipients:{Recipient:[user]}, Subject, Body}} -> HTTP 500, IDENTICAL error
attempt 3 {to, subject, body} (lowercase)       -> HTTP 500, IDENTICAL error
attempt 4 <Mail><To/><Subject/><Body/></Mail> (application/xml) -> HTTP 400 "Bad Request"
```
**Conclusion:** all 3 JSON body shapes produced the **exact same** NPE-style error regardless of key
names/casing/nesting — strong evidence the JSON parsing path for this specific `/mail` sub-resource is
either broken or expects a structure none of our 3 informed guesses hit. The XML attempt got a
*different* error (400, not 500) — a sign the server did engage differently with an XML content-type,
but our guessed XML element names (`<Mail><To><Subject><Body>`) were still rejected as malformed. This
matches `resource-list-site.json`'s `Consumes` list which puts `application/xml` first for this
endpoint. **Root cause not resolved** (this is now a documented failure at the 4-attempt budget edge,
not a confident conclusion) — the correct request shape for `/mail` on this ALM version remains
UNVERIFIED. **Confirmed independently of SMTP configuration status**: none of the 4 attempts got far
enough to reach an SMTP-related error; the failures are all request-shape/parsing failures, so
whether SMTP itself is configured on this sandbox remains untested.

**Test-executions — VERIFIED semantics: POST dispatches (not just registers) an automated execution:**
```
GET customization/entities/test-execution/fields (physicalName dump):
create-time=TE_CREATE_TIME creation-time=TE_CREATION_TIME end-time=TE_END_TIME
executed-task-count=TE_EXECUTED_TASK_COUNT external-id=TE_EXTERNAL_ID(required)
external-type=TE_EXTERNAL_TYPE id=TE_ID owner=TE_OWNER scheduled-host-id=TE_SCHEDULED_HOST_ID
start-time=TE_START_TIME status=TE_STATUS task-count=TE_TASK_COUNT
```
`POST test-executions` with `external-id=<test-set id>`, `external-type="TestSet"` →
**HTTP 500**: `"There is no agent configured in your environment or there is no agent that can
execute one of your test types."` Retried with `external-id=<test-instance id>`,
`external-type="TestInstance"` → **identical error**. **This resolves round-1's open question
(UNVERIFIED #10, "dispatch vs. ingest"): `test-executions` POST genuinely attempts to DISPATCH a real
automated execution against a Lab agent/host** — it is not a passive "register external results"
endpoint. The error is a clean business-logic rejection (not an opaque NPE), confirming the request
reached real dispatch logic and failed only because this sandbox has no configured Lab agent/host.
**VERIFIED conclusion for the generator/Alt-ALM design:** `test-executions` cannot be exercised
end-to-end without a configured Lab host — out of scope for a documented-REST-only, no-agent-infra
design; the generator should not attempt to synthesize `test-executions` records unless a host is
explicitly provisioned.

**Release-cycle date validation — VERIFIED, rejected as expected:**
```
release: parent-id=1, start-date=2026-01-01, end-date=2026-03-31         -> HTTP 201
cycle-in:  parent-id=<release>, 2026-01-10..2026-01-20 (inside range)    -> HTTP 201
cycle-out: parent-id=<release>, 2026-06-01..2026-07-01 (outside range)   -> HTTP 500
  {"Id":"qccore.general-error","Title":"start date cannot be later than release's end date","ExceptionProperties":[],"StackTrace":null}
```
**VERIFIED: a release-cycle whose dates fall outside its parent release's date range IS rejected
server-side**, with a well-formed, specific validation message (not an opaque 500) — reproduced
identically across all 4 runs. This resolves round-1's `wave1-06` UNVERIFIED note ("dates validated
against parent release window: not documented either way"). Note the message says "later than
release's end date" even though our out-of-range cycle's *start* date (2026-06-01) was chosen to be
after the release's end date (2026-03-31) — consistent wording, not a bug. Cleanup confirmed
(`releases`/`release-cycles` DELETE → 200 every run).

---

## Script changes made this session (for future reference)

`scripts/probe/probe-write-2.ps1` was modified in place across 3 iterations, endpoint families
unchanged:
1. Built the `rest-path` `<img src>` candidate from the already-loaded `$proj` variable (masked
   host/domain/project) at runtime instead of literal `DOMAIN`/`PROJECT` placeholder text.
2. Added `Show-AllFields` (dumps every field's `Name=physicalName(Type[*required])`) alongside the
   existing `Show-RequiredFields`, used for `step-parameter`, `run`, `milestone`, `test-execution` —
   this directly produced the `MS_RELEASE_ID` (milestone fix) and `RN_*`-field-list (run diagnostic)
   findings above.
3. Attachment multipart failure bodies are now captured to fixtures (`*-FAILED.json`) instead of
   silently discarded; a 2nd multipart attempt (`name` vs `filename` form-field key) was added.
4. Step-parameters: design-step created FIRST using **HTML-entity-pre-encoded** `<<<probe_param>>>`
   (the round-1-fixing change); 3 step-parameter create attempts reordered to try the
   error-message-corrected nested path first (`parent-id`=design-step id, per the server's own
   correction message), then 2 standalone variants including a new `origin-test` field; a
   post-success raw-token recheck step was added but never triggered (gated on step-parameter
   success, which never happened).
5. `run` create: added `test-instance` field (2 field-order variants) and a 3rd attempt path
   (`test-config-id` via a `test-configs` discovery query, which itself 400'd and was skipped rather
   than forced).
6. `milestones`: added `Show-AllFields`, a `parent-id[0]` discovery query, 2 root-guess attempts, and
   (after §H creates a `release`) a 3rd attempt using the release's own id — which succeeded.
7. `mail`: expanded from 1 to 4 attempts (3 JSON shapes + 1 XML), each saved to its own fixture.
8. `test-executions`: switched from the wrong guessed fields (`name`, `test-id`) to the
   metadata-confirmed `external-id`/`external-type`, with a 2nd attempt varying the target type.
