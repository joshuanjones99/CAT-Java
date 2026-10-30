package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Requirements;

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
        assertTrue(systemChecks.detail().startsWith("2 required setup item(s) need attention: "), systemChecks.detail());
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

    /** The checks of {@link SystemChecksStep#checkRequirements} over {@code requirements}, as log text. */
    private String requirementLines(LaunchConfig config, Requirements requirements, String note) throws IOException {
        Path folder = dir.resolve("req-" + System.nanoTime());
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "eeeeeeeeeeee", folder,
                RunEvents.open(folder, java.time.Clock.systemUTC()), new PauseGate(), java.time.Clock.systemUTC());
        ctx.requirements(requirements, note);
        List<String> failures = SystemChecksStep.checkRequirements(ctx, new StepEmitter(ctx, "System Checks"),
                requirements, null);
        ctx.closeEvents();
        StringBuilder out = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode event : LaunchFixtures.events(folder)) {
            if (event.has("message")) {
                out.append(event.get("message").asText()).append('\n');
            }
        }
        return out + "failures=" + failures.size();
    }

    @Test
    void aPlanThatNeedsCommanderConnectPassesWithOnboardWarnsWhenWaivedAndFailsOtherwise() throws IOException {
        // The Python's check_requirements: the plans that need it, joined, on one line.
        Requirements needs = new Requirements(List.of(
                new PlanRequirements("PETROCORE-135480", "", true, List.of(), List.of()),
                new PlanRequirements("PETROCORE-1", "", true, List.of(), List.of())), List.of());
        LaunchConfig.Builder base = new LaunchConfig.Builder("lab").testPlan("PETROCORE-135480").testPlan("PETROCORE-1");

        String failed = requirementLines(base.build(), needs, "");
        assertTrue(failed.contains("FAIL  Commander Connect onboarding — PETROCORE-135480, PETROCORE-1 need it: turn on "
                + "Commander Connect > Onboard"), failed);
        assertTrue(failed.endsWith("failures=1"), failed);

        String onboard = requirementLines(base.commanderConnect(true).build(), needs, "");
        assertTrue(onboard.contains("PASS  Commander Connect onboarding — PETROCORE-135480, PETROCORE-1 need it, and "
                + "Onboard is on"), onboard);

        String waived = requirementLines(new LaunchConfig.Builder("lab").ccRequirementWaived(true).build(), needs, "");
        assertTrue(waived.contains("WARN  Commander Connect onboarding — PETROCORE-135480, PETROCORE-1 need it; waived "
                + "for this run, not onboarded"), waived);
        assertTrue(waived.endsWith("failures=0"), waived);
    }

    @Test
    void anUnlistedPlanAndThePagesReasonAreWarningsAndAnUnknownSimulatorFails() throws IOException {
        Requirements requirements = new Requirements(List.of(
                new PlanRequirements("PETROCORE-1", "", false, List.of("No Such Simulator"), List.of())),
                List.of("PETROCORE-2"));
        String text = requirementLines(new LaunchConfig.Builder("lab").build(), requirements, "read from the cache");
        assertTrue(text.contains("WARN  plan requirements — read from the cache"), text);
        assertTrue(text.contains("WARN  plan requirements — PETROCORE-2 has no row on Supported CAT Test Plans; none "
                + "checked"), text);
        assertTrue(text.contains("FAIL  simulator No Such Simulator — not one CAT knows"), text);
        assertTrue(text.endsWith("failures=1"), text);
    }

    @Test
    void aLicenceFepTheCasesNeedRequiresCommanderConnect() throws IOException {
        String licensed = com.verifone.cat.fep.SecondaryFeps.loadCatalogue().stream()
                .filter(com.verifone.cat.fep.SecondaryFep::licenseEnabled).findFirst().orElseThrow().name();
        String text = requirementLines(new LaunchConfig.Builder("lab").secondaryFep(licensed).build(), null, "");
        assertTrue(text.contains("FAIL  " + SystemChecksStep.LICENSE_LABEL + " — the cases need " + licensed
                + ": turn on Commander Connect > Onboard on the Config tab"), text);
        String onboard = requirementLines(new LaunchConfig.Builder("lab").secondaryFep(licensed).commanderConnect(true)
                .build(), null, "");
        assertTrue(onboard.endsWith("failures=0"), onboard);
    }

    @Test
    void aPlanWhoseCasesHaveNoTestOnDiskOrPulledFailsTheTestsToRunLine() throws IOException {
        // The fixtures' petroapps is no checkout, so nothing is pulled, and there are no local suites.
        LaunchResult result = run(LaunchFixtures.paths(dir), fakePos("PETROCORE-70960"));

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state(), systemChecks.detail());
        String text = log(result);
        assertTrue(text.contains("FAIL  tests to run — no case of PETROCORE-70960 has a test on disk or pulled from "
                + "petroapps"), text);
        assertEquals("nothing to run: no case of the plan has a test", systemChecks.detail());
    }

    @Test
    void aPlanWithResolvedCasesPassesStepOneAndTheRunIsTheWholePlan() throws IOException {
        Path file = Files.createDirectories(dir.resolve("cases")).resolve("PETROCORE-1-TestCase.xml");
        Files.writeString(file, "<testscript/>");
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.wireTests(null, config -> List.of(new CaseEntry("PETROCORE-1", "tests/x/PETROCORE-1-TestCase.xml",
                file), new CaseEntry("PETROCORE-2", null, null)), null);
        LaunchResult result = launcher.launch(fakePos("PETROCORE-70960"));

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.COMPLETE, systemChecks.state(), systemChecks.detail());
        String text = log(result);
        assertFalse(text.contains("tests to run"), text);
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
