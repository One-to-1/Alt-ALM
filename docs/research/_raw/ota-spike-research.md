# OTA (Open Test Architecture) COM API — Spike Research

Scope: classic ALM/QC `/qcbin` product, `TDApiOle80.TDConnection` COM automation interface.
NOT ALM Octane. Target versions: ALM 24.1 / 25.1 / 26.1 (formerly HP ALM / Micro Focus ALM/QC).

Research only — no COM calls, no installers, no git commands were executed to produce this
document. All claims are labelled with a source URL; anything not corroborated by at least one
source is marked **UNVERIFIED**.

---

## Q1 — Connection and authentication

**Call sequence (username/password).** Confirmed by multiple independent sources (community
threads + official doc summaries):

```vbscript
Set tdc = CreateObject("TDApiOle80.TDConnection")
tdc.InitConnectionEx "http://yourServer/qcbin"
tdc.Login "username", "password"
tdc.Connect "domain", "project"
```

- `InitConnectionEx` — "Initializes the connection" between the client and the ALM server; must
  be called before `Login`. Source: official ADM Help page, TDConnection Object —
  https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic8937.html (fetched via search
  summary; page itself is a frameset and did not render directly).
- `Login(username, password)` — "Authorizes the user. On success, the user is logged in and can
  connect to projects." Same source as above.
- `Connect(domain, project)` — "Connects the logged-in user to the specified project in the
  domain." Same source.
- `Disconnect` / `ReleaseConnection` — tear-down calls; `ReleaseConnection` releases the COM
  pointer and should be called before the host process exits. Same source.

