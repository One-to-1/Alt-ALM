package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmList;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a write body against <em>this project's</em> field metadata before it reaches ALM.
 *
 * <p><strong>This layer exists because ALM's own validation is switched off for us.</strong> Workflow
 * scripts — the VBScript hooks where an ALM project puts its field rules, its auto-population and its
 * "you cannot close a defect without a fix comment" logic — are bypassed on REST writes by default
 * ({@code CLIENT_TYPES_BYPASS_REST_WF}). A record Alt-ALM writes therefore skips every check a record
 * created in the stock client passes through. Whatever validation exists here is the only validation
 * there is.
 *
 * <p>⚠️ <strong>And it is necessarily incomplete.</strong> The bypassed scripts are arbitrary VBScript
 * belonging to each deployment; nothing can reproduce them from the outside. This class enforces what
 * metadata actually states, and CLAUDE.md records the gap as a permanent, documented limitation
 * rather than something a later slice will close. Do not let this class's existence read as "writes
 * are validated".
 *
 * <h2>What is deliberately NOT checked, and why</h2>
 *
 * <ul>
 *   <li><strong>Required-on-create.</strong> {@link FieldDescriptor#required()} does not mean
 *       "required on create" and {@link FieldDescriptor#editable()} does not mean "may be sent" —
 *       {@code test-parameter.ref-count} is reported {@code required:false, editable:false} yet the
 *       create fails without it (probe 9). Rejecting a body for a missing "required" field would
 *       refuse writes ALM accepts, and accepting one because nothing was flagged would not prevent
 *       the failure. The server's own {@code missing required field} error is the only reliable
 *       signal, and {@link AlmWriteRetry} handles it.
 *   <li><strong>Reference and user targets.</strong> Whether an id or a username exists is a query,
 *       not a metadata fact, and one per field per write is a cost this cannot justify against an
 *       error the server already returns clearly.
 * </ul>
 *
 * <h2>Lookup lists ARE checked, as of 2026-08-20 — carefully</h2>
 *
 * <p>{@link AlmFieldType#LOOKUP_LIST} values are validated against the project's own lists, which
 * closes the most likely source of ALM rejections: a user typing free text into what is really a
 * fixed set. Three deliberate softenings, because this is the check most able to do harm by being
 * wrong:
 *
 * <ul>
 *   <li><strong>An unreadable list set validates nothing.</strong> {@code AlmMetadataCatalog.lists}
 *       answers empty when it cannot read them, and empty means <em>do not judge</em>. A degraded
 *       metadata read must not become a refusal of every lookup value.
 *   <li><strong>A list with no items validates nothing.</strong> Three of the sandbox's 39 have
 *       none; treating "no items" as "nothing is permitted" would refuse every write to a field
 *       bound to one.
 *   <li><strong>{@code listId == 0} validates nothing</strong> — the field is list-typed but
 *       unbound, so there is no set to check against.
 * </ul>
 *
 * <p>⚠️ Every one of those is the same rule: <strong>when the evidence is absent, let ALM decide.</strong>
 * The cost of a wrong rejection here is a field the user cannot fill in at all, with an error
 * blaming their input.
 */
public final class AlmWriteValidator {

    /** ALM's Date literal format — probe-verified, other formats fail (api-ref §4.1). */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** ALM's DateTime literal format. Note the space, not a {@code T}: this is not ISO-8601. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /**
     * Fields the server owns outright. Sending one is a caller mistake worth naming rather than
     * forwarding: {@code id} is assigned on create and addressed in the URL on update, and
     * {@code ver-stamp} is a server-maintained counter (probe 31) that means nothing coming the
     * other way.
     */
    private static final Set<String> SERVER_OWNED = Set.of("id", "ver-stamp");

    private final AlmMetadataCatalog metadata;

    public AlmWriteValidator(AlmMetadataCatalog metadata) {
        this.metadata = metadata;
    }

    /**
     * One thing wrong with one field.
     *
     * @param field  the logical field name, or empty for a whole-body problem
     * @param code   a stable machine-readable code, so the SPA can react without parsing prose
     * @param detail what is wrong, phrased for the person who will have to fix it
     */
    public record Problem(String field, String code, String detail) {
    }

    /** Raised when a body cannot be sent. Carries every problem, not just the first. */
    public static class RejectedException extends RuntimeException {

        private final transient List<Problem> problems;

        public RejectedException(List<Problem> problems) {
            super(problems.size() + " field problem(s): " + problems.stream()
                    .map(p -> p.field() + " " + p.code())
                    .toList());
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }

    /**
     * Validates and throws, which is the entry point every write should use.
     *
     * @throws RejectedException with every problem found — deliberately not fail-fast, so a caller
     *                           fixing a form is not sent round the loop once per field
     */
    public void check(AlmProjectRef project, String entity, Map<String, List<String>> fields) {
        List<Problem> problems = validate(project, entity, fields);
        if (!problems.isEmpty()) {
            throw new RejectedException(problems);
        }
    }

    /**
     * Every problem with this body, in field order. Empty means nothing here is knowably wrong —
     * which is not the same as "this write will succeed" (see the class javadoc).
     */
    public List<Problem> validate(AlmProjectRef project, String entity,
                                  Map<String, List<String>> fields) {
        List<Problem> problems = new ArrayList<>();

        if (fields == null || fields.isEmpty()) {
            problems.add(new Problem("", "empty-body",
                    "a write with no fields would replace nothing and still consume a version — "
                            + "send at least one field"));
            return problems;
        }

        // Per-type when the body declares one: a subtype's field set is NARROWER than the entity's
        // (a Folder requirement has no status), so validating a folder against the entity-level set
        // would wave through a field that record cannot hold. Falls back to the entity set when the
        // project has no subtypes, which is the common case — only `requirement` has any (probe 25).
        List<String> typeIds = fields.get("type-id");
        Map<String, FieldDescriptor> known = describe(project, entity,
                typeIds == null || typeIds.isEmpty() ? null : typeIds.get(0));

        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue() == null ? List.of() : entry.getValue();

            if (SERVER_OWNED.contains(name)) {
                problems.add(new Problem(name, "server-owned",
                        name + " is assigned by the server and cannot be written"));
                continue;
            }

            FieldDescriptor field = known.get(name);
            if (field == null) {
                // Same reasoning as the grid's filter check: field sets are per-project
                // customization, so an unknown name is a real answer about THIS project rather than
                // a typo to guess at. Naming it here beats ALM's 500, which does not say which field.
                problems.add(new Problem(name, "unknown-field",
                        "no field '" + name + "' on " + entity + " in this project — field sets are "
                                + "per-project customization, so check this project's metadata"));
                continue;
            }

            if (field.virtual()) {
                // Unlike required/editable, virtual IS a reliable signal: it means computed
                // server-side, so there is nothing for a value to do.
                problems.add(new Problem(name, "not-writable",
                        name + " is computed server-side and never accepts a value"));
                continue;
            }

            // ⚠️ The ONE metadata flag this validator enforces, and the exception is earned.
            // `required` and `editable` are deliberately ignored because probe 9 showed they do not
            // describe what a write needs. `supportsMultivalue` is different: probe 33 checked it
            // against actual behaviour on both fields that carry it. Letting a second value through
            // on a single-value field does not produce a clean refusal — ALM stores something, and
            // which value survives is not something this code should be discovering in production.
            if (values.size() > 1 && !field.supportsMultivalue()) {
                problems.add(new Problem(name, "not-multi-value",
                        name + " holds one value; " + values.size() + " were sent"));
                continue;
            }

            for (String each : values) {
                problemWithValue(field, each).ifPresent(problems::add);
                lookupProblem(project, field, each).ifPresent(problems::add);
            }
        }

        return problems;
    }

    private Map<String, FieldDescriptor> describe(AlmProjectRef project, String entity, String typeId) {
        List<FieldDescriptor> descriptors = metadata.fields(project, entity);

        if (typeId != null && !typeId.isBlank()) {
            List<FieldDescriptor> perType = metadata.fields(project, entity, typeId);
            // ⚠️ Only narrow to a NON-EMPTY per-type set. An empty one means the subtype lookup told
            // us nothing, and treating "nothing" as "this record has no fields" would reject every
            // field of a perfectly valid body as unknown-field - a wall of confident, specific,
            // wrong errors pointing at the caller instead of at the metadata read.
            //
            // AlmMetadataCatalog already falls back when the per-type fetch THROWS. This covers the
            // other shape: a fetch that succeeds and returns nothing. Found by a test whose own
            // stub returned an empty list, which is exactly what the live failure would look like.
            if (!perType.isEmpty()) {
                descriptors = perType;
            }
        }

        Map<String, FieldDescriptor> byName = new LinkedHashMap<>();
        for (FieldDescriptor d : descriptors) {
            byName.put(d.name(), d);
        }
        return byName;
    }

    /** The per-type checks. Empty values pass everywhere: clearing a field is a legitimate write. */
    private static Optional<Problem> problemWithValue(FieldDescriptor field, String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String name = field.name();

        switch (field.type()) {
            case NUMBER -> {
                // BigDecimal, NOT Long: whether ALM's Number type accepts a decimal is UNVERIFIED,
                // and parsing as an integer would quietly add a constraint no probe established -
                // refusing writes the server may well take. This rejects only text that is not a
                // number at all, which is the part we can actually claim.
                // Experiment that would settle it: PUT a decimal into a Number field on the sandbox
                // and read it back.
                try {
                    new java.math.BigDecimal(value.trim());
                } catch (NumberFormatException e) {
                    return Optional.of(new Problem(name, "not-a-number",
                            "'" + value + "' is not a number"));
                }
            }
            case DATE -> {
                if (unparseable(value, DATE)) {
                    return Optional.of(new Problem(name, "not-a-date",
                            "'" + value + "' is not yyyy-MM-dd. ALM's date grammar accepts no other "
                                    + "format, and a rejected literal comes back as an opaque error"));
                }
            }
            case DATE_TIME -> {
                if (unparseable(value, DATE_TIME)) {
                    return Optional.of(new Problem(name, "not-a-datetime",
                            "'" + value + "' is not 'yyyy-MM-dd HH:mm:ss' — note the space; ALM's "
                                    + "DateTime literal is not ISO-8601 and a 'T' will not parse"));
                }
            }
            case MEMO -> {
                return memoProblem(name, value);
            }
            case STRING -> {
                // size is the declared column width; -1 means unlimited. Checked here because the
                // server's own answer to an overlong string is unverified, and finding out by
                // truncating live data is not the way to verify it.
                if (field.size() > 0 && value.length() > field.size()) {
                    return Optional.of(new Problem(name, "too-long",
                            name + " declares a size of " + field.size() + " characters; this is "
                                    + value.length() + ". What ALM does with an overlong string is "
                                    + "UNVERIFIED - refusing here beats finding out by truncating "
                                    + "live data"));
                }
            }
            // USERS_LIST and REFERENCE stay pass-through; LOOKUP_LIST is handled separately by
            // lookupProblem(), which needs the project to resolve the list.
            default -> {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a lookup value is one the project's list permits.
     *
     * <p>See the class javadoc: every "cannot tell" path returns empty, because a wrong rejection
     * here makes a field unfillable and blames the user for it.
     */
    private Optional<Problem> lookupProblem(AlmProjectRef project, FieldDescriptor field,
                                            String value) {
        if (field.type() != AlmFieldType.LOOKUP_LIST || value == null || value.isEmpty()
                || field.listId() == 0) {
            return Optional.empty();
        }
        Optional<AlmList> list = metadata.list(project, field.listId());
        if (list.isEmpty() || list.get().items().isEmpty() || list.get().permits(value)) {
            return Optional.empty();
        }
        List<String> permitted = list.get().values();
        // The permitted values go in the message. This is the one validation failure where the user
        // cannot work out the answer themselves - a free-text box gives no hint what is legal.
        String shown = permitted.size() <= 8
                ? String.join(", ", permitted)
                : String.join(", ", permitted.subList(0, 8)) + ", … (" + permitted.size() + " total)";
        return Optional.of(new Problem(field.name(), "not-in-list",
                "'" + value + "' is not one of the values " + field.name() + " accepts: " + shown));
    }

    /**
     * The probe-27 trap: a memo field is HTML and only HTML.
     *
     * <p>Everything written to one is parsed as HTML and re-serialised into a full
     * {@code <html><body>} document, and <strong>raw newlines are collapsed to spaces rather than
     * converted to {@code <br>}</strong>. So plain text with line breaks arrives as a single run-on
     * paragraph, is stored that way, and reads back that way — a silent flattening with a 200
     * response and nothing to notice.
     *
     * <p>Rejected rather than converted on the caller's behalf. Converting would mean guessing which
     * newlines were meaningful, and a guess that is wrong is indistinguishable from the bug it was
     * trying to prevent. A body that already contains markup is left alone: newlines between elements
     * are formatting whitespace and lose nothing.
     */
    private static Optional<Problem> memoProblem(String name, String value) {
        boolean hasNewline = value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        boolean looksLikeHtml = value.indexOf('<') >= 0;
        if (hasNewline && !looksLikeHtml) {
            return Optional.of(new Problem(name, "plain-text-memo",
                    name + " is a memo field, which ALM stores as HTML: a raw newline is collapsed "
                            + "to a space, so the paragraphs would be lost silently. Send <br> or "
                            + "<p> elements instead"));
        }
        return Optional.empty();
    }

    private static boolean unparseable(String value, DateTimeFormatter format) {
        try {
            format.parse(value.trim());
            return false;
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
