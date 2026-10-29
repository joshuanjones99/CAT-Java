package com.verifone.cat.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pos.pages.ExpressPanel;
import com.verifone.cat.pos.pages.ItemPanel;
import com.verifone.cat.recorder.Collapse;
import com.verifone.cat.recorder.Collapse.Step;
import com.verifone.cat.recorder.Recording;
import com.verifone.cat.recorder.RecordingFormat;
import com.verifone.cat.validate.PyRepr;

/**
 * A recording as an authored case: the recorder's taps, said in the step library's phrases. Port of
 * {@code petrotest.authoring.recorded}.
 *
 * <p>{@link Collapse#collapse} folds taps into the page-object calls a hand-written test makes. That set of calls is
 * closed, so each has one phrase here, and a recording becomes a {@code .feature} a person can read and edit:
 * <pre>
 *   pos.key_plu(9999)                    When the cashier rings up PLU 9999
 *   pos.item_panel.pay_cash("1100")      And the customer pays 11.00 in cash
 *   pos.express_panel.button(7).tap()    And the cashier taps express button 7   (UI level)
 * </pre>
 *
 * <p><b>Loud, never silent.</b> A tap {@code collapse} could not map, or a call this table has no phrase for (a sign-on
 * to a non-sales till), becomes a line the library does not have, with the taps and what was observed in a comment above
 * it. The checker refuses that line by name, so the case cannot replay until a person (or a new phrase) resolves it.
 * Pinpad, card, fuel and DCR are not observed by the recorder; the feature says so in a comment.
 */
public final class Recorded {

    private Recorded() {}

    /** Keyed cents as a {@code Money} the step keys back identically: {@code 1100} gives {@code 11.00}. */
    public static String moneyText(String digits) {
        String padded = digits.length() < 2 ? "0".repeat(2 - digits.length()) + digits : digits;
        return padded.substring(0, padded.length() - 2) + "." + padded.substring(padded.length() - 2);
    }

    /** A generated argument back to text: {@code 9999} or {@code "0009"} gives the digits. */
    private static String literal(String source) {
        return stripQuotes(source.strip());
    }

