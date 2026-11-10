package com.verifone.cat.plans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a case's test file under the checkout, and re-checks a cached node against it.
 *
 * <p>A node is resolved once, at fetch time, and then sits in a JSON file while suites are
 * renamed, moved and migrated underneath it. A stale path handed to pytest aborts the whole
 * session, so every node is looked up again here before it is trusted. Ported from
 * {@code cat.plans}' {@code resolve_node}, {@code _verify_nodes} and {@code _in_plan_suite}.
 *
 * <p>This is on the Run and Config tabs' every draw: one Config read verifies every offered plan's case list, and
 * each verification needs the list of test files under {@code tests/suites} (4,000 files on the lab checkout,
 * 2026-11-10). Walked afresh on every call, that made the Overview take 3 s and the Config tab 5 s. So two things
 * are remembered, each re-checked before it is used, and neither ever answers differently from a fresh read:
 * <ul>
 *   <li>{@link #isUnmigrated} on the file's {@code (path, modified time, size)}, as the Python's {@code
 *       is_unmigrated} is: an edited file is still noticed, for one stat instead of one read;</li>
 *   <li>the suites' file list, kept with each folder's modified time and used again only while every folder still
 *       has that time (adding, removing or renaming a file changes its folder's). Re-checking costs a stat per
 *       folder (30 on the lab checkout). A listing taken within {@link #SETTLE} of a folder's change is not kept,
 *       as git treats a racy index entry: a change in the same clock tick as the walk could otherwise hide.</li>
 * </ul>
 */
public final class CaseNodes {

    /** How long a folder must have been unchanged before a listing of it is kept. */
    static final Duration SETTLE = Duration.ofSeconds(2);

    /** The most {@link #isUnmigrated} answers remembered (the Python's {@code lru_cache(maxsize=2048)}, doubled). */
    private static final int UNMIGRATED_MEMO = 4096;

    private static final Map<String, Boolean> UNMIGRATED = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > UNMIGRATED_MEMO;
        }
    };

    /** The marker a migration placeholder carries: the file exists for the reasoning only. */
    static final String UNMIGRATED_MARKER = "pytest.mark.unmigrated";

    /** The same, for an authored {@code .feature}. */
    static final String UNMIGRATED_TAG = "@unmigrated";

    /** Where {@code petrotest collect} puts pulled tests. They sort last and lose ties. */
    static final String PULLED_PREFIX = "tests/suites/pulled/";

    /**
     * A plan whose cases run from a suite folder of its own: Cadence on base 55.02 uses
     * petroapps' {@code master055.02} line, whose steps differ for some of the same keys.
     * A folder named here belongs to its plan alone.
     */
    static final Map<String, String> PLAN_SUITES = Map.of("PETROCORE-79667", "cadence_55_02");

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)\\s*$");
    private static final Pattern FEATURE_FILE = Pattern.compile("PETROCORE-(.+)-TestCase\\.feature");
    private static final Pattern PY_FILE = Pattern.compile("PETROCORE-(.+)-TestCase\\.py");
    private static final Pattern PLAIN_PY_FILE = Pattern.compile("test_petrocore_(.+)\\.py");
    private static final List<Pattern> FILE_PATTERNS = List.of(FEATURE_FILE, PY_FILE, PLAIN_PY_FILE);

    private final Path root;
    private final Clock clock;

    /** The kept listing of the suites, or null; guarded by {@code this}. */
    private Listing listing;
    private int walks;

    /** The suites' test files in {@link #suiteFiles} order, and every folder's modified time when they were read. */
    private record Listing(List<Path> files, Map<Path, FileTime> folders) {}

    CaseNodes(Path repoRoot) {
        this(repoRoot, Clock.systemUTC());
    }

    /** With the clock that decides when a listing is old enough to keep (tests). */
    CaseNodes(Path repoRoot, Clock clock) {
        this.root = repoRoot.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** How many times the suites have been walked (tests). */
    synchronized int walks() {
        return walks;
    }

    /** {@code PETROCORE-61334} to its test file, or empty for Not Run (D7). */
    Optional<String> resolve(String caseKey) {
        Optional<String> number = numberOf(caseKey);
        if (number.isEmpty()) {
            return Optional.empty();
        }
        for (Path file : suiteFiles()) {
            if (number.get().equals(numberOfFile(file.getFileName().toString()).orElse(null))) {
                return isUnmigrated(file) ? Optional.empty() : Optional.of(posix(file));
            }
        }
        return Optional.empty();
    }

    /**
     * Re-checks every row's node against this checkout, in both directions: a stale node is
     * looked up again, and a Not Run row (or one whose file has since been migrated) takes the
     * file the lookup finds.
     */
    List<PlanCase> verify(List<PlanCase> rows) {
        boolean[] keep = new boolean[rows.size()];
        boolean allKept = true;
        for (int i = 0; i < rows.size(); i++) {
            String node = rows.get(i).node();
            keep[i] = node != null
                    && !node.startsWith(PULLED_PREFIX)
                    && isRunnable(root.resolve(node))
                    && !featureBeside(root.resolve(node));
            allKept &= keep[i];
        }
        Map<String, Path> files = allKept ? Map.of() : firstByNumber(suiteFiles());
        List<PlanCase> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            PlanCase row = rows.get(i);
            out.add(keep[i] ? row : resolved(row, files));
        }
        return out;
    }

    /** The case rows of a {@link #PLAN_SUITES} plan, each pointed at its own folder's file. */
    List<PlanCase> inPlanSuite(String planKey, List<PlanCase> rows) {
        String folder = PLAN_SUITES.get(planKey);
        if (folder == null) {
            return rows;
        }
        Path dir = root.resolve("tests").resolve("suites").resolve(folder);
        // The folder is listed once for the whole plan, not once per case.
        Set<String> names = names(dir);
        List<PlanCase> out = new ArrayList<>(rows.size());
        for (PlanCase row : rows) {
            Optional<String> number = numberOf(row.key());
            Optional<String> node = number.isEmpty() ? Optional.empty()
                    : ownFolderFile(dir, names, number.get()).map(this::posix);
            out.add(node.isPresent() ? new PlanCase(row.key(), row.summary(), node.get()) : row);
        }
        return out;
    }

    /**
     * True when {@code file} is a migration placeholder rather than a ported test. Never throws. Remembered on the
     * file's {@code (path, modified time, size)}, as the Python's {@code is_unmigrated}: an edited file is read again.
     */
    public static boolean isUnmigrated(Path file) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return false;
        }
        String key = file.toAbsolutePath() + "|" + attributes.lastModifiedTime().toMillis() + "|" + attributes.size();
        synchronized (UNMIGRATED) {
            Boolean known = UNMIGRATED.get(key);
            if (known != null) {
                return known;
            }
        }
        String name = file.getFileName().toString();
        String marker = name.endsWith(".feature") ? UNMIGRATED_TAG : UNMIGRATED_MARKER;
        boolean placeholder;
        try {
            placeholder = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(marker);
        } catch (IOException e) {
            // Not a placeholder: the honest default. Calling a real case Not Run because of a
            // permissions error would be the silent mistake. Not remembered: the next read may work.
            return false;
        }
        synchronized (UNMIGRATED) {
            UNMIGRATED.put(key, placeholder);
        }
        return placeholder;
    }

    /** The file is present and is not a placeholder. */
    private static boolean isRunnable(Path file) {
        return Files.isRegularFile(file) && !isUnmigrated(file);
    }

    /** Whether {@code PETROCORE-<n>-TestCase.py} has its {@code .feature} beside it. */
    private static boolean featureBeside(Path file) {
        String name = file.getFileName().toString();
        String suffix = "-TestCase.py";
        if (!name.endsWith(suffix)) {
            return false;
        }
        String stem = name.substring(0, name.length() - suffix.length());
        return Files.isRegularFile(file.resolveSibling(stem + "-TestCase.feature"));
    }

    private PlanCase resolved(PlanCase row, Map<String, Path> files) {
        Optional<String> number = numberOf(row.key());
        Path file = number.map(files::get).orElse(null);
        String node = file == null || isUnmigrated(file) ? null : posix(file);
        return Objects.equals(node, row.node()) ? row : new PlanCase(row.key(), row.summary(), node);
    }

    /**
     * {@code _in_plan_suite}: {@code dir}'s own file of the case number, first by name, present and not a placeholder.
     * Shared with {@link LocalSuites}.
     */
    static Optional<Path> ownFolderFile(Path dir, String number) {
        return ownFolderFile(dir, names(dir), number);
    }

    /** As {@link #ownFolderFile(Path, String)}, with {@code dir}'s file names already read. */
    private static Optional<Path> ownFolderFile(Path dir, Set<String> names, String number) {
        // The two names in sorted order: in one folder the .feature wins.
        for (String name : List.of("PETROCORE-" + number + "-TestCase.feature", "PETROCORE-" + number + "-TestCase.py")) {
            if (names.contains(name) && isRunnable(dir.resolve(name))) {
                return Optional.of(dir.resolve(name));
            }
        }
        return Optional.empty();
    }

    /** The names in {@code dir}, or none when it is not a readable folder. */
    private static Set<String> names(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        try (Stream<Path> listing = Files.list(dir)) {
            listing.forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            return Set.of();
        }
        return names;
    }

    /**
     * Every test file under {@code tests/suites}, in the order {@code resolve_node} takes them:
     * by path, with anything under {@link #PULLED_PREFIX} last. A plan-owned folder is left out.
     * The kept listing while no folder has changed since it was read (see the class comment), else a fresh walk.
     */
    private synchronized List<Path> suiteFiles() {
        Path suites = root.resolve("tests").resolve("suites");
        if (listing != null && unchanged(listing.folders())) {
            return listing.files();
        }
        listing = null;
        if (!Files.isDirectory(suites)) {
            return List.of();
        }
        Instant started = clock.instant();
        walks++;
        Map<Path, FileTime> folders = new HashMap<>();
        Map<Path, String> found = new HashMap<>();
        try {
            Files.walkFileTree(suites, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Read as unchanged() reads it, not from the parent's listing, which NTFS may update late.
                    // A folder whose time will not read is recorded as changing now, so the listing is not kept.
                    try {
                        folders.put(dir, Files.getLastModifiedTime(dir));
                    } catch (IOException e) {
                        folders.put(dir, FileTime.from(started));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // The walk's own attributes: no second stat per file.
                    if (attrs.isRegularFile() && numberOfFile(file.getFileName().toString()).isPresent()) {
                        String posix = posix(file);
                        if (!planOwned(posix)) {
                            found.put(file, posix);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        List<Path> files = new ArrayList<>(found.keySet());
        files.sort(Comparator.comparing((Path p) -> found.get(p).startsWith(PULLED_PREFIX))
                .thenComparing(found::get));
        List<Path> kept = List.copyOf(files);
        Instant settled = started.minus(SETTLE);
        if (folders.values().stream().allMatch(time -> time.toInstant().isBefore(settled))) {
            listing = new Listing(kept, Map.copyOf(folders));
        }
        return kept;
    }

    /** Whether every folder of a kept listing is still there with the modified time it had. */
    private static boolean unchanged(Map<Path, FileTime> folders) {
        for (Map.Entry<Path, FileTime> folder : folders.entrySet()) {
            try {
                if (!Files.getLastModifiedTime(folder.getKey()).equals(folder.getValue())) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Path> firstByNumber(List<Path> files) {
        Map<String, Path> byNumber = new HashMap<>();
        for (Path file : files) {
            numberOfFile(file.getFileName().toString()).ifPresent(n -> byNumber.putIfAbsent(n, file));
        }
        return byNumber;
    }

    /** Under a {@link #PLAN_SUITES} folder, which only its own plan resolves into. */
    private static boolean planOwned(String posix) {
        String[] parts = posix.split("/");
        return parts.length > 2 && PLAN_SUITES.containsValue(parts[2]);
    }

    private String posix(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The trailing digits of a case key: {@code 36847} from {@code PETROCORE-36847}. */
    static Optional<String> numberOf(String caseKey) {
        Matcher match = TRAILING_NUMBER.matcher(caseKey);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /** The case number a test file is for, in any of the three spellings the suites use. */
    static Optional<String> numberOfFile(String name) {
        for (Pattern pattern : FILE_PATTERNS) {
            Matcher match = pattern.matcher(name);
            if (match.matches()) {
                return Optional.of(match.group(1));
            }
        }
        return Optional.empty();
    }
}
