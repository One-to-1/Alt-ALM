package ai.surgeone.altalm.bff.alm.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code customization/entities/{entity}/fields} responses.
 *
 * <p>Deliberately has <strong>no</strong> HTTP dependency, so the whole parse path is testable
 * against the redacted fixtures in {@code tests/fixtures/} with no server and no credentials —
 * which is the P0 test-harness requirement.
 *
 * <p>The response nests one level deeper than the collection endpoints do:
 * {@code {"Fields":{"Field":[ ... ]}}}, and its property names are lowerCamel
 * ({@code physicalName}, {@code supportsMultivalue}) rather than the PascalCase used by entity
 * payloads. Both are easy to get wrong from documentation alone.
 */
public final class AlmMetadataParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlmMetadataParser() {
    }

    /**
     * @param json raw response body
     * @return descriptors in server order
     * @throws IllegalArgumentException if the payload is not the expected shape — better to fail
     *         loudly than to return an empty list that looks like "this entity has no fields"
     */
    public static List<FieldDescriptor> parseFields(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty metadata payload");
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode fieldArray = root.path("Fields").path("Field");
        if (!fieldArray.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected metadata shape: expected {\"Fields\":{\"Field\":[...]}}");
        }

        List<FieldDescriptor> out = new ArrayList<>(fieldArray.size());
        for (JsonNode f : fieldArray) {
            String name = f.path("name").asString();
            String wireType = f.path("type").asString();
            AlmFieldType type = AlmFieldType.fromWireName(wireType).orElseThrow(() ->
                    new IllegalStateException(
                            "unknown ALM field type '" + wireType + "' on field '" + name
                                    + "'. The verified type system has exactly 8 members; a new one "
                                    + "means this deployment differs and must be re-probed."));

            out.add(new FieldDescriptor(
                    name,
                    f.path("physicalName").asString(),
                    type,
                    f.path("label").asString(),
                    f.path("required").asBoolean(false),
                    f.path("editable").asBoolean(false),
                    f.path("system").asBoolean(false),
                    f.path("virtual").asBoolean(false),
                    f.path("supportsMultivalue").asBoolean(false),
                    // Probe 21: `active` and `visibleInWebUI` together approximate the field set
                    // ALM's own Details form renders. `visible` is deliberately NOT read — it is
                    // true for every field in every project probed, so it carries no information.
                    f.path("active").asBoolean(false),
                    f.path("visibleInWebUI").asBoolean(false),
                    f.path("groupable").asBoolean(false),
                    // ⚠️ The server sends lowerCamel `listId`; `List-Id` is what the API reference
                    // documented and is NOT what arrives. The fallback covers both, but the second
                    // is the live one — an analysis keyed on the documented spelling concludes
                    // "no field is list-bound" when 56 of 58 are.
                    f.path("List-Id").asInt(f.path("listId").asInt(0)),
                    f.path("size").asInt(0),
                    referencedEntityOf(f)));
        }
        return List.copyOf(out);
    }

    /**
     * What a {@code Reference} field points at, from {@code fieldRelationReferences}.
     *
     * <p>⚠️ <strong>An empty answer is meaningful.</strong> {@code target-rel} carries
     * {@code references[0].referencedEntityType = "release"}, while {@code type-id} carries an empty
     * {@code references} array — and that absence is what identifies it as the subtype
     * discriminator rather than a broken payload. Returning "" for both a non-Reference field and a
     * reference-less one is deliberate; {@link FieldDescriptor#choiceSource()} is where the
     * distinction is drawn, using the field's type alongside this.
     *
     * <p>Only the first reference is taken. Every Reference field observed names exactly one target,
     * and a multi-target field would be a shape no probe has seen — better to use the first than to
     * invent a merge rule for a case that may not exist.
     */
    private static String referencedEntityOf(JsonNode field) {
        JsonNode references = field.path("fieldRelationReferences").path("references");
        if (!references.isArray() || references.isEmpty()) {
            return "";
        }
        return references.path(0).path("referencedEntityType").asString("");
    }
}
