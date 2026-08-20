---
name: alm-data-gen
description: Generator conventions for the ALM Faker record generator — safety checklist, field-strategy matrix, rich-text/parameter grammar, creation-order DAG, and write mechanics. Load when generating synthetic ALM data.
---

Ground truth: `docs/plan/data-generator-spec.md` (primary), `docs/research/alm-api-reference.md`
§7 (rich text), `docs/research/alm-data-model.md`, `docs/research/live-probe-log.md`. Transport
mechanics (auth, XSRF, envelope shape, query grammar, error codes) are owned by the `alm-api`
skill — reference it, don't restate it here. Entity relationships/fields are owned by
`alm-entity-model` — reference it for the DAG detail beyond what's reproduced below.

## 1. Pre-write safety checklist — MUST, non-negotiable

1. **Dry-run is the default.** The generator MUST NOT issue any non-GET request unless invoked with
   an explicit execute flag. Dry-run MUST still perform read calls needed to resolve metadata/roots
   (non-destructive).
2. **Explicit allowlist, hard stop.** MUST refuse to write to any `(domain, project)` not present in
   an operator-maintained allowlist (outside `Secrets/`). Refusal is a hard stop before any write
   call is constructed — never a warning the operator can click through. Checked even during
   dry-run plan validation.
3. **Refusal semantics.** A refused run MUST: emit one specific error naming which check failed
   (allowlist / missing seed / plan-validation), perform zero writes, and leave no partial
   checkpoint (or mark it `REFUSED`). Refusal is a pre-flight gate, not an exception from a failed
   API call.
4. **Provenance prefix.** Every created record with a user-facing textual field (`name` on
   requirement/test/test-folder/test-set/test-instance/defect/release/release-cycle/
   release-folder/milestone/resource) MUST be prefixed `ALTALM-GEN-<runid>`, default format
   `ALTALM-GEN-<runid>-<ENTITY>-<ordinal>` (e.g. `ALTALM-GEN-8f3a-REQ-014`). `<runid>` is
   generator-assigned, not the seed, so the same seed can be replayed under a fresh marker.
5. **Transitive provenance for nameless entities.** `req-trace`, `requirement-coverage`,
   `test-config-coverage` (auto-created side effect), `defect-link`, `run-step`, and `run` itself
   (name is server-assigned on Fast_Run synthesis and **cannot be overridden** — verified,
   non-negotiable) have no textual field. Every FK on such a record MUST point only at
   same-run prefixed ancestors (for `run`: at a prefixed `test-instance` under a prefixed
   `test-set`).
6. **Two cleanup modes.**
   - **Manifest replay (default, precise).** Every run writes an append-only checkpoint of every
     `(entity-type, id)` created, in creation order. Cleanup deletes exactly those ids in
     **reverse** order (children before parents, e.g. `run-step` before `run`,
     `requirement-coverage` before `requirement`). MUST stop and report on the first delete that
     both returns non-2xx AND fails a verify-by-GET check.
   - **Prefix sweep (fallback, orphan recovery).** Used when no manifest exists. Query
     `{name[ALTALM-GEN*]}` (or configured prefix) against every named collection, delete in
     reverse-DAG order, then cascade to unnamed children by matching their parent-id/
     first-endpoint-id against just-deleted/about-to-delete prefixed ids.
7. **Never delete non-prefixed records.** Both cleanup modes operate only on ids from (a) a
   manifest this generator wrote, or (b) the prefix-sweep-plus-cascade rule. No "delete everything"
   mode; no free-text id list accepted for deletion.
8. **Write attribution.** All writes ride the single BFF service-account session; ALM-side
   `owner`/`detected-by`/etc. resolve to whichever seeded project user the generator's UsersList
   strategy picked — except fields the server always stamps with the calling identity regardless of
   payload (`req-trace.owner`, `defect-link.owner`), which will show the service account (a known,
   documented limitation).
9. **Secrets never logged.** MUST NOT log the service-account key/secret, session cookies, or XSRF
   token at any log level including debug. Delegated to and enforced by the `alm-api` write-safety
   layer — the generator MUST NOT construct its own HTTP calls bypassing it.

