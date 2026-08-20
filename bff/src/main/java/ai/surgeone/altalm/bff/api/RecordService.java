package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import ai.surgeone.altalm.bff.alm.write.AlmCommentWriter;
import ai.surgeone.altalm.bff.alm.write.AlmEntityBody;
import ai.surgeone.altalm.bff.alm.write.AlmVersionGuard;
import ai.surgeone.altalm.bff.alm.write.AlmWriteClient;
import ai.surgeone.altalm.bff.alm.write.AlmWriteResult;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Record CRUD, and the place an {@code UNKNOWN} write stops being a shrug.
 *
 * <p>{@code AlmWriteClient} deliberately refuses to decide whether a 5xx committed the row: it does
 * not know what identifies the row a caller was trying to write, so it returns {@code UNKNOWN} and
 * offers {@code verify(result, finder)}. This class is where that finder gets written, because here
 * the identifying question is answerable — a create knows the name it sent, an update knows the
 * values it sent, and a delete knows the id.
 *
 * <p>⚠️ <strong>Verification is best-effort and says so.</strong> Names are not unique in ALM, so a
 * create whose name matches two rows resolves nothing and stays {@code UNKNOWN}. That is the honest
 * answer: picking the first match would invent a fact, and the caller's next step (refresh and look)
 * differs from "here is your row" in a way that matters.
 */
@Service
public class RecordService {

    /**
     * Collections this API will write. A subset of {@link AlmCollections}'s read allowlist, and
     * narrower for reasons rather than caution.
     *
     * <p>{@code attachments} is absent because an attachment is not a JSON entity: it needs a
     * hand-built multipart body with {@code ref-subtype=1}, whose construction is client-stack
     * dependent (PS7's {@code -Form} produced a body this server rejects). {@code runs} is absent
     * because runs <strong>cannot be created directly</strong> — {@code POST runs} fails
     * definitively, and the only working route is a status {@code PUT} on a test-instance that makes
     * the server synthesize a {@code Fast_Run}. Offering either here would be an endpoint that
     * always fails.
     */
    private static final Set<String> WRITABLE_COLLECTIONS = Set.of(
            "requirements", "tests", "defects", "design-steps",
            "test-sets", "test-instances", "test-folders", "test-set-folders",
            "releases", "release-cycles", "release-folders",
            "defect-links", "req-traces", "requirement-coverages");

    private final AlmWriteClient writes;
    private final AlmEntityClient reads;
    private final AlmMetadataCatalog metadata;
    private final AlmWriteValidator validator;
    private final AlmCommentWriter comments;
    private final AlmAccessPolicy policy;

    public RecordService(AlmWriteClient writes, AlmEntityClient reads, AlmMetadataCatalog metadata,
                         AlmWriteValidator validator, AlmCommentWriter comments,
                         AlmAccessPolicy policy) {
        this.writes = writes;
        this.reads = reads;
        this.metadata = metadata;
        this.validator = validator;
        this.comments = comments;
        this.policy = policy;
    }

    /**
     * Asserts the write is permitted before anything else looks at the project.
     *
     * <p>⚠️ Not redundant with {@link AlmWriteClient}'s own check, which stays the real boundary and
     * runs immediately before the I/O. This one is about the <em>message</em>: validation reads
     * metadata, metadata is access-checked for READS, so without this a POST to a project nobody
     * enrolled was refused with "read denied" — an accurate 403 that names the wrong operation and
     * sends the operator looking at the wrong setting.
     */
    private void checkWritable(AlmProjectRef project) {
        policy.checkWrite(project);
    }

    // ==========================================================================================

    /** Creates one record. */
    public WriteDto.WriteResponse create(AlmProjectRef project, String collection,
                                         Map<String, List<String>> fields) {
        checkWritable(project);
        String entity = writableEntityOf(collection);
        validator.check(project, entity, fields);

        AlmWriteResult result = writes.create(project, collection, bodyOf(entity, fields));

        // The identifying question for a create: does a row with the name we sent exist now? Only
        // answerable when a name was sent and matches exactly one row — see the class javadoc.
        String name = firstOf(fields, "name");
        if (result.needsVerification() && name != null && !name.isBlank()) {
            result = writes.verify(result, () -> singleIdWhere(project, collection, "name", name));
        }
        return WriteDto.WriteResponse.of(result, detailFor(result, "create"));
    }

