# Alt-ALM — Feature → API Feasibility Matrix

**Purpose**: For every stock ALM/QC UI feature catalogued in
[`alm-ui-feature-inventory.md`](alm-ui-feature-inventory.md), determine whether Alt-ALM can implement
equivalent functionality using the documented ALM/QC REST API (or, where the project charter allows,
the OTA/COM fallback), and cite the empirical or documentary evidence for that verdict.

**Date**: 2026-08-12
**Server**: ALM 26.1 sandbox (internal `SiteVersion 20.0 (Build 20.00.0.143)`), API-key auth, Customer
Admin SA role.
**Method**: UI inventory (row list) × [`alm-api-reference.md`](alm-api-reference.md) ×
[`alm-data-model.md`](alm-data-model.md) × [`live-probe-log.md`](live-probe-log.md). **Wherever
sources conflict, `live-probe-log.md` wins** — it is the only document built entirely from direct
observation against our target server.

**Numbering note**: the source UI inventory does not number its feature rows individually (only a
module-level count-summary table). This document assigns its own sequential `#` across every markdown
table row in the inventory, plus every `Views:` bullet (treated as a feature). That yields **218**
numbered rows here versus the inventory's self-reported "231" grand total — the ~13-row gap is
entirely descriptive tab-name lists (e.g. "BPT tabs: Details, Manual Implementation, …", "Test Plan
tabs: …") that the inventory's own count-summary appears to have expanded into per-tab counts but which
were never present as distinct table rows to carry forward here. **No table row or Views bullet from
the source document was skipped.**

## Verdict legend

| Verdict | Meaning |
|---|---|
| `FULL` | Achievable with documented REST, probe-verified or unambiguous docs |
| `FULL*` | Achievable via REST, but by an indirect/non-obvious route — footnote required |
| `PARTIAL` | Core works, identified gaps (stated in Notes) |
| `OTA` | REST-impossible, OTA/COM fallback candidate (OTA support itself often unconfirmed — noted where so) |
| `NO` | Not achievable via any allowed API (documented REST or OTA) — honest gap |
| `N/A` | Desktop-client concept that doesn't map to Alt-ALM, or a documentation/architectural note rather than a buildable feature |
| `UNVERIFIED` | Plausible per docs but not probed; the confirming experiment is named |

Evidence citation shorthand: `[probeN]` = `live-probe-log.md` Probe *N*; `[api-ref §x]` =
`alm-api-reference.md` section *x*; `[data-model §x]` = `alm-data-model.md` section *x*.

---

## 1. Requirements Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 1 | New Requirement / New Folder | FULL | `POST requirements` | [probe4] 201; [api-ref §6.1] | Folder = `type-id=1`, same create call |
| 2 | Delete Requirement/Folder | FULL | `DELETE requirements/{id}` | [probe4][probe5][probe6] cleanup, all DELETEs 200 | Bulk `?ids-to-delete=` also available |
| 3 | Rename Requirement | FULL | `PUT requirements/{id}` | [probe4] rich-text PUT 200 on requirement demonstrates the PUT mechanism | Same call, `name` field |
| 4 | Cut/Copy/Paste Requirement | PARTIAL | `POST requirements/copy`; `PUT parent-id` | [api-ref §5] generic `copy` sub-resource exists, gated `SupportsCopying`, never write-probed | Cut/paste via reparent+recreate is FULL by ordinary CRUD |
| 5 | Move (drag / Move Up/Down) | PARTIAL | `PUT parent-id` | Reparent uses same verified PUT mechanism as #3 | Sibling reorder (Move Up/Down) field/mechanism unconfirmed |
| 6 | Convert to Tests wizard | FULL* [^composite] | `POST tests` + `POST requirement-coverages` | [api-ref §6.1/§6.2] both sub-steps 201-verified | Composite client-side operation, no single endpoint |
| 7 | Assign to Release/Cycle | UNVERIFIED | `PUT target-rel/target-rcyc` OR `POST requirement-target-releases` | [data-model §2.10/§7] two candidate write paths, neither probed | Exp: try both, compare |
| 8 | Find / Go to Requirement by ID | FULL | `GET requirements/{id}` | Used as the verification step in every probe round | |
| 9 | Expand/Collapse All | FULL | none — client-side | N/A | Pure Alt-ALM tree-state, no server call needed |
| 10 | Renumber | UNVERIFIED | unknown | No field/mechanism identified anywhere | Exp: attempt PUT on an order-like field, observe |
| 11 | Change Requirement Type | UNVERIFIED | `PUT type-id` | `type-id` is `Reference`, not flagged read-only, but post-create editability untested | Exp: PUT type-id on existing requirement, check 200 + field-set change |
| 12 | Add Requirement Traceability (Tree/By-ID From/To) | FULL | `POST req-traces` | [api-ref §6.3][data-model §2.4] 201 verified | `from-req-id`/`to-req-id` |
| 13 | Impact Analysis (Traceability tab) | FULL* [^clientside] | `GET req-traces` | Client-side graph walk over verified reads | No server endpoint computes the cascade |
| 14 | Configure Traceability Matrix | FULL* [^clientside] | `GET req-traces` + `GET requirement-coverages` | Underlying link data is fully REST-readable | 4-stage wizard is stock-UI-only; Alt-ALM builds its own equivalent view |
| 15 | Add to Coverage (Without/Include Children) | FULL* [^composite] | `POST requirement-coverages` (looped) | [api-ref §6.2] 201 verified for one link | "Include children" = client-side recursive loop |
| 16 | Coverage by Test Configuration | PARTIAL | `GET test-config-coverages` | [data-model §2.3] auto-create side effect confirmed; full CRUD unverified | View works; direct create/manage unconfirmed |
| 17 | Risk Assessment questions | PARTIAL | `PUT rbt-*` fields | 27 `rbt-*` fields exist, LookupList-bound [api-ref §8]; generic requirement PUT mechanism verified, this specific field-write not individually probed | |
| 18 | Analyze / Analyze and Apply to Children | NO | none found | No computation endpoint in resource-list/Swagger; scoring algorithm undocumented | Would require reverse-engineering OpenText's formula |
| 19 | Override Testing Level/Time | PARTIAL | `PUT rbt-*` override fields | Same caveat as #17 | |
| 20 | Risk export to Word | NO | none | Desktop-local document generation | Alt-ALM can build its own export instead |
| 21 | Business Models Linkage | UNVERIFIED | `bpm-folders` + linkage fields | Collection exists [data-model §1]; linkage fields never probed | Exp: fetch requirement field metadata for BPM-linkage fields, then write-probe |
| 22 | Version comparison | FULL* [^clientside] | `GET requirements/{id}/versions` | Sub-resource exists [api-ref §5] | Comparison is a client-side diff of two fetched versions, not a server endpoint |
| 23 | Baseline capture/compare | OTA | none | Confirmed absent from REST — zero resource-list hits [probe3] | OTA fallback candidate |
| 24 | Import from Word/Excel | PARTIAL | `POST requirements` (bulk, after Alt-ALM parses the file itself) | File parsing is Alt-ALM's own client-side logic, outside ALM's API surface | Record creation via bulk POST is FULL once parsed |
| 25 | Attachments | FULL | `POST/GET/PUT/DELETE .../requirements/{id}/attachments` | [data-model §6] both octet-stream+Slug and multipart ref-subtype=1 confirmed working | |
| 26 | Rich text Description/Comments | PARTIAL | `PUT description`/`req-rich-content` | [api-ref §7][probe4] VERIFIED storage+sanitizer | Not byte-identical round-trip; compare canonicalized HTML |
| 27 | *(View)* Requirements Tree | FULL* [^clientside] | `GET requirements` + walk `parent-id` | "Subject"-style breadcrumb path isn't returned by REST; rebuild client-side | |
| 28 | *(View)* Requirements Grid | FULL | `GET requirements?query=…` | Standard query grammar [api-ref §4] | |
| 29 | *(View)* Coverage Analysis | PARTIAL | `GET requirement-coverages`/`test-config-coverages` | Underlying data REST-readable; stock page itself 404'd in docs research | Client-side reconstruction |
| 30 | *(View)* Traceability Matrix | FULL* [^clientside] | Same as #14 | | |
| 31 | *(View)* Web Client Requirements tab | FULL | Same as #27/#28 | Native web-client parity confirms REST support exists | |

[^composite]: "Composite" = Alt-ALM issues multiple ordinary, individually-verified REST calls in sequence/loop; no single ALM server endpoint performs the whole operation.
[^clientside]: "Client-side" = Alt-ALM computes/renders the view itself from REST-readable data; the stock desktop feature's computation/wizard has no server equivalent to call.

---

## 2. Test Plan + BPT + Test Resources Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 32 | New Folder / New Subject | FULL | `POST test-folders` | [data-model §3] 201 verified | Root = id 2 "Subject", project-specific, discover at runtime |
| 33 | New Test | FULL | `POST tests` | [api-ref §6.4] 201 verified | |
| 34 | Copy/Cut/Paste (same project) | PARTIAL | `POST tests/copy` | Generic `copy` endpoint exists [api-ref §5], never write-probed | Cut/paste via ordinary CRUD is FULL |
| 35 | Copy/Paste cross-project | FULL* [^crossproj] | `GET`/`POST` against two project sessions | Every underlying entity type individually CRUD-verified | Bypasses the desktop UI's same-session/same-version constraint entirely |
| 36 | Delete / Rename (test/folder) | FULL | `DELETE`/`PUT` | Confirmed via create+cleanup delete cycles every probe round | |
| 37 | Find / Go to Test | FULL | `GET tests/{id}` | | |
| 38 | Sort / Filter | FULL | Query grammar (`order-by`, `query{}`) | [api-ref §4] | |
| 39 | New Design Step | FULL | `POST design-steps` | [api-ref §6.4][probe4] 201, **contradicts stale doc's "not applicable"** | |
| 40 | Call to Test | UNVERIFIED | `PUT design-steps.link-test` | `link-test` field IS present on the design-step create response [api-ref §6.4], never independently write-probed | Exp: PUT link-test to a test id, verify readback + stock-UI rendering |
| 41 | Generate Script | N/A | none | Desktop-local automation-framework skeleton generation | Not applicable to Alt-ALM's manual/API-driven test model |
| 42 | Convert manual test → component | OTA | none | Depends on Business Components, REST-blocked [probe6] | |
| 43 | New Test Configuration | UNVERIFIED | `POST test-configs` | Existence + relationship confirmed via side effect [data-model §2.6]; **direct create never write-probed** | Exp: POST test-configs with name+parent-id against a known test |
| 44 | Map Parameters | UNVERIFIED | `test-configs.data-obj` (XML) | Field exists, structure never probed | Exp: build a config via stock UI, GET it, inspect `data-obj` |
| 45 | New Parameter / Insert Parameter (`<<<name>>>`) | PARTIAL | `POST design-steps` (entity-encoded token) | [data-model §6][probe5/6] token survives **only if HTML-entity-pre-encoded** (`&lt;&lt;&lt;name&gt;&gt;&gt;`); flips `has-params=Y` | `step-parameters` (the value-recording side) has **NO REST creation path** — see Generator appendix |
| 46 | Link to Model / Add to Linkage (BPM) | UNVERIFIED | `bpm-folders` + linkage fields | Same as Requirements #21 | |
| 47 | Baseline / Audit Log / Check-in-out / Compare versions (test) | PARTIAL | `.../tests/{id}/versions` | Full VC confirmed present via `vc-*` field set [data-model §5]; Audit Log confirmed **partial-only** (status changes visible only) | Baseline = NO (absent) |
| 48 | Resource tree (typed folders) | UNVERIFIED | `resources`/`resource-folders` | Collections exist, direct create never write-probed [data-model §3/§7] | Exp: direct create probe |
| 49 | Upload File/Folder (Resources) | UNVERIFIED | attachment-style upload against a resource | Resource file-CONTENT historically staff-disclaimed REST-unsupported (2017); not re-checked on 24.1+ | Exp: attempt upload against a resource entity |
| 50 | Download (Resources) | UNVERIFIED | symmetric to #49 | | |
| 51 | Application Area Viewer | NO | none | No REST surface for structured Application Area content beyond generic resource CRUD | |
| 52 | Dependencies (Used by/Using) | NO | none | No relationship-tracking endpoint found for resources | |
| 53 | History (Resources) | UNVERIFIED | `GET resources/{id}/audits` | Sub-resource exists per generic contract (24-collection list, [api-ref §5]), never individually probed | Same partial-coverage caveat as requirement audits applies if confirmed |
| 54 | Business Components tree | OTA | none | `GET /components` → **403 operation-forbidden** (license-gated); `GET /business-components` → **404** [probe6] | REST-blocked, confirmed not just absent |
| 55 | BPT composition (flows) | OTA | none | Depends on #54 | |
| 56 | Add/Delete manual step (Component) | OTA | none | Depends on #54 | |
| 57 | Keep Editable / Sync to Automation | OTA | none | Depends on #54 | |
| 58 | *(View)* Test Plan Tree | FULL* [^clientside] | `GET test-folders`+`tests`, walk `parent-id` | | |
| 59 | *(View)* Test Plan Grid | FULL | `GET tests?query=…` | | |

[^crossproj]: The desktop client's "same version/patch" constraint is a **UI/session** limitation, not an API one — a BFF holding two authenticated project sessions can read every field/attachment of a source test and recreate it via ordinary verified CRUD calls in a different project, with no version/patch coupling. This is a genuinely surprising capability gain over the stock client.

---

## 3. Test Lab Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 60 | Create test set folder / set | FULL | `POST test-set-folders` / `POST test-sets` | [data-model §2.7] 201 verified both | Root = id 0 "Root" |
| 61 | Assign set to release/cycle | UNVERIFIED | unknown field | No release/cycle reference field confirmed on test-set | Exp: fetch test-set field metadata, locate + write-probe the field |
| 62 | Copy/Cut/Paste set or folder | PARTIAL | `POST test-sets/copy` | Generic `copy` endpoint exists, never write-probed | |
| 63 | Pin test set to baseline | OTA | none | Depends on Baselines, confirmed absent from REST | |
| 64 | Clear pinned baseline | OTA | none | Same dependency | |
| 65 | Reset test set | UNVERIFIED | unknown | Page never reviewed, no evidence either way | Exp: capture stock-UI traffic |
| 66 | Purge Runs | OTA | none | Confirmed absent as a REST endpoint; only per-id `DELETE runs/{id}` exists | Manual per-run delete IS a working REST substitute already |
| 67 | Mail / Export test set | UNVERIFIED | `POST .../{id}/mail` | Endpoint exists (19 types) but **4/4 attempted body shapes failed** [probe5/probe6][data-model §7] | Exp: capture stock web client's mail POST body |
| 68 | Select Tests (add instances) | FULL | `POST test-instances` | [data-model §2.7] 201 verified | |
| 69 | Go to Test by ID (Web Test Lab) | FULL | `GET tests/{id}` | | |
| 70 | Test Instance Details (full dialog) | PARTIAL | `GET test-instances/{id}` + sub-reads | 32 fields confirmed [probe2]; Runs/Attachments/Linked Defects composable; History = audits-partial | |
| 71 | Host Manager | UNVERIFIED | `bv-hosts`/`host-groups` | Full CRUD present per resource-list [probe3], **zero write-probes** [data-model §7] | Exp: direct CRUD probe |
| 72 | Run with Manual Runner | FULL* [^fastrun] | `PUT test-instances/{id}` (status) | [data-model §2.9][probe6] Fast_Run synthesis reliable 3/3 | Direct `POST runs` FAILS definitively |
| 73 | Run with Sprinter | N/A | none | Desktop-only third-party tool integration | Not applicable — Alt-ALM is its own runner |
| 74 | Continue Manual Run | PARTIAL | `PUT run-steps/{id}` (incrementally) | Run-step PUT confirmed [probe6]; "resume"/locking semantics unprobed | |
| 75 | Run with Automatic Runner | OTA | `POST test-executions` | Confirmed to DISPATCH [probe5] but fails with "no agent configured" — requires provisioned Lab hosts | Out of practical REST-only reach without lab infrastructure |
| 76 | Execution Flow conditions (arrows) | UNVERIFIED | opaque test-set description blob | Readable via GET, structure undocumented | Exp: configure a flow in stock UI, GET the field, reverse-engineer format |
| 77 | Automation tab (On-Failure/Notification rules) | UNVERIFIED | unknown | No REST surface identified anywhere (resource-list, Swagger) | Same experiment as #76 |
| 78 | On Test Failure per-test override | UNVERIFIED | unknown | Same reasoning as #77 | |
| 79 | *(View)* Execution Grid | FULL | `GET test-instances?query=…` | | |
| 80 | *(View)* Execution Flow diagram | UNVERIFIED | Same as #76 | | |
| 81 | *(View)* Timeslots sub-area | OTA | none | Confirmed absent from REST [probe3] | OTA `Host`/`HostTimeOut` fallback candidate |
| 82 | *(View)* Last Run Report pane | PARTIAL | `GET runs/{id}` + attachments | Tool-specific binary report formats (Sprinter/UFT) readable as blobs; interpretation out of scope | |
| 83 | *(View)* Live Analysis tab | NO | none | Never server-persisted, ALM Enterprise-only per inventory | |
| 84 | *(View)* Web Client Test Lab equivalent | FULL | Same underlying REST as desktop | | |

[^fastrun]: `POST runs` fails on this server with a reproducible, bimodal error (`"Fail to get a must number attribute 'TESTSET'"` or `"Failed to post step"` depending on field set — 8 attempts across 2 rounds, [data-model §2.9]). The only confirmed path is `PUT test-instances/{id}.status`, which makes the server synthesize a full `run` entity named `Fast_Run_<M>-<D>_<HH-MM-SS>` with auto-copied `run-steps` — verified reliably 3/3 sessions [probe6].

---

## 4. Test Runs Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 85 | View Test Runs (filter/sort + Details/Report/Results/History/Event Log) | PARTIAL | `GET runs?query=…` + `GET runs/{id}` | List/filter FULL; Details/Report FULL; History = audits-partial; Event Log endpoint unidentified | |
| 86 | Link defect to run/step (indirect links) | PARTIAL | `POST defect-links` | [api-ref §6.5] verified for `defect`/`requirement` second-endpoint-types only | `run`/`instance`/`set`/`test` as target types UNVERIFIED |
| 87 | Create a run | FULL* [^fastrun] | `PUT test-instances/{id}` → Fast_Run | [data-model §2.9][probe6] | Direct `POST runs` fails definitively, 8 attempts |
| 88 | Create run step | FULL* [^fastrun] | Auto-copy on Fast_Run synthesis | [probe6] count matches design-step count exactly (2↔2, 1↔1) | Independent standalone `POST run-steps` never probed |
| 89 | Read/update/delete run step | FULL | `GET/PUT/DELETE run-steps/{id}` | [probe6] status-flip to Failed confirmed working | |
| 90 | Continue Manual Run | PARTIAL | See #74 | | Cross-referenced with Test Lab |
| 91 | Run Details page (OS info, Attach to Run, New Defect, Start/End/Cancel) | PARTIAL | `PUT run` fields + attachments + defect create/link | Attach-to-Run FULL (generic contract); New-Defect-auto-link = client orchestration of two verified calls | OS-info field writability on a Fast_Run-synthesized run unconfirmed |
| 92 | Step Details page (Pass/Fail Selected, Attach to Step, New Defect) | FULL* [^composite] | `PUT run-steps` status + attachments + defect create/link | All 3 sub-calls individually verified | Multi-call client orchestration |
| 93 | Web Client Manual Runner (custom statuses, arrow-key nav, in-run defect linking) | PARTIAL | `PUT runs/status` | Fixed status list is LookupList-bound; whether arbitrary custom statuses are accepted is unconfirmed | Exp: PUT a custom-defined status string, verify |
| 94 | *(Views)* Test Runs / Test Set Runs / BVS Runs tabs | FULL | `GET runs?query=…` scoped by test-set/subtype | BVS is ALM-Edition-specific — license-tier UNVERIFIED on this instance | |
| 95 | *(View)* Web Client Test Runs tabs | PARTIAL | Same composition as #85 | | |

---

## 5. Defects Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 96 | New Defect | FULL | `POST defects` | [api-ref §6.5] 201 verified | |
| 97 | Go to Defect | FULL | `GET defects/{id}` | | |
| 98 | Send by E-mail / Send IM | N/A | none | Alt-ALM sends its own mail rather than using ALM's | See #198 |
| 99 | Defect Details (4-tab dialog) | FULL | `GET/PUT defects/{id}` + attachments + defect-links + audits | | |
| 100 | Export (grid: text/Excel/Word/HTML) | FULL* [^composite] | Alt-ALM's own client-side export from already-fetched grid data | No ALM export endpoint needed; Project Reports read path also FULL for pre-built reports | |
| 101 | Copy / Paste (within/across projects) | FULL* [^crossproj] | Ordinary defect CRUD across two sessions | Same reasoning as Test Plan #35 | |
| 102 | Copy URL / Paste URL | FULL | none — Alt-ALM's own routing | Trivial, no ALM API involved | |
| 103 | Delete | FULL | `DELETE defects/{id}` | | |
| 104 | Select All / Invert Selection | FULL | none — client-side | | |
| 105 | Find / Find Next / Replace | FULL | client-side search + `PUT` for replace | | |
| 106 | Update Selected (bulk) | PARTIAL | `PUT .../defects;type=collection` | [api-ref §4.5] mechanism fully documented and structurally consistent with the verified single-write path (deterministic field order applies); **bulk endpoint itself not independently write-probed this session** | Downgraded from the UI inventory's FULL — stricter evidence bar applied here |
| 107 | Text Search | NO | none | Server FTS index, opt-in, confirmed absent from REST | |
| 108 | Find Similar Defects / Similar Text | OTA | none | OTA-only per doc [api-ref §6.5] | |
| 109 | Alerts / Clear Alerts | OTA | none | Confirmed absent from REST [probe3] | |
| 110 | Flag for Follow Up | OTA | none | No dedicated source located | |
| 111 | Pin / Unpin | UNVERIFIED | unknown | No REST surface identified distinct from Favorites | Exp: capture stock-UI traffic on pin action |
| 112 | Set / Clear Default Values | N/A | none | Pure client-side create-form convenience, no server concept | |
| 113 | Grid Filters / Filter-Sort / Group By | PARTIAL | Query grammar FULL for filter/sort | `groups/{field}` alm-web-dialect endpoint exists, body shape UNVERIFIED [api-ref §3.5] | Grouping is client-side aggregation |
| 114 | Indicator Columns | FULL | client-side rendering | | |
| 115 | Information Panel (docked History/Linked Entities preview) | PARTIAL | `GET defect-links` (FULL); History = audits-partial | Inline-edit-in-panel UNVERIFIED | |
| 116 | Select Columns / Refresh All | FULL | client-side | | |
| 117 | Favorites | PARTIAL | `GET/POST favorites` | Confirmed [wave-1, cited in inventory] | Full CRUD/permissions UNVERIFIED |
| 118 | Project Reports / Graphs from module | PARTIAL | `GET reports/{id}?alt={mime}` | FULL for existing shared reports | Create/design = NO (UI-only Report Wizard) |
| 119 | Global Search | NO | none | No dedicated cross-project search endpoint identified | Bundled with Text Search FTS absence |
| 120 | Share Analysis Item | FULL | `GET reports/{id}?authKey=…` | Read path exists once shared once in the stock UI | |
| 121 | Drill-down on graph segment | FULL* [^clientside] | Filtered `GET` against the underlying entity collection | Stock UI computes this client-side from graph data; Alt-ALM implements the equivalent directly against raw entities | |
| 122 | Linked Entities tab | FULL | `POST/GET defect-links` | [probe4] 201 for `defect`/`requirement` second-endpoint-types | |
| 123 | History tab | PARTIAL | `GET defects/{id}/audits` | Exists on 24 entity types [probe3], **coverage confirmed incomplete** — status-field changes only; creates/memo PUTs invisible [probe4 §10] | |

---

## 6. Dashboard / Analysis Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 124 | Analysis View tree (read existing) | PARTIAL | `GET reports/{id}?alt={mime}` | FULL for Project Reports; Entity/Composite/Business-View/PPT graphs and Health Reports read-path unconfirmed | Create/design NO across all types |
| 125 | Dashboard View (pages, compose) | FULL* [^clientside] | Client-side dashboard layer built from raw entity-query data | Alt-ALM builds its own dashboard layer; not dependent on ALM's dashboard-authoring API | |
| 126 | Entity Graphs (read existing) | UNVERIFIED | `graphs/{ID}/layouts/{name}` | Tech-Preview endpoint 404'd this round | Exp: re-check live Swagger; try `reports/{id}?alt=mime` against an Entity-Graph item |
| 127 | Create/configure new Entity Graph | FULL* [^clientside] | Client-side rendering from raw entity queries | | |
| 128 | Composite Graphs | FULL* [^clientside] | Client-side composition of multiple entity queries | | |
| 129 | Business View Graphs | NO | none | Business Views metadata out-of-scope family, no REST surface confirmed | |
| 130 | PPT Graphs (Releases module) | NO | none | Depends on KPIs/scope items, confirmed largely absent from REST | |
| 131 | Project Reports (read existing) | FULL | `GET reports/{ID}?alt={mime}` | Documented, consistently cited read path | Only for items already created+shared in UI |
| 132 | Create/design new Project Report | NO | none | UI-only Report Wizard | |
| 133 | Excel Reports (standard SQL) | NO | none | **Structurally out of scope by hard constraint** (raw SQL) | Not a gap to close |
| 134 | Live Analysis Graphs | NO | none | Never server-persisted, no REST surface | |
| 135 | Web Client Dashboard (25.1+ native) | PARTIAL | Same graph-read caveats as #124 | Create/edit NO via documented REST | |
| 136 | 26.1 Tech-Preview "Web Graphs" | PARTIAL | Export-as-image confirmed existing in Tech Preview per inventory | Broader API surface UNVERIFIED | Exp: probe the Tech-Preview export endpoint directly |
| 137 | Drill-down on graph segment (Dashboard) | FULL* [^clientside] | Same as #121 | | |
| 138 | Add graph to Dashboard page | FULL* [^clientside] | Client-side compose | | |
| 139 | Export graph as image | PARTIAL | Web 26.1 TP confirmed | Desktop export mechanism UNVERIFIED | |

---

## 7. Releases / Libraries / Management Module

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 140 | Releases (tree + Details/Status/Master Plan/Release Scope/Scorecard) | PARTIAL | `POST/GET/PUT/DELETE releases` | Core CRUD FULL [api-ref §6.7] 201 verified | Master Plan/Release Scope/Scorecard = see #144/#143/#145 |
| 141 | Cycles | FULL | `POST release-cycles` | [data-model §6][probe5] 201 verified; dates validated inside parent release's window | |
| 142 | Milestones/KPIs (PPT) | PARTIAL | `POST milestones` | Core CRUD FULL [data-model §2.8] 201 verified, `parent-id=MS_RELEASE_ID` | KPI computation/thresholds = NO, no REST surface identified |
| 143 | Release Scope items | UNVERIFIED | unknown | Linkage mechanism (req/test/test-set/defect → release) not identified; milestone response's `milestone-scopeitem-count` hints at a sub-structure | Exp: full field dump of milestone metadata; probe scope-item write |
| 144 | Master Plan (Gantt) | FULL* [^clientside] | Client-side timeline from release/cycle/milestone dates | All three date sources REST-readable | Scope-item plotting blocked by #143's gap |
| 145 | Scorecard (KPI readiness) | NO | none | Depends on KPI computation, absent from REST | |
| 146 | Entity assignment to Release/Cycle | UNVERIFIED | Same as Requirements #7, extended to test-sets/defects | Not probed for any entity type | |
| 147 | Libraries | OTA | none | Confirmed absent — zero resource-list/Swagger hits, 404s [probe3] | OTA support itself unconfirmed |
| 148 | Baselines | OTA | none | Same | |
| 149 | Create Baseline | OTA | none | Background-job wizard has no REST trigger | |
| 150 | Compare Baselines | OTA | none | Depends on baseline data, itself absent from REST | |
| 151 | Baseline exclusions note | N/A | none | Documentation-only fact | |
| 152 | Pinned test sets (pin/clear) | OTA | none | Depends on Baselines — duplicate of Test Lab #63/#64 | |
| 153 | Import/synchronise libraries | OTA | none | ALM Edition+ only; depends on Libraries, absent from REST | |

---

## 8. Cross-cutting Behaviors

### A. Filters & Views

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 154 | Filter tab | FULL | Core query grammar | [api-ref §4.1] | Cross-field OR impossible server-side (documented grammar limit, not a gap) |
| 155 | Cross Filter tab | PARTIAL | Relation-alias cross-filters | [api-ref §4.2] FULL for unambiguous pairs | Ambiguous relations need disambiguating alias; `>1` alias per type silently wrong |
| 156 | View Order tab | FULL | `order-by={f1,f2,…}` | [api-ref §4.3] multi-field supported | |
| 157 | Group tab (≤3 levels) | PARTIAL | Client-side aggregation | `groups/{field}` alm-web dialect exists, body shape UNVERIFIED | |
| 158 | Copy/Paste Filter Settings | FULL | none — Alt-ALM's own filter-state serialization | | |
| 159 | Filtered trees marker behavior | FULL | client-side tree filtering | | |

### B. Favorites

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 160 | Favorites (filter+sort+view-type capture) | PARTIAL | `GET/POST favorites` | Confirmed [wave-1] | Full CRUD/permissions/Organize drag-reorder UNVERIFIED |

### C. Grids

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 161 | Select Columns | FULL | client-side | | |
| 162 | Drag reorder/resize | FULL | client-side | | |
| 163 | Multi-key sort | FULL | `order-by` supports multi-field server-side | [api-ref §4.3] | |
| 164 | ≤3-level grouping | PARTIAL | client-side aggregation | Same as #157 | |
| 165 | Update Selected bulk-edit | PARTIAL | Bulk `PUT ;type=collection` | Documented mechanism [api-ref §4.5], not independently probed this session | Same downgrade rationale as #106 |
| 166 | Alerts row indicators | NO | none | Depends on Alerts data, absent from REST | Alt-ALM can substitute its own notification model |
| 167 | Web Runner grid ceiling (25.1) | N/A | none | Documents OpenText's *own* web client's limitation | Not an Alt-ALM constraint — Alt-ALM builds its own grid against the same REST data desktop parity uses |

### D. Find/Replace & Search

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 168 | Find | FULL | client-side over fetched filtered set | | |
| 169 | Replace | FULL | client-side + `PUT` to persist | | |
| 170 | Text Search (project-wide FTS) | NO | none | Server FTS index, opt-in, confirmed absent from REST | |
| 171 | Go-to-by-ID | FULL | `GET /{collection}/{id}` | | |
| 172 | Dedicated web go-to-entity page | UNVERIFIED | unknown | Mechanics never fetched/confirmed | Exp: capture stock 25.1 web client's go-to-entity network request |

### E. History, Versioning, Audit

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 173 | History tab | PARTIAL | `GET .../{id}/audits` | Confirmed present on 24 entity types [probe3]; coverage confirmed incomplete [probe4 §10] | Client-source column presence in payload UNVERIFIED |
| 174 | Versions tab (VC projects) | PARTIAL | `GET .../{id}/versions` | List confirmed to exist [api-ref §5]; check-out/check-in/undo write sequence never write-probed | |
| 175 | Entity baseline history | NO | none | Depends on Baselines, absent from REST | |
| 176 | Audit Log (Test Plan/BPT) | PARTIAL | Same `/audits` partial-coverage caveat as #173 | | |
| 177 | Version control UI (locks, Version Status, compare) | PARTIAL | `.../{id}/lock` (GET/POST/DELETE) + `.../{id}/versions` | Both sub-resources confirmed to exist [api-ref §5]; check-out/in write sequence + two-version compare never probed | |
| 178 | Checked-out-by-other warning | UNVERIFIED | lock-conflict response | Never probed | Exp: attempt to lock an already-locked entity, observe response |
| 179 | SHOW_CLIENT_SOURCE=Y config | UNVERIFIED | `site-params` (SA API) | Param CRUD confirmed [api-ref §6.9]; whether resulting audit entries expose the client-source field via REST is unconfirmed | Exp: set param, write, GET audits, inspect fields |

### F. Attachments

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 180 | Attachment types (File/URL/Snapshot/System Info/Clipboard) | PARTIAL | `POST .../attachments` | File type FULL (both upload forms confirmed working) | URL/Snapshot/System-Info/Clipboard as enforced server-side *types* UNVERIFIED — likely just filename/mimetype convention |
| 181 | Download and Open | FULL | `GET .../attachments/{name}` | Used to verify uploads throughout probing | |
| 182 | Upload Selected (push edited copy) | PARTIAL | `PUT .../attachments/{id}` | Documented generic-contract mechanism, not independently probed | |
| 183 | Save (attachment metadata) | PARTIAL | Same as #182 | | |
| 184 | Delete (multi) | FULL | `DELETE` + bulk `?ids-to-delete=` | Single-attachment delete exercised during probe cleanup | |
| 185 | Refresh | FULL | client-side re-`GET` | | |
| 186 | Per-attachment History | NO | none | No dedicated audit trail identified for attachments | |
| 187 | Description field with formatting+spell-check | PARTIAL | attachment `description` field present | Spell-check is Alt-ALM's own editor concern, not an ALM API matter | |
| 188 | Attach to Run/Step | PARTIAL | `POST .../runs/{id}/attachments`, `.../run-steps/{id}/attachments` | Generic contract applies identically [api-ref §5]; not separately probed on these two collections | |
| 189 | Legacy_Rich_Content.doc attachment | N/A | none | Word add-in-specific artifact | Not applicable to Alt-ALM's own editor |
| 190 | Image insertion via Insert Image button | FULL | Multipart `ref-subtype=1` upload + `<img src>` | [data-model §6][probe5/6] full flow confirmed end-to-end | |

### G. Rich-text Editing

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 191 | Rich-text Description/Comments | PARTIAL | `PUT` memo fields | [api-ref §7][probe4] storage+sanitizer fully characterized | Not byte-identical; compare canonicalized HTML |
| 192 | Toolbar inventory | N/A | none | Alt-ALM implements its own toolbar | Stock enumeration irrelevant to API feasibility |
| 193 | Memo UDF export from Word | N/A | none | Word add-in-specific limitation | |
| 194 | Live sandbox round-trip probing | N/A | none | Research-methodology recommendation, already executed (this document's source probes) | |

### H. Follow-up Flags & Alerts

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 195 | Follow-up flag | OTA | none | No dedicated REST source located | OTA support unconfirmed |
| 196 | Alerts (4 admin rules) | OTA | none | Confirmed absent from REST [probe3] | Alt-ALM could alternatively build its own notification engine |
| 197 | Clear per-record or globally | OTA | none | Depends on Alerts | |

### I. Send by Email

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 198 | Send by email | N/A | `POST .../{id}/mail` exists but unresolved | Endpoint exists (19 types) but every attempted body shape failed [probe5/6][data-model §7] | Alt-ALM's own design sends its own mail instead |
| 199 | Async mail execution | N/A | none | Config-flag concern, not applicable to Alt-ALM's own mail path | |

### J. Export & Print

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 200 | Project Reports (templated export) | PARTIAL | `GET reports/{ID}?alt={mime}` | Same as #131/#118 | Create/design NO |
| 201 | No one-click grid export in classic ALM | N/A | none | Negative-finding documentation note | Alt-ALM builds its own client-side export trivially |
| 202 | Print | FULL | browser-native | No ALM API involved | |
| 203 | Wrong-product trap avoided | N/A | none | Documentation note, not a feature | |

### K. Permissions & Access Control

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 204 | Per-module C/U/D permission grid per group | PARTIAL | `GET /permissions`, `GET /roles` (SA API) | Read confirmed reachable, not SaaS-gated [api-ref §6.9] | Write path for group permission grids UNVERIFIED |
| 205 | Data-hiding tab per group per module | NO | none | No REST enforcement/config surface identified | |
| 206 | CRITICAL TRAP: Viewer group bypass | N/A | none | Architectural note for Alt-ALM's own permission-mirroring logic | Not itself an API-reachable feature |
| 207 | Module Access grid | UNVERIFIED | SA API (178 ops) | Group/role endpoints exist; this specific control's endpoint unidentified | Exp: search SA Swagger for module-access operations |
| 208 | Site Admin Client Management | UNVERIFIED | SA API | Site-wide Web-Client gating control unconfirmed | |

### L. Workflow-script Dynamics (VBScript)

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 209 | Workflow-script field visibility (`Field.IsVisible`) | NO | none | **By design** — REST writes bypass workflow scripts entirely via `CLIENT_TYPES_BYPASS_REST_WF` [api-ref §6.8] | Permanent, honest gap |
| 210 | "Required" checkbox rendering | NO | none | Same bypass | Alt-ALM must independently enforce `Required=true` from field metadata |
| 211 | Field presence contract instability | N/A | none | Architectural/documentation signal | Not a feature with a verdict |
| 212 | Script Generators for defect dialogs | N/A | none | Desktop Project-Customization authoring tool | |

### M. User / Session Surfaces

| # | Feature | Verdict | API route(s) | Evidence | Notes |
|---|---|---|---|---|---|
| 213 | Masthead (switcher, username, Search, Tools, Help, Close) | FULL | `GET domains/projects` + session state | Mechanically trivial | |
| 214 | Global Search ("Quality Insight") | NO | none | Inferred absent from Text Search's FTS-index gap | No dedicated cross-project search endpoint identified |
| 215 | Tools menu | PARTIAL | Customization = `GET customization/*` FULL | Task Manager/spell-config UNVERIFIED; "quick defect" = ordinary `POST defects` FULL | |
| 216 | Sidebar (module navigation) | FULL | none — Alt-ALM's own navigation shell | | |
| 217 | Pinned Items panel | UNVERIFIED | unknown | Same as Defects #111 | |
| 218 | My Settings/password | UNVERIFIED | `PUT site-users/{name}` (admin) | Admin-level user edit confirmed [api-ref §6.9]; self-service "my settings" endpoint distinct from admin CRUD unconfirmed | Exp: search Core customization + 178-op SA Swagger for a self-service profile endpoint |

---

## Conflicts resolved

Every conflict the UI inventory flagged as unresolved, plus two errors this task was directed to
correct in `alm-api-reference.md`:

1. **Design Steps API Write Path** (inventory Conflict 1) — **RESOLVED FULL.** `POST/PUT/DELETE
   design-steps` all work; `POST` returns 201 [probe4 §4]. Settled by Probe 4, contradicting the
   static doc's "Not applicable."
2. **Business Components REST Surface** (Conflict 2) — **RESOLVED OTA.** `GET /components` → 403
   (license-gated, endpoint exists but forbidden); `GET /business-components` → 404 (absent). Settled
   by Probe 6.
3. **Test Resources File-Content REST** (Conflict 3) — **STILL UNVERIFIED.** Collections
   (`resources`/`resource-folders`) confirmed to exist; the file-content read/write mechanism itself
   was never write-probed this round. Not fully settled — see row #48/#49/#50.
4. **Copy/Paste Cross-project (Versions)** (Conflict 4) — **RESOLVED FULL\*.** The desktop UI's
   same-version/patch constraint is a session limitation, not an API one; a BFF with two authenticated
   project sessions can read-and-recreate via ordinary CRUD. Surprising capability gain over the stock
   client (rows #35, #101).
5. **Web Client Grid Capabilities (25.1 ceiling)** (Conflict 5) — **RESOLVED N/A.** This documents
   OpenText's own web client's ceiling, not a constraint on Alt-ALM, which builds its own grid against
   the same REST data used for desktop-parity features (row #167).
6. **History/Audit-Log REST Absence** (Conflict 6) — **RESOLVED PARTIAL**, refining the inventory's
   blanket "NOT-VIA-API." `GET .../{entity}/{id}/audits` **does exist** on 24 entity types [probe3],
   but coverage is confirmed incomplete — only status-field changes were logged; creates and memo PUTs
   produced no audit entry [probe4 §10]. Versions tab is separately PARTIAL (list works, write
   sequence unprobed).
7. **Releases/Cycles REST Availability** (Conflict 7) — **RESOLVED FULL.** `POST releases` and
   `POST release-cycles` both verified 201 [probe5][api-ref §6.7].
8. **Favorites REST** (Conflict 8) — **RESOLVED PARTIAL.** `GET/POST favorites` confirmed [wave-1];
   full CRUD/permissions remain UNVERIFIED.
9. **Update Selected Bulk Edit** (Conflict 9) — **RESOLVED PARTIAL**, one notch more conservative than
   the inventory's FULL claim. The bulk `;type=collection` mechanism is fully documented
   [api-ref §4.5] and structurally consistent with the verified single-write path, but the bulk
   endpoint itself was never independently write-probed this session — rows #106/#165.
10. **Pinned Items vs Favorites** (Conflict 10) — **RESOLVED as two distinct features.** Favorites =
    PARTIAL (REST-confirmed). Pinned Items = UNVERIFIED (no REST evidence located at all) — rows
    #111/#160/#217.
11. **`alm-api-reference.md` §6.1 "test-set parent-id discrepancy"** — **this is spurious, do not
    propagate.** The round-2 test-set create fixture's `parent-id` value is **`5`**, not `2` as the
    api-reference's prose states — an intermediate `test-set-folder` created earlier in the same probe
    run, not the root. `test-set-folders/0` = "Root" stands uncontested [data-model §2.1].
12. **`alm-api-reference.md` §9 multipart `ref-subtype=1` "open failure"** — **stale, do not
    propagate.** Round-3 confirms it **WORKS**: a hand-built multipart body (explicit boundary, CRLF
    discipline, file part last) → 201, 3/3 [probe6]. The round-2 failure was a PowerShell `-Form`
    constructor artifact, not a server limitation — row #190.

---

## Summary statistics

### By verdict (grand total, 218 rows)

| Verdict | Count | % |
|---|---|---|
| FULL | 53 | 24% |
| FULL* | 22 | 10% |
| PARTIAL | 51 | 23% |
| UNVERIFIED | 31 | 14% |
| NO | 21 | 10% |
| OTA | 23 | 11% |
| N/A | 17 | 8% |
| **Total** | **218** | 100% |

**"Achievable" (FULL + FULL* + PARTIAL) = 126 of 218 rows (58%).**

### By module

| Module | Rows | FULL | FULL* | PARTIAL | UNVERIFIED | NO | OTA | N/A |
|---|---|---|---|---|---|---|---|---|
| 1. Requirements | 31 | 9 | 7 | 8 | 4 | 2 | 1 | 0 |
| 2. Test Plan+BPT+Resources | 28 | 7 | 2 | 3 | 8 | 2 | 5 | 1 |
| 3. Test Lab | 25 | 5 | 1 | 4 | 8 | 1 | 5 | 1 |
| 4. Test Runs | 11 | 2 | 3 | 6 | 0 | 0 | 0 | 0 |
| 5. Defects | 28 | 11 | 3 | 6 | 1 | 2 | 3 | 2 |
| 6. Dashboard/Analysis | 16 | 1 | 5 | 4 | 1 | 5 | 0 | 0 |
| 7. Releases/Management | 14 | 1 | 1 | 2 | 2 | 1 | 6 | 1 |
| 8. Cross-cutting | 65 | 17 | 0 | 18 | 7 | 8 | 3 | 12 |
| **Total** | **218** | **53** | **22** | **51** | **31** | **21** | **23** | **17** |

### Headline findings

**Surprisingly possible:**
- `design-steps` full write CRUD (POST/PUT/DELETE) — the static doc's "Not applicable" is simply
  wrong on this server.
- `requirement-coverages` and `req-traces` (requirement traceability) — both contested/believed-absent
  for years, both work cleanly.
- `milestones` full CRUD under a release — flips the earlier "PPT is NOT-VIA-API" belief, though
  KPIs/scope-items remain absent.
- Manual test-run execution end-to-end via REST alone (Fast_Run synthesis + run-step auto-copy +
  status mirror) — no OTA needed for the core generator use case.
- Embedded rich-text images end-to-end via REST (multipart `ref-subtype=1` + absolute-URL/`data:`
  `<img src>`).
- Cross-project copy/paste is achievable *better* than the stock desktop client (no version/patch
  coupling) simply because Alt-ALM isn't bound by the desktop UI's single-session model.
- Site Admin user management is fully automatable — solves the sandbox's single-user realism problem
  without any manual admin step.

**Honestly impossible (or OTA-only, unconfirmed):**
- `step-parameters` (test-parameter value definitions) — no REST creation path found after 5 informed
  attempts across two rounds; the underlying "Test parameter" object has no discoverable REST-creatable
  form.
- Business Components/BPT — REST-blocked (403 license-gated / 404 absent), OTA candidate.
- Libraries, Baselines, Alerts, Follow-up flags, Timeslots, Purge-runs, KPIs/Scope-items — all
  confirmed absent from the 1,111-operation resource-list inventory.
- Workflow-script field visibility/required-ness — REST writes bypass workflow scripts *by design*
  (`CLIENT_TYPES_BYPASS_REST_WF`); this is not a probing gap, it's an architectural fact Alt-ALM must
  design around (independent field-metadata-driven validation).
- Direct `POST runs` — fails definitively (8/8 attempts); the entire Test Lab execution chain depends
  on the Fast_Run indirect route.
- Entity History/Audit is only partially exposed — status-field changes are logged, but creates and
  rich-text edits are invisible. Alt-ALM's own change-tracking cannot rely on ALM's audit trail alone
  if full provenance is required.

---

## Generator-impact appendix

The record generator's full creation chain — **requirements → releases/cycles → tests → design-steps →
test-sets → instances → runs (Fast_Run) → defects → links** — maps to this matrix as follows:

| Chain step | Verdict | Row(s) | Generator implication |
|---|---|---|---|
| Requirement create | FULL | #1 | Standard, deterministic field order required (`name`→relational ids→type/subtype last) |
| Release / cycle create | FULL | #141 | Cycle dates must fall inside the parent release's window — server-enforced, will 500 otherwise |
| Test create | FULL | #33 | Root test-folder is project-specific — discover via `parent-id[0]` query at runtime, never hardcode |
| Design-step create | FULL | #39 | Works, but any free text the generator did not author as deliberate markup should be HTML-entity-encoded before write (sanitizer risk, see below) |
| `<<<param>>>` tokens in step text | PARTIAL | #45 | **Must be HTML-entity-pre-encoded** (`&lt;&lt;&lt;name&gt;&gt;&gt;`) or the sanitizer mangles them to `<<>>` |
| `step-parameters` (parameter value records) | **OTA / NO** | #45 note | **Genuine REST-unreachable gap** — no confirmed way to create the underlying "Test parameter" object via REST after 5 attempts. The generator's parameterized-step feature is blocked unless OTA/COM `StepFactory` is used, or the feature is scoped out |
| Requirement-coverage links | FULL | #15/#16 | One `test-config-coverages` row auto-creates per link — do not also POST it directly |
| Test-set / test-set-folder create | FULL | #60 | Root = id 0 "Root" |
| Test-instance create | FULL | #68 | `cycle-id` = **test-set** id (legacy-naming trap, not a release cycle) |
| Run creation | **FULL\*** | #72/#87/#88 | **Only reachable via `PUT test-instances/{id}.status`** synthesizing a `Fast_Run_<...>` record — direct `POST runs` fails definitively. Run name is server-generated and cannot be overridden. Run-steps auto-copy from design-steps (verified), but do not auto-aggregate status from step to run or vice versa (unverified beyond one directional case) |
| Defect create | FULL | #96 | Flat list, no hierarchy field |
| Defect-links | FULL | #122 | Confirmed for `second-endpoint-type` ∈ {`defect`, `requirement`} only — `test`/`run`/`test-instance` targets UNVERIFIED, do not assume they work |
| Rich-text / memo content generally | PARTIAL | #26/#191 | Storage is a full `<html><body>` document, sanitized on write (implicit `<tbody>`, whitespace pretty-print, `<script>` stripped) — round-trip fidelity tests must canonicalize, never compare bytes |
| Embedded images in rich text | FULL | #190 | Two working paths: (1) octet-stream+`Slug` upload + absolute-URL/`data:` `<img src>`, or (2) proper multipart `ref-subtype=1` upload. **Bare filename or relative `src` silently loses the attribute** — never generate those |
| Requirement→release/cycle assignment (`target-rel`/`target-rcyc`) | UNVERIFIED | #7 | Write path (direct multivalue field PUT vs. separate join collections) is unresolved — do not build generator logic on an assumed mechanism until probed |
| UsersList-typed fields (owner/detected-by/etc., 77 fields) | (infrastructure) | — | Sandbox had only 1 project user; Site Admin API can seed dummy users (`POST site-users` + `POST .../projects/{p}/users`), fully automatable — do this before generator realism testing |
| Every write above | (cross-cutting hazard) | — | **Deterministic field order is a hard requirement** (wrong JSON member order → opaque 500s) and **HTTP 500 is not proof of no-commit** (one 500 silently committed a row) — the generator's write layer must always verify-by-GET after any non-2xx response before retrying |

**Bottom line for the generator spec**: the full happy-path chain (requirement → release/cycle → test →
design-step → test-set → instance → Fast_Run → defect → links) is achievable end-to-end via documented
REST, with one hard, permanent gap (`step-parameters`, i.e. parameterized-test data generation) that
should be either scoped out of the generator's v1 or explicitly flagged as an OTA-dependent stretch
feature.
