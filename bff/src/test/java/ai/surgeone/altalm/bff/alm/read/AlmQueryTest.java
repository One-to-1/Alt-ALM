package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Query-string assembly, exercised with no HTTP and no server — pure string grammar. */
class AlmQueryTest {

    @Test
    @DisplayName("an untouched builder renders to the empty string, not a bare '?'")
    void emptyQueryRendersEmpty() {
        assertThat(AlmQuery.none().toQueryString()).isEmpty();
    }

    @Test
    @DisplayName("a single filter becomes one brace-wrapped clause")
    void singleFilter() {
        String q = AlmQuery.none().filter("status", "Passed").toQueryString();

        assertThat(q).isEqualTo("?query={status[Passed]}");
    }

    @Test
    @DisplayName("multiple filter() calls compose into one outer brace pair, semicolon-separated")
    void multipleFiltersCompose() {
        String q = AlmQuery.none()
                .filter("status", "Passed")
                .filter("owner", "jdoe")
                .toQueryString();

        assertThat(q).isEqualTo("?query={status[Passed];owner[jdoe]}");
    }

    @Test
    @DisplayName("filter() and filterRaw() interleave in call order within the same outer braces")
    void filterAndFilterRawCompose() {
        String q = AlmQuery.none()
                .filter("status", "Passed")
                .filterRaw("id", "GT 1 AND NOT 5")
                .toQueryString();

        // filterRaw's condition is inserted verbatim (unencoded) - the caller's escape hatch.
        assertThat(q).isEqualTo("?query={status[Passed];id[GT 1 AND NOT 5]}");
    }

    @Test
    @DisplayName("fields= is comma-separated, in call order")
    void fieldsProjection() {
        String q = AlmQuery.none().fields("id", "name").toQueryString();

        assertThat(q).isEqualTo("?fields=id,name");
    }

    @Test
    @DisplayName("fields() called more than once accumulates rather than replacing")
    void fieldsAccumulateAcrossCalls() {
        String q = AlmQuery.none().fields("id").fields("name").toQueryString();

        assertThat(q).isEqualTo("?fields=id,name");
    }

    @Test
    @DisplayName("fields() rejects an empty call")
    void fieldsRejectsEmpty() {
        assertThatIllegalArgumentException().isThrownBy(() -> AlmQuery.none().fields());
    }

    @Test
    @DisplayName("a single ascending order-by field is brace-wrapped")
    void singleOrderBy() {
        String q = AlmQuery.none().orderBy("id").toQueryString();

        assertThat(q).isEqualTo("?order-by={id}");
    }

    @Test
    @DisplayName("orderBy and orderByDescending accumulate in call order, SEMICOLON-separated (probe 17)")
    void orderByAccumulatesWithDescending() {
        String q = AlmQuery.none().orderBy("id").orderByDescending("name").toQueryString();

        // Probed, not chosen: a comma here returns HTTP 404 "not existing field: \"id,name\"" —
        // the server reads the whole string as one field name. api-ref §4.3 said both things; the
        // live server said semicolon.
        assertThat(q).isEqualTo("?order-by={id;name[DESC]}");
    }

    @Test
    @DisplayName("page-size accepts the lower bound 0")
    void pageSizeLowerBound() {
        assertThat(AlmQuery.none().pageSize(0).toQueryString()).isEqualTo("?page-size=0");
    }

    @Test
    @DisplayName("page-size accepts the upper bound 2000")
    void pageSizeUpperBound() {
        assertThat(AlmQuery.none().pageSize(2000).toQueryString()).isEqualTo("?page-size=2000");
    }

