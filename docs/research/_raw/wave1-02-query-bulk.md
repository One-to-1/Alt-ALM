# Wave 1 / Agent 2 — Query grammar, pagination, projection, bulk operations (verbatim subagent report)

> Persisted unedited (transport HTML-entities decoded). Reconciled version lands in `docs/research/alm-api-reference.md`.

**Product confirmed**: OpenText/Micro Focus ALM (Quality Center lineage) — `/qcbin` context path, Domain/Project structure. One Octane doc surfaced in search (`user_tags EQ {null}` syntax) and was discarded as wrong-product.

Two **distinct, coexisting REST API generations** were found: a current **"Core"** API (curly-brace query grammar, `/qcbin/rest/...`) and an older **"Deprecated"** API (symbol-based query grammar, `/qcbin/api/...`). Both are still live and documented as of the 24.1–26.1 doc sets.

## Sources

| # | URL | Product/Version | Primary/Secondary |
|---|---|---|---|
| 1 | admhelp.microfocus.com/alm/api_refs/REST_core/.../General/Filtering.html | Core, 15.5+ (reflects 24.1 P1 changes) | Primary |
| 2 | .../General/query.html | Core, 15.5+ | Primary |
| 3 | .../General/order-by.html | Core, 15.5+ | Primary |
| 4 | .../General/Data_Paging.html | Core, 15.5+ | Primary |
| 5 | .../General/fields.html | Core, 15.5+ | Primary |
| 6 | .../General/BulkOperations.htm | Core, 15.5+ | Primary |
| 7 | .../General/BulkErrorReturn.html | Core, 15.5+ | Primary |
| 8 | .../General/General_Notes_and_Limitations.html | Core, 15.5+ | Primary |
| 9 | .../General/Query_a_Collection_of_Entities.html | Core, 15.5+ | Primary |
| 10 | .../General/relations_btwn_entities.htm | Core, 15.5+ | Primary |
| 11 | .../REST/fields_customization.html | Core, 15.5+ | Primary |
| 12 | .../General/Overview.html | Core, 15.5+ | Primary |
| 13 | .../General/WhatsNew.htm | Core changelog, 15.5.1 → 25.1 | Primary |
| 14 | REST_deprecated/.../query_Clause.htm | Deprecated | Primary |
| 15 | REST_deprecated/.../Data_Types.htm | Deprecated | Primary |
| 16 | REST_deprecated/.../Data_Paging.htm | Deprecated | Primary |
| 17 | REST_deprecated/.../Update_Multiple_Instances.htm | Deprecated | Primary |
| 18 | REST_deprecated/.../Request_Clauses.htm | Deprecated | Primary |
| 19 | REST_deprecated/.../Query_a_Collection.htm | Deprecated | Primary |
| 20 | REST_deprecated/.../fields_clause.htm | Deprecated | Primary |
| 21 | admhelp.microfocus.com/alm/api_refs/site_params/metadata.htm | All versions | Primary |
| 22 | alm/en/26.1/online_help/Content/api_rest_api_reference_core.htm | ALM 26.1 | Primary |
| 23 | alm/en/24.1/online_help/Content/api_rest_api_reference_core.htm | ALM 24.1 | Primary |
| 24 | alm/en/17.0-17.0.1/online_help/Content/api_rest_api_reference_core.htm | ALM 17.0-17.0.1 | Primary |
| 25 | alm/en/25.1/online_help/Content/api_ALM_REST_Site_Admin.htm | ALM 25.1 | Primary |
| 26 | api_refs/15.5-15.5.1/REST_core/.../query.html | ALM 15.5-15.5.1 pinned | Primary |
| 27 | api_refs/15.5-15.5.1/REST_core/.../Query_a_Collection_of_Entities.html | ALM 15.5-15.5.1 pinned | Primary |

Two community.opentext.com threads (date/time queries; 12.53 OR expressions) are login-walled — unread, listed as leads only.

## Findings

### 1. Full `query={...}` grammar — Core API (`/qcbin/rest/...`)

Base: `GET .../{entities}?query={query statement}` [2, 9].

