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
 * <p>⚠️ <strong>The sandbox-only write rule was lifted by the user on 2026-08-18.</strong> Writes
 * previously succeeded against the credentialed project and nothing else, with no override
 * parameter at all. That is no longer the rule, and this javadoc is the record of the change rather
 * than a description of something that quietly stopped being true. What replaced it:
 *
 * <ul>
 *   <li><strong>Writes follow the same allowlist as reads.</strong> A project the operator enrolled
 *       may be written; the sandbox is no longer a special case. The read/write distinction is gone,
 *       the enrolled/not-enrolled one is not.
 *   <li><strong>Reads: allowlist only</strong>, unchanged. A project must be explicitly enrolled. An
 *       empty allowlist permits nothing beyond the credentialed project, which stays the posture of
 *       a fresh deployment and mirrors {@code CLAUDE.md}'s requirement that the record generator
 *       "refuse any target not on an explicit allowlist".
 *   <li><strong>Denials throw, never return false.</strong> A boolean invites an unchecked call
 *       site. An exception cannot be ignored.
 * </ul>
 *
 * <p>⚠️ <strong>What this costs, stated plainly so it is not rediscovered later.</strong> Enrolling a
 * project for reading now also makes it writable. The tenant's other projects were granted to
 * Alt-ALM read-only on 2026-08-14 and are other teams' live data; nothing in this class distinguishes
 * that grant from a sandbox any more. If those projects become reachable again, the only remaining
 * control is which projects are enrolled in {@code alt-alm.alm.readable-projects} — so that setting
 * is now load-bearing in a way it was not before.
 *
 * <p>Still deliberately <em>not</em> here: a "force" flag or a bypass for tests. Enrolment is the
 * one lever, and it is an operator's explicit act.
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
        // ⚠️ NOT Set.copyOf. Its iteration order is randomised per JVM run (ImmutableCollections
        // salts its hash order deliberately), so the project list came back in a different order
        // after every restart — the dropdown reshuffled itself, and a screenshot harness selecting
        // by index silently pointed at a different project between two runs. Insertion order is the
        // operator's configured order, which is the one worth keeping.
        this.readable = java.util.Collections.unmodifiableSet(all);
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
     * Asserts a write is permitted — true for any enrolled project since 2026-08-18.
     *
     * <p>Kept as a separate method from {@link #checkRead} even though the two now apply the same
     * test. They are different questions that happen to share an answer, and collapsing them would
     * mean a future decision to re-separate them has nowhere to live: every write call site would
     * already be calling {@code checkRead}, and finding them again is the hard part.
     *
     * @throws AccessDeniedException if the project is not enrolled
     */
    public void checkWrite(AlmProjectRef project) {
        if (project == null || !readable.contains(project)) {
            throw new AccessDeniedException(
                    "WRITE DENIED: " + (project == null ? "<null>" : project.pseudonym())
                            + " is not on the allowlist. Enrol it in alt-alm.alm.readable-projects "
                            + "if it is genuinely a target for this deployment.");
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
        // Since 2026-08-18 both branches apply the same allowlist, so this routes rather than gates —
        // but it stays, because the day the two diverge again this is the only place to change.
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
        return project != null && readable.contains(project);
    }

    /**
     * True for the one project the credentials name.
     *
     * <p>Retained after the write rule was lifted because two things still need it: the metadata
     * field resolver binds to a single project's schema, and it remains the sane default target when
     * a caller has not said which project it means.
     */
    public boolean isSandbox(AlmProjectRef project) {
        return sandbox.equals(project);
    }
}
