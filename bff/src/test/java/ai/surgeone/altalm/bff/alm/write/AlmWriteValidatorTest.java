package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stand-in for validation ALM is not running for us.
 *
 * <p>The cases are ordered by what they protect. The ones that matter most are at the bottom and
 * assert an <strong>absence</strong>: this class must not reject a body for a missing "required"
 * field or a non-editable one, because probe 9 established metadata is wrong about both. A validator
 * that helpfully enforced those flags would refuse writes ALM accepts, and the failure would look
 * like an ALM limitation rather than like our own rule.
 */
class AlmWriteValidatorTest {

    private static final AlmProjectRef PROJECT = new AlmProjectRef("D", "SANDBOX");

    private AlmMetadataCatalog metadata;
    private AlmWriteValidator validator;

    @BeforeEach
    void setUp() {
        metadata = mock(AlmMetadataCatalog.class);
        validator = new AlmWriteValidator(metadata);

        when(metadata.fields(any(), eq("requirement"))).thenReturn(List.of(
                field("id", AlmFieldType.NUMBER),
                field("name", AlmFieldType.STRING, 255),
                field("description", AlmFieldType.MEMO, -1),
                field("priority", AlmFieldType.LOOKUP_LIST),
                field("owner", AlmFieldType.USERS_LIST),
                field("parent-id", AlmFieldType.REFERENCE),
                field("target-date", AlmFieldType.DATE),
                field("creation-time", AlmFieldType.DATE_TIME),
                field("estimate", AlmFieldType.NUMBER),
                field("type-id", AlmFieldType.NUMBER),
                virtual("father-name", AlmFieldType.STRING),
                readOnlyButRequiredOnCreate("ref-count", AlmFieldType.NUMBER),
                requiredPerMetadata("req-type", AlmFieldType.LOOKUP_LIST)));
    }

    private static FieldDescriptor field(String name, AlmFieldType type) {
        return field(name, type, 0);
    }

