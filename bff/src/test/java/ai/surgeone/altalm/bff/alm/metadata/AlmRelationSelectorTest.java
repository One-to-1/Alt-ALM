package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The reduction from ALM's raw relation list to a tab strip.
 *
 * <p>Each test names the rule it pins, because the rules are the design — the selector is only worth
 * having if the reasons it discards a candidate survive contact with a future refactor.
 */
class AlmRelationSelectorTest {

    private static final Path FIXTURES = Path.of("..", "tests", "fixtures");

    /**
     * What the BFF can currently populate a tab from — mirrors {@code AlmCollections}'s related set.
     *
     * <p>Kept identical to production on purpose. An earlier version of this test also allowed
     * {@code bpm-link}, which no collection can actually read (probe 23: {@code bpm-links} 404s), so
     * it asserted a tab the app never shows.
     */
    private static final Predicate<String> READABLE =
            Set.of("attachment", "defect-link", "req-trace", "requirement-coverage")::contains;

    private static List<AlmRelation> relations(String entity) throws IOException {
        Path path = FIXTURES.resolve("customization-relations-" + entity + ".json");
        assumeThat(Files.exists(path)).isTrue();
        return AlmRelationParser.parseRelations(Files.readString(path));
    }

    @Test
    @DisplayName("requirement reduces to ALM's own related-tab set")
    void requirementMatchesTheStockDialog() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        // The stock dialog's related-entity tabs are Attachments, Linked Defects, Requirement
        // Traceability, Test Coverage and Business Models Linkage. Five, and we produce five — the
        // last is dropped upstream when bpm-links proves unreadable, and requirement-coverage is
        // reached two ways that merge into one tab.
        assertThat(s.tabs()).extracting(AlmRelationSelector.Tab::readEntity)
                .containsExactlyInAnyOrder("attachment", "defect-link", "req-trace",
                        "requirement-coverage");

