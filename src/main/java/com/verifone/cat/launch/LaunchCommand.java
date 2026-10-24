package com.verifone.cat.launch;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * {@code launch --rig <name|file.yaml> [--suite <node>]... [--plan <key>]... [--upgrade <mode>]
 * [--simulator <name>]... [--fake-pos] [--fake-pinpad]}: one run, headless, through the seven steps.
 *
 * <p>The Python counterpart is {@code tools/rig/run_plan.py} over {@code launch_run}. It prints each
 * step's end and the run folder, and exits 0 only when every step completed. In this port no run can
 * complete: System Checks ends unavailable after its two checks, which stops the run, and the rest are
 * skipped with the reason. That is the honest state until the steps are ported.
 *
 * <p>Exit codes: 0 the run completed, 1 a step failed or was unavailable, 2 a usage error. Nothing here
 * contacts a device: the rig file is read, and the run lock is a file in the lease folder.
 *
 * <p>Not wired into {@code Main}: the orchestrator decides that. {@link #run(String[])} is the entry.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LaunchCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = "usage: launch --rig <name|file.yaml> [--suite <node>]... [--plan <key>]..."
            + " [--upgrade skip|classic|vhq|new_install] [--simulator <name>]... [--fake-pos] [--fake-pinpad]";

    /** The fake-POS banner, the same words the Python CAT's run log uses. */
    static final String FAKE_POS_BANNER = "[FAKE POS: evidence about CAT, not about a rig]";

    /** Runs the command with the environment's paths and the process streams. */
    public int run(String[] argv) {
        return run(argv, LaunchPaths.fromEnv(System.getenv()), System.out, System.err);
    }

    /** The testable seam: the paths and both streams are passed in. */
    int run(String[] argv, LaunchPaths paths, PrintStream out, PrintStream err) {
        LaunchConfig config;
        try {
            config = Options.parse(argv).toConfig();
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }

        RunLauncher launcher = new RunLauncher(paths);
        // Step 5 runs the plan's collected cases through the production handler set, on the fake POS or the rig.
        launcher.wireTests(new StandardRegistry(), PlanCases.forRun(Path.of("").toAbsolutePath()),
                new BrowserConnector());
        LaunchResult result = launcher.launch(config);
        if (config.fakePos()) {
            out.println(FAKE_POS_BANNER);
        }
        out.println("run " + result.runId() + " — " + result.runFolder());
        for (StepResult step : result.steps()) {
            out.println(String.format("  %-24s %-9s%s", step.name(), step.state(),
                    step.detail().isEmpty() ? "" : " — " + step.detail()));
        }
        return result.complete() ? EXIT_OK : EXIT_FAILED;
    }

    /** The parsed command line. */
    private static final class Options {

        String rig;
        final List<String> suites = new ArrayList<>();
        final List<String> plans = new ArrayList<>();
        final List<String> simulators = new ArrayList<>();
        UpgradeMode upgrade = UpgradeMode.SKIP;
        boolean fakePos;
        boolean fakePinpad;

        static Options parse(String[] argv) {
            Options o = new Options();
            Deque<String> args = new ArrayDeque<>(Arrays.asList(argv));
            while (!args.isEmpty()) {
                String flag = args.pop();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--suite" -> o.suites.add(value(flag, args));
                    case "--plan" -> o.plans.add(value(flag, args));
                    case "--simulator" -> o.simulators.add(value(flag, args));
                    case "--upgrade" -> o.upgrade = UpgradeMode.of(value(flag, args));
                    case "--fake-pos" -> o.fakePos = true;
                    case "--fake-pinpad" -> o.fakePinpad = true;
                    default -> throw new IllegalArgumentException("Unknown option " + flag + ".");
                }
            }
            if (o.rig == null && !o.fakePos) {
                throw new IllegalArgumentException("--rig is required.");
            }
            return o;
        }

        LaunchConfig toConfig() {
            LaunchConfig.Builder builder = new LaunchConfig.Builder(rig == null ? "" : rig)
                    .upgradeMode(upgrade)
                    .fakePos(fakePos)
                    .fakePinpad(fakePinpad);
            suites.forEach(builder::suite);
            plans.forEach(builder::testPlan);
            simulators.forEach(builder::simulator);
            return builder.build();
        }

        private static String value(String flag, Deque<String> args) {
            if (args.isEmpty()) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            return args.pop();
        }
    }
}
