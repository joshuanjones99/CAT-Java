package com.verifone.cat.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where downloaded builds live on this PC, and which of them are already here.
 *
 * <p>The layout is PATS's: {@code <root>\<build>\<dist>\}, where {@code <build>} is
 * {@code Solutions<n>} for the Solutions branch and {@code <branch>-<n>} for any other
 * ({@code master-268}). The root is {@code C:\CICD\Builds}, and {@code CAT_BUILDS_ROOT} moves it.
 * It is never inside the repository.
 *
 * <p>Every resolver takes its environment as a {@link Map} (see
 * {@link com.verifone.cat.report.ReportPaths}), so a test points the root at a {@code @TempDir}.
 *
 * <p>"Newest" for a local build is the overlay's own date (its newest zip entry), then the
 * build number, then the folder's mtime. The number alone is not enough: Solutions numbering
 * restarted after 1097, so build 65 can be newer than 1097 (HANDOFF.md §1.99).
 *
 * <p>The Python CAT's {@code petrotest.build.discovery}, {@code petrotest.build.solutions}
 * (local-discovery half) and the naming in {@code petrotest.build.nightly}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run on staged folders, not on the C:\CICD tree.
 */
public final class BuildDiscovery {

    /** The environment variable that moves the builds root. */
    public static final String BUILDS_ROOT_ENV = "CAT_BUILDS_ROOT";

    /** The documented default: PATS's {@code Builds} folder. A path, not an address. */
    public static final Path DEFAULT_BUILDS_ROOT = Path.of("C:/CICD/Builds");

    /** The Solutions branch's name, which names its folders {@code Solutions<n>}. */
    public static final String SOLUTIONS = "Solutions";

    /** The overlay file every dist folder carries. */
    public static final String OVERLAY = "overlay.zip";

    private static final Pattern SOLUTIONS_BUILD = Pattern.compile("^Solutions(\\d+)$");
    private static final Pattern BRANCH_BUILD = Pattern.compile("^(.+)-(\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");
    /** A dist's folder name is one word: {@code buypass}, {@code testFep}, {@code Phillips66}. */
    private static final Pattern DIST_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** A downloaded build folder's name, split. */
    public record BuildName(String branch, String number) {}

    private BuildDiscovery() {}

    /** {@code CAT_BUILDS_ROOT} from {@code env}, else {@link #DEFAULT_BUILDS_ROOT}. Blank counts as unset. */
    public static Path buildsRoot(Map<String, String> env) {
        String configured = env.getOrDefault(BUILDS_ROOT_ENV, "").strip();
        return configured.isEmpty() ? DEFAULT_BUILDS_ROOT : Path.of(configured);
    }

    /** {@code "1082"} gives {@code "Solutions1082"}; a name already so is returned as it is. */
    public static String buildName(String number) {
        String name = number.strip();
        return name.startsWith(SOLUTIONS) ? name : SOLUTIONS + name;
    }

    /**
     * The build number, digits only.
     *
     * @throws BuildException when it is not a number
     */
    public static String buildNumber(String build) {
        String number = build == null ? "" : build.strip();
        if (!NUMBER.matcher(number).matches()) {
            throw new BuildException("not a build number: " + build);
        }
        return number;
    }

    /**
     * The local folder a build lands in: {@code Solutions65}, {@code master-268}. A branch's
     * {@code /} becomes {@code _}.
     */
    public static String buildLabel(String branch, String build) {
        String name = ArtifactoryClient.trimSlashes(branch);
        if (SOLUTIONS.equals(name)) {
            return SOLUTIONS + buildNumber(build);
        }
        return name.replace('/', '_') + "-" + buildNumber(build);
    }

    /** Where a dist's files for a build go: {@code <root>\<build_label>\<dist>}. */
    public static Path buildDir(String branch, String build, String dist, Path root) {
        String label = SOLUTIONS.equals(ArtifactoryClient.trimSlashes(branch))
                ? SOLUTIONS + buildNumber(build)
                : buildLabel(branch, build);
        return root.resolve(label).resolve(dist);
    }

