# Wave 1 / Agent 3 — Requirements domain: CRUD, hierarchy, types, coverage, traceability, risk (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

**Product confirmed:** classic `/qcbin` ALM/QC. The REST API Core reference is labelled "Version 15.5 and later" and is shared across 17.0/24.1/25.1/26.1 portals — one living reference, not per-version copies.

## Sources

**Primary (fetched directly):**
1. REST_core General/Overview.html
2. REST/requirements.html · 3. REST/requirements_by_ID.html
4. REST/entity.html · 5. REST/types.html · 6. REST/types_-_subtype_fields.html · 7. REST/fields_customization.html · 8. REST/lists_related_to_entity.html
9. REST/test-config-coverages_Collection.html · 10. REST/defect-links.html (analogy) · 11. REST/tests_by_ID.html · 12. Get_Test_Return_Sample_XML.html
13. **GET_requirements_XML.html — key primary source, full requirement field dump**
14. GET_test-config-coverages_XML.html · 15. GET_customization_entity_types_XML.html (worked example is for `test`)
16–19. Read/Create/Update/Delete_an_Entity · 20. BulkOperations.htm · 21. Example_Application.html
22. **CodeSamples/CreateDeleteExample.htm** (worked entity = requirement) · 23. **CodeSamples/UpdateExample.htm** (requirement checkout/checkin/lock) · 24. ReadExample.htm · 25. **Constants.htm** (literal minimal requirement-creation XML)
26. 25.1 Project_Customization/cust_proj_req_types.htm · 27. 24.1 cust_project_entities.htm · 28. 24.1 cust_rbqm_toc.htm · 29. 24.1 UG/t_assess_risk.htm
30. 26.1 UG/t_trace_requirements.htm (same topic exists at 17.0 and 12.60) · 31. 25.1 + 17.0 UG/t_create_req_coverage.htm (essentially identical)
32. 17.0 + 26.1 api_rest_api_reference_core.htm landing pages · 33. REST_deprecated Resources.htm (**no `requirements` resource in old API**) · 34. 25.1 api_guides_main_page.htm · 35. OTA ReqType Object (topic8645/8646, cross-reference only)

**Secondary:** 36. community "ALM11 REST API - Requirement Coverage" (HP staff 2013 + community ~2017) · 37. community 513443 (link req↔test) · 38. community 527507 (**OpenText-staff-verified** requirement-coverages GET query, 2024) · 39. community idea "Rest API for requirements (Business models)" (24-CE) · 40. Broadcom ARD ALM(REST) integration doc

Many plausible doc URLs 404'd — absence itself is a finding (see below).

## Findings

### 1. `requirements` — CRUD, required fields, tree reading

**Collection** `GET/POST .../requirements`; bulk POST/PUT/DELETE via `;type=collection` media types. **"Passing data in PUT or POST requests that contain fields that do not belong to the requirement's type is an error."** → must know each requirement's type before writing.

**Single** `GET/PUT/DELETE .../requirements/{id}` (POST N/A). GET returns only fields belonging to the requirement's type.

**Required fields on create**: discover via `GET .../customization/entities/requirement/fields?required=true`. "Do not use the field list returned by retrieving a single entity. The data returned for an entity contains calculated fields that cannot be POSTed or PUT."