**Structural rules** (verbatim guidelines, source 1):
- Filter in curly brackets `{}`; per-field expression in square brackets `[]`; fields delimited by semicolon `;`.
- **"The only operation supported between fields is AND. The AND operation is implicit and is not specified in the query syntax. Only the ';' delimiter is specified."** Hard grammar limit.
- Application-sent queries **must be URL-encoded**: `{status[NOT (Ready or Design)]}` → `%7Bstatus%5BNOT%20(Ready%20or%20Design)%5D%7D`.
- Very long queries: split into multiple requests, or (on-prem) increase Jetty `requestHeaderSize` in `<ALM deploy folder>/server/conf/jetty.xml`.

**Statement**: `<field name>[condition]; <field name>[condition]; ...` — logical field names. Example: `tests?query={id[GT 1 AND NOT 5]; status[Ready or Design]}`.

**Operators** (verbatim table):

| Operator | Symbol | Meaning | Version note |
|---|---|---|---|
| GT | `>` | greater than | Symbol only before 24.1 P1; either form from 24.1 P1 |
| LT | `<` | less than | same |
| EQ | `=` | equal | same |
| GE | `>=` | ≥ | same |
| LE | `<=` | ≤ | same |

"Starting from 24.1 P1, you can use either GT or >. However, OpenText recommends you use GT in case your organization has strict security policies. Before 24.1 P1, you can only use >." Confirmed in changelog [13].

Logical operators within one field's brackets: `AND`, `OR`, `NOT` (keywords only). Literals with spaces → single **or** double quotes. Literals can contain `*` wildcard. Parentheses nest freely.

Verbatim valid fragments: `GT 1 AND ( LT 3 OR GT 5 )` · `GT 1 AND = "( LT 3 OR GT 5)"` · `GT 1 AND NOT (LT 3 OR GT 5)` · `AAAAAP*` · `aaa or not cccccc` · `not ( Design Or Repair )` · `=AAAAAP*`

Worked examples: `tests?query={status[NOT (Ready or Design)]}` · `{id[GE 1 And NOT = 5]}` · `{exec-status['Not Completed']}` · `{exec-status['Not Com*']}`

**Deprecated grammar** (`/qcbin/api/...`) [14, 15]:
- `query="<expression>"`. Symbols only (`=`, `<`, `>`, `<=`, `>=`).
- `;` = AND, `||` = OR, `!` = NOT (prefix).
- **Documented limitation**: "An OR expression on two difference fields cannot be ANDed with another expression." Workaround: distribute — `(id > 200 ; owner = 'sa') || (status < '5-Ready' ; owner = 'sa')`.
- Single-quoted strings; `\'` escapes an embedded quote (`'d\'Artagnan'`); wildcard `*`.
- **`null` is a first-class literal**: `release-id = null`.
- "A query clause cannot contain a reference to another type of resource" — **no cross-filters** in this generation.

### 2. Null tests; date/time literals

