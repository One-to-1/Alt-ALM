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
            out.add(new AlmRelation(
                    name,
                    r.path("Label").asString(null),
                    r.path("SourceEntity").asString(),
                    r.path("TargetEntity").asString(),
                    r.path("Type").asString(),
                    associationEntity(r),
                    name.endsWith(MIRRORED_SUFFIX)));
        }
        return List.copyOf(out);
    }

    /**
     * The join entity, for relations stored as a many-to-many association.
     *
     * <p>Two storage shapes were observed, both nested under {@code customizationStorageDescriptor}:
     * {@code AssociationStorage} (a join table, naming its own entity plus the endpoint id columns)
     * and {@code ReferenceStorage} (a plain foreign key). Only the former has a join entity, and it
     * is the one that matters — {@code bpm-link} and {@code defect-link} are read as collections in
     * their own right.
     */
    private static String associationEntity(JsonNode relation) {
        return relation.path("StorageDescriptor")
                .path("customizationStorageDescriptor")
                .path("AssociationStorage")
                .path("AssociationEntity")
                .asString("");
    }
}
