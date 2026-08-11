# Wave 2 / Agent 1 — Requirements module UI inventory (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-ui-feature-inventory.md`.
> **Reconciliation note (lead):** this agent CONFIRMS a desktop "Convert to Tests" wizard exists — the wave-1 requirements API agent found no REST/UI evidence; UI evidence now primary-sourced (S3). Feasibility question becomes "composite client-side operation via tests + requirement-coverages".

## Sources

| # | Title | URL | Version | Client | Type |
|---|---|---|---|---|---|
| S1 | Requirements module landing | alm/en/26.1/online_help/Content/UG/t_use_requirements.htm | 26.1 | Desktop | Primary |
| S2 | Requirements module landing | alm/en/17.0-17.0.1/.../t_use_requirements.htm | 17.0 | Desktop | Primary |
| S3 | Create requirements | alm/en/26.1/.../t_create_requirements.htm | 26.1 | Desktop | Primary |
| S4 | Trace requirements | alm/en/26.1/.../t_trace_requirements.htm | 26.1 | Desktop | Primary |
| S5 | Traceability matrix | alm/en/26.1/.../t_use_traceability_matrix.htm | 26.1 | Desktop | Primary |
| S6 | Risk-based quality management | alm/en/26.1/.../t_assess_risk.htm | 26.1 | Desktop | Primary |
| S7 | Create requirement coverage | alm/en/26.1/.../t_create_req_coverage.htm | 26.1 | Desktop | Primary |
| S8 | Create test coverage | alm/en/24.1/.../t_create_test_coverage.htm | 24.1 | Desktop | Primary |
| S9 | Business process models | alm/en/24.1/.../t_use_bpm.htm | 24.1 | Desktop | Primary |
| S10 | Use libraries and baselines | alm/en/26.1/.../t_use_libraries_baselines.htm | 26.1 | Both | Primary |
| S11 | Alerts and flags | alm/en/24.1/.../menu_alerts_flags.htm | 24.1 | Desktop | Primary |
| S12 | Web Client main menu | alm/en/24.1/online_help/Content/Web_Runner/main_menu.htm | 24.1 | Web | Primary |
| S13 | Create and manage requirements (Web) | alm/en/24.1/.../Web_Runner/create_reqs.htm | 24.1 | Web | Primary |
| S14 | View requirement coverage (Web) | alm/en/25.1/.../Web_Runner/view_req_coverage.htm | 25.1 | Web | Primary |
| S15 | Organize and display data (Web) | alm/en/24.1/.../Web_Runner/common-func.htm | 24.1 | Web | Primary |
| S16 | What's New 25.1 | 25.1 | Web | Primary |
| S17 | What's New 26.1 | 26.1 | Web | Primary |
| S18 | requirements Collection (REST Core) | api_refs/REST_core/.../REST/requirements.html | Core | API | Primary |
| S19 | test-config-coverages Collection | .../test-config-coverages_Collection.html | Core | API | Primary |
| S20 | Search snippets of 404'd ui_*.htm reference pages (ui_requirements_window/buttons/details, ui_req_coverage_tab, ui_coverage_analysis_view, ui_convert_to_tests_wizard, ui_requirements_icons/fields; 12.60–17.0) | — | Desktop | Secondary — **UNVERIFIED** where sole source |

**Source availability note:** all classic `ui_*.htm` reference topics 404 on live fetch across every version tried, though search engines still snippet them — apparently pruned/restructured. Task topics (`t_*.htm`) and `Web_Runner/*` fetch reliably.

## Views

