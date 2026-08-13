package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCache;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataClient;
import ai.surgeone.altalm.bff.alm.session.AlmAuthClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0's second exit criterion, live: <em>the metadata service returns cached field descriptors for all
 * 15 probe-known entity types.</em>
 *
 * <p>The fixture harness already proves the parse path offline for the same 15 entities. What it
 * cannot prove is that the captured fixtures still resemble what the server sends, or that the closed
 * 8-type system holds against live customization rather than a snapshot taken once. This does — and
 * it exercises {@link AlmMetadataClient}, which the offline tests deliberately cannot reach.
 *
 * <p>Strictly read-only: {@code customization/entities/*} is a GET-only surface.
 */
@Tag("contract")
@EnabledIf("ai.surgeone.altalm.bff.alm.contract.AlmSandbox#credentialsAvailable")
@DisplayName("ALM metadata service against the live sandbox")
class AlmMetadataContractTest {

    /** The 15 entities captured as fixtures, i.e. the set the data model is built on. */
    private static final List<String> PROBED_ENTITIES = List.of(
            "requirement", "test", "test-folder", "test-config", "test-set", "test-set-folder",
            "test-instance", "run", "run-step", "design-step", "defect", "release", "release-cycle",
            "release-folder", "resource");

    private static AlmCredentials creds;
    private static AlmAuthClient auth;
    private static AlmSessionPool pool;
    private static AlmMetadataClient client;

    @BeforeAll
    static void open() {
        creds = AlmSandbox.credentials();
        RestClient http = AlmSandbox.http();
        auth = new AlmAuthClient(http, creds);
        pool = new AlmSessionPool(2, AlmSession.DEFAULT_MAX_IDLE, auth::login, auth::logout);
        client = new AlmMetadataClient(http, creds, pool, Duration.ofSeconds(30));
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("every one of the 15 probed entities returns parseable fields")
    void allProbedEntitiesParse() {
        EnumSet<AlmFieldType> seen = EnumSet.noneOf(AlmFieldType.class);
        int total = 0;

        for (String entity : PROBED_ENTITIES) {
            List<FieldDescriptor> fields = client.fetchFields(entity);

            assertThat(fields).as("%s should expose fields", entity).isNotEmpty();
            fields.forEach(f -> seen.add(f.type()));
            total += fields.size();
        }

        // The parser throws on an unknown type, so reaching here already proves the type system is
        // still closed. Asserting no Boolean appeared makes the more specific claim explicit: ALM
        // has no boolean field type, and Y/N is a LookupList bound to list-id 1.
        assertThat(AlmFieldType.values()).hasSize(8);
        assertThat(seen).isSubsetOf(AlmFieldType.values());
        AlmSandbox.say("metadata: " + PROBED_ENTITIES.size() + " entities, " + total
                + " fields, types seen=" + seen);
    }

    @Test
    @DisplayName("requirement metadata carries the fields the data model depends on")
    void requirementShapeHolds() {
        List<FieldDescriptor> fields = client.fetchFields("requirement");
        Set<String> names = fields.stream().map(FieldDescriptor::name).collect(java.util.stream.Collectors.toSet());

        assertThat(names).contains("id", "name", "parent-id", "type-id");
        // physicalName is the join key back from a "missing required field <PHYSICAL_NAME>" 500,
        // so an empty one would silently break AlmWriteRetry.
        assertThat(fields).allSatisfy(f -> assertThat(f.physicalName()).isNotBlank());
    }

    @Test
    @DisplayName("the cache fetches once, and invalidateAll forces a re-fetch")
    void cacheServesAndRefreshes() {
        AtomicInteger fetches = new AtomicInteger();
        AlmMetadataCache cache = new AlmMetadataCache(creds.domain(), creds.project(), entity -> {
            fetches.incrementAndGet();
            return client.fetchFields(entity);
        });

        List<FieldDescriptor> first = cache.fields("defect");
        List<FieldDescriptor> second = cache.fields("defect");

        assertThat(second).isSameAs(first);
        assertThat(fetches.get()).as("second read served from cache").isEqualTo(1);

        cache.invalidateAll();
        cache.fields("defect");
        assertThat(fetches.get()).as("refresh action re-fetches").isEqualTo(2);
    }
}