    /**
     * Updates one record.
     *
     * @param expectedVersion the {@code ver-stamp} the caller read, or empty to accept overwriting a
     *                        concurrent edit. Checked against a read taken immediately beforehand —
     *                        detection, not locking; see {@link AlmVersionGuard}
     */
    public WriteDto.WriteResponse update(AlmProjectRef project, String collection, String id,
                                         Map<String, List<String>> fields,
                                         Optional<String> expectedVersion) {
        checkWritable(project);
        String entity = writableEntityOf(collection);
        validator.check(project, entity, fields);

        if (expectedVersion.filter(v -> !v.isBlank()).isPresent()) {
            AlmVersionGuard.check(expectedVersion, currentVersionOf(project, collection, id));
        }

        AlmWriteResult result = writes.update(project, collection, id, bodyOf(entity, fields));

        if (result.needsVerification()) {
            result = writes.verify(result, () -> valuesLanded(project, collection, entity, id, fields)
                    ? Optional.of(id)
                    : Optional.empty());
        }
        return WriteDto.WriteResponse.of(result, detailFor(result, "update"));
    }

    /**
     * Deletes one record.
     *
     * <p>⚠️ Deleting a container is not deleting its contents. ALM does not cascade the way callers
     * assume — an OTA folder delete left the tests inside it orphaned (probe 8) — so a caller
     * removing a tree works bottom-up or leaves rows behind that nothing lists.
     */
    public WriteDto.WriteResponse delete(AlmProjectRef project, String collection, String id) {
        checkWritable(project);
        writableEntityOf(collection);
        AlmWriteResult result = writes.delete(project, collection, id);

        // The one verification that is unambiguous: a deleted row is one that is no longer there.
        if (result.needsVerification()) {
            result = writes.verify(result, () -> rowExists(project, collection, id)
                    ? Optional.empty()
                    : Optional.of(id));
        }
        return WriteDto.WriteResponse.of(result, detailFor(result, "delete"));
    }

    /**
     * Adds a comment without destroying the ones already there.
     *
     * <p>Delegates to {@link AlmCommentWriter} rather than composing the merge here, because the
     * merge is the entire safety property: a memo PUT replaces the field, so a comment written
     * through {@link #update} deletes the record's whole comment history and answers 200 (probe 30).
     */
    public WriteDto.WriteResponse comment(AlmProjectRef project, String collection, String id,
                                          String author, String comment,
                                          Optional<String> expectedVersion) {
        checkWritable(project);
        String entity = writableEntityOf(collection);
        AlmWriteResult result = comments.addComment(project, collection, entity, id, author, comment,
                expectedVersion);
        return WriteDto.WriteResponse.of(result, detailFor(result, "comment"));
    }

    /** The comment field for an entity, so the SPA knows whether to offer a comment box at all. */
    public Optional<String> commentField(AlmProjectRef project, String collection) {
        return comments.commentFieldOf(project, writableEntityOf(collection));
    }

    // ==========================================================================================

    private static AlmEntityBody bodyOf(String entity, Map<String, List<String>> fields) {
        AlmEntityBody body = AlmEntityBody.of(entity);
        // Insertion order is irrelevant — AlmEntityBody re-ranks into the canonical order. Passing
        // the map through unchanged keeps that the single place the rule lives.
        fields.forEach(body::setAll);
        return body;
    }

