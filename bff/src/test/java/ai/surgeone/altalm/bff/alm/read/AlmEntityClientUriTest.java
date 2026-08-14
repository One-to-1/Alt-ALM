package ai.surgeone.altalm.bff.alm.read;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for the URI-encoding bug that a contract test caught (probe 18).
 *
 * <p>ALM's query grammar uses characters RFC 3986 forbids in a query. Building the URI naively
 * throws before any request is sent, so every filtered, sorted or paged read was broken while the
 * unit tests stayed green — they exercised the query <em>string</em>, never the URI.
 */
class AlmEntityClientUriTest {

    @Test
    @DisplayName("a query with braces, brackets and semicolons produces a legal URI")
    void grammarCharactersAreEncoded() {
        String url = "https://alm.invalid/qcbin/rest/domains/D/projects/P/requirements"
                + AlmQuery.none()
                .filter("parent-id", "-1")
                .orderBy("type-id")
                .orderByDescending("id")
                .pageSize(50)
                .toQueryString();

        assertThatCode(() -> AlmEntityClient.almUri(url)).doesNotThrowAnyException();

        URI uri = AlmEntityClient.almUri(url);
        assertThat(uri.toString())
                .contains("%7B").contains("%7D")   // { }
                .contains("%5B").contains("%5D")   // [ ]
                .contains("%3B")                   // ;
                .doesNotContain("{").doesNotContain("}")
                .doesNotContain("[").doesNotContain("]");
    }

    @Test
    @DisplayName("query separators survive — only grammar characters are encoded")
    void separatorsAreNotEncoded() {
        String url = "https://alm.invalid/x/requirements?fields=id,name&page-size=50&start-index=1";

        assertThat(AlmEntityClient.almUri(url).toString())
                .contains("?fields=id,name")
                .contains("&page-size=50")
                .contains("&start-index=1");
    }

    @Test
    @DisplayName("a URL with no query is passed through untouched")
    void noQueryIsUntouched() {
        String url = "https://alm.invalid/qcbin/rest/domains/D/projects/P/requirements";

        assertThat(AlmEntityClient.almUri(url)).hasToString(url);
    }

    @Test
    @DisplayName("the path is never re-encoded — only the query part is rewritten")
    void pathIsLeftAlone() {
        // A path segment could legitimately contain characters we encode in the query; encoding
        // them there would change which resource is addressed.
        String url = "https://alm.invalid/qcbin/rest/domains/D/projects/P/requirements?query={id[1]}";

        assertThat(AlmEntityClient.almUri(url).toString())
                .startsWith("https://alm.invalid/qcbin/rest/domains/D/projects/P/requirements?");
    }
}
