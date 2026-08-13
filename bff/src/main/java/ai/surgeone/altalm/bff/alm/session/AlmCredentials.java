package ai.surgeone.altalm.bff.alm.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Service-account credentials, loaded from disk <strong>at runtime only</strong>.
 *
 * <p>{@code CLAUDE.md} hard constraint: the credentials file is git-ignored and must never be
 * committed, printed, logged or forwarded. This type therefore refuses to render its own contents —
 * see {@link #toString()} — so a stray log statement or an exception message cannot leak it.
 *
 * <p>The on-disk keys are {@code alm_adress} (sic — the file's own spelling), {@code api_key},
 * {@code api_secret}, {@code domain}, {@code project}.
 */
public record AlmCredentials(
        String baseUrl,
        String apiKey,
        String apiSecret,
        String domain,
        String project) {

    public AlmCredentials {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalArgumentException("apiKey and apiSecret are required");
        }
    }

    /**
     * Reads the credentials JSON and normalises the base URL to end in {@code /qcbin}.
     *
     * @throws IOException if the file is missing or unreadable — deliberately not caught here, so a
     *                     misconfigured deployment fails at startup rather than at first request
     */
    public static AlmCredentials load(Path file) throws IOException {
        JsonNode n = new ObjectMapper().readTree(Files.readString(file));
        String url = n.path("alm_adress").asString("").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/qcbin")) {
            url = url + "/qcbin";
        }
        return new AlmCredentials(
                url,
                n.path("api_key").asString(""),
                n.path("api_secret").asString(""),
                n.path("domain").asString(""),
                n.path("project").asString(""));
    }

    /** Base path for project-scoped Core REST calls. */
    public String projectBase() {
        return baseUrl + "/rest/domains/" + domain + "/projects/" + project;
    }

    /**
     * Never renders secrets. Domain and project are masked too: they identify a customer tenant and
     * the masking discipline in {@code CLAUDE.md} covers them alongside keys.
     */
    @Override
    public String toString() {
        return "AlmCredentials[baseUrl=<redacted>, apiKey=<redacted>, apiSecret=<redacted>, "
                + "domain=<redacted>, project=<redacted>]";
    }
}
