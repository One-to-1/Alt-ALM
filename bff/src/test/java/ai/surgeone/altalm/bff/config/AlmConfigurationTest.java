package ai.surgeone.altalm.bff.config;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCache;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmKeepalive;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ALM beans wire up and carry the configured values. */
@SpringBootTest
@DisplayName("ALM bean wiring")
class AlmConfigurationTest {

    @Autowired
    private AlmSessionPool pool;

    @Autowired
    private AlmMetadataCache metadataCache;

    @Autowired
    private AlmKeepalive keepalive;

    @Autowired
    private AlmCredentials credentials;

    @Test
    @DisplayName("the context starts without contacting ALM")
    void startsWithoutTouchingAlm() {
        // The pool must be lazy. If any bean logged in at startup, this would be non-zero - and CI,
        // which has no credentials and no route to the sandbox, could never start the context.
        assertThat(pool.liveCount()).isZero();
        assertThat(metadataCache.size()).isZero();
        assertThat(keepalive).isNotNull();
    }

    @Test
    @DisplayName("an idle keepalive sweep makes no calls and reports nothing refreshed")
    void keepaliveSweepIsANoOpWhenIdle() {
        // Proves the scheduler is safe to run against an empty pool: no sessions, no HTTP, no
        // exception. The test-context credentials point at alm.invalid, so any real call would fail.
        assertThat(keepalive.sweep()).isZero();
    }

    @Test
    @DisplayName("inline properties resolve into credentials that still refuse to render themselves")
    void inlinePropertiesBuildCredentials() {
        assertThat(credentials.domain()).isEqualTo("TEST_DOMAIN");
        assertThat(credentials.baseUrl()).endsWith("/qcbin");
        assertThat(credentials.toString()).doesNotContain("TEST_DOMAIN").contains("<redacted>");
    }

    @Test
    @DisplayName("a credentials file wins over inline properties")
    void credentialsFileTakesPrecedence(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("creds.json");
        Files.writeString(file, """
                {"alm_adress":"https://from-file.invalid","api_key":"k","api_secret":"s",
                 "domain":"FILE_DOMAIN","project":"FILE_PROJECT"}
                """);
        AlmProperties props = new AlmProperties();
        props.setCredentialsFile(file.toString());
        props.setUrl("https://inline.invalid/qcbin");
        props.setApiKey("inline");
        props.setApiSecret("inline");

        AlmCredentials resolved = new AlmConfiguration().almCredentials(props);

        assertThat(resolved.domain()).isEqualTo("FILE_DOMAIN");
        // The loader appends /qcbin when the configured URL omits it.
        assertThat(resolved.baseUrl()).isEqualTo("https://from-file.invalid/qcbin");
    }

    @Test
    @DisplayName("no configuration at all fails fast, naming properties and never values")
    void missingConfigurationFailsFast() {
        assertThatThrownBy(() -> new AlmConfiguration().almCredentials(new AlmProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ALM credentials configured")
                .hasMessageContaining("alt-alm.alm.credentials-file");
    }

    @Test
    @DisplayName("a credentials-file path that does not exist is a startup error, not a fallback")
    void missingCredentialsFileIsAnError() {
        // Silently falling back to the inline values here would be the worst outcome: the operator
        // thinks they are on the sandbox file and are actually somewhere else entirely.
        AlmProperties props = new AlmProperties();
        props.setCredentialsFile("does-not-exist.json");
        props.setUrl("https://inline.invalid/qcbin");
        props.setApiKey("k");
        props.setApiSecret("s");

        assertThatThrownBy(() -> new AlmConfiguration().almCredentials(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials-file");
    }

    @Test
    @DisplayName("pool defaults are the documented politeness limit, not a server cap")
    void poolDefaults() {
        AlmProperties.Pool defaults = new AlmProperties().getPool();

        assertThat(defaults.getMaxSize()).isEqualTo(8);
        assertThat(defaults.getMaxIdle()).isEqualTo(Duration.ofMinutes(60));
        // The sweep must run several times inside the margin, or a session can expire between sweeps.
        assertThat(defaults.getKeepaliveInterval()).isLessThan(defaults.getKeepaliveMargin());
    }
}
