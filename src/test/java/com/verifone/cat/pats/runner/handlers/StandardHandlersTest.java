package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pos.pinpad.PinpadDouble;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run's one handler set: every PATS action name the Python's {@code registry._HANDLERS} runs has exactly one handler,
 * and the handler set builds with every family wired. The names are the Python's {@code SUPPORTED_ACTIONS} (71), read
 * off {@code petrotest/pats/runner/registry.py}.
 */
class StandardHandlersTest {

    /** The Python's {@code SUPPORTED_ACTIONS}, upper-cased. */
    static final Set<String> PATS_ACTIONS = Set.of(
            "SLEEP", "TOUCH_EVENT", "WIDGET_VALIDATION", "VALIDATION_BY_TEXT", "IS_ELEMENT_PRESENT",
            "IS_ELEMENT_CLICKABLE", "APP_SELECTOR", "CASHIER_LOGIN", "VALIDATE_OVERLAY", "SCAN_ITEM", "PRESS_ENTER",
            "MOP", "OTHER_MOP", "TOTAL", "ADD_ITEM", "QTY", "PREPAY", "FUEL", "APPROVE", "LOGIN", "LOGOUT",
            "ENTER_DRAWER_AMNT", "SELECT_TILL", "REFUND", "STORE_TRANS_MAP", "REFUND_WITH_TRANS_MAP", "REPORT_PRINT",
            "NETWORK_REPORT", "VALIDATE_RECEIPT", "VALIDATE_TRANSACTION", "VALIDATE_REPORT", "RESET_PACKETS",
            "VALIDATE_PACKETS", "UPDATE_CONFIGURATION", "IMPORT_CONFIGURATION", "CONTROL_FUNCTION",
            "INSERT_OR_SLIDE_CARD", "REMOVE_CARD", "VOID_TICKET", "VOID_LINE", "ERROR_CORRECT", "SUSP", "CONFIRMATION",
            "POS_KEYBOARD_EVENT", "ADD_DEPT_ITEM", "PRINT_FLASH_REPORT", "CLOSE_REPORT", "CLOUD_HTTP_REQUEST", "R1",
            "R2", "R3", "R4", "ADD_PLU_NUMERIC_KEYPAD", "VALIDATE_DYNAMIC_PROMPTS", "PREPAY_FUEL_AMOUNT",
            "PREPAY_FUEL_VOLUME", "SEND_HTTP_REQUEST", "VALIDATE_PAYLOAD", "WAIT_FOR_PROMPT", "GET_ANY_KEY",
            "GET_DECIMAL", "WAIT_DCR_PROMPT", "KEY_PRESS", "L1", "L2", "L3", "L4", "FUEL_AMOUNT", "HANDLE_DOWN",
            "HANDLE_UP", "HTTP_SIM_EVENT");

    @TempDir
    Path root;

    private ActionRegistry registry() throws Exception {
        Files.createDirectories(root.resolve("petroapps"));
        return StandardHandlers.create(WiringFixtures.unbrowsed(root, new PinpadDouble("1", java.time.Duration.ofMillis(10))));
    }

    @Test
    void everyPatsActionHasExactlyOneHandler() throws Exception {
        // ActionRegistry refuses a second handler for a name, so the set equal to PATS_ACTIONS is the exact-one proof.
        assertEquals(PATS_ACTIONS, registry().supportedActions());
    }

    @Test
    void theRegistryHoldsNoNameItDoesNotNeed() throws Exception {
        assertTrue(PATS_ACTIONS.containsAll(registry().supportedActions()));
    }

    @Test
    void everyFieldsTableActionIsPatsAction() {
        assertEquals(PATS_ACTIONS, Fields.FIELDS_READ.keySet());
    }

    @Test
    void eachFamilysHandlersAreRegisteredUnderItsOwnName() throws Exception {
        ActionRegistry registry = registry();
        for (List<String> family : List.of(
                List.of("SLEEP", "TOUCH_EVENT", "APP_SELECTOR", "LOGIN", "SELECT_TILL"),
                List.of("SCAN_ITEM", "PRESS_ENTER", "VOID_TICKET", "ADD_DEPT_ITEM"),
                List.of("INSERT_OR_SLIDE_CARD", "WAIT_FOR_PROMPT", "R1", "L4", "HANDLE_UP"),
                List.of("SEND_HTTP_REQUEST", "VALIDATE_PAYLOAD"),
                List.of("REPORT_PRINT", "CLOUD_HTTP_REQUEST", "IMPORT_CONFIGURATION", "HTTP_SIM_EVENT"),
                List.of("CONTROL_FUNCTION"),
                List.of("VALIDATE_RECEIPT", "VALIDATE_PACKETS"),
                List.of("ADD_PLU_NUMERIC_KEYPAD", "VALIDATE_DYNAMIC_PROMPTS", "PREPAY_FUEL_AMOUNT", "PREPAY_FUEL_VOLUME"))) {
            for (String action : family) {
                assertTrue(registry.handlerFor(action).isPresent(), action);
            }
        }
        assertFalse(registry.handlerFor("NO_SUCH_ACTION").isPresent());
    }
}
