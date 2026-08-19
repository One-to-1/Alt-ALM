package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataClient;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.read.AlmReadRetry;
import ai.surgeone.altalm.bff.alm.session.AlmAuthClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import ai.surgeone.altalm.bff.alm.write.AlmCommentWriter;
import ai.surgeone.altalm.bff.alm.write.AlmVersionGuard;
import ai.surgeone.altalm.bff.alm.write.AlmEntityBody;
import ai.surgeone.altalm.bff.alm.write.AlmMetadataFieldResolver;
import ai.surgeone.altalm.bff.alm.write.AlmWriteClient;
import ai.surgeone.altalm.bff.alm.write.AlmWriteOutcome;
import ai.surgeone.altalm.bff.alm.write.AlmWriteResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The P2 write path, against the live server.
 *
 * <p><strong>This suite writes.</strong> That is the point — P0's equivalent found two real bugs in
 * {@code AlmAuthClient} that every unit test had passed over, because the mocked server did what the
 * mock's author expected and the real one did not. The unit tests for {@link AlmWriteClient} assert
 * against responses <em>we</em> wrote; this asserts against responses ALM wrote.
 *
 * <p>The full sandbox gate is active, per the implementation plan:
 * <ul>
 *   <li>every created record is named {@code ALTALM-CONTRACT-<timestamp>-…}, so an escapee is
 *       greppable and sweepable;
 *   <li>ids are tracked as they are created and deleted in <strong>reverse</strong> order;
 *   <li>{@link #sweep()} runs last and <strong>asserts zero survivors</strong> — a leak fails the
 *       build rather than being logged and forgotten.
 * </ul>
 *
 * <p>⚠️ Tests are deliberately self-contained rather than chained create→update→delete. JUnit's
 * method order is deterministic but unspecified, and a chain that silently reorders would leave rows
 * behind while still passing.
 */
@Tag("contract")
@EnabledIf("ai.surgeone.altalm.bff.alm.contract.AlmSandbox#credentialsAvailable")
class AlmWriteClientContractTest {

    private static final String RUN_PREFIX = AlmSandbox.PROBE_PREFIX + "-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    private static AlmCredentials credentials;
    private static AlmSessionPool pool;
    private static AlmProjectRef sandbox;
    private static AlmEntityClient entities;
    private static AlmWriteClient writes;
    private static AlmCommentWriter comments;

    /** Everything created, in creation order. Unwound backwards so children go before parents. */
    private static final List<String> created = new ArrayList<>();

    /** An existing requirement to parent under. ⚠️ NOT -1: that is the root sentinel (probe 27). */
    private static String parentId;
    private static String typeId = "1";

    @BeforeAll
    static void setUp() {
        credentials = AlmSandbox.credentials();
        var http = AlmSandbox.http();
        var auth = new AlmAuthClient(http, credentials);
        pool = new AlmSessionPool(2, Duration.ofMinutes(60), auth::login, auth::logout);
        sandbox = AlmProjectRef.sandboxOf(credentials);

        AlmAccessPolicy policy = new AlmAccessPolicy(sandbox, Set.of());
        entities = new AlmEntityClient(http, credentials, pool, policy,
                new AlmReadRetry(3, Duration.ofMillis(250)), Duration.ofSeconds(30));
        var catalog = new AlmMetadataCatalog(
                new AlmMetadataClient(http, credentials, pool, Duration.ofSeconds(30)), policy);
        writes = new AlmWriteClient(http, credentials, pool, policy,
                new AlmMetadataFieldResolver(catalog, sandbox), Duration.ofSeconds(30));
        comments = new AlmCommentWriter(entities, writes, catalog);

        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().fields("id", "type-id").pageSize(1));
        assertThat(page.entities())
                .as("the sandbox needs at least one requirement to parent under")
                .isNotEmpty();
        parentId = page.entities().get(0).first("id").orElseThrow();
        typeId = page.entities().get(0).first("type-id").orElse("1");
        AlmSandbox.say("parenting under an existing requirement, type-id=" + typeId);
    }

    @AfterAll
    static void sweep() {
        if (writes == null) {
            return;
        }
        try {
            for (int i = created.size() - 1; i >= 0; i--) {
                AlmWriteResult deleted = writes.delete(sandbox, "requirements", created.get(i));
                AlmSandbox.say("cleanup DELETE requirements/" + created.get(i) + " -> "
                        + deleted.outcome());
            }

            // The assertion that matters. A 5xx write may have committed (probe 4), so "we deleted
            // what we tracked" is not the same claim as "nothing survived" - only a query is.
            List<String> survivors = findByPrefix(AlmSandbox.PROBE_PREFIX);
            assertThat(survivors)
                    .as("orphan sweep: contract-test rows left in the sandbox")
                    .isEmpty();
            AlmSandbox.say("orphan sweep clean");
        } finally {
            pool.close();
        }
    }

    /** Ids of every requirement whose name starts with the given prefix. */
    private static List<String> findByPrefix(String prefix) {
        return entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("name", prefix + "*").fields("id", "name").pageSize(100))
                .entities().stream()
                .map(e -> e.first("id").orElse(""))
                .filter(id -> !id.isBlank())
                .toList();
    }

    /** Creates a tracked requirement, failing the test if it did not commit. */
    private static String createRequirement(String suffix) {
        AlmWriteResult result = writes.create(sandbox, "requirements",
                AlmEntityBody.of("requirement")
                        .set("name", RUN_PREFIX + "-" + suffix)
                        .set("parent-id", parentId)
                        .set("type-id", typeId));

        // Tracked before asserting: if the assertion fails, the row still has to be swept.
        result.effectiveId().ifPresent(created::add);
        assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);
        return result.effectiveId().orElseThrow();
    }

    // ==========================================================================================

    @Test
    @DisplayName("a create commits and comes back with the server's id")
    void createCommits() {
        String id = createRequirement("create");

        assertThat(id).isNotBlank();
        // Read it back through the read path: the write's own response saying "id 42" and the
        // collection actually containing 42 are different claims.
        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().filter("id", id).fields("id", "name").pageSize(1));
        assertThat(page.entities()).hasSize(1);
        assertThat(page.entities().get(0).first("name")).contains(RUN_PREFIX + "-create");
    }

    @Test
    @DisplayName("an update changes the field and leaves the rest of the record alone")
    void updateIsPartialByField() {
        String id = createRequirement("update");

        AlmWriteResult result = writes.update(sandbox, "requirements", id,
                AlmEntityBody.of("requirement").set("description", "<html><body>set by contract test</body></html>"));

        assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);

        AlmEntityPage after = entities.page(sandbox, "requirements",
                AlmQuery.none().filter("id", id).fields("id", "name", "description").pageSize(1));
        AlmEntityPage.AlmEntity row = after.entities().get(0);
        assertThat(row.first("description")).get().asString().contains("set by contract test");
        // The name was not in the body and must be untouched - "partial by field" is the half of
        // the update contract that is safe to rely on.
        assertThat(row.first("name")).contains(RUN_PREFIX + "-update");
    }

    @Test
    @DisplayName("⚠️ a memo update REPLACES, exactly as probe 30 found - the data-loss case, pinned live")
    void memoUpdateReplacesRatherThanAppends() {
        String id = createRequirement("memo");

        writes.update(sandbox, "requirements", id,
                AlmEntityBody.of("requirement").set("comments", "FIRST from the contract test."));
        writes.update(sandbox, "requirements", id,
                AlmEntityBody.of("requirement").set("comments", "SECOND from the contract test."));

        String stored = entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("id", id).fields("id", "comments").pageSize(1))
                .entities().get(0).first("comments").orElse("");

        assertThat(stored).contains("SECOND");
        // If this ever starts failing, ALM began appending server-side and the read-modify-write
        // in the comment path above this client is no longer merely necessary - it would be
        // DOUBLING every comment. That is why the destructive behaviour is pinned rather than
        // just documented.
        assertThat(stored)
                .as("a memo PUT replaces; if ALM ever appends, the comment write path must change")
                .doesNotContain("FIRST");
    }

    @Test
    @DisplayName("a delete removes the row, and the row is really gone")
    void deleteRemovesTheRow() {
        String id = createRequirement("delete");

        AlmWriteResult result = writes.delete(sandbox, "requirements", id);
        assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.COMMITTED);
        created.remove(id);

        assertThat(entities.page(sandbox, "requirements",
                AlmQuery.none().filter("id", id).fields("id").pageSize(1)).entities()).isEmpty();
    }

    @Test
    @DisplayName("a create missing a required field is REJECTED, with ALM's own error id")
    void missingRequiredFieldIsRejected() {
        AlmWriteResult result = writes.create(sandbox, "requirements",
                AlmEntityBody.of("requirement").set("parent-id", parentId).set("type-id", typeId));
        result.effectiveId().ifPresent(created::add);

        // A 4xx is the one outcome safe to call a clean failure. Asserted against the LIVE server
        // because the unit test's 400 was one we wrote ourselves.
        assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.REJECTED);
        assertThat(result.errorId()).isNotBlank();
        AlmSandbox.say("missing-name create -> " + result.errorId() + " / " + result.errorTitle());
    }

    @Test
    @DisplayName("⚠️ a bad parent reference is UNKNOWN, not REJECTED - and verification finds nothing")
    void brokenReferenceIsUnknownAndVerifiable() {
        // Probe 29 established this answers 500. A 500 is "may have committed", so the client must
        // NOT call it a failure - and the verification query is what actually settles it.
        AlmWriteResult result = writes.create(sandbox, "requirements",
                AlmEntityBody.of("requirement")
                        .set("name", RUN_PREFIX + "-badparent")
                        .set("parent-id", "999999")
                        .set("type-id", typeId));

        assertThat(result.outcome()).isEqualTo(AlmWriteOutcome.UNKNOWN);
        assertThat(result.needsVerification()).isTrue();

        AlmWriteResult verified = writes.verify(result, () ->
                findByPrefix(RUN_PREFIX + "-badparent").stream().findFirst());

        // Nothing committed - which is what we expected, and is now established by a query rather
        // than by assuming a 500 meant failure.
        verified.verifiedId().ifPresent(created::add);
        assertThat(verified.verifiedId())
                .as("a 500 that did commit would show up here, and would be a finding")
                .isEmpty();
    }

    @Test
    @DisplayName("verification finds a row that IS there, closing the loop the other way")
    void verificationFindsACommittedRow() {
        // The previous test proves verify() reports absence. This proves it is not simply always
        // returning empty - which would make that test pass for the wrong reason.
        String id = createRequirement("verifiable");

        AlmWriteResult pretendUnknown = AlmWriteResult.unknown("qccore.general-error", "simulated", false);
        AlmWriteResult verified = writes.verify(pretendUnknown, () ->
                findByPrefix(RUN_PREFIX + "-verifiable").stream().findFirst());

        assertThat(verified.verifiedId()).contains(id);
        assertThat(verified.needsVerification()).isFalse();
        // Still UNKNOWN: the row existing is not evidence the write succeeded.
        assertThat(verified.outcome()).isEqualTo(AlmWriteOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("the root sentinel is refused by us, before the request goes out")
    void rootSentinelIsRefusedLocally() {
        assertThatThrownBy(() -> writes.update(sandbox, "requirements", "-1",
                AlmEntityBody.of("requirement").set("name", RUN_PREFIX + "-sentinel")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root sentinel");

        // And nothing by that name exists, because nothing was sent.
        assertThat(findByPrefix(RUN_PREFIX + "-sentinel")).isEmpty();
    }

    @Test
    @DisplayName("a write to an unenrolled project is refused without touching the network")
    void unenrolledProjectIsRefused() {
        // The sandbox-only rule was lifted on 2026-08-18, so this asserts what replaced it:
        // enrolment is the lever, and a project nobody enrolled is still refused.
        AlmProjectRef stranger = new AlmProjectRef(credentials.domain(), "NOT-ENROLLED-BY-ANYONE");

        assertThatThrownBy(() -> writes.create(stranger, "requirements",
                AlmEntityBody.of("requirement").set("name", RUN_PREFIX + "-nope")))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("the metadata field resolver resolves a real physical column from this project")
    void fieldResolverWorksAgainstLiveMetadata() {
        // The missing-field retry is untestable on demand against a live server - it needs ALM to
        // demand a column its own metadata calls optional. What IS testable is the half that would
        // silently disable the retry: whether the resolver can map a physical name at all.
        var catalog = new AlmMetadataCatalog(
                new AlmMetadataClient(AlmSandbox.http(), credentials, pool, Duration.ofSeconds(30)),
                new AlmAccessPolicy(sandbox, Set.of()));
        var resolver = new AlmMetadataFieldResolver(catalog, sandbox);

        Optional<?> resolved = resolver.byPhysicalName("requirement", "RQ_REQ_NAME");
        assertThat(resolved)
                .as("if this is empty the retry is silently dead, and a create that needs it "
                        + "would fail with the original 500 forever")
                .isPresent();

        assertThat(resolver.byPhysicalName("requirement", "NO_SUCH_COLUMN")).isEmpty();
    }

    // ======================================================================================
    // The comment path. These are the cases the class was written for, so they are the ones
    // worth running against the real server: ALM re-serialises the memo on the way in, and a
    // merge that survives a mock does not automatically survive that.

    @Test
    @DisplayName("adding a second comment PRESERVES the first - the data loss this path prevents")
    void commentAppendPreservesHistory() {
        String id = createRequirement("comment-history");

        comments.addComment(sandbox, "requirements", "requirement", id,
                "Contract Test", "FIRST comment.", Optional.empty());
        comments.addComment(sandbox, "requirements", "requirement", id,
                "Contract Test", "SECOND comment.", Optional.empty());

        String stored = entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("id", id).fields("id", "comments").pageSize(1))
                .entities().get(0).first("comments").orElse("");

        // Both, in order. The paired test above (memoUpdateReplacesRatherThanAppends) proves the
        // RAW field replaces - so this passing is evidence of the merge working, not of ALM
        // having quietly started appending on its own.
        assertThat(stored).contains("FIRST comment.");
        assertThat(stored).contains("SECOND comment.");
        assertThat(stored.indexOf("FIRST")).isLessThan(stored.indexOf("SECOND"));
    }

    @Test
    @DisplayName("a real concurrent write is detected by ver-stamp, and the comment is refused")
    void concurrentEditIsDetected() {
        String id = createRequirement("comment-conflict");

        String stampBefore = entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("id", id).fields("id", "ver-stamp").pageSize(1))
                .entities().get(0).first("ver-stamp").orElseThrow();

        comments.addComment(sandbox, "requirements", "requirement", id,
                "Someone Else", "landed first", Optional.empty());

        // Now write as if we had only seen the record at stampBefore - which is exactly what a
        // second user with the page already open would be doing.
        assertThatThrownBy(() -> comments.addComment(sandbox, "requirements", "requirement", id,
                "Contract Test", "would have clobbered", Optional.of(stampBefore)))
                .isInstanceOf(AlmVersionGuard.ConflictException.class);

        String stored = entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("id", id).fields("id", "comments").pageSize(1))
                .entities().get(0).first("comments").orElse("");
        assertThat(stored).contains("landed first");
        assertThat(stored).doesNotContain("would have clobbered");
    }

    @Test
    @DisplayName("the comment field is discovered from live metadata as `comments` for a requirement")
    void commentFieldIsDiscoveredLive() {
        // Probe 30: a requirement's comment field is `comments` (physically RQ_DEV_COMMENTS) while a
        // defect's is `dev-comments`. If discovery broke, every comment would silently go to the
        // wrong field or none at all.
        assertThat(comments.commentFieldOf(sandbox, "requirement")).contains("comments");
        assertThat(comments.commentFieldOf(sandbox, "defect")).contains("dev-comments");
    }

    @Test
    @DisplayName("newlines survive as <br> rather than collapsing to spaces (probe 27's trap)")
    void newlinesSurviveTheRoundTrip() {
        String id = createRequirement("comment-newlines");

        comments.addComment(sandbox, "requirements", "requirement", id,
                "Contract Test", "para one" + "\n" + "para two", Optional.empty());

        String stored = entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("id", id).fields("id", "comments").pageSize(1))
                .entities().get(0).first("comments").orElse("");

        // ALM collapses raw newlines to spaces and canonicalises <br> to <br />, so this asserts
        // the separation survived at all rather than asserting an exact byte sequence.
        //
        // The first version of this test sent a LITERAL backslash-n rather than a newline, so the
        // escaper had nothing to convert and ALM stored the two characters verbatim. It failed, and
        // it was right to - but the bug was in the test. Worth the note: a newline test that builds
        // its own input wrongly looks exactly like a product that does not handle newlines.
        assertThat(stored).contains("para one");
        assertThat(stored).contains("para two");
        assertThat(stored).matches("(?s).*para one\\s*<br\\s*/?>\\s*para two.*");
    }
}
