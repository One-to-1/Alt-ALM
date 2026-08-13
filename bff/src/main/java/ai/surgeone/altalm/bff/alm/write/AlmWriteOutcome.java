package ai.surgeone.altalm.bff.alm.write;

/**
 * The outcome of an ALM write, with a third state that most HTTP clients do not model.
 *
 * <p>A probe-verified hazard: <strong>an HTTP 5xx may still have committed the row.</strong> During
 * write round 1 a request returned 500 and the entity existed afterwards
 * ({@code alm-api-reference.md} 3.3, Probe 4). Treating 5xx as "failed" and retrying therefore
 * risks creating duplicates; treating it as "succeeded" risks losing the record.
 *
 * <p>The only correct handling is to treat 5xx as {@link #UNKNOWN} and resolve it by querying for
 * the row. Nothing in the BFF may collapse {@code UNKNOWN} into success or failure without that
 * verification step.
 */
public enum AlmWriteOutcome {

    /** 2xx. The server confirmed the write. */
    COMMITTED,

    /**
     * 5xx, or a transport failure after the request was sent. The row may or may not exist.
     * Resolve with a query before deciding anything; never retry blind.
     */
    UNKNOWN,

    /** 4xx. The server rejected the request and did not write. Safe to treat as a clean failure. */
    REJECTED;

    /**
     * Classifies a response status. Anything 5xx is {@link #UNKNOWN}, never {@code REJECTED} —
     * that distinction is the entire point of this type.
     */
    public static AlmWriteOutcome fromStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return COMMITTED;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return REJECTED;
        }
        // 5xx and anything else unexpected: assume nothing.
        return UNKNOWN;
    }

    /** True when the caller must issue a verification query before reporting a result. */
    public boolean requiresVerification() {
        return this == UNKNOWN;
    }
}
