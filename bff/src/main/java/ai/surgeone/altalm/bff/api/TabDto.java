package ai.surgeone.altalm.bff.api;

import java.util.List;
import java.util.Map;

/** Wire shapes for the detail pane's related-entity tab strip. */
public final class TabDto {

    private TabDto() {
    }

    /**
     * One related-entity tab.
     *
     * @param key        stable id, used to request the tab's rows. Derived from the entities
     *                   involved rather than the label, because labels are per-project customization
     *                   and a label change must not invalidate a bookmark
     * @param label      this project's own caption — probe 21.6 verified it is the stock client's
     * @param collection the collection the rows come from, for the UI to name honestly
     * @param attachment whether this is the attachments tab, which needs its own rendering
     * @param relations  the ALM relation names merged into this tab. Exposed because the reduction
     *                   is an approximation: when a tab shows something unexpected, the relation
     *                   names are the first thing anyone will want
     */
    public record Tab(String key, String label, String collection, boolean attachment,
                      List<String> relations) {
    }

    /**
     * The whole strip for one entity.
     *
     * @param dropped candidate relations that did not become tabs, each with the rule that discarded
     *                it. Shipped to the client on purpose — "why is there no Business Models Linkage
     *                tab" is a question this answers without anyone re-running a probe, and the
     *                answer ({@code bpm-links} 404s) is one nobody would guess
     */
    public record Strip(String collection, List<Tab> tabs, Map<String, String> dropped) {
    }
}
