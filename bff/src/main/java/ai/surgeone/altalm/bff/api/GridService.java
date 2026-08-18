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

    // The collection allowlist lives in AlmCollections — TreeService needs the same one, and a
    // security boundary copied into two places is one that will eventually differ between them.

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
        return grid(project, collection, pageSize, startIndex, sortField, descending, Map.of());
    }

    /**
     * Reads one page with filters applied.
     *
     * @param filters field name → literal value, ANDed together. Values go through
     *                {@link AlmQuery#filter}, which <strong>refuses</strong> values containing
     *                {@code ; [ ] }} rather than inventing an escaping scheme ALM does not document
     *                (api-ref §4.1). That refusal surfaces here as a 400 rather than as a filter
     *                that silently matches the wrong rows
     */
    public GridDto.Grid grid(AlmProjectRef project, String collection, int pageSize, int startIndex,
                             String sortField, boolean descending, Map<String, String> filters) {

        // Metadata for THIS project, not the credentialed one — see AlmMetadataCatalog.
        List<FieldDescriptor> fields = metadata.fields(project, entityOf(collection));
        List<GridDto.Column> columns = fields.stream()
                .map(GridDto.Column::of)
                .toList();

        AlmQuery query = AlmQuery.none().pageSize(pageSize).startIndex(startIndex);

        if (filters != null && !filters.isEmpty()) {
            // Validate against THIS project's metadata before building the query. Two reasons:
            // an unknown field produces a 404 whose message is indistinguishable from a malformed
            // separator (probe 17), and rejecting it here names the actual problem.
            var known = fields.stream().map(FieldDescriptor::name).collect(java.util.stream.Collectors.toSet());
            for (var entry : filters.entrySet()) {
                if (!known.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "unknown filter field '" + entry.getKey() + "' for " + collection
                                    + " in this project — field sets are per-project customization, "
                                    + "so check this project's metadata rather than assuming");
                }
                query = query.filter(entry.getKey(), entry.getValue());
            }
        }

        if (sortField != null && !sortField.isBlank()) {
            // Same check as the filters, and for a sharper reason: probe 17 showed that a bad
            // order-by field and a bad order-by *separator* produce the identical server error
            // ("not existing field"), so the server cannot tell the caller which mistake was made.
            // Validating here means an unknown column says so instead of arriving as a mystery 404.
            if (fields.stream().noneMatch(f -> f.name().equals(sortField))) {
                throw new IllegalArgumentException(
                        "unknown sort field '" + sortField + "' for " + collection + " in this project");
            }
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

    /**
     * One entity by id, with its columns — the detail pane's read.
     *
     * <p>Fetched as a filtered collection read rather than {@code GET {collection}/{id}} for a
     * probe-backed reason: on a single-entity GET, Core's {@code fields} parameter is a
     * <strong>no-op</strong> (api-ref §4.3), so there is no way to ask for a projection, and the
     * envelope differs from the collection form. Reading {@code {id[N]}} keeps one parser, one
     * envelope shape, and one code path.
     *
     * @return the row, or empty when no such id exists in this project
     */
    public java.util.Optional<GridDto.Grid> detail(AlmProjectRef project, String collection, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        GridDto.Grid grid = grid(project, collection, 2, 1, null, false, Map.of("id", id));

        if (grid.rows().isEmpty()) {
            return java.util.Optional.empty();
        }
        if (grid.rows().size() > 1) {
            // ids are unique per collection; more than one row means the filter did not mean what
            // we think it means, and returning the first would hide that.
            throw new IllegalStateException(
                    "filtering " + collection + " by id returned " + grid.rows().size()
                            + " rows — the id filter is not behaving as a unique lookup");
        }
        return java.util.Optional.of(narrowToType(project, collection, grid));
    }

    /**
     * Group counts for one field, validated against this project's metadata first.
     *
     * <p>{@code displayValue} falls back to the raw value when ALM has nothing to resolve against —
     * a plain string field groups with a null {@code ReferenceValue} (probe 15 §15.2), and rendering
     * the literal text "null" as a group heading is the obvious way to get that wrong.
     */
    public List<Map<String, Object>> groups(AlmProjectRef project, String collection, String field) {
        List<FieldDescriptor> fields = metadata.fields(project, entityOf(collection));
        if (fields.stream().noneMatch(f -> f.name().equals(field))) {
            throw new IllegalArgumentException(
                    "unknown group-by field '" + field + "' for " + collection + " in this project");
        }

        return entities.groups(project, collection, field).stream()
                .map(g -> Map.<String, Object>of(
                        "value", g.value() == null ? "" : g.value(),
                        "label", g.displayValue() != null ? g.displayValue()
                                : (g.value() == null ? "(none)" : g.value()),
                        // A real count, unlike TotalResults elsewhere in this API.
                        "size", g.size(),
                        "expression", g.expression() == null ? "" : g.expression()))
                .toList();
    }

    /**
     * Re-describes a record's columns using its own <em>subtype</em>'s field set, when it has one.
     *
     * <p>A subtype omits fields that do not apply to it — a Folder or Group requirement has no
     * {@code status} and no {@code req-type} — so the entity-level set puts a Direct Cover Status on
     * a folder, which the stock client would not. The values are untouched; only the column list
     * narrows, which is exactly the claim being corrected: the field does not exist for this kind of
     * record.
     *
     * <p>⚠️ Gated on the record actually carrying a {@code type-id}, and that gate is doing real
     * work. Probe 25 found only {@code requirement} has subtypes at all — and {@code defect}'s types
     * endpoint returns <strong>HTTP 500</strong>, so asking unconditionally would fire a failing
     * upstream request on every defect opened, forever, since a failed metadata load is deliberately
     * not cached.
     */
    private GridDto.Grid narrowToType(AlmProjectRef project, String collection, GridDto.Grid grid) {
        List<String> typeIds = grid.rows().getFirst().values().get("type-id");
        if (typeIds == null || typeIds.isEmpty() || typeIds.getFirst() == null
                || typeIds.getFirst().isBlank()) {
            return grid;
        }
        List<FieldDescriptor> typeFields =
                metadata.fields(project, entityOf(collection), typeIds.getFirst());
        java.util.Set<String> keep = typeFields.stream()
                .map(FieldDescriptor::name)
                .collect(java.util.stream.Collectors.toSet());
        List<GridDto.Column> narrowed = grid.columns().stream()
                .filter(c -> keep.contains(c.name()))
                .toList();
        // If the per-type read fell back to the entity-level set, this is a no-op rather than a
        // second, redundant object.
        return narrowed.size() == grid.columns().size()
                ? grid
                : new GridDto.Grid(grid.collection(), grid.writable(), narrowed, grid.rows(),
                        grid.page());
    }

    private static String entityOf(String collection) {
        return AlmCollections.entityOf(collection);
    }
}
