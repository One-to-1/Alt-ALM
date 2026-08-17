package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.read.AlmTreeRoots;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tree navigation: root discovery and level-at-a-time expansion.
 *
 * <p>The root rule lives in {@link AlmTreeRoots} and is deliberately not reimplemented here — it is
 * the single most expensive mistake this project has made in code (`{parent-id[0]}` silently
 * resolving `test-set-folders` to `Recycle Bin`), so it has exactly one implementation.
 */
@Service
public class TreeService {

    /** Collections that are trees. Same allowlist discipline as {@link GridService}. */
    private static final List<String> TREES = AlmTreeRoots.TREE_COLLECTIONS;

    /** Server-stated maximum rows per page (api-ref §4.4); over it, ALM answers 404, not a clamp. */
    private static final int MAX_PAGE = 2000;

    /**
     * Ids per batched {@code parent-id[a OR b …]} query.
     *
     * <p>Q48: the longest query <em>probed</em> was 1,625 characters and that was a property of the
     * largest available project, not a demonstrated ceiling. 120 ids of realistic width lands around
     * 1 KB — comfortably inside both the probed range and the ~8 KB URL cap intermediaries commonly
     * impose. Chunking is cheap; discovering the real limit in production is not.
     */
    private static final int IDS_PER_QUERY = 120;

    /**
     * How far {@link #path} will walk upward before giving up.
     *
     * <p>Probe 20 measured the deepest reachable requirement tree at 6 levels. 32 leaves room for a
     * far deeper real hierarchy while still bounding a cycle — this is a guard, not a limit anyone
     * should reach.
     */
    private static final int MAX_DEPTH = 32;

    private final AlmEntityClient entities;
    private final AlmMetadataCatalog metadata;
    private final AlmAccessPolicy policy;

    public TreeService(AlmEntityClient entities, AlmMetadataCatalog metadata, AlmAccessPolicy policy) {
        this.entities = entities;
        this.metadata = metadata;
        this.policy = policy;
    }

    /** Every tree's root, with per-tree failures reported rather than thrown. */
    public List<TreeDto.Root> roots(AlmProjectRef project) {
        AlmTreeRoots roots = entities.treeRoots(project);
        return roots.resolveAll().stream()
                .map(r -> r.ok()
                        // parentId is left null: the root's own parent is -1 or 0 depending on the
                        // tree, and neither is a node the UI can navigate to, so surfacing it would
                        // invite exactly the confusion that produced the recycle-bin bug.
                        ? new TreeDto.Root(r.collection(),
                        new TreeDto.Node(r.root().id(), r.root().name(), null, true), null)
                        : new TreeDto.Root(r.collection(), null, r.error()))
                .toList();
    }

    /**
     * Children of one or more nodes, in a single call.
     *
     * <p>Two ALM round trips regardless of how many parents are asked for (plus one more per chunk
     * beyond {@link #IDS_PER_QUERY}):
     *
     * <ol>
     *   <li>fetch the children of every requested parent — {@code parent-id[p1 OR p2 OR …]};</li>
     *   <li>fetch the children <em>of those children</em>, and keep only which parents came back.</li>
     * </ol>
     *
     * <p>Step 2 is what makes {@code hasChildren} a fact. Probe 19 established that ALM's own
     * {@code children-count} reads 0 for every node on this version, so the previous implementation
     * had to claim every node was expandable and let the user discover the truth by clicking. One
     * extra query per level replaces that guess, and its rows are exactly what the client needs to
     * prefetch the next level — so the same call both draws correct expanders and makes expanding
     * instant.
     *
     * @throws IllegalArgumentException if {@code collection} is not a tree — the same
     *                                  no-fallback allowlist reasoning as {@link GridService}, since
     *                                  this value reaches the ALM request URL
     */
    public TreeDto.Children children(AlmProjectRef project, String collection, List<String> parentIds) {
        if (!TREES.contains(collection)) {
            throw new IllegalArgumentException(
                    "'" + collection + "' is not a tree collection; expected one of " + TREES);
        }
        List<String> parents = clean(parentIds);
        if (parents.isEmpty()) {
            throw new IllegalArgumentException("at least one parentId is required");
        }

        Fetch level = fetchChildren(project, collection, parents);

        // Which of the nodes we just fetched are themselves parents?
        Set<String> haveChildren;
        boolean exact;
        if (level.rows.isEmpty()) {
            haveChildren = Set.of();
            exact = true;
        } else {
            Fetch grandchildren = fetchChildren(project, collection,
                    level.rows.stream().map(Row::id).filter(id -> !id.isBlank()).toList());
            haveChildren = grandchildren.rows.stream().map(Row::parentId).collect(HashSet::new,
                    HashSet::add, HashSet::addAll);
            exact = grandchildren.complete;
        }

        // When step 2 may have been truncated we fall back to the old optimistic answer rather than
        // reporting "no children" for a node we simply did not see. A spurious expander costs one
        // wasted request; a missing one makes a subtree unreachable, which is how probe 19's bug
        // presented in the first place.
        List<TreeDto.Node> nodes = level.rows.stream()
                .map(r -> new TreeDto.Node(r.id(), r.name(), r.parentId(),
                        exact ? haveChildren.contains(r.id()) : true))
                .toList();

        return new TreeDto.Children(collection, parents, nodes, exact);
    }

