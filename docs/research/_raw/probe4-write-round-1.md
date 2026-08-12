# Write-probe round 1 — ALM/QC sandbox (2026-08-12)

Executed via `scripts/probe/probe-write-1.ps1` against the sandbox project in
`Secrets/ALM_API_credentials.json` (confirmed disposable by user 2026-08-12). All host/domain/
project/API-key/username values are masked as `REDACTED` throughout — see script for masking
mechanism. 9 sign-in sessions were used in total (script was iterated after failures; one extra
session was required for the orphan cleanup below).

## ⚠️ LEFTOVER RECORD FOUND AND REMOVED — READ FIRST

An early run (session 2, before the JSON-field-order fix described in Finding 2 below) attempted to
POST a `requirement` three times (`parent-id` candidates `0`, `-1`, omitted). **All three attempts
returned HTTP 500/400 client-side**, so the script's own bookkeeping never recorded a created id and
the `finally` cleanup block had nothing to delete for that step.

A later, successful run's response fixture (`req-create-response.json`) showed a `father-name` field
of `ALTALM-PROBE-20260812-102411-req1` — a name from that earlier "failed" session. This proved the
server had **silently committed one of the two 500-returning POSTs despite returning an error to the
client**. A one-off follow-up script (not retained) confirmed `GET requirements/1` returned HTTP 200
with `name = ALTALM-PROBE-20260812-102411-req1`, deleted it (`DELETE requirements/1` → HTTP 200), and
logged out. **The sandbox is clean as of the end of this session** — verified by requirement-id
sequence continuity across all subsequent runs (1 [orphan, now deleted], 2, 3, 4, 5, 6 — no other
gaps) and by defect-id sequence starting cleanly at 1 in the final run (ruling out a similar orphan
from a defect attempt that also returned an opaque 500 in an earlier run).

**Finding, VERIFIED:** on this ALM/QC instance, an HTTP 500 response to a POST is not proof that no
row was written. Any client (Alt-ALM's BFF included) that retries after a 500 must be prepared for a
duplicate, or must verify via GET before retrying. Opaque `"General Error"` / NPE-style 500 bodies
(`Cannot invoke ...`) are the highest-risk shape for this; well-formed business-validation 500s (e.g.
`"Cannot create 'Test Folder'. Invalid owner specified: 0"`) look like proper pre-insert validation
and did not show this behavior in this session's checks — but that inference (rather than direct
verification) is as far as it goes; treat any 500 as f-unknown outcome, not a confirmed no-op.

## Finding: XSRF-missing status code — VERIFIED

`POST requirements` with the XSRF cookie present but **no** `X-XSRF-TOKEN` header:

```
HTTP 401
{"Id":"qccore.general-error","Title":"Unauthorized request. For more details see XSRF Token
section in REST API documentation.","ExceptionProperties":null,"StackTrace":null}
```

Reproduced identically across every run. This request never reached entity-level processing (no
silent-commit risk here — the auth/XSRF gate runs first).

## Finding: request JSON field ORDER affects server behavior — VERIFIED (observable), root cause UNVERIFIED

This was the main obstacle and the most important structural finding of the session.

The probe script builds each POST/PUT body as `{"Fields":[{"Name":n,"values":[{"value":v}]},...],
"Type":t}` (the documented Core REST entity shape). The script originally built the `Fields` array
by iterating a plain PowerShell `Hashtable`'s `.Keys`. **.NET randomizes string-hashtable iteration
order per process** (hash-seed randomization), so the same logical field set serialized in a
different member order on almost every script run, even though the code was unchanged.

Observed server responses for `POST requirements` with fields `name`, `type-id=3`, and a `parent-id`
candidate, varied **only with field order**, not with the `parent-id` value tried:

| Fields array order (as sent) | `parent-id=0` | `parent-id=-1` | `parent-id` omitted |
|---|---|---|---|
| `parent-id, name, type-id` | 500 `Cannot invoke "Object.hashCode()" because "key" is null` | 500 same | 400 `qccore.required-field-missing`, field `name` (misleading — `name` WAS present in the JSON) |
| `type-id, parent-id, name` | 500 `Cannot invoke "String.equals(Object)" because ... FieldEntry.getName() is null` | 500 same | 500 same |
| `name, parent-id, type-id` (deterministic, `[ordered]@{}`) | **201 created** | *(not retried once order fixed)* | *(not retried)* |

