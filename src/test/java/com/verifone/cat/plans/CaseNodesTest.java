package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
    void inItsOwnFolderAFeatureWinsOverThePyAndAPlaceholderIsPassedOver() throws IOException {
        write("tests/suites/cadence_55_02/PETROCORE-7-TestCase.py", "def test_x(): pass\n");
        write("tests/suites/cadence_55_02/PETROCORE-7-TestCase.feature", "Feature: seven\n");
        write("tests/suites/cadence_55_02/PETROCORE-8-TestCase.feature", "@unmigrated\nFeature: eight\n");
        write("tests/suites/cadence_55_02/PETROCORE-8-TestCase.py", "def test_x(): pass\n");

        List<PlanCase> rows = new CaseNodes(root).inPlanSuite("PETROCORE-79667", List.of(
                new PlanCase("PETROCORE-7", "", null), new PlanCase("PETROCORE-8", "", null),
                new PlanCase("PETROCORE-9", "", "tests/suites/charity/PETROCORE-9-TestCase.py")));
        assertEquals("tests/suites/cadence_55_02/PETROCORE-7-TestCase.feature", rows.get(0).node());
        assertEquals("tests/suites/cadence_55_02/PETROCORE-8-TestCase.py", rows.get(1).node());
        assertEquals("tests/suites/charity/PETROCORE-9-TestCase.py", rows.get(2).node(), "not in the folder: kept");
    }

    /** Every folder under the suites dated an hour back, so a listing of them is old enough to keep. */
    private void settle() throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(1)));
        try (Stream<Path> walk = Files.walk(root.resolve("tests/suites"))) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                Files.setLastModifiedTime(dir, old);
            }
        }
    }

    @Test
    void theSuitesAreWalkedOnceWhileNoFolderChangesAndAgainWhenOneDoes() throws IOException {
        write("tests/suites/charity/PETROCORE-36847-TestCase.py", "def test_x(): pass\n");
        write("tests/suites/fuel/PETROCORE-1-TestCase.py", "def test_x(): pass\n");
        settle();
        CaseNodes nodes = new CaseNodes(root);

        assertEquals(Optional.of("tests/suites/charity/PETROCORE-36847-TestCase.py"), nodes.resolve("PETROCORE-36847"));
        assertEquals(Optional.empty(), nodes.resolve("PETROCORE-2"));
        nodes.verify(List.of(new PlanCase("PETROCORE-2", "", null)));
        assertEquals(1, nodes.walks(), "one walk for three lookups of an unchanged tree");

        // A file landing in a folder changes the folder's time: the next lookup walks again and finds it.
        write("tests/suites/fuel/PETROCORE-2-TestCase.py", "def test_x(): pass\n");
        assertEquals(Optional.of("tests/suites/fuel/PETROCORE-2-TestCase.py"), nodes.resolve("PETROCORE-2"));
        assertEquals(2, nodes.walks());

        // So does a new folder (its parent's time changes), and a file removed.
        settle();
        nodes.resolve("PETROCORE-2");
        write("tests/suites/moved/PETROCORE-3-TestCase.py", "def test_x(): pass\n");
        assertEquals(Optional.of("tests/suites/moved/PETROCORE-3-TestCase.py"), nodes.resolve("PETROCORE-3"));
        Files.delete(root.resolve("tests/suites/fuel/PETROCORE-2-TestCase.py"));
        assertEquals(Optional.empty(), nodes.resolve("PETROCORE-2"));
    }

    @Test
    void aListingTakenJustAfterAFolderChangedIsNotKept() throws IOException {
        // The folders were written a moment ago: a change in the same clock tick as the walk could hide in a kept
        // listing, so every lookup walks until the tree has settled.
        write("tests/suites/charity/PETROCORE-36847-TestCase.py", "def test_x(): pass\n");
        CaseNodes nodes = new CaseNodes(root, Clock.systemUTC());

        nodes.resolve("PETROCORE-36847");
        nodes.resolve("PETROCORE-36847");
        assertEquals(2, nodes.walks());

        // Seen from well after the change, the same tree is kept.
        CaseNodes later = new CaseNodes(root, Clock.fixed(Instant.now().plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        later.resolve("PETROCORE-36847");
        later.resolve("PETROCORE-36847");
        assertEquals(1, later.walks());
    }

    @Test
    void anEditedPlaceholderIsReadAgain() throws IOException {
        Path file = root.resolve("tests/suites/charity/PETROCORE-37043-TestCase.py");
        write("tests/suites/charity/PETROCORE-37043-TestCase.py",
                "import pytest\n@pytest.mark.unmigrated\ndef test_x(): pytest.fail('SKIPPED')\n");
        assertTrue(CaseNodes.isUnmigrated(file));
        assertTrue(CaseNodes.isUnmigrated(file), "remembered");

        // Migrated: new content, so a new size and time; the remembered answer is not used.
        write("tests/suites/charity/PETROCORE-37043-TestCase.py", "def test_x(): pass\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plus(Duration.ofSeconds(5))));
        assertFalse(CaseNodes.isUnmigrated(file));
        assertFalse(CaseNodes.isUnmigrated(root.resolve("tests/suites/charity/gone.py")), "unreadable: not one");
    }

    @Test
    void theNumberOfACaseKeyIsItsTrailingDigits() {
        assertEquals(Optional.of("36847"), CaseNodes.numberOf("PETROCORE-36847"));
        assertEquals(Optional.empty(), CaseNodes.numberOf("no digits"));
        assertTrue(CaseNodes.numberOfFile("test_petrocore_12.py").isPresent());
        assertFalse(CaseNodes.numberOfFile("readme.md").isPresent());
    }
}
