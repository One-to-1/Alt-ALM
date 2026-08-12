# OpenText ALM / Quality Center — Entity & Data Model Reference

Reconciled entity/data-model reference for OpenText ALM/QC, scoped like its sibling
[`alm-api-reference.md`](alm-api-reference.md) (**read that first** — it covers transport/API
mechanics: auth, query grammar, envelopes, error codes, rich-text sanitization, field-order and
5xx-commit hazards. This document does **not** restate that content; it cross-references it) to our
**ALM 26.1 sandbox** as ground truth.

**Provenance tags**: `[probe]` = observed directly against our sandbox (`live-probe-log.md` or a
`_raw/probe*` report, or a `tests/fixtures/write-probe/*` response fixture); `[fixture]` =
`tests/fixtures/customization-fields-*.json` field-descriptor dump (offline, no server call this
session, but itself probe-captured); `[swagger]` = the per-instance OpenAPI fixtures; `[resource-list]`
= the `GET /qcbin/rest/resource-list` inventory fixture; `[docs-research]` = wave-1 subagent
documentation research, not independently probed; `UNVERIFIED` = no direct evidence, with the
experiment that would settle it.

**Source priority for this document** (highest wins conflicts): (1) `live-probe-log.md`, (2)
`_raw/probe4-write-round-1.md` / `probe5-write-round-2.md` / `probe3-mining-fieldtypes.md` /
`probe3-mining-swagger.md`, (3) `tests/fixtures/customization-fields-*.json` (15 entities) +
`customization-requirement-types.txt` + `customization-used-lists.json`, (4) `_raw/wave1-03..07`
documentation research. Zero server calls were made to produce this document — every claim traces to
an already-captured file.

---

## 0. A note on `r3-*` fixtures — narration now complete

This document was drafted in parallel with the write-round-3 write-up, so its `[probe]` citations of
`r3-*` fixtures carry "(unnarrated `r3-*` fixture)" notes. **Those are now resolved**: round 3 is
fully narrated as **Probe 6 in `live-probe-log.md`** (and `_raw/probe6-write-round-3.md`), which
confirms every `r3-*`-based finding cited here (Fast_Run synthesis, run-step auto-copy, multipart
`ref-subtype=1` success). Treat "(unnarrated `r3-*` fixture)" below as a plain pointer to
live-probe-log.md Probe 6. [Lead review 2026-08-12.]

---

## 1. Entity catalog

The REST surface exposes **62 top-level, creatable/listable project-entity collections**
(`GET`+`POST` on a bare `/domains/{d}/projects/{p}/{collection}` path) — the authoritative,
independently-derived catalog `[resource-list]` (probe3-mining-swagger §3d; see `alm-api-reference.md`
§5 for the generic sub-resource contract shared across them: attachments, `{id}/lock`, `{id}/audits`,
`{id}/mail`, `groups/{field}`, `copy`, bulk `?ids-to-delete=`, `versions`). Grouped by domain below;
**★ marks the 15 entities with a metadata-probed field descriptor** (`tests/fixtures/
customization-fields-*.json` — requirement, test, design-step, test-config, test-folder,
test-set-folder, test-set, test-instance, run, run-step, defect, release, release-cycle,
release-folder, resource) `[fixture]`.

| Domain | Collections (62 total) |
|---|---|
| **Requirements** (5) | ★requirements, req-traces, requirement-coverages, requirement-target-cycles, requirement-target-releases |
| **Test Plan** (11) | ★test-folders, ★tests, ★design-steps, ★test-configs, test-config-coverages, test-criterion-coverages, test-criterions, test-parameters, step-parameters, ★resources, resource-folders |
| **Test Lab** (8) | ★test-set-folders, ★test-sets, ★test-instances, ★runs, ★run-steps, test-executions, results, lab-runs-protocol-granularities |
| **Defects** (2) | ★defects, defect-links |
| **Releases & Milestones** (4) | ★release-folders, ★releases, ★release-cycles, milestones |
| **Generic/cross-cutting** (4) | attachments, locks, environments, bpm-folders |
| **Personal workspace** (6) | favorites, favorite-folders, dashboard-folders, dashboard-pages, workspaces, workspace-folders |
| **Customization list-values** (1) | list-items |
| **CI/SCM integration** (14) | build-artifacts, build-code-refs, build-contexts, build-instances, build-servers, build-types, changesets, changeset-files, changeset-link-associations, branch-policy-links, policy-items, scm-branchs, scm-branch-releases, scm-repositorys |
| **Business-views / analysis** (5) | analysis-item-file, analysis-item-files, analysis-item-folders, analysis-items, analysis-segments |
| **Lab hosts** (2) | bv-hosts, host-groups |

`[resource-list]` (probe3-mining-swagger §3d). The 15 ★ entities are the only ones with a directly
inspected field descriptor in this session; everything else is known to exist (collection-level, plus
its generic sub-resource surface) but its field shape is `UNVERIFIED` beyond what a live
`customization/entities/{name}/fields` call would show.

