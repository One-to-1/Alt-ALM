package ai.surgeone.altalm.bff.alm.read;

import java.util.List;

/**
 * One entry in a record's change history.
 *
 * <p>⚠️ <strong>ALM's audit trail is genuinely partial, and this record cannot make it complete.</strong>
 * Probe 24 read the history of 119 records across three entities in a live project: <strong>678
 * entries, every single one {@code UPDATE}</strong>. Not one {@code CREATE}, not one {@code DELETE},
 * and only 12 distinct fields ever changed — none of them a memo. That reproduces, at scale, what
 * probe 4 saw once on a single probe record: creates and rich-text edits leave no trace.
 *
 * <p>So a History tab built on this must say what it is showing rather than implying completeness.
 * "No history" means "ALM recorded no field changes", never "nothing happened to this record".
 *
 * @param id      ALM's own audit id
 * @param action  always {@code UPDATE} in everything probed; kept because the field exists and a
 *                future ALM release could start populating the others
 * @param time    {@code yyyy-MM-dd HH:mm:ss}, no timezone offset — probe 24 found that format in all
 *                678 entries. Rendered as sent rather than reformatted: without an offset, any
 *                conversion to local time would be a guess about the server's zone
 * @param user    the ALM username that made the change
 * @param changes the per-field before/after rows, which may be <strong>empty</strong> — 85 of 678
 *                entries carried no properties at all
 */
public record AlmAudit(String id, String action, String time, String user, List<Change> changes) {

    public AlmAudit {
        changes = List.copyOf(changes);
    }

    /**
     * One field's before and after.
     *
     * @param field    the physical field name, e.g. {@code status}
     * @param label    ALM's own caption for it in this project — per-project customization, so this
     *                 is preferred over anything derived from the name
     * @param oldValue value before the change; may be empty when the field had none
     * @param newValue value after
     */
    public record Change(String field, String label, String oldValue, String newValue) {
    }
}
