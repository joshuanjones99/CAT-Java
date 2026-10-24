package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pats.feature.Gherkin;
import com.verifone.cat.pats.feature.PatsFeature;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The key evidence for the step library: the domain phrases equal the Python's own library, dumped by
 * {@code src/test/resources/authoring/dump_steps.py} under the Python CAT's interpreter, and the PATS phrases the checker
 * reads agree with {@code pats.feature}'s parser on every line of the Charity PATS cases.
 *
 * <p>Skipped (JUnit assumption, not failure) when the Python CAT is not checked out at {@code CAT_PYTHON_SRC} (default
 * {@code C:\gitrepos\CAT\src}) or its interpreter is not at {@code CAT_PYTHON} (default
 * {@code C:\gitrepos\CAT\.venv\Scripts\python.exe}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: agreement here is agreement with the Python library's text, not with a rig.
 */
class StepLibraryParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PYTHON_SRC = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON_SRC", "C:\\gitrepos\\CAT\\src"));
    private static final Path PYTHON = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON", "C:\\gitrepos\\CAT\\.venv\\Scripts\\python.exe"));
    private static final String DUMP = "src/test/resources/authoring/dump_steps.py";

    private static JsonNode pythonLibrary() throws Exception {
        Path repo = PYTHON_SRC.getParent();
        Path script = Path.of(DUMP).toAbsolutePath();
        Process process = new ProcessBuilder(PYTHON.toString(), script.toString())
                .directory(repo.toFile())
                .redirectErrorStream(false)
                .start();
        byte[] out = process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the Python dump did not finish");
        assertEquals(0, process.exitValue(), "the Python dump failed");
        return JSON.readTree(new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void theDomainPhrasesEqualThePythonLibrary() throws Exception {
        assumeTrue(Files.isDirectory(PYTHON_SRC) && Files.isRegularFile(PYTHON),
                "the Python CAT is not checked out here");
        JsonNode python = pythonLibrary();
        JsonNode resource;
        try (InputStream in = StepLibrary.class.getResourceAsStream(StepLibrary.RESOURCE)) {
            resource = JSON.readTree(in);
        }
        assertEquals(python.path("groups"), resource.path("groups"), "the groups differ from the Python");
        assertEquals(python.path("steps"), resource.path("steps"),
                "the step library differs from the Python: regenerate step-library.json with dump_steps.py");
    }

    @Test
    void everyPatsLineOfTheCharityCasesIsAPatsPhraseOnBothSides() throws Exception {
        Path root = Path.of(StepLibraryParityTest.class.getResource("/pats/feature/charity_combined").toURI());
        List<String> lines = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                for (Gherkin.Scenario scenario : Gherkin.read(file).scenarios()) {
                    lines.addAll(scenario.steps());
                }
            }
        }
        assertTrue(lines.size() > 100, "the Charity cases are missing: " + lines.size() + " lines");
        int patsLines = 0;
        for (String line : lines) {
            boolean parserSaysPats = PatsFeature.isPatsLine(line);
            boolean checkerSaysPats = StepLibrary.matches(line, StepLibrary.steps()).stream()
                    .anyMatch(d -> d.level().equals("pats"));
            assertEquals(parserSaysPats, checkerSaysPats, "PATS phrase disagreement on: " + line);
            if (parserSaysPats) {
                patsLines++;
            }
        }
        assertTrue(patsLines > 0);
    }
}
