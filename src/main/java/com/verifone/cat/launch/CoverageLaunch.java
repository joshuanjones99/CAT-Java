package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.coverage.CoverageCommand;
import com.verifone.cat.coverage.CoverageReport;
import com.verifone.cat.coverage.CoverageSummary;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.xray.Attach;
import com.verifone.cat.xray.XrayReporter;

/**
 * JaCoCo coverage in a run: ready the devices in step 4, collect and report in step 6. The Python's
 * {@code cat.launch.coverage}.
 *
 * <p>Only when the run asks for it ({@link LaunchConfig#coverage}, the Config tab's switch, off by default), on a run
 * that touches the rig. The device and report work is {@code cat-java coverage} ({@link CoverageCommand}), run
 * in-process with its lines streamed into the step as one timed row each ({@code JaCoCo coverage prepare — cat-java
 * coverage prepare}); the Jira upload is {@code cat-java xray-attach} ({@link Attach}), a detached process like the
 * Xray sidecar.
 *
 * <p><b>Never a reason to stop the run.</b> A {@link #prepare} that fails is a failed row, a WARN line and a run
 * without coverage (step 6 then says why); a collect or report that fails is a failed row in Generate Report, whose
 * state then reads failed ({@link #FAILED_DETAIL}), because the operator asked for something that did not arrive. The
 * tests' verdicts are untouched either way.
 *
 * <p><b>Step 6 reboots the Commander</b> ({@code collect}, so the JVMs that write their data on exit write it) before
 * Cleanup closes the day; {@code collect} waits for CGILink to answer again first.
 *
 * <p><b>The ticket</b> the bundle is attached to ({@link #coverageIssues}): the run's Xray Test Execution(s) when it
 * reported to Xray ({@code xray.json}), else the test plan(s) it ran, else none, and the step says so.
 *
 * <p>Deviations from the Python, each because of how CAT-Java is built:
 * <ul>
 *   <li>The commands run in-process ({@link Ops#coverage}), as step 4's other stages do in CAT-Java, not as child
 *       processes; the row's failure detail is the last line, with the last error line appended, as the Python's
 *       relay returns it.</li>
 *   <li>"Touches the rig" is {@code !fakePos} with a rig named, the rule {@link CommanderConfigurationStep} uses for
 *       every non-fake run; the Python also requires {@code provision_register}, which every one of its real entry
 *       points sets.</li>
 *   <li>Step 4's {@code coverage_ready} is kept here, per run ({@link #ready}), not on {@link RunContext}, which
 *       another slice owns. {@code report} is passed {@code --results-root} (the launch's), where the Python relies on
 *       the same default.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the tests use a fake {@link Ops}; nothing reaches a device or Jira.
 */
public final class CoverageLaunch {

    /** The run folder's coverage directory. */
    public static final String COVERAGE_DIR = "coverage";

    /** The row label of every coverage line, as the Python's. */
    static final String LABEL = "JaCoCo coverage";

    /** What Generate Report's detail gains when coverage was asked for and did not arrive. */
    public static final String FAILED_DETAIL = "; JaCoCo coverage failed (see this step's log)";

    /** The Python relay's error line: {@code SomethingError:}, {@code ...Exception:}, or a leading ERROR. */
    private static final Pattern ERROR_LINE = Pattern.compile("\\b\\w*(?:Error|Exception):|^\\s*ERROR\\b");

    /** What coverage reaches the world through: the {@code coverage} command, and a detached process. */
    public interface Ops {

        /** Runs {@code cat-java coverage <args>}; its lines go to {@code out} and {@code err}; returns its exit code. */
        int coverage(List<String> args, Consumer<String> out, Consumer<String> err);

        /** Starts {@code argv} detached with its output appended to {@code log}; returns the pid. */
        long spawn(List<String> argv, Path log, Path cwd) throws IOException;
    }

    /** Which Jira keys the bundle goes to, and where they came from. */
    public record Issues(List<String> keys, String source) {

        public Issues {
            keys = List.copyOf(keys);
        }
    }

    /** Per run: the ops it uses, and whether step 4 got the devices ready. */
    private static final class State {
        Ops ops;
        boolean ready;
    }

