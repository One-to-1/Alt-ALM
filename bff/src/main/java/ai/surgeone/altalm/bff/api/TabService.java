package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.metadata.AlmMetadataCatalog;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelation;
import ai.surgeone.altalm.bff.alm.metadata.AlmRelationSelector;
import ai.surgeone.altalm.bff.alm.metadata.FieldDescriptor;
import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmEntityClient;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.read.AlmQuery;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TabService.class);

    /**
     * Rows per tab. ALM's own dialog paginates these lists; a hard cap here means a record with
     * 4,000 links renders a page rather than trying to hold all of them.
     */
    private static final int TAB_PAGE_SIZE = 200;

    /**
     * Far-end ids per {@code id[a OR b …]} lookup.
     *
     * <p>Matches {@code TreeService}'s batch size for the same reason: probe 18 found no ceiling on
     * query length up to 1,625 characters, and this stays comfortably inside what was actually
     * measured rather than what might work (Q48).
     */
    private static final int IDS_PER_QUERY = 120;

    private final GridService grids;
    private final AlmEntityClient entities;
    private final AlmMetadataCatalog metadata;
    private final AlmAccessPolicy policy;

    public TabService(GridService grids, AlmEntityClient entities,
                      AlmMetadataCatalog metadata, AlmAccessPolicy policy) {
        this.grids = grids;
        this.entities = entities;
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
            List<TabDto.Table> tables = new ArrayList<>(tab.tables().size());
            for (AlmRelationSelector.Table table : tab.tables()) {
                AlmRelation backing = queryRelation(table.relations());
                if (backing == null) {
                    continue;
                }
                // The same clauses filtersFor() builds when the tab reads its rows, handed to the
                // client so a drill-in queries the collection the way the tab does. Deriving it
                // here rather than letting the SPA name a field is the whole of ADR 0005 in one
                // line: `cycle-id` is correct for a test instance in this project and is nobody's
                // guarantee about the next one.
                Map<String, String> scopeFixed = backing.discriminated()
                        ? Map.of(backing.filterTypeField(), backing.filterTypeValue())
                        : Map.of();
                tables.add(new TabDto.Table(
                        table.key(),
                        table.label(),
                        table.targetEntity(),
                        AlmCollections.moduleOf(table.targetEntity()).orElse(""),
                        linkRelation(table.relations()) != null,
                        backing.filterIdField(),
                        scopeFixed));
            }
            if (tables.isEmpty()) {
                continue;
            }
            tabs.add(new TabDto.Tab(
                    tab.key(),
                    tab.label(),
                    AlmCollections.readCollectionOf(tab.readEntity()).orElse(""),
                    tab.isAttachment(),
                    tables,
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
    public Optional<List<TabDto.TableRows>> rows(AlmProjectRef project, String collection, String id,
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

        String relatedCollection = AlmCollections.readCollectionOf(tab.readEntity()).orElse(null);
        if (relatedCollection == null) {
            return Optional.empty();
        }

        List<TabDto.TableRows> out = new ArrayList<>(tab.tables().size());
        for (AlmRelationSelector.Table table : tab.tables()) {
            AlmRelation backing = queryRelation(table.relations());
            if (backing == null) {
                continue;
            }
            out.add(readTable(project, tab, table, backing, relatedCollection, id));
        }
        return Optional.of(List.copyOf(out));
    }

    /**
     * Which tabs hold at least one row for this record — the tab strip's populated marks.
     *
     * <p>ALM's own dialog does this: it blues Attachments and Requirement Traceability on a record
     * that has them and leaves the empty ones plain, so the strip is scannable without opening each
     * tab in turn. There is no cheaper honest way to know — a relation says a tab <em>can</em> hold
     * rows, never whether it <em>does</em> — so this is one {@code page-size=1} read per table.
     *
     * <p>⚠️ Deliberately a boolean, not a count. {@code TotalResults} describes the page rather than
     * the collection (probe 15 measured it reporting 0 for a populated collection), so "1 row came
     * back" is the strongest claim the payload supports. A tab whose read fails is <strong>absent
     * from the map</strong> rather than reported empty: unknown and empty look identical to a user,
     * and only one of them is true.
     *
     * @return tab key → whether it holds rows, omitting any tab whose read did not succeed
     */
    public Map<String, Boolean> populated(AlmProjectRef project, String collection, String id) {
        policy.checkRead(project);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        AlmRelationSelector.Selection selection = AlmRelationSelector.select(
                metadata.relations(project, AlmCollections.entityOf(collection)), TabService::canRead);

        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (AlmRelationSelector.Tab tab : selection.tabs()) {
            String relatedCollection = AlmCollections.readCollectionOf(tab.readEntity()).orElse(null);
            if (relatedCollection == null) {
                continue;
            }
            try {
                boolean any = false;
                for (AlmRelationSelector.Table table : tab.tables()) {
                    AlmRelation backing = queryRelation(table.relations());
                    if (backing == null) {
                        continue;
                    }
                    Map<String, String> filters =
                            filtersFor(project, tab, backing, relatedCollection, id);
                    if (!grids.grid(project, relatedCollection, 1, 1, null, false, filters)
                            .rows().isEmpty()) {
                        any = true;
                        // One populated table is enough to colour the tab; the rest can wait until
                        // it is opened.
                        break;
                    }
                }
                out.put(tab.key(), any);
            } catch (RuntimeException e) {
                // Leave it out. A tab marked "empty" because its probe 500'd is a lie the user
                // cannot see through; an unmarked tab merely says nothing.
                log.debug("populated-probe failed for tab {} — leaving it unmarked", tab.key(), e);
            }
        }
        return out;
    }

    /**
     * The query one table issues, from the relation's own storage descriptor (probe 22) — never
     * from a per-entity special case.
     *
     * <p>Two clauses when ALM says so: the id, and — for a polymorphic join like {@code defect-link},
     * which probe 23 found serving seven entity types from one table — the type discriminator,
     * without which the tab would list other entities' links.
     */
    private Map<String, String> filtersFor(AlmProjectRef project, AlmRelationSelector.Tab tab,
                                           AlmRelation backing, String relatedCollection, String id) {
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
        return filters;
    }

    private TabDto.TableRows readTable(AlmProjectRef project, AlmRelationSelector.Tab tab,
                                       AlmRelationSelector.Table table, AlmRelation backing,
                                       String relatedCollection, String id) {

        Map<String, String> filters = filtersFor(project, tab, backing, relatedCollection, id);
        GridDto.Grid grid = grids.grid(project, relatedCollection, TAB_PAGE_SIZE, 1, null, false,
                filters);

        // Resolve each row's far end so the SPA can offer it as a link. Skipped entirely when the
        // relation is a plain reference (no far-end column) or when nothing in this build can open
        // the target entity — a link that goes nowhere is worse than a plain id.
        // ⚠️ The relation that names the far end is NOT necessarily the one that issued the query.
        // The query wants the broadest relation and the link wants the navigable one, and for
        // requirement coverage those are two different relations reaching the same rows. The column
        // it names is in the row data either way, which is what makes the split work.
        AlmRelation link = linkRelation(table.relations());
        Map<String, String> farEndOf = new java.util.LinkedHashMap<>();
        String targetModule = AlmCollections.moduleOf(table.targetEntity()).orElse(null);
        if (link != null && targetModule != null) {
            for (GridDto.Row row : grid.rows()) {
                List<String> values = row.values().get(link.targetIdField());
                if (values != null && !values.isEmpty() && values.getFirst() != null
                        && !values.getFirst().isBlank()) {
                    farEndOf.put(row.id(), values.getFirst());
                }
            }
        }

        Map<String, String> names = namesOf(project, targetModule, farEndOf.values());
        Map<String, TabDto.LinkTarget> targets = new java.util.LinkedHashMap<>();
        farEndOf.forEach((rowId, farId) -> targets.put(rowId, new TabDto.LinkTarget(
                table.targetEntity(), targetModule, farId, names.getOrDefault(farId, ""))));

        return new TabDto.TableRows(tab.key(), table.key(), table.label(), grid, targets);
    }

    /**
     * The names of the far-end records, batched.
     *
     * <p>One extra read per table — {@code id[a OR b OR …]} over the target collection — rather than
     * one per row. That is what makes "Defect: Summary" and "Req: Name" renderable at all; see
     * {@link TabDto.LinkTarget#name()} for why the link row itself cannot supply them.
     *
     * <p>Failure is not fatal here: an unresolved name leaves the column blank, which is a worse
     * grid but still a working one. Losing the whole tab because a name lookup 500'd would trade a
     * cosmetic loss for a functional one.
     */
    private Map<String, String> namesOf(AlmProjectRef project, String collection,
                                        java.util.Collection<String> ids) {
        if (collection == null || ids.isEmpty()) {
            return Map.of();
        }
        List<String> unique = ids.stream().distinct().toList();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (int from = 0; from < unique.size(); from += IDS_PER_QUERY) {
                List<String> chunk = unique.subList(from, Math.min(from + IDS_PER_QUERY, unique.size()));
                entities.page(project, collection, AlmQuery.none()
                                .filterAnyOf("id", chunk)
                                .fields("id", "name")
                                .pageSize(chunk.size()))
                        .entities()
                        .forEach(e -> e.first("id").ifPresent(
                                id -> out.put(id, e.first("name").orElse(""))));
            }
        } catch (RuntimeException e) {
            log.debug("could not resolve far-end names in {} — the name column stays blank",
                    collection, e);
        }
        return out;
    }

    /**
     * The relation a table actually <strong>queries</strong> when several back it.
     *
     * <p>Prefers the <em>broadest</em>: an undiscriminated relation over a discriminated one. Where
     * a group holds both — a plain {@code requirement-id} filter and the same filter plus
     * {@code entity-type[test]} — the discriminated one returns a strict subset, so querying it
     * would silently drop every coverage row that is not test-backed.
     *
     * <p>⚠️ Note this is the <em>opposite</em> preference from {@link #linkRelation}, and both are
     * needed. An earlier version used one relation for both jobs and preferred navigable, which
     * quietly narrowed the query to whatever the navigable relation happened to filter on.
     *
     * <p>Where a group holds only discriminated relations — a defect's nine {@code defect-link}
     * fan-outs — there is no broader sibling and the discriminator is kept, which is what stops
     * Linked Runs listing every link the defect has.
     */
    private static AlmRelation queryRelation(List<AlmRelation> relations) {
        return relations.stream()
                .filter(AlmRelation::fillable)
                .min(java.util.Comparator
                        .comparing(AlmRelation::discriminated)
                        .thenComparing(AlmRelation::mirrored)
                        .thenComparing(AlmRelation::name))
                .orElse(null);
    }

    /**
     * The relation that names the <strong>far end</strong>, so rows can be followed.
     *
     * <p>Only the association form carries {@code AssociationTargetIdColumn}; a plain reference
     * names one column and it points back at the open record. Null when nothing in the group can
     * say where a row leads, which is a normal answer — those rows render as plain ids.
     *
     * <p>The column it names is present in the row data whichever relation issued the query, which
     * is what lets the broad query and the navigable link coexist in one table.
     */
    private static AlmRelation linkRelation(List<AlmRelation> relations) {
        return relations.stream()
                .filter(AlmRelation::navigable)
                .min(java.util.Comparator
                        .comparing(AlmRelation::mirrored)
                        .thenComparing(AlmRelation::name))
                .orElse(null);
    }

    /** Whether a tab backed by this entity can be filled — the injected half of the selector. */
    private static boolean canRead(String entity) {
        return AlmCollections.isReadable(entity);
    }
}
