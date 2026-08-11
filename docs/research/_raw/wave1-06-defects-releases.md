# Wave 1 / Agent 6 — Defects, links, releases/cycles, libraries/baselines, peripheral entities (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

**Primary — ALM/QC REST API Reference (Core), "Version 15.5 and later" (living snapshot):**
- .../REST_API_Core/General/Overview.html
- .../REST_API_Core/REST/defects.html
- .../REST_API_Core/REST/defect-links.html
- .../REST_API_Core/REST/release-folders.html
- .../REST_API_Core/REST/release-cycles.html
- .../REST_API_Core/REST/releases.html
- .../REST_API_Core/REST/favorites.html
- .../REST_API_Core/REST/relations.html (handoff-relevant)
- .../REST_API_Core/REST/resource-list.html
- .../REST_API_Core/REST/XML-JSON-Samples/GET_Defects.html
- .../REST_API_Core/General/Create_an_Entity.html / Read_an_Entity.html / Update_an_Entity.html / Delete_an_Entity.html
- .../REST_API_Core/General/Filtering.html
- .../REST_API_Core/General/force-delete-children.html
- .../REST_API_Core/General/HTTP_Return_Codes.html
- .../REST_API_Core/General/Actions_on_Collections.html
- .../REST_API_Core/schema.htm (schema ships as downloadable zip)

**Primary — pinned 15.5–15.5.1 snapshot** (drift check): .../15.5-15.5.1/REST_core/.../defect-links.html

**Primary — REST API Reference (Deprecated)**: Overview.htm, Defects.htm, Resources.htm, Collections.htm, Standard_Headers.htm, General_Notes_and_Limitations.htm

**Primary — OTA API Reference** (COM, cited only for similar-defects): admhelp.microfocus.com/alm/api_refs/ota_docx/topic89.html ("Find similar defects")

**Primary — ALM Help Center per-version**: 25.1 api_rest_api_reference_core.htm (Core-vs-Swagger split statement); 24.1 UG/ui_defects_fields.htm (Defects Module Fields table); 25.1 UG/t_use_releases_cycles.htm; 26.1 UG/t_track_defects.htm; 25.1 Web_Runner/favorite.htm; 24.1 Project_Customization/cust_alert_rules_toc.htm; 25.1 UG/t_use_libraries_baselines.htm; 25.1 Web_Runner/customize_advanced_wf.htm (CLIENT_TYPES_BYPASS_REST_WF); 25.1 UG/t_use_ppt.htm (Milestones/PPT)

**Primary — OpenText Community, staff-confirmed**: discussion 527746 (staff: some UI entities have no REST entity reference — "Test Pool" precedent)

**Secondary (leads only)**: community 520473 (defect workflow status lists); consulting-bolte.de defect-link worked example (HP ALM ~11.52, field names cross-checked)

---

## Findings

### 1. `defects`

**Collection:** `GET|POST /qcbin/rest/domains/{d}/projects/{p}/defects`, bulk `PUT|DELETE` with `Content-Type: application/xml;type=collection` (or json). Single instance `/defects/{id}`: GET, PUT (partial update), DELETE — documented generically on Read/Update/Delete_an_Entity pages, not per entity.

- **POST (create):** `<Entity Type="defect"><Fields><Field Name="..."><Value>...</Value></Field></Fields></Entity>` (XML) or `{"Fields":[{"Name":"...","values":[{"value":"..."}]}]}` (JSON). Returns **201**, `Location` header, full created entity with server defaults.
- **PUT (update):** partial — include only changed fields. Returns **200** with complete updated entity. Docs warn: build update payloads from the **customization fields list**, never from a GET of an existing entity (calculated/read-only fields get rejected on write). Locking/check-out recommended first.
- **DELETE (single):** returns **200** with full body of deleted entity.
- **Required fields are per-project customization**: discover via `GET .../customization/entities/defect/fields?required=true`.

**Standard field set** (Defects Module Fields, ALM 24.1 UI doc; REST names lower-kebab; confirmed vs CONSTRUCTED flagged):

| UI field | Notes |
|---|---|
| Defect ID | read-only; REST `id` |
| Summary | REST `name` (confirmed via GET_Defects sample) |
| Description | REST `description` (confirmed) |
| Status | Closed/Fixed/New/Open/Rejected/Reopen; default **New**; REST `status` (confirmed) |
| Severity | 1-Low…5-Urgent; REST `severity` (confirmed) |
| Priority | 1…5; CONSTRUCTED `priority` |
| Detected By | REST `detected-by` (confirmed) |
| Assigned To | CONSTRUCTED `assigned-to` |
| Detected on Date | default = current DB server date; CONSTRUCTED `detection-date` |
| Detected in Release/Cycle | setting Detected-in-Cycle auto-populates Detected-in-Release (26.1 UG) |
| Target Release / Target Cycle | — |
| Closing Date | — |
| Actual Fix Time | auto-computed as Closing − Detected if blank |
| Reproducible | Y/N, default **Y** |
| Comments | append-only via "Add Comment" (new dated, user-stamped section); REST almost certainly `dev-comments` |
| Detected/Closed/Planned Closing Version | distinct from Release/Cycle |
| Modified | read-only timestamp |

