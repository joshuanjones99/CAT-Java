package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.events.CatEvent;

/**
 * Coverage in a run (the Python's {@code test_cat_coverage}): the right {@code coverage} commands at the right point
 * with the right arguments, what comes back handled, and the bundle sent to the right ticket. The commands and the
 * upload are a recording fake, so this is evidence about the wiring only, never that a device was measured.
 */
class CoverageLaunchTest {

    @TempDir
    Path dir;

    /** Records each command; {@code report} writes the bundle and a summary where the real one would. */
    private static final class Recorder implements CoverageLaunch.Ops {
        final List<List<String>> calls = new ArrayList<>();
        final List<List<String>> spawned = new ArrayList<>();
        boolean reportWrites = true;
        int prepareExit;
        int collectExit;
        boolean spawnFails;

        @Override
        public int coverage(List<String> args, Consumer<String> out, Consumer<String> err) {
            calls.add(args);
            String stage = args.get(1);
            if (stage.equals("prepare")) {
                if (prepareExit != 0) {
                    out.accept("FAIL  commander: DeviceUnreachable: commander.invalid");
                }
                return prepareExit;
            }
            if (stage.equals("collect")) {
                return collectExit;
            }
            if (reportWrites) {
                Path coverage = Path.of(args.get(args.indexOf("--dir") + 1));
                String run = args.get(args.indexOf("--run-id") + 1);
                try {
                    Files.createDirectories(coverage);
                    Files.writeString(coverage.resolve("coverage-" + run + ".zip"), "PK");
                    Files.writeString(coverage.resolve("summary.json"), "{\"run_id\": \"" + run + "\"}");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                out.accept("# JaCoCo coverage — run " + run);
                return 0;
            }
            err.accept("FAIL  coverage report — no overlay.zip for this run (no --build or --overlay); the class "
                    + "files come from it");
            return 1;
        }

        @Override
        public long spawn(List<String> argv, Path log, Path cwd) throws IOException {
            if (spawnFails) {
                throw new IOException("no java");
            }
            spawned.add(argv);
            return 4242;
        }
    }

    private RunContext context(LaunchConfig config, Recorder ops) throws IOException {
        Path folder = dir.resolve("results").resolve("dddddddddddd");
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "dddddddddddd", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        CoverageLaunch.use(ctx, ops);
        return ctx;
    }

    private static LaunchConfig.Builder covered() {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).coverage(true);
    }

