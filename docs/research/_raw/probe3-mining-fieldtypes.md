# Probe 3: Mining Field Types & Customization

**Date**: 2026-08-12  
**Source**: ALM project field customization fixtures (15 entities, 432 total fields)  
**Analysis**: Offline fixture mining for field type definitions, list bindings, read-only surface, and data model constraints.

---

## Executive Summary (8-line findings)

1. **Boolean field type**: NO Boolean type exists in ALM REST API. Yes/no fields use LookupList ID 1 (YesNo: Y/N values) or String/Number types with flag-naming semantics.
2. **80 flag-like fields** across 15 entities named with patterns (attachment, has-*, is-*, *-flag, vc-*); most are read-only, virtual, or system fields—not user-editable booleans.
3. **43 total lists** in customization-lists.json vs. 39 actually used in field bindings; **4-list delta**: ID 255 (Activity Status, 3 items), ID 82 (VC Status, 3 items), ID 285 (Resource Type, 6 items), ID 320 (TestType, 18 items).
4. **Read-only surface**: 191 fields across all entities are Editable=false and System=true; highest concentration in `run` (25) and `test` (38) entities.
5. **Multivalue**: Only 2 fields support SupportsMultivalue=true—both in requirement entity: `target-rcyc` and `target-rel` (Reference type).
6. **List binding counts**: requirement (31 list fields), test (11), defect (11), run (5), resource (5), others (7 combined).
7. **Size outliers**: 62 Memo/String fields with size -1 (unlimited) or 99999 (virtual truncation); memo fields consistently -1, virtual path fields consistently 99999.
8. **Requirement types**: 8 types (IDs 0–6, 66) with varying risk analysis levels; no document-root types defined; Folder and Group cannot have direct coverage.

---

## 1. Boolean Field Type Answer

**Question**: Is there a Boolean field type in the ALM REST API?

**Answer**: **NO**. ALM defines no Boolean type.

**Findings**: Yes/no-ish fields are encoded as:

- **LookupList bound to ID 1** (YesNo)
  - Items: `["Y", "N"]`
  - Used for: `rbt-ignore-in-analysis`, `rbt-use-custom-func-cmplx`, `rbt-use-custom-risk`, `rbt-use-custom-bsns-impact`, `rbt-use-custom-fail-prob`, `rbt-use-custom-tl-and-te`
  - Semantics: INFERRED as toggle/checkbox replacement for workflow or RBQM feature flags.

- **String type fields** with flag-like naming
  - Examples: `attachment` (read-only, system), `has-rich-content` (read-only, system), `has-linkage` (virtual, read-only)
  - Semantics: INFERRED as computed properties or version-control status flags, not user input.

- **Number type** (istemplate, no-of-sons, numeric counters)
  - Examples: `istemplate` (Number, read-only), `no-of-sons` (Number, read-only)
  - Semantics: INFERRED as computed counters or hierarchy markers.

- **UsersList type** for user flags
  - Examples: `owner` (UsersList, Verify=true), `request-assign-to` (UsersList, read-only in some contexts)
  - Semantics: User assignment rather than boolean; Verify flag indicates ALM workflow validation.

**Interpretation (INFERRED)**: Boolean semantics are **absent from the REST API surface**. Flag-like fields are either:
- Non-editable computed properties (has-*, is-*, attachment flags)
- LookupList bindings to Yes/No choice lists
- Numeric or String representations of state

No uniform Boolean type; generator must treat each flag field individually per entity.

### Table: All 80 Boolean-Like Fields by Entity

