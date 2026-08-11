# Wave 1 / Agent 7 — Project Customization: fields, types, lists, users, groups, permissions (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

## Sources

**Primary (admhelp.microfocus.com / OpenText official docs):**

| # | URL | Product/Version | Notes |
|---|---|---|---|
| S1 | `/alm/api_refs/REST_core/Content/REST_API_Core/General/Overview.html` | ALM REST API Reference (Core), "15.5 and later" | Unversioned "current" doc tree, footer dated Mar 2026 |
| S2 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/fields_customization.html` | Core REST API | fields Collection |
| S3 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/Field_Names.html` | Core REST API | logical vs physical names |
| S4 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/lists_related_to_entity.html` | Core REST API | entity-scoped lists |
| S5 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/used-lists.html` | Core REST API | project-wide lists |
| S6 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/types.html` | Core REST API | subtypes collection |
| S7 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/types_-_subtype_fields.html` | Core REST API | per-subtype fields |
| S8 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/entities_Collection.html` | Core REST API | entity-resource-descriptors |
| S9 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/entity.html` | Core REST API | single entity descriptor |
| S10 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/users.html` | Core REST API | users collection |
| S11 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/users_by_name.html` | Core REST API | users/{name}, `show-user-groups-names` |
| S12 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/permissions.html` | Core REST API | entity-type permissions |
| S13 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/extensions.html` | Core REST API | extensions collection |
| S14 | `/alm/api_refs/REST_core/Content/REST_API_Core/REST/groups.html` | Core REST API | **false friend** — query GroupBy, not permission groups |
| S15 | `/alm/api_refs/REST_core/.../GET_CustEntTypsSbtypFld_XML.html` | Core REST API | worked example, `test/BUSINESS-PROCESS` subtype |
| S16 | `/alm/api_refs/REST_core/.../GET_defects_fields_XML.html` | Core REST API | worked example, full defect field set incl. UDF |
| S17 | `/alm/api_refs/REST_core/.../Get_Lists_Return_Sample_XML.html` | Core REST API | worked example, Severity list |
| S18 | `/alm/api_refs/REST_core/.../GET_customization_entity_types_XML.html` | Core REST API | worked example, test subtypes enumeration |
| S19 | `/alm/api_refs/REST_core/Content/REST_API_Core/General/WhatsNew.htm` | Core REST API changelog | 15.5.1 → 25.1 entries |
| S20 | `/alm/api_refs/REST_core/Content/REST_API_Core/General/Restrict_access.html` | Core REST API | site params (handoff: auth) |
| S21 | `/alm/api_refs/REST_core/Content/REST_API_Core/schema.htm` | Core REST API | schema download pointer only |
| S22 | `/alm/api_refs/site_admin_rest/.../Project_users/get_all_users_in_project.html` | Site Admin REST API | `extra-fields=group` |
| S23 | `/alm/api_refs/site_admin_rest/.../Project_users/add_a_user_to_project.html` | Site Admin REST API | POST add user |
| S24 | `/alm/api_refs/site_admin_rest/.../Site_users/site_users_schema_reference.html` | Site Admin REST API | site-level user schema |
| S25 | `/alm/api_refs/site_admin_rest/.../Projects/project_schema_reference.html` | Site Admin REST API | project schema (`has-vcs-db` etc.) |
| S26 | `/alm/api_refs/site_admin_rest/Content/SA_REST_API/WhatsNew.htm` | Site Admin REST API changelog | only populated to 16.00 |
| S27 | `/alm/api_refs/site_params/metadata.htm` | ALM Site Parameters | `EXTENDED_MEMO_FIELDS` etc. |
| S28 | `/alm/en/24.1/online_help/Content/Project_Customization/cust_project_entities.htm` | ALM 24.1 UI help | UDF limits, field types |
| S29 | `/alm/en/24.1/online_help/Content/Web_Runner/customize_advanced_wf.htm` | ALM 24.1 UI help | Advanced (JS) project scripts, `CLIENT_TYPES_BYPASS_REST_WF` |
| S30 | `/alm/en/24.1/online_help/Content/Web_Runner/customize_wf.htm` | ALM 24.1 UI help | Web Client workflow overview |
| S31 | `/alm/en/26.1/online_help/Content/Project_Customization/cust_groups_perms_managing_toc.htm` | ALM 26.1 UI help | 5 default groups |
| S32 | `/alm/en/25.1/online_help/Content/Project_Customization/customize-module-access.htm` | ALM 25.1 UI help | module access per group |

**Secondary (community — leads/corroboration only):**

