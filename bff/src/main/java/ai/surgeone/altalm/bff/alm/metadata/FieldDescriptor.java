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