    private static String stripQuotes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '"') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '"') {
            end--;
        }
        return text.substring(start, end);
    }

    private static final String PAD = "(?<pad>pos\\.keypad|pos\\.calculator_keypad"
            + "|Keypad\\(pos\\.driver, namespace=KeypadNamespace\\.VIRTUAL_KEYBOARD\\))";

    private static String pad(String expression) {
        if (expression.contains("calculator")) {
            return "calculator";
        }
        if (expression.contains("VIRTUAL_KEYBOARD")) {
            return "on-screen";
        }
        return "numeric";
    }

    private static String control(String name, String args) {
        if (args == null || args.isEmpty()) {
            return "the cashier taps control \"" + name + "\"";
        }
        List<String> inside = new ArrayList<>();
        for (String a : args.split(",", -1)) {
            inside.add(literal(a));
        }
        return "the cashier taps control \"" + name + "(" + String.join(", ", inside) + ")\"";
    }

    private static final Pattern PAIR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private static String named(String name, String kwargs) {
        List<String> inside = new ArrayList<>();
        Matcher pairs = PAIR.matcher(kwargs);
        while (pairs.find()) {
            inside.add(pairs.group(1) + "=" + pairs.group(2));
        }
        return "the cashier taps control \"" + name + "(" + String.join(", ", inside) + ")\"";
    }

    private record PhraseRule(Pattern pattern, Function<Matcher, String> build) {}

    private static PhraseRule rule(String pattern, Function<Matcher, String> build) {
        return new PhraseRule(Pattern.compile(pattern), build);
    }

    /** Each call {@code collapse} can produce, and its phrase; the more specific first where two could match. */
    private static final List<PhraseRule> PHRASES = List.of(
            rule("pos\\.key_plu\\((?<v>.+)\\)", m -> "the cashier rings up PLU " + literal(m.group("v"))),
            rule("pos\\.item_panel\\.pay_cash\\(\"(?<d>\\d+)\"\\)",
                    m -> "the customer pays " + moneyText(m.group("d")) + " in cash"),
            rule("pos\\.item_panel\\.pay_credit\\(\\)", m -> "the customer pays by credit"),
            rule("pos\\.item_panel\\.total\\(\\)", m -> "the cashier totals the sale"),
            rule("pos\\.item_panel\\.select_with_quantity\\((?<i>\\d+), (?<q>\\d+)\\)",
                    m -> "the cashier rings up " + m.group("q") + " of item " + m.group("i")),
            rule("pos\\.item_panel\\.select\\(MOP\\.(?<m>\\w+)\\)",
                    m -> "the cashier taps item " + ItemPanel.MOP.valueOf(m.group("m")).id()),
            rule("pos\\.item_panel\\.select\\((?<i>\\d+)\\)", m -> "the cashier taps item " + m.group("i")),
            rule("pos\\.sign_on\\(rig\\.cashier\\.id, rig\\.cashier\\.resolve_password\\(\\)\\)",
                    m -> "the cashier signs on"),
            rule("pos\\.sign_off\\(\\)", m -> "the cashier signs off"),
            rule("pos\\.void_ticket\\(\\)", m -> "the cashier voids the ticket"),
            rule(PAD + "\\.enter\\(\"(?<d>[^\"]*)\", confirm=False\\)",
                    m -> "the cashier keys \"" + m.group("d") + "\" on the " + pad(m.group("pad")) + " keypad without OK"),
            rule(PAD + "\\.enter\\(\"(?<d>[^\"]*)\"\\)",
                    m -> "the cashier keys \"" + m.group("d") + "\" on the " + pad(m.group("pad")) + " keypad"),
            rule(PAD + "\\.(?<k>ok|cancel|clear)\\(\\)",
                    m -> "the cashier presses " + m.group("k").toUpperCase(java.util.Locale.ROOT) + " on the "
                            + pad(m.group("pad")) + " keypad"),
            rule("pos\\.express_panel\\.keypad_icon\\.tap\\(\\)",
                    m -> "the cashier taps control \"EXPRESS_PANEL_VIEW_KEYPAD_ICON\""),
            rule("pos\\.express_panel\\.csr_function\\.tap\\(\\)",
                    m -> "the cashier taps express button " + ExpressPanel.CSR_FUNCTION_SLOT),
            rule("pos\\.express_panel\\.button\\((?<n>\\d+)\\)\\.tap\\(\\)",
                    m -> "the cashier taps express button " + m.group("n")),
            rule("pos\\.review_overlay\\.item\\((?<n>\\d+)\\)\\.tap\\(\\)",
                    m -> "the cashier taps row " + m.group("n") + " of the list"),
            rule("pos\\.driver\\.tap\\(locators\\.(?<name>\\w+)\\.at\\((?<kw>.*)\\)\\)",
                    m -> named(m.group("name"), m.group("kw"))),
            rule("pos\\.driver\\.tap\\(locators\\.(?<name>\\w+)(?:, (?<args>.*))?\\)",
                    m -> control(m.group("name"), m.group("args"))));

    /** The library phrase for one generated call, or null when there is none. */
    public static String phrase(String code) {
        for (PhraseRule rule : PHRASES) {
            Matcher match = rule.pattern().matcher(code);
            if (match.matches()) {
                return rule.build().apply(match);
            }
        }
        return null;
    }

    static String taps(List<Integer> seqs) {
        if (seqs.isEmpty()) {
            return "tap ?";
        }
        return seqs.size() == 1 ? "tap " + seqs.get(0) : "taps " + seqs.get(0) + "-" + seqs.get(seqs.size() - 1);
    }

    /** One recorded step as a feature line, with the comment that explains it. */
    public static final class PhrasedStep {
        private final Step step;
        private final boolean resolved;
        private final String text;
        private final String comment;

        public PhrasedStep(Step step) {
            this.step = step;
            String found = step.ok() ? phrase(step.code()) : null;
            this.resolved = found != null;
            String where = taps(step.seqs());
            if (found != null) {
                text = found;
                comment = "";
            } else if (!step.ok()) {
                text = "the recorded " + where + " could not be mapped";
                comment = "NOT RECORDED: " + where + " — " + step.observed();
            } else {
                text = "the recorded " + where + " need a phrase";
                comment = "NO PHRASE YET: " + where + " did `" + step.code() + "` — " + step.observed();
            }
        }

        public Step step() {
            return step;
        }

        /** Whether the step has a library phrase. */
        public boolean resolved() {
            return resolved;
        }

        public String text() {
            return text;
        }

        /** The comment above the line, or the empty string. */
        public String comment() {
            return comment;
        }
    }

    /** Every recorded step, phrased. */
    public static List<PhrasedStep> phrased(Recording recording) {
        List<PhrasedStep> out = new ArrayList<>();
        for (Step step : Collapse.collapse(recording)) {
            out.add(new PhrasedStep(step));
        }
        return out;
    }

    private static String opening(Recording recording) {
        Object text = Recording.map(recording.header().get("initial_screen")).get("csr_function_text");
        if (ExpressPanel.IDLE_TEXT.equals(text)) {
            return "the register is idle";
        }
        if (ExpressPanel.TRANSACTION_ACTIVE_TEXT.equals(text)) {
            return "a sale is in progress";
        }
        return null;
    }

    private static String source(Recording recording) {
        Map<String, Object> source = Recording.map(recording.header().get("source"));
        Object kind = source.get("kind");
        if ("fake_pos".equals(kind)) {
            return "the fake POS — evidence about this repository, not a rig";
        }
        if ("rig".equals(kind)) {
            return "rig " + PyRepr.str(source.get("rig")) + ", register " + PyRepr.str(source.get("register"));
        }
        return "a CDP endpoint (no rig named)";
    }

    /** The authored case a recording becomes, ready for the editor and the checker. */
    public static String recordingFeature(Recording recording, String title, String feature, String key) {
        List<String> lines = new ArrayList<>(List.of("@" + key, "Feature: " + feature, ""));
        lines.add("  # Recorded " + PyRepr.str(recording.header().get("started_at")) + " on " + source(recording) + ".");
        List<String> missing = new ArrayList<>();
        for (String k : RecordingFormat.NOT_CAPTURED.keySet()) {
            if (List.of("pinpad", "card", "fuel", "dcr").contains(k)) {
                missing.add(k);
            }
        }
        lines.add("  # Not observed by the recorder, so add by hand if the case used them: "
                + String.join(", ", missing) + ".");
        lines.add("  Scenario: " + title);
        String opening = opening(recording);
        if (opening != null) {
            lines.add("    Given " + opening);
        }
        String keyword = "When";
        for (PhrasedStep item : phrased(recording)) {
            if (!item.comment().isEmpty()) {
                lines.add("    # " + item.comment());
            }
            lines.add("    " + keyword + " " + item.text());
            keyword = "And";
        }
        lines.add("    Then the receipt matches its baseline");
        lines.add("    And the transaction log matches its baseline");
        return String.join("\n", lines) + "\n";
    }
}
