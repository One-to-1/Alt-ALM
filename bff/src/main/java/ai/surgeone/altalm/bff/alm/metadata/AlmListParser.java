package ai.surgeone.altalm.bff.alm.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code customization/used-lists}.
 *
 * <p>No HTTP dependency, for the same reason {@link AlmMetadataParser} has none: the whole parse
 * path stays testable against the redacted fixture in {@code tests/fixtures/} with no server and no
 * credentials.
 *
 * <p><strong>The collection returns every list WITH its items inline</strong> — one request for all
 * 39 lists and 125 items on the sandbox. That is worth knowing before designing around it: there is
 * no need for a per-list fetch, no N+1, and the natural cache unit is the whole set rather than one
 * list at a time.
 *
 * <p>⚠️ Property casing is mixed and inconsistent within a single object, which is exactly the kind
 * of thing that is easy to get wrong from documentation alone: the list carries PascalCase
 * {@code Name}/{@code Id}/{@code Items}, while each item inside carries lowerCamel
 * {@code value}/{@code logicalName}.
 */
public final class AlmListParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlmListParser() {
    }

    /**
     * @param json raw {@code customization/used-lists} body
     * @return list id → list, in server order
     * @throws IllegalArgumentException if the payload is not the expected shape — loudly, rather
     *         than returning an empty map that reads as "this project defines no lists"
     */
    public static Map<Integer, AlmList> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty lists payload");
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode lists = root.path("lists");
        if (!lists.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected lists shape: expected {\"lists\":[...]}");
        }

        Map<Integer, AlmList> out = new LinkedHashMap<>();
        for (JsonNode node : lists) {
            int id = node.path("Id").asInt(0);
            if (id == 0) {
                // A list with no usable id cannot be joined to a field's listId, so keeping it
                // would only produce a lookup that silently never matches.
                continue;
            }
            List<AlmList.AlmListItem> items = new ArrayList<>();
            for (JsonNode item : node.path("Items")) {
                String value = item.path("value").asString("");
                // An item with no value is not a choice a user could pick, and offering a blank
                // entry in a dropdown that ALM would then reject helps nobody.
                if (!value.isBlank()) {
                    items.add(new AlmList.AlmListItem(value, item.path("logicalName").asString("")));
                }
            }
            out.put(id, new AlmList(id, node.path("Name").asString(""), items));
        }
        return out;
    }
}
