package com.verifone.cat.pats.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The Charity-Indoor plan's {@code .feature} files (copied from the Python CAT's {@code tests/suites/charity}, the plan
 * PETROCORE-70960 runs) are written in the step library's domain phrases, not PATS action phrases. Each reads to one
 * {@link LibrarySteps#ACTION} action per step, every step is exactly one library phrase, and the fixtures a case
 * declares are its phrases', as the Python's {@code scenario_fixtures} declares them.
 */
class LibraryStepsTest {

    private static final String ROOT = "/pats/feature/charity_indoor/";

    private static List<Path> features() throws Exception {
        Path folder = Path.of(LibraryStepsTest.class.getResource(ROOT).toURI());
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-TestCase.feature")).sorted().toList();
        }
    }

    @Test
    void everyCharityIndoorCaseReadsToOneLibraryStepPerLine() throws Exception {
        List<Path> files = features();
        assertEquals(39, files.size(), "the Charity-Indoor plan's 39 cases");
        for (Path file : files) {
            RunnableCase kase = PatsCaseLoader.load(file);
            assertFalse(kase.actions().isEmpty(), file.getFileName() + " has steps");
            for (RunnableAction action : kase.actions()) {
                assertTrue(LibrarySteps.isStep(action), file.getFileName() + ": " + action.describe());
                String line = action.get(LibrarySteps.LINE_FIELD);
                StepDef phrase = LibrarySteps.resolve(line);
                assertTrue(phrase.arguments(line).isPresent(), line);
            }
        }
    }

    @Test
    void aStepIsDescribedByItsOwnLine() throws Exception {
        RunnableCase kase = PatsCaseLoader.load(Path.of(getClass().getResource(ROOT
                + "PETROCORE-61334-TestCase.feature").toURI()));
        assertEquals("PETROCORE-61334", kase.key());
        assertEquals("Cash sale with rest for charity", kase.title());
        assertEquals("#1 the register is idle", kase.actions().get(0).describe());
        assertEquals("#4 the customer pays 11.00 in cash", kase.actions().get(3).describe());
    }

    @Test
    void theFixturesAreThePhrasesOwn() throws Exception {
        RunnableCase cash = PatsCaseLoader.load(Path.of(getClass().getResource(ROOT
                + "PETROCORE-61334-TestCase.feature").toURI()));
        assertEquals(List.of(FixtureName.POS, FixtureName.PINPAD), FixtureRequirements.fixturesFor(cash));
        // A case that keys the register through RMI declares the POS too.
        RunnableCase rmi = PatsCaseLoader.load(Path.of(getClass().getResource(ROOT
                + "PETROCORE-61335-TestCase.feature").toURI()));
        List<FixtureName> fixtures = FixtureRequirements.fixturesFor(rmi);
        assertTrue(fixtures.contains(FixtureName.LEGACY_RMI) && fixtures.contains(FixtureName.POS), fixtures.toString());
        RunnableCase store = PatsCaseLoader.load(Path.of(getClass().getResource(ROOT
                + "PETROCORE-61396-TestCase.feature").toURI()));
        assertEquals(List.of(FixtureName.COMMANDER_CONFIG, FixtureName.CONFIGURATIONS),
                FixtureRequirements.fixturesFor(store));
    }

    @Test
    void aLineThatIsNoPhraseFailsByName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LibrarySteps.resolve("the cashier juggles three oranges"));
        assertTrue(e.getMessage().startsWith("step definition not found"), e.getMessage());
    }

    @Test
    void argumentsAreReadAsWritten() {
        String line = "the cashier keys 1 at 10.77 on department key 0x0C for department 1567";
        assertEquals(Map.of("quantity", "1", "amount", "10.77", "key", "0x0C", "department", "1567"),
                LibrarySteps.resolve(line).arguments(line).orElseThrow());
    }

    @Test
    void aPatsPhraseScenarioStillReadsAsPatsActions() throws Exception {
        Path folder = Path.of(getClass().getResource("/pats/feature/charity_outdoor/").toURI());
        try (Stream<Path> files = Files.list(folder)) {
            Path one = files.filter(p -> p.toString().endsWith(".feature")).findFirst().orElseThrow();
            assertTrue(PatsCaseLoader.load(one).actions().stream().noneMatch(LibrarySteps::isStep));
        }
    }
}
