# Wave 1 / Agent 1 — Authentication, Sessions & API Plumbing (verbatim subagent report)

> Persisted unedited. Reconciled version lands in `docs/research/alm-api-reference.md`.

# ALM / Quality Center REST API — Authentication, Sessions & API Plumbing Research Report

**Product confirmed throughout:** OpenText Application Lifecycle Management / Quality Center (formerly HP ALM / HP Quality Center → Micro Focus ALM/QC → OpenText ALM → "OpenText Application Quality Management"). All findings below are from `/qcbin`-rooted REST APIs (`/qcbin/rest/...`, `/qcbin/api/...`, `/qcbin/authentication-point/...`), Domain/Project-organized. No ALM Octane content is included as a finding (Octane material surfaced in searches was discarded — flagged inline where it had to be explicitly excluded).

## Sources

**Primary (OpenText/Micro Focus ADM Help Centers — official REST API Reference doc set, "REST API Reference (Core)", nominal baseline "15.5 and later" unless noted):**

1. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Authenticate.html — auth-method comparison table
2. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/alm-authenticate.html — `alm-authenticate` resource
3. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/before_API_call.htm — pre-call checklist
4. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/send_xsrf_header.htm — XSRF header rule
5. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/sign_in.htm — `/qcbin/api/authentication/sign-in`
6. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API-shared/login.htm — `/qcbin/rest/oauth2/login` (shared Core/Site-Admin resource)
7. https://admhelp.microfocus.com/alm/api_refs/site_admin_rest/Content/SA_REST_API/Authenticate.html — Site Admin REST API auth (doc set covers 15.0–16.0.1)
8. https://admhelp.microfocus.com/alm/api_refs/site_admin_rest/Content/SA_REST_API/Welcome.html — Site Admin REST API scope/overview
9. https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/WebAdmin/api_key_mgmt.htm — API key management (v26.1)
10. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Session_Management.html — session lifecycle, timeouts
11. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Handle_an_Exception.html — error envelope rules
12. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/logout.html — `/qcbin/authentication-point/logout`
13. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/sign-out.html — `/qcbin/api/authentication/sign-out` (deprecated)
14. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/Exception_json.htm — JSON exception shape
15. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/exceptionHTMLexample.htm — default HTML error page
16. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/HTTP_Return_Codes.html — status-code catalogue
17. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Exceptions.html — exception-Id catalogue
18. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/is-authenticated_new.html — `/qcbin/v2/rest/is-authenticated` (recommended)
19. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/is-authenticated.html — `/qcbin/rest/is-authenticated` (legacy)
20. https://admhelp.microfocus.com/alm/en/25.1/online_help/Content/WebAdmin/sso-enable-sso.htm — SSO enablement (v25.1)
21. https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/SSO/configure_SSO_API.htm — "Configure SSO for APIs" (v26.1)
22. https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/Security/ALM_security_site_settings.htm — security site parameters (v26.1)
23. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/WhatsNew.htm — REST API changelog by version
24. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Overview.html — REST API architecture overview
25. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/health-check.html — `/qcbin/healthcheck`
26. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/REST/site-session.html — `site-session` resource (states "12.60 and later")
27. https://admhelp.microfocus.com/alm/api_refs/REST_core/Content/REST_API_Core/General/Restrict_access.html — `RESTAPI_ACCESS_APIKEY_ONLY` / whitelist
28. https://admhelp.microfocus.com/alm/api_refs/site_params/metadata.htm — site-parameter reference with defaults

**Secondary (community/blog — used only for corroboration, each cross-checked against a primary source above):**

- https://community.opentext.com/devops-cloud/alm-qc/f/discussions/192256/reagaring-alm-license-consumption-of-rest-api — OpenText staff confirming REST consumes no license
- https://community.opentext.com/devops-cloud/alm-qc/f/discussions/361332/qc-alm-support-tip-how-to-get-site-session-for-rest-api-for-alm12-x/607325 — ALM 12.x worked example (community "support tip")
- https://community.opentext.com/devops-cloud/alm-qc/f/discussions/33386/one-alm-qc-api-session-shows-up-as-lots-of-connections-in-site-admin — cookie-container reuse pitfall (unresolved thread)
- https://community.opentext.com/devops-cloud/aqm/f/discussions/523020/... — SSO + `LogOnWithApiKey`, but this is the **legacy Site Admin COM/VBScript API** (`SAClient80MP.SAapi`), not REST — flagged as tangential
- https://aneejian.com/hp-alm-rest-api-authentication/ — blog worked example (C#), corroborates `sign-in`/cookie shapes
- https://gist.github.com/ojacques/c86001cf490a66f60dc62307b05af841 — community curl worked example, corroborates 2-step flow

