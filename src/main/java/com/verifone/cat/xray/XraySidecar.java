package com.verifone.cat.xray;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.RunEnded;
import com.verifone.cat.results.RunIndex;
import com.verifone.cat.results.RunSummary;
import com.verifone.cat.xray.Summary.SummaryFacts;
import com.verifone.cat.xray.Summary.Verdict;

/**
 * The Xray reporting sidecar: {@code java -cp cat-java.jar com.verifone.cat.xray.XraySidecar --run
 * <run-id>}. Port of the Python CAT's {@code python -m cat.xray}.
 *
 * <p>It follows {@code <results root>/<run-id>/events.ndjson} until the run is over, feeds each event
 * to an {@link XrayReporter}, and leaves {@code xray.json} and {@code xray-summary.md} in the run's
 * folder. Exit status is 0 even when Xray could not be reached: the outcome is in {@code xray.json},
 * and nothing upstream treats a reporting failure as a run failure. {@code --dry-run} (or
 * {@code CAT_XRAY_DRY_RUN=1}) writes the requests to {@code xray-requests.ndjson} and sends nothing.
 *
 * <p>The run is over when a {@code run.finished} says it was cancelled, or Cleanup finishes; a
 * stream that stops without either ends after {@code idleAfterRunOver} seconds once
 * {@code run.ended} exists, or after {@code idleLimit} seconds of silence in any case.
 *
 * <p>Reached as {@code cat-java xray ...} ({@link #run(String[])}). Nothing spawns it yet: the launcher
 * does not start it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class XraySidecar {

    private static final Logger LOG = LoggerFactory.getLogger(XraySidecar.class);

    /** With no new bytes this long after the run was seen to end, stop waiting (Python's 10 s). */
    public static final double IDLE_AFTER_RUN_OVER = 10.0;

    /** With no new bytes this long at any point, the stream is dead (Python's 3 h). */
    public static final double IDLE_LIMIT = 3 * 3600.0;

    /** The poll interval of the file follower, in seconds. */
    public static final double POLL_SECONDS = 1.0;

    /** The default coalescing interval for pushes, in seconds (Python's 5 s). */
    public static final double FLUSH_INTERVAL_SECONDS = 5.0;

    /** Everything one sidecar run needs. The defaults are the CLI's. */
    public record Options(
            String runId,
            Path resultsRoot,
            boolean once,
            boolean dryRun,
            String build,
            String dist,
            String branch,
            String upgrade,
            double flushIntervalSeconds,
            double pollSeconds,
            double idleAfterRunOver,
            double idleLimit,
            Path credentialsFile,
            AtlassianClient atlassian,
            Function<String, List<PlanCase>> planCases,
            Function<String, String> planName,
            Function<XrayRunState, Map<String, List<Verdict>>> history) {

        /** The CLI's defaults for {@code runId} under {@code resultsRoot}, reporting live. */
        public static Options defaults(String runId, Path resultsRoot) {
            return new Options(runId, resultsRoot, false, false, "", "", "", "",
                    FLUSH_INTERVAL_SECONDS, POLL_SECONDS, IDLE_AFTER_RUN_OVER, IDLE_LIMIT,
                    AtlassianCredentials.defaultPath(), null, null, null, null);
        }
    }

    private XraySidecar() {}

    /**
     * Parses the command line: {@code --run <id>} (required), {@code --results-root <dir>},
     * {@code --once}, {@code --dry-run}, {@code --build}, {@code --dist}, {@code --branch},
     * {@code --upgrade}, {@code --flush-interval <seconds>}.
     *
     * @throws IllegalArgumentException on an unknown flag, a missing value or a missing {@code --run}
     */
    public static Options parse(String[] args, Map<String, String> env) {
        String runId = null;
        Path root = null;
        boolean once = false;
        boolean dryRun = false;
        String build = "";
        String dist = "";
        String branch = "";
        String upgrade = "";
        double flush = FLUSH_INTERVAL_SECONDS;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--once" -> once = true;
                case "--dry-run" -> dryRun = true;
                case "--run", "--results-root", "--build", "--dist", "--branch", "--upgrade",
                        "--flush-interval" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(flag + " needs a value");
                    }
                    String value = args[++i];
                    switch (flag) {
                        case "--run" -> runId = value;
                        case "--results-root" -> root = Path.of(value);
                        case "--build" -> build = value;
                        case "--dist" -> dist = value;
                        case "--branch" -> branch = value;
                        case "--upgrade" -> upgrade = value;
                        default -> flush = parseSeconds(flag, value);
                    }
                }
                default -> throw new IllegalArgumentException("unknown argument " + flag);
            }
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("--run <run-id> is required");
        }
        Path resultsRoot = root != null ? root : ReportPaths.resultsRoot(env);
        String dryEnv = env.getOrDefault("CAT_XRAY_DRY_RUN", "");
        boolean dry = dryRun || (!dryEnv.isEmpty() && !"0".equals(dryEnv));
        Options base = Options.defaults(runId, resultsRoot);
        return new Options(runId, resultsRoot, once, dry, build, dist, branch, upgrade, flush,
                base.pollSeconds(), base.idleAfterRunOver(), base.idleLimit(), base.credentialsFile(),
                null, null, null, null);
    }

    private static double parseSeconds(String flag, String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (seconds <= 0) {
                throw new IllegalArgumentException(flag + " must be positive");
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs a number of seconds");
        }
    }

    /** The standalone entry point: {@link #run(String[])}, then exit with its code. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * The command line, as {@code cat-java xray ...} and {@link #main} use it: parses {@code args}
     * (printing the usage on a bad one) and follows the run.
     *
     * @return 2 for a usage error, otherwise 0 whatever Xray did
     */
    public static int run(String[] args) {
        Options options;
        try {
            options = parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: cat-java xray --run <run-id> [--results-root <dir>] [--once] "
                    + "[--dry-run] [--build <b>] [--dist <d>] [--branch <b>] [--upgrade <m>] "
                    + "[--flush-interval <seconds>]");
            return 2;
        }
        return run(options);
    }

    /**
     * Follows the run's stream and reports it. Returns 0 whatever Xray did.
     *
     * @param options the run and the settings; {@code atlassian} is used when set, else the
     *                credentials file is read
     */
    public static int run(Options options) {
        Path runDir = ReportPaths.runFolder(options.resultsRoot(), options.runId());
        Path eventsFile = runDir.resolve(ReportPaths.EVENTS_FILE);
        Path endedFile = runDir.resolve(RunEnded.RUN_ENDED_FILE);
        AtomicBoolean cancel = new AtomicBoolean(false);
        CountDownLatch cancelLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cancel.set(true);
            cancelLatch.countDown();
        }));

        SummaryFacts facts = new SummaryFacts(options.build(), options.dist(), options.branch(),
                options.upgrade(), controllerName(), "");
        XrayClient client = null;
        if (!options.dryRun()) {
            try {
                AtlassianClient atlassian = options.atlassian() != null
                        ? options.atlassian()
                        : atlassianFromFile(options.credentialsFile());
                client = new XrayClient(atlassian, Duration.ofSeconds(20), cancelLatch);
            } catch (AtlassianUnavailable e) {
                LOG.error("no credentials: {}", e.getMessage());
                XrayReporter refused = reporter(runDir, null, facts, options);
                refused.disable("no Atlassian credentials: " + e.getMessage());
                return 0;
            }
        }
        XrayReporter reporter = reporter(runDir, client, facts, options);
        LOG.info("following {} ({})", eventsFile, options.dryRun() ? "dry run" : "live");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "xray-follow");
            thread.setDaemon(true);
            return thread;
        });
        try {
            XrayFollowRule rule = new XrayFollowRule(options, reporter, endedFile);
            CompletableFuture<Void> done = new EventFollower(eventsFile, rule::stop,
                    Duration.ofMillis(Math.max(1, Math.round(options.pollSeconds() * 1000))), cancel,
                    event -> {
                        reporter.feed(event);
                        reporter.maybeFlush();
                    }).start(scheduler);
            try {
                done.get();
                reporter.finish();
            } catch (ExecutionException e) {
                LOG.error("the reporter failed; the run is unaffected", e.getCause());
                finishQuietly(reporter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finishQuietly(reporter);
            }
        } finally {
            scheduler.shutdownNow();
        }
        LOG.info("done: {}", reporter.status().path("state").asText());
        return 0;
    }

    private static void finishQuietly(XrayReporter reporter) {
        try {
            reporter.finish();
        } catch (RuntimeException e) {
            LOG.error("and could not finish", e);
        }
    }

    private static XrayReporter reporter(Path runDir, XrayClient client, SummaryFacts facts, Options options) {
        Function<String, List<PlanCase>> planCases = options.planCases();
        Function<String, String> planName = options.planName();
        if (planCases == null || planName == null) {
            PlanCatalogue catalogue = PlanCatalogue.offline(Path.of(System.getProperty("user.dir")));
            if (planCases == null) {
                planCases = key -> catalogue.cases(key, PlanCatalogue.Mode.CACHE_ONLY).rows();
            }
            if (planName == null) {
                planName = catalogue::planName;
            }
        }
        Path root = options.resultsRoot();
        Function<XrayRunState, Map<String, List<Verdict>>> history = options.history() != null
                ? options.history()
                : state -> historyFromIndex(root, state);
        return new XrayReporter(runDir, client, facts, options.flushIntervalSeconds(), planCases, planName,
                history, () -> System.nanoTime() / 1e9);
    }

    /** Earlier rig runs of the same plan, through the Results index (built if stale). */
    static Map<String, List<Verdict>> historyFromIndex(Path root, XrayRunState state) {
        List<RunSummary> rows = RunIndex.build(root, RunIndex.indexPath(System.getenv()), (done, total) -> { })
                .rows();
        return Summary.historyFromRows(rows, state.runId(), state.testPlans(), state.rig());
    }

    /** The stop rule, as the Python's {@code stop(idle)}. */
    static final class XrayFollowRule {

        private final Options options;
        private final XrayReporter reporter;
        private final Path endedFile;

        XrayFollowRule(Options options, XrayReporter reporter, Path endedFile) {
            this.options = options;
            this.reporter = reporter;
            this.endedFile = endedFile;
        }

        boolean stop(double idle) {
            if (options.once()) {
                return true;
            }
            boolean ended = java.nio.file.Files.isRegularFile(endedFile);
            if (reporter.runOver()) {
                return idle >= options.idleAfterRunOver() || ended;
            }
            if (ended && idle >= options.idleAfterRunOver()) {
                return true; // ended without a Cleanup event: the run ended abnormally
            }
            return idle >= options.idleLimit();
        }
    }

    private static AtlassianClient atlassianFromFile(Path file) {
        // Both halves are read up front so a machine with no credentials is refused at once.
        AtlassianCredentials.loadJira(file);
        AtlassianCredentials.loadXray(file);
        return AtlassianClient.forCredentialsFile(file, Duration.ofSeconds(60));
    }

    private static String controllerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "";
        }
    }
}
