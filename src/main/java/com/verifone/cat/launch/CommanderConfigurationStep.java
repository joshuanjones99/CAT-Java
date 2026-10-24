package com.verifone.cat.launch;

import java.util.Map;
import java.util.function.Function;

import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.Say;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;

/**
 * Step 4, Commander Configuration: the Python CAT's {@code cat.launch.steps.commander._step_commander}, in
 * its order, with what CAT-Java has ported run in-process and the rest reported unavailable at the point
 * the Python would run it. Unavailable is a failure: the step never reports success for work it did not do.
 *
 * <p>The order, and what each part is here:
 * <ol>
 *   <li>The Commander build download and install ({@code install_commander_build}): with upgrade mode
 *       {@code skip} and no build chosen (CAT-Java carries no Downloads section) there is nothing to do;
 *       any other mode is unavailable.</li>
 *   <li>On a rig: JaCoCo coverage when asked (unavailable: its launch wiring is not ported); the pos-shim
 *       jar build (the Python CAT's, so a row says so); {@code provision} (ported, the {@code provision}
 *       package); the printer-port hold (unavailable, a warning, as the Python's hold is best effort);
 *       {@code commander-prep} (ported).</li>
 *   <li>{@code commander-init} (ported, every run: CAT-Java has only the {@code dev} execution mode), with
 *       {@code --no-forecourt} when Petro Simulator Suite is not selected. The Commander snapshot restore it
 *       starts with is not ported: a warning row says so.</li>
 *   <li>Commander Connect, the cloud tunnel and C-Site: not carried by {@link LaunchConfig}, so not asked
 *       for, as in a Python run without {@code cc_onboard}.</li>
 *   <li>Then the Python runs, on every rig run, the Secondary FEPs, the pinpad clear, the payment links,
 *       the EMV tables and keys, the register app, the day close and the pinpad link. None is ported, so a
 *       rig run ends unavailable here ({@link #MISSING_AFTER_INIT}).</li>
 *   <li>{@code payment-ready} (ported; a WARN, never a failure, as in the Python), then the installed-build
 *       read (a warning: not ported). The snapshot capture follows only a fresh install, which is
 *       unavailable already.</li>
 * </ol>
 *
 * <p>A fake-POS run touches no device: everything is skipped, and the step is {@code skipped}, as the
 * Python's. Every non-fake run is treated as the Python's {@code provision_register=True}, which each of the
 * Python's real entry points (Config tab, web form, {@code run_plan}) sets.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no stage has run against a rig from CAT-Java.
 */
final class CommanderConfigurationStep implements LaunchStep {

    static final String PYTHON_MODULE = "src/cat/launch/steps/commander.py";

    /** What the Python step runs after the inits, on every rig run, that CAT-Java does not have. */
    static final String MISSING_AFTER_INIT = "the Secondary FEPs (petrotest secondary-fep), the pinpad clear, the "
            + "payment links (petrotest.commander_links), the EMV tables and keys (petrotest emv-keys), the "
            + "register app, the day close (cat/launch/steps/cleanup.py _close_day) and the pinpad link "
            + "(cat/launch/steps/simulators.py _link_pinpad_simulator), which step 4 runs after commander-init";

    /** The checklist rows, as the Python's {@code STAGE_LABELS}. */
    static final Map<String, String> STAGE_LABELS = Map.of(
            "build", "Download the Commander build",
            "provision", "Provision the register (pos-shim, browser, CDP relay)",
            "commander-prep", "Commander prep (licence dates, gemreg marker)",
            "commander-init", "Commander init (base import, fuel and DCR inits)",
            "payment-ready", "EPS ready for card sales",
            "coverage", "JaCoCo coverage");

    static final String POS_SHIM_BUILD_ROW = "pos-shim build";
    static final String PRINTER_ROW = "Receipt printer port";
    static final String SNAPSHOT_ROW = "Commander snapshot restore";
    static final String INSTALLED_BUILD_ROW = "Installed build";

    private final CommanderStages stages;
    private final Map<String, String> environ;

    /** The production step: the real stages, the process environment. */
    CommanderConfigurationStep() {
        this(CommanderStages.real(System.getenv()), System.getenv());
    }

    CommanderConfigurationStep(CommanderStages stages, Map<String, String> environ) {
        this.stages = stages;
        this.environ = environ == null ? Map.of() : environ;
    }

    @Override
    public String name() {
        return LaunchStepNames.COMMANDER;
    }