    private static final Map<RunContext, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private CoverageLaunch() {}

    /** The production ops for a run: {@link CoverageCommand} with the run's rigs folder, a detached process. */
    static Ops real(RunContext ctx) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put(RigLoader.RIGS_DIR_ENV, ctx.paths().rigsDir().toString());
        CoverageCommand command = new CoverageCommand(env, com.verifone.cat.coverage.DeviceOps::defaults, null, null);
        XraySidecarStart.Spawner spawner = XraySidecarStart.detached();
        return new Ops() {
            @Override
            public int coverage(List<String> args, Consumer<String> out, Consumer<String> err) {
                return command.execute(args, out, err);
            }

            @Override
            public long spawn(List<String> argv, Path log, Path cwd) throws IOException {
                return spawner.spawn(argv, log, cwd);
            }
        };
    }

    /** Uses {@code ops} for this run instead of the real ones. Tests only. */
    static void use(RunContext ctx, Ops ops) {
        state(ctx).ops = ops;
    }

    /** Whether step 4's {@code prepare} left every device ready: the Python's {@code ctx.coverage_ready}. */
    public static boolean ready(RunContext ctx) {
        State state = STATES.get(ctx);
        return state != null && state.ready;
    }

    /** Coverage asked for, on a run that touches the rig (see the class comment). */
    static boolean wanted(RunContext ctx) {
        LaunchConfig config = ctx.config();
        return config.coverage() && !config.fakePos() && config.rig() != null && !config.rig().isBlank();
    }

    /**
     * Step 4, before {@code provision}: the agent loaded on every device, old data cleared. A failure leaves the run
     * going without coverage. Nothing happens unless {@link #wanted}.
     */
    public static void prepare(RunContext ctx, StepEmitter step) {
        if (!wanted(ctx)) {
            return;
        }
        boolean ok = task(ctx, step, List.of("coverage", "prepare", "--rig", ctx.config().rig()));
        state(ctx).ready = ok;
        if (!ok) {
            step.warn(LABEL, "the devices are not ready, so this run collects none; the tests run as usual");
        }
    }

    /** The Jira keys to attach to, and where they came from; see the class comment. */
    public static Issues coverageIssues(RunContext ctx) {
        LaunchConfig config = ctx.config();
        if (config.xrayReport()) {
            JsonNode status = XrayReporter.readStatus(ctx.runFolder());
            List<String> keys = new ArrayList<>();
            if (status != null) {
                status.path("executions").forEach(execution -> {
                    String key = execution.path("key").asText("");
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                });
            }
            if (!keys.isEmpty()) {
                return new Issues(keys, "the run's Xray test execution(s)");
            }
        }
        if (!config.testPlans().isEmpty()) {
            return new Issues(config.testPlans(), "the run's test plan(s)");
        }
        return new Issues(List.of(), "");
    }

    /**
     * Step 6, first: collect, report, attach. Returns false when coverage was asked for and did not arrive; the
     * caller then marks Generate Report failed with {@link #FAILED_DETAIL}.
     */
    public static boolean collect(RunContext ctx, StepEmitter step) {
        if (!wanted(ctx)) {
            return true;
        }
        if (!ready(ctx)) {
            step.warn(LABEL, "Commander Configuration did not get the devices ready "
                    + "(its log says why), so there is nothing to collect");
            return false;
        }
        LaunchConfig config = ctx.config();
        Path coverageDir = ctx.runFolder().resolve(COVERAGE_DIR);
        boolean collected = task(ctx, step, List.of("coverage", "collect", "--rig", config.rig(), "--out",
                coverageDir.resolve("exec").toString()));
        if (!collected) {
            step.warn("coverage", "some data did not arrive; reporting what did");
        }

        List<String> args = new ArrayList<>(List.of("coverage", "report", "--dir", coverageDir.toString(), "--dist",
                config.dist()));
        args.addAll(List.of("--run-id", ctx.runId()));
        String build = ctx.installedBuild();
        if (build != null && !build.isEmpty()) {
            args.addAll(List.of("--build", build));
        }
        for (String plan : config.testPlans()) {
            args.addAll(List.of("--plan", plan));
        }
        Path petroapps = ctx.paths().petroappsRoot();
        if (petroapps != null && Files.isDirectory(petroapps)) {
            args.addAll(List.of("--sources", petroapps.toString()));
        }
        args.addAll(List.of("--results-root", ctx.paths().resultsRoot().toString()));
        boolean reported = task(ctx, step, args);

        Path bundle = coverageDir.resolve(CoverageReport.zipName(ctx.runId()));
        boolean bundled = Files.isRegularFile(bundle);
        step.check(bundled, "coverage bundle", bundle.toString(), true);
        if (bundled) {
            attach(ctx, step, bundle, coverageDir.resolve(CoverageSummary.SUMMARY_JSON));
        }
        return collected && reported && bundled;
    }

    /** The upload to Jira, detached: the Python's {@code _attach}. Never fails the step. */
    static void attach(RunContext ctx, StepEmitter step, Path bundle, Path summary) {
        Issues issues = coverageIssues(ctx);
        if (issues.keys().isEmpty()) {
            step.warn("coverage to Jira",
                    "no Xray execution and no test plan: this run has no Jira ticket to attach it to");
            return;
        }
        Path runDir = ctx.runFolder();
        List<String> argv = new ArrayList<>(List.of(XraySidecarStart.javaExecutable(), "-cp",
                System.getProperty("java.class.path", ""), "com.verifone.cat.Main", "xray-attach",
                "--run-dir", runDir.toString(), "--file", bundle.toString(), "--summary", summary.toString()));
        for (String issue : issues.keys()) {
            argv.addAll(List.of("--issue", issue));
        }
        long pid;
        try {
            pid = ops(ctx).spawn(argv, runDir.resolve(Attach.LOG_FILE), Path.of(System.getProperty("user.dir", ".")));
        } catch (IOException | RuntimeException e) {
            step.warn("coverage to Jira", "the upload did not start (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
            return;
        }
        step.log("coverage to Jira: attaching " + bundle.getFileName() + " to " + String.join(", ", issues.keys())
                + " (" + issues.source() + "), pid " + pid + "; outcome in " + runDir.resolve(Attach.STATUS_FILE),
                "INFO");
    }

    /**
     * {@code cat-java coverage <args>} as one timed row ({@code JaCoCo coverage <stage> — cat-java coverage <stage>});
     * unlike step 4's stages, never fails the step. The Python's {@code _petrotest}.
     */
    static boolean task(RunContext ctx, StepEmitter step, List<String> args) {
        String command = String.join(" ", args.subList(0, Math.min(2, args.size())));
        String label = LABEL + " " + args.get(1) + " — cat-java " + command;
        step.log("cat-java " + String.join(" ", args), "INFO");
        step.substep(label, SubstepOutcome.RUNNING, "", 0.0);
        long started = System.nanoTime();
        String[] last = {""};
        String[] error = {""};
        Consumer<String> relay = text -> {
            for (String line : text.split("\\R", -1)) {
                String clean = line.stripTrailing();
                if (clean.isEmpty()) {
                    continue;
                }
                boolean isError = ERROR_LINE.matcher(clean).find();
                if (isError) {
                    error[0] = clean.strip();
                }
                last[0] = clean;
                step.log(clean, isError ? "ERROR" : "INFO");
            }
        };
        int code;
        try {
            code = ops(ctx).coverage(args, relay, relay);
        } catch (RuntimeException e) {
            relay.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            code = 1;
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        if (code == 0) {
            step.substep(label, SubstepOutcome.PASSED, "", elapsed);
            return true;
        }
        String detail = last[0].strip();
        if (!error[0].isEmpty() && !detail.contains(error[0])) {
            detail = detail + " — " + error[0];
        }
        String failure = detail.isEmpty() ? "exit " + code : detail;
        step.log("FAIL  cat-java " + command + " — " + failure, "ERROR");
        step.substep(label, SubstepOutcome.FAILED, failure, elapsed);
        return false;
    }

    private static Ops ops(RunContext ctx) {
        State state = state(ctx);
        if (state.ops == null) {
            state.ops = real(ctx);
        }
        return state.ops;
    }

    private static State state(RunContext ctx) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(ctx, k -> new State());
        }
    }
}
