package ai.surgeone.altalm.bff.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The navigation rail's honesty guarantees.
 *
 * <p>Every test here pins a claim the UI makes to a user. The rail is the one screen that talks
 * about features Alt-ALM does <em>not</em> have, so being wrong here is worse than being wrong in a
 * grid: a dead link promises something the documented API cannot deliver.
 */
class ModuleServiceTest {

    private final ModuleService service = new ModuleService();

    private List<ModuleDto.Item> items() {
        return service.rail().groups().stream().flatMap(g -> g.items().stream()).toList();
    }

    private ModuleDto.Item item(String key) {
        return items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a READABLE entry always names a collection this build will actually serve")
    void readableEntriesAreBackedByTheAllowlist() {
        assertThat(items())
                .filteredOn(i -> i.reach() == ModuleDto.Reach.READABLE)
                .isNotEmpty()
                .allSatisfy(i -> {
                    assertThat(i.collection()).isNotBlank();
                    // The allowlist is the security boundary the rest of the BFF enforces. An entry
                    // claiming to be readable past it would be a link that 400s.
                    assertThat(AlmCollections.isModule(i.collection())).isTrue();
                });
    }

    @Test
    @DisplayName("every entry that is not readable explains itself")
    void unreachableEntriesCarryTheirReason() {
        assertThat(items())
                .filteredOn(i -> i.reach() != ModuleDto.Reach.READABLE)
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.reason())
                        .as("entry '%s' is not readable and must say why", i.key())
                        .isNotBlank());
    }

    @Test
    @DisplayName("⚠️ 'not built yet' and 'no API reaches this' stay different answers")
    void unbuiltAndUnreachableAreDistinct() {
        // Releases is a screen nobody has written; the read path is verified and waiting.
        assertThat(item("releases").reach()).isEqualTo(ModuleDto.Reach.BUILDABLE);

        // Libraries is not waiting for anyone. Every documented path 404s. Rendering these two the
        // same way would promise a feature that cannot be delivered over the documented API — the
        // single most misleading thing this UI could do.
        assertThat(item("libraries").reach()).isEqualTo(ModuleDto.Reach.NO_API);

        // And the Dashboard entries are a third case again: reachable, but only over the Windows
        // sidecar, so they stay unavailable in any deployment without it.
        assertThat(item("analysis-view").reach()).isEqualTo(ModuleDto.Reach.NEEDS_SIDECAR);
        assertThat(item("dashboard-view").reach()).isEqualTo(ModuleDto.Reach.NEEDS_SIDECAR);
    }

    @Test
    @DisplayName("an entry with nothing to open names no collection")
    void nonNavigableEntriesNameNoCollection() {
        assertThat(item("libraries").collection()).isEmpty();
        assertThat(item("analysis-view").collection()).isEmpty();
        assertThat(item("homepage").collection()).isEmpty();
    }

    @Test
    @DisplayName("the rail uses ALM's module names, not the collection names")
    void railUsesAlmsOwnNames() {
        // The distinction anyone who has used the stock client will notice immediately: the module
        // is Test Plan and the records in it are tests.
        assertThat(item("tests").label()).isEqualTo("Test Plan");
        assertThat(item("test-sets").label()).isEqualTo("Test Lab");
        assertThat(item("runs").label()).isEqualTo("Test Runs");
    }

    @Test
    @DisplayName("keys are unique, so the SPA can key its list on them")
    void keysAreUnique() {
        assertThat(items()).extracting(ModuleDto.Item::key).doesNotHaveDuplicates();
    }
}
