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
import ai.surgeone.altalm.bff.alm.write.AlmMetadataFieldResolver;
import ai.surgeone.altalm.bff.alm.write.AlmStaleWriteGuard;
import ai.surgeone.altalm.bff.alm.write.AlmWriteClient;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;
import ai.surgeone.altalm.bff.api.RecordService;
import ai.surgeone.altalm.bff.api.WriteDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CRUD service, against the live server.
 *
 * <p>{@code RecordServiceTest} mocks ALM, so everything it proves is proved against responses
 * <em>we</em> wrote. Two things in this layer cannot be established that way, and both are the whole
 * point of the layer:
 *
 * <ul>
 *   <li><strong>Validation is metadata-driven against a real project.</strong> A stubbed field list
 *       will always agree with the body the same test wrote. Only the live field set can show that
 *       the validator accepts what this project actually has and refuses what it does not — the
 *       ADR 0005 claim, checked rather than asserted.
 *   <li><strong>The conflict detector detects a real conflict.</strong> A mocked {@code ver-stamp}
 *       moves because the test moved it. Here the server moves it, on its own terms, in response to
 *       a write — which is the only version of the question worth answering after probe 31 showed
 *       ALM will happily accept a stale stamp.
 * </ul>
 *
 * <p><strong>This suite writes</strong>, under the same sandbox discipline as its sibling: every row
 * is named {@code ALTALM-CONTRACT-REC-<timestamp>-…}, ids are unwound in reverse, and {@link #sweep()}
 * <strong>asserts</strong> nothing survived rather than logging it.
 */
@Tag("contract")
@EnabledIf("ai.surgeone.altalm.bff.alm.contract.AlmSandbox#credentialsAvailable")
class RecordServiceContractTest {

    /**
     * ⚠️ Distinct from the sibling suite's prefix on purpose. Both sweep the shared
     * {@code ALTALM-CONTRACT} root, and JUnit runs test classes sequentially here (no parallel
     * configuration), so the two cannot overlap — but a distinguishable prefix means a row that
     * <em>does</em> escape names the suite that made it.
     */
    private static final String RUN_PREFIX = AlmSandbox.PROBE_PREFIX + "-REC-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    private static AlmCredentials credentials;
    private static AlmSessionPool pool;
    private static AlmProjectRef sandbox;
    private static AlmEntityClient entities;
    private static AlmWriteClient writes;
    private static RecordService service;

    private static final List<String> created = new ArrayList<>();

    /** ⚠️ NOT -1: that is the tree root sentinel, and a child POSTed against it 500s (probe 27). */
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

        service = new RecordService(writes, entities, catalog,
                new AlmWriteValidator(catalog), new AlmCommentWriter(entities, writes, catalog),
                policy);

        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().fields("id", "type-id").pageSize(1));
        assertThat(page.entities())
                .as("the sandbox needs at least one requirement to parent under")
                .isNotEmpty();
        parentId = page.entities().getFirst().first("id").orElseThrow();
        typeId = page.entities().getFirst().first("type-id").orElse("1");
    }

    @AfterAll
    static void sweep() {
        if (writes == null) {
            return;
        }
        try {
            for (int i = created.size() - 1; i >= 0; i--) {
                var deleted = writes.delete(sandbox, "requirements", created.get(i));
                AlmSandbox.say("cleanup DELETE requirements/" + created.get(i) + " -> "
                        + deleted.outcome());
            }
            // A 5xx write may have committed (probe 4), so "we deleted what we tracked" is a weaker
            // claim than "nothing survived". Only a query settles the second one.
            assertThat(findByPrefix(AlmSandbox.PROBE_PREFIX))
                    .as("orphan sweep: contract-test rows left in the sandbox")
                    .isEmpty();
            AlmSandbox.say("orphan sweep clean");
        } finally {
            pool.close();
        }
    }

    private static List<String> findByPrefix(String prefix) {
        return entities.page(sandbox, "requirements",
                        AlmQuery.none().filter("name", prefix + "*").fields("id", "name").pageSize(100))
                .entities().stream()
                .map(e -> e.first("id").orElse(""))
                .filter(id -> !id.isBlank())
                .toList();
    }

    /**
     * ⚠️ Returns the <em>list</em> shape, because that is what the service takes. It used to
     * return {@code Map<String, String>} and this file stopped compiling when multi-value support
     * landed - which nobody saw, because Maven's incremental compilation had no reason to rebuild a
     * test whose own source had not changed. A green run over stale class files.
     */
    private static Map<String, List<String>> body(String... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], List.of(pairs[i + 1]));
        }
        return m;
    }

    /** Creates a tracked record under the shared parent. */
    private static String create(String suffix) {
        return create(suffix, parentId);
    }

    /** Creates a tracked record through the service under test, under a named parent. */
    private static String create(String suffix, String under) {
        WriteDto.WriteResponse response = service.create(sandbox, "requirements", body(
                "name", RUN_PREFIX + "-" + suffix,
                "parent-id", under,
                "type-id", typeId));

        // Tracked before asserting: a failed assertion still leaves a row to sweep.
        if (response.id() != null) {
            created.add(response.id());
        }
        assertThat(response.outcome()).isEqualTo("COMMITTED");
        return response.id();
    }

    private static Optional<String> fieldOf(String id, String field) {
        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().filter("id", id).fields("id", field).pageSize(1));
        return page.entities().isEmpty() ? Optional.empty() : page.entities().getFirst().first(field);
    }

    // ==========================================================================================

    @Test
    @DisplayName("a create through the service commits and the row is really there")
    void createCommits() {
        String id = create("create");

        assertThat(id).isNotBlank();
        // The response saying "id 42" and the collection containing 42 are different claims.
        assertThat(fieldOf(id, "name")).contains(RUN_PREFIX + "-create");
    }

    @Test
    @DisplayName("an update lands, and the fields it did not mention are untouched")
    void updateIsPartialByField() {
        String id = create("update");

        WriteDto.WriteResponse response = service.update(sandbox, "requirements", id,
                body("description", "<html><body>set by the record service</body></html>"),
                Map.of());

        assertThat(response.outcome()).isEqualTo("COMMITTED");
        assertThat(fieldOf(id, "description")).get().asString().contains("set by the record service");
        assertThat(fieldOf(id, "name")).contains(RUN_PREFIX + "-update");
    }

    @Test
    @DisplayName("a delete removes the row, confirmed by query rather than by the response")
    void deleteRemovesTheRow() {
        String id = create("delete");

        WriteDto.WriteResponse response = service.delete(sandbox, "requirements", id);
        assertThat(response.outcome()).isEqualTo("COMMITTED");
        created.remove(id);

        assertThat(fieldOf(id, "name")).isEmpty();
    }

    // ---- the two things only the live server can settle ----------------------------------------

    @Test
    @DisplayName("validation runs against THIS project's real field set, both ways")
    void validationUsesLiveMetadata() {
        // Refused: no such field in this project. The value of doing this live is that the field
        // list came from ALM, not from a stub that agrees with the test by construction.
        assertThatThrownBy(() -> service.create(sandbox, "requirements",
                body("name", RUN_PREFIX + "-novalidate", "no-such-field-here", "x")))
                .isInstanceOf(AlmWriteValidator.RejectedException.class)
                .satisfies(e -> assertThat(((AlmWriteValidator.RejectedException) e).problems())
                        .singleElement()
                        .satisfies(p -> assertThat(p.code()).isEqualTo("unknown-field")));

        // Accepted: the same shape with only real fields commits. Without this half, a validator
        // that rejected everything would pass the case above.
        String id = create("validate-ok");
        assertThat(id).isNotBlank();
    }

    @Test
    @DisplayName("a memo written as plain text with newlines is refused before ALM can flatten it")
    void memoPlainTextIsRefusedLive() {
        // description is a real Memo field on this project, resolved from live metadata. ALM would
        // accept this write and silently collapse the newline to a space (probe 27) - a 200 with
        // the paragraphs gone. The validator is the only thing that notices.
        assertThatThrownBy(() -> service.update(sandbox, "requirements", parentId,
                body("description", "para one" + (char) 10 + "para two"), Map.of()))
                .isInstanceOf(AlmWriteValidator.RejectedException.class)
                .satisfies(e -> assertThat(((AlmWriteValidator.RejectedException) e).problems())
                        .singleElement()
                        .satisfies(p -> assertThat(p.code()).isEqualTo("plain-text-memo")));
    }

    @Test
    @DisplayName("a second writer working from a stale field value is refused")
    void conflictIsDetectedAgainstAServerMovedValue() {
        String id = create("conflict");

        // The baseline both writers loaded: the description as it stands before either saves.
        Map<String, List<String>> asLoaded = Map.of("description",
                fieldOf(id, "description").map(List::of).orElse(List.of()));

        // The first writer's save must proceed - otherwise the next assertion would pass for the
        // trivial reason that this guard refuses everything.
        WriteDto.WriteResponse ok = service.update(sandbox, "requirements", id,
                body("description", "<html><body>first writer</body></html>"), asLoaded);
        assertThat(ok.outcome()).isEqualTo("COMMITTED");

        // The second writer's page was loaded before that landed, so its baseline is now stale.
        assertThatThrownBy(() -> service.update(sandbox, "requirements", id,
                body("description", "<html><body>would have clobbered</body></html>"), asLoaded))
                .isInstanceOf(AlmStaleWriteGuard.ConflictException.class)
                .hasMessageContaining("description");

        // ⚠️ And the point that must not be lost: the refusal came from US, not from ALM. Probe 31
        // established the server accepts a stale write and lets it land. Sending this straight to
        // ALM would have overwritten "first writer" with a 200.
        assertThat(fieldOf(id, "description")).get().asString().contains("first writer");
    }

    @Test
    @DisplayName("filing a CHILD moves the parent's ver-stamp and does NOT block the parent's save")
    void aChildCreateDoesNotBlockTheParentsSave() {
        String id = create("parent-of-child");

        Map<String, List<String>> asLoaded = Map.of("description",
                fieldOf(id, "description").map(List::of).orElse(List.of()));
        String stampBefore = fieldOf(id, "ver-stamp").orElseThrow();

        // Someone files a record underneath the one we have open.
        String childId = create("child-of-parent", id);
        assertThat(childId).isNotBlank();

        // ⚠️ This is the whole reason the guard stopped looking at ver-stamp (probe 34). If this
        // assertion ever fails, ALM changed and the stamp-based guard was fine after all.
        assertThat(fieldOf(id, "ver-stamp").orElseThrow())
                .as("creating a child moves the PARENT's ver-stamp")
                .isNotEqualTo(stampBefore);

        // No field on the parent differs, so the parent's own save must go through. The stamp-based
        // guard refused exactly this, naming a conflict that did not exist.
        WriteDto.WriteResponse response = service.update(sandbox, "requirements", id,
                body("description", "<html><body>saved after a child appeared</body></html>"),
                asLoaded);
        assertThat(response.outcome()).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("a comment through the service preserves history rather than replacing it")
    void commentPreservesHistory() {
        String id = create("comment");

        service.comment(sandbox, "requirements", id, "Contract Test", "FIRST note.", Optional.empty());
        // The second comment guards on the thread the first one produced - the shape the SPA sends,
        // and the one that refuses if anybody else commented in between.
        service.comment(sandbox, "requirements", id, "Contract Test", "SECOND note.",
                fieldOf(id, "comments"));

        String stored = fieldOf(id, "comments").orElse("");
        assertThat(stored).contains("FIRST note.").contains("SECOND note.");
        assertThat(stored.indexOf("FIRST")).isLessThan(stored.indexOf("SECOND"));
    }

    @Test
    @DisplayName("the comment field is discovered from live metadata, and differs per entity")
    void commentFieldIsDiscoveredLive() {
        // Probe 30: a requirement's is `comments`, a defect's is `dev-comments`, and neither tracks
        // the physical name. A constant that is right for one is wrong for the other.
        assertThat(service.commentField(sandbox, "requirements")).contains("comments");
        assertThat(service.commentField(sandbox, "defects")).contains("dev-comments");
    }

    // ---- refusals that must hold before any I/O ------------------------------------------------

    @Test
    @DisplayName("a project nobody enrolled is refused as a WRITE, and nothing is sent")
    void unenrolledProjectIsRefused() {
        AlmProjectRef other = new AlmProjectRef(credentials.domain(), "NOT-ENROLLED-BY-THIS-TEST");

        assertThatThrownBy(() -> service.create(other, "requirements", body("name", "x")))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class)
                .hasMessageContaining("WRITE DENIED")
                // The message names a pseudonym, never the project, so a 403 body cannot be used to
                // enumerate the tenant.
                .hasMessageNotContaining("NOT-ENROLLED-BY-THIS-TEST");
    }

    @Test
    @DisplayName("runs and attachments are refused as endpoints, not attempted and failed")
    void unwritableCollectionsAreRefused() {
        // POST runs fails definitively (8 attempts); the only route is a status PUT on a
        // test-instance that makes ALM synthesize a Fast_Run. Attachments need multipart.
        assertThatThrownBy(() -> service.create(sandbox, "runs", body("name", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(sandbox, "attachments", body("name", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
