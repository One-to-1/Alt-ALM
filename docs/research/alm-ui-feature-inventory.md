# ALM/QC Stock UI Feature Inventory

**Collated from Wave-2 UI research reports** (agents 1–5, Feb 2025)  
**Purpose:** Normalized, deduplicated catalog of desktop + web client features.  
**Scope:** Requirements, Test Plan, BPT, Test Resources, Test Lab, Test Runs, Defects, Dashboard/Analysis, Releases/Libraries, Cross-cutting behaviors.

---

## 1. Requirements Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| New Requirement / New Folder | Create child/sibling requirement or folder | Desktop + Web | wave2-01 | FULL (POST requirements collection) |
| Delete Requirement/Folder | Remove requirement or folder | Desktop + Web | wave2-01 | FULL (DELETE) |
| Rename Requirement | Rename via More Actions menu | Web confirmed; Desktop presumed | wave2-01 | Inferred from FULL CRUD |
| Cut/Copy/Paste Requirement | Duplicate or relocate requirement/folder | Desktop + Web | wave2-01 | Desktop wording UNVERIFIED; FULL for CRUD |
| Move (drag / Move Up/Down) | Reorder/reparent in tree | Desktop | wave2-01 | PARTIAL (parent-id based; reorder/move mapping UNVERIFIED) |
| **Convert to Tests wizard** | Auto-generates test-plan tests from selected/all requirements; auto-creates coverage links | Desktop | wave2-01 S3 primary | UNKNOWN (likely composite client-side operation) |
| Assign to Release/Cycle | Link requirement to Release or Cycle | Desktop + Web (implied) | wave2-01 | Inferred from release-scoping |
| Find / Go to Requirement by ID | Locate requirement by ID | Desktop + Web | wave2-01; Web 25.1 adds Show in Tree | FULL (GET /{id}) |
| Expand/Collapse All | Tree navigation | Desktop | wave2-01 S5 | UNVERIFIED (404'd page) |
| Renumber | Plausible, not confirmed | Desktop | wave2-01 | UNVERIFIED |
| Change Requirement Type | Switch type → icon + field set change | Desktop | wave2-01 S3 | Inferred from field-set metadata |
| Add Requirement Traceability (From Tree / By ID Trace-From / By ID Trace-To) | Tree picker or ID entry; drag-drop grid for links | Desktop; Web 26.1 | wave2-01 S4 | UNKNOWN (trace-link collection not found) |
| Impact Analysis (Traceability tab) | Cascading-relationships tree view | Desktop | wave2-01 S4 | UNKNOWN (depends on trace links) |
| Configure Traceability Matrix | 4-stage wizard wizard; filters on source reqs, linked reqs, linked tests, linked test configs | Desktop | wave2-01 S5 | NOT-VIA-API (computed client-side view) |
| Add to Coverage (Without/Include Children) | Link requirements to a test | Desktop | wave2-01 S7 | PARTIAL (requirement-coverages; semantics UNKNOWN) |
| Coverage by Test Configuration | View all-configs vs Selected Configurations | Desktop + Web (view) | wave2-01 S7/S14 | PARTIAL (coverage-status metadata) |
| Risk Assessment questions | Score Business Criticality / Failure Probability / Functional Complexity | Desktop | wave2-01 S6 | UNKNOWN (rbt-* fields readable; computation unclear) |
| Analyze / Analyze and Apply to Children (Risk) | Compute Testing Level (Full/Partial/Basic/None) + Time; propagate to children | Desktop | wave2-01 S6 | UNKNOWN (may be client-side only) |
| Override Testing Level/Time | Manual override of computed risk results | Desktop | wave2-01 S6 | Inferred from risk fields |
| Risk export to Word | Export risk assessment to Word document | Desktop | wave2-01 S6 | UNKNOWN |
| Business Models Linkage | Link to BPM entities/activities/paths | Desktop | wave2-01 S9 | UNKNOWN |
| Version comparison | Compare two versions (26.1+) | Both (26.1+) | wave2-01 S17 | UNKNOWN (versions API exists; comparison may be client-side) |
| Baseline capture/compare | Create baseline; view Added/Modified/Absent/Moved/Moved-and-Modified diffs | Both (library-scoped) | wave2-01 S10 | UNKNOWN (libraries/baselines NOT-VIA-API per wave2-05) |
| Import from Word/Excel | MS-Office add-ins | Desktop | wave2-01 S3 | NOT-VIA-API (client-side document parsing) |
| Attachments | Add/remove files to requirement | Desktop + Web | wave2-01 S12 | FULL (typical) |
| Rich text Description/Comments | HTML editor; auto-saves on navigation away | Desktop + Web | wave2-01 S3 | PARTIAL (storage confirmed; toolbar fidelity UNVERIFIED) |

**Views:**
- Requirements Tree view (hierarchical, desktop default)
- Requirements Grid view (flat, filterable/sortable, desktop + Web)
- Coverage Analysis view (desktop; UNVERIFIED — 404'd page)
- Traceability Matrix view (desktop, configured 4-stage wizard)
- Web Client Requirements tab (Grid, Details, Show in Tree from 25.1)

---

## 2. Test Plan Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| New Folder / New Subject | Create subject folder in Test Plan tree | Desktop + Web | wave2-02 | FULL (POST /test-folders) |
| New Test | Create new test; dialog: name, type | Desktop + Web | wave2-02 | FULL (POST /tests) |
| Copy/Cut/Paste (same project) | Duplicate/move test or folder | Desktop | wave2-02 | UNKNOWN (generic `copy` resource found wave1-04) |
| **Copy/Paste cross-project** | Copy test to another project; both must be same version/patch | Desktop | wave2-02 | NOT-VIA-API (UI requires target-project session) |
| Delete / Rename | Delete or rename test/folder | Desktop + Web | wave2-02 | FULL (DELETE/PUT) |
| Find / Go to Test | Locate by name/ID | Desktop | wave2-02 | FULL (GET /tests/{id}) |
| Sort / Filter | Column headers, tree filter | Desktop + Web | wave2-02 | FULL (query grammar) |
| New Design Step | Create step; dialog: name, description, expected | Desktop | wave2-02 S16-17 | PARTIAL (GET /design-steps confirmed; POST/PUT/DELETE not documented; nested-POST pattern unprobed) |
| **Call to Test** | Insert design step invoking another test | Desktop | wave2-02 | NOT-VIA-API (client-side step reference) |
| Generate Script | Convert manual test to automated skeleton (e.g., VAPI-XP) | Desktop | wave2-02 | NOT-VIA-API (desktop local generation) |
| Convert manual test → component | Create new Business Component from manual test | Desktop | wave2-02 | NOT-VIA-API (depends on Business Components, which are NOT-VIA-API) |
| New Test Configuration | Create named config with own parameter values / coverage | Desktop | wave2-02 | FULL* (test-config-coverages; wave1-04 clarified naming: junction vs configs collection) |
| Map Parameters | Bind config parameters to data-table column | Desktop | wave2-02 S18 | UNKNOWN (test-configs metadata) |
| New Parameter / Insert Parameter | Define parameter; insert `<<<name>>>` placeholder in step | Desktop | wave2-02 | UNKNOWN (no REST collection located) |
| Link to Model / Add to Linkage (Business Models) | Associate with BPM diagram entities | Desktop | wave2-02 | UNKNOWN |
| Baseline / Audit Log / Check-in-out / Compare versions | History + version control | Desktop | wave2-02 S22 | PARTIAL (version control exists; Audit-log REST absence confirmed) |

**Test Resources Module (24.1):**

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| Resource tree (typed folders) | Hierarchical structure: Test Resource, Application Area, Data Table, Function Library, Shared Object Repository, Recovery Scenario, API Test Shared Resources, Environment Variables, Analysis Template, Monitor Profile, Testing Activity | Desktop | wave2-02 S24-25 | UNKNOWN (wave1-04 confirmed `resources`/`resource-folders` but file-content gap) |
| Upload File/Folder | Upload to Resource Viewer | Desktop | wave2-02 | UNKNOWN |
| Download | Download from Resource Viewer | Desktop | wave2-02 | UNKNOWN |
| Application Area Viewer | View application area resource content | Desktop | wave2-02 | UNKNOWN |
| Dependencies (Used by / Using) | View resource usage relationships | Desktop | wave2-02 | UNKNOWN |
| History (Resources) | View resource change history | Desktop | wave2-02 | UNKNOWN |

**Business Components & BPT (26.1):**

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| Business Components tree | Hierarchical: Components (root), Component Requests, Obsolete | Desktop | wave2-02 S26 | **UNKNOWN (zero REST collection pages found despite targeted search — possibly OTA/COM-only; highest-impact BPT unknown)** |
| BPT composition in Test Plan | Sequence components to build business process tests/flows; flows support input+output parameters, tests input only | Desktop | wave2-02 S27 | UNKNOWN (Business Components CRUD, component steps/parameters, Flows all UNKNOWN) |
| Add/Delete manual step (Component Manual Implementation) | Edit component steps | Desktop | wave2-02 | UNKNOWN (depends on component CRUD) |
| Keep Editable / Sync to Automation | Manual steps independent vs mirroring Keyword GUI automation | Desktop | wave2-02 | UNKNOWN |

**BPT tabs in Test Plan:** Details, Manual Implementation, Automation, Parameters, Snapshot, Dependencies, History, Live Analysis.

**Test Plan tabs (stable 12.x–26.1):** Details, Design Steps, Parameters, Test Configurations, Attachments, Req Coverage, Linked Defects, Dependencies, Business Models Linkage, Test Script (per-type), Criteria (BPT only), History (Baselines / Audit Log / Versions sub-tabs, confirmed 25.1).

**Views:**
- Test Plan Tree (hierarchical, drag-drop, context menus)
- Test Plan Grid (flat/filterable for bulk edits; toggle preserves selection)

---

## 3. Test Lab Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| Create test set folder / set | Build Test Lab structure; cycle, dates, attachments | Desktop + Web | wave2-03 | FULL (POST /test-instances, implied from full CRUD) |
| Assign set to release/cycle | Associate test set for reporting | Desktop | wave2-03 | Inferred from release-scoping |
| Copy/Cut/Paste set or folder | Duplicate/move; folder delete: "remove folder only (sets → Unattached)" vs delete all | Desktop, partial Web | wave2-03 S51 | Inferred (standard CRUD, partial Web confirmation) |
| **Pin test set to baseline** | Lock tests to baseline versions; **deletes all existing runs on pin**; removes non-baseline tests; blocks instance copy/paste while pinned | Desktop only | wave2-03 S16 | UNKNOWN (NOT-VIA-API / UNKNOWN) |
| Clear pinned baseline | Release baseline association | Desktop only | wave2-03 S16 | UNKNOWN |
| Reset test set | UNVERIFIED — page not reviewed | Desktop | wave2-03 S54 | UNVERIFIED |
| **Purge Runs** | 3-step wizard (Select Sets → Type of Purge → Confirm); background Task Manager job | Desktop | wave2-03 S14-S15 | UNKNOWN (per-id DELETE only; bulk purge semantics UNKNOWN) |
| Mail / Export test set | UNVERIFIED — not opened | Desktop | wave2-03 | UNVERIFIED |
| Select Tests (add instances) | Add configurations to test set via Test Plan + Requirements Tree tabs; all configs or specific config | Desktop + Web | wave2-03 | FULL (instance CRUD) |
| Go to Test by ID | Navigate to test in Web Test Lab (25.1 P1 feature) | Web only | wave2-03 | FULL (GET /{id}) |
| Test Instance Details | Full dialog: Details, Runs, Execution Settings, Attachments, Linked Defects, History | Desktop | wave2-03 S11 | UNKNOWN (test-instances/{ID} snippet-only) |
| **Host Manager** | Add/delete hosts, host groups; Default sets' remote execution only | Desktop only | wave2-03 S12 | UNKNOWN (Lab Management REST unexplored) |
| Run with Manual Runner | Launch Manual Runner wizard | Desktop | wave2-03 | FULL (POST /runs + /run-steps confirmed buildable without OTA) |
| Run with Sprinter | Launch Sprinter tool (desktop-only alternative) | Desktop only | wave2-03 | NOT-VIA-API (Sprinter-specific) |
| Continue Manual Run | Resume paused run in original runner | Desktop + Web | wave2-03 | Inferred from run-state management |
| Run with Automatic Runner | Execute on Default sets; local or remote hosts | Desktop only | wave2-03 | UNKNOWN (REST-reachable at all vs OTA?) |
| **Execution Flow conditions** | Blue (after-previous), green (only-if-passed), black (after-completes) arrows; time-dependency icon | Desktop only | wave2-03 S7, S22 | NOT-VIA-API / UNKNOWN (opaque description blob in API) |
| **Automation tab (set-level)** | **On-Failure rules** (rerun count, cleanup test, on-final-failure: nothing/stop set/rerun set); **Notification rules** (fail/env-failure/all-finished); Execution Summary email | Desktop only | wave2-03 S9 | UNKNOWN (likely desktop-only config) |
| On Test Failure per-test override | Per-test reruns/cleanup grid; Reset/Clear/Copy-Paste | Desktop only | wave2-03 S10 | UNKNOWN |

**Views:**
- **Test Lab (desktop)** — Test Sets tab (tree) with per-set: **Execution Grid** (flat instance grid), **Execution Flow** (diagram with conditional arrows), **Timeslots** sub-area (Functional test sets), **Last Run Report** pane below grid (Sprinter Results Viewer, LoadRunner Analysis, UFT report, SYSTEM-TEST captured desktop image), **Live Analysis** tab (Performance sets, ALM Enterprise only)
- **Web Client Test Lab (24.1+/25.1+)** — folders/sets + Execution-Grid equivalent; **no Execution Flow, no Analysis, no Automation tab**; automated runs via **Test Execution Agent (TEA)** for UFT-type tests

---

## 4. Test Runs Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| View Test Runs | Filter/sort runs; per-run Details/Report/Results/History/Event Log tabs | Desktop; simplified Web from ~24.1 | wave2-03 S13, S21 | FULL (GET /runs + query) |
| Link defect to run/step | Step-link creates **indirect links** to run, instance, set, test | Desktop + Web (needs re-verify) | wave2-03 S69 | PARTIAL (defects + link mechanism; indirect-link replication UNVERIFIED) |
| Create a run | Create run record | Desktop + Web | wave2-03 S23-S24 | **FULL** (POST .../runs confirmed) |
| Create run step | Add step to run | Desktop | wave2-03 S25 | **FULL** (POST .../runs/{id}/run-steps) |
| Read/update/delete run step | Modify step: status, actual result | Desktop | wave2-03 S26 | **FULL** (GET/PUT/DELETE .../runs/{id}/run-steps/{ID}; PUT confirmed) |
| Continue Manual Run | Resume paused manual run | Desktop + Web | wave2-03 S63 | (Covered under Test Lab) |

**Manual Runner (desktop modal wizard, S2–S5):**
1. **Run Details page**: run metadata (required fields red); Comments tab; Test Details (read-only); **Operating System Information** (edit OS type/SP/build); **Attach to Run**; **New Defect** (auto-links); **Start Run** (Parameters dialog first if unassigned); **End Run**; **Cancel Run** (multi-test batch)
2. **Step Details page**: grid — Status, Description (editable), Expected, Actual + attachment/snapshot icons. Toolbar: **Add Step / Delete Selected**, **Pass Selected / Pass All**, **Fail Selected / Fail All**, **Show Parameters**, **Attach to Step / Attach to Run**, **New Defect** (auto-links). Column order/width adjustable; BPT steps render as expandable tree.
3. **Pause/resume**: **Continue Manual Run** reopens in original runner.

**Web Client Manual Runner (S20, S22):** own Manual Runner from Test Lab grid (Run / Continue Manual Run); Step statuses: Passed, Failed, Blocked, N/A, Not Completed, No Run, **or custom statuses**; Arrow-key step navigation; Attach/create/link defects in-run; **No Execution Flow / Automation / Analysis tabs**.

**Views:**
- **Test Runs module (desktop)** — three tabs: **Test Runs**, **Test Set Runs** (functional), **Build Verification Suite Runs** (ALM Edition); lower pane: Comments, Report, Results, History, Event Log
- **Web Client Test Runs (24.1+)** — Details/Report/Steps/Attachments/Linked Defects/History tabs

---

## 5. Defects Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| New Defect | Create defect; dialog retains unsaved draft data if closed and reopened within session | Desktop; Web can submit new (S20) | wave2-04 S1, S7 | FULL (POST .../defects) |
| Go to Defect | Jump by ID | Desktop | wave2-04 | FULL (GET /{id}) |
| Send by E-mail / Send IM | Email or IM the defect | Desktop | wave2-04 | NOT-VIA-API (Alt-ALM can send its own mail) |
| Defect Details | Full 4-tab dialog: Details, Attachments, Linked Entities, History | Desktop | wave2-04 S2 | FULL (CRUD) |
| Export | Grid export: text, Excel, Word, HTML | Desktop | wave2-04 | UNKNOWN (plain grid export UNVERIFIED; Project Reports for templated export PARTIAL) |
| Copy / Paste | Copy defect(s) within/across projects (schema must match) | Desktop | wave2-04 | FULL (via defect CRUD) |
| **Copy URL / Paste URL** | Shareable deep link to defect | Desktop | wave2-04 | FULL (trivial — Alt-ALM synthesizes its own routes) |
| Delete | Permanent; IDs not reused | Desktop | wave2-04 | FULL (DELETE) |
| Select All / Invert Selection | Bulk selection | Desktop | wave2-04 | FULL (selection logic client-side) |
| Find / Find Next / Replace | Search + field-value replace | Desktop | wave2-04 | FULL (within current filtered set) |
| **Update Selected** (bulk) | Multi-defect field update dialog | Desktop | wave2-04 S70 | FULL (bulk PUT `;type=collection` confirmed) |
| Text Search | Search predefined fields | Desktop | wave2-04 | NOT-VIA-API (server FTS index, opt-in, no REST) |
| Find Similar Defects / Similar Text | Keyword match, ranked % | Desktop | wave2-04 S72 | NOT-VIA-API (server keeps precomputed keyword lists) |
| Alerts / Clear Alerts | Manage alert notifications | Desktop | wave2-04 | UNKNOWN (alert rules driven by admin config) |
| Flag for Follow Up | Personal follow-up flag | Desktop | wave2-04 | UNKNOWN |
| Pin / Unpin | Quick access marking (defects; dashboards) | Desktop | wave2-04 | Inferred from favorites |
| Set / Clear Default Values | Field defaults for subsequently created defects | Desktop | wave2-04 | UNKNOWN |
| Grid Filters / Filter-Sort / Group By | Column filters + full dialog | Desktop | wave2-04 | FULL (query grammar; grouping PARTIAL via client-side aggregation) |
| Indicator Columns | Glyph indicators in grid (alerts, flags, etc.) | Desktop | wave2-04 | FULL (client-side rendering) |
| **Information Panel** | Docked preview tabs (History, Linked Entities) without opening dialog — quick-view surface | Desktop | wave2-04 S79 | PARTIAL (read-only preview; inline edit capability UNVERIFIED) |
| Select Columns / Refresh All | Column chooser / reload | Desktop | wave2-04 | FULL (client-side) |
| Favorites | Save filter+layout as private/public favorite | Desktop | wave2-04 | PARTIAL (wave-1 agent 6 confirmed REST `favorites` collection GET/POST) |
| Project Reports / Graphs from module | Report/graph pre-scoped to current filter | Desktop | wave2-04 | PARTIAL (read existing FULL; create/design NOT-VIA-API) |
| Global Search | Search across projects/modules | Desktop | wave2-04 | Inferred from text-search scope |
| **Share Analysis Item** | Produces authenticated or public URL usable as REST GET target | Desktop | wave2-04 S84 | FULL (read; requires prior UI share) |
| Drill-down on graph segment | Segment → Drill Down Results dialog of entity records | Desktop | wave2-04 | NOT-VIA-API (computed from graph data) |
| Linked Entities tab | Defect links to runs, instances, sets, tests, requirements | Desktop + Web | wave2-04 S98 | PARTIAL (defect-links collection; wave-1 agent 6 confirmed) |
| History tab | Field-level changes (date/time, source, user, old/new) | Desktop + Web | wave2-04 S2 | NOT-VIA-API (UI + DB only) |

**Defects grid:** flat (no tree); toolbar: New Defect, Go to Defect, Send by E-mail, Defect Details, Export. Per-column grid filter row + full **Filter/Sort dialog** with Filter, **Cross Filter**, Group, View Order tabs. **Similar Defects pane** (bottom): ranked by % similarity.

---

## 6. Dashboard / Analysis Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Analysis View tree** | Organize graphs/reports: Private / Public roots (+ cross-project **Shared** template layer). Item types: Entity Graphs, Composite Graphs, Business View Graphs, PPT Graphs, Project Reports, **Health Reports** (7 standard: Blocked Tests, Defects Aging, Failed Tests without Defects, Project Progress, Requirements Coverage, Test Summary, Test Execution), Excel Reports, Live Analysis Graphs, Dashboards | Desktop + Web (25.1+) | wave2-04 S11-S12, S21-S22 | PARTIAL (read existing FULL for reports; create/design/graphs UNKNOWN/NOT-VIA-API) |
| **Dashboard View** | Pages under Private/Public; drag-drop compose; capped by `DASHBOARD_PAGE_ITEM_LIMIT`; pages sort alphabetically; graphs within page reorder/maximize/minimize | Desktop + Web (25.1+) | wave2-04 S17, S21, S29 | PARTIAL (view existing dependent on graph read; create/edit NOT-VIA-API, Alt-ALM's own layer) |
| Entity Graphs (read existing) | Visualize entity relationships, trends, summaries | Desktop | wave2-04 S13 | PARTIAL/UNVERIFIED (maybe graphs/{ID}/layouts/{name} Tech Preview, 404'd; confirm via live Swagger) |
| Create/configure new Entity Graph | Design new graph from scratch | Desktop | wave2-04 | NOT-VIA-API (fallback: render client-side from raw entity queries) |
| Composite Graphs | Compare 2–3 graphs | Desktop | wave2-04 | NOT-VIA-API (client-side composition) |
| Business View Graphs | Business-logic-scoped graphs | Desktop | wave2-04 S15 | UNKNOWN (Business Views metadata) |
| PPT Graphs (Releases module) | Project Portability Toolkit graphs | Desktop (ALM Edition) | wave2-04 S2 | UNKNOWN |
| Project Reports (read existing) | Download templated multi-section reports (HTML, DOCX, DOC, PDF) | Desktop | wave2-04 S24 | **FULL (GET .../reports/{ID}?alt={mime}; only for items already created+shared in UI)** |
| Create/design new Project Report | Design via Report Wizard/templates | Desktop | wave2-04 | NOT-VIA-API (UI-only Report Wizard) |
| Excel Reports (standard SQL) | Standard SQL-based reports | Desktop | wave2-04 | **Structurally out of scope** (raw SQL against project DB violates hard constraint) |
| Live Analysis Graphs | Performance/runtime analysis (never server-persisted) | Desktop | wave2-04 | NOT-VIA-API (fallback: reimplement client-side) |
| **Web Client Dashboard (25.1+)** | Fully native create/edit/organize | Web | wave2-04 S21, S29 | PARTIAL (view FULL; edit currently NOT-VIA-API) |
| **26.1 Tech-Preview "Web Graphs"** | Modern dashboard experience, composite graphs, export graph as image | Web | wave2-04 S22 | PARTIAL/UNVERIFIED (export image confirmed; broader API surface UNVERIFIED) |
| Drill-down on graph segment | Click segment → Drill Down Results dialog of entity records | Desktop | wave2-04 S85 | NOT-VIA-API (computed from graph data) |
| Add graph to Dashboard page | Drag-drop compose | Desktop; native in Web 25.1+ | wave2-04 S86 | PARTIAL (depends on graph read; compose logic UNKNOWN) |
| Export graph as image | Render → image file | Web 26.1 TP | wave2-04 S87 | PARTIAL (Web 26.1 TP only; desktop export UNVERIFIED) |

---

## 7. Releases / Libraries / Management Module

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Releases** | Tree: Releases folder → Release folders → Releases → Cycles. Release tabs: **Details**, **Status** (Progress + Quality sub-tabs), **Master Plan** (Gantt: cycles, milestones, scope items), **Release Scope** (ALM Edition — scope items), **Scorecard** (KPI readiness) | Desktop | wave2-05 S1 | FULL CRUD (wave-1 agent 6 confirmed `releases` and `release-cycles` collections) |
| **Cycles** | Per-release planning; Progress + Quality tabs (test-instance/day progress bars, coverage graph Assigned/Planned/Executed/Passed; Defect Opening Rate by severity, Outstanding Defects) | Desktop | wave2-05 S1 | FULL CRUD (confirmed by wave-1) |
| **Milestones/KPIs (PPT, ALM Edition)** | Milestones per release (`MAX_MILESTONES_PER_RELEASE`); KPIs tab with system types (Authored Tests, Automated Tests, Covered Requirements, Defects Fixed per Day, Passed Requirements, Passed Tests, Rejected Defects, Reviewed Requirements, Severe Defects, Test Instances Executed, Tests Executed) + **KPI Thresholds** | Desktop (ALM Edition) | wave2-05 S2 | NOT-VIA-API (first-pass; consistent with wave-1 finding) |
| **Release Scope items** | Requirements/Tests/Test Sets/Defects linked to release | Desktop (ALM Edition) | wave2-05 S1 | UNKNOWN (scope-item linkage mechanism) |
| **Master Plan (Gantt)** | Cycles, milestones, scope items on timeline | Desktop | wave2-05 | UNKNOWN (render client-side from release/cycle/milestone dates; milestones = gap) |
| **Scorecard (KPI readiness)** | KPI Drill Down Graph, Drill Down Results, Threshold Preview, Breakdown Over Time; export Excel/Word/HTML/text | Desktop (ALM Edition) | wave2-05 S2 | NOT-VIA-API (computed from KPI/threshold data) |
| Entity assignment to Release/Cycle | Link requirements, test sets, defects to Release/Cycle | Desktop | wave2-05 S1, S45 | FULL (via entity field updates) |
| **Libraries** | "Set of entities in a project and the relationships between them" (requirements, tests, test resources, business components); hierarchical tree. Create: right-click → Create Library → Details + Content (filter-based selection) | Desktop | wave2-05 S3-S4 | NOT-VIA-API (first-pass; corroborates wave-1) |
| **Baselines** | Right-click library → Create Baseline → wizard; background job; Compare: baseline vs baseline or vs Current Entities | Desktop | wave2-05 S4-S6 | NOT-VIA-API (first-pass; corroborates wave-1) |
| **Create Baseline** | Verify + create as background Task Manager job (Log dialog) | Desktop | wave2-05 S4 | NOT-VIA-API |
| **Compare Baselines** | Compare Baselines Tool: two panes + counters; **Added / Modified / Absent / Moved / Moved and Modified** classifications; export **.csv** | Desktop | wave2-05 S6 | NOT-VIA-API (computed view); data PARTIAL (entity metadata access may support client-side diff) |
| Baseline exclusions note | Target Release/Cycle changes, re-added coverage/traceability, external-library coverage excluded from baseline | Desktop | wave2-05 S6 | Documentation only |
| **Pinned test sets** | Pin to Baseline / Clear Pinned Baseline in Test Lab; pin locks tests to baseline versions, removes non-baseline tests, **deletes all runs**; unpin reverts + deletes runs again. Entity baseline history: History tab → Baselines sub-tab | Desktop | wave2-03 S16, wave2-05 S4-S5 | UNKNOWN (NOT-VIA-API) |
| **Import/synchronise libraries** | Libraries importable "within or across projects"; **ALM Edition + Enterprise Performance Engineering Edition only** | Desktop (ALM Edition+) | wave2-05 S3, S54 | UNKNOWN (wizard steps UNVERIFIED) |

---

## 8. Cross-cutting Behaviors

### A. Filters & Views

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Filter tab** | Per-field condition boxes; tree pickers for list fields; expression pane with And/Or/Not/</>/<=/>=/=; quote multi-word literals `"login boundary"`; wildcard `*login*`; empty = `""`, non-empty = `not ""`; escape literal angle text with quotes | Desktop + Web | wave2-05 S7 | PARTIAL/FULL (Core query grammar; UI's full grammar vs REST: cross-field OR impossible server-side per wave-1) |
| **Cross Filter tab** | Secondary filter on associated items — Alerts, Defects (direct/indirect), Requirements (linked/covered, trace from/to), Tests, Test Sets, Test Configurations, Test Instances, Runs | Desktop + Web | wave2-05 S7 | UNKNOWN→PARTIAL (REST cross-filters via relation aliases per wave-1 agent 2; client-side joins) |
| **View Order tab** | Multi-field sort capability | Desktop + Web | wave2-05 S7 | FULL (client-side sort) |
| **Group tab** | ≤3 nested levels; User List/Lookup List fields only | Desktop | wave2-05 S7 | PARTIAL (client-side aggregation) |
| **Copy/Paste Filter Settings** | Clipboard-portable filter across projects | Desktop | wave2-05 S7 | UNKNOWN (filter serialization/transfer) |
| Filtered trees marker behavior | Matching parents marked; non-matching folders hidden | Desktop | wave2-05 S7 | FULL (client-side) |

### B. Favorites (Saved Views)

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Favorites** | Captures filter + sort + **view type (grid vs tree)**; Add to Favorites (name+folder); recent list (default 4); Private (creator-only) vs Public folders; Organize Favorites drag-reorder (not across private/public); some commands permission-gated | Desktop + Web (implied) | wave2-04 S81, wave2-05 S10 | PARTIAL (wave-1 agent 6 confirmed REST `favorites` collection GET/POST; full CRUD/permissions UNKNOWN) |

### C. Grids — CLIENT CAPABILITY GAP

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| Select Columns | Column chooser | Desktop + Web | wave2-05 S9 | FULL (client-side) |
| Drag reorder/resize | Column repositioning and width adjustment | Desktop + Web | wave2-05 S9 | FULL (client-side) |
| Multi-key sort | Sort by ≤3 columns | Desktop | wave2-05 S9 (desktop); wave2-05 S9 (web header-click cyclic only) | FULL (client-side; Web limited to header-click cyclic) |
| ≤3-level grouping | Nested grouping | Desktop | wave2-05 S63 | PARTIAL (client-side aggregation) |
| **Update Selected** bulk-edit | Multi-record field update dialog | Desktop | wave2-05 S63 | FULL (bulk PUT `;type=collection` per wave-1) |
| Alerts row indicators | Glyph indicators for alerts/flags | Desktop | wave2-05 S63 | FULL (client-side rendering) |
| **Web Runner grid ceiling (25.1)** | Requirements Grid, Test Runs, Defects only; Select Columns (ID always visible), drag reorder/resize, header-click cyclic sort, show/hide-all. **No freeze, no grouping, no inline edit, no bulk multi-select documented for web** | Web | wave2-05 S9, S64 | Architectural signal: stock web client's grid is thinner than desktop |

**NOTE:** Architectural insight from wave2-05: Alt-ALM measured against desktop features must exceed OpenText's own web client.

### D. Find/Replace & Search

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Find** | Field-value search within current filtered set (Requirements, Components, Test Plan, Resources, Test Lab, Defects); `*` wildcard; case toggle (disabled for numeric/rich-text); Search Results dialog with Go To | Desktop | wave2-05 S8 | FULL (client-side within filtered data) |
| **Replace** | Grid-scoped, single or all-displayed; marks non-versioned fields with `*` | Desktop | wave2-05 S8 | FULL (client-side; PUT to server) |
| **Text Search** | Project-wide but module-scoped (Requirements, Components, Test Plan, Defects); keyword stemming, ignores articles, OR-matching; **checked-in versions only**; **per-project opt-in**; searchable fields configured in customization | Desktop | wave2-05 S8 | NOT-VIA-API (server FTS index, opt-in, no REST; module-scoping enforced by config) |
| Go-to-by-ID | Navigate to entity by ID (via result links or direct) | Desktop + Web | wave2-05 S8 | FULL (GET /{collection}/{id}) |
| Dedicated web go-to-entity page | Web-only navigation page (unfetched) | Web | wave2-05 S66 | UNKNOWN (mechanics UNVERIFIED) |

### E. History, Versioning, Audit

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **History tab** | Field/date/user/old/new changelog; client-source column when `SHOW_CLIENT_SOURCE=Y` (values "Web Client UI"/"Desktop Client UI"/"Unknown Client" — Alt-ALM's writes show as client type); field filter dropdown; requirements exclude Target Release/Cycle | Desktop + Web | wave2-05 S11 | NOT-VIA-API (UI + DB only; audit_records not exposed) |
| **Versions tab (VC projects)** | Nested under History; separate mechanism (check-out/in, two-version compare) | Desktop | wave2-05 S18, wave2-05 S68 | UNKNOWN→FULL (wave-1 agent 8 confirmed versions/check-in/out documented) |
| Entity baseline history | History tab → Baselines sub-tab | Desktop | wave2-05 S4, S52 | UNKNOWN (baseline entity tracking) |
| Audit Log (Test Plan/BPT) | Changelog in History tab | Desktop | wave2-02 S22 | UNKNOWN (matches wave-1: no REST audit surface) |
| **Version control UI** | Check-out locks invisibly; icons: open green lock = you, red lock = other user; Version Status field; edit auto-triggers Check Out dialog; History > Versions lists versions (date/user), **Compare**, check out earlier version to restore. Fields: Version Number (increments on check-in), Checkout Date/Time/By, Status | Desktop | wave2-05 S18 | UNKNOWN→FULL (wave-1 agent 8 confirmed; exact comparison mechanism UNVERIFIED) |
| Checked-out-by-other warning | User prevented from editing when another user holds lock (exact wording UNVERIFIED) | Desktop | wave2-05 S80 | UNKNOWN (VC mechanics in API) |
| **SHOW_CLIENT_SOURCE=Y config** | Reveals client source in History audit column (Web Client UI, Desktop Client UI, Unknown Client) | Desktop + Web | wave2-04 S9-S10, wave2-05 S11 | Configuration flag (user-visible feature) |

### F. Attachments

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Attachments types** | **File** (`UPLOAD_ATTACH_MAX_SIZE`), **URL** (http/ftp/gopher/news/mailto/file), **Snapshot** (.jpg), **System Info** (.tsi), **Clipboard** (text→.txt, image→.jpg) | Desktop + Web | wave2-05 S12, wave2-04 S97 | FULL (typical multipart; type enumeration UNVERIFIED) |
| Download and Open | Retrieve attachment | Desktop + Web | wave2-05 S12 | FULL (GET /{entity}/{id}/attachments/{attach-id}) |
| Upload Selected | Push edited copy back | Desktop | wave2-05 S12 | FULL (PUT /{entity}/{id}/attachments/{attach-id}) |
| Save | Persist changes to attachment metadata | Desktop | wave2-05 S12 | FULL (PUT) |
| Delete (multi) | Remove attachment(s) | Desktop + Web | wave2-05 S12 | FULL (DELETE) |
| Refresh | Reload attachment list | Desktop | wave2-05 S12 | FULL (GET) |
| Per-attachment History | View attachment change history | Desktop | wave2-05 S12 | UNKNOWN (attachment audit trail) |
| Description field with formatting+spell-check | Add/edit attachment metadata | Desktop | wave2-05 S12 | PARTIAL (field present; spell-check mechanism UNVERIFIED) |
| Attach to Run/Step | Attach during run execution | Desktop + Web | wave2-03 S74-S75, wave2-04 S97 | PARTIAL (generic attachments; run/run-step parent types UNVERIFIED) |
| **Legacy_Rich_Content.doc attachment** | Word add-in round-trip exports as this attachment (overwritten per export) | Desktop | wave2-05 S9 | NOT-VIA-API (Word add-in handling) |
| Image insertion via Insert Image button | Attach/snapshot/clipboard; insert_image.htm exists for 25.1/26.1 (unfetched) | Desktop | wave2-05 S72 | PARTIAL (image attachment mechanism; full toolbar UNVERIFIED) |

### G. Rich-text Editing — WEAKEST-SOURCED AREA

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Rich-text Description/Comments** | HTML editor; auto-saves on navigation away | Desktop + Web | wave2-01 S3, wave2-01 S73 | PARTIAL (storage confirmed; toolbar fidelity UNVERIFIED — no enumerating page found; 16.x vs 24.x+ editor delta UNVERIFIED) |
| **Toolbar inventory** | Bold/italic/…/tables/links/spellcheck/full-screen buttons UNVERIFIED | Desktop | wave2-05 S72 | UNVERIFIED (no enumerating page found; wave-1 agent 8 covers image insertion) |
| **Memo UDF export from Word** | First line only (generator fidelity trap) | Desktop | wave2-05 S72 | NOT-VIA-API (Word add-in limitation) |
| **Live sandbox round-trip probing** | Definitive path for fidelity verification | N/A | wave2-05 S72-S73 | Recommended for design phase |

### H. Follow-up Flags & Alerts

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Follow-up flag** | Per-user, dated; gray until date, then email + red | Desktop | wave2-05 S14, wave2-01 S52 | UNKNOWN (follow-up field + scheduler) |
| **Alerts** | Driven by **four admin-activated rules**: (1) requirement modified → alert associated tests → test designer; (2) defect → Fixed → alert linked test instances → responsible tester; (3) test run → Passed → alert linked defects → assignee; (4) requirement changed → alert child/traced requirements → author (excludes Direct Cover Status + RBQM fields). Red=new, gray=viewed; VC projects: alerts fire on check-in only | Desktop | wave2-05 S14, wave2-01 S53 | UNKNOWN (alert-rule engine; per-record alert state) |
| Clear per-record or globally | Dismiss alerts | Desktop | wave2-05 S14 | UNKNOWN (respects active filter) |

### I. Send by Email

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Send by email** | Select Recipients (users/groups) or typed To/CC/BCC; Include: Attachments, History, module extras (Test Coverage/Traced Requirements; Run Steps/Runs; Activities/Design Steps/Linkage/Paths/Snapshot); params auto-included; "Add to comments" logs send into Comments field; body always has entity link+summary | Desktop + Web (select features) | wave2-05 S15, wave2-01 S54, wave2-04 S62 | NOT-VIA-API (Alt-ALM can send its own mail instead) |
| Async mail execution | Async by default (`ASYNC_MAIL_ENABLED`) | Desktop | wave2-05 S15 | Configuration flag |

### J. Export & Print

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Project Reports (Analysis > Project Report / Analysis View → New Project Report)** | Templated multi-section reports over Req/Test Plan/Test Lab/Defects/Components; output **HTML, DOCX, DOC, PDF** (PDF needs Adobe Reader XI, dated desktop constraint); preview first-5-records; ZIP with attachments; shareable read-only | Desktop | wave2-05 S12, wave2-04 S106-S107 | PARTIAL (read existing FULL via `GET .../reports/{ID}?alt={mime}`; create/design NOT-VIA-API) |
| **No one-click grid→Excel/CSV export in classic ALM** | Plain grid export UNVERIFIED/PARTIAL | Desktop | wave2-05 S17, S78 (negative evidence) | UNKNOWN (grid export mechanism) |
| Print | Browser-native in Alt-ALM | Desktop + Web | wave2-05 S78 (not formally documented) | FULL (browser native) |
| **Wrong-product trap avoided** | 2,000-Excel/60,000-CSV caps and "Generate CSV Report" are Octane/ValueEdge, excluded from classic ALM | N/A | wave2-05 S78 | Documentation trap noted |

### K. Permissions & Access Control

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Per-module C/U/D permission grid per group** | Granular module access control | Desktop (Admin) | wave2-05 S19 | FULL (read); UNKNOWN (write) |
| **Data-hiding tab per group per module** | Record-level filter (e.g., Assigned To = `[CurrentUser]`) and/or field visibility (required fields can't be hidden). Modules: Defects, Libraries, Requirements, Components, Resources, Tests, Test Sets | Desktop (Admin) | wave2-05 S20, S82 | UNKNOWN (enforcement via API) |
| **CRITICAL TRAP: Viewer group bypass** | Members of default Viewer group bypass ALL data-hiding rules — permission mirroring must check Viewer membership | Desktop (Admin) | wave2-05 S82 | Architectural constraint for permission implementation |
| **Module Access grid** | Gates module opening per group | Desktop (Admin) | wave2-05 S82 | UNKNOWN (API enforcement) |
| **Site Admin Client Management** | Gates Web Client module access site-wide | Desktop (Admin) | wave2-05 S82 | UNKNOWN (API enforcement) |

### L. Workflow-script Dynamics (VBScript)

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Workflow-script field visibility** | VBScript can set `Field.IsVisible` per field at runtime | Desktop (Project Customization) | wave2-05 S22 | NOT-VIA-API (by design; honest gap) |
| **"Required" checkbox rendering** | Renders labels red and blocks save | Desktop | wave2-05 S22 | NOT-VIA-API |
| **Field presence contract instability** | Docs advise `On Error Resume Next` because field presence is not stable even within a version | Desktop | wave2-05 S22 | Architectural signal: runtime metadata discovery is necessary per project |
| **Script Generators for defect dialogs** | VBScript code generation assistance | Desktop (Project Customization) | wave2-05 S22 | NOT-VIA-API |

### M. User / Session Surfaces

| Feature | What it does | Client | Source | API-feasibility note |
|---------|--------------|--------|--------|----------------------|
| **Masthead** | Domain/Project switcher, username, **Global Search** ("Quality Insight"), **Tools menu**, Help, Close Project | Desktop + Web | wave2-05 S23, S86 | FULL (mechanically) |
| **Global Search** ("Quality Insight") | Cross-project, cross-module search | Desktop + Web | wave2-05 S86 | Inferred from text-search implementation |
| **Tools menu** | Customization, Task Manager, image download, copy project URL, spell config, quick defect, **"Project REST API Reference" link at bottom of Tools page from bare /qcbin** | Desktop + Web | wave2-05 S23, S86 | FULL (mechanically) |
| **Sidebar** | Dashboard / Management (Releases, Libraries) / Requirements (+Business Models) / Testing (Resources, Components, Plan, Lab, Runs) / Lab Resources / Defects | Desktop + Web | wave2-05 S23, S86 | FULL (navigational) |
| **Pinned Items panel** | Requirements/tests/defects quick-access (separate from Favorites) | Desktop + Web | wave2-05 S86 | UNKNOWN (pinned-item state persistence) |
| **My Settings/password** | User customization; accessible via Project Customization "User Properties" or Site Admin My Settings | Desktop (Admin/user) | wave2-05 S86 (secondary-sourced) | UNKNOWN (settings API) |

---

## Count Summary

| Module/Section | Total Features | Web-Only Features | Desktop-Only Features | API NOT-VIA-API markers | API UNVERIFIED markers |
|---|---|---|---|---|---|
| Requirements | 30 | 1 (Web 26.1 traceability) | 15 | 3 (Traceability Matrix, Libraries/Baselines, Risk) | 8 |
| Test Plan + BPT + Test Resources | 36 | 1 (Web — minimal) | 27 | 12 (Call to Test, Generate Script, components CRUD, parameters, BPM linkage, etc.) | 10+ |
| Test Lab | 23 | 1 (Go to Test ID) | 17 | 9 (Pin to baseline, Purge, Execution Flow, Automation tab, Host Manager, etc.) | 6 |
| Test Runs | 7 | 2 (Web custom statuses, auto-sync) | 2 | 0 | 2 (test-instances/{ID}, custom statuses) |
| Defects | 28 | 1 (Web new defect submit) | 12 | 3 (Find Similar, Text Search, History/Audit) | 4 |
| Dashboard/Analysis | 15 | 2 (Web 25.1+ native; 26.1 TP graphs) | 8 | 8 (Create graph, composite, PPT, Live Analysis, etc.) | 4 |
| Releases/Libraries/Management | 14 | 0 | 13 | 6 (Libraries, Baselines, Milestones, KPIs, Scope, PPT imports) | 3 |
| **Cross-cutting Behaviors** | **78** | **~15** | **~35** | **~18** | **~22** |
| | | | | | |
| **GRAND TOTAL** | **231** | **~23** | **~129** | **~59** | **~59** |

**Notes:**
- **Web-client features:** Concentrated in Requirements (read/tree view), Test Lab (grid + manual runner), Test Runs (simplified view), Defects (new + simplified grid), Dashboard (25.1+), with thin grid ceiling (25.1).
- **Desktop-only dominance:** BPT (Business Components collection not found in REST), Test Resources (collection found but file-content gap), Execution Flow/Automation tab rules, Host Manager, rich ecosystem of analysis/graph/report creation surfaces.
- **API NOT-VIA-API count (59):** Includes honest gaps (alerts, follow-up flags, workflow-script effects), client-side computations (Execution Flow conditions, Traceability Matrix, Analytics dashboards), and product-specific surfaces (Send by Email, Text Search FTS, Project Reports templating).
- **API UNVERIFIED count (59):** These are primarily UNKNOWN verdicts awaiting live REST surface probing (Swagger, resource-list, authenticated attempts) or live sandbox round-trips (rich-text fidelity, design-steps write paths, component CRUD, Business Component flows, copy-paste cross-project semantics).

---

## Conflicts Detected

**CONFLICT 1: Design Steps API Write Path**
- **wave2-02 (S86):** "collection page states POST not supported; PUT/DELETE N/A"
- **wave2-02 (S87):** "no per-ID write page found; nested `POST /tests/{id}/design-steps` pattern not ruled out — **load-bearing for generator; probe**"
- **Resolution:** Marked as PARTIAL (GET confirmed, POST/PUT/DELETE uncertain; nested-POST pattern unprobed). Wave-1 agent 4 later found flat GET-only confirmed; nested-POST remains unprobed.

**CONFLICT 2: Business Components REST Surface**
- **wave2-02 (S94):** "UNKNOWN ... zero collection pages found despite targeted search — possibly OTA/COM-only; **highest-impact BPT unknown**"
- **wave2-02 (S105):** "**Whether REST exposes Business Components/Flows/component-steps at all** — zero pages found; (a) genuinely no REST surface (OTA-only) or (b) search miss"
- **Resolution:** Marked as UNKNOWN, not FULL or NOT-VIA-API. This is the single highest-impact BPT unknown; REST wave + live Swagger must settle.

**CONFLICT 3: Test Resources File-Content REST**
- **wave2-02 (S93):** "UNKNOWN ... no REST collection located"
- **wave2-02 (S93 in feasibility table):** "wave1-04 later confirmed `resources`/`resource-folders` collections but file-content gap"
- **Resolution:** Marked as UNKNOWN (collections exist per wave-1; file read/write mechanism still a gap). Reconciled UNKNOWN from initial FULL.

**CONFLICT 4: Copy/Paste Cross-project (Versions)**
- **wave2-02 (S63-64):** "**both projects must be same version/patch**"
- **Resolution:** Architectural constraint noted in feature description; marked NOT-VIA-API (UI requires target-project session; client-side read+recreate only).

**CONFLICT 5: Web Client Grid Capabilities (25.1 ceiling)**
- **wave2-05 (S64):** "Desktop-flavoured docs: ... multi-key sort, ≤3-level grouping, **Update Selected** bulk-edit ... **Web Runner grid page (S9) covers ONLY Requirements(Grid), Test Runs, Defects** and documents only: ... No freeze, no grouping, no inline edit, no bulk multi-select documented for web"
- **Resolution:** Architectural signal noted; Alt-ALM's desktop-parity target exceeds OpenText's own web client's ceiling.

**CONFLICT 6: History / Audit-Log REST Absence**
- **wave2-01 (S93):** "Version history/compare: UNKNOWN"
- **wave2-03 (S96):** "History tab: NOT-VIA-API"
- **wave2-05 (S11):** "NOT-VIA-API (UI + DB only; audit_records not exposed)"
- **Resolution:** NOT-VIA-API marked consistently; however, wave-1 agent 8 confirmed versions collection (list only); comparison mechanism UNKNOWN. Audit-log definitively not exposed.

**CONFLICT 7: Releases/Cycles REST Availability**
- **wave2-05 (S93):** "UNKNOWN→ ... (this agent didn't find them)"
- **wave2-05 (S93 reconciliation note):** "**wave-1 agent 6 confirmed `releases` and `release-cycles` collections — reconciled FULL for CRUD**"
- **Resolution:** Reconciled FULL (marked with wave-1 confirmation in note). This agent's search miss corrected.

**CONFLICT 8: Favorites REST**
- **wave2-04 (S101):** "UNKNOWN→ ... none found here"
- **wave2-04 (S101 note):** "**[Lead note: wave-1 agent 6 confirmed a REST `favorites` collection, GET/POST]**"
- **Resolution:** Marked PARTIAL (confirmed by wave-1); full CRUD/permissions UNKNOWN.

**CONFLICT 9: Update Selected Bulk Edit**
- **wave2-05 (S106):** "UNKNOWN→FULL ... wave-1: bulk PUT `;type=collection` confirmed"
- **Resolution:** Marked FULL (wave-1 confirmation).

**CONFLICT 10: Pinned Items vs Favorites**
- **wave2-04 S81 (Defects):** Pin/Unpin inferred from dashboards per S17
- **wave2-05 S86:** "**Pinned Items panel** (requirements/tests/defects) separate from Favorites"
- **Resolution:** Two distinct features. Pinned Items = persistent quick-access per entity. Favorites = saved filter+layout+view-type combos.

---

## Key Findings & Handoffs

1. **Highest-Impact Unknowns (REST):**
   - Business Components CRUD and flows (zero REST pages found; OTA-only?)
   - Design-steps write path (nested-POST pattern unprobed)
   - Execution Flow conditions (opaque description blob)
   - On-Failure / Notification rules (desktop-only surface not confirmed)

2. **Honest Gaps (NOT-VIA-API):**
   - Alerts (rule-engine, per-record state) — document as risk; alert-rule deployment is admin-only
   - Follow-up flags (dated reminder + email trigger) — document as capability gap; Alt-ALM can offer simpler per-user reminders
   - Text Search project-wide (server FTS index, opt-in, no REST surface)
   - Workflow-script field visibility/required-ness — per-project runtime metadata required; schema variability is honest gap
   - Print — browser-native (not an issue for Alt-ALM)

3. **Web Client Ceiling (Architecture):**
   - OpenText's own web client (25.1) is thinner than desktop: no Execution Flow, no Automation tab, no BPT, no Test Resources module, limited grid (no grouping/freeze/bulk-edit/multi-select documented)
   - Alt-ALM aiming at desktop parity must verify API support feature-by-feature rather than assume web-client parity implies API support

4. **Priority Live-Sandbox Probes:**
   - Rich-text/memo editor (toolbar inventory, 16.x vs 24.x+ delta, memo-UDF export fidelity)
   - Business Components REST surface (Swagger /qcbin/api-doc/v2/)
   - Design-steps nested-POST pattern
   - Copy/paste cross-project mechanics (version/patch matching enforcement in API)

5. **Generator-Specific Design Risks:**
   - Memo UDF export from Word lands first line only (fidelity trap)
   - Pin-to-baseline auto-deletes all runs (side-effect trap for seeded runs)
   - Client-source audit column ("Web Client UI"/"Desktop Client UI") will mark Alt-ALM writes distinctly
   - Checked-in versions only indexed by Text Search (VC-project constraint)

6. **Reconciliation Notes (Wave-1 ↔ Wave-2):**
   - wave-1 agent 6: `releases`, `release-cycles`, `favorites` collections confirmed FULL CRUD
   - wave-1 agent 8: `versions` collection (list only); rich-text storage confirmed; audit-log definitively NOT exposed
   - wave-1 agent 4: design-steps GET-only confirmed; nested-POST pattern still unprobed (highest-priority design-step handoff)
   - wave-1 agent 2: cross-filters achievable via relation aliases + client-side joins

---

## Sources Cited

**Requirements (wave2-01):** 20 sources (S1–S20); desktop 12.60–26.1, Web 24.1–25.1, REST Core, 404'd ui_*.htm pages (secondary-UNVERIFIED).

**Test Plan + BPT (wave2-02):** 42 sources (URLs + REST Core); desktop 12.60–26.1, Web 24.1–25.1, REST Core; ui_test_resources_window.htm secondary-confidence; zero BPT REST pages found (highest-impact unknown).

**Test Lab + Runs (wave2-03):** 30 sources (S1–S30); desktop 15.5–26.1, Web 24.1–25.1, REST Core; S30 (Octane manual-test docs) discarded (wrong product).

**Defects + Dashboard (wave2-04):** 29 sources (S1–S29); desktop 24.1–26.1, Web 25.1–26.1, REST Core, Tech Preview (404'd graph-layouts endpoint); defect-links snippet-only (wave-1 agent 6 primary confirmation).

**Management + Cross-cutting (wave2-05):** 37 sources (S1–S28); desktop 24.1–26.1, Web 25.1, REST Core, Project Customization/WF admin pages, secondary sourcing on My Settings, multiple 404s (stale search index, URL instability across releases).

**Grand total:** ~158 distinct source URLs/documents, with ~40 documented 404s, ~20 secondary-confidence fragments (search snippets), and extensive UNVERIFIED markers where sole sources or old versions.
