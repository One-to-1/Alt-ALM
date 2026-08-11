# Wave 2 / Agent 3 — Test Lab + Test Runs UI inventory, Manual Runner deep-dive (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-ui-feature-inventory.md`.

## Sources

| # | URL | Version | Client | Type |
|---|---|---|---|---|
| S1 | alm/en/26.1/online_help/Content/UG/t_run_tests.htm | 26.1 (also 24.1) | Desktop | Primary |
| S2 | alm/en/17.0/online_help/Content/UG/ui_manual_runner_wizard.htm | 17.0 | Desktop | Primary |
| S3 | alm/en/17.0-17.0.1/.../ui_manual_runner_run_details.htm | 17.0-17.0.1 | Desktop | Primary |
| S4 | .../ui_manual_runner_step_details.htm | 17.0-17.0.1 | Desktop | Primary |
| S5 | .../t_run_tests_manually.htm | 17.0-17.0.1 (also 24.1/25.1) | Desktop | Primary |
| S6 | alm/en/17.0/.../ui_auto_runner.htm | 17.0 | Desktop | Primary |
| S7 | .../ui_excecution_flow.htm | 17.0-17.0.1 (also 15.5) | Desktop | Primary |
| S8 | .../ui_execution_grid.htm | 17.0-17.0.1 (also 16.0) | Desktop | Primary |
| S9 | .../ui_automation_tab.htm | 17.0-17.0.1 | Desktop | Primary |
| S10 | alm/en/17.0/.../ui_on_test_failure.htm | 17.0 | Desktop | Primary |
| S11 | alm/en/17.0/.../ui_test_instance_details.htm | 17.0 | Desktop | Primary |
| S12 | alm/en/17.0/.../ui_host_manager.htm | 17.0 | Desktop | Primary |
| S13 | .../ui_test_runs_module.htm | 17.0-17.0.1 | Desktop | Primary |
| S14 | .../ui_purge_runs_wizard.htm | 17.0-17.0.1 | Desktop | Primary |
| S15 | alm/en/24.1/.../t_purge_runs.htm | 24.1 (also 25.1) | Desktop | Primary |
| S16 | alm/en/16.00-16.0.1/.../c_pinned_tests_sets.htm | 16.0 (also 25.1) | Desktop | Primary |
| S17 | .../c_test_exec_overview.htm | 17.0-17.0.1 | Desktop | Primary |
| S18 | .../alm_keyboard_shortcuts.htm | 17.0-17.0.1 | Desktop | Primary — exact keystrokes UNVERIFIED |
| S19 | alm/en/25.1/online_help/Content/Web_Runner/web-client.htm | 25.1 | Web | Primary |
| S20 | .../Web_Runner/RunTest.htm | 25.1 (also 24.1, 26.1) | Web | Primary |
| S21 | .../Web_Runner/view_test_runs.htm | 25.1 | Web | Primary |
| S22 | .../FAQs/WebRunner_FAQs.htm (feature-support matrix) | 25.1 (compares to 15.0.x) | Web | Primary |
| S23–S28 | REST Core: Overview, runs.html, run-steps.html, run-step_by_ID.html, test-instances.html, GET_runs_XML sample | Core | REST | Primary |
| S29 | design-steps_Collection.html | Core | REST | Secondary (name only) |
| S30 | Octane manual-test docs | — | — | **Discarded — wrong product** |

## Views

**Test Lab (desktop)** — Test Sets tab (tree) with per-set: **Execution Grid** (flat instance grid), **Execution Flow** (diagram with conditional arrows), **Timeslots** sub-area (Functional test sets). **Last Run Report pane** below grid with type-specific viewers (Sprinter Results Viewer, LoadRunner Analysis, UFT report, SYSTEM-TEST captured desktop image). **Live Analysis** tab (Performance sets, ALM Enterprise only).

**Test Runs module (desktop)** — separate module, three tabs: **Test Runs**, **Test Set Runs** (functional), **Build Verification Suite Runs** (ALM Edition). Lower pane: Comments, Report, Results, History, Event Log.

**Manual Runner (desktop)** — modal wizard, two pages: **Run Details** and **Step Details**, plus **Compact View**.

