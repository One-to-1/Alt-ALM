package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAttachmentClient;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a browser receives when it asks Alt-ALM for an attachment.
 *
 * <p>⚠️ <strong>This is a security test wearing an HTTP test's clothes.</strong> Alt-ALM is one
 * deployable on one origin (ADR 0001), so any attachment served inline runs with the app's own
 * session. Every assertion here is about the difference between a file the browser saves and a file
 * the browser <em>executes</em>, and each one fails open if it is deleted: remove the disposition
 * assertion and an uploaded {@code .html} renders; remove the magic-number assertion and an HTML
 * document called {@code .png} renders.
 *
 * <p>The user chose "everything downloads" over an allowlist split (2026-08-20). {@link Downloads}
 * pins that there is no exception; {@link InlineImages} pins that the one endpoint which *can*
 * render is narrow for reasons rather than by luck.
 */
@WebMvcTest(AttachmentController.class)
@Import(AttachmentControllerTest.Credentials.class)
class AttachmentControllerTest {

    /** Dummy credentials — the controller resolves a default project from them. Never leave the JVM. */
    @TestConfiguration
    static class Credentials {
        @Bean
        AlmCredentials almCredentials() {
            return new AlmCredentials("https://alm.invalid/qcbin", "key", "secret", "DOM", "PROJ");
        }
    }

