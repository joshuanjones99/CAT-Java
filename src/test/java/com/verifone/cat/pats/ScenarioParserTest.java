package com.verifone.cat.pats;

import com.verifone.cat.pats.runner.RunnableAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.pos.Locators;

/**
 * {@link ScenarioParser}: PATS's BDD steps read into the XML case's {@link Action}s (the Python's
 * {@code test_pats_scenario}). Step texts that carry element IDs live in {@code src/test/resources/pats/scenarios/}.
 */
class ScenarioParserTest {

    private static String scenario(String name) {
        try (InputStream in = ScenarioParserTest.class.getResourceAsStream("/pats/scenarios/" + name)) {
            assertTrue(in != null, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Action only(ParsedCase parsed) {
        assertEquals(1, parsed.steps().size());
        return parsed.steps().get(0);
    }

    @Test
    void aCaseReadsIntoTheXmlFieldNames() {
        ParsedCase parsed = ScenarioParser.parseScenario(scenario("bdd-case.scenario"));
        assertTrue(parsed.title().startsWith("PETROCORE-120859 Indoor postpay"));
        assertEquals(List.of("WIDGET_VALIDATION", "FUEL_AMOUNT", "HANDLE_DOWN", "ADD_PLU_NUMERIC_KEYPAD",
                "INSERT_OR_SLIDE_CARD", "GET_DECIMAL", "REFUND_WITH_TRANS_MAP", "SLEEP", "VALIDATE_RECEIPT"),
                parsed.steps().stream().map(Action::name).toList());

        List<Action> actions = parsed.steps();
        Action widget = actions.get(0);
        Action fuel = actions.get(1);
        Action down = actions.get(2);
        Action plus = actions.get(3);
        Action card = actions.get(4);
        Action pin = actions.get(5);
        Action refund = actions.get(6);
        Action sleep = actions.get(7);
        Action receipt = actions.get(8);

        assertEquals("csr-button", widget.get("WEB ELEMENT"));
        assertEquals("CSR_FCT", widget.get("VALIDATION KEY"));
        assertEquals("10.00", fuel.get("AMOUNT"));
        assertEquals("2", fuel.get("PUMP NO"));
        assertEquals("false", fuel.get("PREPAY"));
        assertEquals("true", down.get("PREPAY"));
        assertEquals("9112,9113", plus.get("PLUS"));
        assertEquals("EMV_CT_Visa", card.get("CARD_NAME"));
        assertEquals("VISA_CI_2013-v1_2.xml", card.get("CARD_SIMULATION_IMAGE_FILE"));
        assertEquals("VIPERPAY", card.get("DEVICE"));
        assertEquals("0042", pin.get("ENTER_DECIMAL"), "a PIN keeps its leading zeros");
        assertEquals("SALE1", refund.get("REFUND_KEY"));
        assertEquals("5000", sleep.get("SLEEP"));
        assertEquals("PETROCORE-120859-Receipt.txt", receipt.get("SELECT_FILE"));
        assertEquals("RECEIPT-RULE.txt", receipt.get("SELECT_RULE_FILE"));
        assertTrue(actions.stream().allMatch(a -> a.get("SOURCE").equals("BDD")));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), actions.stream().map(Action::index).toList());
    }

    @Test
    void anUnknownStepIsAnErrorNamingTheLine() {
        UnknownStepException refused = assertThrows(UnknownStepException.class,
                () -> ScenarioParser.parseScenario("Scenario: x\nWhen FLY_TO pump 9\n"));
        assertEquals("line 2: no PATS step matches 'FLY_TO pump 9'", refused.getMessage());
    }

    @Test
    void theViperpayAndSiteStepsReadIntoTheXmlFieldNames() {
        ParsedCase parsed = ScenarioParser.parseScenario(scenario("simulator-case.scenario"));
        assertEquals(List.of("RESET_PACKETS", "UPDATE_CONFIGURATION", "WAIT_FOR_PROMPT", "INSERT_OR_SLIDE_CARD",
                "GET_ANY_KEY", "GET_DECIMAL", "IMPORT_CONFIGURATION", "CONTROL_FUNCTION", "REPORT_PRINT",
                "VALIDATE_REPORT"), parsed.steps().stream().map(Action::name).toList());
        Map<String, Action> byName = new HashMap<>();
        parsed.steps().forEach(a -> byName.putIfAbsent(a.name(), a));

        assertEquals("BUYPASS", byName.get("RESET_PACKETS").get("DEVICE"));
        Action update = byName.get("UPDATE_CONFIGURATION");
        assertEquals("BUYPASS", update.get("DEVICE"));
        assertEquals("PartialAuth.txt", update.get("SELECT_FILE"));
        Action prompt = byName.get("WAIT_FOR_PROMPT");
        assertEquals("DO YOU WANT, CASHBACK ", prompt.get("DISPLAY_KEY"), "PATS's substring, as written");
        assertEquals("No", prompt.get("DISPLAY_KEY_EXACT_MATCH[YES/NO]"));
        assertEquals("MSR DEBIT", byName.get("INSERT_OR_SLIDE_CARD").get("CARD_NAME"));
        assertNull(byName.get("INSERT_OR_SLIDE_CARD").raw("CARD_SIMULATION_IMAGE_FILE"));
        assertEquals("10.00% - $3.40", byName.get("GET_ANY_KEY").get("ENTER_KEY"));
        assertEquals("0042", byName.get("GET_DECIMAL").get("ENTER_DECIMAL"));
        for (String pinpad : List.of("WAIT_FOR_PROMPT", "INSERT_OR_SLIDE_CARD", "GET_ANY_KEY", "GET_DECIMAL")) {
            assertEquals("VIPERPAY", byName.get(pinpad).get("DEVICE"));
        }
        assertEquals("Upsell-Naxml", byName.get("IMPORT_CONFIGURATION").get("SELECT_FOLDER"));
        assertEquals("crefreshcfg", byName.get("CONTROL_FUNCTION").get("INIT_TYPE"));
        assertEquals("SITECONTROLLER", byName.get("CONTROL_FUNCTION").get("DEVICE"));
        assertEquals("SITECONTROLLER", byName.get("IMPORT_CONFIGURATION").get("DEVICE"));

        Action report = byName.get("VALIDATE_REPORT");
        assertEquals("PETROCORE-115560-Report.xml", report.get("SELECT_FILE"));
        assertEquals("REPORT-RULE.txt", report.get("SELECT_RULE_FILE"));
        // The table is ReportValidator's map: an empty cell stays empty, an absent key absent.
        assertEquals("vrubyrept", report.get("CMD"));
        assertEquals("2", report.get("PERIOD"));
        assertEquals("2", report.get("REPTNUM"));
        assertEquals("", report.raw("REPORTTYPE"));
        assertNull(report.raw("FILENAME"));
    }

    @Test
    void printFlashReportKeysItsArgumentsInTheOrderWritten() {
        Action action = only(ScenarioParser.parseScenario("Scenario: x\nWhen PRINT_FLASH_REPORT with functions_menu_no "
                + "\"1\" main_menu_no \"20\" flash_report_menu_no \"3\" on register \"topaz101\"\n"));
        assertEquals("PRINT_FLASH_REPORT", action.name());
        assertEquals(List.of("1", "20", "3"),
                List.of(action.get("MAIN MENU NO"), action.get("FUNCTIONS MENU NO"), action.get("FLASH REPORTS MENU NO")));
    }

    @Test
    void thePosAndCloudStepsReadIntoTheXmlFieldNames() {
        String sco = Locators.APP_SELECTION_PROMPT_VIEW_APP_PANEL_SCO.value();
        ParsedCase parsed = ScenarioParser.parseScenario(scenario("pos-and-cloud.scenario").replace("@SCO@", sco));
        assertEquals(3, parsed.steps().size());
        Action login = parsed.steps().get(0);
        Action selector = parsed.steps().get(1);
        Action cloud = parsed.steps().get(2);

        assertEquals("CASHIER_LOGIN", login.name());
        assertEquals("POS", login.get("DEVICE"));
        assertEquals(sco, selector.get("WEB ELEMENT"));
        assertEquals("ID", selector.get("TYPE[ID/XPATH]"));
        assertEquals("PUT", cloud.get("HTTP_METHOD"));
        assertEquals("pcms/v1/config/{service_id}/tips/1", cloud.get("CONFIGURATION_URL"));
        assertEquals("TippingConfigurationPayload.json", cloud.get("SELECT_PAYLOAD_FILE"));
        assertEquals("C-SITE", cloud.get("DEVICE"));
    }

    @Test
    void sendHttpRequestAndValidatePayloadKeepTheirTablesKeyForKey() {
        String text = "Scenario: x\n"
                + "  When SEND_HTTP_REQUEST using command \"v1/sites/{service_id}/ctrlcmd/cfuelinit\" "
                + "with payload \"PETROCORE-136964-cfuelinit-default-payload.json\"\n"
                + "    |device|CSITE|\n"
                + "    |method|POST|\n"
                + "\t|captureFields|$.transactionId->transactionId|\n"
                + "   Then VALIDATE_PAYLOAD with reference file \"PETROCORE-136964-refPayload.json\" "
                + "and rule file \"ctrlcmd-rule.txt\"\n"
                + "     |validateSchema|false|\n";
        List<Action> actions = ScenarioParser.parseScenario(text).steps();
        assertEquals(2, actions.size());
        Action send = actions.get(0);
        Action check = actions.get(1);

        assertEquals("SEND_HTTP_REQUEST", send.name());
        assertEquals("v1/sites/{service_id}/ctrlcmd/cfuelinit", send.get("COMMAND"));
        assertEquals("PETROCORE-136964-cfuelinit-default-payload.json", send.get("PAYLOAD"));
        assertEquals("CSITE", send.get("DEVICE"));
        assertEquals("POST", send.get("METHOD"));
        assertEquals("$.transactionId->transactionId", send.get("CAPTUREFIELDS"));
        assertEquals("PETROCORE-136964-refPayload.json", check.get("SELECT_REFERENCE_FILE"));
        assertEquals("ctrlcmd-rule.txt", check.get("SELECT_RULE_FILE"));
        assertEquals("false", check.get("VALIDATESCHEMA"));
    }

    @Test
    void aTableUnderAStepThatTakesNoneIsAnError() {
        UnknownStepException refused = assertThrows(UnknownStepException.class, () -> ScenarioParser.parseScenario(
                "Scenario: x\nWhen RESET_PACKETS for device \"BUYPASS\"\n| a | b |\n"));
        assertTrue(refused.getMessage().contains("a table row with no step that takes one"));
    }

    @Test
    void aSleepWithoutItsUnitIsNoPatsStep() {
        UnknownStepException refused = assertThrows(UnknownStepException.class,
                () -> ScenarioParser.parseScenario("Scenario: x\nAnd SLEEP for 2000 \n"));
        assertTrue(refused.getMessage().contains("SLEEP for 2000"));
    }

    @Test
    void aTagLineIsNotAStep() {
        ParsedCase parsed = ScenarioParser.parseScenario(
                "  Scenario: S\n    When SLEEP for 20000 milliseconds\n\n@test\n");
        assertEquals("S", parsed.title());
        assertEquals(List.of("SLEEP"), parsed.steps().stream().map(Action::name).toList());
    }

    @Test
    void aConfigImportReadsAsTheXmlCasesSiteControllerActions() {
        ParsedCase parsed = ScenarioParser.parseScenario(
                "Scenario: PETROCORE-126468 - Import-base-vipercinfig-Pinpad-device\n"
                        + "When IMPORT_CONFIGURATION folder \"buypass-viper-pinpad-devices\"\n"
                        + "And CONTROL_FUNCTION init type \"crefreshcfg\"\n");
        assertEquals(List.of(
                new Action("IMPORT_CONFIGURATION", List.of(
                        new RunnableAction.ActionField("SELECT_FOLDER", "buypass-viper-pinpad-devices"),
                        new RunnableAction.ActionField("DEVICE", "SITECONTROLLER"),
                        new RunnableAction.ActionField("SOURCE", "BDD")), 0),
                new Action("CONTROL_FUNCTION", List.of(
                        new RunnableAction.ActionField("INIT_TYPE", "crefreshcfg"),
                        new RunnableAction.ActionField("DEVICE", "SITECONTROLLER"),
                        new RunnableAction.ActionField("SOURCE", "BDD")), 1)), parsed.steps());
    }

    @Test
    void dynamicPromptsAndTheDcrStepsReadIntoRunnerActions() {
        String label = Locators.INPUT_OVERLAY_VIEW_LABEL1.value();
        String one = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at("1").value();
        String ok = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value();
        String text = scenario("dynamic-prompts.scenario")
                .replace("@LABEL@", label).replace("@ONE@", one).replace("@OK@", ok);
        List<Action> actions = ScenarioParser.parseScenario(text).steps();

        assertEquals(List.of("VALIDATE_DYNAMIC_PROMPTS", "WAIT_DCR_PROMPT", "L4", "KEY_PRESS"),
                actions.stream().map(Action::name).toList());
        assertEquals("[{\"promptWebElement\":\"" + label + "\",\"promptText\":\"Enter Unit number\","
                + "\"promptElementLocatorType\":\"ID\",\"touchActionLocatorType\":\"ID\","
                + "\"touchActionWebElements\":\"" + one + "," + ok + "\"}]", actions.get(0).get("PROMPTS"));
        assertEquals("WELCOME", actions.get(1).get("DISPLAY_KEY"));
        assertEquals("DCR1", actions.get(1).get("DEVICE"));
        assertEquals("DCR1", actions.get(2).get("DEVICE"));
        assertEquals("712345", actions.get(3).get("KEY_PRESS"));
        assertEquals("DCR1", actions.get(3).get("DEVICE"));
    }

    @Test
    void aCardAtTheDcrReadsAsTheXmlCasesDcrCard() {
        Action card = only(ScenarioParser.parseScenario("Scenario: PETROCORE-1 dcr\n"
                + "When INSERT_OR_SLIDE_CARD of card type \"CREDIT\" with card name \"MannatecFleet2\" "
                + "at dcr position \"DCR1\"\n"));
        assertEquals("INSERT_OR_SLIDE_CARD", card.name());
        assertEquals("MannatecFleet2", card.get("CARD_NAME"));
        assertEquals("DCR1", card.get("DEVICE"));
    }

    @Test
    void aScenarioGluedToAStepsEndIsSplitBackApart() {
        // PETROCORE-124883: a step and the next Scenario: title share one physical line.
        ParsedCase parsed = ScenarioParser.parseScenario("Scenario: A\nWhen SLEEP for 5 milliseconds"
                + "Scenario: B\nThen SLEEP for 6 milliseconds\n");
        assertEquals("A", parsed.title());
        assertEquals(List.of("5", "6"), parsed.steps().stream().map(a -> a.get("SLEEP")).toList());
    }

    @Test
    void theDynamicPromptsJsonIsCompactAndAsciiOnlyAsPythonWritesIt() {
        String json = ScenarioParser.dynamicPromptsField(List.of(Map.of(
                "promptWebElement", " elé ",
                "promptText", "say \"hi\"",
                "promptElementLocatorType", "ID",
                "touchActionLocatorType", "ID",
                "touchActionWebElements", " A ,, B ")));
        assertEquals("[{\"promptWebElement\":\"el\\u00e9\",\"promptText\":\"say \\\"hi\\\"\","
                + "\"promptElementLocatorType\":\"ID\",\"touchActionLocatorType\":\"ID\","
                + "\"touchActionWebElements\":\"A,B\"}]", json);
    }

    @Test
    void aDynamicPromptRowMissingAColumnIsRefused() {
        UnknownStepException refused = assertThrows(UnknownStepException.class,
                () -> ScenarioParser.dynamicPromptsField(List.of(Map.of("promptText", "x"))));
        assertTrue(refused.getMessage().contains("promptWebElement"));
    }
}
