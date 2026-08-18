package ai.surgeone.altalm.bff.alm.read;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the query-string portion of an ALM Core collection {@code GET} — {@code query=},
 * {@code fields=}, {@code order-by=}, {@code page-size=} and {@code start-index=} — without doing
 * any HTTP or depending on Spring. Pure string assembly over the grammar in
 * {@code docs/research/alm-api-reference.md} §4.
 *
 * <p><strong>Known limitation, by design, not oversight</strong> (api-ref §4.1): Core documents
 * <em>no escaping rule</em> for the delimiter characters {@code ;}, {@code [}, {@code ]} and
 * {@code }} inside a filter literal — the primary doc and every probe attempt (`{name['O''Brien']}`
 * variants) leave this an open gap. Rather than invent a scheme that might silently mangle a filter
 * or produce a request the server parses differently than intended, {@link #filter} rejects any
 * value containing one of those characters outright. {@link #filterRaw} is the deliberate escape
 * hatch for callers who accept that risk themselves.
 *
 * <p>Instances are immutable; every method returns a new instance. Multiple calls to {@link #filter}
 * or {@link #filterRaw} compose into the single outer {@code {}} that Core's {@code query=} grammar
 * requires (api-ref §4.1: "the WHOLE set wrapped in one outer pair of braces"); multiple calls to
 * {@link #orderBy}/{@link #orderByDescending} accumulate in call order.
 */
public final class AlmQuery {

    /**
     * Characters Core's filter grammar treats as structural ({@code {}}, {@code []}, {@code ;}) with
     * no documented escape. Detected in {@link #filter} values only — {@link #filterRaw} is exactly
     * the escape hatch for callers who need one of these anyway.
     */
    private static final char[] UNESCAPABLE_FILTER_CHARS = {';', '[', ']', '}'};

    private final List<String> filterClauses;
    private final List<String> fieldNames;
    private final List<String> orderByClauses;
    private final String pageSize;
    private final Integer startIndex;

    private AlmQuery(List<String> filterClauses, List<String> fieldNames,
                      List<String> orderByClauses, String pageSize, Integer startIndex) {
        this.filterClauses = filterClauses;
        this.fieldNames = fieldNames;
        this.orderByClauses = orderByClauses;
        this.pageSize = pageSize;
        this.startIndex = startIndex;
    }

    /**
     * Starting point for the fluent chain — no filters, fields, ordering or paging set, i.e. the
     * server's own defaults (a plain collection GET). Named {@code none()} rather than {@code of()}
     * to read correctly at the call site ({@link AlmEntityClient#page}: "{@code ...or AlmQuery.none()
     * for defaults}") — this class has no single required argument for {@code of(...)} to take.
     */
    public static AlmQuery none() {
        return new AlmQuery(List.of(), List.of(), List.of(), null, null);
    }

    /**
     * Adds one {@code field[value]} clause to the {@code query=} filter set.
     *
     * <p>{@code value} is percent-encoded (see {@link #encodeValue}) so structural characters we
     * assemble ourselves — the outer {@code {}}, the per-field {@code []}, the {@code ;} separator —
     * survive the URL untouched while the value's own content does not break them.
     *
     * @throws UnsupportedOperationException if {@code value} contains one of
     *         {@link #UNESCAPABLE_FILTER_CHARS} — see the class Javadoc and api-ref §4.1. Use
     *         {@link #filterRaw} instead and accept the risk.
     */
    public AlmQuery filter(String field, String value) {
        requireNonBlank(field, "filter field");
        if (value == null) {
            throw new IllegalArgumentException(
                    "filter value is required (use filterRaw for a condition with no plain literal)");
        }
        checkEscapable(value);
        String clause = encodeValue(field) + "[" + encodeValue(quoteIfNeeded(value)) + "]";
        return new AlmQuery(append(filterClauses, clause), fieldNames, orderByClauses, pageSize, startIndex);
    }

    /**
     * Adds one {@code field[a OR b OR c]} clause — "this field equals any of these values".
     *
     * <p>This exists as a first-class method rather than a {@link #filterRaw} call at each site
     * because {@code OR} is a <em>grammar keyword</em>, and routing user-supplied values through
     * {@code filterRaw} to get it would mean the values arrive unencoded and unchecked. Here each
     * value is encoded and delimiter-checked exactly as {@link #filter} does; only the separator
     * this method controls is structural.
     *
     * <p><strong>Probe 20 verified this against the live server</strong>, not merely the docs
     * (api-ref §4.3 carried {@code OR} as {@code [docs-research]} until then): 8, 16, 32, 63, 100
     * and 233 terms all returned exactly the union of the equivalent one-value-at-a-time queries,
     * checked against a parent→children map built independently from a full-page read.
     *
     * <p>⚠️ <strong>Callers must chunk (Q48).</strong> The longest query probed was 1,625 characters
     * — the largest project available, not a demonstrated ceiling. ALM's own URL limit is unknown and
     * intermediaries commonly cap around 8 KB, so a caller with an unbounded id list must split it.
     * This method deliberately does <em>not</em> chunk internally: an {@code AlmQuery} is one request
     * by definition, and silently becoming several would break that.
     *
     * @throws IllegalArgumentException if {@code values} is null or empty
     * @throws UnsupportedOperationException if any value contains {@link #UNESCAPABLE_FILTER_CHARS}
     */
    public AlmQuery filterAnyOf(String field, List<String> values) {
        requireNonBlank(field, "filter field");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("filterAnyOf needs at least one value");
        }
        List<String> encoded = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException("filterAnyOf values must not be null");
            }
            checkEscapable(value);
            encoded.add(encodeValue(value));
        }
        // %20 rather than a literal space: the value halves are already percent-encoded, so leaving
        // real spaces here would make the separator the only unencoded whitespace in the URL.
        String clause = encodeValue(field) + "[" + String.join("%20OR%20", encoded) + "]";
        return new AlmQuery(append(filterClauses, clause), fieldNames, orderByClauses, pageSize, startIndex);
    }

    /**
     * Escape hatch for a raw {@code field[condition]} clause: operators ({@code GT}, {@code AND},
     * {@code NOT}, ...), wildcards, quoted literals with embedded delimiters, or anything else
     * {@link #filter} would reject. {@code rawCondition} is inserted <strong>verbatim, unencoded</strong>
     * between the brackets — the caller owns both grammar correctness and any URL-safety encoding
     * the condition needs, which is the whole point of calling this instead of {@link #filter}.
     */
    public AlmQuery filterRaw(String field, String rawCondition) {
        requireNonBlank(field, "filter field");
        if (rawCondition == null) {
            throw new IllegalArgumentException("raw condition is required");
        }
        String clause = encodeValue(field) + "[" + rawCondition + "]";
        return new AlmQuery(append(filterClauses, clause), fieldNames, orderByClauses, pageSize, startIndex);
    }

    /**
     * Appends one or more field names to the {@code fields=} projection. api-ref §4.3: no effect on
     * a single-entity GET, but meaningful on a collection GET, which is this class's only concern.
     */
    public AlmQuery fields(String... names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("at least one field name is required");
        }
        List<String> encoded = new ArrayList<>(names.length);
        for (String name : names) {
            requireNonBlank(name, "field name");
            encoded.add(encodeValue(name));
        }
        return new AlmQuery(filterClauses, append(fieldNames, encoded), orderByClauses, pageSize, startIndex);
    }

    /** Adds one ascending sort field, in the order this method is called. */
    public AlmQuery orderBy(String field) {
        requireNonBlank(field, "order-by field");
        return new AlmQuery(filterClauses, fieldNames, append(orderByClauses, encodeValue(field)),
                pageSize, startIndex);
    }

    /**
     * Adds one descending sort field. The {@code field[DESC]} form is documented, not guessed:
     * api-ref §4.3's own worked example is {@code order-by={status;name[DESC]}}.
     *
     * <p><strong>The separator is {@code ;}, and this was probed because the documentation was
     * self-contradictory.</strong> api-ref §4.3's grammar summary wrote {@code order-by={field[,field…]}}
     * (comma) one line above a worked example using {@code ;}. Probe 17 asked the server:
     *
     * <pre>
     * order-by={type-id;id}  → HTTP 200, correctly sorted
     * order-by={type-id,id}  → HTTP 404 qccore.invalid-query-value,
     *                          "not existing field: \"type-id,id\""
     * </pre>
     *
     * <p>So a comma is not a separator at all — the whole string is read as one field name, and the
     * server reports it as a missing field rather than as a syntax error. That error shape is worth
     * remembering: <em>any</em> unsupported separator produces "not existing field", so a malformed
     * sort looks exactly like a typo'd column, and it answers <strong>404</strong> rather than 400.
     */
    public AlmQuery orderByDescending(String field) {
        requireNonBlank(field, "order-by field");
        return new AlmQuery(filterClauses, fieldNames,
                append(orderByClauses, encodeValue(field) + "[DESC]"), pageSize, startIndex);
    }

    /**
     * Sets {@code page-size} to a specific integer. api-ref §4.4 / probe 15 §15.3: the server itself
     * states the bound — 0 to 2000 inclusive — and returns HTTP 404 outside it, so this validates
     * up front rather than letting a caller discover the range from an opaque 404.
     *
     * @throws IllegalArgumentException if {@code size} is outside 0..2000; use {@link #pageSizeMax()}
     *         for the {@code "max"} keyword instead of guessing 2000.
     */
    public AlmQuery pageSize(int size) {
        if (size < 0 || size > 2000) {
            throw new IllegalArgumentException(
                    "page-size must be an integer between 0 and 2000 inclusive, or use pageSizeMax() "
                            + "for the \"max\" keyword (server-enforced bound, api-ref §4.4 / probe 15 §15.3): was "
                            + size);
        }
        return new AlmQuery(filterClauses, fieldNames, orderByClauses, Integer.toString(size), startIndex);
    }

    /**
     * Sets {@code page-size=max} — probe 15 §15.3 confirmed this literal keyword is accepted (HTTP
     * 200) and is the documented way to request the largest page the server allows, rather than
     * guessing a numeric upper bound that might change.
     */
    public AlmQuery pageSizeMax() {
        return new AlmQuery(filterClauses, fieldNames, orderByClauses, "max", startIndex);
    }

    /**
     * Sets {@code start-index}. api-ref §4.4: Core's start index is <strong>1-based</strong>
     * (unlike Deprecated's 0-based {@code offset}) — validated here so a caller porting logic from
     * the 0-based generation fails loudly instead of silently skipping the first row.
     *
     * @throws IllegalArgumentException if {@code index} is less than 1
     */
    public AlmQuery startIndex(int index) {
        if (index < 1) {
            throw new IllegalArgumentException("start-index is 1-based per api-ref §4.4: was " + index);
        }
        return new AlmQuery(filterClauses, fieldNames, orderByClauses, pageSize, index);
    }

    /**
     * Renders the full query string, e.g.
     * {@code ?query={status[Passed]}&fields=id,name&order-by={id}&page-size=100&start-index=1}.
     * Components are emitted in that fixed order, and only the ones actually set — an untouched
     * {@code AlmQuery.none()} renders to {@code ""}, not a bare {@code "?"}.
     */
    public String toQueryString() {
        List<String> parts = new ArrayList<>(5);
        if (!filterClauses.isEmpty()) {
            parts.add("query={" + String.join(";", filterClauses) + "}");
        }
        if (!fieldNames.isEmpty()) {
            parts.add("fields=" + String.join(",", fieldNames));
        }
        if (!orderByClauses.isEmpty()) {
            // Semicolon, NOT comma — probe 17 settled this. See orderByDescending()'s javadoc.
            parts.add("order-by={" + String.join(";", orderByClauses) + "}");
        }
        if (pageSize != null) {
            parts.add("page-size=" + pageSize);
        }
        if (startIndex != null) {
            parts.add("start-index=" + startIndex);
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "?" + String.join("&", parts);
    }

    @Override
    public String toString() {
        return "AlmQuery[" + toQueryString() + "]";
    }

    /**
     * Wraps a value in double quotes when ALM's grammar needs them.
     *
     * <h2>⚠️ Without this, a multi-word filter silently matches the WRONG ROWS</h2>
     *
     * <p>Measured against a live project, filtering {@code status} by each of its seven group values:
     *
     * <pre>
     *   Blocked         3 rows   (group says 3)    ✓
     *   Failed         48 rows   (group says 48)   ✓
     *   Not Completed 233 rows   (group says 8)    ✗ — the ENTIRE collection
     *   Not Covered   233 rows   (group says 117)  ✗ — the ENTIRE collection
     *   No Run          HTTP 400
     * </pre>
     *
     * <p>{@code NOT} is a grammar keyword, so {@code status[Not Completed]} parses as
     * <em>"status is not Completed"</em> — a valid query returning almost everything, with a 200 and
     * no hint that it answered a different question. This is the same failure mode as the tree-root
     * bug: a confidently wrong answer is worse than an error.
     *
     * <p>ALM's own {@code groups} endpoint quotes exactly these values in the drill-in
     * {@code expression} it returns, which is where the rule comes from rather than from a guess.
     * Quoting is applied whenever the value contains whitespace or a grammar keyword; single tokens
     * are left bare, matching what ALM itself emits.
     */
    private static String quoteIfNeeded(String value) {
        if (value.isEmpty() || (value.startsWith("\"") && value.endsWith("\""))) {
            return value;
        }
        boolean needsQuotes = value.chars().anyMatch(Character::isWhitespace);
        return needsQuotes ? "\"" + value + "\"" : value;
    }

    private static void checkEscapable(String value) {
        for (char c : UNESCAPABLE_FILTER_CHARS) {
            if (value.indexOf(c) >= 0) {
                throw new UnsupportedOperationException(
                        "ALM Core documents no escaping rule for '" + c + "' in filter literals (api-ref "
                                + "§4.1: \"No documented escaping rule for delimiter characters\"). Use "
                                + "filterRaw(...) instead and accept the risk, or remove the character from "
                                + "the value.");
            }
        }
    }

    /**
     * Percent-encodes one value or field name so it survives inside the query string without
     * disturbing the structural characters ({@code {} [] ; ,}) this class assembles around it —
     * "encode values, not the structural characters." {@link URLEncoder} is
     * {@code application/x-www-form-urlencoded}, which turns a space into {@code +}; that is
     * ambiguous in a URL query string (some stacks treat a literal {@code +} as data, not a space),
     * so it is swapped for the unambiguous {@code %20} afterward.
     */
    private static String encodeValue(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
    }

    private static List<String> append(List<String> existing, String item) {
        List<String> copy = new ArrayList<>(existing);
        copy.add(item);
        return List.copyOf(copy);
    }

    private static List<String> append(List<String> existing, List<String> items) {
        List<String> copy = new ArrayList<>(existing);
        copy.addAll(items);
        return List.copyOf(copy);
    }
}
