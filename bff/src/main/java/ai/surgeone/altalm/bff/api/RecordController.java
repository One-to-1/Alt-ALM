package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.write.AlmVersionGuard;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * The write half of the SPA's API — and the only controller in this package that has one.
 *
 * <p>Kept apart from {@link GridController} deliberately. That class's javadoc says every mapping on
 * it is a {@code @GetMapping} and that this is load-bearing; the way to keep that true once writes
 * exist is a separate, obviously-named controller rather than a mixed one whose read-only claim
 * quietly stops holding.
 *
 * <p>Everything here reaches ALM through {@code AlmWriteClient}, via {@link RecordService}. That is
 * asserted by {@code ApiIsReadOnlyTest}, which fails the build on a write mapping that cannot — the
 * one place the sandbox rule, the canonical field order, the 5xx-is-UNKNOWN rule and the single
 * missing-required-field retry are all enforced together.
 *
 * <h2>Status codes, and the one that needs explaining</h2>
 *
 * <ul>
 *   <li>{@code 201}/{@code 200} — committed.
 *   <li>{@code 422} — refused by {@link AlmWriteValidator} before anything left the BFF. The body
 *       carries every problem, not the first one.
 *   <li>{@code 400} — ALM refused it. The body carries ALM's own error id.
 *   <li>{@code 409} — the record changed since the caller read it ({@link AlmVersionGuard}).
 *   <li>{@code 502} — <strong>the outcome is unknown</strong>. ALM returned a server error and a
 *       follow-up query could not establish whether the row exists. ⚠️ The status describes what the
 *       upstream did, <em>not</em> what happened to the row: the write may well have committed. The
 *       response body is the authority, and it says {@code "outcome": "UNKNOWN"} for exactly that
 *       reason. A client that treats 502 as "it failed, retry" will create duplicates.
 * </ul>
 *
 * <p>An {@code UNKNOWN} that verification <em>did</em> resolve returns {@code 200} with the outcome
 * still reported as {@code UNKNOWN}: the caller can proceed, and the body preserves the distinction
 * between "the row is there" and "the write succeeded".
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService records;
    private final AlmCredentials credentials;

    public RecordController(RecordService records, AlmCredentials credentials) {
        this.records = records;
        this.credentials = credentials;
    }

    /** Creates one record. 201 on a committed write. */
    @PostMapping("/{collection}")
    public ResponseEntity<WriteDto.WriteResponse> create(@PathVariable String collection,
                                                         @RequestParam(required = false) String project,
                                                         @RequestBody WriteDto.CreateRequest request) {
        WriteDto.WriteResponse response =
                records.create(resolve(project), collection, fieldsOf(request.fields()));
        return respond(response, HttpStatus.CREATED);
    }

    /**
     * Updates one record.
     *
     * <p>⚠️ Not a patch-by-omission safety net: a field present in the body is <em>replaced</em>,
     * memo fields included. Adding a comment through here would delete the record's comment history,
     * which is why {@link #comment} exists as a separate route rather than as a convention.
     */
    @PutMapping("/{collection}/{id}")
    public ResponseEntity<WriteDto.WriteResponse> update(@PathVariable String collection,
                                                         @PathVariable String id,
                                                         @RequestParam(required = false) String project,
                                                         @RequestBody WriteDto.UpdateRequest request) {
        WriteDto.WriteResponse response = records.update(resolve(project), collection, id,
                fieldsOf(request.fields()), Optional.ofNullable(request.expectedVersion()));
        return respond(response, HttpStatus.OK);
    }

    /**
     * Deletes one record.
     *
     * <p>⚠️ No cascade. Deleting a folder does not delete what is inside it (probe 8), and ALM
     * reports no such thing — a caller clearing a subtree works bottom-up or leaves rows nothing
     * lists.
     */
    @DeleteMapping("/{collection}/{id}")
    public ResponseEntity<WriteDto.WriteResponse> delete(@PathVariable String collection,
                                                         @PathVariable String id,
                                                         @RequestParam(required = false) String project) {
        return respond(records.delete(resolve(project), collection, id), HttpStatus.OK);
    }

    /**
     * Adds a comment, preserving the ones already there.
     *
     * <p>A route of its own because the obvious implementation — PUT the new text into the comment
     * field — destroys every earlier comment, including ones written in the stock client, and answers
     * HTTP 200 (probe 30). Read-modify-write happens server-side so there is one implementation to be
     * right rather than one per caller.
     */
    @PostMapping("/{collection}/{id}/comments")
    public ResponseEntity<WriteDto.WriteResponse> comment(@PathVariable String collection,
                                                          @PathVariable String id,
                                                          @RequestParam(required = false) String project,
                                                          @RequestBody WriteDto.CommentRequest request) {
        if (request.comment() == null || request.comment().isBlank()) {
            throw new IllegalArgumentException("a comment is required");
        }
        // ⚠️ The author is whatever the caller says it is, and cannot be anything better yet. Every
        // write goes out under one service-account API key (ADR 0004), so ALM's own identity for
        // this record is the same for all users and says nothing about who typed the comment.
        // Alt-ALM's app-level user model is what will eventually supply this; until then the banner
        // carries a claim, not an authenticated identity, and the fallback says so plainly rather
        // than borrowing the service account's name and implying otherwise.
        String author = request.author() == null || request.author().isBlank()
                ? "Alt-ALM"
                : request.author();
        WriteDto.WriteResponse response = records.comment(resolve(project), collection, id, author,
                request.comment(), Optional.ofNullable(request.expectedVersion()));
        return respond(response, HttpStatus.OK);
    }

    /**
     * The comment field's name for this collection, or 404 when it has none.
     *
     * <p>Read-only, and here rather than on {@link GridController} because it answers a question only
     * the write path raises: whether to offer a comment box at all. The name is per entity and does
     * not track the physical column — a requirement's is {@code comments}, a defect's is
     * {@code dev-comments} (probe 30) — so it is discovered, never assumed.
     */
    @GetMapping("/{collection}/comment-field")
    public ResponseEntity<Map<String, String>> commentField(@PathVariable String collection,
                                                            @RequestParam(required = false) String project) {
        return records.commentField(resolve(project), collection)
                .map(field -> ResponseEntity.ok(Map.of("field", field)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ==========================================================================================

    private static ResponseEntity<WriteDto.WriteResponse> respond(WriteDto.WriteResponse response,
                                                                  HttpStatus onCommitted) {
        if ("REJECTED".equals(response.outcome())) {
            return ResponseEntity.badRequest().body(response);
        }
        if (response.isUnresolvedUnknown()) {
            // ⚠️ 502 says the upstream failed. It does NOT say the row is unwritten — see the class
            // javadoc. The body's outcome is what a client must branch on.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }
        return ResponseEntity.status(onCommitted).body(response);
    }

    private static Map<String, String> fieldsOf(Map<String, String> fields) {
        // A null body member and an empty one mean the same thing to the validator, which has a
        // clearer message for it than a NullPointerException here would.
        return fields == null ? Map.of() : fields;
    }

    /** Parses {@code DOMAIN/PROJECT}, defaulting to the credentialed project. */
    private AlmProjectRef resolve(String project) {
        if (project == null || project.isBlank()) {
            return AlmProjectRef.sandboxOf(credentials);
        }
        int slash = project.indexOf('/');
        if (slash <= 0 || slash == project.length() - 1) {
            throw new IllegalArgumentException("project must be 'DOMAIN/PROJECT'");
        }
        return new AlmProjectRef(project.substring(0, slash), project.substring(slash + 1));
    }

    // ---- errors -------------------------------------------------------------------------------

    /** Refused before anything left the BFF. Every problem, so a form is fixed in one pass. */
    @ExceptionHandler(AlmWriteValidator.RejectedException.class)
    public ResponseEntity<WriteDto.WriteResponse> invalid(AlmWriteValidator.RejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(WriteDto.WriteResponse.rejectedByValidation(e.problems()));
    }

    /**
     * The record moved since the caller read it.
     *
     * <p>⚠️ A 409 here means the conflict was <em>detected</em>, not that concurrent writes are
     * prevented. ALM has no optimistic locking (probe 31) and a write landing between the check and
     * the request is still lost.
     */
    @ExceptionHandler(AlmVersionGuard.ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(AlmVersionGuard.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "version-conflict", "detail", e.getMessage()));
    }

    /** The policy's message names a pseudonym, so a 403 cannot be used to enumerate the tenant. */
    @ExceptionHandler(AlmAccessPolicy.AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AlmAccessPolicy.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "access-denied", "detail", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, UnsupportedOperationException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad-request", "detail", e.getMessage()));
    }
}
