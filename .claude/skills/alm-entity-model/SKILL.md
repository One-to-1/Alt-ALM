---
name: alm-entity-model
description: ALM/QC entity catalog, field-type system, relationship map, and creation-order DAG — load when working with ALM entities, their fields, or their relationships.
---

Ground truth: ALM 26.1 sandbox, `docs/research/alm-data-model.md` (primary),
`docs/research/alm-api-reference.md` §5/§6/§8. This skill does not restate transport mechanics
(auth, XSRF, request envelopes, query grammar) — see the `alm-api` skill for those.

## 1. Runtime discovery — never hardcode

Schemas, roots, list-ids, and subtypes are **per project and customizable**. Always fetch at
runtime:

- Field descriptors: `GET customization/entities/{entity}/fields`
- Subtype/type enumerations: `GET customization/entities/{entity}/types`
- List values: `GET customization/used-lists/{list-id}/items` (NOT the unrelated `list-items`
  project-entity collection — see §6 trap table)
- Tree roots: `GET {collection}?query={parent-id[0]}`

**Sanity-check-only defaults** (user-supplied, probe-confirmed on our sandbox, but discover fresh
per target project): requirements root = id `0` "Requirements" `[probe]`; test-folders root = id
`2` "Subject" `[probe]`; test-set-folders root = id `0` "Root" `[probe]`. **Release-folder root is
UNVERIFIED** — every probed release create used `parent-id=1` without a prior `{parent-id[0]}`
discovery query; do not assume `1` or any other value is the root [data-model §2.1, §7].

## 2. Entity catalog — 62 collections

`GET+POST /domains/{d}/projects/{p}/{collection}`, independently derived from `resource-list`
[data-model §1]. ★ = one of the 15 entities with a probed field descriptor
(`customization-fields-*.json`).

| Domain | Collections |
|---|---|
| Requirements (5) | ★requirements, req-traces, requirement-coverages, requirement-target-cycles, requirement-target-releases |
| Test Plan (11) | ★test-folders, ★tests, ★design-steps, ★test-configs, test-config-coverages, test-criterion-coverages, test-criterions, test-parameters, step-parameters, ★resources, resource-folders |
| Test Lab (8) | ★test-set-folders, ★test-sets, ★test-instances, ★runs, ★run-steps, test-executions, results, lab-runs-protocol-granularities |
| Defects (2) | ★defects, defect-links |
| Releases & Milestones (4) | ★release-folders, ★releases, ★release-cycles, milestones |
| Generic/cross-cutting (4) | attachments, locks, environments, bpm-folders |
| Personal workspace (6) | favorites, favorite-folders, dashboard-folders, dashboard-pages, workspaces, workspace-folders |
| Customization list-values (1) | list-items |
| CI/SCM (14) | build-artifacts, build-code-refs, build-contexts, build-instances, build-servers, build-types, changesets, changeset-files, changeset-link-associations, branch-policy-links, policy-items, scm-branchs, scm-branch-releases, scm-repositorys |
| Business-views/analysis (5) | analysis-item-file(s), analysis-item-folders, analysis-items, analysis-segments |
| Lab hosts (2) | bv-hosts, host-groups |

Everything non-★ is known to exist (collection-level + generic sub-resource surface) but its field
shape is UNVERIFIED beyond a live metadata call [data-model §1].

## 3. Generic entity contract

Shared sub-resource surface across the 62 collections, counted over 1,111 resource-list operations
[api-ref §5]:

| Sub-resource | Coverage | Notes |
|---|---|---|
| `{id}/attachments(+/{name})` | most collections | 17 have a `[DEPRECATED]` plain-POST form (bpm-folders, design-steps, environments, defects, milestones, releases, release-cycles, release-folders, requirements, runs, run-steps, test-configs, tests, test-folders, test-instances, test-sets, test-set-folders) — avoid these in new code |
| `{id}/lock` (GET/POST/DELETE, `version` param) | 41 collections | optimistic-concurrency revision counter, NOT versioning |
| `{id}/audits` (`readChunks`) | 24 collections + project-level | partial coverage — do not assume audit history exists for an arbitrary entity |
| `{id}/mail` (POST) | 19 collections | body shape UNVERIFIED — every attempted shape (3 JSON + 1 XML) failed |
| `groups/{groupsFields}` (GET) | 35 collections | group-by aggregation, not a permissions collection |
| `copy` (POST) | gated by `SupportsCopying` | subtree duplication, preserves attachments + co-copied links |
| bulk `DELETE ?ids-to-delete=` | 58 collections | non-transactional, size cap 2000 |
| `versions` (+check-in/out) | **requirements, tests, resources only** (+favorites/favorite-folders per generic table, not fixture-verified) | full version control, distinct from the lock mechanism |

