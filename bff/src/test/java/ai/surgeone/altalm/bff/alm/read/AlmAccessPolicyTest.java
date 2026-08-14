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
 * The access boundary is the only thing preventing a bug in Alt-ALM from writing to another team's
 * live project, so it is tested for what it <em>refuses</em>, not just what it allows.
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
    @DisplayName("an enrolled project is readable but NOT writable — read access implies nothing about writing")
    void readableProjectIsNotWritable() {
        assertThatCode(() -> policy.checkRead(READABLE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.checkWrite(READABLE))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class)
                .hasMessageContaining("WRITE DENIED");
        assertThat(policy.isWritable(READABLE)).isFalse();
    }

    @Test
    @DisplayName("a project absent from the allowlist cannot even be read")
    void unenrolledProjectIsDenied() {
        assertThatThrownBy(() -> policy.checkRead(STRANGER))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} against a read-only project is denied")
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "post", "Put"})
    @DisplayName("every non-GET method is a write, whatever its case")
    void everyNonGetIsAWrite(String method) {
        assertThatThrownBy(() -> policy.checkMethod(method, READABLE))
                .isInstanceOf(AlmAccessPolicy.AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0} is treated as a write, not waved through as harmless")
    @ValueSource(strings = {"HEAD", "OPTIONS"})
    @DisplayName("methods nobody enumerated default to DENY rather than to allow")
    void unenumeratedMethodsDefaultToDeny(String method) {
        // These are read-ish in HTTP terms, and that is exactly the argument that would let a
        // future method slip through. The policy allows GET and denies everything else.
        assertThatThrownBy(() -> policy.checkMethod(method, READABLE))
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
        assertThatThrownBy(() -> policy.checkWrite(READABLE))
                .hasMessageNotContaining("OTHER_TEAM")
                .hasMessageContaining(READABLE.pseudonym());
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
