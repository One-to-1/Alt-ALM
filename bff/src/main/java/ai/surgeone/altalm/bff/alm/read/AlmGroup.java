package ai.surgeone.altalm.bff.alm.read;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of an ALM server-side group-by result, e.g. {@code GET requirements/groups/type-id}.
 *
 * <p>Parsed by {@link AlmGroupParser}, which has no HTTP dependency, so this whole shape is
 * testable against the redacted fixtures in {@code tests/fixtures/grids/} with no server and no
 * credentials — the same P0 test-harness requirement {@code AlmMetadataParser} and
 * {@link AlmEntityParser} were built to.
 *
 * <p>The verified wire shape (probe 15 §15.2), on the plain (non-{@code alm-web}) media type:
 * <pre>{@code
 * {"subLevel":[{"subLevel":[],"Expression":"1","ReferenceValue":"Folder","Name":"type-id",
 *               "Value":"1","size":1}]}
 * }</pre>
 *
 * <p>Note the server's own inconsistency, encoded here verbatim rather than "fixed": every property
 * except {@code size} and {@code subLevel} is PascalCase ({@code Expression}, {@code
 * ReferenceValue}, {@code Name}, {@code Value}); {@code size} and {@code subLevel} are lowercase.
 *
 * @param field       {@code "Name"} on the wire — the field this group was grouped by, e.g.
 *                    {@code "type-id"}.
 * @param value       {@code "Value"} on the wire — the raw (unresolved) field value that defines
 *                    this group, e.g. {@code "1"}.
 * @param displayValue {@code "ReferenceValue"} on the wire — the human-readable label for
 *                     {@link #value()}, resolved server-side for Reference/lookup fields (e.g.
 *                     {@code "Folder"} for a {@code type-id} of {@code "1"}). <strong>May be {@code
 *                     null}</strong> — the captured {@code status} fixture has exactly that, because
 *                     {@code status} groups by a plain string field with nothing to resolve against.
 *                     A caller that wants a display label falls back to {@link #value()} when this
 *                     is {@code null}; it is never stringified into the literal text {@code "null"}.
 * @param expression  {@code "Expression"} on the wire — the filter expression that selects exactly
 *                    this group's rows. This is the valuable part of a group-by result: it lets a
 *                    caller drill into a group by reusing the server's own expression instead of
 *                    reconstructing one from {@link #value()}, which would be unreliable for
 *                    Reference fields where the wire value is an id, not the label a user typed.
 * @param size        {@code "size"} on the wire — the row count inside this group. ⚠️ Unlike {@code
 *                    TotalResults} elsewhere in this API (see {@link AlmEntityPage#totalResults()}),
 *                    which describes the page and not the collection, this field IS a genuine count
 *                    of every row in the group, not just the ones on some page — called out
 *                    explicitly because that sibling trap is documented all over this codebase and a
 *                    reader will reasonably wonder whether it applies here too. It does not
 *                    (probe 15 §15.2: the fixture's single-row group reports {@code size: 1}, and
 *                    group-by is not itself paged the way collection reads are).
 * @param subGroups   {@code "subLevel"} on the wire — nested sub-groups for multi-level grouping
 *                    (the stock UI supports up to 3 levels). <strong>Inferred from shape, not
 *                    probe-verified</strong>: no captured fixture nests more than one level deep, so
 *                    recursive parsing here is a reasonable extrapolation of the recursive shape, not
 *                    an observed fact. An empty {@code subLevel} array (as both real fixtures have)
 *                    means "leaf group", i.e. no further grouping was requested or possible.
 */
public record AlmGroup(
        String field,
        String value,
        String displayValue,
        String expression,
        int size,
        List<AlmGroup> subGroups) {

    public AlmGroup {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(subGroups, "subGroups");
        subGroups = List.copyOf(subGroups);
    }

    /**
     * @return {@link #displayValue()} if present, otherwise {@link #value()} — the fallback the
     *         {@code displayValue} javadoc describes, in one place so callers do not each
     *         reimplement it.
     */
    public String displayOrValue() {
        return displayValue != null ? displayValue : value;
    }

    /** {@code true} when this group has no further nesting, i.e. {@link #subGroups()} is empty. */
    public boolean isLeaf() {
        return subGroups.isEmpty();
    }

    /** {@link #displayValue()} as an {@link Optional}, for callers that prefer that idiom. */
    public Optional<String> displayValueOptional() {
        return Optional.ofNullable(displayValue);
    }
}
