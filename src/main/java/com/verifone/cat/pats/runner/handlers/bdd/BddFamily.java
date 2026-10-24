package com.verifone.cat.pats.runner.handlers.bdd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.devices.ActionFields;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.validate.PyRepr;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The four actions only PATS's BDD steps have: PLUs on the keypad, the host's prompts answered in any order, and prepay
 * by amount and by volume. Ported from the Python {@code petrotest.pats.runner.bdd}, which is the specification.
 *
 * <p>Each is a fixed sequence of presses on IDs from PATS's {@code automn_app_config.prop}. Every press goes through
 * the case's own {@code TOUCH_EVENT} ({@link BddContext#press}); every text check through the screen seam. PATS's
 * {@code isPromptPresent} (a 6 s substring look that returns rather than throws) is {@code shows} here, with the same
 * ceiling.
 *
 * <p>Two things are the case's own, not this family's: the prepay preset and the grade row are found by reading their
 * text among the ID families, since PATS's XPath for them is not in the catalogue (run 5bfd41d438b5); and the
 * {@code widget_keys} texts come from {@link com.verifone.cat.pats.WidgetKeys}, not from here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE until a rig run of PETROCORE-122795 is recorded in HANDOFF.md.
 */
public final class BddFamily {

    /** The action names this family registers, as PATS names them (upper-case). */
    public static final List<String> ACTIONS = List.of(
            "ADD_PLU_NUMERIC_KEYPAD", "VALIDATE_DYNAMIC_PROMPTS", "PREPAY_FUEL_AMOUNT", "PREPAY_FUEL_VOLUME");

    /**
     * The POS elements, by their catalogue names (`automn_app_config.prop`, petroapps, read 2026-10-06). The keypad
     * keys, the fuel tiles and the prepay buttons are the catalogue's {@code _N} families, read at an index.
     */
    /**
     * PATS finds a {@code $n} preset and a grade row by XPath, which the catalogue cannot hold: the value is the case's.
     * Here the buttons are read through their ID families and the one whose text matches is pressed by ID.
     */
    static final Locator PRESET_FAMILY = Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_BUTTONS_PANEL_PREPAY_BUTTONS_N;
    static final String KEYPAD_ICON = Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON.value();
    static final Expect KEYPAD_HEADER =
            new Expect(Locators.VIRTUAL_KEYBOARD_VIEW_NUMERIC_KEY_PAD_HEADER.value(), "Numeric Keypad");
    static final Locator PLU_KEY = Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N;
    static final String PLU_OK = Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value();
    static final String KEYPAD_CANCEL = Locators.VIRTUAL_KEYBOARD_VIEW_CANCEL_PNG.value();
    static final Expect PLU_NOT_FOUND = new Expect(Locators.INPUT_OVERLAY_VIEW_TITLE.value(), "PLU NOT FOUND");
    static final Locator FUEL_BUTTON = Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA;
    static final String PREPAY_BUTTON = PRESET_FAMILY.at(1).value();
    static final Expect PREPAY_HEADER = new Expect(Locators.INPUT_OVERLAY_VIEW_TITLE.value(), "Prepay Fuel");
    static final Locator PREPAY_KEY = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N;
    static final String PREPAY_OK = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value();
    static final String PREPAY_CANCEL = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL.value();
    static final Locator GRADE_FAMILY = Locators.REVIEW_OVERLAY_VIEW_ITEM_N;
    static final Expect GRADE_HEADER = new Expect(Locators.REVIEW_OVERLAY_VIEW_HEADER_TEXT.value(), "Select Fuel Product");

    private static final ObjectMapper JSON = new ObjectMapper();

    private BddFamily() {
    }

    /** A widget's element and the text it must show (the Python's {@code (element, text)} pairs). */
    record Expect(String element, String text) {
    }

    /**
     * Registers the four BDD handlers.
     *
     * @throws IllegalStateException when one of the names already has a handler
     */
    public static void register(ActionRegistry registry, BddContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        registry.register("ADD_PLU_NUMERIC_KEYPAD", (run, action) -> addPluNumericKeypad(run, action, context));
        registry.register("VALIDATE_DYNAMIC_PROMPTS", (run, action) -> validateDynamicPrompts(run, action, context));
        registry.register("PREPAY_FUEL_AMOUNT", (run, action) -> prepayFuelAmount(run, action, context));
        registry.register("PREPAY_FUEL_VOLUME", (run, action) -> prepayFuelVolume(run, action, context));
    }

    // -- PLUs on the keypad -----------------------------------------------------------------

