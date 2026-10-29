package com.verifone.cat.plans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The hand-migrated suites a run takes its cases from before it pulls any from petroapps: the Python CAT's
 * {@code tests/suites/**}, read in place. CAT-Java has no suites of its own; the Python checkout
 * ({@code $CAT_PYTHON_ROOT}, default {@code C:\gitrepos\CAT}, as {@code provision.ProvisionPaths}) holds them.
 * Nothing here writes into that checkout.
 *
 * <p>Ported from {@code cat.plans}: {@code resolve_node}, {@code _case_files}, {@code _suite_order},
 * {@code _plan_owned}, {@code _in_plan_suite} and {@code is_unmigrated}. A case of plan {@code P} resolves, in this
 * order:
 * <ol>
 *   <li>when {@code P} owns a folder ({@link CaseNodes#PLAN_SUITES}: PETROCORE-79667 owns {@code cadence_55_02}), the
 *       first runnable {@code PETROCORE-<n>-TestCase.feature} or {@code .py} directly in that folder, sorted by name
 *       (the {@code .feature} sorts first);</li>
 *   <li>otherwise, the first file of the case's number under {@code tests/suites/**} in any of the three spellings
 *       ({@code PETROCORE-<n>-TestCase.feature}, {@code PETROCORE-<n>-TestCase.py}, {@code test_petrocore_<n>.py}),
 *       sorted by repository-relative path, leaving out every plan-owned folder: a folder named in
 *       {@code PLAN_SUITES} is resolved into by its own plan only, so 118517 and 135127 (base 57.02) take
 *       {@code tests/suites/cadence/} and never {@code cadence_55_02/}. When that first file is an
 *       {@code @unmigrated} placeholder the case is {@link Kind#UNMIGRATED} (Not Run, D7), as {@code resolve_node}
 *       returns {@code None} for it rather than trying the next file;</li>
 *   <li>else {@link Kind#ABSENT}: no local suite holds it, and the run pulls it from petroapps.</li>
 * </ol>
 *
 * <p>Deviation: the Python checkout's own {@code tests/suites/pulled/} is left out. In the Python it sorts last and
 * loses every tie (decision A4), so it only ever supplies a case no hand-migrated file holds; that is exactly the
 * case CAT-Java pulls fresh into its own {@code tests/suites/pulled/}, and the Python's copy may be from another
 * branch or an older run.
 */
public final class LocalSuites {

    /** What a case key resolves to under the local suites. */
    public enum Kind {
        /** A hand-migrated test: run it from its own folder, its {@code -CAT*} baselines beside it. */
        LOCAL,
        /** A migration placeholder: Not Run, and not pulled (A4: the hand-migrated file wins over a pulled one). */
        UNMIGRATED,
        /** No local suite holds the case: pull it from petroapps. */
        ABSENT
    }

    /**
     * One case's resolution.
     *
     * @param kind what was found
     * @param file the test file (absolute) for {@link Kind#LOCAL}, the placeholder for {@link Kind#UNMIGRATED}, else null
     * @param node {@code file} relative to the Python root, POSIX separators, as the Python reports a node; or null
     */
    public record Resolution(Kind kind, Path file, String node) {

        static final Resolution ABSENT = new Resolution(Kind.ABSENT, null, null);
    }

    private static final String PULLED = "pulled";

    private final Path root;
    private Map<String, Path> firstByNumber;

    /** @param pythonRoot the Python CAT checkout, whose {@code tests/suites} holds the hand-migrated suites */
    public LocalSuites(Path pythonRoot) {
        this.root = pythonRoot.toAbsolutePath().normalize();
    }

    /** The checkout this reads. */
    public Path root() {
        return root;
    }

    /** Where case {@code caseKey} of plan {@code planKey} runs from, by the precedence in the class Javadoc. */
    public Resolution resolve(String planKey, String caseKey) {
        Optional<String> number = CaseNodes.numberOf(caseKey);
        if (number.isEmpty()) {
            return Resolution.ABSENT;
        }
        String folder = planKey == null ? null : CaseNodes.PLAN_SUITES.get(planKey);
        if (folder != null) {
            Optional<Path> own = ownFolder(suites().resolve(folder), number.get());
            if (own.isPresent()) {
                return new Resolution(Kind.LOCAL, own.get(), posix(own.get()));
            }
        }
        Path file = index().get(number.get());
        if (file == null) {
            return Resolution.ABSENT;
        }
        return new Resolution(CaseNodes.isUnmigrated(file) ? Kind.UNMIGRATED : Kind.LOCAL, file, posix(file));
    }

    private Path suites() {
        return root.resolve("tests").resolve("suites");
    }

    /** {@code _in_plan_suite}: the folder's own file of the number, first by name, present and not a placeholder. */
    private static Optional<Path> ownFolder(Path dir, String number) {
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
                    .filter(p -> Files.isRegularFile(p) && !CaseNodes.isUnmigrated(p))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Case number to its first test file in {@code _suite_order}, plan-owned folders and pulled left out; one walk. */
    private Map<String, Path> index() {
        if (firstByNumber != null) {
            return firstByNumber;
        }
        Path suites = suites();
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(suites)) {
            try (Stream<Path> walk = Files.walk(suites)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> CaseNodes.numberOfFile(p.getFileName().toString()).isPresent())
                        .filter(p -> !excluded(suites, p))
                        .forEach(found::add);
            } catch (IOException e) {
                found.clear();
            }
        }
        found.sort(Comparator.comparing(this::posix));
        Map<String, Path> byNumber = new HashMap<>();
        for (Path file : found) {
            CaseNodes.numberOfFile(file.getFileName().toString()).ifPresent(n -> byNumber.putIfAbsent(n, file));
        }
        firstByNumber = byNumber;
        return byNumber;
    }

    /** Under a plan-owned folder ({@code _plan_owned}) or the Python's own pulled folder. */
    private static boolean excluded(Path suites, Path file) {
        Path rel = suites.relativize(file);
        if (rel.getNameCount() < 2) {
            return false;
        }
        String top = rel.getName(0).toString();
        return PULLED.equals(top) || CaseNodes.PLAN_SUITES.containsValue(top);
    }

    private String posix(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