| # | URL | Notes |
|---|---|---|
| C1 | community.opentext.com/.../205205/... | undocumented `customization/usergroups/{name}`; "most data read-only" |
| C2 | community.opentext.com/.../523782/... | `customization/entities` discovery pattern |
| C3 | community.opentext.com/.../48426/... | ALM 24.1 new list-item REST APIs (detail paywalled, KM000030193) |

---

## Findings

### 1. Entity field metadata

Four related resources, **all GET-only**, all under `/qcbin/rest/domains/{domain}/projects/{project}/customization/`:

- `entities` — collection of all customizable entity types ("entity-resource-descriptors Schema"; ETag-cached). Documented attributes: `Table`, `Name`, `Label`, `SupportsHistory`, `SupportsWorkflow`, `SupportsMultiValue` (S8).
- `entities/{entity name}` — single entity descriptor (S9).
- `entities/{entity name}/fields` — **the field-metadata collection**, "Fields Schema" (S2). Filter params: `required=true|false`, `can-filter=true|false`.
- `entities/{entity name}/types/{subtype ID}/fields` — subtype-specific field overrides, same Fields Schema (S7).

Entity names are **singular** (`test`, not `tests`). An "**Extended Mode**" for pulling fields together with other customization data is referenced but its activation parameter was not found (UNVERIFIED #10).

**Field descriptor attribute set** (union from two worked examples S15 + S16):

| Attribute | XML form | JSON form |
|---|---|---|
| Logical name | `Name=` (attr) | `name` |
| Physical/DB column name | `PhysicalName=` (attr) | `physicalName` |
| Display label | `Label=` (attr) | `label` |
| Max length/precision | `<Size>` | `size` |
| History tracking | `<History>` | `history` |
| Mandatory | `<Required>` | `required` |
| Built-in vs UDF | `<System>` | `system` |
| **Type identifier** | `<Type>` | `type` |
| Duration flag on Number | `<isTime>` | `time` |
| Verified against list/users | `<Verify>` | `verify` |
| Computed/non-stored | `<Virtual>` | `virtual` |
| Active | `<Active>` | `active` |
| Editable | `<Editable>` | `editable` |
| Filterable | `<Filterable>` | `filterable` |
| Groupable | `<Groupable>` | `groupable` |
| Multi-value | `<SupportsMultivalue>` | `supportsMultivalue` |
| Visible (Desktop) | `<Visible>` | `visible` |
| Full-text searchable | `<Searchable>` | `searchable` |
| Versioned | `<VersionControlled>` | `versionControlled` |
| Visible in Web UI | `<VisibleInWebUI>` | `visibleInWebUI` |
| Description | `<Description>` | `description` |
| Admin can toggle Required | `<CanChangeRequired>` | `canChangeRequired` |
| Linked lookup list | `<List-Id>` (only if list-bound) | — |
| Reference-target metadata | — | `fieldRelationReferences: {referenceTypeField, references[]}` |

The two documented examples do NOT show identical attribute sets (S15 shows the full set; S16 a subset) — do not assume a fixed attribute set without a live probe (UNVERIFIED #1).

**Worked example 1 (S15)** — `GET .../customization/entities/test/types/BUSINESS-PROCESS/fields`:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Fields>
    <Field PhysicalName="TS_HAS_DEPENDENCIES" Name="has-dependencies" Label="Asset relation dependencies status">
        <Size>1</Size>
        <History>false</History>
        <Required>false</Required>
        <System>true</System>
        <Type>String</Type>
        <isTime>false</isTime>
        <Verify>false</Verify>
        <Virtual>true</Virtual>
        <Active>false</Active>
        <Editable>false</Editable>
        <Filterable>false</Filterable>
        <Groupable>false</Groupable>
        <SupportsMultivalue>false</SupportsMultivalue>
        <Visible>true</Visible>
        <Searchable>false</Searchable>
        <VersionControlled>false</VersionControlled>
        <VisibleInWebUI>true</VisibleInWebUI>
        <Description></Description>
        <CanChangeRequired>true</CanChangeRequired>
    </Field>
</Fields>
```
JSON equivalent confirms camelCase keys and adds `fieldRelationReferences: {"referenceTypeField": null, "references": []}`.

### 2. Field TYPE identifier strings

Confirmed against the defect entity's full field set (S16, 48–50 fields): exactly **8 distinct `Type` string values**:

| Type string | Example field (Name / PhysicalName) | Notes |
|---|---|---|
| `String` | `attachment` / `BG_ATTACHMENT` | plain text; also some system/virtual fields |
| `Number` | `actual-fix-time` / `BG_ACTUAL_FIX_TIME` | integer-like; `isTime` marks duration semantics |
| `Date` | `closing-date` / `BG_CLOSING_DATE`; UDF `user-02` / `BG_USER_02` | date-only |
| `DateTime` | `last-modified` / `BG_VTS` | date+time |
| `Memo` | `description` / `BG_DESCRIPTION` | `Size=-1` (unbounded) |
| `LookupList` | `priority`/`BG_PRIORITY`, `severity`, `status` | carries `<List-Id>` |
| `UsersList` | `owner` (Assigned To) / `BG_RESPONSIBLE` | `Verify=true` |
| `Reference` | `detected-in-rel` / `BG_DETECTED_IN_REL` | points to another entity; no List-Id |

**No** `Float`, `TreeNode`, or `Boolean` type strings found anywhere. `Number` is the sole numeric string (decimal-UDF behaviour UNVERIFIED #2). **[Lead note: matches our live probe exactly — same 8 identifiers observed on the sandbox.]**

**Rich-text vs plain Memo**: no distinguishing Type value or attribute found — governed out-of-band by site params (`MEMO_FIELD_ADD_IMAGE_MODE`, `MEMO_FIELD_AUTO_DOWNLOAD_IMAGES`, `DS_MEMO_FIELD_AUTO_DOWNLOAD_IMAGES` — S27) and the Requirements "Rich Text" tab feature (UNVERIFIED #3).

UI-facing UDF creation types (S28, 24.1): **Number, String, Date, Lookup List, User List, Memo** — 6 of the 8 (DateTime and Reference are system-assigned only).

### 3. User-defined fields (UDFs)

- **Naming (confirmed by example, S16)**: logical `Name="user-02"`, physical `PhysicalName="BG_USER_02"` (table prefix per entity: BG_=defect, TS_=test, etc.). `System="false"` marks UDF.
- Example UDF is `Type=Date`, `Required=true`, `Size=40` — UDFs can be mandatory and carry any of the 6 UDF-eligible types.
- **Limit**: up to **99 UDFs per entity** (S28).
- **Memo UDFs**: default cap **5** per entity; extendable to **15** via `EXTENDED_MEMO_FIELDS=Y` site param (S27, S28).
- Requirement-entity UDFs are assigned per **requirement type** (S28).
- Test-step entities cannot use "Allow Multiple Values" for UDFs (S28).
- Labels default to the physical name (`Label="BG_USER_02"`) until customized.

### 4. Lookup lists

Two GET-only resources:
- **Entity-scoped**: `.../customization/entities/{entity name}/lists` (S4).
- **Project-wide**: `.../customization/used-lists` — "all the lists in the project that are connected to a field or used in a business rule"; filterable `?name=Status,Run State` or `?id=` (S5).

**Response shape (S17)**:
```xml
<Lists>
   <List>
        <Name>Severity</Name>
        <Id>276</Id>
        <LogicalName>hp.qc.severity</LogicalName>
        <Items>
            <Item value="1-Low" logicalName="hp.qc.severity.low"/>
            <Item value="2-Medium" logicalName="hp.qc.severity.medium"/>
            <Item value="3-High" logicalName="hp.qc.severity.high"/>
            <Item value="4-Very High" logicalName="hp.qc.severity.very-high"/>
            <Item value="5-Urgent" logicalName="hp.qc.severity.urgent"/>
        </Items>
    </List>
</Lists>
```
JSON mirrors as `{"lists":[{"Name","Id","LogicalName","Items":[{"logicalName","value"}]}]}`.

**Field↔List linkage**: `LookupList` field's `<List-Id>` matches a `<List><Id>`. **List IDs are instance-specific** — never hard-code them.

**Hierarchy**: only flat lists documented; no Parent/ParentId attribute found (UNVERIFIED #6).

**24.1 write capability**: changelog (S19): ALM 24.1 added "Project list customization APIs: Six new endpoints for managing project list items, including retrieval, creation, renaming, and deletion." Corroborated by C3 (paywalled KM000030193). **Not present in the static doc tree** — Swagger-only (UNVERIFIED #5 for exact shapes).

### 5. Users

- `.../customization/users` — GET-only, ETag-cached, filter `name=` (S10).
- `.../customization/users/{user name}` — GET **and PUT** (update; cannot modify `Name`) (S11).
- **`show-user-groups-names=true`** on `users/{name}` returns the user's group names — added **ALM 25.1** (S19, S11). Exact response element name unverified (#7).
- **Site Admin REST API** (root `/qcbin/v2/sa/api/`): `GET .../domains/{d}/projects/{p}/users?extra-fields=group` returns per user: `name, id, full-name, email, description, phone, is-active, expire-date, identity-key, idp-name, policy(...), role, user-auth-data(...), is-locked` + `group` elements (S22).
- Site-level user schema (S24): `name, id, full-name, email, description, phone, is-active, expire-date, password, identity-key, idp-name`.

### 6. Groups and permissions

- **No documented "groups" collection in the Core REST API.** `.../{entity name}/groups/{field}` (S14) is **query GroupBy**, a false friend.
- Undocumented community-reported endpoint: `.../customization/usergroups/{userid}` GET (C1) — "most of data is read-only by REST API".
- **Documented**: `GET .../customization/entities/{entity name}/permissions` — "Permissions Schema" (S12). No worked example found; whether it returns current-user effective permissions or a full group matrix is UNVERIFIED (#4).
- UI model (S31, 26.1): five default groups — TDAdmin, QATester, Project Manager, Developer, Viewer; custom groups clone existing ones; new users default to Viewer.
- **Module access per group** (S32, 25.1): UI-only; controls which modules a group can open.
- No "can current user do X" endpoint found beyond the unverified `permissions` resource.
- Site Admin REST can add/remove a user to/from a project (`POST .../projects/{p}/users`, body `{"user":{"name":"..."}}`) (S23); group assignment via SA REST is unclear (C1 claims TDAdmin-only; UNVERIFIED #8).

### 7. Requirement types and test subtypes as metadata

Generic mechanism: `GET .../customization/entities/{entity name}/types` → `{name, id}` pairs (S6); then `.../types/{subtype ID}/fields` (S7).

**Test subtypes — full enumeration (S18)**:
```xml
<types>
    <type name="ALT-SCENARIO" id="ALT-SCENARIO"/>
    <type name="ALT-TEST" id="ALT-TEST"/>
    <type name="BUSINESS-PROCESS" id="BUSINESS-PROCESS"/>
    <type name="VuGen-Script" id="DB-TEST"/>
    <type name="EXTERNAL-TEST" id="EXTERNAL-TEST"/>
    <type name="FLOW" id="FLOW"/>
    <type name="LEANFT-TEST" id="LEANFT-TEST"/>
    <type name="LR-SCENARIO" id="LR-SCENARIO"/>
    <type name="MANUAL" id="MANUAL"/>
    <type name="QAINSPECT-TEST" id="QAINSPECT-TEST"/>
    <type name="QTSAP-TESTCASE" id="QTSAP-TESTCASE"/>
    <type name="QUICKTEST_TEST" id="QUICKTEST_TEST"/>
    <type name="SERVICE-TEST" id="SERVICE-TEST"/>
    <type name="SYSTEM-TEST" id="SYSTEM-TEST"/>
    <type name="VAPI-XP-TEST" id="VAPI-XP-TEST"/>
    <type name="WR-AUTOMATED" id="WR-AUTOMATED"/>
    <type name="WR-BATCH" id="WR-BATCH"/>
    <type name="XR-TEST" id="XR-TEST"/>
</types>
```
`id`==`name` except `DB-TEST` (id) ↔ `VuGen-Script` (display name).

**Requirement types**: same path expected (`.../customization/entities/requirement/types`); requirement types are project-admin-defined, not a fixed enum. No worked REST example found (UNVERIFIED #11). **[Lead note: our live probe already got HTTP 200 on this endpoint; fixture saved.]**

### 8. Project structure/extensions

`GET .../customization/extensions` (S13) — GET-only:
```xml
<Extensions>
    <Extension Name="SPRINTER_EXTENSION" DisplayName="Sprinter"><Version>15.50</Version></Extension>
    <Extension Name="ANALYSIS" DisplayName="Analysis Extension"><Version>15.50</Version></Extension>
    <Extension Name="QUALITY_CENTER" DisplayName="Quality Center"><Version>15.50</Version></Extension>
</Extensions>
```
Set returned is project/licence dependent; no BPT-named entry seen in examples (UNVERIFIED #9).

**Site Admin project schema** (S25) exposes `has-vcs-db` (version control enabled) etc. — but at site-admin privilege. Project-scoped discoverability of versioning status: via `customization/entities/{type}` → `<SupportsVC>` (see attachments-agent handoff).

### 9. Workflow scripts

1. **Classic Desktop Client workflow** = VBScript per-module scripts. **No REST exposure of script source found** — workflow scripts are absent from the documented customization surface.
2. **Web Client "advanced project scripts"** (S29, S30; 24.1+) = **JavaScript**, hooking `<entity>_Create/_Update/_Delete` etc., genuinely server-triggered.

**Critical, quotable (S29)**:
> "By default, advanced project scripts apply to Web Client only. If you want to apply them to all your applications that use ALM REST API, change the **`CLIENT_TYPES_BYPASS_REST_WF`** site parameter to **None**."

**REST API writes bypass workflow/advanced-script validation by default.**

### 10. Site-admin REST surface

Root `/qcbin/v2/sa/api/...`; live Swagger at `{server}/qcbin/api-doc/sa/v2/`. Confirmed resources: site users (CRUD), project users (get-all + add), projects (get, schema). Per-page "Permissions" sections. Some ops tagged "SaaS only". SA REST What's New stale (populated to 16.00 only).

---

## Pitfalls & behavioural notes

- **Read-only by default, two write exceptions**: `users/{name}` PUT, and the 24.1 list-item write endpoints (Swagger-only).
- **REST writes silently skip workflow validation unless `CLIENT_TYPES_BYPASS_REST_WF` reconfigured** — generator won't be blocked by workflow rules by default, but also won't receive auto-populated/derived values; must replicate that logic itself.
- **List IDs are instance-specific** — resolve per target instance.
- **Legacy `hp.qc.*` logical-name prefixes persist** — useful signal for system vs custom lists.
- **Field-descriptor completeness inconsistent between examples** — live probe required per target version.
- **`isTime` on Number = duration (minutes) semantics** — generator must not emit arbitrary integers there.
- **UDF labels default to physical names** — don't assume Label is human-meaningful.
- **`groups` resource name is a trap** (GroupBy, not permission groups).
- **Post-24.1 REST additions documented only via live Swagger** (`/qcbin/api-doc/v2/`, `/qcbin/api-doc/sa/v2/`).

## Version differences

- **16.00**: X-XSRF-TOKEN required (auth scope); SA REST gains user-group assignment (scope unclear).
- **24.1**: six project-list-item write endpoints (Swagger-only); advanced project scripts (JS) formalized with `CLIENT_TYPES_BYPASS_REST_WF` gate.
- **25.1**: `users/{name}?show-user-groups-names=true`.
- **26.1**: no customization-relevant REST changes found (changelog ends at 25.1 — inconclusive, UNVERIFIED #12).
- **On-prem vs SaaS**: SA REST tags some ops "SaaS only"; no divergence documented for Core customization surface (absence-of-evidence).
- **Doc trees**: version-pinned snapshots (e.g. `/alm/api_refs/15.5-15.5.1/REST_core/...`) + unversioned living tree. Living tree is effectively the 26.1-era reference.

## UNVERIFIED (probes)

1. Complete field-descriptor attribute set on live 25.1+ — GET defect fields with Accept: application/json, diff key set. **[Partially done: our sandbox fixtures]**
2. Distinct numeric type string for decimal UDFs? Create decimal Number UDF, GET fields, inspect Type.
3. Any attribute distinguishing rich-text-capable Memo fields? Diff requirement rich-text field vs plain memo descriptor.
4. Shape of `.../customization/entities/{entity}/permissions` — call as Viewer vs TDAdmin, diff.
5. Exact paths/bodies of 24.1 list-item write endpoints — open `/qcbin/api-doc/v2/` Swagger on live instance.
6. Hierarchical list representation — build multi-level list, GET used-lists, inspect.
7. `customization/usergroups/{username}` real/current? — GET on live instance.
8. SA REST group-assignment scope (TDAdmin-only?) — test via `/qcbin/api-doc/sa/v2/`.
9. BPT/extension Name values in `customization/extensions` — GET on BPT-enabled project.
10. "Extended Mode" mechanics on entities Collection — locate doc section or probe `?extended-mode=true`.
11. `customization/entities/requirement/types` live shape — GET + per-type fields. **[Fixture already captured]**
12. 26.1 customization REST changes — re-check changelog.

## Handoffs

- Auth scope: XSRF; `RESTAPI_ACCESS_APIKEY_ONLY`/whitelist; client-type session attribute.
- Query scope: GroupBy resource; 24.1 operator abbreviations; fields clause; CI order-by.
- Entity CRUD scope: Create/Update/Query docs; `requirement-coverages` and `req-traces` collections (community-reported); `copy` resource (`REST/copy.html`); `relations` collection triage.
- Attachments/rich-text scope: `MEMO_FIELD_ADD_IMAGE_MODE`, `MEMO_FIELD_AUTO_DOWNLOAD_IMAGES`, `DS_MEMO_FIELD_AUTO_DOWNLOAD_IMAGES`; Rich Text Template; sanitizing_output.
- Site-admin boundary: site-level user create/delete, `user-auth-data` (LDAP); Lab Extension import.
