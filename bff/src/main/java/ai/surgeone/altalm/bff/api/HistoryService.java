package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmAudit;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * A record's History tab.
 *
 * <p>Thin on purpose — the interesting decisions are all about what this <em>cannot</em> show.
 *
 * <h2>Baselines are not here, and that is not an omission to fix later</h2>
 *
 * <p>ALM's History tab has two halves: Baselines and Audit Log. Only the second is reachable.
 * Baselines live behind the library/baseline API, which probe 12 established is
 * <strong>OTA-only</strong> — doc-host probes for {@code libraries}, {@code baselines} and every
 * {@code vc-*} variant returned 404, and the 1,111-operation resource-list inventory has zero hits.
 * It is not a matter of finding the right path; there is no documented REST surface, and inventing
 * one would violate the project's documented-API-only constraint.
 *
 * <p>So the SPA renders a History tab with the Audit Log in it and no Baselines sub-tab at all,
 * which is the honest shape: an absent capability that stays absent reads better than a tab that is
 * permanently empty for a reason the user cannot see.
 */
@Service
public class HistoryService {

    private final AlmEntityClient entities;
    private final AlmAccessPolicy policy;

    public HistoryService(AlmEntityClient entities, AlmAccessPolicy policy) {
        this.entities = entities;
        this.policy = policy;
    }

    /**
     * The recorded change history of one record, most recent first.
     *
     * @param collection must be a browsable module — audits are a sub-resource of a record, and a
     *                   link row has no history worth showing
     */
    public HistoryDto.History history(AlmProjectRef project, String collection, String id) {
        policy.checkRead(project);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (!AlmCollections.isModule(collection)) {
            throw new IllegalArgumentException(
                    "history is only available for a record, not for '" + collection + "'");
        }

        List<HistoryDto.Entry> entries = entities.audits(project, collection, id).stream()
                // Newest first. ALM's own order is oldest-first, and a History tab that opens on a
                // 2019 edit makes the reader scroll to find out what just happened.
                .sorted(Comparator.comparing(AlmAudit::time).reversed())
                .map(HistoryService::toEntry)
                .toList();

        // Hardcoded true, and honestly so: every ALM probed under-records. See HistoryDto.History.
        return new HistoryDto.History(collection, id, entries, true);
    }

    private static HistoryDto.Entry toEntry(AlmAudit audit) {
        return new HistoryDto.Entry(
                audit.id(),
                audit.action(),
                audit.time(),
                audit.user(),
                audit.changes().stream()
                        .map(c -> new HistoryDto.Change(
                                c.field(), c.label(), c.oldValue(), c.newValue()))
                        .toList());
    }
}
