# Wave 2 / Agent 4 — Defects + Dashboard/Analysis UI inventory (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-ui-feature-inventory.md`.

## Sources

| # | URL | Version | Client | Type |
|---|---|---|---|---|
| S1 | alm/en/24.1/online_help/Content/UG/t_track_defects.htm | 24.1 | Desktop | Primary |
| S2 | .../UG/ui_defect_details.htm | 24.1 | Desktop | Primary |
| S3 | .../UG/ui_defects_buttons.htm (Defects Module Menus and Buttons) | 24.1 | Desktop | Primary |
| S4 | .../UG/ui_defects_fields.htm | 24.1 | Desktop | Primary |
| S5 | .../Tutorial/sa_defect_match.htm (Matching Defects) | 24.1 | Desktop | Primary |
| S6 | .../UG/ui_filter.htm (Filter/Sort dialog) | 24.1 | Desktop | Primary |
| S7 | .../Tutorial/sa_defect_add.htm | 24.1 | Desktop | Primary |
| S8 | .../Tutorial/sa_defect_update.htm | 24.1 | Desktop | Primary |
| S9 | alm/en/25.1/online_help/Content/UG/entity_history.htm | 25.1 | Both | Primary |
| S10 | alm/en/25.1/online_help/Content/audit.htm | 25.1 | Both | Primary |
| S11 | alm/en/24.1/online_help/Content/UG/t_analyze_data.htm | 24.1 | Desktop | Primary |
| S12 | .../Tutorial/sa_analyze_toc.htm | 24.1 | Desktop | Primary |
| S13 | alm/en/26.1/online_help/Content/UG/configure_entity_graphs.htm | 26.1 | Desktop | Primary |
| S14 | alm/en/24.1/online_help/Content/UG/menu_live_analysis.htm | 24.1 | Desktop | Primary |
| S15 | .../UG/menu_graphs_bv.htm (Business view graphs) | 24.1 | Desktop | Primary |
| S16 | .../UG/menu_project_reports.htm | 24.1 | Desktop | Primary |
| S17 | .../UG/dashboards.htm | 24.1 | Desktop/Web | Primary |
| S18 | alm/en/25.1/online_help/Content/UG/menu_excel_reports.htm | 25.1 | Desktop | Primary |
| S19 | alm/en/15.5-15.5.1/online_help/Content/UG/ui_share_analysis_item.htm | 15.5 | Desktop | Primary (older; verify vs 24.1) |
| S20 | alm/en/25.1/online_help/Content/Web_Runner/web-client.htm | 25.1 | Web | Primary |
| S21 | 25.1 What's New | 25.1 | Web | Primary |
| S22 | 26.1 What's New (latest) | 26.1 | Web | Primary |
| S23 | REST Core: defects.html | Core | REST | Primary |
| S24 | REST Core: **reports.html** | Core | REST | Primary |
| S25 | REST Core: attachments_collection.html | Core | REST | Primary |
| S26 | REST Core: resource-list.html | Core | REST | Primary |
| S27 | REST Tech Preview: graph_layouts (`graphs/{ID}/layouts/{name}`) — page 404'd; pattern from search snippet only | TP | REST | **UNVERIFIED** |
| S28 | 26.1 api_rest_api_reference_core.htm (Swagger note) | 26.1 | REST | Primary |
| S29 | community "New Innovations in OpenText AQM 25.1" | 25.1 | Web | Secondary |

Note: could not confirm whether a `graphs` collection (parallel to `reports`) is independently documented — only a snippet describing `/graphs/{ID}/layouts/{layout name}`; the Tech-Preview URL 404'd. Confirm against live Swagger `/qcbin/api-doc/v2/`.

## Views

