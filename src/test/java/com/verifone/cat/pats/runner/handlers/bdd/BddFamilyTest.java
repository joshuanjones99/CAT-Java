package com.verifone.cat.pats.runner.handlers.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.HandlerHarness;
import com.verifone.cat.pats.runner.handlers.bdd.BddScreen.Member;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The BDD family's four actions, ported from the Python {@code tests/unit/test_pats_bdd.py}. A fake touch and a fake
 * screen stand in for the register: this is evidence about the lookup and the sequence, not about a rig.
 */
class BddFamilyTest {

    private static final String FUEL_1 = BddFamily.FUEL_BUTTON.at(1).value();
    private static final String FUEL_4 = BddFamily.FUEL_BUTTON.at(4).value();
    private static final String PREPAY_BUTTON = BddFamily.PREPAY_BUTTON;
    private static final String KEY_1 = BddFamily.PLU_KEY.at(1).value();
    private static final String KEY_9 = BddFamily.PLU_KEY.at(9).value();

    @TempDir
    Path folder;

    private final List<String> pressed = new ArrayList<>();
    private final FakeScreen screen = new FakeScreen();
    private Consumer<String> onPress = element -> { };
    private ActionRegistry registry;
    private CaseRun run;
    private BddContext context;

    @BeforeEach
    void setUp() {
        ActionHandler touch = (caseRun, action) -> {
            String element = action.get("WEB ELEMENT");
            pressed.add(element);
            onPress.accept(element);
        };
        context = new BddContext(touch, screen, Duration.ofMillis(50));
        registry = new ActionRegistry();
        BddFamily.register(registry, context);
    }

    private CaseRun caseRun(RunnableAction... actions) {
        run = HandlerHarness.caseRun(registry, folder, Set.of(), actions);
        return run;
    }

    private void perform(RunnableAction action) throws Exception {
        caseRun(action);
        registry.handlerFor(action.name()).orElseThrow().handle(run, action);
    }

    private static RunnableAction act(String name, String... fields) {
        return HandlerHarness.act(0, name, fields);
    }

    private static String presetId(int n) {
        return BddFamily.PRESET_FAMILY.at(n).value();
    }

    private static String gradeId(int n) {
        return BddFamily.GRADE_FAMILY.at(n).value();
    }

    private static String key(char c) {
        return BddFamily.PLU_KEY.at(c - '0').value();
    }

    private static String prepayKey(char c) {
        return BddFamily.PREPAY_KEY.at(c - '0').value();
    }

    // -- registration and the field table ---------------------------------------------------

    @Test
    void theFourActionsAreRegisteredAndNoMoreAndTheySecondRegistrationIsRefused() {
        assertEquals(Set.copyOf(BddFamily.ACTIONS), registry.supportedActions());
        assertThrows(IllegalStateException.class, () -> BddFamily.register(registry, context));
    }

    @Test
    void theFieldsReadForOurActionsEqualPATSsFieldsTable() {
        // PATS's fields.py (the Python's FIELDS_READ) for the four actions.
        assertEquals(List.of("PLUS"), Fields.FIELDS_READ.get("ADD_PLU_NUMERIC_KEYPAD"));
        assertEquals(List.of("PROMPTS"), Fields.FIELDS_READ.get("VALIDATE_DYNAMIC_PROMPTS"));
        assertEquals(List.of("PUMP NO", "AMOUNT"), Fields.FIELDS_READ.get("PREPAY_FUEL_AMOUNT"));
        assertEquals(List.of("PUMP NO", "GALLONS", "GRADE"), Fields.FIELDS_READ.get("PREPAY_FUEL_VOLUME"));
    }

    // -- prepay by amount -------------------------------------------------------------------

    @Test
    void aPresetIsPressedByTheIdOfTheButtonReadingItsAmount() throws Exception {
        screen.families.put(BddFamily.PRESET_FAMILY.name(), List.of(
                new Member(presetId(1), "Prepay", true), new Member(presetId(3), "$10", true)));

        perform(act("PREPAY_FUEL_AMOUNT", "PUMP NO", "1", "AMOUNT", "10.00"));

        assertEquals(List.of(FUEL_1, presetId(3)), pressed);
        assertTrue(run.scratch(com.verifone.cat.pats.runner.ScratchKey.PREPAY).orElse(false));
    }

    @Test
    void aHiddenPresetIsNotPressed() throws Exception {
        screen.families.put(BddFamily.PRESET_FAMILY.name(), List.of(new Member(presetId(3), "$10", false)));

        perform(act("PREPAY_FUEL_AMOUNT", "PUMP NO", "1", "AMOUNT", "10.00"));

        assertEquals(FUEL_1, pressed.get(0));
        assertEquals(PREPAY_BUTTON, pressed.get(1));
        assertFalse(pressed.contains(presetId(3)));
    }

