package com.verifone.cat.recorder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.verifone.cat.authoring.PyJson;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.pages.ExpressPanel;
import com.verifone.cat.pos.pages.ItemPanel;
import com.verifone.cat.pos.pages.KeypadNamespace;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.validate.PyRepr;

/**
 * A recording's taps as the page-object calls a hand-written test would make. Port of
 * {@code petrotest.recorder.generate.collapse} and its token rules (CAT_RESULTS_AND_GENERATE.md 3.4, P11.4).
 *
 * <p>A recording is taps; a test is intentions. Keypad presses fold into entries first, then the hand-written rule table
 * collapses taps into the calls the page objects themselves make, so a replay presses what the operator pressed:
 * <pre>
 *   keypad icon, VirtualKeyboardView digits, OK[, CANCEL]  -> pos.key_plu(&lt;digits&gt;)
 *   ItemPanel 145397, 145394, VFI digits, OK                -> pos.item_panel.pay_cash("&lt;digits&gt;")
 *   ItemPanel 145397, 145395                                -> pos.item_panel.pay_credit()
 *   button "Total"                                          -> pos.item_panel.total()
 *   button "Quantity", VFI digits, OK, ItemPanel n          -> pos.item_panel.select_with_quantity
 *   idle message, VFI id + OK, VFI password + OK[, till]    -> pos.sign_on(rig.cashier...)
 *   express 7 then 5 "Log Out" (or 5 alone)                 -> pos.sign_off()
 *   express 4 "Void Ticket"                                 -> pos.void_ticket()
 *   ItemPanel n                                             -> pos.item_panel.select(n | MOP.X)
 * </pre>
 * Anything else mapped falls back to a keypad call, {@code pos.review_overlay.item(n)}, {@code pos.express_panel.button(n)}
 * or, last, {@code pos.driver.tap(locators.<NAME>, ...)}: a catalogue name. Each step's {@code code} is the Python's
 * text exactly, because {@link com.verifone.cat.authoring.Recorded} phrases a step by matching that text.
 *
 * <p><b>Loud, never silent.</b> An unmapped or ambiguous tap, a redacted keypad entry that is not part of a sign-on, or an
 * {@code inferred} line becomes a step that is not {@link Step#ok()}, naming the taps; observed ids stay in the recording.
 *
 * <p>Deviation: a {@link Step} carries no imports or fixtures, which only the Python's pytest-module writer read.
 */
public final class Collapse {

    private Collapse() {}

    /** The three keypads, and the Python enum's value (the id prefix) each one's observation names. */
    static String keypadValue(KeypadNamespace keypad) {
        return switch (keypad) {
            case VFI -> "VFIKeypadWidget";
            case VIRTUAL_KEYBOARD -> "VirtualKeyboardView";
            case CALCULATOR -> "CalculatorView";
        };
    }

    private record Key(KeypadNamespace keypad, String kind) {}

    /** Catalogue name to (keypad, key). {@code digit} keys carry their digit in the slot. */
    private static final Map<String, Key> KEYS = Map.of(
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.name(), new Key(KeypadNamespace.VFI, "digit"),
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.name(), new Key(KeypadNamespace.VFI, "ok"),
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL.name(), new Key(KeypadNamespace.VFI, "cancel"),
            Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CLEAR.name(), new Key(KeypadNamespace.VFI, "clear"),
            Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N.name(),
            new Key(KeypadNamespace.VIRTUAL_KEYBOARD, "digit"),
            Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK.name(),
            new Key(KeypadNamespace.VIRTUAL_KEYBOARD, "ok"),
            Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL.name(),
            new Key(KeypadNamespace.VIRTUAL_KEYBOARD, "cancel"),
            Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N.name(), new Key(KeypadNamespace.CALCULATOR, "digit"),
            Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK.name(), new Key(KeypadNamespace.CALCULATOR, "ok"));

    /** How a generated call reaches each keypad. */
    static String keypadExpr(KeypadNamespace keypad) {
        return switch (keypad) {
            case VFI -> "pos.keypad";
            case CALCULATOR -> "pos.calculator_keypad";
            case VIRTUAL_KEYBOARD -> "Keypad(pos.driver, namespace=KeypadNamespace.VIRTUAL_KEYBOARD)";
        };
    }

    private static final String ITEM = Locators.ITEM_PANEL_N.name();
    private static final String EXPRESS = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.name();
    private static final String KEYPAD_ICON = Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON.name();
    private static final String IDLE_MSG = Locators.IDLE_SCREEN_VIEW_IDLE_MSG.name();
    private static final String REVIEW_ITEM = Locators.REVIEW_OVERLAY_VIEW_ITEM_N.name();

    /**
     * One statement of the generated case, and the taps it came from.
     *
     * @param ok false when the taps could not become a call (a loud failure)
     */
    public record Step(List<Integer> seqs, String code, String observed, boolean ok) {

