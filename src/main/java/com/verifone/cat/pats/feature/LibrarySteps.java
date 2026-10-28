package com.verifone.cat.pats.feature;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.authoring.StepLibrary;
import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.validate.PyRepr;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A {@code .feature} written in the step library's own phrases (the domain and UI levels: "the register is idle",
 * "the cashier keys TOTAL"), read into one runner action per step, so the PATS runner runs it step by step as
 * pytest-bdd runs the Python's {@code petrotest.authoring.plugin} feature module.
 *
 * <p>Each step becomes a {@value #ACTION} action carrying the step's line ({@value #LINE_FIELD}); the
 * {@code pats.runner.handlers.steps} family resolves the line against {@link StepLibrary} again when it runs, and calls
 * the Java body of that phrase. A line that is no library phrase, or more than one, still reads (pytest-bdd collects it
 * too) and fails when it runs, by name, as pytest-bdd's "step definition not found" does.
 *
 * <p>The fixtures a step needs are its phrase's ({@link StepDef#fixtures()}); a step that keys the register through RMI
 * also declares the POS, as the Python's {@code scenario_fixtures} does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: this only reads text.
 */
public final class LibrarySteps {

    /** The runner action name a library step is carried under. Not a PATS action name. */
    public static final String ACTION = "CAT_STEP";

    /** The action field holding the step's line, keyword stripped. */
    public static final String LINE_FIELD = "STEP";

    private LibrarySteps() {
    }

    /** One action per line, in order, each a {@value #ACTION} action at its position. */
    public static List<Action> actions(List<String> lines) {
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            out.add(Action.of(ACTION, LINE_FIELD, lines.get(i)).withIndex(i));
        }
        return out;
    }

    /** Whether the action is a library step rather than a PATS action. */
    public static boolean isStep(RunnableAction action) {
        return ACTION.equalsIgnoreCase(action.name());
    }

    /** The domain and UI phrases of the library ({@code pats}-level phrases are PATS actions, read by {@link PatsFeature}). */
    public static List<StepDef> phrases() {
        return StepLibrary.steps().stream().filter(d -> !"pats".equals(d.level())).toList();
    }

    /**
     * The one library phrase {@code line} is.
     *
     * @throws IllegalArgumentException when the line matches no phrase, or several
     */
    public static StepDef resolve(String line) {
        List<StepDef> found = StepLibrary.matches(line, phrases());
        if (found.size() == 1) {
            return found.get(0);
        }
        if (found.isEmpty()) {
            List<String> near = StepLibrary.closest(line, phrases(), 3);
            throw new IllegalArgumentException("step definition not found: " + PyRepr.reprString(line)
                    + (near.isEmpty() ? "" : "; closest phrases: " + near));
        }
        throw new IllegalArgumentException(PyRepr.reprString(line) + " matches " + found.size()
                + " library phrases: " + found);
    }

    /** The fixtures a library step's line needs; none when the line is no single phrase (it fails when run). */
    public static Set<FixtureName> fixturesOf(String line) {
        List<StepDef> found = StepLibrary.matches(line, phrases());
        EnumSet<FixtureName> needed = EnumSet.noneOf(FixtureName.class);
        if (found.size() != 1) {
            return needed;
        }
        for (String fixture : found.get(0).fixtures()) {
            FixtureName.ofKey(fixture).ifPresent(needed::add);
        }
        if (needed.contains(FixtureName.LEGACY_RMI)) {
            needed.add(FixtureName.POS);
        }
        return needed;
    }
}
