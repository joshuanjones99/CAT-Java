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
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.EvidenceSink;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.posui.PinpadView;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.pos.driver.CaseArtifacts;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.pos.fake.FakePosBanner;
import com.verifone.cat.pos.fake.FakePosPinpad;
import com.verifone.cat.pos.pinpad.PinpadDouble;
import com.verifone.cat.recovery.Capturer;
import com.verifone.cat.recovery.RecoveryLadder;
import com.verifone.cat.recovery.RecoveryOutcome;
import com.verifone.cat.recovery.RunSummary;
import com.verifone.cat.report.CaseLog;
import com.verifone.cat.report.CaseRecord;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.Outcome;
import com.verifone.cat.rig.Rig;

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
 * {@link RecoveryLadder} when the run has a recovery target (on a rig run, {@link BrowserConnector} gives the rig's,
 * {@code recovery.RigRecovery}; a fake-POS run has none, as the Python walks only with {@code --rig}), emitting
 * {@code recovery.rung}; a walk that
 * ends in rung 6 (DRAIN) stops the remaining cases, as the Python's {@code shouldstop} does. A case that
 * failed only on its validations ran every action as PATS does, so it is not walked, as in the Python.
 *
 * <p>The state a case carries to the next one is the run's {@link RunState}: a case after a failed one
 * waits for the POS, as PATS does.
 *
 * <p>A {@code @plan_end} case (an end-of-plan report, built from every sale before it) is judged only in a full,
 * in-order run of its plan on a rig ({@link PlanStart#isFullPlanRun}, the Python's {@code PETROTEST_FULL_PLAN_RUN}):
 * in any other run it is skipped before any fixture, {@code exc_type} {@code Skipped}, with the Python plugin's words
 * ({@link #PLAN_END_SKIP}; rig owner, 2026-09-17). In a full run, one whose validations fail after an earlier case
 * failed is an expected failure, not a failure: {@code xfailed} in its record, {@code skipped} with {@code exc_type}
 * {@code XFailed} on its {@code test.finished}, its detail {@code "plan_end verdict untrusted: N earlier case(s)
 * failed\n..."}, and it counts neither as passed nor as failed. A step that could not be done is still a failure. This
 * is the Python's {@code PatsCase.run} / {@code PatsScenario.finish} ({@code pytest.xfail}) over
 * {@code validate.plugin.earlier_failures}, which counts cases that failed in setup or call (rig owner, 2026-09-23).
 *
 * <p>The cases are those run start resolved ({@link RunContext#caseEntries}) that {@link LaunchConfig#suites} still
 * names: the whole plan, or a partial selection. A {@code .feature} Scenario Outline with Examples is one case per row
 * ({@link PatsCaseLoader#loadAll}), its node id the file's with the row's id in brackets and its folder the key with
 * the id added, as pytest-bdd parametrises it.
 *
 * <p>A fixture a case declares that the run cannot give (no pos-shim for {@code legacy_rmi} on a fake-POS run, no
 * pinpad, no Commander) errors the case at setup, before its first action, naming the fixture, as pytest does with a
 * fixture that raises: {@code error}, which counts as an earlier failure and walks the ladder as a {@code setup}
 * failure.
 *
 * <p>A failed validation's evidence (the POS screenshot and DOM, {@code PosDriver.capture}) goes to the case's folder:
 * the connector's driver writes through a {@link CaseArtifacts} ({@link CaseEvidence}) that is pointed at the case's
 * folder for the case's duration, the Python's {@code pos_artifacts}. A run with no POS driver captures nothing.
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
 * <p>The fixtures a case's actions ask for are the {@link RegistryFactory}'s ({@link RegistryFactory#build}): for
 * {@link StandardRegistry}, {@code StandardHandlers.fixtureSource} over the run's one {@code HandlerFixtures}, so
 * {@code LEGACY_RMI} is the pos-shim and {@code DCR} and {@code FUEL_PUMPS} are the session's Petro devices.
 *
 * <p>What the Python's pytest session adds around the cases, done here as the session:
 * <ul>
 *   <li><b>The handoffs</b> ({@code _step_tests}): PATS's pump port 1072, held by the run since step 3
 *       ({@link PetroPorts}), is released before the session's pump listener binds it and held again when the
 *       step ends. The receipt printer's port is the session's while the tests run: CAT's setup hold
 *       ({@link PrinterHold}) is released, then the connector opens it at the session's start (as the Python's
 *       {@code _topaz_printer_session} does) and closes it at the end, and its Receipt Printer row says it is lent,
 *       then stopped ({@code _release_printer_port(lent=True)}, {@code cat.launch.printer.lend_to_tests},
 *       {@code tests_ended}).</li>
 *   <li><b>Petro's pumps and DCR 1</b> ({@link PetroSession}): one listener each for the whole step when a case of
 *       the run declares them; the DCR link check and its once-per-session recovery at a DCR case's setup; and after a
 *       case that failed in its actions, its pumps cleared and their due fills settled.</li>
 *   <li><b>A fixture's setup failure</b> (a Petro fixture the rig cannot give, a listener that did not start, a DCR
 *       link that would not come back) errors the case before its first action, as a pytest fixture error does, and
 *       walks the recovery ladder as a {@code setup} failure.</li>
 * </ul>
 * {@link RunContext#testsStarted()} is set on entering the step, as the Python's {@code tests_started} (its
 * {@code _step_tests} sets it first), so Cleanup's day close runs even when the step fails before its first case. Only
 * an unwired step (no registry or case source, which the Python cannot be) leaves it unset.
 *
 * <p>FEPSIM v2: when it serves Buypass this run, its listener is the {@link StandardRegistry}'s {@code buypassV2}
 * fixture and packet source ({@code StartSimulatorsStep.fepsimBuypass}, the Python's {@code fepsim_buypass_env}); after
 * the session, what each Secondary FEP's FEPSIM listener heard is one line each ({@code fep_exchange_lines}), WARNING
 * when it starts {@code WARN}.
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
    private final PetroSession.Devices forecourt;

    /**
     * @param registries the action registry factory, or {@code null} when none is wired (the step is then
     *                   unavailable, which is a failure)
     * @param cases      where the run's cases come from, or {@code null} when none is wired
     * @param connector  adds the devices the run connects; {@code null} means {@link RunServices.Connector#NONE}
     */
    TestsRunStep(RegistryFactory registries, CaseSource cases, RunServices.Connector connector) {
        this(registries, cases, connector, null);
    }

    /**
     * @param forecourt Petro Simulator Suite's devices for the session, or {@code null} for the rig's own
     *                  ({@link PetroSession.Devices#forRig}); a test passes loopback fakes
     */
    TestsRunStep(RegistryFactory registries, CaseSource cases, RunServices.Connector connector,
            PetroSession.Devices forecourt) {
        this.registries = registries;
        this.cases = cases;
        this.connector = connector == null ? RunServices.Connector.NONE : connector;
        this.forecourt = forecourt;
    }

    @Override
    public String name() {
        return LaunchStepNames.TESTS;
    }

    /** What the step lends and takes for the session, released whatever way the step ends. */
    private static final class Session {
        PetroSession petro;
        boolean heldPumpPort;
        boolean printerLent;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        if (registries == null || (cases == null && ctx.caseEntries() == null)) {
            return step.unavailable(PYTHON_MODULE, "the action registry and the plan's case source, which this run "
                    + "is not wired with yet (pats.runner.handlers and the plan's collected cases)");
        }
        ctx.testsStarted(true);
        FakePos fake = null;
        Session session = new Session();
        try {
            // The pinpad double first: the fake POS's MOP screens raise their prompts on it (FakePosPinpad.wire).
            PinpadDouble pinpad = null;
            if (PinpadDouble.fakePinpadSelected(ctx.config().fakePinpad(), ctx.config().fakePos())) {
                pinpad = new PinpadDouble();
                banner(ctx, PinpadDouble.BANNER_TITLE, List.of(PinpadDouble.BANNER_WARNING));
            }
            if (ctx.config().fakePos()) {
                fake = FakePos.open(pinpad == null ? FakePos.Hooks.NONE : FakePosPinpad.wire(pinpad));
                banner(ctx, FakePosBanner.TITLE, FakePosBanner.LINES);
            }
            boolean keepGoing = runCases(ctx, step, fake, pinpad, session);
            fepExchanges(ctx, step);
            return keepGoing;
        } catch (IOException e) {
            return step.fail("the step could not start: " + e.getMessage());
        } finally {
            // The listeners first, so PATS's port is free before the run holds it again.
            if (session.petro != null) {
                session.petro.close();
            }
            connector.close();
            if (fake != null) {
                fake.close();
            }
            if (session.printerLent) {
                // On a stopped run too: the connector has closed the port.
                printerRow(ctx, "stopped", "the test session has ended; nothing holds it");
            }
            if (session.heldPumpPort) {
                // Back from the session for the report and Cleanup's day close, while Petro still runs.
                PetroPorts.hold(ctx, step);
            }
        }
    }

    // -- the cases -----------------------------------------------------------------------------

    /**
     * A case read before the session starts: its node id (the file's, with an Outline row's id in brackets), its
     * Examples row id ("" for none), its actions, or why it could not be read.
     */
    private record Loaded(CaseEntry entry, String nodeid, String param, RunnableCase kase, Exception error) {
    }

    /**
     * The run's cases: those run start resolved, kept to the targets run start left in {@link LaunchConfig#suites}
     * (the whole plan, or a partial selection), Not Run ones included; else, with nothing resolved at run start (a
     * run with no plan), the step's own source.
     */
    private List<CaseEntry> runEntries(RunContext ctx) throws IOException {
        List<CaseEntry> resolved = ctx.caseEntries();
        if (resolved == null) {
            return cases.cases(ctx.config());
        }
        Set<String> targets = new java.util.HashSet<>(ctx.config().suites());
        List<CaseEntry> kept = new ArrayList<>();
        for (CaseEntry entry : resolved) {
            if (!entry.runnable() || targets.contains(entry.nodeid())) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private boolean runCases(RunContext ctx, StepEmitter step, FakePos fake, PinpadDouble pinpad, Session session)
            throws IOException {
        List<CaseEntry> toRun = new ArrayList<>();
        boolean partial = ctx.config().partial();
        for (CaseEntry entry : runEntries(ctx)) {
            if (entry.runnable()) {
                toRun.add(entry);
            } else if (!partial) {
                step.log("NOT RUN  " + entry.key() + " — the plan lists it with no migrated test to run", "WARNING");
            }
        }
        if (toRun.isEmpty()) {
            step.fail("no case of the plan has a migrated test to run");
            endSession(ctx, Outcome.FAILED, 0, 0);
            return true;
        }
        // Every case is read up front, as pytest collects: the session's listeners start for what the cases declare.
        // An Outline with Examples is one case per row, as pytest-bdd parametrises it.
        List<Loaded> loaded = new ArrayList<>();
        Set<FixtureName> wanted = EnumSet.noneOf(FixtureName.class);
        for (CaseEntry entry : toRun) {
            try {
                for (PatsCaseLoader.Variant variant : PatsCaseLoader.loadAll(entry.file())) {
                    String nodeid = variant.id().isEmpty() ? entry.nodeid() : entry.nodeid() + "[" + variant.id() + "]";
                    wanted.addAll(FixtureRequirements.fixturesFor(variant.kase()));
                    loaded.add(new Loaded(entry, nodeid, variant.id(), variant.kase(), null));
                }
            } catch (IOException | RuntimeException e) {
                loaded.add(new Loaded(entry, entry.nodeid(), "", null, e));
            }
        }
        // The Python's PETROTEST_FULL_PLAN_RUN: a @plan_end case is judged only in a whole, in-order run on a rig.
        boolean fullPlan = PlanStart.isFullPlanRun(ctx.config(), ctx.planCases());

        // The handoffs: the printer's port and PATS's pump port are the session's from here. CAT's setup hold of the
        // printer first, so the connector can open the port.
        PrinterHold.release(ctx, true);
        session.printerLent = lendPrinter(ctx);
        session.heldPumpPort = PetroPorts.release(ctx, PetroPumps.PATS_FUEL_PORT);
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        session.petro = PetroSession.start(rig, ctx.config().fakePos(), wanted, forecourt,
                (level, message) -> step.log(message, level));

        RunServices base = new RunServices(ctx.config(), ctx.paths(), ctx.runId(), ctx.runFolder(),
                ctx.rig(), null, null, null, pinpad, fake == null ? null : fake.url(), null, null,
                session.petro.dcr(), session.petro.fuelPumps());
        RunServices services = connector.connect(base);
        if (registries instanceof StandardRegistry standard) {
            // The Python's fepsim_buypass_env: FEPSIM v2's Buypass listener, when it serves Buypass this run.
            standard.buypassV2(StartSimulatorsStep.fepsimBuypass(ctx).orElse(null));
        }
        RegistryFactory.Built built = registries.build(services);
        step.log("fixtures this run provides: " + declaredFixtures(services), "INFO");
        RecoveryLadder ladder = services.recovery() == null ? null
                : new RecoveryLadder(services.recovery(), new Capturer(), ctx.runId(),
                        new RunSummary(ctx.runId()), null);
        // The POS driver's evidence sink, pointed at each case's folder while it runs (the Python's pos_artifacts).
        CaseArtifacts evidence = connector instanceof CaseEvidence source && services.pos() != null
                ? source.caseArtifacts() : null;

        int total = loaded.size();
        ctx.emit(new CatEvent.TestCollected(total));
        RunState state = new RunState();
        int passed = 0;
        int failed = 0;
        int finished = 0;
        // validate.plugin's _FAILED_SO_FAR: the cases that failed in setup or call so far this session.
        int earlierFailures = 0;
        String drained = "";
        for (Loaded one : loaded) {
            CaseEntry entry = one.entry();
            pauseBeforeCase(ctx, one.nodeid());
            CaseVerdict verdict = runCase(ctx, one, built, session.petro, state, ladder, earlierFailures, fullPlan,
                    services.pos(), evidence);
            finished++;
            if (verdict.passed()) {
                passed++;
            } else if (verdict.failed()) {
                failed++;
            }
            if (verdict.countsAsFailure()) {
                earlierFailures++;
            }
            step.progress(entry.key(), finished, total);
            if (!verdict.drainReason().isEmpty()) {
                drained = verdict.drainReason();
                break;
            }
        }

        if (!drained.isEmpty()) {
            step.fail("stopped after " + finished + "/" + total + " tests: " + drained);
            endSession(ctx, Outcome.FAILED, passed, failed);
            return true;
        }
        step.setDetail(finished + "/" + total + " tests completed"
                + (failed > 0 ? ", " + failed + " failed" : ""));
        endSession(ctx, passed > 0 && failed == 0 ? Outcome.PASSED : Outcome.FAILED, passed, failed);
        return true;
    }

    /** The reason a {@code @plan_end} case is skipped outside a full, in-order run: the Python plugin's words. */
    static final String PLAN_END_SKIP = "plan_end: judged only inside a full, in-order CAT run of its plan "
            + "($PETROTEST_FULL_PLAN_RUN is not set)";

    /**
     * One case, from its start to its verdict, with its events, its folder, any recovery and the pump teardown.
     *
     * @param fullPlan whether this run is the whole plan in plan order on a rig ({@link PlanStart#isFullPlanRun})
     * @param pos      the run's POS driver, or null
     * @param evidence the driver's evidence sink, or null when failed validations capture nothing
     */
    private CaseVerdict runCase(RunContext ctx, Loaded one, RegistryFactory.Built built, PetroSession petro,
            RunState state, RecoveryLadder ladder, int earlierFailures, boolean fullPlan,
            com.verifone.cat.pos.driver.PosDriver pos, CaseArtifacts evidence) {
        CaseEntry entry = one.entry();
        String nodeid = one.nodeid();
        Path folder = ctx.runFolder().resolve(caseFolder(entry.key(), one.param()));
        CaseRecord record = new CaseRecord(nodeid, entry.key(), folder, ctx.clock().instant());
        ctx.emit(new CatEvent.TestStarted(nodeid));
        long started = System.nanoTime();

        // The fixtures the case's test declares are the ones its actions need (the Python generates the test with
        // them); one the run cannot provide errors the case at setup, as a pytest fixture does.
        Set<FixtureName> declared = EnumSet.noneOf(FixtureName.class);
        if (one.kase() != null) {
            declared.addAll(FixtureRequirements.fixturesFor(one.kase()));
        }
        Attempt attempt;
        boolean planEnd = planEnd(entry.file());
        if (planEnd && !fullPlan && one.kase() != null) {
            // validate.plugin's pytest_runtest_setup: before any fixture, so the skip touches nothing on the rig.
            for (RunnableAction later : one.kase().actions()) {
                record.addUnreachedStep(later.index() + 1, "", later.describe());
            }
            attempt = Attempt.skipped(PLAN_END_SKIP);
        } else {
            if (evidence != null) {
                evidence.pointAt(folder);
            }
            try {
                attempt = execute(ctx, record, one, built, declared, petro, state,
                        evidence == null ? null : label -> pos.capture(label));
            } finally {
                if (evidence != null) {
                    evidence.restore();
                }
            }
        }
        if (attempt.validationOnly() && earlierFailures > 0 && planEnd) {
            attempt = Attempt.untrusted(attempt, earlierFailures);
        }
        record.finish(attempt.outcome(), attempt.detail(), "", ctx.clock().instant());
        writeStepsLog(record, folder);

        String drained = "";
        if (ladder != null && attempt.recoverable()) {
            drained = recover(ctx, ladder, nodeid, attempt);
        }
        // The fuel_pumps fixture's teardown: after the ladder, as pytest tears fixtures down after its report hook.
        if (!attempt.skipped()) {
            // A skipped case set no fixture up, so there is nothing to tear down.
            PetroSession.DueFuel dueFuel = built.handlers() != null && declared.contains(FixtureName.POS)
                    ? PetroSession.DueFuel.over(built.handlers().pos()) : null;
            petro.afterCase(declared, attempt.callFailed(), dueFuel);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        ctx.emit(new CatEvent.TestFinished(nodeid, attempt.testOutcome(), seconds, 0, attempt.excType(),
                truncate(attempt.detail())));
        ctx.countTest(attempt.testOutcome(), 0);
        return new CaseVerdict(attempt.passed(), attempt.failedOrError(), attempt.countsAsFailure(), drained);
    }

    /**
     * The case's folder under the run's: its key, with an Outline row's id added ({@code PETROCORE-61338-1-2}), as the
     * Python's {@code case_folder_name} names a parametrised test's.
     */
    static String caseFolder(String key, String param) {
        if (param == null || param.isEmpty()) {
            return key;
        }
        StringBuilder safe = new StringBuilder();
        param.codePoints().forEach(c -> safe.appendCodePoint(
                Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_' ? c : '_'));
        String cleaned = safe.toString().replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? key : key + "-" + cleaned;
    }

    /** Whether the case's file carries {@code @plan_end}, on its feature or its scenario (pytest's marker). */
    static boolean planEnd(Path file) {
        if (file == null) {
            return false;
        }
        try {
            return PartialRun.fileTags(file).stream().anyMatch(tags -> tags.contains("@plan_end"));
        } catch (RuntimeException e) {
            return false;
        }
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
    private static Attempt execute(RunContext ctx, CaseRecord record, Loaded one, RegistryFactory.Built built,
            Set<FixtureName> declared, PetroSession petro, RunState state, EvidenceSink evidence) {
        CaseEntry entry = one.entry();
        RunnableCase kase = one.kase();
        if (kase == null) {
            Exception e = one.error();
            return Attempt.error("could not read the case: " + e.getMessage(), e);
        }
        record.setTitle(kase.title());
        List<RunnableAction> actions = kase.actions();
        // The fixtures' setup, before the first action, as pytest sets up the fixtures a test declares: one the run
        // cannot give (no pos-shim on a fake-POS run, no pinpad, no Commander) errors the case, naming it.
        try {
            for (FixtureName name : declared) {
                built.fixtures().fixture(name);
            }
            petro.beginCase(declared);
        } catch (RuntimeException e) {
            for (RunnableAction later : actions) {
                record.addUnreachedStep(later.index() + 1, "", later.describe());
            }
            return Attempt.setupFailed(e);
        }
        CaseRun run = new CaseRun(kase, record, built.registry(), built.fixtures(), declared, state)
                .withBaselineDir(entry.file().getParent())
                .withUpdate(ctx.config().snapshotUpdate());
        if (evidence != null) {
            run.withEvidence(evidence);
        }
        String nodeid = one.nodeid();
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
                } catch (AssertionError e) {
                    // Playwright's expect() fails with an Error; the Python's except Exception catches its counterpart.
                    stop = new PatsStepError(e.getMessage(), e);
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
        } catch (AssertionError e) {
            return Attempt.stepFailed(new PatsStepError(e.getMessage(), e));
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

    /**
     * What each Secondary FEP's FEPSIM listener heard during the session, one line each (the Python's
     * {@code fep_exchange_lines}): a passing fleet sale whose card went to Buypass instead would otherwise look the same.
     * Never fails the step.
     */
    private static void fepExchanges(RunContext ctx, StepEmitter step) {
        List<String> lines;
        try {
            lines = StartSimulatorsStep.fepExchangeLines(ctx);
        } catch (RuntimeException e) {
            LOG.warn("the Secondary FEPs' exchanges could not be read: {}", e.getMessage());
            return;
        }
        for (String line : lines) {
            step.log(line, line.startsWith("WARN") ? "WARNING" : "INFO");
        }
    }

    /** The run's own {@code RunFinished}, with the counts of the cases this step ran. */
    private static void endSession(RunContext ctx, Outcome outcome, int passed, int failed) {
        double elapsed = Math.max(0.0, Duration.between(ctx.startedAt(), ctx.clock().instant()).toMillis() / 1000.0);
        ctx.emit(new CatEvent.RunFinished(ctx.runId(), outcome, passed, failed, 0, List.of(), false, elapsed, 0));
        ctx.sessionFinished(true);
    }

    // -- fixtures and banners --------------------------------------------------------------------

    /**
     * The fixtures a run provides when its registry factory built no handler fixtures ({@link RegistryFactory#build}'s
     * default, and {@link StandardRegistry} with no POS driver): each from the services, an absent one failing the
     * action as unavailable. {@code FUEL_PUMPS} is the POS UI family's view of the pumps, which only the handler
     * fixtures build, so it is unavailable here.
     */
    static FixtureSource fixtureSource(RunServices services) {
        return name -> switch (name) {
            case POS -> present(services.pos(), "pos", "no POS driver is connected to this run");
            // The POS UI's SLEEP and dialogs read the pinpad as a PinpadView: its settle is the pinpad's own, and the
            // device's keys and prompts are reached through the devices family's PinpadDevice, not this fixture.
            case PINPAD -> {
                present(services.pinpad(), "pinpad", "no pinpad is connected to this run");
                yield new PinpadView() {
                };
            }
            case COMMANDER_CONFIG -> present(services.commander(), "commander_config",
                    "no Commander PDK shim is connected to this run");
            case LEGACY_RMI -> present(services.posShim(), "legacy_rmi", "no pos-shim is connected to this run");
            case DCR -> present(services.dcr(), "dcr", "the session has no Petro DCR listener");
            case FUEL_PUMPS -> throw new DeviceUnavailable("fuel_pumps",
                    "the run's handler fixtures are not built (no POS driver), so the pumps' view is not either");
            case CONFIGURATIONS -> PetroappsFiles.fromEnvironment(System.getenv()).configurations();
        };
    }

    /** The fixtures this run really provides, from what is wired into its services: logged at the session's start. */
    static Set<FixtureName> declaredFixtures(RunServices services) {
        Set<FixtureName> declared = EnumSet.of(FixtureName.CONFIGURATIONS);
        if (services.pos() != null) {
            declared.add(FixtureName.POS);
        }
        if (services.pinpad() != null) {
            declared.add(FixtureName.PINPAD);
        }
        if (services.commander() != null) {
            declared.add(FixtureName.COMMANDER_CONFIG);
        }
        if (services.posShim() != null && (services.rig() == null || services.rig().rig().posShim())) {
            declared.add(FixtureName.LEGACY_RMI);
        }
        if (services.dcr() != null) {
            declared.add(FixtureName.DCR);
        }
        if (services.fuelPumps() != null && services.pos() != null) {
            declared.add(FixtureName.FUEL_PUMPS);
        }
        return declared;
    }

    // -- the printer's port --------------------------------------------------------------------

    /** The primary register's {@code printer_port}, or null: the one port the session holds for the register. */
    static String printerPort(RunContext ctx) {
        if (ctx.rig() == null) {
            return null;
        }
        String port = ctx.rig().rig().primaryRegister().printerPort();
        return port == null || port.isBlank() ? null : port;
    }

    /**
     * Says the printer's port is now the session's (the Python's {@code lend_to_tests}). False, and no row, when the
     * session holds no printer: under {@code --fake-pos}, or on a rig with no {@code printer_port}.
     */
    private static boolean lendPrinter(RunContext ctx) {
        if (printerPort(ctx) == null || ctx.config().fakePos()) {
            return false;
        }
        printerRow(ctx, "started", "lent to the test session, which opens it at its start");
        return true;
    }

    /** The Receipt Printer's row: one {@code SimulatorState}, as step 3 sends for each simulator. */
    private static void printerRow(RunContext ctx, String state, String detail) {
        String port = printerPort(ctx);
        ctx.emit(new CatEvent.SimulatorState(TopazPrinter.PRINTER_NAME, state, "supervised", "CAT",
                port == null ? "" : port, detail));
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

    /**
     * One case's verdict: whether it passed, whether it failed or errored (the step's {@code N failed}: an expected
     * failure and a skip are neither), whether it counts towards the session's earlier failures, and the drain reason
     * ("" when recovery did not drain the rig).
     */
    private record CaseVerdict(boolean passed, boolean failed, boolean countsAsFailure, String drainReason) {

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
        /** The pytest phase that failed, as the recovery reason names it: {@code call} or {@code setup}. */
        private final String phase;

        private Attempt(String outcome, String detail, String excType, boolean recoverable, String phase) {
            this.outcome = outcome;
            this.detail = detail;
            this.excType = excType;
            this.recoverable = recoverable;
            this.phase = phase;
        }

        static Attempt ok() {
            return new Attempt("passed", "", "", false, "call");
        }

        static Attempt validationsFailed(ValidationsFailed failed) {
            return new Attempt("failed", failed.getMessage(), failed.getClass().getSimpleName(), false, "call");
        }

        /** A {@code @plan_end} case's failed validations after an earlier failure: pytest's {@code xfail}. */
        static Attempt untrusted(Attempt failed, int earlierFailures) {
            return new Attempt("xfailed", "plan_end verdict untrusted: " + earlierFailures
                    + " earlier case(s) failed\n" + failed.detail(), "XFailed", false, "call");
        }

        /** Whether every action ran and only validations failed (the Python's {@code AssertionError}). */
        boolean validationOnly() {
            return "failed".equals(outcome) && !recoverable && "call".equals(phase);
        }

        boolean xfailed() {
            return "xfailed".equals(outcome);
        }

        /** A {@code @plan_end} case outside a full plan run: pytest's {@code skip} at setup. */
        static Attempt skipped(String reason) {
            return new Attempt("skipped", reason, "Skipped", false, "setup");
        }

        boolean skipped() {
            return "skipped".equals(outcome);
        }

        /** Failed or errored: what the step's {@code N failed} counts (the Python's failed, error, timeout). */
        boolean failedOrError() {
            return "failed".equals(outcome) || "error".equals(outcome);
        }

        /** pytest's {@code report.failed} in setup or call, which {@code earlier_failures} counts. */
        boolean countsAsFailure() {
            return "failed".equals(outcome) || ("error".equals(outcome) && "setup".equals(phase));
        }

        static Attempt stepFailed(RuntimeException cause) {
            String type = cause.getClass().getSimpleName();
            String message = cause instanceof PatsStepError ? cause.getMessage() : type + ": " + cause.getMessage();
            return new Attempt("failed", message, type, true, "call");
        }

        /** A fixture's setup failed before the first action: a pytest setup error, which the ladder walks. */
        static Attempt setupFailed(RuntimeException cause) {
            String type = cause.getClass().getSimpleName();
            return new Attempt("error", type + ": " + cause.getMessage(), type, true, "setup");
        }

        static Attempt error(String message, Exception cause) {
            return new Attempt("error", message, cause.getClass().getSimpleName(), false, "collect");
        }

        /**
         * Whether the case failed in its actions, before it finished them (the Python's {@code _CALL_FAILED}): not a
         * pass, not a validation-only failure, not a setup or read error. The pump teardown runs only then.
         */
        boolean callFailed() {
            return recoverable && "call".equals(phase);
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
            return phase + " raised " + excType + ": " + detail;
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
                // pytest reports an xfail as skipped, with wasxfail; the plugin names it XFailed.
                case "xfailed", "skipped" -> Outcome.SKIPPED;
                default -> Outcome.ERROR;
            };
        }
    }
}