**Fix applied:** `Build-Entity` and every call site were changed to use PowerShell `[ordered]@{...}`
dictionaries instead of plain hashtables, and fields were ordered `name` → relational ids (`parent-id`
etc.) → `type-id`/type-like fields last. This order was stable and successful across three subsequent
full runs (sessions 6, 7, 8) for every entity type probed (requirement, test-folder, test,
design-step, requirement-coverage, req-trace, defect, defect-link).

**Root cause is UNVERIFIED** (server internals not visible) — plausibly the server does some
positional/streaming processing of the `Fields` array rather than a pure name-keyed map lookup, but
that is a guess, not a confirmed mechanism. **Actionable conclusion for the real client:** the
Alt-ALM backend-for-frontend must serialize entity-write JSON with a **deterministic, fixed field
order** (never rely on hashmap/dict iteration order in whatever language is used) — this is a hard
requirement, not a style preference, on this ALM version.

## 1. Requirement create — VERIFIED

Working parent-id: **`1`** (the discovered orphan/root — see below; this ALM version's requirement
root/self-referencing-ish id was NOT `0` and NOT `-1`; both of those either NPE'd or, for `-1`,
produced `"Entity with key '-1' does not exist in table 'REQ'"` in earlier debugging before the
field-order fix was found, which was itself a hint that `-1` is treated as a literal lookup key, not
a sentinel "root" value).

Field names used, confirmed valid via the type-fields fixture (`tests/fixtures/customization-fields-
requirement.json`): `name` (String, required), `parent-id` (Number, physical `RQ_FATHER_ID`), `type-id`
(Reference, required, physical `RQ_TYPE_ID`; value `3` = "Functional", confirmed via
`customization-requirement-types.txt`).

Request body shape (deterministic order):
```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"1"}]},
           {"Name":"type-id","values":[{"value":"3"}]}],
 "Type":"requirement"}
```
Result: **HTTP 201**, `id` returned. Full response saved to
`tests/fixtures/write-probe/req-create-response.json` — confirms ~70 fields returned on create
(all the RBQM/version-control fields default to empty, `status` defaults to `"Not Covered"`,
`has-rich-content` defaults to `"N"`).

## 2. Rich-text round-trip on requirement (description + req-rich-content) — VERIFIED, DIFFERS

Torture HTML block sent (identical for both fields), **SENT verbatim**:
```
<html><body><b>bold</b> <i>italic</i> <u>under</u> <font color="#ff0000">red</font><ul><li>li-one</li><li>li-two</li></ul><table border="1"><tr><td>cell-a</td><td>cell-b</td></tr></table><a href="http://example.com/x">link-text</a> &amp;amp; escaped &amp;lt;tag&amp;gt; <span style="background-color:yellow">hl-span</span><div style="text-align:center">centered</div><script>alert(1)</script></body></html>
```

**GOT verbatim** (readback via `GET requirements/{id}?fields=description` and identically for
`req-rich-content`):
```
<html><body>
<b>bold</b> <i>italic</i> <u>under</u> <font color="#ff0000">red</font>
<ul>
<li>li-one</li>
<li>li-two</li>
</ul>
<table border="1">
<tbody>
<tr>
<td>cell-a</td>
<td>cell-b</td>
</tr>
</tbody>
</table><a href="http://example.com/x">link-text</a> &amp;amp; escaped &amp;lt;tag&amp;gt; <span style="background-color:yellow">hl-span</span>
<div style="text-align:center">
centered
</div>
</body></html>
```
(Saved verbatim to `tests/fixtures/write-probe/richtext-roundtrip-description.txt` and
`richtext-roundtrip-req-rich-content.txt`.)

**Verdict: readback DIFFERS from sent, for both fields, identically.**

