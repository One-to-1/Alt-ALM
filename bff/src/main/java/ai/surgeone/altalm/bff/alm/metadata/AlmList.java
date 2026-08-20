package ai.surgeone.altalm.bff.alm.metadata;

import java.util.List;

/**
 * One ALM lookup list and the values it permits.
 *
 * <p>⚠️ <strong>Instance-specific: never hardcode an id.</strong> Lists are per-project
 * customization (ADR 0005) and the ids differ between projects. The sandbox has 39 lists bound to
 * fields, out of 43 defined — the four unbound ones exist but no field uses them.
 *
 * <p>Every {@link AlmFieldType#LOOKUP_LIST} field carries a {@code listId} pointing here, and that
 * includes every Y/N flag in the model: <strong>ALM has no Boolean type</strong>, so "does this
 * requirement have children" is a two-item list like any other.
 *
 * @param id      the list id a field's {@code listId} refers to
 * @param name    display name, e.g. {@code Requirement Status}
 * @param items   permitted values in server order. ⚠️ May be <strong>empty</strong> — three of the
 *                sandbox's 39 lists have no items at all, so an empty list is a real answer and not
 *                a failed parse. A field bound to one accepts nothing, which is worth showing as an
 *                empty dropdown rather than as free text
 */
public record AlmList(int id, String name, List<AlmListItem> items) {

    public AlmList {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * One permitted value.
     *
     * @param value       what is stored and what a filter must match — the thing a write sends
     * @param logicalName ALM's stable identifier, e.g. {@code hp.qc.review-status.reviewed}. Kept
     *                    because it survives a value being renamed, which {@code value} does not
     */
    public record AlmListItem(String value, String logicalName) {
    }

    /** Whether a value is one this list permits. Exact match: ALM's stored values are literal. */
    public boolean permits(String value) {
        return items.stream().anyMatch(i -> i.value().equals(value));
    }

    /** The permitted values, for a dropdown or an error message. */
    public List<String> values() {
        return items.stream().map(AlmListItem::value).toList();
    }
}
