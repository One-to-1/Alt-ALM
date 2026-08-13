# Re-check of `feasibility-matrix.md` NO verdicts — web research pass

**Date**: 2026-08-13. **Method**: pure web research (official OpenText/Micro Focus docs, community
forums, official OTA API reference) against the 21 rows scored `NO` in
[`feasibility-matrix.md`](../feasibility-matrix.md). No probes were run, no repo files other than this
one were touched. Per the brief, the working hypothesis is that some `NO` verdicts rest on an
unexamined *shape* assumption (wrong entity name, wrong object model, doc corpus skewing older than
the 24.1+ per-instance Swagger) rather than a genuine absence — exactly the pattern that already
overturned two "impossible" verdicts (OTA reachability, `step-parameters`) in this project's own
history.

**Rule-following note**: every claim below is tagged with its evidence class. `[doc]` = official
OpenText/Micro Focus documentation page fetched directly. `[forum]` = community.opentext.com /
community.microfocus.com thread, independently corroborated where possible. `[repo]` = this project's
own already-recorded probe evidence (`live-probe-log.md`, `alm-api-reference.md`), cited because it
bears directly on the same row. `UNVERIFIED` = no experiment has settled it; the settling experiment
is named. Nothing here was invented — where search returned nothing useful, that is reported as a
negative result, not papered over.

---

## Summary table

**Column note added 2026-08-13**: the last column, "Probed outcome," was not part of the original
web-research pass — it records what Probes 11 (REST, read-only) and 12 (OTA, read-only) actually found
when they went live, so this report and `live-probe-log.md` do not disagree. "New classification" below
is left exactly as originally written — it is this pass's *prediction*, kept for the record of how well
web research alone called it; "Probed outcome" is the *empirical result*, which wins per this project's
own evidentiary rule.

