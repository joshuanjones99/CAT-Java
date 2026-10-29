package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.build.BuildException;
import com.verifone.cat.commander.Say;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimFetch;
import com.verifone.cat.sims.SimRunning;
import com.verifone.cat.sims.ToolsRoot;
import com.verifone.cat.upgrade.ClassicUpgrade;
import com.verifone.cat.upgrade.ClassicUpgradeTools;
import com.verifone.cat.upgrade.NewInstall;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.PsiInit;
import com.verifone.cat.upgrade.StepResult;

/**
 * Install Upgrade: the Downloads section's choices, carried out and nothing else. The Python's
 * {@code cat/launch/install_upgrade.py} ({@code launch_install_upgrade}) and {@code CatService.install_upgrade}:
 *
 * <ol>
 *   <li>download the Commander Build, if one is chosen;</li>
 *   <li>install it with the Upgrade mode (PSI, {@link NewInstall});</li>
 *   <li>after a PSI install, {@code psi-init --no-pairing} at once ({@link PsiInit}, the password reset): the
 *       registers are not paired, which needs {@code provision}'s browser and is a run's step 4;</li>
 *   <li>replace the staged simulators with the Simulator Build, if one is chosen ({@link SimFetch}).</li>
 * </ol>
 *
 * <p>One action at a time: the {@link RigBusy} flag is shared with the run and the Launch Simulators page, so an
 * install is refused while a run, a preflight or a simulator launch holds the rig, and they are refused while it
 * runs. A running simulator is refused before anything starts when a Simulator Build is chosen: its files cannot be
 * replaced, and finding that out after a forty-minute PSI install would leave the rig half-done.
 *
 * <p>Stop (the Python's {@code stop()}, which cancels the task) interrupts the worker and ends the install before its
 * next stage; a stage already running ends where it next waits, as the Python's cancel does at its next await.
 *
 * <p>AU runs the upgrade slice's {@code ClassicUpgrade} (stage "upgrade"; no psi-init after it). Deviations: VHQ is refused
 * before anything starts (the Python's fails saying it is not implemented). The Simulator Build is replaced in the base-57 form of the
 * registry rows (the Commander's base is not read here; a run's System Checks reads it).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). A PSI install reformats the Commander's disk and reinstalls every
 * device, so tests pass fakes for the installer, psi-init and the simulator replacement; nothing here reaches a rig.
 */
final class UpgradeService {

    /** The stages, in order, named as the Python's {@code INSTALL_STAGES}. */
    static final List<String> STAGES = List.of("download", "upgrade", "psi-init", "simulators");

    /** Runs the PSI install against the rig named. */
    @FunctionalInterface
    interface Installer {
        List<StepResult> install(String rig, NewInstallTools tools, Map<String, String> env, Consumer<String> log);
    }

    /** {@code psi-init --no-pairing} on the rig named; true when the password reset succeeded. */
    @FunctionalInterface
    interface PsiRunner {
        boolean resetPassword(String rig, Map<String, String> env, Consumer<String> log);
    }

    /** The Simulator Build's side: which of the rows it replaces are running, and the replacement itself. */
    interface SimReplacer {
        /** Row name to why it is running, for the rows the build would replace. */
        Map<String, String> running(String rig);

        List<SimFetch.FetchResult> replace(String rig, String branch, String build, Consumer<String> log);
    }

    /** The real installer: the rig file, then the PSI path over JSch and the CGILink probe. */
    static final Installer SYSTEM = (rig, tools, env, log) ->
            NewInstall.system(tools, log).run(RigLoader.load(rig, RigLoader.rigsDir(env)), env);

    /** The real psi-init: {@code PsiInit.forRig(...).run(rig, false)}, its lines to the log. */
    static final PsiRunner PSI_INIT = (rigName, env, log) -> {
        Rig rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
        Say say = new Say() {
            @Override
            public void out(String line) {
                log.accept(line);
            }

            @Override
            public void err(String line) {
                log.accept(line);
            }
        };
        return PsiInit.forRig(rig, env, say).run(rig, false);
    };

    /** An AU push of the build in {@code dir} to the rig named. */
    @FunctionalInterface
    interface ClassicInstaller {
        List<StepResult> install(String rig, Path dir, Map<String, String> env, Consumer<String> log);
    }

    /** The real AU push: {@code ClassicUpgrade} with the build's own jars (the upgrade slice's port). */
    static final ClassicInstaller CLASSIC = (rig, dir, env, log) -> ClassicUpgrade
            .system(ClassicUpgradeTools.forBuild(dir), log).run(RigLoader.load(rig, RigLoader.rigsDir(env)), env);

