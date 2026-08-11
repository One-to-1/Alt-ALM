# Wave 1 / Agent 8 — Attachments, rich text/memo, history/audit, version control (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

**Primary — ALM REST API Reference (Core), `/qcbin/rest/...`, "15.5 and later" (living, current through 26.1):**
- General/Overview.html, Update_an_Entity.html, Read_an_Entity.html, WhatsNew.htm, **sanitizing_output.htm**
- REST/attachments_collection.html; XML-JSON-Samples/GET_attachment.html, GET_attachments_XML.html, GET_Defects.html, POST_Defect_XML.html
- REST/VersionCntrlAndLockingRrs.html, check-in.html, check-out.html, undo-check-out.html, versions.html, lock.html
- REST/Field_Names.html, resource-list.html, defects.html
- CodeSamples/AttachmentsExample.htm, CodeSamples/infrastructure/Entity.htm

**Primary — REST API Reference (Deprecated), `/qcbin/api/...`:** Overview.htm, attachments.htm, Resources.htm, lock_unlock_example.htm, **sanitizing_html_whitelist.htm**

**Primary — Site Parameters / Project DB / Site Admin REST:** api_refs/site_params/metadata.htm; api_refs/project_db/Content/project_db/topic74.html (AUDIT_LOG, "12.50 and later"); alm/en/24.1/online_help/Content/api_ALM_REST_Site_Admin.htm

**Primary — online help/release notes:** 25.1 UG/entity_history.htm; 26.1 UG/insert_image.htm; 24.1 + 25.1 + 26.1 What's New pages

**Secondary:** Broadcom TechDocs Rally connector (BG_ATTACHMENT claim); community tips (one dead end); search-snippet copies of site-params page for UPLOAD_* params.

**Explicitly discarded:** all Octane/ValueEdge `history_logs`/`history_records` hits (wrong product).

---

## Findings

### 1. Attachments

**Resource shape:** `.../{entity-collection}/{entity-id}/attachments` (collection) and `.../attachments/{name-or-id}` (member). Real examples: `defects/4/attachments`, `defects/4/attachments/2?by-id=true`, `defects/4/attachments/1.txt`.

**Collection POST** — two documented content types:

| Approach | Headers | Body |
|---|---|---|
| `multipart/form-data` | boundary | Parts: `filename` (required), `file` (required, **must be last part**), `description` (opt), `override-existing-attachment` (opt, default "n"), `ref-subtype` (opt, default 0) |
| `application/octet-stream` | `Slug: <filename>` | raw bytes |

- `override-existing-attachment="y"` replaces same-named file, preserves existing description if none supplied; `"n"` creates uniquely-named file.
- **`ref-subtype`: 0 = not rich content; 1 = "rich content" — "the file can be linked from a requirement req-rich-content field"** → this is the documented mechanism for embedding images in memo fields.
- POST → **201** + `Location` header.

**Member:** GET metadata (`Accept: application/xml|json`) or bytes (`Accept: application/octet-stream`). Confirmed fields: `name, id, file-size, description, parent-id, parent-type, ref-type ("File"), ref-subtype, last-modified, vc-cur-ver, vc-user-name`. PUT: octet-stream (new content) or xml/json (metadata). DELETE removes. Parent must be locked (non-versioned) or checked out (versioned) first — attachments are not a lock-exempt side channel.

**Worked CRUD (cited):**
```
POST .../defects/4/attachments   (multipart: filename, file last, description, override, ref-subtype)
GET  .../defects/4/attachments/2?by-id=true  →
<Entity Type="attachment"><Fields>
   <Field Name="name"><Value>1.txt</Value></Field>
   <Field Name="file-size"><Value>22</Value></Field>
   <Field Name="id"><Value>2</Value></Field>
   <Field Name="parent-id"><Value>4</Value></Field>
   <Field Name="parent-type"><Value>defect</Value></Field>
</Fields></Entity>
PUT  .../defects/4/attachments/9?by-id=true  (octet-stream, new bytes)
```