**Trap — `list-items` vs. used-list items.** `list-items` (row above) is an ordinary **project-entity
collection** with its own `/lock`, `/mail`, `/groups/{groupsFields}`, bulk `?ids-to-delete=` — it is
**unrelated** to `customization/used-lists/{list-id}/items`, the list-of-values editor documented in
the v2 Swagger (`POST/PUT/DELETE .../used-lists/{list-id}/items(/{item-id})`, body = bare `Item`
object `{"value":"…"}`) `[swagger]` `[resource-list]` (probe3-mining-swagger §3d, §1.2). Two resources
sharing the word "list/items" — do not conflate them in the entity-model skill or the generator's
metadata layer.

---

## 2. Relationship map

### 2.1 Root/parent-id defaults — VERIFIED and one correction

| Tree | Root id | Status |
|---|---|---|
| Requirements | **`0`** ("Requirements") | `[probe]` VERIFIED round 2, clean state (`requirements/0` → 200, `name:"Requirements"`; `POST requirements parent-id=0` → 201, `father-name` returned `"Requirements"`) — round 1's `parent-id=1` was a **contaminated orphan**, not the root (see §2.4) |
| Test Plan (test-folders) | **`2`** ("Subject") | `[probe]` VERIFIED (`test-folders?query={parent-id[0]}` → exactly one hit, id=2, name="Subject") — **project-specific, discover at runtime, never hardcode** |
| Test Lab (test-set-folders) | **`0`** ("Root") | `[probe]` VERIFIED (`test-set-folders/0` → 200, name="Root") |
| Release folders | **UNVERIFIED** | Every release create probed so far used `parent-id=1` without a prior `parent-id[0]` discovery query (unlike the other three trees) — root value not confirmed; discover via `release-folders?query={parent-id[0]}` before hardcoding |

**Conflict resolved — the "test-set parent-id=2 discrepancy" in `alm-api-reference.md` §6.1 is
spurious.** That document states the round-2 test-set create fixture shows `parent-id=2` "not the
documented root id 0" and calls it an unadjudicated discrepancy. Direct inspection of
`tests/fixtures/write-probe/r2-test-set-create.json` shows the actual value is **`parent-id="5"`**,
not `2` — and round-2's own prose immediately preceding the test-set create states *"test-set-folders
under Root(0) → HTTP 201"* (`probe5-write-round-2.md` §c). **Adjudication: no discrepancy exists.**
The test-set was correctly created under a freshly-created intermediate `test-set-folder` (id 5 in
that run), which was itself created under the true root `test-set-folders/0` ("Root") in the same
probe session. `test-set-folders/0` = "Root" stands **uncontested**. `[fixture]` (direct read of
`r2-test-set-create.json`) overrides the unresolved-discrepancy note in `alm-api-reference.md`.

### 2.2 Requirement type-id table (condensed — full risk-analysis detail in `alm-api-reference.md` §6.1)

`GET customization/entities/requirement/types` → 8 types `[probe]` (`customization-requirement-types.txt`):

| id | name | has-direct-coverage | risk-analysis-type |
|---|---|---|---|
| 0 | Undefined | Y | 0 |
| 1 | Folder | N | 2 |
| 2 | Group | N | 2 |
| 3 | Functional | Y | 1 |
| 4 | Business | N | 1 |
| 5 | Testing | Y | 1 |
| 6 | Performance | Y | 1 |
| 66 | Business Model | Y | 1 |

`type-id` is a `Reference` field, physical `RQ_TYPE_ID`, **required** on requirement create; `3`
(Functional) is the value used in every successful probe create `[probe]` `[fixture]`.

### 2.3 Coverage chain (requirement ↔ test)