    private ClassicInstaller classic = CLASSIC;

    /** The AU push this service runs (tests pass a fake). */
    UpgradeService withClassic(ClassicInstaller push) {
        classic = push;
        return this;
    }

    /** One stage: its name, its status ("", running, done, skipped, failed, stopped) and a detail. */
    record Stage(String name, String status, String detail) {}

    private final DownloadsService downloads;
    private final Supplier<String> rig;
    private final RigBusy busy;
    private final Installer installer;
    private final Function<Path, NewInstallTools> toolsFor;
    private final PsiRunner psi;
    private final SimReplacer sims;
    private final Map<String, String> env;
    private final Object lock = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();

    // Guarded by lock.
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    private String notice = "";
    private String message = "";

    UpgradeService(DownloadsService downloads, Supplier<String> rig, RigBusy busy, Installer installer,
            Function<Path, NewInstallTools> toolsFor, PsiRunner psi, SimReplacer sims, Map<String, String> env) {
        this.downloads = downloads;
        this.rig = rig;
        this.busy = busy;
        this.installer = installer;
        this.toolsFor = toolsFor;
        this.psi = psi;
        this.sims = sims;
        this.env = env;
    }

    /** The real service: PSI, psi-init and the simulator replacement for the rig named with {@code --rig}. */
    static UpgradeService standard(DownloadsService downloads, Supplier<String> rig, RigBusy busy,
            Map<String, String> env) {
        return new UpgradeService(downloads, rig, busy, SYSTEM, dir -> NewInstallTools.forBuild(dir, env), PSI_INIT,
                systemSims(downloads, env), env);
    }

    /** The real Simulator Build side: every row CAT starts, plus the staged-only ones, under the tools root. */
    static SimReplacer systemSims(DownloadsService downloads, Map<String, String> env) {
        return new SimReplacer() {
            private Rig load(String rigName) {
                try {
                    return RigLoader.load(rigName, RigLoader.rigsDir(env));
                } catch (RuntimeException e) {
                    return null;
                }
            }

            private SimContext ctx(Rig loaded) {
                Path root = ToolsRoot.resolve(env.get(ToolsRoot.ENV), null);
                return SimContext.of(root, loaded == null ? "" : loaded.controller().host());
            }

            private List<SimEntry> rows() {
                List<String> used = Registry.launchable(false).stream().map(SimEntry::name).toList();
                return SimFetch.buildRows(Registry.forBase(false), used);
            }

            @Override
            public Map<String, String> running(String rigName) {
                return SimRunning.runningRows(rows(), ctx(load(rigName)), new PowerShellProcessOps());
            }

            @Override
            public List<SimFetch.FetchResult> replace(String rigName, String branch, String build,
                    Consumer<String> log) {
                return new SimFetch(downloads::client, log).fetchBuild(rows(), ctx(load(rigName)), branch, build,
                        false, (entries, c) -> SimRunning.runningRows(entries, c, new PowerShellProcessOps()));
            }
        };
    }

    // ------------------------------------------------------------ refusals

    /** Why Start cannot run now, or "" when it can. Names what to pick. */
    String refusal() {
        String held = busy.refusal();
        if (!held.isEmpty()) {
            return held;
        }
        if (!downloads.busy().isEmpty()) {
            return "A download or install is already in progress.";
        }
        String rigName = rig.get();
        if (rigName == null || rigName.isBlank()) {
            return "No rig: start CAT with --rig before an install.";
        }
        String blocker = blocker(downloads.choice());
        return blocker.isEmpty() ? "" : "Nothing to install yet: pick " + blocker + ".";
    }

    /**
     * What the choice lacks, or is not built yet; "" for an install that can start. The Python's
     * {@code install_problems}: the Downloads blockers, then Skip with no Simulator Build.
     */
    String blocker(DownloadsService.Choice c) {
        List<String> problems = new ArrayList<>(downloads.problems());
        if (c.mode() == UpgradeMode.SKIP && !c.simChosen()) {
            problems.add("an Upgrade mode other than Skip, or a Simulator Build");
        }
        if (c.mode() == UpgradeMode.VHQ) {
            problems.add("another Upgrade mode: VHQ is not implemented (petrotest/upgrade/vhq.py)");
        }
        if (c.installing() && c.commanderBranch().isEmpty() && downloads.localBuildDir(c).isEmpty()) {
            problems.add("a Commander Build: none on this PC holds " + c.dist() + " with the "
                    + (c.mode() == UpgradeMode.CLASSIC ? "AU" : "PSI")
                    + " files, so pick a branch to download one from");
        }
        return String.join(" and ", problems);
    }

