package ai.surgeone.altalm.bff.alm.write;

import java.util.Optional;

/**
 * What happened to one write, including the case where nobody knows.
 *
 * <p>This is a record rather than a bare id-or-exception because ALM has a third outcome that most
 * HTTP clients do not model: <strong>an HTTP 5xx may still have committed the row</strong>
 * ({@link AlmWriteOutcome}). A method signature returning {@code String id} or throwing forces that
 * third state to be flattened into one of the other two at the moment it is least knowable.
 *
 * <p>⚠️ There is deliberately <strong>no</strong> {@code isSuccess()}. The whole point is that
 * {@link AlmWriteOutcome#UNKNOWN} is neither, and a convenience boolean is exactly how it would get
 * quietly bucketed with one of them.
 *
 * @param outcome    what the status code tells us, and no more
 * @param id         the new/affected row's id when the server confirmed one. Empty for
 *                   {@code UNKNOWN} <em>even after a successful verification</em> — see
 *                   {@link #verifiedAs} for why that is a separate field
 * @param verifiedId the id a follow-up query found for an {@code UNKNOWN} write, if one was run
 * @param errorId    ALM's machine-readable error code, e.g. {@code qccore.required-field-missing}
 * @param errorTitle ALM's human-readable message. ⚠️ Safe to show a user only after masking —
 *                   ALM error text has been observed carrying physical column names and, in the
 *                   site-admin API, third-party identities
 * @param retried    true when the single missing-required-field retry (probe 9) was used, so the
 *                   caller can tell "worked" from "worked on the second try", which is the signal
 *                   that project metadata is lying about a field
 */
public record AlmWriteResult(
        AlmWriteOutcome outcome,
        Optional<String> id,
        Optional<String> verifiedId,
        String errorId,
        String errorTitle,
        boolean retried) {

    public AlmWriteResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        id = id == null ? Optional.empty() : id;
        verifiedId = verifiedId == null ? Optional.empty() : verifiedId;
        errorId = errorId == null ? "" : errorId;
        errorTitle = errorTitle == null ? "" : errorTitle;
    }

    public static AlmWriteResult committed(String id, boolean retried) {
        return new AlmWriteResult(AlmWriteOutcome.COMMITTED, Optional.ofNullable(id),
                Optional.empty(), "", "", retried);
    }

    public static AlmWriteResult rejected(String errorId, String errorTitle, boolean retried) {
        return new AlmWriteResult(AlmWriteOutcome.REJECTED, Optional.empty(), Optional.empty(),
                errorId, errorTitle, retried);
    }

    public static AlmWriteResult unknown(String errorId, String errorTitle, boolean retried) {
        return new AlmWriteResult(AlmWriteOutcome.UNKNOWN, Optional.empty(), Optional.empty(),
                errorId, errorTitle, retried);
    }

    /**
     * The same UNKNOWN result, now carrying what a verification query found.
     *
     * <p>⚠️ The outcome stays {@code UNKNOWN} on purpose. A verified-present row was still written by
     * a request the server reported as failed, and the difference matters to anything that retries:
     * "the row exists" is not the same claim as "the write succeeded", and only the first is
     * evidence. Callers that just need to know whether to write again should read
     * {@link #verifiedId()}.
     */
    public AlmWriteResult verifiedAs(String foundId) {
        return new AlmWriteResult(outcome, id, Optional.ofNullable(foundId), errorId, errorTitle,
                retried);
    }

    /** True while an {@code UNKNOWN} write has not yet been resolved by a query. */
    public boolean needsVerification() {
        return outcome.requiresVerification() && verifiedId.isEmpty();
    }

    /**
     * The id to work with, whichever way it was established.
     *
     * <p>Present for a committed write, and for an unknown one that verification located.
     */
    public Optional<String> effectiveId() {
        return id.isPresent() ? id : verifiedId;
    }
}
