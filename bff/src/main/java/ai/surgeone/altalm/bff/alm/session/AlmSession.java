package ai.surgeone.altalm.bff.alm.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One authenticated ALM REST session: its cookie set plus the XSRF token lifted out of it.
 *
 * <p>Probe 10 established that a single API key holds <strong>at least 50</strong> of these
 * simultaneously with no eviction, and that {@code JSESSIONID}, {@code LWSSO_COOKIE_KEY},
 * {@code QCSession} and {@code XSRF-TOKEN} are each unique per session (only {@code ALM_USER} is
 * shared). That is what makes pooling safe: sessions are genuinely independent, not aliases of one
 * server-side session.
 *
 * <p>Instances are immutable apart from {@link #touch()}, which records use for idle tracking.
 */
public final class AlmSession {

    /** Server default for {@code REST_SESSION_MAX_IDLE_TIME}. */
    public static final Duration DEFAULT_MAX_IDLE = Duration.ofMinutes(60);

    private final Map<String, String> cookies;
    private final String xsrfToken;
    private final Instant createdAt;
    private volatile Instant lastUsedAt;

    public AlmSession(Map<String, String> cookies, String xsrfToken, Instant createdAt) {
        this.cookies = Map.copyOf(Objects.requireNonNull(cookies, "cookies"));
        // Every non-GET needs this header; without it ALM returns 401 (Probe 4).
        this.xsrfToken = Objects.requireNonNull(xsrfToken, "xsrfToken");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastUsedAt = createdAt;
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public String xsrfToken() {
        return xsrfToken;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    /** Serializes the cookie set into a {@code Cookie} header value. */
    public String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    public void touch() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * Whether the server has probably dropped this session.
     *
     * <p>"Probably" is honest: idle timeout is a server-side setting we cannot read back (site-params
     * returns 403 even holding Customer Admin — Probe 11), so this is a client-side estimate. Callers
     * must still handle a 401 on a session this method considers live.
     */
    public boolean isLikelyExpired(Duration maxIdle, Instant now) {
        return lastUsedAt.plus(maxIdle).isBefore(now);
    }

    @Override
    public String toString() {
        // Never render cookie values - they are session credentials.
        return "AlmSession[cookies=" + cookies.keySet() + ", createdAt=" + createdAt
                + ", lastUsedAt=" + lastUsedAt + "]";
    }
}
