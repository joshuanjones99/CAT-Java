package com.verifone.cat.launch;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.Installed;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.devices.csite.CSiteClient;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;

/**
 * Step 4, Commander Configuration: the Python CAT's {@code cat.launch.steps.commander._step_commander}, in
 * its order, with what CAT-Java has ported run in-process and the rest reported unavailable at the point
 * the Python would run it. Unavailable is a failure: the step never reports success for work it did not do.
 *
 * <p>The order, and what each part is here:
 * <ol>
 *   <li>Before anything is installed, on a rig run: what the run asks for that CAT-Java does not have
 *       ({@link #unported}: the cloud tunnel of a run onboarding to Commander Connect) fails the step as
 *       unavailable, one failed row each. JaCoCo coverage is wired ({@link CoverageLaunch#prepare}, before
 *       {@code provision}). Deviation: the Python reaches these after the
 *       install; CAT-Java cannot do them, so it stops before a PSI install wipes both devices for a run that would
 *       end there anyway. System Checks fails the same run first.</li>
 *   <li>The Commander build download and install ({@link InstallCommanderBuild}, the Python's
 *       {@code install_commander_build}): the chosen build downloaded, the PSI install, and {@code psi-init
 *       --no-pairing}. AU and VHQ are unavailable.</li>
 *   <li>On a rig: the pos-shim jar build (the Python CAT's, so a row says so); {@code provision} (ported, the
 *       {@code provision} package); the printer-port hold ({@link PrinterHold#hold}, the Python's
 *       {@code _hold_printer_port}: idempotent when System Checks already holds it); after a PSI install,
 *       {@code psi-init} with pairing, then {@link RunContext#commanderLoginReady()} and {@code provision} again
 *       (pairing reboots the register); {@code commander-prep} (ported).</li>
 *   <li>When the run onboards to Commander Connect ({@link LaunchConfig#commanderConnect}): the cloud tunnel, before
 *       the inits (reached only once it is ported; see the first item). Without onboarding (the default) the
 *       Commander Connect row is skipped with the Python's reason.</li>
 *   <li>{@code commander-init} (ported, every run: CAT-Java has only the {@code dev} execution mode), with
 *       {@code --no-forecourt} when Petro Simulator Suite is not selected. The init restores the Commander snapshot
 *       first ({@code commander.snapshot.SnapshotRestore}; {@code $CAT_NO_SNAPSHOT} switches it off), its lines in
 *       the init's. PATS's pump port {@value PetroPumps#PATS_FUEL_PORT} is lent to it, as the Python lends it (the
 *       init may release a pump through Petro).</li>
 *   <li>Onboarding only: {@code commander-connect} ({@code ConnectService.apply}), then, with a service id and a
 *       C-Site account, {@code csite-reset} (a failure leaves its row failed, a WARN line, and the step going)
 *       and {@code cloud-snapshot restore} (not ported: unavailable). Reached only once the tunnel is ported.</li>
 *   <li>{@code secondary-fep} (the {@code fep} package), every rig run: the run's FEPs
 *       ({@link RunContext.CommanderOptions#secondaryFeps}, from {@link LaunchConfig#secondaryFeps}), or
 *       {@code none}.</li>
 *   <li>{@code pinpad-clear}, on a {@code pinpad: vats} rig only (not ported: unavailable); else a skipped row.</li>
 *   <li>{@code payment-links} ({@code commander.links}). A Buypass FEP drift it fixed
 *       ({@link CommanderLinks#FEP_DRIFT_FIXED}) leaves a CAT-started BUYPASS Host deaf: it is restarted, and the
 *       links checked again. An adopted BUYPASS Host is the operator's to restart (D9): a WARN line.</li>
 *   <li>{@code emv-keys} ({@code commander.emv.EmvKeys}), {@code --tables always} after a new install, else
 *       {@code auto}.</li>
 *   <li>{@code register-app} ({@code commander.registerapp}), every rig run.</li>
 *   <li>The day close ({@link DayClose}, shared with Cleanup; {@code closeday.CloseDay} with its register pass), with
 *       {@value PetroPumps#PATS_FUEL_PORT} lent; a day that did not close fails the step.</li>
 *   <li>The pinpad link ({@link PinpadLink}) when Petro Simulator Suite is selected or the pinpad is vats (a vats
 *       link is not ported: unavailable).</li>
 *   <li>{@code payment-ready} (ported; a WARN, never a failure, as in the Python).</li>
 *   <li>After a new install, or when asked ({@link RunContext.CommanderOptions#captureSnapshot}): the Commander
 *       snapshot capture, {@code --if-missing --twice} with the build label. Its failure leaves its row failed and the
 *       step going, with a WARN line, as the Python's.</li>
 *   <li>The installed-build read ({@code build.Installed}, bounded at {@link #IDENTIFY_BUILD_TIMEOUT}); a warning
 *       when unknown.</li>
 * </ol>
 *
 * <p>A fake-POS run touches no device: everything is skipped, and the step is {@code skipped}, as the
 * Python's. Every non-fake run is treated as the Python's {@code provision_register=True}, which each of the
 * Python's real entry points (Config tab, web form, {@code run_plan}) sets.
 *
 * <p>Deviations from the Python: no FEP simulator supersedes BUYPASS Host in CAT-Java's step 3, so {@code emv-keys}
 * never gets {@code --host-packets} and the drift restart never defers to one; a {@code csite-reset} failure is a WARN
 * line (the Python's comment says "never fails the step", while its {@code run_petrotest} marks the step failed and
 * goes on, so the Python step ends failed while returning success); a stage that is not ported throws
 * {@link StageUnavailable}, which fails the step there.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no stage has run against a rig from CAT-Java.
 */
