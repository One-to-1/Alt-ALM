package ai.surgeone.altalm.bff.config;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCache;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataClient;
import ai.surgeone.altalm.bff.alm.session.AlmAuthClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.session.AlmKeepalive;
import ai.surgeone.altalm.bff.alm.session.AlmSessionPool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the ALM session and metadata layers into the application context.
 *
 * <p>Nothing here contacts ALM at startup. The pool opens its first session lazily on the first
 * borrow, so the context starts — and CI's context test passes — without a reachable server. What
 * <em>does</em> happen eagerly is credential resolution, which fails fast with a clear message rather
 * than deferring a misconfiguration to the first user request.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlmProperties.class)
@EnableScheduling
public class AlmConfiguration {

    /**
     * Shared HTTP client.
     *
     * <p>Explicit timeouts because the JDK client has <strong>no</strong> request timeout by default,
     * and a hung ALM would otherwise pin a request thread indefinitely. Redirects are off: ALM answers
     * an unauthenticated request with a 302 to its login form, and quietly following that turns a
     * clean auth failure into a confusing HTML body parsed as JSON.
     */
    @Bean
    public RestClient almRestClient() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Resolves credentials: the file if configured, otherwise the inline properties.
     *
     * <p>Every failure message here names the <em>property</em> that is missing, never a value —
     * this is the one bean whose exception messages could otherwise carry a secret into a log.
     */
    @Bean
    public AlmCredentials almCredentials(AlmProperties props) {
        String file = props.getCredentialsFile();
        if (file != null && !file.isBlank()) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        "alt-alm.alm.credentials-file points at a path that is not a readable file: "
                                + path.toAbsolutePath());
            }
            try {
                return AlmCredentials.load(path);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read alt-alm.alm.credentials-file", e);
            }
        }
        if (props.getUrl() == null || props.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "no ALM credentials configured: set alt-alm.alm.credentials-file, or "
                            + "alt-alm.alm.url / .api-key / .api-secret / .domain / .project");
        }
        String url = props.getUrl().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/qcbin")) {
            url = url + "/qcbin";
        }
        return new AlmCredentials(url, props.getApiKey(), props.getApiSecret(),
                props.getDomain(), props.getProject());
    }

    @Bean
    public AlmAuthClient almAuthClient(RestClient almRestClient, AlmCredentials almCredentials) {
        return new AlmAuthClient(almRestClient, almCredentials);
    }

    /** Closed on shutdown, which logs every held session out — both calls (see {@code logout}). */
    @Bean(destroyMethod = "close")
    public AlmSessionPool almSessionPool(AlmProperties props, AlmAuthClient auth) {
        AlmProperties.Pool pool = props.getPool();
        return new AlmSessionPool(pool.getMaxSize(), pool.getMaxIdle(), auth::login, auth::logout);
    }

    @Bean
    public AlmKeepalive almKeepalive(AlmSessionPool pool, AlmAuthClient auth, AlmProperties props) {
        return new AlmKeepalive(pool, auth, props.getPool().getKeepaliveMargin());
    }

    @Bean
    public AlmMetadataClient almMetadataClient(RestClient almRestClient, AlmCredentials creds,
                                               AlmSessionPool pool, AlmProperties props) {
        return new AlmMetadataClient(almRestClient, creds, pool, props.getPool().getBorrowTimeout());
    }

    /** Project-scoped by construction: the cache is keyed to the credentials' domain/project. */
    @Bean
    public AlmMetadataCache almMetadataCache(AlmCredentials creds, AlmMetadataClient client) {
        return new AlmMetadataCache(creds.domain(), creds.project(), client::fetchFields);
    }

    /**
     * Runs the keepalive sweep on a schedule.
     *
     * <p>A separate bean rather than {@code @Scheduled} on {@link AlmKeepalive} itself, so that class
     * stays a plain object a test can call directly without a scheduler or a Spring context.
     */
    @Bean
    public AlmKeepaliveScheduler almKeepaliveScheduler(AlmKeepalive keepalive) {
        return new AlmKeepaliveScheduler(keepalive);
    }

    /** Thin scheduling adapter. */
    public static class AlmKeepaliveScheduler {

        private final AlmKeepalive keepalive;

        AlmKeepaliveScheduler(AlmKeepalive keepalive) {
            this.keepalive = keepalive;
        }

        /** No-ops when the pool is empty, so an idle BFF makes no ALM traffic at all. */
        @Scheduled(fixedDelayString = "${alt-alm.alm.pool.keepalive-interval:PT5M}")
        public void sweep() {
            keepalive.sweep();
        }
    }
}
