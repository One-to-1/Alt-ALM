package ai.surgeone.altalm.bff.alm.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the write-safety rules. Every case here corresponds to a hazard that was
 * observed live against the sandbox, not to a hypothetical.
 */
class AlmWriteSafetyTest {

    @Nested
    @DisplayName("Fields-array order is load-bearing (Probe 4)")
    class FieldOrder {

        @Test
        @DisplayName("name first, relational ids next, type discriminators last")
        void canonicalOrder() {
            AlmEntityBody body = AlmEntityBody.of("test")
                    .set("subtype-id", "MANUAL")
                    .set("description", "<html><body>x</body></html>")
                    .set("parent-id", "42")
                    .set("name", "ALTALM-PROBE-x");

            assertThat(body.orderedFieldNames())
                    .containsExactly("name", "parent-id", "description", "subtype-id");
        }

        @Test
        @DisplayName("serialization is byte-identical regardless of insertion order")
        void insertionOrderDoesNotChangeOutput() {
            String a = AlmEntityBody.of("test")
                    .set("name", "n").set("parent-id", "1").set("subtype-id", "MANUAL").toJson();
            String b = AlmEntityBody.of("test")
                    .set("subtype-id", "MANUAL").set("parent-id", "1").set("name", "n").toJson();

            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("emits the Core write shape")
        void coreWireShape() {
            String json = AlmEntityBody.of("test-parameter")
                    .set("name", "altalm_p1").set("ref-count", "0").toJson();

            assertThat(json).isEqualTo(
                    "{\"Fields\":["
                            + "{\"Name\":\"name\",\"values\":[{\"value\":\"altalm_p1\"}]},"
                            + "{\"Name\":\"ref-count\",\"values\":[{\"value\":\"0\"}]}"
                            + "],\"Type\":\"test-parameter\"}");
        }

        @Test
        @DisplayName("re-setting a field keeps its position, so retries stay order-safe")
        void resetKeepsPosition() {
            AlmEntityBody body = AlmEntityBody.of("test")
                    .set("name", "a").set("parent-id", "1").set("name", "b");

            assertThat(body.orderedFieldNames()).containsExactly("name", "parent-id");
            assertThat(body.toJson()).contains("\"value\":\"b\"");
        }

        @Test
        @DisplayName("equal-rank fields keep insertion order")
        void stableWithinRank() {
            List<String> names = AlmEntityBody.of("defect")
                    .set("zeta", "1").set("alpha", "2").set("middle", "3")
                    .orderedFieldNames();

            assertThat(names).containsExactly("zeta", "alpha", "middle");
        }
    }

    @Nested
    @DisplayName("A 5xx may still have committed the row (Probe 4)")
    class Outcomes {

        @Test
        void fiveXxIsUnknownNeverRejected() {
            assertThat(AlmWriteOutcome.fromStatus(500)).isEqualTo(AlmWriteOutcome.UNKNOWN);
            assertThat(AlmWriteOutcome.fromStatus(503)).isEqualTo(AlmWriteOutcome.UNKNOWN);
            assertThat(AlmWriteOutcome.fromStatus(500).requiresVerification()).isTrue();
        }

        @Test
        void successAndClientErrorAreDefinite() {
            assertThat(AlmWriteOutcome.fromStatus(201)).isEqualTo(AlmWriteOutcome.COMMITTED);
            assertThat(AlmWriteOutcome.fromStatus(400)).isEqualTo(AlmWriteOutcome.REJECTED);
            assertThat(AlmWriteOutcome.fromStatus(401)).isEqualTo(AlmWriteOutcome.REJECTED);
            assertThat(AlmWriteOutcome.fromStatus(201).requiresVerification()).isFalse();
            assertThat(AlmWriteOutcome.fromStatus(400).requiresVerification()).isFalse();
        }
    }

    @Nested
    @DisplayName("Metadata does not describe write requirements (Probe 9)")
    class MissingRequiredField {

        /** Verbatim body observed from the sandbox when ref-count was omitted. */
        private static final String REF_COUNT_500 = """
                {"Id":"qccore.general-error",\
                "Title":"failed converting entity test-parameter to FREC, \
                request is missing required field TP_REF_COUNT",\
                "ExceptionProperties":[],"StackTrace":null}""";

        @Test
        void extractsThePhysicalFieldName() {
            assertThat(AlmWriteRetry.missingRequiredPhysicalField(REF_COUNT_500))
                    .contains("TP_REF_COUNT");
        }

        @Test
        void retryableOnlyOn5xx() {
            assertThat(AlmWriteRetry.isRetryableMissingField(500, REF_COUNT_500)).isTrue();
            // Same body, client-error status: a different problem, must not take this path.
            assertThat(AlmWriteRetry.isRetryableMissingField(400, REF_COUNT_500)).isFalse();
        }

        @Test
        void unrelatedErrorsAreNotRetryable() {
            String other = "{\"Id\":\"qccore.unknown-field-name\","
                    + "\"Title\":\"Entity: test-parameter doesn't have a field named: 'value'\"}";

            assertThat(AlmWriteRetry.missingRequiredPhysicalField(other)).isEmpty();
            assertThat(AlmWriteRetry.isRetryableMissingField(500, other)).isFalse();
            assertThat(AlmWriteRetry.missingRequiredPhysicalField(null)).isEmpty();
        }
    }
}
