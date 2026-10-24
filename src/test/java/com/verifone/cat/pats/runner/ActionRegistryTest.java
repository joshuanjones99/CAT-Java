package com.verifone.cat.pats.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The action-name registry, and the fixtures each case declares. */
class ActionRegistryTest {

    private static final ActionHandler NOOP = (run, action) -> { };

    @Test
    void aHandlerIsFoundByItsNameWhateverTheCase() {
        ActionRegistry registry = new ActionRegistry();
        registry.register("TOTAL", NOOP);

        assertTrue(registry.handlerFor("total").isPresent());
        assertTrue(registry.handlerFor("TOTAL").isPresent());
        assertFalse(registry.handlerFor("FROBNICATE").isPresent());
        assertEquals(Set.of("TOTAL"), registry.supportedActions());
    }

    @Test
    void aNameRegisteredTwiceIsAWiringErrorAndIsRefused() {
        ActionRegistry registry = new ActionRegistry();
        registry.register("TOTAL", NOOP);

        assertThrows(IllegalStateException.class, () -> registry.register("total", NOOP));
    }

    @Test
    void supportedActionsCannotBeChangedFromOutside() {
        ActionRegistry registry = new ActionRegistry();
        registry.register("TOTAL", NOOP);

        assertThrows(UnsupportedOperationException.class, () -> registry.supportedActions().add("X"));
    }

    @Test
    void theRegistryIsNotGlobalSoEachRunHoldsItsOwn() {
        ActionRegistry first = new ActionRegistry();
        first.register("TOTAL", NOOP);

        assertFalse(new ActionRegistry().handlerFor("TOTAL").isPresent());
    }

    @Test
    void aCaseDeclaresTheFixturesItsActionsUseInFixtureOrder() {
        TestCase kase = TestCase.of("PETROCORE-9",
                TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", "x"),
                TestAction.of(1, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "X", "DEVICE", "DCR1"),
                TestAction.of(2, "VALIDATE_TRANSACTION", "DEVICEID", "DCR3"),
                TestAction.of(3, "TOTAL", "DEVICE", "POS"));

        assertEquals(List.of(FixtureName.POS, FixtureName.LEGACY_RMI, FixtureName.DCR,
                FixtureName.COMMANDER_CONFIG), FixtureRequirements.fixturesFor(kase));
    }

    @Test
    void aCardOnThePosNeedsThePinpadNotTheDcr() {
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "X"));

        assertEquals(List.of(FixtureName.PINPAD), FixtureRequirements.fixturesFor(kase));
    }

    @Test
    void aDcrReceiptIsReadFromTheDcrOnlyOutsideAPosSale() {
        TestCase outside = TestCase.of("PETROCORE-9",
                TestAction.of(0, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "X", "DEVICE", "DCR2"));
        TestCase fuelled = TestCase.of("PETROCORE-9",
                TestAction.of(0, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "X", "DEVICE", "DCR2"),
                TestAction.of(1, "FUEL", "PUMP", "1"));

        assertTrue(FixtureRequirements.printsAtDcr(outside));
        assertFalse(FixtureRequirements.printsAtDcr(fuelled));
        assertTrue(FixtureRequirements.fixturesFor(outside).contains(FixtureName.DCR));
    }

    @Test
    void fixtureNamesAreTheTestsOwnNames() {
        assertEquals(FixtureName.LEGACY_RMI, FixtureName.ofKey("legacy_rmi").orElseThrow());
        assertFalse(FixtureName.ofKey("nosuch").isPresent());
    }
}
