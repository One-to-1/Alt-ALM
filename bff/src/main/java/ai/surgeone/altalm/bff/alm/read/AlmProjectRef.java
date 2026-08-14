package ai.surgeone.altalm.bff.alm.read;

import ai.surgeone.altalm.bff.alm.session.AlmCredentials;

/**
 * A domain/project pair — the unit Alt-ALM addresses when it reads.
 *
 * <p>Until 2026-08-14 the BFF only ever talked to one project, so {@link AlmCredentials#projectBase()}
 * was sufficient. That changed when the user granted read access to the tenant's other projects for
 * P1 validation: the sandbox holds 1 requirement and 0 tests, while a sibling project holds 233
 * requirements, 129 tests and 178 runs (probe 16). Reading those requires naming a project that is
 * <strong>not</strong> the credentialed one, which is exactly the capability that needs guarding —
 * see {@link AlmAccessPolicy}.
 *
 * <p>⚠️ A project name is not a harmless identifier here. These are other teams' live projects in a
 * shared tenant, so a name is their data: it is masked in output, pseudonymised in documentation
 * (`PROJECT-5`), and the real values live only in the git-ignored credentials directory. Hence
 * {@link #toString()} refuses to render, on the same reasoning as {@code AlmCredentials}.
 *
 * @param domain  ALM domain
 * @param project ALM project within that domain
 */
public record AlmProjectRef(String domain, String project) {

    public AlmProjectRef {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
    }

    /** The project this BFF's credentials authenticate against — the only writable one. */
    public static AlmProjectRef sandboxOf(AlmCredentials credentials) {
        return new AlmProjectRef(credentials.domain(), credentials.project());
    }

    /** Base path for project-scoped Core REST calls against this project. */
    public String restBase(String qcbinBaseUrl) {
        return qcbinBaseUrl + "/rest/domains/" + domain + "/projects/" + project;
    }

    /**
     * Refuses to render, because a project name identifies a third party's workspace. Use
     * {@link #pseudonym()} in anything a human or a log will read.
     */
    @Override
    public String toString() {
        return "AlmProjectRef[domain=<redacted>, project=<redacted>]";
    }

    /**
     * A stable, non-identifying label safe for logs and error messages.
     *
     * <p>Derived from a hash rather than a counter so that the same project yields the same label
     * across processes, which is what makes a log line correlatable without being disclosive.
     */
    public String pseudonym() {
        return "PROJECT-" + Integer.toHexString((domain + '/' + project).hashCode()).toUpperCase();
    }
}
