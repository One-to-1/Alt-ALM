# ADR 0005 — Runtime metadata-driven rendering, no hardcoded schemas

- Status: Accepted
- Date: 2026-08-12

## Context

`CLAUDE.md` names this as a known design problem to solve, not assume away: "ALM field metadata is
**per project and customizable**. Forms and grids must render from metadata fetched at runtime, never
from hardcoded schemas." Research confirms both the necessity and the shape of the fix:

- **Field metadata is genuinely per-project.** `customization/entities/{entity}/fields` returns a full
  descriptor set (`Name`, `PhysicalName`, `Label`, `Size`, `Required`, `System`, `Type`, `Editable`,
  `Filterable`, `Groupable`, `SupportsMultivalue`, `Visible`, `Searchable`, `VersionControlled`,
  `List-Id`, …) confirmed live on 15 entity types, 432 fields total (api-ref §6.8, data-model §1). UDFs
  (`user-NN`/physical `XX_USER_NN`) appear in the same descriptor stream as system fields — up to 99 per
  entity, memo UDFs capped at 5 (15 with `EXTENDED_MEMO_FIELDS=Y`) — with no separate discovery path
  (api-ref §6.8).
- **The type system is small, closed, and fully enumerated by probe: exactly 8 field types** —
  `String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`, `Reference` — confirmed on
  every one of 15 probed entity types, with a doc-research finding independently landing on the
  identical 8 identifiers ("matches our live probe exactly," api-ref §8). **No Boolean type exists.**
  Yes/No semantics run through three encodings, none of them a togglable boolean field type in the
  metadata: `LookupList` bound to list id 1 ("YesNo": Y/N) for genuinely editable flags, plain
  read-only/system `String` for most flag-shaped fields (`has-*`, `is-*`), or `Number` for computed
  counters (api-ref §8). A renderer registry keyed on 8 known types, with Y/N handled as a `LookupList`
  special case, covers the entire observed surface with no residual "unknown type" fallback needed.
