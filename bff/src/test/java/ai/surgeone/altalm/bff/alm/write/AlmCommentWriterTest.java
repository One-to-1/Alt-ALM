package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The comment path, whose whole reason to exist is that the obvious implementation deletes data.
 *
 * <p>The cases below are ordered by what they protect: first that history survives at all, then that
 * a concurrent edit is noticed, then the smaller correctness details.
 */
class AlmCommentWriterTest {

    private static final AlmProjectRef PROJECT = new AlmProjectRef("D", "SANDBOX");

    private AlmEntityClient reads;
    private AlmWriteClient writes;
    private AlmMetadataCatalog metadata;
    private AlmCommentWriter writer;

    @BeforeEach
    void setUp() {
        reads = mock(AlmEntityClient.class);
        writes = mock(AlmWriteClient.class);
        metadata = mock(AlmMetadataCatalog.class);
        writer = new AlmCommentWriter(reads, writes, metadata);

        when(metadata.fields(any(), eq("requirement"))).thenReturn(List.of(
                descriptor("id", AlmFieldType.NUMBER),
                descriptor("comments", AlmFieldType.MEMO),
                descriptor("ver-stamp", AlmFieldType.NUMBER)));
        when(writes.update(any(), any(), any(), any()))
                .thenReturn(AlmWriteResult.committed("7", false));
    }

