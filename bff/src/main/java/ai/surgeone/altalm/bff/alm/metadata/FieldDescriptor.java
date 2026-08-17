package ai.surgeone.altalm.bff.alm.metadata;

/**
 * One field from {@code customization/entities/{entity}/fields}.
 *
 * @param name          logical name used on the wire, e.g. {@code parent-id}
 * @param physicalName  DB column, e.g. {@code TP_TEST_ID}. This is what a server error names when a
 *                      write is missing a field, so it is the join key back from an error message.
 * @param type          one of the eight verified types
 * @param label         display label
 * @param required      metadata's Required flag. <strong>Not the same as "required on create"</strong>
 *                      — see {@link #requiredOnWriteIsUnknowable()}.
 * @param editable      metadata's Editable flag. Also not a reliable "may I send this?" signal.
 * @param system        true for built-in fields; false for UDFs ({@code user-NN})
 * @param virtual       computed server-side; never writable
 * @param supportsMultivalue only two fields in the entire model have this
 * @param active        metadata's Active flag. With {@code visibleInWebUI}, approximates the field
 *                      set ALM's own Details form renders — see {@link #onDetailsForm()}
 * @param visibleInWebUI metadata's VisibleInWebUI flag. ⚠️ Note this is <em>not</em> the
 *                      similarly-named {@code visible} attribute, which the parser deliberately
 *                      ignores: probe 21 found {@code visible} true for <strong>every field of
 *                      every entity in all nine probed projects</strong>, so it carries no
 *                      information while looking exactly like the flag you want
 * @param listId        for {@link AlmFieldType#LOOKUP_LIST}, the bound list; 0 when unbound.
 *                      <strong>Instance-specific — never hardcode</strong> (ADR 0005).
 * @param size          declared size; {@code -1} means unlimited (memo fields)
 */
public record FieldDescriptor(
        String name,
        String physicalName,
        AlmFieldType type,
        String label,
        boolean required,
        boolean editable,
        boolean system,
        boolean virtual,
        boolean supportsMultivalue,
        boolean active,
        boolean visibleInWebUI,
        int listId,
        int size) {

    public FieldDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
    }

    /** True for a user-defined field. UDFs are named {@code user-NN} with physical {@code XX_USER_NN}. */
    public boolean isUserDefined() {
        return !system;
    }

    /** Memo fields declare size -1 (unlimited) and hold a full {@code <html><body>} document. */
    public boolean isUnboundedMemo() {
        return type == AlmFieldType.MEMO && size == -1;
    }

    /**
     * Whether ALM's own Details form would <em>probably</em> render this field.
     *
     * <p><strong>An approximation, and known to be imperfect.</strong> Probe 21 compared this rule
     * against the stock client's Requirement Details dialog for a real record: it predicts the right
     * <em>number</em> of fields and 16 of the 17 names, and is wrong in both directions —
     * {@code father-name} ("Req Parent") satisfies the rule but is absent from the form, and
     * {@code req-type} ("Old Type (obsolete)") fails it but is present.
     *
     * <p>The real layout is not exposed by any documented API, so this is the closest an API-only
     * client can get. It is named "probably" in spirit for that reason: do not build anything that
     * silently depends on it being exact.
     */
    public boolean onDetailsForm() {
        return active && visibleInWebUI;
    }

    /**
     * The risk-analysis group — {@code active} but hidden from the web UI's main form.
     *
     * <p>Probe 21: this set is <strong>exactly 25 fields in all nine probed projects</strong>
     * despite their differing customization, and is entirely {@code rbt-*} plus {@code req-type}.
     * That invariance across independently-configured projects is what identifies it as ALM's
     * built-in Risk Analysis tab rather than a coincidence of one project's setup.
     */
    public boolean inRiskAnalysisGroup() {
        return active && !visibleInWebUI;
    }

    /**
     * A deliberate reminder, in code, that this metadata does not determine write requirements.
     *
     * <p>{@code test-parameter.ref-count} is reported {@code editable:false, required:false} yet the
     * create fails without it and succeeds with it (Probe 9). So neither flag can be used to decide
     * whether to include a field in a write body. The only reliable signal is the server's own
     * "missing required field {@code <PHYSICAL_NAME>}" error, handled by
     * {@code AlmWriteRetry}.
     *
     * @return always true — the point is that callers must not branch on {@link #required()} or
     *         {@link #editable()} when composing a create body
     */
    public boolean requiredOnWriteIsUnknowable() {
        return true;
    }
}
