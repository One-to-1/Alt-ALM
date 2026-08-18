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
 * @param filterIdField the field on {@link #readEntity()} holding the source record's id — this is
 *                      what makes a tab fillable. ALM supplies it in the storage descriptor as
 *                      either {@code ReferenceIdColumn} or {@code AssociationSourceIdColumn}, so the
 *                      query for every tab is derivable rather than hand-written per entity.
 * @param filterTypeField the companion type discriminator, or empty when the relation has none.
 *                      ⚠️ <strong>Load-bearing when present.</strong> Probe 23 read a real project's
 *                      {@code defect-links} and found <em>one</em> table serving seven entity types
 *                      — defect, requirement, test, run, run-step, test-set, test-instance.
 *                      Filtering on the id alone would mix them.
 * @param filterTypeValue what {@code filterTypeField} must equal, or empty when there is no
 *                      discriminator. ⚠️ <strong>It is not always the source entity.</strong> The
 *                      discriminator can sit on either endpoint, and which one decides the value:
 *                      from a <em>requirement</em>, the record is at {@code second-endpoint}, so the
 *                      relation carries {@code AssociationSourceTypeColumn} and the value is
 *                      {@code requirement}; from a <em>defect</em>, the record is always at
 *                      {@code first-endpoint} and needs no proof, but the far end does — so the
 *                      relation carries {@code AssociationTargetTypeColumn} and the value is the
 *                      <em>target</em> entity ({@code run}, {@code test}, {@code test-set}). Read
 *                      the source column and "Linked Runs" would list every link the defect has.
 * @param targetIdField the field on {@link #readEntity()} holding the FAR end's id — what a "Linked
 *                      Defects" row must expose so clicking it can open that defect. Present only on
 *                      the association form ({@code AssociationTargetIdColumn}); see
 *                      {@link #navigable()}.
 */
public record AlmRelation(
        String name,
        String label,
        String sourceEntity,
        String targetEntity,
        String type,
        String associationEntity,
        boolean mirrored,
        String filterIdField,
        String filterTypeField,
        String filterTypeValue,
        String targetIdField) {

    /** ALM's relation-type strings that this codebase reasons about by name. */
    public static final String TYPE_ATTACHMENT = "attachment";
    public static final String TYPE_CONTAINMENT = "containment";

    public AlmRelation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("relation name is required");
        }
        label = label == null ? "" : label;
        associationEntity = associationEntity == null ? "" : associationEntity;
        filterIdField = filterIdField == null ? "" : filterIdField;
        filterTypeField = filterTypeField == null ? "" : filterTypeField;
        filterTypeValue = filterTypeValue == null ? "" : filterTypeValue;
        targetIdField = targetIdField == null ? "" : targetIdField;
        if (filterTypeField.isBlank() != filterTypeValue.isBlank()) {
            throw new IllegalArgumentException(
                    "relation '" + name + "' has a half-built discriminator (field='"
                            + filterTypeField + "', value='" + filterTypeValue + "'); a type column "
                            + "with nothing to match it against would filter everything away");
        }
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

    /** Whether this relation carries enough information to be queried at all. */
    public boolean fillable() {
        return !filterIdField.isBlank() && !pointsAtOwnContainer();
    }

    /**
     * Whether this relation describes the record's own container rather than a list of related
     * records — "the folder this test set is in", not "the test sets in this folder".
     *
     * <p>⚠️ These break {@link #filterIdField()}'s invariant, and silently. That field is documented
     * as a column on {@link #readEntity()}, and for every other relation it is; on the mirrored
     * direction of a plain containment reference, ALM's descriptor hands back the column from the
     * <em>source</em> side instead. So {@code testSetFolderToTestSetContainment_mirrored} yields
     * "filter test-set-folders by parent-id = 301", which asks for the folders whose <em>parent</em>
     * is that test set.
     *
     * <p>Nothing catches it downstream. {@code test-set-folder} really does have a {@code parent-id}
     * — folders nest — so the field-exists validation in {@code TabService} passes, ALM answers 200
     * with zero rows, and the pane renders a tab saying the record is in no folder. A wrong answer
     * wearing the costume of a right one, which is the failure mode this codebase keeps meeting.
     *
     * <p>Only mirrored plain references qualify. The forward direction (a folder's contents) is a
     * real tab, and an association-backed relation keeps its columns on the join entity where the
     * invariant holds.
     */
    public boolean pointsAtOwnContainer() {
        return mirrored && TYPE_CONTAINMENT.equals(type) && associationEntity.isBlank();
    }

    /** Whether this relation needs a second clause to avoid mixing entity types. */
    public boolean discriminated() {
        return !filterTypeField.isBlank();
    }

    /**
     * Whether a row of this relation can be followed to the record on the other end.
     *
     * <p>⚠️ Only the <strong>association</strong> form can. A {@code ReferenceStorage} relation names
     * one column — the one pointing back at the open record — and says nothing about the far end, so
     * {@code requirementToDefectLinkLink} can list link rows but cannot tell you which defect each
     * one reaches. The association form names both endpoints, which is why
     * {@link AlmRelationSelector} and the tab service prefer it: the difference between a tab you can
     * read and a tab you can navigate from is entirely this field.
     */
    public boolean navigable() {
        return !targetIdField.isBlank();
    }
}
