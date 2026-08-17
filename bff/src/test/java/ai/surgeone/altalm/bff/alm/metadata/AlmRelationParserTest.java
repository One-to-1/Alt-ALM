package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Parses the probe-22 relation captures with no server and no credentials, and pins the facts the
 * tab strip is built on — including the one that corrected probe 21.6.
 */
class AlmRelationParserTest {

    private static final Path FIXTURES = Path.of("..", "tests", "fixtures");

    private static Path fixture(String entity) {
        return FIXTURES.resolve("customization-relations-" + entity + ".json");
    }

    private static Stream<Path> relationFixtures() throws IOException {
        if (!Files.isDirectory(FIXTURES)) {
            return Stream.empty();
        }
        try (var s = Files.list(FIXTURES)) {
            return s.filter(p -> p.getFileName().toString().startsWith("customization-relations-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relationFixtures")
    @DisplayName("every captured relations document parses into addressable relations")
    void everyFixtureParses(Path path) throws IOException {
        List<AlmRelation> relations = AlmRelationParser.parseRelations(Files.readString(path));

        assertThat(relations).as("%s should expose relations", path.getFileName()).isNotEmpty();
        assertThat(relations).allSatisfy(r -> {
            assertThat(r.name()).isNotBlank();
            assertThat(r.sourceEntity()).isNotBlank();
            assertThat(r.targetEntity()).isNotBlank();
            assertThat(r.type()).isNotBlank();
        });
        assertThat(relations).extracting(AlmRelation::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("⚠️ a relation may carry NO label — this corrects probe 21.6")
    void someRelationsAreUnlabelled() throws IOException {
        assumeThat(Files.exists(fixture("defect"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("defect")));

        // Probe 21.6 recorded "each carrying a human-readable Label" after reading `requirement`
        // alone. Probe 22 captured all three and found `defect` has five without one. If this ever
        // goes to zero the selector's first rule has lost its reason to exist, and someone should
        // find out why before deleting it.
        assertThat(relations).filteredOn(AlmRelation::unlabelled).isNotEmpty();
        assertThat(relations).filteredOn(AlmRelation::unlabelled)
                .as("unlabelled relations are field-backed references: release, cycle, environment")
                .allSatisfy(r -> assertThat(r.targetEntity())
                        .isIn("release", "release-cycle", "environment"));
    }

    @Test
    @DisplayName("the association entity is read from AssociationStorage, and is the collection to query")
    void associationEntityIsExtracted() throws IOException {
        assumeThat(Files.exists(fixture("requirement"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("requirement")));

        AlmRelation bpm = relations.stream()
                .filter(r -> "representativeRequirementToRequirementConnection".equals(r.name()))
                .findFirst().orElseThrow();

        // TargetEntity is `requirement` — the far end — but the rows live in the join entity, and
        // that is what a tab has to read. Conflating the two produces a tab that queries the wrong
        // collection and comes back plausibly empty.
        assertThat(bpm.targetEntity()).isEqualTo("requirement");
        assertThat(bpm.associationEntity()).isEqualTo("bpm-link");
        assertThat(bpm.readEntity()).isEqualTo("bpm-link");
    }

    @Test
    @DisplayName("a ReferenceStorage relation has no association entity and reads its target")
    void referenceStorageReadsTheTarget() throws IOException {
        assumeThat(Files.exists(fixture("requirement"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("requirement")));

        AlmRelation attachments = relations.stream()
                .filter(r -> AlmRelation.TYPE_ATTACHMENT.equals(r.type()))
                .findFirst().orElseThrow();

        assertThat(attachments.associationEntity()).isEmpty();
        assertThat(attachments.readEntity()).isEqualTo("attachment");
    }

    @Test
    @DisplayName("the _mirrored suffix is recognised, and both directions survive the parse")
    void mirroredRelationsAreMarkedNotDropped() throws IOException {
        assumeThat(Files.exists(fixture("test"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("test")));

        assertThat(relations).filteredOn(AlmRelation::mirrored).isNotEmpty();
        // For `test` the mirrored direction is the useful one — a test's covered requirements come
        // from requirementToTestConnection_mirrored — so mirrored must never be a drop signal.
        assertThat(relations)
                .filteredOn(r -> "requirementToTestConnection_mirrored".equals(r.name()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.mirrored()).isTrue();
                    assertThat(r.label()).isEqualTo("Test to covered Requirements");
                });
    }

    @Test
    @DisplayName("entity counts match probe 21.6 / 22")
    void countsMatchTheProbe() throws IOException {
        assumeThat(Files.exists(fixture("requirement"))).isTrue();
        assertThat(AlmRelationParser.parseRelations(Files.readString(fixture("requirement")))).hasSize(22);
        assertThat(AlmRelationParser.parseRelations(Files.readString(fixture("test")))).hasSize(27);
        assertThat(AlmRelationParser.parseRelations(Files.readString(fixture("defect")))).hasSize(17);
    }

    @Test
    @DisplayName("the storage descriptor yields the filter field, so every tab's query is derived")
    void filterFieldsComeFromTheStorageDescriptor() throws IOException {
        assumeThat(Files.exists(fixture("requirement"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("requirement")));

        // ReferenceStorage: filter the target collection by its own FK column.
        AlmRelation traces = byName(relations, "requirementToReqTraceLinkLeft");
        assertThat(traces.filterIdField()).isEqualTo("from-req-id");
        assertThat(traces.filterTypeField()).isEmpty();
        assertThat(traces.fillable()).isTrue();

        AlmRelation coverage = byName(relations, "requirementCoverageToRequirementLink");
        assertThat(coverage.filterIdField()).isEqualTo("requirement-id");

        // AssociationStorage: the SOURCE columns. Taking the target columns would filter by the far
        // end's id — a query that returns rows, just the wrong ones.
        AlmRelation defects = byName(relations, "requirementToDefectConnection");
        assertThat(defects.filterIdField()).isEqualTo("second-endpoint-id");
        assertThat(defects.filterTypeField()).isEqualTo("second-endpoint-type");
    }

    @Test
    @DisplayName("⚠️ probe 23: a polymorphic link carries a type discriminator, and it is the entity name")
    void polymorphicLinksCarryATypeDiscriminator() throws IOException {
        assumeThat(Files.exists(fixture("defect"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("defect")));

        // defect-links is ONE table serving seven entity types (probe 23 counted them on real data:
        // defect, requirement, test, run, run-step, test-set, test-instance). Every relation that
        // fans out through it must filter on a type as well as an id, or the tab mixes them.
        // Restricted to the fan-out relations: those that reach a far end THROUGH the join. The two
        // that read defect-link as itself (target == the join table) need no discriminator, since
        // the defect always occupies first-endpoint — an asymmetry worth not "fixing".
        assertThat(relations)
                .filteredOn(r -> "defect-link".equals(r.readEntity()) && !r.unlabelled())
                .filteredOn(r -> !"defect-link".equals(r.targetEntity()))
                .filteredOn(r -> !"defect".equals(r.targetEntity()))
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.discriminated()).isTrue());

        // ⚠️ From a DEFECT the discriminator is on the target endpoint, and names the target: the
        // defect is always first-endpoint so its own type needs no proof, but "Linked Runs" versus
        // "Linked Tests" is entirely the far end's type. Reading the source column here — the
        // obvious symmetry — would make every one of these tabs list all of the defect's links.
        AlmRelation linkedRuns = byName(relations, "runToDefectConnection_mirrored");
        assertThat(linkedRuns.filterIdField()).isEqualTo("first-endpoint-id");
        assertThat(linkedRuns.filterTypeField()).isEqualTo("second-endpoint-type");
        assertThat(linkedRuns.filterTypeValue()).isEqualTo("run");

        // …while from a REQUIREMENT the record sits at the polymorphic endpoint, so the same table's
        // discriminator names the SOURCE instead.
        List<AlmRelation> fromRequirement = AlmRelationParser.parseRelations(
                Files.readString(fixture("requirement")));
        AlmRelation linkedDefects = byName(fromRequirement, "requirementToDefectConnection");
        assertThat(linkedDefects.filterIdField()).isEqualTo("second-endpoint-id");
        assertThat(linkedDefects.filterTypeField()).isEqualTo("second-endpoint-type");
        assertThat(linkedDefects.filterTypeValue()).isEqualTo("requirement");
    }

    private static AlmRelation byName(List<AlmRelation> relations, String name) {
        return relations.stream().filter(r -> name.equals(r.name())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a wrong-shaped payload fails loudly rather than parsing to zero tabs")
    void wrongShapeThrows() {
        // The failure this guards against is specific: `relations` is PascalCase where the sibling
        // `fields` endpoint is lowerCamel. Reading "relation" would yield an empty list, and an
        // empty list renders as "this entity has no related records" — which is never true.
        assertThatThrownBy(() -> AlmRelationParser.parseRelations("{\"relation\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Relation");
        assertThatThrownBy(() -> AlmRelationParser.parseRelations(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("relation types stay a string, so an unseen ninth type cannot fail the parse")
    void typesAreOpenEnded() throws IOException {
        assumeThat(Files.exists(fixture("test"))).isTrue();
        List<AlmRelation> relations = AlmRelationParser.parseRelations(
                Files.readString(fixture("test")));

        Set<String> types = relations.stream().map(AlmRelation::type)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // Eight observed across the three captures. Recorded, not enforced — unlike field types,
        // where the closure over exactly 8 is a probe-verified invariant worth failing on.
        assertThat(types).isSubsetOf("link", "connection", "containment", "composition",
                "usage", "dependency", "attachment", "realization");
    }
}