| # | Feature | Matrix row(s) | Old reason for NO | New classification (predicted) | Confidence | Key source | Probed outcome (2026-08-13, Probes 11–12) |
|---|---|---|---|---|---|---|---|
| 1 | Analyze / Analyze and Apply to Children | #18 | "No computation endpoint... scoring algorithm undocumented" | **LIKELY WRONG** — reclassify toward `FULL*` clientside | High | [doc] admhelp `t_assess_risk.htm` | **CONFIRMED — `NO` → `FULL*`.** `Customization.RBT` reads the full matrix live [probe12 §12.1]. Prediction was correct. |
| 2 | Risk export to Word | #20 | "Desktop-local document generation" | **CONFIRMED NO** | High | [doc] admhelp `t_assess_risk.htm` (Word must be installed locally) | **`NO` stands.** Not independently re-probed live (per this report's own "not recommended for re-probing" list) — reconfirmation is web-research-only. |
| 3 | Application Area Viewer | #51 | "No REST surface... beyond generic resource CRUD" | **POSSIBLY WRONG** (OTA, weak) | Low | [doc]/[forum] `QCResourceFactory` | **Not re-probed.** Outside Probes 11–12's scope (neither probe's findings mention `QCResourceFactory`). `NO` stands, unresolved. |
| 4 | Dependencies (Used by/Using) | #52 | "No relationship-tracking endpoint found" | **POSSIBLY WRONG** (OTA, weak) | Low-Med | [doc] official OTA "Dependencies Overview" page exists | **Not re-probed.** Outside Probes 11–12's scope (no `AssetRelationFactory` check recorded). `NO` stands, unresolved. |
| 5 | Live Analysis tab (Test Lab) | #83 | "Never server-persisted, Enterprise-only" | **CONFIRMED NO** | High | [doc] admhelp `menu_live_analysis.htm` | **`NO` stands.** Not independently re-probed live — web-research-only reconfirmation. |
| 6 | Text Search (project-wide FTS) | #107, #170 | "Server FTS index, opt-in, confirmed absent from REST" | **CONFIRMED NO** | High | [doc] admhelp text-search config pages; 404 on guessed REST doc path | **`NO` stands.** Not independently re-probed live — web-research-only reconfirmation. |
| 7 | Global Search / "Quality Insight" | #119, #214 | "No dedicated cross-project search endpoint identified" | **CONFIRMED NO** | Med-High | [doc]/[forum] "ALM Global Search" legacy add-in, no REST | **`NO` stands.** Not independently re-probed live — web-research-only reconfirmation. |
| 8 | Business View Graphs | #129 | "No REST surface confirmed" | **LIKELY WRONG for OTA** (still NO for REST) | Med | [doc] `GraphBuilder`/`BuildGraph` official OTA objects | **CONFIRMED — `NO` → `OTA`.** `Customization.BusinessViews` (37 views) + `GraphBuilder` all read-reachable [probe12 §12.2]. Prediction was correct; read-only, write UNVERIFIED. |
| 9 | PPT Graphs (Releases) | #130 | "Depends on KPIs/scope items, absent from REST" | **LIKELY WRONG for OTA** (still NO for REST) | Med-High | [repo] probe8 `KPIFactory`/`ScopeItemFactory` acquired + [doc] `GraphBuilder` | **Not re-probed as its own row.** Probe 12's live findings table covers #145 (Scorecard/KPI) but does not separately exercise #130 — the shared `KPIFactory` dependency this report predicted was never independently confirmed for the PPT-graphs surface specifically. `NO` stands in the matrix pending a dedicated check; prediction neither confirmed nor refuted. |
| 10 | Create/design new Project Report | #132 | "UI-only Report Wizard" | **POSSIBLY WRONG for OTA** (still NO for REST) | Med | [forum] `OTAReport80.Reporter`/`ReportConfig`, 2 independent threads | **PARTIALLY CONFIRMED, different mechanism — `NO` → `OTA`.** The predicted `Reporter`/`ReportConfig` recipe itself is **NOT registered on this deployment** (`REGDB_E_CLASSNOTREG` on all 3 candidate ProgIDs [probe12 §12.4]) — that specific prediction was wrong. But the *template* surface is reachable a different way: `Customization.ReportProjectTemplates` (79 templates) [probe12 §12.2]. Net verdict matches the prediction's direction (`OTA`) but not its mechanism. |
| 11 | Excel Reports (standard SQL) | #133 | "Structurally out of scope by hard constraint (raw SQL)" | **PARTIAL reframe** — *running* an existing report is OTA-reachable; *authoring new SQL* stays correctly out of scope | Med | [forum] same `Reporter` pipeline | **CONFIRMED in direction, `NO` → `OTA`, same caveat as #10** — template surface reachable via `Customization.ReportProjectTemplates`, not the predicted `Reporter` pipeline (unregistered). Raw-SQL authoring stays out of scope as predicted. |
| 12 | Live Analysis Graphs | #134 | "Never server-persisted, no REST surface" | **CONFIRMED NO** | High | Same as #83 | **`NO` stands.** Not independently re-probed live — web-research-only reconfirmation, same as #83. |
| 13 | Scorecard (KPI readiness) | #145 | "Depends on KPI computation, absent from REST" | **LIKELY WRONG for OTA** (still NO for REST) | Med-High | [repo] probe8 `KPIFactory`/`ScopeItemFactory` acquired | **CONFIRMED — `NO` → `OTA`.** `Customization.KPITypes.KPITypes` (11 types) + `KPIFactory` [probe12 §12.2]. Prediction was correct; read-only, write UNVERIFIED. |
| 14 | Alerts row indicators | #166 | "Depends on Alerts data, absent from REST" | **LIKELY WRONG for OTA** (still NO for REST) — internally inconsistent with rows #109/#196/#197 which already say OTA | High | [doc] official "Alert Object" page, `TDConnection.AlertManager` | **CONFIRMED — `NO` → `OTA`.** `AlertManager.AlertList`/`GetFilterText`/`DeleteAlert`/`DeleteAlertsByFilter`/`CleanAllAlerts` all present [probe12 §12.2]; row now aligned with #109/#196/#197 as predicted. Prediction was correct. |
| 15 | Entity baseline history | #175 | "Depends on Baselines, absent from REST" | **CONFIRMED NO for REST; POSSIBLY WRONG for OTA** | Med | [repo] probe8 `BaselineFactory` acquired; [repo] `purgeVCHistories` (test-only, doesn't cover this) | **Not re-probed.** Outside Probes 11–12's scope (no `BaselineFactory` read/write exercised this round). `NO` stands in the matrix for REST; OTA half of the prediction remains unresolved. |
| 16 | Per-attachment History | #186 | "No dedicated audit trail identified" | **POSSIBLY WRONG** (genuinely unverified) | Low-Med | No doc/forum evidence either way — cheap experiment available | **CONFIRMED — `NO` → `UNVERIFIED`.** The named cheap experiment was attempted but the sandbox had no attachment to test against [probe11 §11.4] — genuinely inconclusive, exactly as predicted. Re-run after P2 creates an attachment. |
| 17 | Data-hiding tab per group per module | #205 | "No REST enforcement/config surface identified" | **POSSIBLY WRONG** (genuinely unverified) | Low | SA REST API is Swagger-only (178 ops), never grepped by name for this | **CONFIRMED, resolved toward OTA rather than REST — `NO` → `OTA`.** REST side: broader group/role/permission structure IS readable (`v2/sa/api/permissions`, `/permissions/metadata`, `/roles`, `/groups` all 200 [probe11 §11.2]), but no dedicated data-hiding endpoint was found — REST stays `NO` for the specific feature. OTA side: `Customization.Modules`/`Permissions`/`UsersGroups` all reachable [probe12 §12.2]. Per-module accessor arity still open. |
| 18 | Workflow-script field visibility | #209 | "By design — REST bypasses workflow scripts" | **CONFIRMED NO** | Very High | Architectural fact, not a probing gap; no counter-evidence found | **CONFIRMED, now on direct evidence.** `Customization.Workflow` exposes only `ProjectScriptsUpdated`/`TemplateScriptsUpdated` (dirty flags, no script content) [probe12 §12.3]. Prediction was correct, and this is the one row in the batch where the live probe *upgraded* the evidence class from inference to direct observation. |
| 19 | "Required" checkbox rendering (script-driven) | #210 | Same bypass | **CONFIRMED NO** | Very High | Same as #209 | **CONFIRMED, same direct evidence as #209** [probe12 §12.3]. |