    @Test
    @DisplayName("page-size rejects 2001 - one past the server-enforced bound")
    void pageSizeAboveBoundThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlmQuery.none().pageSize(2001))
                .withMessageContaining("0 and 2000");
    }

    @Test
    @DisplayName("page-size rejects a negative value")
    void pageSizeNegativeThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlmQuery.none().pageSize(-1))
                .withMessageContaining("0 and 2000");
    }

    @Test
    @DisplayName("pageSizeMax() renders the literal 'max' keyword, not a guessed number")
    void pageSizeMaxKeyword() {
        assertThat(AlmQuery.none().pageSizeMax().toQueryString()).isEqualTo("?page-size=max");
    }

    @Test
    @DisplayName("start-index is 1-based")
    void startIndexIsOneBased() {
        assertThat(AlmQuery.none().startIndex(1).toQueryString()).isEqualTo("?start-index=1");
    }

    @Test
    @DisplayName("start-index rejects 0 - Core has no 0-based offset, unlike Deprecated")
    void startIndexRejectsZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlmQuery.none().startIndex(0))
                .withMessageContaining("1-based");
    }

    @Test
    @DisplayName("⚠️ a multi-word filter value is QUOTED, not just percent-encoded")
    void quotesMultiWordValues() {
        String q = AlmQuery.none().filter("name", "John Doe").toQueryString();

        // This test previously asserted `{name[John%20Doe]}` and passed. It was asserting a bug.
        //
        // Measured live: filtering `status` by each of its seven group values, the single-token ones
        // matched their group counts exactly while `Not Completed` returned 233 rows against a group
        // count of 8, and `Not Covered` returned 233 against 117 — 233 being the whole collection.
        // `NOT` is a grammar keyword, so the unquoted form parsed as "status is not Completed": a
        // valid query, a 200 response, and an answer to a different question.
        //
        // With quoting, all seven buckets reproduce their counts exactly.
        assertThat(q).isEqualTo("?query={name[%22John%20Doe%22]}");
    }

    @Test
    @DisplayName("a single-token value is left bare, matching what ALM's own groups endpoint emits")
    void singleTokenValuesAreNotQuoted() {
        assertThat(AlmQuery.none().filter("status", "Failed").toQueryString())
                .isEqualTo("?query={status[Failed]}");
    }

    @Test
    @DisplayName("an already-quoted value is not double-quoted")
    void alreadyQuotedValuePassesThrough() {
        // ALM's groups endpoint returns drill-in expressions pre-quoted; feeding one straight back
        // must not produce {status[""No Run""]}.
        assertThat(AlmQuery.none().filter("status", "\"No Run\"").toQueryString())
                .isEqualTo("?query={status[%22No%20Run%22]}");
    }

    @Test
    @DisplayName("a filter value with an ampersand is percent-encoded so it cannot start a new param")
    void encodesAmpersand() {
        String q = AlmQuery.none().filter("name", "A&B").toQueryString();

        assertThat(q).isEqualTo("?query={name[A%26B]}");
    }

    @ParameterizedTest
    @DisplayName("filter() loudly rejects values containing an undocumented-escaping delimiter")
    @ValueSource(strings = {";", "[", "]", "}"})
    void filterRejectsUnescapableCharacters(String badChar) {
        assertThatThrownBy(() -> AlmQuery.none().filter("name", "has" + badChar + "char"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("filterRaw");
    }

    @Test
    @DisplayName("filterRaw() accepts the same delimiter characters filter() rejects")
    void filterRawAcceptsDelimiters() {
        String q = AlmQuery.none().filterRaw("status", "Ready or Design").toQueryString();

        assertThat(q).isEqualTo("?query={status[Ready or Design]}");
    }

    @Test
    @DisplayName("the full example from the class contract assembles in query, fields, order-by, paging order")
    void fullExampleOrdering() {
        String q = AlmQuery.none()
                .filter("status", "Passed")
                .fields("id", "name")
                .orderBy("id")
                .pageSize(100)
                .startIndex(1)
                .toQueryString();

        assertThat(q).isEqualTo("?query={status[Passed]}&fields=id,name&order-by={id}&page-size=100&start-index=1");
    }

    @Test
    @DisplayName("filter() rejects a blank field name")
    void filterRejectsBlankField() {
        assertThatIllegalArgumentException().isThrownBy(() -> AlmQuery.none().filter(" ", "value"));
    }

    @Test
    @DisplayName("filterAnyOf builds the probe-20 OR form with an encoded separator")
    void filterAnyOfBuildsTheOrForm() {
        String q = AlmQuery.none().filterAnyOf("parent-id", java.util.List.of("1", "2", "3")).toQueryString();

        assertThat(q).isEqualTo("?query={parent-id[1%20OR%202%20OR%203]}");
    }

    @Test
    @DisplayName("filterAnyOf with a single value is a plain equality, not a degenerate OR")
    void filterAnyOfSingleValue() {
        assertThat(AlmQuery.none().filterAnyOf("parent-id", java.util.List.of("42")).toQueryString())
                .isEqualTo("?query={parent-id[42]}");
    }

    @Test
    @DisplayName("filterAnyOf encodes each value, so a value cannot inject grammar of its own")
    void filterAnyOfEncodesValues() {
        String q = AlmQuery.none().filterAnyOf("owner", java.util.List.of("a b", "c&d")).toQueryString();

        assertThat(q).isEqualTo("?query={owner[a%20b%20OR%20c%26d]}");
    }

    @Test
    @DisplayName("filterAnyOf applies the same unescapable-character refusal as filter()")
    void filterAnyOfRejectsDelimiters() {
        assertThatThrownBy(() -> AlmQuery.none().filterAnyOf("name", java.util.List.of("ok", "b[ad]")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("filterAnyOf refuses an empty list rather than emitting an empty bracket pair")
    void filterAnyOfRejectsEmpty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlmQuery.none().filterAnyOf("parent-id", java.util.List.of()));
    }

    @Test
    @DisplayName("AlmQuery is immutable - each call returns a new instance, the original is untouched")
    void immutability() {
        AlmQuery base = AlmQuery.none().filter("status", "Passed");
        AlmQuery extended = base.filter("owner", "jdoe");

        assertThat(base.toQueryString()).isEqualTo("?query={status[Passed]}");
        assertThat(extended.toQueryString()).isEqualTo("?query={status[Passed];owner[jdoe]}");
    }
}