**Web Client (24.1+/25.1+)** — purpose-built web app (Dashboard, Releases, Requirements, Test Plan, Test Lab, Test Runs, Defects). Test Lab web: folders/sets + Execution-Grid equivalent; **no Execution Flow, no Analysis, no Automation tab**; automated runs only via **Test Execution Agent (TEA)** for UFT-type tests. Web Test Runs (24.1+): Details/Report/Steps/Attachments/Linked Defects/History tabs; supports linking defects.

## Actions inventory

| Action | Where | What it does | Client |
|---|---|---|---|
| Create test set folder / set | tree right-click / menu | builds structure; cycle, dates, attachments | Desktop + Web |
| Assign set to release/cycle | set Details tab | associates for reporting | Desktop |
| Copy/Cut/Paste set or folder | tree context menu | duplicate/move; folder delete offers "remove folder only (sets → Unattached)" vs delete all | Desktop, partial Web |
| **Pin test set to baseline** | tree right-click | locks tests to baseline versions; **deletes all existing runs on pin**; removes non-baseline tests; blocks instance copy/paste while pinned | Desktop only (S16) |
| Clear pinned baseline | tree right-click | releases association | Desktop only |
| Reset test set | Test Sets menu | UNVERIFIED — page not reviewed | Desktop |
| **Purge Runs** | Test Lab or Test Runs menu | 3-step wizard (Select Sets → Type of Purge → Confirm); background Task Manager job; runs only (not set-runs/BVS) | Desktop (S14, S15) |
| Mail / Export test set | menu | UNVERIFIED — not opened | Desktop |
| Select Tests (add instances) | Execution Grid/Flow pane | Test Plan Tree + Requirements Tree tabs; add all configs or specific config | Desktop + Web |
| Go to Test by ID | Web Test Lab | 25.1 P1 feature | Web only |
| Test Instance Details | grid double-click / Ctrl+Alt+D | Details, Runs, Execution Settings, Attachments, Linked Defects, History tabs | Desktop (S11) |
| **Host Manager** | Test Sets menu | add/delete hosts, host groups; Default sets' remote execution only | Desktop only (S12) |
| Run with Manual Runner | Run dropdown | launches wizard | Desktop |
| Run with Sprinter | Run dropdown | launches Sprinter | Desktop only |
| Continue Manual Run | Tests menu / Run dropdown | resumes paused run in original runner | Desktop + Web |
| Run with Automatic Runner | Run dropdown | Default sets only; local or remote hosts | Desktop only |
| Execution Flow conditions | Execution Flow tab | blue (after-previous), green (only-if-passed), black (after-completes) arrows; time-dependency icon | Desktop only (S7, S22) |
| Automation tab (set-level) | set Details | **On-Failure rules** (rerun count, cleanup test, on-final-failure: nothing/stop set/rerun set) + **Notification rules** (fail/env-failure/all-finished) + Execution Summary email | Desktop only (S9) |
| On Test Failure per-test override | Automation tab | per-test reruns/cleanup grid; Reset/Clear/Copy-Paste | Desktop only (S10) |
| View Test Runs | Test Runs module | filter/sort; per-run Details/Report/Results/History/Event Log | Desktop; simplified Web from ~24.1 |
| Link defect to run/step | Test Runs / within Manual Runner | step-link creates **indirect links** to run, instance, set, test | Desktop + Web (needs primary re-verify) |

## Manual Runner walkthrough (desktop, S2–S5)

1. **Launch**: Execution Grid/Flow → select instance(s) → Run dropdown → Run with Manual Runner. Manual AND automated tests can run through it.
2. **Run Details page**: run metadata (required fields red); Comments tab; Test Details (read-only view); **Operating System Information** (edit OS type/SP/build recorded on run); **Attach to Run**; **New Defect** (auto-links to run; Linked Defects dropdown); **Start Run** (Parameters dialog first if unassigned values — BPT handles parameters on Step Details instead); **End Run**; **Cancel Run** (multi-test batch: prompts cancel-remaining + save-progress).
3. **Step Details page**: step grid — Status, Description (editable), Expected, Actual columns + attachment/snapshot icons. Toolbar: **Add Step / Delete Selected** (not for BPT), **Pass Selected / Pass All**, **Fail Selected / Fail All**, **Show Parameters**, **Attach to Step / Attach to Run**, **New Defect** (auto-links to current step). Navigation: Previous/Next Step, **Compact View** (+ Back to Steps Grid), **Keep on Top**. **End Run** prompts to save edits; edited steps can optionally propagate back to design steps (step-level attachments without memo edit do NOT propagate). Column order/width adjustable; BPT steps render as expandable tree.
4. **Pause/resume**: **Continue Manual Run** reopens in the original runner.
5. **Results**: statuses flow back to Execution Grid + Test Runs module.