        public Step {
            seqs = List.copyOf(seqs);
        }

        Step(List<Integer> seqs, String code, String observed) {
            this(seqs, code, observed, true);
        }
    }

    // -- tokens: taps, with keypad presses folded into entries ---------------------

    private interface Token {
        List<Integer> seqList();

        String observed();
    }

    private static final class Tap implements Token {
        final int seq;
        final Mapping map;
        final String text;
        final List<String> texts;
        final boolean redacted;

        Tap(int seq, Mapping map, String text, List<String> texts, boolean redacted) {
            this.seq = seq;
            this.map = map;
            this.text = text;
            this.texts = texts;
            this.redacted = redacted;
        }

        String name() {
            return map.isMapped() ? map.locator() : null;
        }

        String slot() {
            return map.args().size() == 1 ? map.args().get(0) : null;
        }

        /** An id-less button's text, from either kind of text locator. */
        String label() {
            if (!"text".equals(map.via())) {
                return null;
            }
            String label = map.kwargs().get("label");
            if (label != null && !label.isEmpty()) {
                return label;
            }
            return name() != null ? CatalogueMap.textOf(name()) : null;
        }

        @Override
        public List<Integer> seqList() {
            return List.of(seq);
        }

        @Override
        public String observed() {
            if (!map.isMapped()) {
                return map.status() + " (" + (map.reason().isEmpty() ? "see the recording" : map.reason()) + ")";
            }
            if (label() != null) {
                return "button " + PyRepr.reprString(label());
            }
            return map.args().isEmpty() ? String.valueOf(name()) : name() + "(" + String.join(", ", map.args()) + ")";
        }
    }

    private static final class Entry implements Token {
        final KeypadNamespace keypad;
        final List<Integer> seqs = new ArrayList<>();
        String digits = "";
        boolean confirmed;
        boolean redacted;

        Entry(KeypadNamespace keypad) {
            this.keypad = keypad;
        }

        @Override
        public List<Integer> seqList() {
            return seqs;
        }

        @Override
        public String observed() {
            String shown = redacted ? "(redacted)" : PyRepr.reprString(digits);
            return keypadValue(keypad) + " keys " + shown + (confirmed ? ", OK" : "");
        }
    }

    private static List<Token> tokens(Recording recording) {
        List<Token> out = new ArrayList<>();
        Entry open = null;
        for (Map<String, Object> line : recording.taps()) {
            Map<String, Object> element = Recording.map(line.get("element"));
            Object text = element.get("text");
            List<String> texts = new ArrayList<>();
            if (element.get("texts") instanceof List<?> list) {
                list.forEach(item -> texts.add(PyRepr.str(item)));
            }
            Tap tap = new Tap(((Number) line.get("seq")).intValue(), Mapping.fromDict(Recording.map(line.get("map"))),
                    text == null || "".equals(text) ? "" : PyRepr.str(text), texts,
                    Boolean.TRUE.equals(line.get("redacted")));
            Key key = KEYS.get(tap.name() == null ? "" : tap.name());
            if (key != null && ("digit".equals(key.kind()) || "ok".equals(key.kind()))) {
                if (open == null || open.keypad != key.keypad()) {
                    open = new Entry(key.keypad());
                    out.add(open);
                }
                open.seqs.add(tap.seq);
                if ("digit".equals(key.kind())) {
                    open.digits += tap.redacted ? "*" : tap.map.args().get(0);
                    open.redacted |= tap.redacted;
                } else {
                    open.confirmed = true;
                    open = null;
                }
                continue;
            }
            open = null;
            out.add(tap);
        }
        return out;
    }

    // -- the rules ----------------------------------------------------------------

    private record Hit(int used, Step step) {}

    @FunctionalInterface
    private interface Rule {
        Hit apply(List<Token> t, int i);
    }

    private static Token at(List<Token> tokens, int index) {
        return index < tokens.size() ? tokens.get(index) : null;
    }

    private static boolean isTap(Token token, String name, Object slot) {
        if (!(token instanceof Tap tap) || !Objects.equals(tap.name(), name)) {
            return false;
        }
        return slot == null || String.valueOf(slot).equals(tap.slot());
    }

    private static boolean isEntry(Token token, KeypadNamespace keypad, boolean redacted) {
        return token instanceof Entry entry && entry.keypad == keypad && entry.confirmed && !entry.digits.isEmpty()
                && entry.redacted == redacted;
    }

    private static boolean labelIs(Token token, String label) {
        return token instanceof Tap tap && label.equals(tap.label());
    }

    /** A double-quoted literal, as {@code json.dumps(value, ensure_ascii=False)} writes one. */
    static String str(String value) {
        return PyJson.dumps(value, false);
    }

