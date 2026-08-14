package ai.surgeone.altalm.bff.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: <strong>the HTTP API exposes no write surface at all.</strong>
 *
 * <p>Why this is a test rather than a code-review habit. This BFF holds one API key that can write
 * to nine ALM projects, eight of which belong to other teams (probe 16). {@code AlmAccessPolicy}
 * refuses writes to all but the sandbox, but that is the second line of defence; the first is that
 * no HTTP route capable of triggering a write exists in the first place. A single {@code @PostMapping}
 * added during P2 without routing through the write-safety component would be reachable from any
 * page the browser loads.
 *
 * <p>When P2 legitimately adds writes, this test should be <em>changed deliberately</em> — to assert
 * that every write endpoint goes through the write path — not deleted. A failure here is the
 * intended prompt for that conversation.
 */
class ApiIsReadOnlyTest {

    private static final Path API_SOURCES =
            Path.of("src", "main", "java", "ai", "surgeone", "altalm", "bff", "api");

    @Test
    @DisplayName("no controller method in the api package is annotated with a write mapping")
    void noWriteMappings() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : apiClasses()) {
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class)
                        || m.isAnnotationPresent(PatchMapping.class)) {
                    offenders.add(type.getSimpleName() + "#" + m.getName());
                }
                RequestMapping rm = m.getAnnotation(RequestMapping.class);
                if (rm != null) {
                    for (RequestMethod method : rm.method()) {
                        if (method != RequestMethod.GET) {
                            offenders.add(type.getSimpleName() + "#" + m.getName() + " (" + method + ")");
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("""
                        A write endpoint appeared in the api package. That is not automatically wrong \
                        — P2 adds writes — but it must go through the write-safety component and \
                        AlmAccessPolicy.checkWrite, and this test must then be updated to assert that \
                        rather than simply deleted.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no source file in the api package mentions a write mapping annotation")
    void noWriteMappingsInSource() throws IOException {
        // Belt and braces: catches an annotation on a class that failed to load, and reads as a
        // plain grep so it keeps working if the reflection above is ever refactored away.
        if (!Files.isDirectory(API_SOURCES)) {
            return;
        }
        try (var stream = Files.walk(API_SOURCES)) {
            List<String> offenders = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .map(String::trim)
                                    // Skip comment lines. The javadoc in this package deliberately
                                    // *names* these annotations to explain why they are absent, and
                                    // a naive grep reads that prose as the thing it warns about.
                                    .filter(line -> !line.startsWith("*") && !line.startsWith("//")
                                            && !line.startsWith("/*"))
                                    .anyMatch(line -> line.startsWith("@PostMapping")
                                            || line.startsWith("@PutMapping")
                                            || line.startsWith("@DeleteMapping")
                                            || line.startsWith("@PatchMapping"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .toList();

            assertThat(offenders).isEmpty();
        }
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