| Entity | Field Name | Type | List ID | Notes |
|--------|-----------|------|---------|-------|
| defect | attachment | String | — | Read-only, system |
| defect | has-change | String | — | Read-only, system |
| defect | has-linkage | String | — | Read-only, system |
| defect | has-others-linkage | String | — | Read-only, system |
| design-step | attachment | String | — | Read-only, system |
| design-step | has-params | Number | — | Read-only, system |
| design-step | vc-user-name | UsersList | — | Read-only, system |
| release | has-attachments | String | — | Read-only, system |
| release-cycle | has-attachments | String | — | Read-only, system |
| release-folder | has-attachments | String | — | Read-only, system |
| requirement | attachment | String | — | Read-only, system |
| requirement | has-linkage | String | — | Virtual, read-only, system |
| requirement | has-rich-content | String | — | Read-only, system |
| requirement | istemplate | Number | — | Read-only, system |
| requirement | rbt-analysis-parent-req-id | Number | — | Read-only, system |
| requirement | rbt-analysis-result-data | Memo | — | Read-only, system |
| requirement | rbt-analysis-setup-data | Memo | — | Read-only, system |
| requirement | rbt-custom-risk | LookupList | 251 | Editable, RBQM custom risk level |
| requirement | rbt-effective-risk | LookupList | 251 | Read-only, system, RBQM computed |
| requirement | rbt-ignore-in-analysis | LookupList | 1 | Editable, YesNo (Y/N) |
| requirement | rbt-last-analysis-date | Date | — | Read-only, system |
| requirement | rbt-risk | LookupList | 251 | Read-only, system, RBQM computed |
| requirement | rbt-use-custom-risk | LookupList | 1 | Editable, YesNo (Y/N) |
| requirement | vc-checkin-comments | Memo | — | Read-only, system |
| requirement | vc-checkin-date | Date | — | Read-only, system |
| requirement | vc-checkin-time | String | — | Read-only, system |
| requirement | vc-checkin-user-name | UsersList | — | Read-only, system |
| requirement | vc-checkout-comments | Memo | — | Read-only, system |
| requirement | vc-checkout-date | Date | — | Read-only, system |
| requirement | vc-checkout-time | String | — | Read-only, system |
| requirement | vc-status | LookupList | 170 | Read-only, system, Version Status |
| requirement | vc-version-number | Number | — | Read-only, system |
| resource | has-dependencies | String | — | Read-only, system |
| resource | vc-checkin-comments | Memo | — | Read-only, system |
| resource | vc-checkin-date | Date | — | Read-only, system |
| resource | vc-checkin-time | String | — | Read-only, system |
| resource | vc-checkin-user-name | UsersList | — | Read-only, system |
| resource | vc-checkout-comments | Memo | — | Read-only, system |
| resource | vc-checkout-date | Date | — | Read-only, system |
| resource | vc-checkout-time | String | — | Read-only, system |
| resource | vc-status | LookupList | 82 | Read-only, system, VC Status |
| resource | vc-version-number | Number | — | Read-only, system |
| run | attachment | String | — | Read-only, system |
| run | build-revision | String | — | Read-only, system |
| run | has-linkage | String | — | Read-only, system |
| run | has-vtc | String | — | Read-only, system |
| run | vc-locked-by | UsersList | — | Read-only, system |
| run | vc-status | LookupList | — | Read-only, system |
| run | vc-version-number | Number | — | Read-only, system |
| run-step | attachment | String | — | Read-only, system |
| run-step | has-linkage | String | — | Read-only, system |
| run-step | has-picture | String | — | Read-only, system |
| test | attachment | String | — | Read-only, system |
| test | has-components | String | — | Read-only, system |
| test | has-criteria | String | — | Read-only, system |
| test | has-dependencies | String | — | Read-only, system |
| test | has-linkage | String | — | Read-only, system |
| test | is-aviator-generated | String | — | Read-only, system |
| test | is-change-detectable | String | — | Read-only, system |
| test | vc-checkin-comments | Memo | — | Read-only, system |
| test | vc-checkin-date | Date | — | Read-only, system |
| test | vc-checkin-time | String | — | Read-only, system |
| test | vc-checkin-user-name | UsersList | — | Read-only, system |
| test | vc-comments | Memo | — | Read-only, system |
| test | vc-date | Date | — | Read-only, system |
| test | vc-end-audit-action-id | String | — | Read-only, system |
| test | vc-start-audit-action-id | String | — | Read-only, system |
| test | vc-status | LookupList | 170 | Read-only, system, Version Status |
| test | vc-time | String | — | Read-only, system |
| test | vc-version-number | Number | — | Read-only, system |
| test-config | attachment | String | — | Read-only, system |
| test-config | has-dependencies | String | — | Read-only, system |
| test-config | vc-checkout-user-name | UsersList | — | Read-only, system |
| test-folder | attachment | String | — | Read-only, system |
| test-instance | attachment | String | — | Read-only, system |
| test-instance | has-linkage | String | — | Read-only, system |
| test-instance | is-dynamic | String | — | Read-only, system |
| test-set | attachment | String | — | Read-only, system |
| test-set | has-linkage | String | — | Read-only, system |
| test-set-folder | attachment | String | — | Read-only, system |