**Status workflow enforcement:** ALM has **no REST-native state machine**. Transition rules live in project workflow scripts. Per the primary 25.1 doc: **"By default, advanced project scripts apply to Web Client only. If you want to apply them to all your applications that use ALM REST API, change the `CLIENT_TYPES_BYPASS_REST_WF` site parameter to None."** → Out of the box, a REST client can set any `status` value, bypassing UI-enforced transitions. First-class finding for the generator.

### 2. `defect-links`

**Endpoint:** `.../defect-links` — "The collection of links to and from defects."

- **Methods:** GET, POST (XML/JSON). Collection-level PUT/DELETE = N/A (no bulk); single link deletable via generic `DELETE /defect-links/{id}`.
- **Fields:** `first-endpoint-id`, `second-endpoint-id`, `second-endpoint-type`. **Defect-to-defect links are non-directional**: "There is no importance to which defect is identified with first-endpoint-id and which is identified with second-endpoint-id." (identical wording in 15.5-pinned snapshot — no drift).
- Required fields: `.../customization/entities/defect-link/fields?required=true`.
- **Query both directions:** from a defect: `defect-links?query={first-endpoint-id[<id>]}`; involving a defect regardless of side: `defect-links?query={second-endpoint-type[defect];second-endpoint-id[1]}`.
- **Creation returns 201** with new link entity.
- **No link-type/relation-kind field** (duplicate vs related) documented. Only confirmed `second-endpoint-type` value: `requirement` (cross-checked worked example). `defect`/`test`/`run`/`test-instance` plausible but UNVERIFIED.

**Worked example (CONSTRUCTED; shape from Create_an_Entity + defect-links.html):**
```xml
POST .../defect-links
Content-Type: application/xml

<Entity Type="defect-link">
  <Fields>
    <Field Name="first-endpoint-id"><Value>459</Value></Field>
    <Field Name="second-endpoint-id"><Value>2</Value></Field>
    <Field Name="second-endpoint-type"><Value>requirement</Value></Field>
  </Fields>
</Entity>
```
→ 201 Created.

### 3. Similar defects

**OTA-only capability, not REST.** OTA: `Bug.FindSimilarBugs(SimRatio)` → FactoryList of Bug candidates + `.Ratio(index)`; SimRatio 0–100. UI (26.1): text-similarity on summary/description, default min similarity 25%, ignores articles/conjunctions/booleans/wildcards. **No REST resource found**; `resource-list.html` states: "use only the resources documented... Any resources included in the REST API that are not documented are not intended for public use." → Build duplicate-detection app-side if wanted.

### 4. `release-folders` and `releases`

- **`release-folders`**: GET (list), POST (create); collection PUT/DELETE N/A; single-instance GET/PUT/DELETE generic. `DELETE .../{id}?force-delete-children=y` deletes children; default `n` relocates children to "Unattached". (force-delete-children doc demonstrates on test-folders/test-set-folders; applicability to release-folders is CONSTRUCTED via the shared clause link.)
- **`releases`**: GET, POST; same media-type matrix. No field table in REST docs (by design — per-project customizable). UI-level minimum: Name, Start Date, End Date, Description; releases roll up milestones, KPIs, scope items. Exact REST field names for start/end date UNVERIFIED (likely `start-date`/`end-date`, CONSTRUCTED). [Lead note: our fixtures customization-fields-release.json hold the live answer.]

### 5. `release-cycles`

`.../release-cycles` — GET/POST + bulk `;type=collection`. Whether cycle dates are validated server-side against the parent release window: **not documented either way** — UNVERIFIED; do not assume enforcement.

### 6. Milestones

Extensively documented **UI** feature (PPT: New Milestone dialog, KPIs, `MAX_KPIS_PER_MILESTONE`, Master Plan/Gantt) — **no REST entity reference found**. All plausible resource names (`milestones`, `release-scope-items`, `scope-items`, …) 404 on the doc host. Staff-confirmed precedent for "UI feature with no REST entity" exists (Test Pool, discussion 527746). Treat as a genuine API gap; do not guess endpoints.

### 7. Libraries and baselines

