# Live-Instance Probe Log

Empirical findings from read-only probes against the designated sandbox instance. Everything here
was **observed directly**, not taken from documentation — when documentation and this log disagree,
this log wins for our target server. Host, domain, project, and credentials are never recorded here;
probe scripts live in `scripts/probe/` and read `Secrets/ALM_API_credentials.json` at runtime,
masking sensitive values in all output.

| | |
|---|---|
| Probe date | 2026-08-11 |
| Server self-reported version | `SiteVersion "20.0 (Build 20.00.0.143)"` via `GET /qcbin/rest/sa/version` — internal site-version numbering; mapping to the marketing version (24.1 / 25.1 / 26.1) pending the version-lineage research |
| Auth method | API key (client ID + secret) |
| Sandbox state | Effectively empty (0 defects), **1 project user** |

## Probe 1 — auth handshake (`scripts/probe/probe-auth.ps1`)

**VERIFIED end to end:**

1. `GET /qcbin/rest/is-authenticated` unauthenticated → **401**.
2. `POST /qcbin/rest/oauth2/login` with body `{"clientId":"…","secret":"…"}` (Content-Type
   `application/json`) → **200**, and sets cookies `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`,
   `ALM_USER`, `JSESSIONID` **in one step** — on this server, the oauth2 login establishes the full
   session, not just the LWSSO token.
3. `POST /qcbin/rest/site-session` → **201** (idempotent-looking session confirmation; harmless
   after oauth2 login — whether it is strictly required after oauth2/login is still open).
4. `GET /qcbin/rest/is-authenticated` with cookies → **200**.
5. `GET /qcbin/rest/domains` (Accept: application/json) → **200**; configured domain present.
6. `GET /qcbin/rest/domains/{d}/projects` → **200**; configured project present.
7. `GET /qcbin/authentication-point/logout` → **200**; session cookies dropped (only `JSESSIONID`
   remains).

**Implications:** the kickoff prompt's §21 hypothesis about a two-step LWSSO/QCSession dance is
*simplified* by API-key login on this server — one `oauth2/login` call yields a working session.
Candidates `POST /qcbin/api/authentication/sign-in` and Basic-auth against
`/qcbin/authentication-point/authenticate` were not needed (untested beyond not being required).

## Probe 2 — customization metadata (`scripts/probe/probe-metadata.ps1`)

**Server version:** `GET /qcbin/rest/sa/version` → 200 (shape above). `rest/server/version`,
`api/server/version`, `rest/site/version` → 404.

**Field metadata:** `GET …/customization/entities/{entity}/fields` with `Accept: application/json`
returns JSON (`{"Fields":{"Field":[…]}}` shape) for **all 15 entity types probed**:

| entity | fields | type identifiers observed |
|---|---|---|
| requirement | 74 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| test | 57 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| design-step | 12 | DateTime, Memo, Number, String, UsersList |
| test-config | 15 | Date, DateTime, LookupList, Memo, Number, String, UsersList |
| test-folder | 14 | DateTime, Memo, Number, String |
| test-set-folder | 15 | DateTime, LookupList, Memo, Number, Reference, String |
| test-set | 29 | Date, DateTime, LookupList, Memo, Number, Reference, String |
| test-instance | 32 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| run | 51 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| run-step | 29 | Date, LookupList, Memo, Number, String, UsersList |
| defect | 42 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |
| release | 12 | Date, DateTime, Memo, Number, String |
| release-cycle | 11 | Date, DateTime, Memo, Number, String |
| release-folder | 7 | Memo, Number, String |
| resource | 32 | Date, DateTime, LookupList, Memo, Number, Reference, String, UsersList |

**The complete set of field-type identifiers observed on this server (8):**
`String`, `Memo`, `Number`, `Date`, `DateTime`, `LookupList`, `UsersList`, `Reference`.
Notably absent: any float/boolean/tree types — booleans presumably surface as `LookupList`
(e.g. Yes/No lists) or `String`; to be confirmed against field-level metadata in the fixtures.

**Lists:** `GET …/customization/used-lists` → 200, **39 lists**; `GET …/customization/lists` →
200, **43 lists**. Both exist; the 4-list delta = lists not bound to any field (to be confirmed
from documentation).

**Requirement types:** `GET …/customization/entities/requirement/types` → **200** (fixture saved).

**Users:** `GET …/customization/users` → 200, **1 user** (count only; user data is never saved to
fixtures). ⚠️ Generator implication: `UsersList` fields can only reference real project users —
with one user, user-distribution realism collapses. Ask the admin to add a handful of dummy users
to the sandbox.

**Entity envelope (JSON):** `GET …/defects?page-size=1` → 200 with top-level keys
`entities`, `TotalResults` (= 0; project empty).

## Fixtures captured (redacted; under `tests/fixtures/`)

- `customization-fields-<entity>.json` × 15
- `customization-used-lists.json`, `customization-lists.json`
- `customization-requirement-types.txt`

Redaction = host/domain/project/key strings replaced with `REDACTED` before write. User data and
entity data are not captured.

## Open items for the next probe round

1. Map `SiteVersion 20.0 (20.00.0.143)` → marketing version via the lineage research.
2. Is `site-session` required after `oauth2/login`, or fully redundant? (Skip it, observe.)
3. XSRF: confirm the `X-XSRF-TOKEN` header requirement on the first WRITE probe (sandbox write —
   only after explicit user confirmation of the sandbox project).
4. Rich-text round-trip fidelity (the big one) — needs a write probe.
5. Whether `Accept: application/json` works on every collection or only some (observed: yes on all
   probed so far).
6. Booleans: inspect saved field fixtures for how yes/no fields are typed.
