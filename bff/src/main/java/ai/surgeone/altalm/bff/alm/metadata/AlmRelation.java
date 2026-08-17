package ai.surgeone.altalm.bff.alm.metadata;

/**
 * One entry from {@code customization/entities/{entity}/relations/}.
 *
 * <p>Probe 21.6 found this endpoint while looking for the source of the stock client's related-entity
 * tab strip (Attachments, Linked Defects, Requirement Traceability, Test Coverage, …). It is the
 * right source, but it is a source of <strong>candidates</strong>, not of the tab set: the sandbox
 * returns 22 relations for {@code requirement} where the stock dialog shows 6 related-entity tabs.
 * {@link AlmRelationSelector} does the reduction and documents each rule.
 *
 * @param name          the relation's stable id, e.g. {@code requirementToDefectLinkLink}. Unique
 *                      within an entity; the {@code _mirrored} suffix marks the reverse direction of
 *                      a relation that also appears in its forward form.
 * @param label         the human-readable name, which probe 21.6 verified <em>is</em> the stock
 *                      client's tab caption ("Linked Defects", "Test Coverage"). ⚠️ <strong>May be
 *                      absent</strong> — 5 of the sandbox's 17 {@code defect} relations have no
 *                      label, and every one of them is a field-backed reference (target release,
 *                      detected-in cycle, environment) rather than a tab. Empty string when absent;
 *                      never null.
 * @param sourceEntity  the entity whose relations were requested
 * @param targetEntity  the entity on the other end — this names the collection to read to fill the
 *                      tab, which is what makes the endpoint useful rather than merely descriptive
 * @param type          ALM's own classification: {@code link}, {@code connection}, {@code
 *                      containment}, {@code composition}, {@code usage}, {@code dependency},
 *                      {@code attachment}, {@code realization}. Observed values only — this is a
 *                      free-form string, not an enum, because an unknown ninth type must not fail a
 *                      parse of an otherwise fine document.
 * @param associationEntity the join entity for a many-to-many relation ({@code bpm-link},
 *                      {@code defect-link}), or empty for a direct reference. Present only when the
 *                      storage descriptor is an {@code AssociationStorage}.
 * @param mirrored      whether this is the reverse direction of another relation. ⚠️ <strong>Not a
 *                      drop signal.</strong> For {@code test}, the useful direction is usually the
 *                      mirrored one ({@code requirementToTestConnection_mirrored} is "the
 *                      requirements this test covers"), and for {@code defect}, "Linked from
 *                      Defects" is a genuinely distinct tab from "Linked to Defects".
 */
public record AlmRelation(
        String name,
        String label,
        String sourceEntity,
        String targetEntity,
        String type,
        String associationEntity,
        boolean mirrored) {

    /** ALM's relation-type strings that this codebase reasons about by name. */
    public static final String TYPE_ATTACHMENT = "attachment";
    public static final String TYPE_CONTAINMENT = "containment";

    public AlmRelation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("relation name is required");
        }
        label = label == null ? "" : label;
        associationEntity = associationEntity == null ? "" : associationEntity;
    }

    /** A relation ALM did not caption. Probe 22: these are field-backed references, not tabs. */
    public boolean unlabelled() {
        return label.isBlank();
    }

    /** True when this relation points back at its own entity — the hierarchy, not a related list. */
    public boolean selfReferential() {
        return sourceEntity != null && sourceEntity.equals(targetEntity);
    }

    /** The collection whose rows fill this tab: the join entity when there is one, else the target. */
    public String readEntity() {
        return associationEntity.isBlank() ? targetEntity : associationEntity;
    }
}
