package ai.surgeone.altalm.bff.api;

import java.util.List;

/**
 * Tree navigation contract for the SPA.
 *
 * <p>ALM returns no breadcrumb or materialised path field (`matrix #27`), so a tree is walked one
 * `parent-id` level at a time. The BFF does that walking rather than the browser, which keeps the
 * corrected root rule (probe 15/16) in one place instead of in every client.
 */
public final class TreeDto {

    private TreeDto() {
    }

    /**
     * One node.
     *
     * @param hasChildren <strong>Exact, as long as {@link Children#exact} is true.</strong> ALM's
     *                    own {@code children-count} attribute is useless — it reads 0 for every node
     *                    on this version even when children exist (probe 19) — so this is derived
     *                    instead, by asking which of this level's ids appear as a {@code parent-id}
     *                    one level down (probe 20's batched {@code OR} makes that one query, not
     *                    one per node). When {@code exact} is false the value degrades to the older
     *                    optimistic "might have children"
     */
    public record Node(String id, String name, String parentId, boolean hasChildren) {
    }

    /**
     * A resolved tree root, or the reason it could not be resolved.
     *
     * <p>Failure is a first-class value here rather than an exception: a project need not contain
     * every module — BPM and resources are frequently unused — so one unresolvable tree must not
     * stop the rest of the navigation from rendering.
     *
     * @param error null when {@code root} is present; a human-readable reason otherwise
     */
    public record Root(String collection, Node root, String error) {
        public boolean ok() {
            return root != null;
        }
    }

    /**
     * Children of one or more nodes.
     *
     * <p>Batched because a tree level is drawn all at once: asking for every node on a level in one
     * call is what lets the server answer {@link Node#hasChildren} exactly, and gives the client the
     * next level to hold in reserve so expanding feels instant.
     *
     * @param parentIds the parents actually queried, after blanks and duplicates were dropped — so a
     *                  client can tell which of its requests were honoured
     * @param exact     false when a page came back filled exactly to the 2,000-row cap, meaning rows
     *                  may have been cut off and {@code hasChildren} has fallen back to optimistic.
     *                  Surfaced rather than hidden: a client that cares can re-ask node by node
     */
    public record Children(String collection, List<String> parentIds, List<Node> nodes, boolean exact) {
    }
}
