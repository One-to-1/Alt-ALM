package ai.surgeone.altalm.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything the BFF needs to reach one ALM project, bound from configuration.
 *
 * <p>Credentials arrive one of two ways, and <strong>the file wins</strong>:
 * <ol>
 *   <li>{@link #credentialsFile} — a path to the git-ignored JSON described in {@code CLAUDE.md}.
 *       This is how developers run locally against the sandbox.</li>
 *   <li>{@link #url}/{@link #apiKey}/{@link #apiSecret}/{@link #domain}/{@link #project} — inline,
 *       for deployments that inject secrets as environment variables
 *       ({@code ALT_ALM_ALM_API_SECRET} and friends) rather than staging a file.</li>
 * </ol>
 *
 * <p>The secret-bearing properties are deliberately plain {@code String}s that get copied into
 * {@link ai.surgeone.altalm.bff.alm.session.AlmCredentials} — which refuses to render itself — as
 * early as possible. Note that this object itself has no such protection: Spring Boot's
 * {@code /actuator/configprops} and {@code /env} endpoints would expose it, which is exactly why
 * neither endpoint is exposed (see {@code application.properties}).
 */
@ConfigurationProperties(prefix = "alt-alm.alm")
public class AlmProperties {

    /** Path to the credentials JSON. Takes precedence over the inline values below. */
    private String credentialsFile;

    /** Base URL; a missing {@code /qcbin} suffix is appended. */
    private String url;

    private String apiKey;
    private String apiSecret;
    private String domain;
    private String project;

    /**
     * Projects this BFF may <strong>read</strong>, as {@code DOMAIN/PROJECT} strings.
     *
     * <p>Empty by default, and that default is the point: a deployment reaches exactly the
     * credentialed project until someone deliberately enrols another. The user granted read access
     * to the tenant's other projects on 2026-08-14 for P1 validation (the sandbox holds 1
     * requirement and 0 tests, a sibling holds 847 rows — probe 16), but the grant covers reading
     * only, and enrolment stays explicit.
     *
     * <p>⚠️ These values name other teams' live projects, so they are configuration, never
     * committed: keep them in a local profile or an environment variable, not in a tracked
     * {@code application.properties}. Writes are impossible here regardless — see
     * {@link ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy}, which permits writes to the
     * credentialed project alone and offers no override.
     */
    private java.util.List<String> readableProjects = new java.util.ArrayList<>();

    private final Pool pool = new Pool();

    public java.util.List<String> getReadableProjects() {
        return readableProjects;
    }

    public void setReadableProjects(java.util.List<String> readableProjects) {
        this.readableProjects = readableProjects == null ? new java.util.ArrayList<>() : readableProjects;
    }

    public String getCredentialsFile() {
        return credentialsFile;
    }

    public void setCredentialsFile(String credentialsFile) {
        this.credentialsFile = credentialsFile;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public Pool getPool() {
        return pool;
    }

    /** Session-pool tuning. */
    public static class Pool {

        /**
         * How many sessions to hold open.
         *
         * <p><strong>A politeness limit, not a server cap.</strong> Probe 10 opened 50 concurrent
         * sessions on one API key with zero evictions and never found a ceiling, and REST sessions
         * consume no licence seat. 8 is chosen to keep our footprint and keepalive cost predictable.
         */
        private int maxSize = 8;

        /**
         * Discard-and-replace threshold for an idle session.
         *
         * <p>Matches the server's {@code REST_SESSION_MAX_IDLE_TIME} default. That parameter is not
         * readable over REST — {@code site-params} returns 403 even holding Customer Admin (Probe 11)
         * — so this is a client-side estimate of a server-side setting and must stay configurable.
         */
        private Duration maxIdle = Duration.ofMinutes(60);

        /** How long {@code borrow} waits when every session is checked out. */
        private Duration borrowTimeout = Duration.ofSeconds(30);

        /** Safety margin: refresh a session this long before {@link #maxIdle} would expire it. */
        private Duration keepaliveMargin = Duration.ofMinutes(10);

        /** How often the keepalive sweep runs. Must be well under {@link #keepaliveMargin}. */
        private Duration keepaliveInterval = Duration.ofMinutes(5);

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getMaxIdle() {
            return maxIdle;
        }

        public void setMaxIdle(Duration maxIdle) {
            this.maxIdle = maxIdle;
        }

        public Duration getBorrowTimeout() {
            return borrowTimeout;
        }

        public void setBorrowTimeout(Duration borrowTimeout) {
            this.borrowTimeout = borrowTimeout;
        }

        public Duration getKeepaliveMargin() {
            return keepaliveMargin;
        }

        public void setKeepaliveMargin(Duration keepaliveMargin) {
            this.keepaliveMargin = keepaliveMargin;
        }

        public Duration getKeepaliveInterval() {
            return keepaliveInterval;
        }

        public void setKeepaliveInterval(Duration keepaliveInterval) {
            this.keepaliveInterval = keepaliveInterval;
        }
    }
}
