package ai.surgeone.altalm.bff.alm.write;

import java.util.Optional;

/**
 * Turns the <em>physical</em> column name in an ALM error back into a logical field name and a value
 * safe to send for it.
 *
 * <p>Exists for one specific recovery (probe 9). ALM answers some creates with:
 *
 * <pre>
 * HTTP 500 "failed converting entity test-parameter to FREC,
 *           request is missing required field TP_REF_COUNT"
 * </pre>
 *
 * <p>The error names {@code TP_REF_COUNT}; the write body speaks {@code ref-count}. Only runtime
 * metadata knows they are the same field, so {@link AlmWriteClient} cannot do this mapping itself
 * without dragging the metadata cache into the write path.
 *
 * <p>⚠️ The <em>value</em> is part of the resolution, not an afterthought. Metadata reports
 * {@code ref-count} as {@code editable:false, required:false} — it is lying on both counts — so
 * there is no "the user supplied it" path to fall back on. Something has to choose a value, and a
 * String default sent for a Number column is a second 500 that looks exactly like the first.
 *
 * <p>Implementations must be side-effect free: this runs inside a failed write's recovery, where a
 * surprise second failure is at its most expensive.
 */
@FunctionalInterface
public interface AlmFieldResolver {

    /**
     * @param entity       the Core entity type being written, e.g. {@code test-parameter}
     * @param physicalName the column named by the server, e.g. {@code TP_REF_COUNT}
     * @return the logical name and a type-appropriate default, or empty when this deployment's
     *         metadata does not describe the column at all — in which case the caller must
     *         <strong>not</strong> guess, and the original error stands
     */
    Optional<Resolved> byPhysicalName(String entity, String physicalName);

    /**
     * @param logicalName  the name to put in the write body, e.g. {@code ref-count}
     * @param defaultValue what to send. Empty string is a legitimate choice for a String column and
     *                     a bad one for a Number, which is exactly why this is resolved from the
     *                     field's declared type rather than hardcoded here
     */
    record Resolved(String logicalName, String defaultValue) {
        public Resolved {
            if (logicalName == null || logicalName.isBlank()) {
                throw new IllegalArgumentException("logicalName is required");
            }
            defaultValue = defaultValue == null ? "" : defaultValue;
        }
    }

    /**
     * A resolver that never resolves anything.
     *
     * <p>The correct default for a deployment with no metadata wired in: it disables the retry
     * rather than making it guess. A retry that invents a field name is worse than no retry, because
     * the first one at least fails visibly.
     */
    static AlmFieldResolver none() {
        return (entity, physicalName) -> Optional.empty();
    }
}