    // ------------------------------------------------------------ the page's words

    /** The Install Upgrade page's "What Start will do". */
    String summary(DownloadsService.Choice c) {
        String what = switch (c.mode()) {
            case NEW_INSTALL -> "installs with PSI (" + String.join(" and ", DownloadsService.jarsFor(c.mode()))
                    + "), then psi-init --no-pairing resets the Commander's password";
            case SKIP -> "is not installed: Upgrade mode is Skip";
            case CLASSIC -> "is pushed with AU (" + String.join(" and ", DownloadsService.jarsFor(c.mode())) + ")";
            case VHQ -> "is not installed: VHQ is not implemented";
        };
        String simulators = c.simChosen()
                ? c.simBranch() + " " + (c.simBuild().isEmpty() ? "(pick a build)" : c.simBuild())
                        + "'s simulators replace the staged ones, config files and all; a running one is refused"
                : "the staged simulators are kept";
        return "Commander: " + c.commanderLabel() + ", distribution " + c.dist() + ", " + what + ".\n"
                + "Simulators: " + simulators + ".\n"
                + "No test runs. The registers are not paired: a run's step 4 pairs them after provision.";
    }

    /** The question Start asks, naming the rig and the build, since the install reinstalls the Commander. */
    String confirmText(DownloadsService.Choice c) {
        if (c.mode() == UpgradeMode.CLASSIC) {
            return "Push " + c.commanderLabel() + " (" + c.dist() + ") to rig " + rig.get()
                    + " with Auto_Upgrade.jar (AU)?";
        }
        if (c.mode() != UpgradeMode.NEW_INSTALL) {
            return "Replace the simulators on rig " + rig.get() + " with " + c.simBranch() + " " + c.simBuild() + "?";
        }
        return "Install " + c.commanderLabel() + " (" + c.dist() + ") on rig " + rig.get() + "? "
                + "The PSI install reinstalls the Commander and every register, and reformats the Commander's disk. "
                + "psi-init --no-pairing follows; the registers are not paired.";
    }

    /** Every stage, in order; a stage not yet reached has status "". */
    List<Stage> stages() {
        List<Stage> out = new ArrayList<>();
        synchronized (lock) {
            for (String name : STAGES) {
                out.add(stages.getOrDefault(name, new Stage(name, "", "")));
            }
        }
        return out;
    }

    String notice() {
        synchronized (lock) {
            return notice;
        }
    }

    /** The install's state line: "installing…", "done — no test was run", "stopped", "failed: …", or "". */
    String message() {
        synchronized (lock) {
            return message;
        }
    }

    /** Whether an install holds the rig now (the page shows Stop). */
    boolean installing() {
        return RigBusy.INSTALL.equals(busy.own());
    }

    // ------------------------------------------------------------ the install

    /**
     * Starts the install in the background, or refuses it and says why in {@link #notice()}.
     *
     * @return "" when started, else the refusal
     */
    String start() {
        String why = refusal();
        DownloadsService.Choice c = downloads.choice();
        String rigName = rig.get();
        if (why.isEmpty() && c.simChosen()) {
            Map<String, String> running;
            try {
                running = sims.running(rigName);
            } catch (RuntimeException e) {
                running = Map.of("(the simulator registry)", "could not tell: " + DownloadsService.message(e));
            }
            if (!running.isEmpty()) {
                List<String> parts = new ArrayList<>();
                running.forEach((name, reason) -> parts.add(name + " (" + reason + ")"));
                why = "simulators are running, so the Simulator Build cannot replace them: " + String.join("; ", parts)
                        + ". Stop them first; nothing was installed.";
            }
        }
        if (why.isEmpty()) {
            why = busy.begin(RigBusy.INSTALL);
        }
        if (why.isEmpty()) {
            stopping.set(false);
            synchronized (lock) {
                stages.clear();
                message = "installing…";
            }
            why = downloads.begin("install", "Install Upgrade", "Install Upgrade done. No test was run.", job -> {
                try {
                    String error = install(rigName, c, job);
                    synchronized (lock) {
                        message = stopping.get() ? "stopped"
                                : error.isEmpty() ? "done — no test was run" : "failed: " + error;
                    }
                    return stopping.get() ? "stopped" : error;
                } finally {
                    busy.end(RigBusy.INSTALL);
                }
            });
            if (!why.isEmpty()) {
                busy.end(RigBusy.INSTALL);
            }
        }
        setNotice(why);
        return why;
    }