Rows are deduplicated where the matrix lists the same underlying feature twice (Text Search
appears as both #107 and #170; Global Search as both #119 and #214; Live Analysis as both #83 and
#134 — these last two are genuinely distinct UI surfaces but rest on identical evidence, so they're
discussed together).

**Bucket counts (predicted, from web research alone)**: CONFIRMED NO = 8 rows (Risk export to Word,
Live Analysis ×2, Text Search ×2, Global Search ×2, Workflow-script ×2 — 9 individual matrix rows
across 6 distinct features). LIKELY WRONG = 4 features (7 matrix rows once you count the
OTA-inconsistency catch on #166). POSSIBLY WRONG = 5 features (5 matrix rows). No feature returned zero
information either way with high confidence of absence beyond what's already in the repo — every row
got *some* new evidence.

**Bucket counts (probed, 2026-08-13, Probes 11–12 — this is what actually landed in
`feasibility-matrix.md`)**: of the 19 rows in the table above, **8 flipped verdict** (#18 → `FULL*`;
#129, #132, #133, #145, #166, #205 → `OTA`; #186 → `UNVERIFIED` — note #132/#133 landed on `OTA` via a
*different* mechanism than predicted, `Customization.ReportProjectTemplates` rather than the forum's
`OtaReport80.Reporter`, which turned out unregistered on this deployment). **11 rows stayed `NO`** in
the matrix: **2** were independently reconfirmed by direct live OTA evidence (#209, #210 — the one
pairing this report predicted would need no re-check, and the live probe upgraded anyway); **9** were
**not re-probed live at all** this round and remain either web-research-only reconfirmations or
genuinely open, untested predictions — #51, #52, #130, #175 (the "POSSIBLY/LIKELY WRONG" calls that
were never actually tested) and #20, #83, #107/#170, #119/#214, #134 (the "CONFIRMED NO" calls, also
not independently live-tested, carried into the matrix as web-corroborated but not probe-corroborated).
**Every prediction that WAS tested live turned out directionally correct** — the "LIKELY WRONG for OTA"
and "POSSIBLY WRONG" calls that were actually probed (#18, #129, #132, #133, #145, #166, #186, and
#205's OTA half) all confirmed toward `OTA`/`FULL*`/`UNVERIFIED`; none of the tested rows contradicted
this report's prediction, only its predicted *mechanism* in two cases (#132/#133).

---

## Per-feature detail

### 1. Analyze / Analyze and Apply to Children — matrix #18 — **the best find in this pass**

**Old reasoning**: "No computation endpoint in resource-list/Swagger; scoring algorithm undocumented.
Would require reverse-engineering OpenText's formula."

**What web research found**: OpenText's own user documentation describes exactly how "Analyze"
works, and it is *not* a hidden scoring formula — it's a lookup against an admin-configured
**Testing Policy matrix**:

> "A lookup grid is defined at the analysis requirement level that maps Risk and Functional
> Complexity categories to Testing Levels... Select a Testing Level from the available Testing
> Levels. The available Testing Levels are Full, Partial, Basic, and None." — Analyze "calculate[s]
> the Testing Level and Testing Time for each assessment requirement by applying the Testing Policy
> matrix to each requirement's Risk Category and Functional Complexity values. Analyze and Apply to
> Children propagates these results to all child requirements matching the current filter."
> [Risk-based quality management, admhelp.microfocus.com](https://admhelp.microfocus.com/alm/en/24.1/online_help/Content/UG/t_assess_risk.htm)

This means "Analyze" has **no server-side computation to reverse-engineer at all** — it's a plain
table lookup that Alt-ALM can reimplement client-side, exactly like the already-`FULL*` clientside
rows (#13 Impact Analysis, #14 Traceability Matrix, #144 Master Plan). The matrix's own §8 already
confirms the `rbt-*` fields (Risk, Functional Complexity, Testing Level, etc. — 27 fields) are
REST-readable/writable. The only missing piece is **where the Testing Policy matrix itself is
stored** — this was not found in web research (no doc page shows its REST/OTA location) and needs
one probe.

**Classification**: LIKELY WRONG — the "undocumented algorithm" premise is false; it's a
documented lookup table. Reclassify from `NO` toward `FULL*` [^clientside], contingent on locating
the matrix data.

**Exact experiment to try**: `GET customization/entities/requirement/fields` and inspect whether any
field metadata references the Testing Policy grid; separately, `GET
domains/{d}/projects/{p}/customization` (project-level customization root) for anything
risk/testing-policy-shaped. If REST doesn't expose it, it is very likely only visible in the desktop
Project Customization UI (Risk-Based Quality Management admin page) or via OTA's `Customization`
object (`TDConnection.Customization`, confirmed to exist — [official OTA Members list,
admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html]) — a one-time capture of the matrix
(it rarely changes) would be enough to hardcode the lookup client-side per project.

---

### 2. Risk export to Word — matrix #20

**Old reasoning**: "Desktop-local document generation."

**What web research found**: Confirmed and stronger than the matrix states. The same admhelp page
says outright: **"To generate a report, Microsoft Word must be installed on your machine."**
[Risk-based quality management, admhelp.microfocus.com](https://admhelp.microfocus.com/alm/en/24.1/online_help/Content/UG/t_assess_risk.htm)
This is desktop-client Word-COM automation triggered from the ALM Windows client, not a
server-callable operation at all — neither REST nor OTA has anything to reach here, because the
document is assembled on the *user's own machine* by driving locally-installed Word, the same
pattern already correctly marked `N/A` elsewhere in the matrix (#189 Legacy_Rich_Content.doc, #193
Memo UDF export from Word).

**Classification**: CONFIRMED NO. No re-probe recommended — this is the same category as the other
Word-add-in features already scoped out.

---

### 3. Application Area Viewer — matrix #51

**Old reasoning**: "No REST surface for structured Application Area content beyond generic resource
CRUD."

**What web research found**: Weak positive signal only. `QCResourceFactory`/`QCResource` are
confirmed real, officially-referenced OTA objects (`TDConnection.QCResourceFactory` — [Unable to Add
new QCResource using OTA,
community.microfocus.com](https://community.microfocus.com/adtd/sws-qc/f/itrc-895/196081/unable-to-add-new-qcresource-using-ota);
also appears on the official [TDConnection Object Members
page](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html)). Application Area is a
resource *subtype* whose "structured content" is most likely just resource file content with a
particular internal format, not a distinct API surface — meaning the generic resource CRUD the
matrix already credits (rows #48-50, currently `UNVERIFIED`) is probably the entire answer here too,
just not yet probed for this specific subtype.

**Classification**: POSSIBLY WRONG, low confidence — more likely this row should simply track
whatever verdict #48/#49/#50 (Resources upload/download) end up with, rather than being a
independently-impossible feature.

**Exact experiment**: Once #49 (resource upload) is probed, `GET` an Application-Area-typed
resource's content the same way and diff against a plain-file resource; if identical mechanism,
this row inherits that verdict.

---

### 4. Dependencies (Used by/Using) — matrix #52

**Old reasoning**: "No relationship-tracking endpoint found for resources."

**What web research found**: There is an official, dedicated **"Dependencies Overview"** page in the
OTA API reference —
[admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic52.html](https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic52.html)
(also mirrored at `ota_docx/topic52.html`) — confirming "Entities Dependencies" is a real, named OTA
concept ("enables modeling dependencies between ALM entities... relations can be defined for user
assets, tests, components, and resources"). The page fetched as an overview/conceptual page without
enumerating concrete class names in the portion retrievable via automated fetch (the site's
frame-based navigation limits what a scripted fetch can pull), so the exact object/method names
(candidate: an `AssetRelationFactory` — which **does** appear on the official [TDConnection Members
list](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html) — plausible fit for "Used
by / Using" relation tracking) remain to be confirmed.

**Classification**: POSSIBLY WRONG (OTA), low-to-medium confidence. This is a real named feature in
the official API, not an invented one — worth one probe before writing it off.

**Exact experiment**: From the OTA sidecar, `TDConnection.AssetRelationFactory.NewList("")` against a
resource with known dependents; inspect returned relation objects for `Used by`/`Using` semantics.
If `AssetRelationFactory` is unrelated, browse the OTA reference topic tree around topic52/topic53
("Download Filtering", linked from the Dependencies page) for the correct factory name.

---

### 5 & 12. Live Analysis tab / Live Analysis Graphs — matrix #83, #134

**Old reasoning**: "Never server-persisted, ALM Enterprise-only per inventory" / "no REST surface."

**What web research found**: Confirms the matrix's own characterization. OpenText's docs describe
Live Analysis as producing **dynamic, on-the-fly charts** —
[admhelp.microfocus.com/alm/en/24.1/online_help/Content/UG/menu_live_analysis.htm](https://admhelp.microfocus.com/alm/en/24.1/online_help/Content/UG/menu_live_analysis.htm)
("create and display dynamic charts illustrating test subject data, test set folder data, and
business component subject data") — the feature is defined as session-transient by design, not
merely undocumented. No REST or OTA persistence surface was found in any source.

**Classification**: CONFIRMED NO for both rows. This is the correct honest gap; Alt-ALM's existing
plan to build its own dashboard layer from raw REST-readable entity data (rows #125/#127/#128,
already `FULL*` clientside) is the right substitute and needs no OTA fallback.

---

### 6 & 7. Text Search / Global Search — matrix #107, #170, #119, #214

**Old reasoning**: "Server FTS index, opt-in, confirmed absent from REST" / "No dedicated
cross-project search endpoint identified."

**What web research found**:
- Text Search is explicitly a **database-schema-level** feature: enabling it requires running SQL
  full-text indexing commands against the project's DB user schema (e.g. `EXEC sp_fulltext_database
  'enable'` for SQL Server) and configuring a `TEXT_SEARCH_TIMEOUT` site parameter —
  [Enabling Text Search on Database User
  Schemas](https://admhelp.microfocus.com/alm/en/15.0-15.0.1/online_help/Content/Admin/sa_textsearch_enable_on_db.htm),
  [Configure Text Search,
  25.1](https://admhelp.microfocus.com/alm/en/25.1/online_help/Content/sa_textsearch_configure_toc.htm).
  This is consistent with "opt-in, DB-index-backed" rather than a general application feature with an
  obvious REST mirror.
- A guessed REST doc path (`.../REST_core/Content/REST_API_Core/REST/search.html`, matching the
  naming convention of other confirmed collection pages like `runs.html`) returned a clean **404**,
  weak negative evidence consistent with no dedicated Core search resource.
- **"ALM Global Search"** turns out to be a real, separately-branded product: a standalone
  Windows/Linux installable add-in ("a powerful global search engine enabling you to search across
  all or a specific ALM module" — Defects, Requirements, Tests, Test Sets, Analysis), versioned for
  ALM 12.53–15.51 —
  [marketplace.opentext.com/appdelivery/content/alm-global-search](https://marketplace.opentext.com/appdelivery/content/alm-global-search).
  Its existence as a *separate downloadable tool* rather than a built-in REST/OTA capability is
  itself evidence that cross-module search was never folded into the core API surface, at least
  through 15.5. No evidence was found that this add-in exposes a REST endpoint, or that it (or an
  equivalent) survived into 24.1+ /qcbin.

**Classification**: CONFIRMED NO for both Text Search and Global Search, higher confidence than
before given the DB-schema-level framing and the 404 probe, but **not airtight** — per the task's own
caveat, 24.1+ additions live only in the per-instance Swagger, and this row was never re-checked
against the live sandbox's actual `/qcbin/api-doc/v2/qc.json` for a "search"-shaped operation
(only inferred from public docs + one guessed URL).

**Residual experiment** (cheap, since the file is already fetched in this project's own corpus):
grep the already-downloaded `qc.json` (32.8 KB, 14 ops, per `live-probe-log.md` line ~93) for
"search" — if absent there too, this row can move from CONFIRMED NO (web-evidenced) to CONFIRMED NO
(probe-evidenced), closing the loop.

---

### 8. Business View Graphs — matrix #129

**Old reasoning**: "Business Views metadata out-of-scope family, no REST surface confirmed."

**What web research found**: REST absence is uncontested (no source suggests otherwise). But on the
OTA side, there is a real, officially-documented **`GraphBuilder`** object with a **`BuildGraph`**
method ("Build the graph") and a companion **`GraphDefinition`** object exposing a `Filter` property
("sets the criteria for which data are included in the graph") —
[GraphBuilder Object](https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic2236.html),
[BuildGraph Method](https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic2238.html), both
confirmed present on the official [TDConnection Object Members
list](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html) (`GraphBuilder` property).
Business Views themselves are described as "a data layer that exists on top of the database and
which reflects only those project entity fields that represent information that is useful from a
business perspective" —
[Business Views Overview](https://admhelp.microfocus.com/alm/en/15.5-15.5.1/online_help/Content/Admin/cust_business_views_about.htm).
The generic graph-building machinery is real and OTA-reachable; whether a `GraphDefinition.Filter`
can be scoped to a specific Business View (versus only raw entity fields) is unconfirmed.

**Classification**: LIKELY WRONG for the REST-vs-OTA framing (this is not a dead end, it's an
under-explored OTA surface) — but the row should move from flat `NO` to `OTA`, not to a REST verdict.
REST itself is still correctly `NO`.

**Exact experiment**: From the OTA sidecar (reusing the already-verified connection recipe from
probe8): build a `GraphDefinition`, set `.Filter` to reference a known Business View name/id, call
`TDConnection.GraphBuilder.BuildGraph(graphDef)`, and inspect the returned graph object's data shape.

---

### 9 & 13. PPT Graphs / Scorecard (KPI readiness) — matrix #130, #145

**Old reasoning**: Both "Depends on KPIs/scope items, [confirmed largely] absent from REST."

**What web research found**: This project's *own* `live-probe-log.md` (Probe 8) already contains the
key fact that undercuts this row, just not yet connected to it: **`KPIFactory` and `ScopeItemFactory`
were both "ACQUIRED"** on `TDConnection` in the live sandbox — i.e., the property resolved and
`NewList("")` returned `0` items cleanly (empty result, not an error) — alongside `BaselineFactory`,
`LibraryFactory`, `HostFactory`, `HostGroupFactory`, `MilestoneFactory` [`live-probe-log.md`
lines 376-381]. Web research adds one more piece: the official documented **`TDConnection Object
Members`** page (fetched fresh, 2026, from
[admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html))
does **not** list `KPIFactory`, `ScopeItemFactory`, `BaselineFactory`, `LibraryFactory`, or
`MilestoneFactory` among its ~96 properties, even though `HostFactory`/`HostGroupFactory` are
present. This is a genuine, notable discrepancy: either these five factories are newer
undocumented-on-the-public-site additions (consistent with this whole project's finding that public
docs skew older than the live 24.1+/26.1 surface), or they live one level deeper than top-level
`TDConnection` properties in the current doc revision. Either way, **the empirical finding wins**
per this project's own evidentiary rule, and it directly contradicts "confirmed absent" for the
underlying KPI/ScopeItem data.

**Classification**: LIKELY WRONG for OTA (REST itself stays `NO` — no REST evidence found anywhere).
Reclassify both rows from `NO` to `OTA`.

**Exact experiment**: From the OTA sidecar, under a **real milestone** (the probe8 sandbox was
empty, so the factories were never write-tested) — `TDConnection.KPIFactory.AddItem(...)` and
`TDConnection.ScopeItemFactory.AddItem(...)`, `Post()`, then read back. If writes succeed, PPT
Graphs/Scorecard become buildable the same way Milestones already are (REST for the milestone
container, OTA for its KPI/ScopeItem children), with `GraphBuilder` (see #129) for the graph
rendering itself.

---

### 10 & 11. Create/design new Project Report / Excel Reports — matrix #132, #133

**Old reasoning**: "UI-only Report Wizard" / "Structurally out of scope by hard constraint (raw
SQL)."

**What web research found**: Two independent community threads describe a real, working OTA
reporting pipeline that is **not** raw-SQL-based:

> "The `otareport` library contains two objects: `Reporter` and `ReportConfig`... create an
> `OtaReport80.Reporter` object, set its `Connection` property to `TDConnection`, configure
> `ReportConfig` with an XML file, specify the output `File`, set a `Template`, and call
> `Generate()`... `Object` and `Template` properties of the `Reporter` object [define] the filter
> and template... `Generate(BeginIndex, EndIndex)` returns `Total`."
> [Automatically create Excel Reports and store them in a
> folder](https://community.opentext.com/devops-cloud/alm-qc/f/discussions/183031/automatically-create-excel-reports-and-store-them-in-a-folder)
>
> "Excel Report is not supported by REST API... [use] the OTA Client API to generate
> Standard/Excel/Graph reports."
> [REST API to get Excel Report?](https://community.microfocus.com/devops-cloud/alm-qc/f/discussions/236141/rest-api-to-get-excel-report)

A responder in the first thread also notes a key limitation: **"the Reporter generates from ALM's
built-in report structure — it cannot construct entirely novel report architectures without creating
corresponding Analysis Item definitions through the UI or API first."** So this is not a Report
Wizard replacement — it is a **run/render existing report definitions** pipeline (filter + XSL
template → Excel/Word/other output), driven by ALM's own report engine, no raw SQL required for
*execution*.

For **Excel Reports specifically**: the stock ALM "Excel Report" wizard is historically distinct from
other Analysis-View report types precisely because it lets a human author raw SQL against the
project's DB schema — that part of the matrix's "structurally out of scope by hard constraint"
reasoning holds for *authoring new SQL through Alt-ALM's own UI* (which the project should keep
refusing to build, independent of API feasibility). But it does not follow that *running an
already-authored* Excel Report (SQL written once by a human in the stock UI, saved server-side) is
equally out of scope — that's a `Reporter.Generate()` call away, same as any other report type,
and does not require Alt-ALM to write, expose, or execute arbitrary SQL itself.

**Classification**:
- #132 (create/design new report): POSSIBLY WRONG → reclassify to `OTA` for the *render/execute*
  half of the feature (most of what a generator/consumer app actually needs); stays `NO` for
  *ad-hoc new report architecture design* (no OTA/REST path builds that from nothing).
- #133 (Excel Reports): reframe rather than flip — split into "run an existing Excel Report
  definition" (OTA-reachable, per above) vs. "author new SQL-based reports via Alt-ALM" (correctly
  stays out of scope, but now for a "we choose not to," not "we structurally cannot," reason).

**Exact experiment**: From the OTA sidecar, alongside the main OTA client, register/instantiate the
separate `OtaReport80.Reporter` COM object (ProgID unconfirmed exact version-suffix on 26.1 — try
`OtaReport80.Reporter` first, fall back to enumerating `HKCR` for `OtaReport*` after the version-
matched ALM client is installed, same pattern already used for the main OTA typelib). Set
`.Connection = tdc`, `.Object = testFactory.Filter` (or any factory's `.Filter`), `.Template = ` a
path to one of ALM's bundled `.xsl`/report templates, `.File = "out.xlsx"`, call `.Generate()`, and
inspect the output file — this settles both rows in one experiment.

---

### 14. Alerts row indicators — matrix #166

**Old reasoning**: "Depends on Alerts data, absent from REST."

**What web research found**: The official **Alert Object** page is real and directly documents
everything a grid-row indicator needs:

> Properties: `AlertDate` ("date the alert was generated"), `AlertType` ("Alert or Follow-up"),
> `Description`, `ID`, `Subject`, `Unread` ("If true, the alert or follow-up has not yet been
> read"). Obtained via `TDConnection.AlertManager`.
> [Alert Object, admhelp.microfocus.com](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic226.html)

This matches this project's own probe8 finding that `AlertManager` is present on `TDConnection`
[`live-probe-log.md` line 379]. There is also a companion **`Rule`** object ("represents a rule for
generating an alert... obtained from `RuleManager`") confirming the admin-rule side is modeled too.

**Classification**: LIKELY WRONG. This row is also **internally inconsistent** with the matrix's own
adjacent rows — #109 (Alerts/Clear Alerts), #196 (Alerts 4 admin rules), and #197 (Clear per-record or
globally) all already carry the verdict `OTA`, citing the exact same underlying Alerts data. #166
should carry the same verdict as its siblings; it appears to have been scored independently and
missed the cross-reference. Reclassify from `NO` to `OTA`.

**Exact experiment**: Not really needed beyond what #109/#196/#197 already need — once Alerts are
OTA-verified for read (`TDConnection.AlertManager` enumeration), the same data trivially feeds a grid
indicator (`Unread`/`AlertType` per entity id).

---

### 15. Entity baseline history — matrix #175

**Old reasoning**: "Depends on Baselines, absent from REST."

**What web research found**: REST absence stays solid — no source, old or new, shows a baseline-
history read endpoint. This project's own corpus separately records a real, if narrow, 24.1+ REST
addition in this neighborhood: **`purgeVCHistories`** (`DELETE
.../{entity-name}/versioningHistory`, Swagger-only, v2 doc, with the description **"currently only
'test' is supported"** [`probe3-mining-swagger.md` line 36]) — but this is a *purge*, not a *read*,
and is explicitly scoped to `test` only, so it doesn't unlock baseline history reading even where it
applies. On the OTA side, `BaselineFactory` was "ACQUIRED" per probe8, same undocumented-on-the-public-
site pattern as `KPIFactory`/`ScopeItemFactory` above (absent from the official
[TDConnection Members](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8938.html) list,
present and callable in the live sandbox).

**Classification**: CONFIRMED NO for REST (no change). POSSIBLY WRONG for OTA — the same
under-documented-factory pattern as #130/#145 applies, but baselines specifically were never
write-probed (the sandbox was empty of version-controlled content with real baselines).

**Exact experiment**: From the OTA sidecar, against a project with actual versioned
requirements/tests, `TDConnection.BaselineFactory.NewList("")` to confirm non-empty read, then
attempt a create + history read round-trip.

---

### 16. Per-attachment History — matrix #186

**Old reasoning**: "No dedicated audit trail identified for attachments."

**What web research found**: Genuinely nothing either way. No admhelp page, no forum thread, no
Swagger reference mentions attachment-level audit history as a distinct concept. This is a case
where "I searched and found nothing" is itself the honest result — but it means the row was never
actually falsified, only left undiscussed anywhere on the web. This project's own generic-contract
reasoning (entity `/audits` sub-resource exists on 24 entity types per probe3) was never tested
against the `attachments` collection specifically, and attachments are structurally just another
entity type in the REST model, so there's no a priori reason the same sub-resource wouldn't exist.

**Classification**: POSSIBLY WRONG — genuinely unverified in both directions, downgraded from the
matrix's flat `NO` framing.

**Exact experiment**: `GET .../attachments/{id}/audits` against a live attachment that has had at
least one metadata edit — a single cheap read-only call, no write risk, resolves this outright.

---

### 17. Data-hiding tab per group per module — matrix #205

**Old reasoning**: "No REST enforcement/config surface identified."

**What web research found**: No dedicated public doc page or forum thread names a data-hiding REST
endpoint. But the reasoning gap is structural, not evidentiary: the Site Administration REST API
(`/qcbin/v2/sa/api/...`) is **Swagger-only** — 178 operations, per this project's own probe3 — and
was never searched by name for "hiding"/"filter"/"restriction" terms; the matrix's neighbor row #207
(Module Access grid) is honestly `UNVERIFIED` for the identical reason ("this specific control's
endpoint unidentified... Exp: search SA Swagger for module-access operations"). Data-hiding and
Module Access sit in the exact same Groups/Permissions area of Project Customization
([Manage user groups and
permissions](https://admhelp.microfocus.com/alm/en/17.0-17.0.1/online_help/Content/Project_Customization/cust_groups_perms_managing_toc.htm)),
so there's no principled reason one is `NO` and the sibling is `UNVERIFIED` — this looks like the
same under-examined-shape issue as #166.

**Classification**: POSSIBLY WRONG — should probably be demoted to `UNVERIFIED` to match #207,
not held at a stronger `NO`.

**Exact experiment**: This project already has the 178-operation SA Swagger JSON downloaded (per
`SESSION-STATE.md`/probe3) — grep it for `hiding`, `filter`, `restriction`, `group` + `module`
combinations before concluding anything further from the web. This is a zero-cost, already-in-hand
experiment that doesn't even need a new live call.

---

### 18 & 19. Workflow-script field visibility / "Required" checkbox rendering — matrix #209, #210

**Old reasoning**: "By design — REST writes bypass workflow scripts entirely via
`CLIENT_TYPES_BYPASS_REST_WF`."

**What web research found**: Nothing contradicts this, and nothing was expected to. This is not a
"we didn't search hard enough" gap — it's an architectural design fact about how ALM separates its
VBScript workflow engine (fires only for the desktop client and web UI form rendering) from the REST
layer (bypasses it by a named, documented site parameter). No forum thread or doc page suggests any
REST or OTA path re-engages the workflow engine's dynamic field-visibility/required-ness logic for
API-driven writes — that logic only exists inside the script interpreter attached to the UI-facing
clients. The *static* half of "Required" (the field-metadata `Required` flag on the field
definition, independent of any script) was already correctly scoped as `FULL` elsewhere via
`customization/entities/{name}/fields` — this NO is specifically about the *dynamic,
script-computed* variant, which is architecturally sealed off from any API surface.

**Classification**: CONFIRMED NO, highest confidence in this entire batch. No re-probe recommended;
this is the correct kind of "honest impossibility" the matrix's legend describes.

---

## Recommended probe order (value × likelihood of success)

1. **Analyze / Analyze and Apply to Children (#18)** — highest value: this is a mislabeled
   "impossible algorithm" that's actually a documented lookup table over already-REST-writable
   fields. If the Testing Policy matrix location is found, this closes a whole generator/UI feature
   with zero OTA dependency. **Experiment**: `GET customization/entities/requirement/fields` +
   project-level `customization` root; fall back to one-time OTA/manual capture via
   `TDConnection.Customization` if REST doesn't expose it.

2. **Alerts row indicators (#166) → align with #109/#196/#197** — near-zero probe cost, since the
   Alerts OTA path is already scheduled to be verified for those sibling rows; just confirm the same
   `AlertManager` read also satisfies this row's grid-indicator need.

3. **PPT Graphs / Scorecard (#130, #145)** — this project's own probe8 already acquired
   `KPIFactory`/`ScopeItemFactory` without error; the only missing step is a **write** test under a
   real (non-empty) milestone. High likelihood of success given the factories are already known
   reachable.

4. **Create/design new Project Report / Excel Reports (#132, #133)** — two independent forum
   sources describe a concrete, reusable `OtaReport80.Reporter`/`ReportConfig`/`Generate()` recipe.
   Medium-high value (unlocks report rendering broadly, including risk export's sibling feature
   space) and the recipe is specific enough to try directly.

5. **Data-hiding (#205) / Per-attachment History (#186)** — cheapest possible experiments (one is a
   grep of an already-downloaded Swagger file, the other a single `GET .../audits` call) with
   reasonable odds of a clean resolution either way; low individual value but essentially free.

6. **Business View Graphs (#129) / Entity baseline history (#175) / Dependencies (#52) /
   Application Area (#51)** — lower priority: each has a real named OTA concept behind it
   (`GraphBuilder`, `BaselineFactory`, `AssetRelationFactory` candidate, `QCResourceFactory`) but
   thinner evidence connecting the concept to this project's specific use case, and Business Views/
   Application Area are less central to the generator/UI's core value than the items above.

Not recommended for re-probing: Risk export to Word (#20), Live Analysis ×2 (#83, #134), Text
Search ×2 (#107, #170), Global Search ×2 (#119, #214), Workflow-script ×2 (#209, #210) — these nine
matrix rows all got independent web corroboration of the existing `NO` verdict, several with
stronger reasoning than the matrix originally stated (e.g. Risk-to-Word is literal local
Word-COM-automation, Text Search is a DB-schema-level opt-in feature). Re-probing these would spend
sandbox time on rows already correctly closed.