    static String label(String command) {
        return STAGE_LABELS.getOrDefault(command, command);
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        LaunchConfig config = ctx.config();

        // 1. The Commander build. CAT-Java has no Downloads section, so no build is ever chosen.
        if (config.upgradeMode() != UpgradeMode.SKIP) {
            return step.unavailable(PYTHON_MODULE, "the Commander build install for upgrade mode '"
                    + config.upgradeMode().wire() + "' (cat.launch.upgrade.install_commander_build: petrotest build, "
                    + "upgrade and psi-init)");
        }
        step.skip(label("build"), "no Commander Build chosen; upgrade mode is skip");

        if (config.fakePos()) {
            step.skip(label("provision"), "--fake-pos never touches real hardware");
            if (config.coverage()) {
                step.skip(label("coverage"), "this run does not touch the rig's devices");
            }
            step.skip("Commander configuration", "using the existing build, no upgrade");
            return step.skipStep("existing build, upgrade mode 'skip', provision not requested");
        }

        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        if (rig == null) {
            return step.fail("the rig file did not load, so the Commander cannot be configured");
        }

        // 2. The register, then the Commander's license nags.
        if (config.coverage()) {
            return step.unavailable(PYTHON_MODULE, "JaCoCo coverage's launch wiring (cat.launch.coverage."
                    + "prepare_coverage), which runs before provision when the Config tab asks for coverage");
        }
        step.skip(POS_SHIM_BUILD_ROW, "built by the Python CAT (tools/build_pos_shim.py); provision reports a "
                + "missing or partial payload");
        if (!stage(step, "provision", say -> stages.provision(rig, say))) {
            return false;
        }
        Rig.Register primary = rig.primaryRegister();
        if (primary != null && primary.printerPort() != null && !primary.printerPort().isBlank()) {
            step.warn(PRINTER_ROW, primary.printerPort() + " not held during setup: unavailable in CAT-Java (needs "
                    + "petrotest.devices.topaz_printer.TopazPrinter); the register's first receipt after a POS "
                    + "restart may be dropped");
        }
        if (!stage(step, "commander-prep", say -> stages.commanderPrep(rig, say))) {
            return false;
        }

        // 3. The base import and the inits, every run (dev is CAT-Java's only execution mode).
        boolean forecourt = config.simulators().contains(StartSimulatorsStep.PETRO_SIMULATOR);
        if (!forecourt) {
            step.skip("fuel and DCR driver inits", StartSimulatorsStep.PETRO_SIMULATOR + " is not selected");
        }
        if (!CommanderInit.snapshotDisabled(environ)) {
            step.warn(SNAPSHOT_ROW, CommanderInit.SNAPSHOT_UNAVAILABLE + "; a setting a past plan left on stays on");
        }
        if (!stage(step, "commander-init", say -> stages.commanderInit(rig, forecourt, say))) {
            return false;
        }

        // 4. What the Python runs next on every rig run, which CAT-Java does not have.
        String missing = stages.missingAfterInit();
        if (!missing.isEmpty()) {
            return step.unavailable(PYTHON_MODULE, missing);
        }

        // 5. The EPS ready for card sales: a WARN, never a failure.
        StepSay ready = new StepSay(step);
        String label = label("payment-ready");
        step.substep(label, SubstepOutcome.RUNNING, "", 0.0);
        long started = System.nanoTime();
        boolean isReady;
        try {
            isReady = stages.paymentReady(rig, ready);
        } catch (RuntimeException e) {
            ready.err(e.getClass().getSimpleName() + ": " + e.getMessage());
            isReady = false;
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        if (isReady) {
            step.substep(label, SubstepOutcome.PASSED, "", elapsed);
        } else {
            step.substep(label, SubstepOutcome.WARNED, ready.lastErr(), elapsed);
            step.log("WARN  the EPS was not ready for card sales; see payment-ready above", "WARNING");
        }

        step.warn(INSTALLED_BUILD_ROW, "unknown — unavailable in CAT-Java (needs petrotest.build.installed); the "
                + "Xray execution is titled with the rig's name");
        return true;
    }

    /**
     * One ported stage as one timed checklist row, as the Python's {@code run_petrotest} makes each child one:
     * a failure fails the step with the stage's last error line.
     */
    private static boolean stage(StepEmitter step, String command, Function<Say, Boolean> body) {
        String label = label(command);
        step.log("CAT-Java " + command, "INFO");
        step.substep(label, SubstepOutcome.RUNNING, "", 0.0);
        StepSay say = new StepSay(step);
        long started = System.nanoTime();
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(body.apply(say));
        } catch (RuntimeException e) {
            say.err(e.getClass().getSimpleName() + ": " + e.getMessage());
            ok = false;
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        if (ok) {
            step.substep(label, SubstepOutcome.PASSED, "", elapsed);
            return true;
        }
        String why = say.lastErr().isEmpty() ? command + " failed" : say.lastErr();
        step.substep(label, SubstepOutcome.FAILED, why, elapsed);
        return step.fail(why);
    }

    /** A stage's lines into the step's log; its last error line is kept for the step's reason. */
    static final class StepSay implements Say {

        private final StepEmitter step;
        private String lastErr = "";

        StepSay(StepEmitter step) {
            this.step = step;
        }

        @Override
        public void out(String line) {
            for (String one : line.split("\n", -1)) {
                step.log(one, "INFO");
            }
        }

        @Override
        public void err(String line) {
            for (String one : line.split("\n", -1)) {
                step.log(one, "WARNING");
                if (!one.isBlank()) {
                    lastErr = one;
                }
            }
        }

        String lastErr() {
            return lastErr;
        }
    }
}
