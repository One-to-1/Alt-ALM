package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAccessPolicy;
import ai.surgeone.altalm.bff.alm.read.AlmProjectRef;
import ai.surgeone.altalm.bff.alm.session.AlmCredentials;
import ai.surgeone.altalm.bff.alm.write.AlmVersionGuard;
import ai.surgeone.altalm.bff.alm.write.AlmWriteValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP layer of the write API — the half {@code RecordServiceContractTest} does not reach.
 *
 * <p>That suite proves the service does the right thing against a real ALM. It says nothing about
 * what a browser receives, and the mapping from outcome to status code is where this API is easiest
 * to get quietly wrong.
 *
 * <p><strong>The case this class exists for is {@link UnknownOutcome}.</strong> An ALM 5xx may have
 * committed the row, so the response has to carry a third state that neither 2xx nor 4xx expresses.
 * The chosen mapping is 502 with {@code "outcome": "UNKNOWN"} in the body — and the reason it needs a
 * test rather than a comment is that 502 is the one status a client is most likely to treat as
 * "failed, retry", which for a write that may already have landed produces duplicates.
 */
@WebMvcTest(RecordController.class)
@Import(RecordControllerTest.Credentials.class)
class RecordControllerTest {

    /**
     * Dummy credentials. Not secrets and not a masking question: {@code AlmCredentials} is only here
     * because the controller resolves a default project from it, and these values never leave the
     * test JVM.
     */
    @TestConfiguration
    static class Credentials {
        @Bean
        AlmCredentials almCredentials() {
            return new AlmCredentials("https://alm.invalid/qcbin", "key", "secret", "DOM", "PROJ");
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RecordService records;

    private static WriteDto.WriteResponse committed(String id) {
        return new WriteDto.WriteResponse("COMMITTED", id, false, false, "", "committed", List.of());
    }

    // ==========================================================================================

    @Nested
    @DisplayName("the ordinary paths")
    class HappyPaths {

        @Test
        @DisplayName("a create is 201 and returns the new id")
        void createIs201() throws Exception {
            when(records.create(any(), eq("requirements"), any())).thenReturn(committed("7001"));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"ALTALM-x\"}}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.outcome").value("COMMITTED"))
                    .andExpect(jsonPath("$.id").value("7001"));
        }

        @Test
        @DisplayName("an update is 200 and forwards the expected version")
        void updateIs200() throws Exception {
            when(records.update(any(), eq("requirements"), eq("7001"), any(), any()))
                    .thenReturn(committed("7001"));

            mvc.perform(put("/api/records/requirements/7001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"new\"},\"expectedVersion\":\"3\"}"))
                    .andExpect(status().isOk());

            // The version has to survive the hop. Dropping it would silently downgrade every edit
            // to last-writer-wins while the API still looked like it took a version.
            verify(records).update(any(), eq("requirements"), eq("7001"),
                    eq(Map.of("name", "new")), eq(Optional.of("3")));
        }

        @Test
        @DisplayName("an omitted expectedVersion arrives as empty, not as a null that reads as one")
        void absentVersionIsEmpty() throws Exception {
            when(records.update(any(), any(), any(), any(), any())).thenReturn(committed("7001"));

            mvc.perform(put("/api/records/requirements/7001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"new\"}}"))
                    .andExpect(status().isOk());

            verify(records).update(any(), any(), any(), any(), eq(Optional.empty()));
        }

