package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which {@code configurations/} areas the snapshot can put back, and which plans import the ones it cannot. A port of
 * {@code commander_snapshot.coverage}, {@code _coverage_status}, {@code feature_import_folders} and
 * {@code case_folder_reader}.
 *
 * <p>Reads files only; nothing here talks to a Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Coverage {

    private static final Pattern IMPORT_PHRASE = Pattern.compile("the Commander imports configuration \"([^\"]*)\"");
    private static final String FEATURE_SUFFIX = "-TestCase.feature";

    private Coverage() {}

    /**
     * One area a {@code configurations/} folder can import.
     *
     * @param ok      the snapshot can put this area back (or base re-imports it every run)
     * @param folders the folders that import it
     * @param cases   {@code PLAN:CASE} that import it
     */
    public record Row(String area, String status, boolean ok, List<String> folders, List<String> cases) {

        public Row {
            folders = List.copyOf(folders);
            cases = List.copyOf(cases);
        }
    }

    /** The whole report. */
    public static final class Report {
        final Path snapshot;
        final boolean hasSnapshot;
        final List<Row> rows = new ArrayList<>();
        /** {@code PLAN:CASE} -> why its import folders could not be read. */
        final Map<String, String> unresolved = new LinkedHashMap<>();
        /** {@code PLAN:CASE} -> a folder it imports that {@code configurations/} does not hold. */
        final Map<String, List<String>> missingFolders = new LinkedHashMap<>();

        Report(Path snapshot, boolean hasSnapshot) {
            this.snapshot = snapshot;
            this.hasSnapshot = hasSnapshot;
        }

        public Path snapshot() {
            return snapshot;
        }

        public boolean hasSnapshot() {
            return hasSnapshot;
        }

        public List<Row> rows() {
            return List.copyOf(rows);
        }

        public Map<String, String> unresolved() {
            return new LinkedHashMap<>(unresolved);
        }

        public Map<String, List<String>> missingFolders() {
            return new LinkedHashMap<>(missingFolders);
        }

        /** Areas a plan imports that the snapshot cannot put back. */
        public List<Row> blocking() {
            return rows.stream().filter(r -> !r.cases().isEmpty() && !r.ok()).toList();
        }
    }

    /**
     * Every area a {@code configurations/} folder can import, the folders (and, given {@code plans} as plan key ->
     * case keys and {@code caseFolders} as case key -> the folders its IMPORT_CONFIGURATION steps name, the cases)
     * that import it, and whether the snapshot can restore it. An area is ok when the snapshot holds it and
     * {@link Areas#exclusion} allows it, or when it is excluded but {@code baseFolder} re-imports it every run.
     *
     * @param caseFolders may throw for a case it cannot read: that case is reported, not fatal; null reads none
     */
    public static Report coverage(Path snapshot, Path configurationsRoot, Map<String, List<String>> plans,
            Function<String, List<String>> caseFolders, String baseFolder) {
        Map<String, List<String>> files = Areas.configurationFiles(configurationsRoot);
        Map<String, Object> manifest = Snapshots.loadManifest(snapshot);
        Report report = new Report(snapshot, manifest != null);
        Map<String, String> problems = manifest != null ? Capture.snapshotErrors(snapshot, manifest) : Map.of();
        Map<String, List<String>> importers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> plan : (plans == null ? Map.<String, List<String>>of() : plans).entrySet()) {
            for (String key : plan.getValue()) {
                String label = plan.getKey() + ":" + key;
                List<String> folders;
                try {
                    folders = caseFolders == null ? List.of() : List.copyOf(caseFolders.apply(key));
                } catch (RuntimeException e) { // a case that cannot be read is reported, not fatal
                    report.unresolved.put(label, String.valueOf(e.getMessage()));
                    continue;
                }
                for (String folder : folders) {
                    List<String> held = new ArrayList<>();
                    files.forEach((area, fs) -> {
                        if (fs.contains(folder)) {
                            held.add(area);
                        }
                    });
                    if (held.isEmpty()) {
                        report.missingFolders.computeIfAbsent(label, k -> new ArrayList<>()).add(folder);
                    }
                    for (String area : held) {
                        importers.computeIfAbsent(area, k -> new ArrayList<>()).add(label);
                    }
                }
            }
        }
        Set<String> sorted = new TreeSet<>(Areas.ORDER);
        sorted.addAll(files.keySet());
        for (String area : sorted) {
            String[] status = status(area, manifest, files.get(area).contains(baseFolder));
            String problem = problems.containsKey(area) ? problems.get(area) : problems.get("snapshot");
            boolean ok = Boolean.parseBoolean(status[1]);
            String text = status[0];
            if (problem != null && Areas.exclusion(area) == null) {
                text = "capture failed: " + problem;
                ok = false;
            }
            report.rows.add(new Row(area, text, ok, files.get(area), importers.getOrDefault(area, List.of())));
        }
        return report;
    }

    /** {@link #coverage(Path, Path, Map, Function, String)} with {@code base} as the base folder. */
    public static Report coverage(Path snapshot, Path configurationsRoot, Map<String, List<String>> plans,
            Function<String, List<String>> caseFolders) {
        return coverage(snapshot, configurationsRoot, plans, caseFolders, "base");
    }

    private static String[] status(String area, Map<String, Object> manifest, boolean baseImports) {
        if (manifest == null) {
            return new String[] {"no snapshot", "false"};
        }
        String reason = Areas.exclusion(area);
        Object areas = manifest.get("areas");
        Object failed = manifest.get("failed");
        if (areas instanceof Map<?, ?> held && held.containsKey(area)) {
            if (reason == null) {
                return new String[] {"restorable", "true"};
            }
            if (baseImports) {
                return new String[] {"excluded, base re-imports it: " + reason, "true"};
            }
            return new String[] {"excluded: " + reason, "false"};
        }
        if (failed instanceof Map<?, ?> f && f.containsKey(area)) {
            return new String[] {"capture failed: " + f.get(area), "false"};
        }
        if (reason != null && !reason.equals(Areas.UNREVIEWED) && baseImports) {
            return new String[] {"excluded, base re-imports it: " + reason, "true"};
        }
        return new String[] {"not in the snapshot (no v/u pair in the funcList it was captured with)", "false"};
    }

    /** The folders a CAT {@code .feature} case imports ({@code the Commander imports configuration "<folder>"}). */
    public static List<String> featureImportFolders(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> out = new ArrayList<>();
        Matcher m = IMPORT_PHRASE.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Case key -> the folders it imports: from the {@code .feature} CAT runs when there is one under
     * {@code suitesRoot} ({@code <suite>/<key>-TestCase.feature}), else from {@code fallback}.
     *
     * @param fallback reads a case that has no {@code .feature}; null makes such a case unreadable
     */
    public static Function<String, List<String>> caseFolderReader(Path suitesRoot,
            Function<String, List<String>> fallback) {
        Map<String, Path> features = new LinkedHashMap<>();
        if (suitesRoot != null && Files.isDirectory(suitesRoot)) {
            List<Path> found = new ArrayList<>();
            try (Stream<Path> suites = Files.list(suitesRoot)) {
                for (Path suite : suites.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> cases = Files.list(suite)) {
                        cases.filter(p -> p.getFileName().toString().endsWith(FEATURE_SUFFIX)
                                && Files.isRegularFile(p)).forEach(found::add);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            found.sort(null);
            for (Path path : found) {
                String name = path.getFileName().toString();
                features.putIfAbsent(name.substring(0, name.length() - FEATURE_SUFFIX.length()), path);
            }
        }
        return key -> {
            if (features.containsKey(key)) {
                return featureImportFolders(features.get(key));
            }
            if (fallback == null) {
                throw new IllegalStateException(key + ": no .feature under " + suitesRoot + ", and no petroapps");
            }
            return fallback.apply(key);
        };
    }
}
