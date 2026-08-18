package ai.surgeone.altalm.bff.alm.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses ALM's collection-read envelope ({@code GET {entity}?...}) into {@link AlmEntityPage}.
 *
 * <p>Deliberately has <strong>no</strong> HTTP dependency, so the whole parse path is testable
 * against the redacted fixtures in {@code tests/fixtures/entities/} with no server and no
 * credentials — the same P0 test-harness requirement {@link
 * ai.surgeone.altalm.bff.alm.metadata.AlmMetadataParser} was built to, and this class follows that
 * one's shape and Jackson-3 usage ({@code tools.jackson.*}, unchecked exceptions) deliberately.
 *
 * <p>The verified shape (probe 15 §15.2):
 * <pre>{@code
 * {"entities":[{"Fields":[{"Name":"name","values":[{"value":"Requirements"}]},
 *                         {"Name":"id","values":[{"value":"0"}]}],
 *               "Type":"requirement","ErrorMessage":"","EntityStatus":"Success",
 *               "children-count":0}],
 *  "TotalResults":1}
 * }</pre>
 *
 * <p>This is the <em>plain</em> media type, not the {@code alm-web} dialect. The dialect flattens
 * this whole {@code Fields}/{@code values} envelope, but it is undocumented on a plain collection
 * GET (not one of the 42 operations that advertise it — probe 15 §15.2, recorded as risk R15), so
 * CLAUDE.md routes it to the risk register rather than to this parser.
 */
public final class AlmEntityParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUCCESS = "Success";

    private AlmEntityParser() {
    }

    /**
     * @param json raw response body of a collection GET
     * @return the parsed page — an empty {@code entities} list is a legitimate result (fact 6: an
     *         empty collection is {@code {"entities":[],"TotalResults":0}}), but a payload that does
     *         not even match the envelope shape throws instead of silently degrading to one, which
     *         would be indistinguishable from a real empty collection to every caller upstream.
     * @throws IllegalArgumentException if the payload is not the expected envelope shape
     */
    public static AlmEntityPage parsePage(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty entity-collection payload");
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode entitiesNode = root.path("entities");
        if (!entitiesNode.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected entity-collection shape: expected {\"entities\":[...],"
                            + "\"TotalResults\":N}, got top-level keys " + fieldNamesOf(root));
        }

        List<AlmEntityPage.AlmEntity> entities = new ArrayList<>(entitiesNode.size());
        for (JsonNode e : entitiesNode) {
            entities.add(parseEntity(e));
        }

        // Deliberately NOT validated against entities.size() here — §15.3 established that
        // TotalResults reflects the page, not the collection, so a mismatch against this page's row
        // count is expected and normal, not a sign of a malformed payload.
        int totalResults = root.path("TotalResults").asInt(0);
        return new AlmEntityPage(entities, totalResults);
    }

    private static AlmEntityPage.AlmEntity parseEntity(JsonNode e) {
        JsonNode fieldArray = e.path("Fields");
        if (!fieldArray.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected entity shape: expected \"Fields\" to be an array, entity keys="
                            + fieldNamesOf(e));
        }

        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (JsonNode f : fieldArray) {
            String name = f.path("Name").asString("");
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "entity field with no \"Name\": " + f.toString());
            }
            fields.put(name, parseValues(name, f.path("values")));
        }

        String type = e.path("Type").asString("");
        int childrenCount = e.path("children-count").asInt(0);
        // "EntityStatus" is optional-with-default, and probe 29 is why that is now a decision
        // rather than a guess. It threw ~25 deliberately broken reads at the server — fields that do
        // not exist, ids that do not exist, virtual and inactive fields, per-subtype fields,
        // forbidden collections, degenerate paging — plus single and multi-entity writes in both
        // media types. Every single failure came back as a REQUEST-level `QCRestException`
        // (Id/Title/ExceptionProperties) with no entity envelope at all, and every entity the server
        // ever returned carried EntityStatus explicitly as "Success". ⚠️ There is no bulk write on
        // this deployment to produce a mixed page: a multi-entity JSON body is parsed as ONE entity
        // and 500s on the missing top-level Fields, and the XML <Entities> wrapper is refused 400
        // while the same builder's single <Entity> commits 201.
        //
        // So an absent EntityStatus has never been observed, and neither has a non-"Success" one.
        // Defaulting to SUCCESS keeps a page renderable if a future version drops a key it has
        // always sent; throwing instead would discard real rows over missing metadata. The opposite
        // default is taken in AlmEntityPage.AlmEntity#isError, deliberately — see its javadoc.
        String entityStatus = e.path("EntityStatus").asString(SUCCESS);
        String errorMessage = e.path("ErrorMessage").asString("");

        return new AlmEntityPage.AlmEntity(type, fields, childrenCount, entityStatus, errorMessage);
    }

    /**
     * @param fieldName the owning field's {@code Name}, only used to make a thrown message useful
     * @param valuesNode the field's {@code "values"} node, expected to be an array (fact 1: it is
     *                    always an array on the wire, even for the ~all fields that are
     *                    single-valued in practice — only 2 fields in the whole model are genuinely
     *                    multivalue)
     */
    private static List<String> parseValues(String fieldName, JsonNode valuesNode) {
        if (!valuesNode.isArray()) {
            throw new IllegalArgumentException(
                    "field '" + fieldName + "' has no \"values\" array — the wire shape always sends "
                            + "one, even when empty (probe 15); got: " + valuesNode);
        }

        List<String> out = new ArrayList<>(valuesNode.size());
        for (JsonNode v : valuesNode) {
            JsonNode valueNode = v.path("value");
            // Fact 2: a `value` can be JSON null (probe 15 saw "referenceValue":null in a sibling
            // shape). Mapped to "omit this entry" rather than the literal string "null" — a null
            // carries no data, and stringifying it would fabricate a value the server never sent.
            // This is why an empty `values` array and a `values` array full of nulls both end up as
            // an empty list: both mean "no value", just spelled differently on the wire.
            if (valueNode.isNull() || valueNode.isMissingNode()) {
                continue;
            }
            out.add(valueNode.asString(""));
        }
        return out;
    }

    private static List<String> fieldNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
