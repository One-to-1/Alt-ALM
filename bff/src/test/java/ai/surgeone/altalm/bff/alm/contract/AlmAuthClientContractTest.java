package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.session.AlmAuthClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 exit criterion, live: <em>the BFF authenticates against the sandbox and holds a keepalive
 * session.</em>
 *
 * <p>Everything else in P0 is provable offline against fixtures. This is not — the whole point is that
 * {@link AlmAuthClient} talks to a real ALM server, so a fixture would only prove we can replay our own
 * assumptions. It therefore runs against the designated sandbox, is tagged {@code contract}, and stays
 * out of the default build.
 *
 * <p><strong>Read-only, with one deliberate exception.</strong> The only non-GET is a POST engineered
 * to be rejected at the XSRF gate. Probe 4 established that gate runs <em>before</em> entity
 * processing, so it carries no silent-commit risk — but "no risk" is a claim about a server we have
 * been wrong about before, so {@link #sweepAndLogout()} sweeps for the prefix afterwards and fails
 * loudly rather than trusting the reasoning.
 *
 * <p>Methods are ordered because they trace one session's lifecycle: open it, use it, refresh it, then
 * destroy it. Independent tests would mean six logins to assert six steps of one sequence.
 */
@Tag("contract")
@EnabledIf("ai.surgeone.altalm.bff.alm.contract.AlmSandbox#credentialsAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AlmAuthClient against the live sandbox")
class AlmAuthClientContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AlmCredentials creds;
    private static RestClient http;
    private static AlmAuthClient client;

    /** The session under test; nulled by the logout case so the teardown does not double-close it. */
    private static AlmSession session;

    @BeforeAll
    static void openClient() {
        creds = AlmSandbox.credentials();
        http = AlmSandbox.http();
        client = new AlmAuthClient(http, creds);
    }

    @Test
    @Order(1)
    @DisplayName("one-step login returns the whole cookie set, XSRF included")
    void loginIsOneStep() {
        session = client.login();

        // Probe 1's headline claim, now executable: oauth2/login alone yields a usable session.
        // If ALM ever reverts to the two-step alm-authenticate dance, QCSession/XSRF go missing here.
        assertThat(session.cookies())
                .containsKeys("LWSSO_COOKIE_KEY", "QCSession", "XSRF-TOKEN");
        assertThat(session.xsrfToken()).isNotBlank();
        AlmSandbox.say("login: cookies=" + session.cookies().keySet());
    }

    @Test
    @Order(2)
    @DisplayName("the session authenticates over the v2 JSON path")
    void sessionIsAuthenticated() {
        String body = getJson(creds.baseUrl() + "/v2/rest/is-authenticated", session);

        JsonNode node = JSON.readTree(body);
        String username = node.path("AuthenticationInfo").path("Username").asString("");
        // PII, and it lands in owner/detected-by on anything we create - mask it before it can reach
        // any output stream, including an assertion failure message.
        AlmSandbox.addMaskTerm(username);

        assertThat(username).isNotBlank();
        AlmSandbox.say("is-authenticated: 200, username=" + AlmSandbox.mask(username));
    }

    @Test
    @Order(3)
    @DisplayName("the project context is reachable with the pooled session")
    void projectContextIsReachable() {
        // Cheapest possible project-scoped read: one field, one row. Proves the domain/project pair
        // resolves and that cookies survive the hop from /qcbin/v2 to the Core REST tree.
        String body = getJson(creds.projectBase() + "/defects?page-size=1&fields=id", session);

        assertThat(JSON.readTree(body).has("entities")).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("keepalive refreshes the idle clock and reports success")
    void keepaliveRefreshesIdleClock() throws Exception {
        Instant before = session.lastUsedAt();
        Thread.sleep(20);

        assertThat(client.keepalive(session)).isTrue();
        assertThat(session.lastUsedAt()).isAfter(before);
    }

    @Test
    @Order(5)
    @DisplayName("a missing X-XSRF-TOKEN is rejected at the gate, before any row is written")
    void missingXsrfIsRejected() {
        String body = "{\"Fields\":[{\"Name\":\"name\",\"values\":[{\"value\":\""
                + AlmSandbox.PROBE_PREFIX + "-xsrf-negative\"}]}],\"Type\":\"requirement\"}";

        int status = http.post()
                .uri(URI.create(creds.projectBase() + "/requirements"))
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                // No X-XSRF-TOKEN header. This is the point of the test.
                .exchange((req, res) -> res.getStatusCode().value(), false);

        assertThat(status).isEqualTo(401);
        AlmSandbox.say("POST requirements without X-XSRF-TOKEN -> " + status + " (expected 401)");
    }

    @Test
    @Order(6)
    @DisplayName("one API key holds several independent sessions through the pool")
    void poolHoldsIndependentSessions() throws Exception {
        // Probe 10 held 50; three is enough to prove independence without being rude to the sandbox.
        Set<String> qcSessions = new LinkedHashSet<>();
        try (AlmSessionPool pool =
                     new AlmSessionPool(3, AlmSession.DEFAULT_MAX_IDLE, client::login, client::logout)) {
            AlmSession a = pool.borrow(Duration.ofSeconds(30));
            AlmSession b = pool.borrow(Duration.ofSeconds(30));
            AlmSession c = pool.borrow(Duration.ofSeconds(30));

            for (AlmSession s : new AlmSession[]{a, b, c}) {
                qcSessions.add(s.cookies().get("QCSession"));
                // Each must be independently usable - not an alias of one server-side session.
                assertThat(getJson(creds.baseUrl() + "/v2/rest/is-authenticated", s)).isNotBlank();
            }

            assertThat(pool.liveCount()).isEqualTo(3);
            pool.release(a);
            pool.release(b);
            pool.release(c);
        }

        assertThat(new HashSet<>(qcSessions)).hasSize(3);
        AlmSandbox.say("pool: 3 concurrent sessions, 3 distinct QCSession cookies");
    }

    @Test
    @Order(7)
    @DisplayName("site-session after oauth2/login: redundant or required (open item #2)")
    void siteSessionNecessity() {
        // Settles a logged open question rather than asserting a preferred answer: log in WITHOUT the
        // site-session call and see whether the project tree is reachable anyway.
        var login = http.post()
                .uri(URI.create(creds.baseUrl() + "/rest/oauth2/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clientId", creds.apiKey(), "secret", creds.apiSecret()))
                .retrieve()
                .toBodilessEntity();

        StringBuilder cookie = new StringBuilder();
        for (String raw : login.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            int eq = raw.indexOf('=');
            int semi = raw.indexOf(';');
            if (eq > 0) {
                if (!cookie.isEmpty()) {
                    cookie.append("; ");
                }
                cookie.append(raw, 0, eq).append('=')
                        .append(raw, eq + 1, semi < 0 ? raw.length() : semi);
            }
        }

        int status = http.get()
                .uri(URI.create(creds.projectBase() + "/defects?page-size=1&fields=id"))
                .header(HttpHeaders.COOKIE, cookie.toString())
                .accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> res.getStatusCode().value(), false);

        // Either answer is a finding; a 5xx would mean the question is still unanswered.
        assertThat(status).isIn(200, 401);
        AlmSandbox.say("project read WITHOUT POST site-session -> " + status
                + (status == 200 ? " (site-session is redundant after oauth2/login)"
                                 : " (site-session is REQUIRED)"));
    }

    @Test
    @Order(8)
    @DisplayName("DELETE site-session ends the project session but NOT the authentication")
    void deleteSiteSessionLeavesAuthenticationAlive() {
        // The finding that caught a real bug in logout(). Pinned here because it is counter-intuitive
        // and invisible from any single status code: the obvious liveness check keeps saying 200
        // while every call the BFF actually makes returns 401.
        int deleted = status("DELETE", creds.baseUrl() + "/rest/site-session", session, true);
        assertThat(deleted).isEqualTo(200);

        int projectRead = status("GET", creds.projectBase() + "/defects?page-size=1&fields=id",
                session, false);
        int isAuth = status("GET", creds.baseUrl() + "/v2/rest/is-authenticated", session, false);

        // The claim, and the one that drove the logout fix: authentication outlives the site session.
        assertThat(isAuth).as("LWSSO authentication survives DELETE site-session").isEqualTo(200);

        // projectRead is deliberately NOT asserted: observed 401 on 6 of 8 runs and 200 on 2, with no
        // code change between them. Recorded as an observation because an assertion on it would be a
        // flaky test dressed up as a finding.
        // UNVERIFIED hypothesis: this SaaS deployment load-balances across nodes that learn about the
        // teardown at different times. Experiment: capture the LB/routing response headers on the
        // DELETE and the following GET and check whether the 200s correlate with a node change. See
        // risks register Q40.
        AlmSandbox.say("after DELETE site-session: project-read=" + projectRead
                + ", is-authenticated=" + isAuth + " (authentication outlives the site session)");

        session = null; // spent - the teardown opens its own
    }

    @Test
    @Order(9)
    @DisplayName("logout() tears down the authentication, not just the site session")
    void logoutTearsDownAuthentication() {
        // Exercises the real code path from a clean session. Before Probe 13, logout() issued only
        // the DELETE and this assertion failed with is-authenticated still 200 - one leaked identity
        // per pooled session.
        AlmSession fresh = client.login();
        assertThat(status("GET", creds.baseUrl() + "/v2/rest/is-authenticated", fresh, false))
                .isEqualTo(200);

        client.logout(fresh);

        Response after = call("GET", creds.baseUrl() + "/v2/rest/is-authenticated", fresh, false);

        assertThat(after.status()).as("the session must no longer authenticate").isNotEqualTo(200);
        // The status is 500, not 401 - replaying a logged-out session's cookies is a server error on
        // this deployment, not a clean refusal. Pin the body too: a 5xx that means "your token is
        // dead" must stay distinguishable from a 5xx that means "your write may have committed",
        // because the write-safety layer treats those two identically today (see risk register).
        if (after.status() >= 500) {
            assertThat(after.body()).contains("logged out");
        }
        AlmSandbox.say("is-authenticated after full logout -> " + after.status()
                + " (authentication ended)");
    }

    /**
     * Sweeps for anything this suite might have committed, then closes the session.
     *
     * <p>The sweep is an assertion, not housekeeping. "The XSRF gate runs before business logic so
     * nothing can commit" is exactly the shape of confident negative claim this project has had to
     * retract four times; the sweep is what turns it into an observation.
     */
    @AfterAll
    static void sweepAndLogout() {
        if (client == null) {
            return;
        }
        // Always a fresh session: the ordered cases deliberately destroy the shared one.
        AlmSession sweeper = client.login();
        try {
            String query = URLEncoder.encode("{name[\"" + AlmSandbox.PROBE_PREFIX + "*\"]}",
                    StandardCharsets.UTF_8);
            for (String collection : new String[]{"requirements", "defects", "tests"}) {
                String body = getJson(creds.projectBase() + "/" + collection
                        + "?query=" + query + "&fields=id,name&page-size=50", sweeper);
                int found = JSON.readTree(body).path("entities").size();
                assertThat(found)
                        .withFailMessage("orphan sweep found %d %s named %s* - the contract suite "
                                        + "committed a row it should never have created",
                                found, collection, AlmSandbox.PROBE_PREFIX)
                        .isZero();
            }
            AlmSandbox.say("orphan sweep: clean");
        } finally {
            client.logout(sweeper);
        }
    }

    /** A response reduced to the two things these cases assert on. */
    private record Response(int status, String body) {
    }

    /** Convenience for the many cases that only care about the status. */
    private static int status(String method, String url, AlmSession using, boolean xsrf) {
        return call(method, url, using, xsrf).status();
    }

    /**
     * Issues a request and returns its status and body without throwing.
     *
     * <p>{@code retrieve()} turns a non-2xx into an exception, which is the wrong shape here: several
     * of these cases assert on a specific error status, so it is the result, not a failure.
     */
    private static Response call(String method, String url, AlmSession using, boolean xsrf) {
        RestClient.RequestHeadersSpec<?> spec = switch (method) {
            case "GET" -> http.get().uri(URI.create(url));
            case "DELETE" -> http.delete().uri(URI.create(url));
            default -> throw new IllegalArgumentException("unsupported method " + method);
        };
        spec = spec.header(HttpHeaders.COOKIE, using.cookieHeader()).accept(MediaType.APPLICATION_JSON);
        if (xsrf) {
            spec = spec.header("X-XSRF-TOKEN", using.xsrfToken());
        }
        return spec.exchange((req, res) -> {
            String body = "";
            try (var in = res.getBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // A bodiless error response is fine; the status carries the finding.
            }
            // Masked at the boundary: an ALM error body can echo the host or project back at us.
            return new Response(res.getStatusCode().value(), AlmSandbox.mask(body));
        }, false);
    }

    /** GET returning the body as text, with the session's cookies attached. */
    private static String getJson(String url, AlmSession using) {
        return http.get()
                .uri(URI.create(url))
                .header(HttpHeaders.COOKIE, using.cookieHeader())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }
}
