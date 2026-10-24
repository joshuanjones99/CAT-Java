package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.verifone.cat.pats.runner.ActionRegistry;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The family's registration: its five action names, and a wiring error when it is registered twice. */
class ValidationFamilyTest {

    private static ValidationContext freshContext() {
        return context(receiptSource(""), new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), new ValidationKit.FakeReferences(), noEnv());
    }

    @Test
    void theFamilyRegistersItsFiveActionsUnderTheirPatsNames() {
        ActionRegistry registry = new ActionRegistry();
        ValidationFamily.register(registry, freshContext());

        assertEquals(List.of("RESET_PACKETS", "VALIDATE_PACKETS", "VALIDATE_RECEIPT", "VALIDATE_REPORT",
                "VALIDATE_TRANSACTION"), List.copyOf(registry.supportedActions()));
    }

    @Test
    void registeringTheFamilyTwiceIsAWiringError() {
        ActionRegistry registry = new ActionRegistry();
        ValidationFamily.register(registry, freshContext());

        assertThrows(IllegalStateException.class, () -> ValidationFamily.register(registry, freshContext()));
    }
}