**UI-only as far as documentation shows.** Libraries/Baselines flow is wizard + async task (Create Baseline Wizard, View Log, Task Manager); comparison, CSV export, pinning, import/sync all GUI-only with zero REST/OTA mentions. Doc-host probes for `libraries`, `baselines`, `vc-*` variants all 404 (contrast: `favorites.html` was found by the same method). **Finding of absence** — baseline capture/compare/pin currently have **no REST path** per documentation. Flag to architecture/feasibility.

### 8. Alerts, follow-up flags, favorites

- **Favorites — REST-exposed (confirmed):** `.../favorites` — "collection of user settings defined for the logged-on user." GET + POST; PUT/DELETE N/A at collection level; "Favorites Schema". UI doc (25.1) describes favorites as Requirements/Test Plan/Test Lab/Defects only, Private vs Public folders, capturing filter+columns+sort+grouping — and never mentions REST (docs not cross-linked).
- **Alerts / alert rules — no REST exposure found.** Four built-in rules (Requirement Changes, Defect Fixed, Test Passed, Requirement Deletion/Change) flag entities + optional email. `alerts.html`/`alert-rules.html` 404.
- **Follow-up flags:** no dedicated source located; may exist only as UI decoration driven by alert state — UNVERIFIED.

---

## Pitfalls & behavioural notes

1. **Field tables are deliberately absent from REST docs** — fields are per-project; authoritative source is runtime customization metadata. Generator must fetch per target project.
2. **"DELETE: N/A" on a collection page means no BULK delete** — singular `DELETE /{collection}/{id}` still works generically.
3. **Workflow/status enforcement is opt-in for REST** (`CLIENT_TYPES_BYPASS_REST_WF`, default = bypass). Valid transitions are not discoverable via REST at all (VBScript per project).
4. **Comments are additive with no server-side append semantic** — PUT replaces `dev-comments` wholesale; generator must reproduce ALM's user+date banner format client-side (exact format UNVERIFIED — probe).
5. **Undocumented ≠ usable** — resource-list doc explicitly disclaims undocumented resources.
6. **UI docs and REST docs are not cross-linked** — silence in one says nothing about the other (favorites proves it).
7. **Comparison operators changed in 24.1 P1** (GT/LT… alongside symbols; text form recommended).

## Version differences

- **Core reference = pre-24.1 APIs only.** v25.1 doc: "For REST APIs introduced before version 24.1, see REST API Reference (Core). For REST APIs introduced in version 24.1 and later, refer to the Swagger-powered help embedded in your environment" — `http://<Server>:<port>/qcbin/api-doc/v2/` or Tools ▸ "Project REST API Reference" in the web client. **Whether 24.1+ added anything for defects/links/releases/milestones/libraries/baselines is unknowable from static docs — must check live Swagger.**
- Deprecated and Core doc sets both labelled "15.5 and later" — "Deprecated" = superseded REST generation, not just older.
- 15.5-pinned defect-links.html textually identical to current — no drift.
- On-prem vs SaaS: no documented difference found for these entities (absence-of-evidence).

## UNVERIFIED (probes)

1. `second-endpoint-type` enum for defect-links → GET defect-link fields metadata (full, not just required) and inspect constraints.
2. Defect↔run/run-step/test-instance link mechanics → POST defect-link with each type; or inspect a UI-created linked defect on a run.
3. Exact REST field names for releases/release-cycles dates → customization fields (live). [Lead note: already in our fixtures.]
4. Cycle-date validation vs parent release window → POST out-of-window cycle, check 400 vs success.
5. force-delete-children applicability to release-folders → live probe.
6. Exact `dev-comments` append banner format → add two UI comments, GET raw value.
7. Milestones/Libraries/Baselines/Alert Rules truly zero REST → GET /qcbin/rest/resource-list and text-search (with the caveat that undocumented = unsupported).
8. Whether 24.1+ Swagger adds anything in this scope → open api-doc/v2 on live instance and diff.
9. Follow-up flags as standalone entity → search help + resource-list.
10. Per-operation permission requirements → attempt CRUD as different roles, record 403s.

## Handoffs

- `relations` collection (`.../customization/relations`, `.../customization/entities/{entity}/relations/`) = relation **schema** metadata (GET-only, ETag) — customization sibling.
- Requirement↔test coverage — requirements sibling.
- Test Pool REST gap (staff-confirmed precedent) — test-plan/lab siblings.
- Query grammar detail (operators, cross-entity dot notation `connected-to-defect.name[...]`) — query sibling.
- Standard headers/auth — auth sibling.
- Attachments — attachments sibling (Deprecated doc set has individual topics for Defects, Attachments, Fields, List-items, Locks only).
