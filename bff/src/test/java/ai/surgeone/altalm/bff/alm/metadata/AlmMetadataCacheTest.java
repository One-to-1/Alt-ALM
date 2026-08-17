package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cache behaviour, exercised with a counting fake loader - no server, no credentials. */
class AlmMetadataCacheTest {

    private static FieldDescriptor field(String name) {
        return new FieldDescriptor(name, "RQ_" + name.toUpperCase(), AlmFieldType.STRING,
                name, false, true, true, false, false, true, true, 0, 40);
    }

    private static AlmMetadataCache cache(AtomicInteger loads) {
        return new AlmMetadataCache("DOM", "PROJ", entity -> {
            loads.incrementAndGet();
            return List.of(field(entity + "-a"), field(entity + "-b"));
        });
    }

    @Test
    @DisplayName("fetches once, then serves from cache")
    void cachesAfterFirstLoad() {
        AtomicInteger loads = new AtomicInteger();
        AlmMetadataCache cache = cache(loads);

        List<FieldDescriptor> first = cache.fields("requirement");
        List<FieldDescriptor> second = cache.fields("requirement");

        assertThat(second).isEqualTo(first);
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps entities separate")
    void entitiesAreIndependent() {
        AtomicInteger loads = new AtomicInteger();
        AlmMetadataCache cache = cache(loads);

        cache.fields("requirement");
        cache.fields("defect");

        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.cachedEntities()).containsExactlyInAnyOrder("requirement", "defect");
    }

    @Test
    @DisplayName("invalidate drops one entity and re-fetches it")
    void invalidateOneEntity() {
        AtomicInteger loads = new AtomicInteger();
        AlmMetadataCache cache = cache(loads);
        cache.fields("requirement");
        cache.fields("defect");

        cache.invalidate("requirement");

        assertThat(cache.cachedEntities()).containsExactly("defect");
        cache.fields("requirement");
        cache.fields("defect");
        assertThat(loads.get()).as("requirement re-fetched, defect still cached").isEqualTo(3);
    }

    @Test
    @DisplayName("invalidateAll is the refresh-metadata lever")
    void invalidateEverything() {
        AtomicInteger loads = new AtomicInteger();
        AlmMetadataCache cache = cache(loads);
        cache.fields("requirement");
        cache.fields("defect");

        cache.invalidateAll();

        assertThat(cache.size()).isZero();
        cache.fields("requirement");
        assertThat(loads.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed load is not cached, and the next call retries")
    void failureIsNotCached() {
        // ADR 0005 rejected stale/default fallbacks: a form that silently omits required fields is
        // worse than one that refuses to render. So the failure must reach the caller AND leave
        // nothing behind - a cached failure would turn one blip into a permanent dead entity.
        AtomicInteger attempts = new AtomicInteger();
        AlmMetadataCache cache = new AlmMetadataCache("DOM", "PROJ", entity -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("metadata fetch failed");
            }
            return List.of(field("name"));
        });

        assertThatThrownBy(() -> cache.fields("requirement"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata fetch failed");
        assertThat(cache.size()).isZero();

        assertThat(cache.fields("requirement")).hasSize(1);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent callers for the same entity cause one fetch, not one each")
    void concurrentCallersShareOneFetch() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AlmMetadataCache cache = new AlmMetadataCache("DOM", "PROJ", entity -> {
            loads.incrementAndGet();
            try {
                // Hold the "HTTP call" open so the other threads pile up behind it.
                releaseLoader.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(field("name"));
        });

        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch done = new CountDownLatch(callers);
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        cache.fields("requirement");
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(loads.get()).as("8 concurrent callers, 1 metadata fetch").isEqualTo(1);
    }

    @Test
    @DisplayName("the cache is project-scoped, not global")
    void separateProjectsDoNotShareEntries() {
        // ADR 0005's central point: ALM customization is per project, so one project's field set
        // must never satisfy another project's request. Two caches, same entity name, no crosstalk.
        Map<String, AtomicInteger> loads = Map.of("A", new AtomicInteger(), "B", new AtomicInteger());
        AlmMetadataCache projectA = new AlmMetadataCache("DOM", "PROJ_A", e -> {
            loads.get("A").incrementAndGet();
            return List.of(field("a-only"));
        });
        AlmMetadataCache projectB = new AlmMetadataCache("DOM", "PROJ_B", e -> {
            loads.get("B").incrementAndGet();
            return List.of(field("b-only"));
        });

        assertThat(projectA.fields("requirement")).extracting(FieldDescriptor::name)
                .containsExactly("a-only");
        assertThat(projectB.fields("requirement")).extracting(FieldDescriptor::name)
                .containsExactly("b-only");
        assertThat(loads.get("A").get()).isEqualTo(1);
        assertThat(loads.get("B").get()).isEqualTo(1);
    }
}
