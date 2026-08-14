package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the tree-root rule, written directly from the two probes that established it.
 *
 * <p>The shapes below are the real observed responses (probe 15 §15.1 on the near-empty sandbox,
 * probe 16 §16.3 on a populated project), so a future "simplification" back to
 * {@code {parent-id[0]}} fails here rather than in a user's Test Lab tree.
 */
class AlmTreeRootsTest {

    /** Fake reader: map of "collection@parentId" → rows. */
    private static AlmTreeRoots over(Map<String, List<AlmTreeRoots.Row>> data) {
        return new AlmTreeRoots((collection, parentId) ->
                data.getOrDefault(collection + "@" + parentId, List.of()));
    }

    @Test
    @DisplayName("requirements: root is parented to -1, and parent-id 0 returns its CHILDREN")
    void requirementsRootIsAtMinusOne() {
        // Probe 16, populated project: -1 → 1 row (the root), 0 → 4 rows (children of root id 0).
        AlmTreeRoots roots = over(Map.of(
                "requirements@-1", List.of(new AlmTreeRoots.Row("0", "Requirements")),
                "requirements@0", List.of(
                        new AlmTreeRoots.Row("11", "child-a"), new AlmTreeRoots.Row("12", "child-b"),
                        new AlmTreeRoots.Row("13", "child-c"), new AlmTreeRoots.Row("14", "child-d"))));

        assertThat(roots.resolve("requirements").id()).isEqualTo("0");
    }

    @Test
    @DisplayName("test-folders: no -1 row, so it correctly falls back to parent-id 0")
    void testFoldersFallBackToZero() {
        AlmTreeRoots roots = over(Map.of(
                "test-folders@0", List.of(new AlmTreeRoots.Row("2", "Subject"))));

        assertThat(roots.resolve("test-folders").id()).isEqualTo("2");
    }

    @Test
    @DisplayName("⚠️ THE BUG: test-set-folders must resolve to Root, never to Recycle Bin")
    void testSetFoldersDoNotResolveToRecycleBin() {
        // Exactly the sandbox shape from probe 15: parent-id 0 returns ONE row, Recycle Bin, which
        // is structurally indistinguishable from a correct answer. The old rule returned it.
        AlmTreeRoots roots = over(Map.of(
                "test-set-folders@-1", List.of(new AlmTreeRoots.Row("0", "Root")),
                "test-set-folders@0", List.of(new AlmTreeRoots.Row("1", "Recycle Bin"))));

        AlmTreeRoots.Row root = roots.resolve("test-set-folders");
        assertThat(root.name()).isEqualTo("Root");
        assertThat(root.name()).isNotEqualTo("Recycle Bin");
    }

    @Test
    @DisplayName("release-folders: the root that was UNVERIFIED until probe 15")
    void releaseFoldersRoot() {
        AlmTreeRoots roots = over(Map.of(
                "release-folders@-1", List.of(new AlmTreeRoots.Row("1", "Releases"))));

        assertThat(roots.resolve("release-folders").id()).isEqualTo("1");
    }

    @Test
    @DisplayName("a multi-row parent-id-0 answer is the root's children and must NOT be guessed at")
    void multipleChildrenIsNotARoot() {
        // The populated-project shape with no -1 row: 6 rows under 0. Taking the first would be the
        // recycle-bin bug in a new costume.
        AlmTreeRoots roots = over(Map.of(
                "test-set-folders@0", List.of(
                        new AlmTreeRoots.Row("1", "a"), new AlmTreeRoots.Row("2", "b"),
                        new AlmTreeRoots.Row("3", "c"), new AlmTreeRoots.Row("4", "d"),
                        new AlmTreeRoots.Row("5", "e"), new AlmTreeRoots.Row("6", "f"))));

        assertThatThrownBy(() -> roots.resolve("test-set-folders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("children");
    }

    @Test
    @DisplayName("more than one node parented to -1 is refused rather than arbitrated")
    void multipleRootsIsAnError() {
        AlmTreeRoots roots = over(Map.of(
                "requirements@-1", List.of(new AlmTreeRoots.Row("0", "a"), new AlmTreeRoots.Row("9", "b"))));

        assertThatThrownBy(() -> roots.resolve("requirements"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected exactly one root");
    }

    @Test
    @DisplayName("an empty tree fails loudly instead of returning a null root")
    void emptyTreeFails() {
        assertThatThrownBy(() -> over(Map.of()).resolve("bpm-folders"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("resolveAll reports per-tree failures without losing the trees that did resolve")
    void resolveAllIsPartiallyTolerant() {
        // A project with no BPM module must still get a working requirements tree.
        AlmTreeRoots roots = over(Map.of(
                "requirements@-1", List.of(new AlmTreeRoots.Row("0", "Requirements")),
                "test-folders@0", List.of(new AlmTreeRoots.Row("2", "Subject"))));

        List<AlmTreeRoots.Resolved> all = roots.resolveAll();

        assertThat(all).hasSize(AlmTreeRoots.TREE_COLLECTIONS.size());
        assertThat(all).filteredOn(AlmTreeRoots.Resolved::ok)
                .extracting(AlmTreeRoots.Resolved::collection)
                .containsExactly("requirements", "test-folders");
        assertThat(all).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(r.error()).isNotBlank());
    }
}