    /** A keyed number as source: an int, unless a leading zero would be lost. */
    static String number(String digits) {
        return digits.equals("0") || !digits.startsWith("0") ? digits : str(digits);
    }

    private static List<Integer> seqs(List<Token> tokens) {
        List<Integer> out = new ArrayList<>();
        tokens.forEach(t -> out.addAll(t.seqList()));
        return out;
    }

    private static String observed(List<Token> tokens) {
        List<String> parts = new ArrayList<>();
        tokens.forEach(t -> parts.add(t.observed()));
        return String.join("; ", parts);
    }

    private static Hit step(List<Token> tokens, String code) {
        return new Hit(tokens.size(), new Step(seqs(tokens), code, observed(tokens)));
    }

    private static Hit keyPlu(List<Token> t, int i) {
        if (!(isTap(t.get(i), KEYPAD_ICON, null) && isEntry(at(t, i + 1), KeypadNamespace.VIRTUAL_KEYBOARD, false))) {
            return null;
        }
        Entry entry = (Entry) t.get(i + 1);
        List<Token> used = t.subList(i, i + 2);
        Token cancel = at(t, i + 2);
        if (cancel instanceof Tap tap
                && new Key(KeypadNamespace.VIRTUAL_KEYBOARD, "cancel").equals(KEYS.get(tap.name() == null ? "" : tap.name()))) {
            used = t.subList(i, i + 3);
        }
        return step(used, "pos.key_plu(" + number(entry.digits) + ")");
    }

    private static Hit pay(List<Token> t, int i) {
        if (!isTap(t.get(i), ITEM, ItemPanel.MOP.PAYMENT_GATEWAY.id())) {
            return null;
        }
        if (isTap(at(t, i + 1), ITEM, ItemPanel.MOP.CASH.id()) && isEntry(at(t, i + 2), KeypadNamespace.VFI, false)) {
            Entry entry = (Entry) t.get(i + 2);
            return step(t.subList(i, i + 3), "pos.item_panel.pay_cash(" + str(entry.digits) + ")");
        }
        if (isTap(at(t, i + 1), ITEM, ItemPanel.MOP.CREDIT.id())) {
            return step(t.subList(i, i + 2), "pos.item_panel.pay_credit()");
        }
        return null;
    }

    private static Hit total(List<Token> t, int i) {
        return labelIs(t.get(i), "Total") ? step(t.subList(i, i + 1), "pos.item_panel.total()") : null;
    }

    private static Hit quantity(List<Token> t, int i) {
        Token item = at(t, i + 2);
        if (!(labelIs(t.get(i), "Quantity") && isEntry(at(t, i + 1), KeypadNamespace.VFI, false))) {
            return null;
        }
        if (!(item instanceof Tap tap && ITEM.equals(tap.name()) && tap.slot() != null && !tap.slot().isEmpty())) {
            return null;
        }
        Entry entry = (Entry) t.get(i + 1);
        String code = "pos.item_panel.select_with_quantity(" + itemArg(tap) + ", " + new BigInteger(entry.digits) + ")";
        return step(t.subList(i, i + 3), code);
    }

    private static Hit signOn(List<Token> t, int i) {
        int start = isTap(t.get(i), IDLE_MSG, null) ? i + 1 : i;
        if (!(isEntry(at(t, start), KeypadNamespace.VFI, false) && isEntry(at(t, start + 1), KeypadNamespace.VFI, true))) {
            return null;
        }
        int end = start + 2;
        String till = "";
        Token row = at(t, end);
        if (row instanceof Tap tap && REVIEW_ITEM.equals(tap.name())) {
            String text = tap.text.toUpperCase(Locale.ROOT);
            if (text.contains(Pos.OPEN_TILL) && isEntry(at(t, end + 1), KeypadNamespace.CALCULATOR, false)) {
                end += 2;
            } else if (text.contains(Pos.NON_SALES_ONLY)) {
                till = ", till=pos.NON_SALES_ONLY";
                end += 1;
            }
        }
        return step(t.subList(i, end), "pos.sign_on(rig.cashier.id, rig.cashier.resolve_password()" + till + ")");
    }

    private static Hit express(List<Token> t, int i) {
        if (!isTap(t.get(i), EXPRESS, null)) {
            return null;
        }
        Tap tap = (Tap) t.get(i);
        Token next = at(t, i + 1);
        String slot = tap.slot();
        if (String.valueOf(ExpressPanel.CSR_FUNCTION_SLOT).equals(slot)
                && isTap(next, EXPRESS, ExpressPanel.LOG_OUT_SLOT)
                && ((Tap) next).text.contains(ExpressPanel.LOG_OUT_TEXT)) {
            return step(t.subList(i, i + 2), "pos.sign_off()");
        }
        if (String.valueOf(ExpressPanel.LOG_OUT_SLOT).equals(slot) && tap.text.contains(ExpressPanel.LOG_OUT_TEXT)) {
            return step(t.subList(i, i + 1), "pos.sign_off()");
        }
        if (String.valueOf(ExpressPanel.VOID_TICKET_SLOT).equals(slot)
                && tap.text.contains(ExpressPanel.VOID_TICKET_TEXT)) {
            return step(t.subList(i, i + 1), "pos.void_ticket()");
        }
        if (String.valueOf(ExpressPanel.CSR_FUNCTION_SLOT).equals(slot)) {
            return step(t.subList(i, i + 1), "pos.express_panel.csr_function.tap()");
        }
        return step(t.subList(i, i + 1), "pos.express_panel.button(" + (slot == null ? "None" : slot) + ").tap()");
    }

