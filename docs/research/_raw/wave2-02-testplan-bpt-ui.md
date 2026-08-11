# Wave 2 / Agent 2 — Test Plan + Test Resources + BPT UI inventory (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-ui-feature-inventory.md`.
> **Reconciliation note (lead):** this agent's "design-steps read-only via REST" first-pass verdict is refined by wave1-04 (flat collection GET-only per docs; nested-POST pattern unprobed) — top write-probe item.

## Sources

| URL | Version | Client | Type |
|---|---|---|---|
| ui_test_plan_window.htm | 16.00 | Desktop | Primary |
| ui_test_plan_buttons.htm | 16.00/12.60 | Desktop | Primary |
| t_plan_tests.htm | 25.1 | Desktop | Primary |
| r_test_types.htm | 16.00 | Desktop | Primary |
| ui_design_steps.htm | 16.00 | Desktop | Primary |
| t_use_test_parameters.htm | 26.1 | Desktop | Primary |
| ui_test_parameters_tab.htm | 16.00 | Desktop | Primary |
| t_test_configurations.htm | 26.1 | Desktop | Primary |
| ui_test_config_mapparamdb.htm (Map Parameters) | 12.60 | Desktop | Primary |
| ui_criteria_tab.htm | 15.5 | Desktop | Primary |
| ui_models_linkage_tab.htm | 16.00 | Desktop | Primary |
| t_use_bpm.htm | 24.1 | Desktop | Primary |
| entity_history.htm | 25.1 | Both | Primary |
| audit.htm | 16.00 | Desktop | Primary |
| t_use_test_resources.htm | 24.1 | Desktop | Primary |
| ui_test_resources_window.htm (search-summarized; fetch 404) | 16.00 | Desktop | Secondary confidence |
| bpt.htm hub | 26.1 | Desktop | Primary |
| BPT/ui_compswindow.htm | 26.1 | Desktop | Primary |
| BPT/ui_designstepstab.htm (Manual Implementation) | 24.1 | Desktop | Primary |
| BPT/t_comp_usecomponents.htm | 24.1 | Desktop | Primary |
| BPT/t_data_createparams.htm | 24.1 | Desktop | Primary |
| BPT/t_testplan_plantestsflows.htm | 26.1 | Desktop | Primary |
| BPT/c_data_iterationoverview.htm | 15.5 | Desktop | Primary |
| BPT/t_data_setdataforiterations.htm | 17.0 | Desktop | Primary |
| BPT/part_runs.htm | 25.1 | Desktop | Secondary (title only) |
| Web_Runner/main_menu.htm | 24.1 | Web | Primary |
| FAQs/cross_client_FAQs.htm | 24.1 | Both | Primary |
| Web_Runner/create_test_folders_tests.htm | 25.1 | Web | Secondary (title) |
| Web_Runner/view_req_coverage.htm | 25.1 | Web | Secondary (title) |
| Tutorial/sa_plantests_copying.htm | 24.1 | Desktop | Secondary |
| t_create_vapixp_scripts.htm | 26.1 | Desktop | Secondary |
| REST Core: Overview, relations_btwn_entities, design-steps_Collection, tests, test-instances, test-config-coverages, test-set-folders_by_ID | Core | API | Primary |
| Community 188776 (design steps of instance) | — | — | Secondary lead, unresolved |

## Views

**Test Plan module (desktop)**: **Test Plan Tree** (hierarchical, drag-drop, context menus) ⇄ **Test Plan Grid** (flat/filterable for bulk edits); toggle preserves selection.

**Test details tabs** (stable 12.x–26.1): Details, Design Steps, Parameters, Test Configurations, Attachments, Req Coverage, Linked Defects, Dependencies, Business Models Linkage, Test Script (per-type), Criteria (BPT only), History (Baselines / Audit Log / Versions sub-tabs, confirmed 25.1).

**Test Resources module (24.1)**: resource tree of folders + typed resources — Test Resource, Application Area, Data Table, Function Library, Shared Object Repository, Recovery Scenario, API Test Shared Resources, Environment Variables, Analysis Template, Monitor Profile, Testing Activity. Viewer tabs: Resource Viewer (upload/download), Application Area Viewer, Dependencies (Used by / Using), History.

**Business Components module (26.1)**: component tree with Components (root), Component Requests (from Test Plan), Obsolete (read-only) folders. Detail tabs: Details, Manual Implementation, Automation, Parameters, Snapshot, Dependencies, History, Live Analysis.

**BPT composition in Test Plan (26.1)**: business process tests/flows built by sequencing components (tests may include flows); flows support input+output parameters, tests input only; iterations, configurations, coverage, defect linking layer on like other test types.

## Actions inventory