    private static List<String> logs(RunContext ctx) throws IOException {
        ctx.closeEvents();
        List<String> lines = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            if (event.get("type").asText().equals("log.line")) {
                lines.add(event.get("message").asText());
            }
        }
        return lines;
    }

    @Test
    void offByDefaultNoCoverageCommandRuns() throws IOException {
        Recorder ops = new Recorder();
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), ops);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);

        CoverageLaunch.prepare(ctx, step);
        assertTrue(CoverageLaunch.collect(ctx, step));

        assertTrue(ops.calls.isEmpty());
        assertTrue(ops.spawned.isEmpty());
    }

    @Test
    void aFakePosRunNeverMeasures() throws IOException {
        Recorder ops = new Recorder();
        RunContext ctx = context(new LaunchConfig.Builder("").fakePos(true).coverage(true).build(), ops);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.REPORT);
        CoverageLaunch.prepare(ctx, step);
        assertTrue(CoverageLaunch.collect(ctx, step));
        assertTrue(ops.calls.isEmpty());
    }

    @Test
    void preparedCollectedReportedAndTheBundleGoesToThePlan() throws IOException {
        Recorder ops = new Recorder();
        RunContext ctx = context(covered().testPlan("PETROCORE-70960").build(), ops);
        ctx.installedBuild("Solutions134");

        CoverageLaunch.prepare(ctx, new StepEmitter(ctx, LaunchStepNames.COMMANDER));
        assertTrue(CoverageLaunch.ready(ctx));
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.REPORT);
        assertTrue(CoverageLaunch.collect(ctx, step));

        assertEquals(List.of("coverage", "prepare", "--rig", LaunchFixtures.RIG), ops.calls.get(0));
        List<String> collect = ops.calls.get(1);
        assertEquals(List.of("coverage", "collect"), collect.subList(0, 2));
        assertEquals(ctx.runFolder().resolve("coverage").resolve("exec").toString(),
                collect.get(collect.indexOf("--out") + 1));
        List<String> report = ops.calls.get(2);
        assertEquals("dddddddddddd", report.get(report.indexOf("--run-id") + 1));
        assertEquals("Solutions134", report.get(report.indexOf("--build") + 1));
        assertEquals("PETROCORE-70960", report.get(report.indexOf("--plan") + 1));
        assertEquals(ctx.paths().petroappsRoot().toString(), report.get(report.indexOf("--sources") + 1));
        assertEquals(ctx.paths().resultsRoot().toString(), report.get(report.indexOf("--results-root") + 1));

        List<String> argv = ops.spawned.get(0);
        assertEquals("xray-attach", argv.get(argv.indexOf("com.verifone.cat.Main") + 1));
        assertEquals("PETROCORE-70960", argv.get(argv.indexOf("--issue") + 1));
        assertEquals(ctx.runFolder().resolve("coverage").resolve("summary.json").toString(),
                argv.get(argv.indexOf("--summary") + 1));
        List<String> lines = logs(ctx);
        assertTrue(lines.contains("cat-java coverage prepare --rig " + LaunchFixtures.RIG), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("coverage to Jira: attaching coverage-dddddddddddd.zip "
                + "to PETROCORE-70960 (the run's test plan(s)), pid 4242")), lines.toString());
    }

    @Test
    void aFailedPrepareMeansNothingToCollectAndSaysWhy() throws IOException {
        Recorder ops = new Recorder();
        ops.prepareExit = 1;
        RunContext ctx = context(covered().build(), ops);

        CoverageLaunch.prepare(ctx, new StepEmitter(ctx, LaunchStepNames.COMMANDER));
        assertFalse(CoverageLaunch.ready(ctx));
        assertFalse(CoverageLaunch.collect(ctx, new StepEmitter(ctx, LaunchStepNames.REPORT)));

        assertEquals(1, ops.calls.size(), "prepare only");
        List<String> lines = logs(ctx);
        assertTrue(lines.contains("FAIL  cat-java coverage prepare — FAIL  commander: DeviceUnreachable: "
                + "commander.invalid"), lines.toString());
        assertTrue(lines.contains("WARN  JaCoCo coverage — the devices are not ready, so this run collects none; the "
                + "tests run as usual"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("WARN  JaCoCo coverage — Commander Configuration did "
                + "not get the devices ready")), lines.toString());
    }

    @Test
    void coverageAskedForAndNotDeliveredFailsGenerateReportOnly() throws IOException {
        Recorder ops = new Recorder();
        ops.reportWrites = false;
        RunContext ctx = context(covered().build(), ops);
        CoverageLaunch.prepare(ctx, new StepEmitter(ctx, LaunchStepNames.COMMANDER));
        ctx.emit(new CatEvent.LogLine("INFO", "cat", "a line the report reads past", LaunchStepNames.REPORT));

        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.REPORT);
        assertTrue(new GenerateReportStep().run(ctx, step), "the run goes on to Cleanup");

        assertEquals(StepResult.FAILED, step.state());
        assertTrue(step.detail().endsWith("; JaCoCo coverage failed (see this step's log)"), step.detail());
        assertTrue(ops.spawned.isEmpty());
        for (String name : GenerateReportStep.OUTPUTS) {
            assertTrue(Files.isRegularFile(ctx.runFolder().resolve(name)), "the reports still stand: " + name);
        }
        List<String> lines = logs(ctx);
        assertTrue(lines.contains("FAIL  coverage bundle — " + ctx.runFolder().resolve("coverage")
                .resolve("coverage-dddddddddddd.zip")), lines.toString());
    }

    @Test
    void collectThatMissedSomeDataStillReportsWhatArrived() throws IOException {
        Recorder ops = new Recorder();
        ops.collectExit = 1;
        RunContext ctx = context(covered().testPlan("PETROCORE-1").build(), ops);
        CoverageLaunch.prepare(ctx, new StepEmitter(ctx, LaunchStepNames.COMMANDER));

        assertFalse(CoverageLaunch.collect(ctx, new StepEmitter(ctx, LaunchStepNames.REPORT)));

        assertEquals(3, ops.calls.size(), "the report still ran");
        assertEquals(1, ops.spawned.size(), "and what arrived was attached");
        assertTrue(logs(ctx).contains("WARN  coverage — some data did not arrive; reporting what did"));
    }

    @Test
    void theXrayExecutionWhenTheRunReportedToXray() throws IOException {
        RunContext ctx = context(covered().xrayReport(true).testPlan("PETROCORE-1").build(), new Recorder());
        Files.createDirectories(ctx.runFolder());
        Files.writeString(ctx.runFolder().resolve("xray.json"),
                "{\"executions\": {\"PETROCORE-1\": {\"key\": \"PETROCORE-900\"}}}");
        assertEquals(new CoverageLaunch.Issues(List.of("PETROCORE-900"), "the run's Xray test execution(s)"),
                CoverageLaunch.coverageIssues(ctx));
    }

    @Test
    void thePlanWhenXrayMadeNoExecutionAndNoneForBareSuites() throws IOException {
        RunContext plans = context(covered().xrayReport(true).testPlan("PETROCORE-1").testPlan("PETROCORE-2").build(),
                new Recorder());
        assertEquals(List.of("PETROCORE-1", "PETROCORE-2"), CoverageLaunch.coverageIssues(plans).keys());
        RunContext bare = context(covered().build(), new Recorder());
        assertEquals(new CoverageLaunch.Issues(List.of(), ""), CoverageLaunch.coverageIssues(bare));
    }

    @Test
    void noTicketOrAnUploadThatCannotStartIsAWarningNeverAFailure() throws IOException {
        Recorder ops = new Recorder();
        RunContext bare = context(covered().build(), ops);
        CoverageLaunch.prepare(bare, new StepEmitter(bare, LaunchStepNames.COMMANDER));
        assertTrue(CoverageLaunch.collect(bare, new StepEmitter(bare, LaunchStepNames.REPORT)));
        assertTrue(ops.spawned.isEmpty());
        assertTrue(logs(bare).contains("WARN  coverage to Jira — no Xray execution and no test plan: this run has no "
                + "Jira ticket to attach it to"));

        Recorder failing = new Recorder();
        failing.spawnFails = true;
        dir = Files.createDirectories(dir.resolve("second"));
        RunContext ctx = context(covered().testPlan("PETROCORE-1").build(), failing);
        CoverageLaunch.prepare(ctx, new StepEmitter(ctx, LaunchStepNames.COMMANDER));
        assertTrue(CoverageLaunch.collect(ctx, new StepEmitter(ctx, LaunchStepNames.REPORT)));
        assertTrue(logs(ctx).contains("WARN  coverage to Jira — the upload did not start (IOException: no java)"));
    }
}