    /** An item argument: {@code MOP.CASH} for a MOP button, else the number. */
    private static String itemArg(Tap tap) {
        BigInteger value = new BigInteger(tap.slot() == null || tap.slot().isEmpty() ? "0" : tap.slot());
        for (ItemPanel.MOP mop : ItemPanel.MOP.values()) {
            if (BigInteger.valueOf(mop.id()).equals(value)) {
                return "MOP." + mop.name();
            }
        }
        return value.toString();
    }

    private static Hit select(List<Token> t, int i) {
        Token token = t.get(i);
        if (!(token instanceof Tap tap && ITEM.equals(tap.name()) && tap.slot() != null && !tap.slot().isEmpty())) {
            return null;
        }
        return step(t.subList(i, i + 1), "pos.item_panel.select(" + itemArg(tap) + ")");
    }

    private static final List<Rule> RULES = List.of(Collapse::keyPlu, Collapse::pay, Collapse::total,
            Collapse::quantity, Collapse::signOn, Collapse::express, Collapse::select);

    /** One token no rule took: a keypad call, a catalogue tap, or a loud failure. */
    private static Step fallback(Token token) {
        if (token instanceof Entry entry) {
            if (entry.redacted) {
                return new Step(entry.seqs, "", entry.observed(), false);
            }
            String keypad = keypadExpr(entry.keypad);
            String code;
            if (entry.digits.isEmpty()) {
                code = keypad + ".ok()";
            } else if (entry.confirmed) {
                code = keypad + ".enter(" + str(entry.digits) + ")";
            } else {
                code = keypad + ".enter(" + str(entry.digits) + ", confirm=False)";
            }
            return new Step(entry.seqs, code, entry.observed());
        }
        Tap tap = (Tap) token;
        if (!tap.map.isMapped() || tap.redacted) {
            return new Step(List.of(tap.seq), "", tap.observed(), false);
        }
        String name = tap.name();
        Key key = KEYS.get(name);
        if (key != null) { // cancel / clear: ok and digits were folded into entries
            return new Step(List.of(tap.seq), keypadExpr(key.keypad()) + "." + key.kind() + "()", tap.observed());
        }
        if (name.equals(KEYPAD_ICON)) {
            return new Step(List.of(tap.seq), "pos.express_panel.keypad_icon.tap()", tap.observed());
        }
        if (name.equals(REVIEW_ITEM) && tap.slot() != null && !tap.slot().isEmpty()) {
            return new Step(List.of(tap.seq), "pos.review_overlay.item(" + new BigInteger(tap.slot()) + ").tap()",
                    tap.observed());
        }
        String code;
        if (!tap.map.kwargs().isEmpty()) {
            List<String> args = new ArrayList<>();
            tap.map.kwargs().forEach((k, v) -> args.add(k + "=" + str(v)));
            code = "pos.driver.tap(locators." + name + ".at(" + String.join(", ", args) + "))";
        } else {
            StringBuilder args = new StringBuilder();
            tap.map.args().forEach(a -> args.append(", ").append(number(a)));
            code = "pos.driver.tap(locators." + name + args + ")";
        }
        return new Step(List.of(tap.seq), code, tap.observed());
    }

    /** The recording's taps as the page-object calls a hand-written test would make. */
    public static List<Step> collapse(Recording recording) {
        List<Token> tokens = tokens(recording);
        List<Step> steps = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            Hit hit = null;
            for (Rule rule : RULES) {
                hit = rule.apply(tokens, i);
                if (hit != null) {
                    break;
                }
            }
            if (hit != null) {
                steps.add(hit.step());
                i += hit.used();
            } else {
                steps.add(fallback(tokens.get(i)));
                i++;
            }
        }
        for (Map<String, Object> line : recording.inferred()) {
            Object device = line.get("device");
            String observed = "inferred " + (device == null ? "?" : PyRepr.str(device)) + " line (reserved in format v1)";
            Object seq = line.get("seq");
            steps.add(new Step(List.of(seq instanceof Number n ? n.intValue() : 0), "", observed, false));
        }
        return steps;
    }
}
