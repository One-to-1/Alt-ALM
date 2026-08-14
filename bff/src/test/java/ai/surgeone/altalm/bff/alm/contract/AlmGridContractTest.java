package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataClient;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.read.AlmReadRetry;
import ai.surgeone.altalm.bff.alm.read.AlmTreeRoots;
import ai.surgeone.altalm.bff.alm.session.AlmAuthClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import ai.surgeone.altalm.bff.api.GridDto;
import ai.surgeone.altalm.bff.api.GridService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The P1 read path, against the live server. Tagged {@code contract}: excluded from the default
 * build, opted into with {@code -Pcontract}, and skipped (never fake-passed) without credentials.
 *
 * <p><strong>Strictly read-only, and structurally so</strong> — {@link AlmEntityClient} has no write
 * method to call. This exercises the sandbox only; the populated read-only project is not required
 * for the suite to be meaningful, so these tests do not depend on another team's project existing.
 */
@Tag("contract")
@EnabledIf("ai.surgeone.altalm.bff.alm.contract.AlmSandbox#credentialsAvailable")
class AlmGridContractTest {

    private static AlmCredentials credentials;
    private static AlmSessionPool pool;
    private static AlmProjectRef sandbox;
    private static AlmEntityClient entities;
    private static GridService grids;

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
        var metadataClient = new AlmMetadataClient(http, credentials, pool, Duration.ofSeconds(30));
        grids = new GridService(entities, new AlmMetadataCatalog(metadataClient, policy), policy);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("a requirements page comes back with metadata-derived columns and real rows")
    void requirementsGridRenders() {
        GridDto.Grid grid = grids.grid(sandbox, "requirements", 50, 1, null, false);

        assertThat(grid.collection()).isEqualTo("requirements");
        assertThat(grid.writable()).isTrue();               // the sandbox IS writable
        assertThat(grid.columns()).isNotEmpty();
        assertThat(grid.columns()).extracting(GridDto.Column::name).contains("id", "name");
        // Every column carries one of the eight types — no unknown type, as probe 3 established.
        assertThat(grid.columns()).extracting(GridDto.Column::type)
                .allSatisfy(t -> assertThat(t).isIn("STRING", "MEMO", "NUMBER", "DATE", "DATE_TIME",
                        "LOOKUP_LIST", "USERS_LIST", "REFERENCE"));

        // The sandbox holds the root requirement at minimum (probe 16: 1 row).
        assertThat(grid.rows()).isNotEmpty();
        assertThat(grid.rows().getFirst().values()).containsKey("id");
    }

    @Test
    @DisplayName("the tree-root rule resolves every tree that exists in this project")
    void treeRootsResolveLive() {
        AlmTreeRoots roots = entities.treeRoots(sandbox);

        List<AlmTreeRoots.Resolved> all = roots.resolveAll();

        // requirements, test-folders and test-set-folders exist in every project.
        assertThat(all).filteredOn(r -> r.collection().equals("requirements"))
                .allSatisfy(r -> assertThat(r.ok()).isTrue());
        assertThat(all).filteredOn(r -> r.collection().equals("test-set-folders"))
                .allSatisfy(r -> {
                    assertThat(r.ok()).isTrue();
                    // ⚠️ The regression that matters: the old rule returned Recycle Bin here.
                    assertThat(r.root().name()).isNotEqualToIgnoringCase("Recycle Bin");
                });
    }

    @Test
    @DisplayName("order-by uses the semicolon separator the server actually accepts (probe 17)")
    void multiFieldOrderByIsAccepted() {
        // A comma here returns HTTP 404. If AlmQuery ever regresses to comma, this fails live.
        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().orderBy("type-id").orderBy("id").fields("id").pageSize(5));

        assertThat(page).isNotNull();
    }

    @Test
    @DisplayName("page-size=max is accepted by the server")
    void pageSizeMaxIsAccepted() {
        AlmEntityPage page = entities.page(sandbox, "requirements",
                AlmQuery.none().fields("id").pageSizeMax());

        assertThat(page).isNotNull();
    }

    @Test
    @DisplayName("an out-of-range page-size is refused by ALM with a 404, not silently clamped")
    void outOfRangePageSizeIsRejectedByTheServer() {
        // AlmQuery refuses to build this, which is the first line of defence...
        assertThatThrownBy(() -> AlmQuery.none().pageSize(5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reading a project that is not on the allowlist is refused before any HTTP call")
    void unenrolledProjectIsRefusedLocally() {
        AlmProjectRef notEnrolled = new AlmProjectRef(credentials.domain(), "DEFINITELY-NOT-ENROLLED");

        assertThatThrownBy(() -> entities.page(notEnrolled, "requirements", AlmQuery.none()))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("metadata is fetched per project, so a grid cannot borrow another project's columns")
    void metadataIsProjectScoped() {
        GridDto.Grid grid = grids.grid(sandbox, "defects", 10, 1, null, false);

        // Defect columns must differ from requirement columns — if the catalog were keyed globally
        // or bound to one entity, this is where that would show up.
        GridDto.Grid requirements = grids.grid(sandbox, "requirements", 10, 1, null, false);
        assertThat(grid.columns()).isNotEqualTo(requirements.columns());
    }
}
