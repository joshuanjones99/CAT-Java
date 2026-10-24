package com.verifone.cat.pats.feature;

import com.verifone.cat.pats.Action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hand-written Gherkin reading, held to what pytest-bdd gives the Python: tags, comments, outlines read as written,
 * the background left out, tables and doc strings not changing a step's name, and exactly one scenario per file.
 */
class GherkinTest {

    @TempDir
    Path dir;

    @Test
    void tagsCommentsAndStepKeywordsAreRead() {
        Gherkin.Feature feature = Gherkin.parse("""
                # language: en
                @PETROCORE-1 @fepsim
                Feature: Charity Donations

                  # a comment
                  Some description text.

                  @pats_bdd
                  Scenario: Sale
                    Given the register is idle
                    When the cashier touches ITEM_PANEL_N(145397)
                    And PATS pauses 2000 ms
                    But the register keys TOTAL
                    * the receipt matches "X.txt"
                    Then the result is shown
                """);
        assertEquals("Charity Donations", feature.name());
        assertEquals(Set.of("PETROCORE-1", "fepsim"), feature.tags());
        Gherkin.Scenario scenario = feature.scenarios().get(0);
        assertEquals("Sale", scenario.name());
        assertEquals(Set.of("pats_bdd"), scenario.tags());
        assertFalse(scenario.outline());
        assertEquals(List.of("the register is idle", "the cashier touches ITEM_PANEL_N(145397)",
                "PATS pauses 2000 ms", "the register keys TOTAL", "the receipt matches \"X.txt\"",
                "the result is shown"), scenario.steps());
    }

    @Test
    void anOutlinesStepsAreReadAsWrittenAndItsExamplesAreNot() {
        Gherkin.Feature feature = Gherkin.parse("""
                Feature: Outline
                  Scenario Outline: Pump <n>
                    When pump "<n>" fuels "1.00"
                    Examples:
                      | n |
                      | 1 |
                """);
        Gherkin.Scenario scenario = feature.scenarios().get(0);
        assertTrue(scenario.outline());
        assertEquals(List.of("pump \"<n>\" fuels \"1.00\""), scenario.steps());
    }

    @Test
    void theBackgroundBelongsToNoScenario() {
        Gherkin.Feature feature = Gherkin.parse("""
                Feature: Background
                  Background:
                    Given the register is idle
                  Scenario: One
                    When the register keys TOTAL
                """);
        assertEquals(List.of("the register keys TOTAL"), feature.scenarios().get(0).steps());
    }

    @Test
    void tablesAndDocStringsDoNotChangeAStepName() {
        Gherkin.Feature feature = Gherkin.parse("""
                Feature: Tables
                  Scenario: Table
                    When the register keys TOTAL
                      | key | value |
                      | a   | b     |
                    And the register keys TOTAL
                      \"\"\"
                      Given this is inside a doc string
                      \"\"\"
                    Then the result is shown
                """);
        assertEquals(List.of("the register keys TOTAL", "the register keys TOTAL", "the result is shown"),
                feature.scenarios().get(0).steps());
    }

    @Test
    void examplesBlocksAreSkipped() {
        Gherkin.Feature feature = Gherkin.parse("""
                Feature: Examples
                  Scenario: Plain
                    When the register keys TOTAL
                  Examples:
                    | x |
                """);
        assertEquals(1, feature.scenarios().size());
        assertEquals(List.of("the register keys TOTAL"), feature.scenarios().get(0).steps());
    }

    @Test
    void aFileWithOneScenarioReadsItsActions() throws IOException {
        Path file = dir.resolve("PETROCORE-9-TestCase.feature");
        Files.writeString(file, """
                @PETROCORE-9 @pats_bdd
                Feature: Nine
                  Scenario: Nine
                    When the register keys TOTAL
                    And PATS pauses 2000 ms
                """, StandardCharsets.UTF_8);
        List<Action> actions = PatsFeature.actionsOf(file);
        assertEquals(List.of("TOTAL", "SLEEP"), actions.stream().map(Action::name).toList());
        assertTrue(actions.stream().allMatch(a -> "BDD".equals(a.raw("SOURCE"))));
    }

    @Test
    void aFileWithTwoScenariosIsRefused() throws IOException {
        Path file = dir.resolve("two.feature");
        Files.writeString(file, """
                Feature: Two
                  Scenario: A
                    When the register keys TOTAL
                  Scenario: B
                    When the register keys TOTAL
                """, StandardCharsets.UTF_8);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PatsFeature.actionsOf(file));
        assertTrue(refused.getMessage().contains("expected one scenario, found 2"), refused.getMessage());
    }
}
