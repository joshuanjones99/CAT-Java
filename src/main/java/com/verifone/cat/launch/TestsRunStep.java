package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.pos.fake.FakePosBanner;
import com.verifone.cat.pos.pinpad.PinpadDouble;
import com.verifone.cat.recovery.Capturer;
import com.verifone.cat.recovery.RecoveryLadder;
import com.verifone.cat.recovery.RecoveryOutcome;
import com.verifone.cat.recovery.RunSummary;
import com.verifone.cat.report.CaseLog;
import com.verifone.cat.report.CaseRecord;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.Outcome;

/**
 * Step 5, Tests Run: the run's cases, one after another in plan order, each through {@link CaseRun} with
 * the action registry the {@link RegistryFactory} built for the run. The Python CAT runs the same cases as
 * a pytest session; there is no pytest here, so this step is the session.
 *
 * <p>Per case: it holds while the Run tab's Pause is on, and ends the step as a stop when the operator
 * stops the run (a stop takes effect between cases; the case in hand finishes its own work first). It
 * emits {@code test.started}, one {@code test.step} per action (running, then settled), one
 * {@code test.validation} per validation verdict, and {@code test.finished}, as the Python's plugin does.
 * It writes the case's {@code steps.log} into {@code <run>/<KEY>/}. After a failed case it walks the
 * {@link RecoveryLadder} when the run has a recovery target, emitting {@code recovery.rung}; a walk that
 * ends in rung 6 (DRAIN) stops the remaining cases, as the Python's {@code shouldstop} does. A case that
 * failed only on its validations ran every action as PATS does, so it is not walked, as in the Python.
 *
 * <p>The state a case carries to the next one is the run's {@link RunState}: a case after a failed one
 * waits for the POS, as PATS does.
 *
 * <p>The verdict: {@code complete}, detailed {@code finished/total tests completed[, N failed]}, when every
 * case ran to a verdict (a failing test is not a failing step, as the Python's {@code _tests_outcome}
 * says). {@code failed} when recovery drained the rig, or when no case had a file to run. It always writes
 * the run's {@code RunFinished}: {@code passed} when some case passed and none failed, else {@code failed}.
 *
 * <p>A fake-POS run starts {@link FakePos} on loopback for the run and emits the fake-POS banner (the run
 * is evidence about the repository only). A pinpad double is given to the registry when
 * {@link PinpadDouble#fakePinpadSelected} says so, with its banner. The run's {@code RunStarted} already
 * carries {@code fake_pos=true}.
 *
 * <p>Not done here: the Python's printer lending and port handoffs around pytest, the DCR and fuel-pump
 * listeners, and failed-validation evidence capture (no {@link com.verifone.cat.pats.runner.EvidenceSink}
 * is given). A case that needs one of those fails with {@link DeviceUnavailable}; nothing reports success.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
final class TestsRunStep implements LaunchStep {

    /** The Python module this step is ported from. */
    static final String PYTHON_MODULE = "src/cat/launch/steps/tests_run.py";

    /** The most characters of a case's failure detail kept on its {@code test.finished} event. */
    private static final int DETAIL_CHARS = 4000;

    private static final Logger LOG = LoggerFactory.getLogger(TestsRunStep.class);

    private final RegistryFactory registries;
    private final CaseSource cases;
    private final RunServices.Connector connector;

    /**
     * @param registries the action registry factory, or {@code null} when none is wired (the step is then
     *                   unavailable, which is a failure)
     * @param cases      where the run's cases come from, or {@code null} when none is wired
     * @param connector  adds the devices the run connects; {@code null} means {@link RunServices.Connector#NONE}
     */
    TestsRunStep(RegistryFactory registries, CaseSource cases, RunServices.Connector connector) {
        this.registries = registries;
        this.cases = cases;
        this.connector = connector == null ? RunServices.Connector.NONE : connector;
    }

    @Override
    public String name() {
        return LaunchStepNames.TESTS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        if (registries == null || cases == null) {
            return step.unavailable(PYTHON_MODULE, "the action registry and the plan's case source, which this run "
                    + "is not wired with yet (pats.runner.handlers and the plan's collected cases)");
        }
        FakePos fake = null;
        try {
            if (ctx.config().fakePos()) {
                fake = FakePos.open(FakePos.Hooks.NONE);
                banner(ctx, FakePosBanner.TITLE, FakePosBanner.LINES);
            }
            PinpadDouble pinpad = null;
            if (PinpadDouble.fakePinpadSelected(ctx.config().fakePinpad(), ctx.config().fakePos())) {
                pinpad = new PinpadDouble();
                banner(ctx, PinpadDouble.BANNER_TITLE, List.of(PinpadDouble.BANNER_WARNING));
            }
            return runCases(ctx, step, fake, pinpad);
        } catch (IOException e) {
            return step.fail("the step could not start: " + e.getMessage());
        } finally {
            if (fake != null) {
                fake.close();
            }
        }
    }

    // -- the cases -----------------------------------------------------------------------------

    private boolean runCases(RunContext ctx, StepEmitter step, FakePos fake, PinpadDouble pinpad)
            throws IOException {
        List<CaseEntry> toRun = new ArrayList<>();
        for (CaseEntry entry : cases.cases(ctx.config())) {
            if (entry.runnable()) {
                toRun.add(entry);
            } else {
                step.log("NOT RUN  " + entry.key() + " — the plan lists it with no migrated test to run", "WARNING");
            }
        }
        if (toRun.isEmpty()) {
            step.fail("no case of the plan has a migrated test to run");
            endSession(ctx, Outcome.FAILED, 0, 0);
            return true;
        }

        RunServices base = new RunServices(ctx.config(), ctx.paths(), ctx.runId(), ctx.runFolder(),
                ctx.rig(), null, null, null, pinpad, fake == null ? null : fake.url(), null);
        RunServices services = connector.connect(base);
        ActionRegistry registry = registries.create(services);
        FixtureSource fixtures = fixtureSource(services);
        Set<FixtureName> declared = declaredFixtures(services);
        RecoveryLadder ladder = services.recovery() == null ? null
                : new RecoveryLadder(services.recovery(), new Capturer(), ctx.runId(),
                        new RunSummary(ctx.runId()), null);

        ctx.emit(new CatEvent.TestCollected(toRun.size()));
        RunState state = new RunState();
        int passed = 0;
        int failed = 0;
        int finished = 0;
        String drained = "";
        for (CaseEntry entry : toRun) {
            pauseBeforeCase(ctx, entry.nodeid());
            CaseVerdict verdict = runCase(ctx, entry, registry, fixtures, declared, state, ladder);
            finished++;
            if (verdict.passed()) {
                passed++;
            } else {
                failed++;
            }
            step.progress(entry.key(), finished, toRun.size());
            if (!verdict.drainReason().isEmpty()) {
                drained = verdict.drainReason();
                break;
            }
        }

        if (!drained.isEmpty()) {
            step.fail("stopped after " + finished + "/" + toRun.size() + " tests: " + drained);
            endSession(ctx, Outcome.FAILED, passed, failed);
            return true;
        }
        step.setDetail(finished + "/" + toRun.size() + " tests completed"
                + (failed > 0 ? ", " + failed + " failed" : ""));
        endSession(ctx, passed > 0 && failed == 0 ? Outcome.PASSED : Outcome.FAILED, passed, failed);
        return true;
    }

    /** One case, from its start to its verdict, with its events, its folder and any recovery. */
    private CaseVerdict runCase(RunContext ctx, CaseEntry entry, ActionRegistry registry, FixtureSource fixtures,
            Set<FixtureName> declared, RunState state, RecoveryLadder ladder) {
        String nodeid = entry.nodeid();
        Path folder = ctx.runFolder().resolve(entry.key());
        CaseRecord record = new CaseRecord(nodeid, entry.key(), folder, ctx.clock().instant());
        ctx.emit(new CatEvent.TestStarted(nodeid));
        long started = System.nanoTime();

        Attempt attempt = execute(ctx, record, entry, registry, fixtures, declared, state);
        record.finish(attempt.outcome(), attempt.detail(), "", ctx.clock().instant());
        writeStepsLog(record, folder);

        String drained = "";
        if (ladder != null && attempt.recoverable()) {
            drained = recover(ctx, ladder, nodeid, attempt);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        ctx.emit(new CatEvent.TestFinished(nodeid, attempt.testOutcome(), seconds, 0, attempt.excType(),
                truncate(attempt.detail())));
        return new CaseVerdict(attempt.passed(), drained);
    }

    /** Walks the ladder for a failed case, emits the walk, and returns the drain reason, or "" when none. */
    private static String recover(RunContext ctx, RecoveryLadder ladder, String nodeid, Attempt attempt) {
        RecoveryOutcome walk;
        try {
            walk = ladder.recover(nodeid, attempt.reason());
        } catch (RuntimeException e) {
            LOG.error("recovery for {} raised {}: {}", nodeid, e.getClass().getSimpleName(), e.getMessage());
            return "";
        }
        ctx.emit(new CatEvent.RecoveryRung(nodeid, walk.rung().ordinal(),
                walk.recovered() ? Outcome.PASSED : Outcome.FAILED));
        if (walk.recovered()) {
            return "";
        }
        return "recovery rung 6 (DRAIN) drained the rig after " + nodeid + ": " + attempt.reason();
    }

    /**
     * Runs the case's actions in order, emitting each step as it starts and settles. Stops at the first
     * action that could not be done, recording the rest as not reached, as {@link CaseRun#run} does.
     */
    private static Attempt execute(RunContext ctx, CaseRecord record, CaseEntry entry, ActionRegistry registry,
            FixtureSource fixtures, Set<FixtureName> declared, RunState state) {
        RunnableCase kase;
        try {
            kase = PatsCaseLoader.load(entry.file());
        } catch (IOException | RuntimeException e) {
            return Attempt.error("could not read the case: " + e.getMessage(), e);
        }
        record.setTitle(kase.title());
        CaseRun run = new CaseRun(kase, record, registry, fixtures, declared, state)
                .withBaselineDir(entry.file().getParent())
                .withUpdate(ctx.config().snapshotUpdate());
        List<RunnableAction> actions = kase.actions();
        String nodeid = entry.nodeid();
        int validationsSent = 0;
        try {
            run.begin();
            for (int i = 0; i < actions.size(); i++) {
                RunnableAction action = actions.get(i);
                ctx.emit(new CatEvent.TestStep(nodeid, action.index() + 1, "", action.describe(), "running", 0.0, ""));
                RuntimeException stop = null;
                try {
                    run.perform(action);
                } catch (RuntimeException e) {
                    stop = e;
                }
                validationsSent = emitSettled(ctx, nodeid, record, validationsSent);
                if (stop != null) {
                    for (RunnableAction later : actions.subList(i + 1, actions.size())) {
                        record.addUnreachedStep(later.index() + 1, "", later.describe());
                    }
                    return Attempt.stepFailed(stop);
                }
            }
            run.finish();
            return Attempt.ok();
        } catch (ValidationsFailed failed) {
            // Every action ran, as PATS runs them: a failed verdict, not a rig fault, so no ladder walk.
            return Attempt.validationsFailed(failed);
        } catch (RuntimeException e) {
            return Attempt.stepFailed(e);
        }
    }

    /**
     * Emits the step that just settled and the validation verdicts it added. Returns how many verdicts
     * have now been sent.
     */
    private static int emitSettled(RunContext ctx, String nodeid, CaseRecord record, int validationsSent) {
        List<CaseRecord.StepRecord> steps = record.steps();
        if (!steps.isEmpty()) {
            CaseRecord.StepRecord step = steps.get(steps.size() - 1);
            ctx.emit(new CatEvent.TestStep(nodeid, step.index(), step.keyword(), step.text(), step.outcome(),
                    Math.round(step.duration() * 1000.0) / 1000.0, truncate(step.detail())));
        }
        List<CaseRecord.ValidationRecord> verdicts = record.validations();
        for (int i = validationsSent; i < verdicts.size(); i++) {
            CaseRecord.ValidationRecord v = verdicts.get(i);
            ctx.emit(new CatEvent.TestValidation(nodeid, v.caseKey(), v.kind(), v.name(), v.passed(), v.diff(),
                    v.expected(), v.actual()));
        }
        return verdicts.size();
    }

    // -- pause, stop, the session's end ----------------------------------------------------------

    /** Holds before a case while the Run tab's Pause is on; a stop ends the step instead of running the case. */
    private static void pauseBeforeCase(RunContext ctx, String nodeid) {
        if (ctx.cancelled()) {
            throw new StepRunner.Cancelled();
        }
        PauseGate pause = ctx.pause();
        if (!pause.paused()) {
            return;
        }
        ctx.emit(new CatEvent.LogLine("INFO", "cat.pause",
                "PAUSED before " + nodeid + ": press Continue on CAT's Run tab", LaunchStepNames.TESTS));
        pause.awaitContinue(ctx::cancelled);
        if (ctx.cancelled()) {
            throw new StepRunner.Cancelled();
        }
        ctx.emit(new CatEvent.LogLine("INFO", "cat.pause", "CONTINUED: running " + nodeid, LaunchStepNames.TESTS));
    }

    /** The run's own {@code RunFinished}, with the counts of the cases this step ran. */
    private static void endSession(RunContext ctx, Outcome outcome, int passed, int failed) {
        double elapsed = Math.max(0.0, Duration.between(ctx.startedAt(), ctx.clock().instant()).toMillis() / 1000.0);
        ctx.emit(new CatEvent.RunFinished(ctx.runId(), outcome, passed, failed, 0, List.of(), false, elapsed, 0));
        ctx.sessionFinished(true);
    }

    // -- fixtures and banners --------------------------------------------------------------------

    /** The fixtures a run can provide, each from the services; an absent one fails the action as unavailable. */
    static FixtureSource fixtureSource(RunServices services) {
        return name -> switch (name) {
            case POS -> present(services.pos(), "pos", "no POS driver is connected to this run");
            case PINPAD -> present(services.pinpad(), "pinpad", "no pinpad is connected to this run");
            case COMMANDER_CONFIG -> present(services.commander(), "commander_config",
                    "no Commander PDK shim is connected to this run");
            case LEGACY_RMI -> throw new DeviceUnavailable("legacy_rmi",
                    "PATS's RMI read is replaced by the pos-shim, which this run does not connect yet");
            case DCR -> throw new DeviceUnavailable("dcr", "the DCR session listener is not ported to this run");
            case FUEL_PUMPS -> throw new DeviceUnavailable("fuel_pumps",
                    "the fuel-pump listener is not connected to this run");
            case CONFIGURATIONS -> throw new DeviceUnavailable("configurations",
                    "the petroapps configuration files are not read by this run yet");
        };
    }

    /** The fixtures a case's test is taken to declare: the ones this run can provide. */
    static Set<FixtureName> declaredFixtures(RunServices services) {
        Set<FixtureName> declared = EnumSet.noneOf(FixtureName.class);
        if (services.pos() != null) {
            declared.add(FixtureName.POS);
        }
        if (services.pinpad() != null) {
            declared.add(FixtureName.PINPAD);
        }
        if (services.commander() != null) {
            declared.add(FixtureName.COMMANDER_CONFIG);
        }
        return declared;
    }

    private static Object present(Object value, String surface, String detail) {
        if (value == null) {
            throw new DeviceUnavailable(surface, detail);
        }
        return value;
    }

    /** A banner as run.log lines, at WARNING, so the run's evidence limits are on the record. */
    private static void banner(RunContext ctx, String title, List<String> lines) {
        ctx.emit(new CatEvent.LogLine("WARNING", "cat.fake", "==== " + title + " ====", LaunchStepNames.TESTS));
        for (String line : lines) {
            ctx.emit(new CatEvent.LogLine("WARNING", "cat.fake", line, LaunchStepNames.TESTS));
        }
    }

    // -- files and small helpers -----------------------------------------------------------------

    private static void writeStepsLog(CaseRecord record, Path folder) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(CaseRecord.STEPS_LOG), CaseLog.render(record, ZoneId.systemDefault()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("steps.log for {} could not be written: {}", record.key(), e.getMessage());
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= DETAIL_CHARS ? text : text.substring(0, DETAIL_CHARS);
    }

    /** One case's verdict: whether it passed, and the drain reason ("" when recovery did not drain the rig). */
    private record CaseVerdict(boolean passed, String drainReason) {

        CaseVerdict {
            drainReason = drainReason == null ? "" : drainReason;
        }
    }

    /**
     * How a case ended, before its record is finished: {@code passed}, {@code failed} (a step that could not
     * be done, or a failed validation verdict), or {@code error} (the case could not be read, so no rig was
     * touched and no walk is made).
     */
    private static final class Attempt {

        private final String outcome;
        private final String detail;
        private final String excType;
        private final boolean recoverable;

        private Attempt(String outcome, String detail, String excType, boolean recoverable) {
            this.outcome = outcome;
            this.detail = detail;
            this.excType = excType;
            this.recoverable = recoverable;
        }

        static Attempt ok() {
            return new Attempt("passed", "", "", false);
        }

        static Attempt validationsFailed(ValidationsFailed failed) {
            return new Attempt("failed", failed.getMessage(), failed.getClass().getSimpleName(), false);
        }

        static Attempt stepFailed(RuntimeException cause) {
            String type = cause.getClass().getSimpleName();
            String message = cause instanceof PatsStepError ? cause.getMessage() : type + ": " + cause.getMessage();
            return new Attempt("failed", message, type, true);
        }

        static Attempt error(String message, Exception cause) {
            return new Attempt("error", message, cause.getClass().getSimpleName(), false);
        }

        String outcome() {
            return outcome;
        }

        boolean passed() {
            return "passed".equals(outcome);
        }

        String detail() {
            return detail;
        }

        /** The reason a recovery walk is recorded with, as the Python's {@code "<when> raised <type>: <msg>"}. */
        String reason() {
            return "call raised " + excType + ": " + detail;
        }

        String excType() {
            return excType;
        }

        boolean recoverable() {
            return recoverable;
        }

        Outcome testOutcome() {
            return switch (outcome) {
                case "passed" -> Outcome.PASSED;
                case "failed" -> Outcome.FAILED;
                default -> Outcome.ERROR;
            };
        }
    }
}
