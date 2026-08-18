package ai.surgeone.altalm.bff.api;

import java.util.List;
import java.util.Map;
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
                      List<Table> tables, List<String> relations) {
    }

    /**
     * One grid within a tab — ALM's Requirement Traceability holds two ("Trace From", "Trace To").
     *
     * @param targetEntity   the entity a row reaches
     * @param targetCollection the module to open when a row is followed, or empty when this build
     *                       has no module for that entity
     * @param navigable      whether rows carry a far-end id at all. False for a plain reference
     *                       relation, which names only the column pointing back at the open record
     * @param scopeField     the column on {@code targetEntity} holding the open record's id — the
     *                       one clause that turns "all test instances" into "this set's instances".
     *                       Empty when the relation does not name one
     * @param scopeFixed     any further clauses that do not depend on the open record, such as a
     *                       polymorphic join's type discriminator. Usually empty
     */
    public record Table(String key, String label, String targetEntity, String targetCollection,
                        boolean navigable, String scopeField, Map<String, String> scopeFixed) {

        /**
         * Normalised so a caller never has to null-check, and frozen because this record is handed
         * to Jackson and then read by request threads.
         */
        public Table {
            scopeField = scopeField == null ? "" : scopeField;
            scopeFixed = scopeFixed == null ? Map.of() : Map.copyOf(scopeFixed);
        }

        /**
         * Whether this table can be opened as a full grid rather than a strip inside the pane.
         *
         * <p>The distinction is not cosmetic. Without a {@code scopeField} the only way to show
         * these rows is the tab itself, because there is no filter that would select them — opening
         * a grid anyway would show the <em>whole</em> collection under a heading naming one record,
         * which is the most confidently wrong screen this app could draw.
         *
         * <p>⚠️ Deliberately says nothing about {@link #targetCollection}. That names the far end a
         * row <em>links to</em> — for a test set's instances it is {@code tests} — while a drill-in
         * opens the collection the rows themselves come from, which is the enclosing
         * {@link Tab#collection()}. Conflating the two opens the wrong module with a filter that
         * does not apply to it.
         */
        public boolean scopable() {
            return !scopeField.isEmpty();
        }
    }

    /**
     * A row's far end: what clicking its id opens.
     *
     * @param entity     wire entity name, e.g. {@code defect}
     * @param collection the module to open, e.g. {@code defects}
     * @param id         the far record's id
     * @param name       the far record's own name, or empty when it could not be resolved.
     *                   <p>⚠️ This is <strong>not</strong> in the link row — it is a second read.
     *                   ALM's own Linked Defects grid leads with "Defect: Summary" and its
     *                   Traceability grid with "Req: Name", and neither is a column of the join
     *                   table: {@code defect-link} carries {@code second-endpoint-name}, which from
     *                   a requirement's tab names <em>the requirement you are already looking at</em>,
     *                   not the defect. Without resolving the far end the most useful column in the
     *                   grid is either absent or, worse, quietly the wrong record's name
     */
    public record LinkTarget(String entity, String collection, String id, String name) {
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

    /**
     * One table's rows, with each row's far end resolved.
     *
     * @param grid    the rows, shaped exactly like a module grid so the SPA reuses its rendering
     * @param targets row id → where following that row leads. Absent for a row whose relation has no
     *                far-end column, which is why this is a map rather than a field on every row
     */
    public record TableRows(String tabKey, String tableKey, String label, GridDto.Grid grid,
                            Map<String, LinkTarget> targets) {
    }
}
