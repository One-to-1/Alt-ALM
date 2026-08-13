package ai.surgeone.altalm.bff.alm.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pool behaviour, exercised with a fake session factory - no server, no credentials. */
class AlmSessionPoolTest {

    private static AlmSession session(Instant at) {
        return new AlmSession(Map.of("QCSession", "q", "LWSSO_COOKIE_KEY", "l"), "xsrf", at);
    }

    private static AlmSessionPool pool(int max, Duration maxIdle,
                                       AtomicInteger opened, List<AlmSession> closed) {
        return new AlmSessionPool(max, maxIdle,
                () -> { opened.incrementAndGet(); return session(Instant.now()); },
                closed::add);
    }

    @Test
    @DisplayName("reuses a returned session instead of opening another")
    void reusesReleasedSession() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        try (AlmSessionPool p = pool(4, Duration.ofMinutes(60), opened, new CopyOnWriteArrayList<>())) {
            AlmSession first = p.borrow(Duration.ofSeconds(1));
            p.release(first);
            AlmSession second = p.borrow(Duration.ofSeconds(1));

            assertThat(second).isSameAs(first);
            assertThat(opened.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("grows to the bound, then refuses rather than overshooting")
    void respectsBound() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        try (AlmSessionPool p = pool(2, Duration.ofMinutes(60), opened, new CopyOnWriteArrayList<>())) {
            p.borrow(Duration.ofMillis(50));
            p.borrow(Duration.ofMillis(50));

            assertThat(p.liveCount()).isEqualTo(2);
            assertThatThrownBy(() -> p.borrow(Duration.ofMillis(50)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no ALM session available");
            // The bound is our politeness limit, not a server cap: probe 10 held 50 concurrently.
            assertThat(opened.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("discards an idle-expired session and replaces it")
    void discardsExpiredOnBorrow() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        List<AlmSession> closed = new CopyOnWriteArrayList<>();
        // Zero idle allowance: anything returned is immediately considered stale.
        try (AlmSessionPool p = pool(3, Duration.ZERO, opened, closed)) {
            AlmSession first = p.borrow(Duration.ofSeconds(1));
            p.release(first);
            Thread.sleep(5);

            AlmSession second = p.borrow(Duration.ofSeconds(1));

            assertThat(second).isNotSameAs(first);
            assertThat(closed).contains(first);
            assertThat(opened.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a failed login frees its reserved slot")
    void failedLoginDoesNotLeakSlot() {
        AlmSessionPool p = new AlmSessionPool(1, Duration.ofMinutes(60),
                () -> { throw new IllegalStateException("login refused"); }, s -> { });

        assertThatThrownBy(() -> p.borrow(Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.liveCount()).isZero();
        p.close();
    }

    @Test
    @DisplayName("close logs out everything it holds")
    void closeLogsOut() throws Exception {
        List<AlmSession> closed = new CopyOnWriteArrayList<>();
        AlmSessionPool p = pool(3, Duration.ofMinutes(60), new AtomicInteger(), closed);
        AlmSession a = p.borrow(Duration.ofSeconds(1));
        p.release(a);

        p.close();

        assertThat(closed).contains(a);
        assertThatThrownBy(() -> p.borrow(Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("keepalive is due before the server's idle timeout, not after")
    void keepaliveDueBeforeTimeout() throws Exception {
        try (AlmSessionPool p = pool(2, Duration.ofMinutes(60), new AtomicInteger(),
                new CopyOnWriteArrayList<>())) {
            AlmSession s = p.borrow(Duration.ofSeconds(1));
            p.release(s);

            // Nothing due right after use.
            assertThat(p.sessionsNeedingKeepalive(Duration.ofMinutes(10), Instant.now())).isEmpty();
            // 55 minutes on, with a 10-minute safety margin, it is due.
            assertThat(p.sessionsNeedingKeepalive(
                    Duration.ofMinutes(10), Instant.now().plus(Duration.ofMinutes(55))))
                    .containsExactly(s);
        }
    }

    @Test
    @DisplayName("toString never leaks cookie values")
    void toStringHidesCredentials() {
        AlmSession s = session(Instant.now());
        assertThat(s.toString()).doesNotContain("xsrf").doesNotContain("=q").doesNotContain("=l");
        assertThat(s.cookieHeader()).contains("QCSession=q");
    }
}
