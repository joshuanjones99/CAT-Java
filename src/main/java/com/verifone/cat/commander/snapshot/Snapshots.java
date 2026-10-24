package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where snapshots live, what a snapshot folder holds, and which one a Commander build may restore. A port of
 * {@code commander_snapshot}'s root, manifest, build and {@code choose_snapshot} functions.
 *
 * <p>A snapshot is a manifest map ({@code Map<String, Object>}, the JSON as read) beside its area files. The
 * manifest's keys and types are the Python's, so a Python-written snapshot is read as it is.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Snapshots {

    /** Overrides the snapshots root wholesale, as {@code CAT_BUILDS_ROOT} does for builds. */
    public static final String SNAPSHOTS_ROOT_ENV = "CAT_SNAPSHOTS_ROOT";

    /** Beside PATS's {@code C:\CICD\Builds} and {@code C:\CICD\Tools}. A path, not an address. */
    public static final Path DEFAULT_SNAPSHOTS_ROOT = Path.of("C:\\CICD\\Snapshots");

    /** Set (to anything but "", "0", "false", "no") to skip the restore in step 4, for triage. */
    public static final String NO_SNAPSHOT_ENV = "CAT_NO_SNAPSHOT";

    /** Each snapshot folder's index. */
    public static final String MANIFEST = "manifest.json";

    /** The manifest's format number; a snapshot of another format is refused. */
    public static final int MANIFEST_FORMAT = 1;

    /** suiteinfo.dat's key for the exact commit, and for when it was built. */
    public static final String BUILD_HASH = "Commit_Hash";

    /** suiteinfo.dat's key for the build's own time. */
    public static final String BUILD_TIME = "Timestamp";

    static final ObjectMapper JSON = new ObjectMapper();

    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    private Snapshots() {}

    /** A snapshot found under a base level: its folder and manifest. */
    public record Found(Path directory, Map<String, Object> manifest) {}

    /** The snapshot step 4 restores from, and how. */
    public record Choice(Path directory, Map<String, Object> manifest, boolean exact, String note) {}

    /** {@code $CAT_SNAPSHOTS_ROOT}, else {@link #DEFAULT_SNAPSHOTS_ROOT}. */
    public static Path snapshotsRoot(Map<String, String> environ) {
        String env = environ == null ? "" : environ.getOrDefault(SNAPSHOTS_ROOT_ENV, "").strip();
        return env.isEmpty() ? DEFAULT_SNAPSHOTS_ROOT : Path.of(env);
    }

    /** The snapshot folder for a Commander {@code Base_Level} ({@code 057.02.00}). */
    public static Path snapshotDir(String baseLevel, Path root) {
        String level = baseLevel == null ? "" : baseLevel.strip();
        if (level.isEmpty()) {
            throw new IllegalArgumentException("no base level to name the snapshot folder after");
        }
        return root.resolve(Areas.pyQuote(level));
    }

    /** Whether {@code $CAT_NO_SNAPSHOT} asks for the restore to be skipped. */
    public static boolean snapshotDisabled(Map<String, String> environ) {
        String value = environ == null ? "" : environ.getOrDefault(NO_SNAPSHOT_ENV, "");
        String v = value.strip().toLowerCase(Locale.ROOT);
        return !(v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no"));
    }

    /**
     * A build's folder name under its base level: {@code 2026-10-10-0cb76ccfb492}, the build date then the first 12
     * characters of the commit. Built from the Commander's own suiteinfo.dat.
     *
     * @throws IllegalArgumentException the suiteinfo has no commit
     */
    public static String buildKey(Map<String, ?> suite) {
        String commit = str(suite.get(BUILD_HASH)).strip();
        if (commit.isEmpty()) {
            throw new IllegalArgumentException("suiteinfo has no " + BUILD_HASH
                    + ": no build to name the snapshot after");
        }
        String day = str(suite.get(BUILD_TIME));
        day = day.length() > 10 ? day.substring(0, 10) : day;
        String name = day.isEmpty() ? commit.substring(0, Math.min(12, commit.length()))
                : day + "-" + commit.substring(0, Math.min(12, commit.length()));
        return Areas.pyQuote(name);
    }

    /** {@code <root>/<Base_Level>/<build_key>}: where a capture of this build goes. */
    public static Path buildDir(String baseLevel, Map<String, ?> suite, Path root) {
        return snapshotDir(baseLevel, root).resolve(buildKey(suite));
    }

    /** The suiteinfo a snapshot was captured from; empty when it was not recorded. */
    public static Map<String, Object> snapshotBuild(Map<String, Object> manifest) {
        Object suite = manifest.get("suiteinfo");
        return suite instanceof Map<?, ?> m ? castMap(m) : Map.of();
    }

    /** A snapshot's build, for people: its label, else its key, else "an unrecorded build". */
    public static String buildName(Map<String, Object> manifest) {
        String label = str(manifest.get("build_label")).strip();
        String key;
        try {
            key = buildKey(snapshotBuild(manifest));
        } catch (IllegalArgumentException e) {
            key = "";
        }
        if (!label.isEmpty() && !key.isEmpty()) {
            return label + " (" + key + ")";
        }
        return !label.isEmpty() ? label : !key.isEmpty() ? key : "an unrecorded build";
    }

    /** When a build was made, from its suiteinfo {@code Timestamp}; empty when it is absent or unreadable. */
    static Optional<Instant> builtAt(Map<String, ?> suite) {
        String text = str(suite.get(BUILD_TIME)).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (RuntimeException e) {
            // fall through: a time without a zone is UTC, a date alone is midnight UTC
        }
        try {
            return Optional.of(LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            // fall through
        }
        try {
            return Optional.of(LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code manifest.json} in {@code directory}, or null when the directory holds no readable manifest object.
     */
    public static Map<String, Object> loadManifest(Path directory) {
        Path file = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Object document = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Object.class);
            return document instanceof Map<?, ?> m ? castMap(m) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes {@code manifest} as indented JSON, the same shape the Python writes. */
    static String manifestJson(Map<String, Object> manifest) throws IOException {
        return JSON.writer(PRETTY).writeValueAsString(manifest);
    }

    /**
     * Every snapshot of one base level: the build folders under it and, from before 2026-10-20, a flat snapshot at the
     * base level itself. A capture's generation folders hold no manifest.
     */
    public static List<Found> baseSnapshots(Path base) {
        List<Found> found = new ArrayList<>();
        Map<String, Object> manifest = loadManifest(base);
        if (manifest != null) {
            found.add(new Found(base, manifest));
        }
        for (Path child : sortedDirectories(base)) {
            Map<String, Object> childManifest = loadManifest(child);
            if (childManifest != null) {
                found.add(new Found(child, childManifest));
            }
        }
        return found;
    }

    /** Every snapshot folder under the root, at either layout. */
    public static List<Path> allSnapshots(Path root) {
        List<Path> out = new ArrayList<>();
        for (Path base : sortedDirectories(root)) {
            for (Found f : baseSnapshots(base)) {
                out.add(f.directory());
            }
        }
        return out;
    }

    private static List<Path> sortedDirectories(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The snapshot for the Commander build {@code suite} describes, among {@code base}'s.
     *
     * <ol>
     * <li>This exact build ({@code Commit_Hash}): restored whole, as captured (its latest capture).</li>
     * <li>Else the newest build older than this one, by suiteinfo {@code Timestamp}: merge restore.</li>
     * <li>Else the oldest of the newer builds', restored whole, with a warning.</li>
     * </ol>
     * A snapshot or Commander whose build was not recorded cannot be placed: it is restored whole, as every snapshot
     * was before builds were told apart, and only when nothing better is known.
     *
     * @return null when there is no snapshot this build may use
     */
    public static Choice chooseSnapshot(Path base, Map<String, ?> suite) {
        List<Found> found = baseSnapshots(base);
        if (found.isEmpty()) {
            return null;
        }
        String commit = str(suite.get(BUILD_HASH)).strip();
        Optional<Instant> here = builtAt(suite);
        List<Found> same = new ArrayList<>();
        for (Found f : found) {
            if (!commit.isEmpty() && str(snapshotBuild(f.manifest()).get(BUILD_HASH)).strip().equals(commit)) {
                same.add(f);
            }
        }
        if (!same.isEmpty()) {
            Found latest = same.stream().max(Comparator.comparing(
                    (Found f) -> str(f.manifest().get("captured_at")))).orElseThrow();
            return new Choice(latest.directory(), latest.manifest(), true,
                    "this build's: " + buildName(latest.manifest()));
        }
        List<Dated> dated = new ArrayList<>();
        List<Found> unplaced = new ArrayList<>();
        for (Found f : found) {
            Optional<Instant> when = builtAt(snapshotBuild(f.manifest()));
            if (when.isPresent()) {
                dated.add(new Dated(when.get(), f));
            } else {
                unplaced.add(f);
            }
        }
        String target = commit.isEmpty() ? "this build" : buildKeyOrThis(suite);
        if (here.isPresent()) {
            Instant now = here.get();
            Dated older = dated.stream().filter(d -> !d.when().isAfter(now))
                    .max(Comparator.comparing(Dated::when)).orElse(null);
            if (older != null) {
                Found f = older.found();
                return new Choice(f.directory(), f.manifest(), false,
                        "restoring from " + buildName(f.manifest()) + "'s snapshot onto " + target
                                + ": merge restore");
            }
            Dated oldestNewer = dated.stream().min(Comparator.comparing(Dated::when)).orElse(null);
            if (oldestNewer != null) {
                Found f = oldestNewer.found();
                return new Choice(f.directory(), f.manifest(), true,
                        "WARNING: only newer builds have a snapshot; " + buildName(f.manifest())
                                + "'s, as captured, onto " + target);
            }
        } else if (!dated.isEmpty()) {
            Found f = dated.stream().max(Comparator.comparing(Dated::when)).orElseThrow().found();
            return new Choice(f.directory(), f.manifest(), true,
                    "this Commander's build is unrecorded: " + buildName(f.manifest()) + "'s, as captured");
        }
        if (!unplaced.isEmpty()) {
            Found f = unplaced.get(unplaced.size() - 1);
            return new Choice(f.directory(), f.manifest(), true, "a snapshot of an unrecorded build, as captured");
        }
        return null;
    }

    private static String buildKeyOrThis(Map<String, ?> suite) {
        try {
            return buildKey(suite);
        } catch (IllegalArgumentException e) {
            return "this build";
        }
    }

    private record Dated(Instant when, Found found) {}

    /** {@code Object.toString} for a JSON value, or empty for null: how the Python's {@code str(x or "")} reads it. */
    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** A {@code Map} with {@code String} keys, copied (and mutable, as a Python dict is); values as the JSON had them. */
    static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * {@code value} as JSON would read it back: maps as {@code LinkedHashMap}, lists as {@code ArrayList}, numbers as
     * Jackson reads them. A manifest built in memory is compared with one read from disk in this form.
     */
    static Map<String, Object> asJson(Map<String, ?> value) {
        try {
            return castMap(JSON.readValue(JSON.writeValueAsString(value), Map.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JSON document: " + e.getMessage(), e);
        }
    }

    /** {@code $CAT_SNAPSHOTS_ROOT} from the process environment, else {@link #DEFAULT_SNAPSHOTS_ROOT}. */
    public static Path snapshotsRoot() {
        return snapshotsRoot(System.getenv());
    }
}