    /** Convenience for the single-parent case. */
    public TreeDto.Children children(AlmProjectRef project, String collection, String parentId) {
        return children(project, collection, List.of(parentId == null ? "" : parentId));
    }

    /**
     * The same level, but carrying every field value — a tree rendered <em>as a grid</em>.
     *
     * <p>ALM's Requirements module is one table whose first column indents and expands, with Req ID,
     * Direct Cover Status, Initiator and Modified sitting beside it as ordinary columns. That needs
     * hierarchy and field values together, which {@link #children} deliberately does not carry: it
     * projects down to {@code id,name,parent-id} because a folder tree does not need 76 fields per
     * node. This method drops the projection for the requested level only — the extra level fetched
     * to settle {@code hasChildren} stays projected, since all it contributes is a set of ids.
     */
    public TreeDto.Rows rows(AlmProjectRef project, String collection, List<String> parentIds) {
        if (!TREES.contains(collection)) {
            throw new IllegalArgumentException(
                    "'" + collection + "' is not a tree collection; expected one of " + TREES);
        }
        List<String> parents = clean(parentIds);
        if (parents.isEmpty()) {
            throw new IllegalArgumentException("at least one parentId is required");
        }

        // Columns from THIS project's metadata, exactly as the grid does it — the tree view and the
        // grid view must not disagree about a field's label or type.
        List<FieldDescriptor> fields = metadata.fields(project, AlmCollections.entityOf(collection));
        List<GridDto.Column> columns = fields.stream()
                .map(GridDto.Column::of)
                .toList();

        List<AlmEntityPage.AlmEntity> entitiesOnLevel = new ArrayList<>();
        boolean complete = true;
        for (int from = 0; from < parents.size(); from += IDS_PER_QUERY) {
            List<String> chunk = parents.subList(from, Math.min(from + IDS_PER_QUERY, parents.size()));
            AlmEntityPage page = entities.page(project, collection,
                    AlmQuery.none()
                            .filterAnyOf("parent-id", chunk)
                            .orderBy("name")
                            .pageSize(MAX_PAGE));
            entitiesOnLevel.addAll(page.entities());
            if (page.entities().size() >= MAX_PAGE) {
                complete = false;
            }
        }

        List<String> ids = entitiesOnLevel.stream()
                .map(e -> e.id().orElse(""))
                .filter(id -> !id.isBlank())
                .toList();

        Set<String> haveChildren = Set.of();
        boolean exact = complete;
        if (!ids.isEmpty()) {
            Fetch below = fetchChildren(project, collection, ids);
            haveChildren = below.rows.stream()
                    .map(Row::parentId)
                    .collect(HashSet::new, HashSet::add, HashSet::addAll);
            exact = exact && below.complete;
        }

        final Set<String> parentsWithChildren = haveChildren;
        final boolean isExact = exact;
        List<TreeDto.Row> nodes = entitiesOnLevel.stream()
                .map(e -> {
                    String id = e.id().orElse("");
                    return new TreeDto.Row(
                            id,
                            e.fields().getOrDefault("parent-id", List.of("")).getFirst(),
                            // Same degradation rule as children(): when the answer may be truncated,
                            // claim expandable rather than hide a subtree.
                            !isExact || parentsWithChildren.contains(id),
                            new LinkedHashMap<>(e.fields()),
                            e.isError() ? e.errorMessage() : null);
                })
                .toList();

        return new TreeDto.Rows(collection, policy.isWritable(project), columns, parents, nodes, exact);
    }

