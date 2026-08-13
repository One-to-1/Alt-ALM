# TDConnect / "ALM-QC Connectivity Add-in" — Web Research

Scope: what the client-side OTA-registration installer (`TDConnectivity.exe` / `TDConnect_*.exe`,
also called "HP/Micro Focus/OpenText Quality Center Connectivity Add-in") is, what it installs, and
— the urgent question — whether any documented path lets OTA/COM survive an SSO-fronted SaaS ALM
front door. Written after Probe 7 (`docs/research/live-probe-log.md`) proved OTA transport dead
against our specific sandbox via the SSO redirect at `/qcbin/servlet/tdservlet/TdServlet`.

This is desk research only — no installer executed, no code run, no live probing. Everything below
is from public documentation and community forum threads, dated where the source is dated. All
version/product identifiers below refer to ALM/QC classic (`/qcbin`), never ALM Octane.

---

## Q1 — What TDConnect actually is and what it installs

The **Connectivity Add-in** (`TDConnectivity.exe`, an InstallShield PackageForTheWeb self-extractor)
is a client-side package that lays down and COM-registers `OTAClient.dll` (plus supporting DLLs —
our own Probe 7 payload included `tdclient.dll`, `tdclntui.dll`, `WebClient.dll`) so that COM/OTA
clients — Workflow scripts, VAPI-XP tests, QuickTest/UFT's `QCUtil`, Excel/Word add-ins, third-party
tool integrations, or hand-written COM code — can talk to a given ALM/QC server. It is explicitly
scoped for *integration* use: "the HP ALM Connectivity is needed usually when you need to establish a
connection between some HP testing tools (QTP, PC, Excel add-in, UFT, Synchronizer, etc.) or other
third-party testing tools with the ALM application. The Connectivity Add-in is using the OTAClient.dll
and the OTA API." [OpenText Community — Connectivity Add-in vs Register HP ALM Client](https://community.opentext.com/devops-cloud/alm-qc/f/discussions/185171/hp-quality-center-connectivity-add-in-vs-register-hp-alm-client)

This is distinct from full **"ALM Client Registration"**: registration is what you run when you are
writing your own COM/OTA code directly (Java or .NET) against the OTA library, whereas the
Connectivity Add-in exists so a *pre-built* third-party or Micro Focus tool has the DLL present. In
practice, per the same thread, both are frequently installed together on workstations; "if a vendor
has supplied an add-in to help integrate ALM with some other product, then you may need only the
Connectivity add-in." [same source]

The official OTA "Getting Started" page confirms the mechanics: the COM library
(`OTAClient.dll`) is downloaded and registered per-workstation, installing under
`C:\Users\<username>\AppData\Local\ALM-Client\<server name>\OTAClient.dll` — matching what our own
Probe 7 found for the 26.1 payload, and confirming per-user install is a documented pattern, not
something we improvised. [ADR Help Center — Getting Started, OTA API](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic4.html)

No source found describes the installer laying down configuration beyond COM registration (no
mention of certificates, proxy settings, or SSO components bundled in the package itself) —
**UNVERIFIED beyond absence of evidence**; none of the fetched sources enumerate the full payload
contents authoritatively.

---

## Q2 — THE key question: any documented way to make OTA survive an SSO-fronted / SaaS front door?

**Answer: no working documented path was found. Confidence: high that no *public* documented
workaround exists for the specific failure mode we hit (transport-level 302 to the SSO discovery
page before any OTA handshake begins).** What *is* documented is a narrower thing — how to
authenticate the OTA *Login* call when SSO/IdP is in front of ALM — and even that path does not
address our failure, for reasons explained below.

**What IS documented (ALM 14+ IdP authentication for OTA):**

Starting at ALM 14.00, "authentication is not handled by ALM but by an external IdP/IdM. The Login
and Logout methods were adjusted to provide backward compatibility if you do not develop your own
authentication flow to acquire an authentication token from the IdP and IdM." Backward compatibility
requires referencing two extra DLLs, `IdmClientSdk.dll` and `IdmSdkWrapper.dll`, alongside
`OTAClient.dll`. [ADM Help Center — Login Method, OTA API](https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic9005.html)
(page is frame-based; content recovered via search-index snippets, corroborated across two
independent search queries — treat as high-confidence paraphrase, not a verbatim quote)

