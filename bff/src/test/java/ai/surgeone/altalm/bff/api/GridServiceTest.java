package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GridServiceTest {

    private static final AlmProjectRef SANDBOX = new AlmProjectRef("D", "SANDBOX");
    private static final AlmProjectRef READONLY = new AlmProjectRef("D", "OTHER");

    private final AlmEntityClient entities = mock(AlmEntityClient.class);
    private final AlmMetadataCatalog metadata = mock(AlmMetadataCatalog.class);
    private final AlmAccessPolicy policy = new AlmAccessPolicy(SANDBOX, Set.of(READONLY));
    private final GridService service = new GridService(entities, metadata, policy);

    private static FieldDescriptor field(String name, AlmFieldType type) {
        return new FieldDescriptor(name, "RQ_" + name.toUpperCase(), type, name + " label",
                false, true, true, false, false, true, true, false, 0, 255);
    }

    private static AlmEntityPage page(int totalResults, AlmEntityPage.AlmEntity... rows) {
        return new AlmEntityPage(List.of(rows), totalResults);
    }

    private static AlmEntityPage.AlmEntity row(String id, String name) {
        return new AlmEntityPage.AlmEntity("requirement",
                Map.of("id", List.of(id), "name", List.of(name)), 0, "Success", "");
    }

    @Test
    @DisplayName("a typed record's columns narrow to its SUBTYPE's field set")
    void detailNarrowsColumnsToTheRecordSubtype() {
        when(metadata.fields(eq(SANDBOX), eq("requirement"))).thenReturn(List.of(
                field("id", AlmFieldType.NUMBER), field("name", AlmFieldType.STRING),
                field("type-id", AlmFieldType.NUMBER), field("status", AlmFieldType.LOOKUP_LIST)));
        // Type 1 is Folder, which genuinely has no `status` — probe 25 measured exactly this.
        when(metadata.fields(eq(SANDBOX), eq("requirement"), eq("1"))).thenReturn(List.of(
                field("id", AlmFieldType.NUMBER), field("name", AlmFieldType.STRING),
                field("type-id", AlmFieldType.NUMBER)));
        when(entities.page(eq(SANDBOX), eq("requirements"), any())).thenReturn(page(1,
                new AlmEntityPage.AlmEntity("requirement",
                        Map.of("id", List.of("7"), "name", List.of("a"), "type-id", List.of("1")),
                        0, "Success", "")));

        GridDto.Grid grid = service.detail(SANDBOX, "requirements", "7").orElseThrow();

        // The entity-level set would put a Direct Cover Status on a folder, which the stock client
        // does not. Only the column list narrows; the values are untouched.
        assertThat(grid.columns()).extracting(GridDto.Column::name)
                .containsExactly("id", "name", "type-id")
                .doesNotContain("status");
    }

    @Test
    @DisplayName("a record with no type-id is left alone, and never asks for per-type fields")
    void detailWithoutATypeIdDoesNotAskForSubtypeFields() {
        when(metadata.fields(eq(SANDBOX), eq("defect"))).thenReturn(List.of(
                field("id", AlmFieldType.NUMBER), field("name", AlmFieldType.STRING)));
        when(entities.page(eq(SANDBOX), eq("defects"), any())).thenReturn(page(1,
                new AlmEntityPage.AlmEntity("defect",
                        Map.of("id", List.of("7"), "name", List.of("a")), 0, "Success", "")));

        GridDto.Grid grid = service.detail(SANDBOX, "defects", "7").orElseThrow();

        assertThat(grid.columns()).hasSize(2);
        // ⚠️ This gate is load-bearing: probe 25 found `defect`'s types endpoint returns HTTP 500,
        // and a failed metadata load is deliberately not cached — so asking unconditionally would
        // fire a failing upstream request every time anyone opened a defect, forever.
        verify(metadata, never()).fields(any(), any(), any());
    }

    @Test
    @DisplayName("columns come from THIS project's metadata, not the sandbox's")
    void columnsAreProjectScoped() {
        when(metadata.fields(eq(READONLY), eq("requirement")))
                .thenReturn(List.of(field("id", AlmFieldType.NUMBER), field("name", AlmFieldType.STRING)));
        when(entities.page(eq(READONLY), eq("requirements"), any())).thenReturn(page(1, row("1", "a")));

        GridDto.Grid grid = service.grid(READONLY, "requirements", 50, 1, null, false);

        assertThat(grid.columns()).extracting(GridDto.Column::name).containsExactly("id", "name");
        assertThat(grid.columns()).extracting(GridDto.Column::type).containsExactly("NUMBER", "STRING");
    }

    @Test
    @DisplayName("writable follows the allowlist, so an enrolled project reports true")
    void writabilityReflectsThePolicy() {
        // Until 2026-08-18 this asserted READONLY was not writable and only the sandbox was. The
        // user lifted the sandbox-only write rule; enrolment is now the whole test. Rewritten rather
        // than deleted so the flag's meaning stays traceable - the SPA renders edit affordances off
        // it, and it silently changed meaning for every enrolled project.
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));
        when(entities.page(any(), any(), any())).thenReturn(page(0));

        assertThat(service.grid(READONLY, "requirements", 50, 1, null, false).writable()).isTrue();
        assertThat(service.grid(SANDBOX, "requirements", 50, 1, null, false).writable()).isTrue();
    }

    @Test
    @DisplayName("reportedTotal is passed through untouched and never used as a row count")
    void reportedTotalIsNotTreatedAsACount() {
        // Probe 15's trap in miniature: two real rows alongside TotalResults=0.
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));
        when(entities.page(any(), any(), any())).thenReturn(page(0, row("1", "a"), row("2", "b")));

        GridDto.Grid grid = service.grid(READONLY, "requirements", 50, 1, null, false);

        assertThat(grid.page().rowsReturned()).isEqualTo(2);
        assertThat(grid.page().reportedTotal()).isZero();
        assertThat(grid.rows()).hasSize(2);
    }

    @Test
    @DisplayName("a full page sets mayHaveMore; a short page does not")
    void mayHaveMoreIsInferredFromFullness() {
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));

        when(entities.page(any(), any(), any())).thenReturn(page(2, row("1", "a"), row("2", "b")));
        assertThat(service.grid(READONLY, "requirements", 2, 1, null, false).page().mayHaveMore()).isTrue();

        when(entities.page(any(), any(), any())).thenReturn(page(1, row("1", "a")));
        assertThat(service.grid(READONLY, "requirements", 50, 1, null, false).page().mayHaveMore()).isFalse();
    }

    @Test
    @DisplayName("sorting builds a semicolon-separated order-by via AlmQuery (probe 17)")
    void sortIsAppliedThroughTheBuilder() {
        when(metadata.fields(any(), any()))
                .thenReturn(List.of(field("id", AlmFieldType.NUMBER), field("name", AlmFieldType.STRING)));
        AtomicReference<AlmQuery> captured = new AtomicReference<>();
        when(entities.page(any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(2));
            return page(0);
        });

        service.grid(READONLY, "requirements", 50, 1, "name", true);

        assertThat(captured.get().toQueryString()).contains("order-by={name[DESC]}");
    }

    @Test
    @DisplayName("an unknown sort field is rejected here, because the server's error is ambiguous")
    void unknownSortFieldIsRejectedLocally() {
        // Probe 17: a bad order-by FIELD and a bad order-by SEPARATOR produce the identical server
        // error ("not existing field"), so the server cannot say which mistake was made. Catching
        // it here is the only way the caller learns which one it actually was.
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));

        assertThatThrownBy(() -> service.grid(READONLY, "requirements", 50, 1, "no-such-field", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown sort field");
    }

    @Test
    @DisplayName("an unknown filter field is rejected against THIS project's metadata")
    void unknownFilterFieldIsRejected() {
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));

        assertThatThrownBy(() -> service.grid(READONLY, "requirements", 50, 1, null, false,
                java.util.Map.of("not-a-field", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown filter field");
    }

    @Test
    @DisplayName("filters compose into a single query clause set")
    void filtersAreApplied() {
        when(metadata.fields(any(), any()))
                .thenReturn(List.of(field("id", AlmFieldType.NUMBER), field("status", AlmFieldType.LOOKUP_LIST)));
        AtomicReference<AlmQuery> captured = new AtomicReference<>();
        when(entities.page(any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(2));
            return page(0);
        });

        service.grid(READONLY, "requirements", 50, 1, null, false, java.util.Map.of("status", "Passed"));

        assertThat(captured.get().toQueryString()).contains("query={status[Passed]}");
    }

    @Test
    @DisplayName("detail returns empty for an id that does not exist rather than an empty grid")
    void detailIsEmptyWhenMissing() {
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));
        when(entities.page(any(), any(), any())).thenReturn(page(0));

        assertThat(service.detail(READONLY, "requirements", "999")).isEmpty();
    }

    @Test
    @DisplayName("detail refuses to pick a winner if an id lookup somehow returns two rows")
    void detailRefusesAmbiguity() {
        when(metadata.fields(any(), any())).thenReturn(List.of(field("id", AlmFieldType.NUMBER)));
        when(entities.page(any(), any(), any())).thenReturn(page(2, row("1", "a"), row("1", "b")));

        assertThatThrownBy(() -> service.detail(READONLY, "requirements", "1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique lookup");
    }

    @Test
    @DisplayName("an unknown collection fails loudly rather than rendering a column-less grid")
    void unknownCollectionIsRejected() {
        // Asserts the guarantee, not the wording: the rejected name is named back, and the caller
        // is told what IS allowed. "widgets" ends in "s", which an earlier trim-the-plural
        // implementation happily accepted — that bug is what made this an allowlist.
        assertThatThrownBy(() -> service.grid(READONLY, "widgets", 50, 1, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("widgets")
                .hasMessageContaining("requirements");
    }

    @Test
    @DisplayName("a plausible-looking plural is still rejected — the allowlist has no fallback")
    void plausiblePluralsAreStillRejected() {
        // "widgets" ends in 's', so any trim-the-plural heuristic would accept it and aim an
        // authenticated ALM session at /widgets. The allowlist is what prevents that.
        assertThatThrownBy(() -> service.grid(READONLY, "widgets", 50, 1, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grid(READONLY, "../../sa/api/site-users", 50, 1, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an allowlisted collection resolves to its singular entity name")
    void allowlistedCollectionResolves() {
        when(metadata.fields(eq(READONLY), eq("test-set-folder"))).thenReturn(List.of());
        when(entities.page(any(), any(), any())).thenReturn(page(0));

        GridDto.Grid grid = service.grid(READONLY, "test-set-folders", 50, 1, null, false);

        assertThat(grid.collection()).isEqualTo("test-set-folders");
    }

    @Test
    @DisplayName("a column's `writable` comes from `virtual` ALONE, never from required/editable")
    void writableIsVirtualOnly() {
        // The exact probe-9 shape: reported neither required nor editable, and ALM demands it on
        // create. A contract that derived `writable` from `editable` would tell the SPA to grey out
        // the one field the server insists on, and the user would have no way to supply it.
        FieldDescriptor probe9 = new FieldDescriptor("ref-count", "TP_REF_COUNT",
                AlmFieldType.NUMBER, "Ref count",
                false, false, true, false, false, true, true, false, 0, 0);
        // Computed server-side - the one write-related flag probing found trustworthy.
        FieldDescriptor computed = new FieldDescriptor("father-name", "RQ_FATHER_NAME",
                AlmFieldType.STRING, "Req Parent",
                false, false, true, true, false, true, true, false, 0, 255);

        assertThat(GridDto.Column.of(probe9).writable())
                .as("editable:false must NOT make a field unwritable - probe 9")
                .isTrue();
        assertThat(GridDto.Column.of(computed).writable())
                .as("virtual IS a reliable never-writable signal")
                .isFalse();
    }
}
