package com.verifone.cat.upgrade;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.InstallerJars;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java upgrade --rig <name> [--mode {skip,classic,vhq,new_install}] [--build-dir <dir>] [--dist <dist>]}:
 * get a rig onto a build, or say why not. The Python's {@code petrotest upgrade} ({@code petrotest.upgrade.cli}).
 *
 * <ul>
 *   <li>{@code skip} (the default): nothing to do, exit 0.</li>
 *   <li>{@code classic}: {@link ClassicUpgrade} with {@link ClassicUpgradeTools#forBuild} of the build directory, or,
 *       with none given and none downloaded, {@link ClassicUpgradeTools#fromEnv}.</li>
 *   <li>{@code vhq}: {@link VhqUpgrade}, which fails saying what it needs.</li>
 *   <li>{@code new_install}: {@link NewInstall} with {@link NewInstallTools#forBuild}.</li>
 * </ul>
 * With no {@code --build-dir}, the build is the newest one already under the builds root that holds {@code --dist}
 * with the mode's files ({@link BuildDiscovery#latestBuildDir}), and the output says which.
 *
 * <p>Output is the Python's: every line to stdout, then one {@code step: status — detail} line per step and
 * {@code OK} or {@code FAILED}. Exit codes: 0 when every step passed, 1 when one failed or nothing could be
 * attempted, 2 for a usage or rig-loading error. Deviations: a run's progress notes (the AU settle wait, the PSI
 * ledger) are printed as they happen, before the summary; the commands it names are {@code cat-java}'s.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): it closes the day, flashes firmware or reformats the Commander.
 */
public final class UpgradeCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java upgrade --rig <name|file.yaml> [--mode {skip,classic,vhq,new_install}]",
            "                        [--build-dir <dir>] [--dist <dist>]",
            "",
            "Upgrade a rig's build (Overarch/Monarch -UpgradeMode equivalent).",
            "  --mode       skip (default), classic (AU), new_install (PSI), vhq (not implemented)",
            "  --build-dir  the build directory to install from; default: the newest one already downloaded",
            "  --dist       distribution, e.g. 'buypass': which subfolder of a downloaded build to install");

    /** How each real path runs; a test passes recorders, so no test reaches a device. */
    public interface Paths {

        List<StepResult> classic(Rig rig, ClassicUpgradeTools tools, Map<String, String> env, Consumer<String> log);

        List<StepResult> newInstall(Rig rig, NewInstallTools tools, Map<String, String> env, Consumer<String> log);

        /** The AU tools for a build directory: {@link ClassicUpgradeTools#forBuild}, checked on this controller. */
        default ClassicUpgradeTools classicTools(Path buildDir) {
            return ClassicUpgradeTools.forBuild(buildDir);
        }

        /** The PSI tools for a build directory: {@link NewInstallTools#forBuild}, checked on this controller. */
        default NewInstallTools newInstallTools(Path buildDir, Map<String, String> env) {
            return NewInstallTools.forBuild(buildDir, env);
        }

        /** The real paths: {@link ClassicUpgrade#system} and {@link NewInstall#system}. */
        Paths SYSTEM = new Paths() {
            @Override
            public List<StepResult> classic(Rig rig, ClassicUpgradeTools tools, Map<String, String> env,
                    Consumer<String> log) {
                return ClassicUpgrade.system(tools, log).run(rig, env);
            }

            @Override
            public List<StepResult> newInstall(Rig rig, NewInstallTools tools, Map<String, String> env,
                    Consumer<String> log) {
                return NewInstall.system(tools, log).run(rig, env);
            }
        };
    }

    private final Paths paths;

    public UpgradeCommand() {
        this(Paths.SYSTEM);
    }

    public UpgradeCommand(Paths paths) {
        this.paths = paths;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "upgrade".equals(args.get(0))) {
            args.remove(0);
        }
        String rigName = null;
        String modeText = "skip";
        String buildDir = null;
        String dist = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--help".equals(arg) || "-h".equals(arg)) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (!List.of("--rig", "--mode", "--build-dir", "--dist").contains(arg)) {
                return usage(err, "unknown argument: " + arg);
            }
            if (i + 1 >= args.size()) {
                return usage(err, arg + " needs a value");
            }
            String value = args.get(++i);
            switch (arg) {
                case "--rig" -> rigName = value;
                case "--mode" -> modeText = value;
                case "--build-dir" -> buildDir = value;
                default -> dist = value;
            }
        }
        if (rigName == null) {
            return usage(err, "--rig is required");
        }
        if (!List.of("skip", "classic", "vhq", "new_install").contains(modeText)) {
            return usage(err, "--mode: invalid choice: '" + modeText
                    + "' (choose from 'skip', 'classic', 'vhq', 'new_install')");
        }
        Rig rig;
        try {
            rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        Path dir = buildDir == null || buildDir.isEmpty() ? null : Path.of(buildDir);
        return upgrade(rig, UpgradeMode.of(modeText), dir, dist, env, BuildDiscovery.buildsRoot(env),
                line -> {
                    out.println(line);
                    out.flush();
                });
    }

    /**
     * The upgrade itself, every line to {@code out}: the Python's {@code upgrade(rig, mode=, build_dir=, dist=)}.
     *
     * @param buildDir the build to install; null for the newest under {@code buildsRoot}
     * @param dist     which dist folder to look for when {@code buildDir} is null; null or blank for any
     * @return the exit code: 0 when every step passed
     */
    public int upgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Map<String, String> env,
            Path buildsRoot, Consumer<String> out) {
        List<StepResult> results;
        if (mode == UpgradeMode.SKIP) {
            out.accept("upgrade mode is 'skip' — nothing to do.");
            return EXIT_OK;
        }
        if (mode == UpgradeMode.CLASSIC) {
            Path resolved = resolveBuildDir(buildDir, mode, dist, buildsRoot, out);
            ClassicUpgradeTools tools;
            try {
                tools = resolved != null ? paths.classicTools(resolved) : ClassicUpgradeTools.fromEnv(env);
            } catch (IllegalArgumentException e) {
                out.accept(e.getMessage());
                return EXIT_FAILED;
            }
            results = paths.classic(rig, tools, env, out);
        } else if (mode == UpgradeMode.VHQ) {
            results = VhqUpgrade.run();
        } else {
            Path resolved = resolveBuildDir(buildDir, mode, dist, buildsRoot, out);
            if (resolved == null) {
                out.accept("new_install found no build to install under " + buildsRoot + ": no "
                        + "<build>/<dist>/ there holds overlay.zip, Petro_Suite_Installer.jar and "
                        + "Auto_Upgrade.jar. Download one with `cat-java build --solutions <n>`, "
                        + "or pass --build-dir.");
                return EXIT_FAILED;
            }
            NewInstallTools tools;
            try {
                tools = paths.newInstallTools(resolved, env);
            } catch (IllegalArgumentException e) {
                out.accept(e.getMessage());
                return EXIT_FAILED;
            }
            results = paths.newInstall(rig, tools, env, out);
        }
        out.accept(render(results));
        return results.stream().anyMatch(StepResult::failed) ? EXIT_FAILED : EXIT_OK;
    }

    /** {@code buildDir} if given, else the newest build already downloaded for the mode, said on {@code out}. */
    static Path resolveBuildDir(Path buildDir, UpgradeMode mode, String dist, Path buildsRoot, Consumer<String> out) {
        if (buildDir != null) {
            return buildDir;
        }
        Optional<Path> found = BuildDiscovery.latestBuildDir(buildsRoot, dist, InstallerJars.forMode(mode));
        found.ifPresent(path -> out.accept("no --build-dir given; using the build already downloaded: " + path));
        return found.orElse(null);
    }

    /** One {@code step: status — detail} line per result, then {@code OK} or {@code FAILED}: the Python's render. */
    static String render(List<StepResult> results) {
        List<String> lines = new ArrayList<>();
        for (StepResult result : results) {
            String line = result.step() + ": " + result.status().wire();
            if (!result.detail().isEmpty()) {
                line = line + " — " + result.detail();
            }
            lines.add(line);
        }
        lines.add(results.stream().anyMatch(StepResult::failed) ? "FAILED" : "OK");
        return String.join("\n", lines);
    }

    private static int usage(PrintStream err, String message) {
        err.println("cat-java upgrade: " + message);
        err.println(USAGE);
        return EXIT_USAGE;
    }
}
