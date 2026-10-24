package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.build.BuildException;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.upgrade.NewInstall;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.StepResult;

/**
 * Install Upgrade: installs the chosen Commander Build on the rig with the PSI path, and reports each
 * stage. No test runs.
 *
 * <p>The Python's {@code cat/web/service.py} {@code install_upgrade} and
 * {@code cat/launch/install_upgrade.py}, for the parts this port has: the PSI install
 * ({@link NewInstall}), after the Commander build is on this PC (downloaded, or the one already here).
 *
 * <p>Refused, with the reason named, while a run or preflight is in progress, while a download is going,
 * and for anything not built yet: AU ({@code petrotest/upgrade/classic.py}), VHQ, a Simulator Build
 * ({@code cat/sims/fetch.py}), and psi-init ({@code petrotest/upgrade/psi_init.py}), which the PSI
 * install needs afterwards and which is reported as a stage, not run.
 *
 * <p>There is no Stop: {@link NewInstall} has no cancel, so an install runs to its own end.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). A PSI install reformats the Commander's disk and
 * reinstalls every device, so a test passes a fake {@link Installer}; nothing here reaches a rig.
 */
final class UpgradeService {

    /** The stages, in order, named as the Python's {@code INSTALL_STAGES}. */
    static final List<String> STAGES = List.of("download", "upgrade", "psi-init", "simulators");

    /**
     * Runs the PSI install against the rig named. The production one loads the rig and runs
     * {@link NewInstall}; a test passes a fake.
     */
    @FunctionalInterface
    interface Installer {
        List<StepResult> install(String rig, NewInstallTools tools, Map<String, String> env, Consumer<String> log);
    }

    /** The real installer: the rig file, then the PSI path over JSch and the CGILink probe. */
    static final Installer SYSTEM = (rig, tools, env, log) ->
            NewInstall.system(tools, log).run(RigLoader.load(rig, RigLoader.rigsDir(env)), env);

    /** One stage: its name, its status ("", running, done, skipped, failed, not built) and a detail. */
    record Stage(String name, String status, String detail) {}

    private final DownloadsService downloads;
    private final Supplier<String> rig;
    private final BooleanSupplier runBusy;
    private final Installer installer;
    private final Function<Path, NewInstallTools> toolsFor;
    private final Map<String, String> env;
    private final Object lock = new Object();

    // Guarded by lock.
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    private String notice = "";

    UpgradeService(DownloadsService downloads, Supplier<String> rig, BooleanSupplier runBusy, Installer installer,
            Function<Path, NewInstallTools> toolsFor, Map<String, String> env) {
        this.downloads = downloads;
        this.rig = rig;
        this.runBusy = runBusy;
        this.installer = installer;
        this.toolsFor = toolsFor;
        this.env = env;
    }

    /**
     * The real service: the PSI tools checked for the build, and the rig the operator named with {@code --rig}.
     *
     * @param runBusy true while a run or its preflight holds the rig
     */
    static UpgradeService standard(DownloadsService downloads, Supplier<String> rig, BooleanSupplier runBusy,
            Map<String, String> env) {
        return new UpgradeService(downloads, rig, runBusy, SYSTEM, dir -> NewInstallTools.forBuild(dir, env), env);
    }

    // ------------------------------------------------------------ refusals

    /** Why Start cannot run now, or "" when it can. Names what to pick. */
    String refusal() {
        if (runBusy.getAsBoolean()) {
            return "A run is in progress: stop it first.";
        }
        if (!downloads.busy().isEmpty()) {
            return "A download or install is already in progress.";
        }
        String rigName = rig.get();
        if (rigName == null || rigName.isBlank()) {
            return "No rig: start CAT with --rig before an install.";
        }
        return blocker(downloads.choice());
    }

