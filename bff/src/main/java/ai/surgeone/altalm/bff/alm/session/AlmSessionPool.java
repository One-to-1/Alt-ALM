package ai.surgeone.altalm.bff.alm.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A bounded pool of ALM sessions belonging to one service-account API key (ADR 0004).
 *
 * <p>Probe 10 opened 50 concurrent sessions on one key with zero evictions and no cap reached, so
 * <strong>the pool bound here is our own politeness limit, not a server constraint</strong>. It
 * exists to keep our footprint predictable and to bound keepalive cost, not because ALM pushes back.
 *
 * <p>Sessions are borrowed and returned. A borrowed session that has gone idle past
 * {@code maxIdle} is discarded and replaced rather than handed out, because the server may have
 * dropped it — see {@link AlmSession#isLikelyExpired}.
 */
public final class AlmSessionPool implements AutoCloseable {

    private final BlockingQueue<AlmSession> idle;
    private final Supplier<AlmSession> factory;
    private final java.util.function.Consumer<AlmSession> closer;
    private final Duration maxIdle;
    private final int maxSize;
    private final AtomicInteger live = new AtomicInteger();
    private volatile boolean closed;

    /**
     * @param maxSize  pool bound; a politeness limit, see class docs
     * @param maxIdle  discard-and-replace threshold, normally {@link AlmSession#DEFAULT_MAX_IDLE}
     * @param factory  opens a new authenticated session
     * @param closer   logs a session out; failures here must not propagate
     */
    public AlmSessionPool(int maxSize, Duration maxIdle, Supplier<AlmSession> factory,
                          java.util.function.Consumer<AlmSession> closer) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.maxSize = maxSize;
        this.maxIdle = maxIdle;
        this.factory = factory;
        this.closer = closer;
        this.idle = new ArrayBlockingQueue<>(maxSize);
    }

    /**
     * Borrows a session, opening one if the pool is under its bound.
     *
     * @param timeout how long to wait when the pool is saturated
     * @throws IllegalStateException if the pool is closed, or no session becomes available in time
     */
    public AlmSession borrow(Duration timeout) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("session pool is closed");
        }
        // Prefer a pooled session, discarding any that have gone stale.
        AlmSession pooled;
        while ((pooled = idle.poll()) != null) {
            if (pooled.isLikelyExpired(maxIdle, Instant.now())) {
                discard(pooled);
            } else {
                pooled.touch();
                return pooled;
            }
        }
        // Room to grow?
        if (live.get() < maxSize) {
            // Reserve the slot before the (slow) login so concurrent borrowers cannot overshoot.
            int reserved = live.incrementAndGet();
            if (reserved <= maxSize) {
                try {
                    AlmSession fresh = factory.get();
                    fresh.touch();
                    return fresh;
                } catch (RuntimeException e) {
                    live.decrementAndGet();
                    throw e;
                }
            }
            live.decrementAndGet();
        }
        AlmSession waited = idle.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (waited == null) {
            throw new IllegalStateException(
                    "no ALM session available within " + timeout + " (pool size " + maxSize + ")");
        }
        waited.touch();
        return waited;
    }

    /** Returns a session for reuse. A session that no longer fits the pool is logged out. */
    public void release(AlmSession session) {
        if (session == null) {
            return;
        }
        session.touch();
        if (closed || !idle.offer(session)) {
            discard(session);
        }
    }

    /**
     * Drops a session already known to be dead — normally one that failed its keepalive.
     *
     * <p>Only acts if the session is still sitting idle here. A session that has since been borrowed
     * is the borrower's to return, and freeing its slot from underneath them would let the pool
     * exceed its bound.
     *
     * @return true if it was removed
     */
    public boolean evict(AlmSession session) {
        if (idle.remove(session)) {
            discard(session);
            return true;
        }
        return false;
    }

    /** Drops a session and its slot, logging it out best-effort. */
    private void discard(AlmSession session) {
        live.decrementAndGet();
        try {
            closer.accept(session);
        } catch (RuntimeException ignored) {
            // Logout failure must never break the caller's path; the server will time it out.
        }
    }

    /** Sessions currently open (idle plus borrowed). */
    public int liveCount() {
        return live.get();
    }

    /** Sessions sitting idle in the pool. */
    public int idleCount() {
        return idle.size();
    }

    /**
     * Refreshes idle sessions so they do not cross the server's idle timeout.
     *
     * <p>Intended to run on a schedule. Returns the sessions that should be pinged; the caller
     * issues the actual keepalive request, keeping this class free of HTTP.
     */
    public List<AlmSession> sessionsNeedingKeepalive(Duration refreshBefore, Instant now) {
        List<AlmSession> due = new ArrayList<>();
        for (AlmSession s : idle) {
            if (s.lastUsedAt().plus(maxIdle.minus(refreshBefore)).isBefore(now)) {
                due.add(s);
            }
        }
        return due;
    }

    @Override
    public void close() {
        closed = true;
        AlmSession s;
        while ((s = idle.poll()) != null) {
            discard(s);
        }
    }
}