**Status properties**, all confirmed by the same TDConnection doc page and corroborated by
community examples (e.g. https://sumeetkushwah.com/2015/03/19/connecting-almqc-using-hps-otaopen-test-architecture-api/):
- `Connected` — false until `InitConnectionEx` succeeds.
- `LoggedIn` — false until `Login` succeeds.
- `ProjectConnected` — false until `Connect` succeeds.

`InitConnection` (without `Ex`) exists in older/legacy OTA versions but every current example
found uses `InitConnectionEx`; no source documented a functional difference beyond `Ex` being the
modern/recommended entry point. **UNVERIFIED**: exact deprecation status of plain `InitConnection`
in ALM 24.1+.

**URL form.** Confirmed by multiple independent community code samples that the URL passed to
`InitConnectionEx` **includes** the `/qcbin` context path, e.g.:
- `tdc.InitConnectionEx "http://yourURL/qcbin"`
- `tdc.InitConnectionEx "http://alm:8080/qcbin"`
- `QCServerName = "https://microfocus-alm.srv.volvo.com/qcbin/"` (trailing slash also seen and
  apparently tolerated)

Source: aggregated community VBScript samples (create-an-issue-via-vbs thread, testingdevil.blogspot.com,
sumeetkushwah.com — see Q1 search summary). No source showed a bare hostname (without `/qcbin`)
working. Confidence: high that `/qcbin` must be included; trailing slash tolerance is
**UNVERIFIED** (only single-source evidence).

**API key authentication — YES, OTA supports it, via a dedicated method, not through `Login`.**
This directly answers the critical question:

- OTA does **not** accept clientId/secret through the plain `Login(username, password)` call.
  Instead there is a separate connection method: **`InitConnectionWithApiKeyEx`**, used in place
  of `InitConnectionEx` + `Login`:

  ```vbscript
  Set QCConnection = CreateObject("TDApiOle80.TDConnection")
  QCConnection.InitConnectionWithApiKeyEx "Your-ALM-url", "your_apikey_client", "your_apikey_secret"
  QCConnection.Connect "Your_Domain", "Your_Project"
  ```

  Signature reported by search-engine summary of the official ADM Help page (page itself is a
  frameset, could not render directly): `Public Function InitConnectionWithApiKeyEx(ByVal
  ServerName As String, ByVal apiKey As String, ByVal apiKeySecret As String) As List`.
  Source: official doc page title/URL confirmed at
  https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic9021.html ("InitConnectionWithApiKey
  Method") plus a matching community thread,
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/74373/ota-how-to-connect-the-alm15-by-ota
  ("OTA:How to connect the ALM15 by OTA"). A non-`Ex` variant `InitConnectionWithApiKey` also
  appears to exist per the doc title but its signature difference from the `Ex` form is
  **UNVERIFIED** (the frameset page would not render; likely `Ex` adds SSO/return-URL handling
  the way `InitConnectionEx` does over `InitConnection`, by analogy — not confirmed).

- Confirms API keys are usable **regardless of whether SSO is enabled**, and are specifically
  recommended when ALM is SSO-enabled (since a real interactive login can't be scripted in that
  case). Source: same community thread summary.
- ALM's official API-key docs (24.1 and 26.1 admin guide pages) explicitly say API-key auth
  applies to **"REST and OTA"**: "For details on API key authentication when using REST and OTA,
  see the Developer Help." Source:
  https://admhelp.microfocus.com/alm/en/24.1/online_help/Content/api_keys_toc.htm (fetched
  directly, confirmed).
- The same page documents a site parameter **`OTA_ACCESS_APIKEY_ONLY`**, which "controls whether
  3rd-party applications can use username and password to get authenticated using the OTA API" —
  i.e. an admin can lock OTA down to API-key-only auth. This is strong independent confirmation
  that OTA API-key auth is a first-class, admin-governed feature, not a rumor. Source: same page,
  fetched directly.

**Practical implication for the spike:** we should be able to authenticate OTA against our
sandbox using the same API key (clientId + secret) already provisioned for REST, via
`InitConnectionWithApiKeyEx`, without needing a real user's password. This should be attempted
first in the live probe.

**SaaS support.** Mixed/nuanced picture, not a clean yes or no:
- One community thread confirms OTA **can** connect to ALM 14 SaaS with SSO enabled: "users are
  able to connect to ALM 14 SaaS (SSO enabled) through OTA" — title: "OTA API (SSO) connection
  string in VB for ALM 14 SAAS,"
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/206237/ota-api-sso-connection-string-in-vb-for-alm-14-saas
  (found via search summary; not directly fetched).
- A separate thread, **"SA OTA Connectivity with ALM 12.5 SaaS Instance"**
  (https://community.opentext.com/devops-cloud/aqm/f/discussions/429887/sa-ota-connectivity-with-alm-12-5-saas-instance,
  fetched directly) reports that the **SA (Site Administration) API** — a related but distinct
  OTA-family COM API for site-admin operations — is **not accessible on SaaS**: "the SA API is not
  accessible on SaaS. You should verify with Micro Focus." This is about the Site Admin OTA API,
  not the project-level `TDConnection` OTA API used for tests/defects/requirements — do not
  conflate the two. Thread was locked without full resolution.
- Net assessment: project-level OTA (`TDApiOle80.TDConnection`) does appear usable against SaaS
  instances, particularly with SSO + API key, but **Site Administration** OTA calls are reported
  as SaaS-restricted. **UNVERIFIED at high confidence** — only forum evidence, no official SaaS
  restriction matrix was found. This should be an early item in the live probe (attempt
  `InitConnectionWithApiKeyEx` against the actual SaaS/on-prem target we have credentials for).

---

## Q2 — 32-bit vs 64-bit registration

**Confirmed: `TDApiOle80.dll` / the OTA client COM components are 32-bit only.**
- "You can only use the TDConnection object in 32-bit applications (also on 64-bit OS)." Source:
  community thread "How to create TDApiOle80.TDConnection object in powershell (for HP QC 12.21
  connection)?" —
  https://community.microfocus.com/t5/Quality-Center-ALM-User/How-to-create-TDApiOle80-TDConnection-object-in-powershell-for/td-p/1661409
  (found via search summary).
- Corroborating thread: "Workaround: OTA & 64 Bit Applications (Excel, Word etc.)" —
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/34568/workaround-ota-64-bit-applications-excel-word-etc/1820674 —
  clarifies the nuance that matters for us: "Most problems are not related to 64-bit operating
  systems but 64-bit *applications* — Windows 64-bit OS can run e.g. Excel 32-bit and you will not
  face issues with OTA; only if you try to use OTA in 64-bit Excel/Word etc. would you need the
  workaround." I.e. the OS being 64-bit is fine; the **host process** instantiating the COM object
  must be 32-bit.

