package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.report.ReportPaths;

/**
 * A stat-keyed summary index over every past run: the Python CAT's {@code cat.results.index}.
 *
 * <p>Replaying about a thousand runs on every open is not an option, so each run's
 * {@link RunSummary} is cached in {@code ~/.cat/java/results-index.json} (or {@code CAT_RESULTS_INDEX})
 * keyed on {@code (run folder name, file size, mtime)}. An unchanged run is never re-read;
 * a changed, new or vanished one is.
 *
 * <p>The Python CAT keeps its own {@code ~/.cat/results-index.json}. CAT-Java does not share it:
 * until the two formats are proven identical, a write from one could corrupt the other. The
 * shape is the Python's, so the file could be swapped in later once that is proven.
 *
 * <p>The index is a derived convenience. An unreadable index, missing, truncated or of
 * another version, is a cache miss, never a failure; a run that cannot be read is reported
 * in {@link IndexResult#failed()} and left out of the rows.
 */
public final class RunIndex {

    /** Overrides the index location. Tests point it at a temporary file. */
    public static final String INDEX_ENV = "CAT_RESULTS_INDEX";

    /** The index's file name in a directory that is not the user's {@code ~/.cat}. */
    public static final String INDEX_FILE = "results-index.json";

    /** Bump when {@link RunSummary}'s shape changes; an index of another version is a miss. */
    public static final int INDEX_VERSION = 1;

    private static final Logger LOG = Logger.getLogger(RunIndex.class.getName());

    /**
     * What one {@link #build} pass produced.
     *
     * @param rows           one per run with an events file, newest first
     * @param reread         how many runs had to be replayed (0 for a no-op pass)
     * @param failed         run ids whose file could not be replayed, and so are absent from rows
     * @param cacheWasUsable false when an index file existed but could not be read
     */
    public record IndexResult(List<RunSummary> rows, int reread, List<String> failed, boolean cacheWasUsable) {}

    private record Entry(long size, long mtimeNs, RunSummary row) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunIndex() {}

    /** {@code $CAT_RESULTS_INDEX}, else {@code ~/.cat/java/results-index.json}. Never the Python's file. */
    public static Path indexPath(Map<String, String> env) {
        String configured = env.getOrDefault(INDEX_ENV, "").strip();
        return configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".cat", "java", INDEX_FILE)
                : Path.of(configured);
    }

    /** The cached rows as they stand, newest first, with no stat and no replay. Empty when unreadable. */
    public static List<RunSummary> loadIndex(Path path) {
        Map<String, Entry> entries = read(path);
        if (entries == null) {
            return List.of();
        }
        return newestFirst(entries.values().stream().map(Entry::row).toList());
    }

    /**
     * Brings the index up to date with {@code root} and returns every row.
     *
     * @param root     the results root
     * @param path     the index file
     * @param progress called as {@code progress(done, total)} after each run folder, or null
     */
    public static synchronized IndexResult build(Path root, Path path, BiConsumer<Integer, Integer> progress) {
        Map<String, Entry> cached = read(path);
        boolean usable = cached != null;
        if (cached == null) {
            cached = Map.of();
        }
        List<Path> dirs = RunStore.iterRunDirs(root);
        Map<String, Entry> fresh = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        int reread = 0;
        int done = 0;
        for (Path runDir : dirs) {
            done++;
            String name = runDir.getFileName().toString();
            try {
                Path events = runDir.resolve(ReportPaths.EVENTS_FILE);
                var attributes = Files.readAttributes(events, BasicFileAttributes.class);
                long size = attributes.size();
                long mtimeNs = toNanos(attributes.lastModifiedTime().toInstant());
                Entry entry = cached.get(name);
                if (entry == null || entry.size() != size || entry.mtimeNs() != mtimeNs) {
                    RunSummary row = RunStore.summarize(runDir, RunStore.replay(events, false));
                    entry = new Entry(size, mtimeNs, row);
                    reread++;
                }
                fresh.put(name, entry);
            } catch (IOException | RuntimeException e) {
                LOG.warning("results: could not index " + runDir + ": " + e);
                failed.add(name);
            }
            if (progress != null) {
                progress.accept(done, dirs.size());
            }
        }
        if (reread > 0 || !fresh.keySet().equals(cached.keySet()) || !usable) {
            try {
                write(path, fresh);
            } catch (IOException e) {
                // An unwritable cache costs speed, not correctness.
                LOG.warning("results: could not write " + path + ": " + e);
            }
        }
        List<RunSummary> rows = newestFirst(fresh.values().stream().map(Entry::row).toList());
        return new IndexResult(rows, reread, List.copyOf(failed), usable);
    }

    /** The cached entries, empty for no file, null for an unreadable one. */
    private static Map<String, Entry> read(Path path) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            JsonNode document = MAPPER.readTree(Files.readString(path));
            if (document == null || document.path("version").asInt(-1) != INDEX_VERSION) {
                return null;
            }
            Map<String, Entry> entries = new LinkedHashMap<>();
            JsonNode runs = document.get("runs");
            if (runs == null || !runs.isObject()) {
                return null;
            }
            var fields = runs.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode e = field.getValue();
                entries.put(field.getKey(), new Entry(e.get("size").asLong(), e.get("mtime_ns").asLong(),
                        RunSummary.fromJson(e.get("row"))));
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            return null; // an unreadable cache is a miss, never a failure
        }
    }

    private static void write(Path path, Map<String, Entry> entries) throws IOException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("version", INDEX_VERSION);
        ObjectNode runs = payload.putObject("runs");
        new java.util.TreeMap<>(entries).forEach((name, entry) -> {
            ObjectNode node = runs.putObject(name);
            node.put("size", entry.size());
            node.put("mtime_ns", entry.mtimeNs());
            node.set("row", entry.row().toJson(MAPPER));
        });
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, MAPPER.writeValueAsString(payload));
        // A crash mid-write leaves the old index, not half a file.
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<RunSummary> newestFirst(List<RunSummary> rows) {
        List<RunSummary> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(RunSummary::startedAt).thenComparing(RunSummary::runId).reversed());
        return List.copyOf(sorted);
    }

    private static long toNanos(java.time.Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