## 2. Field-type → strategy matrix

Dispatch order for every field: **skip if `System=true AND Editable=false`** (191/432 probed
fields — never write `id`, any `vc-*`, any `has-*`, `last-modified`, `Size=99999` virtual fields)
→ else check `Required` → else dispatch by `Type`. See `alm-entity-model` §4 for the type system
itself.

| Type | Strategy | Constraints / traps |
|---|---|---|
| String | Bounded lorem-style text, length capped by `Size` (no cap needed for `-1`/`99999` — never written anyway) | Most flag-shaped Strings are read-only/system, filtered out before dispatch |
| Memo | Full rich-text block, §3 below | Uniformly `Size=-1`; complete `<html><body>…</body></html>` document, not a fragment |
| Number | Integer/decimal in a plan-configured range; FK-shaped Number fields (e.g. `requirement-id` on `requirement-coverage`) pull from the manifest of already-created ids, never randomly | `run.cycle-id` is typed String despite looking numeric — send as a JSON string in every field regardless of declared type (whole envelope is string-typed anyway) |
| Date | `yyyy-MM-dd`, sampled from a plan-configured window | Release-cycle dates MUST be sampled inside the parent release's `start-date`/`end-date` — server-enforced 500 otherwise. Timezone rule UNVERIFIED, treat as server-local |
| DateTime | `yyyy-MM-dd HH:mm:ss` | Same format/timezone caveats as Date |
| LookupList | Fetch valid values for the field's `listId` at runtime (`customization/used-lists/{id}/items` — NOT the unrelated `list-items` collection), weighted-random pick per §6 | List ids are per-instance — never hardcode. `test-set.parent-id`/`test-set-folder.parent-id` are typed LookupList despite being tree-parent refs; still write the target folder id as the value |
| UsersList | Pick from the pool seeded by the pre-run **user-seeding step** (`POST /qcbin/v2/sa/api/site-users` then `POST .../projects/{p}/users`, both reachable under Customer Admin role) | Sandbox has only **1** project user by default. Without seeding, all 77 UsersList fields across 11 entities degenerate to that single value — user-seeding MUST run before any plan expecting `count > 1` unique users |
| Reference | FK pull from the manifest of already-created ids of the referenced type, honoring DAG level ordering | Only 2 fields in the whole model are multivalue — `requirement.target-rel`/`target-rcyc`, both Reference — write path UNVERIFIED, deferred, not attempted in MVP |

**UDF handling**: `user-NN` fields are discovered from the same metadata call and carry a normal
`Type` — the dispatch table applies unmodified, no UDF-specific code path.

## 3. Rich-text block grammar

Memo fields are generated as **complete HTML documents**, never a bare fragment. Verified sanitizer
behavior on the sandbox (deployment-specific `sanitizer-whitelist.xml` — treat as the target grammar):

**Survives intact**: `<font color>`, inline `style=`, `href` on `<a>`, `<table>`/`<tr>`/`<td>`
(gains an implicit `<tbody>` wrapper — expected, not a bug), already-double-escaped entity text
(preserved literally). Inline tags (`<b>`, `<i>`, `<u>`, `<font>`, `<a>`, `<span>`) are not
reformatted; block tags get whitespace/pretty-print normalization inserted around them.

**Stripped**: `<script>…</script>` removed entirely. A bare filename or relative-path `<img src>`
loses only its `src` attribute (tag survives, attribute silently dropped) — never emit a relative
`img src`.

**UNVERIFIED, do not rely on**: bare top-level `<style>` blocks, inline event-handler attributes.

**Round-trip validation MUST compare canonicalized HTML, never raw bytes** — implicit `<tbody>`
insertion and whitespace re-pretty-printing are expected outcomes, not failures.

**`has-rich-content` auto-flips N→Y on write.**

## 4. Parameter tokens

A raw `<<<name>>>` token in a design-step's `description`/`expected` is parsed by the sanitizer as
a malformed tag and collapses to `<<>>`, destroying the parameter name. **HTML-entity-pre-encode
the angle brackets** — send `&lt;&lt;&lt;name&gt;&gt;&gt;` — which survives round-trip intact and
still flips `has-params="Y"`.

