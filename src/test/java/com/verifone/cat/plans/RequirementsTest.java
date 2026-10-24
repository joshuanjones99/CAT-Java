package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class RequirementsTest {

    @Test
    void theBaseCellsReadAsTheyAreWrittenOnThePage() {
        assertEquals(new BaseRule(57, 0, false), BaseRule.parse("57≥").orElseThrow());
        assertEquals(new BaseRule(57, 1, false), BaseRule.parse("on Base 57.01 onwards").orElseThrow());
        assertEquals(new BaseRule(56, 0, true), BaseRule.parse("56≤").orElseThrow());
        assertEquals(new BaseRule(57, 0, false), BaseRule.parse("Base057.00 onwards").orElseThrow());
        assertEquals(Optional.empty(), BaseRule.parse("-"));
        assertEquals(Optional.empty(), BaseRule.parse(""));
    }

    @Test
    void aMinimumAllowsItsBaseAndLaterAndAnUpperBoundAllowsItsBaseAndEarlier() {
        BaseRule minimum = new BaseRule(57, 1, false);
        assertFalse(minimum.allows(57, 0));
        assertTrue(minimum.allows(57, 1));
        assertTrue(minimum.allows(58, 0));

        // A `56≤` (minor zero) compares majors only, so 56.02 is allowed.
        BaseRule upper = new BaseRule(56, 0, true);
        assertTrue(upper.allows(56, 2));
        assertFalse(upper.allows(57, 0));
        assertEquals("base 56.00 or earlier", new BaseRule(56, 0, true).toString());
    }

    @Test
    void theRequirementsOfTwoPlansAreMergedAndDeduplicated() {
        PlanRequirements cadence = new PlanRequirements("PETROCORE-135127", "57≥", false,
                List.of("BUYPASS Host", "PCATS01 Loyalty"), List.of("printer"));
        PlanRequirements other = new PlanRequirements("PETROCORE-70960", "56≤", true,
                List.of("PCATS01 Loyalty"), List.of());
        Requirements both = Requirements.forPlans(
                List.of("PETROCORE-70960", "PETROCORE-135127", "PETROCORE-404"),
                List.of(cadence, other));

        assertEquals(List.of("PETROCORE-70960", "PETROCORE-135127"),
                both.plans().stream().map(PlanRequirements::key).toList(), "in the ticked order");
        assertEquals(List.of("PETROCORE-404"), both.unlisted());
        assertEquals(List.of("PETROCORE-70960"), both.commanderConnect());
        // Ticked order: 70960 comes first and names PCATS01 Loyalty, so it leads; the duplicate is dropped.
        assertEquals(List.of("PCATS01 Loyalty", "BUYPASS Host"), both.simulators());
        assertEquals(List.of("printer"), both.devices(), "one plan names devices, so the defaults do not apply");
        assertTrue(both.needsPrinter());
        assertEquals(Map.of("PETROCORE-135127", new BaseRule(57, 0, false),
                "PETROCORE-70960", new BaseRule(56, 0, true)), both.baseRules());
    }

    @Test
    void aPlanThatNamesNoDevicesLeavesThePrinterNeededAndTheListsEmpty() {
        Requirements none = Requirements.forPlans(List.of("PETROCORE-1"),
                List.of(new PlanRequirements("PETROCORE-1", "", false, List.of(), List.of())));
        assertTrue(none.simulators().isEmpty());
        assertTrue(none.devices().isEmpty());
        assertTrue(none.needsPrinter());
    }

    @Test
    void aPlanThatNamesDevicesWithoutThePrinterDoesNotNeedIt() {
        Requirements pinpadOnly = Requirements.forPlans(List.of("PETROCORE-1"),
                List.of(new PlanRequirements("PETROCORE-1", "", false, List.of(), List.of("pinpad_hardware"))));
        assertFalse(pinpadOnly.needsPrinter());
    }

    @Test
    void theTestPlanLabelPadsItsColumnsAndMarksABlankCaseCount() {
        TestPlan cadence = new TestPlan("PETROCORE-135127", "Cadence", "", "Working", "358", "", "XML");
        assertEquals("Cadence" + " ".repeat(27) + " PETROCORE-135127   " + " 358 cases  Working",
                cadence.label());

        TestPlan blank = new TestPlan("PETROCORE-1", "Alpha", "", "", "", "", "");
        assertTrue(blank.label().contains("   ? cases"), blank.label());
        assertFalse(blank.label().endsWith(" "), "trailing blanks are stripped");
    }
}
