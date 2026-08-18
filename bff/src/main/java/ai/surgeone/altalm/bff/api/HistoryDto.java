package ai.surgeone.altalm.bff.api;

import java.util.List;

/** Wire shapes for a record's change history. */
public final class HistoryDto {

    private HistoryDto() {
    }

    /**
     * One recorded change event.
     *
     * @param time  {@code yyyy-MM-dd HH:mm:ss} as ALM sent it. Deliberately not an instant: the
     *              payload carries no timezone offset, so anything more precise would be a guess
     *              about where the server is
     * @param changes may be empty — ALM records some events without recording what they altered
     */
    public record Entry(String id, String action, String time, String user, List<Change> changes) {
    }

    /** One field's before and after. {@code label} is this project's own caption for the field. */
    public record Change(String field, String label, String oldValue, String newValue) {
    }

    /**
     * A record's history, with the honesty flag the UI needs.
     *
     * @param entries most recent first
     * @param partial always {@code true} against every ALM probed, and the reason the UI must not
     *                render an empty list as "nothing has happened to this record". Probe 24 read 678
     *                entries across 119 records in a live project: <strong>every one was
     *                {@code UPDATE}</strong>, spanning only 12 distinct fields and no memo field at
     *                all. Creates and rich-text edits are simply not recorded. It is a field rather
     *                than a constant in the SPA so that an ALM release which starts recording them
     *                can turn the caveat off from the side that can actually tell
     */
    public record History(String collection, String id, List<Entry> entries, boolean partial) {
    }
}
