package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelation;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelationSelector;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The detail pane's related-entity tab strip: which tabs a record has, and what fills one.
 *
 * <p>Everything here is derived from {@code customization/entities/{e}/relations/} rather than from a
 * list of entity names — the relation set differs per entity (requirement 22, test 27, defect 17)
 * and per project, so a hardcoded strip would be wrong on the first project that differs (ADR 0005).
 * {@link AlmRelationSelector} owns the reduction and documents why it over-shows.
 */
@Service
public class TabService {

    /**
     * Rows per tab. ALM's own dialog paginates these lists; a hard cap here means a record with
     * 4,000 links renders a page rather than trying to hold all of them.
     */
    private static final int TAB_PAGE_SIZE = 200;

    private final GridService grids;
    private final AlmMetadataCatalog metadata;
    private final AlmAccessPolicy policy;

    public TabService(GridService grids, AlmMetadataCatalog metadata, AlmAccessPolicy policy) {
        this.grids = grids;
        this.metadata = metadata;
        this.policy = policy;
    }

    /**
     * The tab strip for one collection.
     *
     * <p>Does <strong>not</strong> read any records — it answers "what tabs exist for this entity in
     * this project", which is metadata and therefore cached. Counting rows per tab would mean one
     * query per tab per record, which is what ALM's own client avoids by leaving the counts off.
     */
    public TabDto.Strip strip(AlmProjectRef project, String collection) {
        policy.checkRead(project);
        String entity = AlmCollections.entityOf(collection);

        List<AlmRelation> relations = metadata.relations(project, entity);
        AlmRelationSelector.Selection selection =
                AlmRelationSelector.select(relations, TabService::canRead);

        List<TabDto.Tab> tabs = new ArrayList<>(selection.tabs().size());
        for (AlmRelationSelector.Tab tab : selection.tabs()) {
            AlmRelation backing = primary(tab);
            if (backing == null) {
                continue;
            }
            tabs.add(new TabDto.Tab(
                    tab.key(),
                    tab.label(),
                    AlmCollections.relatedCollectionOf(tab.readEntity()).orElse(""),
                    tab.isAttachment(),
                    tab.relations().stream().map(AlmRelation::name).toList()));
        }

        return new TabDto.Strip(collection, tabs, selection.dropped());
    }

    /**
     * The rows behind one tab, for one record.
     *
     * @param id the record whose detail pane is open — the source of the relation
     * @return the tab's rows shaped exactly like a grid, so the SPA renders them with the component
     *         it already has; empty when the tab key is not one this entity has
     */
    public Optional<GridDto.Grid> rows(AlmProjectRef project, String collection, String id,
                                       String tabKey) {
        policy.checkRead(project);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        String entity = AlmCollections.entityOf(collection);

        AlmRelationSelector.Selection selection = AlmRelationSelector.select(
                metadata.relations(project, entity), TabService::canRead);

        AlmRelationSelector.Tab tab = selection.tabs().stream()
                .filter(t -> t.key().equals(tabKey))
                .findFirst()
                .orElse(null);
        if (tab == null) {
            return Optional.empty();
        }

        AlmRelation backing = primary(tab);
        if (backing == null) {
            return Optional.empty();
        }
        String relatedCollection = AlmCollections.relatedCollectionOf(tab.readEntity()).orElse(null);
        if (relatedCollection == null) {
            return Optional.empty();
        }

        // The filter comes from the relation's own storage descriptor (probe 22), never from a
        // per-entity special case. Two clauses when ALM says so: the id, and — for a polymorphic
        // join like defect-link, which probe 23 found serving seven entity types from one table —
        // the type discriminator, without which the tab would list other entities' links.
        Map<String, String> filters = new java.util.LinkedHashMap<>();
        filters.put(backing.filterIdField(), id);
        if (backing.discriminated()) {
            filters.put(backing.filterTypeField(), backing.filterTypeValue());
        }

        // Validate against the related entity's own metadata before querying: an unknown field is a
        // 404 from ALM whose message cannot be told apart from a malformed separator (probe 17).
        List<FieldDescriptor> relatedFields = metadata.fields(project, tab.readEntity());
        for (String field : filters.keySet()) {
            if (relatedFields.stream().noneMatch(f -> f.name().equals(field))) {
                throw new IllegalStateException(
                        "relation '" + backing.name() + "' says to filter " + relatedCollection
                                + " by '" + field + "', but that field is not in this project's "
                                + tab.readEntity() + " metadata — the relation and the entity "
                                + "disagree, and querying anyway would 404 opaquely");
            }
        }

        return Optional.of(grids.grid(project, relatedCollection, TAB_PAGE_SIZE, 1, null, false,
                filters));
    }

    /**
     * The relation a tab actually queries when several back it.
     *
     * <p>Prefers a discriminated relation over an undiscriminated one, then a forward over a
     * mirrored. The first preference is the important one: where two relations reach the same rows
     * and only one carries a type column, the one without it returns a superset — every link the
     * record has, of every kind. Picking by list order would make that a coin toss.
     */
    private static AlmRelation primary(AlmRelationSelector.Tab tab) {
        return tab.relations().stream()
                .filter(AlmRelation::fillable)
                .min(java.util.Comparator
                        .comparing((AlmRelation r) -> !r.discriminated())
                        .thenComparing(AlmRelation::mirrored)
                        .thenComparing(AlmRelation::name))
                .orElse(null);
    }

    /** Whether a tab backed by this entity can be filled — the injected half of the selector. */
    private static boolean canRead(String entity) {
        return AlmCollections.isReadableRelated(entity);
    }
}