**General rule, not just for parameters**: entity-encode literal `<`/`>` in any free text the
generator did not itself construct as valid, whitelisted markup, to avoid the same malformed-tag
collapse on arbitrary generated content.

**Parameters are now generatable end-to-end (Probe 9) — this was previously a hard gap, now closed.**
Two entities: `test-parameter` (physical `TP_*`) *defines* a parameter on a test; `step-parameter`
(physical `SP_*`) *records a value* against an already-defined one. **Use Route A, not the token
above, as the authoritative creation path**: `POST tests/{testId}/test-parameters` with `name` +
`ref-count` (never omit `ref-count` — metadata says `editable:false, required:false` but omitting it
500s with `"missing required field TP_REF_COUNT"`, see `alm-api` skill hazard #5). Route A is
deterministic and doesn't depend on sanitizer/text-parsing, unlike the `<<<token>>>` above (Route B),
which the generator should still emit in step text for UI-authenticity but must not treat as the
source of truth for its own manifest/provenance tracking. Per-step values: `POST step-parameters` with
`parent-id` = the Route A `test-parameter`'s id (**not** the test/design-step id — this was the shape
bug behind the old "no REST path" belief) and `used-by-owner-id` = the design-step or test. Default
values: `PUT test-parameters/{id}` with `default-value` (Memo, same sanitizer rules as any other
memo field) — REST can do this where OTA cannot.

## 5. Embedded images

Two confirmed, REST-only paths (plan toggle `richText.embeddedImages`, default off):

1. Upload via `application/octet-stream` + `Slug: <filename>` (`ref-subtype=0`), then reference it
   with an **absolute REST attachment URL** in `<img src>`.
2. Hand-built `multipart/form-data` with `ref-subtype=1` (explicit boundary, CRLF discipline, text
   parts first, `file` part **last**, `Content-Type: image/png` on the file part — verified working
   3/3 sessions; PowerShell's `-Form` constructor is a known-bad client for this, integration-test
   whatever HTTP client the BFF actually uses), then reference it via that same absolute URL or a
   `data:` URI.

Both `src` forms (absolute REST URL, `data:` URI) are confirmed to survive intact. A bare filename
or relative path silently drops the `src` attribute — never generate one.

## 6. Creation order + distribution defaults

Execution levels (each level's writes MUST complete/verify before the next begins — bulk is
non-transactional, see §7): `[release-folder]` → `[release]` → `[release-cycle, milestone]`
(parallel with) `[requirement]` and `[test-folder]` → `[req-trace]` and `[test]` →
`[design-step, test-parameter]` (parallel — test-parameter does not depend on design-step) →
`[step-parameter]` (needs the owning `test-parameter` id; may reference either a `design-step` or the
`test`) and `[requirement-coverage]` (needs both requirement and test levels done) →
`[test-set-folder]` → `[test-set]` → `[test-instance]` → `[run via Fast_Run PUT]` → `[defect]` →
`[defect-link]`.

Releases/milestones, requirements, and the test tree are **independent parallel branches** — they
only join at `requirement-coverage` and the deferred `target-rel`/`target-rcyc`. Nothing in the
verified model requires releases to exist before requirements or tests. See `alm-entity-model` §5
for the full annotated DAG diagram.

**Distribution defaults — design defaults, NOT API facts, all tunable per-plan:**

| Knob | Default | Notes |
|---|---|---|
| Requirement tree depth | 3–4 levels | Root nodes type Folder(1)/Group(2); leaves type Functional(3)/Testing(5) for coverage eligibility |
| Requirement tree fanout | 2–6 per non-leaf, right-skewed | Avoids uniformly-bushy trees |
| Test-folder depth/fanout | 2–3 levels, 3–8 tests/leaf | "module → sub-feature → tests" shape |
| Requirement coverage density | 60–80% of eligible requirements covered | Remainder intentionally uncovered — realistic gap for coverage-report testing |
| Cross-requirement trace density | 5–15% of same-subtree pairs | Directed, cycles allowed (real data has them too) |
| Defect status mix | New 40 / Open 25 / Fixed 15 / Closed 15 / Rejected 5 | REST bypasses workflow scripts by default — generator must synthesize the mix itself |
| Defect severity mix | 1-Low 30 / 2-Med 35 / 3-High 20 / 4-Crit 10 / 5-Urgent 5 | Literal list strings fetched at runtime, weights only |
| Run pass/fail mix | Passed 65 / Failed 20 / No Run 10 / Blocked 5 | Set via `PUT test-instances/{id}.status` (Fast_Run synthesis) or a follow-up status PUT |
| Defect-link density | 5–10% of defects linked | ~70/30 split defect↔defect vs. defect↔requirement (the only 2 confirmed `second-endpoint-type` values) |

