package com.verifone.cat.pats.feature;

import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.RunnableAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pos.Locators;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Port of the phrase and action half of {@code tests/unit/test_pats_feature.py}: every phrase's example reads, the
 * dynamic-prompts table round-trips through catalogue names, a scenario is all PATS phrases or none, the canonical form
 * ignores what no handler reads, and drift is named. Drives the real phrase table.
 */
class PhrasesTest {

    private static final Action TOUCH = new Action("TOUCH_EVENT", List.of(
            new RunnableAction.ActionField("WEB ELEMENT", Locators.ITEM_PANEL_N.at(145397).value()),
            new RunnableAction.ActionField("TYPE[ID/XPATH]", "ID")), 0);

    static List<Phrase> phrases() {
        return Phrases.ALL;
    }

    @ParameterizedTest
    @MethodSource("phrases")
    void everyPhrasesExampleBuildsAnActionNamedAsItsPhraseSays(Phrase phrase) {
        String line = phrase.example().substring(phrase.example().indexOf(' ') + 1);
        Action action = PatsFeature.buildLine(line);
        if (phrase.action() != null) {
            assertEquals(phrase.action(), action.name());
        }
        assertFalse(action.name().isEmpty(), line);
    }

    @Test
    void anActionWithNoPhraseIsRefusedNotWritten() {
        Action unknown = new Action("VALIDATION_BY_TEXT", List.of(
                new RunnableAction.ActionField("WEB ELEMENT", "no-such-catalogue-literal")), 0);
        assertThrows(UnreadableActionException.class, () -> PatsFeature.render(unknown));
    }

    @Test
    void aTouchRendersAsItsPhraseAndReadsBackTheSame() {
        String line = PatsFeature.render(TOUCH);
        assertEquals("the cashier touches ITEM_PANEL_N(145397)", line);
        assertEquals(Fields.canonical(TOUCH), Fields.canonical(PatsFeature.buildLine(line)));
    }

    @Test
    void anActionNoPhraseNamesFallsToTheCatchAllAndReadsBack() {
        Action report = new Action("NETWORK_REPORT", List.of(
                new RunnableAction.ActionField("MAIN_MENU_NO", "2"),
                new RunnableAction.ActionField("NETWORK_MENU_NO", "4"),
                new RunnableAction.ActionField("REPORT_NO", "1")), 0);
        String line = PatsFeature.render(report);
        assertTrue(line.startsWith("PATS runs NETWORK_REPORT with \""), line);
        assertEquals(Fields.canonical(report), Fields.canonical(PatsFeature.buildLine(line)));
    }

    @Test
    void theCatchAllRefusesAnElementItWouldWriteRaw() {
        Action raw = new Action("TOUCH_EVENT", List.of(
                new RunnableAction.ActionField("WEB ELEMENT", Locators.ITEM_PANEL_N.at(145397).value())), 0);
        assertThrows(UnreadableActionException.class, () -> Phrase.pack(raw));
    }

    @Test
    void theBddOnlyShapesAreWrittenByAPhraseOfTheirOwn() {
        String[] lines = {
            "pump \"2\" fuels \"10.00\" on hose \"1\", prepay \"false\"",
            "pump \"2\" hangs up, prepay \"true\"",
            "the cashier keys PLUs \"9116,9134\" on the numeric keypad",
            "the cashier prepays \"10.00\" on pump \"1\" on the keypad",
            "the cashier prepays \"2.000\" gallons of grade \"1\" on pump \"2\" on the keypad",
            "the card \"EMV_CT_Amex\" from image \"AEIPS 22.xml\" is presented at \"VIPERPAY\"",
        };
        for (String text : lines) {
            Action action = PatsFeature.buildLine(text, 3);
            String line = PatsFeature.render(action);
            assertFalse(line.startsWith("PATS runs"), line);
            assertEquals(Fields.canonical(action), Fields.canonical(PatsFeature.buildLine(line, action.index())));
        }
    }

    @Test
    void dynamicPromptsRoundTripThroughCatalogueNames() {
        String[] keys = {
            Locators.VFI_KEYPAD_WIDGET_ALPHABET_UPPER_CASE_KEYPAD_B.value(),
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at("1").value(),
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value(),
        };
        String field = PromptRows.json(List.of(java.util.Map.of(
                "promptWebElement", Locators.INPUT_OVERLAY_VIEW_LABEL1.value(),
                "promptText", "Enter Unit number",
                "promptElementLocatorType", "ID",
                "touchActionLocatorType", "ID",
                "touchActionWebElements", String.join(", ", keys))));
        Action action = new Action("VALIDATE_DYNAMIC_PROMPTS", List.of(new RunnableAction.ActionField("PROMPTS", field)), 0);
        String line = PatsFeature.render(action);
        assertTrue(line.contains("VFI_KEYPAD_WIDGET_ALPHABET_UPPER_CASE_KEYPAD_B"), line);
        for (String key : keys) {
            assertFalse(line.contains(key), "gate 5: no raw id in a step: " + line);
        }
        assertEquals(field, PatsFeature.buildLine(line).get("PROMPTS"));
    }

