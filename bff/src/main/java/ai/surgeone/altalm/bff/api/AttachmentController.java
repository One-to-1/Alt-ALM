package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmAttachmentClient;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serving attachments out of ALM.
 *
 * <h2>⚠️ Why everything downloads</h2>
 *
 * <p>Alt-ALM is <strong>one deployable on one origin</strong> (ADR 0001). Anything served inline is
 * therefore served from the SPA's own origin, with the SPA's own session — so an uploaded
 * {@code .html} or {@code .svg} rendered in a tab is stored XSS, not a preview. The obvious design
 * ("images and PDFs open, everything else downloads") makes safety depend on an allowlist being
 * right, on {@code nosniff} being present, and on a media type ALM derived from a file extension
 * being true.
 *
 * <p>So {@link #file} has one rule and no branches: {@code Content-Disposition: attachment},
 * {@code application/octet-stream}, {@code nosniff}, for every attachment there is. The user chose
 * this over the split on the grounds that two behaviours confuse people; it also happens to be the
 * only version with no allowlist to get wrong.
 *
 * <h2>The one exception, and what it costs to have one</h2>
 *
 * <p>{@link #image} exists because a memo field can embed an image whose bytes live in ALM, and a
 * download link cannot render inside a paragraph. It is deliberately a <em>separate endpoint</em>
 * rather than a mode of the first, so that "this may render in the browser" is a property of the URL
 * the page asked for, not of a header somebody has to get right.
 *
 * <p>It serves inline only when <strong>both</strong> of these hold:
 *
 * <ul>
 *   <li>ALM's reported media type is on {@link #INLINE_IMAGE_TYPES} — raster formats only.
 *       ⚠️ {@code image/svg+xml} is absent and must stay absent: SVG carries script.
 *   <li>the <em>bytes</em> begin with that format's magic number. ALM derives its media type from
 *       the file extension, so a {@code .png} full of HTML is announced as {@code image/png}. The
 *       claim alone is not evidence, and this is the endpoint where being wrong executes.
 * </ul>
 *
 * <p>Anything else is <strong>415</strong> with no body — not a silent fallback to the download
 * path, because a page that asked for an image and got a download has learned something it should
 * act on.
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    /**
     * Raster image types Alt-ALM will render inline.
     *
     * <p>⚠️ Every entry must be a format that cannot carry script or markup. {@code image/svg+xml}
     * is an XML document with {@code <script>} support and belongs on no such list. Adding a type
     * here without a magic number in {@link #looksLike} makes {@link #image} unreachable for it,
     * which is the safe direction for the mistake to fail in.
     */
    private static final Set<String> INLINE_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private final AlmAttachmentClient attachments;
    private final AlmCredentials credentials;

    public AttachmentController(AlmAttachmentClient attachments, AlmCredentials credentials) {
        this.attachments = attachments;
        this.credentials = credentials;
    }

    /** The attachments filed against one record. Metadata only — no bytes are fetched. */
    @GetMapping("/{collection}/{id}")
    public Map<String, List<AttachmentDto>> list(@PathVariable String collection,
                                                 @PathVariable String id,
                                                 @RequestParam(required = false) String project) {
        List<AttachmentDto> items = attachments.list(resolve(project), collection, id).stream()
                .map(AttachmentDto::of)
                .toList();
        return Map.of("items", items);
    }

    /**
     * One attachment's bytes, as a download. Always a download — see the class javadoc.
     *
     * <p>⚠️ ALM's own {@code Content-Type} is never echoed. It is a claim about a file a user
     * uploaded, and the whole point of this endpoint is that no such claim decides what the browser
     * does with the response.
     */
    @GetMapping("/{collection}/{id}/{attachmentId}/file")
    public ResponseEntity<byte[]> file(@PathVariable String collection,
                                       @PathVariable String id,
                                       @PathVariable String attachmentId,
                                       @RequestParam(required = false) String project) {
        AlmAttachmentClient.AlmAttachmentBytes content =
                attachments.content(resolve(project), collection, id, attachmentId);

        return ResponseEntity.ok()
                .headers(hardened())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition("attachment", content.fileName(), attachmentId))
                .body(content.bytes());
    }

    /**
     * One attachment's bytes, inline, if and only if it is really a raster image.
     *
     * <p>Exists for images embedded in memo fields. See the class javadoc for the two conditions and
     * why the second one (magic number) is not paranoia.
     */
    @GetMapping("/{collection}/{id}/{attachmentId}/image")
    public ResponseEntity<byte[]> image(@PathVariable String collection,
                                        @PathVariable String id,
                                        @PathVariable String attachmentId,
                                        @RequestParam(required = false) String project) {
        AlmAttachmentClient.AlmAttachmentBytes content =
                attachments.content(resolve(project), collection, id, attachmentId);

        String claimed = content.mediaType().toLowerCase();
        if (!INLINE_IMAGE_TYPES.contains(claimed) || !looksLike(claimed, content.bytes())) {
            // ⚠️ No body, and deliberately not a redirect to the download route. A page that asked
            // for an image and silently received something else is a page that has been lied to.
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .headers(hardened())
                    .build();
        }

        return ResponseEntity.ok()
                .headers(hardened())
                .contentType(MediaType.parseMediaType(claimed))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition("inline", content.fileName(), attachmentId))
                .body(content.bytes());
    }

    // ==========================================================================================

    /**
     * Headers both routes carry.
     *
     * <p>{@code nosniff} on the download route is not redundant with
     * {@code application/octet-stream}: without it, some browsers content-sniff a response whose
     * declared type they distrust, and the sniffed type is derived from bytes an uploader chose.
     *
     * <p>{@code no-store} keeps another user's attachment out of a shared cache. One service account
     * fronts every request (ADR 0004), so a cache has no way to tell two users' responses apart.
     */
    private static HttpHeaders hardened() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.setCacheControl("no-store");
        return headers;
    }

    /**
     * A {@code Content-Disposition} whose filename cannot break the header.
     *
     * <p>⚠️ The name comes from ALM, which got it from whoever uploaded the file, so it is attacker
     * -controlled text going into a response header.
     *
     * <p>⚠️ <strong>{@link ContentDisposition} does not make it safe on its own, and the first
     * version of this method assumed it did.</strong> Given a charset it emits <em>both</em> forms —
     * a percent-encoded RFC 5987 {@code filename*} <em>and</em> a plain quoted {@code filename} —
     * and the plain one is passed through with only its quotes escaped. A test feeding it
     * {@code ev"il\r\n; filename=other.html} got exactly that back, CRLF and all, sitting in a
     * header value. {@link #headerSafe} is what removes it; the builder is then doing the encoding
     * job it is good at on input that can no longer hurt.
     */
    private static String disposition(String type, String fileName, String attachmentId) {
        String name = fileName == null || fileName.isBlank()
                ? "attachment-" + attachmentId
                : fileName;
        return ContentDisposition.builder(type)
                .filename(headerSafe(name), StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /**
     * A filename with every character that could end a header value, start a new one, or escape the
     * quoted string replaced by {@code _}.
     *
     * <p>Denies structure, not alphabets: control characters (CR and LF above all), the quote and
     * backslash that delimit and escape the quoted form, the {@code ;} and {@code ,} that separate
     * header parameters and values, and both path separators — a name is not a path, and
     * {@code ../} in one has no business reaching a browser's save dialog.
     *
     * <p>⚠️ Letters outside ASCII are deliberately <em>kept</em>. They survive percent-encoded in
     * {@code filename*}, which every current browser prefers, so a Cyrillic or CJK filename still
     * downloads under its own name. The cost is that the plain {@code filename} parameter carries
     * bytes HTTP/1.1 does not strictly allow — cosmetic, ignored by anything that reads
     * {@code filename*}, and a better trade than turning every non-Latin name into underscores.
     *
     * <p>Length is capped because a filesystem will refuse a 4KB name anyway, and truncating here
     * beats a browser or a proxy doing it somewhere less predictable.
     */
    static String headerSafe(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}\"\\\\;,/]", "_");
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }

    /**
     * Whether the bytes actually start the way the claimed format does.
     *
     * <p>Not a full format validation and not trying to be: the question is only whether ALM's
     * extension-derived claim is contradicted by the file's first bytes, which is what catches an
     * HTML document named {@code .png}. Unknown claim → false, so an unrecognised type can never
     * reach the inline path.
     */
    private static boolean looksLike(String mediaType, byte[] bytes) {
        return switch (mediaType) {
            case "image/png" -> startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(bytes, 'G', 'I', 'F', '8');
            case "image/bmp" -> startsWith(bytes, 'B', 'M');
            // RIFF....WEBP — the four size bytes in between are content, so they are skipped.
            case "image/webp" -> startsWith(bytes, 'R', 'I', 'F', 'F')
                    && bytes.length > 11
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes == null || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** Parses {@code DOMAIN/PROJECT}, defaulting to the credentialed project. */
    private AlmProjectRef resolve(String project) {
        if (project == null || project.isBlank()) {
            return AlmProjectRef.sandboxOf(credentials);
        }
        int slash = project.indexOf('/');
        if (slash <= 0 || slash == project.length() - 1) {
            throw new IllegalArgumentException("project must be 'DOMAIN/PROJECT'");
        }
        return new AlmProjectRef(project.substring(0, slash), project.substring(slash + 1));
    }

    /** The policy's message names a pseudonym, so a 403 cannot be used to enumerate the tenant. */
    @ExceptionHandler(AlmAccessPolicy.AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AlmAccessPolicy.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "access-denied", "detail", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad-request", "detail", e.getMessage()));
    }
}
