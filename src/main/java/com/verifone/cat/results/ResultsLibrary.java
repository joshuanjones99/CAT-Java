package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.verifone.cat.report.ReportPaths;

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
     *                    default says none is. (The Python CAT reads the repository; that
     *                    check is not ported yet.)
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

    /** The library for {@code env}: {@code CAT_RESULTS_DIR} and {@code CAT_RESULTS_INDEX}, else the defaults. */
    public static ResultsLibrary fromEnvironment(Map<String, String> env) {
        return new ResultsLibrary(ReportPaths.resultsRoot(env), RunIndex.indexPath(env));
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
        return Optional.of(RunStore.replay(folder.get().resolve(ReportPaths.EVENTS_FILE), includeLogs));
    }
}