**Explicitly discarded (do not treat as findings):** an ALM Octane licensing page that surfaced in search results (`admhelp.microfocus.com/octane/...op-how_manage_licenses.htm`) — wrong product per the disambiguation rule, excluded from all licensing claims; Micro Focus KB `doc.php?id=3046516` ("413 request entity too large") — verified on fetch to be a **Novell iPrint/NetWare** article, unrelated product, excluded; `admhelp.microfocus.com/alm/en/12.55/api_refs/REST_TECH_PREVIEW/.../Authenticate.html` — 404 at fetch time, not used.

---

## Findings

### 1. Authentication endpoints across versions

The Core REST API doc's **Authenticate** page (source 1) gives an explicit comparison table of five sign-in mechanisms. Reproducing its content (paraphrased from the fetched table, columns = Method / Browser support / Non-web-client support / SSO support / Versions):

| Method & path | Verb | Browser | Non-web | SSO (non-hybrid) | Versions |
|---|---|---|---|---|---|
| `/qcbin/authentication-point/alm-authenticate` (Recommended) | POST | No | Yes | No (hybrid mode only, 25.1 P1+) | 12.5x+, excludes 14.x |
| `/qcbin/rest/oauth2/login` (Recommended) | POST | No | Yes | Yes | 12.6x, 14.00 P2+, 15.x+ |
| Resource URL + `login-form-required=y` (Login Form) | GET | Yes | No | Yes | 12.5x+ |
| `/qcbin/authentication-point/authenticate` | GET | No | Yes | No (hybrid only, 25.1 P1+) | 12.5x+, excludes 14.x |
| `/qcbin/api/authentication/sign-in` | POST | No | Yes | No (hybrid only, 25.1 P1+) | 12.5x+, excludes 14.x |

Per the REST API changelog (source 23, "WhatsNew"), as of **ALM 17.0**, `/qcbin/authentication-point/authenticate` and `/qcbin/api/authentication/sign-in` were flagged for **future deprecation**, and `alm-authenticate` gained **JSON** support (it was XML-only before). The dedicated `sign_in.htm` page (source 5) independently confirms: *"This method will be deprecated in a future version. We recommend you use the alm-authenticate method to get authenticated"* — yet that same page carries a "last updated March 31, 2026" stamp, i.e. it is still live and undeprecated as of this research date. Treat `sign-in` as legacy-but-present, not yet removed.

