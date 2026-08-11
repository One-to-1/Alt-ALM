# Wave 1 / Agent 9 — Version lineage, per-version REST changes, edition differences (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

| # | URL | Product / Version | Primary / Secondary |
|---|---|---|---|
| 1 | admhelp.microfocus.com/alm/en/**24.1**/online_help/Content/What_New/wn_alm_latest.htm | ALM 24.1 P1 What's New | Primary (OpenText ADM Help Center) |
| 2 | admhelp.microfocus.com/alm/en/**26.1**/online_help/Content/What_New/wn_alm_latest.htm | ALM 26.1 P1 What's New | Primary |
| 3 | admhelp.microfocus.com/alm/en/**25.1**/online_help/Content/What_New/wn_alm_latest.htm | ALM 25.1 P1 What's New | Primary |
| 4 | admhelp.microfocus.com/alm/en/**17.0-17.0.1**/online_help/Content/What_New/wn_alm_latest.htm | ALM 17.0/17.0.1 What's New | Primary |
| 5 | admhelp.microfocus.com/alm/en/**25.1**/online_help/Content/What_New/wn_alm_17.0.1.htm | "What's New in 17.0.1" (archived topic) | Primary |
| 6 | admhelp.microfocus.com/alm/en/**25.1**/online_help/Content/What_New/wn_alm_1250.htm | "What's New in 12.50" (archived topic) | Primary |
| 7 | admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/**WhatsNew.htm** | REST API (Core) changelog, spans 12.x–25.1 | Primary, version-rolling doc |
| 8 | admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Overview.html | REST API (Core) overview | Primary |
| 9 | admhelp.microfocus.com/alm/api_refs/**REST_deprecated**/Content/REST_API_Deprecated/Overview.htm | REST API (Deprecated) overview | Primary |
| 10 | admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/logout.html | REST endpoint example | Primary |
| 11 | admhelp.microfocus.com/alm/en/**24.1**/online_help/Content/api_rest_api_reference_core.htm | REST API Reference (Core), 24.1 | Primary |
| 12 | admhelp.microfocus.com/alm/en/**24.1**/online_help/Content/api_guides_main_page.htm | Developer Help hub, 24.1 | Primary |
| 13 | admhelp.microfocus.com/alm/en/**26.1**/online_help/Content/api_ota_reference.htm | OTA API reference, 26.1 | Primary |
| 14 | admhelp.microfocus.com/alm/en/**24.1**/online_help/Content/WebAdmin/api_key_mgmt.htm | API key management, 24.1 | Primary |
| 15 | admhelp.microfocus.com/documents/alm/alm-system-requirements/latest/alm-support-matrix.htm | ALM 26.1.x Support Matrix | Primary |
| 16 | community.opentext.com/devops-cloud/b/devops-blog/posts/**new-innovations-in-opentext-application-quality-management-25-1** | AQM 25.1 announcement (dated Jan 29, 2025) | Primary (official blog) |
| 17 | blogs.opentext.com/**opentext-application-quality-management-26-1-is-here** | AQM 26.1 announcement (dated Apr 13, 2026) | Primary (official blog) |
| 18 | community.opentext.com/devops-cloud/b/devops-blog/posts/**discover-the-power-of-alm-quality-center-in-the-new-15-0-release** | ALM/QC 15.0 announcement (dated Aug 19, 2019) | Primary |
| 19 | community.opentext.com/devops-cloud/b/devops-blog/posts/**maximize-the-value-of-your-alm-quality-center---get-ready-for-version-16-0** | ALM/QC 16.0 pre-announcement (dated Sep 24, 2021) | Primary |
| 20 | community.opentext.com/adtd/b/sws-alm/posts/**now-available-service-pack-1-for-alm-quality-center-16-0** | ALM/QC 16.0.1 (SP1) (dated Feb 23, 2022) | Primary |
| 21 | community.opentext.com/devops-cloud/b/devops-blog/posts/**what-s-new-in-opentext-alm-quality-center** | General ALM/QC overview | Primary |
| 22 | community.opentext.com/devops-cloud/aqm/f/discussions/**526348**/alm-versions-and-patches---roadmap | OpenText-staff statement on release cadence | Primary (staff reply) |
| 23 | community.opentext.com/devops-cloud/aqm/f/discussions/**514122**/alm-qc-12-60-will-end-committed-support-on-august-31-2022 | ALM/QC 12.60 EOL | Primary |
| 24 | community.opentext.com/devops-cloud/aqm/f/discussions/**28128**/qc-alm-support-tip-end-of-life... | QC 10.0x/11.0x/11.5x EOL precedent | Primary, historical |
| 25 | community.opentext.com/devops-cloud/aqm/f/discussions/**528100**/alm-on-premise-vs-alm-saas-comparision | On-prem vs SaaS | Primary (staff reply) |
| 26 | community.opentext.com/devops-cloud/aqm/f/discussions/**522715**/hp-alm-ota-api | OTA API / 64-bit, staff reply | Primary |
| 27 | community.opentext.com/devops-cloud/aqm/f/discussions/**514035**/using-the-rest-api-rest-vs-api | `/rest` vs `/api`, ALM 15.0.1 | Primary (community, staff-touched) |
| 28 | community.opentext.com/devops-cloud/aqm/f/discussions/**530148**/what-s-new-in-alm24---incremental-patching-mentioned | 24.1 patching model | Primary |
| 29 | community.opentext.com/devops-cloud/aqm/f/discussions/**193033**/quality-center-alm-version-history-and-release-dates | v10→v11/ALM naming origin | Primary (thin) |
| 30 | opentext.com/lifecycle ; microfocus.com/lifecycle | Support lifecycle tool (interactive) | Primary, but not query-able via fetch |
| 31 | en.wikipedia.org/wiki/OpenText_ALM | General lineage/ownership | **Secondary** — lead only |
| 32 | en.wikipedia.org/wiki/OpenText_Quality_Center | Infobox: "17.0.1 / April 3, 2023" | **Secondary** — lead only, not independently reproduced on a primary page |

