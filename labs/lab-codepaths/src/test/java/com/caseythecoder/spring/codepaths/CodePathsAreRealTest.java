package com.caseythecoder.spring.codepaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The code paths in {@code paths/*.json} are a study guide: "open these classes, in this order".
 * A study guide that quietly points at a method Spring renamed two versions ago is worse than no
 * guide at all, so this test resolves every entry against the Spring jars on the classpath.
 *
 * <p>When Spring renames something, this fails at build time instead of the site lying to you.
 *
 * <p>Note the direction of the dependency: the site consumes these files, it does not validate
 * them. Verification stays in Java, next to the thing being verified.
 */
class CodePathsAreRealTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> KNOWN_REPOS = Set.of("framework", "boot");

    /** Walks up from the module directory until it finds the repository root. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("paths")) && Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Path.of("").toAbsolutePath());
    }

    static Stream<Path> codePathFiles() {
        Path dir = repositoryRoot().resolve("paths");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void thereIsAtLeastOneCodePathToCheck() {
        assertThat(codePathFiles()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codePathFiles")
    void everyStepNamesAClassAndMethodThatExists(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        String name = file.getFileName().toString();

        assertThat(root.path("title").asText()).as("%s needs a title", name).isNotBlank();
        assertThat(root.path("summary").asText()).as("%s needs a summary", name).isNotBlank();
        assertThat(root.path("entry").asText())
                .as("%s needs an entry point — where to put the first breakpoint", name)
                .isNotBlank();

        JsonNode steps = root.path("steps");
        assertThat(steps.isArray()).as("%s needs a steps array", name).isTrue();
        assertThat(steps.size()).as("a path with one stop is not a path (%s)", name).isGreaterThanOrEqualTo(2);

        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String where = "%s step %d".formatted(name, i + 1);

            String className = step.path("class").asText();
            String methodName = step.path("method").asText();
            assertThat(className).as("%s needs a class", where).isNotBlank();
            assertThat(methodName).as("%s needs a method", where).isNotBlank();
            assertThat(step.path("notice").asText())
                    .as("%s needs a reason to stop there", where)
                    .isNotBlank();
            assertThat(step.path("module").asText()).as("%s needs a module", where).isNotBlank();

            if (step.hasNonNull("repo")) {
                assertThat(step.path("repo").asText()).as("%s repo", where).isIn(KNOWN_REPOS);
            }

            Class<?> type = resolve(className, where);
            assertThat(declaredMethodNames(type))
                    .as("%s: %s has no method named %s", where, className, methodName)
                    .contains(methodName);
        }
    }

    private static Class<?> resolve(String className, String where) {
        try {
            // initialize = false: resolving the class must not run its static initialisers.
            return Class.forName(className, false, CodePathsAreRealTest.class.getClassLoader());
        }
        catch (ClassNotFoundException ex) {
            return fail("%s: no such class on the classpath: %s".formatted(where, className));
        }
    }

    /** Declared methods only — a path step should name the class that actually defines the method. */
    private static List<String> declaredMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).distinct().sorted().toList();
    }
}
