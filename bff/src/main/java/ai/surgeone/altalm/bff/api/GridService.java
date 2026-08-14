package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmEntityPage;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an ALM collection read into the SPA's grid contract.
 *
 * <p>Columns come from field metadata every time rather than from a constant: field sets, labels and
 * list bindings are per-project customization (ADR 0005), so a grid built from a hardcoded column
 * list would be wrong on the first project that differs from the sandbox — which is now a live
 * concern, since P1 reads a project we did not configure.
 */
@Service
public class GridService {

    /**
     * Collection → singular entity name, and <strong>an allowlist, not a lookup table with a
     * fallback.</strong>
     *
     * <p>Two reasons it is closed rather than derived by trimming a trailing "s".
     *
     * <p>First, correctness: the metadata endpoint wants the singular, and a collection whose name
     * does not resolve produces a grid with <em>no columns</em> rather than an error — a silent,
     * plausible-looking failure of exactly the kind this project keeps finding.
     *
     * <p>Second, and the reason there is no fallback at all: {@code collection} arrives as a path
     * variable from the browser and is interpolated into the ALM request URL. Accepting anything
     * that merely ends in "s" would let a caller aim the BFF's authenticated session at arbitrary
     * REST paths. An allowlist means the set of things a request can reach is the set written here.
     */
    private static final Map<String, String> ENTITY_OF = Map.ofEntries(
            Map.entry("requirements", "requirement"),
            Map.entry("tests", "test"),
            Map.entry("defects", "defect"),
            Map.entry("test-sets", "test-set"),
            Map.entry("test-instances", "test-instance"),
            Map.entry("runs", "run"),
            Map.entry("design-steps", "design-step"),
            Map.entry("test-folders", "test-folder"),
            Map.entry("test-set-folders", "test-set-folder"),
            Map.entry("releases", "release"),
            Map.entry("release-cycles", "release-cycle"),
            Map.entry("release-folders", "release-folder"),
            Map.entry("resource-folders", "resource-folder"),
            Map.entry("bpm-folders", "bpm-folder"),
            Map.entry("run-steps", "run-step"),
            Map.entry("test-configs", "test-config"));

    private final AlmEntityClient entities;
    private final AlmMetadataCatalog metadata;
    private final AlmAccessPolicy policy;

    public GridService(AlmEntityClient entities, AlmMetadataCatalog metadata, AlmAccessPolicy policy) {
        this.entities = entities;
        this.metadata = metadata;
        this.policy = policy;
    }

    /**
     * Reads one page and shapes it for the SPA.
     *
     * @param sortField  optional field to sort by
     * @param descending direction for {@code sortField}
     */
    public GridDto.Grid grid(AlmProjectRef project, String collection, int pageSize, int startIndex,
                             String sortField, boolean descending) {

        // Metadata for THIS project, not the credentialed one — see AlmMetadataCatalog.
        List<FieldDescriptor> fields = metadata.fields(project, entityOf(collection));
        List<GridDto.Column> columns = fields.stream()
                .map(f -> new GridDto.Column(f.name(), f.label(), f.type().name(), f.listId(),
                        f.supportsMultivalue()))
                .toList();

        AlmQuery query = AlmQuery.none().pageSize(pageSize).startIndex(startIndex);
        if (sortField != null && !sortField.isBlank()) {
            // Semicolon-separated on the wire (probe 17) — AlmQuery owns that detail.
            query = descending ? query.orderByDescending(sortField) : query.orderBy(sortField);
        }

        AlmEntityPage page = entities.page(project, collection, query);

        List<GridDto.Row> rows = new ArrayList<>(page.entities().size());
        for (AlmEntityPage.AlmEntity e : page.entities()) {
            Map<String, List<String>> values = new LinkedHashMap<>(e.fields());
            rows.add(new GridDto.Row(
                    e.id().orElse(""),
                    values,
                    e.childrenCount(),
                    e.isError() ? e.errorMessage() : null));
        }

        // "A full page probably means more rows" — evidence, not proof, because the clamp question
        // (Q45) is unresolved. The DTO field is named mayHaveMore for exactly that reason.
        boolean mayHaveMore = rows.size() >= pageSize && pageSize > 0;

        return new GridDto.Grid(collection, policy.isWritable(project), columns, rows,
                new GridDto.Page(rows.size(), page.totalResults(), mayHaveMore));
    }

    private static String entityOf(String collection) {
        String entity = ENTITY_OF.get(collection);
        if (entity == null) {
            throw new IllegalArgumentException(
                    "no known entity name for collection '" + collection + "' — add it to ENTITY_OF "
                            + "rather than deriving one, since this value reaches the ALM request URL");
        }
        return entity;
    }
}