- **List-Ids and tree roots are instance-specific, not just per-deployment.** List bindings (39
  `used-lists`, 43 `lists` on the sandbox) and root `parent-id` values differ per project — the
  requirement root was confirmed `0` only after a contaminated-orphan false start at `1` was corrected
  by a clean-state re-probe (data-model §2.1, §2.4 "Conflicts adjudicated" #1); the test-folder root
  ("Subject") was confirmed project-specific by discovering exactly one hit for
  `test-folders?query={parent-id[0]}` on *this* project (api-ref §6.4); the release-folder root remains
  `UNVERIFIED` precisely because it was never discovered via the same runtime-query pattern the other
  three roots were (data-model §2.1, §7). This is direct, repeated evidence that hardcoding any root or
  List-Id — even a value confirmed correct once — is unsafe across projects, and unsafe even *within*
  one project if state changes (the orphan-record episode).
- **Requirement types are an 8-row, per-project-discoverable enumeration** (`customization/entities/
  requirement/types`, api-ref §6.1) with real behavioural consequences: "passing fields that don't
  belong to the requirement's type is an error" is a direct-quoted doc finding (api-ref §6.1) — the
  generator and any create/edit UI must know each requirement's type-scoped field set from metadata,
  not assume a universal requirement shape.

## Decision

Forms, grids, and filters render from **runtime-fetched customization metadata** — fields (with all 8
types), lists, users, requirement types/subtypes — cached per project by the BFF's metadata service
with explicit invalidation (`architecture.md` §2.2), never from a schema baked into the SPA or BFF at
build time.

- **Roots are discovered at runtime**, via `?query={parent-id[0]}` against each tree collection
  (requirements, test-folders, test-set-folders, release-folders), per the pattern already proven for
  three of the four trees (data-model §2.1). The **user-supplied default root table**
  (`_lead-decision-brief.md` D5; requirement root 0, test-plan folders 2/1001, test-set folders 0/1) is
  retained in the metadata service as a **documented sanity-check assertion** the discovery result is
  compared against — logged as a warning if they diverge, never used as a substitute for the discovery
  query itself. This distinction matters because it is exactly the discipline that caught the
  contaminated-orphan false start (data-model §2.1) rather than shipping a hardcoded `1`.
- **One field-renderer registry, keyed on the 8 field types.** `LookupList` bound to list-id 1 renders
  as the Y/N control; every other `LookupList`/`UsersList` binding renders from that field's own
  `List-Id`/live user list, fetched through the metadata service, never assumed. This single registry
  is deliberately the *only* place type-specific rendering logic lives — no per-entity special-casing
  of, say, "the requirement form" versus "the defect form" beyond what the metadata itself expresses
  (required/editable/visible flags, type-scoping).
- **UDFs render automatically** through the same metadata path as system fields — `user-NN` fields
  carry no separate code path, discovery mechanism, or renderer; they arrive in the same
  `customization/entities/{entity}/fields` response and flow through the same registry (api-ref §6.8).
- **Per-project caching with explicit invalidation.** Metadata is cached because re-fetching it on
  every render would be wasteful, but the cache is project-scoped (not global — customization is
  per-project, api-ref §6.8) and invalidated explicitly (manual refresh action) rather than trusted
  indefinitely, since project admins can and do change customization at any time.

## Consequences

- The SPA and BFF carry **zero hardcoded ALM field lists, list-value enumerations, or root IDs** in
  product code — every one of those lives in cache, sourced from a live metadata call, matching
  `CLAUDE.md`'s instruction directly.
- New/changed custom fields, new UDFs, or a changed list on the target project appear in Alt-ALM after
  the next metadata refresh, with no Alt-ALM code change — this is the direct payoff of the decision,
  not an incidental benefit.
- The renderer registry has a bounded, closed surface (8 types) to build and test once, rather than an
  open-ended "handle whatever field shows up" problem — the type-enumeration research (api-ref §8) is
  what makes this tractable rather than aspirational.
- The BFF's validation layer (`architecture.md` §2.2, ADR 0004) reuses the *same* metadata fetch the
  renderer registry uses for Required/Editable/List-binding checks — one source of truth for "what does
  this field allow," not two systems that could drift.
- Cost: every new project connection requires an initial metadata-priming fetch before the UI is fully
  usable, and cache invalidation bugs (stale metadata after an admin changes customization) are a real
  operational risk class this design accepts in exchange for never hardcoding schemas. The BFF surfaces
  a manual "refresh metadata" action specifically to give users a lever against that risk.
- The generator (D6) inherits the same discipline: its field-type→strategy matrix keys off the same 8
  types and the same runtime-fetched Required/Editable/System=read-only flags (191 of 432 probed fields
  are `Editable=false AND System=true`, api-ref §8) — it cannot ship a fixed "how to fake data for a
  requirement" template independent of the target project's actual customization.

## Alternatives considered

- **Hardcode a schema per entity, generated once from the sandbox and checked into the repo.** Rejected
  outright — this is the exact failure mode `CLAUDE.md` calls out by name, and the research corpus gives
  concrete, repeated evidence it would break silently: the requirement root differed between a
  contaminated probe run and clean state on the *same* sandbox (data-model §2.1), and the test-folder
  root is stated to be project-specific, meaning a schema snapshot taken against this sandbox would
  already be wrong on a different project.
- **A hybrid: hardcode the 8-type renderer registry's *shape* but ship default field lists as a
  fallback when live metadata is unreachable.** Considered and rejected for the mainline design: a
  silent fallback to stale/wrong field lists on a metadata-fetch failure is worse than a clear "cannot
  load form, metadata unavailable" error state, because it risks presenting a form that silently omits
  required fields or offers fields the current project doesn't have — exactly the kind of confident
  fabrication `CLAUDE.md` warns against, applied to UI instead of documentation. The renderer surfaces
  a loading/error state instead.
- **Cache metadata with no invalidation (fetch once at BFF startup, never refresh).** Rejected: field
  metadata is explicitly project-admin-editable at any time (customization is a documented, expected
  ALM admin activity, not a rare event) — a never-refreshed cache would drift from the live schema
  indefinitely with no operator lever to fix it short of restarting the BFF.
