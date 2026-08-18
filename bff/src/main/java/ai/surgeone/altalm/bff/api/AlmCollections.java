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

    /**
     * Entity → collection for the <strong>related</strong> reads that fill detail-pane tabs.
     *
     * <p>Separate from {@link #ENTITY_OF} on purpose. These are not browsable modules — nobody opens
     * a grid of {@code req-traces} — they are the collections a tab queries, and keeping the two
     * lists apart stops a link table drifting into the module allowlist by accident.
     *
     * <p>Every entry is <strong>probe-verified to exist</strong> (probe 23): each returned HTTP 200
     * on the sandbox. ⚠️ {@code bpm-link} is deliberately absent — the obvious pluralisation
     * {@code bpm-links} returns <strong>404</strong>, so ALM's Business Models Linkage tab has no
     * known REST read and {@code AlmRelationSelector}'s "a tab that cannot be filled is not shown"
     * rule drops it. Guessing a name here would produce a tab that 404s on click.
     */
    private static final Map<String, String> RELATED_COLLECTION_OF = Map.of(
            "defect-link", "defect-links",
            "req-trace", "req-traces",
            "requirement-coverage", "requirement-coverages",
            "attachment", "attachments");

    private AlmCollections() {
    }

    /**
     * The collection to query for a related entity, or empty when nothing here can read it.
     *
     * <p>Empty is a normal answer, not a failure: it is what makes a tab disappear rather than
     * appear and fail.
     */
    public static java.util.Optional<String> relatedCollectionOf(String entity) {
        return java.util.Optional.ofNullable(RELATED_COLLECTION_OF.get(entity));
    }

    /** Whether a detail-pane tab backed by this entity can be populated at all. */
    /**
     * The collection to read a tab's rows from — the related list first, then the modules.
     *
     * <p>The fallback is what makes ALM's Test Lab possible. A test set's instances arrive as an
     * ordinary containment relation, but {@code test-instance} is not a <em>link</em> entity, it is
     * a module entity, so a related-only lookup answered "nothing can read this" and the tab was
     * dropped — for a collection the BFF has served all along. The two maps answer different
     * questions ("is this browsable?" vs "can a tab be filled from it?") and only the second one
     * belongs here.
     */
    public static java.util.Optional<String> readCollectionOf(String entity) {
        java.util.Optional<String> related = relatedCollectionOf(entity);
        return related.isPresent() ? related : moduleOf(entity);
    }

    /** Whether a tab backed by this entity can be filled at all. */
    public static boolean isReadable(String entity) {
        return readCollectionOf(entity).isPresent();
    }

    public static boolean isReadableRelated(String entity) {
        return RELATED_COLLECTION_OF.containsKey(entity);
    }

    /** Reverse of {@link #RELATED_COLLECTION_OF}, so a read of either kind can resolve its entity. */
    private static final Map<String, String> RELATED_ENTITY_OF = RELATED_COLLECTION_OF.entrySet()
            .stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getValue,
                    Map.Entry::getKey));

    /**
     * The singular entity for any collection this BFF may read — module or related.
     *
     * <p>Accepts both lists because the grid read path serves both: a tab's rows are shaped by the
     * same code that shapes a module's grid, and giving them separate shaping code to keep the
     * lookups apart would trade a real duplication for a nominal one.
     *
     * <p>The security property is unchanged — the reachable set is still exactly what is written in
     * this file. What the two lists still separate is <em>browsability</em>: {@link #isModule} is
     * what the SPA offers as a module, and {@code req-traces} is not on it.
     *
     * @throws IllegalArgumentException if {@code collection} is on neither list — deliberately not a
     *                                  fallback to a guessed singular
     */
    public static String entityOf(String collection) {
        String entity = ENTITY_OF.get(collection);
        if (entity == null) {
            entity = RELATED_ENTITY_OF.get(collection);
        }
        if (entity == null) {
            throw new IllegalArgumentException(
                    "unknown collection '" + collection + "'; expected one of " + ENTITY_OF.keySet()
                            + " or a related collection " + RELATED_ENTITY_OF.keySet());
        }
        return entity;
    }

    /** Whether this collection is a browsable module, as opposed to a tab's backing collection. */
    public static boolean isModule(String collection) {
        return ENTITY_OF.containsKey(collection);
    }

    /** Reverse of {@link #ENTITY_OF} — the module to open for an entity. */
    private static final Map<String, String> MODULE_OF = ENTITY_OF.entrySet().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getValue,
                    Map.Entry::getKey));

    /**
     * The browsable module for an entity, or empty when this build has none.
     *
     * <p>Drives whether a related row's id is a link. Empty is a normal answer: a
     * {@code requirement-coverage} row can reach a {@code component}, and there is no Components
     * module to open. Better a plain id than a link that goes nowhere.
     */
    public static java.util.Optional<String> moduleOf(String entity) {
        return java.util.Optional.ofNullable(MODULE_OF.get(entity));
    }

    public static boolean isKnown(String collection) {
        return ENTITY_OF.containsKey(collection) || RELATED_ENTITY_OF.containsKey(collection);
    }
}
