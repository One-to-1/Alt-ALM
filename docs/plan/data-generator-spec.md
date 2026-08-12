# ALM Faker — Record Generator Specification

Elaborates lead-brief decisions **D6** (generator non-negotiables) and **D7** (write-safety
component) in `docs/plan/_lead-decision-brief.md` against the verified research corpus. Carries
forward CLAUDE.md's hard constraints: documented REST only, `Secrets/` never read into a document,
sandbox-only writes with an explicit allowlist, `UNVERIFIED` labelling discipline, and "never invent
API behaviour." Every API-behaviour claim below cites `alm-data-model.md` or `alm-api-reference.md`
by section; design defaults (distributions, prefixes, phrasing of refusal messages) are stated as
such and are not API facts.

---

## 1. Purpose & product placement

ALM Faker is **not a standalone tool** — it is a module of Alt-ALM (D6), sharing the BFF's session
manager, write-safety component (D7), and metadata service (D5) rather than reimplementing any of
them. Two halves:

- **BFF-side generation engine**: plan resolver, seeded PRNG, field-strategy dispatch, DAG-ordered
  executor, checkpoint/manifest store. Runs inside the same Spring Boot process as the rest of the
  BFF (D2) and calls ALM exclusively through the D7 write-safety client — it has no private HTTP path
  to `/qcbin`.
- **SPA-side UI panel**: plan authoring form (counts/distributions), allowlist-gated "Run" action,
  dry-run preview, live progress against the checkpoint stream, and a "Clean up this run" button.

**Use cases**: (1) seeding integration-test fixtures for Alt-ALM's own read/write test suite and for
third-party integrations being tested against ALM; (2) populating demo/sandbox projects with
plausible, interlinked data for stakeholder walkthroughs; (3) load-shaped data for pagination/query
performance testing (large flat collections — defects, requirements — where only volume matters and
DAG depth can be shallow).

Non-goals for the MVP (see §9): parameter-*definition* authoring, BPT, mail, cross-project or
cross-instance generation, anything requiring OTA/COM unless the bridge (D3) is present and its
capability flag is set.

---

## 2. Safety model (normative)

This section uses MUST/SHOULD per RFC-2119-style convention, binding for every implementation of the
generator engine.

1. **Dry-run is the default mode.** The generator MUST NOT issue any non-`GET` request to ALM unless
   invoked with an explicit `--execute`/`live: true` flag (naming is implementation-detail; the
   *default absence of a flag* MUST mean dry-run). A dry-run invocation MUST still perform the read
   calls needed to resolve metadata and roots (§4), since those are non-destructive.
2. **Explicit allowlist, not a live-project picker.** The generator MUST refuse to write to any
   `(domain, project)` pair not present in an operator-maintained allowlist (config file or DB table,
   outside `Secrets/`). Refusal MUST be a hard stop before any write call is constructed — not a
   warning the operator can click through. The allowlist check happens even in dry-run mode's plan
   validation step, so a plan against a non-allowlisted project is flagged before the user ever
   reaches the "execute" button.
3. **Refusal semantics.** A refused run MUST: emit a single, specific error identifying which check
   failed (allowlist / missing seed / plan-validation error), perform zero writes, and leave no
   partial checkpoint file behind (or mark the checkpoint `REFUSED` if one was already opened for
   planning). Refusal is not an exception bubbling up from a failed API call — it is a pre-flight gate.
4. **Provenance prefix.** Every created record whose entity type carries a user-facing textual field
   (`name` on requirement/test/test-folder/test-set/test-instance/defect/release/release-cycle/
   release-folder/milestone/resource; `key` is not applicable here) MUST have that field prefixed with
   a configurable run marker, default `ALTALM-GEN-<runid>` (`<runid>` = an opaque generator-assigned
   run identifier, not the seed itself, so the same seed can be replayed under a fresh run marker for
   comparison). Default separator/format: `ALTALM-GEN-<runid>-<ENTITY>-<ordinal>`, e.g.
   `ALTALM-GEN-8f3a-REQ-014`.