    /** A field's first value, or null when it has none. For the single-value questions below. */
    private static String firstOf(Map<String, List<String>> fields, String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Rejects a collection this API will not write, naming it as a decision rather than a 404.
     *
     * @throws IllegalArgumentException for a collection that is readable but not writable, which is
     *                                  a different answer from one that does not exist
     */
    private static String writableEntityOf(String collection) {
        if (!AlmCollections.isKnown(collection)) {
            throw new IllegalArgumentException("unknown collection '" + collection + "'");
        }
        if (!WRITABLE_COLLECTIONS.contains(collection)) {
            throw new IllegalArgumentException(
                    "'" + collection + "' is readable but not writable through this API — "
                            + "attachments need a multipart body and runs cannot be created "
                            + "directly (a status PUT on a test-instance makes ALM synthesize one)");
        }
        return AlmCollections.entityOf(collection);
    }

    private String currentVersionOf(AlmProjectRef project, String collection, String id) {
        return row(project, collection, id, List.of("id", "ver-stamp"))
                .flatMap(r -> r.first("ver-stamp"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no " + collection + " row with id " + id + " — it may have been deleted"));
    }

    private boolean rowExists(AlmProjectRef project, String collection, String id) {
        return row(project, collection, id, List.of("id")).isPresent();
    }

    /**
     * Whether the values we sent are what the record now holds.
     *
     * <p>⚠️ Memo fields are excluded from the comparison, and their exclusion is not laziness: ALM
     * re-serialises a memo on the way in — wrapping a fragment in {@code <html><body>},
     * canonicalising {@code <br>} to {@code <br />} and applying whatever output sanitisation the
     * project is configured for (probe 27). A byte comparison would report a perfectly successful
     * write as unverified, every time.
     *
     * @return false when nothing comparable was sent, which correctly leaves the write UNKNOWN
     *         rather than claiming a verification that never happened
     */
    private boolean valuesLanded(AlmProjectRef project, String collection, String entity, String id,
                                 Map<String, List<String>> sent) {
        Map<String, FieldDescriptor> known = new LinkedHashMap<>();
        metadata.fields(project, entity).forEach(f -> known.put(f.name(), f));

        List<String> comparable = sent.keySet().stream()
                .filter(name -> {
                    FieldDescriptor d = known.get(name);
                    return d != null && d.type() != AlmFieldType.MEMO;
                })
                .toList();
        if (comparable.isEmpty()) {
            return false;
        }

        Optional<AlmEntityPage.AlmEntity> found = row(project, collection, id, comparable);
        if (found.isEmpty()) {
            return false;
        }
        for (String name : comparable) {
            // ⚠️ Compares EVERY value, not just the first. A multi-value write that landed only its
            // first value would otherwise verify as successful — which is precisely the corrupted
            // outcome probe 33 was run to rule out, and the one worth catching if it ever appears.
            List<String> expected = (sent.get(name) == null ? List.<String>of() : sent.get(name))
                    .stream().map(v -> v == null ? "" : v.trim()).toList();
            List<String> actual = found.get().all(name).stream().map(String::trim).toList();
            if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private Optional<AlmEntityPage.AlmEntity> row(AlmProjectRef project, String collection,
                                                  String id, List<String> fields) {
        AlmEntityPage page = reads.page(project, collection, AlmQuery.none()
                .filter("id", id)
                .fields(fields.toArray(String[]::new))
                .pageSize(1));
        return page.entities().isEmpty() ? Optional.empty() : Optional.of(page.entities().getFirst());
    }

    /**
     * The id of the one row matching a field, or empty when zero or several match.
     *
     * <p>Several is deliberately empty rather than "the first": names are not unique, and a verify
     * that picks arbitrarily would attach a stranger's id to the caller's write.
     */
    private Optional<String> singleIdWhere(AlmProjectRef project, String collection, String field,
                                           String value) {
        AlmEntityPage page;
        try {
            page = reads.page(project, collection,
                    AlmQuery.none().filter(field, value).fields("id", field).pageSize(2));
        } catch (UnsupportedOperationException e) {
            // AlmQuery refuses values containing ALM's own grammar characters (; [ ] }) rather than
            // inventing an escaping scheme the server does not document. An unverifiable name is a
            // fine outcome here; a wrongly-escaped filter matching other rows is not.
            return Optional.empty();
        }
        return page.entities().size() == 1 ? page.entities().getFirst().id() : Optional.empty();
    }

    /** Says what the caller should do, in the one case where that is genuinely not obvious. */
    private static String detailFor(AlmWriteResult result, String operation) {
        return switch (result.outcome()) {
            case COMMITTED -> result.retried()
                    ? "committed on the second attempt — this project's metadata does not report a "
                            + "field the server requires (probe 9)"
                    : "committed";
            case REJECTED -> result.errorTitle().isBlank()
                    ? "ALM refused the " + operation
                    : result.errorTitle();
            case UNKNOWN -> result.verifiedId().isPresent()
                    ? "ALM returned a server error, but a follow-up query found the record — the "
                            + operation + " appears to have taken effect. Do not retry it"
                    : "ALM returned a server error and the outcome is genuinely unknown: the "
                            + operation + " may still have taken effect. Re-read the record before "
                            + "retrying — retrying blind is how duplicates get made";
        };
    }
}
