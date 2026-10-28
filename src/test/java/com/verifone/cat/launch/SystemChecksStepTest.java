package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 1, System Checks, through the run: each line it owns, what fails it, and what a fake-POS run skips. Every path
 * is under {@code @TempDir} (see {@link LaunchFixtures}); the petroapps tests use a throwaway git origin.
 */
class SystemChecksStepTest {

    @TempDir
    Path dir;

    private LaunchResult run(LaunchPaths paths, LaunchConfig config) {
        return new RunLauncher(paths).launch(config);
    }

    private static LaunchConfig fakePos(String... plans) {
        LaunchConfig.Builder builder = new LaunchConfig.Builder("").fakePos(true);
        for (String plan : plans) {
            builder.testPlan(plan);
        }
        return builder.build();
    }

    private static String log(LaunchResult result) throws IOException {
        return Files.readString(result.runFolder().resolve("run.log"), StandardCharsets.UTF_8);
    }

    @Test
    void aFakePosRunPassesEveryAuditLineItOwnsAndSkipsTheRig() throws IOException {
        LaunchResult result = run(LaunchFixtures.paths(dir), fakePos());

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.COMPLETE, systemChecks.state(), systemChecks.detail());
        String text = log(result);
        assertTrue(text.contains("PASS  Java 17 — "), text);
        assertTrue(text.contains("PASS  Tools folder — "), text);
        assertTrue(text.contains("PASS  Results folder writable — "), text);
        assertTrue(text.contains("SKIP  rig — --fake-pos drives the fake POS"), text);
        assertFalse(text.contains("rig free"), "a fake-POS run takes no run lock:\n" + text);
    }

    @Test
    void aMissingRequiredWorkstationItemFailsSystemChecksWithItsDetail() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        deleteTree(dir.resolve("tools"));

        LaunchResult result = run(paths, fakePos());

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state());
        assertTrue(systemChecks.detail().startsWith("2 required check(s) failed: "), systemChecks.detail());
        assertTrue(systemChecks.detail().contains("Tools folder — tools root not found: "), systemChecks.detail());
        assertTrue(log(result).contains("FAIL  Tools folder — "), log(result));
        assertEquals(StepResult.SKIPPED, result.steps().get(1).state());
    }

    @Test
    void aStagingRowWithNoFolderIsAWarningAndTheStepStillPasses() throws IOException {
        LaunchResult result = run(LaunchFixtures.paths(dir), fakePos());

        assertEquals(StepResult.COMPLETE, result.steps().get(0).state(), result.steps().get(0).detail());
        String text = log(result);
        assertTrue(text.contains(" cwd — "), text);
        assertTrue(text.contains("PASS  javaw.exe — "), text);
    }

    @Test
    void aPetroappsCheckoutBehindOriginIsBroughtCurrent() throws Exception {
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        Path petroapps = PetroappsRepo.withOneCase(dir);
        Path work = dir.resolve("work");
        Files.writeString(work.resolve("AutomationTests").resolve("rulefiles.txt"), "newer", StandardCharsets.UTF_8);
        PetroappsRepo.git(work, "add", "-A");
        PetroappsRepo.commit(work, "a newer commit");
        PetroappsRepo.git(work, "push", "-q", "origin", "master");
        String origin = PetroappsRepo.git(work, "rev-parse", "HEAD");

        LaunchResult result = run(LaunchFixtures.paths(dir, petroapps), fakePos());

        assertEquals(StepResult.COMPLETE, result.steps().get(0).state(), result.steps().get(0).detail());
        String text = log(result);
        assertTrue(text.contains("PASS  petroapps current — master at "), text);
        assertTrue(text.contains("brought up to date from master at "), text);
        assertEquals(origin, PetroappsRepo.git(petroapps, "rev-parse", "HEAD"));
    }

    @Test
    void petroappsIsCheckedAgainstTheRunsTestPlanBranchAndItsUncommittedChangesAreKept() throws Exception {
        // The lab's checkout is on a ticket branch with uncommitted deletions: step 1 must check that branch (the
        // Python's test_plan_branch), not switch the checkout to master.
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        Path petroapps = PetroappsRepo.withOneCase(dir);
        Path work = dir.resolve("work");
        String branch = "PETROCORE-141728";
        PetroappsRepo.git(work, "checkout", "-q", "-b", branch);
        Files.writeString(work.resolve("AutomationTests").resolve("ticket.txt"), "ticket", StandardCharsets.UTF_8);
        PetroappsRepo.git(work, "add", "-A");
        PetroappsRepo.commit(work, "the ticket's commit");
        PetroappsRepo.git(work, "push", "-q", "origin", branch);
        PetroappsRepo.git(work, "checkout", "-q", "master");
        Files.writeString(work.resolve("AutomationTests").resolve("rulefiles.txt"), "newer", StandardCharsets.UTF_8);
        PetroappsRepo.git(work, "add", "-A");
        PetroappsRepo.commit(work, "master moves on");
        PetroappsRepo.git(work, "push", "-q", "origin", "master");
        PetroappsRepo.git(petroapps, "fetch", "-q", "origin");
        PetroappsRepo.git(petroapps, "checkout", "-q", "-b", branch, "origin/" + branch);
        Path deleted = petroapps.resolve("AutomationTests").resolve("ticket.txt");
        Files.delete(deleted);

        LaunchResult result = run(LaunchFixtures.paths(dir, petroapps),
                new LaunchConfig.Builder("").fakePos(true).testPlanBranch(branch).build());

        assertEquals(StepResult.COMPLETE, result.steps().get(0).state(), result.steps().get(0).detail());
        String text = log(result);
        assertTrue(text.contains("PASS  petroapps current — " + branch + " at "), text);
        assertTrue(text.contains("up to date with origin/" + branch), text);
        assertEquals(branch, PetroappsRepo.git(petroapps, "rev-parse", "--abbrev-ref", "HEAD"));
        assertFalse(Files.exists(deleted), "the uncommitted deletion must be kept");
        assertFalse(Files.exists(petroapps.resolve("AutomationTests").resolve("rulefiles.txt")),
                "master's commit must not reach the ticket branch's working tree");
    }

    @Test
    void aPetroappsCheckoutThatCannotBeFetchedFailsSystemChecks() throws Exception {
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        Path petroapps = PetroappsRepo.withOneCase(dir);
        PetroappsRepo.git(petroapps, "remote", "set-url", "origin", dir.resolve("gone.git").toString());

        LaunchResult result = run(LaunchFixtures.paths(dir, petroapps), fakePos());

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state());
        assertTrue(systemChecks.detail().contains("petroapps current — cannot fetch origin/master"),
                systemChecks.detail());
    }

    @Test
    void aPlanWithNoCaseInTheCatalogueFailsTheTestsToRunLine() throws IOException {
        LaunchResult result = run(LaunchFixtures.paths(dir), fakePos("PETROCORE-0000000"));

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state(), systemChecks.detail());
        assertTrue(log(result).contains("FAIL  tests to run"), log(result));
    }

    @Test
    void aPlanThatNeedsCommanderConnectPassesWithOnboardWarnsWhenWaivedAndFailsOtherwise() throws IOException {
        // PETROCORE-135480 needs Commander Connect (the seed's row). The second, unknown plan stops every run at
        // step 1, so no case runs.
        String plans = "PETROCORE-135480";
        String failed = log(run(LaunchFixtures.paths(dir), new LaunchConfig.Builder("").fakePos(true).testPlan(plans)
                .testPlan("PETROCORE-0000000").build()));
        assertTrue(failed.contains("FAIL  Commander Connect onboarding — PETROCORE-135480 need it: turn on Commander "
                + "Connect > Onboard"), failed);

        String onboard = log(run(LaunchFixtures.paths(dir), new LaunchConfig.Builder("").fakePos(true).testPlan(plans)
                .testPlan("PETROCORE-0000000").commanderConnect(true).build()));
        assertTrue(onboard.contains("PASS  Commander Connect onboarding — PETROCORE-135480 need it, and Onboard is on"),
                onboard);

        String waived = log(run(LaunchFixtures.paths(dir), new LaunchConfig.Builder("").fakePos(true).testPlan(plans)
                .testPlan("PETROCORE-0000000").ccRequirementWaived(true).build()));
        assertTrue(waived.contains("WARN  Commander Connect onboarding — PETROCORE-135480 need it; waived for this "
                + "run, not onboarded"), waived);
    }

    @Test
    void aPlanWithCasesPassesTheTestsToRunLine() throws IOException {
        LaunchResult result = run(LaunchFixtures.paths(dir), fakePos("PETROCORE-70960"));

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.COMPLETE, systemChecks.state(), systemChecks.detail());
        String text = log(result);
        assertTrue(text.contains("PASS  tests to run — "), text);
        assertTrue(text.contains("the on-disk check is not ported"), text);
        assertFalse(text.contains("FAIL  simulator"), text);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
