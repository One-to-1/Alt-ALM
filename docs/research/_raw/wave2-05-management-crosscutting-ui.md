# Wave 2 / Agent 5 — Management module (Releases/Libraries) + cross-cutting UI behaviours (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-ui-feature-inventory.md`.

## Sources

| # | URL | Version | Client | Type |
|---|---|---|---|---|
| S1 | alm/en/25.1/online_help/Content/UG/t_use_releases_cycles.htm | 25.1 | web help tree | Primary |
| S2 | alm/en/24.1/.../t_use_ppt.htm (PPT releases) | 24.1, ALM Edition only | — | Primary |
| S3 | alm/en/25.1/.../c_libraries_overview.htm | 25.1 | — | Primary |
| S4 | alm/en/25.1/.../t_use_libraries_baselines.htm | 25.1 | — | Primary |
| S5 | alm/en/25.1/.../c_pinned_tests_sets.htm | 25.1 | — | Primary |
| S6 | alm/en/24.1/.../ui_compare_baselines_tool.htm | 24.1 | — | Primary |
| S7 | alm/en/24.1/.../ui_filter.htm | 24.1 | web-based | Primary |
| S8 | alm/en/25.1/.../t_search_replace_alm_data.htm | 25.1 | desktop-flavoured | Primary |
| S9 | alm/en/25.1/online_help/Content/Web_Runner/customize-grid.htm | 25.1 | **Web Runner explicitly** | Primary |
| S10 | alm/en/25.1/.../menu_favorite_views.htm | 25.1 | — | Primary |
| S11 | alm/en/25.1/.../entity_history.htm | 25.1 | both | Primary |
| S12 | alm/en/26.1/.../t_add_attachment.htm | 26.1 | — | Primary |
| S13 | alm/en/24.1/online_help/Content/Word/sa_format_req_toc.htm | 24.1 | Word add-in | Primary (thin on editor toolbar) |
| S14 | alm/en/25.1/.../menu_alerts_flags.htm | 25.1 | — | Primary |
| S15 | alm/en/25.1/.../send_email.htm | 25.1 | — | Primary |
| S16 | alm/en/24.1/.../menu_project_reports.htm | 24.1 | — | Primary |
| S17 | alm/en/17.0-17.0.1/.../t_display_alm_data.htm | 17.0 | desktop-flavoured | Primary (negative evidence re grid export) |
| S18 | alm/en/25.1/.../menu_version_control.htm | 25.1 | — | Primary |
| S19 | alm/en/25.1/online_help/Content/Project_Customization/set-user-group-permission.htm | 25.1 | admin | Primary |
| S20 | alm/en/24.1/.../Project_Customization/data-hiding.htm | 24.1 | admin | Primary |
| S21 | customize-module-access.htm | 25.1 | admin | Secondary (title) |
| S22 | alm/en/26.1/online_help/Content/WF_Customization/wf_script_create.htm | 26.1 | desktop VBScript | Secondary (snippets) |
| S23 | alm/en/24.1/.../ui_alm_common_areas.htm (masthead/sidebar/pinned) | 24.1 | Web | Primary |
| S24 | REST Core Overview | Core | API | Primary |
| S25 | REST Core release-folders.html | Core | API | Primary |
| S26 | REST Core attachments_collection.html | Core | API | Primary |
| S27 | Multiple REST Core collection pages (titles via search) | Core | API | Secondary (titles) |
| S28 | Tools menu → "Project REST API Reference" link location | 26.1-era | Web | Secondary (search-summarised) |

## Management module

### Releases
Tree: root Releases folder → folders → **Releases** → **Cycles** (S1). Release tabs: **Details**, **Status** (Progress + Quality sub-tabs), **Master Plan** (Gantt: cycles, milestones, scope items), **Release Scope** (ALM Edition — scope items with own Content tab: Requirements/Tests/Test Sets/Defects), **Scorecard** (KPI readiness; needs scheduled/manual calculations). Cycle tabs: Progress + Quality (test-instance/day progress bars, coverage graph Assigned/Planned/Executed/Passed; Defect Opening Rate by severity, Outstanding Defects).

**Milestones/KPIs (PPT, ALM Edition, S2)**: milestones per release (`MAX_MILESTONES_PER_RELEASE`), each tied to scope items; KPIs tab with system KPI types (Authored Tests, Automated Tests, Covered Requirements, Defects Fixed per Day, Passed Requirements, Passed Tests, Rejected Defects, Reviewed Requirements, Severe Defects, Test Instances Executed, Tests Executed) + **KPI Thresholds** (Date, OK Above/Below, % warning; `MAX_THRESHOLD_VALUES_PER_KPI`). Rescheduling cascades. Scorecard: KPI Drill Down Graph, Drill Down Results, Threshold Preview, Breakdown Over Time, export Excel/Word/HTML/text.

