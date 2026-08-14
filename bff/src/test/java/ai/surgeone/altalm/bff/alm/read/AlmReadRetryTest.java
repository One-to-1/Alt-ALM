package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the read-retry policy introduced for Q46 (probe 16 §16.2). */
class AlmReadRetryTest {

    /** No real sleeping — the backoff is asserted, not waited on. */
    private final AtomicInteger slept = new AtomicInteger();
    private final AlmReadRetry retry =
            new AlmReadRetry(3, Duration.ofMillis(10), d -> slept.incrementAndGet());

    @Test
    @DisplayName("a first-attempt success does not retry or sleep")
    void happyPath() {
        AtomicInteger calls = new AtomicInteger();
        String out = retry.call(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(out).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(slept).hasValue(0);
    }

    @Test
    @DisplayName("the observed failure mode: one 500 then success — exactly probe 16's shape")
    void recoversFromASingleBlink() {
        AtomicInteger calls = new AtomicInteger();
        String out = retry.call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new AlmReadRetry.Transient5xx(500);
            }
            return "recovered";
        });

        assertThat(out).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
        assertThat(slept).hasValue(1);
    }

    @Test
    @DisplayName("gives up after maxAttempts and reports the last status")
    void exhaustsAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.call(() -> {
            calls.incrementAndGet();
            throw new AlmReadRetry.Transient5xx(503);
        }))
                .isInstanceOf(AlmReadRetry.ReadFailedException.class)
                .hasMessageContaining("503")
                .extracting(e -> ((AlmReadRetry.ReadFailedException) e).status()).isEqualTo(503);

        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("a non-5xx failure is NOT retried — repeating a rejected request cannot help")
    void doesNotRetryOtherFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.call(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("400 bad query");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).hasValue(1);
        assertThat(slept).hasValue(0);
    }

    @Test
    @DisplayName("maxAttempts=1 disables retry entirely")
    void singleAttemptIsAllowed() {
        AtomicInteger calls = new AtomicInteger();
        AlmReadRetry once = new AlmReadRetry(1, Duration.ZERO, d -> slept.incrementAndGet());

        assertThatThrownBy(() -> once.call(() -> {
            calls.incrementAndGet();
            throw new AlmReadRetry.Transient5xx(500);
        })).isInstanceOf(AlmReadRetry.ReadFailedException.class);

        assertThat(calls).hasValue(1);
        assertThat(slept).hasValue(0);
    }

    @Test
    @DisplayName("maxAttempts below 1 is rejected at construction")
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new AlmReadRetry(0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
