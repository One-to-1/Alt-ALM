package ai.surgeone.altalm.bff.alm.metadata;

import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Fetches field metadata over HTTP and hands it to {@link AlmMetadataParser}.
 *
 * <p>This class exists so the parser can stay HTTP-free and testable offline against the redacted
 * fixtures. The split is deliberate: parsing is where the surprising, probe-earned knowledge lives
 * (the {@code {"Fields":{"Field":[…]}}} nesting, lowerCamel property names, the closed 8-type
 * system), and none of that should require a server to test.
 */
public final class AlmMetadataClient {

    private final RestClient http;
    private final AlmCredentials credentials;
    private final AlmSessionPool pool;
    private final Duration borrowTimeout;

    public AlmMetadataClient(RestClient http, AlmCredentials credentials, AlmSessionPool pool,
                             Duration borrowTimeout) {
        this.http = http;
        this.credentials = credentials;
        this.pool = pool;
        this.borrowTimeout = borrowTimeout;
    }

    /**
     * Fetches field metadata for the credentialed (sandbox) project.
     *
     * @param entity wire entity name, e.g. {@code requirement} or {@code test-parameter}
     * @return descriptors in server order
     */
    public List<FieldDescriptor> fetchFields(String entity) {
        return fetchFields(new AlmProjectRef(credentials.domain(), credentials.project()), entity);
    }

    /**
     * Fetches field metadata for a specific project.
     *
     * <p>The project parameter is not a generalisation for its own sake. ALM customization is
     * per project — field sets, labels and list bindings all differ — so rendering one project's
     * grid from another's metadata produces a plausible-looking, wrong grid. That became a live
     * risk the moment P1 started reading a project nobody here configured (probe 16), which is
     * exactly the failure ADR 0005 exists to prevent.
     *
     * @param project which project's customization to read; access-checked by the caller's policy
     * @param entity  wire entity name
     */
    public List<FieldDescriptor> fetchFields(AlmProjectRef project, String entity) {
        String path = project.restBase(credentials.baseUrl()) + "/customization/entities/"
                + URLEncoder.encode(entity, StandardCharsets.UTF_8) + "/fields";

        AlmSession session;
        try {
            session = pool.borrow(borrowTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an ALM session", e);
        }
        try {
            String body = http.get()
                    // URI, not the String overload: that one treats braces as URI variables, and ALM
                    // query syntax is made of braces. Harmless here, a trap the moment this is copied.
                    .uri(URI.create(path))
                    .header(HttpHeaders.COOKIE, session.cookieHeader())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return AlmMetadataParser.parseFields(body);
        } finally {
            pool.release(session);
        }
    }
}
