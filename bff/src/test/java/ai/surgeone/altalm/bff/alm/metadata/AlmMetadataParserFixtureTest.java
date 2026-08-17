package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * P0's fixture-based harness: exercises the metadata parse path against the redacted captures in
 * {@code tests/fixtures/}, with <strong>no server and no credentials</strong>.
 *
 * <p>These assert probe-established facts about the data model, so they fail if a future refactor
 * quietly changes the parse, and they document the model in executable form.
 */
class AlmMetadataParserFixtureTest {

    /** Repo root, resolved from the bff module directory. */
    private static final Path FIXTURES = Path.of("..", "tests", "fixtures");

    private static Stream<Path> customizationFixtures() throws IOException {
        if (!Files.isDirectory(FIXTURES)) {
            return Stream.empty();
        }
        try (var s = Files.list(FIXTURES)) {
            return s.filter(p -> p.getFileName().toString().startsWith("customization-fields-"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("customizationFixtures")
    @DisplayName("every captured entity parses, and uses only the 8 verified field types")
    void everyFixtureParses(Path fixture) throws IOException {
        List<FieldDescriptor> fields = AlmMetadataParser.parseFields(Files.readString(fixture));

        assertThat(fields).as("%s should expose fields", fixture.getFileName()).isNotEmpty();
        // parseFields throws on an unknown type, so reaching here already proves the 8-type
        // closure; this makes the intent explicit rather than implicit in a thrown exception.
        assertThat(fields).allSatisfy(f -> {
            assertThat(f.type()).isNotNull();
            assertThat(f.name()).isNotBlank();
            assertThat(f.physicalName()).isNotBlank();
        });
    }

    @Test
    @DisplayName("there is no Boolean type anywhere in the captured model")
    void noBooleanType() throws IOException {
        assertThat(AlmFieldType.values()).hasSize(8);
        assertThat(Stream.of(AlmFieldType.values()).map(AlmFieldType::wireName))
                .doesNotContain("Boolean", "Bool");
        assertThat(AlmFieldType.fromWireName("Boolean")).isEmpty();
    }

    @Test
    @DisplayName("requirement carries the rbt-* risk fields that make #18 client-side computable")
    void requirementHasRiskFields() throws IOException {
        Path p = FIXTURES.resolve("customization-fields-requirement.json");
        assumeThat(Files.exists(p)).isTrue();

        List<FieldDescriptor> fields = AlmMetadataParser.parseFields(Files.readString(p));
        List<String> rbt = fields.stream().map(FieldDescriptor::name)
                .filter(n -> n.startsWith("rbt-")).toList();

        // Probe research counted 27 rbt-* fields; assert the family exists rather than an exact
        // count, since per-project customization can legitimately vary it.
        assertThat(rbt).as("rbt-* risk fields back the Analyze reimplementation").isNotEmpty();
    }

    @Test
    @DisplayName("multivalue is rare - it is the exception, not a general case")
    void multivalueIsRare() throws IOException {
        long multivalue = 0;
        for (Path p : customizationFixtures().toList()) {
            multivalue += AlmMetadataParser.parseFields(Files.readString(p)).stream()
                    .filter(FieldDescriptor::supportsMultivalue).count();
        }
        // Probe mining found exactly 2 across the whole model (requirement.target-rel/-rcyc).
        assertThat(multivalue).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("memo fields declare unlimited size")
    void memoFieldsAreUnbounded() throws IOException {
        Path p = FIXTURES.resolve("customization-fields-defect.json");
        assumeThat(Files.exists(p)).isTrue();

        List<FieldDescriptor> memos = AlmMetadataParser.parseFields(Files.readString(p)).stream()
                .filter(f -> f.type() == AlmFieldType.MEMO).toList();

        assumeThat(memos).isNotEmpty();
        assertThat(memos).anyMatch(FieldDescriptor::isUnboundedMemo);
    }

    @Test
    @DisplayName("probe 21: the Details-form approximation selects a form-sized subset, not everything")
    void detailsFormFlagsAreParsedAndDiscriminating() throws IOException {
        Path requirement = FIXTURES.resolve("customization-fields-requirement.json");
        assumeThat(Files.exists(requirement)).isTrue();
        List<FieldDescriptor> fields = AlmMetadataParser.parseFields(Files.readString(requirement));

        long onForm = fields.stream().filter(FieldDescriptor::onDetailsForm).count();
        long risk = fields.stream().filter(FieldDescriptor::inRiskAnalysisGroup).count();

        // The whole point of these flags is that they DISCRIMINATE. If a future parser change drops
        // them, every field defaults to false and both counts collapse to zero — or, if someone
        // wires them to `visible` instead, onForm becomes "all of them". Probe 21 found `visible`
        // true for 74/74 fields, which is exactly the trap this asserts against.
        assertThat(fields).hasSizeGreaterThan(60);
        assertThat(onForm).as("fields on the Details form").isBetween(10L, 40L);
        assertThat(onForm).isLessThan(fields.size());
        assertThat(risk).as("the built-in Risk Analysis group").isEqualTo(25);

        // The memo fields that survive the filter are the ones the stock client actually tabs.
        assertThat(fields.stream()
                .filter(f -> f.type() == AlmFieldType.MEMO && f.onDetailsForm())
                .map(FieldDescriptor::label))
                .containsExactlyInAnyOrder("Comments", "Description", "Rich Text");
    }

    @Test
    @DisplayName("a malformed payload fails loudly instead of looking like an empty entity")
    void malformedPayloadThrows() {
        assertThat(catchType(() -> AlmMetadataParser.parseFields("{\"Fields\":{}}")))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmMetadataParser.parseFields("")))
                .isEqualTo(IllegalArgumentException.class);
    }

    private static Class<?> catchType(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Exception e) {
            return e.getClass();
        }
    }
}
