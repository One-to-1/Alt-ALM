package ai.surgeone.altalm.bff.alm.read;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which ALM projects this BFF may touch, and how. <strong>This is a safety boundary, not a
 * convenience.</strong>
 *
 * <p>The situation it exists for: one API key can reach nine projects in this tenant (probe 16).
 * Exactly one of them — the credentialed project — was designated a disposable sandbox by the user.
 * The other eight are other teams' live projects, granted to Alt-ALM on 2026-08-14 as
 * <em>read-only</em>. Nothing in the ALM API distinguishes those cases: the same key, the same
 * session and the same URL shape will happily {@code PUT} to any of them. The only thing standing
 * between a bug and someone else's production data is this class.
 *
 * <p>So the rule is enforced structurally rather than by discipline:
 *
 * <ul>
 *   <li><strong>Writes: sandbox only.</strong> Not "sandbox by default" — there is no override
 *       parameter, because an override is the thing that eventually gets passed by accident.
 *   <li><strong>Reads: allowlist only.</strong> A project must be explicitly enrolled. An empty
 *       allowlist therefore permits nothing beyond the sandbox, which is the correct posture for a
 *       fresh deployment: this mirrors {@code CLAUDE.md}'s requirement that the record generator
 *       "refuse any target not on an explicit allowlist".
 *   <li><strong>Denials throw, never return false.</strong> A boolean invites an unchecked call
 *       site. An exception cannot be ignored.
 * </ul>
 *
 * <p>Note what is deliberately <em>not</em> here: no "force" flag, no environment variable that
 * relaxes the rule, no bypass for tests. A test that needs to write uses the sandbox like everything
 * else.
 */
public final class AlmAccessPolicy {

    /** Thrown when a call would touch a project in a way the grant does not cover. */
    public static class AccessDeniedException extends SecurityException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    private final AlmProjectRef sandbox;
    private final Set<AlmProjectRef> readable;

    /**
     * @param sandbox       the one writable project — the credentialed one
     * @param readOnlyExtra projects granted read access; the sandbox is always readable and need not
     *                      be listed
     */
    public AlmAccessPolicy(AlmProjectRef sandbox, Set<AlmProjectRef> readOnlyExtra) {
        if (sandbox == null) {
            throw new IllegalArgumentException("a sandbox project is required");
        }
        this.sandbox = sandbox;
        Set<AlmProjectRef> all = new LinkedHashSet<>();
        all.add(sandbox);
        if (readOnlyExtra != null) {
            all.addAll(readOnlyExtra);
        }
        this.readable = Set.copyOf(all);
    }

    /** The conservative default: the credentialed project, and nothing else, readable or writable. */
    public static AlmAccessPolicy sandboxOnly(AlmCredentials credentials) {
        return new AlmAccessPolicy(AlmProjectRef.sandboxOf(credentials), Set.of());
    }

    /**
     * Asserts a read is permitted.
     *
     * @throws AccessDeniedException if the project is not enrolled. The message names the
     *                               {@linkplain AlmProjectRef#pseudonym() pseudonym}, never the real
     *                               project, so that a stack trace in a log or a bug report does not
     *                               disclose a third party's workspace.
     */
    public void checkRead(AlmProjectRef project) {
        if (project == null || !readable.contains(project)) {
            throw new AccessDeniedException(
                    "read denied: " + (project == null ? "<null>" : project.pseudonym())
                            + " is not on the read allowlist. Enrol it in alt-alm.alm.readable-projects "
                            + "if the grant actually covers it.");
        }
    }

    /**
     * Asserts a write is permitted. Only ever true for the sandbox.
     *
     * @throws AccessDeniedException for every other project, including ones that are readable —
     *                               being allowed to read a project says nothing about writing it
     */
    public void checkWrite(AlmProjectRef project) {
        if (!sandbox.equals(project)) {
            throw new AccessDeniedException(
                    "WRITE DENIED: " + (project == null ? "<null>" : project.pseudonym())
                            + " is not the designated sandbox. Writes are permitted to exactly one "
                            + "project; the rest of the tenant is read-only and belongs to other teams.");
        }
    }

    /**
     * Asserts an HTTP method is permitted against a project. The single choke point the entity
     * client calls, so that "is this a write?" is decided in one place rather than at each call site.
     *
     * @param method uppercase HTTP method
     */
    public void checkMethod(String method, AlmProjectRef project) {
        if (method == null) {
            throw new IllegalArgumentException("method is required");
        }
        // Anything that is not a plain read counts as a write. Note HEAD and OPTIONS are absent
        // deliberately: neither is needed, and enumerating only what is used keeps the default deny.
        if ("GET".equalsIgnoreCase(method)) {
            checkRead(project);
            return;
        }
        checkWrite(project);
    }

    public AlmProjectRef sandbox() {
        return sandbox;
    }

    /** Every project this deployment may read, sandbox included. */
    public Set<AlmProjectRef> readableProjects() {
        return readable;
    }

    /** True when this project may be written — for capability flags in the UI, not for gating. */
    public boolean isWritable(AlmProjectRef project) {
        return sandbox.equals(project);
    }
}
