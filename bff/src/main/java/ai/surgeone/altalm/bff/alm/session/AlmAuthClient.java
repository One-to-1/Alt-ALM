package ai.surgeone.altalm.bff.alm.session;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ALM authentication handshake, probe-verified end to end.
 *
 * <p>Key findings this encodes:
 * <ul>
 *   <li>{@code POST /qcbin/rest/oauth2/login} with {@code {clientId, secret}} returns the
 *       <strong>full cookie set in one call</strong> — no separate authenticate step.</li>
 *   <li>{@code X-XSRF-TOKEN} is required on <strong>every</strong> non-GET; omitting it yields 401
 *       (Probe 4). Its value is the {@code XSRF-TOKEN} cookie.</li>
 *   <li>REST sessions consume <strong>no licence seat</strong>, and one key holds at least 50
 *       concurrently (Probe 10) — so opening a pool of them is safe.</li>
 *   <li>Session checks must use {@code /qcbin/v2/rest/is-authenticated}; the Core path is XML-only
 *       and returns 406 for JSON.</li>
 * </ul>
 */
public final class AlmAuthClient {

    /** Cookies that together constitute a session; ALM_USER is excluded (shared, not per-session). */
    private static final List<String> SESSION_COOKIES =
            List.of("JSESSIONID", "LWSSO_COOKIE_KEY", "QCSession", "XSRF-TOKEN");

    private final RestClient http;
    private final AlmCredentials credentials;

    public AlmAuthClient(RestClient http, AlmCredentials credentials) {
        this.http = http;
        this.credentials = credentials;
    }

    /** Opens a session: one-step login, then {@code site-session} to establish the project context. */
    public AlmSession login() {
        ResponseEntity<Void> login = http.post()
                .uri(credentials.baseUrl() + "/rest/oauth2/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clientId", credentials.apiKey(), "secret", credentials.apiSecret()))
                .retrieve()
                .toBodilessEntity();

        Map<String, String> cookies = extractCookies(login.getHeaders());
        String xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf == null) {
            // Without this every subsequent write would 401 with an unhelpful message; fail here
            // instead, where the cause is obvious.
            throw new IllegalStateException(
                    "login succeeded but returned no XSRF-TOKEN cookie; cannot perform writes");
        }

        AlmSession session = new AlmSession(cookies, xsrf, Instant.now());
        http.post()
                .uri(credentials.baseUrl() + "/rest/site-session")
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .header("X-XSRF-TOKEN", xsrf)
                .retrieve()
                .toBodilessEntity();
        return session;
    }

    /**
     * Refreshes a session's idle timer.
     *
     * <p>{@code GET site-session} is the documented keepalive. Returns false rather than throwing
     * when the session has already lapsed, so the pool can quietly replace it.
     */
    public boolean keepalive(AlmSession session) {
        try {
            http.get()
                    .uri(credentials.baseUrl() + "/rest/site-session")
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    .retrieve()
                    .toBodilessEntity();
            session.touch();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Best-effort logout. Failures are swallowed: the server times sessions out regardless. */
    public void logout(AlmSession session) {
        try {
            http.delete()
                    .uri(credentials.baseUrl() + "/rest/site-session")
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    .header("X-XSRF-TOKEN", session.xsrfToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ignored) {
            // Nothing useful to do; do not propagate into the caller's path.
        }
    }

    /** Parses Set-Cookie headers into the session cookie set. */
    private static Map<String, String> extractCookies(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return out;
        }
        for (String raw : setCookies) {
            int eq = raw.indexOf('=');
            int semi = raw.indexOf(';');
            if (eq <= 0) {
                continue;
            }
            String name = raw.substring(0, eq).trim();
            String value = raw.substring(eq + 1, semi < 0 ? raw.length() : semi).trim();
            if (SESSION_COOKIES.contains(name)) {
                out.put(name, value);
            }
        }
        return out;
    }
}
