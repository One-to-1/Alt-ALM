package ai.surgeone.altalm.bff.alm.metadata;

import java.util.Optional;

/**
 * The complete ALM field-type system. Probe-verified as <strong>exactly eight</strong> types across
 * all 15 entity types on the sandbox ({@code docs/research/alm-data-model.md}).
 *
 * <p>Two consequences worth stating, because both have bitten this project:
 * <ul>
 *   <li><strong>There is no Boolean.</strong> Y/N fields are a {@link #LOOKUP_LIST} bound to
 *       list-id 1. Code that expects a boolean will silently mis-handle them.</li>
 *   <li>Only two genuinely multivalue fields exist in the whole model
 *       ({@code requirement.target-rel} and {@code .target-rcyc}), so multivalue is the rare
 *       exception, not a general case to design around.</li>
 * </ul>
 */
public enum AlmFieldType {

    STRING("String"),
    MEMO("Memo"),
    NUMBER("Number"),
    DATE("Date"),
    DATE_TIME("DateTime"),
    /** Includes every Y/N flag in the model — there is no Boolean type. */
    LOOKUP_LIST("LookupList"),
    USERS_LIST("UsersList"),
    REFERENCE("Reference");

    private final String wireName;

    AlmFieldType(String wireName) {
        this.wireName = wireName;
    }

    /** The identifier as it appears in {@code customization/entities/{e}/fields}. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolves a wire identifier, case-sensitively.
     *
     * <p>Returns empty rather than throwing or defaulting: a type we do not recognise means the
     * server exposed something outside the verified eight, which is a discovery worth surfacing
     * loudly, not silently coercing to a guess.
     */
    public static Optional<AlmFieldType> fromWireName(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        for (AlmFieldType t : values()) {
            if (t.wireName.equals(wireName)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
