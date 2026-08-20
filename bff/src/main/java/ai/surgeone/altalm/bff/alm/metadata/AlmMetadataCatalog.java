package ai.surgeone.altalm.bff.alm.metadata;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link AlmMetadataCache} per project.
 *
 * <p>{@code AlmMetadataCache} is deliberately project-scoped — its own javadoc calls a global cache
 * "the exact class of bug ADR 0005 exists to prevent". That was sufficient while the BFF talked to
 * one project. It stopped being sufficient when P1 began reading the tenant's other projects (probe
 * 16): a single cache bound to the credentialed project would have served <em>sandbox</em> columns
 * for a grid over someone else's project, and the result would have looked entirely plausible —
 * same field names, wrong labels, wrong list bindings, silently missing that project's UDFs.
 *
 * <p>This class keeps that guarantee intact by holding a separate cache per project rather than by
 * relaxing the cache's scoping. Every entry point is access-checked first, so metadata cannot become
 * a side channel that reads a project the read policy would refuse.
 */
public final class AlmMetadataCatalog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AlmMetadataCatalog.class);

    private final Map<AlmProjectRef, AlmMetadataCache> caches = new ConcurrentHashMap<>();

    /**
     * Lookup lists, cached per project.
     *
     * <p>Held here rather than in {@link AlmMetadataCache} because lists are <strong>project</strong>
     * scoped, not entity scoped — that cache is keyed by entity and lists do not have one. The whole
     * set arrives in a single request, so the natural cache unit is all of them together.
     *
     * <p>A {@code CompletableFuture} for the same reason the field cache uses one: N concurrent
     * callers cause one fetch, not N. And a failed load is removed rather than cached, so a
     * transient error does not pin "this project has no lists" for the life of the process.
     */
    private final Map<AlmProjectRef, CompletableFuture<Map<Integer, AlmList>>> lists =
            new ConcurrentHashMap<>();

    /** Subtypes, keyed by project+entity — the one metadata read that is scoped to both. */
    private final Map<String, CompletableFuture<List<AlmEntityType>>> typeCache =
            new ConcurrentHashMap<>();
    private final AlmMetadataClient client;
    private final AlmAccessPolicy policy;

    public AlmMetadataCatalog(AlmMetadataClient client, AlmAccessPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    /** Field descriptors for one entity in one project, fetched once per project. */
    public List<FieldDescriptor> fields(AlmProjectRef project, String entity) {
        return cacheFor(project).fields(entity);
    }

    /**
     * Field descriptors for one <em>subtype</em>, falling back to the entity-level set.
     *
     * <p>The fallback is not defensive padding — it is the normal path for most entities. Only
     * {@code requirement} has subtypes on the probed project; {@code defect}'s types endpoint
     * returns HTTP 500. A caller asking for a type that does not exist should get the best answer
     * available, which is the entity's own field set, not an exception.
     */
    public List<FieldDescriptor> fields(AlmProjectRef project, String entity, String typeId) {
        AlmMetadataCache cache = cacheFor(project);
        if (typeId == null || typeId.isBlank()) {
            return cache.fields(entity);
        }
        try {
            return cache.fields(entity, typeId);
        } catch (RuntimeException e) {
            log.debug("no per-type fields for {} type {} — using the entity-level set",
                    entity, typeId, e);
            return cache.fields(entity);
        }
    }

    /**
     * Relations for one entity in one project — the candidate set for the detail pane's tab strip.
     *
     * <p>Project-scoped for the same reason fields are: the relation set differs per project, and
     * one project's tabs over another's records would be wrong in a way that still renders.
     */
    public List<AlmRelation> relations(AlmProjectRef project, String entity) {
        return cacheFor(project).relations(entity);
    }

    /**
     * An entity's subtypes, cached per project+entity.
     *
     * <p>⚠️ Returns <strong>empty</strong> on failure rather than throwing — same rule as
     * {@link #lists}: a choice list that cannot be read must degrade to "no constraint", never to
     * an error. {@code defect}'s types endpoint 500s, and this is what keeps that from surfacing as
     * a broken detail pane.
     */
    public List<AlmEntityType> types(AlmProjectRef project, String entity) {
        policy.checkRead(project);
        String key = project.domain() + "/" + project.project() + "#" + entity;
        CompletableFuture<List<AlmEntityType>> pending = typeCache.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> client.fetchTypes(project, entity),
                        Runnable::run));
        try {
            return pending.join();
        } catch (RuntimeException e) {
            typeCache.remove(key, pending);
            log.debug("no subtypes for {} - the field will offer no choices", entity, e);
            return List.of();
        }
    }

    /**
     * Every lookup list in a project, by id.
     *
     * <p>⚠️ Returns an <strong>empty map</strong> when the lists cannot be read, rather than
     * throwing. A validator that cannot see the lists must fall back to letting values through and
     * letting ALM decide — refusing every lookup value because a metadata read failed would turn a
     * degraded cache into an outage. The caller cannot distinguish "no lists" from "could not read
     * them", which is deliberate: both mean the same thing to a validator, namely *do not judge*.
     */
    public Map<Integer, AlmList> lists(AlmProjectRef project) {
        policy.checkRead(project);
        CompletableFuture<Map<Integer, AlmList>> pending =
                lists.computeIfAbsent(project, p -> CompletableFuture.supplyAsync(
                        () -> client.fetchLists(p), Runnable::run));
        try {
            return pending.join();
        } catch (RuntimeException e) {
            // Not cached: the next caller retries rather than inheriting this failure forever.
            lists.remove(project, pending);
            log.debug("could not read lookup lists for {} - values will not be validated",
                    project.pseudonym(), e);
            return Map.of();
        }
    }

    /**
     * One list by id, or empty when this project has no such list.
     *
     * <p>Empty is a normal answer: a field's {@code listId} is instance-specific, and a project that
     * does not define it is not an error condition.
     */
    public java.util.Optional<AlmList> list(AlmProjectRef project, int listId) {
        return java.util.Optional.ofNullable(lists(project).get(listId));
    }

    /**
     * The cache for one project, created on first use.
     *
     * <p>Access-checked here rather than only at the read client, because "which fields exist"
     * is itself information about a project.
     */
    public AlmMetadataCache cacheFor(AlmProjectRef project) {
        policy.checkRead(project);
        return caches.computeIfAbsent(project, p ->
                new AlmMetadataCache(p.domain(), p.project(),
                        entity -> client.fetchFields(p, entity),
                        entity -> client.fetchRelations(p, entity),
                        (entity, typeId) -> client.fetchTypeFields(p, entity, typeId)));
    }

    /** Drops one project's cached metadata — the operator's "refresh metadata" lever (ADR 0005). */
    public void invalidate(AlmProjectRef project) {
        AlmMetadataCache cache = caches.get(project);
        if (cache != null) {
            cache.invalidateAll();
        }
        // Lists are customization too, and an operator refreshing metadata after editing a
        // list-of-values expects the new items — leaving them cached would make the lever look
        // broken for the one change most likely to prompt pulling it.
        lists.remove(project);
        typeCache.keySet().removeIf(
                k -> k.startsWith(project.domain() + "/" + project.project() + "#"));
    }

    /** Projects with metadata currently cached. */
    public Set<AlmProjectRef> cachedProjects() {
        return Set.copyOf(caches.keySet());
    }
}
