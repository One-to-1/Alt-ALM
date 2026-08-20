package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lookup-list parser, against the real captured payload.
 *
 * <p>Run against the fixture rather than hand-written JSON wherever possible: the shape's mixed
 * casing — PascalCase on the list, lowerCamel on the items <em>inside</em> it — is precisely what a
 * hand-written sample would quietly normalise, and then the parser would pass its tests and fail on
 * the server.
 */
class AlmListParserTest {

    private static final Path FIXTURE =
            Path.of("..", "tests", "fixtures", "customization-used-lists.json");

    private static Map<Integer, AlmList> parseFixture() throws IOException {
        return AlmListParser.parse(Files.readString(FIXTURE));
    }

    @Test
    @DisplayName("parses every list in the captured payload, items and all")
    void parsesTheFixture() throws IOException {
        Map<Integer, AlmList> lists = parseFixture();

        // 39 bound lists out of 43 defined — the four unbound ones are not in `used-lists` at all.
        assertThat(lists).hasSize(39);
        assertThat(lists.values().stream().mapToInt(l -> l.items().size()).sum()).isEqualTo(125);
    }

    @Test
    @DisplayName("reads a list's items in server order, with both value and logical name")
    void readsItems() throws IOException {
        AlmList os = parseFixture().get(183);

        assertThat(os.name()).isEqualTo("Operating System (Environment)");
        assertThat(os.values()).containsExactly("Apple Mac OS", "Linux", "Microsoft Windows");
        // The logical name survives a value being renamed; the value does not. Both are kept.
        assertThat(os.items().getFirst().logicalName()).isEqualTo("hp.qc.operating-system-mac-os");
    }

    @Test
    @DisplayName("an EMPTY list is a real answer, not a failed parse")
    void emptyListsSurvive() throws IOException {
        Map<Integer, AlmList> lists = parseFixture();

        // Three of the 39 genuinely have no items. A parser that dropped them would make a field
        // bound to one look unbound, and the UI would offer free text where nothing is permitted.
        assertThat(lists.values().stream().filter(l -> l.items().isEmpty())).hasSize(3);
    }

    @Test
    @DisplayName("membership is an exact match, because ALM stores the literal value")
    void membershipIsExact() throws IOException {
        AlmList os = parseFixture().get(183);

        assertThat(os.permits("Linux")).isTrue();
        assertThat(os.permits("linux")).isFalse();
        assertThat(os.permits("Linux ")).isFalse();
        assertThat(os.permits("Solaris")).isFalse();
    }

    @Test
    @DisplayName("a list with no usable id is dropped rather than kept under a key nothing matches")
    void unusableIdsAreDropped() {
        Map<Integer, AlmList> lists = AlmListParser.parse("""
                {"lists":[{"Name":"No id","Items":[{"value":"x"}]},
                          {"Name":"Fine","Id":7,"Items":[{"value":"y","logicalName":"l"}]}]}
                """);

        assertThat(lists).containsOnlyKeys(7);
    }

    @Test
    @DisplayName("a valueless item is dropped — it is not a choice anyone could pick")
    void valuelessItemsAreDropped() {
        Map<Integer, AlmList> lists = AlmListParser.parse("""
                {"lists":[{"Name":"L","Id":9,"Items":[{"value":""},{"value":"real"}]}]}
                """);

        // Offering a blank entry in a dropdown that ALM would then reject helps nobody.
        assertThat(lists.get(9).values()).containsExactly("real");
    }

    @Test
    @DisplayName("a wrong shape fails loudly rather than reading as 'no lists in this project'")
    void wrongShapeThrows() {
        assertThatThrownBy(() -> AlmListParser.parse("{\"Lists\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlmListParser.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