    @Test
    void withNoSuchPresetTheAmountIsKeyed() throws Exception {
        screen.families.put(BddFamily.PRESET_FAMILY.name(), List.of(
                new Member(presetId(1), "Prepay", true), new Member(presetId(3), "$100", true)));

        perform(act("PREPAY_FUEL_AMOUNT", "PUMP NO", "4", "AMOUNT", "10.00"));

        assertEquals(List.of(FUEL_4, PREPAY_BUTTON), pressed.subList(0, 2));
        List<String> keys = new ArrayList<>();
        for (char c : "1000".toCharArray()) {
            keys.add(prepayKey(c));
        }
        keys.add(BddFamily.PREPAY_OK);
        assertEquals(keys, pressed.subList(2, pressed.size()));
        assertEquals(BddFamily.PREPAY_HEADER.element(), screen.expectations.get(0).split("=")[0]);
    }

    @Test
    void theAmountReadsAsDecimalFormatShowsIt() {
        assertEquals("10", BddFamily.wholeOrPlain("10.00"));
        assertEquals("10.5", BddFamily.wholeOrPlain("10.50"));
        assertEquals("100", BddFamily.wholeOrPlain("100"));
        assertEquals("0", BddFamily.wholeOrPlain("0.00"));
        PatsStepError error = assertThrows(PatsStepError.class, () -> BddFamily.wholeOrPlain("ten"));
        assertTrue(error.getMessage().contains("'ten'"), error.getMessage());
    }

    @Test
    void aMissingFieldFailsTheStepNamingIt() {
        PatsStepError error = assertThrows(PatsStepError.class,
                () -> perform(act("PREPAY_FUEL_AMOUNT", "PUMP NO", "1")));
        assertTrue(error.getMessage().contains("AMOUNT"), error.getMessage());
    }

    // -- prepay by volume -------------------------------------------------------------------

    @Test
    void aVolumePrepayPressesTheGradeRowByItsId() throws Exception {
        screen.families.put(BddFamily.GRADE_FAMILY.name(), List.of(
                new Member(gradeId(13631488), "PETR-UNLD1", true),
                new Member(gradeId(13631491), "PETROL-LD4", false),
                new Member(gradeId(13631492), "PETROL-LD4", true)));
        screen.texts.put(BddFamily.GRADE_HEADER.element(), BddFamily.GRADE_HEADER.text());

        perform(act("PREPAY_FUEL_VOLUME", "PUMP NO", "4", "GALLONS", "10.00", "GRADE", "PETROL-LD4"));

        // Tile, Prepay, the gallons without the point, then CANCEL (PATS's key for a volume), then the grade row.
        List<String> expected = new ArrayList<>(List.of(FUEL_4, PREPAY_BUTTON));
        for (char c : "1000".toCharArray()) {
            expected.add(prepayKey(c));
        }
        expected.add(BddFamily.PREPAY_CANCEL);
        expected.add(gradeId(13631492));
        assertEquals(expected, pressed);
    }

    @Test
    void aGradeTheMenuDoesNotListFailsTheStep() {
        screen.families.put(BddFamily.GRADE_FAMILY.name(), List.of(new Member(gradeId(13631488), "PETR-UNLD1", true)));
        screen.texts.put(BddFamily.GRADE_HEADER.element(), BddFamily.GRADE_HEADER.text());

        PatsStepError error = assertThrows(PatsStepError.class, () ->
                perform(act("PREPAY_FUEL_VOLUME", "PUMP NO", "4", "GALLONS", "10.00", "GRADE", "KERSN")));
        assertTrue(error.getMessage().contains("KERSN"), error.getMessage());
    }

    // -- PLUs on the keypad -----------------------------------------------------------------

    @Test
    void theKeypadOpensKeysEachPluAndCloses() throws Exception {
        perform(act("ADD_PLU_NUMERIC_KEYPAD", "PLUS", "9116, 9134"));

        List<String> expected = new ArrayList<>();
        expected.add(BddFamily.KEYPAD_ICON);
        for (String plu : List.of("9116", "9134")) {
            for (char c : plu.toCharArray()) {
                expected.add(key(c));
            }
            expected.add(BddFamily.PLU_OK);
        }
        expected.add(BddFamily.KEYPAD_CANCEL);
        assertEquals(expected, pressed);
        assertEquals(BddFamily.KEYPAD_HEADER.element(), screen.expectations.get(0).split("=")[0]);
    }

