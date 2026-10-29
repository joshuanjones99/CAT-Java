package com.verifone.cat.launch;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.verifone.cat.build.Installed;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.upgrade.StepResult;

/**
 * {@code cat-java install-resume --rig R [--build-dir DIR] [--dist D] [--no-forecourt] [--build-label L]
 * [--stop-after-pairing]}: carries a PSI install on from its root-access push, for a Commander that came back after the
 * install (it answers CGILink with the PSI password) without root SSH (CAT-Java HANDOFF §6 item 1a). Neither CAT had
 * this; the order is step 4's after a new install (HANDOFF §6 item 1).
 *
 * <ol>
 *   <li>The root-access packages pushed to each device until root answers with the no-pw key, then the install's
 *       Commander tail (setenforce, the bypass switch, a reboot, SSH and CGILink back): {@link RootAccessResume}, over
 *       {@code upgrade.RootAccess.pushUntilConfirmed} ({@link #PRODUCTION_RESUME}: see its note).</li>
 *   <li>{@code psi-init --no-pairing} (the Commander password PSI left), {@code provision}, {@code psi-init} with
 *       pairing, {@code provision} again (pairing reboots the register).</li>
 *   <li>Unless {@code --stop-after-pairing}: the rest of step 4 that runs outside a launch, in its order:
 *       {@code commander-prep}, {@code commander-init} ({@code --no-forecourt} when Petro Simulator Suite is not
 *       running), {@code secondary-fep none}, {@code payment-links}, {@code emv-keys --tables always},
 *       {@code register-app}, {@code close-day}, {@code payment-ready} (a WARN), the Commander snapshot capture
 *       ({@code --if-missing --twice}, with {@code --build-label}; a WARN) and the installed-build read.</li>
 * </ol>
 *
 * <p>Not here, because they belong to a run: the pinpad link (it needs the run's Petro Simulator Suite), the
 * printer-port hold and the BUYPASS Host restart after a FEP drift fix (a WARN line asks for it by hand). After a
 * resume, {@code launch --upgrade skip} runs the plan on the rig as an install would have left it.
 *
 * <p>Exit codes: 0 every stage passed, 1 a stage failed (the chain stops there), 2 usage or a rig that does not load.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): designed from HANDOFF §6 item 1 while the lab Commander was down; run
 * only against recording stages.
 */
public final class InstallResume {

    /** The install's root-access push and Commander tail, from where an interrupted install stopped. */
    @FunctionalInterface
    public interface RootAccessResume {
        /** @return one result per step, in order; the last is the failure, if there was one */
        List<StepResult> resume(Rig rig, Path buildDir, Map<String, String> environ, Consumer<String> log);
    }

    /**
     * The production resume. {@code upgrade.RootAccess} and {@code NewInstall}'s SSH and CGILink waits are package-
     * private to the upgrade slice, so until it exposes {@code NewInstall.resumeRootAccess} (the orchestrator's hook)
     * this says so and the chain stops before anything is sent.
     */
    public static final RootAccessResume PRODUCTION_RESUME = (rig, buildDir, environ, log) -> {
        throw new StageUnavailable("src/petrotest/upgrade/new_install.py",
                "NewInstall.resumeRootAccess (upgrade: RootAccess.pushUntilConfirmed per device, the SSH waits and the "
                        + "Commander tail, public)");
    };

    static final RigCommand.Spec SPEC = new RigCommand.Spec("install-resume",
            "usage: cat-java install-resume --rig <name|file.yaml> [--build-dir DIR] [--dist D] [--no-forecourt] "
                    + "[--build-label L] [--stop-after-pairing]",
            Map.of("--build-dir", "", "--dist", LaunchConfig.DEFAULT_DIST, "--build-label", ""), Set.of(),
            Set.of("--no-forecourt", "--stop-after-pairing"), Set.of(), List.of());

    private final RigCommand.Loader loader;
    private final Function<Map<String, String>, CommanderStages> stages;
    private final RootAccessResume resume;

    /** The production command: the real stages. */
    public InstallResume() {
        this(RigCommand.Loader.RIGS, CommanderStages::real, PRODUCTION_RESUME);
    }

