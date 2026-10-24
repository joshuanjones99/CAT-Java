package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The step library: every phrase says what it does, its example binds to it alone, and the lookups read it. */
class StepLibraryTest {

    static List<StepDef> steps() {
        return StepLibrary.steps();
    }

    private static String lineOf(StepDef definition) {
        String example = definition.example();
        return example.contains(" ") ? example.substring(example.indexOf(' ') + 1) : example;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("steps")
    void everyExampleBindsToItsOwnPhraseAndNoOther(StepDef definition) {
        assertEquals(List.of(definition), StepLibrary.matches(lineOf(definition), StepLibrary.steps()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("steps")
    void everyPhraseSaysWhatItDoes(StepDef definition) {
        assertFalse(definition.doc().isBlank(), definition.text() + " has no docstring for the catalogue");
        assertTrue(StepDef.GROUPS.contains(definition.group()));
        for (String param : definition.params()) {
            assertFalse(definition.fixtures().contains(param));
        }
    }

    @Test
    void theLibraryCoversEveryActorAndHoldsBothHalves() {
        Set<String> groups = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (StepDef definition : StepLibrary.steps()) {
            groups.add(definition.group());
            texts.add(definition.text());
        }
        assertEquals(new HashSet<>(StepDef.GROUPS), groups);
        assertEquals(StepLibrary.steps().size(), texts.size(), "two phrases share a template");
        assertTrue(StepLibrary.steps().stream().anyMatch(d -> d.level().equals("ui")));
        assertTrue(StepLibrary.steps().stream().anyMatch(d -> d.level().equals("pats")));
    }

    @Test
    void moneyKeysTheDigitsAsWritten() {
        assertEquals(1, StepLibrary.matches("the customer pays 09.50 in cash", steps()).size());
        assertEquals(1, StepLibrary.matches("the customer pays $11.00 in cash", steps()).size());
        assertEquals(0, StepLibrary.matches("the customer pays 11 in cash", steps()).size());
    }

    @Test
    void mopKeyNamesMatchTheCreditKeyPhrase() {
        assertEquals(1, StepLibrary.matches("the cashier keys the credit key", steps()).size());
    }

    @Test
    void aPhraseWhoseExampleDoesNotMatchIsRefusedAtConstruction() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> StepDef.of(
                "the cashier does {n:Number} things", "POS screen", "action", "domain", "When x", "doc", List.of()));
        assertTrue(refused.getMessage().contains("does not match"));
    }

    @Test
    void anUnknownGroupIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StepDef.of(
                "the cashier waves", "Nowhere", "action", "domain", "When the cashier waves", "doc", List.of()));
    }

    @Test
    void theClosestPhrasesAreSuggestedFirst() {
        List<String> close = StepLibrary.closest("the cashier rings up the PLU 9999", steps(), 3);
        assertTrue(close.contains("the cashier rings up PLU {plu}"), close.toString());
    }

    @Test
    void difflibRatioMatchesThePythonOnKnownPairs() {
        assertEquals(1.0, Similarity.ratio("abcd", "abcd"));
        assertEquals(0.0, Similarity.ratio("abc", "xyz"));
        assertEquals(0.75, Similarity.ratio("abcd", "bcde"));
    }
}
