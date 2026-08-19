package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Adds a comment to a record without destroying the ones already there.
 *
 * <p><strong>This class exists because the obvious implementation is a data-loss bug.</strong> A
 * memo PUT <em>replaces</em> the field — there is no server-side append (probe 30) — so an "add a
 * comment" box that sends only the new text deletes the record's entire comment history, including
 * comments other people wrote in the stock client, and gets HTTP 200 for it. Nothing in the response
 * says anything was lost.
 *
 * <p>So every write here is read-modify-write, and it lives in the BFF rather than the SPA so there
 * is one implementation to be right rather than one per caller.
 *
 * <p>⚠️ <strong>Read-modify-write inherits the lost update, and this class narrows it rather than
 * fixing it.</strong> Probe 31 established ALM offers no optimistic locking: {@code ver-stamp}
 * increments on every write (including memo writes) but a <em>stale</em> one is accepted and the
 * write lands, so the server cannot be asked to refuse a conflicting update. What it can be asked is
 * what the current version is — so this re-reads immediately before the PUT and refuses when it
 * moved.
 *
 * <p>The residual race is real and must not be described away: a write landing between that check
 * and the PUT is still lost. This converts "silent data loss, always" into "detected in all but a
 * very short window". It is not a lock.
 */
public final class AlmCommentWriter {

    /**
     * Candidate names for "the comment field", in preference order.
     *
     * <p>⚠️ Probed, not assumed (probe 30). The name differs per entity <em>and does not track the
     * physical name</em>: a requirement's {@code comments} is physically {@code RQ_DEV_COMMENTS},
     * while a defect's is {@code dev-comments}/{@code BG_DEV_COMMENTS}. This list is the search
     * order; metadata decides which one this entity actually has.
     */
    private static final List<String> CANDIDATES =
            List.of("dev-comments", "comments", "user-comments", "remarks");

    private final AlmEntityClient reads;
    private final AlmWriteClient writes;
    private final AlmMetadataCatalog metadata;

    public AlmCommentWriter(AlmEntityClient reads, AlmWriteClient writes,
                            AlmMetadataCatalog metadata) {
        this.reads = reads;
        this.writes = writes;
        this.metadata = metadata;
    }

    /**
     * The comment field's logical name for an entity, from this project's metadata.
     *
     * @return empty when the entity has no comment field at all, which is a legitimate answer and
     *         not an error — callers should not offer a comment box for such an entity
     */
    public Optional<String> commentFieldOf(AlmProjectRef project, String entity) {
        List<String> present = metadata.fields(project, entity).stream()
                .map(f -> f.name())
                .toList();
        return CANDIDATES.stream().filter(present::contains).findFirst();
    }

    /**
     * Appends a comment, preserving everything already in the field.
     *
     * @param expectedVersion the {@code ver-stamp} the caller's view was built on, or empty to skip
     *                        the check. ⚠️ Passing empty is "I accept overwriting a concurrent
     *                        edit", not "there is no concurrency" — it is spelled as an explicit
     *                        {@link Optional} so that choice is visible at the call site
     * @throws AlmVersionGuard.ConflictException if the record moved on since {@code expectedVersion}
     */
    public AlmWriteResult addComment(AlmProjectRef project, String collection, String entity,
                                     String id, String author, String comment,
                                     Optional<String> expectedVersion) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("an empty comment would rewrite the field for nothing");
        }
        String field = commentFieldOf(project, entity).orElseThrow(() ->
                new IllegalArgumentException(entity + " has no comment field in this project"));

        // One read for both the current value and the current version. Two reads would open a race
        // between them and make the check guard a value it did not fetch.
        AlmEntityPage.AlmEntity row = readRow(project, collection, id, field);
        String existing = row.first(field).orElse("");
        String version = row.first("ver-stamp").orElse("");

        AlmVersionGuard.check(expectedVersion, version);

        String merged = AlmCommentBanner.append(existing, author, comment, LocalDate.now());

        AlmWriteResult result = writes.update(project, collection, id,
                AlmEntityBody.of(entity).set(field, merged));

        // ⚠️ An UNKNOWN here is worse than an UNKNOWN on a create. A create that may or may not have
        // happened leaves a row to find; this may or may not have REPLACED the field, and retrying
        // blind could double the comment or lose the merge. Resolved by asking what the field now
        // holds rather than by assuming either way.
        return writes.verify(result, () -> {
            String now = readRow(project, collection, id, field).first(field).orElse("");
            return now.contains(stripTags(comment)) ? Optional.of(id) : Optional.empty();
        });
    }

    private AlmEntityPage.AlmEntity readRow(AlmProjectRef project, String collection, String id,
                                            String field) {
        AlmEntityPage page = reads.page(project, collection,
                AlmQuery.none().filter("id", id).fields("id", "ver-stamp", field).pageSize(1));
        if (page.entities().isEmpty()) {
            throw new IllegalArgumentException(
                    "no " + collection + " row with id " + id + " — it may have been deleted");
        }
        return page.entities().get(0);
    }

    /**
     * The comment's text without markup, for locating it in a stored memo.
     *
     * <p>Needed because what goes in is not byte-identical to what comes back: ALM re-serialises the
     * document, canonicalising {@code <br>} to {@code <br />} and inserting newlines of its own
     * (probe 27). Comparing the escaped form would report a successful write as unverified.
     */
    private static String stripTags(String text) {
        return text.replaceAll("<[^>]*>", "").trim();
    }
}
