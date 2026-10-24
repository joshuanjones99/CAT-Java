package com.verifone.cat.pats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PatsCaseLoader}: a case read from a file path comes back as a {@link RunnableCase}, whichever of the three
 * file types it is, with its key and source set. Reads real files from the temporary folder; no petroapps checkout.
 */
class PatsCaseLoaderTest {

    @TempDir
    Path folder;

    /** Copies the test resource {@code resource} to {@code target} under the temporary folder, and returns it. */
    private Path copy(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = PatsCaseLoaderTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing fixture " + resource);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    @Test
    void anXmlCaseIsReadAsItsActionsWithItsKeyFromItsFolderAndItsSource() throws IOException {
        Path file = copy("/pats/testcases/wrapped-testcase.xml",
                folder.resolve("PETROCORE-43126").resolve("PETROCORE-43126-TestCase.xml"));

        RunnableCase kase = PatsCaseLoader.load(file);

        assertEquals("PETROCORE-43126", kase.key());
        assertEquals("DEPT-TRANS", kase.title());
        assertEquals(file.toAbsolutePath().toString(), kase.source());
        assertEquals(List.of("TOTAL", "SLEEP"), kase.actions().stream().map(RunnableAction::name).toList());
    }

    @Test
    void aBddScenarioIsReadAsItsActionsMarkedAsBdd() throws IOException {
        Path file = copy("/pats/scenarios/bdd-case.scenario", folder.resolve("PETROCORE-7-TestCase.scenario"));

        RunnableCase kase = PatsCaseLoader.load(file);

        assertEquals("PETROCORE-7", kase.key());
        assertFalse(kase.actions().isEmpty());
        assertTrue(kase.actions().stream().allMatch(action -> "BDD".equals(action.get("SOURCE"))));
    }

    @Test
    void aFeatureIsReadAsItsOneScenarioWithItsKeyFromItsFileName() throws IOException {
        Path file = copy("/pats/feature/charity_outdoor/PETROCORE-37039-TestCase.feature",
                folder.resolve("PETROCORE-37039-TestCase.feature"));

        RunnableCase kase = PatsCaseLoader.load(file);

        assertEquals("PETROCORE-37039", kase.key());
        assertFalse(kase.title().isBlank());
        assertFalse(kase.actions().isEmpty());
        List<Integer> indices = kase.actions().stream().map(RunnableAction::index).toList();
        assertEquals(IntStream.range(0, indices.size()).boxed().toList(), indices);
    }

    @Test
    void aFileOfAnyOtherTypeIsRefused() throws IOException {
        Path file = Files.writeString(folder.resolve("PETROCORE-1-TestCase.txt"), "not a case");

        assertThrows(IllegalArgumentException.class, () -> PatsCaseLoader.load(file));
    }

    @Test
    void theKeyIsTheFileNameThenTheNearestFolderThenTheBaseName() {
        assertEquals("PETROCORE-5", PatsCaseLoader.keyOf(Path.of("C:/cases/PETROCORE-5/PLU-Cash-TestCase.xml")));
        assertEquals("PETROCORE-6", PatsCaseLoader.keyOf(Path.of("C:/cases/PETROCORE-6/Plain.xml")));
        assertEquals("Plain", PatsCaseLoader.keyOf(Path.of("C:/cases/Plain-TestCase.xml")));
    }
}
