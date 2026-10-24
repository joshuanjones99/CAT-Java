package com.verifone.cat.plans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>The Python version memoises {@code is_unmigrated} on the file's stat; this one reads the
 * file on each call. Nothing in Java calls this on a hot path yet, so the cost is not measured.
 */
final class CaseNodes {

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

    private final Path root;

    CaseNodes(Path repoRoot) {
        this.root = repoRoot.toAbsolutePath().normalize();
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
        List<PlanCase> out = new ArrayList<>(rows.size());
        for (PlanCase row : rows) {
            Optional<String> number = numberOf(row.key());
            Optional<String> node = number.isEmpty() ? Optional.empty() : ownFolderNode(dir, number.get());
            out.add(node.isPresent() ? new PlanCase(row.key(), row.summary(), node.get()) : row);
        }
        return out;
    }

    /** True when {@code file} is a migration placeholder rather than a ported test. Never throws. */
    static boolean isUnmigrated(Path file) {
        String name = file.getFileName().toString();
        String marker = name.endsWith(".feature") ? UNMIGRATED_TAG : UNMIGRATED_MARKER;
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(marker);
        } catch (IOException e) {
            // Not a placeholder: the honest default. Calling a real case Not Run because of a
            // permissions error would be the silent mistake.
            return false;
        }
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

    private Optional<String> ownFolderNode(Path dir, String number) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String feature = "PETROCORE-" + number + "-TestCase.feature";
        String python = "PETROCORE-" + number + "-TestCase.py";
        try (Stream<Path> listing = Files.list(dir)) {
            return listing
                    .filter(p -> p.getFileName().toString().equals(feature)
                            || p.getFileName().toString().equals(python))
                    .sorted()
                    .filter(CaseNodes::isRunnable)
                    .findFirst()
                    .map(this::posix);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Every test file under {@code tests/suites}, in the order {@code resolve_node} takes them:
     * by path, with anything under {@link #PULLED_PREFIX} last. A plan-owned folder is left out.
     */
    private List<Path> suiteFiles() {
        Path suites = root.resolve("tests").resolve("suites");
        if (!Files.isDirectory(suites)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(suites)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> numberOfFile(p.getFileName().toString()).isPresent())
                    .filter(p -> !planOwned(p))
                    .forEach(found::add);
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparing((Path p) -> posix(p).startsWith(PULLED_PREFIX))
                .thenComparing(this::posix));
        return found;
    }

    private Map<String, Path> firstByNumber(List<Path> files) {
        Map<String, Path> byNumber = new HashMap<>();
        for (Path file : files) {
            numberOfFile(file.getFileName().toString()).ifPresent(n -> byNumber.putIfAbsent(n, file));
        }
        return byNumber;
    }

    /** Under a {@link #PLAN_SUITES} folder, which only its own plan resolves into. */
    private boolean planOwned(Path file) {
        String[] parts = posix(file).split("/");
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
        for (Pattern pattern : List.of(FEATURE_FILE, PY_FILE, PLAIN_PY_FILE)) {
            Matcher match = pattern.matcher(name);
            if (match.matches()) {
                return Optional.of(match.group(1));
            }
        }
        return Optional.empty();
    }
}