final class CommanderConfigurationStep implements LaunchStep {

    static final String PYTHON_MODULE = "src/cat/launch/steps/commander.py";

    /** The checklist rows, as the Python's {@code STAGE_LABELS}. */
    static final Map<String, String> STAGE_LABELS = Map.ofEntries(
            Map.entry("build", "Download the Commander build"),
            Map.entry("upgrade", "Install the Commander build"),
            Map.entry("psi-init", "PSI init (Commander password, register pairing)"),
            Map.entry("provision", "Provision the register (pos-shim, browser, CDP relay)"),
            Map.entry("commander-prep", "Commander prep (licence dates, gemreg marker)"),
            Map.entry("commander-init", "Commander init (base import, fuel and DCR inits)"),
            Map.entry("commander-connect", "Commander Connect onboarding"),
            Map.entry("secondary-fep", "Secondary FEPs"),
            Map.entry("payment-links", "Payment links"),
            Map.entry("emv-keys", "EMV tables and CA keys"),
            Map.entry("close-day", DayClose.LABEL),
            Map.entry("payment-ready", "EPS ready for card sales"),
            Map.entry("coverage", "JaCoCo coverage"));

    static final String POS_SHIM_BUILD_ROW = "pos-shim build";
    static final String INSTALLED_BUILD_ROW = "Installed build";

    /** The simulator {@code payment-links}' drift fix leaves deaf: the Python's {@code PAYMENT_HOST}. */
    static final String PAYMENT_HOST = "BUYPASS Host";

    /** The skipped row when the run does not onboard, with the Python's reason for doing nothing. */
    static final String NO_ONBOARDING = "not asked for: the run does not onboard to Commander Connect";

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

    /**
     * Something a rig run asks for that CAT-Java does not have: its row, and what {@link StepEmitter#unavailable} says.
     */
    record Gap(String row, String pythonModule, String missing) {

        /** The row's detail and the step's failure: the {@link StageUnavailable} wording. */
        String detail() {
            return "unavailable in CAT-Java: needs " + missing + " (Python: " + pythonModule + ")";
        }
    }