**Web Client equivalent (S20, S22)**: own Manual Runner from Test Lab grid (Run / Continue Manual Run). Step statuses: Passed, Failed, Blocked, N/A, Not Completed, No Run, **or custom statuses** (paraphrase-sourced — re-verify). Arrow-key step navigation. Attach-to-step/run; create/link defects in-run. Automated execution needs **TEA agent**; status auto-syncs. **No Execution Flow / Automation / Analysis tabs in Web** → on-failure rules, scheduling, notifications are desktop-only surfaces.

**Sprinter**: desktop-only alternative manual-execution tool; same Run dropdown; out of depth scope — Alt-ALM must decide ignore/stub/replicate (ADR).

## Feasibility first-pass

| Feature | Verdict | Endpoint(s) | Note |
|---|---|---|---|
| Create a run | **FULL** | POST .../runs (S24) | confirmed |
| List/read runs | **FULL** | GET .../runs (S24, S28) | |
| Create run step | **FULL** | POST .../runs/{id}/run-steps (S25) | sufficient for step-by-step capture |
| Read/update/delete run step | **FULL** | GET/PUT/DELETE .../runs/{id}/run-steps/{ID} (S26) | PUT confirmed → per-step status/actual edits reachable |
| List/create test instances | **FULL** | GET/POST .../test-instances (S27) | |
| Single test-instance CRUD | UNKNOWN | snippet-only | re-verify test-instances_by_ID |
| Execution Flow conditions | NOT-VIA-API / UNKNOWN | none found | sibling wave found none either (opaque description blob) |
| On-Failure / Notification rules | UNKNOWN | none found | likely desktop-only config |
| Host Manager / host groups | UNKNOWN | none found | Lab Management REST unexplored |
| Attach to run/step | PARTIAL/UNKNOWN | generic attachments | confirm run/run-step parent types |
| New Defect from step, auto-linked | PARTIAL | defects + link mechanism | indirect-link replication via REST UNVERIFIED |
| Pin to baseline | NOT-VIA-API / UNKNOWN | none found | |
| Purge Runs | UNKNOWN | none found | per-id DELETE only |
| Test Runs grid (read) | FULL | GET /runs + query | |
| Web custom step statuses | UNKNOWN | n/a | paraphrase-sourced |
| Automatic Runner trigger | UNKNOWN | none found | REST-reachable at all vs OTA? → execution-model ADR |

## UNVERIFIED
- Exact keyboard shortcuts (S18) — scheme confirmed; every specific binding needs raw-page re-read.
- Web custom step statuses; Web "auto status sync" vs desktop contrast mechanics.
- Defect auto-population field list from step/run/instance contexts.
- test-instances/{ID} CRUD (snippet-only).
- "Reset test set", "Mail/Export test set" pages unopened.
- Lab Management REST exposure for hosts/reservations.
- Attachments parent-type enumeration for run/run-step.
- On-Failure/Notification/Execution-Flow/Purge/Pin REST absence = not yet confirmed absence (UI-focused pass).
- Web version-introduction claims (Test Runs 24.1, parameters 25.1.x, version control 25.1 P1) — paraphrased FAQ table.

## Handoffs
- REST wave: confirm/refute UNKNOWN rows (execution flow, on-failure, hosts, purge, pin-to-baseline); verify test-instances/{ID}; attachments parent types.
- **Execution-model ADR: POST /runs + POST/PUT run-steps confirmed ⇒ a from-scratch REST-only manual runner is very plausibly buildable without OTA.** Weigh OTA only for Execution-Flow scheduling / On-Failure automation gaps.
- Generator spec: confirmed run+run-step chain and observed run fields (status, execution-date/time, duration, host, os-*, cycle-id, test-config-id) = concrete seeding field list.
- Sprinter decision needs an ADR (match Manual Runner only vs account for Sprinter-originated runs).