**Defects module (desktop, flat grid — no tree)**
- Grid toolbar: New Defect, Go to Defect, Send by E-mail, Defect Details, Export (S3).
- Per-column grid filter row + full **Filter/Sort dialog** with Filter, **Cross Filter**, Group, View Order tabs (S6). Cross Filter dimensions for defects: linked Requirements, linked Tests, and **has-Alerts** (S6).
- **New Defect dialog**: retains unsaved draft data if closed and reopened within the session (S1, S7). "Find Similar Defects" invoked from inside the dialog pre-submit (S1).
- **Defect Details dialog**: four tabs — Details, Attachments, Linked Entities, History (S2). Dialog toolbar: Save, First/Prev/Next/Last navigation, Go to Defect, Flag for Follow Up, Alerts (conditional), Standard Defect Report, Send by E-mail, Send IM, Spell Check/Thesaurus/Spelling Options, Field Search (S2).
- **History tab** = field-level changes (date/time, source, user, old/new). Audit-log source-of-change column gated by `SHOW_CLIENT_SOURCE=Y` (S9, S10).
- **Similar Defects pane**: bottom panel; ranked by % similarity from stored Summary/Description keyword lists (keywords >2 chars, case-insensitive) (S5).

**Dashboard/Analysis**
- **Analysis View** module: tree with **Private** / **Public** roots (+ cross-project **Shared** template layer). Item types: Entity Graphs, Composite Graphs (2–3 compared), Business View Graphs, PPT Graphs (Releases module), Project Reports, **Health Reports (7 standard: Blocked Tests, Defects Aging, Failed Tests without Defects, Project Progress, Requirements Coverage, Test Summary, Test Execution)**, Excel Reports (standard SQL + Business-View), Live Analysis Graphs, Dashboards (S11, S12).
- **Dashboard View** module: pages under Private/Public; compose by drag-drop of Analysis-View graphs; capped by `DASHBOARD_PAGE_ITEM_LIMIT`; pages sort alphabetically (no manual reorder); graphs within a page reorder/maximize/minimize (S17).
- **Web Client Dashboard** (25.1+): fully native create/edit/organize (S21, S29). **26.1 Tech-Preview "Web Graphs"**: modern dashboard experience, composite graphs, export graph as image (S22).

## Actions inventory

| Action | Where | What it does | Client |
|---|---|---|---|
| New Defect | grid toolbar / Defects menu | dialog; draft persists in session | Desktop; Web can submit new defects (S20) |
| Go to Defect | Defects menu | jump by ID | Desktop |
| Send by E-mail / Send IM | menu / details toolbar | mail or IM the defect | Desktop |
| Defect Details | Defects menu | full 4-tab dialog | Desktop |
| Export | Defects menu | grid export: text, Excel, Word, HTML | Desktop |
| Copy / Paste | Edit menu | copy defect(s) within/across projects (schema must match) | Desktop |
| **Copy URL / Paste URL** | Edit menu | shareable deep link to defect | Desktop |
| Delete | Edit menu | permanent; IDs not reused | Desktop |
| Select All / Invert Selection | Edit menu | bulk selection | Desktop |
| Find / Find Next / Replace | Edit menu | search + field-value replace | Desktop |
| **Update Selected** (bulk) | Edit menu | multi-defect field update dialog | Desktop |
| Text Search | Edit menu | search predefined fields | Desktop |
| Find Similar Defects / Similar Text | Edit menu / New Defect dialog | keyword match, ranked % | Desktop |
| Alerts / Clear Alerts | Edit menu / details toolbar | manage alert notifications | Desktop |
| Flag for Follow Up | Edit menu / details toolbar | personal follow-up flag | Desktop |
| Pin / Unpin | Edit menu | quick access marking (defects; dashboards per S17) | Desktop |
| Set / Clear Default Values | Edit menu | field defaults for subsequently created defects | Desktop |
| Grid Filters / Filter-Sort / Group By | View menu | column filters + full dialog | Desktop |
| Indicator Columns | View menu | glyph indicators in grid | Desktop |
| **Information Panel** | View menu | docked preview tabs (History, Linked Entities…) without opening dialog — the "quick view" surface | Desktop |
| Select Columns / Refresh All | View menu | column chooser / reload | Desktop |
| Favorites | View | save filter+layout as private/public favorite | Desktop |
| Project Reports / Graphs from module | Analysis menu in Defects | report/graph pre-scoped to current filter | Desktop |
| Global Search | cross-module | search across projects/modules | Desktop |
| **Share Analysis Item** | Analysis/Dashboard tree right-click | produces authenticated or public URL usable directly as REST GET target | Desktop |
| Drill-down on graph segment | graph view | segment → Drill Down Results dialog of entity records | Desktop |
| Add graph to Dashboard page | Dashboard View | drag-drop compose | Desktop; native in Web 25.1+ |
| Export graph as image | Web Graphs TP | render → image file | Web 26.1 TP |

