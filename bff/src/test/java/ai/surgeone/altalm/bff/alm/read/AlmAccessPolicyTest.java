package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The access boundary, tested for what it <em>refuses</em> and not just what it allows.
 *
 * <p>⚠️ <strong>The sandbox-only write rule was lifted by the user on 2026-08-18</strong>, and the
 * cases below were rewritten rather than deleted. That matters: a deleted test leaves no trace that
 * a rule ever existed, and the next person to read this file would have no way to tell "writes
 * follow the allowlist" from "nobody ever thought about writes". Each rewritten case now pins the
 * new rule and names the old one.
 *
 * <p>What still holds, and is what these tests are now for: <strong>enrolment is the only lever</strong>.
 * A project absent from the allowlist can be neither read nor written, there is no force flag, and
 * there is no bypass for tests.
 */
class AlmAccessPolicyTest {

    private static final AlmProjectRef SANDBOX = new AlmProjectRef("DOMAIN", "SANDBOX");
    private static final AlmProjectRef READABLE = new AlmProjectRef("DOMAIN", "OTHER_TEAM");
    private static final AlmProjectRef STRANGER = new AlmProjectRef("DOMAIN", "NOT_ENROLLED");

    private final AlmAccessPolicy policy = new AlmAccessPolicy(SANDBOX, Set.of(READABLE));

    @Test
    @DisplayName("the sandbox is both readable and writable")
    void sandboxIsFullyAccessible() {
        assertThatCode(() -> policy.checkRead(SANDBOX)).doesNotThrowAnyException();
        assertThatCode(() -> policy.checkWrite(SANDBOX)).doesNotThrowAnyException();
        assertThat(policy.isWritable(SANDBOX)).isTrue();
    }

    @Test
    @DisplayName("an enrolled project is now writable too (rule lifted 2026-08-18)")
    void enrolledProjectIsWritable() {
        // Until 2026-08-18 this asserted the opposite: read access implied nothing about writing,
        // and checkWrite(READABLE) threw. The user lifted that restriction. Enrolling a project for
        // reading now also makes it writable, which is what makes the allowlist load-bearing.
        assertThatCode(() -> policy.checkRead(READABLE)).doesNotThrowAnyException();
        assertThatCode(() -> policy.checkWrite(READABLE)).doesNotThrowAnyException();
        assertThat(policy.isWritable(READABLE)).isTrue();
    }

    @Test
    @DisplayName("a project off the allowlist still cannot be written — enrolment is the one lever")
    void unenrolledProjectIsStillNotWritable() {
        assertThatThrownBy(() -> policy.checkWrite(STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class)
                .hasMessageContaining("WRITE DENIED");
        assertThat(policy.isWritable(STRANGER)).isFalse();
        assertThatThrownBy(() -> policy.checkMethod("POST", STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("the sandbox is still identifiable, even though it is no longer privileged")
    void sandboxIsStillDistinguishable() {
        // The metadata field resolver binds to one project's schema, so "which project is THE one"
        // still has to be answerable after the write rule stopped depending on it.
        assertThat(policy.isSandbox(SANDBOX)).isTrue();
        assertThat(policy.isSandbox(READABLE)).isFalse();
        assertThat(policy.sandbox()).isEqualTo(SANDBOX);
    }

    @Test
    @DisplayName("a project absent from the allowlist cannot even be read")
    void unenrolledProjectIsDenied() {
        assertThatThrownBy(() -> policy.checkRead(STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} against an UNENROLLED project is denied")
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "post", "Put"})
    @DisplayName("every non-GET method is a write, whatever its case")
    void everyNonGetIsAWrite(String method) {
        // Retargeted from READABLE to STRANGER when the write rule was lifted. The property under
        // test is unchanged — case-insensitive method classification — but READABLE no longer
        // refuses writes, so it can no longer demonstrate it.
        assertThatThrownBy(() -> policy.checkMethod(method, STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} is treated as a write, not waved through as harmless")
    @ValueSource(strings = {"HEAD", "OPTIONS"})
    @DisplayName("methods nobody enumerated default to DENY rather than to allow")
    void unenumeratedMethodsDefaultToDeny(String method) {
        // These are read-ish in HTTP terms, and that is exactly the argument that would let a
        // future method slip through. The policy allows GET and denies everything else.
        assertThatThrownBy(() -> policy.checkMethod(method, STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("GET is allowed against the allowlist and denied off it")
    void getFollowsTheReadAllowlist() {
        assertThatCode(() -> policy.checkMethod("GET", READABLE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.checkMethod("GET", STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("sandboxOnly() enrols nothing — a fresh deployment reaches exactly one project")
    void defaultPostureIsSandboxOnly() {
        AlmAccessPolicy strict = new AlmAccessPolicy(SANDBOX, Set.of());
        assertThatCode(() -> strict.checkRead(SANDBOX)).doesNotThrowAnyException();
        assertThatThrownBy(() -> strict.checkRead(READABLE))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("a null project is denied, not NPE'd into an allow")
    void nullIsDenied() {
        assertThatThrownBy(() -> policy.checkRead(null))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.checkWrite(null))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @Test
    @DisplayName("denial messages carry the pseudonym, never the real project name")
    void denialMessagesDoNotDiscloseTheProject() {
        // A stack trace ends up in logs and bug reports. A third party's project name must not.
        assertThatThrownBy(() -> policy.checkWrite(STRANGER))
                .hasMessageNotContaining("NOT_ENROLLED")
                .hasMessageContaining(STRANGER.pseudonym());
    }

    @Test
    @DisplayName("toString on a project ref refuses to render domain or project")
    void projectRefDoesNotRenderItself() {
        assertThat(READABLE.toString())
                .doesNotContain("OTHER_TEAM")
                .doesNotContain("DOMAIN")
                .contains("redacted");
    }

    @Test
    @DisplayName("the pseudonym is stable for the same project and differs between projects")
    void pseudonymIsStableAndDistinct() {
        assertThat(READABLE.pseudonym())
                .isEqualTo(new AlmProjectRef("DOMAIN", "OTHER_TEAM").pseudonym())
                .isNotEqualTo(STRANGER.pseudonym());
    }
}