---

## 2. List Bindings

### Overview

**Total LookupList/UsersList fields**: 77 across 11 entities (none in release, release-cycle, release-folder, test-folder, test-set-folder, test-config)

| Entity | Field Count | Example Bindings |
|--------|------------|------------------|
| requirement | 31 | status (309), req-priority (298), req-product (259), rbt-risk (251) |
| test | 11 | plan-status (209), test-type (320), testing-framework (224) |
| defect | 11 | severity (279), status (237), priority (298) |
| run | 5 | state (231), test-type (320) |
| resource | 5 | location (317), res-type (285), vc-status (82) |
| test-instance | 4 | state (356), coverage-mode (214) |
| design-step | 1 | execution-type (217) |
| test-set | 3 | state (356) |
| run-step | 2 | status (356), language (228) |

### Detailed List Bindings by Entity

#### Requirement (31 list fields)

| Field Name | List ID | List Name | Items |
|-----------|---------|-----------|-------|
| status | 309 | Coverage Status | Blocked, Failed, N/A, No Run, Not Completed, Not Covered, Passed |
| req-priority | 298 | Priority | 1-Low, 2-Medium, 3-High, 4-Very High, 5-Urgent |
| req-product | 259 | All Projects | (empty list) |
| req-reviewed | 191 | Review Status | Not Reviewed, Reviewed |
| rbt-bsns-impact | 345 | RBT Business Impact Levels | A, B, C |
| rbt-custom-bsns-impact | 345 | RBT Business Impact Levels | A, B, C |
| rbt-custom-fail-prob | 349 | RBT Failure Probability Levels | 1, 2, 3 |
| rbt-custom-func-cmplx | 220 | RBQM Functional Complexity Levels | 1, 2, 3 |
| rbt-custom-risk | 251 | RBQM Risk Levels | A, B, C |
| rbt-custom-testing-level | 204 | RBT Testing Levels | 1-Full, 2-Partial, 3-Basic, 4-None |
| rbt-effective-bsns-impact | 345 | RBT Business Impact Levels | A, B, C |
| rbt-effective-fail-prob | 349 | RBT Failure Probability Levels | 1, 2, 3 |
| rbt-effective-func-cmplx | 220 | RBQM Functional Complexity Levels | 1, 2, 3 |
| rbt-effective-risk | 251 | RBQM Risk Levels | A, B, C |
| rbt-fail-prob | 349 | RBT Failure Probability Levels | 1, 2, 3 |
| rbt-func-cmplx | 220 | RBQM Functional Complexity Levels | 1, 2, 3 |
| rbt-ignore-in-analysis | 1 | YesNo | Y, N |
| rbt-risk | 251 | RBQM Risk Levels | A, B, C |
| rbt-testing-level | 204 | RBT Testing Levels | 1-Full, 2-Partial, 3-Basic, 4-None |
| rbt-use-custom-bsns-impact | 1 | YesNo | Y, N |
| rbt-use-custom-fail-prob | 1 | YesNo | Y, N |
| rbt-use-custom-func-cmplx | 1 | YesNo | Y, N |
| rbt-use-custom-risk | 1 | YesNo | Y, N |
| rbt-use-custom-tl-and-te | 1 | YesNo | Y, N |
| request-status | 194 | Requirement Status | 1-Requirements Setup Completed, 2-Test Plan Setup Completed, 3-Test Lab Setup Completed, 4-Running Tests in Quality Center, 5-Test Execution Completed, Cancelled, Closed, New |
| owner | — | UsersList | (users list) |
| request-assign-to | — | UsersList | (users list) |
| vc-checkin-user-name | — | UsersList | (users list) |
| check-out-user-name | — | UsersList | (users list) |
| vc-status | 170 | Version Status | Checked_In, Checked_Out |

#### Test (11 list fields)

