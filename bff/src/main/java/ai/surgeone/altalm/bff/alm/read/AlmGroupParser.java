package ai.surgeone.altalm.bff.alm.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses ALM's server-side group-by envelope ({@code GET {collection}/groups/{field}}) into a
 * {@link AlmGroup} tree.
 *
 * <p>Deliberately has <strong>no</strong> HTTP dependency, so the whole parse path is testable
 * against the redacted fixtures in {@code tests/fixtures/grids/} with no server and no
 * credentials — the same P0 test-harness requirement {@link
 * ai.surgeone.altalm.bff.alm.metadata.AlmMetadataParser} was built to, and this class follows that
 * one's shape and Jackson-3 usage ({@code tools.jackson.*}, unchecked exceptions) deliberately, plus
 * {@link AlmEntityParser}'s sibling shape in this same package.
 *
 * <p>The verified shape (probe 15 §15.2), on the plain (non-{@code alm-web}) media type:
 * <pre>{@code
 * {"subLevel":[{"subLevel":[],"Expression":"1","ReferenceValue":"Folder","Name":"type-id",
 *               "Value":"1","size":1}]}
 * }</pre>
 *
 * <p>An empty result is {@code {"subLevel":[]}} — a legitimate "no groups" answer, distinct from a
 * malformed payload that does not even have a {@code subLevel} array, which throws instead (mirrors
 * {@link AlmEntityParser}'s "empty entities is legitimate, missing entities is not" rule).
 */
public final class AlmGroupParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlmGroupParser() {
    }

    /**
     * @param json raw response body of a {@code groups/{field}} read
     * @return the top-level groups, in server order, each with its {@link AlmGroup#subGroups()}
     *         parsed recursively
     * @throws IllegalArgumentException if the payload is not the expected envelope shape
     */
    public static List<AlmGroup> parseGroups(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty group-by payload");
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode subLevel = root.path("subLevel");
        if (!subLevel.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected group-by shape: expected {\"subLevel\":[...]}, got top-level keys "
                            + fieldNamesOf(root));
        }

        List<AlmGroup> groups = new ArrayList<>(subLevel.size());
        for (JsonNode g : subLevel) {
            groups.add(parseGroup(g));
        }
        return List.copyOf(groups);
    }

    private static AlmGroup parseGroup(JsonNode g) {
        String name = g.path("Name").asString("");
        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "group with no \"Name\": " + g.toString());
        }

        String value = g.path("Value").asString("");
        String expression = g.path("Expression").asString("");
        int size = g.path("size").asInt(0);

        // ReferenceValue may be JSON null (the captured "status" fixture is exactly that) — mapped
        // to a Java null rather than the literal string "null", mirroring AlmEntityParser's handling
        // of a null "value" node. Callers fall back to Value via AlmGroup#displayOrValue().
        JsonNode refNode = g.path("ReferenceValue");
        String displayValue = (refNode.isNull() || refNode.isMissingNode())
                ? null
                : refNode.asString("");

        JsonNode subLevel = g.path("subLevel");
        if (!subLevel.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected group shape: expected \"subLevel\" to be an array on group '" + name
                            + "', got keys " + fieldNamesOf(g));
        }

        // Recursive by construction — inferred from the shape, not probe-verified beyond one level
        // (see AlmGroup#subGroups() javadoc): no captured fixture nests deeper than one level.
        List<AlmGroup> subGroups = new ArrayList<>(subLevel.size());
        for (JsonNode sub : subLevel) {
            subGroups.add(parseGroup(sub));
        }

        return new AlmGroup(name, value, displayValue, expression, size, subGroups);
    }

    private static List<String> fieldNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