    /**
     * The ancestor chain of one node, root first — what the tree expands to reveal it.
     *
     * <p>ALM has <strong>no "ancestors of" query</strong>, so this walks {@code parent-id} upward one
     * read at a time. That is up to {@link #MAX_DEPTH} small requests, which is why it exists as its
     * own endpoint rather than being done in the browser: one round trip instead of six.
     *
     * <p>Bounded deliberately. A malformed hierarchy — a cycle, or a depth beyond anything real —
     * must stop the walk rather than hang the request, so the result says {@code truncated} and the
     * client selects what it can.
     *
     * @return empty when the id does not exist in this project
     */
    public java.util.Optional<TreeDto.Path> path(AlmProjectRef project, String collection, String id) {
        policy.checkRead(project);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        String trimmed = id.trim();

        java.util.Deque<String> chain = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String current = trimmed;
        boolean truncated = false;

        for (int depth = 0; ; depth++) {
            if (depth >= MAX_DEPTH) {
                truncated = true;
                break;
            }
            AlmEntityPage page = entities.page(project, collection,
                    AlmQuery.none().filter("id", current).fields("id", "parent-id").pageSize(2));
            if (page.entities().isEmpty()) {
                // The first miss means the record itself is gone; a later one means a dangling
                // parent, which is a truncated path rather than a missing record.
                if (depth == 0) {
                    return java.util.Optional.empty();
                }
                truncated = true;
                break;
            }
            chain.addFirst(current);
            if (!seen.add(current)) {
                truncated = true;
                break;
            }

            String parent = page.entities().getFirst().first("parent-id").orElse("");
            // ALM's roots report a parent of -1 or nothing at all; both end the walk.
            if (parent.isBlank() || "-1".equals(parent) || "0".equals(parent)) {
                break;
            }
            current = parent;
        }

        return java.util.Optional.of(
                new TreeDto.Path(collection, trimmed, List.copyOf(chain), truncated));
    }

    /**
     * One {@code parent-id[…]} sweep over however many parents, chunked per {@link #IDS_PER_QUERY}.
     *
     * <p>{@code complete} is false when any chunk came back full, i.e. the page cap may have cut
     * rows off. It is not an error — the caller decides what a possibly-partial answer means.
     */
    private Fetch fetchChildren(AlmProjectRef project, String collection, List<String> parentIds) {
        List<Row> rows = new ArrayList<>();
        boolean complete = true;

        for (int from = 0; from < parentIds.size(); from += IDS_PER_QUERY) {
            List<String> chunk = parentIds.subList(from, Math.min(from + IDS_PER_QUERY, parentIds.size()));
            AlmEntityPage page = entities.page(project, collection,
                    AlmQuery.none()
                            .filterAnyOf("parent-id", chunk)
                            .fields("id", "name", "parent-id")
                            .orderBy("name")
                            .pageSize(MAX_PAGE));

            for (var entity : page.entities()) {
                rows.add(new Row(
                        entity.first("id").orElse(""),
                        entity.first("name").orElse(""),
                        entity.first("parent-id").orElse("")));
            }
            // A page filled exactly to the cap is indistinguishable from a truncated one, so it is
            // treated as truncated. TotalResults is per-page on this dialect (probe 15) and cannot
            // settle it.
            if (page.entities().size() >= MAX_PAGE) {
                complete = false;
            }
        }
        return new Fetch(rows, complete);
    }

    /** Trims, drops blanks, and de-duplicates while preserving the caller's order. */
    private static List<String> clean(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                seen.putIfAbsent(id.trim(), Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }

    private record Row(String id, String name, String parentId) {
    }

    private record Fetch(List<Row> rows, boolean complete) {
    }
}
