package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-based harness for {@link AlmGroupParser}: exercises the group-by parse path against the
 * redacted captures in {@code tests/fixtures/grids/}, with <strong>no server and no
 * credentials</strong>, plus hand-built cases for shapes no single fixture covers (multi-level
 * nesting, malformed input).
 */
class AlmGroupParserTest {

    /** Repo root, resolved from the bff module directory — same convention as AlmMetadataParserFixtureTest. */
    private static final Path FIXTURES = Path.of("..", "tests", "fixtures", "grids");

    @Test
    @DisplayName("real type-id fixture: one group, size 1, ReferenceValue resolved to 'Folder'")
    void typeIdFixtureParses() throws IOException {
        String json = Files.readString(FIXTURES.resolve("groups-requirements-type-id-plain.json"));

        List<AlmGroup> groups = AlmGroupParser.parseGroups(json);

        assertThat(groups).hasSize(1);
        AlmGroup group = groups.get(0);
        assertThat(group.field()).isEqualTo("type-id");
        assertThat(group.value()).isEqualTo("1");
        assertThat(group.displayValue()).isEqualTo("Folder");
        assertThat(group.expression()).isEqualTo("1");
        assertThat(group.size()).isEqualTo(1);
        assertThat(group.subGroups()).isEmpty();
        assertThat(group.isLeaf()).isTrue();
        assertThat(group.displayOrValue()).isEqualTo("Folder");
    }

    @Test
    @DisplayName("real status fixture: ReferenceValue is null, not the string \"null\"")
    void statusFixtureHasNullReferenceValue() throws IOException {
        String json = Files.readString(FIXTURES.resolve("groups-requirements-status-plain.json"));

        List<AlmGroup> groups = AlmGroupParser.parseGroups(json);

        assertThat(groups).hasSize(1);
        AlmGroup group = groups.get(0);
        assertThat(group.field()).isEqualTo("status");
        assertThat(group.value()).isEqualTo("N/A");
        assertThat(group.displayValue()).isNull();
        assertThat(group.displayValueOptional()).isEmpty();
        // Falls back to Value when ReferenceValue is null.
        assertThat(group.displayOrValue()).isEqualTo("N/A");
        assertThat(group.expression()).isEqualTo("N/A");
        assertThat(group.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty {\"subLevel\":[]} result is a legitimate empty group list, not an error")
    void emptySubLevelIsLegitimate() {
        List<AlmGroup> groups = AlmGroupParser.parseGroups("{\"subLevel\":[]}");

        assertThat(groups).isEmpty();
    }

    @Test
    @DisplayName("two-level nesting parses recursively (hand-built - no fixture nests this deep)")
    void twoLevelNestingParsesRecursively() {
        String json = """
                {"subLevel":[
                  {"Name":"type-id","Value":"1","ReferenceValue":"Folder","Expression":"1","size":3,
                   "subLevel":[
                     {"Name":"status","Value":"New","ReferenceValue":null,"Expression":"New","size":2,
                      "subLevel":[]},
                     {"Name":"status","Value":"Reviewed","ReferenceValue":null,"Expression":"Reviewed",
                      "size":1,"subLevel":[]}
                   ]}
                ]}
                """;

        List<AlmGroup> groups = AlmGroupParser.parseGroups(json);

        assertThat(groups).hasSize(1);
        AlmGroup top = groups.get(0);
        assertThat(top.field()).isEqualTo("type-id");
        assertThat(top.size()).isEqualTo(3);
        assertThat(top.isLeaf()).isFalse();
        assertThat(top.subGroups()).hasSize(2);

        AlmGroup firstChild = top.subGroups().get(0);
        assertThat(firstChild.field()).isEqualTo("status");
        assertThat(firstChild.value()).isEqualTo("New");
        assertThat(firstChild.size()).isEqualTo(2);
        assertThat(firstChild.isLeaf()).isTrue();

        AlmGroup secondChild = top.subGroups().get(1);
        assertThat(secondChild.value()).isEqualTo("Reviewed");
        assertThat(secondChild.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("malformed input throws instead of returning an empty list")
    void malformedPayloadThrows() {
        assertThat(catchType(() -> AlmGroupParser.parseGroups(null)))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmGroupParser.parseGroups("")))
                .isEqualTo(IllegalArgumentException.class);
        assertThat(catchType(() -> AlmGroupParser.parseGroups("   ")))
                .isEqualTo(IllegalArgumentException.class);
        // No "subLevel" key at all.
        assertThat(catchType(() -> AlmGroupParser.parseGroups("{\"foo\":\"bar\"}")))
                .isEqualTo(IllegalArgumentException.class);
        // "subLevel" present but not an array.
        assertThat(catchType(() -> AlmGroupParser.parseGroups("{\"subLevel\":{}}")))
                .isEqualTo(IllegalArgumentException.class);
        // A group missing "Name".
        assertThat(catchType(() -> AlmGroupParser.parseGroups(
                "{\"subLevel\":[{\"Value\":\"1\",\"Expression\":\"1\",\"size\":1,\"subLevel\":[]}]}")))
                .isEqualTo(IllegalArgumentException.class);
        // A group whose nested "subLevel" is not an array.
        assertThat(catchType(() -> AlmGroupParser.parseGroups(
                "{\"subLevel\":[{\"Name\":\"type-id\",\"Value\":\"1\",\"Expression\":\"1\","
                        + "\"size\":1,\"subLevel\":\"oops\"}]}")))
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
