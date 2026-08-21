package ai.surgeone.altalm.bff.alm.read;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads attachments — the list, and the bytes. <strong>Reads only</strong>, for the same reason
 * {@link AlmEntityClient} is: one API key reaches nine projects and eight of them are other teams'.
 *
 * <p>Writing an attachment is a different problem and deliberately not solved here. It needs a
 * hand-built multipart body whose construction is client-stack dependent — PowerShell 7's
 * {@code -Form} produced a body this server rejects — so {@code RecordService} refuses
 * {@code attachments} as a writable collection rather than offering an endpoint that fails.
 *
 * <h2>⚠️ The trap this class exists to contain</h2>
 *
 * <p>Probe 35: a {@code GET} on an attachment member returns <strong>entity metadata</strong> under
 * a wildcard Accept ({@code &#42;/&#42;}) <em>and</em> under {@code application/json} — HTTP 200, a
 * well-formed JSON document, and not the file. Only a byte comparison catches it; anything checking
 * the status code sees success. <strong>{@code application/octet-stream} is what returns the
 * bytes</strong>, and asking for the file's actual type ({@code image/png}) is a
 * <strong>406</strong>. So the Accept header here is a correctness requirement, not a formality,
 * and it is generic on purpose.
 *
 * <p>ALM does report the real media type on the way back, so nothing has to guess one from the file
 * extension. ⚠️ It appends {@code ;charset=utf-8} to binary types, which is meaningless and must not
 * be propagated — {@link AlmAttachment#mediaType()} strips parameters.
 *
 * <p>Members are addressed by {@code ?by-id=true} rather than by filename. A name can collide,
 * contain a slash, or need escaping this class would then own; an id has none of those properties.
 */
public final class AlmAttachmentClient {

    /** One attachment's metadata, as the list endpoint reports it. */
    public record AlmAttachment(String id, String name, String description, long size,
                                String mediaType) {
    }

    /**
     * An attachment's bytes, with the media type ALM reported for them.
     *
     * <p>⚠️ {@code mediaType} is ALM's claim about a file somebody uploaded, not a fact — it is
     * carried so a caller can *decide* with it, never so a caller can echo it into a response
     * header. Deciding what is safe to serve is {@code AttachmentController}'s job.
     */
    public record AlmAttachmentBytes(byte[] bytes, String mediaType, String fileName) {
    }

    private final RestClient http;
    private final AlmCredentials credentials;
    private final AlmSessionPool pool;
    private final AlmAccessPolicy policy;
    private final AlmReadRetry retry;
    private final Duration borrowTimeout;

    public AlmAttachmentClient(RestClient http, AlmCredentials credentials, AlmSessionPool pool,
                               AlmAccessPolicy policy, AlmReadRetry retry, Duration borrowTimeout) {
        this.http = http;
        this.credentials = credentials;
        this.pool = pool;
        this.policy = policy;
        this.retry = retry;
        this.borrowTimeout = borrowTimeout;
    }

    /**
     * The attachments filed against one record.
     *
     * @return an empty list for a record with none, which is the ordinary case and not an error
     */
    public List<AlmAttachment> list(AlmProjectRef project, String collection, String id) {
        policy.checkMethod("GET", project);

        String url = memberBase(project, collection, id)
                + "?fields=id,name,description,file-size,ref-subtype&page-size=200";

        AlmEntityPage page = retry.call(() -> AlmEntityParser.parsePage(getJson(url)));
        return page.entities().stream()
                .map(e -> new AlmAttachment(
                        e.first("id").orElse(""),
                        e.first("name").orElse(""),
                        e.first("description").orElse(""),
                        parseSize(e.first("file-size").orElse("")),
                        ""))
                .filter(a -> !a.id().isBlank())
                .toList();
    }

    /**
     * One attachment's bytes.
     *
     * <p>⚠️ {@code Accept: application/octet-stream}, always. See the class javadoc: the obvious
     * headers return a 200 carrying metadata instead of the file, and the specific one returns 406.
     */
    public AlmAttachmentBytes content(AlmProjectRef project, String collection, String id,
                                      String attachmentId) {
        policy.checkMethod("GET", project);

        String url = memberBase(project, collection, id)
                + "/" + URLEncoder.encode(attachmentId, StandardCharsets.UTF_8)
                + "?by-id=true";

        return retry.call(() -> {
            AlmSession session = borrow();
            try {
                ResponseEntity<byte[]> response = http.get()
                        .uri(AlmEntityClient.almUri(url))
                        .header(HttpHeaders.COOKIE, session.cookieHeader())
                        .accept(MediaType.APPLICATION_OCTET_STREAM)
                        .retrieve()
                        .onStatus(status -> true, (req, res) -> { })
                        .toEntity(byte[].class);

                int status = response.getStatusCode().value();
                if (status >= 500) {
                    throw new AlmReadRetry.Transient5xx(status);
                }
                if (status >= 400) {
                    throw new IllegalArgumentException(
                            "ALM refused the attachment read (HTTP " + status + ")");
                }
                byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
                return new AlmAttachmentBytes(body,
                        mediaTypeOf(response.getHeaders()),
                        fileNameOf(response.getHeaders()));
            } finally {
                pool.release(session);
            }
        });
    }

    // ==========================================================================================

    private String memberBase(AlmProjectRef project, String collection, String id) {
        return project.restBase(credentials.baseUrl())
                + "/" + URLEncoder.encode(collection, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + "/attachments";
    }

    private String getJson(String url) {
        AlmSession session = borrow();
        try {
            ResponseEntity<String> response = http.get()
                    .uri(AlmEntityClient.almUri(url))
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);

            int status = response.getStatusCode().value();
            if (status >= 500) {
                throw new AlmReadRetry.Transient5xx(status);
            }
            if (status >= 400) {
                throw new IllegalArgumentException(
                        "ALM refused the attachment list (HTTP " + status + ")");
            }
            return response.getBody();
        } finally {
            pool.release(session);
        }
    }

    private AlmSession borrow() {
        try {
            return pool.borrow(borrowTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an ALM session", e);
        }
    }

    /**
     * ALM's media type with its parameters stripped.
     *
     * <p>⚠️ It appends {@code ;charset=utf-8} to binary content types, which is nonsense for a PNG
     * and would be nonsense to pass on. Empty when absent — an absent claim must not become a
     * guessed one.
     */
    private static String mediaTypeOf(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(HttpHeaders.CONTENT_TYPE))
                .map(value -> {
                    int semicolon = value.indexOf(';');
                    return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
                })
                .orElse("");
    }

    /** The filename ALM reports, or empty. Never trusted for a response header without sanitising. */
    private static String fileNameOf(HttpHeaders headers) {
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition == null) {
            return "";
        }
        int at = disposition.toLowerCase().indexOf("filename=");
        if (at < 0) {
            return "";
        }
        String value = disposition.substring(at + "filename=".length()).trim();
        if (value.startsWith("\"")) {
            int close = value.indexOf('"', 1);
            return close < 0 ? "" : value.substring(1, close);
        }
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
    }

    /** A non-numeric or absent size is 0 rather than an exception — it is a display detail. */
    private static long parseSize(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
