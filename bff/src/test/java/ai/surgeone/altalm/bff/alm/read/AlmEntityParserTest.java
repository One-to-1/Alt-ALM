package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link AlmEntityParser} against the redacted, hand-authored captures in
 * {@code tests/fixtures/entities/} — no server, no credentials. Follows the fixture-loading
 * convention in {@code AlmMetadataParserFixtureTest}.
 *
 * <p>The fixtures are synthetic ({@code ALTALM-SAMPLE-*} names, invented ids) — they encode the
 * envelope <em>shapes</em> probe 15 verified live, not literal probe captures, so field values here
 * carry no evidentiary weight beyond "this is a well-formed example of the shape".
 */
class AlmEntityParserTest {

    /** Repo root, resolved from the bff module directory. */
    private static final Path FIXTURES = Path.of("..", "tests", "fixtures", "entities");

    private static String fixture(String name) throws IOException {
        return Files.readString(FIXTURES.resolve(name));
    }

    @Test
    @DisplayName("a multi-row page parses every entity, in server order, with id/name readable via first()")
    void multiRowPageParses() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-multi-row.json"));

        assertThat(page.totalResults()).isEqualTo(2);
        assertThat(page.entities()).hasSize(2);

        AlmEntityPage.AlmEntity first = page.entities().get(0);
        assertThat(first.type()).isEqualTo("requirement");
        assertThat(first.id()).contains("1001");
        assertThat(first.first("name")).contains("ALTALM-SAMPLE-1");
        assertThat(first.childrenCount()).isEqualTo(2);
        assertThat(first.isError()).isFalse();

        AlmEntityPage.AlmEntity second = page.entities().get(1);
        assertThat(second.id()).contains("1002");
        assertThat(second.first("parent-id")).contains("1001");
        assertThat(second.childrenCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("an empty collection parses to an empty page, not an exception (fact 6)")
    void emptyCollectionParsesToEmptyPage() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-empty.json"));

        assertThat(page.entities()).isEmpty();
        assertThat(page.totalResults()).isZero();
    }

    @Test
    @DisplayName("a null value inside values[] is omitted, never turned into the literal string \"null\"")
    void nullValueIsOmittedNotStringified() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-null-value.json"));
        AlmEntityPage.AlmEntity entity = page.entities().get(0);

        // "owner":[{"value":null}] -> present in the map, but with an empty list: the null was
        // dropped rather than turned into the string "null".
        assertThat(entity.fields()).containsKey("owner");
        assertThat(entity.fields().get("owner")).isEmpty();
        assertThat(entity.first("owner")).isEmpty();
    }

    @Test
    @DisplayName("a field present with an empty values[] is distinct from a field never sent at all")
    void emptyValuesArrayIsDistinctFromAbsentField() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-null-value.json"));
        AlmEntityPage.AlmEntity entity = page.entities().get(0);

        // "user-01":[] was sent by the server -> present in the map, empty list.
        assertThat(entity.fields()).containsKey("user-01");
        assertThat(entity.fields().get("user-01")).isEmpty();

        // "description" was never sent at all -> absent from the map entirely.
        assertThat(entity.fields()).doesNotContainKey("description");

        // first() intentionally does not distinguish the two - both read as "no value".
        assertThat(entity.first("user-01")).isEmpty();
        assertThat(entity.first("description")).isEmpty();
    }

    @Test
    @DisplayName("a multivalue field parses every entry, in order, as a list")
    void multivalueFieldParsesAllEntries() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-multivalue.json"));
        AlmEntityPage.AlmEntity entity = page.entities().get(0);

        List<String> targetRel = entity.fields().get("target-rel");
        assertThat(targetRel).containsExactly("9001", "9002");

        // first() surfaces only the first entry - correct for single-valued fields, deliberately
        // lossy for a genuinely multivalue one; callers who need every value use fields() directly.
        assertThat(entity.first("target-rel")).contains("9001");
    }

    @Test
    @DisplayName("an entity with a non-Success EntityStatus is surfaced as an error, not returned as if healthy")
    void nonSuccessEntityStatusIsSurfacedAsError() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-entity-status-error.json"));
        AlmEntityPage.AlmEntity entity = page.entities().get(0);

        assertThat(entity.isError()).isTrue();
        assertThat(entity.entityStatus()).isEqualTo("Failure");
        assertThat(entity.errorMessage()).isEqualTo("insufficient permissions to read field 'severity'");
        // The row still parses its fields - isError() is a signal for the caller to check, not a
        // reason for the parser to drop the row's data.
        assertThat(entity.id()).contains("4001");
    }

    @Test
    @DisplayName("a healthy row's EntityStatus defaults to Success and isError() is false")
    void missingEntityStatusDefaultsToSuccess() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-multi-row.json"));
        AlmEntityPage.AlmEntity entity = page.entities().get(0);

        assertThat(entity.entityStatus()).isEqualTo("Success");
        assertThat(entity.isError()).isFalse();
    }

    @Test
    @DisplayName("TotalResults describes the page, not the collection - a page can carry more rows than TotalResults claims (fact 3)")
    void totalResultsDoesNotHaveToMatchPageRowCount() throws IOException {
        AlmEntityPage page = AlmEntityParser.parsePage(fixture("entity-page-total-results-mismatch.json"));

        // Two real rows parsed even though TotalResults says 0 - the parser must not "reconcile" or
        // reject this: probe 15 established the field reflects the page, not the collection, so a
        // mismatch is expected wire behaviour, not a malformed payload.
        assertThat(page.entities()).hasSize(2);
        assertThat(page.totalResults()).isZero();
    }

    @Test
    @DisplayName("a malformed payload fails loudly instead of looking like an empty page")
    void malformedPayloadThrows() {
        assertThat(catchType(() -> AlmEntityParser.parsePage(null)))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmEntityParser.parsePage("")))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmEntityParser.parsePage("{}")))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmEntityParser.parsePage("{\"entities\":\"not-an-array\"}")))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() ->
                AlmEntityParser.parsePage("{\"entities\":[{\"Type\":\"requirement\"}]}")))
                .as("an entity with no \"Fields\" array")
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmEntityParser.parsePage(
                "{\"entities\":[{\"Fields\":[{\"values\":[{\"value\":\"x\"}]}]}]}")))
                .as("a field with no \"Name\"")
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmEntityParser.parsePage(
                "{\"entities\":[{\"Fields\":[{\"Name\":\"id\",\"values\":\"not-an-array\"}]}]}")))
                .as("a field whose \"values\" is not an array")
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
