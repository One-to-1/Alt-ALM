package ai.surgeone.altalm.bff.alm.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code {collection}/{id}/audits} responses. <strong>No HTTP dependency</strong>, so the
 * parse path is testable offline against a fixture, like every other parser here.
 *
 * <h2>The shape, and the trap in it</h2>
 *
 * <pre>
 * {"Audits":{"TotalResults":3,"Audit":[
 *    {"Id":1,"ParentId":42,"ParentType":"TEST","Action":"UPDATE",
 *     "Time":"2026-01-02 09:15:00","User":"…",
 *     "Properties":{"Property":[{"Name":"status","Label":"Status",
 *                                "OldValue":"…","NewValue":"…"}]}}]}}
 * </pre>
 *
 * <p>PascalCase, like the {@code customization/relations} endpoint and unlike the lowerCamel
 * {@code fields} one.
 *
 * <p>⚠️ <strong>Both {@code Audit} and {@code Property} collapse to a bare object when there is
 * exactly one of them</strong> — the classic XML-to-JSON single-element collapse. This is not a
 * theoretical hazard: probe 24 counted it across 119 records in a live project and found
 * {@code Audit} as an object <strong>4</strong> times against 115 arrays, and {@code Property} as an
 * object <strong>464</strong> times against 129 arrays — the collapsed form is the <em>common</em>
 * one. A parser written from a single pretty sample would read as correct, pass review, and then
 * throw on the majority of real records. {@link #each} is the whole defence, and it is why this
 * parser exists rather than a Jackson binding.
 *
 * <p>A third case: {@code Properties} may be absent entirely (85 of 678 entries), which is an audit
 * entry recording that something changed without recording what. Those are kept, with no changes,
 * rather than dropped — an entry we cannot explain is still evidence the record was touched.
 */
public final class AlmAuditParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlmAuditParser() {
    }

    /**
     * @param json raw response body
     * @return entries in server order; empty when ALM recorded nothing for this record
     * @throws IllegalArgumentException when the payload is not an audits envelope at all — an empty
     *                                  list would read as "this record was never changed", which is a
     *                                  different and unearned claim
     */
    public static List<AlmAudit> parseAudits(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty audits payload");
        }
        JsonNode audits = MAPPER.readTree(json).path("Audits");
        if (!audits.isObject()) {
            throw new IllegalArgumentException(
                    "unexpected audits shape: expected {\"Audits\":{…}}");
        }

        List<AlmAudit> out = new ArrayList<>();
        for (JsonNode entry : each(audits, "Audit")) {
            List<AlmAudit.Change> changes = new ArrayList<>();
            for (JsonNode property : each(entry.path("Properties"), "Property")) {
                String field = property.path("Name").asString("");
                if (field.isBlank()) {
                    continue;
                }
                changes.add(new AlmAudit.Change(
                        field,
                        property.path("Label").asString(""),
                        property.path("OldValue").asString(""),
                        property.path("NewValue").asString("")));
            }
            out.add(new AlmAudit(
                    entry.path("Id").asString(""),
                    entry.path("Action").asString(""),
                    entry.path("Time").asString(""),
                    entry.path("User").asString(""),
                    changes));
        }
        return List.copyOf(out);
    }

    /**
     * The children under {@code key}, whether ALM sent an array, a single object, or nothing.
     *
     * <p>The single-object case is the one that matters — see the class comment for how often it
     * actually occurs.
     */
    private static List<JsonNode> each(JsonNode parent, String key) {
        JsonNode node = parent.path(key);
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>(node.size());
            node.forEach(out::add);
            return out;
        }
        return node.isObject() ? List.of(node) : List.of();
    }
}