**`alm-authenticate`** (source 2) — the currently recommended primary endpoint:
- `POST /qcbin/authentication-point/alm-authenticate`
- XML body: `<alm-authentication><user>username</user><password>password</password></alm-authentication>`
- JSON body (17.0+): `{"alm-authentication": {"user": "apikey-mnjpgeffaobeqmqimtlp", "password": "ceoacdmiepledgep"}}` — **this exact example is copied from the doc itself**; the doc uses an `apikey-`-prefixed value as the sample "user" to demonstrate API-key credentials flowing through this same endpoint (see §3 below — the "prefix convention" is my reading of the doc's own worked example, not a separately narrated rule, so treat the *prefix mechanic* as CONSTRUCTED-from-example even though the *literal example string* is verbatim-cited).
- Response: `201` = authenticated, sets `LWSSO_COOKIE_KEY` cookie only. `401` = failed, with header `WWW-Authenticate: ALMAUTH`.
- Explicitly does **not** open an application session — a separate `site-session` POST is required.
- Security note (verbatim): *"User and password are sent unencrypted in XML format. To encrypt, use HTTPS protocol."*
- 25.1 P1+: under hybrid SSO mode, local (non-IdP) users can use this endpoint with username/password even though SSO is enabled site-wide.

**`/qcbin/api/authentication/sign-in`** (source 5) — one-shot alternative:
- `POST` only, **no XML/JSON body** — credentials go in `Authorization: Basic base64(user:pass)` or `Authorization: Basic base64(apikey:apikeysecret)` (API key form available 15.00+).
- Response: `200 OK`, and — unlike `alm-authenticate` — it sets **all four** cookies in one call: `ALM_USER`, `LWSSO_COOKIE_KEY`, `QCSession`, `XSRF-TOKEN`. This makes it a 1-step alternative to the 2-step `alm-authenticate` → `site-session` flow.

**`/qcbin/rest/oauth2/login`** (source 6, shared between Core and Site-Admin REST — also documented verbatim on the Site Admin Authenticate page, source 7) — the documented API-key-oriented one-shot call:
- `POST`, `Content-Type: application/json`, body `{"clientId": "oauth2-[client_id]@[company]", "secret": "[api_key_secret]"}`.
- `200 OK`, empty body (`Content-Length: 0`). Sets `JSESSIONID` (HttpOnly), `LWSSO_COOKIE_KEY` (HttpOnly), `QCSession` (HttpOnly, may carry a Base64 client-type suffix — see §2), `ALM_USER`, `XSRF-TOKEN` — again a single-call flow.
- Errors: `401` incorrect secret, `403` client ID not active, `500` server error.
- Version note: source 6's own page states "Applies to ALM v15.5 and later," while the comparison table on source 1 claims support back to "12.6x, 14.00 patch 2+, 15.x+" for the same endpoint — **this is a documented inconsistency between two primary pages**, not something I've resolved; note it and verify against your target version's own doc snapshot before relying on it.

**GET-based flows**: `/qcbin/authentication-point/authenticate` (bare GET, non-browser, presumably HTTP Basic like `sign-in` — no dedicated resource page with a worked body was found, flagged UNVERIFIED below) and the same path with `?login-form-required=y` appended (browser-facing HTML login form; per a secondary source, this was *the* sign-in URI for versions prior to 12.53).

### 2. Cookie lifecycle

Two supported shapes:
- **2-step** (`alm-authenticate` → `site-session`): step 1 sets only `LWSSO_COOKIE_KEY`; step 2 — `POST /qcbin/rest/site-session` (sending that cookie back) — returns `201` and sets `QCSession`, `XSRF-TOKEN`, `ALM_USER` (source 3, 26).
- **1-step** (`sign-in` or `oauth2/login`): all four cookies set in a single response (sources 5, 6).

Why two steps exist: per Session Management (source 10), `LWSSO_COOKIE_KEY` establishes platform-level identity (light-weight SSO), while `QCSession` — opened by `site-session` — is ALM's own **application session**, which owns entity locks, client lifetime, and (per source 26) explicitly consumes **no license** by default even though it shows up as a Site Administration connection.

Cookie names are **case-sensitive as documented**: `LWSSO_COOKIE_KEY`, `ALM_USER`, `QCSession`, `XSRF-TOKEN` (source 3, verbatim Cookie-header construction example: `"LWSSO_COOKIE_KEY={}, ALM_USER={}, QCSession={}, XSRF-TOKEN={}"`). Example Set-Cookie values seen in a corroborating worked example (Aneejian, secondary): `Set-Cookie: LWSSO_COOKIE_KEY=...;Path=/;HTTPOnly`, `QCSession=...;Path=/;HttpOnly`, `ALM_USER=...;Path=/`, `XSRF-TOKEN=...;Path=/` — i.e. `LWSSO_COOKIE_KEY` and `QCSession` are HttpOnly (confirmed independently for `oauth2/login`, source 6); `ALM_USER` and `XSRF-TOKEN` are not (consistent with the client needing to read `XSRF-TOKEN` back out and resend it as a header — see §4). All cookies use `Path=/` in every example seen. `site-session` DELETE explicitly returns `Set-Cookie: QCSession=""; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Path=/` (source 26) — the canonical clear pattern used by every logout/expiry mechanism in this API.

**Client-Type / QCSession suffix behavior** (source 6, corroborated by search of source 1's neighboring pages): *"If you specify the client type in the request header, the returned QCSession includes a suffix encoded in Base64 format (for example, QCSession-d2ViYWRtaW4X). Such a QCSession cannot be used to call APIs that do not have the client type specified in the request header."* The exact header name for this was **not** pinned down verbatim in any fetched page — see UNVERIFIED §1. Note this is architecturally distinct from the `<client-type>` **XML body element** inside the `site-session` POST's `session-parameters` payload (see §3/Restrict_access, source 27), which is used for the API-key-only whitelist feature — the docs are not fully consistent about whether "client type" is conveyed as a header, a body element, or both depending on which call you're making; flagged as a Pitfall.

### 3. API key authentication

**Provisioning** (source 9, v26.1): Site/Customer Admins create and manage keys for any active user via **Site Administration → Users → API Key Management**. Basic users may self-serve their own key via **Site Administration → My Settings → My API Key**, gated by `APIKEY_SELF_SERVICE_LEVEL` (values: `admin_only` [default], `user_full_control`, `user_read_only` — source 28).

Each key = **Client ID + API Key Secret**, generated once (secret cannot be retrieved after creation), with a configurable expiration and optional OData-scope flag. Critically: *"Each API key is associated with a system user. Therefore, when an application uses an API key to access the system, the application is limited by its associated user's permissions."* (source 9) — **yes, a key maps to a real user account**, and inherits that user's project/permission scope exactly. Deleting/deactivating the user deletes its keys.

**Limits/defaults** (source 28, primary site-parameter reference, with corroboration from source 9):
- `APIKEY_MAX_NUM_PER_USER` — default **10**, range 0 to int64-max.
- `APIKEY_EXPIRE_DAYS` — default **-1** (never expire).
- `APIKEY_SELF_SERVICE_LEVEL` — default **admin_only**.

Admin operations: create, delete, **revoke** (temporarily block), **regenerate** a revoked key (issues a new secret) (source 9).

**SaaS vs on-prem** (source 9): on SaaS, the user list a Customer Admin sees for key management is scoped to "currently logged-in customer admin" (i.e., tenant-scoped); on-premises, Site Admins manage keys for the whole installation.

**Documented sign-in call shapes for an API key** — three, from three different endpoints:
1. `Authorization: Basic base64(clientId:secret)` → `POST /qcbin/api/authentication/sign-in` (source 5, "API key and API key secret (version 15.00+)").
2. JSON body `{"clientId":"...","secret":"..."}` → `POST /qcbin/rest/oauth2/login` (source 6).
3. `"user":"apikey-<id>"` / `"password":"<secret>"` in the `alm-authenticate` body (source 2's own example — see caveat in §1).

**Access-restriction parameters** (source 27, "Restrict access", ALM 12.60+): `RESTAPI_ACCESS_APIKEY_ONLY` (default **N**) — when set to `Y`, forces API-key-only authentication; combined with `RESTAPI_WHITELIST_APIKEY` (comma-separated allowed client types, default empty) and a `<client-type>` XML element inside the `site-session` POST body's `session-parameters`. The doc's own caution is worth repeating verbatim: *"Under certain circumstances, after enabling API access restriction you may find that you cannot access the ALM server. Contact support if access issues occur."* — treat this as a genuinely risky site parameter to flip.

### 4. XSRF/CSRF

- Header name: **`X-XSRF-TOKEN`** (source 4, source 3).
- Required on **all** REST calls **except GET**, since **ALM 16.00** (source 4, source 23 changelog: *"ALM checks whether the X-XSRF-TOKEN header is included in all requests, except the ones that use the GET HTTP method."*).
- Value = the `XSRF-TOKEN` cookie returned by `site-session`/`sign-in`/`oauth2-login`, echoed back as a request header on every subsequent non-GET call.
- Missing header ⇒ *"the REST API calls fail"* (source 4) — the fetched page did not surface the literal status code in the summarized extraction; **the exact status/body for a missing-XSRF failure is UNVERIFIED** (see UNVERIFIED §2), though `403` is the most plausible candidate given the general error catalogue (`qccore.operation-forbidden`/access-denied family, source 17).
- Source 4 also references a site-level bypass ("Organizations can bypass the check for specific client types via configuration, or disable validation entirely, not recommended") but the **exact parameter name for that bypass was not captured verbatim** — UNVERIFIED §3, do not assume a name.

### 5. Sign-out / session end

Three distinct ways to end a session, all documented:

| Endpoint | Verbs | Status | Notes |
|---|---|---|---|
| `/qcbin/authentication-point/logout` | GET, PUT, DELETE, POST all disconnect | source 12 | **Since ALM 24.1, GET is disabled by default** — needs site parameter `ENABLE_GET_LOGOUT_METHOD=Y` (sources 12, 23). Clears `LWSSO_COOKIE_KEY` and `QCSession` via `Expires=Thu, 01-Jan-1970 00:00:10 GMT`. |
| `/qcbin/api/authentication/sign-out` | GET, PUT, DELETE, POST | source 13 | **Deprecated**: *"We recommend you use the logout method."* Same 24.1 GET-disable rule applies (source 23 names both endpoints together for that change). |
| `DELETE /qcbin/rest/site-session` | DELETE | source 26 | Ends just the application session; `Set-Cookie: QCSession=""` expired immediately. |

All three: *"all locks are released"* on session end, and the docs explicitly warn integrators to discard any locally cached entity data after a session end, since another user/session may now hold different locks (sources 10, 12, 13, 26).

**Keepalive**: `GET` or `PUT` on `/qcbin/rest/site-session` "resets the timeout clock. This extends the lifetime of the session" (sources 10, 26). `PUT` with no body creates a default-client-type session if none exists.

**Idle timeout defaults & controlling parameters** (source 10, source 28):
- `REST_SESSION_MAX_IDLE_TIME` — default **60 minutes**, controls REST client session idle timeout, range 1 to int64-max minutes.
- `SSO_EXPIRATION_TIME` — reported default **11 minutes** for SSO-mediated sessions (including REST clients) per source 10's fetched content; flagged with lighter confidence since this specific figure came through an automated page-summarization rather than a literal quote I re-verified — see UNVERIFIED §4.
- Effective timeout = **whichever of the two is more restrictive** (source 10).

**Expired-session behavior**: the exception catalogue (source 17) documents `qccore.session-has-expired` as a defined error Id, which — combined with the general status-code table (source 16, `401` = "User not authenticated") — indicates an expired `QCSession` surfaces as `401` with `Id: qccore.session-has-expired` in the error envelope (see §9). The exact HTTP status paired with that specific Id was not shown as a worked example in any fetched page, so treat the status-code pairing as a reasonable but CONSTRUCTED inference from the two catalogues, not a verbatim-cited fact.

**Re-auth-and-retry pattern for long-lived clients**: **CONSTRUCTED** — no ALM-specific primary documentation was found describing a recommended client-side retry algorithm. Based purely on the documented pieces above, a defensible pattern is: on any `401`, re-run the authentication+session-open sequence (`alm-authenticate`+`site-session`, or `sign-in`/`oauth2-login`) and retry the original call once; treat repeated `401`s after a fresh re-auth as terminal (bad credentials/revoked key) rather than retryable. This is my construction from the documented primitives, not a cited OpenText recommendation.

### 6. `is-authenticated`-style check endpoints

Two versions exist, confirmed by the changelog (source 23: introduced in **17.0.1**):

| | Legacy | Recommended |
|---|---|---|
| Path | `GET /qcbin/rest/is-authenticated` | `GET /qcbin/v2/rest/is-authenticated` |
| Formats | XML only (source 19: "JSON media type not supported for this endpoint") | XML **and** JSON (source 18) |
| Opens a session? | No | No |
| Response | `<AuthenticationInfo><Username>joe</Username></AuthenticationInfo>` | Same shape, JSON: `{"AuthenticationInfo":{"Username":"sa"}}` |

Both explicitly do not open an ALM application session (sources 18, 19) — useful for a lightweight "am I still logged in" probe without consuming a `site-session`.

### 7. SSO/SAML/external IdP

The REST API Overview page states, in general terms: *"Authentication is handled by an external Identity Provider (IdP), and must follow the WS_Trust authentication protocol"* (source 24) — but every other primary page fetched (§1–§3) shows plain username/password or API-key REST auth with **no WS-Trust exchange** involved. This is a documentation-consistency gap: the WS-Trust line most plausibly describes the case where an org has ALM's "External Authentication" feature engaged, not the default REST auth path; treat it as scoped/conditional rather than universal (flagged again in Pitfalls).

The dedicated "Configure SSO for APIs" page (source 21, v26.1) is thin on REST mechanics: *"The REST client passes the SSO authentication, after which REST API continues as usual"* — no header/parameter specifics given for a non-browser client. It does note a **SiteMinder-specific** quirk (SiteMinder rejects URLs containing a literal single-quote character) and a separate OTA-API workaround (Webgate Customization basic-auth) that belongs to the OTA sibling scope.

**Hybrid SSO mode** (introduced **25.1 P1**, sources 2, 23, 20): when an admin sets **"Enable Local Authentication" = YES**, users flagged as "local" can authenticate via `alm-authenticate`/`authenticate`/`sign-in` with plain username+password even though SSO is broadly enabled for IdP-mapped users — this is the practical mechanism a programmatic REST client can rely on to avoid implementing a SAML/WS-Trust browser dance, *provided* the target service account is configured as a local user and hybrid mode is turned on.

**API keys under SSO — Site Admin REST API**: explicitly confirmed, primary source (7): *"When SSO is enabled, to access ALM Site Administration via RESTful API, only API keys authentication is supported."* For the **core/project REST API**, no equally explicit primary statement was found; a community thread (source, tangential) shows the **legacy Site Admin COM/VBScript client** (`SAClient80MP.SAapi`, method `LogOnWithApiKey`) working under SSO where the plain `Login` method failed — suggestive but not proof for the REST surface, and it's a different API technology entirely. Flagged UNVERIFIED §5.

**431 header-size pitfall under SSO** (source 20, v25.1): enabling SSO appends IdP+SP session cookies to every request, which can trip **"431 Request Header Fields Too Large"**; the documented fix is a Jetty configuration change, recommended header size **81920 bytes**.

### 8. Sessions and licensing

**REST does not consume an ALM license.** Primary: the `site-session` resource page's own defaults note explicitly says *"No licenses consumed"* for a POST-created session (source 26). Corroborated independently by OpenText community staff (Anton Labachev): *"after authentication ALM REST API does not use an ALM License"*; another contributor (Jan Czajkowski) compares this to the (out-of-scope) OTA API's identical no-license behavior.

REST sessions **are still visible** as entries under **Site Administration → Site Connections**, purely for monitoring, even though they consume no seat (corroborated by two independent community threads).

**Pitfall, not a hard limit**: failing to reuse the same cookie jar/container across calls causes **each request to open a new connection/session** rather than reusing one (community thread, "Roddy": *"You are supposed to use the same cookie container to maintain your session. Otherwise each request creates a new connection in ALM"*) — this is a behavioral/client-implementation note, not a documented server limit.

No primary-source **concurrent-REST-session cap** was found (see UNVERIFIED §6). I explicitly did **not** carry over a "concurrent-license, ~3-hour timeout" figure that surfaced in one search snippet — on inspection it traced back to an **ALM Octane** licensing page, wrong product per the disambiguation rule, and is excluded from this report entirely.

### 9. Error model

**Envelope** — format selected by `Accept` header (source 11):
- **HTML** (default, when no/invalid `Accept`): full branded error page with an exception-Id string and a collapsible stack trace (source 15 shows a live example: heading "Not Acceptable", Id `qccore.general-error`, stack rooted in `javax.ws.rs.WebApplicationException` / Apache Wink).
- **XML** (`Accept: application/xml`): conforms to the "Rest Exception Schema" — minimal shape `<QCRestException><Id>qccore.general-error</Id><Title>...</Title></QCRestException>`.
- **JSON** (`Accept: application/json`): minimal shape `{"Id": "...", "Title": "..."}`; for bulk operations the shape extends with `ExceptionProperties` (containing a `BulkValue`/`BulkEntries` array — each entry has `Entity`, `Succeeded`, `EntityType`, `EntityId`, and a nested `Exception` on failure) and a `StackTrace` field (source 14).
- If a binary media type is requested alongside another (e.g. `application/octet-stream,application/xml`), the exception follows the **secondary** type's format rather than defaulting to HTML (source 11).

**`Id` + `Title` structure**: *"The Id element refers to the exception type... the Title provides more specific information"* (source 11). Full documented Id catalogue (source 17):

`qccore.bulk-operation-failed`, `qccore.check-in-failure`, `qccore.check-out-failure`, `qccore.entity-not-found`, `qccore.general-error`, `qccore.invalid-filter-expression`, `qccore.invalid-list-field-value`, `qccore.invalid-value-type-for-field`, `qccore.lock-failure`, `qccore.operation-forbidden`, `qccore.required-field-missing`, `qccore.session-has-expired`, `qccore.undo-check-out-failure`, `qccore.unknown-field-name`.

**HTTP status codes** (source 16, full documented list): `200` OK, `201` Created, `400` Bad Request (syntax/format error, invalid field value, wrong data type, missing required field, unrecognized field name — a deliberate catch-all), `401` User not authenticated, `403` Access denied (authorization failure, blocked file type, lock failure, read-only field violation — also a catch-all), `404` Not found / invalid filter expression, `405` Method not supported by resource, `406` Unsupported Accept type, `409` Conflict (state conflict, or **partial** bulk-operation success), `415` Unsupported request Content-Type, `500` Internal server error, `501` Not implemented.

**Retryable vs terminal — CONSTRUCTED** (not documented anywhere found): a reasonable classification built from the above catalogues —
- *Retryable after remediation*: `401`/`qccore.session-has-expired` (re-auth), `409`/`qccore.lock-failure` (backoff+retry), `500` (backoff+retry), `429`-style throttling if ever encountered (none documented — see §10).
- *Terminal*: `400`, `403`/`qccore.operation-forbidden`, `404`, `405`, `406`, `415`, `501`.
This classification is my own construction from the documented status/Id lists, not an OpenText-stated rule.

### 10. Rate limiting, throttling, request timeouts, payload size limits

No ALM/QC-specific documentation of REST rate limiting, request throttling, or payload/body size caps was located despite repeated targeted searches (general site-parameter reference, security settings page, "restrict access" page, and open web search). This appears to be a genuine **documentation gap** rather than a fact I failed to find efficiently — see UNVERIFIED §7.

The only adjacent, genuinely-documented throttling is **login-attempt lockout** (source 28, primary):
- `MAX_INVALID_LOGINS_ATTEMPT_TO_LOCKOUT` — max invalid login attempts before account lockout (no numeric default captured in the fetch).
- `INTERVAL_BETWEEN_INVALID_LOGINS_TO_LOCKOUT` — default **60,000 ms (1 minute)**, the window for counting invalid attempts.
- `INTERVAL_TO_AUTO_RELEASE_LOCKOUT` — minutes before an auto-lockout release (no default captured).

These throttle *authentication attempts*, not general API call volume. The Jetty 81920-byte header-size guidance under SSO (source 20, §7 above) is a request-**header** size workaround, not a body payload limit or rate limit. Attachment-size parameters (e.g., upload-size-style parameters referenced in passing) belong to the attachments sibling scope — see Handoffs.

---

## Pitfalls & behavioural notes

- **Two authentication "shapes" coexist and must not be conflated**: the 2-step `alm-authenticate`→`site-session` flow (only `LWSSO_COOKIE_KEY` from step 1) vs. the 1-step `sign-in`/`oauth2-login` flow (all four cookies at once). Mixing assumptions (e.g., expecting `QCSession` right after `alm-authenticate`) will break.
- **`GET` is exempt from `X-XSRF-TOKEN`, everything else is not** — a very easy integration bug (works fine until the first `POST`/`PUT`/`DELETE`).
- **Reuse the cookie jar.** Independent-per-request HTTP clients (no shared `CookieContainer`/session object) silently multiply Site Administration connection entries (community-reported, §8).
- **24.1 changed default logout behavior**: `GET` on both logout-style endpoints stopped working by default; anything hard-coding a `GET` logout call needs `ENABLE_GET_LOGOUT_METHOD=Y` or must switch to `POST`.
- **`sign-in` and `authenticate` (GET) are both marked for deprecation** (since 17.0) yet still documented and live as of the latest fetched snapshots — don't assume imminent removal, but don't build new integrations on them either; `alm-authenticate` and `oauth2/login` are the "Recommended" pair.
- **Client-type / QCSession Base64 suffix** is a footgun: a session opened with a client-type declared becomes unusable for calls that omit that declaration on subsequent requests (§2) — exact header vs. body-element mechanics are inconsistently described across pages (flagged UNVERIFIED).
- **RESTAPI_ACCESS_APIKEY_ONLY is a loaded gun**: OpenText's own doc warns you can lock yourself out of the server entirely by misconfiguring it alongside an empty/incorrect `RESTAPI_WHITELIST_APIKEY`.
- **SSO can break on header size, not just auth logic** — the 431 issue (§7) is a real operational trap for any deployment layering SSO cookies onto REST traffic.
- **Doc-set version labels don't uniformly track feature history**: many resource pages carry a blanket "Version: 15.5 and later" banner (because that's the nominal floor of the *doc set*, not the feature), while the same resource elsewhere states an earlier real minimum (e.g., `site-session` itself says "12.60 and later"). Don't take the page-level version banner as the actual introduction version without cross-checking, as I did for `site-session`, `Restrict_access`, and the WhatsNew changelog.
- **Product branding is mid-transition across the doc set**: some pages/error templates still say "Application Lifecycle Management" (e.g., the sample HTML exception page, source 15) while newer admin pages say "Application Quality Management Platform" / "OpenText Application Quality Management" — cosmetic, but worth knowing your screenshots/error-page scrapers may see either string depending on patch level.

## Version differences

**16.x/17.x vs 24.1/25.1/26.1** (all from source 23's changelog unless noted):
- **16.00**: `X-XSRF-TOKEN` enforcement on all non-GET calls introduced — this is the single biggest behavioral line between "old" and "new" REST clients.
- **17.0**: `alm-authenticate` gains JSON support (previously XML-only); `authenticate` (GET) and `sign-in` flagged for future deprecation in favor of `alm-authenticate`.
- **17.0.1**: `/qcbin/v2/rest/is-authenticated` introduced as the recommended replacement for the XML-only legacy `/qcbin/rest/is-authenticated`.
- **24.1**: `GET` disabled by default on both `/qcbin/authentication-point/logout` and `/qcbin/api/authentication/sign-out` (need `ENABLE_GET_LOGOUT_METHOD=Y` to restore); also unrelated query-operator and project-list-customization API additions (sibling scope).
- **25.1 P1**: **Hybrid SSO mode** — local users can authenticate via `alm-authenticate`/`authenticate`/`sign-in` with username+password even when org-wide SSO is on, provided admin enables local authentication (this is the standout auth-relevant change in the 24.x/25.x/26.x era).
- **26.1**: no auth-specific REST changelog entries surfaced beyond continuity of the above; API key management UI/doc pages exist and mirror 24.1/25.1 content closely (`api_key_mgmt.htm` nearly identical across 24.1/25.1/26.1 fetches).

**On-prem vs SaaS**: the clearest documented difference is scope-of-administration for API keys — SaaS Customer Admins see/manage keys only within their tenant ("Available user lists vary by currently logged-in customer admin"), while on-prem Site Admins manage the whole installation (source 9). No other SaaS-vs-on-prem authentication-mechanics difference was found in primary docs in this pass (session timeouts, cookie names, XSRF rule, and error envelope all read as identical); this absence should be treated as "not found," not as "confirmed identical" — see UNVERIFIED §8.

**Site Admin REST API surface**: for versions **15.0–16.0.1** it has its own dedicated static doc set (source 8); starting **17.0**, OpenText points users instead to a live, per-instance **Swagger-generated** reference at `http://<server>:<port>/qcbin/api-doc/sa/v2/` (and `.../api-doc/v2/` for the Core/project API) rather than continuing the static doc set — worth knowing if you need the full endpoint list rather than authentication specifically, since that Swagger surface is generated per-installation and wasn't independently crawled here.

---

## UNVERIFIED

1. **Exact header (or body element) name for "client type" that produces the Base64-suffixed `QCSession`.** Docs describe the *effect* (`QCSession-d2ViYWRtaW4X`-style suffix, and incompatibility with calls lacking the same declaration) but two different pages describe the mechanism inconsistently (a request header vs. an XML `<client-type>` body element inside `site-session`'s `session-parameters`). **Probe**: `POST /qcbin/rest/site-session` twice against a live instance — once with `<session-parameters><client-type>Foo</client-type></session-parameters>` as the XML body, once with a candidate HTTP header such as `Client-Type: Foo` — inspect the returned `QCSession` cookie in both cases to see which one (if either, or both) triggers the Base64 suffix, then attempt a follow-up call omitting the header/element and confirm whether it's rejected.
2. **Exact HTTP status code returned when `X-XSRF-TOKEN` is missing on a non-GET call.** Docs only say "the REST API calls fail." **Probe**: authenticate normally, then issue `PUT /qcbin/rest/site-session` (or any project-level non-GET call) with valid cookies but with the `X-XSRF-TOKEN` header omitted; record the returned status code and error `Id`/`Title`.
3. **Name of the site parameter that disables/bypasses XSRF validation for specific client types.** Source 4 alludes to its existence without naming it. **Probe**: inspect the full site-parameter list via Site Administration (or the metadata endpoint referenced in source 28's URL pattern) filtering for "XSRF" to find the exact parameter name and default.
4. **Exact default of `SSO_EXPIRATION_TIME` (reported as 11 minutes).** This number came through an automated extraction of source 10 rather than a hand-verified verbatim quote. **Probe**: re-open the Session_Management page directly and confirm the literal default value and unit.
5. **Whether API-key auth bypasses SSO for the core/project REST API** (confirmed only for the *Site Admin* REST API in source 7). **Probe**: on an SSO-enabled instance with Hybrid mode off, attempt `POST /qcbin/rest/oauth2/login` with a valid API key against a **project-level** endpoint afterward and confirm success without any IdP redirect.
6. **Any documented hard cap on concurrent REST sessions/connections per user, API key, or site.** No primary source located. **Probe**: open N parallel sessions under one API key and increase N until failures or evictions occur; check Site Administration → Site Connections for eviction behavior.
7. **Any documented REST-specific rate limit, request timeout, or request/response payload size ceiling.** No primary source located. **Probe**: script a rapid burst of authenticated calls and observe; separately POST a very large bulk body and observe the failure mode.
8. **Whether SaaS vs on-prem differ in session-timeout defaults, XSRF enforcement, or error envelope.** **Probe**: compare defaults on a SaaS trial tenant vs. on-prem instance of the same version.
9. **Exact request/response shape of the bare `GET /qcbin/authentication-point/authenticate` for non-browser clients.** **Probe**: issue a plain `GET` with `Authorization: Basic ...` and record response headers/cookies/status.

## Handoffs

- **Pagination**: "Results return paginated; clients must request each page sequentially" (source 24, Overview) — belongs to the query-grammar/pagination scope.
- **Query filter operator syntax change in 24.1 P1** (abbreviations vs. symbols recommended) — belongs to query-grammar scope.
- **Bulk-operation entity shape** (`BulkEntries`, `Entity.Fields`, `EntityType`, per-entry `Succeeded`/`Exception`) seen inside the JSON exception example (source 14) — belongs to entity-CRUD scope.
- **Version-control-specific exception Ids** (`qccore.check-in-failure`, `qccore.check-out-failure`, `qccore.undo-check-out-failure`) — operational context belongs to entity CRUD/version-lineage scope.
- **`OTA_ACCESS_APIKEY_ONLY` site parameter** and the OTA-API Webgate-Customization basic-auth workaround under SSO — belongs to OTA-fallback notes.
- **Attachment size limits** (a parameter resembling `UPLOAD_MEMO_IMAGE_FILES_MAX_SIZE` surfaced incidentally) — belongs to the attachments/rich-text scope.
- **Site Admin REST API's non-auth resources** — full CRUD surface belongs to project-customization/users scope. For 17.0+, the static Site-Admin doc set is retired in favor of live per-instance Swagger UI at `/qcbin/api-doc/sa/v2/` (and `/qcbin/api-doc/v2/` for Core) — useful discovery mechanism for entity/field enumeration.