- **Date/time formats** (identical in both generations): Date `yyyy-MM-dd`, Time `HH:mm:ss`, DateTime `yyyy-MM-dd HH:mm:ss`. "Calls using other formats will fail." No timezone rule documented (UNVERIFIED #2).
- **Null tests**: Deprecated has documented `= null`; **Core has no documented null-test syntax** — genuine gap (UNVERIFIED #1).

### 3. Cross filters — Core only

"The expression for the related entity is `<alias>.<logical field name><filter expression>`." Example: `tests?query={connected-to-defect.name["Widget wobbles*"]}`.

**Which pairs qualify**: governed by schema Relations — the alias must be **unique** among relations connecting the two types and represent a **1:1 relation stored in the source entity** (`<ReferenceLocation>IN_SOURCE_ENTITY</ReferenceLocation>`) [10]. Ambiguity example: design-steps↔test has two relations; `{test.name[e]}` fails; `{has-parts-test.name[e]}` works.

More examples: `{status[Ready]; defect.owner[SallyQA]}` · `{defect.owner[joe]}` · `design-steps?query={used-by-test.name[D*]}`.

**Exclusive cross filter**: `{alias}.inclusive-filter[false]` inverts that alias's clauses. Example: `/tests?query={defect.id[1];defect.status[Closed or Fixed];requirement.id[1];defect.inclusive-filter[false]}` — requirement clause stays inclusive; defect clauses become exclusions.

**Shared aliasing rule**: "Do not use more than one alias to reference the same entity type" per query/fields/order-by — violations silently produce wrong results [1, 3, 5].

### 4. Field projection (`fields=`) — Core

- `fields=<name>[,<name>...]`, logical names; related-entity fields via alias (`tests?fields=id,test-folder.id,test-folder.name`).
- Default: all fields returned.
- **No effect on single-entity GET** (explicit no-op example) — Deprecated's fields clause DOES apply to instances [20]. Real behavioural difference.
- Deprecated: some fields "returned unconditionally... varies depending on the resource" [20].
- Discover filterable fields: `.../customization/entities/{entity}/fields?can-filter=true` [11].
- "Limiting the returned fields... may significantly improve performance."

### 5. Sorting (`order-by`) — Core

- Collections only (no-op on single GET).
- Default sort: entity ID ascending.
- Multi-field: `tests?order-by={status;name[DESC]}`.
- **Reference fields sort by referenced value** (e.g. `parent-id[DESC]` sorts by folder NAME).
- Related-entity sort via alias: `order-by={test-folder.name[ASC]}`.
- Case sensitivity follows DB collation; force with `CI`: `{status;name[DESC,CI]}` (added ALM 17.0).
- No `can-sort` metadata flag found (UNVERIFIED #3).

### 6. Pagination

**Core**: `page-size=n` (default `REST_API_DEFAULT_PAGE_SIZE` else 100; max `REST_API_MAX_PAGE_SIZE` else 2000 — **silently capped**, `page-size=max` requests the cap). `start-index` — **1-based** (worked example: "fourth page of 10 per page" = `page-size=10&start-index=31`). `TotalResults`: XML root attribute `<Entities TotalResults="200">`; JSON key `TotalResults`. "On large collections, performance degrades when retrieving the later pages."

**Deprecated**: `limit=n` / `limit=max`, `offset=n` (default 0). Defaults `REST_API_PAGINATION_DEFAULT_LIMIT` 100 / `REST_API_PAGINATION_MAX_LIMIT` 2000. **Over-limit throws an exception** (vs Core's silent cap).

**Site parameters** [21]:

| Parameter | Default | Range | Introduced | Applies to |
|---|---|---|---|---|
| REST_API_DEFAULT_PAGE_SIZE | 100 | 1..int64 | ALM 11.00 | Core page-size |
| REST_API_MAX_PAGE_SIZE | 2000 | 1..int64 | ALM 11.00 | Core page-size |
| REST_API_DEFAULT_PAGE_SIZE_WITH_ANCESTORS | 30 | 1..50 | ALM 11.00 | `ancestors-needed=y` |
| REST_API_MAX_PAGE_SIZE_WITH_ANCESTORS | 50 | 1..50 | ALM 11.00 | `ancestors-needed=y` |
| REST_API_PAGINATION_DEFAULT_LIMIT | 100 | 1..int64 | ALM 12.20 | Deprecated limit |
| REST_API_PAGINATION_MAX_LIMIT | 2000 | 1..int64 | ALM 12.20 | Deprecated limit |
| REST_API_MAX_ENTITY_TREE_SIZE | 100 | — | ALM 12.00 | `tree` sub-resource (no paging) |

### 7. Bulk operations

**Core** [6, 7]:
- Same-entity-type only. DELETE: `?ids-to-delete=17,28,31,46`. POST/PUT: body per "Entities Collection Schema", header `content-type="application/xml;type=collection"` (or json equivalent).
- **`REST_API_MAX_BULK_SIZE` default 2000** (min 1, ALM 12.00+).
- **Non-transactional**: "The bulk operation executes for all of the entities even if there is one or more failure."
- **200** full success / **500** all failed / **409 partial** with verbatim schema:
```xml
<QCRestException>
    <Id>qccore.bulk-operation-failed</Id>
    <Title>bulk failed. see exception properties.</Title>
    <ExceptionProperties>
        <ExceptionProperty name="qccore.bulk-operation-failed">
            <value>
                <BulkOperationFailed>
                    <BulkEntry Successful="true" EntityId="1" EntityType="defect">
                        <Entity Type="defect"><Fields>...</Fields></Entity>
                    </BulkEntry>
                    <BulkEntry Successful="false" EntityId="2" EntityType="defect">
                        <Id>qccore.required-field-missing</Id>
                        <Title>missing required field.</Title>
                        <StackTrace>...</StackTrace>
                    </BulkEntry>
                    <BulkEntry Successful="false" EntityId="3" EntityType="defect">
                        <Id>qccore.operation-forbidden</Id>
                        <Title>user has no permission to update this entity</Title>
                        <StackTrace>...</StackTrace>
                    </BulkEntry>
                </BulkOperationFailed>
            </value>
        </ExceptionProperty>
    </ExceptionProperties>
</QCRestException>
```

**Deprecated** [17]: only bulk PUT documented ("Update Multiple Instances") — JSON `{"data":[{"type":"defect","id":1001,...,"owner":null,"detected-in-rel":{"id":2001,"type":"release"},"user-01":["multi value 1","multi value 2"]},...]}`. Note the richer JSON value forms (null, object refs, arrays). No bulk POST/DELETE page found (UNVERIFIED #4).

### 8. Adjacent site parameters

`REST_SESSION_MAX_IDLE_TIME` (60 min); **`MAX_REQUEST_LENGTH` default 10000 KB, min 512 KB — "maximum length (in KB) for all `/qcbin/v2/` REST API requests", introduced ALM 16.01 P1**; `REST_API_HTTP_CACHE_ENABLED` default Y — ETag caching for `customization/entities`, `customization/relations`, `customization/used-lists`, `customization/users` (ALM 12.00+).

## Pitfalls & behavioural notes

1. **Cross-field OR is not expressible in one Core query** — OR only inside one field's brackets. Client must issue multiple requests and merge.
2. **Silent cap (Core) vs hard error (Deprecated) on oversized pages.**
3. **Alias collisions silently produce wrong data, not errors.**
4. **Ambiguous cross-filter fails outright** — query builder must resolve to the disambiguating alias.
5. **No documented escaping for delimiter characters in Core literals** (`'`, `"`, `;`, `[`, `]`, `(`, `)`, `,`) — major risk for a data generator; only Deprecated documents `\'`.
6. **fields=/order-by= no-op on single-entity GETs (Core)** but fields applies to instances in Deprecated.
7. **Long-URL failure is Jetty-level**, fixable only in server config — SaaS likely can't change it.
8. **Deep paging degrades** — prefer query narrowing over large offsets.
9. **Bulk is non-transactional** — always parse the 409 body per-item.

## Version differences

- Grammar essentially unchanged 15.5→26.1. Only changes: **24.1 P1** operator abbreviations (GT/LT/EQ/GE/LE); **17.0** `CI` case-insensitive order-by.
- **24.1 split the documentation**: pre-24.1 APIs → static Core pages; 24.1+ APIs → embedded Swagger at `http://<Server>:<port>/qcbin/api-doc/v2/` [22, 23]. Not a clean cutover (some 24.1 additions ARE in Core docs, e.g. `/qcbin/v2/rest/.../customization/used-lists` project-list APIs).
- `/qcbin/v2/` predates 24.1 (MAX_REQUEST_LENGTH ALM 16.01; v2 is-authenticated 17.0.1).
- On-prem vs SaaS: nothing documented in this scope; SA REST "SaaS only" tagging exists [25]; SaaS self-service of REST_API_* site params unknown (UNVERIFIED #6).

## UNVERIFIED (probes)

1. **Core null-test syntax** — probe `{detected-in-rel[null]}`, `{field[]}`, variants.
2. **Literal escaping + timezone handling** — probe `{name['O''Brien']}` etc.; create defect with known timestamp, query from two TZs.
3. **can-sort restriction existence** — inspect full field attribute set; try order-by on a memo field.
4. **Deprecated bulk POST/DELETE existence** — probe directly.
5. **24.1+ Swagger endpoints' grammar** — fetch `/qcbin/api-doc/v2/` on live instance and diff.
6. **SaaS self-service of REST_API_* site params** — check SaaS Site Admin.
7. **start-index base** — probe `start-index=0` vs `1`.

## Handoffs

- Auth: WS-Trust mention; XSRF (16.00+); auth endpoint deprecations (17.0); GET-logout disabled (24.1); REST_SESSION_MAX_IDLE_TIME.
- Per-entity: Deprecated "Defects.htm#defects_fields_no_filtering" (unfilterable fields list); "always returned unconditionally" fields per resource.
- Customization: fields collection `required`/`can-filter`.
- Version lineage: three URL roots' history; Tech-Preview doc set (12.50–15.0.1) as ancestor of Core.
- Attachments: fields not returned by default (attachments/comments/runs/run-steps) — re-confirm.
