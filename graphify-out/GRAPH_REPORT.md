# Graph Report - Alt-ALM  (2026-08-12)

## Corpus Check
- 109 files · ~207,083 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1037 nodes · 1097 edges · 66 communities (43 shown, 23 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 53 edges (avg confidence: 0.84)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `75a8a98b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Research Fan-Out Complete (14 Reports)
- tests (REST entity)
- ALM/QC release lineage (12.x through 26.1)
- Core REST API query grammar ({}/[] syntax)
- Wave 2 Agent 3: Test Lab + Test Runs UI Inventory, Manual Runner Deep-Dive
- Claude Fable 5 lead-architect role
- Wave 2 Agent 2: Test Plan + Test Resources + BPT UI Inventory
- Requirement Type Customization List
- Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)
- Alt-ALM — Implementation Plan
- 6. Per-domain call recipes
- Analysis View Module
- 8. Cross-cutting Behaviors
- defects (REST entity)
- Baselines (Create/Compare)
- Releases Module (Releases/Cycles Tree)
- probe-auth.ps1
- Permission-Driven UI (C/U/D grid, Data-Hiding)
- Secrets/ALM_API_credentials.json
- alm-entity-model skill
- alm-live-probe skill
- alt-alm-ui skill
- REST_SESSION_MAX_IDLE_TIME site parameter
- resource-folders (REST entity)
- Alerts/alert rules (no REST exposure found)
- Hybrid SSO mode (25.1 P1+)
- is-authenticated check endpoints (v1/v2)
- Undocumented rate limiting / throttling / payload caps
- customization/extensions endpoint
- Send by Email
- API Landscape Fragmentation (Core/Deprecated/v2/Swagger-only)
- Attachments Digest
- Auth/Session/Plumbing Research Digest
- Defects & Links Digest
- Query Grammar (Core) Digest
- Rich Text / Memo Fields Digest
- Version Control Digest
- WORKFLOW BOMBSHELL: REST Writes Bypass Workflow Scripts
- 8. Cross-cutting Behaviors
- Probe 3: Mining Field Types & Customization
- 2. Relationship map
- Write-probe round 1 — ALM/QC sandbox (2026-08-12)
- Alt-ALM — Architecture
- 3. resource-list inventory (`resource-list-site.json`)
- ALM Faker — Record Generator Specification
- Write-probe round 2 — ALM/QC sandbox (2026-08-12)
- Write-probe round 3 — ALM/QC sandbox (2026-08-12)
- probe-write-2.ps1
- ADR 0002 — BFF stack: Java 21 + Spring Boot; SPA: React + TypeScript
- probe-write-3.ps1
- probe-write-1.ps1
- ADR 0001 — Backend-for-frontend proxy, not direct browser access
- ADR 0003 — OTA/COM fallback isolated in an optional Windows sidecar
- ADR 0004 — Session model: single service-account key, pooled sessions, app-level users
- ADR 0005 — Runtime metadata-driven rendering, no hardcoded schemas
- probe-swagger.ps1
- alm-entity-model/SKILL.md
- alm-api/SKILL.md
- alm-data-gen/SKILL.md
- Alt-ALM UI conventions
- alm-live-probe/SKILL.md
- OTA (Open Test Architecture) COM API — Spike Research
- probe-ota-3.ps1
- probe-ota-2.ps1
- probe-ota-1.ps1

## God Nodes (most connected - your core abstractions)
1. `Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)` - 320 edges
2. `Wave 2 Agent 3: Test Lab + Test Runs UI Inventory, Manual Runner Deep-Dive` - 24 edges
3. `Write-probe round 1 — ALM/QC sandbox (2026-08-12)` - 18 edges
4. `Wave 2 Agent 2: Test Plan + Test Resources + BPT UI Inventory` - 18 edges
5. `Wave 2 Agent 1: Requirements Module UI Inventory` - 17 edges
6. `8. Cross-cutting Behaviors` - 14 edges
7. `8. Cross-cutting Behaviors` - 14 edges
8. `ALM/QC Stock UI Feature Inventory` - 13 edges
9. `Alt-ALM — Feature → API Feasibility Matrix` - 13 edges
10. `Claude Fable 5 lead-architect role` - 13 edges

## Surprising Connections (you probably didn't know these)
- `Requirement Type: Business Model` --semantically_similar_to--> `User/Session Surfaces (Masthead, Sidebar, Pinned Items)`  [INFERRED] [semantically similar]
  tests/fixtures/customization-requirement-types.txt → docs/research/_raw/wave2-05-management-crosscutting-ui.md
- `Alt-ALM (README description)` --conceptually_related_to--> `Alt-ALM (front-end deliverable)`  [INFERRED]
  README.md → CLAUDE.md
- `Deliverable A: Alt-ALM front end` --conceptually_related_to--> `Alt-ALM (front-end deliverable)`  [INFERRED]
  docs/prompts/fable-5-research-and-plan.md → CLAUDE.md
- `Deliverable B: Record Generator (ALM Faker)` --conceptually_related_to--> `Record Generator ("ALM Faker")`  [INFERRED]
  docs/prompts/fable-5-research-and-plan.md → CLAUDE.md
- `Target system disambiguation (OpenText ALM/QC vs. Octane)` --conceptually_related_to--> `OpenText ALM / Quality Center (target system)`  [INFERRED]
  docs/prompts/fable-5-research-and-plan.md → CLAUDE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **OpenText ALM/QC vs. ALM Octane product disambiguation** — claude_opentext_alm_qc, claude_alm_octane, docs_prompts_fable_5_research_and_plan_target_system_disambiguation, docs_prompts_fable_5_research_and_plan_alm_octane_wrong_product [EXTRACTED 1.00]
- **Alt-ALM's two integrated deliverables (front end + record generator)** — claude_alt_alm, claude_record_generator, docs_prompts_fable_5_research_and_plan_deliverable_a_alt_alm, docs_prompts_fable_5_research_and_plan_deliverable_b_record_generator [EXTRACTED 1.00]
- **Reusable ALM skill suite authored for future sessions** — claude_skill_alm_api, claude_skill_alm_entity_model, claude_skill_alm_data_gen, claude_skill_alt_alm_ui, claude_skill_alm_live_probe [EXTRACTED 1.00]
- **Live-Probe Evidence Base Backing The Research Digest** — ss_live_probe_verified_digest, lp_probe1_auth_handshake, lp_probe2_customization_metadata [INFERRED 0.85]
- **Pattern of REST Write-Path Gaps Requiring OTA Fallback or Further Probes** — ss_workflow_bombshell, ss_test_parameters_finding, ss_design_steps_finding, ss_resource_file_content_gap [INFERRED 0.75]
- **Research Fan-Out To Synthesis Pipeline** — ss_research_fan_out_complete, rr_raw_subagent_reports, ss_next_actions [INFERRED 0.85]
- **Cookie-based auth/session lifecycle** — docs_research__raw_wave1_01_auth_sessions_alm_authenticate, docs_research__raw_wave1_01_auth_sessions_site_session, docs_research__raw_wave1_01_auth_sessions_lwsso_cookie_key, docs_research__raw_wave1_01_auth_sessions_qcsession_cookie, docs_research__raw_wave1_01_auth_sessions_xsrf_token, docs_research__raw_wave1_01_auth_sessions_alm_user_cookie [INFERRED 0.85]
- **Bulk operation partial-failure error envelope pattern** — docs_research__raw_wave1_02_query_bulk_bulk_operations_core, docs_research__raw_wave1_01_auth_sessions_error_model, docs_research__raw_wave1_03_requirements_bulk_crud [INFERRED 0.85]
- **Undocumented/inconsistently-documented requirement-linking resources** — docs_research__raw_wave1_03_requirements_requirement_coverages, docs_research__raw_wave1_03_requirements_traceability, docs_research__raw_wave1_03_requirements_test_config_coverages [INFERRED 0.75]
- **Shared force-delete-children folder semantics across Test Plan, Test Lab, and Release folders** — docs_research__raw_wave1_04_test_plan_test_folders, docs_research__raw_wave1_05_test_lab_test_set_folders, docs_research__raw_wave1_06_defects_releases_release_folders [INFERRED 0.85]
- **Test Set / Test Instance / Run linkage via the confusing cycle-id / testcycl-id naming pair** — docs_research__raw_wave1_05_test_lab_test_sets, docs_research__raw_wave1_05_test_lab_test_instances, docs_research__raw_wave1_05_test_lab_runs [EXTRACTED 1.00]
- **UI-only features with no documented REST entity (test parameters, hosts/timeslots, milestones, libraries/baselines)** — docs_research__raw_wave1_04_test_plan_test_parameters, docs_research__raw_wave1_05_test_lab_automated_execution_hosts_gap, docs_research__raw_wave1_06_defects_releases_milestones, docs_research__raw_wave1_06_defects_releases_libraries_and_baselines [INFERRED 0.80]
- **Rich text field write/embed/sanitize lifecycle** — docs_research__raw_wave1_08_attachments_richtext_rich_text_memo_storage, docs_research__raw_wave1_08_attachments_richtext_ref_subtype_mechanism, docs_research__raw_wave1_08_attachments_richtext_embedded_images_mechanism, docs_research__raw_wave1_08_attachments_richtext_sanitization_mechanism [INFERRED 0.85]
- **ALM entity/field metadata discovery chain** — docs_research__raw_wave1_07_customization_customization_entities_endpoint, docs_research__raw_wave1_07_customization_customization_entities_fields_endpoint, docs_research__raw_wave1_07_customization_field_descriptor_attribute_set, docs_research__raw_wave1_07_customization_field_type_identifiers [INFERRED 0.85]
- **ALM version control lifecycle (lock/version/purge)** — docs_research__raw_wave1_08_attachments_richtext_locking_mechanism, docs_research__raw_wave1_08_attachments_richtext_versioning_mechanism, docs_research__raw_wave1_08_attachments_richtext_supportsvc_flag, docs_research__raw_wave1_08_attachments_richtext_purge_versioning_history_api [INFERRED 0.85]
- **Requirement-to-Test Coverage Linkage Pattern** — docs_research__raw_wave2_01_requirements_ui_add_to_coverage, docs_research__raw_wave2_01_requirements_ui_convert_to_tests_wizard, docs_research__raw_wave2_01_requirements_ui_requirement_coverages, docs_research__raw_wave2_02_testplan_bpt_ui_req_coverage_tab [INFERRED 0.85]
- **Test Execution Run/Run-Step/Instance Chain** — docs_research__raw_wave2_03_testlab_ui_runs_endpoint, docs_research__raw_wave2_03_testlab_ui_run_steps_endpoint, docs_research__raw_wave2_03_testlab_ui_test_instances_endpoint, docs_research__raw_wave2_03_testlab_ui_manual_runner [INFERRED 0.85]
- **BPT / Business Components Feasibility Gap** — docs_research__raw_wave2_02_testplan_bpt_ui_business_components_module, docs_research__raw_wave2_02_testplan_bpt_ui_bpt_composition, docs_research__raw_wave2_02_testplan_bpt_ui_convert_manual_test_to_component, docs_research__raw_wave2_02_testplan_bpt_ui_business_components_crud_unknown [INFERRED 0.80]
- **History/Versioning/Audit-Trail UI Pattern** — docs_research__raw_wave2_04_defects_dashboard_ui_defect_history_tab, docs_research__raw_wave2_05_management_crosscutting_ui_history_tab, docs_research__raw_wave2_05_management_crosscutting_ui_versions_tab, docs_research__raw_wave2_05_management_crosscutting_ui_version_control_ui [INFERRED 0.85]
- **Filter/Cross-Filter/Favorites Saved-View Pattern** — docs_research__raw_wave2_04_defects_dashboard_ui_filter_sort_dialog, docs_research__raw_wave2_04_defects_dashboard_ui_cross_filter, docs_research__raw_wave2_05_management_crosscutting_ui_filters_and_cross_filter, docs_research__raw_wave2_05_management_crosscutting_ui_favorites [INFERRED 0.85]
- **Octane/ValueEdge Product-Disambiguation Trap** — docs_research__raw_wave2_04_defects_dashboard_ui_defect_history_tab, docs_research__raw_wave2_05_management_crosscutting_ui_history_tab, docs_research__raw_wave2_05_management_crosscutting_ui_export_project_reports [INFERRED 0.85]

## Communities (66 total, 23 thin omitted)

### Community 0 - "Research Fan-Out Complete (14 Reports)"
Cohesion: 0.07
Nodes (38): Fable 5 Research and Plan Kickoff Prompt, Entity Envelope Shape ({entities, TotalResults}), 8 Field-Type Identifiers, Fixtures Captured (Redacted), Lists Metadata (used-lists / lists), oauth2/login Endpoint Flow, Open Items For Next Probe Round, Probe 1: Auth Handshake (+30 more)

### Community 1 - "tests (REST entity)"
Cohesion: 0.09
Nodes (33): OpenText Community discussion 527746 (staff), Generic copy resource (POST .../{collection}/copy), Custom Test Types SDK (plugin mechanism, not REST), design-steps (REST entity), hasTestParams never populated via REST, Subject path string absent from REST (breadcrumb reconstruction), subtype-id test-type discriminator / runtime type discovery, SupportsCopying entity-descriptor gate (+25 more)

### Community 2 - "ALM/QC release lineage (12.x through 26.1)"
Cohesion: 0.07
Nodes (32): CLIENT_TYPES_BYPASS_REST_WF site parameter, customization/entities endpoint, customization/entities/{entity}/fields endpoint (Field metadata), customization/entities/{entity}/types/{subtype}/fields endpoint, customization/users endpoint, EXTENDED_MEMO_FIELDS site parameter, Field descriptor attribute set, Field Type identifier strings (8 types: String, Number, Date, DateTime, Memo, LookupList, UsersList, Reference) (+24 more)

### Community 3 - "Core REST API query grammar ({}/[] syntax)"
Cohesion: 0.07
Nodes (36): alm-authenticate endpoint, ALM_USER cookie, API key authentication, Error model / QCRestException envelope, logout endpoint (/qcbin/authentication-point/logout), LWSSO_COOKIE_KEY cookie, oauth2/login endpoint, QCSession cookie (+28 more)

### Community 4 - "Wave 2 Agent 3: Test Lab + Test Runs UI Inventory, Manual Runner Deep-Dive"
Cohesion: 0.13
Nodes (24): Automation Tab: On-Failure & Notification Rules, Continue Manual Run Action, Wave 2 Agent 3: Test Lab + Test Runs UI Inventory, Manual Runner Deep-Dive, Execution Flow View, Execution Grid, Execution-Model ADR (REST-only Manual Runner Feasibility), Host Manager, Link Defect to Run/Step (+16 more)

### Community 5 - "Claude Fable 5 lead-architect role"
Cohesion: 0.07
Nodes (37): OpenText Core SDP / ALM Octane (wrong product), Alt-ALM (front-end deliverable), Backend-for-frontend proxy design problem (CORS/session/XSRF), Documented-REST-API-only constraint, Reference to fable-5-research-and-plan.md kickoff prompt, Licence-seat / session-model decision, Per-project dynamic field-metadata rendering requirement, Never invent API behaviour policy (+29 more)

### Community 6 - "Wave 2 Agent 2: Test Plan + Test Resources + BPT UI Inventory"
Cohesion: 0.08
Nodes (37): Add Requirement Traceability, Add to Coverage Action, Alerts and Follow-up Flags, Baseline Capture/Compare, Business Models Linkage (Requirements), Configure Traceability Matrix Wizard, Convert to Tests Wizard, Coverage Analysis View (+29 more)

### Community 7 - "Requirement Type Customization List"
Cohesion: 0.07
Nodes (31): Cross Filter (Defects), Defect Details Dialog, Defect History Tab, Defects Module (Desktop Grid), Filter/Sort Dialog, New Defect Dialog, REST .../defects/{id}/attachments endpoint, REST .../defects endpoint (+23 more)

### Community 8 - "Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)"
Cohesion: 0.01
Nodes (320): Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md), BasePath: `ali/plugin-info`, BasePath: `ali/version-info`, BasePath: `domains`, BasePath: `domains/{domain}/projects/{project}/analysis-item-file`, BasePath: `domains/{domain}/projects/{project}/analysis-item-files`, BasePath: `domains/{domain}/projects/{project}/analysis-item-files/groups/{groupsFields}`, BasePath: `domains/{domain}/projects/{project}/analysis-item-files/{parent_entity_id: [0-9]+}/mail` (+312 more)

### Community 9 - "Alt-ALM — Implementation Plan"
Cohesion: 0.06
Nodes (31): Alt-ALM — Implementation Plan, Deferred probes — summary map, Milestone / sequencing view, P0 — Foundations, P1 — Read-only Alt-ALM, P2 — Write core, P3 — Test Lab + planning, P4 — Generator MVP (+23 more)

### Community 10 - "6. Per-domain call recipes"
Cohesion: 0.05
Nodes (37): 1. Version & deployment context, 2.1 The one-step API-key flow (our target auth method), 2.2 Site-session and XSRF, 2.3 Session-liveness checks, 2.4 API keys, 2. Auth & session lifecycle, 3.1 XML vs JSON, and the Core `Fields` array shape, 3.2 ⚠️ Deterministic field-order requirement — probe-verified (+29 more)

### Community 11 - "Analysis View Module"
Cohesion: 0.15
Nodes (13): Analysis View Module, Dashboard View Module, Entity Graphs, Excel Reports — Structurally Out of Scope (raw SQL), graphs/{ID}/layouts/{name} (Tech Preview, UNVERIFIED), Health Reports (7 standard), Live Analysis Graphs, Project Reports (Analysis View) (+5 more)

### Community 12 - "8. Cross-cutting Behaviors"
Cohesion: 0.07
Nodes (29): 1. Requirements Module, 2. Test Plan + BPT + Test Resources Module, 3. Test Lab Module, 4. Test Runs Module, 5. Defects Module, 6. Dashboard / Analysis Module, 7. Releases / Libraries / Management Module, 8. Cross-cutting Behaviors (+21 more)

### Community 13 - "defects (REST entity)"
Cohesion: 0.67
Nodes (4): CLIENT_TYPES_BYPASS_REST_WF — REST bypasses workflow status enforcement by default, defect-links (REST entity), defects (REST entity), Similar defects (OTA FindSimilarBugs only, no REST)

### Community 14 - "Baselines (Create/Compare)"
Cohesion: 0.50
Nodes (4): Baselines (Create/Compare), Compare Baselines Tool, Libraries Module, Pinned Test Sets (Pin to Baseline)

### Community 15 - "Releases Module (Releases/Cycles Tree)"
Cohesion: 0.67
Nodes (4): Milestones/KPIs (PPT, ALM Edition), Release/Cycle Status & Scorecard Tabs, Releases Module (Releases/Cycles Tree), REST .../release-folders endpoint

### Community 17 - "Permission-Driven UI (C/U/D grid, Data-Hiding)"
Cohesion: 0.67
Nodes (3): Viewer Group Bypasses Data-Hiding — Critical Trap, Permission-Driven UI (C/U/D grid, Data-Hiding), Workflow-Script Dynamics (VBScript Field.IsVisible)

### Community 39 - "8. Cross-cutting Behaviors"
Cohesion: 0.07
Nodes (26): 1. Requirements Module, 2. Test Plan Module, 3. Test Lab Module, 4. Test Runs Module, 5. Defects Module, 6. Dashboard / Analysis Module, 7. Releases / Libraries / Management Module, 8. Cross-cutting Behaviors (+18 more)

### Community 40 - "Probe 3: Mining Field Types & Customization"
Cohesion: 0.09
Nodes (21): 1. Boolean Field Type Answer, 2. List Bindings, 3. The 4-List Delta, 4. Read-Only Surface, 5. Multivalue Fields, 6. Requirement Types, 7. Size Outliers, Defect (11 list fields) (+13 more)

### Community 41 - "2. Relationship map"
Cohesion: 0.10
Nodes (21): 0. A note on `r3-*` fixtures — narration now complete, 1. Entity catalog, 2.10 Requirement target-releases/-cycles — join semantics unresolved, 2.11 Creation-order DAG for the generator, 2.1 Root/parent-id defaults — VERIFIED and one correction, 2.2 Requirement type-id table (condensed — full risk-analysis detail in `alm-api-reference.md` §6.1), 2.3 Coverage chain (requirement ↔ test), 2.4 Requirement ↔ requirement traceability (+13 more)

### Community 42 - "Write-probe round 1 — ALM/QC sandbox (2026-08-12)"
Cohesion: 0.11
Nodes (18): 10. Audits readback — VERIFIED, PARTIAL coverage, 11. Cleanup — ALL DELETEs returned HTTP 200 in the final successful run, 1. Requirement create — VERIFIED, 2. Rich-text round-trip on requirement (description + req-rich-content) — VERIFIED, DIFFERS, 3. Test-folder + test create — VERIFIED, 4. Design-steps POST — VERIFIED (the write path is confirmed to exist and work), 5. Step-parameters — FAILED after 2 informed attempts (documented failure, not a field-shape bug), 6. Requirement-coverages + test-config-coverages side effect — VERIFIED (+10 more)

### Community 43 - "Alt-ALM — Architecture"
Cohesion: 0.12
Nodes (17): 1. System overview, 2.1 SPA (React + TypeScript), 2.2 BFF (Java 21, Spring Boot), 2.3 OTA bridge sidecar (optional), 2. Component breakdown, 3.1 Render a defect grid, 3.2 Create a requirement with rich text + embedded image, 3.3 Execute a manual test (Fast_Run synthesis) (+9 more)

### Community 44 - "3. resource-list inventory (`resource-list-site.json`)"
Cohesion: 0.12
Nodes (14): 1.1 Operation inventory (14 ops / 8 paths), 1.2 Request/response body shapes, 1. v2 project API (`api-doc-v2-openapi.json`), 2.1 Grouped inventory (by Swagger tag, all 178 ops), 2.2 Detailed schemas for endpoints we'll actually use, 2. Site Admin API (`api-doc-sa-v2-openapi.json`), 3(a). Full deduped table, grouped by BasePath, 3(b). QueryParams — undocumented query capabilities (+6 more)

### Community 45 - "ALM Faker — Record Generator Specification"
Cohesion: 0.17
Nodes (11): 10. Acceptance criteria (generator MVP), 1. Purpose & product placement, 2. Safety model (normative), 3. Run model, 4. Creation-order DAG, 5. Field-type → strategy matrix, 6. Rich-text block grammar, 7. Distribution defaults (+3 more)

### Community 46 - "Write-probe round 2 — ALM/QC sandbox (2026-08-12)"
Cohesion: 0.20
Nodes (9): a. Roots — VERIFIED, round-1 contamination fixed, b. Step-parameters — FAILED after 3 informed attempts per run (4 total attempt-rounds); one major NEW finding on the token mangling, c. Test Lab chain — mixed: instance/test-set/folder creates all VERIFIED; `run` create FAILED after 3 informed attempts; downstream questions therefore UNANSWERED (blocked on run creation), ⚠️ Cleanup status — READ FIRST, d. Image embed — PARTIALLY VERIFIED; multipart image upload FAILED after 2 informed attempts, but the sanitizer's `src`-attribute filtering rule is clearly and consistently VERIFIED, e. Milestones, mail, test-executions, release-cycle date validation, Fixtures saved (`tests/fixtures/write-probe/`, `r2-` prefix, all masked), Script changes made this session (for future reference) (+1 more)

### Community 47 - "Write-probe round 3 — ALM/QC sandbox (2026-08-12)"
Cohesion: 0.20
Nodes (9): 1. Components / business-components — READ-ONLY GET, settled, 2. Run creation via `POST runs` — still FAILED, but now precisely diagnosed with two distinct, reproducible failure signatures, 3. Section C — run-steps auto-copy, status PUT, instance mirror, aggregation (all run against the Fast_Run from B1, since it's the only run obtainable), 4. Multipart `ref-subtype=1` attachment upload — RESOLVED, VERIFIED working with a hand-built body, 5. Design-step transient 500 (anomaly, not a probe question), ⚠️ Cleanup status — READ FIRST, Fixtures saved (`tests/fixtures/write-probe/`, `r3-` prefix, all masked), Script changes made this session (for future reference) (+1 more)

### Community 48 - "probe-write-2.ps1"
Cohesion: 0.47
Nodes (8): Build-Entity(), Get-FieldValue(), Invoke-Alm(), Mask(), New-AlmEntity(), Save-Fixture(), Show-AllFields(), Show-RequiredFields()

### Community 49 - "ADR 0002 — BFF stack: Java 21 + Spring Boot; SPA: React + TypeScript"
Cohesion: 0.25
Nodes (7): ADR 0002 — BFF stack: Java 21 + Spring Boot; SPA: React + TypeScript, Alternatives considered, Consequences, Context, Criteria comparison, Decision, Weighted read

### Community 50 - "probe-write-3.ps1"
Cohesion: 0.50
Nodes (6): Build-Entity(), Get-FieldValue(), Invoke-Alm(), Mask(), New-AlmEntity(), Save-Fixture()

### Community 51 - "probe-write-1.ps1"
Cohesion: 0.62
Nodes (6): Build-Entity(), Get-FieldValue(), Invoke-Alm(), Mask(), New-AlmEntity(), Save-Fixture()

### Community 52 - "ADR 0001 — Backend-for-frontend proxy, not direct browser access"
Cohesion: 0.33
Nodes (5): ADR 0001 — Backend-for-frontend proxy, not direct browser access, Alternatives considered, Consequences, Context, Decision

### Community 53 - "ADR 0003 — OTA/COM fallback isolated in an optional Windows sidecar"
Cohesion: 0.29
Nodes (6): ⚠️ Addendum, 2026-08-12 (post-spike) — the sidecar has no reachable target today, ADR 0003 — OTA/COM fallback isolated in an optional Windows sidecar, Alternatives considered, Consequences, Context, Decision

### Community 54 - "ADR 0004 — Session model: single service-account key, pooled sessions, app-level users"
Cohesion: 0.33
Nodes (5): ADR 0004 — Session model: single service-account key, pooled sessions, app-level users, Alternatives considered, Consequences, Context, Decision

### Community 55 - "ADR 0005 — Runtime metadata-driven rendering, no hardcoded schemas"
Cohesion: 0.33
Nodes (5): ADR 0005 — Runtime metadata-driven rendering, no hardcoded schemas, Alternatives considered, Consequences, Context, Decision

### Community 56 - "probe-swagger.ps1"
Cohesion: 0.83
Nodes (3): Harvest-OpenApi(), Mask(), Probe()

### Community 57 - "alm-entity-model/SKILL.md"
Cohesion: 0.18
Nodes (10): 1. Runtime discovery — never hardcode, 2. Entity catalog — 62 collections, 3. Generic entity contract, 4. Field-type system, 5. Relationship map + creation-order DAG (most important section), 6. Naming traps (critical — causes real bugs), 7. Per-entity quick reference (15 probed entities), 8. Versioning & locking (two distinct mechanisms) (+2 more)

### Community 58 - "alm-api/SKILL.md"
Cohesion: 0.20
Nodes (9): 1. Load-bearing hazards (read this before writing any code), 2. Auth handshake, 3. Envelopes, 4. Query grammar cheat-sheet (Core, `/qcbin/rest/...`), 5. Bulk operations, 6. Error codes, 7. Call recipes (copy-pasteable, verified only), 8. API surface map (+1 more)

### Community 59 - "alm-data-gen/SKILL.md"
Cohesion: 0.20
Nodes (9): 1. Pre-write safety checklist — MUST, non-negotiable, 2. Field-type → strategy matrix, 3. Rich-text block grammar, 4. Parameter tokens, 5. Embedded images, 6. Creation order + distribution defaults, 7. Write mechanics (delegated to `alm-api`), 8. Deferred / blocked features (+1 more)

### Community 60 - "Alt-ALM UI conventions"
Cohesion: 0.22
Nodes (8): 1. The one rule: nothing about ALM's schema is known at build time, 2. The field-renderer registry (the only place type logic lives), 3. Grids, trees, filters, 4. Rich text and attachments, 5. Write UX: the server is less helpful than the stock client, 6. Design tokens, density, accessibility, Alt-ALM UI conventions, See also

### Community 61 - "alm-live-probe/SKILL.md"
Cohesion: 0.25
Nodes (7): 1. Safety rules (non-negotiable), 2. Masking discipline, 3. Reusable script skeleton, 4. PowerShell gotchas found the hard way, 5. Probe protocol, 6. OTA / COM probing (read this before attempting any OTA work), 7. Currently open experiments

### Community 62 - "OTA (Open Test Architecture) COM API — Spike Research"
Cohesion: 0.25
Nodes (7): Confidence and gaps, OTA (Open Test Architecture) COM API — Spike Research, Q1 — Connection and authentication, Q2 — 32-bit vs 64-bit registration, Q3 — Test parameters (key question), Q4 — Business Process Testing (BPT) via OTA, Q5 — Other OTA-only capabilities

### Community 63 - "probe-ota-3.ps1"
Cohesion: 0.60
Nodes (3): Mask(), Report(), Say()

## Ambiguous Edges - Review These
- `tests (REST entity)` → `Custom Test Types SDK (plugin mechanism, not REST)`  [AMBIGUOUS]
  docs/research/_raw/wave1-04-test-plan.md · relation: conceptually_related_to
- `Entity Graphs` → `graphs/{ID}/layouts/{name} (Tech Preview, UNVERIFIED)`  [AMBIGUOUS]
  docs/research/_raw/wave2-04-defects-dashboard-ui.md · relation: references

## Knowledge Gaps
- **713 isolated node(s):** `1. Load-bearing hazards (read this before writing any code)`, `2. Auth handshake`, `3. Envelopes`, `4. Query grammar cheat-sheet (Core, `/qcbin/rest/...`)`, `5. Bulk operations` (+708 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `tests (REST entity)` and `Custom Test Types SDK (plugin mechanism, not REST)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Entity Graphs` and `graphs/{ID}/layouts/{name} (Tech Preview, UNVERIFIED)`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **Why does `Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)` connect `Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)` to `3. resource-list inventory (`resource-list-site.json`)`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Why does `OpenText ALM / Quality Center REST API — Reference` connect `6. Per-domain call recipes` to `Alt-ALM — Implementation Plan`?**
  _High betweenness centrality (0.013) - this node is a cross-community bridge._
- **What connects `1. Load-bearing hazards (read this before writing any code)`, `2. Auth handshake`, `3. Envelopes` to the rest of the system?**
  _713 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Research Fan-Out Complete (14 Reports)` be split into smaller, more focused modules?**
  _Cohesion score 0.06685633001422475 - nodes in this community are weakly interconnected._
- **Should `tests (REST entity)` be split into smaller, more focused modules?**
  _Cohesion score 0.08522727272727272 - nodes in this community are weakly interconnected._