- **Requirements Tree view** (desktop, default): hierarchical folders/requirements tree; zoom, drag-reorder, expand/collapse, grouping/sorting (mechanics UNVERIFIED — 404'd page).
- **Requirements Grid view** (desktop + Web): flat filterable/sortable list for bulk review/edit. Web exposes Grid/Details toggle; 25.1 added "Show in Tree".
- **Coverage Analysis view** (desktop; UNVERIFIED — 404): breakdown of child requirements by test-coverage status rollup.
- **Traceability Matrix view** (desktop, confirmed S5): View > Traceability Matrix; 4-stage Configure wizard (Define Source Requirement → Filter by Linked Requirement → Filter by Linked Tests → Filter by Linked Test Configurations); grid shows name, #linked tests, #traced-from, #traced-to; "Show Full Path"; tabs Traced From / Traced To / Linked Tests.
- **Web Client Requirements tab**: Grid, Details, Show in Tree (25.1), coverage viewing; traceability + library/baseline participation only from 26.1. **Version-controlled projects: Web Client is read-only** ("you can only view requirements of the current checked-in version. You cannot create or edit requirements." S13).

## Actions inventory

| Action | Where | What it does | Client |
|---|---|---|---|
| New Requirement / New Folder | tree right-click, toolbar | child/sibling create | Desktop + Web |
| Cut/Copy/Paste | context menu | duplicate/relocate | Desktop + Web (desktop wording UNVERIFIED) |
| Move (drag / Move Up/Down) | tree | reorder/reparent | Desktop |
| Delete | toolbar/context | remove requirement/folder | Desktop + Web |
| Rename | Web "More Actions" | rename | Web confirmed; Desktop presumed |
| **Convert to Tests wizard** | tree right-click | auto-generates test-plan tests from selected/all requirements, **auto-creating coverage links** (S3 primary: "automatically created between the requirements and their corresponding tests"; wizard steps from snippet, UNVERIFIED) | Desktop |
| Assign to Release/Cycle | details fields | planning link | Desktop + Web (implied) |
| Flags / Follow-up | details/context | personal reminder; red at follow-up date; can email (S11) | Desktop |
| Alerts | system via Alert Rules | 4 rule types (req change → alert covering tests; req modify/delete → alert children & traced reqs to author, …); red=unread, gray=read (S11) | Desktop |
| Send by Email | toolbar | UNVERIFIED (404) | Desktop |
| Export | toolbar | UNVERIFIED | Desktop |
| Find / Go to Requirement by ID | toolbar; Web grid | 25.1 Web adds Go-to + Show in Tree | Desktop + Web |
| Expand/Collapse All | tree toolbar | UNVERIFIED | Desktop |
| Renumber | tree | plausible, not confirmed | Desktop UNVERIFIED |
| Add Requirement Traceability (From Tree / By ID Trace-From / By ID Trace-To) | details > Requirement Traceability tab | tree picker or ID entry; drag-drop grid (S4) | Desktop; Web 26.1 |
| Impact Analysis | Traceability tab | cascading-relationships tree (S4) | Desktop |
| Configure Traceability Matrix | matrix toolbar | 4-stage wizard (S5) | Desktop |
| Add to Coverage (Without/Include Children) | Test Plan > Req Coverage tab > Select Req | link requirements to a test (S7) | Desktop |
| Coverage by Test Configuration | Coverage Mode column | all-configs vs Selected Configurations (S7/S14) | Desktop + Web (view) |
| Risk Assessment questions | details > Risk Analysis > Assessment Questions | score Business Criticality / Failure Probability / Functional Complexity | Desktop |
| Analyze / Analyze and Apply to Children | Risk tab | computes Testing Level (Full/Partial/Basic/None) + Testing Time from risk×policy grid; propagates | Desktop |
| Override Testing Level/Time | Assessment Results | manual override | Desktop |
| Risk export to Word | Risk tab | S6 | Desktop |
| Business Models Linkage | details tab | link to BPM entities/activities/paths (S9) | Desktop |
| Version comparison | History > Versions (26.1) | compare two versions (S17) | Both 26.1+ |
| Baseline capture/compare | Libraries module | Added/Modified/Absent/Moved diff (S10) | Both (library-scoped) |
| Import from Word/Excel | create flow | MS-Office add-ins (S3) | Desktop |
| Attachments | details tab | add/remove (all objects support attachments per 24.1) | Desktop + Web |
| Rich text Description/Comments | details | HTML editor, **auto-saves on navigation away** (S3) | Desktop + Web |
| Change Requirement Type | Type dropdown | switches type → icon + field set change (S3) | Desktop |

## Feasibility first-pass

| Feature | Verdict | Endpoint(s) | Note |
|---|---|---|---|
| Read requirements list/tree | FULL | GET .../requirements (S18) | |
| CRUD incl. bulk | FULL | requirements collection, `type=collection` | per-type field enforcement confirmed |
| Tree hierarchy | PARTIAL | parent-id based | reorder/move mapping UNVERIFIED |
| Coverage Analysis rollup | UNKNOWN | none | aggregate client-side from coverage + requirements |
| Req↔test coverage | PARTIAL | requirement-coverages (cross-referenced in S19) | own page unreachable; CRUD semantics UNKNOWN |
| Traceability trace-from/to | UNKNOWN | none found | sibling wave also found nothing — probe |
| Traceability Matrix | NOT-VIA-API (computed view); data PARTIAL | n/a | reconstruct client-side iff trace + coverage endpoints exist |
| RBQM analyze | UNKNOWN | none | rbt-* fields readable; "Analyze" computation may be client-side only — reimplement |
| Business Models linkage | UNKNOWN | none found | |
| Convert to Tests | UNKNOWN | none | likely composite: create test + coverage client-side |
| Attachments | FULL (typical) | generic pattern | reconcile with wave-1 agent 8 (confirmed) |
| Alerts / follow-up flags | UNKNOWN | none | |
| Libraries & baselines | UNKNOWN | none | wave-1 agent 6: no REST surface found |
| Version history/compare | UNKNOWN | none | versions API exists per wave-1 agent 8 (list only) |
| Web Client req CRUD | FULL | same collection presumed | verify Web Client isn't on a private internal API |

## UNVERIFIED
- Full classic ui_*.htm reference content (all 404) — re-attempt later or find restructured equivalents in 24.1+ doc set.
- Authoritative Requirement Details tab list/order (assembled from multiple sources).
- Coverage-status colour coding.
- Desktop toolbar button set; "Renumber" command existence.
- Whether Web Client uses documented public REST or private internal API.
- requirement-coverages exact path/CRUD; any trace-link collection.

## Handoffs
- REST wave: requirement-coverages, trace links, risk actions, convert-to-tests composite, attachments, alerts/flags, version-history/baseline compare.
- Test Plan agent: Req Coverage tab lives on the test side.
- BPM module inventory: separate scope.
- Project Customization: alert rules, requirement-type customization, workflow scripts.
- Libraries & Baselines module: full mechanics.
- Dashboard: reports/graphs.
