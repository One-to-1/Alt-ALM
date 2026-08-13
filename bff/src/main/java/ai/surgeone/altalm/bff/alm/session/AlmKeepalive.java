package ai.surgeone.altalm.bff.alm.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Pings idle sessions before ALM's idle timeout can drop them.
 *
 * <p>{@link AlmSessionPool} deliberately owns no HTTP — it can say <em>which</em> sessions are due
 * but not refresh them. This class is the other half, and keeping them apart is what lets the pool's
 * eviction and timing logic be unit-tested with a fake factory and no server.
 *
 * <p>A session that fails its keepalive is dropped rather than retried. It is almost certainly gone
 * server-side, and the pool will open a replacement on the next borrow.
 */
public final class AlmKeepalive {

    private static final Logger log = LoggerFactory.getLogger(AlmKeepalive.class);

    private final AlmSessionPool pool;
    private final AlmAuthClient auth;
    private final Duration margin;

    public AlmKeepalive(AlmSessionPool pool, AlmAuthClient auth, Duration margin) {
        this.pool = pool;
        this.auth = auth;
        this.margin = margin;
    }

    /**
     * Refreshes every session within {@code margin} of expiry.
     *
     * @return how many were successfully refreshed
     */
    public int sweep() {
        List<AlmSession> due = pool.sessionsNeedingKeepalive(margin, Instant.now());
        int refreshed = 0;
        for (AlmSession session : due) {
            if (auth.keepalive(session)) {
                refreshed++;
            } else {
                // Nothing to log about the session itself - its toString is cookie-name-only by
                // design, and there is no useful identifier that is safe to print.
                log.info("dropping an ALM session that failed keepalive; it will be reopened on demand");
                pool.evict(session);
            }
        }
        if (refreshed > 0) {
            log.debug("refreshed {} idle ALM session(s)", refreshed);
        }
        return refreshed;
    }
}