For the *forward-compatible* (non-legacy) route, the documented pattern is: **build your own
authentication flow — a WS-Trust exchange against the IdP/IdM — to acquire an LWSSO token, then pass
that token into `InitConnectionWithCookiesEx` instead of calling `Login`.** [ADM Help Center — Login
Method / TDConnection Object, OTA API](https://admhelp.microfocus.com/alm/api_refs/ota/Content/ota/topic7061.html)

**Why this doesn't unblock us:** this is exactly the shape of what Probe 7 already attempted — we
had a valid `LWSSO_COOKIE_KEY` (obtained via `POST /qcbin/rest/oauth2/login`, a working REST session)
and fed it into `InitConnectionWithCookies`/`InitConnectionWithCookiesEx` across four cookie
encodings, plus tried `ApplyCookie` (accepted, but connection still failed) — all before the OTA
transport even reaches the point where a cookie would be evaluated. Our failure is **upstream of
authentication**: `GET /qcbin/servlet/tdservlet/TdServlet` itself 302-redirects to
`/authentication-point/discovery.jsp` for the OTA binary-protocol handshake, and the OTA client
cannot follow an HTML redirect where it expects its own wire protocol. No source found describes a
client-side setting, registry value, or connection flag that changes how `TdServlet` responds to an
unauthenticated or SSO-fronted request — the documented IdP flow assumes `TdServlet` answers the OTA
protocol once a valid token/cookie is supplied, which is precisely the assumption our probe falsified
for this deployment.

**Direct evidence searches for "OTA + SSO + Invalid Server Response" came up empty.** The community
thread with the closest title match — ["Invalid Server Response -------------> Mercury.TD.Client.Ota.Core"](https://community.opentext.com/devops-cloud/alm-qc/f/discussions/217827/invalid-server-response---------------mercury-td-client-ota-core)
— attributes the error to **client-machine admin-rights problems during first-run OTAClient
registration**, and separately to **stale/expired Oracle DB credentials for `qcsiteadmin_db`** — not
to SSO or redirects. Neither cause applies to our situation (we proved per-user COM registration
works, and DB credentials are not something we hold or control on a SaaS tenant). Another close-title
thread, ["ALM 14 OTA Login Backward Compatibility not working"](https://community.opentext.com/devops-cloud/aqm/f/discussions/112297/alm-14-ota-login-backward-compatibility-not-working),
describes a user hitting `TDConnection.Login()` failures on ALM 14 SaaS with the error "Failed to
retrieve configuration settings or configuration is invalid" — a **different error string** from our
"Invalid server response," and the thread has **no documented resolution**; OpenText support's own
suggestion was "open a ticket with ALM SaaS Support," i.e., not resolvable from public docs.

**Indirect but telling evidence that OpenText itself has moved integrations off OTA for SaaS:**
the current Excel Add-in documentation states plainly that this generation of the add-in "connects to
ALM by RESTful APIs, so it's not necessary to register [the] ALM Client" — i.e., the OTA-dependent
add-in architecture has been superseded by a REST-based one for the same use case, precisely to avoid
the OTA/client-registration dependency. [Microsoft Excel Add-In — AppDelivery Marketplace](https://marketplace.opentext.com/appdelivery/content/microsoft-excel-add)
Separately, a 2019 idea-exchange thread asking OpenText to "have SaaS support OTA functionality for
bulk addition of users" was **archived without being implemented**, and a reply in an older, unrelated
thread states flatly: "Currently, the SaaS instances of ALM doesn't support OTA functionality for
Site Admin usage." [OpenText Community — Have SaaS support OTA functionality](https://community.opentext.com/adtd/alm_octane/i/alm_octane_idea/have-saas-support-ota-functionality-for-bulk-addition-of-users-to-site-and-projects)
That quote is scoped to *Site Admin* OTA specifically, not project-level OTA generally — **do not
over-read it as a blanket "OTA is disabled on all SaaS,"** but it is one more data point in the same
direction: OpenText's own SaaS product posture treats OTA as not a first-class SaaS integration
surface.

**No mention anywhere** of an `OTA_ACCESS_APIKEY_ONLY` site parameter, a `CLIENT_TYPES_BYPASS_*`
parameter affecting OTA, or any registry/config toggle that changes `TdServlet`'s redirect behavior.
These remain **UNVERIFIED** — we could not inspect site-params ourselves either (403 on our API key
per the live-probe log), so their existence/effect on this exact symptom is neither confirmed nor
ruled out by documentation.

**Bottom line for Q2: no confirmed unblock exists in public documentation.** The one plausible
un-probed avenue is a genuine interactive IdP login (real username/password through the SaaS SSO
provider, producing a token via an explicit WS-Trust exchange) rather than reusing a REST-API-key
session's cookies — because the documented flow assumes an interactively-obtained IdP token, and we
have never tried that exact input. But this would only matter if `TdServlet`'s redirect is
*conditioned on the request itself* rather than being unconditional for this deployment, which no
source confirms or denies. Treat this as the single open experiment worth trying before declaring OTA
categorically dead on this instance, not as a confirmed fix.

---

## Q3 — Silent / command-line install options

**No silent-install mode is documented for the Connectivity Add-in installer family, and one
community thread reports it explicitly does not support one.** A user asking to push
`TDConnect.exe` silently via helpdesk got this direct answer from OpenText/Micro Focus support staff:
"The HP Quality Center Connectivity Add-in is not designed to support silent install" — the
"Installation Completed" dialog cannot be suppressed. [OpenText Community — TDConnect.exe Silent
Installation Package](https://community.opentext.com/devops-cloud/aqm/f/discussions/206108/tdconnect-exe-silent-installation-package-to-be-pushed-from-our-helpdesk)
This matches what we already observed empirically: `/s /v/qn` extracted the payload but still opened
a GUI dialog (live-probe-log, Probe 7).

**The documented alternative is the "MSI Generator"** (an ALM-side tool, referenced for ALM 11 in
that thread but conceptually current) which produces an `.msi` that *does* support silent
installation, with a **"Use Shared Deployment Mode"** option for Citrix/virtual environments that
installs to `%programdata%` instead of `%userprofile%`, and a narrower "just the component
registration option" mode that one user confirmed worked for silent OTA-only registration. We did
not find the MSI Generator location/URL for ALM 26.1 specifically — **UNVERIFIED whether it still
exists in the current product**, since this reference is old (ALM11-era) and no 24.1+/26.1-specific
mention of it surfaced in these searches.

No admin-rights requirement was documented explicitly for the `.exe` installer beyond the general
observation (from the "Invalid Server Response" thread) that "the logged in user must have Admin
rights on the client machine in order to install the Client Side files the first time QC is
accessed" — consistent with our own finding that the GUI installer blocked without admin rights, and
that we had to build the **per-user** registration workaround ourselves (not itself documented
anywhere found in this search — our per-user `HKCU\Software\Classes` + `RegisterTypeLibForUser`
technique appears to be undocumented, self-derived, and worked only because COM supports per-user
registration generically, not because OpenText publishes it as a supported path).

---

## Q4 — Version compatibility rules

**A version-matched client is required; the OTA library is explicitly documented as NOT backward
compatible.** The official guidance states: "The OTA library is not backward compatible" and "Always
run Client Registration from the version on which your OTA application runs."
[ADM Help Center — Getting Started, OTA API](https://admhelp.microfocus.com/alm/api_refs/ota_docx/topic4.html)

This is corroborated by multiple community threads reporting the specific error "The OTA version is
not compatible with the current version of the Application Lifecycle Management server" whenever a
stale client DLL talks to a newer server, with the standard fix being "remove the client component
from the local machine and reinstall" the version-matched client.
[OpenText Community — OTA version not compatible](https://community.opentext.com/devops-cloud/aqm/f/discussions/234274/the-ota-version-is-not-compatible-with-the-current-version-of-the-application),
[Spirent KB — OTA version not compatible with ALM11 server](https://support.spirent.com/SpirentCSC/SC_KnowledgeView?Id=SOL11264)

This directly explains our own observation: the stale machine-wide 12.53 client failed against the
26.1 server with "Invalid server response" (a *different* symptom string from the "OTA version is not
compatible" error some of these threads report — our stale-client failure and our SSO failure produce
the *same* error string, which is a notable ambiguity: "Invalid server response" appears to be a
catch-all OTA client-side error covering both a hard version mismatch and, per our own root-cause
work, a non-OTA HTTP response such as an SSO redirect. No source distinguishes these internally.

No newer-client-vs-older-server backward-compatibility guarantee is documented anywhere found —
the guidance is unidirectional and strict: match the client to the server, full stop.

---

## Q5 — Other capabilities of the Connectivity client beyond OTA COM

Nothing found suggests the Connectivity Add-in opens any integration surface other than OTA/COM
registration itself. It is the shared dependency underneath several *other* products/add-ins
(QuickTest Professional/UFT's `QCUtil`, the Excel Add-in in its legacy OTA-based form, ALM
Synchronizer in older versions, third-party tools like Worksoft Certify), but the add-in itself does
not appear to add a distinct transport or protocol beyond what OTA already provides — it is
infrastructure for other tools, not an additional API surface. [Worksoft docs — Installing the
ALM/Quality Center Connectivity Add-On Tool](https://docs.worksoft.com/Certify_Integration_with_HP_QC/Getting_Started/Installing_the_ALM_Quality_Center_Connectivity_Add-On_Tool.htm)

Notably, the *trend* visible across these sources is the opposite direction from what we're looking
for: newer versions of the tools that used to depend on this add-in (e.g., the current Excel Add-in)
have been re-architected to use REST instead, specifically so they no longer need OTA/Connectivity
registration at all. This reinforces that OpenText's own tooling treats OTA as the legacy path and
REST as the SaaS-compatible one — consistent with, though not proof of, our own finding that OTA is
a dead end on this SaaS instance.

---

## Bottom line

**(a) Nothing found here plausibly unblocks OTA against our SSO-fronted SaaS instance with any
confidence.** The one documented OTA+IdP authentication path (`InitConnectionWithCookiesEx` with a
WS-Trust-acquired LWSSO token) is the same *shape* of thing Probe 7 already tried and failed at a
point upstream of authentication — the `TdServlet` endpoint itself redirects to the SSO discovery
page before the OTA wire protocol can even begin, and no source describes a way to change that
server-side behavior from the client. Every closely-matching community report either has no
documented resolution, points to unrelated causes (client admin rights, stale DB credentials, plain
version mismatch), or terminates in "open a ticket with OpenText SaaS support." OpenText's own
product direction (REST-based replacements for OTA-dependent add-ins, an unimplemented ask for SaaS
OTA support) suggests this is a known, unaddressed gap rather than a solved problem with an obscure
flag we haven't found.

**(b) What would actually be required:** either (1) a genuine interactive login through the SaaS IdP
producing a token via the documented WS-Trust flow — the one variable Probe 7 didn't vary, worth one
more targeted experiment before closing this out, though there's no documentary evidence it would
change `TdServlet`'s redirect behavior specifically; or (2) direct confirmation from OpenText SaaS
Support/Site Admin about whether an `OTA_ACCESS_APIKEY_ONLY`-style site parameter or SSO-bypass
configuration exists for this tenant (we could not inspect `site-params` ourselves — 403 with our
Customer Admin API key per the live-probe log); or (3) an on-prem/non-SSO-fronted ALM instance, where
Probe 7's own conclusion already holds: the client half is fully functional and the failure is
entirely server-side SSO plumbing that wouldn't exist there.

**Recommendation for the project:** do not schedule further OTA-unblock work against this SaaS
sandbox without first getting an explicit OpenText Support answer on the `TdServlet` redirect — the
public documentation trail is exhausted and points nowhere actionable. ADR 0003's stance (OTA sidecar
strictly optional, capability-flagged, not load-bearing for the mainline plan) remains correct.