Specific transformations observed, all VERIFIED by direct diff of the two blocks above:
- **`<script>alert(1)</script>` is stripped entirely** — does not appear anywhere in GOT. The
  sanitizer removes `<script>` tags on write (or read — round-trip doesn't distinguish which stage,
  but the net effect for any consumer is removal).
- **`<table>` is normalized**: an implicit `<tbody>` wrapper is inserted around `<tr>` that wasn't in
  the input. This is HTML-tidy-style normalization, not naive storage.
- **Whitespace/newlines are inserted** around block-level tags (`<body>`, `<ul>`, `<li>`, `<table>`,
  `<tr>`, `<td>`, `<div>`) — pretty-printing, not byte-for-byte storage. Inline tags (`<b>`, `<i>`,
  `<u>`, `<font>`, `<a>`, `<span>`) are NOT reformatted internally.
- `<font color="#ff0000">`, `<span style="background-color:yellow">`, `<div style="text-align:
  center">`, and the `<a href>` all **survive intact** (attributes preserved).
- The already-double-escaped entity text `&amp;amp; escaped &amp;lt;tag&amp;gt;` is preserved
  **as literally typed** (not re-decoded or re-encoded further) — i.e. the sanitizer does not touch
  entity references it doesn't recognize as structural.
- **`has-rich-content` flips from `N` (on create) to `Y`** after the PUT — VERIFIED via
  `GET requirements/{id}?fields=has-rich-content`.

**Conclusion for the record generator:** rich-text fields are NOT a safe byte-for-byte store. Any
round-trip fidelity test must tolerate (a) whitespace/pretty-print normalization, (b) implicit
`<tbody>` insertion in tables, and (c) `<script>` (and very likely `<style>` — see next section,
UNTESTED for style specifically as a bare top-level tag; only inline `style=` attributes were tested
and those DID survive) being stripped. Do not assume literal round-trip; assume "structurally
equivalent modulo tidy-normalization, with `<script>` removed."

## Sanitizer behavior specifically — PARTIALLY VERIFIED

- `<script>...</script>` (bare tag): **stripped**. VERIFIED (see above).
- `<style>` bare tag: **not tested this round** — only `style="..."` attributes were tested (on
  `<span>` and `<div>`), and those attributes **survived**. A bare `<style>` block is a gap — flag as
  UNVERIFIED, worth a follow-up probe.
- `<font>` tag: **survives** (with its `color` attribute) — VERIFIED. Notable since `<font>` is
  deprecated HTML; this ALM version's sanitizer allowlist still permits it.
- Inline event-handler attributes (e.g. `onclick=`) were **not tested** this round — flag as
  UNVERIFIED, worth a follow-up probe alongside `<style>`.

## 3. Test-folder + test create — VERIFIED

First attempts with guessed root ids all failed:
```
parent-id=0  -> 500 "Cannot create 'Test Folder'. Invalid owner specified: 0."
parent-id=-1 -> 500 "Cannot create 'Test Folder'. Invalid owner specified: -1."
parent-id=1  -> 500 "Cannot create 'Test Folder'. Invalid owner specified: 1."
```
These are well-formed business-validation rejections (server explicitly names and rejects the
supplied owner value), structurally different from the requirement-create NPEs — treated as low risk
of silent commit (see orphan section above for the reasoning, not direct proof).

**Fix:** discovered the real root via `GET test-folders?query={parent-id[0]}&fields=id,name,parent-id
&page-size=5` — returned exactly one top-level folder: **`id=2`, `name="Subject"`**. This matches
staff-documented worked examples elsewhere in this repo's research (`parent-id[2]` pattern noted in
`wave1-04-test-plan.md`). Using `parent-id=2` for the test-folder create succeeded:

```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"2"}]}],
 "Type":"test-folder"}
```
→ **HTTP 201**, id returned (e.g. `1003`). Full response saved to `test-folder-create-response.json`.

Test create under that folder:
```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"parent-id","values":[{"value":"<folder-id>"}]},
           {"Name":"subtype-id","values":[{"value":"MANUAL"}]}],
 "Type":"test"}
```
→ **HTTP 201**. Full response (`test-create-response.json`) shows ~44 fields including `steps=0`,
`exec-status="No Run"`, `configurations-count=1`, `step-param=0`, `has-dependencies=0`,
`text-sync="Y"`.