    /** {@code addPluWithNumericKeypad}: open the keypad if needed, key each PLU and OK, close it. */
    static void addPluNumericKeypad(CaseRun run, RunnableAction action, BddContext ctx) throws Exception {
        List<String> plus = new ArrayList<>();
        for (String part : ActionFields.require(action, "PLUS").split(",", -1)) {
            String plu = part.strip();
            if (!plu.isEmpty()) {
                plus.add(plu);
            }
        }
        if (plus.isEmpty()) {
            throw new PatsStepError("plu list is empty"); // PATS's own message
        }
        if (!shows(run, ctx, KEYPAD_HEADER.element(), KEYPAD_HEADER.text(), "ID")) {
            press(run, ctx, KEYPAD_ICON, "ID");
            expect(run, ctx, KEYPAD_HEADER);
        }
        for (String plu : plus) {
            keyIn(run, ctx, plu, PLU_KEY, PLU_OK);
        }
        // PATS leaves "PLU NOT FOUND" up for the case to see; otherwise it closes the keypad.
        if (!shows(run, ctx, PLU_NOT_FOUND.element(), PLU_NOT_FOUND.text(), "ID")) {
            press(run, ctx, KEYPAD_CANCEL, "ID");
        }
    }

    // -- host prompts -----------------------------------------------------------------------

    /**
     * {@code validateDynamicPrompts}: answer the host's prompts in whatever order they come.
     *
     * <p>PATS makes one pass per row; each pass looks for every row not yet answered, in table order, and keys the row's
     * touch list when its text is in its element. A row's text answers once. After the passes, a row never seen fails
     * the step. Two passes in a row that find no prompt end it early (PETROCORE-127694: 10 rows, 617 s to the same
     * failure). PATS's 1 s pause between passes is left out; the next pass's own looks wait on the screen.
     */
    static void validateDynamicPrompts(CaseRun run, RunnableAction action, BddContext ctx) throws Exception {
        List<JsonNode> rows = promptRows(ActionFields.require(action, "PROMPTS"));
        Set<String> done = new TreeSet<>();
        int idle = 0;
        for (int pass = 0; pass < rows.size(); pass++) {
            int before = done.size();
            for (JsonNode row : rows) {
                String text = field(row, "promptText");
                if (done.contains(text) || !shows(run, ctx, field(row, "promptWebElement"), text,
                        field(row, "promptElementLocatorType").toUpperCase(Locale.ROOT))) {
                    continue;
                }
                for (String element : touchElements(field(row, "touchActionWebElements"))) {
                    press(run, ctx, element, field(row, "touchActionLocatorType").toUpperCase(Locale.ROOT));
                }
                done.add(text);
            }
            // Two passes in a row that found no prompt: the host is asking none.
            idle = done.size() == before ? idle + 1 : 0;
            if (done.size() == rows.size() || idle == 2) {
                break;
            }
        }
        if (done.size() != rows.size()) {
            throw new PatsStepError("Number of prompt does not match : " + pyList(done));
        }
    }

    private static List<JsonNode> promptRows(String raw) {
        JsonNode document;
        try {
            document = JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new PatsStepError("PROMPTS is not JSON: " + e.getOriginalMessage(), e);
        }
        if (document == null || !document.isArray()) {
            throw new PatsStepError("PROMPTS is not a list of prompt rows");
        }
        List<JsonNode> rows = new ArrayList<>();
        document.forEach(rows::add);
        return rows;
    }

    private static String field(JsonNode row, String key) {
        JsonNode value = row.get(key);
        if (value == null || value.isNull()) {
            throw new PatsStepError("a prompt row has no " + key);
        }
        return value.asText();
    }

    /** Python's {@code filter(None, value.split(","))}: the names, unstripped, none empty. */
    private static List<String> touchElements(String value) {
        List<String> out = new ArrayList<>();
        for (String element : value.split(",", -1)) {
            if (!element.isEmpty()) {
                out.add(element);
            }
        }
        return out;
    }

    // -- prepay -----------------------------------------------------------------------------

    /** {@code prepayFuelAmount}: the pump's tile, then its {@code $n} preset if offered, else the amount keyed. */
    static void prepayFuelAmount(CaseRun run, RunnableAction action, BddContext ctx) throws Exception {
        String pump = ActionFields.require(action, "PUMP NO");
        String amount = ActionFields.require(action, "AMOUNT");
        run.setScratch(ScratchKey.PREPAY, true);
        press(run, ctx, FUEL_BUTTON.at(pump).value(), "ID");
        String shown = wholeOrPlain(amount);
        String preset = memberReading(run, ctx, PRESET_FAMILY, text -> text.strip().equals("$" + shown));
        if (preset != null) {
            press(run, ctx, preset, "ID");
            return;
        }
        press(run, ctx, PREPAY_BUTTON, "ID");
        expect(run, ctx, PREPAY_HEADER);
        keyIn(run, ctx, amount.replace(".", ""), PREPAY_KEY, PREPAY_OK);
    }

