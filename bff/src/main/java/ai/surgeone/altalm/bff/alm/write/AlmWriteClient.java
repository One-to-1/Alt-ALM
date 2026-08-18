package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The one place Alt-ALM writes to ALM. Every hazard probing found lives here rather than at a call
 * site, because each of them is invisible at the call site and fatal exactly once.
 *
 * <p>What this class exists to make unavoidable:
 *
 * <ul>
 *   <li><strong>The sandbox rule.</strong> {@link AlmAccessPolicy#checkMethod} runs before any I/O.
 *       There is no flag, no override, no test bypass — a write to anything but the designated
 *       sandbox throws. Eight of the nine reachable projects belong to other teams (probe 16).
 *   <li><strong>Field order is load-bearing.</strong> Bodies are built by {@link AlmEntityBody},
 *       which orders deterministically; a wrong order produces opaque NPE-style 500s that differ
 *       between runs because hash iteration order does (probe 4).
 *   <li><strong>A 5xx may have committed the row.</strong> Classified {@link AlmWriteOutcome#UNKNOWN}
 *       and returned as such. This class will not retry it and will not call it a failure — see
 *       {@link #verify}.
 *   <li><strong>Metadata lies about what a create requires.</strong> One narrow retry, once, on the
 *       server naming a missing physical column (probe 9).
 *   <li><strong>XSRF.</strong> {@code X-XSRF-TOKEN} on every non-GET; without it ALM answers 401,
 *       which reads like an auth expiry and is not one.
 * </ul>
 *
 * <p>⚠️ <strong>Single-entity only, and that is the server's limit, not a simplification.</strong>
 * Probe 29 established there is no bulk write here: a multi-entity JSON body is parsed as one entity
 * and 500s on the missing top-level {@code Fields}, and the XML {@code <Entities>} wrapper is
 * refused 400 while the same builder's single {@code <Entity>} commits 201. Anything resembling a
 * batch is a client-side loop over these methods, with per-row outcomes and <em>no transaction</em>
 * — do not add a method here that implies otherwise.
 *
 * <p>⚠️ <strong>A memo PUT replaces the field.</strong> There is no server-side append (probe 30), so
 * an "add a comment" caller that hands {@link #update} only the new text destroys the record's whole
 * comment history and gets HTTP 200 for it. Read-modify-write belongs above this class; this note is
 * here because this is where someone will be standing when they need it.
 */
public final class AlmWriteClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;
    private final AlmCredentials credentials;
    private final AlmSessionPool pool;
    private final AlmAccessPolicy policy;
    private final AlmFieldResolver fieldResolver;
    private final Duration borrowTimeout;

    public AlmWriteClient(RestClient http, AlmCredentials credentials, AlmSessionPool pool,
                          AlmAccessPolicy policy, AlmFieldResolver fieldResolver,
                          Duration borrowTimeout) {
        this.http = http;
        this.credentials = credentials;
        this.pool = pool;
        this.policy = policy;
        this.fieldResolver = fieldResolver == null ? AlmFieldResolver.none() : fieldResolver;
        this.borrowTimeout = borrowTimeout;
    }

    /**
     * Creates one row: {@code POST {collection}}.
     *
     * @param body the entity, already carrying its own {@code Type} and ordered deterministically
     * @return the outcome. ⚠️ Check {@link AlmWriteResult#needsVerification()} — a returned
     *         {@code UNKNOWN} is not a failure and must not be retried blind
     */
    public AlmWriteResult create(AlmProjectRef project, String collection, AlmEntityBody body) {
        policy.checkMethod("POST", project);
        return send("POST", collectionUrl(project, collection), body, collection);
    }

    /**
     * Updates one row: {@code PUT {collection}/{id}}.
     *
     * <p>⚠️ Partial by field, total by value: fields absent from {@code body} are left alone, but a
     * field that <em>is</em> present is replaced outright — including memo fields, which is how a
     * naive comment write erases comment history (probe 30).
     */
    public AlmWriteResult update(AlmProjectRef project, String collection, String id,
                                 AlmEntityBody body) {
        policy.checkMethod("PUT", project);
        requireId(id);
        return send("PUT", recordUrl(project, collection, id), body, collection);
    }

    /**
     * Deletes one row: {@code DELETE {collection}/{id}}.
     *
     * <p>⚠️ ALM does not cascade the way a caller tends to assume. An OTA folder delete leaves the
     * tests inside it orphaned (probe 8), and a test set deleted before its instances orphans those.
     * Deleting a container is not deleting its contents; work bottom-up.
     */
    public AlmWriteResult delete(AlmProjectRef project, String collection, String id) {
        policy.checkMethod("DELETE", project);
        requireId(id);
        return send("DELETE", recordUrl(project, collection, id), null, collection);
    }

    /**
     * Resolves an {@code UNKNOWN} write by asking whether the row is actually there.
     *
     * <p>This is the other half of {@link AlmWriteOutcome#UNKNOWN} and the reason it is safe to
     * refuse to guess: the caller knows what identifies the row it just tried to write, and this
     * class does not.
     *
     * @param finder runs the identifying query and returns the id if the row exists. Given a
     *               <em>read</em>, so it does not re-enter the write path
     * @return the same result carrying {@link AlmWriteResult#verifiedId()} when the row was found.
     *         The outcome deliberately stays {@code UNKNOWN} — "the row exists" and "the write
     *         succeeded" are different claims, and only the first has evidence behind it
     */
    public AlmWriteResult verify(AlmWriteResult result, java.util.function.Supplier<Optional<String>> finder) {
        if (!result.needsVerification()) {
            return result;
        }
        return finder.get().map(result::verifiedAs).orElse(result);
    }

    // ------------------------------------------------------------------------------------------

    private AlmWriteResult send(String method, String url, AlmEntityBody body, String collection) {
        Attempt first = attempt(method, url, body);
        if (first.outcome() != AlmWriteOutcome.UNKNOWN) {
            return first.toResult(false);
        }

        // The narrow probe-9 recovery, and only it: a 5xx whose body names a physical column that
        // metadata claims is neither required nor editable. Anything else 5xx stays UNKNOWN, because
        // re-sending a request that may already have committed is how duplicates get made.
        Optional<String> physical = AlmWriteRetry.missingRequiredPhysicalField(first.body());
        if (body == null || physical.isEmpty()
                || !AlmWriteRetry.isRetryableMissingField(first.status(), first.body())) {
            return first.toResult(false);
        }

        Optional<AlmFieldResolver.Resolved> resolved =
                fieldResolver.byPhysicalName(entityTypeOf(body, collection), physical.get());
        if (resolved.isEmpty()) {
            // Metadata cannot name the column the server is asking for. Guessing a logical name here
            // would turn one clear failure into an unrelated one; the original error is more useful.
            return first.toResult(false);
        }

        // set() keeps a re-set field's original position, so adding one cannot disturb the order
        // that made the first attempt legal.
        body.set(resolved.get().logicalName(), resolved.get().defaultValue());
        return attempt(method, url, body).toResult(true);
    }

    private Attempt attempt(String method, String url, AlmEntityBody body) {
        AlmSession session;
        try {
            session = pool.borrow(borrowTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an ALM session", e);
        }
        try {
            RestClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(URI.create(url))
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    // Missing on a non-GET, ALM answers 401 — which looks like an expired session
                    // and is not one. Probe 13.
                    .header("X-XSRF-TOKEN", session.xsrfToken())
                    // A wrong or absent Accept returns a branded HTML error page rather than JSON.
                    .accept(MediaType.APPLICATION_JSON);

            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body.toJson());
            }

            ResponseEntity<String> response = spec.retrieve()
                    // Never throw on status: a 5xx here is data, not an exception. Letting
                    // RestClient throw would erase the difference between "rejected" and "may have
                    // committed", which is the one distinction this whole class is built around.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            return new Attempt(response.getStatusCode().value(), response.getBody());
        } finally {
            pool.release(session);
        }
    }

    /**
     * One request/response pair, before it becomes a result.
     *
     * <p>Kept separate from {@link AlmWriteResult} so the retry can look at a raw status and body
     * without a half-built result existing that something might return by mistake.
     */
    private record Attempt(int status, String body) {

        AlmWriteOutcome outcome() {
            return AlmWriteOutcome.fromStatus(status);
        }

        AlmWriteResult toResult(boolean retried) {
            AlmWriteOutcome result = outcome();
            if (result == AlmWriteOutcome.COMMITTED) {
                return AlmWriteResult.committed(idOf(body).orElse(null), retried);
            }
            String errorId = jsonText(body, "Id");
            String errorTitle = jsonText(body, "Title");
            return result == AlmWriteOutcome.REJECTED
                    ? AlmWriteResult.rejected(errorId, errorTitle, retried)
                    : AlmWriteResult.unknown(errorId, errorTitle, retried);
        }
    }

    /**
     * Pulls {@code id} out of a written entity's response.
     *
     * <p>A DELETE returns no entity, and a write's response is a single entity rather than the
     * {@code entities[]} envelope reads use — hence a small dedicated reader rather than
     * {@code AlmEntityParser}, which is built for pages and would reject this shape.
     */
    private static Optional<String> idOf(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (RuntimeException e) {
            // A 2xx with an unparseable body: the write worked, we just cannot name the row. Better
            // to report a committed write with no id than to turn a success into an exception.
            return Optional.empty();
        }
        for (JsonNode field : root.path("Fields")) {
            if ("id".equals(field.path("Name").asString(""))) {
                JsonNode value = field.path("values").path(0).path("value");
                String id = value.asString("");
                return id.isBlank() ? Optional.empty() : Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** Reads one top-level string out of a {@code QCRestException} body, tolerating any other shape. */
    private static String jsonText(String responseBody, String key) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(responseBody).path(key).asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * The entity type to resolve a physical column against.
     *
     * <p>{@link AlmEntityBody} knows its own type but does not expose it, and the collection name is
     * not reliably the entity name ({@code requirements} → {@code requirement}, but
     * {@code design-steps} → {@code design-step} only by luck of pluralisation). Parsed back out of
     * the body's JSON so the authority stays the body itself, with the collection as a fallback.
     */
    private static String entityTypeOf(AlmEntityBody body, String collection) {
        try {
            String type = MAPPER.readTree(body.toJson()).path("Type").asString("");
            if (!type.isBlank()) {
                return type;
            }
        } catch (RuntimeException e) {
            // fall through to the collection-derived guess
        }
        return collection.endsWith("s") ? collection.substring(0, collection.length() - 1) : collection;
    }

    private String collectionUrl(AlmProjectRef project, String collection) {
        return project.restBase(credentials.baseUrl())
                + "/" + URLEncoder.encode(collection, StandardCharsets.UTF_8);
    }

    private String recordUrl(AlmProjectRef project, String collection, String id) {
        return collectionUrl(project, collection)
                + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a record id is required");
        }
        // ⚠️ -1 is the tree ROOT SENTINEL, not a row. Writing against it returns
        // 500 "Entity with key '-1' does not exist in table 'REQ'" (probe 27), which reads like a
        // server fault rather than the caller's mistake it is.
        if ("-1".equals(id.trim())) {
            throw new IllegalArgumentException(
                    "id -1 is the tree root sentinel, not a record — it cannot be written to");
        }
    }
}