    InstallResume(RigCommand.Loader loader, Function<Map<String, String>, CommanderStages> stages,
            RootAccessResume resume) {
        this.loader = loader;
        this.stages = stages;
        this.resume = resume;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, (rig, args, environ, say) ->
                RigCommand.exit(resume(args.rig(), rig, args, stages.apply(environ), environ, say)));
    }

    /** The chain; false at the first stage that failed, which is said last on the error stream. */
    boolean resume(String rigName, Rig rig, RigCommand.Args args, CommanderStages stages, Map<String, String> environ,
            Say say) {
        String dist = args.value("--dist");
        Path buildDir;
        String given = args.value("--build-dir");
        if (!given.isBlank()) {
            buildDir = Path.of(given);
        } else {
            Optional<Path> newest = stages.newestBuild(dist);
            if (newest.isEmpty()) {
                say.err("install-resume: no build under " + stages.buildsRoot() + " holds " + dist
                        + " with the PSI files; name one with --build-dir");
                return false;
            }
            buildDir = newest.get();
        }
        heading(say, "root access (" + buildDir + ")");
        List<StepResult> pushed;
        try {
            pushed = resume.resume(rig, buildDir, environ, say::out);
        } catch (StageUnavailable e) {
            say.err("install-resume: " + e.getMessage());
            return false;
        }
        for (StepResult result : pushed) {
            if (result.failed()) {
                say.err("install-resume: " + result.step() + " failed: " + result.detail());
                return false;
            }
        }
        if (!stage(say, "psi-init --no-pairing", () -> stages.psiInit(rig, false, say))
                || !stage(say, "provision", () -> stages.provision(rig, say))
                || !stage(say, "psi-init", () -> stages.psiInit(rig, true, say))
                || !stage(say, "provision", () -> stages.provision(rig, say))) {
            return false;
        }
        if (args.has("--stop-after-pairing")) {
            say.out("install-resume: paired and provisioned; the rest of step 4 is the next launch's");
            return true;
        }
        boolean forecourt = !args.has("--no-forecourt");
        boolean[] driftFixed = {false};
        Say heard = new Say() {
            @Override
            public void out(String line) {
                if (line.startsWith(CommanderLinks.FEP_DRIFT_FIXED)) {
                    driftFixed[0] = true;
                }
                say.out(line);
            }

            @Override
            public void err(String line) {
                say.err(line);
            }
        };
        if (!stage(say, "commander-prep", () -> stages.commanderPrep(rig, say))
                || !stage(say, "commander-init", () -> stages.commanderInit(rig, forecourt, say))
                || !stage(say, "secondary-fep none", () -> stages.secondaryFep(rig, List.of("none"), "", Map.of(), say))
                || !stage(say, "payment-links", () -> stages.paymentLinks(rig, heard))) {
            return false;
        }
        if (driftFixed[0]) {
            say.out("WARN  " + CommanderConfigurationStep.PAYMENT_HOST + " may answer nothing after the FEP drift fix "
                    + "until it is restarted by hand");
        }
        if (!stage(say, "emv-keys --tables always", () -> stages.emvKeys(rig, "always", say))
                || !stage(say, "register-app", () -> stages.registerApp(rig, say))) {
            return false;
        }
        heading(say, "close-day");
        CloseDay.Outcome closed = stages.closeDay(rig, say);
        if (closed == CloseDay.Outcome.FAILED) {
            say.err("install-resume: close-day failed");
            return false;
        }
        if (closed == CloseDay.Outcome.FORCED_AEOD) {
            say.out("WARN  the Commander forces automatic end of day and refused the close");
        }
        heading(say, "payment-ready");
        if (!stages.paymentReady(rig, say)) {
            say.out("WARN  the EPS was not ready for card sales; see payment-ready above");
        }
        heading(say, "commander-snapshot capture");
        if (!stages.snapshotCapture(rigName, args.value("--build-label"), say)) {
            say.out("WARN  commander-snapshot capture failed; see its lines above");
        }
        try {
            Installed.Identification found = stages.installedBuild(rig, dist);
            say.out("Installed build: " + (found.name().isEmpty() ? "unknown — " + found.why() : found.why()));
        } catch (RuntimeException e) {
            say.out("Installed build: unknown — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        say.out("install-resume: step 4 done; run the plan with launch --upgrade skip");
        return true;
    }

    private static void heading(Say say, String stage) {
        say.out("install-resume: " + stage);
    }

    private static boolean stage(Say say, String name, java.util.function.BooleanSupplier body) {
        heading(say, name);
        boolean ok;
        try {
            ok = body.getAsBoolean();
        } catch (StageUnavailable e) {
            say.err("install-resume: " + name + ": " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            say.err("install-resume: " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
        if (!ok) {
            say.err("install-resume: " + name + " failed; the chain stops here");
        }
        return ok;
    }
}
