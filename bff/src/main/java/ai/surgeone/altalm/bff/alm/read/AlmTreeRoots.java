package ai.surgeone.altalm.bff.alm.read;

import java.util.List;
import java.util.function.Function;

/**
 * Resolves the root node of an ALM tree at runtime. Never hardcodes a root id (ADR 0005).
 *
 * <p><strong>The rule, and why the obvious one is wrong.</strong> The documented rule until
 * 2026-08-14 was "query {@code {parent-id[0]}}". It was verified on two trees, written down as
 * general, and copied into the plan, the data model and three skills. It resolves 2 of 6 trees.
 *
 * <p>Probe 16 explained the mechanism: {@code {parent-id[0]}} does not mean "find the root", it
 * means <em>rows whose parent is node 0</em> — the children of node 0. For {@code requirements} and
 * {@code test-set-folders}, whose root <em>is</em> id 0, it returns the root's children. On a
 * populated project that is obvious (4 and 6 rows). On the near-empty sandbox,
 * {@code test-set-folders} had exactly one child — {@code Recycle Bin} — so "children of the root"
 * and "the root" were indistinguishable: one row, HTTP 200, and a Test Lab tree that would have
 * shown users the recycle bin as the root of their test sets.
 *
 * <p>The verified rule is therefore <strong>{@code {parent-id[-1]}} first, falling back to
 * {@code {parent-id[0]}}</strong>: no real node has id {@code -1}, so the first query cannot
 * accidentally match children. Roots are parented to {@code -1} for requirements, test-set-folders,
 * release-folders and bpm-folders, and to {@code 0} for test-folders and resource-folders. Verified
 * across 12 tree instances in 2 projects (probes 15 §15.1 and 16 §16.3).
 *
 * <p>Root <em>ids</em> differ too — 0, 1 and 2 all occur — and they are project-specific, which is
 * why discovery happens per project and per tree rather than from a table.
 */
public final class AlmTreeRoots {

    /** The trees ALM exposes with a parent-id hierarchy. */
    public static final List<String> TREE_COLLECTIONS = List.of(
            "requirements", "test-folders", "test-set-folders",
            "release-folders", "bpm-folders", "resource-folders");

    /** Queries one collection with a given parent-id and returns the matching rows. */
    @FunctionalInterface
    public interface RootQuery {
        List<Row> byParentId(String collection, int parentId);
    }

    /**
     * A candidate root row.
     *
     * @param id   node id
     * @param name node name — never logged for a foreign project (probe 16 disclosure rules)
     */
    public record Row(String id, String name) {
    }

    private final RootQuery query;

    public AlmTreeRoots(RootQuery query) {
        this.query = query;
    }

    /**
     * Resolves the root of one tree.
     *
     * @throws IllegalStateException if neither parent-id yields exactly one row. Deliberately loud:
     *                               guessing here is how the recycle-bin bug happened, and a tree
     *                               that refuses to render beats one rooted at the wrong node.
     */
    public Row resolve(String collection) {
        // -1 first. No real node carries id -1, so a hit here is unambiguously a root.
        List<Row> byMinusOne = query.byParentId(collection, -1);
        if (byMinusOne.size() == 1) {
            return byMinusOne.getFirst();
        }
        if (byMinusOne.size() > 1) {
            throw new IllegalStateException(
                    "tree '" + collection + "' reports " + byMinusOne.size() + " nodes parented to -1; "
                            + "expected exactly one root. Refusing to guess which is the tree root.");
        }

        // Only trees whose roots are parented to 0 reach here. If this collection's root id is
        // itself 0, this query returns its CHILDREN — so a multi-row answer means "not a root".
        List<Row> byZero = query.byParentId(collection, 0);
        if (byZero.size() == 1) {
            return byZero.getFirst();
        }
        throw new IllegalStateException(
                "cannot resolve the root of '" + collection + "': parent-id -1 returned 0 rows and "
                        + "parent-id 0 returned " + byZero.size() + ". A multi-row answer here is the "
                        + "root's children, not the root (probe 16 §16.3) — do not treat the first row "
                        + "as the root.");
    }

    /**
     * Resolves every tree, reporting failures per tree rather than aborting.
     *
     * <p>A project need not contain every module — BPM and resources are frequently unused — so one
     * unresolvable tree must not prevent the rest of the navigation from rendering.
     *
     * @return a resolver result per collection, in {@link #TREE_COLLECTIONS} order
     */
    public List<Resolved> resolveAll() {
        return TREE_COLLECTIONS.stream().map(c -> {
            try {
                return new Resolved(c, resolve(c), null);
            } catch (RuntimeException e) {
                return new Resolved(c, null, e.getMessage());
            }
        }).toList();
    }

    /** @param error null when {@code root} is present */
    public record Resolved(String collection, Row root, String error) {
        public boolean ok() {
            return root != null;
        }
    }

    /** Adapts a page-returning reader into a {@link RootQuery}. */
    public static AlmTreeRoots over(Function<String, List<Row>> minusOne, Function<String, List<Row>> zero) {
        return new AlmTreeRoots((collection, parentId) ->
                parentId == -1 ? minusOne.apply(collection) : zero.apply(collection));
    }
}
