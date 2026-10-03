package com.fisre.engine.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec gate: every requirement marked Implemented in specs/requirements must be referenced by a
 * {@code @Req} annotation in the tests, and every {@code @Req} must name a real requirement.
 */
class SpecCoverageTest {

    private static final Path SPECS = Path.of("..", "specs", "requirements");
    private static final Path TESTS = Path.of("src", "test", "java");
    private static final Pattern IMPLEMENTED_ROW = Pattern.compile("\\|\\s*(REQ-[A-Z]+-\\d+)\\s*\\|.*\\|\\s*Implemented\\s*\\|");
    private static final Pattern ANY_ROW = Pattern.compile("\\|\\s*(REQ-[A-Z]+-\\d+)\\s*\\|");
    private static final Pattern REQ_REF = Pattern.compile("\"(REQ-[A-Z]+-\\d+)\"");

    @Test
    void everyImplementedRequirementHasATest_andEveryTestReferenceIsKnown() throws IOException {
        Set<String> implemented = matches(SPECS, "md", IMPLEMENTED_ROW);
        Set<String> declared = matches(SPECS, "md", ANY_ROW);
        Set<String> referenced = matches(TESTS, "java", REQ_REF);

        assertThat(implemented).as("implemented requirements found in specs").isNotEmpty();

        Set<String> untested = new TreeSet<>(implemented);
        untested.removeAll(referenced);
        assertThat(untested).as("Implemented requirements without a @Req test").isEmpty();

        Set<String> unknown = new TreeSet<>(referenced);
        unknown.removeAll(declared);
        assertThat(unknown).as("@Req ids that do not exist in specs/requirements").isEmpty();
    }

    private static Set<String> matches(Path dir, String ext, Pattern p) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(x -> x.toString().endsWith("." + ext)).filter(x -> !x.getFileName().toString().equals("SpecCoverageTest.java")).toList()) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }
}
