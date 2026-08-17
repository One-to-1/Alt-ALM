package ai.surgeone.altalm.bff.api;

import java.util.Map;

/**
 * The one allowlist of collections this BFF will talk to, and their singular entity names.
 *
 * <p><strong>An allowlist, not a lookup table with a fallback.</strong> Two reasons it is closed
 * rather than derived by trimming a trailing "s".
 *
 * <p>First, correctness: the metadata endpoint wants the singular, and a collection whose name does
 * not resolve produces a grid with <em>no columns</em> rather than an error — a silent,
 * plausible-looking failure of exactly the kind this project keeps finding.
 *
 * <p>Second, and the reason there is no fallback at all: {@code collection} arrives as a path
 * variable from the browser and is interpolated into the ALM request URL. Accepting anything that
 * merely ends in "s" would let a caller aim the BFF's authenticated session at arbitrary REST paths.
 * An allowlist means the set of things a request can reach is the set written here.
 *
 * <p>It lives in its own class because more than one service needs it, and a security boundary
 * copied into two places is a security boundary that will eventually differ between them.
 */
public final class AlmCollections {

    private static final Map<String, String> ENTITY_OF = Map.ofEntries(
            Map.entry("requirements", "requirement"),
            Map.entry("tests", "test"),
            Map.entry("defects", "defect"),
            Map.entry("test-sets", "test-set"),
            Map.entry("test-instances", "test-instance"),
            Map.entry("runs", "run"),
            Map.entry("design-steps", "design-step"),
            Map.entry("test-folders", "test-folder"),
            Map.entry("test-set-folders", "test-set-folder"),
            Map.entry("releases", "release"),
            Map.entry("release-cycles", "release-cycle"),
            Map.entry("release-folders", "release-folder"),
            Map.entry("resource-folders", "resource-folder"),
            Map.entry("bpm-folders", "bpm-folder"),
            Map.entry("run-steps", "run-step"),
            Map.entry("test-configs", "test-config"));

    private AlmCollections() {
    }

    /**
     * @throws IllegalArgumentException if {@code collection} is not on the allowlist — deliberately
     *                                  not a fallback to a guessed singular
     */
    public static String entityOf(String collection) {
        String entity = ENTITY_OF.get(collection);
        if (entity == null) {
            throw new IllegalArgumentException(
                    "unknown collection '" + collection + "'; expected one of " + ENTITY_OF.keySet());
        }
        return entity;
    }

    public static boolean isKnown(String collection) {
        return ENTITY_OF.containsKey(collection);
    }
}