    /**
     * What a rig run of {@code config} asks for that CAT-Java does not have, in step 4's order: JaCoCo coverage, then
     * the cloud tunnel of a run onboarding to Commander Connect (unless {@code tunnelPorted}). Empty under
     * {@code --fake-pos}, which touches no device. Step 1 and step 4 both check it, before anything is installed.
     */
    static List<Gap> unported(LaunchConfig config, boolean tunnelPorted) {
        if (config.fakePos()) {
            return List.of();
        }
        List<Gap> gaps = new java.util.ArrayList<>();
        // [coverage slice, wave 16] JaCoCo coverage is wired (launch.CoverageLaunch), so it is no longer a gap.
        if (config.commanderConnect() && !tunnelPorted) {
            gaps.add(new Gap("cloud-tunnel", CommanderStages.CLOUD_TUNNEL_MODULE, CommanderStages.CLOUD_TUNNEL_MISSING));
        }
        return List.copyOf(gaps);
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        LaunchConfig config = ctx.config();
        // 0. Before anything destructive: a run that would end unavailable after the PSI install ends here instead.
        List<Gap> gaps = unported(config, stages.cloudTunnelPorted());
        if (!gaps.isEmpty()) {
            for (Gap gap : gaps) {
                step.substep(gap.row(), SubstepOutcome.FAILED, gap.detail(), 0.0);
            }
            Gap first = gaps.get(0);
            return step.unavailable(first.pythonModule(), first.missing());
        }
        ctx.commanderReached(true);
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();

        // 1. The Commander build: download, install, and reset the password PSI left, before anything is pushed
        // to the register, because a PSI install wipes both devices.
        InstallCommanderBuild.Result installed = InstallCommanderBuild.run(ctx, step, stages, rig);
        if (!installed.ok()) {
            return false;
        }

        if (config.fakePos()) {
            step.skip(label("provision"), "--fake-pos never touches real hardware");
            if (config.coverage()) {
                step.skip(label("coverage"), "this run does not touch the rig's devices");
            }
            if (installed.ran()) {
                return true;
            }
            step.skip("Commander configuration", "using the existing build, no upgrade");
            return step.skipStep("existing build, upgrade mode 'skip', provision not requested");
        }

        if (rig == null) {
            return step.fail("the rig file did not load, so the Commander cannot be configured");
        }

        // [coverage slice, wave 16] JaCoCo coverage, when asked: before provision, because getting the agent loaded may
        // reboot a device and provision brings the register's shim back. A failure costs the run its coverage only.
        CoverageLaunch.prepare(ctx, step);

        // 2. The register, then the Commander's license nags.
        step.skip(POS_SHIM_BUILD_ROW, "built by the Python CAT (tools/build_pos_shim.py); provision reports a "
                + "missing or partial payload");
        if (!stage(step, "provision", say -> stages.provision(rig, say))) {
            return false;
        }
        // From here to the test session, be the register's printer (PATS's emulator holds the port for its whole
        // run): the register connects on its next probe, and the first sale's receipt then prints.
        PrinterHold.hold(ctx, step, stages.printerOpener());
        // PATS's -newinstall tasks: the Commander password PSI generated, and pairing. Nothing may log in as
        // manager with the lab password before this reset (2026-09-21). Pairing reboots the register, so
        // provision runs again (a stamp match) to bring the shim up on it.
        if (config.upgradeMode() == UpgradeMode.NEW_INSTALL) {
            if (!stage(step, "psi-init", say -> stages.psiInit(rig, true, say))) {
                return false;
            }
            ctx.commanderLoginReady(true);
            if (!stage(step, "provision", say -> stages.provision(rig, say))) {
                return false;
            }
        }
        if (!stage(step, "commander-prep", say -> stages.commanderPrep(rig, say))) {
            return false;
        }

        // The cloud tunnel before the inits, not just before onboarding: crefreshcfg makes coresvcs push every
        // changed dataset to the cloud, and a Commander with no WAN link floods its syslog (HANDOFF.md §1.104).
        boolean onboard = config.commanderConnect();
        if (onboard && !stage(step, "cloud-tunnel", say -> stages.cloudTunnel(rig, say))) {
            return false;
        }

        // 3. The base import and the inits, every run (dev is CAT-Java's only execution mode).
        boolean forecourt = config.simulators().contains(StartSimulatorsStep.PETRO_SIMULATOR);
        if (!forecourt) {
            step.skip("fuel and DCR driver inits", StartSimulatorsStep.PETRO_SIMULATOR + " is not selected");
        }
        // PATS's pump port to the init, as step 5 lends it to the tests: on a DCR left "not idle" the init releases
        // the pump through Petro first (the Python's held_pump_port).
        boolean heldPumpPort = PetroPorts.release(ctx, PetroPumps.PATS_FUEL_PORT);
        boolean initialised;
        try {
            initialised = stage(step, "commander-init", say -> stages.commanderInit(rig, forecourt, say));
        } finally {
            if (heldPumpPort) {
                PetroPorts.hold(ctx, step);
            }
        }
        if (!initialised) {
            return false;
        }

        // 4. Commander Connect, after the inits, whose base import would otherwise overwrite it.
        CommanderConnect cc = config.commanderConnectSettings();
        if (onboard) {
            if (!stage(step, "commander-connect", say -> stages.commanderConnect(rig, cc, say))) {
                return false;
            }
        } else {
            step.skip(label("commander-connect"), NO_ONBOARDING);
        }

        // 5. The golden cloud dataset: only when this run onboards, with a C-Site account.
        Map<String, String> csite = CSiteClient.accountEnv(cc.lab(), cc.email(), cc.password(), environ);
        boolean onboarding = onboard && !cc.serviceId().isBlank();
        String csiteUser = environ.getOrDefault(CSiteClient.ENV_VARS.get("csite.username"), "");
        if (onboarding && (!csite.isEmpty() || !csiteUser.isEmpty())) {
            warnOnly(step, "csite-reset", say -> stages.csiteReset(rig, cc.serviceId(), csite, say));
            if (!stage(step, "cloud-snapshot", say -> stages.cloudSnapshotRestore(rig, cc.serviceId(), csite, say))) {
                return false;
            }
        }

        // 6. The Secondary FEPs, every rig run: the chosen ones on, every other one off ("none" is a choice too).
        List<String> chosen = ctx.commanderOptions().secondaryFeps();
        List<String> feps = chosen.isEmpty() ? List.of("none") : chosen;
        if (!stage(step, "secondary-fep",
                say -> stages.secondaryFep(rig, feps, cc.serviceId(), csite, say))) {
            return false;
        }

        // 7. A simulated card left in the M400 holds every register login: out, on a vats rig only.
        boolean vats = rig.pinpad() == Rig.Pinpad.VATS;
        if (vats) {
            if (!stage(step, "pinpad-clear", say -> stages.pinpadClear(rig, say))) {
                return false;
            }
        } else {
            step.skip("pinpad-clear", "the rig's pinpad is " + pinpadName(rig) + ", not vats");
        }

        // 8. The payment links; a drift fix leaves a CAT-started BUYPASS Host deaf, so it is restarted.
        boolean[] driftFixed = {false};
        Consumer<String> heard = line -> {
            if (line.startsWith(CommanderLinks.FEP_DRIFT_FIXED)) {
                driftFixed[0] = true;
            }
        };
        if (!stage(step, "payment-links", heard, say -> stages.paymentLinks(rig, say))) {
            return false;
        }
        if (driftFixed[0]) {
            Boolean restarted = restartPaymentHost(ctx, step);
            if (Boolean.FALSE.equals(restarted)) {
                return false;
            }
            if (Boolean.TRUE.equals(restarted) && !stage(step, "payment-links", say -> stages.paymentLinks(rig, say))) {
                return false;
            }
        }

        // 9. PATS's table load after a new install, then the EMV CA keys, every run.
        String tables = config.upgradeMode() == UpgradeMode.NEW_INSTALL ? "always" : "auto";
        if (!stage(step, "emv-keys", say -> stages.emvKeys(rig, tables, say))) {
            return false;
        }

        // 10. Every register in the cashier POS app.
        if (!stage(step, "register-app", say -> stages.registerApp(rig, say))) {
            return false;
        }

        // 11. A fresh period for the tests, whatever the last run left; the same clause as Cleanup's.
        DayClose.Result day;
        try {
            day = DayClose.run(ctx, step, stages::closeDay, false, false);
        } catch (StageUnavailable e) {
            step.substep(DayClose.LABEL, SubstepOutcome.FAILED, e.getMessage(), 0.0);
            return step.unavailable(e.pythonModule(), e.missing());
        }
        if (!day.closed()) {
            return step.fail(day.clause());
        }

        // 12. The pinpad link: to Petro's ViperPay engine, or on a vats rig to the physical terminal.
        if (forecourt || vats) {
            if (!pinpadLink(ctx, step, rig, vats)) {
                return false;
            }
        }

        // 13. The EPS ready for card sales: a WARN, never a failure.
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

        // 14. The snapshot every later run's commander-init restores to: after a fresh install, or when asked.
        RunContext.CommanderOptions options = ctx.commanderOptions();
        if (config.upgradeMode() == UpgradeMode.NEW_INSTALL || options.captureSnapshot()) {
            boolean captured = rowOnly(step, "commander-snapshot",
                    say -> stages.snapshotCapture(config.rig(), options.buildLabel(), say));
            if (!captured) {
                step.log("WARN  commander-snapshot capture failed; see its lines above", "WARNING");
            }
        }

        // 15. The build on the Commander, for the Xray execution's title.
        identifyInstalledBuild(ctx, step, rig);
        return true;
    }