    /**
     * Splits a build folder's name: {@code Solutions1082} gives {@code (Solutions, 1082)} and
     * {@code master-268} gives {@code (master, 268)}. Empty for a name that is neither.
     */
    public static Optional<BuildName> parseBuildName(String folderName) {
        Matcher solutions = SOLUTIONS_BUILD.matcher(folderName);
        if (solutions.matches()) {
            return Optional.of(new BuildName(SOLUTIONS, solutions.group(1)));
        }
        Matcher branch = BRANCH_BUILD.matcher(folderName);
        if (branch.matches()) {
            return Optional.of(new BuildName(branch.group(1), branch.group(2)));
        }
        return Optional.empty();
    }

    /**
     * Every already-downloaded {@code <build>\<dist>} under {@code root} that holds the overlay
     * and every file in {@code extraFiles}, newest first. {@code dist} matches case-insensitively,
     * and {@code null} or blank matches any dist. An absent or unreadable root gives an empty list.
     */
    public static List<Path> localBuildDirs(Path root, String dist, List<String> extraFiles) {
        List<String> needed = new ArrayList<>();
        needed.add(OVERLAY);
        needed.addAll(extraFiles);
        List<Path> candidates = new ArrayList<>();
        for (Path build : subdirs(root)) {
            for (Path distDir : subdirs(build)) {
                if (dist != null && !dist.isBlank()
                        && !distDir.getFileName().toString().equalsIgnoreCase(dist)) {
                    continue;
                }
                if (needed.stream().allMatch(name -> Files.isRegularFile(distDir.resolve(name)))) {
                    candidates.add(distDir);
                }
            }
        }
        candidates.sort(newestFirst());
        return candidates;
    }

    /** The newest {@link #localBuildDirs} entry, or empty when none qualifies. */
    public static Optional<Path> latestBuildDir(Path root, String dist, List<String> extraFiles) {
        List<Path> candidates = localBuildDirs(root, dist, extraFiles);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * Each dist that some downloaded build has an overlay for, once whatever the folders' case,
     * in name order. A folder whose name is not one word is not a dist.
     */
    public static List<String> localDists(Path root) {
        TreeMap<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path build : subdirs(root)) {
            for (Path distDir : subdirs(build)) {
                String name = distDir.getFileName().toString();
                if (DIST_NAME.matcher(name).matches() && Files.isRegularFile(distDir.resolve(OVERLAY))) {
                    found.putIfAbsent(name, name);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** When the build in {@code distDir} was built, {@code yyyy-MM-dd HH:mm}, or {@code ""} if unknown. */
    public static String buildDate(Path distDir) {
        LocalDateTime built = builtAt(distDir);
        return built == null ? "" : built.format(STAMP);
    }

    /**
     * The overlay's newest zip entry, read from the central directory only, or {@code null} when
     * the overlay is absent or unreadable.
     */
    static LocalDateTime builtAt(Path distDir) {
        try (ZipFile zip = new ZipFile(distDir.resolve(OVERLAY).toFile())) {
            LocalDateTime newest = null;
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.getTime() < 0) {
                    continue;
                }
                LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getTime()), ZoneId.systemDefault());
                if (newest == null || at.isAfter(newest)) {
                    newest = at;
                }
            }
            return newest;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Newest first: build date (an unknown date last), then build number, then folder mtime. */
    private static Comparator<Path> newestFirst() {
        Comparator<Path> byDate = Comparator.comparing(BuildDiscovery::builtAt,
                Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()));
        Comparator<Path> byNumber = Comparator.comparingInt(BuildDiscovery::buildNumberOf);
        Comparator<Path> byMtime = Comparator.comparingLong(BuildDiscovery::mtime);
        return byDate.thenComparing(byNumber).thenComparing(byMtime).reversed();
    }

    private static int buildNumberOf(Path distDir) {
        Path build = distDir.getParent();
        if (build == null) {
            return -1;
        }
        Matcher match = TRAILING_NUMBER.matcher(build.getFileName().toString());
        return match.find() ? Integer.parseInt(match.group(1)) : -1;
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Subfolders of {@code dir}, by name; empty when {@code dir} is absent or unreadable. */
    private static List<Path> subdirs(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
