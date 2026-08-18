package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmReadRetry;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only grid API for the SPA.
 *
 * <p>⚠️ <strong>Every mapping here is {@code @GetMapping}, and that is load-bearing.</strong> The
 * BFF holds a key that can write to nine projects, eight of which belong to other teams. A single
 * {@code @PostMapping} added here without thought would be reachable from any page the browser
 * loads. Writes arrive in P2 behind the write-safety component and
 * {@link AlmAccessPolicy#checkWrite}; until then this controller has no non-GET surface at all.
 */
@RestController
@RequestMapping("/api")
public class GridController {

    private final GridService grids;
    private final TreeService trees;
    private final TabService tabs;
    private final HistoryService history;
    private final ModuleService modules;
    private final AlmAccessPolicy policy;
    private final AlmCredentials credentials;

    public GridController(GridService grids, TreeService trees, TabService tabs,
                          HistoryService history, ModuleService modules,
                          AlmAccessPolicy policy, AlmCredentials credentials) {
        this.grids = grids;
        this.trees = trees;
        this.tabs = tabs;
        this.history = history;
        this.modules = modules;
        this.policy = policy;
        this.credentials = credentials;
    }

    /**
     * One page of a collection.
     *
     * @param project {@code DOMAIN/PROJECT}; defaults to the credentialed project when omitted
     * @param pageSize rows per page; the server caps this at 2,000 and rejects more with a 404
     * @param start   1-based row index (ALM's own convention, not 0-based)
     */
    @GetMapping("/grid/{collection}")
    public GridDto.Grid grid(@PathVariable String collection,
                             @RequestParam(required = false) String project,
                             @RequestParam(defaultValue = "50") int pageSize,
                             @RequestParam(defaultValue = "1") int start,
                             @RequestParam(required = false) String sort,
                             @RequestParam(defaultValue = "false") boolean desc,
                             @RequestParam(required = false) List<String> filter) {

        return grids.grid(resolve(project), collection, pageSize, start, sort, desc, parseFilters(filter));
    }

    /** One entity by id — the detail pane. 404 when the id does not exist in this project. */
    @GetMapping("/detail/{collection}/{id}")
    public ResponseEntity<GridDto.Grid> detail(@PathVariable String collection,
                                               @PathVariable String id,
                                               @RequestParam(required = false) String project) {
        return grids.detail(resolve(project), collection, id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Server-side group-by counts for one field.
     *
     * <p>Each group carries an {@code expression} — the filter that selects exactly that group's
     * rows — so the UI can drill in without reconstructing a filter of its own.
     */
    @GetMapping("/groups/{collection}/{field}")
    public List<Map<String, Object>> groups(@PathVariable String collection,
                                            @PathVariable String field,
                                            @RequestParam(required = false) String project) {
        return grids.groups(resolve(project), collection, field);
    }

    /** Every tree's root. Trees this project does not use report an error instead of failing the call. */
    @GetMapping("/tree/roots")
    public List<TreeDto.Root> treeRoots(@RequestParam(required = false) String project) {
        return trees.roots(resolve(project));
    }

    /**
     * Children of one or more nodes.
     *
     * <p>Repeat the parameter to batch a whole tree level — {@code ?parentId=12&parentId=19&…}. One
     * call then both draws that level and reports, exactly, which of its nodes can be expanded; see
     * {@link TreeService#children}.
     */
    @GetMapping("/tree/{collection}/children")
    public TreeDto.Children treeChildren(@PathVariable String collection,
                                         @RequestParam List<String> parentId,
                                         @RequestParam(required = false) String project) {
        return trees.children(resolve(project), collection, parentId);
    }

    /**
     * The same level with full field values and this project's columns — the tree-grid.
     *
     * <p>ALM renders Requirements as one table whose first column indents; this is what feeds it.
     */
    @GetMapping("/tree/{collection}/rows")
    public TreeDto.Rows treeRows(@PathVariable String collection,
                                 @RequestParam List<String> parentId,
                                 @RequestParam(required = false) String project) {
        return trees.rows(resolve(project), collection, parentId);
    }

    /**
     * The related-entity tab strip for a collection — Attachments, Linked Defects, Traceability…
     *
     * <p>Metadata only: no records are read, so this is one cached answer per project+entity rather
     * than a query per tab. It also returns the candidates that did <em>not</em> become tabs, each
     * with its reason, because the reduction is an approximation and the discards are the part
     * nobody could otherwise explain.
     */
    @GetMapping("/tabs/{collection}")
    public TabDto.Strip tabs(@PathVariable String collection,
                             @RequestParam(required = false) String project) {
        return tabs.strip(resolve(project), collection);
    }

    /**
     * Which of this record's tabs hold rows — what the tab rail colours.
     *
     * <p>Sits at {@code /tabs/{collection}/{id}} with no tab key rather than at
     * {@code …/{id}/populated}, which would be indistinguishable from a project that happens to
     * define a tab keyed "populated".
     *
     * <p>A tab missing from the map is <strong>unknown, not empty</strong> — see
     * {@link TabService#populated}.
     */
    @GetMapping("/tabs/{collection}/{id}")
    public Map<String, Boolean> tabsPopulated(@PathVariable String collection,
                                              @PathVariable String id,
                                              @RequestParam(required = false) String project) {
        return tabs.populated(resolve(project), collection, id);
    }

    /**
     * The rows behind one tab, shaped exactly like a grid so the SPA reuses its table.
     *
     * <p>404 when the tab key is not one this entity has in this project — which is a real answer,
     * since the strip is per-project and a bookmarked key can stop existing.
     */
    @GetMapping("/tabs/{collection}/{id}/{tabKey}")
    public ResponseEntity<List<TabDto.TableRows>> tabRows(@PathVariable String collection,
                                                          @PathVariable String id,
                                                          @PathVariable String tabKey,
                                                          @RequestParam(required = false) String project) {
        return tabs.rows(resolve(project), collection, id, tabKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * One record's change history — the History tab's Audit Log.
     *
     * <p>⚠️ There is no Baselines half, and there cannot be: baselines are OTA-only (probe 12), with
     * no documented REST surface to call. See {@link HistoryService}.
     */
    @GetMapping("/history/{collection}/{id}")
    public HistoryDto.History history(@PathVariable String collection,
                                      @PathVariable String id,
                                      @RequestParam(required = false) String project) {
        return history.history(resolve(project), collection, id);
    }

    /**
     * The ancestor chain of one node, root first — what the tree must expand to reveal it.
     *
     * <p>Exists so following a link from a related-records tab can land on the record <em>in its
     * place in the hierarchy</em>, rather than merely opening its fields. Without it the app could
     * select a node it has never loaded and would show an empty tree with a detail pane beside it.
     */
    @GetMapping("/tree/{collection}/path/{id}")
    public ResponseEntity<TreeDto.Path> treePath(@PathVariable String collection,
                                                 @PathVariable String id,
                                                 @RequestParam(required = false) String project) {
        return trees.path(resolve(project), collection, id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Parses repeated {@code filter=field:value} parameters.
     *
     * <p>Colon-delimited because ALM field names cannot contain one, so the split is unambiguous —
     * whereas splitting on {@code =} would collide with the query string itself. Only the FIRST
     * colon splits, so a value may contain colons freely.
     */
    private static Map<String, String> parseFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String f : filters) {
            int colon = f.indexOf(':');
            if (colon <= 0 || colon == f.length() - 1) {
                throw new IllegalArgumentException(
                        "filter must be 'field:value' (got a value with no field or no literal)");
            }
            parsed.put(f.substring(0, colon), f.substring(colon + 1));
        }
        return parsed;
    }

    /**
     * ALM's navigation rail, with a reachability verdict against every entry.
     *
     * <p>Not project-scoped and reads nothing: the rail's shape is ALM's product structure, and the
     * verdicts are about this build's capabilities rather than about any one project's data.
     */
    @GetMapping("/modules")
    public ModuleDto.Rail modules() {
        return modules.rail();
    }

    /**
     * Which projects this deployment may read, and which of them accepts writes.
     *
     * <p>Real project names are returned here on purpose: the operator configured them and is
     * authorised to see their own tenant. The masking rule that governs probes and documents is
     * about what enters the <em>repository and logs</em>, which outlive the session — not about
     * what the authorised user sees in their own browser.
     */
    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return policy.readableProjects().stream()
                .map(p -> Map.<String, Object>of(
                        "domain", p.domain(),
                        "project", p.project(),
                        // Drives whether the UI offers any write affordance at all. The server
                        // enforces this regardless; the flag exists so the UI does not present
                        // buttons that would only ever produce a 403.
                        "writable", policy.isWritable(p)))
                // Writable first, then by name. The name tiebreak is not cosmetic: without it the
                // order depends on however the policy's set iterates, and a list that reorders
                // itself between restarts makes "the third project" mean two different things.
                .sorted(java.util.Comparator
                        .comparing((Map<String, Object> p) -> !((Boolean) p.get("writable")))
                        .thenComparing(p -> (String) p.get("domain"))
                        .thenComparing(p -> (String) p.get("project")))
                .toList();
    }

    /** Parses {@code DOMAIN/PROJECT}, defaulting to the credentialed project. */
    private AlmProjectRef resolve(String project) {
        if (project == null || project.isBlank()) {
            return AlmProjectRef.sandboxOf(credentials);
        }
        int slash = project.indexOf('/');
        if (slash <= 0 || slash == project.length() - 1) {
            throw new IllegalArgumentException("project must be 'DOMAIN/PROJECT'");
        }
        return new AlmProjectRef(project.substring(0, slash), project.substring(slash + 1));
    }

    /**
     * A refused project is a 403, and the body carries the policy's message — which names a
     * pseudonym rather than the project, so the response cannot be used to enumerate the tenant.
     */
    @ExceptionHandler(AlmAccessPolicy.AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AlmAccessPolicy.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "access-denied", "detail", e.getMessage()));
    }

    /**
     * A read that failed every retry is a 502: the upstream failed, not the caller. Distinguished
     * from a 4xx so the SPA can offer "retry" rather than "fix your filter".
     */
    @ExceptionHandler(AlmReadRetry.ReadFailedException.class)
    public ResponseEntity<Map<String, Object>> upstreamFailed(AlmReadRetry.ReadFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "alm-unavailable", "almStatus", e.status(),
                        "detail", "ALM returned a server error on a read after retries"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad-request", "detail", e.getMessage()));
    }
}