**Expected failure mode from a 64-bit host.** No source gave a copy-pasted exact HRESULT/error
text, but the pattern reported across threads is a standard 32/64-bit COM class-registration
mismatch:
- One blog fetch (sumeetkushwah.com) reported commenters seeing **"error 429 — ActiveX component
  can't create object"** when instantiating `TDApiOle80.TDConnection`, which the article attributes
  to bitness/registration problems. Source:
  https://sumeetkushwah.com/2015/03/19/connecting-almqc-using-hps-otaopen-test-architecture-api/
  (fetched directly; error code attribution is the blog's own interpretation, not an official doc,
  so treat the specific "429" mapping as **UNVERIFIED** — could also arise from OTA components
  simply not being registered at all, independent of bitness).
- Generically, a 64-bit process trying to `CreateObject`/`New-Object -ComObject` a 32-bit-only
  registered COM class in Windows fails with `0x80040154` (`REGDB_E_CLASSNOTREG`) because the
  class GUID is only present under the WOW6432Node registry hive, not the 64-bit COM hive. This is
  standard Windows COM behavior (not ALM-specific) and is consistent with, but not literally
  confirmed by, the sources above — labeled **UNVERIFIED** pending an actual repro.

**Known workaround: run a 32-bit host process.** Confirmed pattern across multiple threads
(e.g. the PowerShell/TDApiOle80 thread above, and general community guidance): use the 32-bit
PowerShell executable at `C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe` (or a 32-bit
compiled .NET/VBScript/VBA host — VBScript run via `cscript.exe`/`wscript.exe` from
`C:\Windows\SysWOW64\` is the classic route) rather than the 64-bit
`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`. This matches standard WoW64 COM
practice referenced in the Wikipedia WoW64 search hit and the "Workaround: OTA & 64-Bit
Applications" thread above.

**DLLSurrogate registry workaround.** One source claims a registry-based alternative exists:
"Microsoft 64-bit OS have a feature called DLLSurrogate — in the workaround we use this to run OTA
in a 64-bit process by editing the Windows registry," letting a 64-bit host (e.g. 64-bit Excel)
load the 32-bit OTA DLL out-of-process via `dllhost.exe`. Source: same "Workaround: OTA & 64 Bit
Applications" thread,
https://community.microfocus.com/adtd/sws-qc/f/itrc-895/34568/workaround-ota-64-bit-applications-excel-word-etc/1820674.
The exact registry keys/steps were not captured from the search summary — **UNVERIFIED**, would
need the full thread content (login-gated on refetch) to reproduce. For our spike, the simple
32-bit-host approach (SysWOW64 PowerShell) is lower-risk and sufficient; DLLSurrogate is a fallback
only if we need OTA callable from an already-64-bit host process we can't swap out.

**TDConnect utility.** Confirmed function: it is the client-side installer/registrar for the OTA
COM components, downloaded per-ALM-instance from the "ALM Connectivity" page inside the ALM web
client (client sees a "Client MSI Generator"/connectivity page after login) and run once per
workstation.
- "Users can download and run TDConnect.exe from the 'ALM Connectivity' page to install the
  required OTA components on their PC... run as an Admin user." Source: search summary citing
  community threads including
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/206237/ota-api-sso-connection-string-in-vb-for-alm-14-saas
  and general "ALM OTA access" discussion,
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/379362/alm-ota-access.
- "To address connectivity issues, register client-side components by opening IE in administrator
  mode... run the tdconnect.exe plugin provided and verify you can log in." Source: search summary
  of the perlmonks/community aggregation under the 32-bit query above.
- Official Getting Started doc (fetched directly,
  https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic4.html) confirms the underlying
  mechanism without naming `tdconnect.exe` explicitly: "download and register the COM library on
  every workstation that will communicate with the ALM Platform," and warns **"the OTA library is
  not backward compatible" — always run Client Registration from the same [ALM] version the
  client application targets.** This is an important gotcha for a multi-version target (24.1 /
  25.1 / 26.1): the registered OTA client build must match (or at least be compatible with) the
  server version we're probing, and re-running `TDConnect`/registration when switching target
  servers may be required.
- **Admin rights**: consistently described as needing to be run "as Admin" / "administrator mode."
  Treat as effectively confirmed by convergent community sources, though no single official doc
  sentence was captured verbatim — labeled high-confidence but sourced only from
  community/aggregated summaries, not the primary doc text.

---

## Q3 — Test parameters (key question)

**Bottom line: YES, OTA appears to expose a documented, working object model for creating test
parameters that the REST API does not (`step-parameters` POST fails with "Test parameter does not
exist" per our own probing).** This is corroborated by both official doc titles and working
community code, though nobody handed over one single complete canonical snippet — the sketch below
is assembled from several converging sources and should be treated as "high confidence shape,
verify field names against the live server."

**Factory / interface names (confirmed, converging on the same names across 3+ independent
sources):**
- **`ISupportTestParameters`** — an interface obtained from a `TestFactory`-created `Test` /
  `TSTest` object, exposing a **`TestParameterFactory`** property. Confirmed in: the powershell
  community thread "Test Object and TestParameterFactory" (fetched directly,
  https://community.opentext.com/devops-cloud/aqm/f/discussions/531358/powershell---test-object-and-testparameterfactory)
  where an OpenText employee posted working VBScript using exactly this pattern; and the search
  summary of "How to get the step and Test parameters using OTA API and c# code," 
  https://community.microfocus.com/adtd/sws-qc/f/itrc-895/184770/how-to-get-the-step-and-test-parameters-using-ota-api-and-c-code
  (page itself login-gated on refetch, but indexed/cached search summary was consistent).
- `TestParameterFactory.NewList("")` — retrieves the existing list of parameters on a test (a
  `List`/collection of `TestParameter` items). Confirmed in the powershell/TestParameterFactory
  thread (OpenText-employee-provided VBScript).
- `TestParameterFactory.AddItem(...)` — the create path. Search-summary evidence (from the
  184770 thread) describes: "Creating a new parameter involves calling `AddItem(DBNull.Value)` and
  then setting properties like `Name`, `DefaultValue`, and `Description` before calling `Post()`."
  This is the standard OTA factory idiom (`AddItem` → set fields → `Post`) used elsewhere in OTA
  (e.g. defect/requirement creation), so the shape is plausible and internally consistent with the
  rest of the API, but the **exact property name for the default value** (`DefaultValue` vs
  `DefValue` vs `Value`) is **UNVERIFIED** — sources disagreed in wording between "DefaultValue"
  (search summary paraphrase) and "DefValue" (my own prompt's guess, unconfirmed by any source —
  do not trust `DefValue` without checking `TestParameter` in the live Object Browser / hidden
  members).
- A **deprecated** `Test.Params` property was also mentioned as a fallback read path in the
  177908/error-attempting-to-retrieve-test-parameter-values thread — "the `Params` property is
  deprecated, with `TestParameterFactory` being used as an alternative." This confirms
  `TestParameterFactory` is the *current* sanctioned path and `Params` is legacy/read-oriented,
  reinforcing that parameter *creation* should go through `TestParameterFactory`, not `Params`.

**The `<<<name>>>` design-step token convention — confirmed by official ALM user documentation**
(not OTA-specific, but describes the mechanism our design-step text must reproduce):
- "In the description or expected result of a design step, you can type a new or existing
  parameter name using the syntax `<<<parameter name>>>`. If you typed a new parameter, it is
  automatically added to the test parameter grid." Source:
  https://admhelp.microfocus.com/alm/en/26.1/online_help/Content/UG/t_use_test_parameters.htm
  ("Test parameters," current 26.1 doc) and the older equivalent
  https://admhelp.microfocus.com/alm/en/12.60/online_help/Content/UG/t_use_test_parameters.htm
  (both found via search summary, consistent wording across versions 12.60→26.1, high confidence).
- Important implication for us: **the UI's `<<<name>>>` auto-add behavior is a stock-client
  convenience, driven by parsing step text on save — not itself an OTA method.** If OTA lets us
  set design-step `Description`/`ExpectedResult` text directly (uncontroversial — that's ordinary
  `Design step` field-setting), simply writing `<<<paramname>>>` into that text via OTA is
  **UNVERIFIED** to auto-register the parameter the way the interactive client does; that
  client-side parsing may only fire in the browser UI, not on a raw field POST via OTA/REST. Safer
  assumed design for the generator: explicitly create the `TestParameter` via
  `TestParameterFactory.AddItem`/`Post` **and** write the matching `<<<name>>>` token into the step
  text, rather than relying on the token alone to create it.

**Concrete code sketch (VBScript), assembled from the converging sources above — UNVERIFIED as a
whole (never run end-to-end by any source I found), each line individually sourced or flagged:**

```vbscript
' Connect (Q1)
Set qcc = CreateObject("TDApiOle80.TDConnection")
qcc.InitConnectionEx "http://yourServer/qcbin"
qcc.Login "username", "password"
qcc.Connect "domain", "project"

' Get a test by id (standard OTA TestFactory idiom, high confidence — TestFactory
' is documented, see https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic9341.html)
Set testFactory = qcc.TestFactory
Set theTest = testFactory.Item(testID)   ' Item(id) is the standard OTA factory accessor

' Get ISupportTestParameters / TestParameterFactory
' (confirmed pattern: community-posted, OpenText-employee VBScript,
'  https://community.opentext.com/devops-cloud/aqm/f/discussions/531358/...)
Set paramFactory = theTest.TestParameterFactory

' Add a named parameter with a default value — SHAPE CONFIRMED (AddItem/Post idiom used
' throughout OTA), EXACT PROPERTY NAMES UNVERIFIED, check live Object Browser before running:
Set newParam = paramFactory.AddItem(Null)
newParam.Field("PR_NAME") = "MyParam"          ' UNVERIFIED backend field name — guess by
                                                ' analogy to other OTA *_NAME fields
newParam.Field("PR_DEF_VALUE") = "42"          ' UNVERIFIED backend field name — pure guess
newParam.Field("PR_DESCRIPTION") = "..."       ' UNVERIFIED backend field name — pure guess
newParam.Post()

' Reference it from a design step (UI convenience, not confirmed to work via raw OTA field set —
' see caveat above)
Set stepFactory = theTest.DesignStepFactory
Set step = stepFactory.AddItem(Null)
step.Field("ST_DESCRIPTION") = "Enter <<<MyParam>>> into the field"
step.Post()
```

**What is solid vs guessed in that sketch:** `TestFactory`, `TestParameterFactory`,
`ISupportTestParameters`, `NewList`, and the `AddItem`→set→`Post` idiom are corroborated by
multiple sources. The specific `PR_*` backend field names for `TestParameter` were **not found in
any source** and are placeholders only — the live probe's first job for Q3 should be to open the
OTA Object Browser (or use `.Fields`/reflection) against a real `TestParameter` object and read
back the actual field names before attempting a write.

---

## Q4 — Business Process Testing (BPT) via OTA

**OTA does expose BPT objects — confirmed, with a real working (if partially broken) example.**

- **`ComponentFactory`** — obtained directly off the connection: `Set CompFactory =
  qcConnection.ComponentFactory`. Confirmed via fetched community thread "Unable to link an input
  parameter to an output parameter in a BPT test through OTA API,"
  https://community.opentext.com/devops-cloud/alm-qc/f/discussions/90340/unable-to-link-an-input-parameter-to-an-output-parameter-in-a-bpt-test-through-ota-api
  (fetched directly).
- **`BPComponent`** — a component instance added to a business-process test:
  `Set myBPComp = myBPTest.AddBPComponent(Comp)`. Same source.
- **`BPParameter`** — component-level parameters, with a `ComponentParamName` property and a
  `Reference` property used to link an input parameter of one component to an output parameter of
  another: `myBPComponent1.BPParams.Item(1).Reference = myBPComponent2.BPParams.Item(3)`. Same
  source.
- **Known breakage**: the thread's user reports that attempting exactly this reference-linking
  operation via OTA throws **"Failed to Post Simple Key Entity"** — an unresolved error in the
  thread, with no confirmed fix. This suggests BPT parameter *linking* (as opposed to reading/basic
  component manipulation) may be a rough edge in OTA even when BPT is licensed and reachable. Flag
  as a real risk for the ~6 BPT features in our feasibility matrix, independent of the licensing
  question below.
- A separate community thread title, "How 2 downld QTP scripts from BPT cmpnts with OTA" 
  (https://community.microfocus.com/adtd/sws-qc/f/itrc-895/226496/how-2-downld-qtp-scripts-from-bpt-cmpnts-with-ota,
  found via search only, not fetched) further corroborates that OTA is a normal, established path
  for BPT component automation in the community — this isn't a fringe/unsupported use case.

**Licensing gating — NOT resolved by documentary research; this needs the live probe.**
No source found made an explicit statement of the form "BPT via OTA requires the same license as
BPT via REST/UI" or "OTA bypasses the BPT license." The fetched BPT thread (90340) explicitly
contains **no licensing/permission discussion** — the user's context implies they *did* have BPT
access (their problem was a technical Post error, not a 403/permission error), which is weak
circumstantial evidence that when BPT is licensed, OTA reaches it fine, but says nothing about
whether OTA can reach BPT *without* the license.

Architecturally, OTA and REST both terminate in the same server-side ALM business logic and
license-enforcement layer (this is a reasonable inference from ALM's architecture generally, not
sourced from an explicit doc) — so the most probable answer is that **BPT license gating is
enforced server-side regardless of transport**, meaning if REST returns
`403 qccore.operation-forbidden` on `/components` due to license, OTA's `ComponentFactory` would
most likely hit the same license check and fail equivalently. But this is an **inference, not a
confirmed fact** — mark **UNVERIFIED**, and this is squarely a question only a live OTA probe
against our actual (presumably BPT-unlicensed) sandbox can settle: attempt
`qcConnection.ComponentFactory` and a basic `NewList`/`Item` call, and see whether it fails with a
license-flavored COM error or succeeds.

---

## Q5 — Other OTA-only capabilities

- **Baselines / Libraries**: `BaselineFactory` and `LibraryFactory` **exist as hidden members in
  the OTA type library but are explicitly undocumented and marked "for HP use."** A community
  thread title "LibraryFactory in OTA API" confirms via search summary: "BaselineFactory and
  LibraryFactory do exist as hidden members in the OTA DLL and are marked 'for HP use.' HP (now
  Micro Focus) does not want to publicize any API for the baselining functions, possibly because
  these functions may have a huge impact on overall server response times." Accessing them
  requires enabling "Display Hidden Members" in the VBA Object Browser. Source:
  https://community.microfocus.com/t5/Quality-Center-ALM-User/LibraryFactory-in-OTA-API/td-p/1019870
  (full thread login-gated on direct refetch; above is from indexed search-summary, treat as
  **UNVERIFIED in detail but high-confidence in gist** — multiple independent phrasings of "hidden,
  unsupported, for HP use" converged). **Practical read for our matrix**: baselines/libraries are
  *technically* reachable via OTA but as an unsupported, undocumented, version-fragile surface —
  worse than a normal OTA-only gap, this is explicitly vendor-disclaimed. Treat as last-resort,
  not a planned dependency.
- **Pinned test sets**: no OTA-specific object model reference found in any source. "Pinned Items"
  is described only as a stock-UI convenience feature (a client-side/session pin panel), per ALM
  User Guide content surfaced in search (help.sap.com-hosted ALM User Guide PDFs, versions 15–17,
  generic hits, not fetched). No evidence of a corresponding OTA object. **UNVERIFIED / likely
  UI-only, no OTA hook found** — worth an explicit "not found" note in the feasibility matrix
  rather than assuming it exists.
- **Follow-up flags**: same situation — described only as UI behavior ("right-click a record,
  Flag for Follow Up"; ALM User Guide content, generic search hits). No OTA property/method named
  in any source (e.g. no confirmed `Flag` or `FollowUpDate` field on OTA entity objects, though it
  is plausible such records simply have a normal `BG_FOLLOW_UP` style backend field settable like
  any other field — **UNVERIFIED**, would need Object Browser field inspection on a live defect/
  requirement object).
- **Alerts**: same — "Clear Alerts" is documented only as a stock-UI menu action (Edit > Clear
  Alerts / Tests > Clear Alerts) in the ALM User Guide. No OTA-specific alert object/method
  surfaced in any source. **UNVERIFIED**.
- **Purge runs**: no ALM/QC-specific OTA or REST documentation surfaced for a "purge" object model
  at all — all search hits were for unrelated systems (SAP, Cisco, IBM). **No evidence found
  either way**; flag as needing direct doc-index lookup in the OTA API Reference table of contents
  during the live probe phase, or an explicit note that this may not be a scriptable operation at
  all (possibly a Site Admin-only maintenance action, not a project-data OTA object).
- **Similar-defect search**: **confirmed to exist as a named OTA doc topic.** Search surfaced a
  page titled "Find similar defects" at a SaaS-tenant-hosted OTA doc mirror,
  `https://almlondemo12.saas.hp.com/qcbin/Help/doc_library/api_refs/ota/topic87.html` — this
  confirms the topic exists in the OTA API Reference table of contents (tenant-specific doc
  mirrors serve the same shipped `.chm`-derived content), but the URL is on a customer-specific
  ALM tenant and could not be fetched (not a stable/public doc host). The ALM stock UI does have a
  documented "find similar defects when creating a new defect" feature (source: general ALM UI
  docs surfaced in the same search). **Confidence: the capability and doc topic exist; the exact
  method/object name is UNVERIFIED** — needs a lookup against our own OTA API Reference copy
  (`admhelp.microfocus.com/alm/api_refs/ota/...topic87.html` — worth trying that exact topic
  number on the public admhelp mirror during the live probe, since topic numbers are often stable
  across tenant mirrors and the public host).

---

## Confidence and gaps

**High confidence (multiple independent, partly official sources):**
- The `InitConnectionEx` → `Login` → `Connect` sequence and `Connected`/`LoggedIn`/`ProjectConnected`
  status properties.
- The URL passed to `InitConnectionEx` includes `/qcbin`.
- OTA supports API-key auth via `InitConnectionWithApiKeyEx(url, clientId, secret)`, confirmed
  independently by an official admin-guide page ("REST and OTA") plus the `OTA_ACCESS_APIKEY_ONLY`
  site parameter plus a community how-to thread.
- `TDApiOle80` COM components are 32-bit only; the practical fix is running a 32-bit host process
  (e.g. SysWOW64 PowerShell); `TDConnect.exe` is the per-workstation client registrar and should be
  re-run when the target ALM version changes (OTA is explicitly documented as not backward
  compatible).
- `TestParameterFactory` (via `ISupportTestParameters` on a `Test` object) is the current,
  non-deprecated OTA path for test parameters, confirmed by an OpenText-employee-posted working
  VBScript sample; the `<<<name>>>` design-step token convention is confirmed by official ALM user
  docs across versions 12.60 through 26.1.
- BPT is reachable via OTA (`ComponentFactory` / `BPComponent` / `BPParameter`), confirmed by a
  real (if partly broken) community example — this is not a fringe/theoretical capability.
- `BaselineFactory`/`LibraryFactory` exist but are explicitly undocumented, hidden, vendor-flagged
  "for HP use."

**UNVERIFIED — needs the live COM probe to settle, roughly in priority order for the spike:**
1. Whether `InitConnectionWithApiKeyEx` actually authenticates against our specific sandbox
   instance and version (24.1/25.1/26.1) — this is the single highest-value probe, since it would
   let the whole spike (and eventually the generator) avoid handling real user passwords.
2. Whether OTA's `ComponentFactory`/BPT access is gated by the same license as REST's
   `/components` 403, or whether OTA bypasses it — determines whether ~6 BPT features are
   permanently out of scope or merely REST-inaccessible.
3. The exact `TestParameter` object field names (`Name`/`DefValue`/`Description` — exact backend
   `Field()` keys unknown) — needed before any write attempt; check via Object Browser / hidden
   members inspection first, don't guess-and-post against a live project.
4. Whether writing `<<<name>>>` into a design-step's `Description`/`ExpectedResult` field via raw
   OTA field-set auto-creates the parameter the way the interactive UI does, or whether the
   parameter must always be explicitly created via `TestParameterFactory` first.
5. Exact 64-bit-host failure error code (COM `REGDB_E_CLASSNOTREG` is the generic Windows
   expectation; the "error 429" claim from one blog is unconfirmed and possibly conflates a
   different failure mode).
6. SaaS reachability specifically for our target instance — evidence is contradictory-but-not-
   contradicting (project-level OTA reportedly works on SaaS with SSO; Site Admin OTA reportedly
   does not — these are different APIs, don't let a Site-Admin-specific restriction get
   miscategorized as a project-level OTA restriction).
7. Pinned test sets, follow-up flags, alerts, and purge runs — no OTA object model evidence found
   at all in documentary research; either they don't exist as scriptable OTA objects (plausible for
   UI-only conveniences like "pinned items," which may be pure client-side/session state) or the
   right object names simply weren't surfaced by search. The live probe should check the OTA
   Object Browser's full type list directly rather than relying on further web search.
8. Similar-defect search's exact OTA method name (topic exists in the doc TOC; name not captured).

**Sources that could not be fully read** (login-gated on refetch, so only search-engine cached
summaries were used — treat any claim sourced only to these as slightly softer than a directly
fetched page): the 177908 test-parameter-values thread, the 184770 c#-test-parameters thread, the
90340 BPT thread's full text was fetched directly and IS solid, the LibraryFactory thread
(1019870), and the official TDConnection/InitConnectionWithApiKey frameset doc pages (rendered as
empty frameset shells when fetched directly — content came from search-engine indexing of the same
pages instead).