| Action | Where | What it does | Client |
|---|---|---|---|
| New Folder / New Subject | tree toolbar/context | create subject folder | Desktop + Web |
| New Test | toolbar | Create New Test dialog (name, type) | Desktop + Web |
| Copy/Cut/Paste (same project) | right-click | duplicate/move test/folder | Desktop |
| **Copy/Paste cross-project** | copy, log into target, paste | copies test/subject to another project; **both projects must be same version/patch** | Desktop |
| Delete / Rename | toolbar/context | delete/rename | Desktop + Web |
| Find / Go to | toolbar | locate by name/ID | Desktop |
| Sort / Filter | headers, tree filter | filter/sort | Desktop + Web |
| New Design Step | Design Steps tab | Step Details dialog (name, description, expected) | Desktop |
| **Call to Test** | Design Steps tab | inserts step invoking another test | Desktop |
| Generate Script | Design Steps tab | converts manual test to automated skeleton (e.g. VAPI-XP) | Desktop |
| Convert manual test → component | Test Plan → destination dialog | new Business Component from manual test | Desktop |
| New Test Configuration | Test Configurations tab | named config with own parameter values / coverage | Desktop |
| Map Parameters | config tab dialog | binds config parameters to data-table column | Desktop |
| Upload File/Folder, Download | Resource Viewer | resource file management | Desktop |
| New Parameter / Insert Parameter | Parameters tab / step editor | defines parameter; inserts `<<<name>>>` placeholder | Desktop |
| Add/Delete manual step | Component Manual Implementation | component step editing | Desktop |
| Keep Editable / Sync to Automation | Manual Implementation | manual steps independent vs mirroring Keyword GUI automation | Desktop |
| Link to Model / Add to Linkage | Business Models Linkage | associate with BPM diagram entities | Desktop |
| Baseline / Audit Log / Check-in-out / Compare versions | History tab | history + version control | Desktop |

## Feasibility first-pass

| Feature | Verdict | Endpoint(s) | Note |
|---|---|---|---|
| List/read tests + folders | FULL | GET /tests, GET /test-folders | full CRUD documented on tests incl. bulk |
| Create/update/delete tests | FULL | POST/PUT/DELETE /tests | |
| Read design steps | PARTIAL | GET /design-steps | **collection page states POST not supported; PUT/DELETE N/A** |
| Create/edit design steps | NOT-VIA-API (first-pass) | none found | no per-ID write page found; nested `POST /tests/{id}/design-steps` pattern not ruled out — **load-bearing for generator; probe**; OTA DesignStep object exists as fallback |
| Test configurations | FULL* | GET/POST /test-config-coverages | *naming tension: junction vs configs; wave1-04 later found the real `test-configs` collection |
| Test parameters | UNKNOWN | none found | no REST collection located |
| Requirement coverage | PARTIAL | requirement-coverages (cross-ref) | not independently fetched |
| Attachments | FULL | /{entity}/{id}/attachments (15.5 page) | generic multipart |
| Test instances | FULL | GET/POST /test-instances, {ID} CRUD | |
| Test Resources list/upload/download | UNKNOWN | none found this pass | wave1-04 later confirmed `resources`/`resource-folders` collections but file-content gap |
| **Business Components CRUD** | UNKNOWN | none found | zero collection pages despite targeted search — possibly OTA/COM-only; **highest-impact BPT unknown** |
| Component steps/parameters, Flows | UNKNOWN | none found | same |
| History/Audit read | UNKNOWN | none found | matches wave-1: no REST audit surface |
| BPM linkage | UNKNOWN | none found | |
| Copy/paste within project | UNKNOWN | n/a | wave1-04 later found generic `copy` resource |
| Copy/paste cross-project | NOT-VIA-API | n/a | UI requires target-project session; client-side read+recreate only |
| Generate Script | NOT-VIA-API | none | desktop local generation |
| Convert manual → component | NOT-VIA-API | none | depends on component creation (unknown) |

## UNVERIFIED
- Test Resources Module Window content (404; snippet-reconstructed).
- **Whether REST exposes Business Components/Flows/component-steps at all** — zero pages found; (a) genuinely no REST surface (OTA-only) or (b) search miss. Highest-impact BPT unknown; REST wave / live Swagger must settle before matrix finalization.
- Whether design-steps truly lacks any write path (nested/expand patterns not exhaustively ruled out).
- requirement-coverages own page.
- Web Client exclusion of Test Resources + BPT = absence-of-evidence from module list, not explicit statement.
- test-config-coverages semantics (configs vs coverage-junction) — resolved by wave1-04: it's the junction; `test-configs` is separate.
- Multiple 404s on version-specific URLs (stale search index; URLs unstable across releases — re-check before durable citation).

## Handoffs
- REST wave: design-steps write path, test-configs vs junction, requirement-coverages, resources collections, components/flows, audit endpoints, BPM linkage.
- Test Lab UI agent: instances/iterations/configs at runtime.
- OTA surface (DesignStep, BPT objects) → feasibility/fallback owner.
- Workflow customization of Test Plan/BPT fields → customization owner.
- BPT Project Customization pages → alm-entity-model skill input.
