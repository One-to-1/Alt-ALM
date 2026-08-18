package ai.surgeone.altalm.bff.alm.metadata;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-project field-metadata cache with <strong>explicit</strong> invalidation (ADR 0005).
 *
 * <p>Three properties of this cache are decisions, not defaults:
 *
 * <p><strong>It is project-scoped, never global.</strong> ALM customization is per project — field
 * sets, list bindings and tree roots all differ — so entries are keyed by
 * {@code (domain, project, entity)}. A global cache would serve one project's schema for another's
 * form, which is the exact class of bug ADR 0005 exists to prevent.
 *
 * <p><strong>It has no TTL, and that is the point.</strong> ADR 0005 rejected both "never refresh"
 * (drifts indefinitely from a schema admins genuinely do edit) and a silent time-based expiry. What
 * it asks for is an explicit operator lever, so the only way an entry leaves this cache is
 * {@link #invalidate} or {@link #invalidateAll} — the "refresh metadata" action. A TTL would make
 * staleness a matter of luck; this makes it a matter of someone deciding.
 *
 * <p><strong>A failed load is not cached.</strong> ADR 0005 also rejected falling back to stale or
 * default field lists: a form that silently omits required fields is worse than a form that refuses
 * to render. So a loader exception propagates to the caller and leaves no entry behind, and the next
 * request retries rather than inheriting the failure.
 *
 * <p>Concurrent callers asking for the same uncached entity produce <strong>one</strong> fetch, not
 * one each — the first inserts a placeholder the others wait on.
 */
public final class AlmMetadataCache {

    /**
     * Cache key. Records give equals/hashCode, which is the whole requirement.
     *
     * <p>{@code typeId} is {@code ""} for an entity-level entry and a subtype id for a per-type one,
     * so both live in one map and one {@link #invalidateAll} still drops everything. A second map
     * would have been a second lever, and an operator who pulled one would get a form built from
     * fresh entity fields and a stale per-type override of them.
     */
    private record Key(String domain, String project, String entity, String typeId) {
    }

    /** The typeId of an entity-level entry: not a type at all. */
    private static final String NO_TYPE = "";

    private final Map<Key, CompletableFuture<List<FieldDescriptor>>> entries =
            new ConcurrentHashMap<>();
    /**
     * Relations live in their own map rather than a second cache object, so that one
     * {@link #invalidateAll} is still the whole "refresh this project's customization" lever. Two
     * caches would mean two levers, and an operator who pulled one would get a form built from fresh
     * fields and stale tabs.
     */
    private final Map<Key, CompletableFuture<List<AlmRelation>>> relationEntries =
            new ConcurrentHashMap<>();
    private final Function<String, List<FieldDescriptor>> loader;
    private final java.util.function.BiFunction<String, String, List<FieldDescriptor>> typeLoader;
    private final Function<String, List<AlmRelation>> relationLoader;
    private final String domain;
    private final String project;

    /**
     * @param domain          ALM domain these entries belong to
     * @param project         ALM project these entries belong to
     * @param loader          fetches field descriptors for one entity; normally
     *                        {@code metadataClient::fetchFields}
     * @param relationLoader  fetches relations for one entity; normally
     *                        {@code metadataClient::fetchRelations}
     */
    public AlmMetadataCache(String domain, String project,
                            Function<String, List<FieldDescriptor>> loader,
                            Function<String, List<AlmRelation>> relationLoader) {
        this(domain, project, loader, relationLoader, (entity, typeId) -> {
            throw new UnsupportedOperationException(
                    "this AlmMetadataCache was built without a per-type field loader, so it cannot "
                            + "answer fields for '" + entity + "' type " + typeId);
        });
    }

    /**
     * @param typeLoader fetches the field set for one <em>subtype</em> of an entity; normally
     *                   {@code metadataClient::fetchTypeFields}
     */
    public AlmMetadataCache(String domain, String project,
                            Function<String, List<FieldDescriptor>> loader,
                            Function<String, List<AlmRelation>> relationLoader,
                            java.util.function.BiFunction<String, String, List<FieldDescriptor>> typeLoader) {
        this.domain = domain;
        this.project = project;
        this.loader = loader;
        this.relationLoader = relationLoader;
        this.typeLoader = typeLoader;
    }

    /**
     * Fields-only cache, for callers that never ask for relations.
     *
     * <p>{@link #relations} on such a cache <strong>throws</strong> rather than returning an empty
     * list. An empty list would render as "this record has no related entities" — a claim that is
     * never true and that nobody would think to question.
     */
    public AlmMetadataCache(String domain, String project,
                            Function<String, List<FieldDescriptor>> loader) {
        this(domain, project, loader, entity -> {
            throw new UnsupportedOperationException(
                    "this AlmMetadataCache was built without a relation loader, so it cannot answer "
                            + "relations for '" + entity + "'; construct it with the four-argument "
                            + "form (normally AlmMetadataClient::fetchRelations)");
        });
    }

    /**
     * Returns this entity's field descriptors, fetching them once if absent.
     *
     * @throws RuntimeException whatever the loader threw — deliberately not wrapped in an empty list,
     *                          which would read as "this entity has no fields"
     */
    public List<FieldDescriptor> fields(String entity) {
        return load(entries, new Key(domain, project, entity, NO_TYPE), () -> loader.apply(entity));
    }

    /**
     * The field set for one <em>subtype</em> of an entity.
     *
     * <p>⚠️ The gain here is smaller than this project's notes claimed, and the measurement is worth
     * keeping. Probe 25 compared all eight requirement subtypes against the entity-level set on a
     * live project: <strong>70–72 fields against 74</strong>, not the "13–20 by type" recorded in
     * SESSION-STATE, and <strong>zero</strong> flag differences — no field is re-described as
     * active, visible or required differently per type. A subtype only <em>omits</em> fields.
     *
     * <p>What it omits still matters: a Folder or Group requirement has no {@code status} and no
     * {@code req-type}, so the entity-level set puts a Direct Cover Status on a folder, which ALM
     * would not. One field on the Details form — small, real, and the kind of wrongness that reads
     * as correct.
     */
    public List<FieldDescriptor> fields(String entity, String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return fields(entity);
        }
        return load(entries, new Key(domain, project, entity, typeId),
                () -> typeLoader.apply(entity, typeId));
    }

    /**
     * Returns this entity's relations, fetching them once if absent.
     *
     * @throws RuntimeException whatever the loader threw — an empty list would read as "this entity
     *                          has no related records", which is never true of an ALM entity
     */
    public List<AlmRelation> relations(String entity) {
        return load(relationEntries, new Key(domain, project, entity, NO_TYPE),
                () -> relationLoader.apply(entity));
    }

    /**
     * Drops one entity's entries — every half, since they describe the same customization.
     *
     * <p>Removes by entity rather than by key, so an entity's per-type entries go with it. Removing
     * only the exact entity-level key would leave subtype field sets behind, and a refresh that
     * updates a form's fields but not its per-type overrides is a refresh that produced a state
     * neither version ever had.
     */
    public void invalidate(String entity) {
        entries.keySet().removeIf(k -> k.entity().equals(entity));
        relationEntries.keySet().removeIf(k -> k.entity().equals(entity));
    }

    /** The "refresh metadata" action: drops everything for this project. */
    public void invalidateAll() {
        entries.clear();
        relationEntries.clear();
    }

    /** Entity names with fields currently cached — for the refresh UI and for tests. */
    public Set<String> cachedEntities() {
        return entries.keySet().stream().map(Key::entity).collect(Collectors.toUnmodifiableSet());
    }

    public int size() {
        return entries.size();
    }

    /**
     * Single-flight load, written once and shared by both maps.
     *
     * <p>Publishes an incomplete future first, then loads. Whoever wins {@code putIfAbsent} does the
     * fetch; everyone else blocks on the same result instead of firing a duplicate request.
     */
    private static <T> T load(Map<Key, CompletableFuture<T>> map, Key key,
                              java.util.function.Supplier<T> loader) {
        CompletableFuture<T> existing = map.get(key);
        if (existing != null) {
            return join(existing);
        }
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<T> raced = map.putIfAbsent(key, mine);
        if (raced != null) {
            return join(raced);
        }
        try {
            T loaded = loader.get();
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException | Error e) {
            // Remove before completing: a waiter that wakes on the exception must not find the failed
            // entry still installed and conclude the cache is poisoned.
            map.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** Unwraps the future, rethrowing the loader's own exception rather than a CompletionException. */
    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