    private static String pinpadName(Rig rig) {
        return rig.pinpad() == null ? "unset" : rig.pinpad().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Bound on reading the Commander's {@code suiteinfo.dat} and the downloaded overlays' hashes. */
    static final Duration IDENTIFY_BUILD_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Names the build on the Commander ({@link RunContext#installedBuild()}), for the Xray execution's title: the
     * Python's {@code _identify_installed_build}. One row either way; never fails the step.
     */
    void identifyInstalledBuild(RunContext ctx, StepEmitter step, Rig rig) {
        LaunchConfig config = ctx.config();
        String name;
        String why;
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "identify-installed-build");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Installed.Identification found = worker.submit(() -> stages.installedBuild(rig, ctx.config().dist()))
                    .get(IDENTIFY_BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            name = found.name();
            why = found.why();
        } catch (TimeoutException e) {
            name = "";
            why = "the Commander did not answer in " + IDENTIFY_BUILD_TIMEOUT.toSeconds() + "s";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            name = "";
            why = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            name = "";
            why = "interrupted";
        } finally {
            worker.shutdownNow();
        }
        boolean installedHere = config.commanderBuildChosen() && config.upgradeMode() == UpgradeMode.NEW_INSTALL;
        if (name.isEmpty() && installedHere) {
            // This run installed it, so its name is known even when the read failed.
            name = BuildDiscovery.buildLabel(config.commanderBranch(), config.commanderBuild());
            why = name + ", installed by this run (" + why + ")";
        }
        ctx.installedBuild(name);
        if (!name.isEmpty()) {
            step.log("Installed build: " + why, "INFO");
            step.substep(INSTALLED_BUILD_ROW, SubstepOutcome.PASSED, why, 0.0);
        } else {
            String unknown = "unknown — " + why + "; the Xray execution is titled with the rig's name";
            step.log("Installed build: " + unknown, "WARNING");
            step.substep(INSTALLED_BUILD_ROW, SubstepOutcome.WARNED, unknown, 0.0);
        }
    }

    /**
     * The Python's {@code restart_payment_host}: null when there is nothing to restart (BUYPASS Host not started by
     * CAT, which is said), else whether the restart brought it back (a failure fails the step).
     */
    private Boolean restartPaymentHost(RunContext ctx, StepEmitter step) {
        boolean owned = ctx.simulators().stream()
                .anyMatch(p -> p.entry().name().equals(PAYMENT_HOST) && p.spawned());
        if (!owned) {
            step.log("WARN  " + PAYMENT_HOST + " was not started by CAT; after the FEP drift fix it may answer nothing "
                    + "until it is restarted by hand", "WARNING");
            return null;
        }
        String why = stages.restartSimulator(ctx, PAYMENT_HOST, step);
        if (!why.isEmpty()) {
            step.fail(why);
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /** The pinpad link as one timed row; its failure fails the step with the link's detail. */
    private boolean pinpadLink(RunContext ctx, StepEmitter step, Rig rig, boolean vats) {
        step.substep(PinpadLink.LABEL, SubstepOutcome.RUNNING, "", 0.0);
        long started = System.nanoTime();
        PinpadLink.Result result;
        try {
            if (vats) {
                // _link_vats_pinpad: a new Commander socket to the M400 after emv-keys; Petro is never restarted.
                result = VatsPinpadLink.forRig(rig, stages.pauser()).link(step, rig, ctx.config().rig(),
                        say -> stages.emvKeys(rig, null, say));
            } else {
                boolean started3 = ctx.simulators().stream()
                        .anyMatch(p -> p.entry().name().equals(StartSimulatorsStep.PETRO_SIMULATOR) && p.spawned());
                PinpadLink link = new PinpadLink(stages.pauser(), stages.pinpadTiming());
                result = link.link(step, ctx.config().rig(), stages.pinpadLog(rig), ctx.epsLogMark(), started3,
                        say -> stages.emvKeys(rig, null, say),
                        simulator -> stages.restartSimulator(ctx, simulator, step));
            }
        } catch (StageUnavailable e) {
            step.substep(PinpadLink.LABEL, SubstepOutcome.FAILED, e.getMessage(), elapsed(started));
            return step.unavailable(e.pythonModule(), "the pinpad link, which runs " + e.missing());
        } catch (RuntimeException e) {
            result = new PinpadLink.Result(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.linked()) {
            step.substep(PinpadLink.LABEL, SubstepOutcome.PASSED, "", elapsed(started));
            return true;
        }
        step.substep(PinpadLink.LABEL, SubstepOutcome.FAILED, result.detail(), elapsed(started));
        return step.fail(result.detail());
    }

    private static double elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    /**
     * One stage as one timed checklist row, as the Python's {@code run_petrotest} makes each child one: a failure
     * fails the step with the stage's last error line; a stage CAT-Java does not have fails it as unavailable.
     */
    static boolean stage(StepEmitter step, String command, Function<Say, Boolean> body) {
        return stage(step, command, null, body);
    }

    /** {@link #stage}, with {@code heard} told every out line (the Python's {@code on_line}). */
    static boolean stage(StepEmitter step, String command, Consumer<String> heard,
            Function<Say, Boolean> body) {
        Run run = runRow(step, command, heard, body);
        if (run.unavailable() != null) {
            return step.unavailable(run.unavailable().pythonModule(), run.unavailable().missing());
        }
        if (run.ok()) {
            return true;
        }
        return step.fail(run.why());
    }

    /**
     * One stage whose failure leaves its row failed and the step going (the Python's state-and-detail restore around
     * {@code csite-reset}'s and the snapshot capture's {@code cli}). Returns whether it passed.
     */
    private static boolean rowOnly(StepEmitter step, String command, Function<Say, Boolean> body) {
        return runRow(step, command, null, body).ok();
    }

    /** {@code csite-reset}: a failure leaves its row failed, adds a WARN line, and the step goes on. */
    private static void warnOnly(StepEmitter step, String command, Function<Say, Boolean> body) {
        Run run = runRow(step, command, null, body);
        if (!run.ok()) {
            step.log("WARN  " + command + " — " + run.why(), "WARNING");
        }
    }

    private record Run(boolean ok, String why, StageUnavailable unavailable) {}

    private static Run runRow(StepEmitter step, String command, Consumer<String> heard, Function<Say, Boolean> body) {
        String label = label(command);
        step.log("CAT-Java " + command, "INFO");
        step.substep(label, SubstepOutcome.RUNNING, "", 0.0);
        StepSay say = new StepSay(step, heard);
        long started = System.nanoTime();
        boolean ok;
        StageUnavailable unavailable = null;
        try {
            ok = Boolean.TRUE.equals(body.apply(say));
        } catch (StageUnavailable e) {
            unavailable = e;
            ok = false;
        } catch (RuntimeException e) {
            say.err(e.getClass().getSimpleName() + ": " + e.getMessage());
            ok = false;
        }
        double elapsed = elapsed(started);
        if (ok) {
            step.substep(label, SubstepOutcome.PASSED, "", elapsed);
            return new Run(true, "", null);
        }
        String why = unavailable != null ? unavailable.getMessage()
                : say.lastErr().isEmpty() ? command + " failed" : say.lastErr();
        step.substep(label, SubstepOutcome.FAILED, why, elapsed);
        return new Run(false, why, unavailable);
    }

    /** A stage's lines into the step's log; its last error line is kept for the step's reason. */
    static final class StepSay implements Say {

        private final StepEmitter step;
        private final Consumer<String> heard;
        private String lastErr = "";

        StepSay(StepEmitter step) {
            this(step, null);
        }

        StepSay(StepEmitter step, Consumer<String> heard) {
            this.step = step;
            this.heard = heard;
        }

        @Override
        public void out(String line) {
            for (String one : line.split("\n", -1)) {
                step.log(one, "INFO");
                if (heard != null) {
                    heard.accept(one);
                }
            }
        }

        @Override
        public void err(String line) {
            // A multi-line message (Playwright's "Error {\n message=...\n}") is one error: its first non-blank line
            // is the one worth the row's detail, not its closing brace (run 1a158f28d9cd read "}").
            String first = "";
            for (String one : line.split("\n", -1)) {
                step.log(one, "WARNING");
                if (first.isEmpty() && !one.isBlank()) {
                    first = one;
                }
            }
            if (!first.isEmpty()) {
                lastErr = first;
            }
        }

        String lastErr() {
            return lastErr;
        }
    }
}