```
requirement-coverages          (requirement-id, test-id, entity-type="test")   [probe VERIFIED, POST 201]
        │  side effect: auto-creates exactly 1 row per link
        ▼
test-config-coverages          first-endpoint-id → requirement-coverages.id
                                second-endpoint-id → test-configs.id            [probe VERIFIED side-effect;
                                                                                  full CRUD UNVERIFIED beyond
                                                                                  the auto-created GET]
test-criterion-coverages       (BPT-criterion-level equivalent)                 [resource-list only, never probed]
```
Query `test-config-coverages` by `first-endpoint-id` (the coverage row's own id) — **not**
`requirement-id`, which doesn't exist as a field on `test-config-coverages` (probed, returned HTTP 400)
`[probe]` (probe4-write-round-1.md §6). `test-criterions`/`test-criterion-coverages` exist per
resource-list but were never write-probed `[resource-list]`.

### 2.4 Requirement ↔ requirement traceability

`req-traces`: `from-req-id` → `to-req-id`, both `Number`, required; directed edge; `owner`/
`creation-date` auto-populated `[probe]` VERIFIED, resolving wave1-03's "no REST surface found" gap
entirely (`[docs-research]` loses to `[probe]`).

### 2.5 Defect linking

`defect-links`: `first-endpoint-id`, `second-endpoint-id`, `second-endpoint-type` (String — confirmed
values `"defect"` and `"requirement"` only `[probe]`; `test`/`run`/`test-instance` `UNVERIFIED`).
Defect↔defect is non-directional per doc `[docs-research]`; the endpoint denormalizes
`second-endpoint-name`/`second-endpoint-status` per the resolved entity type regardless of which kind
it points at `[probe]`.

### 2.6 Test → test-config

`test-configs.parent-id` physical name **`TSC_TEST_ID`**, `Number`, **required, non-editable**
(fixed at create, cannot be reparented) `[fixture]`. Direct `POST test-configs` was never write-probed
this session (`UNVERIFIED` — its required-field shape from the fixture is `name`+`parent-id` only), but
its existence and the `test-config-id` auto-population on a synthesized run (§2.9) confirm the
relationship end-to-end.

### 2.7 Test Lab binding chain — including the legacy-naming trap

```
test-set-folders/{id}  (root=0 "Root")
        └─ test-sets            parent-id = test-set-folder id
                                 subtype-id = "hp.qc.test-set.default"                [probe VERIFIED]
              └─ test-instances  cycle-id  = TEST SET id   ← LEGACY NAMING TRAP
                                 test-id   = design test id
                                 subtype-id = "hp.qc.test-instance.MANUAL"
                                 test-config-id = auto-bound test-config              [probe VERIFIED]
                    └─ runs      testcycl-id = TEST INSTANCE id  ← the OTHER half of the trap
                                 cycle-id    = TEST SET id (same field name, same meaning as on
                                               test-instances — the trap is that "cycle" here means
                                               "test set", never "release cycle")
                                 test-config-id = auto-populated from the instance's bound config
                                 test-instance  = ORDINAL (1-based position of this test within the
                                               set), NOT a foreign key — do not confuse with
                                               testcycl-id
```
`cycle-id` = **Test Set** id; `testcycl-id` = **Test Instance** id — the single most error-prone
naming pair in the whole model, independently corroborated by community report + both wave1-05 and
direct probe `[docs-research]` `[probe]`. `run.cycle-id`'s field **type is `String`**, not `Number` —
inconsistent with `run.test-id`/`run.test-config-id` (both `Number`) and `run.testcycl-id` (typed
`Reference`, pointing at `test-instance`) `[fixture]` — a serialization-type trap worth a generator
unit test (send it as a numeric-looking JSON string in all three cases, never a bare JSON number,
since the whole `Fields`/`values`/`value` envelope is string-typed anyway — but be aware `cycle-id`'s
own metadata `type` differs from its siblings').

### 2.8 Milestones under releases

`milestones.parent-id` physical name **`MS_RELEASE_ID`** — milestones parent directly under a
**release**, not a folder or a milestone tree `[probe]` VERIFIED (`probe5-write-round-2.md` §e: two
guessed-root attempts against `parent-id=0`/`1` both failed with well-formed `"Invalid owner
specified"` 500s; `parent-id=<a real release id>` → 201). Cross-corroborated: `r2-milestone-create.json`
shows `parent-id="1004"` exactly matching the `id` of the release created earlier in the same probe run
(`r2-release-create.json`, `id="1004"`) `[fixture]`.

### 2.9 Run creation — two paths, one resolved by unnarrated `r3-*` evidence

**Direct `POST runs` — still FAILS**, all attempts, both probe rounds: `HTTP 500
{"Title":"Fail to get a must number attribute 'TESTSET'"}`. The full 48-field `run` descriptor dump
contains no physical column resembling `TESTSET` `[probe]` (probe5-write-round-2.md §c) — **genuine
open gap for a *direct*, from-scratch run creation.**

**Indirect path — CONFIRMED working, via unnarrated `r3-fastrun-full-entity.json` `[probe]`
(unnarrated `r3-*` fixture)**: PUT-ing a `test-instance`'s `status` field directly makes the server
synthesize a full `run` entity server-side, named `Fast_Run_<M>-<D>_<HH-MM-SS>`. This **resolves
wave1-05's UNVERIFIED #14 ("Fast_Run synthesis on direct instance-status PUT") as CONFIRMED TRUE** —
the synthesized run in the fixture carries `subtype-id="hp.qc.run.MANUAL"`, `test-config-id="1007"`
(auto-bound from the instance), `test-instance="1"` (ordinal, matches the instance's own ordinal),
`testcycl-name` ending in `"[1]"` (matches the ordinal), `cycle-id="5"` and `testcycl-id="5"`
(coincidentally equal in this fixture — `CYCLE` and `TESTCYCL` are separate DB sequences; this is
almost certainly incidental co-numbering across two independent id sequences in a near-empty sandbox,
not evidence the two fields are ever the same value by design — do not build generator logic on them
matching), and `status="Passed"`.

**Same fixture set also resolves wave1-05 UNVERIFIED #8 (run-step auto-copy) for this path**:
`r3-run-steps.json` shows **2 run-steps auto-created** under the synthesized run, each with a
`desstep-id` pointing at a real design-step id (1011, 1012) and `description`/`expected` text matching
those design-steps' content — **design-steps ARE copied into run-steps** when a run is created via this
path. However both run-steps carry `status="No Run"` even though the **parent run's own `status` is
`"Passed"`** — i.e., **no automatic status sync was observed in either direction** between a run and
its run-steps in this synthesis. The forward case was also probed in round 3: flipping a run-step to
`Failed` left the parent run's status unchanged — **no eager step→run aggregation** (caveat: the run
had been force-set `Passed` first, so this shows no auto-recompute, not an exhaustive matrix)
`[probe]` (live-probe-log.md Probe 6).

**Practical implication for the generator**: until a direct `POST runs` shape is found (candidate:
richer test-set field population before instance/run creation, per `probe5-write-round-2.md`'s own
follow-up recommendation — untested), the only **confirmed** way to get a `run` + populated
`run-steps` into the sandbox via REST is the instance-status-PUT → Fast_Run side effect, which also
sets a non-negotiable, auto-generated `name`. This is a workaround, not a designed creation path — flag
prominently in the generator's Test Lab strategy and the data-generator spec.

### 2.10 Requirement target-releases/-cycles — join semantics unresolved

`requirement.target-rel` / `requirement.target-rcyc` are the **only two multivalue fields in the
entire probed model** (`Reference` type, physical `RQ_TARGET_REL`/`RQ_TARGET_RCYC`) `[fixture]`
(§4). Separately, `requirement-target-releases` and `requirement-target-cycles` exist as their own
top-level collections in the 62-entity catalog `[resource-list]`. Whether writing the requirement's
own multivalue `target-rel`/`target-rcyc` field is the correct write path, or whether these two
collections are the real join-table surface (analogous to `test-config-coverages` being the
materialized join for `requirement-coverages`, §2.3) is **`UNVERIFIED`** — neither has been probed.

### 2.11 Creation-order DAG for the generator

```
release-folder (root: UNVERIFIED, discover at runtime)
  └─ release  (parent-id=folder id; start-date/end-date required)          [probe VERIFIED]
       ├─ release-cycle  (parent-id=release id; dates validated INSIDE
       │                  parent release's window — out-of-range → 500)   [probe VERIFIED]
       └─ milestone  (parent-id=MS_RELEASE_ID=release id)                 [probe VERIFIED]

requirements/0 "Requirements" (root, VERIFIED)
  └─ requirement  (name, type-id required; parent-id=0 for top-level)     [probe VERIFIED]
       ├─ req-trace  (from-req-id, to-req-id)                             [probe VERIFIED]
       ├─ requirement-coverage  (requirement-id, test-id — needs a test
       │    to exist first, see below)                                   [probe VERIFIED]
       │    └─ test-config-coverage (AUTO-CREATED side effect, do not
       │         POST directly for the normal path)                      [probe VERIFIED, side effect only]
       └─ target-rel/target-rcyc (multivalue; needs release/cycle to
            exist — write path UNVERIFIED, see §2.10)

test-folders/2 "Subject" (root, VERIFIED, project-specific — discover at runtime)
  └─ test-folder  (parent-id=folder id)                                   [probe VERIFIED]
       └─ test  (parent-id=folder id; subtype-id e.g. "MANUAL")           [probe VERIFIED]
            ├─ design-step  (parent-id=test id)                          [probe VERIFIED —
            │                                                              contradicts stale doc]
            │    └─ step-parameter (references a "Test parameter" object
            │         that has NO confirmed REST creation path)          [probe FAILED — genuine gap]
            └─ test-config  (parent-id=test id, TSC_TEST_ID, non-editable
                 after create)                                           [UNVERIFIED direct create;
                                                                             existence inferred]

test-set-folders/0 "Root" (root, VERIFIED)
  └─ test-set-folder  (parent-id=folder id)                               [probe VERIFIED]
       └─ test-set  (parent-id=folder id; subtype-id=
            "hp.qc.test-set.default")                                     [probe VERIFIED]
            └─ test-instance  (cycle-id=test-set id [TRAP], test-id=
                 test id, subtype-id="hp.qc.test-instance.MANUAL")        [probe VERIFIED,
                                                                              initial status "No Run"]
                 └─ run  DIRECT POST FAILS (§2.9) — only confirmed path
                      is PUT test-instance.status → Fast_Run synthesis    [probe: direct=FAILED,
                                                                              synthesis=VERIFIED via
                                                                              unnarrated r3 fixture]
                      └─ run-step  (auto-copied from design-steps on
                           Fast_Run synthesis; independent POST/PUT/
                           DELETE presence per resource-list, never
                           probed standalone)                             [probe VERIFIED for the
                                                                              synthesis path only]

defect  (name, detected-by, creation-time, severity required — no
  hierarchy field, defects are flat)                                      [probe VERIFIED]
  └─ defect-link  (first/second-endpoint-id + second-endpoint-type
       ∈ {defect, requirement} confirmed)                                 [probe VERIFIED]

attachment  (any entity id; octet-stream+Slug always works; multipart
  ref-subtype=1 embedded-image CONFIRMED working via hand-built
  multipart — see §6)                                                     [probe VERIFIED, both forms]
```

---

## 3. Per-entity notes (15 metadata-probed entities)

Required-field lists below are the `Required=true` set from each entity's
`customization-fields-*.json` fixture `[fixture]`; "verified create" cross-references
`alm-api-reference.md` §6 for the full JSON body where one exists — not restated here except where
this document adds detail (physical names, naming traps) the API reference omits.

| Entity | Fixture-required fields | Verified create? | subtype/type-id used | Naming trap |
|---|---|---|---|---|
| requirement | `name`, `type-id` (fixture does **not** flag `parent-id` required, though a value is needed in practice for a non-root create) | **Yes** [probe] | `type-id=3` (Functional) | none beyond the type-id table |
| test | `name`, `parent-id`, `subtype-id` | **Yes** [probe] | `subtype-id="MANUAL"` | `parent-id` physical name is `TS_SUBJECT`, not `TS_PARENT_ID` |
| design-step | *(fixture shows zero fields flagged `Required=true` — mismatch: `name`+`parent-id` are needed in practice for a working create, per the probe body)* | **Yes** [probe] | n/a | sibling-order field is `step-order` (`DS_STEP_ORDER`), not `order-id` — differs from test-folder/requirement |
| test-config | `name`, `parent-id` | **No** — existence + relationship inferred only (§2.6); direct create `UNVERIFIED` | n/a | `parent-id` = `TSC_TEST_ID`, non-editable after create |
| test-folder | `name`, `parent-id` | **Yes** [probe] | n/a | `parent-id` physical name `AL_FATHER_ID`; `name` physical name is `AL_DESCRIPTION` (not `AL_NAME`) |
| test-set-folder | `name` (`parent-id` NOT flagged required in fixture, but a real value is needed for non-root placement) | **Yes** [probe] | n/a | `parent-id` field **type is `LookupList`**, physical `CF_FATHER_ID` — not `Number` as every other folder tree |
| test-set | `name`, `subtype-id` | **Yes** [probe] | `subtype-id="hp.qc.test-set.default"` | `parent-id` field **type is `LookupList`**, physical `CY_FOLDER_ID` — same oddity as test-set-folder |
| test-instance | `test-id`, `cycle-id` | **Yes** [probe] | `subtype-id="hp.qc.test-instance.MANUAL"` (not fixture-required but used in every probe) | `cycle-id` = TEST SET id (§2.7); `test-instance` field = ordinal, not a foreign key; own `id` physical name is `TC_TESTCYCL_ID` |
| run | `cycle-id`, `name`, `status`, `test-id`, `testcycl-id`, `owner` | **Direct: No — FAILS.** Synthesis path: **Yes** (§2.9) | `subtype-id="hp.qc.run.MANUAL"` (not fixture-required) | `cycle-id` typed `String` (not `Number`); `testcycl-id` typed `Reference`; `cycle-id`≠`testcycl-id` (§2.7) |
| run-step | `parent-id` | Confirmed present only via Fast_Run auto-copy (§2.9); independent direct POST/PUT never probed | n/a | `parent-id` physical name `ST_RUN_ID` (the owning run, URL's run id is used per docs, but field also present) |
| defect | `detected-by`, `creation-time`, `severity`, `name` | **Yes** [probe] | n/a | no hierarchy/`parent-id` field at all — defects are a flat list, not a tree |
| release | `end-date`, `name`, `parent-id`, `start-date` | **Yes** [probe] | n/a | none identified |
| release-cycle | `end-date`, `name`, `parent-id`, `start-date` | **Yes** [probe] | n/a | dates validated inside parent release's window (§2.11) |
| release-folder | `name`, `parent-id` | **No** — never directly write-probed; root `UNVERIFIED` (§2.1) | n/a | — |
| resource | `parent-id`, `name`, `subtype-id` | **No** — never directly write-probed this session | n/a | fully versioned (full `vc-*` set), like requirement/test |

---

## 4. Field-type system summary

Cross-references `alm-api-reference.md` §8 and `probe3-mining-fieldtypes.md` — full tables live
there; this is the generator-facing digest plus two data-model-specific findings not in the API
reference.

- **8 types, no Boolean**: `String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`,
  `Reference`. Yes/No semantics run through **LookupList bound to list id 1** ("YesNo": `Y`/`N`) for
  the handful of genuinely user-editable flags (`rbt-ignore-in-analysis`, `rbt-use-custom-*`); every
  other flag-shaped field (`has-*`, `is-*`, `attachment`) is a read-only/system `String` or `Number`,
  not a togglable boolean `[probe]` `[fixture]`.
- **Multivalue is vanishingly rare**: exactly 2 fields in the entire 15-entity, 432-field probed
  surface support `SupportsMultivalue=true` — `requirement.target-rel` and `requirement.target-rcyc`,
  both `Reference` type. No `LookupList`/`UsersList` field is ever multivalue `[fixture]`.
- **Read-only/System surface**: 191 of 432 fields are `Editable=false AND System=true` — concentrated
  in `test` (38), `requirement`/`run` (36/25). These are non-negotiable for the generator: `id`, all
  `vc-*`, all `has-*`, `last-modified`, and the virtual computed-path fields `[fixture]`.
- **Size semantics**: `-1` = unlimited (every `Memo` field, uniformly); `99999` = a **virtual-field
  sentinel**, not a real size limit — marks computed/denormalized `String` fields (`father-name`,
  `tree-path`, `folder-name`, and `run`'s denormalized `test-name`/`cycle-name`/`testcycl-name`/
  `test-description`) `[fixture]` `[probe]`.
- **New: `parent-id` is not reliably `Number`-typed.** Across the 15 fixtures, `parent-id`/tree-link
  fields are typed `Number` on 10 entities but **`LookupList`** on `test-set` (`CY_FOLDER_ID`) and
  `test-set-folder` (`CF_FATHER_ID`), and defects have **no `parent-id` field at all** (flat list, no
  tree) `[fixture]` — the generator's tree-write logic cannot assume a uniform `Number` type for
  "the field that places this record in its hierarchy."
- **List-binding self-contradiction in the source data, resolved.** `probe3-mining-fieldtypes.md` §2's
  per-entity list-binding table shows `resource.res-type` bound to list **285** and
  `resource.vc-status` bound to list **82** (found via the field's own `List-Id` attribute), while the
  *same report's* §3 "4-list delta" independently concludes lists 82/255/285/320 are "defined but not
  bound to any field" (derived from a `used-lists`(39) vs. `lists`(43) set-difference). **Both are
  true simultaneously and there is no contradiction once reconciled**: the field-level `List-Id` link
  exists in field metadata regardless, but `used-lists` apparently excludes lists reachable only
  through **read-only/system** fields — both `res-type` and `vc-status` on `resource` are
  `Editable=false`/`System=true` `[fixture]`. `INFERRED` explanation, not directly probed — flagged in
  §7's unknowns table.

---

## 5. Versioning & locking

Two independent mechanisms, easy to conflate:

**Optimistic-concurrency lock** (`.../{id}/lock`, GET/POST/DELETE, `version` query param) — present on
**41 of 62 collections** generically `[resource-list]` (alm-api-reference.md §5) and keyed off the
`ver-stamp` field, which is present on **13 of the 15 probed entities** (absent only on `run-step` and
`test-config`) `[fixture]`. This is a plain revision counter for conflict detection — it does **not**
imply full version-control history.

**Full version control** (check-in/check-out/`versions` sub-resource) is restricted to
**requirements, tests, resources** (+ favorites/favorite-folders, per the generic-contract table in
`alm-api-reference.md` §5, not independently fixture-verified here) — confirmed by the presence of the
**complete** `vc-checkin-{date,time,user-name,comments}` / `vc-checkout-{date,time,comments}` /
`vc-status` / `vc-version-number` field set **only** on those three of the 15 probed entities
`[fixture]`. `vc-status` is bound to LookupList **170** ("Version Status": `Checked_In`/`Checked_Out`)
on requirement and test; resource uses a **different** list, **82** ("VC Status": `Checked_In`/
`Checked_Out`/`Read_Only`) `[fixture]`.

**Denormalized VC remnants — do not mistake for independent versioning.** Four other entities carry
one or two `vc-*`-named fields that are read-only mirrors of their *parent test's* VC state, not
their own versioning capability:
- `design-step.vc-user-name` (read-only) — who last checked in the owning test.
- `run.vc-status` / `run.vc-locked-by` / `run.vc-version-number` (all read-only) — the test's VC state
  at execution time.
- `test-config.vc-checkout-user-name` (read-only) — same, one level up.

`.../{entity-name}/versioningHistory` (DELETE, purge) is Swagger-only (v2), body
`{"purgeMode":"date"|"version","offSet":"…"}`; the operation's own description states **"currently only
'test' is supported"** for `entity-name` `[swagger]` (probe3-mining-swagger §1.2) — do not assume it
extends to requirement/resource without re-checking the live Swagger doc on a target instance.

---

## 6. Generator-relevant constraints

- **`UsersList` fields need real project users.** The sandbox has exactly **1** project user
  `[probe]` — every `UsersList`-typed field (`owner`, `detected-by`, `vc-checkin-user-name`, …, 77
  fields across 11 entities `[fixture]`) will degenerate to that single value until more are seeded.
  The API key's user holds SA role **Customer Admin**, so seeding is automatable without leaving REST:
  `POST /qcbin/v2/sa/api/site-users` (create) → `POST
  /qcbin/v2/sa/api/domains/{d}/projects/{p}/users` (attach existing site user to the project, body
  `{"name":"…"}` — does **not** create a user) `[probe]` `[swagger]` (alm-api-reference.md §6.9).
- **Rich-text/memo storage** — see `alm-api-reference.md` §7 for the full sanitizer behavior (not
  restated here). Data-model-relevant summary: memo fields store a complete `<html><body>…</body></html>`
  document; round-trip is **not** byte-identical (whitespace pretty-printing, implicit `<tbody>`,
  `<script>` stripped); `has-rich-content` flips `N`→`Y` on write.
- **`<<<param>>>` entity-encoding trick — probe-verified, generator-actionable.** A raw
  `<<<name>>>` token in a design-step's `description`/`expected` is destroyed by the HTML sanitizer
  (parsed as a malformed tag, collapses to `<<>>`). **Pre-encoding the angle brackets**
  (`&lt;&lt;&lt;name&gt;&gt;&gt;`) survives intact and still flips `has-params="Y"` `[probe]`
  (probe5-write-round-2.md §b, reproduced in all 4 round-2 runs). **This is now a general mitigation
  for any text the sanitizer could parse as a tag, not just parameter tokens** — the generator's
  rich-text writer should HTML-entity-encode literal `<`/`>` in any free-text content it did not
  itself construct as valid markup.
- **`step-parameters` REST gap — genuinely unreachable, OTA candidate.** Every create attempt (2 in
  round 1, 3 more in round 2, 5 total across both nested and standalone paths, both `used-by-owner-type`
  values) fails identically: `HTTP 500 "Test parameter does not exist"`. The physical name of
  `step-parameters.parent-id` is **`SP_TEST_PARAM_ID`**, implying the endpoint is a usage/value record
  for a "Test parameter" object that must **already exist**, and there is no REST-exposed entity or
  endpoint anywhere in this project's Core API surface to create that underlying object `[probe]`
  (probe5-write-round-2.md §b). Treat as REST-unreachable; OTA/COM `StepFactory`/parameter objects are
  the fallback candidate per CLAUDE.md's allowed-fallback note.
- **Release-cycle date validation is enforced server-side.** A cycle whose dates fall outside its
  parent release's window is rejected with a specific, well-formed message (`"start date cannot be
  later than release's end date"`), reproduced identically across all 4 round-2 runs; an in-range
  cycle succeeds `[probe]` (probe5-write-round-2.md §e). The generator must generate cycle dates
  within the parent release's `start-date`/`end-date` window, not independently.
- **Deterministic field order + 500-may-commit** — hard client requirements, fully detailed in
  `alm-api-reference.md` §3.2–3.3 (do not restate here): the `Fields` array's JSON member order
  affects server behavior on write (fixed order required), and an HTTP 500 to a POST is not proof the
  row wasn't committed (verify-by-GET before retrying).
- **NEW — multipart `ref-subtype=1` embedded-image upload now confirmed working**, correcting
  `alm-api-reference.md` §9's open item. Round 2's multipart attempts both failed with an opaque
  `"begin 0, end -1, length 1"` parse error using PowerShell's built-in `-Form` constructor; an
  unnarrated round-3 fixture, `r3-attach-multipart-refsubtype1.json` `[probe]` (unnarrated `r3-*`
  fixture), shows a **successful** `ref-subtype=1` attachment create (`name="probe-embed.png"`,
  `ref-type="File"`, `parent-type="requirement"`), confirming the earlier failure was a client-library
  multipart-construction defect, not a server-side limitation. **Combined with the already-confirmed
  absolute-URL `<img src>` survival rule** (`alm-api-reference.md` §6.6), the generator now has two
  working, REST-only paths to embedded images: (1) octet-stream+`Slug` upload (`ref-subtype=0`) +
  absolute-URL `<img src>` reference (confirmed both rounds), or (2) proper multipart upload with
  `ref-subtype=1` (confirmed round 3, needs a byte-exact multipart body — do not reuse a naive
  PowerShell `-Form`-style client without testing it first in whatever language Alt-ALM's BFF uses).
- **Fast_Run synthesis is the only confirmed run-creation path** (§2.9) — a hard constraint on the
  generator's Test Lab strategy until/unless a direct `POST runs` shape is found. Auto-generated run
  `name` (`Fast_Run_<M>-<D>_<HH-MM-SS>`) cannot be overridden by the generator in this path.

---

## 7. Unknowns table

Per-entity/topic open questions, each with the concrete next probe. Items already resolved by the
unnarrated `r3-*` fixtures (§2.9, §6) are excluded here and stated as findings above instead.

| Entity/topic | Open question | Probe to settle it |
|---|---|---|
| release-folder | Root id (`0`? something else?) | `release-folders?query={parent-id[0]}` before any hardcoded release-folder creation |
| test-config | Does a direct `POST test-configs` succeed with just `name`+`parent-id`? | Direct create probe against a known test id |
| resource / resource-folder | Direct create never probed (fixture-known required: `parent-id`,`name`,`subtype-id`) | Direct create probe; also enumerate `resource/types` for `subtype-id` values |
| requirement target-rel/-rcyc | Is the write path the requirement's own multivalue field, or `requirement-target-releases`/`-cycles` as separate collections? | PUT the requirement's `target-rel` field directly with a release id; separately POST to `requirement-target-releases`; compare |
| test-config-coverages / test-criterion-coverages | PUT/DELETE and criterion-level create never probed (only the coverage auto-create GET side effect is confirmed) | Direct CRUD probe |
| defect-links | Which `second-endpoint-type` values beyond `defect`/`requirement` are valid (test? run? test-instance?) | POST with each candidate type, observe 201 vs. 400/500 |
| run (direct POST) | Can a from-scratch `POST runs` ever succeed, or is Fast_Run synthesis the only path? What test-set field(s) does the stock UI populate that our minimal create doesn't? | Create a test-set via the stock web client (or one with a manually-run test already), diff its full field set against our minimal REST create, retry direct `POST runs` against the richer test-set |
| run-step | Independent (non-synthesis) POST/PUT/DELETE never probed | Direct CRUD probe against an existing run |
| run ↔ run-step status | ~~Does setting a run-step to `Failed` change the parent run's `status`?~~ **Settled (Probe 6): no eager aggregation** — remaining nuance: was only tested on a run force-set `Passed`; a fresh not-completed run might behave differently | PUT run-steps to `Failed` on a freshly synthesized, never-status-set run |
| step-parameters | Is there truly no REST path to define the underlying "Test parameter" object, or does it need to be UI/OTA-created first and only then referenced by REST? | Define a parameter via the stock web client or OTA `StepFactory`, then retry `step-parameters` POST referencing it by `key` |
| milestone | Full field set beyond the 11 seen on create (`kpis-count`, `milestone-scopeitem-count` suggest KPI/scope-item sub-structures) | `GET customization/entities/milestone/fields` full dump (not yet captured as its own fixture — only inferred from the create-response) |
| list-binding "used but not in used-lists" | Confirm the read-only/system-field-exclusion theory (§4) for why `resource.res-type`/`vc-status` bind to lists 285/82 yet those lists are absent from `used-lists` | Bind list 285 or 82 to an *editable* field via UI/admin, re-pull `used-lists`, check if the count changes |
| release-cycle CY_DESCRIPTION | Execution-flow/dependency encoding inside test-set's opaque description blob (wave1-05 #4) — unrelated to release-cycle proper, flagged here for tracking | Configure a dependency in the UI, GET the field, diff |
| mail | Correct request body shape for `POST .../{entity}/{id}/mail` — 4 attempts (3 JSON shapes + 1 XML) all failed, 3 with an identical opaque NPE | Capture the stock web client's actual POST body via browser network tools |
| test-executions | Full CRUD present; POST confirmed to DISPATCH a real execution (needs a configured Lab host) — out of scope without agent infrastructure, not a data-creation path | N/A unless a Lab host is explicitly provisioned |
| bv-hosts / host-groups | Zero CRUD probes despite resource-list presence | Direct CRUD probe — establishes whether lab/host management is REST-reachable at all |
| Cross-instance consistency | Every finding in this document is single-sandbox, single-version evidence | Re-run write probes against a second ALM 26.1 project/instance if one becomes available |

---

## Conflicts adjudicated in this document

1. **Requirement root `parent-id`**: round-1's working value `1` was a **contaminated orphan** (a
   silently-committed 500, per `alm-api-reference.md` §3.3), not the true root. Round-2 clean-state
   evidence (higher priority — `live-probe-log.md`/`probe5`) confirms the true root is **`0`**.
   `[probe]` round 2 wins over `[probe]` round 1.
2. **Test-set "parent-id=2 discrepancy"** flagged as unadjudicated in `alm-api-reference.md` §6.1:
   resolved as **spurious** by direct inspection of the underlying fixture (`r2-test-set-create.json`
   actually shows `parent-id="5"`, an intermediate test-set-folder created earlier in the same probe
   run, not the root). `[fixture]` (direct read) overrides the imprecise prose summary.
3. **`res-type`/`vc-status` list-binding "unused" vs. "bound"** apparent contradiction inside
   `probe3-mining-fieldtypes.md` itself: both are true; resolved (with an `INFERRED` explanation, still
   flagged for a settling probe in §7) by the read-only/system-field distinction between field-level
   `List-Id` linkage and the `used-lists` collection's apparent scope.
4. **Fast_Run synthesis and run-step auto-copy**: previously `UNVERIFIED` in `alm-api-reference.md`
   §9 and open questions #13/#14 in `wave1-05-test-lab.md`. Resolved **CONFIRMED TRUE** by the
   unnarrated `r3-fastrun-full-entity.json`/`r3-run-steps.json` fixtures — `[probe]` (fixture, highest
   priority) overrides the prior `UNVERIFIED` status, with the caveat that the *direct* `POST runs`
   path remains unresolved.
5. **Multipart `ref-subtype=1` image upload**: `alm-api-reference.md` §9 lists this as untested beyond
   a failed round-2 attempt. Resolved **working** by the unnarrated `r3-attach-multipart-refsubtype1.json`
   fixture — the round-2 failure is now attributable to the PowerShell client's multipart construction,
   not a server limitation.
