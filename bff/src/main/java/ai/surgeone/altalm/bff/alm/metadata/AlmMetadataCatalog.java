package ai.surgeone.altalm.bff.alm.metadata;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    }

    /** Projects with metadata currently cached. */
    public Set<AlmProjectRef> cachedProjects() {
        return Set.copyOf(caches.keySet());
    }
}