5. **Entities with no textual field cannot carry the prefix directly** — `req-trace`,
   `requirement-coverage`, `test-config-coverage` (auto-created side effect, §2.11 data-model),
   `defect-link`, `run-step`, and `run` itself (its `name` is server-assigned on Fast_Run synthesis
   and **cannot be overridden**, `alm-data-model.md` §2.9/§6, `alm-api-reference.md` §6.7b — this is a
   verified, non-negotiable constraint, not a generator choice). For these, provenance is
   **transitive**: every foreign key on such a record MUST point only at records created in the same
   run (or, for `run`, at a provenance-prefixed `test-instance` under a provenance-prefixed
   `test-set`). This is spelled out fully in §4's DAG table.
6. **Two cleanup modes.**
   - **Manifest replay (default, precise).** Every run writes a checkpoint/manifest (§3) recording
     every created `(entity-type, id)` pair in creation order. Cleanup-by-manifest deletes exactly
     those ids, in **reverse** of that order (children before parents — e.g. `run-step` before `run`,
     `requirement-coverage` before `requirement`), and MUST stop and report on the first delete that
     returns a non-2xx *and* fails a verify-by-GET check that the row still exists (§8).
   - **Prefix sweep (fallback, best-effort orphan recovery)** — used when no manifest is available
     (lost checkpoint, manual invocation) or to catch cross-run orphans. Query
     `{name[ALTALM-GEN*]}` (or the configured prefix) against every named entity collection, delete in
     the reverse-dependency order of §4's DAG, then **cascade** to unnamed children by looking up their
     parent-id/first-endpoint-id against the just-deleted (or about-to-be-deleted) prefixed ids —
     e.g. delete `run-step` rows whose `parent-id` matches a prefix-swept `run`; delete `run` rows whose
     `testcycl-id` matches a prefix-swept `test-instance`; delete `requirement-coverage` rows whose
     `requirement-id` matches a prefix-swept `requirement`.
7. **The generator MUST refuse to delete non-prefixed records.** Both cleanup modes operate only on
   ids that are either (a) present in a manifest this generator itself wrote, or (b) reachable by the
   prefix-sweep-plus-cascade rule in (6). There is no "delete everything in this project" mode and no
   free-text id list accepted for deletion.
8. **Write attribution.** All generator writes ride the same single service-account session as the
   rest of the BFF (D4) — ALM-side `owner`/`detected-by`/`vc-checkin-user-name` fields resolve to
   whichever seeded project user the generator's `UsersList` strategy picked (§5), not automatically to
   the service account, except for fields the server always stamps with the calling identity
   regardless of payload (`req-trace.owner`, `defect-link.owner`, per `alm-api-reference.md` §6.3/§6.5
   — these will show the service account, an honest, documented limitation carried from D4).
9. **Secrets never logged.** The generator MUST NOT log the service-account key/secret, session
   cookies, or XSRF token at any log level, including debug. This is delegated to and enforced by the
   D7 write-safety component's masking rule; the generator engine MUST NOT bypass D7 by constructing
   its own HTTP calls.

---

## 3. Run model