**Entity assignment**: requirements via right-click Assign to Release/Cycle; test set folders to cycles; defects via target release/cycle fields.

### Libraries
Library = "set of entities in a project and the relationships between them" (requirements, tests, test resources, business components), in a hierarchical tree. Create: right-click → Create Library → Details + Content (filter-based selection) pages (S3, S4).

**Baselines**: right-click library → Create Baseline → wizard verifies + creates as background job (Log dialog; Task Manager in masthead Tools). **Compare**: baseline vs baseline or vs Current Entities; Compare Baselines Tool = module sidebar + two panes (older left) + counters; classifications **Added / Modified / Absent / Moved / Moved and Modified**; export **.csv**. Exclusions: Target Release/Cycle changes, re-added coverage/traceability, external-library coverage (S6). No separate formal "Baseline Report" found in classic ALM (PPM's is a different product).

**Pinned test sets** (S4, S5): Pin to Baseline / Clear Pinned Baseline in Test Lab; pin locks tests to baseline versions, removes non-baseline tests, **deletes all runs**; unpin reverts + deletes runs again. Entity baseline history: History tab → Baselines sub-tab.

**Import/synchronise**: libraries importable "within or across projects", comparable and synchronisable — **ALM Edition + Enterprise Performance Engineering Edition only**; wizard steps UNVERIFIED (one-sentence overview only).

## Cross-cutting behaviours

**3. Filters (S7)**: Filter tab (per-field condition boxes; tree pickers for list fields; expression pane with And/Or/Not/</>/<=/>=/=; quote multi-word literals `"login boundary"`; wildcard `*login*`; empty = `""`, non-empty = `not ""`; escape literal angle text with quotes). **Cross Filter tab**: secondary filter on associated items — Alerts, Defects (direct/indirect), Requirements (linked/covered, trace from/to), Tests, Test Sets, Test Configurations, Test Instances, Runs. **View Order tab**: multi-field sort. **Group tab**: ≤3 nested levels, User List/Lookup List fields only. **Copy/Paste Filter Settings** (clipboard, portable across projects). Filtered trees mark matching parents, hide non-matching folders.

**4. Favorites (S10)**: captures filter + sort + **view type (grid vs tree)**; Add to Favorites (name+folder); recent list (default 4); Private (creator-only) vs Public folders; Organize Favorites drag-reorder (not across private/public); some commands permission-gated. (REST favorites collection = confirmed by wave-1 agent 6.)

**5. Grids — CLIENT CAPABILITY GAP**:
- Desktop-flavoured docs: Select Columns, drag reorder/resize, multi-key sort, ≤3-level grouping, **Update Selected** bulk-edit (title-only confirmed), Alerts row indicators.
- **Web Runner grid page (S9) covers ONLY Requirements(Grid), Test Runs, Defects** and documents only: Select Columns (ID always visible), drag reorder/resize, header-click cyclic sort, show/hide-all. **No freeze, no grouping, no inline edit, no bulk multi-select documented for web** (25.1). → Architectural signal: the stock web client's ceiling is thinner than desktop; Alt-ALM measured against desktop features must exceed OpenText's own web client.

**6. Find/replace & search (S8)**: **Find** = field-value search within current filtered set (Req, Components, Test Plan, Resources, Test Lab, Defects); `*` wildcard; case toggle (disabled for numeric/rich-text); Search Results dialog with Go To. **Replace** grid-scoped, single or all-displayed; marks non-versioned fields with `*`. **Text Search** = project-wide but module-scoped (Req, Components, Test Plan, Defects), keyword stemming, ignores articles, OR-matching, **checked-in versions only**, **per-project opt-in**, searchable fields configured in customization. Go-to-by-ID via result links; dedicated web go-to-entity page exists (unfetched).

**7. History tab (S11)**: field/date/user/old/new; client-source column when `SHOW_CLIENT_SOURCE=Y` (values "Web Client UI"/"Desktop Client UI"/"Unknown Client" — **Alt-ALM's writes will show as a client type**); field filter dropdown; requirements exclude Target Release/Cycle. **Versions tab** (VC projects) nested under History = separate mechanism (check-out/in, two-version compare).

**8. Attachments (S12)**: five types — **File** (`UPLOAD_ATTACH_MAX_SIZE`), **URL** (http/ftp/gopher/news/mailto/file), **Snapshot** (.jpg), **System Info** (.tsi), **Clipboard** (text→.txt, image→.jpg). Controls: Download and Open, Upload Selected (push edited copy back), Save, Delete (multi), Refresh, per-attachment **History**, Description field with formatting+spell-check toolbar.

**9. Rich-text editing — WEAKEST-SOURCED AREA (priority follow-up)**: Confirmed only: Word add-in round-trip lands as **`Legacy_Rich_Content.doc` attachment** (overwritten per export); memo UDFs exported from Word carry **only their first line** (generator fidelity trap); image insertion via Insert Image button (attach/snapshot/clipboard; insert_image.htm exists for 25.1/26.1, unfetched here — wave-1 agent 8 covered it). **Toolbar inventory (bold/italic/…/tables/links/spellcheck/full-screen) UNVERIFIED — no enumerating page found**; no 16.x vs 24.x+ editor delta source. → Live sandbox round-trip probing is the definitive path anyway.

**10. Flags & alerts (S14)**: Follow-up flag = per-user, dated; gray until date, then email + red. Alerts driven by **four admin-activated rules**: (1) requirement modified → alert associated tests → test designer; (2) defect → Fixed → alert linked test instances → responsible tester; (3) test run → Passed → alert linked defects → assignee; (4) requirement changed → alert child/traced requirements → author (excludes Direct Cover Status + RBQM fields). Red=new, gray=viewed; VC projects: alerts fire on check-in only. Clear per-record or Edit > Clear Alerts (respects active filter).

**11. Send by email (S15)**: Select Recipients (users/groups) or typed To/CC/BCC; Include: Attachments, History, module extras (Test Coverage/Traced Requirements; Run Steps/Runs; Activities/Design Steps/Linkage/Paths/Snapshot); params auto-included; "Add to comments" logs send into Comments field; body always has entity link+summary; async by default (`ASYNC_MAIL_ENABLED`).

**12. Export**: **Wrong-product trap avoided** — 2,000-Excel/60,000-CSV caps and "Generate CSV Report" permission are **Octane/ValueEdge**, excluded. Classic ALM: **Project Reports** (Analysis > Project Report / Analysis View → New Project Report) = templated multi-section reports over Req/Test Plan/Test Lab/Defects/Components, output **HTML, DOCX, DOC, PDF** (PDF needs Adobe Reader XI, dated desktop constraint), preview first-5-records, ZIP with attachments, shareable read-only. **No one-click grid→Excel/CSV export found in classic-ALM docs** (S17 negative evidence) — plain grid export UNVERIFIED/PARTIAL. Word/Excel add-ins are import-direction. Print: not documented.

**13. Version control UI (S18)**: check-out locks invisibly; icons: open green lock = you, red lock = other user; Version Status field; edit auto-triggers Check Out dialog; History > Versions lists versions (date/user), **Compare**, check out earlier version to restore. Fields: Version Number (increments on check-in), Checkout Date/Time/By, Status. Exact second-editor warning wording UNVERIFIED (blocked, presumably).

**14. Permission-driven UI (S19, S20)**: (a) per-module C/U/D permission grid per group; (b) **Data-hiding tab** per group per module (Defects, Libraries, Requirements, Components, Resources, Tests, Test Sets): record-level filter (e.g. Assigned To = `[CurrentUser]`) and/or field visibility (required fields can't be hidden). **CRITICAL TRAP: members of the default Viewer group bypass ALL data-hiding rules** — permission mirroring must check Viewer membership or it over-restricts. **Module Access** grid gates module opening; Site Admin Client Management gates Web Client module access site-wide.

**15. Workflow-script dynamics (S22)**: VBScript can set `Field.IsVisible` per field at runtime; "required" checkbox renders labels red and blocks save; Script Generators exist for defect dialogs specifically; docs advise `On Error Resume Next` because **field presence is not a stable contract even within a version** — reinforces per-project runtime metadata + honest-gap stance.

**16. User/session surfaces (S23, S28)**: masthead = Domain/Project switcher, username, **Global Search** ("Quality Insight"), **Tools menu** (customization, Task Manager, image download, copy project URL, spell config, quick defect; **"Project REST API Reference" link at the bottom of the Tools page from bare /qcbin**), Help, Close Project. Sidebar: Dashboard / Management (Releases, Libraries) / Requirements (+Business Models) / Testing (Resources, Components, Plan, Lab, Runs) / Lab Resources / Defects. **Pinned Items panel** (requirements/tests/defects) separate from Favorites. My Settings/password: via Project Customization "User Properties" or Site Admin My Settings (secondary-sourced).

## Feasibility first-pass

| Feature | Verdict | Endpoint(s) | Note |
|---|---|---|---|
| Release folders | FULL | GET/POST .../release-folders (S25) | |
| Releases/Cycles distinct collections | UNKNOWN→ | (this agent didn't find them) | **wave-1 agent 6 confirmed `releases` and `release-cycles` collections — reconciled FULL for CRUD** |
| Master Plan (Gantt) | UNKNOWN | none | render client-side from release/cycle/milestone dates (milestones = gap) |
| Scope items / Scorecard / KPIs / Milestones | NOT-VIA-API (leaning) | none | consistent with wave-1 finding |
| Libraries / Baselines | NOT-VIA-API (first-pass) | none | corroborates wave-1 |
| Pinned test sets | UNKNOWN | none | |
| Attachments | FULL | .../{entity}/{id}/attachments (S26) | |
| Core entity CRUD | FULL (typical) | titles confirmed | |
| Filters server-side | PARTIAL/FULL | Core query grammar | UI's full grammar vs REST: cross-field OR impossible server-side (wave-1) |
| Cross Filter | UNKNOWN→PARTIAL | — | REST cross-filters exist via relation aliases (wave-1 agent 2) + client-side joins |
| Favorites | PARTIAL | overview prose | wave-1 agent 6: GET/POST confirmed |
| Grid chrome (columns/sort/resize) | FULL (client-side) | n/a | |
| Grouping | PARTIAL (client-side aggregation) | n/a | |
| Freeze / inline edit | FULL (client-only) / PARTIAL | n/a | |
| Update Selected bulk | UNKNOWN→FULL | — | wave-1: bulk PUT `;type=collection` confirmed |
| Find in grid | FULL | n/a | |
| Text Search project-wide | NOT-VIA-API (leaning) | none | server FTS index, opt-in, no REST |
| Go to by ID | FULL | .../{collection}/{id} | |
| History tab | NOT-VIA-API | none (Octane hits discarded) | matches wave-1 agent 8 |
| Versions tab | UNKNOWN→FULL | — | wave-1 agent 8: versions/check-in/out documented |
| Rich-text read/write | PARTIAL | — | storage confirmed by wave-1; toolbar fidelity → live probes |
| Follow-up flags | UNKNOWN | none | |
| Alerts | NOT-VIA-API (first-pass) | none | corroborates wave-1 |
| Send by email | NOT-VIA-API (first-pass) | none | Alt-ALM can send its own mail instead |
| Grid export | UNKNOWN (plain) / PARTIAL (Project Reports) | none | Alt-ALM implements export client-side — non-issue |
| Print | UNKNOWN | none | browser-native in Alt-ALM |
| Permissions read | FULL(read)/UNKNOWN(write) | customization resources | effective-permissions endpoint shape unverified (wave-1 #4) |
| Workflow-script effects | NOT-VIA-API (by design) | n/a | honest gap |
| User/session surfaces | FULL (mechanically) | session + SA REST | |

## UNVERIFIED
- Rich-text editor toolbar inventory + 16.x vs 24.x+ editor delta (→ live round-trip probes are definitive).
- Update Selected dialog details (stale help path 404).
- Library import/synchronise wizard steps.
- Checked-out-by-other warning UX.
- Formal Baseline Report beyond .csv export.
- Plain grid→Excel/CSV export existence in classic ALM.
- Exact REST paths for releases/cycles (resolved by sibling), libraries, baselines, favorites (resolved), alerts, flags, history, VC actions (resolved) — mostly Swagger-question items.
- Web go-to-entity page mechanics; My Settings flow.

## Handoffs
1. Reconcile with API wave: releases/cycles (✓ exist), favorites (✓), history (✗ none), VC actions (✓).
2. **Priority follow-up: rich-text/memo editor** — recommend targeted site-map grep or (better) live sandbox probing.
3. 24.1+ Swagger pass may resolve most UNKNOWN endpoints.
4. Product-disambiguation trap log: Octane export caps + history_records excluded.
5. **ADR note: distinguish "desktop client can do" vs "OpenText's own web client can do"** — web client's thinner grid may be the de-facto ceiling the REST API was built to serve; Alt-ALM aiming at desktop parity must verify API support feature-by-feature rather than assuming web-client parity implies API support.
