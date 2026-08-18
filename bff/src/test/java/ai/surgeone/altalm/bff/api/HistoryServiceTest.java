package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAudit;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryServiceTest {

    private static final AlmProjectRef PROJECT = new AlmProjectRef("D", "SANDBOX");

    private final AlmEntityClient entities = mock(AlmEntityClient.class);
    private final AlmAccessPolicy policy = new AlmAccessPolicy(PROJECT, Set.of());
    private final HistoryService service = new HistoryService(entities, policy);

    private static AlmAudit audit(String id, String time, String field) {
        return new AlmAudit(id, "UPDATE", time, "someone",
                List.of(new AlmAudit.Change(field, field, "before", "after")));
    }

    @Test
    @DisplayName("entries come back newest first, whatever order ALM sent them in")
    void newestFirst() {
        when(entities.audits(eq(PROJECT), eq("requirements"), eq("605"))).thenReturn(List.of(
                audit("1", "2026-01-02 09:15:00", "status"),
                audit("2", "2026-03-04 11:00:00", "owner"),
                audit("3", "2026-02-01 08:00:00", "severity")));

        HistoryDto.History history = service.history(PROJECT, "requirements", "605");

        // ALM's own order is oldest-first, which opens the tab on the least interesting change.
        assertThat(history.entries()).extracting(HistoryDto.Entry::id)
                .containsExactly("2", "3", "1");
    }

    @Test
    @DisplayName("⚠️ history always reports itself as partial, because ALM's audit trail is")
    void alwaysFlaggedPartial() {
        when(entities.audits(any(), any(), any())).thenReturn(List.of());

        HistoryDto.History history = service.history(PROJECT, "requirements", "605");

        // Probe 24: 678 entries across 119 records, every one an UPDATE, no creates, no memo edits.
        // An empty list here means "ALM recorded no field changes", never "nothing happened", and
        // the flag is what lets the UI say so.
        assertThat(history.entries()).isEmpty();
        assertThat(history.partial()).isTrue();
    }

    @Test
    @DisplayName("a link collection has no history, and is refused before any read")
    void onlyModulesHaveHistory() {
        assertThatThrownBy(() -> service.history(PROJECT, "req-traces", "7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("req-traces");

        verify(entities, never()).audits(any(), any(), any());
    }

    @Test
    @DisplayName("a refused project is refused before any read")
    void accessIsCheckedFirst() {
        assertThatThrownBy(() ->
                service.history(new AlmProjectRef("D", "NOT_ALLOWED"), "requirements", "605"))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);

        verify(entities, never()).audits(any(), any(), any());
    }
}
