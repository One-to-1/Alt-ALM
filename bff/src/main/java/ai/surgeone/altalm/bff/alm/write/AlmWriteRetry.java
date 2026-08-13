package ai.surgeone.altalm.bff.alm.write;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes the probe-verified rule that ALM's field metadata does not fully describe what a write
 * requires.
 *
 * <p>{@code customization/entities/{e}/fields} reports {@code test-parameter.ref-count} as
 * {@code editable:false, required:false}. Creating a test-parameter without it nevertheless fails:
 *
 * <pre>
 * HTTP 500 {"Id":"qccore.general-error",
 *           "Title":"failed converting entity test-parameter to FREC,
 *                    request is missing required field TP_REF_COUNT"}
 * </pre>
 *
 * <p>Sending it anyway succeeds with HTTP 201. So {@code editable:false} does <em>not</em> mean
 * "omit from the body", and {@code required:false} does <em>not</em> mean "optional on create" —
 * the {@code Required} flag describes UI/validation semantics, not the server's own FREC-conversion
 * preconditions (Probe 9).
 *
 * <p>The recovery is narrow on purpose: parse the offending <em>physical</em> field name out of the
 * error, map it back to its logical name via runtime metadata, add it, and retry <strong>once</strong>.
 * A blind retry loop would be a good way to write the same row several times.
 */
public final class AlmWriteRetry {

    private static final Pattern MISSING_REQUIRED_FIELD =
            Pattern.compile("missing required field\\s+([A-Z0-9_]+)");

    private AlmWriteRetry() {
    }

    /**
     * Extracts the physical field name (e.g. {@code TP_REF_COUNT}) from an ALM error body, if the
     * body is the "missing required field" shape.
     *
     * @param errorBody the raw response body of a failed write; may be null
     * @return the physical field name, or empty when this error is something else entirely
     */
    public static Optional<String> missingRequiredPhysicalField(String errorBody) {
        if (errorBody == null || errorBody.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = MISSING_REQUIRED_FIELD.matcher(errorBody);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Whether a failed write is worth exactly one retry with the named field added.
     *
     * <p>Deliberately requires a 5xx: a 4xx carries a different meaning (bad request, permissions)
     * and must not be retried by this path.
     */
    public static boolean isRetryableMissingField(int statusCode, String errorBody) {
        return statusCode >= 500
                && statusCode < 600
                && missingRequiredPhysicalField(errorBody).isPresent();
    }
}