---

## Findings

### 1. Release lineage

| Version | Date (best evidence) | Vendor at release | Notes |
|---|---|---|---|
| 12.00 / 12.01 / 12.20 / 12.21 | UNVERIFIED | HP | Referenced only in passing as "Web Runner versions" in the 12.50 What's New topic [6] |
| 12.50 | UNVERIFIED exact date | HP | Earliest version in the user's requested lineage |
| 12.53 / 12.55 | UNVERIFIED | HP | No dated primary source found |
| 12.60 | UNVERIFIED GA date; **committed support ended Aug 31, 2022** [23] | HP → Micro Focus (HPE sold its software business, incl. ALM/QC, to Micro Focus, completed Sep 2017) | |
| 15.0 | **August 19, 2019** [18] | Micro Focus | New RESTful **site-admin** API set introduced [18] |
| 15.5 / 15.5.1 | UNVERIFIED exact date | Micro Focus | 15.5.1 added test-execution and health-check REST endpoints [7] |
| 16.0 | Confirmed "now available" by **Sep 24, 2021** [19]; exact GA day UNVERIFIED | Micro Focus | Introduced mandatory `X-XSRF-TOKEN` header on non-GET REST calls [7] |
| 16.0.1 | SP1 announced **Feb 23, 2022** [20] | Micro Focus | |
| 17.0 | UNVERIFIED exact date (between Feb 2022 and Apr 2023) | Micro Focus | Deprecated two auth endpoints; case-insensitive order-by [4][7] |
| 17.0.1 | "April 3, 2023" per Wikipedia infobox [32] — **secondary, not independently confirmed on a primary OpenText page** | Micro Focus (OpenText's acquisition of Micro Focus closed **Jan 31, 2023**) | Added `/qcbin/v2/rest/is-authenticated` [5][7] |
| **[18.x–23.x]** | **No evidence found of any such ALM/Quality Center releases** | — | See discussion below |
| 24.1 | UNVERIFIED exact GA date (circumstantial: first half of 2024) | OpenText | Web Runner renamed **"Web Client"**; API-key management added to Site Admin; 6 new project-list REST endpoints [1][21][14] |
| 24.1 P1 | UNVERIFIED date | OpenText | `GT`/`LT` operator aliases for REST filters [7] |
| 25.1 | **January 29, 2025** [16] | OpenText | **Product renamed "OpenText Application Quality Management"** in this same announcement [16] |
| 26.1 | **April 13, 2026** [17] | OpenText | ALM Aviator/Workflow integration, bring-your-own-model AI |

**Is 24.1 the direct successor of 17.0.x?** The evidence strongly supports **yes**, though no single explicit vendor sentence says "24.1 succeeds 17.0.1 with nothing in between." Convergent evidence: (a) every ADM Help Center version-path segment across dozens of pages runs `…15.5-15.5.1 → 16.00-16.0.1 → 17.0-17.0.1 → 24.1 → 25.1 → 26.1`, with no `18.x`–`23.x` segment ever appearing; (b) official upgrade guidance moves customers from legacy versions (12.60, 15.x, 16.x, 17.x) straight to "the latest version of OpenText Application Quality Management"; (c) OpenText staff roadmap statement: post-acquisition cadence is "one major ALM version… with two patches per year" [22], consistent with 24.1 as a calendar-numbering reset (24=2024, 25=2025, 26=2026 — the rationale is inference, see UNVERIFIED #5). No product-history page surfaced an 18/19/20/21/22/23 ALM/QC release.

**Renames**: Mercury Interactive → **HP** → **Micro Focus** (deal closed September 2017) → **OpenText** (closed **January 31, 2023**) → **"OpenText Application Quality Management"** rebrand announced **January 29, 2025** in the 25.1 release blog [16]. The product is still commonly called "ALM/Quality Center" throughout documentation post-rebrand; `admhelp.microfocus.com` URLs and UI strings still say "ALM."

### 2. Support status

No queryable table of exact EOL dates for 17.0.x/24.1/25.1/26.1 was reachable — `opentext.com/lifecycle` / `microfocus.com/lifecycle` [30] are the correct interactive tools. Historical precedent: ~3–4 years Committed Support + 2 years Extended (QC 10.0x, ALM 11.5x precedents [24]); ALM/QC 12.60 committed support ended **August 31, 2022** [23]. A search snippet (lower confidence, UNVERIFIED) indicated 15.5.x committed support ending **Sep 30, 2024** and 16.0.x ending **Oct 31, 2024**. Cadence per OpenText staff (Caltha Zhuo): **one major version per year, two patches per year** [22]. Given 26.1 shipped April 13, 2026 [17] with a 26.1 P1 already referenced [2], 24.1/25.1/26.1 are almost certainly all within Current Maintenance today (Aug 2026); exact EOL dates UNVERIFIED.

### 3. REST-relevant changes per version

See the version → REST changes table below; principal source is the version-spanning REST API (Core) What's New changelog [7], cross-checked against per-version What's New topics [1][2][3][4][5][6].

### 4. The `/qcbin/rest/` vs `/qcbin/api/` split

Genuinely murkier than assumed:

- The Developer Help hub lists **two separate REST doc sets**: "**ALM REST API Reference (Core)**" (current, "wider coverage") and "**ALM REST API Reference (Deprecated)**" (kept for backward compatibility) [12][11][9]. Neither overview states its literal base path, so it is not confirmed that "Core" = `/qcbin/rest/` and "Deprecated" = `/qcbin/api/`.
- Countering "api = newer": ALM **17.0** What's New explicitly deprecates **`/qcbin/api/authentication/sign-in`** in favor of `/qcbin/authentication-point/alm-authenticate` [4][7] — an `/api/` endpoint being retired, not promoted.
- `/qcbin/authentication-point/...` is a third prefix family [10].
- 17.0.1's new capability landed at **`/qcbin/v2/rest/is-authenticated`** — forward evolution under `/rest`, not `/api` [5][7].
- Community (ALM 15.0.1): `/rest` "worked reliably" while `/api` often 403'd, **except** some metadata operations (e.g., `$metadata/fields`) only exist under `/api` [27]. Staff guidance: "use the formally released APIs per the Online Help."

**Net assessment**: `/qcbin/rest/` is the actively-developed Core surface where new versioned capability (`/v2/rest/...`) lands; `/qcbin/api/` is older/parallel (at least for auth) with at least one endpoint deprecated. No page states an overall deprecation plan for `/qcbin/api/` — UNVERIFIED; confirm per-endpoint against Core vs Deprecated doc trees.

### 5. Client evolution (prior art for our alternative UI)

- **17.0.1**: web client = **"Web Runner"** — manual test execution ("revamped Manual Runner"), coverage creation from requirements, coverage viewing from test plans [4].
- **24.1**: renamed **"ALM/QC Web Client"**, OpenText-branded. Covers: **Releases, Requirements, Test Plan, Test Lab, Test Runs (new), Defects**, plus dashboards; filtering/grouping/favorites added [21][1].
- **25.1**: Web Client gained **fully operational Dashboard module**, version control (multiple versions of requirements/tests), "Go to Requirement", cross-module entity sharing via URL/email, expanded Workflow/TDConnection scripting objects (release, cycle, test instance, test configuration) [3].
- **26.1**: added **Project Reports, Business-View Excel reports, requirement coverage inside Test Plan, version comparison, Libraries and Baselines, "Web Graphs" dashboard (Tech Preview), cross-module workflow querying, Requirements Traceability**, plus **Electronic Signature / controlled-workflow** (bulk approvals, role-based approval permissions, configurable re-authentication) [2].

Trajectory: steady module-by-module web-client expansion closing the gap with the Windows Desktop Client (which historically owned BPT administration and VBScript Workflow editing). No current side-by-side Desktop-vs-Web parity table was retrievable (FAQ page 444'd) — UNVERIFIED #9.

### 6. On-prem vs SaaS

- **API key management** on both models; **site admins** manage on-prem, **customer admins** on SaaS (tenant-scoped user lists) [14]. Client-ID + Secret model, runs under associated user's permissions, expiration, self-service gated by `APIKEY_SELF_SERVICE_LEVEL`, OData toggle per key [14].
- Some REST APIs are explicitly **tagged "SaaS only"** (exact list not enumerated — Handoff).
- 25.1 added SaaS "Contract Expiration Date" attribute; 17.0.1 added SaaS license-usage visibility [3][4].
- Staff framing of SaaS vs on-prem is qualitative (backups/upgrades/perimeters/performance), does not address REST differences, IP allowlisting, or version currency [25].
- **IP allowlisting** and **SaaS version-currency lead** — UNVERIFIED both ways.

### 7. OTA/COM API status

Still shipped and documented in **26.1**: "Open Test Architecture API Reference" states **"This reference contains information about COM-based API"** [13] — still COM, Windows-only, no cross-platform successor mentioned. The 24.1 Developer Help hub lists OTA alongside a **separate "Site Administration COM API Reference"** — *two* COM surfaces still shipping [12]. **No deprecation notice or EOL timeline for OTA/COM found anywhere.** Staff community reply documents an OTA 64-bit COM registration workaround (`common=true&comsurrogate=true`) with no retirement statement [26]. Lean on: **OTA is alive, COM/Windows-only, no documented sunset as of 26.1**; long-term roadmap commitment UNVERIFIED.

### 8. Documentation map

| Doc | Pattern | Verified for |
|---|---|---|
| Help Center root | `admhelp.microfocus.com/alm/en/<version>/online_help/Content/alm_intro.htm` | 25.1 (search index) |
| What's New | `.../online_help/Content/What_New/wn_alm_latest.htm` | 24.1, 25.1, 26.1, 17.0-17.0.1 |
| Developer Help hub | `.../online_help/Content/api_guides_main_page.htm` | 24.1 [12] |
| REST API Reference (Core) | `.../online_help/Content/api_rest_api_reference_core.htm` | 24.1, 25.1, 26.1 [11] |
| Site Admin REST API Reference | `.../online_help/Content/api_ALM_REST_Site_Admin.htm` | 24.1, 25.1, 26.1 |
| OTA API Reference | `.../online_help/Content/api_ota_reference.htm` | 26.1 [13] |
| REST (Core) version-pinned tree | `admhelp.microfocus.com/alm/api_refs/<version-range>/REST_core/...` | e.g. `15.5-15.5.1` |
| REST (Core) rolling tree + changelog | `admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/WhatsNew.htm` | spans 12.x→25.1 [7] |
| REST (Deprecated) | `admhelp.microfocus.com/alm/api_refs/REST_deprecated/Content/REST_API_Deprecated/Overview.htm` | [9] |
| Support Matrix | `admhelp.microfocus.com/documents/alm/alm-system-requirements/latest/alm-support-matrix.htm` | 26.1.x [15] |

**UNVERIFIED**: self-hosted Swagger reference at `http://<Server>:<port>/qcbin/api-doc/v2/` — plausible (17.0.1 What's New mentions "Enhanced Swagger Documentation" [4]) but not confirmed via a primary page; verify against the live instance. [NOTE from lead: the live sandbox probe should test this.]

---

## Version → REST changes table

| Version | REST API changes | Source |
|---|---|---|
| 12.50 | (no REST-specific items surfaced) | [6] |
| 15.0 | New RESTful API set for **site-admin** automation (users, project properties, site parameters) | [18] |
| 15.5.1 | Get test execution by ID; create test execution; `/qcbin/healthcheck` | [7] |
| 16.00 | `X-XSRF-TOKEN` required on all non-GET calls | [7] |
| 17.0 | Case-insensitive order-by; `alm-authenticate` gains JSON; **`authenticate` (GET) and `sign-in` deprecated** | [4][7] |
| 17.0.1 | `/qcbin/v2/rest/is-authenticated` (JSON+XML); enhanced Swagger docs with per-API required-permissions info | [5][7] |
| 24.1 | 6 new **project-list customization** endpoints (GET list/item, POST item, POST sub-item, PUT rename, DELETE); logout GET disabled by default (POST required); API-key management REST APIs in Site Admin | [1][21][7][14] |
| 24.1 P1 | Filter comparison operators: abbreviations (`GT`, `LT`, …) supported/recommended alongside symbols | [7] |
| 25.1 | **Purge versioning-history** API (by date or version, per entity type); `users/{name}` can include **group names**; SSO hybrid mode: local users can auth via REST and OTA with username/password | [7][3] |
| 26.1 / 26.1 P1 | No REST-specific bullet located (What's New skews Web Client/e-signature/Aviator); REST changelog last updated before 26.1 GA — gap, see UNVERIFIED #11 | [2][7] |

---

## UNVERIFIED

1. Exact GA dates for 12.50, 12.53, 12.55, 12.60, 15.5/15.5.1, 17.0, 24.1/24.1 P1. Confirm via OpenText Support Portal archives.
2. Exact committed-support/EOL dates for 17.0.1, 24.1, 25.1, 26.1. Confirm via opentext.com/lifecycle interactive tool.
3. 15.5.x EOL = Sep 30, 2024 and 16.0.x EOL = Oct 31, 2024 (snippet-only). Confirm via community version-currency wiki.
4. Full architectural distinction and roadmap for `/qcbin/rest/` vs `/qcbin/api/`. Confirm by diffing Core vs Deprecated doc trees endpoint-by-endpoint.
5. Whether "24.1/25.1/26.1" is an explicitly-stated calendar-year scheme (inferred only).
6. Swagger path `/qcbin/api-doc/v2/` — verify against live instance.
7. IP allowlisting differences on-prem vs SaaS — no source either way.
8. SaaS version currency vs on-prem — not addressed in any source reached.
9. Explicit Desktop-vs-Web feature parity list for 24.1/25.1/26.1 — canonical FAQ page unreachable (HTTP 444); also the "Comparing versions" checklist PDF 403/444'd.
10. Long-term OTA/COM deprecation roadmap — no sunset documented; could be "no planned deprecation" or undocumented.
11. 26.1 REST-specific changes — rolling changelog not yet updated for 26.1 at fetch time; re-check the 26.1 doc tree.

## Handoffs

- 6 new 24.1 project-list customization endpoints — exact paths/payloads to customization sibling.
- `/qcbin/v2/rest/is-authenticated` shape — auth sibling (already covered there).
- 25.1 purge versioning-history endpoint — attachments/versioning sibling.
- 25.1 `users/{name}` group-name inclusion — customization sibling.
- API key management REST endpoints/object model — auth + customization siblings.
- `/rest` vs `/api` discrepancy incl. `$metadata/fields` only under `/api` — query-grammar/customization siblings; live-probe worthy.
- Site Administration COM API Reference (distinct from OTA) — OTA-fallback notes.
- OTA 64-bit COM registration workaround — OTA-fallback notes.
- TDConnection scripting object expanded entity list in 25.1 — workflow-surface mapping.
- SaaS-only-tagged REST APIs — enumerate via targeted pass through Core reference.