    /** A real 1x1 PNG: valid signature through IEND. A fake would not exercise the magic check. */
    private static final byte[] PNG = HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
                    + "890000000a49444154789c6360000002000100ffff03000006000557bfabd400"
                    + "00000049454e44ae426082");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlmAttachmentClient attachments;

    private void almReturns(byte[] bytes, String mediaType, String fileName) {
        when(attachments.content(any(), eq("requirements"), eq("7001"), eq("42")))
                .thenReturn(new AlmAttachmentClient.AlmAttachmentBytes(bytes, mediaType, fileName));
    }

    // ==========================================================================================

    @Nested
    @DisplayName("the list, which fetches no bytes")
    class Listing {

        @Test
        @DisplayName("reports what is filed against a record")
        void listsAttachments() throws Exception {
            when(attachments.list(any(), eq("requirements"), eq("7001"))).thenReturn(List.of(
                    new AlmAttachmentClient.AlmAttachment("42", "spec.pdf", "the spec", 2048, "")));

            mvc.perform(get("/api/attachments/requirements/7001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value("42"))
                    .andExpect(jsonPath("$.items[0].name").value("spec.pdf"))
                    .andExpect(jsonPath("$.items[0].size").value(2048));
        }

        @Test
        @DisplayName("a record with no attachments is an empty list, not a 404")
        void emptyIsNotAnError() throws Exception {
            when(attachments.list(any(), any(), any())).thenReturn(List.of());

            mvc.perform(get("/api/attachments/requirements/7001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }
    }

    @Nested
    @DisplayName("downloads — one rule, no exceptions")
    class Downloads {

        @Test
        @DisplayName("a PNG downloads too: being safe to render is not a reason to render it")
        void evenAnImageDownloads() throws Exception {
            almReturns(PNG, "image/png", "diagram.png");

            mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/octet-stream"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.startsWith("attachment")));
        }

        @Test
        @DisplayName("ALM's own Content-Type is never echoed, however dangerous it is")
        void almsTypeIsNotEchoed() throws Exception {
            // ⚠️ The case the single rule exists for. Echoing this would serve an uploaded HTML
            // document as HTML, from Alt-ALM's origin, with Alt-ALM's session.
            almReturns("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
                    "text/html", "notes.html");

            mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/octet-stream"))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.startsWith("attachment")));
        }

        @Test
        @DisplayName("the bytes arrive unaltered")
        void bytesRoundTrip() throws Exception {
            almReturns(PNG, "image/png", "diagram.png");

            MvcResult result = mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        }

        @Test
        @DisplayName("⚠️ a hostile filename cannot inject a header — including via the PLAIN form")
        void filenameCannotBreakTheHeader() throws Exception {
            // ⚠️ This test found a real bug on its first run. ContentDisposition emits BOTH a
            // percent-encoded `filename*` and a plain quoted `filename`, and only escapes quotes in
            // the plain one — so this payload came back with its CRLF intact, in a header value.
            // Asserting on the whole header rather than on the encoded half is what caught it.
            almReturns(PNG, "image/png", "ev\"il\r\n; filename=other.html");

            MvcResult result = mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andReturn();

            String disposition = result.getResponse().getHeader("Content-Disposition");
            // No line break, so no second header.
            assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
            // No unescaped quote, so no escaping out of the quoted value.
            assertThat(disposition).doesNotContain("ev\"il");
            // ⚠️ And no separator, so no second PARAMETER. The words "filename=other.html" do
            // survive as inert text inside the quoted value — asserting they are absent entirely
            // would be testing something stronger than the property that matters, and would fail
            // for a file genuinely named that.
            assertThat(disposition).doesNotContain("; filename=other.html");
        }

        @Test
        @DisplayName("a non-Latin filename survives rather than being reduced to underscores")
        void nonAsciiNameSurvives() throws Exception {
            // The sanitiser denies header STRUCTURE, not alphabets. Percent-encoded in `filename*`,
            // which is the parameter every current browser prefers.
            almReturns(PNG, "image/png", "спецификация.png");

            MvcResult result = mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getHeader("Content-Disposition"))
                    .contains("filename*=UTF-8''")
                    .contains("%D1%81");
        }

        @Test
        @DisplayName("a filename is not a path: separators do not survive")
        void pathSeparatorsAreRemoved() throws Exception {
            almReturns(PNG, "image/png", "../../etc/passwd");

            MvcResult result = mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getHeader("Content-Disposition"))
                    .doesNotContain("/etc/")
                    .doesNotContain("%2F");
        }

        @Test
        @DisplayName("a nameless attachment still gets a filename rather than none")
        void namelessGetsAFallback() throws Exception {
            almReturns(PNG, "image/png", "");

            mvc.perform(get("/api/attachments/requirements/7001/42/file"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString("attachment-42")));
        }
    }

    @Nested
    @DisplayName("inline images — the one exception, and how narrow it is")
    class InlineImages {

        @Test
        @DisplayName("a real PNG renders inline, as its own type")
        void realImageRendersInline() throws Exception {
            almReturns(PNG, "image/png", "diagram.png");

            mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.startsWith("inline")));
        }

        @Test
        @DisplayName("⚠️ HTML claiming to be a PNG is refused — the claim is not the evidence")
        void lyingTypeIsRefused() throws Exception {
            // ALM derives its media type from the file EXTENSION, so `payload.png` full of markup
            // arrives announced as image/png. Without the magic-number check this renders.
            almReturns("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                    "image/png", "payload.png");

            mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("⚠️ SVG is refused however genuine it is — it is a document that runs script")
        void svgIsRefused() throws Exception {
            almReturns("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"
                    .getBytes(StandardCharsets.UTF_8), "image/svg+xml", "icon.svg");

            mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("a PDF is refused here: safe to download is not the same as safe to embed")
        void pdfIsRefused() throws Exception {
            almReturns("%PDF-1.7".getBytes(StandardCharsets.UTF_8), "application/pdf", "spec.pdf");

            mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("an attachment ALM reports no type for is refused, not guessed at")
        void missingTypeIsRefused() throws Exception {
            almReturns(PNG, "", "diagram.png");

            mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("a refusal carries no bytes and does not fall back to the download route")
        void refusalIsEmpty() throws Exception {
            almReturns("<html>x</html>".getBytes(StandardCharsets.UTF_8), "image/png", "x.png");

            MvcResult result = mvc.perform(get("/api/attachments/requirements/7001/42/image"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andReturn();

            // ⚠️ A page that asked for an image and silently received a download has been lied to,
            // and the bytes would still have reached the browser.
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }
    }
}
