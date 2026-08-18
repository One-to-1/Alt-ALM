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

    /**
     * Server-side group-by: {@code GET {collection}/groups/{field}}.
     *
     * <p>Uses plain {@code application/json}, not {@code schema=alm-web}. Probe 15 §15.2 established
     * that plain JSON already carries {@code size} and {@code expression} — everything a Group tab
     * needs, including the filter that drills into a group — so the dialect adds nothing here, and
     * adopting it on operations that do not advertise it is R15.
     */
    public List<AlmGroup> groups(AlmProjectRef project, String collection, String field) {
        policy.checkMethod("GET", project);

        String url = project.restBase(credentials.baseUrl())
                + "/" + URLEncoder.encode(collection, StandardCharsets.UTF_8)
                + "/groups/" + URLEncoder.encode(field, StandardCharsets.UTF_8);

        return retry.call(() -> AlmGroupParser.parseGroups(getBody(url)));
    }

    /**
     * One record's change history: {@code GET {collection}/{id}/audits}.
     *
     * <p>A sub-resource of the record rather than a collection of its own, so it does not go through
     * {@link #page} — there is no query grammar here, no paging parameter, and the envelope is a
     * different shape entirely ({@link AlmAuditParser}).
     *
     * <p>⚠️ What comes back is <strong>partial by design of the server, not of this method</strong>:
     * probe 24 found creates and memo edits produce no entry at all. Callers must present it as
     * "recorded field changes", never as "everything that happened".
     */
    public List<AlmAudit> audits(AlmProjectRef project, String collection, String id) {
        policy.checkMethod("GET", project);

        String url = project.restBase(credentials.baseUrl())
                + "/" + URLEncoder.encode(collection, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "/audits";

        return retry.call(() -> AlmAuditParser.parseAudits(getBody(url)));
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
                    .uri(almUri(url))
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

    /**
     * Percent-encodes the characters ALM's query grammar is built from, so the result is a legal URI.
     *
     * <p>This is not cosmetic. ALM's grammar uses {@code { } [ ] ;} as structural characters, all of
     * which RFC 3986 forbids in a query unencoded. {@link URI#create} enforces that and throws —
     * which is how this was found, by a contract test failing with <em>"Illegal character in query at
     * index 121"</em> rather than by anything the server said. Every query-bearing read was broken;
     * the metadata client never hit it because its paths contain no braces.
     *
     * <p>The obvious escape — handing {@code RestClient} the URL as a String — is worse, because it
     * treats braces as URI template variables and ALM's grammar is made of braces.
     *
     * <p>So: encode them, which required knowing whether ALM accepts the encoded form. Probe 18 asked
     * directly, and raw versus percent-encoded produced identical results (HTTP 200, same
     * {@code TotalResults}) for {@code order-by}, multi-field {@code order-by}, and a {@code query}
     * filter containing brackets and a negative number.
     *
     * <p>Only structural grammar characters are touched — {@code ? & =} must survive as themselves,
     * and values were already encoded by {@link AlmQuery}.
     */
    static URI almUri(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return URI.create(url);
        }
        String base = url.substring(0, q);
        String query = url.substring(q + 1)
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace(";", "%3B")
                .replace("|", "%7C")
                .replace(" ", "%20");
        return URI.create(base + "?" + query);
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
