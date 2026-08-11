# Wave 1 / Agent 4 — Test Plan domain via REST: folders, tests, design-steps, parameters, configs, resources, copy (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

**Primary — REST API Reference (Core), "15.5 and later" (evergreen; covers 16.x/17.x/24.1/25.1/26.1):**
- General: Overview.html, General_Notes_and_Limitations.html, relations_btwn_entities.htm, force-delete-children.html
- REST/copy.html · REST/test_folders.html · REST/test-folders_byID.html · REST/tests.html
- XML-JSON-Samples: Get_Tests_Return_Sample_XML.html, Get_Test_Return_Sample_XML.html, GET_run-steps_XML.html
- REST/design-steps_Collection.html · REST/run-steps.html (naming corroboration only)
- **REST/test-configs_Collection.html** · REST/test-config-coverages_Collection.html
- **REST/resources.html** · **REST/resource-folders.html** · REST/resource-list.html
- REST/types.html · REST/types_-_subtype_fields.html · REST/relations.html (customization/relations)

**Primary — Project DB Reference:** td.TEST_CONFIGS (topic575.html)
**Primary — OTA docs (conceptual only):** Test Configurations (ota_docx/topic35.html)
**Primary — User Guide:** t_use_test_parameters.htm (12.60), t_use_test_resources.htm (15.0/26.1)

**Secondary (community, cross-checked/flagged):**
- 527746 — staff (Jacky Zhu): "Test Pool" = renamed Test Plan tree label in 17.0 UI; working `test-folders?query={parent-id[2];}` + `tests?query={parent-id[...]}` examples
- 186209 — test parameters via REST: `hasTestParams` never populated; **no dedicated parameter endpoint**; workarounds = OTA, DB, attachments
- 456663 — "Subject" path field not returned by REST; HTML-encoded description fields
- 397655 — staff-confirmed (2017): **resource file content download NOT possible via REST**, "in the queue"
- testkeis blog — DESSTEPS DB columns (DS_STEP_ORDER, DS_STEP_NAME, DS_EXPECTED)

**Discarded (wrong product):** "Helix ALM REST API" blog series (Perforce Helix ALM, unrelated).

---

## Findings