**Deprecated surface** (`/qcbin/api/domains/{d}/projects/{p}/attachments[/{ID}]`): GET supports octet-stream, **multipart/mixed** (file+metadata together), or json (descriptor). POST multipart parts: `filename`, `entity.type`, `entity.id`, `file` (last), + optionals. Query filters: `entity.type`, `entity.id`, `name`, `id`; **`by-id` is NOT valid on the deprecated API**. JSON sample shows an attachment `description` holding literal HTML.

**Size limits / site parameters:**
- `ATTACH_MAX_SIZE` (default 3000 KB) / `ATTACH_TOTAL_MAX_SIZE` (10000 KB) — govern **emailed** attachments, not uploads. Don't conflate.
- `UPLOAD_ATTACH_MAX_SIZE` — general upload cap; exact default not captured (UNVERIFIED #4).
- `UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE` — **separate smaller cap for memo-embedded images**; default not captured.
- `DAYS_TO_KEEP_IMAGE_FILES` (default 30, 0–1000) — client-side image cache retention.
- **`IMAGE_COMPRESSION_LEVEL` — new in 25.1**: server may re-encode images from entity attachments → round-tripped image bytes may differ. Directly relevant to generator round-trip tests.

No ALM-sourced universal "has attachments" flag confirmed; `BG_ATTACHMENT` (Y/N) named only by a third-party doc (UNVERIFIED #10).

### 2. Rich text / memo fields

**Storage format — CONFIRMED by primary worked example (GET_Defects.html):** memo fields store a **complete HTML document** wrapped in `<html><body>...</body></html>`, not a fragment.
- XML carries it **entity-encoded** inside `<Value>` (standard XML escaping, NOT CDATA): `<Value>&lt;html&gt;&lt;body&gt;d1&lt;/body&gt;&lt;/html&gt;</Value>`
- JSON carries it as a plain string: `"<html><body>\nd1\n</body></html>"`
- Corroborated structurally by the JAXB sample model (`Value` = plain `List<String>`, default entity-escaping).

**The wrapper is required by the sanitizer** (sanitizing_output.htm): "Data to be sanitized must be inside the `<html>` and `<body>` tags of a valid HTML document. Otherwise, a tag might be sanitized even though it is allowed by the whitelist."

**Sanitization mechanism:**
- Global switch: **`ENABLE_OUTPUT_SANITIZATION`** (Y/N, default Y, since ALM 12.00) — named for **REST output** sanitization.
- Per-field mode in Project Customization (verbatim): **"Do nothing"** (return as stored) / **"Text encoding"** (HTML-encode) / **"HTML sanitization"** (whitelist filter).
- Whitelist: **`sanitizer-whitelist.xml`** under `.../webapps/qcbin/WEB-INF/classes/` (Windows: `C:\ProgramData\Micro Focus\ALM\...`; Linux: `/var/opt/Micro Focus/ALM/...`). Three collections: **tags**, **attributes** (per-tag + `:all` shorthand e.g. style/class/align), **protocols** (per tag+attribute, e.g. `img[src]` → http/https; `a[href]` → http/https/mailto; disallowed protocol strips the attribute, leaving bare `<img />`). Restart required; replicate per cluster node.
- **The whitelist is deployment-specific — no universal "ALM allowed HTML subset" exists.** Doc example fragments show html, head, meta, body, a, b as default-ish entries.

**Embedded images:**
- UI (insert_image.htm, 26.1): three modes — **Upload**, **Snapshot** (.png), **Clipboard** (.png). `.ico`/`.tif` unsupported. **Link** checkbox inserts as link rather than inline. Governed by `UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE` and `MEMO_FIELD_ADD_IMAGE_MODE` (Y / N / AS_LINK, default Y).
- REST — **CONSTRUCTED two-step**: (1) POST image to entity `/attachments` via multipart with **`ref-subtype=1`** (octet-stream can't carry ref-subtype); (2) PUT memo HTML containing an `<img>` referencing the attachment. **The exact `src=` syntax is not documented anywhere — the single largest gap for rich-text-with-images (UNVERIFIED #1, top probe).**
- Base64 `data:` URIs: no doc either way; community anecdotes suggest server-side resources referenced by URL, not inline base64 (UNVERIFIED #2).

**Editor output vs REST GET:** sanitization is an **output** transform, per-field configurable → what you GET can differ from what you wrote or what the DB holds. Round-trip testing must treat write and read as separately verified steps; whether write-time sanitization exists at all is UNVERIFIED (#5).

### 3. History / audit

- **UI entity history** (25.1 entity_history.htm): Audit Log tab = field, date/time, user, old value, new value + (25.1, gated by `SHOW_CLIENT_SOURCE=Y`) client source. Requirements exclude **Target Release / Target Cycle** from tracking. Admin "Clear History" purges by entity/field/date.
- Which fields tracked: per-field "audited"/History metadata flag (customization sibling).
- **REST surface for history: NOT FOUND.** No `history`/`audit`/`audit-log` collection documented in Core, Deprecated, or SA REST. (Octane has `history_logs` — wrong product, excluded.)
- **Storage** (project_db topic74): `td.AUDIT_LOG` (AU_ACTION_ID PK, AU_USER, AU_SESSION_ID, AU_TIME, AU_ACTION, AU_ENTITY_TYPE, AU_ENTITY_ID, AU_DESCRIPTION, AU_FATHER_ID) + `AUDIT_PROPERTIES` (field-level old/new). Replaced the older `HISTORY` table. Data exists; queryable only via DB or UI, not documented REST.
- **Separate system audit** (site params): `ENABLE_AUDIT` (12.00+), `AUDIT_LOG_FILE_MAX_SIZE` (10240 KB), `AUDIT_LOG_FILTER` (ALL vs USER_MANAGEMENT), `AUDIT_LOG_PATH` (15.50+), **`AUDIT_LOG_LOCATION` (25.1: route some system audit logs to a DB table)**. Platform events, not field history. Not REST-exposed.

### 4. Version control (versioned projects)

Two mechanisms (VersionCntrlAndLockingRrs.html):
- **Locking** (any project; no new version): applies to **"requirement, test, and defect entities"** (verbatim). `.../{collection}/{id}/lock`: GET status / POST create (no body!) / DELETE remove. XML+JSON.
- **Versioning**: applies to **"requirements, tests, resources, favorites, and favorite-folders"** (verbatim; resources = Test Resources). Exception thrown if used on non-versionable types.
- Runtime discovery: `GET .../customization/entities/{type}` → **`<SupportsVC>true|false</SupportsVC>`**.

**check-out** — `POST .../{collection}/{id}/versions/check-out`. Optional body: version number (default latest) + comment; omit Content-Type if no body. Returns full entity. "The checked out version ... is not visible to other users" until check-in.

**check-in** — `POST .../{collection}/{id}/versions/check-in`. Verbatim example:
```
POST /qcbin/rest/domains/DEFAULT/projects/versionPro/tests/1/versions/check-in
Content-Type: application/xml

<CheckInParameters>
    <Comment>check in from rest</Comment>
    <OverrideLastVersion>false</OverrideLastVersion>
</CheckInParameters>
```
Both fields optional; no body → omit Content-Type. Check-in comment overrides check-out comment. No response body.

**undo-check-out** — `POST .../{id}/versions/undo-check-out`. No body either way.

**versions (list)** — `GET .../{id}/versions` (Entities Collection Schema). No documented member path for one specific version's content (UNVERIFIED #7; check-out's optional version number is the closest analog).

**Ordinary writes without checkout/lock**: Update_an_Entity bifurcates — versioned: check out first; non-versioned: locking "strongly recommended" but not required. **Error/status when precondition skipped: undocumented** (UNVERIFIED #9).

**25.1 purge versioning history**: REST changelog says "Purge versioning history of a specific entity type by date or version number"; product What's New says "of tests". **Exact path/method/params absent from public docs** — lives only in embedded Swagger (24.1+ split). UNVERIFIED #6, actionable.

**26.1 P1**: UI-only version comparison (History > Versions tab); no new REST surface found.

**Deprecated lock API shape differs**: collection-level `POST {project}/locks/` with `{"entity":{"id":{id},"type":"{type}"}}`; `DELETE {project}/locks/{lock.id}`. Do not mix conventions.

---

## Pitfalls & behavioural notes

1. **Sanitization is an output transform** (`ENABLE_OUTPUT_SANITIZATION`); nothing documents write-time sanitization. Read-back may differ from what was written purely due to per-field admin config.
2. **Wrap generated memo HTML in full `<html><body>…</body></html>`** — matches stock storage AND the sanitizer's requirement.
3. **The allowed HTML subset is per-installation** (`sanitizer-whitelist.xml`) — hardcoded allowlists are assumptions to verify per target.
4. **Protocols filtered independently** — non-http(s) `img src` gets the attribute stripped, not the tag rejected.
5. **`file` must be the LAST multipart part** (contractual, both API generations).
6. **octet-stream+Slug can't carry `ref-subtype`/description** — multipart is mandatory for rich-content-linkable images.
7. **Attachment PUT/DELETE inherits parent lock/checkout preconditions.**
8. **`by-id=true` is Core-only.**
9. **Two unrelated audit systems** (entity History vs site audit log) — neither has documented REST read.
10. **≥5 API surfaces**: Core, Deprecated, Tech Preview (unfetched), embedded Swagger (24.1+), SA REST (`v2/sa/api`, per-endpoint SaaS-only tags). No single reference is exhaustive; 25.1 purge-versioning lives only in Swagger.
11. Two lock API shapes (Core per-entity sub-resource vs Deprecated collection-level).

## Version differences

- 12.00: ENABLE_AUDIT, ENABLE_OUTPUT_SANITIZATION introduced.
- 15.5/15.50: doc-set floor; sanitizer whitelist; AUDIT_LOG_PATH.
- 16.00: X-XSRF-TOKEN (all this scope's writes are non-GET).
- 17.0/17.0.1: no attachment/history/VC-specific REST changes found.
- 24.1: Swagger cutover for new APIs; product-level: descriptions/attachments on test folders + test-set folders.
- 25.1: purge versioning history API (Swagger-only); SHOW_CLIENT_SOURCE history column; audit extended to user-property/test-folder/test-set-folder changes + AUDIT_LOG_LOCATION; **IMAGE_COMPRESSION_LEVEL** (affects image round-trip fidelity).
- 26.1 P1: UI version comparison only.
- On-prem vs SaaS: SA REST per-endpoint "SaaS only" tags are the only explicit differentiator found.

## UNVERIFIED (probes — rich-text round-trip ones are top priority)

1. **Exact `<img src=…>` syntax** for attachment-backed inline images → insert image via Web Client UI, GET the entity via REST, read the literal src; correlate with /attachments.
2. **Base64 `data:` URI acceptance** → PUT memo with data-URI img; GET back; check stock-client rendering.
3. **Complete default sanitizer-whitelist.xml contents** → on vanilla instance, POST memo spanning table/list/span-style/b/i/u/img/a + varied protocols; GET back under "HTML sanitization" mode; diff.
4. **Exact defaults for UPLOAD_ATTACH_MAX_SIZE / UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE** → site params page in browser or live Site Admin.
5. **Write-time sanitization existence** → POST `<script>`/onclick content; GET back; inspect raw DB/admin export if possible.
6. **25.1 purge-versioning-history path/schema** → embedded Swagger on live 25.1+; or GET /qcbin/rest/resource-list.
7. **Retrieving ONE specific version's content** → GET .../versions; try .../versions/{N}.
8. **BPT component versionability** → GET customization/entities/{bpt-collection}, inspect SupportsVC.
9. **Status/error on write without checkout/lock** → attempt both scenarios.
10. **Per-entity "has attachment" flag field** → customization fields per type. [Lead note: our fixtures show `attachment` field, Type=String, on every entity.]
11. **Tech Preview API surface** → browse api_refs root for current TP URLs.

## Handoffs

- Field-level "audited" flag + per-field sanitization mode readability → customization sibling.
- XSRF → auth sibling.
- Entity envelope / query grammar → respective siblings.
- `customization/entities/{type}` + `SupportsVC` → customization sibling (general coverage).