    @Test
    void anEmptyPluListFailsWithPATSsMessage() {
        PatsStepError error = assertThrows(PatsStepError.class,
                () -> perform(act("ADD_PLU_NUMERIC_KEYPAD", "PLUS", " , ")));
        assertEquals("plu list is empty", error.getMessage());
    }

    // -- host prompts -----------------------------------------------------------------------

    private static final String PROMPT_LABEL = Locators.INPUT_OVERLAY_VIEW_TITLE.value();
    private static final String UNIT_ROW = "{\"promptWebElement\":\"" + PROMPT_LABEL + "\",\"promptText\":\"Enter Unit number\","
            + "\"promptElementLocatorType\":\"ID\",\"touchActionLocatorType\":\"ID\","
            + "\"touchActionWebElements\":\"" + KEY_1 + "," + BddFamily.PLU_OK + "\"}";
    private static final String ODOMETER_ROW = "{\"promptWebElement\":\"" + PROMPT_LABEL + "\","
            + "\"promptText\":\"Enter Odometer reading\",\"promptElementLocatorType\":\"ID\","
            + "\"touchActionLocatorType\":\"ID\",\"touchActionWebElements\":\"" + KEY_9 + "\"}";

    /** A register whose prompt label reads the head of {@code prompts}; an answer (OK or KEY_9) takes the head off. */
    private RunnableAction dynamicPrompts(String... prompts) {
        Deque<String> queue = new ArrayDeque<>(List.of(prompts));
        screen.chosen = element -> queue.peek();
        onPress = element -> {
            if (element.equals(BddFamily.PLU_OK) || element.equals(KEY_9)) {
                queue.poll();
            }
        };
        return new Action("VALIDATE_DYNAMIC_PROMPTS",
                List.of(new RunnableAction.ActionField("PROMPTS", "[" + UNIT_ROW + "," + ODOMETER_ROW + "]")), 0);
    }

    @Test
    void dynamicPromptsAreAnsweredInTheOrderTheHostAsks() throws Exception {
        RunnableAction action = dynamicPrompts("Enter Odometer reading", "Enter Unit number");
        perform(action);
        assertEquals(List.of(KEY_9, KEY_1, BddFamily.PLU_OK), pressed);
    }

    @Test
    void aPromptTheHostNeverAsksFailsTheStep() {
        RunnableAction action = dynamicPrompts("Enter Unit number");
        PatsStepError error = assertThrows(PatsStepError.class, () -> perform(action));
        assertTrue(error.getMessage().startsWith("Number of prompt does not match"), error.getMessage());
        assertEquals(List.of(KEY_1, BddFamily.PLU_OK), pressed);
    }

    @Test
    void aHostThatAsksNothingFailsAfterTwoEmptyPassesWithoutAnyPress() {
        RunnableAction action = dynamicPrompts();
        PatsStepError error = assertThrows(PatsStepError.class, () -> perform(action));
        assertTrue(error.getMessage().startsWith("Number of prompt does not match"), error.getMessage());
        assertTrue(pressed.isEmpty());
    }

    @Test
    void promptRowsMustBeAList() {
        PatsStepError error = assertThrows(PatsStepError.class,
                () -> perform(act("VALIDATE_DYNAMIC_PROMPTS", "PROMPTS", "{}")));
        assertEquals("PROMPTS is not a list of prompt rows", error.getMessage());
    }

    // -- an unwired screen ------------------------------------------------------------------

    @Test
    void anUnwiredScreenFailsByNameRatherThanPassing() {
        context = BddContext.of((caseRun, action) -> pressed.add(action.get("WEB ELEMENT")), BddScreen.UNAVAILABLE);
        registry = new ActionRegistry();
        BddFamily.register(registry, context);
        assertThrows(DeviceUnavailable.class, () -> perform(
                act("PREPAY_FUEL_AMOUNT", "PUMP NO", "1", "AMOUNT", "10.00")));
    }

    /** A screen whose element texts and ID families are set by the test. */
    private static final class FakeScreen implements BddScreen {

        final Map<String, String> texts = new HashMap<>();
        final Map<String, List<Member>> families = new HashMap<>();
        final List<String> expectations = new ArrayList<>();
        Function<String, String> chosen = null;

        @Override
        public String chosenText(RunnableAction probe) {
            String element = probe.get("WEB ELEMENT");
            if (chosen != null) {
                return chosen.apply(element);
            }
            return texts.get(element);
        }

        @Override
        public List<Member> members(Locator family) {
            return families.getOrDefault(family.name(), List.of());
        }

        @Override
        public void expectText(CaseRun run, RunnableAction probe, String expected) {
            expectations.add(probe.get("WEB ELEMENT") + "=" + expected);
        }
    }
}
