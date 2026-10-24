package com.verifone.cat.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportPathsTest {

    @TempDir
    Path dir;

    @Test
    void theRootIsTheDocumentedDefaultWhenNothingOverridesIt() {
        assertEquals(Path.of("C:/CICD/Results/CAT"), ReportPaths.resultsRoot(new HashMap<>()));
    }

    @Test
    void aBlankOverrideCountsAsUnset() {
        assertEquals(ReportPaths.DEFAULT_RESULTS_ROOT,
                ReportPaths.resultsRoot(Map.of(ReportPaths.RESULTS_DIR_ENV, "   ")));
    }

    @Test
    void catResultsDirMovesTheRoot() {
        Map<String, String> env = Map.of(ReportPaths.RESULTS_DIR_ENV, " " + dir + " ");

        assertEquals(dir, ReportPaths.resultsRoot(env));
        assertEquals(dir.resolve("run-1"), ReportPaths.runFolder(ReportPaths.resultsRoot(env), "run-1"));
    }

    @Test
    void theRunIdIsTheEnvironmentsWhenItIsSet() {
        Map<String, String> env = new HashMap<>(Map.of(ReportPaths.RUN_ID_ENV, "run-fixed"));

        assertEquals("run-fixed", ReportPaths.sessionRunId(env, Instant.parse("2026-10-24T15:30:00Z")));
        assertEquals("run-fixed", env.get(ReportPaths.RUN_ID_ENV));
    }

    @Test
    void anUnsetRunIdIsAUtcTimestampStoredForLaterCallers() {
        Map<String, String> env = new HashMap<>();

        String first = ReportPaths.sessionRunId(env, Instant.parse("2026-10-24T15:30:00Z"));
        String second = ReportPaths.sessionRunId(env, Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals("20261024T153000Z", first);
        assertEquals(first, second, "every writer in the process must agree on one run id");
    }

    @Test
    void caseKeyReadsTheFileNameInBothForms() {
        assertEquals("PETROCORE-61334",
                ReportPaths.caseKey("tests/suites/charity/PETROCORE-61334-TestCase.feature::test_x"));
        assertEquals("PETROCORE-61334",
                ReportPaths.caseKey("tests/suites/charity/test_petrocore_61334.py::test_petrocore_61334"));
        assertEquals("", ReportPaths.caseKey("tests/unit/test_something.py::test_something"));
    }

    @Test
    void aCaseKeyOnlyComesFromTheFileNotTheTestName() {
        assertEquals("", ReportPaths.caseKey("tests/unit/test_x.py::test_PETROCORE-1-TestCase"));
    }

    @Test
    void aCaseTakesItsKeyAsItsFolderName() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();

        assertEquals("PETROCORE-61334",
                folders.name("tests/suites/charity/PETROCORE-61334-TestCase.feature::test_x"));
    }

    @Test
    void aParametrisedCaseAddsItsParameter() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();

        assertEquals("PETROCORE-61338-buypass",
                folders.name("tests/suites/charity/PETROCORE-61338-TestCase.feature::test_x[buypass]"));
    }

    @Test
    void aSecondTestThatWouldTakeTheSameNameGetsItsTestNameAdded() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();

        String first = folders.name("tests/a/PETROCORE-1-TestCase.feature::test_one[x]");
        String second = folders.name("tests/b/test_petrocore_1.py::test_two[x]");

        assertEquals("PETROCORE-1-x", first);
        assertEquals("PETROCORE-1-test_two_x", second);
    }

    @Test
    void theSameNodeidAlwaysGetsTheSameFolder() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();
        String nodeid = "tests/a/PETROCORE-1-TestCase.feature::test_one[x]";

        assertEquals(folders.name(nodeid), folders.name(nodeid));
    }

    @Test
    void aTestThatIsNotACaseKeepsItsNodeidMadeSafe() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();

        assertEquals("tests_suites_test_test_pass.py__test_pass",
                folders.name("tests/suites/test/test_pass.py::test_pass"));
    }

    @Test
    void aLongFolderNameIsBoundedAndStaysDistinct() {
        ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();
        String longA = "tests/suites/" + "a".repeat(200) + ".py::test_" + "x".repeat(50) + "A";
        String longB = "tests/suites/" + "a".repeat(200) + ".py::test_" + "x".repeat(50) + "B";

        String a = folders.name(longA);
        String b = folders.name(longB);

        assertTrue(a.length() <= ReportPaths.CASE_FOLDER_MAX, a);
        assertTrue(a.matches(".*-[0-9a-f]{10}"), a);
        assertTrue(!a.equals(b), "two long node ids must not share a folder");
    }

    @Test
    void theCaseKeyHelperIsNotTheWholeNodeid() {
        assertEquals("PETROCORE-61334",
                ReportPaths.caseKey("tests\\suites\\PETROCORE-61334-TestCase.feature::t"));
    }
}
