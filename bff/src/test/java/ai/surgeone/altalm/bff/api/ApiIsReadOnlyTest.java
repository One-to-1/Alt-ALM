package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.write.AlmWriteClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: <strong>every HTTP write route goes through {@link AlmWriteClient}.</strong>
 *
 * <p>⚠️ <strong>This test changed shape at the start of P2, on purpose.</strong> It used to assert
 * the api package contained no write mapping at all, and CLAUDE.md said in advance that when writes
 * arrived it must be <em>rewritten to assert routing</em> rather than deleted. This is that rewrite.
 * The count of write endpoints is still zero today; the guard is in place before the first one lands
 * rather than after, because "add the endpoint, then remember to add the check" is the order that
 * fails.
 *
 * <p>Why a test rather than a code-review habit. This BFF holds one API key that can write to nine
 * ALM projects, eight of which belong to other teams (probe 16). {@code AlmAccessPolicy} refuses
 * writes to all but the sandbox — but a controller that reached ALM through some other client, or
 * hand-built a request, would never consult it. {@link AlmWriteClient} is where the sandbox rule,
 * the deterministic field order, the 5xx-is-not-a-failure rule and the single missing-field retry
 * all live; a write endpoint that does not go through it has none of them.
 *
 * <p>What this does <em>not</em> check: that the write is correct. It checks that the write is
 * reachable only through the component where correctness is enforced.
 */
class ApiIsReadOnlyTest {

    private static final Path API_SOURCES =
            Path.of("src", "main", "java", "ai", "surgeone", "altalm", "bff", "api");

    @Test
    @DisplayName("every controller with a write mapping can reach AlmWriteClient")
    void writeEndpointsRouteThroughTheWriteClient() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : apiClasses()) {
            if (writeMappingsOf(type).isEmpty()) {
                continue;
            }
            if (!reachesWriteClient(type, 0)) {
                offenders.add(type.getSimpleName() + " " + writeMappingsOf(type));
            }
        }

        assertThat(offenders)
                .as("""
                        A write endpoint exists whose controller cannot reach AlmWriteClient. Every \
                        ALM write must go through it: it is where the sandbox-only rule, the \
                        deterministic field order, the 5xx-is-UNKNOWN rule and the single \
                        missing-required-field retry are enforced. A controller that writes by any \
                        other route has none of them.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no api class builds an ALM write by hand, bypassing the write client")
    void noHandRolledWrites() throws IOException {
        // The reflection above proves a controller *can* reach the write client; it cannot prove the
        // controller uses it rather than issuing its own request. This catches the other half by
        // source: an HTTP verb being invoked directly from the api package.
        if (!Files.isDirectory(API_SOURCES)) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(API_SOURCES)) {
            for (Path file : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String raw : Files.readAllLines(file)) {
                    String line = raw.trim();
                    // Comments name these deliberately when explaining why they are absent, and a
                    // naive grep reads that prose as the thing it warns about.
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (line.contains("RestClient") || line.contains("HttpMethod.POST")
                            || line.contains("HttpMethod.PUT") || line.contains("HttpMethod.DELETE")
                            || line.contains("RestTemplate") || line.contains("HttpClient")) {
                        offenders.add(file.getFileName() + ": " + line);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("An api class is holding an HTTP client. ALM is reached through AlmEntityClient "
                        + "(reads) and AlmWriteClient (writes), never directly.")
                .isEmpty();
    }

    /** Write mappings declared on a controller, by method name. */
    private static List<String> writeMappingsOf(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(PostMapping.class)
                    || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(DeleteMapping.class)
                    || m.isAnnotationPresent(PatchMapping.class)) {
                found.add(m.getName());
            }
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm != null) {
                for (RequestMethod method : rm.method()) {
                    if (method != RequestMethod.GET) {
                        found.add(m.getName() + "(" + method + ")");
                    }
                }
            }
        }
        return found;
    }

    /**
     * Whether a controller holds {@link AlmWriteClient}, directly or through a collaborator.
     *
     * <p>Follows declared fields rather than constructor parameters so it sees the dependency as it
     * actually exists on the object, and is bounded to our own packages and to a shallow depth: the
     * question is "does this controller have a write path", not "is a write reachable somewhere in
     * the object graph", and an unbounded walk would eventually answer yes to everything.
     */
    private static boolean reachesWriteClient(Class<?> type, int depth) {
        if (depth > 3) {
            return false;
        }
        for (Field f : type.getDeclaredFields()) {
            Class<?> fieldType = f.getType();
            if (AlmWriteClient.class.equals(fieldType)) {
                return true;
            }
            if (fieldType.getName().startsWith("ai.surgeone.altalm")
                    && reachesWriteClient(fieldType, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Loads every class in the api package from its source listing. */
    private static List<Class<?>> apiClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        if (!Files.isDirectory(API_SOURCES)) {
            return classes;
        }
        try (var stream = Files.list(API_SOURCES)) {
            for (Path p : stream.toList()) {
                String file = p.getFileName().toString();
                if (!file.endsWith(".java")) {
                    continue;
                }
                classes.add(Class.forName(
                        "ai.surgeone.altalm.bff.api." + file.substring(0, file.length() - 5)));
            }
        }
        return classes;
    }
}