    @Test
    void aPromptsRowIsWrittenWithItsCatalogueNames() {
        String field = "[{\"promptWebElement\":\"" + Locators.INPUT_OVERLAY_VIEW_LABEL1.value()
                + "\",\"promptText\":\"Enter Odometer reading\",\"promptElementLocatorType\":\"ID\","
                + "\"touchActionLocatorType\":\"ID\",\"touchActionWebElements\":\""
                + Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value() + "\"}]";
        String text = PromptRows.toText(field);
        assertEquals("Enter Odometer reading @ INPUT_OVERLAY_VIEW_LABEL1 = "
                + "VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK", text);
    }

    @Test
    void aScenarioIsAllPatsPhrasesOrNone() {
        assertEquals(Optional.empty(), PatsFeature.scenarioActions(List.of("the cashier totals the sale"), false));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                PatsFeature.scenarioActions(List.of("the register keys TOTAL", "the cashier totals the sale"), false));
        assertTrue(refused.getMessage().contains("must all be PATS action phrases"), refused.getMessage());
    }

    @Test
    void aScenarioGetsIndexesFromItsPosition() {
        List<Action> actions = PatsFeature.scenarioActions(
                List.of("the register keys TOTAL", "PATS pauses 2000 ms"), false).orElseThrow();
        assertEquals(List.of(0, 1), actions.stream().map(Action::index).toList());
        assertEquals("TOTAL", actions.get(0).name());
        assertEquals("SLEEP", actions.get(1).name());
    }

    @Test
    void fromBddMarksEachActionOnceAndKeepsAMarkedOne() {
        List<Action> once = PatsFeature.fromBdd(List.of(TOUCH));
        assertEquals("BDD", once.get(0).raw("SOURCE"));
        assertEquals(once, PatsFeature.fromBdd(once));
        assertEquals(1, once.get(0).fields().stream().filter(f -> f.name().equals("SOURCE")).count());
    }

    @Test
    void canonicalIgnoresWhatNoHandlerReadsAndKeepsAbsentApartFromEmpty() {
        Action touch = new Action("TOUCH_EVENT", List.of(
                new RunnableAction.ActionField("WEB ELEMENT", "X"),
                new RunnableAction.ActionField("PLU", "8002"),
                new RunnableAction.ActionField("DEVICE", "POS")), 0);
        Action bare = new Action("TOUCH_EVENT", List.of(new RunnableAction.ActionField("WEB ELEMENT", "X")), 0);
        assertEquals(Fields.canonical(bare), Fields.canonical(touch));

        assertFalse(Fields.canonical(new Action("PRESS_ENTER", List.of(), 0))
                .equals(Fields.canonical(new Action("PRESS_ENTER", List.of(new RunnableAction.ActionField("DATA", "")), 0))));

        Action dcr = new Action("VALIDATE_TRANSACTION", List.of(new RunnableAction.ActionField("DEVICEID", "DCR3")), 0);
        Action pos = new Action("VALIDATE_TRANSACTION", List.of(new RunnableAction.ActionField("DEVICEID", "topaz101")), 0);
        assertFalse(Fields.canonical(dcr).equals(Fields.canonical(pos)));
    }

    @Test
    void driftNamesTheFirstActionThatDiffers() {
        Action same = new Action("SLEEP", List.of(new RunnableAction.ActionField("SLEEP", "2000")), 0);
        Action slower = new Action("SLEEP", List.of(new RunnableAction.ActionField("SLEEP", "3000")), 1);
        assertEquals(Optional.empty(), PatsFeature.drift("K", List.of(same), List.of(same)));
        String message = PatsFeature.drift("K", List.of(same, same), List.of(same, slower)).orElseThrow();
        assertTrue(message.contains("differs from this .feature at action 2"), message);
        String longer = PatsFeature.drift("K", List.of(same, same), List.of(same)).orElseThrow();
        assertTrue(longer.contains("has 2 actions, this .feature 1"), longer);
    }

    @Test
    void aLineThatIsNotExactlyOnePhraseIsRefused() {
        assertThrows(PhraseLookupException.class, () -> PatsFeature.buildLine("the cashier totals the sale"));
    }

    @Test
    void aSleepIsWrittenAsPatsPauses() {
        Action sleep = new Action("SLEEP", List.of(new RunnableAction.ActionField("SLEEP", "2000")), 0);
        assertEquals("PATS pauses 2000 ms", PatsFeature.render(sleep));
        assertFalse(PatsFeature.isPatsLine("the cashier totals the sale"));
    }
}
