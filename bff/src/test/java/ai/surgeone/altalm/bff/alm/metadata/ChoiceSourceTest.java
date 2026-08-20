package ai.surgeone.altalm.bff.alm.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the three mechanisms supplies a field's values — against the real captured metadata.
 *
 * <p>This exists because the three were conflated once already. A field that offers choices does so
 * by a bound lookup list, by a reference to another entity collection, or by the subtype endpoint,
 * and <strong>the field's type cannot tell you which</strong>: {@code target-rel} and {@code type-id}
 * are both {@code REFERENCE} and resolve completely differently.
 *
 * <p>Run against the fixture rather than hand-built descriptors, because the distinguishing detail
 * is an <em>absence</em> — {@code type-id} has an empty {@code references} array — and a hand-built
 * descriptor is exactly where that absence would be filled in by accident.
 */
class ChoiceSourceTest {

    private static Map<String, FieldDescriptor> requirementFields() throws IOException {
        return AlmMetadataParser.parseFields(Files.readString(
                        Path.of("..", "tests", "fixtures", "customization-fields-requirement.json")))
                .stream()
                .collect(Collectors.toMap(FieldDescriptor::name, Function.identity()));
    }

    @Test
    @DisplayName("a bound LookupList resolves via its list")
    void boundLookupIsList() throws IOException {
        FieldDescriptor status = requirementFields().get("status");

        assertThat(status.type()).isEqualTo(AlmFieldType.LOOKUP_LIST);
        assertThat(status.listId()).isEqualTo(309);
        assertThat(status.choiceSource()).isEqualTo(FieldDescriptor.ChoiceSource.LIST);
    }

    @Test
    @DisplayName("the wire key is listId, and 56 of the model's 58 lookup fields carry one")
    void listIdIsRead() throws IOException {
        // ⚠️ Guards a documentation bug that produced a wrong conclusion: the API reference recorded
        // the key as `List-Id`. Keyed on that spelling, an analysis reports ZERO list-bound fields —
        // "dropdowns are impossible here" — when almost all of them are.
        List<FieldDescriptor> lookups = requirementFields().values().stream()
                .filter(f -> f.type() == AlmFieldType.LOOKUP_LIST)
                .toList();

        assertThat(lookups).hasSize(27);
        assertThat(lookups).allMatch(f -> f.listId() > 0);
    }

    @Test
    @DisplayName("a Reference naming another entity resolves by querying THAT collection")
    void referenceWithTargetIsEntity() throws IOException {
        Map<String, FieldDescriptor> fields = requirementFields();

        assertThat(fields.get("target-rel").referencedEntity()).isEqualTo("release");
        assertThat(fields.get("target-rel").choiceSource())
                .isEqualTo(FieldDescriptor.ChoiceSource.ENTITY);
        assertThat(fields.get("target-rcyc").referencedEntity()).isEqualTo("release-cycle");
        assertThat(fields.get("target-rcyc").choiceSource())
                .isEqualTo(FieldDescriptor.ChoiceSource.ENTITY);
    }

    @Test
    @DisplayName("a Reference naming NOTHING is the subtype discriminator, not a broken payload")
    void referenceWithoutTargetIsSubtype() throws IOException {
        FieldDescriptor typeId = requirementFields().get("type-id");

        assertThat(typeId.type()).isEqualTo(AlmFieldType.REFERENCE);
        // The absence is the signal. Reading it as "no choices" would leave Requirement Type as a
        // free-text box over a raw id.
        assertThat(typeId.referencedEntity()).isEmpty();
        assertThat(typeId.choiceSource()).isEqualTo(FieldDescriptor.ChoiceSource.SUBTYPE);
    }

    @Test
    @DisplayName("req-type and type-id are different fields by different routes")
    void similarNamesDifferentMechanisms() throws IOException {
        Map<String, FieldDescriptor> fields = requirementFields();

        // "Old Type (obsolete)" — a lookup list.
        assertThat(fields.get("req-type").choiceSource())
                .isEqualTo(FieldDescriptor.ChoiceSource.LIST);
        // "Requirement Type" — a reference resolved by the subtype endpoint.
        assertThat(fields.get("type-id").choiceSource())
                .isEqualTo(FieldDescriptor.ChoiceSource.SUBTYPE);
    }

    @Test
    @DisplayName("multi-value is exactly the two Reference fields, in the whole model")
    void multiValueIsTwoReferences() throws IOException {
        List<String> multi = requirementFields().values().stream()
                .filter(FieldDescriptor::supportsMultivalue)
                .map(FieldDescriptor::name)
                .sorted()
                .toList();

        assertThat(multi).containsExactly("target-rcyc", "target-rel");
    }

    @Test
    @DisplayName("ordinary fields offer nothing to choose from")
    void plainFieldsHaveNoChoices() throws IOException {
        Map<String, FieldDescriptor> fields = requirementFields();

        assertThat(fields.get("name").choiceSource()).isEqualTo(FieldDescriptor.ChoiceSource.NONE);
        assertThat(fields.get("description").choiceSource())
                .isEqualTo(FieldDescriptor.ChoiceSource.NONE);
    }

    @Test
    @DisplayName("a list-typed field bound to nothing offers nothing, rather than an empty dropdown")
    void unboundLookupIsNone() {
        FieldDescriptor unbound = new FieldDescriptor("choice", "X_CHOICE",
                AlmFieldType.LOOKUP_LIST, "Choice",
                false, true, true, false, false, true, true, false, 0, 0);

        assertThat(unbound.choiceSource()).isEqualTo(FieldDescriptor.ChoiceSource.NONE);
    }

    @Test
    @DisplayName("subtypes parse from the captured payload, id and name")
    void subtypesParse() throws IOException {
        List<AlmEntityType> types = AlmEntityTypeParser.parse(Files.readString(
                Path.of("..", "tests", "fixtures", "customization-requirement-types.txt")));

        assertThat(types).isNotEmpty();
        assertThat(types).anySatisfy(t -> {
            assertThat(t.id()).isEqualTo("1");
            assertThat(t.name()).isEqualTo("Folder");
        });
        // Every entry must be storable in a type-id field; an entry with no id would be a choice
        // that fails on save.
        assertThat(types).allMatch(t -> !t.id().isBlank());
    }

    @Test
    @DisplayName("an absent or unparseable type payload is empty, never an exception")
    void missingTypesAreEmpty() {
        // defect's types endpoint returns HTTP 500. A throw here would surface as a broken detail
        // pane for every defect; empty correctly means "this field offers no choices".
        assertThat(AlmEntityTypeParser.parse("")).isEmpty();
        assertThat(AlmEntityTypeParser.parse("{\"nope\":[]}")).isEmpty();
    }
}
