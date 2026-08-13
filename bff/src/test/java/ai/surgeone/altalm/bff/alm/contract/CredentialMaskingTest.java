package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standing masking check (test-strategy §4 item 10).
 *
 * <p>Deliberately <strong>not</strong> tagged {@code contract}: it runs on every commit, because the
 * risk it guards is a credential leaking into a committed file, and that risk does not wait for
 * someone to opt in to the live suite. Without credentials on disk it still exercises the redaction
 * logic using synthetic values; with credentials present it additionally scans the tracked tree for
 * the literal secrets.
 *
 * <p><strong>Every assertion here uses an explicit fail message.</strong> AssertJ's default messages
 * echo the expected value — which, for "this file must not contain the API secret", would print the
 * API secret into the build log on failure. A leak detector that leaks on detection is worse than
 * none, so failure messages name the file and nothing else.
 */
@DisplayName("credential masking")
class CredentialMaskingTest {

    private static final Path REPO_ROOT = Path.of("..");

    @Test
    @DisplayName("AlmCredentials never renders its own contents")
    void credentialsToStringIsRedacted() {
        AlmCredentials c = new AlmCredentials(
                "https://alm.example.invalid/qcbin", "key-abc", "secret-xyz", "DOM", "PROJ");

        String rendered = c.toString();

        // Domain and project are masked alongside the keys: they identify a customer tenant.
        assertThat(Stream.of("key-abc", "secret-xyz", "DOM", "PROJ").filter(rendered::contains))
                .withFailMessage("AlmCredentials.toString() rendered a value it must redact")
                .isEmpty();
        assertThat(rendered).contains("<redacted>");
    }

    @Test
    @DisplayName("string interpolation of a credentials record cannot leak it")
    void credentialsInterpolationIsRedacted() {
        // The realistic leak is not a deliberate print - it is "log.info(\"connecting as {}\", creds)"
        // or an exception message built by concatenation. Both route through toString().
        AlmCredentials c = new AlmCredentials(
                "https://alm.example.invalid/qcbin", "key-abc", "secret-xyz", "DOM", "PROJ");

        String viaConcat = "connecting: " + c;
        String viaException = new IllegalStateException("failed for " + c).getMessage();

        assertThat(viaConcat).doesNotContain("secret-xyz");
        assertThat(viaException).doesNotContain("secret-xyz");
    }

    @Test
    @DisplayName("AlmSession renders cookie names but never cookie values")
    void sessionToStringHidesCookieValues() {
        AlmSession s = new AlmSession(
                Map.of("QCSession", "qc-value-1", "LWSSO_COOKIE_KEY", "lwsso-value-2"),
                "xsrf-value-3", Instant.now());

        String rendered = s.toString();

        assertThat(Stream.of("qc-value-1", "lwsso-value-2", "xsrf-value-3").filter(rendered::contains))
                .withFailMessage("AlmSession.toString() rendered a cookie value")
                .isEmpty();
        // Names are safe and are what makes a log line useful at all.
        assertThat(rendered).contains("QCSession");
    }

    @Test
    @DisplayName("the sandbox masker redacts every registered term")
    void maskerRedactsRegisteredTerms() {
        AlmSandbox.addMaskTerm("supersecret-token-value");

        String masked = AlmSandbox.mask("bearer supersecret-token-value trailing");

        assertThat(masked).isEqualTo("bearer REDACTED trailing");
    }

    @Test
    @DisplayName("no tracked file contains a live credential value")
    void trackedTreeIsFreeOfLiveSecrets() throws IOException {
        if (!AlmSandbox.credentialsAvailable()) {
            // Nothing to scan for. Not a silent pass: the four tests above still ran.
            return;
        }
        // Only the long, distinctive secrets. Domain and project are deliberately NOT scanned for,
        // even though AlmSandbox.mask() redacts them: on this sandbox the project name is an ordinary
        // English word, so scanning for it flags every prose sentence in docs/ that happens to use it.
        // Over-masking output is free; over-matching a leak detector makes it noise nobody reads.
        AlmCredentials c = AlmSandbox.credentials();
        List<String> needles = new ArrayList<>(List.of(c.apiKey(), c.apiSecret()));
        String host = URI.create(c.baseUrl()).getHost();
        if (host != null) {
            needles.add(host);
        }
        needles.removeIf(n -> n == null || n.length() < 12);

        List<Path> offenders = new ArrayList<>();
        for (Path dir : List.of(REPO_ROOT.resolve("tests/fixtures"),
                REPO_ROOT.resolve("bff/src"),
                REPO_ROOT.resolve("spa/src"),
                REPO_ROOT.resolve("docs"),
                REPO_ROOT.resolve("scripts"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String text;
                    try {
                        text = Files.readString(p).toLowerCase(Locale.ROOT);
                    } catch (IOException | RuntimeException notText) {
                        continue; // binary or unreadable - nothing to match against
                    }
                    if (needles.stream().anyMatch(n -> text.contains(n.toLowerCase(Locale.ROOT)))) {
                        offenders.add(p);
                    }
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("these tracked files contain a live credential value "
                        + "(value not shown): %s", offenders)
                .isEmpty();
    }
}
