package ai.surgeone.altalm.bff.alm.metadata;

/**
 * One subtype of an entity, from {@code customization/entities/{entity}/types}.
 *
 * <p>This is what a {@code type-id} field's value refers to — the third of the three mechanisms a
 * "field with choices" can use, and the one that looks least like the other two.
 *
 * <p>⚠️ Only {@code requirement} has subtypes on the probed project. {@code test}, {@code test-set}
 * and {@code run} return an empty type list, and {@code defect}'s types endpoint returns
 * <strong>HTTP 500</strong> — so asking unconditionally fires a failing request on every defect
 * opened, forever, since a failed metadata load is deliberately not cached.
 *
 * @param id   the value a {@code type-id} field stores
 * @param name display name, e.g. {@code Folder}, {@code Functional}
 */
public record AlmEntityType(String id, String name) {
}
