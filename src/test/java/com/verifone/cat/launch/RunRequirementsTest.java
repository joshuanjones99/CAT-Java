package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.fep.SecondaryFeps;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Requirements;

/**
 * Run start's half of the plan requirements ({@link RunRequirements}, the Python's {@code apply_requirements},
 * {@code feps_from_tags}, {@code describe}) and the config.py helpers on {@link LaunchConfig}. Files under
 * {@code @TempDir} only.
 */
class RunRequirementsTest {

    @TempDir
    Path dir;

    @Test
    void theCasesTagsNameTheirSecondaryFepsInTheTablesOrder() throws IOException {
        List<SecondaryFep> table = SecondaryFeps.loadCatalogue();
        String first = table.get(0).name();
        String second = table.get(1).name();
        Path a = Files.writeString(dir.resolve("PETROCORE-1-TestCase.feature"), "@PETROCORE-1 @"
                + second.toLowerCase().replaceAll("[^a-z0-9]", "_") + "\nFeature: a\n  Scenario: s\n    Given x\n");
        Path b = Files.writeString(dir.resolve("PETROCORE-2-TestCase.feature"), "@PETROCORE-2 @plan_end\nFeature: b\n"
                + "  @" + first.toUpperCase() + "\n  Scenario: s\n    Given x\n");
        Map<String, Path> files = Map.of("a", a, "b", b);

        assertEquals(List.of(first, second), RunRequirements.fepsFromTags(List.of("a", "b"), files::get));
        assertEquals(List.of(), RunRequirements.fepsFromTags(List.of(), files::get));
    }

    @Test
    void runStartAddsTheTaggedFepsToTheOnesNamedOutrightAndLogsThem() throws IOException {
        String tagged = SecondaryFeps.loadCatalogue().get(0).name();
        Path file = Files.writeString(dir.resolve("PETROCORE-1-TestCase.feature"), "@" + tagged
                + "\nFeature: a\n  Scenario: s\n    Given x\n");
        Path folder = dir.resolve("run");
        RunContext ctx = new RunContext(new LaunchConfig.Builder("").fakePos(true).secondaryFep("Named")
                .suite("tests/x/PETROCORE-1-TestCase.feature").build(), LaunchFixtures.paths(dir), "aaaaaaaaaaaa",
                folder, RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        ctx.caseEntries(List.of(new CaseEntry("PETROCORE-1", "tests/x/PETROCORE-1-TestCase.feature", file)));

        RunRequirements.apply(ctx);
        ctx.closeEvents();

        assertEquals(List.of("Named", tagged), ctx.config().secondaryFeps());
        assertEquals(List.of("Named", tagged), ctx.commanderOptions().secondaryFeps(), "step 4 follows the config");
        assertTrue(Files.readString(folder.resolve("events.ndjson")).contains("secondary FEPs, from the cases' tags: "
                + tagged));
        assertEquals(null, ctx.requirements(), "a fake-POS run reads no requirements");
    }

    @Test
    void describeSaysWhatThePlansNeedAsThePageSaysIt() {
        Requirements requirements = Requirements.forPlans(List.of("PETROCORE-1", "PETROCORE-2", "PETROCORE-3"),
                List.of(new PlanRequirements("PETROCORE-1", "57≥", true, List.of("BUYPASS Host"), List.of("scale")),
                        new PlanRequirements("PETROCORE-2", "", false, List.of(), List.of())));
        assertEquals("PETROCORE-1 base 57.00 or later; Commander Connect (PETROCORE-1); simulators BUYPASS Host; "
                + "devices scale; not on the page: PETROCORE-3", RunRequirements.describe(requirements));
        assertEquals("nothing beyond the defaults", RunRequirements.describe(new Requirements(List.of(), List.of())));
    }

    @Test
    void theRecordedButUnwiredChoicesAndTheLicenceFeps() {
        assertEquals(List.of(), LaunchConfig.forRig("lab").unwiredOptions());
        assertEquals(List.of("execution mode BDD", "IFSF IFSF-2"), new LaunchConfig.Builder("lab").executionMode("BDD")
                .ifsfMode("IFSF-2").build().unwiredOptions());
        String licensed = SecondaryFeps.loadCatalogue().stream().filter(SecondaryFep::licenseEnabled).findFirst()
                .orElseThrow().name();
        LaunchConfig config = new LaunchConfig.Builder("lab").secondaryFep(licensed.toLowerCase())
                .secondaryFep("not in the table").build();
        assertEquals(List.of(licensed), config.licenseFeps());
        assertEquals(1, config.secondaryFepRows().size(), "a name the table lacks is left out");
    }

    @Test
    void discoverSuitesListsTheSuiteFoldersSorted() throws IOException {
        Files.createDirectories(dir.resolve("tests/suites/zeta"));
        Files.createDirectories(dir.resolve("tests/suites/alpha"));
        Files.writeString(dir.resolve("tests/suites/file.txt"), "");
        assertEquals(List.of("tests/suites/alpha", "tests/suites/zeta"), LaunchConfig.discoverSuites(dir));
        assertEquals(List.of(), LaunchConfig.discoverSuites(dir.resolve("none")));
    }

    @Test
    void lastConfigReadsTheConfigTabsIfsfModeAndLocalBuild() {
        LastConfig saved = LastConfig.of(Map.of("config-ifsf", "IFSF-2", "config-cmd-local-build", "Solutions133"));
        assertEquals("IFSF-2", saved.ifsfMode());
        assertEquals("Solutions133", saved.localBuild());
        assertEquals("", LastConfig.of(Map.of("config-cmd-branch", "Solutions", "config-cmd-local-build",
                "Solutions133")).localBuild(), "a chosen Commander branch takes no local build");
    }
}