| Field Name | List ID | List Name | Items |
|-----------|---------|-----------|-------|
| plan-status | 209 | Plan Status | Design, Imported, Ready, Repair |
| test-type | 320 | TestType | ALT-SCENARIO, ALT-TEST, BUSINESS-PROCESS, DATA-CASE, DB-TEST, FLOW, LR-SCENARIO, MANUAL, QTSAP-TESTCASE, QUICKTEST_TEST, SR-TEST, SYSTEM-TEST, TESTCENTER-TEST, VAPI-TEST, VAPI-XP-TEST, WR-AUTOMATED, WR-BATCH, XR-TEST |
| testing-framework | 224 | Testing Framework | JUnit, NUnit, TestNG |
| testing-tool | 260 | Testing Tool | LeanFT, Sahi, Selenium, SoapUI, Watir |
| severity | 279 | Severity | 1-Low, 2-Medium, 3-High, 4-Very High, 5-Urgent |
| priority | 298 | Priority | 1-Low, 2-Medium, 3-High, 4-Very High, 5-Urgent |
| owner | — | UsersList | (users list) |
| run-status | 248 | Test Running Status | Closed, Open |
| language | 228 | Language | Python, VBScript |
| vc-checkin-user-name | — | UsersList | (users list) |
| vc-status | 170 | Version Status | Checked_In, Checked_Out |

#### Defect (11 list fields)

| Field Name | List ID | List Name | Items |
|-----------|---------|-----------|-------|
| severity | 279 | Severity | 1-Low, 2-Medium, 3-High, 4-Very High, 5-Urgent |
| status | 237 | Bug Status | Closed, Fixed, New, Open, Rejected, Reopen |
| priority | 298 | Priority | 1-Low, 2-Medium, 3-High, 4-Very High, 5-Urgent |
| detected-in-rel | 35 | Versions | (empty list) |
| target-rcyc | — | Reference | (release cycle references) |
| target-rel | — | Reference | (release references) |
| owner | — | UsersList | (users list) |
| detected-by | — | UsersList | (users list) |
| responsible-tester | — | UsersList | (users list) |
| vc-checkin-user-name | — | UsersList | (users list) |
| check-out-user-name | — | UsersList | (users list) |

#### Run (5 list fields)

| Field Name | List ID | List Name |
|-----------|---------|-----------|
| state | 231 | Run State (Finished, Initializing, Run Failure, Running, Stopping) |
| test-type | 320 | TestType (18 types) |
| owner | — | UsersList |
| vc-checkin-user-name | — | UsersList |

#### Resource (5 list fields)

| Field Name | List ID | List Name | Notes |
|-----------|---------|-----------|-------|
| location | 317 | Resource Location | Local, Reference |
| res-type | 285 | Resource Type | **UNUSED** (6 items) |
| vc-status | 82 | VC Status | **UNUSED** (3 items) |
| owner | — | UsersList | |
| vc-checkin-user-name | — | UsersList | |

**Flags**:
- **List 285 (Resource Type)**: Referenced by `res-type` in resource entity but not in used-lists.json.
- **List 82 (VC Status)**: Referenced by `vc-status` in resource entity but not in used-lists.json.

#### Other Entities (8 list fields)

| Entity | Field | List ID | List Name |
|--------|-------|---------|-----------|
| design-step | execution-type | 217 | Testing Mode (DUAL_TEST, FUNCTIONAL_TEST) |
| test-instance | state | 356 | Status (Blocked, Failed, N/A, No Run, Not Completed, Passed) |
| test-instance | coverage-mode | 214 | Coverage Mode (All Configurations, Selected Configurations) |
| test-instance | operating-system | 183 | Operating System (Environment) |
| test-instance | browser | 304 | Browser (Environment) |
| test-set | state | 356 | Status |
| run-step | status | 356 | Status |
| run-step | language | 228 | Language |

---

## 3. The 4-List Delta

**Question**: Which lists appear in customization-lists.json (43 total) but not customization-used-lists.json (39 total)?

**Answer**: 4 lists are defined but not bound to any field:

| ID | Name | Item Count | Items |
|----|------|-----------|-------|
| 255 | Activity Status | 3 | Requirement Authoring, Requirement Review, Test Authoring |
| 82 | VC Status | 3 | Checked_In, Checked_Out, Read_Only |
| 285 | Resource Type | 6 | Data table, Environment variables, Function library, Recovery scenario, Shared object repository, Test Resource |
| 320 | TestType | 18 | ALT-SCENARIO, ALT-TEST, BUSINESS-PROCESS, DATA-CASE, DB-TEST, FLOW, LR-SCENARIO, MANUAL, QTSAP-TESTCASE, QUICKTEST_TEST, SR-TEST, SYSTEM-TEST, TESTCENTER-TEST, VAPI-TEST, VAPI-XP-TEST, WR-AUTOMATED, WR-BATCH, XR-TEST |

**Interpretation (INFERRED)**:
- **List 320 (TestType)** is likely UNUSED intentionally; test type is managed via the `type-id` Reference field (requirement type inheritance), not a LookupList.
- **Lists 255, 82, 285** may be legacy, deprecated, or available for custom field bindings via ALM admin UI (outside REST API scope).

---

## 4. Read-Only Surface

**Total read-only (Editable=false) system fields**: 191 across all entities.

**All 191 are System=true**, indicating ALM maintains these fields and workflow scripts or background processes drive their values.

| Entity | Read-Only Count | Examples |
|--------|-----------------|----------|
| requirement | 36 | id, last-modified, father-name, has-linkage, has-rich-content, vc-* (11 fields) |
| test | 38 | id, last-modified, tree-path, has-*, vc-* (11 fields), audit-id fields |
| run | 25 | id, last-modified, test-name, cycle-name, test-description, state (partially), vc-* |
| test-instance | 15 | id, last-modified, name, actual, expected, state |
| resource | 24 | id, last-modified, vc-* (9 fields), folder-name |
| run-step | 13 | id, last-modified, actual, expected, status |
| design-step | 9 | id, last-modified, has-params, expected |
| defect | 15 | id, last-modified, creation-time, vc-* (8 fields) |
| test-set | 11 | id, last-modified, tree-path, state |
| test-config | 8 | id, last-modified, vc-checkout-user-name |
| release | 7 | id, last-modified, creation-time |
| test-folder | 10 | id, last-modified |
| release-cycle | 6 | id, last-modified, creation-time |
| release-folder | 4 | id, last-modified |
| test-set-folder | 8 | id, last-modified |

**Virtual fields** (Verify=false, Virtual=true, all read-only):
- `has-linkage` (requirement, test, defect, run, run-step, test-instance)
- `father-name` (requirement)
- `tree-path` (test, test-set)

**Interpretation (INFERRED)**: Read-only system fields are **non-negotiable constraints**. Generator must never attempt to write to them; they are populated by ALM internals (version control, auditing, hierarchy, linked-entity metadata).

---

## 5. Multivalue Fields

**Total multivalue-capable fields**: 2 (both in requirement entity)

| Entity | Field Name | Type | Editable | Notes |
|--------|-----------|------|----------|-------|
| requirement | target-rcyc | Reference | Yes | Target release cycle; supports multivalue link |
| requirement | target-rel | Reference | Yes | Target release; supports multivalue link |

**Interpretation (INFERRED)**: Only Reference types support multivalue in this fixture. LookupList and UsersList fields do NOT support multivalue (SupportsMultivalue=false for all 77 list-bound fields). This constrains generator logic: requirement can link to multiple releases/cycles, but other entities cannot multi-select from choice lists.

---

## 6. Requirement Types

**Total types**: 8

| ID | Name | Has Direct Coverage | Risk Analysis Type | Is Document Root | Default Child Type ID | Notes |
|----|------|---------------------|-------------------|------------------|----------------------|-------|
| 0 | Undefined | Y | 0 | N | 0 | Default type; used for orphaned requirements |
| 1 | Folder | N | 2 | N | 0 | Container; cannot have direct coverage (INFERRED: structural hierarchy node) |
| 2 | Group | N | 2 | N | 0 | Container; cannot have direct coverage |
| 3 | Functional | Y | 1 | N | 0 | Leaf type; has direct test coverage |
| 4 | Business | N | 1 | N | 0 | Abstract/container type; no direct coverage |
| 5 | Testing | Y | 1 | N | 0 | Test-related requirement leaf type |
| 6 | Performance | Y | 1 | N | 0 | Performance requirement leaf type |
| 66 | Business Model | Y | 1 | N | 0 | Business modeling type; has direct coverage |