## 7. Write mechanics (delegated to `alm-api`)

The generator is a **caller**, not a reimplementer, of the write-safety layer:

- **Field order**: the layer owns deterministic `Fields` array ordering (wrong order → opaque
  500s). The generator supplies values, not serialization order.
- **5xx = unknown outcome, never "failed"**: an HTTP 5xx on write requires verify-by-GET before any
  retry (a real leftover row was found this way in probing). Prefixed names make the verify query
  exact (`{name["ALTALM-GEN-<runid>-REQ-014"]}`); for unnamed join entities, dedup by the FK tuple
  (e.g. `from-req-id`+`to-req-id`).
- **Bulk caps**: size cap 2000, used only *within* one DAG level, never across levels (bulk is
  non-transactional — a 409 partial failure needs per-item `BulkEntry[]` parsing). On a 409, mark
  failed ordinals `FAILED` in the manifest and either retry individually or abort the level — never
  silently proceed with manifest gaps.
- **Paging**: default page size 100, capped at 2000 — cleanup/prefix-sweep and bulk-verify queries
  MUST page through full result sets.

See `alm-api` skill for the concrete envelope shapes, error-code table, and paging parameters.

## 8. Deferred / blocked features

| Feature | Status | MVP treatment |
|---|---|---|
| ~~`step-parameters` (parameter *definition*) — REST-unreachable, every create shape returns 500~~ | **RETRACTED (Probe 9) — CLOSED, not deferred.** A missed `test-parameters` collection defines the object directly; the failure was a `parent-id` shape bug. See §4 | Generator authors `test-parameter`/`step-parameter` as ordinary DAG nodes (§6), no capability flag, no OTA dependency |
| BPT (Business Process Testing) | License-gated: `components` → 403, `business-components` → 404 | Out of scope for MVP entirely |
| Mail (`{id}/mail` POST) | Body shape undocumented — 3 JSON + 1 XML shapes all failed | Not attempted |
| `target-rel`/`target-rcyc` | Write path UNVERIFIED (own multivalue field vs. separate `requirement-target-releases/-cycles` collections) | Deferred behind `targetRelCycle` flag; generated requirements carry no release/cycle targeting until settled |
| `test-config` direct create | UNVERIFIED — never probed | Generator never POSTs `test-configs` directly; relies on the implicit default config every test carries |
| `release-folder` root | UNVERIFIED | MUST run `release-folders?query={parent-id[0]}` at runtime before the first create in any plan |
| `run-step` independent CRUD | Only Fast_Run auto-copy confirmed | Never attempted standalone; run-steps only appear via synthesis, then may be PUT for status per §6's mix |
| `defect-links` beyond defect/requirement | test/run/test-instance UNVERIFIED | Generator restricts to the two confirmed `second-endpoint-type` values |
| Direct `POST runs` | Definitively FAILS (8 attempts, 2 distinct 500 modes) — closed door, not open | Generator exclusively uses `PUT test-instances/{id}.status`; direct POST never attempted, not even as fallback |

## See also

- `alm-entity-model` skill — full entity catalog, field types, DAG, naming traps.
- `alm-api` skill — auth, envelopes, error codes, query grammar, paging/bulk mechanics.
- `docs/plan/data-generator-spec.md` — full spec, acceptance criteria (§10), run model (§3).
- `docs/research/live-probe-log.md` — Probe 6 (Fast_Run synthesis, run-step auto-copy, multipart upload).
