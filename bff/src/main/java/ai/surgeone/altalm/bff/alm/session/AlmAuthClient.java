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
 *       and returns 406 for JSON. <strong>It answers a narrower question than it looks like it
 *       does</strong> — see {@link #keepalive}.</li>
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

    /**
     * Opens a session: one-step login, then {@code site-session} to establish the project context.
     *
     * <p><strong>Both responses contribute cookies, and the second set wins.</strong> Probe 13 caught
     * this the hard way: building the session from the login response alone and then firing
     * {@code POST site-session} leaves us holding the pre-site-session {@code QCSession} while the
     * server has moved on. The symptom is nasty — calls keep working (project reads authenticate off
     * LWSSO regardless), so the mismatch stays invisible until session teardown silently targets a
     * different session than the one we hold.
     *
     * <p>Probe 13 also found {@code POST site-session} is <em>redundant</em> after
     * {@code oauth2/login} — a project read succeeds without it. It is kept because it is the
     * documented flow and costs one call at session open; the merge above is what makes it safe.
     */
    public AlmSession login() {
        ResponseEntity<Void> login = http.post()
                .uri(credentials.baseUrl() + "/rest/oauth2/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clientId", credentials.apiKey(), "secret", credentials.apiSecret()))
                .retrieve()
                .toBodilessEntity();

        Map<String, String> cookies = new LinkedHashMap<>(extractCookies(login.getHeaders()));
        String xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf == null) {
            // Without this every subsequent write would 401 with an unhelpful message; fail here
            // instead, where the cause is obvious.
            throw new IllegalStateException(
                    "login succeeded but returned no XSRF-TOKEN cookie; cannot perform writes");
        }

        ResponseEntity<Void> siteSession = http.post()
                .uri(credentials.baseUrl() + "/rest/site-session")
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .header("X-XSRF-TOKEN", xsrf)
                .retrieve()
                .toBodilessEntity();
        cookies.putAll(extractCookies(siteSession.getHeaders()));

        return new AlmSession(cookies, cookies.get("XSRF-TOKEN"), Instant.now());
    }

    /** Cookie header for a raw map, needed before an {@link AlmSession} exists to build it. */
    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /**
     * Refreshes a session's idle timer.
     *
     * <p>{@code GET site-session} is the documented keepalive. Returns false rather than throwing
     * when the session has already lapsed, so the pool can quietly replace it.
     *
     * <p><strong>Use this, not {@code is-authenticated}, to decide whether a pooled session is
     * usable.</strong> Probe 13 found a state where {@code /v2/rest/is-authenticated} returns 200
     * while every project-scoped call returns 401: the LWSSO token is still valid but the site
     * session behind it is gone. {@code is-authenticated} reports on the former, and this method on
     * the latter, which is the one that determines whether a request will actually work.
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

    /**
     * Tears a session down completely. Best-effort: failures are swallowed, since the server times
     * sessions out regardless and a failed logout must not break the caller's path.
     *
     * <p><strong>Two calls are required, and the second one is easy to miss</strong> (Probe 13).
     * {@code DELETE site-session} ends only the <em>project</em> session: project-scoped reads start
     * returning 401, but the LWSSO authentication survives it and
     * {@code /v2/rest/is-authenticated} keeps answering 200. A client that stops there leaks one
     * authenticated identity per session — which for a pool of them is the whole pool.
     * {@code POST authentication-point/logout} is what actually ends the authentication.
     *
     * <p>Both calls carry {@code X-XSRF-TOKEN}. That is not decoration: the logout endpoint sits
     * behind the same XSRF gate as every other non-GET, and without the header it returns 401 and
     * silently does nothing (Probe 13, case B). {@code GET} logout also works on our sandbox, but
     * OpenText disabled GET-logout by default in 24.1, so POST is the portable choice.
     */
    public void logout(AlmSession session) {
        endpoint("/rest/site-session", session, true);
        endpoint("/authentication-point/logout", session, false);
    }

    /** Issues one teardown call, swallowing whatever it throws. */
    private void endpoint(String path, AlmSession session, boolean delete) {
        try {
            RestClient.RequestHeadersSpec<?> spec = delete
                    ? http.delete().uri(credentials.baseUrl() + path)
                    : http.post().uri(credentials.baseUrl() + path);
            spec.header(HttpHeaders.COOKIE, session.cookieHeader())
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