`.../{entity}/versioningHistory` DELETE (purge) is Swagger-only and its own doc says "currently
only 'test' is supported" — do not assume it extends to requirement/resource without re-checking
the live Swagger [data-model §5].

## 4. Field-type system

**Exactly 8 types, no Boolean**: `String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`,
`UsersList`, `Reference`. Yes/No semantics run through LookupList list-id `1` ("YesNo"); every
other flag-shaped field (`has-*`, `is-*`, `attachment`) is read-only String/Number, not a real
boolean [data-model §4].

- **Read-only/System**: 191 of 432 probed fields are `Editable=false AND System=true` — never
  write `id`, any `vc-*`, any `has-*`, `last-modified`, or virtual computed fields. Concentrated in
  `test` (38), `requirement`/`run` (36/25).
- **Size semantics**: `-1` = unlimited (every Memo field); `99999` = virtual-field sentinel (NOT a
  real size limit) marking computed/denormalized String fields (`father-name`, `tree-path`,
  `folder-name`, `run`'s `test-name`/`cycle-name`/`testcycl-name`/`test-description`).
- **Multivalue is vanishingly rare**: exactly 2 fields in the whole 15-entity/432-field surface
  support multivalue — `requirement.target-rel` and `requirement.target-rcyc`, both Reference.
- **`parent-id` is not reliably Number-typed**: Number on 10 entities, but **LookupList** on
  `test-set` (`CY_FOLDER_ID`) and `test-set-folder` (`CF_FATHER_ID`); defects have **no `parent-id`
  field at all** (flat list). Do not assume a uniform type for "the field that places this record
  in its hierarchy."
- **UDF naming**: `user-NN` fields (physical `XX_USER_NN`), ≤99 per entity, memo UDFs capped at 5
  (15 with `EXTENDED_MEMO_FIELDS=Y`); discovered from the same metadata call, no special-case code
  path needed [api-ref §6.8].

## 5. Relationship map + creation-order DAG (most important section)

Branches join only at `requirement-coverage` (needs a requirement AND a test) and
`requirement.target-rel/-rcyc` (write path UNVERIFIED). Releases/milestones, requirements, and the
test tree are otherwise **independent branches**, not one linear chain [gen-spec §4].

```
release-folder (root: UNVERIFIED — discover via {parent-id[0]}, never hardcode)
  └─ release            name, start-date, end-date, parent-id            VERIFIED
       ├─ release-cycle name, start-date, end-date, parent-id            VERIFIED
       │                (dates MUST fall inside parent release's window — server-enforced 500)
       └─ milestone     name, parent-id (=MS_RELEASE_ID = the release id, NOT a folder)  VERIFIED

requirements/0 "Requirements" (root VERIFIED)
  └─ requirement        name, type-id, parent-id                         VERIFIED
       ├─ req-trace         from-req-id, to-req-id                       VERIFIED
       ├─ requirement-coverage  requirement-id, test-id, entity-type="test"  VERIFIED
       │    └─ test-config-coverage  AUTO-CREATED side effect — do not POST directly  VERIFIED (side-effect only)
       └─ target-rel / target-rcyc   write path UNVERIFIED (§2.10 data-model)

test-folders/2 "Subject" (root VERIFIED, project-specific — discover at runtime)
  └─ test-folder        name, parent-id                                  VERIFIED
       └─ test          name, parent-id, subtype-id (e.g. "MANUAL")      VERIFIED
            ├─ design-step  name, parent-id, description, expected       VERIFIED — contradicts stale doc
            │    └─ step-parameter   BLOCKED — no REST create path (see §8)
            └─ test-config   direct create UNVERIFIED; existence + auto-bind on runs confirmed

test-set-folders/0 "Root" (root VERIFIED)
  └─ test-set-folder    name, parent-id (type LookupList, not Number)    VERIFIED
       └─ test-set      name, subtype-id="hp.qc.test-set.default", parent-id  VERIFIED
            └─ test-instance  test-id, cycle-id(=test-set id, TRAP), subtype-id  VERIFIED
                 └─ run   direct POST FAILS (8/8 attempts) — only confirmed path is
                          PUT test-instance.status → Fast_Run synthesis   VERIFIED (synthesis only)
                      └─ run-step  AUTO-COPIED from design-steps on synthesis (count matches
                           exactly); independent POST/PUT/DELETE never probed standalone  VERIFIED (synthesis only)

defect  (name, detected-by, creation-time, severity — no hierarchy field, flat list)  VERIFIED
  └─ defect-link  (first/second-endpoint-id + second-endpoint-type ∈ {defect, requirement})  VERIFIED

attachment (any entity id; octet-stream+Slug always works; multipart ref-subtype=1 CONFIRMED)  VERIFIED
```

## 6. Naming traps (critical — causes real bugs)

| Trap | Detail |
|---|---|
| `cycle-id` vs `testcycl-id` | `cycle-id` = **Test Set** id (on both `test-instance` and `run`); `testcycl-id` = **Test Instance** id. Single most error-prone pair in the model. |
| `run.test-instance` | An **ordinal** (1-based position within the set), NOT a foreign key. Don't confuse with `testcycl-id`. |
| `run.cycle-id` type | Typed **String**, unlike `run.test-id`/`run.test-config-id` (Number) and `run.testcycl-id` (Reference). Send as a JSON string regardless (the whole envelope is string-typed anyway). |
| design-step order field | `step-order` (physical `DS_STEP_ORDER`), NOT `order-id` — differs from test-folder/requirement's ordering field. |
| `test.parent-id` | Physical name `TS_SUBJECT`, not `TS_PARENT_ID`. |
| `test-folder.name` | Physical name is `AL_DESCRIPTION`, not `AL_NAME`; `parent-id` physical is `AL_FATHER_ID`. |
| `test-set`/`test-set-folder` `parent-id` | Field **type is LookupList**, not Number — `CY_FOLDER_ID` / `CF_FATHER_ID` respectively. |
| defects have no `parent-id` | Flat list, no tree, no hierarchy field at all. |
| `list-items` ≠ used-list items | `list-items` is an ordinary project-entity collection (own lock/mail/groups/bulk-delete). It is **unrelated** to `customization/used-lists/{list-id}/items`, the list-of-values editor. Same word, different resources. |
| `milestones.parent-id` | Physical `MS_RELEASE_ID` — parents directly under a **release**, not a folder or milestone tree. |
| `test-config.parent-id` | Physical `TSC_TEST_ID`, required, **non-editable after create** (fixed at create time). |

## 7. Per-entity quick reference (15 probed entities)

| Entity | Required fields | Verified create? | subtype/type-id used | Trap |
|---|---|---|---|---|
| requirement | `name`, `type-id` (parent-id needed in practice, not fixture-flagged) | Yes | `type-id=3` Functional | type-id table below |
| test | `name`, `parent-id`, `subtype-id` | Yes | `subtype-id="MANUAL"` | `parent-id`=`TS_SUBJECT` |
| design-step | *(fixture shows none Required=true; name+parent-id needed in practice)* | Yes | n/a | `step-order` not `order-id` |
| test-config | `name`, `parent-id` | No — direct create UNVERIFIED | n/a | `parent-id`=`TSC_TEST_ID`, non-editable |
| test-folder | `name`, `parent-id` | Yes | n/a | `name`=`AL_DESCRIPTION`, `parent-id`=`AL_FATHER_ID` |
| test-set-folder | `name` (`parent-id` needed for non-root, not fixture-required) | Yes | n/a | `parent-id` type LookupList (`CF_FATHER_ID`) |
| test-set | `name`, `subtype-id` | Yes | `subtype-id="hp.qc.test-set.default"` | `parent-id` type LookupList (`CY_FOLDER_ID`) |
| test-instance | `test-id`, `cycle-id` | Yes | `subtype-id="hp.qc.test-instance.MANUAL"` | `cycle-id`=test-set id; `test-instance`=ordinal; own id physical `TC_TESTCYCL_ID` |
| run | `cycle-id`, `name`, `status`, `test-id`, `testcycl-id`, `owner` | Direct: **No** (FAILS). Synthesis: **Yes** | `subtype-id="hp.qc.run.MANUAL"` | `cycle-id` typed String; `testcycl-id` typed Reference; name server-assigned on synthesis, cannot override |
| run-step | `parent-id` | Confirmed only via Fast_Run auto-copy; standalone never probed | n/a | `parent-id`=`ST_RUN_ID` |
| defect | `detected-by`, `creation-time`, `severity`, `name` | Yes | n/a | no hierarchy field — flat list |
| release | `end-date`, `name`, `parent-id`, `start-date` | Yes | n/a | — |
| release-cycle | `end-date`, `name`, `parent-id`, `start-date` | Yes | n/a | dates validated inside parent release's window |
| release-folder | `name`, `parent-id` | No — never directly probed; root UNVERIFIED | n/a | — |
| resource | `parent-id`, `name`, `subtype-id` | No — never directly probed | n/a | fully versioned (full `vc-*` set), like requirement/test |

**Requirement type-id table** [data-model §2.2]:

| id | name | direct-coverage | risk-analysis |
|---|---|---|---|
| 0 | Undefined | Y | 0 |
| 1 | Folder | N | 2 |
| 2 | Group | N | 2 |
| 3 | Functional | Y | 1 |
| 4 | Business | N | 1 |
| 5 | Testing | Y | 1 |
| 6 | Performance | Y | 1 |
| 66 | Business Model | Y | 1 |

## 8. Versioning & locking (two distinct mechanisms)

- **Optimistic-concurrency lock** (`{id}/lock`, keyed off `ver-stamp`): present generically on 41/62
  collections; `ver-stamp` present on 13/15 probed entities (absent only on `run-step` and
  `test-config`). A plain revision counter, not version history.
- **Full version control** (`vc-checkin-*`/`vc-checkout-*`/`vc-status`/`vc-version-number`, full
  `versions` sub-resource): restricted to **requirements, tests, resources**. `vc-status` bound to
  LookupList 170 ("Version Status": `Checked_In`/`Checked_Out`) on requirement/test; resource uses a
  **different** list, 82 ("VC Status": adds `Read_Only`).
- Four other entities carry read-only VC-mirror fields of their parent test's state, not their own
  versioning: `design-step.vc-user-name`, `run.vc-status`/`vc-locked-by`/`vc-version-number`,
  `test-config.vc-checkout-user-name`.

## 9. Known gaps

| Gap | Status |
|---|---|
| `step-parameters` | BLOCKED — every create shape (5 attempts, both `used-by-owner-type` values) returns `HTTP 500 "Test parameter does not exist"`. It's a "record a value against an already-registered parameter" endpoint, not a "define a new parameter" one. No REST-exposed entity creates the underlying parameter object. OTA `TestParameterFactory` is the fallback candidate. |
| BPT (Business Process Testing) | License-gated: `components` → 403, `business-components` → 404. |
| `test-config` direct create | UNVERIFIED — never write-probed; existence + auto-bind on synthesized runs confirmed only indirectly. |
| `target-rel`/`target-rcyc` write path | UNVERIFIED — unclear whether the requirement's own multivalue field or the separate `requirement-target-releases`/`-cycles` collections are the real join surface. |
| Direct `POST runs` | Definitively FAILS (8 attempts, 2 distinct 500 modes) — not open, a closed door. Fast_Run synthesis (`PUT test-instances/{id}.status`) is the only path. |
| `defect-links` endpoint types | Only `defect`/`requirement` confirmed for `second-endpoint-type`; test/run/test-instance UNVERIFIED. |
| release-folder root | UNVERIFIED — discover via `{parent-id[0]}` before any hardcoded create. |
| `mail` | Body shape genuinely undocumented — 3 JSON + 1 XML shapes all failed. |

## See also

- `alm-api` skill — auth, XSRF, envelopes, query grammar, error codes, bulk/paging mechanics.
- `alm-data-gen` skill — how to actually generate and write synthetic records using this model.
- `docs/research/alm-data-model.md` — full citations, unknowns table, conflict adjudications.
- `docs/research/alm-api-reference.md` §6 — full JSON create-body recipes per entity.
