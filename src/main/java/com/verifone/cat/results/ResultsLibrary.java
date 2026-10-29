package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.verifone.cat.plans.CaseNodes;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.runconfig.ConfigForm;

/**
 * The past runs under one results root, with their index: what the Results page reads.
 *
 * <p>The Python CAT's {@code cat.web.runs.RunLibrary}. Construction does no I/O, so a page can
 * be built before anything is read; the index is brought up to date by {@link #index()}.
 *
 * <p>A run is named only by a folder directly under the root. {@link #runFolder} refuses any
 * other name (a separator, {@code .} or {@code ..}), so a URL cannot read outside the root.
 */
public final class ResultsLibrary {

    private final Path root;
    private final Path indexFile;
    private final Predicate<String> placeholder;

    /**
     * @param root        the results root
     * @param indexFile   the index file
     * @param placeholder true for a node id whose file is an unmigrated placeholder; the
     *                    two-argument constructor says none is. {@link #fromEnvironment} reads
     *                    the checkout, via {@link #placeholderIn}.
     */
    public ResultsLibrary(Path root, Path indexFile, Predicate<String> placeholder) {
        this.root = root;
        this.indexFile = indexFile;
        this.placeholder = placeholder;
    }

    /** A library over {@code root} with the index beside it, for tests and tools that inject a root. */
    public ResultsLibrary(Path root, Path indexFile) {
        this(root, indexFile, nodeid -> false);
    }

    /**
     * The library for {@code env}: {@code CAT_RESULTS_DIR} and {@code CAT_RESULTS_INDEX}, else the
     * defaults. Its placeholder check reads the checkout named by {@code CAT_REPO_ROOT}, as the
     * Python CAT's {@code cat.panels.results._is_placeholder} does.
     */
    public static ResultsLibrary fromEnvironment(Map<String, String> env) {
        return new ResultsLibrary(ReportPaths.resultsRoot(env), RunIndex.indexPath(env),
                placeholderIn(ConfigForm.repoRoot(env)));
    }

    /**
     * True for a node id (a path, optionally {@code ::test}) whose file under {@code repoRoot} is an
     * unmigrated placeholder. Such a test is left out of every rate. Never throws.
     */
    public static Predicate<String> placeholderIn(Path repoRoot) {
        return nodeid -> {
            if (nodeid == null || nodeid.isBlank()) {
                return false;
            }
            return CaseNodes.isUnmigrated(repoRoot.resolve(nodeid.split("::", 2)[0]));
        };
    }

    public Path root() {
        return root;
    }

    public Path indexFile() {
        return indexFile;
    }

    public Predicate<String> placeholder() {
        return placeholder;
    }

    /** Brings the index up to date and returns every run, newest first. Blocking. */
    public RunIndex.IndexResult index() {
        return RunIndex.build(root, indexFile, null);
    }

    /** The run's folder under the root, or empty when {@code runId} is not a single folder name that exists. */
    public Optional<Path> runFolder(String runId) {
        if (runId == null || runId.isBlank() || runId.equals(".") || runId.equals("..")
                || runId.indexOf('/') >= 0 || runId.indexOf('\\') >= 0 || runId.indexOf(':') >= 0) {
            return Optional.empty();
        }
        Path folder = root.resolve(runId).normalize();
        if (!root.normalize().equals(folder.getParent())) {
            return Optional.empty();
        }
        return Files.isRegularFile(folder.resolve(ReportPaths.EVENTS_FILE))
                ? Optional.of(folder)
                : Optional.empty();
    }

    /**
     * Replays one run for the detail page. Empty when there is no such run under the root.
     *
     * @throws IOException when its events file cannot be read
     */
    public Optional<RunReplay> load(String runId, boolean includeLogs) throws IOException {
        Optional<Path> folder = runFolder(runId);
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        Path events = folder.get().resolve(ReportPaths.EVENTS_FILE);
        String stamp = Files.size(events) + "@" + Files.getLastModifiedTime(events).toMillis();
        String key = runId + (includeLogs ? "+logs" : "");
        synchronized (loaded) {
            Loaded hit = loaded.get(key);
            if (hit != null && hit.stamp().equals(stamp)) {
                return Optional.of(hit.replay());
            }
        }
        RunReplay replay = RunStore.replay(events, includeLogs);
        synchronized (loaded) {
            loaded.put(key, new Loaded(stamp, replay));
        }
        return Optional.of(replay);
    }

    /** How many loaded runs stay in memory (the Python {@code RunLibrary}'s {@code _KEEP}). */
    static final int KEEP = 4;

    private record Loaded(String stamp, RunReplay replay) {}

    /**
     * The newest few runs opened, so each dialog and log request of a run page does not re-read its file (a whole
     * Cadence plan's {@code events.ndjson} is ~255 MB). Keyed by the file's size and time, so a run still being
     * written is read again.
     */
    private final Map<String, Loaded> loaded = new java.util.LinkedHashMap<>(8, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Loaded> eldest) {
            return size() > KEEP;
        }
    };
}
