package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlanCases}: a {@code @TempDir} Python checkout with {@code cadence/} and {@code cadence_55_02/} variants of
 * one key (and the Python's own pulled copy), and a throwaway petroapps clone whose test-plan branch holds the 55.02
 * translation of that key plus one case no local suite holds. The 57.02 plan runs {@code cadence/}, the 55.02 plan
 * {@code cadence_55_02/}, the petroapps-only key is pulled, the placeholder is Not Run, and nothing local is pulled.
 * Nothing touches {@code C:\gitrepos\CAT}, {@code C:\gitrepos\petroapps} or {@code C:\CICD}.
 */
class PlanCasesTest {

    static final String PLAN_57 = "PETROCORE-118517";
    static final String PLAN_55 = "PETROCORE-79667";
    static final String SHARED = "PETROCORE-36936";
    static final String PULLED_ONLY = "PETROCORE-777";
    static final String PLACEHOLDER = "PETROCORE-500";

    @TempDir
    Path dir;

    Path python;

    @BeforeEach
    void checkout() throws IOException {
        python = dir.resolve("python");
        write(python, "tests/suites/cadence/PETROCORE-36936-TestCase.feature", "Feature: 57.02\n  SWIPE PAYMENT\n");
        write(python, "tests/suites/cadence/PETROCORE-36936-CATReceipt.txt", "TAX 5.0000%\n");
        write(python, "tests/suites/cadence_55_02/PETROCORE-36936-TestCase.feature",
                "Feature: 55.02\n  Swipe next loyalty card\n");
        write(python, "tests/suites/cadence_55_02/PETROCORE-36936-CATReceipt.txt", "TAX 5.000%\n");
        write(python, "tests/suites/pulled/PETROCORE-36936-TestCase.feature", "Feature: the Python's pulled copy\n");
        write(python, "tests/suites/charity/PETROCORE-500-TestCase.feature", "@unmigrated\nFeature: placeholder\n");
    }

    @Test
    void eachCadencePlanRunsItsOwnVariantAndOnlyTheRestIsPulled() throws Exception {
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        LaunchPaths paths = paths(petroapps());
        cache(paths, PLAN_57, SHARED, PULLED_ONLY, PLACEHOLDER);
        cache(paths, PLAN_55, SHARED);

        List<CaseEntry> fiftySeven = PlanCases.resolve(config(PLAN_57), paths);
        Map<String, CaseEntry> byKey = fiftySeven.stream().collect(Collectors.toMap(CaseEntry::key, e -> e));
        assertEquals(List.of(SHARED, PULLED_ONLY, PLACEHOLDER), fiftySeven.stream().map(CaseEntry::key).toList());

        CaseEntry shared = byKey.get(SHARED);
        assertEquals("tests/suites/cadence/PETROCORE-36936-TestCase.feature", shared.nodeid());
        assertEquals(python.resolve("tests/suites/cadence/PETROCORE-36936-TestCase.feature").toAbsolutePath(),
                shared.file());
        assertTrue(Files.readString(shared.file()).contains("SWIPE PAYMENT"));
        assertEquals("TAX 5.0000%\n",
                Files.readString(shared.file().getParent().resolve("PETROCORE-36936-CATReceipt.txt")));

        CaseEntry pulled = byKey.get(PULLED_ONLY);
        assertEquals("tests/suites/pulled/PETROCORE-777-TestCase.feature", pulled.nodeid());
        assertEquals(paths.repoRoot().resolve("tests/suites/pulled/PETROCORE-777-TestCase.feature"), pulled.file());
        assertTrue(Files.isRegularFile(pulled.file()));

        CaseEntry notRun = byKey.get(PLACEHOLDER);
        assertFalse(notRun.runnable());
        assertNull(notRun.file());

        // The branch's 55.02 copy of the shared key was never pulled: only the key no local suite holds was.
        assertFalse(Files.exists(paths.repoRoot().resolve("tests/suites/pulled/PETROCORE-36936-TestCase.feature")));

        List<CaseEntry> fiftyFive = PlanCases.resolve(config(PLAN_55), paths);
        assertEquals(1, fiftyFive.size());
        assertEquals("tests/suites/cadence_55_02/PETROCORE-36936-TestCase.feature", fiftyFive.get(0).nodeid());
        assertEquals("TAX 5.000%\n", Files.readString(
                fiftyFive.get(0).file().getParent().resolve("PETROCORE-36936-CATReceipt.txt")));
    }

    @Test
    void bothCadencePlansInOneRunRunEachVariantOnce() throws Exception {
        LaunchPaths paths = paths(dir.resolve("no-petroapps"));
        cache(paths, PLAN_57, SHARED);
        cache(paths, PLAN_55, SHARED);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG).testPlan(PLAN_57).testPlan(PLAN_55)
                .testPlan(PLAN_57).build();

        List<CaseEntry> entries = PlanCases.resolve(config, paths);

        // Every case is local, so petroapps (here not a checkout at all) is never read.
        assertEquals(List.of("tests/suites/cadence/PETROCORE-36936-TestCase.feature",
                "tests/suites/cadence_55_02/PETROCORE-36936-TestCase.feature"),
                entries.stream().map(CaseEntry::nodeid).toList());
    }

    @Test
    void withNoPythonRootEveryCaseIsPulledAsBefore() throws Exception {
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        LaunchPaths base = paths(petroapps());
        LaunchPaths paths = new LaunchPaths(base.resultsRoot(), base.rigsDir(), base.leaseDir(), base.repoRoot(),
                base.petroappsRoot(), base.catalogue(), base.setupEnv());
        cache(paths, PLAN_57, SHARED, PULLED_ONLY);

        List<CaseEntry> entries = PlanCases.resolve(config(PLAN_57), paths);

        assertEquals(List.of("tests/suites/pulled/PETROCORE-36936-TestCase.feature",
                "tests/suites/pulled/PETROCORE-777-TestCase.feature"),
                entries.stream().map(CaseEntry::nodeid).toList());
        assertTrue(Files.readString(entries.get(0).file()).contains("Swipe next loyalty card"));
    }

    private LaunchConfig config(String plan) {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).testPlan(plan).build();
    }

    private LaunchPaths paths(Path petroapps) throws IOException {
        LaunchPaths base = LaunchFixtures.paths(dir, petroapps);
        return new LaunchPaths(base.resultsRoot(), base.rigsDir(), base.leaseDir(), base.repoRoot(),
                base.petroappsRoot(), base.catalogue(), base.setupEnv(), python);
    }

    /** {@code cases-<plan>.json} in the catalogue's cache, the rows in order with no node. */
    private void cache(LaunchPaths paths, String plan, String... keys) throws IOException {
        StringBuilder rows = new StringBuilder();
        for (String key : keys) {
            rows.append(rows.length() == 0 ? "" : ",")
                    .append("{\"key\":\"").append(key).append("\",\"summary\":\"").append(key)
                    .append("\",\"node\":null}");
        }
        write(dir, "cache/cases-" + plan + ".json",
                "{\"fetched_at\":\"2026-10-28T00:00:00+00:00\",\"rows\":[" + rows + "]}");
    }

    /**
     * A petroapps clone on {@code master} holding, as PETROCORE-141728 does, the 55.02 translation of the shared key,
     * and one case only petroapps has.
     */
    private Path petroapps() throws IOException {
        Path origin = dir.resolve("origin.git");
        Path work = dir.resolve("work");
        Path petroapps = dir.resolve("petroapps");
        PetroappsRepo.git(dir, "init", "--bare", "-q", origin.toString());
        PetroappsRepo.git(dir, "init", "-q", "-b", "master", work.toString());
        String cases = "AutomationTests/testcases/";
        write(work, cases + SHARED + "/" + SHARED + "-TestCase.xml", "<case/>");
        write(work, cases + SHARED + "/" + SHARED + "-TestCase.feature", "Feature: 55.02\n  Swipe next loyalty card\n");
        write(work, cases + PULLED_ONLY + "/" + PULLED_ONLY + "-TestCase.xml", "<case/>");
        write(work, cases + PULLED_ONLY + "/" + PULLED_ONLY + "-TestCase.feature", "Feature: petroapps only\n");
        PetroappsRepo.git(work, "add", "-A");
        PetroappsRepo.commit(work, "two cases");
        PetroappsRepo.git(work, "remote", "add", "origin", origin.toString());
        PetroappsRepo.git(work, "push", "-q", "origin", "master");
        PetroappsRepo.git(dir, "clone", "-q", origin.toString(), petroapps.toString());
        return petroapps;
    }

    private static void write(Path root, String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
