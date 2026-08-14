package ai.surgeone.altalm.bff.alm.read;

import java.time.Duration;

/**
 * Bounded retry for <strong>reads</strong> against a 5xx.
 *
 * <p>Why this exists, and why it is emphatically not the write rule: probe 16 saw a plain
 * {@code GET} on a populated collection return {@code HTTP 500} exactly once, then return 200
 * across 13 immediate follow-ups — five query variants, sibling collections, and the same
 * collection on all nine projects. The cause is {@code UNVERIFIED}; the pattern matches the
 * intermittency behind Q40 (a post-teardown read that answered 401 six times out of eight and 200
 * twice, hypothesised to be SaaS load-balancing across nodes that disagree).
 *
 * <p>One unreproduced failure is not enough to explain the server, but it is more than enough to
 * decide client behaviour: a grid that renders a hard error because one node blinked is worse than
 * one that quietly asks again.
 *
 * <p><strong>This must never be reused for writes.</strong> The project's write rule is the
 * opposite — {@code api-ref} §3.3: a 5xx on a write may have <em>committed the row</em>, so a write
 * that fails with 5xx is "unknown outcome, verify by query", never "retry". Retrying a write here
 * would manufacture duplicates, which is precisely the hazard {@code AlmWriteOutcome} exists to
 * prevent. Reads are safe to repeat only because they are side-effect free.
 */
public final class AlmReadRetry {

    /** Thrown when every attempt failed; carries the last status for the caller to surface. */
    public static class ReadFailedException extends RuntimeException {
        private final int status;

        public ReadFailedException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** A read attempt that reports its own HTTP status. */
    @FunctionalInterface
    public interface Attempt<T> {
        /** @return the parsed result, or throws {@link Transient5xx} to request another attempt */
        T run();
    }

    /** Signals a 5xx that the caller judged retryable. */
    public static class Transient5xx extends RuntimeException {
        private final int status;

        public Transient5xx(int status) {
            super("ALM returned HTTP " + status + " on a read");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final int maxAttempts;
    private final Duration backoff;
    private final Sleeper sleeper;

    /** Indirection so tests do not actually sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    public AlmReadRetry(int maxAttempts, Duration backoff) {
        this(maxAttempts, backoff, d -> Thread.sleep(d.toMillis()));
    }

    AlmReadRetry(int maxAttempts, Duration backoff, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff == null ? Duration.ZERO : backoff;
        this.sleeper = sleeper;
    }

    /**
     * Runs {@code attempt}, retrying only on {@link Transient5xx}.
     *
     * <p>Deliberately narrow: a 4xx is the server telling us the request is wrong, and repeating it
     * cannot help. Only 5xx — the class of failure actually observed to be transient — is retried.
     */
    public <T> T call(Attempt<T> attempt) {
        Transient5xx last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                return attempt.run();
            } catch (Transient5xx e) {
                last = e;
                if (i == maxAttempts) {
                    break;
                }
                try {
                    // Linear, not exponential: the observed failure recovered immediately, so the
                    // goal is to survive a blink, not to ride out a sustained outage.
                    sleeper.sleep(backoff.multipliedBy(i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ReadFailedException("interrupted while retrying a read", e.status(), ie);
                }
            }
        }
        throw new ReadFailedException(
                "read failed after " + maxAttempts + " attempts, last status "
                        + (last == null ? "unknown" : last.status()),
                last == null ? 0 : last.status(), last);
    }
}