        @Test
        @DisplayName("a delete is 200")
        void deleteIs200() throws Exception {
            when(records.delete(any(), eq("requirements"), eq("7001")))
                    .thenReturn(committed("7001"));

            mvc.perform(delete("/api/records/requirements/7001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("COMMITTED"));
        }
    }

    @Nested
    @DisplayName("an UNKNOWN outcome — the status that must not be read as 'it failed'")
    class UnknownOutcome {

        @Test
        @DisplayName("an unresolved UNKNOWN is 502, and the body says UNKNOWN rather than an error")
        void unresolvedUnknownIs502() throws Exception {
            when(records.create(any(), any(), any())).thenReturn(new WriteDto.WriteResponse(
                    "UNKNOWN", null, false, false, "qccore.general-error",
                    "ALM returned a server error and the outcome is genuinely unknown", List.of()));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"ALTALM-x\"}}"))
                    .andExpect(status().isBadGateway())
                    // ⚠️ The 502 describes the upstream, not the row. A client branching on status
                    // alone will retry a write that may already have committed; the body is what it
                    // must actually read.
                    .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
                    .andExpect(jsonPath("$.verified").value(false));
        }

        @Test
        @DisplayName("a VERIFIED unknown gets the success status - proceed - but stays UNKNOWN in the body")
        void verifiedUnknownIs200() throws Exception {
            when(records.create(any(), any(), any())).thenReturn(new WriteDto.WriteResponse(
                    "UNKNOWN", "7001", true, false, "qccore.general-error",
                    "a follow-up query found the record", List.of()));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"ALTALM-x\"}}"))
                    .andExpect(status().isCreated())
                    // The outcome does NOT get upgraded to COMMITTED. "The row exists" and "the
                    // write succeeded" are different claims and only the first has evidence.
                    .andExpect(jsonPath("$.outcome").value("UNKNOWN"))
                    .andExpect(jsonPath("$.verified").value(true))
                    .andExpect(jsonPath("$.id").value("7001"));
        }
    }

    @Nested
    @DisplayName("refusals, and keeping them distinguishable")
    class Refusals {

        @Test
        @DisplayName("a validator refusal is 422 and lists EVERY problem, not the first")
        void validationIs422() throws Exception {
            when(records.create(any(), any(), any())).thenThrow(
                    new AlmWriteValidator.RejectedException(List.of(
                            new AlmWriteValidator.Problem("nmae", "unknown-field", "no such field"),
                            new AlmWriteValidator.Problem("estimate", "not-a-number", "not a number"))));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"nmae\":\"x\",\"estimate\":\"high\"}}"))
                    .andExpect(status().isUnprocessableEntity())
                    // Not "REJECTED": this body never left the BFF, and conflating the two makes
                    // "ALM refused it" indistinguishable from "we refused to ask".
                    .andExpect(jsonPath("$.outcome").value("INVALID"))
                    .andExpect(jsonPath("$.problems.length()").value(2))
                    .andExpect(jsonPath("$.problems[0].code").value("unknown-field"))
                    .andExpect(jsonPath("$.problems[1].code").value("not-a-number"));
        }

        @Test
        @DisplayName("an ALM refusal is 400 and carries ALM's own error id")
        void almRejectionIs400() throws Exception {
            when(records.create(any(), any(), any())).thenReturn(new WriteDto.WriteResponse(
                    "REJECTED", null, false, false, "qccore.required-field-missing",
                    "Required field missing", List.of()));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"x\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.outcome").value("REJECTED"))
                    .andExpect(jsonPath("$.errorId").value("qccore.required-field-missing"));
        }

        @Test
        @DisplayName("a version conflict is 409, distinct from both refusal kinds")
        void conflictIs409() throws Exception {
            when(records.update(any(), any(), any(), any(), any()))
                    .thenThrow(new AlmVersionGuard.ConflictException("the record changed since read"));

            mvc.perform(put("/api/records/requirements/7001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"new\"},\"expectedVersion\":\"3\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("version-conflict"));
        }

        @Test
        @DisplayName("a denied project is 403")
        void deniedIs403() throws Exception {
            // The message names a pseudonym rather than the project - that property belongs to
            // AlmAccessPolicy and is tested there. What this asserts is that the handler surfaces
            // it as 403 rather than letting a SecurityException become a 500.
            when(records.create(any(), any(), any())).thenThrow(
                    new AlmAccessPolicy.AccessDeniedException("WRITE DENIED: PROJECT-3 is not on the allowlist"));

            mvc.perform(post("/api/records/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"x\"}}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("access-denied"));
        }

        @Test
        @DisplayName("a collection this API will not write is 400, naming why")
        void unwritableCollectionIs400() throws Exception {
            when(records.create(any(), eq("runs"), any())).thenThrow(
                    new IllegalArgumentException("'runs' is readable but not writable through this API"));

            mvc.perform(post("/api/records/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"x\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad-request"));
        }

        @Test
        @DisplayName("a malformed project parameter is 400 before the service is called")
        void malformedProjectIs400() throws Exception {
            mvc.perform(post("/api/records/requirements?project=no-slash-here")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"name\":\"x\"}}"))
                    .andExpect(status().isBadRequest());

            verify(records, never()).create(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the comment route, which exists so a comment cannot take the replacing path")
    class Comments {

        @Test
        @DisplayName("a comment is 200 and goes to comment(), never to update()")
        void commentRoutes() throws Exception {
            when(records.comment(any(), any(), any(), any(), any(), any()))
                    .thenReturn(committed("7001"));

            mvc.perform(post("/api/records/requirements/7001/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"a note\",\"author\":\"Alice\"}"))
                    .andExpect(status().isOk());

            verify(records).comment(any(), eq("requirements"), eq("7001"), eq("Alice"),
                    eq("a note"), eq(Optional.empty()));
            // update() would REPLACE the memo and delete every earlier comment, with a 200 and
            // nothing to notice (probe 30).
            verify(records, never()).update(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an absent author falls back to Alt-ALM rather than to the service account")
        void authorFallback() throws Exception {
            when(records.comment(any(), any(), any(), any(), any(), any()))
                    .thenReturn(committed("7001"));

            mvc.perform(post("/api/records/requirements/7001/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"a note\"}"))
                    .andExpect(status().isOk());

            // Every write goes out under one service-account key, so ALM's identity says nothing
            // about who typed this. The banner carries a claim; naming it "Alt-ALM" is honest about
            // that, whereas borrowing the account's username would imply an authentication that
            // did not happen.
            verify(records).comment(any(), any(), any(), eq("Alt-ALM"), eq("a note"), any());
        }

        @Test
        @DisplayName("an empty comment is 400 - it would rewrite the field for nothing")
        void emptyCommentIs400() throws Exception {
            mvc.perform(post("/api/records/requirements/7001/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            verify(records, never()).comment(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an entity with no comment field is 404, which is a real answer")
        void noCommentFieldIs404() throws Exception {
            when(records.commentField(any(), eq("run-steps"))).thenReturn(Optional.empty());

            mvc.perform(get("/api/records/run-steps/comment-field"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the comment field is returned by name when the entity has one")
        void commentFieldIsReturned() throws Exception {
            when(records.commentField(any(), eq("defects"))).thenReturn(Optional.of("dev-comments"));

            mvc.perform(get("/api/records/defects/comment-field"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.field").value("dev-comments"));
        }
    }

    @Nested
    @DisplayName("project resolution")
    class ProjectResolution {

        @Test
        @DisplayName("an explicit DOMAIN/PROJECT is honoured rather than silently defaulted")
        void explicitProject() throws Exception {
            when(records.delete(any(), any(), any())).thenReturn(committed("7001"));

            mvc.perform(delete("/api/records/requirements/7001?project=OTHER/PROJ2"))
                    .andExpect(status().isOk());

            verify(records).delete(eq(new AlmProjectRef("OTHER", "PROJ2")), eq("requirements"),
                    eq("7001"));
        }

        @Test
        @DisplayName("no project parameter means the credentialed one")
        void defaultProject() throws Exception {
            when(records.delete(any(), any(), any())).thenReturn(committed("7001"));

            mvc.perform(delete("/api/records/requirements/7001"))
                    .andExpect(status().isOk());

            verify(records).delete(eq(new AlmProjectRef("DOM", "PROJ")), eq("requirements"),
                    eq("7001"));
        }
    }
}
