package ai.surgeone.altalm.bff.alm.read;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of an ALM collection-read response, e.g. {@code GET requirements?...}.
 *
 * <p>Parsed by {@link AlmEntityParser}, which has no HTTP dependency, so this whole shape is
 * testable against the redacted fixtures in {@code tests/fixtures/entities/} with no server and no
 * credentials — the same P0 test-harness requirement {@code AlmMetadataParser} was built to.
 *
 * <p>The verified envelope (probe 15 §15.2), on a plain (non-{@code alm-web}) collection read:
 * <pre>{@code
 * {"entities":[{"Fields":[{"Name":"name","values":[{"value":"Requirements"}]},
 *                         {"Name":"id","values":[{"value":"0"}]}],
 *               "Type":"requirement","ErrorMessage":"","EntityStatus":"Success",
 *               "children-count":0}],
 *  "TotalResults":1}
 * }</pre>
 *
 * @param entities     the rows on this page, in server order
 * @param totalResults {@code "TotalResults"} verbatim. ⚠️ <strong>This describes the PAGE, not the
 *                     collection</strong> (probe 15 §15.3) — {@code page-size=0} returned
 *                     {@code TotalResults=0} against a collection that had 2 rows, because the field
 *                     reflects how many rows THIS response carries, not how many the collection
 *                     holds. Never read it as "is this collection empty?"; check
 *                     {@code entities.isEmpty()} for that, or issue a dedicated page-size query
 *                     large enough to be trusted (or {@code page-size=max}).
 */
public record AlmEntityPage(List<AlmEntity> entities, int totalResults) {

    public AlmEntityPage {
        Objects.requireNonNull(entities, "entities");
        entities = List.copyOf(entities);
    }

    /**
     * One row from {@link AlmEntityPage#entities()}.
     *
     * <p>Field values are {@code Map<String, List<String>>}, not {@code Map<String, String>},
     * because the wire shape is always an array — {@code "values":[{"value":"..."}]} — even though
     * only two fields in the whole ALM model are genuinely multivalue (probe mining, see
     * {@code AlmMetadataParserFixtureTest#multivalueIsRare}). Parsing every field as a list avoids
     * having to special-case those two, and callers who know a field is single-valued use
     * {@link #first(String)}.
     *
     * @param type          the entity type, e.g. {@code requirement} ({@code "Type"} on the wire)
     * @param fields        field name to its values, in server field order. A field the server sent
     *                      with an empty {@code values} array is present in this map with an empty
     *                      list; a field the server did not send at all is simply absent from the
     *                      map. Those two cases are deliberately kept distinct — collapsing them
     *                      would make "this field is blank" indistinguishable from "this field was
     *                      never returned" (e.g. because it was excluded via {@code ?fields=}).
     *                      {@link #first(String)} treats both the same way for convenience, but a
     *                      caller that needs the distinction reads {@link #fields()} directly.
     * @param childrenCount {@code "children-count"}, needed for tree UIs; defaults to {@code 0}
     *                      when the server omits it (only tree-shaped entities carry it at all).
     * @param entityStatus  {@code "EntityStatus"} verbatim, e.g. {@code "Success"}. Defaults to
     *                      {@code "Success"} when the server omits the key: every captured envelope
     *                      for a healthy row includes it explicitly, and an omitted key has never
     *                      been observed to mean "this row silently failed" — treating "missing" as
     *                      "assume broken" would fail every fixture captured before this field was
     *                      known to matter. See {@link #isError()}.
     * @param errorMessage  {@code "ErrorMessage"} verbatim; empty string when absent. Only carries
     *                      meaning when {@link #isError()} is true — a healthy row's ErrorMessage is
     *                      routinely {@code ""} on the wire, not absent.
     */
    public record AlmEntity(
            String type,
            Map<String, List<String>> fields,
            int childrenCount,
            String entityStatus,
            String errorMessage) {

        public AlmEntity {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(entityStatus, "entityStatus");
            Objects.requireNonNull(errorMessage, "errorMessage");
            // LinkedHashMap, not Map.copyOf: Map.copyOf does not promise iteration order, and
            // preserving server field order here mirrors AlmMetadataParser's "descriptors in server
            // order" contract on the metadata side.
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /**
         * True when the server flagged this row as something other than a clean read.
         *
         * <p>⚠️ <strong>Probe 29 established that this is currently unreachable</strong>, and the
         * method is kept anyway. Every failure ALM was provoked into — bad field, missing id,
         * forbidden collection, broken parent reference, missing required field, malformed bulk body
         * — is reported at the <em>request</em> level as a {@code QCRestException}, never as a row
         * inside an {@code entities} envelope. {@code EntityStatus} appears on every entity the
         * server returns (a JSON member on reads, an XML attribute on writes) and has only ever held
         * {@code "Success"}.
         *
         * <p>It is kept because the cost of being wrong is asymmetric. If a version, an on-prem
         * deployment, or an operation not yet exercised does produce a failed row, treating any
         * non-{@code "Success"} token as an error shows the user something is wrong; the alternative
         * — matching {@code "Failure"} specifically, or dropping the check — would render a broken
         * record as a healthy one with blank fields, which is this project's recurring failure mode
         * (see the two overturned verdicts in CLAUDE.md). Note this defaults the <em>opposite</em>
         * way to {@link AlmEntityParser}'s handling of an absent key, on purpose: an unknown value
         * is evidence of something, an absent key is evidence of nothing.
         */
        public boolean isError() {
            return !"Success".equals(entityStatus);
        }

        /**
         * Convenience accessor for a single-valued field.
         *
         * @return the first value if the field is present and non-empty, otherwise
         *         {@link Optional#empty()} — deliberately not distinguishing "field absent" from
         *         "field present but empty" here; see {@link #fields()} javadoc for why that
         *         distinction matters and where to find it when it does.
         */
        /**
         * Every value of a field, in server order; empty when the field is absent.
         *
         * <p>Distinct from {@link #first} for the two fields where it matters: {@code target-rel}
         * and {@code target-rcyc} are the model's only multi-value fields, and reading just the
         * first would silently drop the rest — which is how a multi-value write that landed only
         * one value would verify as successful.
         */
        public List<String> all(String field) {
            List<String> values = fields.get(field);
            return values == null ? List.of() : values;
        }

        public Optional<String> first(String field) {
            List<String> values = fields.get(field);
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(values.get(0));
        }

        /** Convenience for the near-universal {@code id} field. */
        public Optional<String> id() {
            return first("id");
        }
    }
}
