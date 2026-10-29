package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalSuites} against a {@code @TempDir} Python checkout: the plan-owned folder for its own plan only, the
 * hand-migrated file over the Python's pulled copy, the placeholder as Not Run, and the {@code .feature} before the
 * {@code .py}. The cases mirror {@code tests/unit/test_plans.py}'s {@code resolve_node} and {@code PLAN_SUITES} tests.
 */
class LocalSuitesTest {

    static final String CADENCE = "PETROCORE-118517";
    static final String CADENCE_55 = "PETROCORE-79667";

    @TempDir
    Path root;

    @BeforeEach
    void suites() throws IOException {
        write("tests/suites/cadence/PETROCORE-36936-TestCase.feature", "Feature: 57.02\n");
        write("tests/suites/cadence_55_02/PETROCORE-36936-TestCase.feature", "Feature: 55.02\n");
        write("tests/suites/pulled/PETROCORE-36936-TestCase.feature", "Feature: pulled\n");
        write("tests/suites/cadence/PETROCORE-36855-TestCase.feature", "Feature: only in 57.02\n");
        write("tests/suites/pulled/PETROCORE-777-TestCase.feature", "Feature: only pulled\n");
        write("tests/suites/charity/PETROCORE-500-TestCase.feature", "@unmigrated\nFeature: placeholder\n");
        write("tests/suites/menu/PETROCORE-600-TestCase.py", "def test(): pass\n");
        write("tests/suites/menu/PETROCORE-600-TestCase.feature", "Feature: authored\n");
        write("tests/suites/cadence_55_02/PETROCORE-36857-TestCase.feature", "@unmigrated\nFeature: 55 placeholder\n");
        write("tests/suites/cadence/PETROCORE-36857-TestCase.feature", "Feature: 57.02\n");
    }

    @Test
    void theFiftySevenPlanTakesTheCadenceFolderAndNeverThePlanOwnedOne() {
        LocalSuites.Resolution found = new LocalSuites(root).resolve(CADENCE, "PETROCORE-36936");
        assertEquals(LocalSuites.Kind.LOCAL, found.kind());
        assertEquals("tests/suites/cadence/PETROCORE-36936-TestCase.feature", found.node());
        assertEquals(root.resolve("tests/suites/cadence/PETROCORE-36936-TestCase.feature").toAbsolutePath(),
                found.file());
    }

    @Test
    void theFiftyFivePlanTakesItsOwnFolder() {
        LocalSuites.Resolution found = new LocalSuites(root).resolve(CADENCE_55, "PETROCORE-36936");
        assertEquals(LocalSuites.Kind.LOCAL, found.kind());
        assertEquals("tests/suites/cadence_55_02/PETROCORE-36936-TestCase.feature", found.node());
    }

    @Test
    void aCaseItsOwnFolderLacksFallsBackToOrdinaryResolution() {
        LocalSuites suites = new LocalSuites(root);
        assertEquals("tests/suites/cadence/PETROCORE-36855-TestCase.feature",
                suites.resolve(CADENCE_55, "PETROCORE-36855").node());
        // A placeholder in the own folder is not runnable there, so ordinary resolution answers (_in_plan_suite).
        assertEquals("tests/suites/cadence/PETROCORE-36857-TestCase.feature",
                suites.resolve(CADENCE_55, "PETROCORE-36857").node());
    }

    @Test
    void aKeyOnlyInThePythonsPulledFolderIsAbsentSoItIsPulledFresh() {
        LocalSuites.Resolution found = new LocalSuites(root).resolve(CADENCE, "PETROCORE-777");
        assertEquals(LocalSuites.Kind.ABSENT, found.kind());
        assertNull(found.file());
    }

    @Test
    void anUnmigratedPlaceholderResolvesToNothingRunnable() {
        LocalSuites.Resolution found = new LocalSuites(root).resolve(CADENCE, "PETROCORE-500");
        assertEquals(LocalSuites.Kind.UNMIGRATED, found.kind());
    }

    @Test
    void theFeatureWinsOverThePyInOneFolder() {
        assertEquals("tests/suites/menu/PETROCORE-600-TestCase.feature",
                new LocalSuites(root).resolve(CADENCE, "PETROCORE-600").node());
    }

    @Test
    void anUnknownKeyOrAMissingCheckoutIsAbsent() {
        assertEquals(LocalSuites.Kind.ABSENT, new LocalSuites(root).resolve(CADENCE, "PETROCORE-1").kind());
        assertEquals(LocalSuites.Kind.ABSENT, new LocalSuites(root).resolve(CADENCE, "no number").kind());
        assertEquals(LocalSuites.Kind.ABSENT,
                new LocalSuites(root.resolve("nowhere")).resolve(CADENCE, "PETROCORE-36936").kind());
    }

    private void write(String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
