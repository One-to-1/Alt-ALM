package ai.surgeone.altalm.bff.alm.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code customization/entities/{entity}/relations/} responses.
 *
 * <p>Like {@link AlmMetadataParser}, this has <strong>no</strong> HTTP dependency, so the parse path
 * is testable against the redacted fixtures in {@code tests/fixtures/} with no server and no
 * credentials.
 *
 * <p>The shape is {@code {"Relation":[ ... ],"TotalResults":22}} — note the singular key holding an
 * array, and <strong>PascalCase</strong> property names ({@code Label}, {@code TargetEntity}), which
 * is the opposite of the lowerCamel used by the sibling {@code fields} endpoint. Getting that
 * backwards yields a successful parse of entirely empty relations, so this parser insists on the
 * array being present rather than defaulting.
 *
 * <p>The most useful part is the {@code StorageDescriptor}: it names the column linking the related
 * collection back to this record, which is what lets every tab's query be derived from metadata
 * instead of hand-written per entity. It comes in two shapes — {@code AssociationStorage} (a join
 * table, naming its own entity and both endpoint columns) and {@code ReferenceStorage} (a plain
 * foreign key) — and this parser flattens both into one id/type pair.
 */
public final class AlmRelationParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ALM marks the reverse direction of a relation with this suffix on the relation name. */
    private static final String MIRRORED_SUFFIX = "_mirrored";

    private AlmRelationParser() {
    }

    /**
     * @param json raw response body
     * @return relations in server order
     * @throws IllegalArgumentException if the payload is not the expected shape — an empty list would
     *                                  read as "this entity has no related entities", which is never
     *                                  true and would silently render a tabless detail pane
     */
    public static List<AlmRelation> parseRelations(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty relations payload");
        }
        JsonNode root = MAPPER.readTree(json);
        JsonNode relations = root.path("Relation");
        if (!relations.isArray()) {
            throw new IllegalArgumentException(
                    "unexpected relations shape: expected {\"Relation\":[...]}");
        }

        List<AlmRelation> out = new ArrayList<>(relations.size());
        for (JsonNode r : relations) {
            String name = r.path("Name").asString();
            if (name == null || name.isBlank()) {
                // Every observed relation has a Name; one without it cannot be addressed or
                // de-duplicated, so it is dropped rather than given a synthetic id.
                continue;
            }
            JsonNode storage = r.path("StorageDescriptor").path("customizationStorageDescriptor");
            JsonNode association = storage.path("AssociationStorage");
            JsonNode reference = storage.path("ReferenceStorage");
            boolean isAssociation = association.isObject();

            String sourceEntity = r.path("SourceEntity").asString();
            String targetEntity = r.path("TargetEntity").asString();

            // Filter by the SOURCE id: the open record is the source, and its id is what narrows the
            // related collection. Taking the target column would return rows — the wrong ones.
            String idField = isAssociation
                    ? association.path("AssociationSourceIdColumn").asString("")
                    : reference.path("ReferenceIdColumn").asString("");

            // ⚠️ The discriminator may sit on EITHER endpoint, and which one decides its value.
            // Source-side: the open record proves its own type — from a requirement, the record sits
            // at second-endpoint, so second-endpoint-type = "requirement".
            // Target-side: the record's endpoint is unambiguous but the far end is not — from a
            // defect the record is always first-endpoint, so the type names the TARGET, and
            // second-endpoint-type = "run" is what separates Linked Runs from Linked Tests.
            String typeField = isAssociation
                    ? association.path("AssociationSourceTypeColumn").asString("")
                    : reference.path("ReferenceTypeColumn").asString("");
            String typeValue = typeField.isBlank() ? "" : sourceEntity;
            if (typeField.isBlank() && isAssociation) {
                typeField = association.path("AssociationTargetTypeColumn").asString("");
                typeValue = typeField.isBlank() ? "" : targetEntity;
            }

            out.add(new AlmRelation(
                    name,
                    r.path("Label").asString(null),
                    sourceEntity,
                    targetEntity,
                    r.path("Type").asString(),
                    association.path("AssociationEntity").asString(""),
                    name.endsWith(MIRRORED_SUFFIX),
                    idField,
                    typeField,
                    typeValue,
                    // Only the association form knows the far end; a plain reference names one column.
                    association.path("AssociationTargetIdColumn").asString("")));
        }
        return List.copyOf(out);
    }

}