    private static FieldDescriptor field(String name, AlmFieldType type, int size) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                false, true, true, false, false, true, true, false, 0, size);
    }

    /** Computed server-side — the one metadata flag that IS a reliable "do not write this". */
    private static FieldDescriptor virtual(String name, AlmFieldType type) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                false, false, true, true, false, true, true, false, 0, 0);
    }

    /** Probe 9's case: reported neither required nor editable, yet the create fails without it. */
    private static FieldDescriptor readOnlyButRequiredOnCreate(String name, AlmFieldType type) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                false, false, true, false, false, true, true, false, 0, 0);
    }

    private static FieldDescriptor requiredPerMetadata(String name, AlmFieldType type) {
        return new FieldDescriptor(name, name.toUpperCase().replace('-', '_'), type, name,
                true, true, true, false, false, true, true, false, 0, 0);
    }

    private List<AlmWriteValidator.Problem> validate(Map<String, String> body) {
        return validator.validate(PROJECT, "requirement", body);
    }

    private static Map<String, String> body(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static List<String> codes(List<AlmWriteValidator.Problem> problems) {
        return problems.stream().map(AlmWriteValidator.Problem::code).toList();
    }

    // ==========================================================================================

    @Nested
    @DisplayName("fields that do not exist, or are not the caller's to set")
    class FieldIdentity {

        @Test
        @DisplayName("an unknown field is named, rather than becoming a 500 that does not say which")
        void unknownField() {
            List<AlmWriteValidator.Problem> problems = validate(body("nmae", "typo"));

            assertThat(codes(problems)).containsExactly("unknown-field");
            // The message has to point at per-project customization, not at a typo: the same name
            // can be valid in one project and absent in the next.
            assertThat(problems.getFirst().detail()).contains("per-project customization");
        }

        @Test
        @DisplayName("id and ver-stamp are the server's, and saying so beats a confusing ALM error")
        void serverOwnedFields() {
            assertThat(codes(validate(body("id", "7001")))).containsExactly("server-owned");
            assertThat(codes(validate(body("ver-stamp", "3")))).containsExactly("server-owned");
        }

        @Test
        @DisplayName("a virtual field is computed server-side, so a value has nothing to do")
        void virtualIsRefused() {
            assertThat(codes(validate(body("father-name", "Parent")))).containsExactly("not-writable");
        }

        @Test
        @DisplayName("an empty body is refused - it would consume a version and change nothing")
        void emptyBody() {
            assertThat(codes(validate(Map.of()))).containsExactly("empty-body");
            assertThat(codes(validator.validate(PROJECT, "requirement", null)))
                    .containsExactly("empty-body");
        }
    }

    @Nested
    @DisplayName("value shapes ALM's grammar actually cares about")
    class ValueShapes {

        @Test
        @DisplayName("a date must be yyyy-MM-dd, and an impossible date is not one")
        void dates() {
            assertThat(validate(body("target-date", "2026-08-19"))).isEmpty();
            assertThat(codes(validate(body("target-date", "19/08/2026")))).containsExactly("not-a-date");
            // Month 13 is rejected by resolution, not merely by pattern-matching - worth pinning,
            // because a formatter that only matched the shape would wave this through and ALM would
            // answer opaquely.
            assertThat(codes(validate(body("target-date", "2026-13-01")))).containsExactly("not-a-date");
        }

        @Test
        @DisplayName("a DateTime uses a space, not a T - it is not ISO-8601")
        void dateTimes() {
            assertThat(validate(body("creation-time", "2026-08-19 10:30:00"))).isEmpty();
            assertThat(codes(validate(body("creation-time", "2026-08-19T10:30:00"))))
                    .containsExactly("not-a-datetime");
        }

        @Test
        @DisplayName("a number rejects text but ACCEPTS a decimal - integer-ness is unverified")
        void numbers() {
            assertThat(validate(body("estimate", "12"))).isEmpty();
            assertThat(codes(validate(body("estimate", "high")))).containsExactly("not-a-number");
            // The point of this case. Nothing probed established that ALM's Number type is an
            // integer, so parsing as one would be this class inventing a constraint and refusing
            // writes the server may well accept.
            assertThat(validate(body("estimate", "1.5"))).isEmpty();
            assertThat(validate(body("estimate", "-3"))).isEmpty();
        }

        @Test
        @DisplayName("a string longer than its declared size is refused, not silently truncated")
        void stringSize() {
            assertThat(validate(body("name", "x".repeat(255)))).isEmpty();
            assertThat(codes(validate(body("name", "x".repeat(256))))).containsExactly("too-long");
        }

        @Test
        @DisplayName("an empty value is a legitimate write - clearing a field is not a mistake")
        void emptyValuesPass() {
            assertThat(validate(body("target-date", "", "estimate", "", "name", ""))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the memo trap: raw newlines vanish, silently, with a 200")
    class MemoIsHtml {

        @Test
        @DisplayName("plain text with newlines is refused - ALM collapses them to spaces (probe 27)")
        void plainTextWithNewlines() {
            List<AlmWriteValidator.Problem> problems =
                    validate(body("description", "para one" + (char) 10 + "para two"));

            assertThat(codes(problems)).containsExactly("plain-text-memo");
            // The message must name the fix, because the failure is invisible: the write succeeds
            // and the paragraphs are simply gone on the way back.
            assertThat(problems.getFirst().detail()).contains("<br>");
        }

        @Test
        @DisplayName("markup with newlines is fine - those are formatting whitespace between elements")
        void htmlWithNewlinesIsFine() {
            assertThat(validate(body("description",
                    "<p>para one</p>" + (char) 10 + "<p>para two</p>"))).isEmpty();
        }

        @Test
        @DisplayName("plain text on one line is fine - there is nothing to lose")
        void singleLinePlainTextIsFine() {
            assertThat(validate(body("description", "a short note"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("what this validator deliberately does NOT enforce")
    class DeliberateOmissions {

        @Test
        @DisplayName("a metadata-required field may be absent - required is not required-on-create")
        void requiredIsNotEnforced() {
            // req-type is required:true in metadata. Rejecting a create without it would refuse
            // writes ALM accepts; probe 9 is the whole reason this assertion is an isEmpty().
            assertThat(validate(body("name", "ALTALM-x"))).isEmpty();
        }

        @Test
        @DisplayName("a non-editable field may be SENT - probe 9's create fails without exactly one")
        void editableFalseIsNotEnforced() {
            // test-parameter.ref-count is editable:false and required:false, and the create 500s
            // without it. A validator that trusted `editable` would make that write impossible.
            assertThat(validate(body("ref-count", "0"))).isEmpty();
        }

        @Test
        @DisplayName("a lookup value passes through - there is no list client to check it against")
        void lookupMembershipIsNotChecked() {
            // Guessing would be worse than not checking: every Y/N flag is a LookupList too, so a
            // wrong guess rejects correct writes. ALM decides, and its error is clear.
            assertThat(validate(body("priority", "Whatever-4"))).isEmpty();
            assertThat(validate(body("owner", "someone"))).isEmpty();
            assertThat(validate(body("parent-id", "999999"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("every problem is reported at once, so a form is fixed in one pass")
        void notFailFast() {
            List<AlmWriteValidator.Problem> problems = validate(body(
                    "nmae", "typo",
                    "target-date", "19/08/2026",
                    "estimate", "high",
                    "id", "7001"));

            assertThat(codes(problems))
                    .containsExactly("unknown-field", "not-a-date", "not-a-number", "server-owned");
        }

        @Test
        @DisplayName("check() throws and carries all of them")
        void checkThrows() {
            assertThatThrownBy(() -> validator.check(PROJECT, "requirement",
                    body("nmae", "typo", "estimate", "high")))
                    .isInstanceOf(AlmWriteValidator.RejectedException.class)
                    .satisfies(e -> assertThat(
                            ((AlmWriteValidator.RejectedException) e).problems()).hasSize(2));
        }

        @Test
        @DisplayName("a body declaring a type-id validates against that SUBTYPE's narrower field set")
        void subtypeNarrows() {
            // A Folder requirement has no status - validating it against the entity-level set would
            // wave through a field that kind of record cannot hold.
            when(metadata.fields(any(), eq("requirement"), eq("3"))).thenReturn(List.of(
                    field("name", AlmFieldType.STRING, 255),
                    field("type-id", AlmFieldType.NUMBER)));

            assertThat(codes(validate(body("name", "Folder", "type-id", "3", "priority", "2"))))
                    .containsExactly("unknown-field");
            // ...and the same body without the discriminator passes, which is what makes this case
            // about the subtype rather than about `priority` being wrong generally.
            assertThat(validate(body("name", "Folder", "priority", "2"))).isEmpty();
        }

        @Test
        @DisplayName("an EMPTY per-type set falls back rather than rejecting the whole body")
        void emptySubtypeSetFallsBack() {
            // Found by RecordServiceTest, whose stub returned an empty list for an unstubbed
            // subtype - which is exactly what a live per-type read returning nothing looks like.
            // Before the fallback, every field of a valid body came back as unknown-field: a wall
            // of confident, specific, wrong errors aimed at the caller rather than at the metadata
            // read that actually failed.
            when(metadata.fields(any(), eq("requirement"), eq("99"))).thenReturn(List.of());

            assertThat(validate(body("name", "Still valid", "priority", "2"))).isEmpty();
            assertThat(validate(body("name", "Still valid", "type-id", "99"))).isEmpty();
        }
    }
}
