package ai.surgeone.altalm.bff.alm.write;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The separator ALM's stock client puts between comments.
 *
 * <p>⚠️ <strong>This format is UNVERIFIED and cannot currently be probed.</strong> Reading the real
 * one requires a record somebody commented on through ALM's own UI: the sandbox has none, and the
 * borrowed projects that did are no longer reachable (user, 2026-08-18). What is written here is a
 * reconstruction from what the field accepts (probe 30 round-tripped it intact), not a capture of
 * what ALM emits.
 *
 * <p>It is a class of its own for exactly that reason. When someone finally opens the stock client
 * and adds one comment, correcting this is a one-line change in one place — rather than a hunt for
 * separator strings scattered through the write path, which is what would happen if this lived
 * inline where it is used.
 *
 * <p>What <em>is</em> verified (probe 30): the server adds nothing of its own. No banner, no
 * username, no timestamp — REST writes bypass workflow scripts, so every part of this convention is
 * ours to emit and ours to get right.
 */
public final class AlmCommentBanner {

    /** ALM renders dates day-first in the client this convention imitates. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private AlmCommentBanner() {
    }

    /**
     * The separator introducing a new comment.
     *
     * @param author display name of whoever is commenting. Entity-encoded, because a memo field is
     *               HTML and only HTML (probe 27) — an unescaped {@code <} in a name would open a
     *               tag and swallow the comment that follows it
     */
    public static String banner(String author, LocalDate on) {
        return "<b>________________________________________</b><br>"
                + "<b>" + escape(author) + ", " + STAMP.format(on) + ":</b><br>";
    }

    /**
     * Appends a comment to an existing memo value.
     *
     * <p>Newest last, matching the stock client's reading order. ⚠️ Note this appends to the raw
     * stored HTML rather than parsing it: the value is a full {@code <html><body>} document and ALM
     * re-serialises it on the way in anyway (probe 27 saw {@code <br>} come back as {@code <br />}),
     * so a parse-and-rebuild would add a failure mode without adding fidelity.
     *
     * @param existing the memo's current full value, exactly as read back from ALM
     */
    public static String append(String existing, String author, String comment, LocalDate on) {
        String body = existing == null ? "" : existing;
        String addition = banner(author, on) + escape(comment);
        if (body.isBlank()) {
            return addition;
        }
        // Injected before the closing tags when they are present, so the result stays one document
        // rather than two concatenated ones. ALM tolerates a fragment and wraps it, but a body that
        // already ends in </body></html> followed by more text is a shape nothing has tested.
        int close = body.lastIndexOf("</body>");
        if (close < 0) {
            return body + addition;
        }
        return body.substring(0, close) + addition + body.substring(close);
    }

    /**
     * Minimal HTML escaping for text going into a memo.
     *
     * <p>Deliberately not a sanitiser. This escapes text the user typed as *text*; Alt-ALM's actual
     * defence against hostile markup is {@code spa/src/detail/richText.ts}, on the way out, in the
     * browser (see the mutation-XSS reasoning in CLAUDE.md). Escaping on the way in and sanitising
     * on the way out are different jobs and both are needed — output sanitisation alone would still
     * let a stray {@code <} silently eat the rest of a comment.
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                // Newlines are collapsed to spaces by ALM, not converted (probe 27) - so a
                // plain-text comment with paragraphs would silently become one run-on line. Convert
                // them here, where the intent is still known.
                .replace("\r\n", "<br>")
                .replace("\n", "<br>");
    }
}
