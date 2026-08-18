package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The audits parser, against the four payload forms probe 24 measured in a live project.
 *
 * <p>Every one of these is a real observed shape, not an imagined edge case. The single-object forms
 * in particular are the <em>common</em> case, and a parser that handles only the array form would
 * pass a naive test suite and fail on most real records.
 */
class AlmAuditParserTest {

    private static final Path FIXTURES = Path.of("..", "tests", "fixtures");

    private static String fixture(String name) throws IOException {
        Path path = FIXTURES.resolve(name);
        assumeThat(Files.exists(path)).isTrue();
        return Files.readString(path);
    }

    @Test
    @DisplayName("entries come back in server order with their per-field before/after")
    void parsesEntriesAndChanges() throws IOException {
        List<AlmAudit> audits = AlmAuditParser.parseAudits(fixture("audits-shape.json"));

        assertThat(audits).hasSize(3);
        AlmAudit first = audits.getFirst();
        assertThat(first.action()).isEqualTo("UPDATE");
        assertThat(first.user()).isEqualTo("fixture.user");
        // Sent as-is: the format carries no timezone offset (probe 24 measured
        // `yyyy-MM-dd HH:mm:ss` in all 678 entries), so converting it would be inventing a zone.
        assertThat(first.time()).isEqualTo("2026-01-02 09:15:00");
        assertThat(first.changes()).extracting(AlmAudit.Change::field)
                .containsExactly("status", "owner");
        assertThat(first.changes().getFirst().label()).isEqualTo("Direct Cover Status");
        assertThat(first.changes().getFirst().newValue()).isEqualTo("Passed");
    }

    @Test
    @DisplayName("⚠️ a lone Property arrives as an OBJECT — the majority case, not an edge case")
    void singlePropertyCollapsesToAnObject() throws IOException {
        List<AlmAudit> audits = AlmAuditParser.parseAudits(fixture("audits-shape.json"));

        // Probe 24: Property was an object 464 times against 129 arrays. Treating the object form as
        // "no changes" would blank most of the History tab while looking like it worked.
        assertThat(audits.get(1).changes()).singleElement()
                .satisfies(c -> assertThat(c.field()).isEqualTo("req-priority"));
    }

    @Test
    @DisplayName("⚠️ a lone Audit arrives as an OBJECT — a record edited exactly once")
    void singleAuditCollapsesToAnObject() throws IOException {
        List<AlmAudit> audits = AlmAuditParser.parseAudits(fixture("audits-shape-single.json"));

        assertThat(audits).singleElement()
                .satisfies(a -> assertThat(a.changes()).singleElement()
                        .satisfies(c -> assertThat(c.oldValue()).isEqualTo("New")));
    }

    @Test
    @DisplayName("an entry with no Properties is kept, empty — it still evidences a change")
    void entryWithoutPropertiesSurvives() throws IOException {
        List<AlmAudit> audits = AlmAuditParser.parseAudits(fixture("audits-shape.json"));

        // 85 of 678 entries had no Properties at all. Dropping them would under-report the history
        // of exactly the records whose history is already thinnest.
        assertThat(audits.get(2).changes()).isEmpty();
        assertThat(audits.get(2).id()).isEqualTo("9003");
    }

    @Test
    @DisplayName("a payload that is not an audits envelope fails loudly, not as 'never changed'")
    void wrongShapeThrows() {
        assertThatThrownBy(() -> AlmAuditParser.parseAudits("{\"entities\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Audits");

        assertThatThrownBy(() -> AlmAuditParser.parseAudits(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a record ALM recorded nothing for parses to an empty list, not an error")
    void emptyHistoryIsNotAnError() {
        assertThat(AlmAuditParser.parseAudits("{\"Audits\":{\"TotalResults\":0}}")).isEmpty();
    }
}
