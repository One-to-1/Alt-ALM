package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.write.AlmCommentWriter;
import ai.surgeone.altalm.bff.alm.write.AlmEntityBody;
import ai.surgeone.altalm.bff.alm.write.AlmVersionGuard;
import ai.surgeone.altalm.bff.alm.write.AlmWriteClient;
import ai.surgeone.altalm.bff.alm.write.AlmWriteResult;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CRUD service, and specifically the part {@code AlmWriteClient} refuses to do for it.
 *
 * <p>The write client returns {@code UNKNOWN} on a 5xx and declines to guess whether the row landed,
 * because it does not know what identifies the row. This class does. So most of what is worth testing
 * here is <strong>the finder</strong> it hands to {@code verify(...)} — what question it asks, and
 * when it correctly declines to ask one.
 *
 * <p>Those cases capture the supplier and invoke it directly rather than letting a stubbed
 * {@code verify} run it. {@code AlmWriteClientTest} owns {@code verify}'s own semantics; re-deciding
 * them in a stub here would test the stub.
 */
class RecordServiceTest {

    private static final AlmProjectRef SANDBOX = new AlmProjectRef("D", "SANDBOX");
    private static final AlmProjectRef NOT_ENROLLED = new AlmProjectRef("D", "SOMEONE-ELSE");

    private AlmWriteClient writes;
    private AlmEntityClient reads;
    private AlmMetadataCatalog metadata;
    private AlmCommentWriter comments;
    private RecordService service;

    @BeforeEach
    void setUp() {
        writes = mock(AlmWriteClient.class);
        reads = mock(AlmEntityClient.class);
        metadata = mock(AlmMetadataCatalog.class);
        comments = mock(AlmCommentWriter.class);

        when(metadata.fields(any(), eq("requirement"))).thenReturn(List.of(
                descriptor("id", AlmFieldType.NUMBER),
                descriptor("name", AlmFieldType.STRING),
                descriptor("description", AlmFieldType.MEMO),
                descriptor("priority", AlmFieldType.LOOKUP_LIST),
                descriptor("parent-id", AlmFieldType.REFERENCE),
                descriptor("type-id", AlmFieldType.NUMBER),
                descriptor("ver-stamp", AlmFieldType.NUMBER)));

        // A REAL validator over mocked metadata, not a mocked validator. The question "does this
        // endpoint actually validate?" is one of the things worth asserting, and a mock would
        // answer it by construction.
        AlmWriteValidator validator = new AlmWriteValidator(metadata);
        AlmAccessPolicy policy = new AlmAccessPolicy(SANDBOX, Set.of());

        service = new RecordService(writes, reads, metadata, validator, comments, policy);

        when(writes.create(any(), any(), any())).thenReturn(AlmWriteResult.committed("7001", false));
        when(writes.update(any(), any(), any(), any())).thenReturn(AlmWriteResult.committed("7001", false));
        when(writes.delete(any(), any(), any())).thenReturn(AlmWriteResult.committed("7001", false));
        // Returns the result untouched: these tests assert on the finder, not on verify's plumbing.
        when(writes.verify(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static FieldDescriptor descriptor(String name, AlmFieldType type) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                false, true, true, false, false, true, true, false, 0, 0);
    }

    /** A row as the read client returns it. */
    private void serverHas(Map<String, String> values) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        values.forEach((k, v) -> fields.put(k, List.of(v)));
        when(reads.page(any(), any(), any(AlmQuery.class))).thenReturn(new AlmEntityPage(
                List.of(new AlmEntityPage.AlmEntity("requirement", fields, 0, "Success", "")), 1));
    }

    private void serverHasNothing() {
        when(reads.page(any(), any(), any(AlmQuery.class)))
                .thenReturn(new AlmEntityPage(List.of(), 0));
    }

    private void serverHasTwoRowsNamed(String name) {
        List<AlmEntityPage.AlmEntity> rows = new ArrayList<>();
        for (String id : List.of("7001", "7002")) {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            fields.put("id", List.of(id));
            fields.put("name", List.of(name));
            rows.add(new AlmEntityPage.AlmEntity("requirement", fields, 0, "Success", ""));
        }
        when(reads.page(any(), any(), any(AlmQuery.class))).thenReturn(new AlmEntityPage(rows, 2));
    }

    /** The finder RecordService handed to verify(), so a test can ask it the question directly. */
    @SuppressWarnings("unchecked")
    private Supplier<Optional<String>> capturedFinder() {
        ArgumentCaptor<Supplier<Optional<String>>> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(writes).verify(any(), captor.capture());
        return captor.getValue();
    }