**Conclusion:** the Test Plan tree root ("Subject" in 17.0+ UI) is project-specific and must be
**discovered at runtime** via the `parent-id[0]` query, never hardcoded — consistent with the
CLAUDE.md warning about per-project customization.

## 4. Design-steps POST — VERIFIED (the write path is confirmed to exist and work)

```json
{"Fields":[{"Name":"name","values":[{"value":"Step 1"}]},
           {"Name":"parent-id","values":[{"value":"<test-id>"}]},
           {"Name":"description","values":[{"value":"<html><body>Do the thing with <<<probe_param>>> token</body></html>"}]},
           {"Name":"expected","values":[{"value":"<html><body>Thing done</body></html>"}]}],
 "Type":"design-step"}
```
→ **HTTP 201**. This directly contradicts the "POST/PUT/DELETE marked Not applicable on the Core
page" note carried over from earlier read-only research (`wave1-04-test-plan.md` UNVERIFIED #3) —
**resolved: design-steps IS directly POSTable at `.../design-steps` with `parent-id` = the owning
test's id, no nested `tests/{id}/design-steps` path needed.**

Returned field list (VERIFIED, from the actual create response, `design-step-create-response.json`):
`step-order, vts, ver-stamp, attachment, has-params, expected, vc-user-name, name, description, id,
link-test, parent-id`. Note `step-order` (not `order-id`) is the sibling-ordering field for
design-steps — different from the `order-id` field name used elsewhere (test-folder, requirement).

**Important side finding — parameter token mangled by the sanitizer, VERIFIED:** the embedded
`<<<probe_param>>>` placeholder token was **not** preserved. Sent
`description = "<html><body>Do the thing with <<<probe_param>>> token</body></html>"`; the readback
in the same create response shows:
```
"description":"<html><body>\nDo the thing with &lt;&lt;&gt;&gt; token\n</body></html>"
```
i.e. `<<<probe_param>>>` became `<<>>`  — **the sanitizer parsed `<probe_param>` as an HTML tag and
stripped its tag-name content**, leaving only the surrounding `<` `<` `>` `>` characters (HTML-
entity-encoded on readback). `has-params` was still returned as `"Y"` on this design-step, suggesting
the server extracts/registers the parameter token from the raw pre-sanitized input server-side
*before* running it through the HTML sanitizer for storage — but the *stored, readable* description
text no longer contains the parameter name. This is almost certainly why step-parameter creation
failed (next section): the parameter name that actually got registered server-side, if any, was not
literally `probe_param`.

