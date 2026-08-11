# Wave 1 / Agent 5 — Test Lab domain: test-set folders/sets/instances, runs, run-steps, execution, hosts/timeslots (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

**Primary (OpenText/Micro Focus official):** REST_core pages for test-sets (+by-ID +sample), test-set-folders (+by-ID +sample), force-delete-children, test-instances (+by-ID +2 samples), runs (+by-ID +2 samples), run-steps (+by-ID +sample), test-executions (+by-ID), Overview (evergreen + 15.5-pinned), HTTP_Return_Codes, Create/Update_an_Entity, General_Notes_and_Limitations, 25.1 landing page; Project DB Reference (12.50+): td.TESTCYCL (topic595), td.RUN (topic495), td.CYCLE (topic206); UI help: t_purge_runs (25.1), Web_Runner/RunTest ("latest"), t_run_tests_manually, t_run_auto_tests; OTA docs (Host object, HostTimeOut) cited only to support REST-absence finding.

**Secondary (cross-checked):** community 186676 (testcycl-id vs cycle-id), community 183405 (Fast_Run auto-creation; snippet-only), LobsterMan blog (run-creation walkthrough), community 193570 (add test instance; required fields + locking scope).

## Findings

### 1. `test-set-folders`
- Collection GET/POST; single GET/PUT/DELETE.
- Fields (live sample): `last-modified, ver-stamp, attachment, workflow, hierarchical-path, name, description, no-of-sons, id, parent-id, order-id, assign-rcyc`. Sample root has `parent-id=0` (root sentinel CONSTRUCTED, high confidence — UNVERIFIED #1).
- `DELETE .../{ID}?force-delete-children=y|n` — default `n` **relocates children to "Unattached"**; `y` deletes.

### 2. `test-sets`
- Collection GET/POST; single GET/PUT/DELETE.
- Full field list (live sample): `os-config, pinned-baseline, ver-stamp, report-settings, description, order-id, request-id, has-linkage, exec-event-handle, last-modified, environment, open-date, subtype-id, attachment, close-date, mail-settings, cycle-config, name, dynamic-data, comment, id, parent-id, assign-rcyc, status`.
- **`assign-rcyc` = the assigned release cycle** (`CY_ASSIGN_RCYC` → RELEASE_CYCLES.RCYC_ID) — confirmed. `status`: Open/Closed. `parent-id` = test-set-folder id. `subtype-id` observed: `hp.qc.test-set.default` (other values UNVERIFIED #3).
- **Execution-flow/dependency data lives inside `CY_DESCRIPTION`** ("execution flow information including dependencies") — opaque encoding, UNVERIFIED #4.

### 3. `test-instances`
- Collection GET/POST; single GET/PUT/DELETE.
- Full field list (live sample): `test-id, os-config, data-obj, is-dynamic, exec-time, cycle, has-linkage, exec-event-handle, exec-date, last-modified, subtype-id, cycle-id, attachment, id, assign-rcyc, test-config-id, owner, pinned-baseline, ver-stamp, test-instance, host-name, order-id, eparams, iterations, environment, actual-tester, name, bpta-change-awareness, plan-scheduling-time, status`.
- **Binding fields**: `test-id` (design test), **`cycle-id` (= TEST SET id — legacy naming)**, `test-config-id`. Server assigns `id` — don't POST it.
- `order-id` = position in test set (TC_TEST_ORDER).
- **`test-instance` field is an ORDINAL** (instance number of the same test within one set, TC_TEST_INSTANCE), NOT a foreign key. (CONSTRUCTED from sample + DB ref.)
- `status` = **last-run status mirror** (TC_STATUS: "Last run status — Not Completed, Passed, Failed, etc."); never-run = `No Run`.
- `owner` = Responsible Tester (TC_TESTER_NAME) vs `actual-tester` = who executed last run (CONSTRUCTED triangulation, UNVERIFIED #5).
- `eparams` (instance) / `iters-params-values` (run): parameter actual values, format undocumented (UNVERIFIED #6).
- `subtype-id` observed: `hp.qc.test-instance.MANUAL`.

### 4. `runs`
- Collection GET/POST; single GET/PUT/DELETE (individual delete works).
- Full field list (live sample): `test-id, test-name, has-linkage, path, cycle-id, vc-version-number, draft, host, id, state, test-config-id, ver-stamp, iters-params-values, os-build, os-sp, name, testcycl-name, status, os-config, vc-locked-by, has-vtc, bpt-structure, cycle, duration, execution-date, last-modified, subtype-id, attachment, test-description, text-sync, assign-rcyc, owner, pinned-baseline, comments, iters-sum-status, bpta-change-detected, test-instance, cycle-name, os-name, environment, vc-status, execution-time, bpta-change-awareness, testcycl-id`.
- **THE confusing pair**: `cycle-id` → **Test Set** (RN_CYCLE_ID → CYCLE.CY_CYCLE_ID); **`testcycl-id` → Test Instance** (RN_TESTCYCL_ID → TESTCYCL.TC_TESTCYCL_ID). Independently corroborated by community 186676.
- DB-confirmed: `RN_DURATION` in **seconds**; `RN_STATUS` (Status list: Not Completed, Passed, Failed, Blocked, N/A); `RN_STATE` (separate "Run State" project list); **`RN_DRAFT` Y/N = draft run**; `RN_SUBTYPE_ID` literally "for future use" in DB doc yet populated `hp.qc.run.MANUAL`.
- **Run creation worked example** (CONSTRUCTED from blog; field names all match primary sample):
```xml
POST .../runs
<Entity Type='run'><Fields>
  <Field Name='name'><Value>…</Value></Field>
  <Field Name='test-instance'><Value>1</Value></Field>
  <Field Name='testcycl-id'><Value>{instance-id}</Value></Field>
  <Field Name='cycle-id'><Value>{testset-id}</Value></Field>
  <Field Name='test-id'><Value>{test-id}</Value></Field>
  <Field Name='subtype-id'><Value>hp.qc.run.MANUAL</Value></Field>
  <Field Name='status'><Value>Not Completed</Value></Field>
  <Field Name='owner'><Value>{user}</Value></Field>
</Fields></Entity>
```
then **separate PUT** to `runs/{id}` for final status (see Pitfall 3).
- Generic create/update mechanics apply (201 + Location; PUT 200 full entity; never resubmit read-only fields).
- **Locking/checkout NOT needed for Test Lab entities** (only versioned types: tests, requirements, resources).

### 5. `run-steps`
- Collection: `GET|POST .../runs/{Run ID}/run-steps`. POST: `parent-id` optional (URL's Run ID used); mismatch → fail.
- Single: `GET|PUT|DELETE .../runs/{Run ID}/run-steps/{ID}`.
- Full field list (live sample): `test-id, comp-status, description, rel-obj-id, obj-id, has-linkage, execution-date, path, desstep-id, attachment, has-picture, tree-parent-id, id, component-data, bpt-path, actual, step-order, level, expected, line-no, comp-subtype-name, extended-reference, name, execution-time, bpta-condition, parent-id, bpt-facet-type, status`.
- `description`/`expected`/`actual` are HTML-escaped rich text (`<html><body>…</body></html>`).
- **`desstep-id` links run-step → design step** (sample: equals design step id) — strong evidence run-steps mirror design steps; whether server auto-copies on run creation is UNVERIFIED #8 (key probe).
- `step-order`: 1-based integer.
- **Run-status aggregation from step statuses: NOT DOCUMENTED either way** — UNVERIFIED #9 (key probe for generator consistency).

### 6. Manual execution flow
- No "continue-manual-run" or manual-runner REST verbs exist. Web Client Manual Runner: sequential multi-instance runs, "Continue Manual Run", per-step or bulk status setting, add/delete/modify steps during run; run status auto-syncs to Desktop Client.
- **REST-only manual runner must be assembled from primitives** (CONSTRUCTED): POST run (Not Completed) → GET run-steps (check auto-population; else POST them) → per-step PUT (status/actual) → final PUT run status.

### 7. Automated execution
- **`test-executions`**: `POST .../test-executions` (XML only) — min field `external-id`; `external-type` = `TestSet` (default) | `TestInstance`. `GET .../test-executions/{ID}` only. Whether POST **dispatches** execution vs **registers external results** is not stated (UNVERIFIED #10; "external-*" naming suggests ingestion).
- Host assignment, scheduling, execution-flow order/dependencies: **UI-only** (Automatic Runner dialog: Run All/Selected, per-test Run on Host, host groups = first available host, scheduling). **No REST fields/endpoints found.**
- **hosts / host-groups / timeslots: no REST_core resources** (direct URL probes 404). Only addressable in **OTA (COM)**. Timeslots UI-only. **Likely not manageable via documented REST — load-bearing gap** (UNVERIFIED #11 → check 24.1+ Swagger).

### 8. Purging runs
- **Purge Test Runs = UI feature only** (needs Run>Delete permission; by-date, keep-last-N, draft-only, full vs steps-only; background PurgeRunsTask; cannot purge test-set runs or BVS runs). **No REST purge endpoint found**; only per-id DELETE (UNVERIFIED #12 → Swagger check).

## Pitfalls & behavioural notes

1. **`cycle-id` = Test Set; `testcycl-id` = Test Instance.** Most error-prone naming in the domain. Bare `cycle` field is vestigial/empty.
2. **`test-instance` is an ordinal, not an ID.**
3. **POST-then-PUT for run status**: status set directly in creation POST reportedly does NOT propagate to the instance; POST `Not Completed` then PUT final status does. Secondary-sourced — verify (UNVERIFIED #13).
4. **Fast_Run side effect**: PUT-ing status directly on a test-instance makes the server synthesize a `Fast_Run_<timestamp>` run. Generator should drive runs, not instance status (UNVERIFIED #14).
5. **Locking/checkout is a red herring for Test Lab writes.**
6. **Never resubmit GET-derived read-only fields** (400 "Read-only field").
7. `RN_SUBTYPE_ID` documented "for future use" — don't build semantics on it.
8. **force-delete-children default relocates to "Unattached"**, not delete.
9. **No 204 anywhere** — expect 200 on DELETE.
10. `has-linkage` flag on instance/run/run-step likely = linked defects (relations collection, sibling scope).

## Version differences
- Test Lab REST surface stable 15.5 → 26.1 (evergreen doc, pinned 15.5 copy matches; DB docs "12.50 and later" use same names).
- 24.1+ additions live only in embedded Swagger `/qcbin/api-doc/v2/` — unknown whether new Test Lab endpoints exist there (UNVERIFIED #15).
- `test-executions` predates 24.1 (in Core docs); intro version unknown (15.5.1 changelog mentions test-execution endpoints).
- SaaS vs on-prem: nothing Test-Lab-specific found.

## UNVERIFIED (probes)
1. Root folder id=0 → GET test-set-folders?query={parent-id[0]}; GET .../test-set-folders/0.
2. release-cycles/releases collections exist → GET both. **[Lead note: confirmed live — our metadata probe got 200 on both entity types' field metadata.]**
3. test-set subtype-id values → customization types.
4. CY_DESCRIPTION execution-flow encoding → configure dependency in UI, GET description, diff.
5. owner = Responsible Tester → set in UI, GET, confirm field.
6. eparams / iters-params-values format → parameterized test, set actuals, GET both entities.
7. Draft run creation via REST → POST with draft=Y.
8. **Auto-copy of design-steps → run-steps on run POST** → POST run for test with 3 design steps; GET run-steps immediately.
9. **Step-status → run-status aggregation** → PUT two steps Failed; GET run.
10. test-executions semantics (dispatch vs ingest) → POST against automated test set with hosts; poll.
11. hosts/timeslots in 24.1+ Swagger → browse api-doc/v2, search host/timeslot/lab.
12. REST purge in Swagger → search purge.
13. POST-then-PUT necessity → POST run with status=Passed; GET instance.
14. Fast_Run auto-creation → PUT instance status; GET runs?query={testcycl-id[…]}.
15. New Swagger Test Lab endpoints → browse.

## Handoffs
- Auth: 2-step flow + WS-Trust/SaaS note.
- Query grammar: query={…} usage; ?required=true.
- Attachments: `attachment` flag on all Test Lab entities.
- Defect linking: has-linkage + `relations` collection shape.
- Test Plan sibling: design-steps, test-configs (no dedicated test-configs collection page found under obvious names — double-check; load-bearing for instance binding).