        // ⚠️ An earlier version produced EIGHT, because it made one tab per (far end, entity read)
        // pair — so req-trace, requirement-coverage and bpm-link each appeared twice, once reached
        // directly and once through an association. Merging them by read entity fixed that and broke
        // defect, and the trade-off was written down as a limit. It was not: one tab holding several
        // TABLES gets both right. This asserts the count so nobody reintroduces the split.
        assertThat(s.tabs()).hasSize(4);
    }

    @Test
    @DisplayName("rule 1: an unlabelled relation is a field-backed reference, not a tab")
    void unlabelledRelationsAreDropped() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("defect"), READABLE);

        assertThat(s.dropped()).containsKey("defectToTargetReleaseConnection");
        assertThat(s.dropped().get("defectToTargetReleaseConnection")).contains("no label");
        assertThat(s.tabs()).extracting(AlmRelationSelector.Tab::readEntity)
                .doesNotContain("release", "release-cycle", "environment");
    }

    @Test
    @DisplayName("rule 2: a self-referential containment is the tree, but a self-referential connection is a tab")
    void containmentIsTheHierarchyButConnectionIsNot() throws IOException {
        AlmRelationSelector.Selection reqs = AlmRelationSelector.select(relations("requirement"), READABLE);
        assertThat(reqs.dropped()).containsKey("requirementToRequirementContainment");
        assertThat(reqs.dropped().get("requirementToRequirementContainment")).contains("hierarchy");

        // defect→defect is `connection`, not `containment`: "Linked to Defects" is a real tab and
        // must not be swept up by the same rule. It reads through the defect-link join, so the tab
        // is identified by its far end.
        AlmRelationSelector.Selection defects = AlmRelationSelector.select(relations("defect"), READABLE);
        assertThat(defects.tabs()).flatExtracting(AlmRelationSelector.Tab::tables)
                .extracting(AlmRelationSelector.Table::label)
                .contains("Linked from Defects", "Linked to Defects");
    }

    @Test
    @DisplayName("⚠️ a polymorphic join must not collapse nine tabs into one")
    void defectLinkFansOutByFarEnd() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("defect"), READABLE);

        // Nine of defect's relations read `defect-link`, to nine different far ends. Grouping by the
        // entity read alone — the first rule I wrote — merged all nine into one tab, which would
        // have shown linked runs under the heading "Linked to Defects". This is the regression test
        // for that.
        // They now arrive as nine TABLES under one tab, which is what the stock client does — a
        // defect has a single Linked Entities grid, not nine tabs. What must never happen is the
        // nine collapsing into one query, and each keeping its own table is what prevents that.
        assertThat(s.tabs()).flatExtracting(AlmRelationSelector.Tab::tables)
                .extracting(AlmRelationSelector.Table::label)
                .contains("Linked Requirements", "Linked Runs", "Linked Tests");
    }

    @Test
    @DisplayName("rule 3: a tab nothing can fill is not offered")
    void unreadableTargetsAreDropped() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("test"), READABLE);

        // Dropped for its READ entity, not its target: this relation ends at deleted-asset-info but
        // reads through the assets-relation join, and assets-relation is what we cannot query.
        assertThat(s.dropped()).containsKey("testToDeletedAssetInfoUsage");
        assertThat(s.dropped().get("testToDeletedAssetInfoUsage")).contains("assets-relation");
        assertThat(s.tabs()).extracting(AlmRelationSelector.Tab::readEntity)
                .doesNotContain("deleted-asset-info", "user-asset", "repository-item",
                        "assets-relation");
    }

    @Test
    @DisplayName("rule 4: traceability is ONE tab holding TWO tables, exactly as ALM shows it")
    void traceabilityIsOneTabWithTwoTables() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab traces = s.tabs().stream()
                .filter(t -> "req-trace".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // ALM's Requirement Traceability tab holds two grids — "Trace From (Requirements that
        // affect X)" and "Trace To (Requirements affected by X)". One per direction, and the
        // direction IS the query: one filters from-req-id, the other to-req-id.
        assertThat(traces.tables()).hasSize(2);
        assertThat(traces.tables()).extracting(AlmRelationSelector.Table::label)
                .containsExactlyInAnyOrder("Trace", "Traced To Requirements");

        // Each direction merges its reference relation with its association twin, because they
        // query identical rows — and the association half is what makes the rows followable.
        assertThat(traces.tables()).allSatisfy(table ->
                assertThat(table.relations()).hasSize(2));
        assertThat(traces.tables()).extracting(AlmRelationSelector.Table::targetEntity)
                .containsOnly("requirement");
    }

    @Test
    @DisplayName("rule 5: two read entities sharing a label are one tab")
    void bothDefectRoutesBecomeOneTab() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        List<AlmRelationSelector.Tab> linkedDefects = s.tabs().stream()
                .filter(t -> "Linked Defects".equals(t.label()))
                .toList();

        // requirement reaches defects as a `defect` connection AND a `defect-link` link, both
        // labelled "Linked Defects". One tab, two backing relations.
        assertThat(linkedDefects).hasSize(1);
        assertThat(linkedDefects.getFirst().relations()).hasSize(2);
    }

    @Test
    @DisplayName("a discriminated query beside its own superset is ONE table, not two")
    void aLoneRefinementFoldsIntoItsSuperset() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab coverage = s.tabs().stream()
                .filter(t -> "requirement-coverage".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // A requirement reaches its coverage twice: `requirement-id` alone, and `requirement-id`
        // plus `entity-type[test]`. Those were two tables, and against a live project both returned
        // the SAME 29 rows — the second captioned "Requirement to Tests that cover Requirement",
        // which is the relation's formal name and reads as machinery.
        //
        // Adding a discriminator can only narrow, so the narrow one is a subset that contributes
        // nothing but navigability. One table: the broad query for the rows, the navigable relation
        // for the link column.
        assertThat(coverage.tables()).hasSize(1);
        assertThat(coverage.tables().getFirst().label()).isEqualTo("Test Coverage");
        assertThat(coverage.tables().getFirst().relations())
                .anyMatch(AlmRelation::navigable)
                .anyMatch(r -> !r.discriminated());
    }

    @Test
    @DisplayName("⚠️ but NINE discriminated queries beside a superset stay nine tables")
    void aFanOutIsNotFoldedIntoItsUnion() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("defect"), READABLE);

        // This is the regression the fold rule caused on its first attempt and this suite caught.
        // A defect's `first-endpoint-id` carries nine discriminated relations AND an undiscriminated
        // one (`defectToDefectLinkLink`), so "fold anything that has an undiscriminated sibling"
        // merged Linked Requirements, Linked Runs and Linked Tests into a single list of every link
        // the defect has.
        //
        // The distinguishing fact is the COUNT: one narrow group beside a broad one is a
        // refinement of it; nine are disjoint slices next to their union.
        assertThat(s.tabs()).flatExtracting(AlmRelationSelector.Tab::tables)
                .extracting(AlmRelationSelector.Table::label)
                .contains("Linked Requirements", "Linked Runs", "Linked Tests");
    }

    @Test
    @DisplayName("⚠️ a fan-out tab is not headed with the name of one of its ten contents")
    void aFanOutTabTakesANeutralHeading() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("defect"), READABLE);

        AlmRelationSelector.Tab links = s.tabs().stream()
                .filter(t -> "defect-link".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // "Take the shortest caption" headed this tab "Linked Runs" — over a tab that also holds
        // linked requirements, tests, test sets and test instances. Fluent and wrong.
        assertThat(links.tables()).hasSizeGreaterThan(1);
        assertThat(links.label()).isEqualTo("Defect Links");
    }

    @Test
    @DisplayName("but a shortest caption that GENERALISES the others is still used")
    void aGeneralisingCaptionHeadsItsTab() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab traces = s.tabs().stream()
                .filter(t -> "req-trace".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // "Trace" is a prefix of "Traced To Requirements", so it genuinely covers both directions
        // rather than naming one of them. The per-direction detail stays on each table's caption.
        assertThat(traces.label()).isEqualTo("Trace");
    }

    @Test
    @DisplayName("a table takes ALM's readable caption, not the relation's formal name")
    void tableLabelPrefersTheReadableForm() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab coverage = s.tabs().stream()
                .filter(t -> "requirement-coverage".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // The group holds "Test Coverage" and "Requirement to Tests that cover Requirement". The
        // second is the relation's formal name and reads as machinery; ALM's own tab says the first.
        // Two tables, and both are real: the plain reference lists ALL coverage rows, while the
        // association adds `entity-type[test]` and so lists only the test-backed ones. Different
        // queries, so different grids — but the caption of the broad one is ALM's own word for it.
        assertThat(coverage.tables()).extracting(AlmRelationSelector.Table::label)
                .contains("Test Coverage");
    }

    @Test
    @DisplayName("attachments are identifiable, because they read as a sub-resource not a collection")
    void attachmentTabIsFlagged() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        assertThat(s.tabs()).filteredOn(AlmRelationSelector.Tab::isAttachment)
                .singleElement()
                .satisfies(t -> assertThat(t.readEntity()).isEqualTo("attachment"));
    }

    @Test
    @DisplayName("every discarded candidate is recorded with its reason")
    void nothingIsDroppedSilently() throws IOException {
        List<AlmRelation> all = relations("requirement");
        AlmRelationSelector.Selection s = AlmRelationSelector.select(all, READABLE);

        int kept = s.tabs().stream().mapToInt(t -> t.relations().size()).sum();
        // The point of the audit trail: candidates in == tabs' backing relations + dropped. If a
        // future rule silently swallows one, this fails.
        assertThat(kept + s.dropped().size()).isEqualTo(all.size());
    }

    @Test
    @DisplayName("a project where nothing is readable yields no tabs rather than empty ones")
    void nothingReadableMeansNoTabs() throws IOException {
        AlmRelationSelector.Selection s =
                AlmRelationSelector.select(relations("requirement"), e -> false);

        assertThat(s.tabs()).isEmpty();
        assertThat(s.dropped()).isNotEmpty();
    }

    /**
     * Builds one relation the way ALM's storage descriptor hands it over.
     *
     * <p>Written out by hand rather than parsed from a fixture because the case being pinned is a
     * <em>shape</em> — mirrored, containment, no join entity — and no captured project happens to
     * make it interesting for an entity we already have a fixture for.
     */
    private static AlmRelation relation(String name, String source, String target, String type,
                                        String association, boolean mirrored, String filterIdField) {
        return new AlmRelation(name, "Label", source, target, type, association, mirrored,
                filterIdField, "", "", "");
    }

    @Test
    @DisplayName("⚠️ a mirrored containment reference is not fillable — its filter column is the source's")
    void containerReferenceIsNotFillable() {
        // "the folder this test set is in". ALM hands back parent-id, which is a column on the TEST
        // SET, while the tab would query TEST-SET-FOLDERS. Filtering folders by parent-id = the set's
        // id asks which folders are children of a test set: always none.
        AlmRelation container = relation("testSetFolderToTestSetContainment_mirrored",
                "test-set-folder", "test-set-folder", AlmRelation.TYPE_CONTAINMENT, "", true,
                "parent-id");
        assertThat(container.pointsAtOwnContainer()).isTrue();
        assertThat(container.fillable()).isFalse();

        // The forward direction — a folder's contents — is a real tab and must survive.
        AlmRelation contents = relation("testSetFolderToTestSetContainment",
                "test-set-folder", "test-set", AlmRelation.TYPE_CONTAINMENT, "", false, "parent-id");
        assertThat(contents.pointsAtOwnContainer()).isFalse();
        assertThat(contents.fillable()).isTrue();

        // So is a mirrored ASSOCIATION: its columns live on the join entity, where the invariant
        // that filterIdField belongs to the read entity actually holds.
        AlmRelation linked = relation("defectToRequirementLink_mirrored", "defect", "requirement",
                "dependency", "defect-link", true, "second-endpoint-id");
        assertThat(linked.pointsAtOwnContainer()).isFalse();
        assertThat(linked.fillable()).isTrue();
    }
}
