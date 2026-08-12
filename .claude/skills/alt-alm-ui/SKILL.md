---
name: alt-alm-ui
description: Alt-ALM front-end conventions — metadata-driven form/grid/filter patterns, the 8-type renderer registry, design tokens, density, error/loading states, and accessibility. Load before writing any SPA code.
---

# Alt-ALM UI conventions

Front end for the Alt-ALM ALM/QC alternative client. Stack: **React + TypeScript SPA** talking to the
Alt-ALM **BFF** (Java 21 + Spring Boot) — never to `/qcbin` directly (ADR 0001; browsers cannot reach
it through CORS + cookie session + XSRF).

Design source of truth: `docs/adr/0005-metadata-driven-rendering.md`, `docs/plan/architecture.md`.
Feature scope and what is genuinely impossible: `docs/research/feasibility-matrix.md`.

---

## 1. The one rule: nothing about ALM's schema is known at build time

ALM field metadata is **per project and admin-editable at any time**. The SPA ships **zero** hardcoded
field lists, list values, root IDs, or entity form layouts. Everything renders from metadata the BFF
fetched at runtime and cached per project.

| Never in product code | Fetch instead |
|---|---|
| Field lists / form layouts | `customization/entities/{e}/fields` via BFF metadata endpoint |
| Dropdown values | the field's own `List-Id` → list values |
| User pickers | live project user list |
| Tree root IDs | runtime discovery `?query={parent-id[0]}` |
| Requirement types / test subtypes | `customization/entities/{e}/types` |

The default root table (requirements `0`, test-folders `2` "Subject", test-set-folders `0` "Root") is a
**sanity-check assertion only** — warn on divergence, never substitute it for discovery. Release-folder
root is `UNVERIFIED`. This discipline is what caught a real contaminated-root bug during probing.

**No silent fallbacks.** If metadata can't load, render an explicit error state. A form built from
stale or guessed fields is worse than no form — it silently omits required fields or offers fields the
project doesn't have.

---

## 2. The field-renderer registry (the only place type logic lives)

Exactly **8 field types** exist, probe-confirmed across 15 entities / 432 fields. There is **no Boolean
type**. One registry keyed on these 8 covers the entire surface — no per-entity special-casing, no
"unknown type" fallback branch.

| Type | Control | Notes |
|---|---|---|
| `String` | text input | `Size` caps length; `Size=99999` marks a **virtual/computed** field → render read-only |
| `Memo` | rich-text editor | `Size=-1` (unlimited); see §4 |
| `Number` | numeric input | |
| `Date` | date picker | wire format `yyyy-MM-dd` |
| `DateTime` | date+time picker | wire format `yyyy-MM-dd HH:mm:ss`; timezone rule `UNVERIFIED` — send server-local |
| `LookupList` | select, options from the field's `List-Id` | **`List-Id` 1 = the Y/N list** → render as a checkbox/toggle. This is the *only* boolean-ish control |
| `UsersList` | user picker from the live project user list | single-value always |
| `Reference` | entity picker (tree or search) | the **only** type that is ever multivalue |

**Multivalue is vanishingly rare:** exactly two fields in the whole model support it
(`requirement.target-rel`, `requirement.target-rcyc`, both `Reference`). Drive the multi-select purely
off `SupportsMultivalue`; never assume it from the type.

**Descriptor flags drive the control, not the entity name:**
`Required` → validation + marker · `Editable=false`/`System=true` → read-only (191 of 432 probed fields
are both) · `Visible`/`VisibleInWebUI` → inclusion · `Filterable` → offer in the filter builder ·
`Groupable` → offer in group-by · `Searchable` → include in search.

**UDFs are not special.** `user-NN` fields arrive in the same descriptor stream and flow through the
same registry with no separate code path.

---

## 3. Grids, trees, filters

- **Grid columns** come from metadata (`Visible`, `Label`), with user-configurable column sets
  persisted per user+entity in Alt-ALM's own store — not in ALM.
- **Paging**: server-side. `page-size` is silently capped at 2000 by ALM; `start-index` is **1-based**.
  Always show total from `TotalResults`. Prefer narrowing the query over deep offsets.
- **Trees**: build breadcrumbs client-side by walking `parent-id` — ALM does **not** return the
  desktop client's "Subject\..." path. Lazy-load children; `no-of-sons` tells you whether to show an
  expander without fetching.
