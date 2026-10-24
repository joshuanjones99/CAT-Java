package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.report.ReportPaths;

/**
 * Finds past runs under the results root and digests them: the Python CAT's
 * {@code cat.results.store}, which Results reads through.
 *
 * <p>A run is a direct child of the root holding an {@code events.ndjson}. A folder without
 * one (screenshots only, a probe) is not a run anything can be said about, so it is not
 * listed. The root comes from {@link ReportPaths#resultsRoot}, so {@code CAT_RESULTS_DIR}
 * moves it as it does for the writers; tests pass their own environment map.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class RunStore {

    private RunStore() {}

    /** The results root for {@code env}: {@code CAT_RESULTS_DIR}, else the documented default. */
    public static Path resultsRoot(Map<String, String> env) {
        return ReportPaths.resultsRoot(env);
    }

    /** Every direct child of {@code root} holding an events file, in name order. Empty when {@code root} is absent. */
    public static List<Path> iterRunDirs(Path root) {
        List<Path> runs = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return runs;
        }
        try (var children = Files.list(root)) {
            children.sorted().forEach(child -> {
                if (Files.isRegularFile(child.resolve(ReportPaths.EVENTS_FILE))) {
                    runs.add(child);
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        return runs;
    }

    /**
     * Folds one run's events file.
     *
     * @throws IOException when the file cannot be read
     */
    public static RunReplay replay(Path eventsFile, boolean includeLogs) throws IOException {
        return RunReplay.read(eventsFile, includeLogs);
    }

    /**
     * Digests a replayed run into its index row.
     *
     * <p>Cancelled is its own status, and a run with no run.finished gets no verdict
     * ({@code incomplete}). A passing test is kept only for a fake-POS run or a named rig,
     * since a developer's thousand unit tests would otherwise dominate the index and no
     * statistic aggregates them.
     *
     * @throws IOException when the events file's time cannot be read
     */
    public static RunSummary summarize(Path runDir, RunReplay replay) throws IOException {
        String started = replay.startedAt();
        if (started.isEmpty()) {
            started = OffsetDateTime.ofInstant(
                    Files.getLastModifiedTime(runDir.resolve(ReportPaths.EVENTS_FILE)).toInstant(),
                    ZoneOffset.UTC).toString();
        }
        boolean fakePos = replay.fakePos() || hasFakeFlag(replay.cmd());
        boolean keepPasses = fakePos || !RunSummary.NO_RIG.contains(replay.rig());
        List<RunSummary.TestRow> tests = new ArrayList<>();
        for (RunReplay.TestState test : replay.tests()) {
            if (keepPasses || !(test.status().equals("passed") || test.status().equals("warned"))) {
                tests.add(new RunSummary.TestRow(test.nodeid(), test.status(),
                        round3(test.duration()), test.excType()));
            }
        }
        List<RunSummary.StepRow> steps = new ArrayList<>();
        for (RunReplay.StepState step : replay.steps()) {
            steps.add(new RunSummary.StepRow(step.name(), step.status(), round3(step.elapsed())));
        }
        return new RunSummary(
                runDir.getFileName().toString(),
                started,
                replay.rig(),
                fakePos,
                replay.testPlans(),
                status(replay),
                replay.passed(),
                replay.failed(),
                replay.warned(),
                replay.notRunCases().size(),
                round3(replay.runElapsed()),
                replay.gitHead(),
                replay.cmd(),
                steps,
                tests,
                replay.planNames());
    }

    /** The verdict word a run's index row carries. */
    static String status(RunReplay replay) {
        if (replay.cancelled()) {
            return "cancelled";
        }
        return replay.outcome() == null ? "incomplete" : replay.outcome();
    }

    private static boolean hasFakeFlag(String cmd) {
        for (String token : cmd.split("\\s+")) {
            if (token.equals("--fake-pos") || token.equals("--pos-double")) {
                return true;
            }
        }
        return false;
    }

    static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
