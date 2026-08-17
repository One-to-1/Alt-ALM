package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Level-batched tree expansion.
 *
 * <p>The behaviour under test exists because of two probes that disagreed with the documentation:
 * probe 19 (ALM's {@code children-count} is always 0, so it cannot drive expanders) and probe 20
 * (a {@code parent-id[a OR b]} filter resolves many parents in one query, so the honest answer is
 * affordable).
 */
class TreeServiceTest {

    private static final AlmProjectRef PROJECT = new AlmProjectRef("D", "P");

    private final AlmEntityClient entities = mock(AlmEntityClient.class);
    private final ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog metadata =
            mock(ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog.class);
    private final ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy policy =
            new ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy(PROJECT, java.util.Set.of());
    private final TreeService service = new TreeService(entities, metadata, policy);

    private static AlmEntityPage.AlmEntity node(String id, String name, String parentId) {
        return new AlmEntityPage.AlmEntity("requirement",
                Map.of("id", List.of(id), "name", List.of(name), "parent-id", List.of(parentId)),
                0, "Success", "");
    }

    /** Answers each call from a parent -> children map, recording the queries it was asked. */
    private List<String> stubHierarchy(Map<String, List<String>> tree) {
        List<String> queries = new ArrayList<>();
        when(entities.page(eq(PROJECT), eq("requirements"), any())).thenAnswer(invocation -> {
            AlmQuery query = invocation.getArgument(2);
            String rendered = query.toQueryString();
            queries.add(rendered);
            List<AlmEntityPage.AlmEntity> rows = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : tree.entrySet()) {
                // The clause is parent-id[a%20OR%20b]; a parent is requested when its id appears
                // between the brackets as a whole term.
                if (containsTerm(rendered, entry.getKey())) {
                    for (String child : entry.getValue()) {
                        rows.add(node(child, "node " + child, entry.getKey()));
                    }
                }
            }
            return new AlmEntityPage(rows, rows.size());
        });
        return queries;
    }

    private static boolean containsTerm(String rendered, String id) {
        int start = rendered.indexOf("parent-id[");
        if (start < 0) {
            return false;
        }
        String clause = rendered.substring(start + "parent-id[".length(), rendered.indexOf(']', start));
        for (String term : clause.split("%20OR%20")) {
            if (term.equals(id)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("hasChildren is exact: a leaf reports false, a parent reports true")
    void hasChildrenIsDerivedNotGuessed() {
        stubHierarchy(Map.of(
                "0", List.of("10", "20"),
                "10", List.of("11")));   // 20 is a leaf

        TreeDto.Children children = service.children(PROJECT, "requirements", "0");

        assertThat(children.exact()).isTrue();
        assertThat(children.nodes()).extracting(TreeDto.Node::id).containsExactlyInAnyOrder("10", "20");
        assertThat(children.nodes()).filteredOn(n -> n.id().equals("10"))
                .extracting(TreeDto.Node::hasChildren).containsExactly(true);
        assertThat(children.nodes()).filteredOn(n -> n.id().equals("20"))
                .extracting(TreeDto.Node::hasChildren).containsExactly(false);
    }

    @Test
    @DisplayName("a whole level is one query, not one query per node")
    void aLevelCostsTwoQueriesRegardlessOfWidth() {
        List<String> parents = new ArrayList<>();
        Map<String, List<String>> tree = new java.util.HashMap<>();
        for (int i = 1; i <= 40; i++) {
            parents.add(String.valueOf(i));
            tree.put(String.valueOf(i), List.of("c" + i));
        }
        List<String> queries = stubHierarchy(tree);

        TreeDto.Children children = service.children(PROJECT, "requirements", parents);

        assertThat(children.nodes()).hasSize(40);
        // One query for the level, one for the level below it to settle hasChildren.
        assertThat(queries).hasSize(2);
    }

    @Test
    @DisplayName("more than 120 parents are chunked rather than sent as one oversized URL (Q48)")
    void idsAreChunked() {
        List<String> parents = new ArrayList<>();
        Map<String, List<String>> tree = new java.util.HashMap<>();
        for (int i = 1; i <= 250; i++) {
            parents.add(String.valueOf(i));
            tree.put(String.valueOf(i), List.of());
        }
        List<String> queries = stubHierarchy(tree);

        service.children(PROJECT, "requirements", parents);

        // 250 parents -> 3 chunks of at most 120. No second pass: the first returned no rows.
        assertThat(queries).hasSize(3);
        for (String q : queries) {
            String clause = q.substring(q.indexOf("parent-id[") + 10, q.indexOf(']'));
            assertThat(clause.split("%20OR%20")).hasSizeLessThanOrEqualTo(120);
        }
    }

    @Test
    @DisplayName("duplicate and blank parent ids are dropped before they reach the URL")
    void parentIdsAreCleaned() {
        List<String> queries = stubHierarchy(Map.of("7", List.of()));

        service.children(PROJECT, "requirements", java.util.Arrays.asList("7", " 7 ", "", null, "7"));

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst()).contains("parent-id[7]");
    }

    @Test
    @DisplayName("a full page means hasChildren may be wrong, so it degrades to optimistic and says so")
    void truncationIsReportedNotHidden() {
        // First call returns one node; the second (grandchildren) comes back filled to the cap,
        // which is indistinguishable from truncated.
        when(entities.page(eq(PROJECT), eq("requirements"), any())).thenAnswer(invocation -> {
            AlmQuery query = invocation.getArgument(2);
            if (containsTerm(query.toQueryString(), "0")) {
                return new AlmEntityPage(List.of(node("10", "only child", "0")), 1);
            }
            List<AlmEntityPage.AlmEntity> full = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                full.add(node("x" + i, "n", "999"));   // none of them parented by 10
            }
            return new AlmEntityPage(full, full.size());
        });

        TreeDto.Children children = service.children(PROJECT, "requirements", "0");

        assertThat(children.exact()).isFalse();
        // 10 was NOT seen as a parent, but the page may have been cut off — so it must not be
        // reported as a leaf. A missing expander hides a subtree; a spurious one costs one request.
        assertThat(children.nodes()).extracting(TreeDto.Node::hasChildren).containsExactly(true);
    }

    @Test
    @DisplayName("rows() carries field values and this project's columns, plus exact hasChildren")
    void rowsCarryValuesAndColumns() {
        when(metadata.fields(eq(PROJECT), eq("requirement"))).thenReturn(List.of(
                new ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor(
                        "id", "RQ_REQ_ID", ai.surgeone.altalm.bff.alm.metadata.AlmFieldType.NUMBER,
                        "Req ID", false, true, true, false, false, true, true, 0, 255),
                new ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor(
                        "name", "RQ_REQ_NAME", ai.surgeone.altalm.bff.alm.metadata.AlmFieldType.STRING,
                        "Name", false, true, true, false, false, true, true, 0, 255)));
        stubHierarchy(Map.of(
                "0", List.of("10", "20"),
                "10", List.of("11")));

        TreeDto.Rows rows = service.rows(PROJECT, "requirements", List.of("0"));

        assertThat(rows.columns()).extracting(GridDto.Column::label).containsExactly("Req ID", "Name");
        assertThat(rows.nodes()).extracting(TreeDto.Row::id).containsExactlyInAnyOrder("10", "20");
        assertThat(rows.nodes()).filteredOn(n -> n.id().equals("10"))
                .extracting(TreeDto.Row::hasChildren).containsExactly(true);
        assertThat(rows.nodes()).filteredOn(n -> n.id().equals("20"))
                .extracting(TreeDto.Row::hasChildren).containsExactly(false);
        // The values map is the entity's own fields, so the tree-grid can render any column.
        assertThat(rows.nodes().getFirst().values()).containsKey("name");
        assertThat(rows.exact()).isTrue();
    }

    @Test
    @DisplayName("rows() refuses a non-tree collection just as children() does")
    void rowsRejectsNonTree() {
        assertThatThrownBy(() -> service.rows(PROJECT, "defects", List.of("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a tree collection");
    }

    @Test
    @DisplayName("a non-tree collection is refused, not passed through to the ALM URL")
    void nonTreeCollectionIsRejected() {
        assertThatThrownBy(() -> service.children(PROJECT, "defects", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a tree collection");
    }

    @Test
    @DisplayName("an empty parent list is refused rather than querying the whole collection")
    void emptyParentListIsRejected() {
        assertThatThrownBy(() -> service.children(PROJECT, "requirements", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one parentId");
    }
}
