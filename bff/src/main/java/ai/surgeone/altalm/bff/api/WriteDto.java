package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.write.AlmWriteOutcome;
import ai.surgeone.altalm.bff.alm.write.AlmWriteResult;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;

import java.util.List;
import java.util.Map;

/** The SPA's write contract: what a caller sends, and what it learns happened. */
public final class WriteDto {

    private WriteDto() {
    }

    /**
     * A create.
     *
     * @param fields logical field name → value. A value is a <strong>string</strong>, or an
     *               <strong>array of strings</strong> for one of the model's two multi-value fields
     *               ({@code target-rel}, {@code target-rcyc} — probe 33). Order of the map itself is
     *               irrelevant: {@code AlmEntityBody} imposes the canonical field order, because
     *               ALM's is load-bearing and a caller's JSON object order is not something to
     *               depend on. Order <em>within</em> an array is preserved and sent as-is
     */
    public record CreateRequest(Map<String, Object> fields) {
    }

    /**
     * An update.
     *
     * @param fields          the fields to change. ⚠️ Absent fields are left alone, but a field that
     *                        <em>is</em> present is replaced outright — including memo fields, which
     *                        is how a naive comment write erases history (probe 30)
     * @param expectedValues what the caller's view showed for the fields it is changing, in the
     *                        same shape as {@code fields}. Null or empty accepts overwriting a
     *                        concurrent edit. ⚠️ <strong>Not a {@code ver-stamp}.</strong> A stamp
     *                        also moves when someone files a child under this record, so guarding on
     *                        one refused saves where no field differed (probe 34). Baselines for
     *                        fields absent from {@code fields} are ignored rather than refused —
     *                        sending everything the form displayed is a reasonable client. See
     *                        {@code AlmStaleWriteGuard}: this detects a conflict, it does not lock
     */
    public record UpdateRequest(Map<String, Object> fields, Map<String, Object> expectedValues) {
    }

    /**
     * A comment.
     *
     * <p>Separate from {@link UpdateRequest} on purpose. Writing a comment through the generic update
     * path would <em>replace</em> the field and destroy every earlier comment with a 200 response;
     * this request routes to the read-modify-write path instead. The distinction is in the API shape
     * rather than in a caller's memory.
     *
     * @param expectedThread the comment field's value as the caller's view rendered it, or null to
     *                       accept appending onto a thread that has moved. The concurrency baseline
     *                       for a comment is the thread itself: it is the only thing a comment write
     *                       can destroy, and it is what the caller was looking at
     */
    public record CommentRequest(String comment, String author, String expectedThread) {
    }

    /** One validation problem, as the SPA sees it. */
    public record Problem(String field, String code, String detail) {

        static Problem of(AlmWriteValidator.Problem p) {
            return new Problem(p.field(), p.code(), p.detail());
        }
    }

    /**
     * What happened.
     *
     * @param outcome   {@code COMMITTED}, {@code REJECTED} or {@code UNKNOWN}. ⚠️ {@code UNKNOWN} is
     *                  a real third state, not a dressed-up failure: an ALM 5xx may still have
     *                  committed the row
     * @param id        the row's id where one is known
     * @param verified  true when an {@code UNKNOWN} write was resolved by a follow-up query. The
     *                  outcome stays {@code UNKNOWN} even then — "the row exists" and "the write
     *                  succeeded" are different claims and only the first has evidence
     * @param retried   true when the single missing-required-field retry fired, which is the signal
     *                  that this project's metadata is lying about a field (probe 9)
     * @param errorId   ALM's machine-readable code, e.g. {@code qccore.required-field-missing}
     * @param detail    what the caller should do about it
     * @param problems  validation problems, when the body never reached ALM at all
     */
    public record WriteResponse(
            String outcome,
            String id,
            boolean verified,
            boolean retried,
            String errorId,
            String detail,
            List<Problem> problems) {

        /**
         * ⚠️ {@code detail} may carry ALM's own error text, and {@link AlmWriteResult} warns that
         * such text has been observed naming physical column names and — in the site-admin API —
         * third-party identities. Forwarding it is a deliberate decision on the same grounds as
         * {@code /api/projects} returning real project names: the masking rule governs what enters
         * the <em>repository and logs</em>, which outlive the session, not what the authorised
         * operator sees in their own browser. A physical column name is not a secret from the person
         * configuring the project.
         *
         * <p>What that reasoning does <strong>not</strong> cover is logging this field. If a future
         * change writes a rejected write's detail to a log or a bug report, it needs masking first.
         */
        static WriteResponse of(AlmWriteResult result, String detail) {
            return new WriteResponse(
                    result.outcome().name(),
                    result.effectiveId().orElse(null),
                    result.verifiedId().isPresent(),
                    result.retried(),
                    result.errorId(),
                    detail,
                    List.of());
        }

        static WriteResponse rejectedByValidation(List<AlmWriteValidator.Problem> problems) {
            return new WriteResponse(
                    // Not ALM's REJECTED: this body never left the BFF, and conflating the two would
                    // make "ALM refused it" indistinguishable from "we refused to ask".
                    "INVALID", null, false, false, "altalm.validation",
                    "the body was refused before it reached ALM",
                    problems.stream().map(Problem::of).toList());
        }

        boolean isUnresolvedUnknown() {
            return AlmWriteOutcome.UNKNOWN.name().equals(outcome) && !verified;
        }
    }
}
