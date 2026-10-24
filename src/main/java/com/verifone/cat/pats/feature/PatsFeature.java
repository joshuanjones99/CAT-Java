package com.verifone.cat.pats.feature;

import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.RunnableAction;

import com.verifone.cat.validate.PyRepr;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * A PATS case written as a {@code .feature}: one step per PATS action, read into the actions the PATS runner executes.
 *
 * <p>Port of {@code petrotest.pats.feature}, the parts that do not run anything: {@link #buildLine} reads a step's phrase
 * into its action, {@link #render} writes an action as its phrase (checked to read back the same, as the Python does),
 * {@link #scenarioActions} reads a scenario's step lines, {@link #actionsOf} reads a {@code .feature} file, and
 * {@link #drift} says where petroapps' case no longer reads as the {@code .feature} does. The runner that executes the
 * actions, {@code PatsScenario} and the pytest plugin are not ported here.
 *
 * <p>Every case the Python writes is held to the same actions by {@code CharityParityTest}: each Charity feature read
 * here gives the actions the Python gives, as recorded by the Python's own {@code scenario_actions}. UNVERIFIED AGAINST
 * HARDWARE.
 */
public final class PatsFeature {

    /**
     * The tag of a {@code .feature} written from PATS's BDD {@code -TestCase.scenario}: its actions carry
     * {@code SOURCE=BDD}. The Python's {@code petrotest.pats.feature.BDD_TAG}.
     */
    public static final String BDD_TAG = "pats_bdd";

    private PatsFeature() {}

    /** {@link #buildLine(String, int)} at position 0. */
    public static Action buildLine(String line) {
        return buildLine(line, 0);
    }

    /**
     * The action one step line says (keyword stripped).
     *
     * @throws PhraseLookupException when the line is not exactly one PATS action phrase
     */
    public static Action buildLine(String line, int index) {
        List<PhraseMatcher.Match> found = PhraseMatcher.matches(line);
        if (found.size() != 1) {
            throw new PhraseLookupException(PyRepr.reprString(line)
                    + " is not exactly one PATS action phrase (" + found.size() + " match)");
        }
        PhraseMatcher.Match match = found.get(0);
        return match.phrase().build(match.arguments(), index);
    }

    /** Whether {@code line} (keyword stripped) is a PATS action phrase. */
    public static boolean isPatsLine(String line) {
        return !PhraseMatcher.matches(line).isEmpty();
    }

    /** {@link #render(Action, Function)} read back with {@link #buildLine(String)}. */
    public static String render(Action action) {
        return render(action, PatsFeature::buildLine);
    }

    /**
     * {@code action} as a step (no keyword): the first phrase whose line {@code read} gives back the same action as the
     * runner sees it.
     *
     * @throws UnreadableActionException when no phrase does
     */
    public static String render(Action action, Function<String, Action> read) {
        Fields.Canonical want = Fields.canonical(action);
        String upper = action.name().toUpperCase(Locale.ROOT);
        List<String> tried = new ArrayList<>();
        for (Phrase phrase : Phrases.ALL) {
            if (phrase.action() != null && !phrase.action().equals(upper)) {
                continue;
            }
            String line;
            try {
                line = phrase.write(action);
            } catch (IllegalArgumentException exc) {
                tried.add(phrase.text() + ": " + exc.getMessage());
                continue;
            }
            Action back;
            try {
                back = read.apply(line);
            } catch (RuntimeException exc) {
                tried.add(line + ": " + exc.getMessage());
                continue;
            }
            Fields.Canonical got = Fields.canonical(back);
            if (got.equals(want)) {
                return line;
            }
            tried.add(line + ": reads back as " + got);
        }
        throw new UnreadableActionException(action + ": no phrase reads back the same:\n  "
                + String.join("\n  ", tried));
    }

    /**
     * The actions a scenario's step lines say, or empty when none is a PATS phrase.
     *
     * <p>A scenario is all PATS phrases or none: the runner's rules read a case's actions around each one, which a domain
     * step between them would hide. {@code bdd}: the case was written from PATS's {@code -TestCase.scenario}, so every
     * action carries {@code SOURCE=BDD} ({@link #fromBdd}).
     *
     * @throws IllegalArgumentException when some lines are PATS phrases and some are not
     */
    public static Optional<List<Action>> scenarioActions(Iterable<String> lines, boolean bdd) {
        List<String> steps = new ArrayList<>();
        lines.forEach(steps::add);
        List<Boolean> pats = steps.stream().map(PatsFeature::isPatsLine).toList();
        if (!pats.contains(true)) {
            return Optional.empty();
        }
        int firstDomain = pats.indexOf(false);
        if (firstDomain >= 0) {
            throw new IllegalArgumentException(PyRepr.reprString(steps.get(firstDomain))
                    + ": a PATS case's steps must all be PATS action phrases");
        }
        List<Action> actions = IntStream.range(0, steps.size())
                .mapToObj(i -> buildLine(steps.get(i), i))
                .toList();
        return Optional.of(bdd ? fromBdd(actions) : actions);
    }

    /**
     * The actions a {@code .feature} file says: its one scenario's steps, read as {@link #scenarioActions} reads them, with
     * {@code SOURCE=BDD} when the file or its scenario carries {@link #BDD_TAG}.
     *
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when the file does not hold exactly one scenario, or none of its steps is a PATS phrase
     */
    public static List<Action> actionsOf(Path path) throws IOException {
        Gherkin.Feature feature = Gherkin.read(path);
        if (feature.scenarios().size() != 1) {
            throw new IllegalArgumentException(path.getFileName() + ": expected one scenario, found "
                    + feature.scenarios().size());
        }
        Gherkin.Scenario scenario = feature.scenarios().get(0);
        boolean bdd = feature.tags().contains(BDD_TAG) || scenario.tags().contains(BDD_TAG);
        return scenarioActions(scenario.steps(), bdd).orElseThrow(
                () -> new IllegalArgumentException(path.getFileName() + " holds no PATS action phrase"));
    }

    /**
     * {@code actions}, each marked {@code SOURCE=BDD} as {@code petrotest.pats.scenario} marks a BDD case's (an action that
     * already carries it is kept as it is).
     */
    public static List<Action> fromBdd(List<Action> actions) {
        List<Action> out = new ArrayList<>();
        for (Action action : actions) {
            if ("BDD".equals(action.raw("SOURCE"))) {
                out.add(action);
            } else {
                List<RunnableAction.ActionField> fields = new ArrayList<>(action.fields());
                fields.add(new RunnableAction.ActionField("SOURCE", "BDD"));
                out.add(new Action(action.name(), fields, action.index()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Where petroapps' case ({@code upstream}) no longer reads as this {@code .feature} ({@code ours}): the warning the
     * plugin logs, naming the first action that differs, or the length that differs. Empty when they agree.
     */
    public static Optional<String> drift(String key, List<Action> upstream, List<Action> ours) {
        int common = Math.min(upstream.size(), ours.size());
        for (int position = 0; position < common; position++) {
            Action theirs = upstream.get(position);
            Action mine = ours.get(position);
            if (!Fields.canonical(theirs.withIndex(0)).equals(Fields.canonical(mine.withIndex(0)))) {
                return Optional.of(key + ": petroapps' case differs from this .feature at action " + (position + 1)
                        + ": " + mine + " here, " + theirs + " there (the case changed upstream after it was"
                        + " migrated; regenerate with tools/gen_cadence_features.py)");
            }
        }
        if (upstream.size() != ours.size()) {
            return Optional.of(key + ": petroapps' case has " + upstream.size() + " actions, this .feature "
                    + ours.size() + " (the case changed upstream after it was migrated; regenerate with"
                    + " tools/gen_cadence_features.py)");
        }
        return Optional.empty();
    }
}
