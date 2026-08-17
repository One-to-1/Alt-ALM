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

    /** What the BFF can currently populate a tab from. Mirrors the api layer's readable set. */
    private static final Predicate<String> READABLE = Set.of(
            "attachment", "defect", "defect-link", "req-trace", "requirement-coverage",
            "bpm-link", "requirement", "test", "test-instance", "run", "design-step",
            "test-config", "test-parameter", "run-step", "test-set")::contains;

    private static List<AlmRelation> relations(String entity) throws IOException {
        Path path = FIXTURES.resolve("customization-relations-" + entity + ".json");
        assumeThat(Files.exists(path)).isTrue();
        return AlmRelationParser.parseRelations(Files.readString(path));
    }

    @Test
    @DisplayName("requirement reduces to a superset of ALM's related-tab set — it over-shows by design")
    void requirementCoversTheStockDialogAndOverShows() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        // Every related-entity tab in the reference screenshot is present. History is not a relation
        // (it is the /audits sub-resource) and Risk Analysis is field-backed, so neither appears here.
        assertThat(s.tabs()).extracting(AlmRelationSelector.Tab::readEntity)
                .contains("attachment", "defect-link", "req-trace", "requirement-coverage", "bpm-link");

        // …and three extras, all the same shape: a join entity reachable both directly and through
        // an association, which ALM shows as one tab and we show as two.
        //   req-trace            + req-trace:requirement            (Traced To… / Trace)
        //   requirement-coverage + requirement-coverage:test        (Test Coverage / …that cover…)
        //   bpm-link             + bpm-link:requirement             (BP Model Link / Business Models Linkage)
        // Merging those by read entity is exactly the rule that breaks defect — see
        // defectLinkFansOutByFarEnd. Pinned at 8 so the number is a decision, not a drift.
        assertThat(s.tabs()).hasSize(8);
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
        assertThat(defects.tabs()).extracting(AlmRelationSelector.Tab::label)
                .contains("Linked to Defects");
    }

    @Test
    @DisplayName("⚠️ a polymorphic join must not collapse nine tabs into one")
    void defectLinkFansOutByFarEnd() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("defect"), READABLE);

        // Nine of defect's relations read `defect-link`, to nine different far ends. Grouping by the
        // entity read alone — the first rule I wrote — merged all nine into one tab, which would
        // have shown linked runs under the heading "Linked to Defects". This is the regression test
        // for that.
        assertThat(s.tabs()).extracting(AlmRelationSelector.Tab::label)
                .contains("Linked to Defects", "Linked Requirements", "Linked Runs", "Linked Tests");
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
    @DisplayName("rule 4: two relations reading the same entity are one tab")
    void bothTraceDirectionsBecomeOneTab() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab traces = s.tabs().stream()
                .filter(t -> "req-trace".equals(t.key()))
                .findFirst().orElseThrow();

        // requirementToReqTraceLinkLeft + …Right — ALM shows one "Requirement Traceability" tab.
        // The `dependency` pair (Trace / Trace From) also reads req-trace but ends at `requirement`,
        // so the pair key keeps it in its own group; that is the over-showing documented on the
        // selector, not a merge failure.
        assertThat(traces.relations()).extracting(AlmRelation::name)
                .containsExactly("requirementToReqTraceLinkLeft", "requirementToReqTraceLinkRight");
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
    @DisplayName("the shorter, forward label wins a merge")
    void labelSelectionPrefersForwardAndShort() throws IOException {
        AlmRelationSelector.Selection s = AlmRelationSelector.select(relations("requirement"), READABLE);

        AlmRelationSelector.Tab bpm = s.tabs().stream()
                .filter(t -> "bpm-link".equals(t.readEntity()))
                .findFirst().orElseThrow();

        // The group holds "Business Models Linkage" and its mirrored twin "Requirement to
        // Requirement Business Models Linkage"; the unqualified forward label is the readable one.
        assertThat(bpm.label()).isEqualTo("Business Models Linkage");
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
}
