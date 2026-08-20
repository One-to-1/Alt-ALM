package ai.surgeone.altalm.bff.alm.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code customization/entities/{entity}/types}.
 *
 * <p>No HTTP dependency, like its siblings, so the parse is testable against the captured fixture.
 *
 * <p>⚠️ The payload nests {@code {"types":[...]}} and each entry carries a {@code Fields} member
 * that is <strong>null</strong> on this endpoint — the per-type field set comes from
 * {@code types/{id}/fields} instead. Reading it here would produce an empty field list that looks
 * like "this subtype has no fields".
 */
public final class AlmEntityTypeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlmEntityTypeParser() {
    }

    /**
     * @return subtypes in server order; <strong>empty is a normal answer</strong> — most entities
     *         have none
     */
    public static List<AlmEntityType> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode types = MAPPER.readTree(json).path("types");
        if (!types.isArray()) {
            return List.of();
        }
        List<AlmEntityType> out = new ArrayList<>(types.size());
        for (JsonNode t : types) {
            String id = t.path("id").asString("");
            // An entry with no id cannot be stored in a type-id field, so offering it would produce
            // a choice that fails on save.
            if (!id.isBlank()) {
                out.add(new AlmEntityType(id, t.path("name").asString("")));
            }
        }
        return List.copyOf(out);
    }
}
