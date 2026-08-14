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
     * @param hasChildren ⚠️ <strong>"might have children", not "does".</strong> ALM's
     *                    {@code children-count} envelope attribute reads 0 for every node on this
     *                    version even when children exist (probe 19), so there is no cheap way to
     *                    know in advance. The server answers optimistically and the client learns
     *                    the truth by expanding — a node that turns out to be empty renders as
     *                    empty rather than being undiscoverable
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

    /** Children of one node, for lazy expansion. */
    public record Children(String collection, String parentId, List<Node> nodes) {
    }
}