- **Filter builder** must respect the Core query grammar's real limits (`alm-api` skill has the full
  cheat-sheet): **AND only between fields** (no cross-field OR — issue multiple requests and merge
  client-side if a user asks for it); AND/OR/NOT allowed *inside* one field; `*` wildcard. There is
  **no documented null-test and no documented escaping rule** — both `UNVERIFIED`. Until settled, the
  filter builder must either reject or explicitly quote-and-flag free-text values containing
  `' " ; [ ] ( ) ,` rather than silently producing a malformed query.
- **Sorting**: `order-by` works on collections only. `Reference` fields sort by the referenced
  *display value*, not the id — say so in the UI when it surprises.
- Grouping via server `groups/{field}` is possible but its `alm-web` response shape is `UNVERIFIED`;
  **client-side aggregation is the default** until a probe settles it.

---

## 4. Rich text and attachments

Memo fields store a **complete `<html><body>…</body></html>` document**, not a fragment, and the server
**sanitizes and re-formats** what you send.

- Survives: `<font>`, inline `style=`, `href`, tables (with `<tbody>` auto-injected).
- Stripped: `<script>`, and any `<img src>` that isn't absolute-`https://` or a `data:` URI.
- Whitespace is re-pretty-printed. **Never diff raw bytes** to detect user edits — canonicalize first,
  or you will show phantom "unsaved changes".
- `has-rich-content` flips N→Y automatically; don't set it.
- **Editor toolbar must not offer markup the sanitizer drops.** The allowed set is
  deployment-specific (`sanitizer-whitelist.xml`) — verify per target deployment, don't assume this
  sandbox's subset transfers.
- **Embedded images**: upload as an attachment (`ref-subtype=1`) via the BFF, then reference it by
  **absolute REST URL** or `data:` URI. A bare filename silently loses its `src`.
- Literal `<` / `>` typed by a user in a plain-text field must be entity-encoded before it reaches a
  memo field, or the sanitizer eats it as a tag.

---

## 5. Write UX: the server is less helpful than the stock client

REST writes **bypass ALM's workflow scripts** by default. The stock client's field defaulting,
derived values, and status-transition rules simply do not run for us. Consequences for the UI:

- Validation is **ours** (BFF, built from metadata) — surface it inline, before submit.
- Any status value is settable; there is no server-side state machine to lean on.
- **Never claim a write failed on a 5xx.** The BFF re-queries to determine the real outcome; the UI
  shows "verifying…" then a definite result — never an error toast that might be a lie.
- Optimistic concurrency uses `ver-stamp` / entity locks; surface conflicts as "someone else changed
  this", with a refresh-and-merge path.

**Feature honesty.** Where ALM's API genuinely can't do something (see the feasibility matrix), the UI
must say so plainly rather than silently degrading. Two specific cases with committed copy
requirements:
- **History/audit is partial** — only some field changes are recorded; creates and memo edits are
  invisible. The History view must state this, or users will read absence as "nobody edited it".
- **Runs are created indirectly** (setting an instance status synthesizes the run, and its name is
  server-generated). Don't present a "New Run" form that implies naming control.

---

## 6. Design tokens, density, accessibility

This is a **data-dense professional tool** used all day. Bias to information density over whitespace,
but never at the cost of legibility.

- **Tokens, not literals.** Colour, spacing, radius, type scale, and elevation live as CSS custom
  properties in one theme file. No hex codes or magic pixel values in components.
- **Three density modes** (comfortable / compact / condensed) driven by a single row-height +
  spacing-scale token pair, user-switchable and persisted. Grids must stay usable at ~28px rows.
- **Light and dark themes** are both first-class; define the full palette on `:root` and override only
  the tokens in the dark block. Never let a component define a colour only inside a media query.
- **Status colour is never the only signal** — pair every status/severity colour with text or an icon
  (colour-blind users, and ALM statuses are semantically dense).
- **Keyboard first**: full tab traversal, arrow-key grid navigation, Enter to open, Esc to cancel, and
  a visible focus ring that survives theming. Power users of the desktop client expect this.
- **Accessible names on everything**: icon-only buttons need labels; grids need proper
  header/row semantics; trees need `aria-expanded`/`aria-level`; live regions announce async results.
- Respect `prefers-reduced-motion`.
- Target **WCAG 2.1 AA** contrast for text and UI boundaries.

---

## See also

- `alm-api` skill — transport, auth, query grammar, error codes, call recipes (load it first).
- `alm-entity-model` skill — entities, fields, relationships, naming traps.
- `docs/research/feasibility-matrix.md` — per-feature verdicts, and what to tell users is impossible.
- `docs/plan/architecture.md` — BFF module boundaries and data-flow walkthroughs.