    /** What the choice lacks, or is not built yet; "" for a PSI install that can start. */
    String blocker(DownloadsService.Choice c) {
        switch (c.mode()) {
            case SKIP -> {
                return "pick an Upgrade mode other than Skip (PSI), or nothing is installed";
            }
            case CLASSIC -> {
                return "AU (classic) is not built yet (petrotest/upgrade/classic.py)";
            }
            case VHQ -> {
                return "VHQ is not built yet (petrotest/upgrade/vhq.py)";
            }
            default -> {
                // PSI, below.
            }
        }
        if (c.simChosen()) {
            return "a Simulator Build is not built yet (cat/sims/fetch.py): choose Don't download for Simulator Build";
        }
        if (!c.commanderBranch().isEmpty() && c.commanderBuild().isEmpty()) {
            return "a Commander Build number on " + c.commanderBranch();
        }
        if (c.commanderBranch().isEmpty() && downloads.localBuildDir(c).isEmpty()) {
            return "a Commander Build: none on this PC holds " + c.dist()
                    + " with the PSI files, so pick a branch to download one from";
        }
        return "";
    }

    // ------------------------------------------------------------ the page's words

    /** The Install Upgrade page's "What Start will do". */
    String summary(DownloadsService.Choice c) {
        String what = c.mode() == UpgradeMode.NEW_INSTALL
                ? "installs with PSI (" + String.join(" and ", DownloadsService.jarsFor(c.mode())) + ")"
                : "is not installed: " + modeWord(c.mode());
        String sims = c.simChosen()
                ? "a Simulator Build (not built yet)"
                : "the staged simulators are kept";
        return "Commander: " + c.commanderLabel() + ", distribution " + c.dist() + ", " + what + ".\n"
                + "Simulators: " + sims + ".\n"
                + "After PSI, psi-init (pairing) is not built yet, so the Commander is not paired.";
    }

    private static String modeWord(UpgradeMode mode) {
        return switch (mode) {
            case SKIP -> "Skip is chosen";
            case CLASSIC -> "AU is not built yet";
            case VHQ -> "VHQ is not built yet";
            case NEW_INSTALL -> "PSI";
        };
    }

    /** The question Start asks, naming the rig and the build, since the install reinstalls the Commander. */
    String confirmText(DownloadsService.Choice c) {
        return "Install " + c.commanderLabel() + " (" + c.dist() + ") on rig " + rig.get() + "? "
                + "The PSI install reinstalls the Commander and every register, and reformats the Commander's disk. "
                + "psi-init (pairing) is not built yet, so the Commander is not paired afterwards.";
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

    // ------------------------------------------------------------ the install

    /**
     * Starts the install in the background, or refuses it and says why in {@link #notice()}.
     *
     * @return "" when started, else the refusal
     */
    String start() {
        String why = refusal();
        if (why.isEmpty()) {
            DownloadsService.Choice c = downloads.choice();
            String rigName = rig.get();
            why = downloads.begin("install", "Install Upgrade",
                    "Install Upgrade done: psi-init is not built yet, so the Commander is not paired (see Stages).",
                    job -> {
                        resetStages();
                        return install(rigName, c, job);
                    });
        }
        setNotice(why);
        return why;
    }

    void setNotice(String text) {
        synchronized (lock) {
            notice = text == null ? "" : text;
        }
    }

    private void resetStages() {
        synchronized (lock) {
            stages.clear();
        }
    }

    private void stage(String name, String status, String detail) {
        synchronized (lock) {
            stages.put(name, new Stage(name, status, detail));
        }
    }

    /** The PSI sequence: the build on this PC, the PSI install, then the stages not built yet. Returns "" or the failure. */
    private String install(String rigName, DownloadsService.Choice c, JobLog log) {
        String current = "download";
        try {
            Path dir;
            if (!c.commanderBranch().isEmpty()) {
                stage("download", "running", c.commanderLabel());
                dir = downloads.fetchCommander(c, log);
                stage("download", "done", dir.toString());
            } else {
                Optional<Path> local = downloads.localBuildDir(c);
                dir = local.orElseThrow(() -> new BuildException("no build on this PC holds the PSI files"));
                stage("download", "skipped", "already on this PC: " + dir);
            }
            current = "upgrade";
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
            current = "psi-init";
            stage("psi-init", "not built", "petrotest/upgrade/psi_init.py: the Commander is not paired");
            stage("simulators", "skipped", "the staged ones are kept");
            log.line("PSI install finished; psi-init is not built yet.");
            return "";
        } catch (RuntimeException e) {
            String why = DownloadsService.message(e);
            stage(current, "failed", why);
            return why;
        }
    }
}
