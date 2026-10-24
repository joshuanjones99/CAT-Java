package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Node resolution against a fake checkout built in a temp folder. */
class CaseNodesTest {

    @TempDir
    Path root;

    private void write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void aCaseResolvesToItsMigratedTestAndAPlaceholderIsNotRunnable() throws IOException {
        write("tests/suites/charity/PETROCORE-36847-TestCase.py", "def test_x(): pass\n");
        write("tests/suites/charity/PETROCORE-37043-TestCase.py",
                "import pytest\n@pytest.mark.unmigrated\ndef test_x(): pytest.fail('SKIPPED')\n");
        CaseNodes nodes = new CaseNodes(root);

        assertEquals(Optional.of("tests/suites/charity/PETROCORE-36847-TestCase.py"),
                nodes.resolve("PETROCORE-36847"));
        assertEquals(Optional.empty(), nodes.resolve("PETROCORE-37043"), "a placeholder is Not Run");
        assertEquals(Optional.empty(), nodes.resolve("PETROCORE-99999"));
    }

    @Test
    void aPulledCopyLosesToAHandMigratedFileOfTheSameNumber() throws IOException {
        write("tests/suites/pulled/PETROCORE-5-TestCase.py", "def test_p(): pass\n");
        write("tests/suites/charity/PETROCORE-5-TestCase.py", "def test_c(): pass\n");

        assertEquals(Optional.of("tests/suites/charity/PETROCORE-5-TestCase.py"),
                new CaseNodes(root).resolve("PETROCORE-5"));
    }

    @Test
    void aFeatureBesideAPyStubWinsAndTheStubIsDropped() throws IOException {
        write("tests/suites/charity/PETROCORE-6-TestCase.py", "def test_stub(): pass\n");
        write("tests/suites/charity/PETROCORE-6-TestCase.feature", "Feature: six\n");
        CaseNodes nodes = new CaseNodes(root);

        List<PlanCase> rows = nodes.verify(List.of(
                new PlanCase("PETROCORE-6", "", "tests/suites/charity/PETROCORE-6-TestCase.py")));
        assertEquals("tests/suites/charity/PETROCORE-6-TestCase.feature", rows.get(0).node());
    }

    @Test
    void aStaleNodeIsLookedUpAgainAndAGoneOneReadsAsNotRun() throws IOException {
        write("tests/suites/charity/PETROCORE-8-TestCase.py", "def test_x(): pass\n");
        CaseNodes nodes = new CaseNodes(root);

        List<PlanCase> rows = nodes.verify(List.of(
                new PlanCase("PETROCORE-8", "", "tests/suites/old/PETROCORE-8-TestCase.py"),
                new PlanCase("PETROCORE-9", "", "tests/suites/old/PETROCORE-9-TestCase.py")));
        assertEquals("tests/suites/charity/PETROCORE-8-TestCase.py", rows.get(0).node());
        assertNull(rows.get(1).node());
    }

    @Test
    void aPlanWithItsOwnSuiteFolderPointsAtThatFolderOnly() throws IOException {
        write("tests/suites/cadence_55_02/PETROCORE-7-TestCase.py", "def test_x(): pass\n");
        write("tests/suites/charity/PETROCORE-7-TestCase.py", "def test_other(): pass\n");
        CaseNodes nodes = new CaseNodes(root);

        List<PlanCase> rows = nodes.inPlanSuite("PETROCORE-79667",
                List.of(new PlanCase("PETROCORE-7", "", null)));
        assertEquals("tests/suites/cadence_55_02/PETROCORE-7-TestCase.py", rows.get(0).node());

        // The folder is the owning plan's alone: another plan resolves past it.
        assertEquals(Optional.of("tests/suites/charity/PETROCORE-7-TestCase.py"), nodes.resolve("PETROCORE-7"));
    }

    @Test
    void theNumberOfACaseKeyIsItsTrailingDigits() {
        assertEquals(Optional.of("36847"), CaseNodes.numberOf("PETROCORE-36847"));
        assertEquals(Optional.empty(), CaseNodes.numberOf("no digits"));
        assertTrue(CaseNodes.numberOfFile("test_petrocore_12.py").isPresent());
        assertFalse(CaseNodes.numberOfFile("readme.md").isPresent());
    }
}
