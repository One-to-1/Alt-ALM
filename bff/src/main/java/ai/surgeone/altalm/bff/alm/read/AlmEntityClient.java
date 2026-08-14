package ai.surgeone.altalm.bff.alm.read;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Reads entity collections from ALM. <strong>Reads only — this class has no write path at all.</strong>
 *
 * <p>That absence is deliberate rather than incidental. One API key reaches nine projects in this
 * tenant, of which exactly one is a sandbox; the other eight are other teams' live projects, granted
 * read-only (probe 16). A client that could write, guarded by a flag, would eventually write with the
 * flag set wrong. This one cannot, and every request additionally passes through
 * {@link AlmAccessPolicy#checkMethod} so the guarantee is enforced rather than merely intended.
 *
 * <p>Three probe-earned behaviours are wired in here:
 *
 * <ul>
 *   <li><strong>5xx reads are retried</strong> ({@link AlmReadRetry}, Q46) — a plain GET was seen to
 *       500 once and never again.
 *   <li><strong>{@code Accept} discipline</strong> — a missing or wrong {@code Accept} returns a
 *       branded HTML error page instead of parseable JSON ({@code api-ref} §3.4), so it is set on
 *       every call rather than left to a default.
 *   <li><strong>URIs are built with {@link URI#create}</strong>, never the String overload:
 *       {@code RestClient} treats braces as URI template variables, and ALM's query grammar is made
 *       of braces.
 * </ul>
 */
public final class AlmEntityClient {

    private final RestClient http;
    private final AlmCredentials credentials;
    private final AlmSessionPool pool;
    private final AlmAccessPolicy policy;
    private final AlmReadRetry retry;
    private final Duration borrowTimeout;

    public AlmEntityClient(RestClient http, AlmCredentials credentials, AlmSessionPool pool,
                           AlmAccessPolicy policy, AlmReadRetry retry, Duration borrowTimeout) {
        this.http = http;
        this.credentials = credentials;
        this.pool = pool;
        this.policy = policy;
        this.retry = retry;
        this.borrowTimeout = borrowTimeout;
    }

    /**
     * Fetches one page of a collection.
     *
     * @param project    which project to read — checked against the allowlist before any I/O
     * @param collection wire collection name, e.g. {@code requirements}
     * @param query      filter/paging/sort, or {@link AlmQuery#none()} for defaults
     */
    public AlmEntityPage page(AlmProjectRef project, String collection, AlmQuery query) {
        policy.checkMethod("GET", project);

        String url = project.restBase(credentials.baseUrl())
                + "/" + URLEncoder.encode(collection, StandardCharsets.UTF_8)
                + (query == null ? "" : query.toQueryString());

        return retry.call(() -> AlmEntityParser.parsePage(getBody(url)));
    }

    /**
     * Rows of a tree whose parent is {@code parentId}. Used by {@link AlmTreeRoots} and by tree
     * expansion, which are the same query with different arguments.
     */
    public List<AlmTreeRoots.Row> childrenOf(AlmProjectRef project, String collection, int parentId) {
        AlmEntityPage page = page(project, collection,
                AlmQuery.none().filter("parent-id", String.valueOf(parentId))
                        .fields("id", "name")
                        .pageSize(2000));

        return page.entities().stream()
                .map(e -> new AlmTreeRoots.Row(
                        e.first("id").orElse(""),
                        e.first("name").orElse("")))
                .toList();
    }

    /** A {@link AlmTreeRoots} bound to one project. */
    public AlmTreeRoots treeRoots(AlmProjectRef project) {
        return new AlmTreeRoots((collection, parentId) -> childrenOf(project, collection, parentId));
    }

    /**
     * Issues the GET, translating a 5xx into the signal {@link AlmReadRetry} retries on.
     *
     * <p>{@code toEntity} rather than {@code body}: the status is needed to distinguish "retry this"
     * from "this request is wrong", and {@code retrieve().body()} would have already thrown.
     */
    private String getBody(String url) {
        AlmSession session;
        try {
            session = pool.borrow(borrowTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an ALM session", e);
        }
        try {
            ResponseEntity<String> response = http.get()
                    .uri(URI.create(url))
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    // Do not throw on non-2xx: a 5xx is a retry signal here, not an exception, and a
                    // 4xx carries a qccore error body worth surfacing verbatim.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            if (status >= 500) {
                throw new AlmReadRetry.Transient5xx(status);
            }
            if (status >= 400) {
                // api-ref §3.4: parse Id/Title, never branch on the status code — an out-of-range
                // page-size answers 404, which has nothing to do with the resource being absent.
                throw new IllegalArgumentException(
                        "ALM rejected the read (HTTP " + status + "): " + summarise(response.getBody()));
            }
            return response.getBody();
        } finally {
            pool.release(session);
        }
    }

    /** Trims an error body for a message without dumping a whole HTML page into a log. */
    private static String summarise(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
