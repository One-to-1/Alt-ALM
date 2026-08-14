package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.read.AlmTreeRoots;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tree navigation: root discovery and lazy child expansion.
 *
 * <p>The root rule lives in {@link AlmTreeRoots} and is deliberately not reimplemented here — it is
 * the single most expensive mistake this project has made in code (`{parent-id[0]}` silently
 * resolving `test-set-folders` to `Recycle Bin`), so it has exactly one implementation.
 */
@Service
public class TreeService {

    /** Collections that are trees. Same allowlist discipline as {@link GridService}. */
    private static final List<String> TREES = AlmTreeRoots.TREE_COLLECTIONS;

    private final AlmEntityClient entities;

    public TreeService(AlmEntityClient entities) {
        this.entities = entities;
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
     * Children of one node.
     *
     * @throws IllegalArgumentException if {@code collection} is not a tree — the same
     *                                  no-fallback allowlist reasoning as {@link GridService}, since
     *                                  this value reaches the ALM request URL
     */
    public TreeDto.Children children(AlmProjectRef project, String collection, String parentId) {
        if (!TREES.contains(collection)) {
            throw new IllegalArgumentException(
                    "'" + collection + "' is not a tree collection; expected one of " + TREES);
        }
        if (parentId == null || parentId.isBlank()) {
            throw new IllegalArgumentException("parentId is required");
        }

        AlmEntityPage page = entities.page(project, collection,
                AlmQuery.none()
                        .filter("parent-id", parentId)
                        .fields("id", "name", "parent-id")
                        .orderBy("name")
                        // A folder with more than 2,000 direct children is not a tree anyone can
                        // navigate, and the server rejects a larger page outright, so this is the
                        // ceiling rather than a guess.
                        .pageSize(2000));

        List<TreeDto.Node> nodes = page.entities().stream()
                .map(e -> new TreeDto.Node(
                        e.first("id").orElse(""),
                        e.first("name").orElse(""),
                        e.first("parent-id").orElse(parentId),
                        // ⚠️ Optimistic, NOT derived from children-count. Probe 19: that envelope
                        // attribute reads 0 for every node on this ALM version, including folders
                        // that provably hold 9, 6 and 1 children — on requirements and test-folders
                        // alike, under every fields projection tried. Trusting it renders a tree
                        // where nothing can be expanded.
                        //
                        // So every node claims to be expandable and the client discovers the truth
                        // by expanding. The cost is one wasted request on a leaf; the alternative
                        // is a tree that cannot be navigated at all.
                        true))
                .toList();

        return new TreeDto.Children(collection, parentId, nodes);
    }
}
