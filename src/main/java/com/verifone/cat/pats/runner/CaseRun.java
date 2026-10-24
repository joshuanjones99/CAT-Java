package com.verifone.cat.pats.runner;

import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One case's execution: its actions in order, each dispatched to its handler, with the verdict at the end.
 * Ported from the Python {@code petrotest.pats.runner.core.CaseRun}, which is the specification.
 *
 * <p>Four PATS rules shape it:
 * <ul>
 *   <li><b>Two kinds of failure.</b> A handler that throws {@link ValidationFailure} records a failed
 *       validation and the case carries on; the test fails at the end, listing every one
 *       ({@link #finish()}). Any other throw is a step that could not be done: the step is recorded as
 *       failed, the simulator configuration the case changed is put back ({@link #restoreSimulators()}),
 *       and the case stops with a {@link PatsStepError}.</li>
 *   <li><b>Per-action results</b> go into the case's {@link CaseRecord}: one step per action, passed,
 *       failed, or (after a hard failure) skipped as "not reached". A validation's verdict goes to the
 *       record's validations, unless the handler gave its own verdict first.</li>
 *   <li><b>Recovery hooks.</b> A failed case marks the run's {@link RunState}, so the next case knows the
 *       POS has been pressed on ({@link #afterFailure()}); a failed validation captures evidence through
 *       the {@link EvidenceSink}; a hard failure restores the simulator configuration this case changed.</li>
 *   <li><b>{@code SLEEP} is not a sleep.</b> Its duration is carried into the ceiling of the next bounded
 *       wait ({@link #addCarry} and {@link #budget}); {@link #keepSleeps()} is the triage switch that
 *       waits them out as PATS does.</li>
 * </ul>
 *
 * <p>Left out, for the handler families to add: the per-action pauses PATS takes after an init's reply,
 * the POS overlay sweep, the POS print and transaction marks, the DCR marks, the dead-end OK press at
 * the verdict, and the SLEEP handler itself. A test-only handler can drive this class; no production
 * handler is registered yet.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class CaseRun {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The environment variable that makes {@code SLEEP} wait out its time as PATS does (triage only). */
    public static final String SLEEPS_ENV = "CAT_PATS_SLEEPS";

    private final RunnableCase kase;
    private final CaseRecord record;
    private final ActionRegistry registry;
    private final FixtureSource fixtures;
    private final Set<FixtureName> declared;
    private final RunState state;
    private final List<String> soft = new ArrayList<>();
    private final Map<Object, Runnable> simulatorRestores = new LinkedHashMap<>();

    private Path baselineDir;
    private EvidenceSink evidence;
    private boolean update;
    private boolean keepSleeps = "keep".equals(System.getenv(SLEEPS_ENV));
    private double carry;
    private boolean afterFailure;
    /** The texts the action in hand compared, {expected, actual}; null until a handler sets them. */
    private String[] compared;

    /**
     * @param kase      the case to run
     * @param record    the case's record, where its steps, captures and validations are written
     * @param registry  the action-name to handler registry
     * @param fixtures  where the fixtures come from
     * @param declared  the fixtures the case's test declared; evidence is captured only when it has POS
     * @param state     the run's state, shared by its cases
     */
    public CaseRun(RunnableCase kase, CaseRecord record, ActionRegistry registry, FixtureSource fixtures,
            Set<FixtureName> declared, RunState state) {
        this.kase = kase;
        this.record = record;
        this.registry = registry;
        this.fixtures = fixtures;
        this.declared = Set.copyOf(declared);
        this.state = state;
    }

    /** The directory holding the case's test file, where its CAT baselines live. */
    public CaseRun withBaselineDir(Path directory) {
        this.baselineDir = directory;
        return this;
    }

    /** Where failed validations' evidence goes. Without one, no evidence is captured. */
    public CaseRun withEvidence(EvidenceSink sink) {
        this.evidence = sink;
        return this;
    }

    /** Whether this is an update run, which writes baselines instead of comparing them. */
    public CaseRun withUpdate(boolean update) {
        this.update = update;
        return this;
    }

    /** Whether {@code SLEEP} waits its full time as PATS does, rather than carrying it into the next wait. */
    public CaseRun withKeepSleeps(boolean keepSleeps) {
        this.keepSleeps = keepSleeps;
        return this;
    }

    // -- the loop --------------------------------------------------------------

    /**
     * Every action in order, then the verdict: {@link #begin()}, {@link #perform} each, {@link #finish()}.
     * After a hard failure the actions the case did not reach are recorded as "not reached", so the
     * case's steps read as the whole case.
     *
     * @throws PatsStepError       when an action could not be done
     * @throws ValidationsFailed   when every action ran and some validation failed
     */
    public void run() {
        begin();
        List<RunnableAction> actions = kase.actions();
        for (int i = 0; i < actions.size(); i++) {
            try {
                perform(actions.get(i));
            } catch (PatsStepError stop) {
                for (RunnableAction later : actions.subList(i + 1, actions.size())) {
                    record.addUnreachedStep(later.index() + 1, "", later.describe());
                }
                throw stop;
            }
        }
        finish();
    }

    /**
     * The marks a case takes before its first action. Takes the run's after-failure mark, which says
     * whether the case before this one failed, and logs the case as it begins. A case run step by step
     * (a {@code .feature} case) calls this, then {@link #perform} once per step, then {@link #finish()}.
     */
    public void begin() {
        afterFailure = state.beginCase();
        LOG.info("{}: {} ({} actions, {})", kase.key(), kase.title(), kase.actions().size(), kase.source());
    }

    /**
     * One action. Dispatches it to its handler and records the step: a validation failure carries on
     * (the step is recorded failed, with its detail); any other failure is recorded and stops the case.
     *
     * @throws PatsStepError when the action could not be done, or has no handler
     */
    public void perform(RunnableAction action) {
        String text = action.describe();
        int stepIndex = action.index() + 1;
        Optional<ActionHandler> handler = registry.handlerFor(action.name());
        if (handler.isEmpty()) {
            // As PATS: no state is restored and no evidence taken, since nothing was done.
            String message = kase.key() + " " + text + ": no handler for " + action.name();
            record.beginStep(stepIndex, "", text);
            record.failStep(message);
            throw new PatsStepError(message);
        }
        record.beginStep(stepIndex, "", text);
        int verdictsBefore = record.validations().size();
        compared = null;
        try {
            handler.get().handle(this, action);
        } catch (ValidationFailure check) {
            String message = text + ": " + check.getMessage();
            LOG.warn("{}: validation failed, carrying on as PATS does: {}", kase.key(), message);
            validated(action, verdictsBefore, false, check.getMessage());
            soft.add(message);
            record.noteStepFailure(message);
            evidence("validation-" + stepIndex);
            record.endStep();
            return;
        } catch (PatsStepError stop) {
            state.markCaseFailed();
            validated(action, verdictsBefore, false, "could not validate: " + stop.getMessage());
            evidence("step-" + stepIndex);
            restoreSimulators();
            String message = kase.key() + " " + text + ": " + stop.getMessage();
            record.failStep(message);
            throw new PatsStepError(message, stop);
        } catch (Exception failure) {
            state.markCaseFailed();
            String cause = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            validated(action, verdictsBefore, false, "could not validate: " + cause);
            evidence("step-" + stepIndex);
            restoreSimulators();
            String message = kase.key() + " " + text + ": " + cause;
            record.failStep(message);
            throw new PatsStepError(message, failure);
        }
        if (!(update && ValidationKinds.comparesNothingOnUpdate(action.name()))) {
            validated(action, verdictsBefore, true, "");
        }
        record.endStep();
    }

    /**
     * The verdict after the last action: every failed validation, listed. Raised with
     * {@link ValidationsFailed#actionsCompleted()} set, since every action ran as PATS runs it. A case
     * with no failed validation returns normally.
     *
     * @throws ValidationsFailed when any validation failed
     */
    public void finish() {
        if (soft.isEmpty()) {
            return;
        }
        state.markCaseFailed();
        throw new ValidationsFailed(kase.key() + ": " + soft.size()
                + " validation(s) failed (PATS carries on past these, so every one is listed):\n\n"
                + String.join("\n\n", soft));
    }

    // -- what a handler reaches --------------------------------------------------

    public RunnableCase kase() {
        return kase;
    }

    /** The case's record: its steps, captures and validations. A handler notes a failure through it. */
    public CaseRecord record() {
        return record;
    }

    /** The fixture, as the test's fixture machinery builds it. */
    public Object fixture(FixtureName name) {
        return fixtures.fixture(name);
    }

    /** The fixture, cast to the type a handler expects. */
    public <T> T fixture(FixtureName name, Class<T> type) {
        return type.cast(fixtures.fixture(name));
    }

    /** Whether the case's test declared the fixture. */
    public boolean declares(FixtureName name) {
        return declared.contains(name);
    }

    /** The directory of the case's test file, if the caller gave one. */
    public Optional<Path> baselineDir() {
        return Optional.ofNullable(baselineDir);
    }

    /** Whether this is an update run. */
    public boolean update() {
        return update;
    }

    /** Whether {@code SLEEP} waits out its full time (triage) rather than carrying it into the next wait. */
    public boolean keepSleeps() {
        return keepSleeps;
    }

    /** Whether the case before this one failed, so recovery has just pressed on the POS. */
    public boolean afterFailure() {
        return afterFailure;
    }

    /** The validations that failed so far, as {@link #finish()} will list them. */
    public List<String> softFailures() {
        return List.copyOf(soft);
    }

    /** Sets the texts the running action compared, for its verdict in the Validations view. */
    public void setCompared(String expected, String actual) {
        this.compared = new String[] {expected, actual};
    }

    /** Adds the seconds a {@code SLEEP} carried to the ceiling of the next bounded wait. */
    public void addCarry(double seconds) {
        carry += seconds;
    }

    /** {@code base} plus whatever the case's preceding sleeps carried; the carry is then spent. */
    public double budget(double base) {
        double extra = carry;
        carry = 0.0;
        return base + extra;
    }

    /**
     * Records a simulator property the case changed, and how to put it back. A later change to the same
     * {@code what} replaces the restore, as the Python dict does.
     */
    public void recordSimulatorChange(Object what, Runnable restore) {
        simulatorRestores.put(what, restore);
    }

    /**
     * Puts back every simulator configuration this case changed. Called on a hard failure, which stops the
     * case before its own restoring step; a case that runs to its end keeps what it wrote, as PATS does.
     * A restore that throws is logged and the rest still run.
     */
    public void restoreSimulators() {
        for (Map.Entry<Object, Runnable> entry : simulatorRestores.entrySet()) {
            try {
                entry.getValue().run();
            } catch (RuntimeException e) {
                LOG.warn("{}: could not restore {}: {}", kase.key(), entry.getKey(), e.getMessage());
                continue;
            }
            LOG.warn("{}: restored {} after the failure", kase.key(), entry.getKey());
        }
        simulatorRestores.clear();
    }

    // -- internals -------------------------------------------------------------

    /**
     * Records a validation action's verdict, unless the handler gave its own since {@code before}. Only
     * the actions in {@link ValidationKinds} record one.
     */
    private void validated(RunnableAction action, int before, boolean passed, String diff) {
        Optional<String> kind = ValidationKinds.kindOf(action.name());
        if (kind.isEmpty() || record.validations().size() != before) {
            return;
        }
        String expected = compared == null ? "" : compared[0];
        String actual = compared == null ? "" : compared[1];
        record.recordValidation(kind.get(), ValidationKinds.validationName(action), passed,
                diff == null ? "" : diff, expected, actual, kase.key());
    }

    /**
     * Captures evidence for the case, named {@code <key>-<label>}, when it declared the POS fixture. Best
     * effort: a failure is logged, and the verdict stands without the evidence.
     */
    private void evidence(String label) {
        if (evidence == null || !declared.contains(FixtureName.POS)) {
            return;
        }
        try {
            evidence.capture(kase.key() + "-" + label);
        } catch (Exception e) {
            LOG.warn("{}: could not capture evidence: {}", kase.key(), e.getMessage());
        }
    }
}
