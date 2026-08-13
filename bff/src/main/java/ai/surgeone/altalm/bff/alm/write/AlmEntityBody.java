package ai.surgeone.altalm.bff.alm.write;

// Jackson 3 (Spring Boot 4 / Spring Framework 7). The package moved from
// com.fasterxml.jackson.* to tools.jackson.*, and its exceptions are now unchecked.
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an ALM Core entity write body with a <strong>deterministic</strong> field order.
 *
 * <p>This exists because of a probe-verified hazard: ALM's {@code Fields} array order is
 * behaviourally load-bearing. The same logical data serialized in a different member order
 * produces different server outcomes, including opaque NPE-style HTTP 500s
 * (see {@code docs/research/live-probe-log.md}, Probe 4, and {@code alm-api-reference.md} 3.2).
 * Relying on hash-map iteration order would make writes fail non-deterministically between runs.
 *
 * <p>The canonical order proven stable across probe rounds is:
 * <ol>
 *   <li>{@code name}</li>
 *   <li>relational ids ({@code parent-id}, {@code owner-id}, ...)</li>
 *   <li>everything else, in insertion order</li>
 *   <li>type/subtype discriminators last ({@code type-id}, {@code subtype-id})</li>
 * </ol>
 *
 * <p>Instances are mutable builders and are <em>not</em> thread-safe; build one per request.
 */
public final class AlmEntityBody {

    /** Emitted first: the server appears to key off it when resolving the rest of the body. */
    private static final String NAME = "name";

    /** Emitted last: discriminators must follow the fields they discriminate. */
    private static final List<String> TYPE_DISCRIMINATORS = List.of("type-id", "subtype-id", "type");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String entityType;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private AlmEntityBody(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType is required");
        }
        this.entityType = entityType;
    }

    /** @param entityType the Core entity type, e.g. {@code test-parameter}, {@code design-step}. */
    public static AlmEntityBody of(String entityType) {
        return new AlmEntityBody(entityType);
    }

    /**
     * Sets a field. Null values are permitted and serialize as an empty string, because ALM has no
     * documented null-literal in the Core write grammar.
     *
     * <p>Re-setting a field keeps its original position, which is what makes the retry in
     * {@link AlmWriteRetry} order-safe.
     */
    public AlmEntityBody set(String logicalName, String value) {
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        fields.put(logicalName, value == null ? "" : value);
        return this;
    }

    public boolean has(String logicalName) {
        return fields.containsKey(logicalName);
    }

    /** Field names in the exact order they will be serialized. Exposed for tests and logging. */
    public List<String> orderedFieldNames() {
        List<String> names = new ArrayList<>(fields.keySet());
        // Stable sort: equal ranks keep insertion order, so callers stay in control within a rank.
        names.sort(Comparator.comparingInt(AlmEntityBody::rankOf));
        return names;
    }

    private static int rankOf(String field) {
        if (NAME.equals(field)) {
            return 0;
        }
        if (TYPE_DISCRIMINATORS.contains(field)) {
            return 3;
        }
        // "parent-id", "owner-id", ... but never the type discriminators handled above.
        if (field.endsWith("-id")) {
            return 1;
        }
        return 2;
    }

    /**
     * Serializes to the Core write shape:
     * {@code {"Fields":[{"Name":"x","values":[{"value":"y"}]}],"Type":"entity"}}.
     */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("Fields");
        for (String name : orderedFieldNames()) {
            ObjectNode field = array.addObject();
            field.put("Name", name);
            field.putArray("values").addObject().put("value", fields.get(name));
        }
        root.put("Type", entityType);
        // Jackson 3 throws unchecked JacksonException; a tree we just built cannot fail to
        // serialize, so there is nothing meaningful to recover from here.
        return MAPPER.writeValueAsString(root);
    }

    @Override
    public String toString() {
        return "AlmEntityBody[" + entityType + " " + orderedFieldNames() + "]";
    }
}
