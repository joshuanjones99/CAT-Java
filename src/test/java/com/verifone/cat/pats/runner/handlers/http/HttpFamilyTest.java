package com.verifone.cat.pats.runner.handlers.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleContext;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleFamily;
import com.verifone.cat.pats.runner.handlers.http.Harness.Files;

/** The families' registration, and the field table the handlers' reads are checked against. */
class HttpFamilyTest {

    @Test
    void theFamilyRegistersBothActionsAndNoOther() {
        ActionRegistry registry = new ActionRegistry();
        HttpFamily.register(registry, new HttpContext(new Files(), null, CloudSettle.disabled()));

        assertEquals(List.of("SEND_HTTP_REQUEST", "VALIDATE_PAYLOAD"), List.copyOf(registry.supportedActions()));
    }

    @Test
    void registeringTheFamilyTwiceIsAWiringError() {
        ActionRegistry registry = new ActionRegistry();
        HttpContext context = new HttpContext(new Files(), null, CloudSettle.disabled());
        HttpFamily.register(registry, context);

        assertThrows(IllegalStateException.class, () -> HttpFamily.register(registry, context));
    }

    @Test
    void theCloudSettleFamilyRegistersNoActionBecausePatsHasNone() {
        ActionRegistry registry = new ActionRegistry();
        CloudSettleFamily.register(registry, CloudSettleContext.of(() -> null));

        assertTrue(registry.supportedActions().isEmpty());
    }

    @Test
    void theFieldTableCoversEveryFieldTheHandlersRead() {
        // DEVICE is read for every action by Fields itself, so it is not listed per action.
        List<String> sendReads = List.of("COMMAND", "PAYLOAD", "METHOD", "USERNAME", "PASSWORD",
                "CAPTUREFIELDS", "EXPECTSTATUS", "POLLUNTILFIELD", "POLLUNTILVALUE", "POLLMAXSECONDS",
                "POLLINTERVALSECONDS", "RETRYENABLED", "ROLE", "HEADERS", "PARAMS", "UPLOADFILE", "FORMFIELDS",
                "DATEFORMAT");
        List<String> validateReads = List.of("SELECT_REFERENCE_FILE", "SELECT_RULE_FILE", "VALIDATESCHEMA");
        Map<String, List<String>> table = Fields.FIELDS_READ;

        assertTrue(table.get("SEND_HTTP_REQUEST").containsAll(sendReads), table.get("SEND_HTTP_REQUEST").toString());
        assertTrue(table.get("VALIDATE_PAYLOAD").containsAll(validateReads));
    }

    @Test
    void theScenarioContextKeyHoldsAMap() {
        assertEquals(Map.class, com.verifone.cat.pats.runner.ScratchKey.SCENARIO_CONTEXT.type());
    }
}