- **Seedable PRNG.** A single 64-bit seed (user-supplied or generator-assigned and echoed back)
  drives every stochastic decision — record counts within configured ranges, distribution sampling,
  lorem-ipsum-style text generation, field-value picks. The engine MUST use one deterministic PRNG
  stream, seeded once per run, and MUST NOT reseed from wall-clock time anywhere in the hot path
  (wall-clock is permitted only for the non-deterministic parts of the *run marker*, `<runid>`, and
  for genuinely-current fields like `creation-time` where the plan doesn't pin a synthetic date).
- **Generation plan** — a declarative config (YAML/JSON), not code: per-entity counts or count *ranges*,
  hierarchy shape parameters (requirement tree depth/fanout, test-folder depth), coverage/link density
  knobs (§7), the target `(domain, project)`, the provenance prefix override, and feature toggles
  (`stepParameters.otaBridge`, `richText.embeddedImages`, `targetRelCycle` — all default off per §9).
- **Plan → resolved DAG → execution.** The plan is first resolved into a concrete, ordered list of
  entity-creation "nodes" (§4) with actual counts (ranges sampled once, deterministically, from the
  seed) — this resolved DAG is what dry-run previews and what the manifest is checked against.
  Execution then walks the DAG level by level (a level = a set of nodes with no unresolved
  dependencies among themselves), issuing writes via D7 and recording each success in the manifest
  before advancing to the next level. A level MUST NOT begin until every write in the previous level
  has been confirmed created (via response `id` or, after a 5xx, via verify-by-query, §8) — this is
  the direct consequence of bulk being non-transactional (`alm-api-reference.md` §4.5): a generator
  that fires bulk requests for level *N+1* referencing unconfirmed ids from level *N* risks writing
  child records that point at nothing.
- **Checkpointing.** The manifest is an append-only log (entity type, ordinal, resolved payload
  digest, server-assigned id, timestamp) flushed after every confirmed write, not batched — a crashed
  or killed run can be resumed by replaying the plan, skipping any node whose manifest entry already
  exists, and picking up at the first unconfirmed node. Resume MUST re-verify (GET) the last
  manifest-confirmed id before trusting it, in case the crash happened between the write succeeding and
  the flush landing.
- **Dry-run output** = the full resolved plan preview: exact per-entity counts, the DAG shape, and at
  least one fully-rendered sample record per entity type (complete field values as they would be sent,
  including the deterministic field order of §8) — enough for a reviewer to judge the plan without any
  network call beyond the metadata/root-discovery reads already required to resolve the plan.

---

## 4. Creation-order DAG

Reproduces the verified DAG from `alm-data-model.md` §2.11, annotated with per-node status and the
exact minimal field set each node writes (from `alm-data-model.md` §3 and `alm-api-reference.md` §6
recipes). **Clarification on D6's linear phrasing**: D6 lists the order as "releases/cycles/milestones
→ requirements (+traces) → test tree → coverage → test-lab chain → runs → defects + links." The
underlying DAG is not a single chain — releases/milestones, requirements, and the test tree are three
**independent branches** that only join at (a) `requirement-coverage` (needs a requirement *and* a
test) and (b) `requirement.target-rel`/`target-rcyc` (needs a requirement *and* a release, write path
deferred, §9). D6's list is a valid topological sort of that DAG, not evidence of a direct
release→requirement dependency; the generator's executor should run the release branch, requirement
branch, and test branch as parallel levels rather than strictly sequentially, since nothing in the
verified model requires releases to exist before requirements or tests are created.

```
release-folder (root: UNVERIFIED — discover via {parent-id[0]} at runtime, never hardcode)  [§9]
  └─ release            name, start-date, end-date, parent-id            VERIFIED  [api-ref §6.7]
       ├─ release-cycle name, start-date, end-date, parent-id            VERIFIED  [api-ref §6.7]
       │                (dates MUST fall inside parent release's window — server-enforced 500 otherwise)
       └─ milestone     name, parent-id (=MS_RELEASE_ID, i.e. the release id)   VERIFIED  [data-model §2.8]

requirements/0 "Requirements" (root VERIFIED)
  └─ requirement        name, type-id, parent-id                        VERIFIED  [api-ref §6.1]
       ├─ req-trace         from-req-id, to-req-id                      VERIFIED  [api-ref §6.3]
       │   (no name field — provenance transitive via both endpoint ids)
       ├─ requirement-coverage  requirement-id, test-id, entity-type="test"  VERIFIED [api-ref §6.2]
       │   (needs a test to exist first — cross-branch join; no name field)
       │    └─ test-config-coverage  AUTO-CREATED side effect, do not POST directly  VERIFIED (side-effect only)
       └─ target-rel / target-rcyc   DEFERRED — write path UNVERIFIED (§9, data-model §2.10)

test-folders/2 "Subject" (root VERIFIED, project-specific — discover at runtime, never hardcode)
  └─ test-folder        name, parent-id                                 VERIFIED  [data-model §3]
       └─ test          name, parent-id, subtype-id="MANUAL"            VERIFIED  [api-ref §6.4]
            ├─ design-step  name, parent-id, description, expected      VERIFIED  [api-ref §6.4]
            │   (param tokens MUST be entity-pre-encoded, §6 below)
            │    └─ step-parameter   DEFERRED — no REST create path, OTA-bridge candidate (§9)
            └─ test-config   DEFERRED for direct create — UNVERIFIED (§9, data-model §2.6);
                              generator relies on the implicit default config a test carries

test-set-folders/0 "Root" (root VERIFIED)
  └─ test-set-folder    name, parent-id (type LookupList, not Number — see §5 trap)   VERIFIED
       └─ test-set      name, subtype-id="hp.qc.test-set.default", parent-id  VERIFIED [api-ref §6.7b]
            └─ test-instance  test-id, cycle-id(=test-set id, TRAP), subtype-id  VERIFIED
                 └─ run   NO name field, server-assigned "Fast_Run_<M>-<D>_<HH-MM-SS>",
                          created ONLY via PUT test-instance.status (Fast_Run synthesis) — direct
                          POST runs is a VERIFIED dead end (8 failed attempts, api-ref §6.7b).
                          Provenance rides ENTIRELY on the parent test-instance/test-set prefix;
                          the run's own name can never carry ALTALM-GEN-*.
                      └─ run-step  AUTO-COPIED from design-steps on synthesis, no independent
                                   create attempted by the generator (§9)   VERIFIED (synthesis path only)

defect                name, detected-by, creation-time, severity          VERIFIED  [api-ref §6.5]
  └─ defect-link       first-endpoint-id, second-endpoint-id,
                       second-endpoint-type ∈ {defect, requirement} (only 2 confirmed values)  VERIFIED
                       (no name field — provenance transitive via both endpoint ids)

attachment (any entity id; octet-stream+Slug always works; multipart ref-subtype=1 CONFIRMED)  VERIFIED
  (used by the rich-text/image strategy, §6, not a standalone generator node)
```

**Execution levels** (each level's writes MUST complete/verify before the next begins, per §3):
`[release-folder]` → `[release]` → `[release-cycle, milestone]` (parallel with) `[requirement]` and
`[test-folder]` → `[requirement's children: req-trace]` and `[test]` → `[design-step]` and
`[requirement-coverage]` (needs both requirement and test levels done) → `[test-set-folder]` →
`[test-set]` → `[test-instance]` → `[run via Fast_Run PUT]` → `[defect]` → `[defect-link]`.

---

## 5. Field-type → strategy matrix

Per `alm-data-model.md` §4 and `alm-api-reference.md` §8 (8 types, no Boolean). For every field the
strategy dispatch is: **skip if `System=true AND Editable=false`** (191 of 432 probed fields —
non-negotiable, never write `id`, any `vc-*`, any `has-*`, `last-modified`, or a `Size=99999`
virtual/computed field) → else look up `Required` to decide whether omission is legal → else dispatch
by `Type`.

| Type | Strategy | Constraints / traps |
|---|---|---|
| **String** | Bounded lorem-style text generator, length capped by the field's `Size` (skip cap if `Size=-1` or `99999` — those are never written anyway, previous rule) | Most flag-shaped Strings (`has-*`, `attachment`) are read-only/system — filtered out before dispatch, never hit this row [data-model §4] |
| **Memo** | Full rich-text block per §6 below | Uniformly `Size=-1`. Stored as complete `<html><body>…</body></html>` doc, not a fragment [api-ref §7] |
| **Number** | Integer/decimal in a plan-configured range; for id-like Number fields that are actually foreign keys (e.g. `requirement-id` on `requirement-coverage`), pull from the manifest of already-created ids for that entity type, never randomly | `run.cycle-id` is typed `String` despite being numeric-looking — send as a JSON string, not a bare number, in every field regardless of declared type (the whole envelope is string-typed) [data-model §2.7] |
| **Date** | `yyyy-MM-dd` literal, sampled from a plan-configured window | Release-cycle dates MUST be sampled inside the parent release's own `start-date`/`end-date` window — server-enforced 500 otherwise [api-ref §6.7]. No documented timezone rule — `UNVERIFIED`, treat as server-local |
| **DateTime** | `yyyy-MM-dd HH:mm:ss` literal | Same format rule as Date; no separate timezone finding beyond Date's `UNVERIFIED` note |
| **LookupList** | Fetch valid values for the field's `List-Id` at runtime (`customization/used-lists/{id}/items` — the list-of-values editor, not the unrelated `list-items` project-entity collection, `alm-api-reference.md` §5) and weighted-random-pick per §7's distribution defaults | List-Ids are per-instance — **never hardcode a list id or its item values**. `test-set.parent-id` (`CY_FOLDER_ID`) and `test-set-folder.parent-id` (`CF_FATHER_ID`) are typed `LookupList` even though semantically they are tree-parent references — the generator must still write the target folder's id as the value despite the type oddity [data-model §4] |
| **UsersList** | Pick uniformly (or per §7 weighting) from the pool of project users seeded by the pre-run **user-seeding step** (`POST /qcbin/v2/sa/api/site-users` then `POST .../projects/{p}/users`, both confirmed reachable under the sandbox's Customer Admin role) [data-model §6, api-ref §6.9] | Without seeding, every `UsersList` field (77 fields across 11 entities) degenerates to the single existing project user — the generator MUST run (or the operator MUST have already run) user seeding before any plan with `count > 1` unique-user expectations |
| **Reference** | Foreign-key pull from the manifest of already-created ids of the referenced entity type, honoring the DAG level ordering of §4 | Only 2 fields in the whole probed model are multivalue — both `Reference`, both on `requirement` (`target-rel`, `target-rcyc`) — write path UNVERIFIED, **deferred**, not attempted in the MVP (§9) [data-model §2.10, §4] |

**UDF handling.** `user-NN` fields (physical `XX_USER_NN`, ≤99 per entity, memo UDFs capped at 5 or
15 with `EXTENDED_MEMO_FIELDS=Y`) are discovered from the same
`customization/entities/{entity}/fields` metadata call as every other field and carry a normal `Type`
attribute — the dispatch table above applies to them unmodified; there is no UDF-specific code path
[api-ref §6.8].

---

## 6. Rich-text block grammar

Memo fields (`description`, `req-rich-content`, design-step `description`/`expected`, etc.) are
generated as **complete HTML documents**, `<html><body>…</body></html>`, never a bare fragment
[api-ref §7]. The sanitizer is deployment-specific (`sanitizer-whitelist.xml`, not universal) but the
following subset was directly verified on the sandbox and is the grammar the generator MUST target:

**Survives intact**: `<font color>`, inline `style=` attributes, `href` on `<a>`, `<table>`/`<tr>`/
`<td>` (gains an implicit `<tbody>` wrapper the generator did not write — expected, not a bug),
already-double-escaped entity text (preserved literally, not re-decoded). Inline tags (`<b>`, `<i>`,
`<u>`, `<font>`, `<a>`, `<span>`) are not reformatted; block tags (`<body>`, `<ul>`, `<li>`, `<table>`,
`<tr>`, `<td>`, `<div>`) get whitespace/pretty-print normalization inserted around them.

**Stripped**: `<script>…</script>` removed entirely. A bare filename or relative-path `<img src>`
loses only its `src` attribute (tag survives, attribute silently dropped) — never generate a relative
`img src`.

**Untested (`UNVERIFIED`, do not rely on)**: bare top-level `<style>` blocks, inline event-handler
attributes (`onclick=` etc.) — the generator SHOULD NOT emit either until probed.

**Parameter tokens.** A raw `<<<name>>>` token is parsed by the sanitizer as a malformed tag and
collapses to `<<>>`, destroying the parameter name. The generator MUST HTML-entity-pre-encode the
angle brackets — send `&lt;&lt;&lt;name&gt;&gt;&gt;` — which survives round-trip intact and still
flips `has-params="Y"` [data-model §6, api-ref §6.4]. **General rule, not just for parameters**: any
literal `<`/`>` in free text the generator did not itself construct as valid, whitelisted markup MUST
be entity-encoded before insertion, to avoid the same malformed-tag collapse on arbitrary generated
text.

**Embedded images** — two confirmed, REST-only paths, either usable by the `richText.embeddedImages`
plan toggle (default off, §9):
1. Upload via `application/octet-stream` + `Slug: <filename>` (`ref-subtype=0`), then reference it with
   an **absolute REST attachment URL** in `<img src>` (a relative path or bare filename silently drops
   the `src` attribute, per above).
2. Upload via hand-built `multipart/form-data` with `ref-subtype=1` (explicit boundary, CRLF
   discipline, text parts first, `file` part **last**, `Content-Type: image/png` on the file part —
   PowerShell's `-Form` constructor is known to fail here; whatever HTTP client Alt-ALM's BFF uses
   MUST be integration-tested against the real server before relying on this path, per D2's named
   multipart risk), then reference it via that same absolute URL or a `data:` URI — both confirmed to
   survive intact [api-ref §6.6, data-model §6].

**Round-trip validation MUST compare canonicalized HTML, never raw bytes** — implicit `<tbody>`
insertion and whitespace pretty-printing are expected, not failures [api-ref §7].

---

## 7. Distribution defaults

Design defaults for the generation plan — **tunable knobs, not API facts**, each overridable per-plan.
No citation applies to this section beyond the structural constraints already stated above (e.g. cycle
dates must stay inside the release window regardless of the distribution chosen).

| Knob | Default shape | Notes |
|---|---|---|
| Requirement tree depth | 3–4 levels | Root-level nodes typed `Folder`(1)/`Group`(2); leaf-level nodes typed `Functional`(3) or `Testing`(5) so they carry direct coverage eligibility |
| Requirement tree fanout | 2–6 children per non-leaf node, right-skewed (most nodes near the low end) | Avoids a uniformly-bushy, unrealistic tree |
| Test-folder tree depth/fanout | 2–3 levels, 3–8 tests per leaf folder | Mirrors typical "module → sub-feature → tests" shapes |
| Requirement coverage density | 60–80% of coverage-eligible requirements (type Functional/Testing/Performance/Business Model) linked to ≥1 test | Remaining 20–40% intentionally left uncovered — realistic gap for coverage-report testing |
| Cross-requirement trace density | 5–15% of requirement pairs within the same subtree linked via `req-trace` | Directed edges, no attempt to avoid cycles (real ALM data has cycles too) |
| Defect status mix | New 40% / Open 25% / Fixed 15% / Closed 15% / Rejected 5% | REST bypasses workflow scripts by default (`CLIENT_TYPES_BYPASS_REST_WF`, api-ref §6.11) so any status is settable directly — the generator must synthesize this mix itself, no server-side state machine to lean on |
| Defect severity mix | 1-Low 30% / 2-Medium 35% / 3-High 20% / 4-Critical 10% / 5-Urgent 5% | Values are literal list strings (e.g. `"1-Low"`), fetched from the real list at runtime per §5, not hardcoded — this row states relative weights only |
| Run pass/fail mix (post Fast_Run) | Passed 65% / Failed 20% / No Run 10% / Blocked 5% | Set via a second `PUT test-instances/{id}` status call after synthesis, or via `status` on the synthesized run/run-steps directly |
| Cross-link density (defect-links) | 5–10% of generated defects linked to another defect or to a requirement | Split roughly 70/30 defect↔defect vs. defect↔requirement, reflecting the two confirmed `second-endpoint-type` values only |

---

## 8. Write mechanics (delegated to the D7 write-safety component)

The generator engine is a **caller** of D7, not a reimplementation of it — one client, one test suite,
per the lead brief. What the generator relies on D7 for, and what it must supply on top:

- **Ordered serialization**: D7 owns per-entity-type deterministic `Fields` array ordering (§3.2,
  `alm-api-reference.md` — wrong order produces opaque NPE-style 500s, e.g. requirement create only
  succeeds as `name, parent-id, type-id`, every other tried order fails). The generator supplies field
  *values*; D7 supplies the *order*. The generator MUST NOT construct its own `Fields` array
  serialization bypassing D7.
- **XSRF**: handled transparently by D7's session manager; the generator never touches the token.
- **5xx-verify-by-query with dedup on retry**: an HTTP 5xx on any generator write is "unknown outcome,"
  never "failed" (`alm-api-reference.md` §3.3 — a real leftover row was found this way in probing). D7
  performs the verify-by-GET before allowing a retry. The generator's contribution is making that
  verification query cheap and unambiguous: deterministic, prefixed names (§2.4) mean the verify query
  is an exact `{name["ALTALM-GEN-<runid>-REQ-014"]}` lookup, not a fuzzy match. For unnamed join
  entities (req-trace, requirement-coverage, defect-link), the dedup key is the tuple of foreign keys
  (e.g. `from-req-id`+`to-req-id`) queried via the same mechanism.
- **Bulk usage policy**: size cap 2000 (`REST_API_MAX_BULK_SIZE`, api-ref §4.5), used only *within* a
  single DAG level (§3/§4) for same-entity-type batches — never across levels, since bulk is
  **non-transactional** (a 409 partial failure must be parsed per-item, `BulkEntry[]` with
  `Successful`/`EntityId`/`EntityType`) and the executor cannot let level *N+1* start writing children
  that reference a level-*N* id that turned out to be one of the failed bulk entries. On a 409, the
  generator marks the failed ordinals' manifest entries `FAILED` and either retries them individually
  (small remainder) or aborts the level, per plan configuration — it MUST NOT silently proceed to the
  next level with gaps in the manifest.
- **Paging for verification reads**: `page-size`/`start-index` (1-based), default 100, silently capped
  at 2000 (`alm-api-reference.md` §4.4) — the generator's cleanup-by-prefix-sweep (§2.6) and any
  bulk-verify query MUST page through full result sets rather than assuming a single page holds every
  match, especially on a project that has accumulated multiple prior runs' orphans.

---

## 9. Gaps & deferred features

Each item below is off by default in the MVP plan schema (§3) and requires an explicit capability flag
or is entirely out of scope, with its evidence pointer.

| Gap | Status | Evidence | MVP treatment |
|---|---|---|---|
| **Test-parameter *definition*** | REST-unreachable — every create shape returns 500 "Test parameter does not exist," even after entity-encoded tokens flip `has-params=Y` | `alm-data-model.md` §6, §7; `alm-api-reference.md` §6.4, §9 | Deferred behind `stepParameters.otaBridge` capability flag (D3's OTA sidecar); the generator still emits entity-encoded `<<<name>>>` markers in step text (cosmetic, §6) but does not attempt to create a bound `step-parameters` object unless the bridge reports the capability present |
| **BPT (Business Process Testing)** | `components` → 403 license-gated; `business-components` → 404 | `alm-data-model.md` §6.7b; `live-probe-log.md` Probe 6 | Out of scope for the MVP entirely; OTA-fallback candidate, not planned before D3's bridge exists |
| **Mail** (`POST .../{id}/mail`) | Body shape genuinely undocumented — 3 JSON shapes + 1 XML shape all failed | `alm-api-reference.md` §9 | N/A to record generation; not attempted |
| **`requirement.target-rel`/`target-rcyc`** (and the parallel `requirement-target-releases`/`-cycles` collections) | Write path UNVERIFIED — unclear whether the requirement's own multivalue field or the separate collections are the real join surface; neither probed | `alm-data-model.md` §2.10, §7 | Deferred behind `targetRelCycle` capability flag; generated requirements carry no release/cycle targeting links until settled |
| **`test-config` direct create** | UNVERIFIED — existence and the auto-bound relationship on synthesized runs are confirmed; a from-scratch `POST test-configs` was never probed | `alm-data-model.md` §2.6, §3, §7 | Generator never POSTs `test-configs` directly; relies on the implicit default config every test carries |
| **`release-folder` root** | UNVERIFIED — every probed release create used `parent-id=1` without a prior root-discovery query; unlike the other three trees, no confirmed root value exists | `alm-data-model.md` §2.1, §7 | Generator MUST run `release-folders?query={parent-id[0]}` at runtime before the first release-folder create in any plan; MUST NOT reuse the other trees' hardcoded-root convenience |
| **`run-step` independent CRUD** | Only the Fast_Run auto-copy path is confirmed; standalone POST/PUT/DELETE never probed | `alm-data-model.md` §3, §7 | Generator never attempts a standalone run-step create; run-steps only ever appear via Fast_Run synthesis, then may be PUT to set status per §7's pass/fail mix |
| **`defect-links` beyond `defect`/`requirement`** | Only those two `second-endpoint-type` values confirmed; test/run/test-instance UNVERIFIED | `alm-data-model.md` §2.5; `alm-api-reference.md` §6.5 | Generator restricts defect-link generation to the two confirmed types |
| **Direct `POST runs`** | Definitively FAILS (8 attempts, two distinct 500 modes) — not a gap so much as a closed door, restated here because it shapes the entire Test Lab strategy | `alm-data-model.md` §2.9; `alm-api-reference.md` §6.7b | Generator exclusively uses `PUT test-instances/{id}.status` (Fast_Run synthesis); direct run POST is never attempted, not even as a fallback |

---

## 10. Acceptance criteria (generator MVP)

Testable statements; each should map to an automated check in the eventual test suite.

1. **Refusal is total.** Given a plan targeting a `(domain, project)` absent from the allowlist,
   invoking the generator in either dry-run or execute mode performs **zero** non-`GET` HTTP calls and
   returns a single, specific refusal error.
2. **Dry-run never writes.** Given a valid, allowlisted plan invoked without the execute flag, network
   capture shows zero `POST`/`PUT`/`DELETE` calls; the returned preview shows exact resolved counts per
   entity type and at least one fully-rendered sample record per entity type in the plan.
3. **Determinism.** Two executions of the same plan with the same seed against a clean sandbox produce
   field-for-field identical outgoing payloads (excluding server-assigned ids/timestamps and the
   run-scoped `<runid>` marker itself).
4. **Provenance coverage.** Every created record that has a textual field carries the configured prefix
   in that field; every created record that does not (req-trace, requirement-coverage, defect-link,
   run, run-step) is reachable from a prefixed ancestor via its foreign keys, as enumerated in §4.
5. **Cleanup completeness and safety.** Running manifest-replay cleanup after a live run deletes 100%
   of that run's manifest ids and zero records outside the manifest; running prefix-sweep cleanup
   against a project containing both generator output and pre-existing unprefixed data deletes only the
   prefixed records and their cascaded unnamed children, leaving unprefixed record counts unchanged.
6. **No bare deletion path.** There is no code path by which the generator issues a `DELETE` (single or
   bulk `ids-to-delete=`) using an id that came from neither the manifest nor the prefix-sweep cascade
   of §2.6.
7. **DAG ordering holds.** Across a full-plan execution, no write for a dependent entity (e.g.
   `requirement-coverage`) is issued before its prerequisite entity's (e.g. the `test`) creation has
   been confirmed (response `id` or post-5xx verify-by-query) in the manifest.
8. **Fast_Run exclusivity.** Zero direct `POST runs` calls are ever issued by the generator; every
   `run` in a generated dataset originates from a `PUT test-instances/{id}.status` call, and its
   `run-step` children match the source test's `design-step` count exactly.
9. **UsersList realism.** When user-seeding has been run for a plan with `count > 1` unique-user
   expectation, no two `UsersList`-typed fields across the generated dataset that were independently
   sampled resolve to the same single degenerate user (barring intentional low-cardinality plans).
10. **Rich-text survivability.** Every generated design-step containing a parameter marker round-trips
    with the marker's name intact (canonicalized-HTML comparison, not byte-equality) and its owning
    test/design-step shows `has-params="Y"`.
11. **Release-cycle date containment.** No generated `release-cycle` is ever rejected by the server's
    date-window validation — i.e., the Date-strategy sampler for cycle dates always draws from inside
    the already-created parent release's `start-date`/`end-date`, verified by zero 500s of the
    "start date cannot be later than release's end date" shape across a full run.
12. **5xx dedup.** Injecting a simulated 5xx on a single write during a test run results in exactly one
    committed record for that logical (seed, entity-type, ordinal) key — never zero (silent failure)
    and never two (duplicate from a naive retry).