    private static Map<String, String> body(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // ==========================================================================================

    @Nested
    @DisplayName("the write reaches ALM in the shape the server requires")
    class Shape {

        @Test
        @DisplayName("a create goes through AlmWriteClient with the canonical field order")
        void createRoutesThroughTheWriteClient() {
            service.create(SANDBOX, "requirements", body(
                    "priority", "2", "type-id", "1", "name", "ALTALM-x", "parent-id", "5"));

            ArgumentCaptor<AlmEntityBody> sent = ArgumentCaptor.forClass(AlmEntityBody.class);
            verify(writes).create(eq(SANDBOX), eq("requirements"), sent.capture());

            // Field order is load-bearing on the wire (probe 4), and the caller's JSON object order
            // is not something to depend on - so the body must re-rank regardless of what came in.
            assertThat(sent.getValue().orderedFieldNames())
                    .containsExactly("name", "parent-id", "priority", "type-id");
        }

        @Test
        @DisplayName("a committed create reports its id and says so plainly")
        void committedCreate() {
            WriteDto.WriteResponse response =
                    service.create(SANDBOX, "requirements", body("name", "ALTALM-x"));

            assertThat(response.outcome()).isEqualTo("COMMITTED");
            assertThat(response.id()).isEqualTo("7001");
            assertThat(response.problems()).isEmpty();
        }

        @Test
        @DisplayName("a retried write is reported as such - it means metadata lied about a field")
        void retryIsSurfaced() {
            when(writes.create(any(), any(), any()))
                    .thenReturn(AlmWriteResult.committed("7001", true));

            WriteDto.WriteResponse response =
                    service.create(SANDBOX, "requirements", body("name", "ALTALM-x"));

            assertThat(response.retried()).isTrue();
            assertThat(response.detail()).contains("second attempt");
        }
    }

    @Nested
    @DisplayName("an UNKNOWN write - the finder, and when there is no honest question to ask")
    class Verification {

        @BeforeEach
        void serverErrored() {
            when(writes.create(any(), any(), any()))
                    .thenReturn(AlmWriteResult.unknown("qccore.general-error", "boom", false));
            when(writes.update(any(), any(), any(), any()))
                    .thenReturn(AlmWriteResult.unknown("qccore.general-error", "boom", false));
            when(writes.delete(any(), any(), any()))
                    .thenReturn(AlmWriteResult.unknown("qccore.general-error", "boom", false));
        }

        @Test
        @DisplayName("a create asks whether ONE row now carries the name it sent")
        void createFindsByName() {
            serverHas(Map.of("id", "7001", "name", "ALTALM-x"));

            service.create(SANDBOX, "requirements", body("name", "ALTALM-x"));

            assertThat(capturedFinder().get()).contains("7001");
        }

        @Test
        @DisplayName("two rows with that name resolve NOTHING - picking one would invent a fact")
        void ambiguousNameIsNotResolved() {
            serverHasTwoRowsNamed("ALTALM-x");

            service.create(SANDBOX, "requirements", body("name", "ALTALM-x"));

            // Names are not unique in ALM. Returning the first match would attach a stranger's id
            // to the caller's write, and the caller would stop looking.
            assertThat(capturedFinder().get()).isEmpty();
        }

        @Test
        @DisplayName("a create with no name does not even try - there is nothing to ask about")
        void namelessCreateSkipsVerification() {
            service.create(SANDBOX, "requirements", body("priority", "2"));

            verify(writes, never()).verify(any(), any());
        }

        @Test
        @DisplayName("a delete is verified by ABSENCE, which is the one unambiguous check here")
        void deleteVerifiesByAbsence() {
            serverHasNothing();

            service.delete(SANDBOX, "requirements", "7001");

            assertThat(capturedFinder().get()).contains("7001");
        }

        @Test
        @DisplayName("a delete whose row is still there is NOT verified")
        void deleteWithRowStillPresent() {
            serverHas(Map.of("id", "7001"));

            service.delete(SANDBOX, "requirements", "7001");

            assertThat(capturedFinder().get()).isEmpty();
        }

        @Test
        @DisplayName("an update is verified by the values it sent having landed")
        void updateVerifiesByValues() {
            serverHas(Map.of("id", "7001", "name", "renamed", "priority", "2"));

            service.update(SANDBOX, "requirements", "7001",
                    body("name", "renamed", "priority", "2"), Optional.empty());

            assertThat(capturedFinder().get()).contains("7001");
        }

        @Test
        @DisplayName("an update whose values did not land is NOT verified")
        void updateWithDifferentValues() {
            serverHas(Map.of("id", "7001", "name", "the old name"));

            service.update(SANDBOX, "requirements", "7001", body("name", "renamed"),
                    Optional.empty());

            assertThat(capturedFinder().get()).isEmpty();
        }

        @Test
        @DisplayName("an update of ONLY a memo cannot be verified, and says so by declining")
        void memoOnlyUpdateCannotBeVerified() {
            // ALM re-serialises a memo on the way in - wrapping fragments, canonicalising <br>,
            // applying output sanitisation (probe 27). A byte comparison would report every
            // successful memo write as unverified, so there is nothing comparable to check.
            serverHas(Map.of("id", "7001", "description", "<html><body>text</body></html>"));

            service.update(SANDBOX, "requirements", "7001",
                    body("description", "<p>text</p>"), Optional.empty());

            assertThat(capturedFinder().get()).isEmpty();
        }

        @Test
        @DisplayName("an unresolved UNKNOWN tells the caller not to retry blind")
        void unknownDetailWarnsAgainstRetry() {
            serverHasNothing();

            WriteDto.WriteResponse response =
                    service.create(SANDBOX, "requirements", body("name", "ALTALM-x"));

            assertThat(response.outcome()).isEqualTo("UNKNOWN");
            assertThat(response.verified()).isFalse();
            assertThat(response.detail()).contains("may still have taken effect");
        }
    }

    @Nested
    @DisplayName("concurrency: detected, and only detected")
    class Versioning {

        @Test
        @DisplayName("a ver-stamp that moved refuses the update, and nothing is sent")
        void staleVersionRefuses() {
            serverHas(Map.of("id", "7001", "ver-stamp", "9"));

            assertThatThrownBy(() -> service.update(SANDBOX, "requirements", "7001",
                    body("name", "renamed"), Optional.of("3")))
                    .isInstanceOf(AlmVersionGuard.ConflictException.class);

            verify(writes, never()).update(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a matching ver-stamp proceeds")
        void currentVersionProceeds() {
            serverHas(Map.of("id", "7001", "ver-stamp", "9"));

            service.update(SANDBOX, "requirements", "7001", body("name", "renamed"),
                    Optional.of("9"));

            verify(writes).update(eq(SANDBOX), eq("requirements"), eq("7001"), any());
        }

        @Test
        @DisplayName("no expected version skips the read entirely - it is an explicit choice")
        void absentVersionSkipsTheCheck() {
            service.update(SANDBOX, "requirements", "7001", body("name", "renamed"),
                    Optional.empty());

            verify(writes).update(eq(SANDBOX), eq("requirements"), eq("7001"), any());
            verify(reads, never()).page(any(), any(), any(AlmQuery.class));
        }
    }

    @Nested
    @DisplayName("what this API refuses to write, and why each refusal exists")
    class Refusals {

        @Test
        @DisplayName("a project nobody enrolled is refused as a WRITE, before metadata is touched")
        void unenrolledProjectIsRefused() {
            assertThatThrownBy(() -> service.create(NOT_ENROLLED, "requirements",
                    body("name", "ALTALM-x")))
                    .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class)
                    // Not "read denied". Validation reads metadata, so without an explicit write
                    // check first the operator is sent to look at the wrong setting.
                    .hasMessageContaining("WRITE DENIED");

            verify(metadata, never()).fields(any(), any());
            verify(writes, never()).create(any(), any(), any());
        }

        @Test
        @DisplayName("runs cannot be created - POST runs fails definitively, so no endpoint offers it")
        void runsAreNotWritable() {
            assertThatThrownBy(() -> service.create(SANDBOX, "runs", body("name", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("test-instance");
        }

        @Test
        @DisplayName("attachments are not a JSON entity, so this API does not pretend they are")
        void attachmentsAreNotWritable() {
            assertThatThrownBy(() -> service.create(SANDBOX, "attachments", body("name", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("multipart");
        }

        @Test
        @DisplayName("an unknown collection is refused rather than interpolated into an ALM URL")
        void unknownCollectionIsRefused() {
            assertThatThrownBy(() -> service.create(SANDBOX, "../../admin", body("name", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown collection");
        }

        @Test
        @DisplayName("an invalid body never leaves the BFF")
        void validationRunsBeforeTheWrite() {
            assertThatThrownBy(() -> service.create(SANDBOX, "requirements",
                    body("nmae", "typo")))
                    .isInstanceOf(AlmWriteValidator.RejectedException.class);

            verify(writes, never()).create(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("comments go to the merging path, never to the replacing one")
    class Comments {

        @Test
        @DisplayName("a comment routes to AlmCommentWriter and NOT to a plain update")
        void commentDoesNotUseUpdate() {
            when(comments.addComment(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(AlmWriteResult.committed("7001", false));

            service.comment(SANDBOX, "requirements", "7001", "Alice", "a note", Optional.empty());

            verify(comments).addComment(eq(SANDBOX), eq("requirements"), eq("requirement"),
                    eq("7001"), eq("Alice"), eq("a note"), eq(Optional.empty()));
            // The whole point: writes.update would REPLACE the memo and delete every earlier
            // comment, with a 200 and nothing to notice.
            verify(writes, never()).update(any(), any(), any(), any());
        }

        @Test
        @DisplayName("the comment field is asked for by entity, since the name differs per entity")
        void commentFieldIsDiscovered() {
            when(comments.commentFieldOf(SANDBOX, "defect")).thenReturn(Optional.of("dev-comments"));

            assertThat(service.commentField(SANDBOX, "defects")).contains("dev-comments");
        }
    }
}