**SDK minimal creation payload (primary, Constants.htm):**
```xml
<Entity Type="requirement">
  <Fields>
    <Field Name="name"><Value>[random]</Value></Field>
    <Field Name="type-id"><Value>1</Value></Field>
  </Fields>
</Entity>
```
`name` + `type-id` suffice on default config. **`parent-id` absent from the canned example** (UNVERIFIED #20: does it default to root?).

Create → 201 + Location + full entity.

**Full requirement field set (verbatim from primary GET sample; root node has `type-id`=1, `parent-id`=-1):**

| Group | Fields |
|---|---|
| Identity/hierarchy | `id, name, type-id, parent-id, father-name, no-of-sons, order-id, hierarchical-path` |
| Content | `description, has-rich-content, req-rich-content, comments` |
| Classification | `req-priority, req-reviewed, req-product, req-time, istemplate` |
| Release mapping | `target-rel, target-rcyc` |
| Ownership/audit | `owner, creation-time, last-modified, ver-stamp, check-out-user-name, attachment, has-linkage` |
| request-* (semantics undocumented) | `request-note, request-status, request-id, request-assign-to, request-type, request-server, request-updates` |
| rbt-* risk group | 27 fields (§6) |
| vc-* version control | `vc-checkin-date/-time/-user-name/-comments, vc-checkout-date/-time/-comments, vc-version-number, vc-status` |

**No plain `status` field** — closest is `req-reviewed` (Reviewed/Not Reviewed). "Author" mapping to `owner` is UNVERIFIED (#6).

**Tree reading (CONSTRUCTED):** flat paginated pull of `id,name,parent-id,type-id,order-id` + client-side grouping on `parent-id`; no `/children` sub-resource exists. Alternative: `?query={parent-id[X]}` per node.

**Bulk:** same-type only; `REST_API_MAX_BULK_SIZE` default 2000; bulk delete `?ids-to-delete=…`; 200 full / 500 total-fail / **409 partial** with `BulkOperationFailed`. Generator must chunk ≤2000 and handle 409.

**Update:** PUT partial (only changed fields) → 200 full entity. **Delete:** → 200 with deleted entity. **Cascade behaviour with children undocumented** (UNVERIFIED #8).

### 2. Hierarchy mechanics
- `parent-id` field; **root node `parent-id = -1`**, `type-id=1`, `hierarchical-path="AAA"` (primary sample).
- `order-id` = sibling order; no reorder endpoint documented (UNVERIFIED #10).
- `father-name, no-of-sons, hierarchical-path, ver-stamp` = likely server-calculated (CONSTRUCTED).
- **Move/reparent:** no dedicated endpoint (OTA has Req.Move — COM only). Plausible: PUT with changed `parent-id` — CONSTRUCTED, validation behaviour UNVERIFIED (#9).
- Sibling name-uniqueness: undocumented (UNVERIFIED #7).

### 3. Requirement types
- `GET .../customization/entities/requirement/types` → names + IDs. `GET .../types/{subtype ID}/fields` → per-type fields. `?required=true`, `?can-filter=true` on fields.
- **Built-in types (25.1 UG): Undefined, Folder, Group, Functional, Business, Testing, Business Model** (7 named). A secondary source claimed 8 incl. "Performance" with IDs (Undefined=0, Folder=1, Group=2, Functional=3, Business=4, Testing=5, Performance=6, Business Model=66) — **names reasonably confirmed; numeric IDs and Performance existence UNVERIFIED** (#1, #19). Directly confirmed live: `type-id=1` = root Folder node.
- **A type controls**: optional-vs-required fields (incl. UDFs); Test Coverage enablement (checkbox, sticky once coverage exists); RBQM mode (Perform Analysis / Perform Assessment / None); one Rich Text Template (HTML, auto-applied to new requirements of the type, no graphics).
- OTA `ReqType` (ID, Name, HasDirectCoverage, RiskAnalysisType, RichTextTemplate, EditingControl, Icon) matches 1:1 — corroborates server model.
- **Which types may parent which: not documented** (UNVERIFIED #11).

### 4. Requirement ↔ Test coverage
- Collection **`requirement-coverages`** exists but has **no dedicated reference page** (404s), despite `test-config-coverages` doc referencing it by name in its own prose.
- **GET (staff-verified 2024)**: `.../requirement-coverages?query={test-id[xxx]}`.
- Cross-filter alternative: `.../tests?query={connected-to-requirement.id["{id}"]}`.
- **POST (community-reverse-engineered, contested)**: `<Entity Type="requirement-coverage">` with `test-id` + `requirement-id`. HP staff said unsupported (2013, ALM 11); community claimed working (~2017); another said unsupported (~2022). **UNVERIFIED #2/#3 — top probe.**
- **`test-config-coverages` IS fully documented**: GET/POST; fields `id, first-endpoint-id (→ requirement-coverages row), second-endpoint-id (→ test-configs), last-modified, status` (e.g. "No Run"). Coverage can be at test / test-config / (BPT) criterion level.
- Rolled-up coverage status field on the requirement itself: not identified in field dump.

### 5. Requirement ↔ Requirement traceability
- UI concept (26.1, stable since ≤12.60): **Trace From** (affects selected) / **Trace To** (affected by); drag-drop or by-ID; Impact Analysis tab.
- **REST entity name NOT FOUND** — every plausible slug 404s (`req-trace(s)`, `requirement-links`, `requirement-traces`, `trace-links`…), and **no community precedent** of a working endpoint either (worse gap than coverage). UNVERIFIED #4: capture web-client network traffic while creating a trace link.
- OTA: `Req.ReqTraceFactory` → Trace objects (FromReq/ToReq) — confirms a directed Req→Req edge model.
- Lead: `has-linkage` flag + empty `<RelatedEntities/>` element on every entity — population mechanism undocumented (UNVERIFIED #18).

### 6. Risk-based quality management (rbt-* fields, all confirmed in primary sample)
- Risk: `rbt-risk, rbt-effective-risk, rbt-custom-risk, rbt-use-custom-risk`
- Business impact: `rbt-bsns-impact, rbt-effective-bsns-impact, rbt-custom-bsns-impact, rbt-use-custom-bsns-impact`
- Failure probability: `rbt-fail-prob, rbt-effective-fail-prob, rbt-custom-fail-prob, rbt-use-custom-fail-prob`
- Functional complexity: `rbt-func-cmplx, rbt-effective-func-cmplx, rbt-custom-func-cmplx, rbt-use-custom-func-cmplx`
- Testing level/effort: `rbt-testing-level, rbt-custom-testing-level, rbt-testing-hours, rbt-custom-testing-hours, rbt-rnd-estim-effort-hours, rbt-use-custom-tl-and-te`
- Analysis bookkeeping: `rbt-analysis-parent-req-id, rbt-last-analysis-date, rbt-analysis-setup-data, rbt-analysis-result-data, rbt-assessment-data, rbt-ignore-in-analysis`

Model: Risk = Business Criticality × Failure Probability; Functional Complexity separate; Analysis requirements (folders) aggregate Assessment children; Risk×Complexity grid → Testing Level (Full 100% / Partial / Basic / None). Customization defines criteria+weights, risk grid, constants. Per-type gating via RiskAnalysisType (CONSTRUCTED inference). **No REST operation to trigger/recalculate analysis found** (UNVERIFIED #15).

### 7. Rich-text fields
- `description` confirmed; HTML-in-string convention by analogy with test.description (`<html><body>…</body></html>`) — CONSTRUCTED for requirements (sample was empty).
- **`has-rich-content` + `req-rich-content` are separate additional fields** — which field backs the main editor is UNVERIFIED (#14).
- `comments` confirmed; **append convention (user/date banner format) undocumented** (UNVERIFIED #13).
- Rich Text Template auto-application to REST-created requirements: UNVERIFIED (#12).

### 8. Convert requirement → test
**No evidence found** as REST capability or core-product UI feature. Adjacent real features: Requirement Coverage (link existing test) and the "Testing" requirement type. Conclusion: apparently not supported (UNVERIFIED #16 with UI-inspection probe).

### 9. Version control specifics
Requirements carry the full vc-* field set; officially demonstrated in SDK UpdateExample **using requirement**:
- Versioned: `POST .../requirements/{id}/versions/check-out` → PUT → `POST .../versions/check-in`.
- Non-versioned: `POST .../requirements/{id}/lock` → PUT → `DELETE .../lock`.
No requirement-only exceptions documented.

## Pitfalls & behavioural notes
1. `requirement-coverages` is functioning-but-undocumented — budget for instability.
2. Contradictory evidence on coverage POST — probe before trusting.
3. Never round-trip GET payloads into POST/PUT (calculated fields).
4. **Fields are type-scoped; violations are hard errors** — generator needs per-type field sets.
5. Bulk: same-type, ≤2000, 409 partial semantics.
6. **Traceability looks REST-inaccessible** — generator may need OTA fallback or skip; direct design implication.
7. Doc set is versionless in practice — differences only show up at runtime.
8. Old Deprecated API never had requirements at all.

## Version differences
- No documented requirements-surface differences 16.x→26.1 (shared doc set).
- Type roster discrepancy (7 vs 8 with "Performance") — possible drift, unresolved (#19).
- SaaS vs on-prem: nothing requirements-specific; Overview's WS-Trust mention may be SaaS-flavoured (auth sibling).
- ALM 11 era = low-water mark for coverage writing.

## UNVERIFIED (probes)
1. Numeric type-ids for built-in requirement types → GET customization/entities/requirement/types. **[Lead note: fixture already captured on sandbox.]**
2. POST requirement-coverages works? → POST test-id+requirement-id; also GET customization/entities/requirement-coverage/fields?required=true (is the type registered?).
3. DELETE requirement-coverages/{id} → probe.
4. Trace-link REST name → capture web-client traffic creating a trace; probe req-trace(s)/requirement-traces/trace-links.
5. request-* field group semantics → fields metadata labels.
6. owner = "Author"? → find field with Label="Author".
7. Sibling name uniqueness → POST duplicate names under same parent.
8. Cascade delete with children → create parent+child, DELETE parent, GET child.
9. Reparent via PUT parent-id → probe incl. checked-out-by-other case.
10. order-id writable? → PUT and re-GET siblings.
11. Type parenting rules → matrix probe.
12. Rich Text Template auto-applied on REST create? → POST minimal for templated type; GET description.
13. Comments append banner format → UI comment then REST GET raw.
14. description vs req-rich-content → edit in UI, diff both fields.
15. Risk recalculation via REST? → PUT rbt-custom-*, GET rbt-effective-*.
16. "Convert to test" exists at all? → UI menu inspection.
17. SaaS vs on-prem behaviour → compare.
18. `<RelatedEntities/>` population → try expand-style params.
19. "Performance" type current? → diff type lists across versions.
20. parent-id required on create? → POST minimal payload; inspect resulting parent/hierarchical-path.

## Handoffs
- Query grammar; auth (WS-Trust); generic check-in/out/lock; customization API shape; test entities (test-config-coverages, tests); attachments/rich-text mechanics; defect-links (analogy only); requirement history thread (login-walled, for audit sibling).