## Feasibility first-pass

| Feature | Verdict | Endpoint(s) | Note |
|---|---|---|---|
| Read/list/filter/sort defects | FULL | GET .../defects (S23) | |
| Create defect (incl. bulk) | FULL | POST .../defects (+;type=collection) | |
| Update defect (incl. bulk) | FULL | PUT bulk confirmed; single-instance PUT standard pattern — verify | |
| Delete defect (incl. bulk) | FULL | DELETE bulk confirmed; single standard — verify | |
| Attachments | FULL | .../defects/{id}/attachments (S25) | |
| Linked Entities tab | PARTIAL | defect-links (snippet-sourced here; **confirmed primary by wave-1 agent 6**) | |
| History / Audit Log tab | NOT-VIA-API | none | UI + DB only (Octane history_records hits discarded — wrong product) |
| Find Similar Defects | NOT-VIA-API | none | server keeps precomputed keyword lists; client-side similarity ≠ equivalent |
| Favorites (saved views) | UNKNOWN→ | none found here | **[Lead note: wave-1 agent 6 confirmed a REST `favorites` collection, GET/POST]** |
| Alerts / Follow-up flags | UNKNOWN | none | |
| Copy URL / deep link | FULL (trivial) | n/a | Alt-ALM synthesizes its own routes |
| Bulk Update Selected | FULL | PUT ;type=collection | |
| Status workflow transitions | PARTIAL | PUT status | transition legality is project VBScript — not exposed as metadata; client-side re-derivation or accepted gap |
| Project Reports — download existing shared item | FULL (read-only) | **GET .../reports/{ID}?alt={mime}** (S24) | only for items already created+shared in UI |
| Create/design new Project Report | NOT-VIA-API | none | Report Wizard/templates UI-only |
| Entity Graphs (read existing) | PARTIAL/UNVERIFIED | maybe graphs/{ID}/layouts/{name} (TP, 404'd) | confirm via Swagger |
| Create/configure new Graph | NOT-VIA-API (fallback) | none | **fallback: render client-side from raw entity queries** (Progress/Summary/Trend/Age semantics per S13) |
| Live Analysis | NOT-VIA-API (fallback) | none | never server-persisted anyway; reimplement client-side |
| Excel Reports (standard SQL) | **structurally out of scope** | none | raw SQL against project DB → violates hard constraint, not a mere gap |
| Dashboards (view existing) | PARTIAL | depends on graph read (unverified) | reconstruct client-side if per-graph read confirmed |
| Create/edit Dashboard pages | NOT-VIA-API (fallback) | none | Alt-ALM's own dashboard layer + own layout store |
| Share Analysis Item public URL | FULL (read) | `?authKey={key}` on reports/graph URLs (S24) | unauthenticated read path; requires prior UI share |

## UNVERIFIED
- defect-links (snippet-only here; wave-1 agent 6 has primary confirmation).
- graphs/{ID}/layouts/{name} Tech Preview endpoint (404'd) → re-confirm via live Swagger /qcbin/api-doc/v2/.
- Single-instance PUT/DELETE .../defects/{id} (inferred from convention).
- Business-View Excel Reports retrievable via `reports` resource? Not confirmed.
- Canonical live resource list → GET /qcbin/rest/resource-list on sandbox.
- Information Panel inline editability (read-only preview vs quick-edit) unclear.
- Web Client Defects dialog-level parity (attachments-at-create, similar-check, bulk update) undocumented — desktop-sourced details are desktop-baseline only.

## Handoffs
1. API reconciliation: defect-links shape; single-instance verbs; whether a non-TP `graphs`/`dashboard`/`analysis-item` collection exists beyond `reports` → live Swagger / resource-list.
2. Workflow-script gap → ADR/risk-register entry (per-transition rules are project-customized, unpublishable).
3. **Excel-report SQL exclusion** → architecture must mark structurally out of scope (hard-constraint violation, not capability gap).
4. Web Client parity pass → mine Content/Web_Runner/ module-by-module.
5. Live probe: Share-Analysis-Item + REST reports/graph URL returns usable payloads; TP graph-layout endpoint liveness on target version.
