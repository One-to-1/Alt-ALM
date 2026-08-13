package ai.surgeone.altalm.bff.alm.contract;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Live-sandbox plumbing for contract tests: credential discovery, the gate that keeps them out of a
 * default build, and the masking discipline every one of them must route output through.
 *
 * <p><strong>Why this class refuses to be convenient.</strong> {@code CLAUDE.md} forbids printing,
 * logging or forwarding anything under {@code Secrets/}. Contract tests are the only code in the
 * repository that holds real credentials, so the masking helper lives here rather than in each test —
 * one place to audit, and {@code CredentialMaskingTest} asserts it actually works.
 *
 * <p>Tests using this class are tagged {@code contract} and excluded from the default Surefire run
 * (see {@code pom.xml}); {@code -Pcontract} opts in. They additionally gate on
 * {@link #credentialsAvailable()} so a contributor without sandbox access gets a <em>skip</em>, never
 * a green tick that silently tested nothing — the test-strategy "fail closed" rule (§7).
 */
public final class AlmSandbox {

    /** Overrides credential discovery; useful for CI runners that stage the file elsewhere. */
    public static final String CREDENTIALS_ENV = "ALTALM_CREDENTIALS";

    private static final String CREDENTIALS_FILE = "Secrets/ALM_API_credentials.json";

    /**
     * Name prefix for anything this suite could conceivably create.
     *
     * <p>Nothing here creates records on purpose — the suite is read-only apart from one deliberately
     * rejected POST — but the prefix makes an unexpected commit greppable and sweepable, which is the
     * whole point of the probe convention given that a 5xx write may still have committed.
     */
    public static final String PROBE_PREFIX = "ALTALM-CONTRACT";

    private static final Path CREDENTIALS_PATH = locate();
    private static final List<Pattern> MASK_TERMS = new ArrayList<>();

    private static AlmCredentials cached;

    private AlmSandbox() {
    }

    /** Referenced by {@code @EnabledIf} — absent credentials skip the suite rather than fail it. */
    public static boolean credentialsAvailable() {
        return CREDENTIALS_PATH != null;
    }

    /** Loads (once) and registers every secret term with the masker before returning. */
    public static synchronized AlmCredentials credentials() {
        if (cached == null) {
            if (CREDENTIALS_PATH == null) {
                throw new IllegalStateException(
                        "no sandbox credentials; expected " + CREDENTIALS_FILE
                                + " at the repository root or $" + CREDENTIALS_ENV);
            }
            try {
                cached = AlmCredentials.load(CREDENTIALS_PATH);
            } catch (java.io.IOException e) {
                // Message deliberately omits the path contents; only the location is safe to echo.
                throw new UncheckedIOException("could not read sandbox credentials", e);
            }
            addMaskTerm(URI.create(cached.baseUrl()).getHost());
            addMaskTerm(cached.apiKey());
            addMaskTerm(cached.apiSecret());
            addMaskTerm(cached.domain());
            addMaskTerm(cached.project());
        }
        return cached;
    }

    /**
     * Registers another term to redact — call this with the resolved username as soon as it is known.
     *
     * <p>The username is PII and shows up in {@code owner}/{@code detected-by} fields, so it belongs
     * in the mask set even though it is not strictly a credential.
     */
    public static synchronized void addMaskTerm(String term) {
        if (term != null && !term.isBlank()) {
            MASK_TERMS.add(Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE));
        }
    }

    /** Redacts every registered secret term. All contract-test output must pass through this. */
    public static synchronized String mask(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Pattern p : MASK_TERMS) {
            out = p.matcher(out).replaceAll("REDACTED");
        }
        return out;
    }

    /** The only sanctioned way for a contract test to write to the console. */
    public static void say(String message) {
        System.out.println("[contract] " + mask(message));
    }

    /**
     * An HTTP client with explicit timeouts and no redirect following.
     *
     * <p>Both settings are deliberate. The JDK client has <em>no</em> request timeout by default, so a
     * hung sandbox would stall the build indefinitely. Redirects stay off because ALM's redirect
     * chains have misled us before — a contract test should observe a 302 and say so, not follow it
     * somewhere unexpected and report on the wrong resource.
     */
    public static RestClient http() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Walks up from the working directory looking for the git-ignored credentials file. */
    private static Path locate() {
        String override = System.getenv(CREDENTIALS_ENV);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        // Surefire runs with cwd=bff/, so the repository root is one level up - but do not assume it.
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(CREDENTIALS_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