    /** {@code prepayFuelVolume}: tile, Prepay, gallons keyed and ended with CANCEL (sic), then the grade row. */
    static void prepayFuelVolume(CaseRun run, RunnableAction action, BddContext ctx) throws Exception {
        String pump = ActionFields.require(action, "PUMP NO");
        String gallons = ActionFields.require(action, "GALLONS");
        String grade = ActionFields.require(action, "GRADE");
        run.setScratch(ScratchKey.PREPAY, true);
        press(run, ctx, FUEL_BUTTON.at(pump).value(), "ID");
        press(run, ctx, PREPAY_BUTTON, "ID");
        expect(run, ctx, PREPAY_HEADER);
        // PATS ends a volume with the keypad's CANCEL key, not OK; kept, since that key moves the keypad on to the
        // grade list.
        keyIn(run, ctx, gallons.replace(".", ""), PREPAY_KEY, PREPAY_CANCEL);
        if (shows(run, ctx, GRADE_HEADER.element(), GRADE_HEADER.text(), "ID")) {
            String row = memberReading(run, ctx, GRADE_FAMILY, text -> text.contains(grade));
            if (row == null) {
                throw new PatsStepError("no fuel product row reads " + PyRepr.reprString(grade));
            }
            press(run, ctx, row, "ID");
        }
        // PATS only logs a missing grade prompt; so does this (the case's checks decide).
    }

    /**
     * The amount as {@code DecimalFormat("0.##")} shows it: {@code 10.00} is {@code 10}, {@code 10.50} is {@code 10.5}.
     */
    static String wholeOrPlain(String amount) {
        try {
            return new BigDecimal(amount.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw new PatsStepError("AMOUNT " + PyRepr.reprString(amount) + " is not a number", e);
        }
    }

    // -- the shared looks -------------------------------------------------------------------

    /** {@code expect_text} on {@code expect}'s element. */
    private static void expect(CaseRun run, BddContext ctx, Expect expect) {
        ctx.screen().expectText(run, BddContext.touchAction(expect.element(), "ID"), expect.text());
    }

    /** {@code _press}: one press through the case's own touch. */
    private static void press(CaseRun run, BddContext ctx, String element, String kind) throws Exception {
        ctx.press(run, element, kind);
    }

    /** One key per character of {@code value}, then {@code finalKey}; {@code template} holds a {@code {}} for the key. */
    private static void keyIn(CaseRun run, BddContext ctx, String value, Locator keys, String finalKey)
            throws Exception {
        for (char c : value.toCharArray()) {
            press(run, ctx, digitKey(keys, c), "ID");
        }
        press(run, ctx, finalKey, "ID");
    }

    /**
     * The keypad key for {@code c}: the catalogue family read at the digit. PATS would press an element that does not
     * exist for a letter, so a non-digit fails the step here by name.
     */
    static String digitKey(Locator keys, char c) {
        if (c < '0' || c > '9') {
            throw new PatsStepError("no keypad key for " + PyRepr.reprString(String.valueOf(c)));
        }
        return keys.at(c - '0').value();
    }

    /**
     * {@code isPromptPresent}: {@code text} is in {@code element}'s text within the prompt look. Never throws for an
     * absent prompt; false when it does not show.
     */
    private static boolean shows(CaseRun run, BddContext ctx, String element, String text, String kind) {
        RunnableAction probe = BddContext.touchAction(element, kind);
        try {
            PollUntil.until(() -> {
                String shown = ctx.screen().chosenText(probe);
                return shown != null && shown.contains(text);
            }, lookCeiling(run, ctx), BddScreen.POLL, element + " to show " + PyRepr.reprString(text));
        } catch (DriverTimeout e) {
            return false;
        }
        return true;
    }

    /**
     * {@code _member_reading}: the ID of {@code family}'s visible member whose text {@code matches}, within the prompt
     * look; null when there is none.
     */
    private static String memberReading(CaseRun run, BddContext ctx, Locator family, Predicate<String> matches) {
        try {
            return PollUntil.value(() -> {
                for (BddScreen.Member member : ctx.screen().members(family)) {
                    if (member.visible() && matches.test(member.text()) && !member.id().isEmpty()) {
                        return member.id();
                    }
                }
                return null;
            }, lookCeiling(run, ctx), BddScreen.POLL, "a " + family.name() + " row");
        } catch (DriverTimeout e) {
            return null;
        }
    }

    private static Duration lookCeiling(CaseRun run, BddContext ctx) {
        return Duration.ofMillis(Math.round(run.budget(ctx.promptLook().toMillis() / 1000.0) * 1000));
    }

    /** The Python's {@code repr} of a sorted list of strings: {@code ['a', 'b']}. */
    private static String pyList(Set<String> values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            parts.add(PyRepr.reprString(value));
        }
        return "[" + String.join(", ", parts) + "]";
    }
}
