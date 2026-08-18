package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The write path's behaviour, against a mocked ALM.
 *
 * <p>Every case here corresponds to something a probe actually saw. The reason these are worth
 * mocking rather than leaving to the contract tests: the interesting responses are the ones a live
 * sandbox will not reliably produce on demand — a 500 that committed the row, a create rejected for
 * a column metadata says is optional. Those are exactly the paths that must not be first exercised
 * in production.
 */
class AlmWriteClientTest {

    private static final AlmCredentials CREDENTIALS = new AlmCredentials(
            "https://alm.example.invalid/qcbin", "key", "secret", "DOMAIN", "SANDBOX");
    private static final AlmProjectRef SANDBOX = new AlmProjectRef("DOMAIN", "SANDBOX");
    private static final AlmProjectRef SOMEONE_ELSES = new AlmProjectRef("DOMAIN", "PROJECT-5");

    private static final String REQUIREMENTS =
            "https://alm.example.invalid/qcbin/rest/domains/DOMAIN/projects/SANDBOX/requirements";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AlmSessionPool pool;
    private AtomicInteger borrows;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        borrows = new AtomicInteger();
        pool = new AlmSessionPool(2, Duration.ofMinutes(60),
                () -> {
                    borrows.incrementAndGet();
                    return new AlmSession(Map.of("QCSession", "s1"), "xsrf-token-123", Instant.now());
                },
                session -> { /* eviction only; a released session goes back to the idle queue */ });
    }

    private AlmWriteClient client() {
        return client(AlmFieldResolver.none());
    }

    private AlmWriteClient client(AlmFieldResolver resolver) {
        return new AlmWriteClient(builder.build(), CREDENTIALS, pool,
                AlmAccessPolicy.sandboxOnly(CREDENTIALS), resolver, Duration.ofSeconds(5));
    }

    /** ALM's single-entity response: one entity, not the {@code entities[]} envelope reads use. */
    private static String createdEntity(String id) {
        return "{\"Fields\":[{\"Name\":\"name\",\"values\":[{\"value\":\"ALTALM-x\"}]},"
                + "{\"Name\":\"id\",\"values\":[{\"value\":\"" + id + "\"}]}],"
                + "\"Type\":\"requirement\",\"EntityStatus\":\"Success\",\"ErrorMessage\":\"\"}";
    }

    private static String qcError(String id, String title) {
        return "{\"Id\":\"" + id + "\",\"Title\":\"" + title + "\",\"ExceptionProperties\":[]}";
    }

    // ==========================================================================================

    @Nested
    @DisplayName("the sandbox rule is enforced before any I/O")
    class AccessControl {

        @Test
        @DisplayName("a write to another team's project throws, and never reaches the network")
        void writeToForeignProjectIsDenied() {
            // No server expectation is set. If the policy check happened after the request were
            // built, MockRestServiceServer would fail on an unexpected call - so this asserts both
            // that it is denied AND that nothing was sent.
            assertThatThrownBy(() -> client().create(SOMEONE_ELSES, "requirements",
                    AlmEntityBody.of("requirement").set("name", "x")))
                    .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class)
                    .hasMessageContaining("WRITE DENIED");

            server.verify();
            assertThat(borrows).hasValue(0);
        }

        @Test
        @DisplayName("the denial names the pseudonym, never the real project")
        void denialDoesNotDiscloseTheProject() {
            assertThatThrownBy(() -> client().delete(SOMEONE_ELSES, "requirements", "1"))
                    .hasMessageNotContaining("PROJECT-5".toLowerCase())
                    // pseudonym() is what the message may carry; the raw project name must not
                    // travel into a log or a bug report attached to a stack trace.
                    .hasMessageContaining(SOMEONE_ELSES.pseudonym());
        }

        @Test
        @DisplayName("delete is a write too - it is not exempt for being 'just' a removal")
        void deleteIsAWrite() {
            assertThatThrownBy(() -> client().delete(SOMEONE_ELSES, "requirements", "7"))
                    .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("the request ALM actually requires")
    class RequestShape {

        @Test
        @DisplayName("XSRF goes on every write - without it ALM answers 401 (probe 13)")
        void sendsXsrfHeader() {
            server.expect(requestTo(REQUIREMENTS))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-XSRF-TOKEN", "xsrf-token-123"))
                    .andExpect(header("Cookie", "QCSession=s1"))
                    .andRespond(withSuccess(createdEntity("42"), MediaType.APPLICATION_JSON));

            client().create(SANDBOX, "requirements", AlmEntityBody.of("requirement").set("name", "x"));
            server.verify();
        }

        @Test
        @DisplayName("the body goes out in canonical field order, not insertion order (probe 4)")
        void bodyIsDeterministicallyOrdered() {
            server.expect(requestTo(REQUIREMENTS))
                    .andExpect(content().string(
                            "{\"Fields\":["
                                    + "{\"Name\":\"name\",\"values\":[{\"value\":\"n\"}]},"
                                    + "{\"Name\":\"parent-id\",\"values\":[{\"value\":\"3\"}]},"
                                    + "{\"Name\":\"type-id\",\"values\":[{\"value\":\"1\"}]}"
                                    + "],\"Type\":\"requirement\"}"))
                    .andRespond(withSuccess(createdEntity("42"), MediaType.APPLICATION_JSON));

            // Set in deliberately the wrong order: the client must not preserve it.
            client().create(SANDBOX, "requirements", AlmEntityBody.of("requirement")
                    .set("type-id", "1").set("parent-id", "3").set("name", "n"));
            server.verify();
        }

        @Test
        @DisplayName("a session is always returned to the pool, including on a rejected write")
        void sessionIsReleasedOnFailure() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.required-field-missing", "The field 'Name' is required.")));
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON).body(createdEntity("2")));

            AlmWriteClient client = client();
            client.create(SANDBOX, "requirements", AlmEntityBody.of("requirement").set("x", "y"));
            client.create(SANDBOX, "requirements", AlmEntityBody.of("requirement").set("name", "n"));

            // Asserted through the FACTORY rather than a release counter: the pool's closer only
            // runs on eviction, so counting it would have measured nothing. Two writes that opened
            // one session is the observable proof the first one went back - and a leak here is not
            // cosmetic, it exhausts a bounded pool and hangs the next write on borrow().
            assertThat(borrows).hasValue(1);
        }

        @Test
        @DisplayName("id -1 is the tree root SENTINEL, not a row - rejected before the request")
        void refusesTheRootSentinel() {
            // Probe 27: PUT/POST against -1 returns 500 "Entity with key '-1' does not exist in
            // table 'REQ'", which reads like a server fault rather than the caller's mistake it is.
            assertThatThrownBy(() -> client().update(SANDBOX, "requirements", "-1",
                    AlmEntityBody.of("requirement").set("name", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("root sentinel");

            server.verify();
        }
    }

    @Nested
    @DisplayName("outcome classification - the 5xx rule is the whole point")
    class Outcomes {

        @Test
        @DisplayName("2xx is COMMITTED and carries the new id")
        void committed() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON).body(createdEntity("101")));

            AlmWriteResult result = client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("name", "x"));

            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);
            assertThat(result.id()).contains("101");
            assertThat(result.needsVerification()).isFalse();
        }

        @Test
        @DisplayName("4xx is REJECTED and carries ALM's error id and title")
        void rejected() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.required-field-missing", "The field 'Name' is required.")));

            AlmWriteResult result = client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("description", "x"));

            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.REJECTED);
            assertThat(result.errorId()).isEqualTo("qccore.required-field-missing");
            assertThat(result.errorTitle()).contains("required");
            assertThat(result.needsVerification()).isFalse();
        }

        @Test
        @DisplayName("⚠️ 5xx is UNKNOWN, never REJECTED - the row may exist (probe 4)")
        void serverErrorIsUnknownNotFailed() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error", "Invalid parent requirement")));

            AlmWriteResult result = client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("name", "x"));

            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.UNKNOWN);
            // The distinction this class exists for: a caller must not read this as "did not write".
            assertThat(result.outcome()).isNotEqualTo(AlmWriteOutcome.REJECTED);
            assertThat(result.needsVerification()).isTrue();
        }

        @Test
        @DisplayName("a 5xx is NOT retried by itself - re-sending is how duplicates get made")
        void serverErrorIsNotRetriedBlind() {
            // Exactly one expectation. A blind retry would fail the verify() with an unexpected
            // second request, which is the assertion.
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error", "something opaque")));

            client().create(SANDBOX, "requirements", AlmEntityBody.of("requirement").set("name", "x"));
            server.verify();
        }

        @Test
        @DisplayName("a 2xx whose body cannot be parsed is still COMMITTED, just without an id")
        void unparseableSuccessBodyDoesNotBecomeAFailure() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withSuccess("<html>not json</html>", MediaType.TEXT_HTML));

            AlmWriteResult result = client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("name", "x"));

            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);
            assertThat(result.id()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the missing-required-field retry (probe 9) is narrow on purpose")
    class MissingFieldRetry {

        /** Metadata reports ref-count editable:false, required:false. Both are wrong on create. */
        private final AlmFieldResolver refCount = (entity, physical) ->
                "TP_REF_COUNT".equals(physical)
                        ? Optional.of(new AlmFieldResolver.Resolved("ref-count", "0"))
                        : Optional.empty();

        private static final String PARAMS =
                "https://alm.example.invalid/qcbin/rest/domains/DOMAIN/projects/SANDBOX/test-parameters";

        @Test
        @DisplayName("retries ONCE with the named field added, and reports that it did")
        void retriesOnceWithTheField() {
            server.expect(requestTo(PARAMS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error",
                                    "failed converting entity test-parameter to FREC, "
                                            + "request is missing required field TP_REF_COUNT")));
            server.expect(requestTo(PARAMS))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("ref-count")))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON).body(createdEntity("55")));

            AlmWriteResult result = client(refCount).create(SANDBOX, "test-parameters",
                    AlmEntityBody.of("test-parameter").set("name", "p1"));

            server.verify();
            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);
            assertThat(result.id()).contains("55");
            // The signal that this project's metadata is lying about a field - worth surfacing,
            // not worth hiding behind a successful result.
            assertThat(result.retried()).isTrue();
        }

        @Test
        @DisplayName("the retry does not disturb the field order that made the first attempt legal")
        void retryKeepsCanonicalOrder() {
            server.expect(requestTo(PARAMS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error",
                                    "request is missing required field TP_REF_COUNT")));
            server.expect(requestTo(PARAMS))
                    .andExpect(content().string(
                            "{\"Fields\":["
                                    + "{\"Name\":\"name\",\"values\":[{\"value\":\"p1\"}]},"
                                    + "{\"Name\":\"ref-count\",\"values\":[{\"value\":\"0\"}]}"
                                    + "],\"Type\":\"test-parameter\"}"))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON).body(createdEntity("55")));

            client(refCount).create(SANDBOX, "test-parameters",
                    AlmEntityBody.of("test-parameter").set("name", "p1"));
            server.verify();
        }

        @Test
        @DisplayName("with no resolver, the retry is disabled rather than guessing a field name")
        void withoutMetadataItDoesNotGuess() {
            server.expect(requestTo(PARAMS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error",
                                    "request is missing required field TP_REF_COUNT")));

            AlmWriteResult result = client().create(SANDBOX, "test-parameters",
                    AlmEntityBody.of("test-parameter").set("name", "p1"));

            // One request only: a retry that invents a logical name would turn one clear failure
            // into an unrelated one.
            server.verify();
            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.UNKNOWN);
            assertThat(result.retried()).isFalse();
        }

        @Test
        @DisplayName("a 5xx that is NOT the missing-field shape is not retried")
        void onlyTheMissingFieldShapeQualifies() {
            server.expect(requestTo(PARAMS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error", "Invalid parent requirement")));

            AlmWriteResult result = client(refCount).create(SANDBOX, "test-parameters",
                    AlmEntityBody.of("test-parameter").set("name", "p1"));

            server.verify();
            assertThat(result.retried()).isFalse();
        }

        @Test
        @DisplayName("a 4xx naming a missing field is NOT retried - only 5xx qualifies")
        void clientErrorIsNotRetried() {
            server.expect(requestTo(PARAMS))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.required-field-missing",
                                    "request is missing required field TP_REF_COUNT")));

            AlmWriteResult result = client(refCount).create(SANDBOX, "test-parameters",
                    AlmEntityBody.of("test-parameter").set("name", "p1"));

            server.verify();
            assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.REJECTED);
            assertThat(result.retried()).isFalse();
        }
    }

    @Nested
    @DisplayName("verifying an UNKNOWN write")
    class Verification {

        private AlmWriteResult unknownWrite() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(qcError("qccore.general-error", "opaque")));
            return client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("name", "ALTALM-x"));
        }

        @Test
        @DisplayName("a found row fills in verifiedId - and the outcome STAYS unknown")
        void foundRowDoesNotBecomeASuccess() {
            AlmWriteResult verified =
                    client().verify(unknownWrite(), () -> Optional.of("77"));

            assertThat(verified.verifiedId()).contains("77");
            assertThat(verified.effectiveId()).contains("77");
            assertThat(verified.needsVerification()).isFalse();
            // The claim "the row exists" is not the claim "the write succeeded", and only the first
            // one has evidence. Anything deciding whether to re-write reads verifiedId, not this.
            assertThat(verified.outcome()).isEqualTo(AlmWriteOutcome.UNKNOWN);
        }

        @Test
        @DisplayName("a row that is not there leaves the result needing verification")
        void absentRowStaysUnresolved() {
            AlmWriteResult verified = client().verify(unknownWrite(), Optional::empty);

            assertThat(verified.verifiedId()).isEmpty();
            assertThat(verified.needsVerification()).isTrue();
        }

        @Test
        @DisplayName("verifying a committed write is a no-op - it does not run the query")
        void committedWriteIsNotVerified() {
            server.expect(requestTo(REQUIREMENTS))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON).body(createdEntity("9")));
            AlmWriteResult committed = client().create(SANDBOX, "requirements",
                    AlmEntityBody.of("requirement").set("name", "x"));

            AlmWriteResult after = client().verify(committed, () -> {
                throw new AssertionError("verification must not run for a confirmed write");
            });

            assertThat(after).isEqualTo(committed);
        }
    }
}
