package ai.surgeone.altalm.bff.alm.write;

import java.util.List;
import java.util.Map;

/**
 * The lost-update check, in the one place both write paths can reach it.
 *
 * <p>⚠️ <strong>This is detection, not locking, and the difference is not pedantry.</strong> Probe 31
 * established ALM has no optimistic concurrency control: {@code ver-stamp} increments on every write,
 * including memo writes, but the server <em>accepts a stale one and lets the write land</em>. There is
 * no header, no field and no parameter that makes ALM refuse a conflicting update. Last-writer-wins is
 * the server's behaviour and cannot be turned off.
 *
 * <p>What is left is a change <em>detector</em>: re-read the record immediately before the write and
 * refuse when what we are about to replace is no longer what the caller saw. That converts "silent
 * overwrite, always" into "refused in all but a very short window" — the window between this check
 * and the request reaching the server. <strong>A write landing inside that window is still lost.</strong>
 * Never describe this as safe.
 *
 * <h2>⚠️ Why this compares FIELD VALUES and not {@code ver-stamp}</h2>
 *
 * <p>It used to compare {@code ver-stamp}, and that was wrong in a way only probe 34 made visible:
 * <strong>creating a child moves the parent's stamp.</strong> So opening a requirement, having anyone
 * add a sub-requirement underneath it, and saving produced "someone else changed this record" when
 * not one field on it differed. Confidently wrong, and wrong in the direction that trains people to
 * ignore the message.
 *
 * <p>A stamp answers "did anything about this row move", which is strictly more than the question
 * worth asking. ALM replaces <em>only the fields present in the body</em>, so the only writes this
 * can lose are ones to those fields — and comparing them directly refuses strictly less often while
 * protecting exactly as much. It is also immune to the A→B→A objection: if a field is back to what
 * the caller read, writing the caller's value over it loses nothing.
 *
 * <p>⚠️ The comparison is <em>read against read</em>, which is what makes it safe on memo fields.
 * {@link ai.surgeone.altalm.bff.api.RecordService}'s post-write verification has to exclude memos
 * because ALM re-serialises them on the way <em>in</em> (probe 27) — but both sides here came back
 * out of ALM, so an unchanged memo is byte-identical and a changed one is not.
 *
 * <p>Extracted from {@code AlmCommentWriter} when the record CRUD endpoints needed the same rule.
 * A probe-derived safety rule implemented twice is one that will eventually be implemented
 * differently, and the version that drifts is the one nobody is looking at.
 */
public final class AlmStaleWriteGuard {

    /** Raised when a field this write would replace changed between the caller's read and now. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private AlmStaleWriteGuard() {
    }

    /**
     * Refuses a write whose target fields moved since the caller read them.
     *
     * <p>Only the keys of {@code expected} are checked. A caller that sends no baseline gets no
     * check — that is "I accept overwriting a concurrent edit", <em>not</em> "there is no
     * concurrency", and it is spelled as an empty map at the call site so the choice stays visible.
     *
     * @param expected the values the caller's view was built on, keyed by logical field name.
     *                 ⚠️ Callers narrow this to the fields actually being written before calling:
     *                 guarding a field the write does not touch reintroduces exactly the false
     *                 conflict this class exists to remove
     * @param actual   the same fields as read back immediately before the write
     * @throws ConflictException naming the first field that differs
     */
    public static void check(Map<String, List<String>> expected, Map<String, List<String>> actual) {
        if (expected == null || expected.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String field = entry.getKey();
            List<String> was = normalise(entry.getValue());
            List<String> now = normalise(actual == null ? null : actual.get(field));
            if (!was.equals(now)) {
                // ⚠️ Names the field, never the values. The other person's text can be a
                // multi-kilobyte memo, and an error string is the wrong place for it — the caller's
                // next step is to re-read the record, where they will see it in context.
                throw new ConflictException(
                        "'" + field + "' changed since this record was read. Re-read and re-apply "
                                + "the change — writing now would overwrite whatever landed in "
                                + "between.");
            }
        }
    }

    /**
     * ⚠️ Trims, and treats absent as empty. Both matter: ALM pads some values, and a field that is
     * empty on the server comes back as no {@code values} entry rather than as one empty string —
     * so a caller whose baseline is {@code ""} would otherwise conflict against an untouched field.
     */
    private static List<String> normalise(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .map(v -> v == null ? "" : v.trim())
                        .filter(v -> !v.isEmpty())
                        .toList();
    }
}