    /** Stop: ends the install before its next stage, and interrupts the one running. */
    String stop() {
        if (!installing()) {
            return "No install in progress.";
        }
        stopping.set(true);
        downloads.stopWork();
        return "Stopping the install…";
    }

    void setNotice(String text) {
        synchronized (lock) {
            notice = text == null ? "" : text;
        }
    }

    private void stage(String name, String status, String detail) {
        synchronized (lock) {
            stages.put(name, new Stage(name, status, detail));
        }
    }

    private boolean stopped(String name) {
        if (stopping.get() || Thread.currentThread().isInterrupted()) {
            stopping.set(true);
            stage(name, "stopped", "Stop was pressed");
            return true;
        }
        return false;
    }

    /** The sequence: download, install, psi-init, simulators. Returns "" or the failure. */
    private String install(String rigName, DownloadsService.Choice c, JobLog log) {
        String current = "download";
        try {
            log.line("Install Upgrade on rig " + rigName + ": " + summary(c).replace('\n', ' '));
            Path dir = null;
            if (c.installing()) {
                if (!c.commanderBranch().isEmpty()) {
                    stage("download", "running", c.commanderLabel());
                    dir = downloads.fetchCommander(c, log);
                    stage("download", "done", dir.toString());
                } else {
                    Optional<Path> local = downloads.localBuildDir(c);
                    dir = local.orElseThrow(() -> new BuildException("no build on this PC holds the install files"));
                    stage("download", "skipped", "already on this PC: " + dir);
                }
            } else {
                stage("download", "skipped", "Upgrade mode is Skip");
            }
            if (stopped("upgrade")) {
                return "";
            }
            current = "upgrade";
            if (dir != null && c.mode() == UpgradeMode.CLASSIC) {
                stage("upgrade", "running", "AU: " + dir);
                List<StepResult> results = classic.install(rigName, dir, env, log::line);
                Optional<StepResult> failed = results.stream().filter(StepResult::failed).findFirst();
                if (failed.isPresent()) {
                    String why = failed.get().step() + ": " + failed.get().detail();
                    stage("upgrade", "failed", why);
                    return why;
                }
                stage("upgrade", "done", results.size() + " steps");
                stage("psi-init", "skipped", "no PSI install");
            } else if (dir != null) {
                stage("upgrade", "running", dir.toString());
                NewInstallTools tools = toolsFor.apply(dir);
                List<StepResult> results = installer.install(rigName, tools, env, log::line);
                Optional<StepResult> failed = results.stream().filter(StepResult::failed).findFirst();
                if (failed.isPresent()) {
                    String why = failed.get().step() + ": " + failed.get().detail();
                    stage("upgrade", "failed", why);
                    return why;
                }
                stage("upgrade", "done", results.size() + " steps");
                if (stopped("psi-init")) {
                    return "";
                }
                current = "psi-init";
                stage("psi-init", "running", "psi-init --no-pairing");
                if (!psi.resetPassword(rigName, env, log::line)) {
                    String why = "psi-init --no-pairing failed (see the log)";
                    stage("psi-init", "failed", why);
                    return why;
                }
                stage("psi-init", "done", "password reset; registers not paired");
            } else {
                stage("upgrade", "skipped", "Upgrade mode is Skip");
                stage("psi-init", "skipped", "no PSI install");
            }
            if (stopped("simulators")) {
                return "";
            }
            current = "simulators";
            if (!c.simChosen()) {
                stage("simulators", "skipped", "keep the staged ones");
            } else {
                stage("simulators", "running", c.simBranch() + " " + c.simBuild());
                List<String> failed = new ArrayList<>();
                List<SimFetch.FetchResult> results = sims.replace(rigName, c.simBranch(), c.simBuild(), log::line);
                for (SimFetch.FetchResult row : results) {
                    log.line((row.ok() ? "PASS  " : "FAIL  ") + row.detail());
                    if (!row.ok()) {
                        failed.add(row.detail());
                    }
                }
                if (!failed.isEmpty()) {
                    String why = String.join("; ", failed);
                    stage("simulators", "failed", why);
                    return why;
                }
                stage("simulators", "done", results.size() + " replaced");
            }
            log.line("DONE  Install Upgrade — no test was run");
            return "";
        } catch (RuntimeException e) {
            if (stopping.get()) {
                stage(current, "stopped", "Stop was pressed");
                return "";
            }
            String why = DownloadsService.message(e);
            stage(current, "failed", why);
            return why;
        }
    }
}