### 1. test-folders
- Collection: `GET|POST .../test-folders` (+ `;type=collection` bulk POST). By ID: `GET|PUT|DELETE .../test-folders/{ID}`.
- Hierarchy via `parent-id`; Subject-root ID convention project-specific (UNVERIFIED #1). Staff-confirmed query pattern: folders and tests both queried by `parent-id`.
- `DELETE .../{ID}?force-delete-children=y|n` — default `n` **moves children to Unattached**; `y` deletes subtree.
- Move = PUT `parent-id`; rename = PUT `name` (CONSTRUCTED from generic modify pattern — no worked example).
- 17.0 UI relabels the tree root "Test Pool"; REST names unaffected.

### 2. tests
- Collection: `GET|POST|PUT|DELETE .../tests` (PUT/DELETE collection-level = bulk via `;type=collection`). By ID: standard.
- **Fields in official worked example** (test id=2): `id, name, type("test"), subtype-id("MANUAL"), exec-status("Passed"), creation-time, last-modified, steps(count), parent-id, has-dependencies, configurations-count, description(HTML rich text), ver-stamp, vc-start-audit-action-id, vc-end-audit-action-id, order-id, step-param`; plus empty: `template, has-criteria, estimate-devtime, check-out-user-name, storage-path, attachment, owner, status, vc-comments`, … (~40 total).
- **`subtype-id` = test-type discriminator**; enumerate per project via `GET .../customization/entities/test/types`; per-subtype fields via `.../types/{subtype ID}/fields` (runtime discovery mechanism — matches no-hardcoding constraint).
- `description` confirmed HTML-bearing.
- `has-dependencies` semantics undocumented (likely Test Resources Dependencies tab mirror) — UNVERIFIED #2.
- **REST field gap: the desktop "Subject" path string is NOT returned by REST** — breadcrumbs must be reconstructed by walking `parent-id` (456663).

### 3. design-steps
- **Collection: `GET .../design-steps` — GET ONLY. POST/PUT/DELETE marked "Not applicable" on the Core page.** Either a page-level doc gap or writes flow through a nested path (`POST .../tests/{id}/design-steps`, the pattern used by `runs/{id}/run-steps`) — **needs live probe (UNVERIFIED #3; load-bearing for the generator).** Community POST attempts exist (login-gated thread; field list `step-order, name, parent-id, expected` — UNVERIFIED #6).
- **Two relations to test, disambiguation mandatory:**
  1. Containment — alias **`has-parts-test`** (worked example: `design-steps?query={has-parts-test.name[e]}`).
  2. Call-to-test — exists (doc says the bare form fails *because* both relations exist) but the call alias name was not shown — UNVERIFIED #4; probe `GET .../customization/entities/design-step/relations`.
  - Field storing called-test ID on a call step: UNVERIFIED #5 (create via UI, diff a plain step).
- **Step field mapping (CONSTRUCTED from run-steps sample + DESSTEPS DB columns, internally consistent):** `name` (title), `description` (rich text instructions), `expected` (rich text), `step-order` (ordering), `parent-id` (owning test). Run-steps carry `desstep-id` back-links, confirming design-step IDs referenced at run time. Design-steps-specific worked sample page not found — UNVERIFIED #7.

### 4. Test parameters
- **No dedicated REST entity exists in documentation.** Community-confirmed: `hasTestParams` on tests is never populated via REST; no endpoint across ALM 11/12+; workarounds = OTA (COM), DB `TEST_PARAMS` table, or attachments. **Hard planning constraint: no confirmed REST way to read a test's parameter list.**
- **Token syntax (UI-documented)**: `<<<parameter name>>>` inside design-step description/expected auto-registers the parameter (UI behaviour). Whether a REST-posted `<<<newparam>>>` triggers server-side auto-registration: UNVERIFIED #8 (verify via UI/OTA since REST can't read back).
- **Where parameter VALUES do surface**: `test-configs` — DB `TSC_DATA_OBJ` ("XML string containing static parameter values for BPT configurations") + `TSC_DATA_STATE` (0=static / 1=parent test data resource / 2=config's own data resource). REST names likely `data-obj`/`data-state` — CONSTRUCTED, UNVERIFIED #9. [Lead note: our live test-config fixture can confirm the field names.]
- Run-time binding (`test-step-params-data`, `eparams` in instance/run payloads) → Test Lab sibling; unverified there too.

### 5. test-configs — COLLECTION FOUND (resolves sibling gap)
- **`GET|POST .../test-configs`** (standard media types + bulk) — REST/test-configs_Collection.html. By-ID slug guess 404'd — UNVERIFIED #10 (just GET a known id live).
- Relation to test: DB `TSC_TEST_ID` → REST `test-id` (near-certain).
- Field set from DB schema (CONSTRUCTED mapping): `id, test-id, name, description, created-by, creation-time, exec-status(Not Completed/No Run/Passed/N/A/Failed), ver-stamp, check-out-user-name, attachment, user-NN, data-filtering(XML filter), data-state, data-obj`. TSC_DESC is varchar(16) ⇒ memo pointer, not inline text. UNVERIFIED #11: pull `.../customization/entities/test-config/fields` live. [Lead note: fixture already captured — 15 fields.]
- **Config-level requirement coverage confirmed**: `GET|POST .../test-config-coverages`; `first-endpoint-id` → requirement-coverages row; `second-endpoint-id` → test-configs row. POST body shape unshown — UNVERIFIED #12.

### 6. Test resources
- **`GET|POST .../resources`** and **`GET|POST .../resource-folders`** exist (Core-documented), with the shared force-delete-children clause on folders.
- Resource-type enumeration endpoint: probe `.../customization/entities/resource/types` (UNVERIFIED #13).
- **File content upload/download: historically NOT possible via REST** (2017 staff-confirmed; three URL shapes 404'd). Dated finding — must re-check on 24.1+ Swagger (`/qcbin/api-doc/v2/`) — UNVERIFIED #14. Resources' file payload ≠ standard attachments mechanism (heads-up to attachments owner).
- Dependencies: `has-dependencies` on tests; no `dependencies` sub-collection found (`.../dependencies` 404) — UNVERIFIED #15; probe entity relations.

### 7. Copy/move semantics
- **Generic copy resource**: `POST .../{entity collection}/copy`, gated by per-entity **`SupportsCopying=true`** in the entity descriptor (check test/test-folder live — UNVERIFIED #16).
- **Worked body (verbatim from doc):**
```xml
<Copy>
  <IDs><ID>i</ID><ID>j</ID></IDs>
  <TargetParentId>a</TargetParentId>
</Copy>
```
```json
{ "IDs": ["i", "j"], "TargetParentId": "a" }
```
- Behaviour: hierarchical entities copy **with entire subtree**; attachments copied automatically; co-copied linked entities preserve their relationship; release/cycle links preserved; appended at end of target's order; `TargetParentId` optional (root if omitted). Response = `OutgoingCopyResults` schema (unfetched — UNVERIFIED #18). Test-specific copy (design-steps come along?) extrapolated from the requirement example — UNVERIFIED #17.
- **Move**: no dedicated resource; PUT `parent-id` (CONSTRUCTED — UNVERIFIED #19, check order-id/workflow side effects). Rename: PUT `name`.

### 8. Template tests / criticality / automation fields
- `template` field exists on tests (empty in sample) — semantics UNVERIFIED #20.
- `estimate-devtime`, `storage-path` (plausible script path), `has-criteria` present but undescribed — UNVERIFIED #21; pull full field metadata live.
- No `testing-tool-type`/`script-subject` confirmed anywhere — treat as nonexistent until seen in customization fields (UNVERIFIED #22).
- **Custom Test Types SDK** doc exists (`api_refs/CustomTestType/Default.htm`, blank on fetch) — likely SDK/plugin mechanism, not REST — UNVERIFIED #23.

## Pitfalls & behavioural notes
1. **design-steps collection page documents zero write methods** — nested-POST hypothesis unprobed; generator-critical.
2. **Ambiguous relation queries silently fail** — always use explicit aliases for design-steps↔test.
3. **force-delete-children default relocates to Unattached** — cleanup tools must pass `y` or handle strays.
4. **No REST read-back of test parameters** despite hasTestParams field.
5. **Resource file content historically REST-unsupported** — re-verify on 24.1+.
6. **Subject path string absent from REST** — client-side breadcrumb reconstruction required.
7. **TSC_DESC varchar(16) ⇒ memo pointer pattern** — memo fields need their own write path in the generator's matrix.
8. **`copy` is the entity-agnostic duplication mechanism** (SupportsCopying-gated) — design the generator's tree-variation feature around it.

## Version differences
- Entire domain is Core-documented "15.5 and later" with no per-endpoint version gates seen; no Swagger-only Test Plan endpoints identified (absence of evidence — UNVERIFIED #24: check live Swagger for additions, esp. resource-content and parameter CRUD).
- On-prem vs SaaS: nothing domain-specific; General Notes: REST cannot work through a Basic-Auth proxy.
- "Test Pool" relabel confirmed for 17.0 only (cosmetic) — UNVERIFIED #25.

## UNVERIFIED (25 items, top ones)
1. Subject-root folder ID convention → query parent-id[0] on sandbox.
2. has-dependencies semantics → compare with UI Dependencies tab.
3. **Design-step write path (flat vs nested POST)** → probe both on sandbox. **[TOP GENERATOR PROBE]**
4. Call-relation alias → GET customization/entities/design-step/relations.
5. Called-test field on call steps → UI-create then GET diff.
6. design-step POST field set → iterate on 400s.
7. Which design-step fields accept rich text → POST HTML into name/description/expected.
8. `<<<param>>>` auto-registration via REST → POST then check UI.
9. data-obj/data-state literal REST names → GET a configured test-config. [Fixture may already answer.]
10. test-configs/{ID} slug → GET known id.
11. Full test-config field list → customization fields. [Fixture captured.]
12. test-config-coverages POST body → probe.
13. resource types enumeration → customization/entities/resource/types.
14. **Resource file content on 24.1+** → Swagger + live attempts.
15. dependencies relation → entity relations.
16. SupportsCopying on test/test-folder → entity descriptors.
17. Copy brings design-steps? → copy a stepped test, GET steps.
18. OutgoingCopyResults schema → inspect probe response.
19. Move via PUT parent-id side effects → probe.
20–23. template semantics; undescribed field metadata; guessed fields nonexistence; Custom Test Types page.
24. Swagger-only Test Plan additions → live Swagger.
25. "Test Pool" label currency → cosmetic.

## Handoffs
- Test Lab sibling: test-instances/run-steps touched only for naming corroboration; `test-step-params-data`/`eparams` verification.
- Requirements sibling: requirement-coverages other end.
- Attachments sibling: resources' file payload uses a separate (historically unsupported) mechanism — special-casing heads-up.
- Customization sibling: types/subtype-fields/relations response shapes.
- Query sibling: relation-alias mechanism generally.
