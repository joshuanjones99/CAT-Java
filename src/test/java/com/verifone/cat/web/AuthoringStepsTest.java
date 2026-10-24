package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.authoring.StepLibrary;

/** The Steps tab's filter and the example insert, as pure functions. Evidence about this repository only. */
class AuthoringStepsTest {

    @Test
    void anEmptyFilterListsEveryPhrase() {
        assertEquals(StepLibrary.steps().size(), AuthoringSteps.matching("").size());
        assertEquals(StepLibrary.steps().size(), AuthoringSteps.matching("   ").size());
    }

    @Test
    void everyWordOfTheFilterMustMatch() {
        StepDef first = StepLibrary.steps().get(0);
        List<StepDef> hits = AuthoringSteps.matching(first.example().toUpperCase());

        assertTrue(hits.contains(first), first.example());
        assertTrue(AuthoringSteps.matching("zzqqxxyy").isEmpty());
        assertTrue(AuthoringSteps.matching(first.example() + " zzqqxxyy").isEmpty());
    }

    @Test
    void anExampleGoesUnderTheLastNonBlankLine() {
        String text = "@PETROCORE-1\nFeature: x\n\n  Scenario: s\n    Given the register is idle\n\n";

        String out = AuthoringSteps.insertExample(text, "When the cashier does a thing");

        assertEquals("@PETROCORE-1\nFeature: x\n\n  Scenario: s\n    Given the register is idle\n"
                + "    When the cashier does a thing\n\n", out);
    }

    @Test
    void anExampleGoesUnderTheOnlyLineOfAnEmptyDraft() {
        assertEquals("\n    When it happens", AuthoringSteps.insertExample("", "When it happens"));
    }
}