**Risk Analysis Type encoding** (INFERRED):
- `0`: No risk analysis applicable (Undefined)
- `1`: Enabled for risk analysis (Functional, Business, Testing, Performance, Business Model)
- `2`: No risk analysis (Folder, Group—structural only)

**Interpretation**: Type hierarchy allows nesting (Folder/Group containers holding Functional/Testing/etc. leaves). Risk analysis is available only on leaf and specific types, not containers. No document-root designation in this project; `is-document-root` is always 'N'.

---

## 7. Size Outliers

**Count**: 62 Memo/String fields with unusual size values.

| Size Value | Meaning | Count | Examples |
|-----------|---------|-------|----------|
| -1 | Unlimited | 52 | All Memo fields: description, comments, dev-comments, request-note, vc-checkin-comments, etc. |
| 99999 | Virtual truncation | 8 | Virtual fields: father-name (requirement), tree-path (test, test-set), folder-name (resource), *-name fields (run, test-instance) |
| 0 | Unused | 2 | Seen in some metadata configs but not in active entity fields |

### Memo Fields (Size -1, Unlimited)

**Pattern**: All Memo type fields uniformly sized -1, indicating server-side storage without API-enforced length limit.

**Editable Memo fields** (generator can populate):
- description (all entities), comments (requirement, defect, run, test, test-set), dev-comments (defect, test, requirement), request-note (defect, requirement)
- eparams, exec-event-handle, data-obj (test-instance), bpt-structure, iters-*, detail (run)
- mail-settings, dynamic-data, report-settings, cycle-config, comment (test-set)
- description, component-data, expected, actual, bpta-condition, bpt-path (run-step)
- expected, actual, description (design-step)
- data-filtering, data-obj (test-config)
- workflow (test-set-folder)

**Read-only Memo fields** (generator cannot populate):
- rbt-analysis-*, rbt-assessment-data (requirement), vc-*-comments, vc-checkin-comments (test, requirement, resource, run-step)

### Virtual String Fields (Size 99999)

**Pattern**: All are virtual (Virtual=true), read-only, system fields representing computed hierarchical or relational names.

| Field | Entity | Value Source |
|-------|--------|---------------|
| father-name | requirement | Parent requirement name (computed) |
| tree-path | test | Full path in test plan hierarchy |
| tree-path | test-set | Full path in test set hierarchy |
| folder-name | resource | Resource container name |
| test-name | run | Test name (denormalized from reference) |
| cycle-name | run | Cycle name (denormalized) |
| testcycl-name | run | Test cycle name (denormalized) |
| name | test-instance | Instance name (computed) |
| test-description | run | Test description (denormalized) |

**Interpretation (INFERRED)**: Size 99999 is a flag indicating virtual/computed field truncation behavior in the UI. Generator must treat these as read-only; values are populated by ALM reference resolution.

---

## Summary Data Model Constraints for Generator

1. **No Boolean type**: Use LookupList ID 1 (YesNo: Y/N) for user-editable flags; String for read-only computed flags.
2. **List bindings are entity-specific**: 77 total list fields bound; some lists (4) unused—check field-to-list mapping per entity.
3. **Read-only system fields (191 total)**: Never populate id, *-name, *-path, has-*, vc-* (version control), audit-*, or last-modified fields.
4. **Multivalue limited to References**: Only `target-rel` and `target-rcyc` (requirement) support multivalue linking; all LookupList/UsersList fields are single-value.
5. **Requirement type hierarchy**: Folder/Group containers (no coverage) can parent Functional/Testing/Performance leaves (with coverage). Risk analysis applies to leaf types only.
6. **Memo fields**: Treat as unlimited (size -1); encode rich text where supported; round-trip test required.
7. **Virtual truncation**: Size 99999 indicates computed/denormalized field; do not attempt to populate.

---

**Analysis Status**: COMPLETE  
**Recommendations**: Encode Boolean logic in field-level validation; list binding validation per entity; test round-trip fidelity for Memo fields with rich text markup.