    private static FieldDescriptor descriptor(String name, AlmFieldType type) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                false, true, true, false, false, true, true, false, 0, 0);
    }

    /** A row as the read client returns it. */
    private void rowHas(String comments, String verStamp) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("id", List.of("7"));
        fields.put("ver-stamp", List.of(verStamp));
        fields.put("comments", comments == null ? List.of() : List.of(comments));
        AlmEntityPage page = new AlmEntityPage(
                List.of(new AlmEntityPage.AlmEntity("requirement", fields, 0, "Success", "")), 1);
        when(reads.page(any(), eq("requirements"), any(AlmQuery.class))).thenReturn(page);
    }

    /** The value actually sent to ALM. */
    private String written() {
        var captor = org.mockito.ArgumentCaptor.forClass(AlmEntityBody.class);
        verify(writes).update(any(), eq("requirements"), eq("7"), captor.capture());
        String json = captor.getValue().toJson();
        // The body is the wire shape; pulling the memo back out of it is the point of the assertion.
        int start = json.indexOf("\"comments\"");
        return json.substring(start);
    }

    // ==========================================================================================

    @Nested
    @DisplayName("history survives — the bug this class exists to prevent")
    class HistoryIsPreserved {

        @Test
        @DisplayName("an existing comment is still there after a new one is added")
        void existingCommentSurvives() {
            rowHas("<html><body>\nEARLIER comment by someone else.\n</body></html>", "3");

            writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "My new comment.", Optional.of("3"));

            String sent = written();
            // If this ever fails, the comment box has become a comment DELETER - which is exactly
            // what a naive implementation does, silently, with a 200 response.
            assertThat(sent).contains("EARLIER comment by someone else.");
            assertThat(sent).contains("My new comment.");
        }

        @Test
        @DisplayName("the new comment goes INSIDE the document, not after its closing tags")
        void appendsInsideTheBody() {
            rowHas("<html><body>\nfirst\n</body></html>", "1");

            writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "second", Optional.empty());

            String sent = written();
            // Text after </body></html> would be a shape nothing has tested, and ALM re-parses the
            // value on write - so a second document concatenated onto the first is a good way to
            // find out what its parser does with one.
            assertThat(sent.indexOf("second")).isLessThan(sent.indexOf("</body>"));
        }

        @Test
        @DisplayName("an empty field starts a document rather than appending to nothing")
        void emptyFieldIsFine() {
            rowHas("", "1");

            writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "the first ever comment", Optional.empty());

            assertThat(written()).contains("the first ever comment");
        }

        @Test
        @DisplayName("an empty comment is refused - it would rewrite the field for nothing")
        void emptyCommentIsRefused() {
            rowHas("<html><body>x</body></html>", "1");

            assertThatThrownBy(() -> writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "   ", Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);

            // And nothing was sent: a no-op write still replaces the field, and a replace is
            // exactly the operation worth not performing by accident.
            verify(writes, never()).update(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("concurrent edits are detected, not prevented")
    class ConflictDetection {

        @Test
        @DisplayName("a ver-stamp that moved since the caller read it raises a conflict")
        void staleVersionIsRefused() {
            rowHas("<html><body>someone else already wrote here</body></html>", "9");

            assertThatThrownBy(() -> writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "mine", Optional.of("3")))
                    .isInstanceOf(AlmVersionGuard.ConflictException.class)
                    .hasMessageContaining("ver-stamp 3")
                    .hasMessageContaining("found 9");

            verify(writes, never()).update(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a matching ver-stamp proceeds")
        void currentVersionProceeds() {
            rowHas("<html><body>x</body></html>", "9");

            writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "mine", Optional.of("9"));

            verify(writes).update(any(), eq("requirements"), eq("7"), any());
        }

        @Test
        @DisplayName("no expected version means 'I accept overwriting', and is allowed explicitly")
        void absentVersionSkipsTheCheck() {
            // Spelled as an Optional at the call site rather than a nullable string so the choice
            // is visible to a reader. It is a decision, not a default.
            rowHas("<html><body>x</body></html>", "9");

            writer.addComment(PROJECT, "requirements", "requirement", "7",
                    "Alice", "mine", Optional.empty());

            verify(writes).update(any(), eq("requirements"), eq("7"), any());
        }
    }

    @Nested
    @DisplayName("the field name is discovered, never assumed")
    class FieldDiscovery {

        @Test
        @DisplayName("a defect's comment field is dev-comments, not comments")
        void defectUsesDevComments() {
            // Probe 30: the name differs per entity AND does not track the physical name. A constant
            // that is right for a requirement is wrong for a defect.
            when(metadata.fields(any(), eq("defect"))).thenReturn(List.of(
                    descriptor("id", AlmFieldType.NUMBER),
                    descriptor("dev-comments", AlmFieldType.MEMO)));

            assertThat(writer.commentFieldOf(PROJECT, "defect")).contains("dev-comments");
            assertThat(writer.commentFieldOf(PROJECT, "requirement")).contains("comments");
        }

        @Test
        @DisplayName("an entity with no comment field answers empty rather than inventing one")
        void noCommentFieldIsAnAnswer() {
            when(metadata.fields(any(), eq("run-step"))).thenReturn(List.of(
                    descriptor("id", AlmFieldType.NUMBER)));

            assertThat(writer.commentFieldOf(PROJECT, "run-step")).isEmpty();
            assertThatThrownBy(() -> writer.addComment(PROJECT, "run-steps", "run-step", "7",
                    "Alice", "x", Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no comment field");
        }
    }

    @Nested
    @DisplayName("the banner, whose format is a reconstruction")
    class Banner {

        @Test
        @DisplayName("carries author and date, and separates one comment from the next")
        void bannerShape() {
            String banner = AlmCommentBanner.banner("Alice", LocalDate.of(2026, 8, 18));

            assertThat(banner).contains("Alice").contains("18/08/2026").contains("____");
        }

        @Test
        @DisplayName("a hostile author name cannot open a tag and swallow the comment")
        void authorIsEscaped() {
            String banner = AlmCommentBanner.banner("<script>x</script>", LocalDate.now());

            assertThat(banner).doesNotContain("<script>");
            assertThat(banner).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("comment text is escaped, so a stray < does not eat the rest of it")
        void commentTextIsEscaped() {
            String out = AlmCommentBanner.append("", "Alice", "a < b and c > d", LocalDate.now());

            assertThat(out).contains("a &lt; b and c &gt; d");
        }

        @Test
        @DisplayName("newlines become <br> - ALM collapses them to spaces otherwise (probe 27)")
        void newlinesSurvive() {
            // The trap probe 27 named: a plain-text write path silently flattens paragraphs, and
            // nobody notices until someone reads the record back.
            String out = AlmCommentBanner.append("", "Alice", "line one\nline two", LocalDate.now());

            assertThat(out).contains("line one<br>line two");
        }
    }
}
