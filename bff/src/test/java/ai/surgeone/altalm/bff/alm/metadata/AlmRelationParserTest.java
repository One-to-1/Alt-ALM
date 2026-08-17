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
