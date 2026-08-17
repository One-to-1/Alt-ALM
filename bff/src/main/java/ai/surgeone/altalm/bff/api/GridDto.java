package ai.surgeone.altalm.bff.api;

import java.util.List;
import java.util.Map;

/**
 * The BFF's own grid contract. <strong>The SPA never sees ALM's wire format.</strong>
 *
 * <p>That is the point of ADR 0001: the browser gets flat rows and explicit column descriptors
 * instead of the {@code Fields}/{@code values} envelope, the dual query grammars, and the traps
 * below. Every ALM-specific quirk is resolved here, once, rather than in each consumer.
 */
public final class GridDto {

    private GridDto() {
    }

    /**
     * One column, derived from field metadata at runtime — never a hardcoded schema (ADR 0005).
     *
     * @param name    wire field name, e.g. {@code parent-id}
     * @param label   display label from metadata
     * @param type    one of the eight ALM field types, as a stable string for the renderer registry
     * @param listId  lookup-list binding for {@code LOOKUP_LIST} columns, 0 when unbound.
     *                Instance-specific — the SPA resolves values through the API, never a constant.
     * @param multiValue true for the two multivalue fields that exist in the whole model
     * @param onDetailsForm ALM's own Details form would <em>probably</em> render this field —
     *                      {@code active && visibleInWebUI}. ⚠️ An approximation, not a derivation:
     *                      probe 21 matched 16 of 17 fields against the stock client and was wrong
     *                      in both directions. The real layout is not exposed by any documented API
     * @param riskGroup     the field belongs to ALM's built-in Risk Analysis group
     *                      ({@code active && !visibleInWebUI}) — exactly 25 fields in every project
     *                      probed
     */
    public record Column(String name, String label, String type, int listId, boolean multiValue,
                         boolean onDetailsForm, boolean riskGroup) {

        /**
         * The one mapping from field metadata to a column, shared by the grid and the tree-grid —
         * the two views must not disagree about what a field is called, what type it is, or whether
         * ALM would show it.
         */
        public static Column of(ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor f) {
            return new Column(f.name(), f.label(), f.type().name(), f.listId(),
                    f.supportsMultivalue(), f.onDetailsForm(), f.inRiskAnalysisGroup());
        }
    }

    /**
     * One row. Values are lists because ALM's {@code values} is always an array on the wire, even
     * though almost every field is single-valued — flattening to a scalar here would silently drop
     * data on the two fields that are not.
     *
     * @param id     the row's ALM id, lifted out for convenience
     * @param values field name → its values, in metadata order
     * @param childCount for tree entities: how many children this node has, 0 otherwise
     * @param error  non-null when ALM marked this row as failed. ⚠️ UNVERIFIED shape — no probe has
     *               yet produced a failed row (live-probe-log open item #12), so do not build UI
     *               that depends on the text
     */
    public record Row(String id, Map<String, List<String>> values, int childCount, String error) {
    }

    /**
     * A page of grid data.
     *
     * <p>⚠️ <strong>There is deliberately no {@code total} field.</strong> ALM's {@code TotalResults}
     * describes the page, not the collection: probe 15 saw {@code TotalResults=0} returned for a
     * collection holding rows, simply because {@code page-size=0} was requested. Exposing it as a
     * "total" would have every consumer render a wrong count and, worse, conclude a populated
     * collection was empty. So the honest fields are what we actually know.
     *
     * @param rowsReturned  how many rows are in this response — a fact
     * @param reportedTotal ALM's raw {@code TotalResults}, named to discourage misuse
     * @param mayHaveMore   true when this page came back full, so more rows probably exist.
     *                      ⚠️ Not a guarantee: whether an over-cap {@code page-size} is silently
     *                      clamped at 2,000 is still UNVERIFIED (Q45), so a full page is evidence,
     *                      not proof
     */
    public record Page(int rowsReturned, int reportedTotal, boolean mayHaveMore) {
    }

    /**
     * A complete grid response.
     *
     * @param collection  the ALM collection read
     * @param writable    whether this project accepts writes — false for every project but the
     *                    sandbox, so the UI can hide affordances it must not offer
     * @param columns     metadata-derived column descriptors
     * @param rows        the page's rows
     * @param page        paging facts
     */
    public record Grid(String collection, boolean writable, List<Column> columns, List<Row> rows,
                       Page page) {
    }
}
