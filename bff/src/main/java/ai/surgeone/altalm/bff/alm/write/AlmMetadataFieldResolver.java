package ai.surgeone.altalm.bff.alm.write;

import ai.surgeone.altalm.bff.alm.metadata.AlmFieldType;
import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;

import java.util.Optional;

/**
 * {@link AlmFieldResolver} backed by the runtime metadata catalog.
 *
 * <p>Bound to <strong>one</strong> project, and that is not a limitation: writes go to the sandbox
 * and nowhere else ({@code AlmAccessPolicy}), so a resolver that could be pointed at another
 * project's metadata would only ever be pointed at the wrong one. Field ids and physical names are
 * per-project (ADR 0005), so resolving a sandbox write against a different project's schema would
 * produce a plausible logical name for the wrong column.
 */
public final class AlmMetadataFieldResolver implements AlmFieldResolver {

    private final AlmMetadataCatalog catalog;
    private final AlmProjectRef project;

    public AlmMetadataFieldResolver(AlmMetadataCatalog catalog, AlmProjectRef project) {
        this.catalog = catalog;
        this.project = project;
    }

    @Override
    public Optional<Resolved> byPhysicalName(String entity, String physicalName) {
        if (entity == null || entity.isBlank() || physicalName == null || physicalName.isBlank()) {
            return Optional.empty();
        }
        try {
            return catalog.fields(project, entity).stream()
                    .filter(f -> physicalName.equalsIgnoreCase(f.physicalName()))
                    .findFirst()
                    .map(f -> new Resolved(f.name(), defaultValueFor(f)));
        } catch (RuntimeException e) {
            // This runs inside a failed write's recovery. An unreachable or unparseable metadata
            // response here must not replace the server's original error - which is the useful one -
            // with a metadata error about the attempt to explain it.
            return Optional.empty();
        }
    }

    /**
     * A value that satisfies the column without asserting anything about the record.
     *
     * <p>The field being resolved is one metadata says is <em>not required and not editable</em>
     * while the server demands it (probe 9), so there is no user intent to consult — something has
     * to choose, and the only defensible choice is the type's zero value.
     *
     * <p>⚠️ Type-driven rather than a blanket empty string: {@code ""} in a Number column is a
     * second 500 that looks exactly like the first one, which would make the retry appear not to
     * work rather than appear to be sending the wrong value.
     */
    private static String defaultValueFor(FieldDescriptor field) {
        AlmFieldType type = field.type();
        if (type == AlmFieldType.NUMBER) {
            return "0";
        }
        // Dates, lists, references and users all take an empty value more gracefully than a
        // fabricated one: a made-up id would point at a real row somewhere.
        return "";
    }
}
