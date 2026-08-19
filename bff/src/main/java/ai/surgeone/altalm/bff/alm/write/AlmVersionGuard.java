package ai.surgeone.altalm.bff.alm.write;

import java.util.Optional;

/**
 * The lost-update check, in the one place both write paths can reach it.
 *
 * <p>⚠️ <strong>This is detection, not locking, and the difference is not pedantry.</strong> Probe 31
 * established ALM has no optimistic concurrency control: {@code ver-stamp} increments on every write,
 * including memo writes, but the server <em>accepts a stale one and lets the write land</em>. There is
 * no header, no field and no parameter that makes ALM refuse a conflicting update. Last-writer-wins is
 * the server's behaviour and cannot be turned off.
 *
 * <p>What is left is a reliable change <em>detector</em>: re-read the record immediately before the
 * write and refuse when its stamp moved. That converts "silent overwrite, always" into "refused in
 * all but a very short window" — the window between this check and the request reaching the server.
 * <strong>A write landing inside that window is still lost.</strong> Never describe this as safe.
 *
 * <p>Extracted from {@code AlmCommentWriter} when the record CRUD endpoints needed the same rule.
 * A probe-derived safety rule implemented twice is one that will eventually be implemented
 * differently, and the version that drifts is the one nobody is looking at.
 */
public final class AlmVersionGuard {

    /** Raised when the record changed between the caller reading it and this write going out. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private AlmVersionGuard() {
    }

    /**
     * Refuses a write whose record moved since the caller read it.
     *
     * @param expected the {@code ver-stamp} the caller's view was built on. ⚠️ Empty means "I accept
     *                 overwriting a concurrent edit", <em>not</em> "there is no concurrency" — it is
     *                 an {@link Optional} at every call site so that choice stays visible rather than
     *                 becoming a null nobody notices
     * @param actual   the stamp read back immediately before the write
     * @throws ConflictException when the two differ
     */
    public static void check(Optional<String> expected, String actual) {
        expected.filter(e -> !e.isBlank()).ifPresent(e -> {
            if (!e.equals(actual)) {
                throw new ConflictException(
                        "the record changed since it was read (expected ver-stamp " + e
                                + ", found " + actual + "). Re-read and re-apply the change — "
                                + "writing now would overwrite whatever landed in between.");
            }
        });
    }
}