**Conclusion for the record generator:** parameter placeholder tokens (`<<<name>>>`) must NOT be
embedded directly in rich-text/HTML step fields via naive string concatenation — the HTML sanitizer
treats `<name>` as a tag and destroys it. The real stock UI presumably encodes/escapes this
differently before submission (e.g. HTML-entity-encoding the angle brackets, or using a different
wire representation entirely) — needs a follow-up probe capturing the stock UI's actual POST body for
a parameterized step (out of scope for this round; flagged as UNVERIFIED #NEW).

## 5. Step-parameters — FAILED after 2 informed attempts (documented failure, not a field-shape bug)

Real field shape, discovered via `GET customization/entities/step-parameter/fields` (saved to
`customization-fields-step-parameter.json`) — **our original guessed shape (`name`, `default-value`)
was wrong**. Actual fields:
```
actual-value(Memo) ignore-test-instance-parameters(String) origin-test(Number) id(Number)
used-by-owner-id(Number) key(String) used-by-owner-type(String, REQUIRED) parent-id(Number)
vc-user-name(UsersList)
```

Attempt 1 (`used-by-owner-type=design-step`, `used-by-owner-id=<design-step id>`, `parent-id=<test
id>`, `key=probe_param`, `actual-value=v1`):
```
POST /step-parameters -> HTTP 500
{"Id":"qccore.general-error","Title":"Test parameter does not exist","ExceptionProperties":[],"StackTrace":null}
```

Attempt 2 (same but `used-by-owner-type=test`, `used-by-owner-id=<test id>`):
```
POST /step-parameters -> HTTP 500
{"Id":"qccore.general-error","Title":"Test parameter does not exist","ExceptionProperties":[],"StackTrace":null}
```

**Conclusion (documented failure):** `step-parameters` is not a "define a new parameter" endpoint —
it appears to require an **already-registered parameter definition** (a "Test parameter") to exist
before a `step-parameter` usage/value record referencing it via `key` can be created. Combined with
Finding 4's discovery that the `<<<probe_param>>>` token got mangled by the sanitizer before storage,
the most likely explanation is that no server-side parameter definition named exactly `probe_param`
ever actually got registered, so every `key` lookup fails. This was not re-attempted a third time
(budget) — a follow-up probe should (a) first determine how the stock UI actually defines a test
parameter (there is no dedicated "parameters" endpoint in the API surface we're permitted to call —
this may be a genuine gap only reachable through the stock web client or COM/OTA), and (b) retry
`step-parameters` POST/GET against a test that already has a UI-defined parameter, to see if the
`key` field is then resolvable.

## 6. Requirement-coverages + test-config-coverages side effect — VERIFIED

Fields via `GET customization/entities/requirement-coverage/fields` (saved to
`customization-fields-requirement-coverage.json`): `coverage-mode(LookupList) status(String)
entity-type(String) test-id(Number,required) entity-name(String) last-modified(DateTime)
requirement-id(Number,required) id(Number) modified-count(Number)`.

```json
{"Fields":[{"Name":"requirement-id","values":[{"value":"<req-id>"}]},
           {"Name":"test-id","values":[{"value":"<test-id>"}]},
           {"Name":"entity-type","values":[{"value":"test"}]}],
 "Type":"requirement-coverage"}
```
→ **HTTP 201** on the first attempt (with `entity-type` included; the "retry without entity-type"
fallback in the script was never needed). This **resolves the contested UNVERIFIED #2/#3** flagged in
`wave1-03-requirements.md` (some 2013–2022 community reports said unsupported) — **on this ALM
version, `POST requirement-coverages` with `requirement-id` + `test-id` + `entity-type` works.**
Response (`requirement-coverage-create-response.json`) shows `coverage-mode="All Configurations"`,
`status="No Run"` returned automatically.

**Side effect on `test-config-coverages` — VERIFIED**: querying
`test-config-coverages?query={first-endpoint-id[<coverage-id>]}&page-size=10` (the coverage row's own
`id`, per the documented `first-endpoint-id -> requirement-coverages row` relationship) returned
`TotalResults=1` — **confirms creating a `requirement-coverage` automatically creates exactly one
`test-config-coverage` row** linking it to the test's (single, default) configuration. Note: an
earlier attempt using `requirement-id` as the query field on `test-config-coverages` failed with
HTTP 400 — `test-config-coverages` has no such field; must query by `first-endpoint-id`.

## 7. Req-traces (requirement traceability) — VERIFIED

Fields via `GET customization/entities/req-trace/fields` (saved to
`customization-fields-req-trace.json`): `owner(UsersList) creation-date(Date)
from-req-id(Number,required) last-modified(DateTime) id(Number) to-req-id(Number,required)
comment(String)`.

**Our first-guess field names (`from-req-id`/`to-req-id`) were correct** — no retry needed:
```json
{"Fields":[{"Name":"from-req-id","values":[{"value":"<req1-id>"}]},
           {"Name":"to-req-id","values":[{"value":"<req2-id>"}]}],
 "Type":"req-trace"}
```
→ **HTTP 201**. Response auto-populates `owner` (current user) and `creation-date`.

## 8. Defect create — VERIFIED (after fixing a local script bug, not a server issue)

**Root cause of the first two failures was a bug in the probe script itself**, not the API: it read
`severity` list metadata from a local fixture using the wrong property names (`'List-Id'` instead of
the fixture's actual `listId`, and `.name` instead of the list items' actual `.value` property). This
made `$sevValue` resolve to an empty string, which then failed server-side as:
```
attempt 1 (severity=""): POST /defects -> HTTP 500 {"Title":"General Error"}   (opaque — see orphan-risk note above)
attempt 2 (severity=""): POST /defects -> HTTP 400 {"Id":"qccore.required-field-missing","field-name":"severity"}
```
No orphan resulted from attempt 1's opaque 500 — VERIFIED by the final run's defect-id sequence
starting cleanly at `1` (no gap).

Fixed local lookup resolved `severity List-Id=279` → first list item value `"1-Low"`. With a real
severity value:
```json
{"Fields":[{"Name":"name","values":[{"value":"<name>"}]},
           {"Name":"detected-by","values":[{"value":"<current-user>"}]},
           {"Name":"creation-time","values":[{"value":"2026-08-12"}]},
           {"Name":"severity","values":[{"value":"1-Low"}]}],
 "Type":"defect"}
```
→ **HTTP 201** for both `defect1` and `defect2`. Severity value used: `"1-Low"` (first item of list id
279, the `severity` field's bound lookup list).

## 9. Defect-links — VERIFIED

Defect↔defect (`second-endpoint-type=defect`):
```json
{"Fields":[{"Name":"first-endpoint-id","values":[{"value":"<defect1-id>"}]},
           {"Name":"second-endpoint-id","values":[{"value":"<defect2-id>"}]},
           {"Name":"second-endpoint-type","values":[{"value":"defect"}]}],
 "Type":"defect-link"}
```
→ **HTTP 201**. Response auto-populates `second-endpoint-name`, `owner`, `creation-time`;
`second-endpoint-status` was empty (defects apparently don't carry a `status` surfaced this way, or
this defect had none set).

Defect↔requirement (`second-endpoint-type=requirement`):
```json
{"Fields":[{"Name":"first-endpoint-id","values":[{"value":"<defect1-id>"}]},
           {"Name":"second-endpoint-id","values":[{"value":"<req1-id>"}]},
           {"Name":"second-endpoint-type","values":[{"value":"requirement"}]}],
 "Type":"defect-link"}
```
→ **HTTP 201**. Here `second-endpoint-status` returned `"No Run"` (the requirement's coverage
status) and `second-endpoint-name` returned the requirement's name — confirms `defect-links` resolves
and denormalizes the second endpoint's display fields regardless of entity type, using
`second-endpoint-type` to pick the right lookup table.

**Conclusion:** the `second-endpoint-type` discriminator pattern is confirmed working for both
`defect` and `requirement` as the second-endpoint entity type.

## 10. Audits readback — VERIFIED, PARTIAL coverage

`GET requirements/{id}/audits` → **HTTP 200**. Shape (`requirement-audits-response.json`):
```json
{"Audits":{"Audit":[
  {"ParentId":<id>,"Action":"UPDATE","User":"REDACTED","ParentType":"requirement",
   "Time":"2026-08-11 22:02:15","Id":23,
   "Properties":{"Property":{"OldValue":"Not Covered","Label":"Direct Cover Status","NewValue":"No Run","Name":"status"}}},
  {"ParentId":<id>,"Action":"UPDATE","User":"REDACTED","ParentType":"requirement",
   "Time":"2026-08-11 22:02:07","Id":20,
   "Properties":{"Property":{"OldValue":"","Label":"Direct Cover Status","NewValue":"Not Covered","Name":"status"}}}
],"TotalResults":2}}
```
**Only 2 audit entries were returned for a requirement that had: create, 2×PUT (rich-text on
`description` and `req-rich-content`), and later a `requirement-coverage` link created against it.**
Both entries are `status` field changes (`Direct Cover Status`, driven by the coverage link, not by
our direct edits). **Neither the create, nor either rich-text PUT, produced a visible audit entry.**

**Conclusion (VERIFIED but noteworthy):** audit trail on this ALM version, for requirements, appears
to track only a subset of fields (here, only the derived/computed `status` field) — direct edits to
`description`/`req-rich-content` via REST PUT were NOT captured in `/audits`. This has UNVERIFIED
scope (may be a per-field "history"/"versionControlled" flag distinction — both `description` and
`req-rich-content` ARE marked `"versionControlled":true` in the field metadata, so history tracking
being off for a PUT specifically, vs. audit-log tracking being a separate mechanism entirely, is not
resolved here). Worth a follow-up probe specifically isolating field-level audit behavior.

## 11. Cleanup — ALL DELETEs returned HTTP 200 in the final successful run

Final run (session 8) cleanup, reverse order, every single entry:
```
DELETE /defect-links/2            HTTP 200
DELETE /defect-links/1            HTTP 200
DELETE /defects/2                 HTTP 200
DELETE /defects/1                 HTTP 200
DELETE /req-traces/1002           HTTP 200
DELETE /requirements/6            HTTP 200
DELETE /requirement-coverages/2   HTTP 200
DELETE /design-steps/1002         HTTP 200
DELETE /tests/2                   HTTP 200
DELETE /test-folders/1003         HTTP 200
DELETE /requirements/5            HTTP 200
```
Plus the manually-verified-and-removed orphan from session 2 (`DELETE requirements/1` → HTTP 200,
see top of doc). **Nothing is known to be left behind in the sandbox as of the end of this session.**
(Residual, lower-confidence risk: test-folder-create failures in session 6 and the opaque defect 500
in session 7 were reasoned, not directly proven, to be non-committing — see notes in sections 3 and 8.
A future session could spot-check this by re-running the `parent-id[0]` test-folder discovery query
and confirming only the expected folders are present.)

## Script changes made this session (for future runs)

`scripts/probe/probe-write-1.ps1` was modified in place (same file, same endpoint families — no new
endpoints added):
1. Fixed `$pid`-as-loop-variable bug (`$pid` is a PowerShell read-only automatic variable for the
   process id; renamed to `$parentId` everywhere).
2. Added `AllowInsecureRedirect = $true` to the shared `Invoke-WebRequest` splat, and redirect
   `Location` logging in `Invoke-Alm` — a 3xx response was otherwise throwing an unhandled exception
   under `-MaximumRedirection 0`.
3. `Build-Entity`/`New-AlmEntity` now require `[ordered]@{...}` field dictionaries (deterministic
   JSON field order) — see the field-order finding above. Every call site was updated.
4. Added runtime discovery of the Test Plan root folder id (`test-folders?query={parent-id[0]}`)
   instead of guessing `0`/`-1`/`1`.
5. Fixed the `severity` list-value lookup (`listId` not `'List-Id'`, `.value` not `.name` on list
   items).
6. Fixed `step-parameters` to use the real discovered field names
   (`key`/`used-by-owner-type`/`used-by-owner-id`/`parent-id`/`actual-value`) instead of the guessed
   `name`/`default-value` — still fails for the business-logic reason documented in section 5.
7. Fixed the `test-config-coverages` side-effect check to query by `first-endpoint-id` (the
   coverage's own id) instead of the nonexistent `requirement-id` field.
8. Added a requirement `parent-id=1` candidate ahead of `0`/`-1`/omitted (turned out to be the
   working value once field order was also fixed).

## Deliverable B — fixtures

All fixtures below are redacted (masked) and live in `tests/fixtures/write-probe/`:

- `req-create-response.json` — full requirement create response
- `req2-create-response.json` — second requirement (traceability target)
- `richtext-roundtrip-description.txt` — SENT/GOT for `description`
- `richtext-roundtrip-req-rich-content.txt` — SENT/GOT for `req-rich-content`
- `test-folder-create-response.json`
- `test-create-response.json`
- `design-step-create-response.json`
- `customization-fields-step-parameter.json` — field metadata (create still fails, see §5)
- `customization-fields-requirement-coverage.json` — field metadata
- `requirement-coverage-create-response.json`
- `customization-fields-req-trace.json` — field metadata
- `req-trace-create-response.json`
- `defect-create-response.json`
- `defect-link-defect-response.json`
- `defect-link-requirement-response.json`
- `requirement-audits-response.json`

`step-parameter-create-response.json` does NOT exist — that step never returned a 200/201 to save
(see §5, documented failure).
