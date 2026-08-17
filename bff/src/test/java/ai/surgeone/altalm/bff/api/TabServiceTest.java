package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelation;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelationParser;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tab strip end to end over the real captured relations, with only ALM's HTTP stubbed.
 *
 * <p>Uses the probe-22 fixtures rather than hand-built relations on purpose: the parts that have
 * been wrong so far — which endpoint holds the discriminator, which column to filter — are exactly
 * the parts a hand-built relation would encode according to my current belief rather than ALM's
 * actual payload.
 */
class TabServiceTest {

    private static final AlmProjectRef PROJECT = new AlmProjectRef("D", "SANDBOX");
    private static final Path FIXTURES = Path.of("..", "tests", "fixtures");

    private final AlmEntityClient entities = mock(AlmEntityClient.class);
    private final AlmMetadataCatalog metadata = mock(AlmMetadataCatalog.class);
    private final AlmAccessPolicy policy = new AlmAccessPolicy(PROJECT, Set.of());
    private final GridService grids = new GridService(entities, metadata, policy);
    private final TabService service = new TabService(grids, metadata, policy);

    private static List<AlmRelation> relations(String entity) throws IOException {
        Path path = FIXTURES.resolve("customization-relations-" + entity + ".json");
        assumeThat(Files.exists(path)).isTrue();
        return AlmRelationParser.parseRelations(Files.readString(path));
    }

    private static FieldDescriptor field(String name) {
        return new FieldDescriptor(name, "X_" + name, AlmFieldType.STRING, name, false, true, true,
                false, false, true, true, 0, 255);
    }

    @Test
    @DisplayName("the strip is derived from relations, and names the collection behind each tab")
    void stripComesFromRelations() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));

        TabDto.Strip strip = service.strip(PROJECT, "requirements");

        assertThat(strip.tabs()).extracting(TabDto.Tab::collection)
                .contains("attachments", "defect-links", "req-traces", "requirement-coverages");
        assertThat(strip.tabs()).filteredOn(TabDto.Tab::attachment).hasSize(1);
    }

    @Test
    @DisplayName("⚠️ Business Models Linkage is dropped, with the reason, because bpm-links 404s")
    void unreadableTabIsDroppedWithAReason() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));

        TabDto.Strip strip = service.strip(PROJECT, "requirements");

        // ALM shows this tab; we cannot fill it, because the obvious collection name is a 404
        // (probe 23) and guessing another would produce a tab that fails on click. The drop reason
        // ships to the client so the absence is explainable without re-running a probe.
        assertThat(strip.tabs()).extracting(TabDto.Tab::label)
                .doesNotContain("Business Models Linkage");
        assertThat(strip.dropped().values()).anyMatch(reason -> reason.contains("bpm-link"));
    }

    @Test
    @DisplayName("a tab's query is built from the relation's storage descriptor, not a special case")
    void tabRowsFilterByTheRelationsOwnColumn() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));
        when(metadata.fields(eq(PROJECT), eq("req-trace")))
                .thenReturn(List.of(field("id"), field("from-req-id"), field("to-req-id")));
        AtomicReference<AlmQuery> sent = captureQuery();

        service.rows(PROJECT, "requirements", "605", "req-trace");

        // req-trace's own columns drive the filter — nothing here is hardcoded per entity. Both
        // directions are queried, one table each; the captured query is whichever ran last.
        assertThat(sent.get().toQueryString()).containsAnyOf("from-req-id", "to-req-id");
        assertThat(sent.get().toQueryString()).contains("605");
    }

    @Test
    @DisplayName("⚠️ a polymorphic tab filters on the type too, or it lists other entities' links")
    void polymorphicTabAddsTheDiscriminator() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));
        when(metadata.fields(eq(PROJECT), eq("defect-link")))
                .thenReturn(List.of(field("id"), field("second-endpoint-id"),
                        field("second-endpoint-type")));
        AtomicReference<AlmQuery> sent = captureQuery();

        service.rows(PROJECT, "requirements", "605", "defect-link");

        // One defect-links table serves seven entity types (probe 23). Without the second clause a
        // requirement numbered 605 would show the linked defects of a test numbered 605.
        String query = sent.get().toQueryString();
        assertThat(query).contains("second-endpoint-id", "605");
        assertThat(query).contains("second-endpoint-type", "requirement");
    }

    @Test
    @DisplayName("an unknown tab key is absent, not an error — the strip is per-project")
    void unknownTabKeyIsEmpty() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));

        assertThat(service.rows(PROJECT, "requirements", "605", "no-such-tab")).isEmpty();
    }

    @Test
    @DisplayName("a relation naming a field the related entity does not have fails loudly")
    void relationEntityDisagreementIsNotQueried() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));
        // The relation says filter req-trace by from-req-id; this project's req-trace has no such
        // field. Querying anyway returns an opaque 404 that looks like a malformed query (probe 17).
        when(metadata.fields(eq(PROJECT), eq("req-trace"))).thenReturn(List.of(field("id")));

        assertThatThrownBy(() -> service.rows(PROJECT, "requirements", "605", "req-trace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("from-req-id")
                .hasMessageContaining("disagree");
    }

    @Test
    @DisplayName("a refused project is refused before any metadata is read")
    void accessIsCheckedFirst() {
        AlmProjectRef stranger = new AlmProjectRef("D", "NOT_ALLOWED");

        assertThatThrownBy(() -> service.strip(stranger, "requirements"))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    /** Captures the query the grid would send, and returns an empty page so the read completes. */
    private AtomicReference<AlmQuery> captureQuery() {
        AtomicReference<AlmQuery> sent = new AtomicReference<>();
        when(entities.page(any(), any(), any())).thenAnswer(inv -> {
            sent.set(inv.getArgument(2));
            return new AlmEntityPage(List.of(), 0);
        });
        return sent;
    }

    @Test
    @DisplayName("tab rows come back shaped as a grid, so the SPA reuses its table")
    void tabRowsAreAGrid() throws IOException {
        when(metadata.relations(eq(PROJECT), eq("requirement"))).thenReturn(relations("requirement"));
        when(metadata.fields(eq(PROJECT), eq("req-trace")))
                .thenReturn(List.of(field("id"), field("from-req-id"), field("to-req-id")));
        when(entities.page(any(), any(), any())).thenReturn(new AlmEntityPage(
                List.of(new AlmEntityPage.AlmEntity("req-trace",
                        Map.of("id", List.of("7")), 0, "Success", "")), 1));

        List<TabDto.TableRows> tables =
                service.rows(PROJECT, "requirements", "605", "req-trace").orElseThrow();

        assertThat(tables).isNotEmpty();
        assertThat(tables.getFirst().grid().collection()).isEqualTo("req-traces");
        assertThat(tables.getFirst().grid().rows()).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo("7"));
    }
}
