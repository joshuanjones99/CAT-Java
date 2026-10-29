package com.verifone.cat.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The class files a coverage report is built against, out of the build's {@code overlay.zip}: the Python's
 * {@code petrotest.coverage.classes}.
 *
 * <p>{@code overlay.zip} holds one {@code install/update_<foundation|viper>.<flavour>.<psc|pos>.<x>.tgz} per
 * component; each is a tar of a {@code <dir>/<x>.vpk}, itself a gzipped tar of the component's home
 * ({@code lib/*.jar}, {@code webapps/*.war}). This reads the three layers as streams ({@link TarStream}) and writes
 * only the jars and wars a report names ({@link Components#COMPONENTS}), so a 1.2 GB overlay costs a few MB of disk.
 *
 * <p>Only the {@code base} packages and the run's distribution's ({@code viper.buypass...}, and
 * {@code generic_<dist>}) are read: the other brands' packages carry their own copies of some jars. A vpk's
 * symlinks into other packages are skipped: only regular files are class files.
 *
 * <p>The result is cached beside the overlay, in {@code jacoco-classes/v2/<dist>/}, stamped with the overlay's
 * path, size and modification time (nanoseconds), so a build is unpacked once however many runs report against it.
 * The stamp has the Python's keys and values, so either CAT reuses the other's cache.
 *
 * <p>Deviation: each package is streamed out of the zip rather than read into memory first ({@code archive.read}).
 */
public final class ClassFileExtractor {

    static final String CACHE_DIR = "jacoco-classes";
    static final String STAMP = "stamp.json";

    /** {@code install/update_foundation.base.psc.fcc.V57.02.00.dev.tgz}: flavour {@code base}, side, package. */
    private static final Pattern PACKAGE =
            Pattern.compile("update_(?:foundation|viper)\\.([^.]+)\\.(psc|pos)\\.([^.]+)\\.[^/]+\\.tgz$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClassFileExtractor() {}

    /** One overlay member to read: the zip entry, and its side and package. */
    record Wanted(String member, String side, String pkg) {}

    /** {@link #extract(Path, String, boolean, Consumer)} from the cache when it is current. */
    public static ClassFiles extract(Path overlay, String dist, Consumer<String> log) {
        return extract(overlay, dist, false, log);
    }

    /**
     * Unpacks every component's class files from {@code overlay} (cached). {@code dist} is matched lower-case, as
     * the package and jar names spell it: CAT's saved Distribution is {@code Buypass}.
     */
    public static ClassFiles extract(Path overlay, String dist, boolean refresh, Consumer<String> log) {
        String lower = dist.toLowerCase(Locale.ROOT);
        // A cache from before class files were kept per package lacks the package level.
        Path root = overlay.toAbsolutePath().getParent().resolve(CACHE_DIR).resolve("v2").resolve(lower);
        Path stampPath = root.resolve(STAMP);
        Map<String, Object> stamp = stampOf(overlay);
        if (!refresh && Files.isRegularFile(stampPath)) {
            try {
                JsonNode saved = JSON.readTree(stampPath.toFile());
                if (saved != null && sameStamp(stamp, saved.path("stamp"))) {
                    return ClassFiles.fromJson(root, saved);
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable stamp is a stale one: extract again.
            }
        }
        try {
            deleteTree(root);
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // (side, package) -> the patterns of every component that reads that package.
        Map<String, Set<String>> patterns = new LinkedHashMap<>();
        for (Component component : Components.COMPONENTS) {
            for (String pkg : component.sources()) {
                patterns.computeIfAbsent(key(component.side(), pkg), k -> new LinkedHashSet<>())
                        .addAll(component.patterns(lower));
            }
        }

        // (side, package) -> file name -> extracted path. A later package (a distribution's) wins over the base one.
        Map<String, Map<String, Path>> extracted = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(overlay.toFile())) {
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = archive.entries(); e.hasMoreElements();) {
                names.add(e.nextElement().getName());
            }
            for (Wanted wanted : wantedPackages(names, lower)) {
                Set<String> want = patterns.get(key(wanted.side(), wanted.pkg()));
                if (want != null && !want.isEmpty()) {
                    Map<String, Path> into = extracted.computeIfAbsent(key(wanted.side(), wanted.pkg()),
                            k -> new LinkedHashMap<>());
                    extractPackage(archive, wanted.member(), root.resolve(wanted.side()).resolve(wanted.pkg()),
                            List.copyOf(want), into, log);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<String, List<Path>> found = new LinkedHashMap<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Component component : Components.COMPONENTS) {
            List<Path> hits = new ArrayList<>();
            List<String> misses = new ArrayList<>();
            for (String pattern : component.patterns(lower)) {
                List<Path> forPattern = List.of();
                for (String pkg : component.sources()) {
                    Map<String, Path> files = extracted.getOrDefault(key(component.side(), pkg), Map.of());
                    List<Path> matching = files.entrySet().stream()
                            .filter(entry -> Components.matches(entry.getKey(), List.of(pattern)))
                            .map(Map.Entry::getValue)
                            .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                            .toList();
                    if (!matching.isEmpty()) {
                        forPattern = matching;
                        break;
                    }
                }
                if (forPattern.isEmpty()) {
                    misses.add(pattern);
                } else {
                    hits.addAll(forPattern);
                }
            }
            found.put(component.name(), hits);
            if (!misses.isEmpty()) {
                missing.put(component.name(), misses);
            }
        }
        ClassFiles result = new ClassFiles(root, found, missing);
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("stamp", stamp);
        saved.putAll(result.toJson());
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(stampPath.toFile(), saved);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /** The overlay's path, size and modification time in nanoseconds: the Python's {@code _stamp_of}. */
    static Map<String, Object> stampOf(Path overlay) {
        try {
            Map<String, Object> stamp = new LinkedHashMap<>();
            stamp.put("overlay", overlay.toString());
            stamp.put("size", Files.size(overlay));
            stamp.put("mtime", Files.getLastModifiedTime(overlay).to(TimeUnit.NANOSECONDS));
            return stamp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The Python's {@code saved["stamp"] == stamp}: the same three keys with the same values, nothing else. */
    static boolean sameStamp(Map<String, Object> stamp, JsonNode saved) {
        if (!saved.isObject() || saved.size() != stamp.size()) {
            return false;
        }
        return saved.path("overlay").isTextual() && saved.path("overlay").asText().equals(stamp.get("overlay"))
                && saved.path("size").isIntegralNumber() && saved.path("size").asLong() == (Long) stamp.get("size")
                && saved.path("mtime").isIntegralNumber() && saved.path("mtime").asLong() == (Long) stamp.get("mtime");
    }

    /** Each component package of {@code base} or {@code dist}'s flavour, base first (a distribution's copy wins). */
    static List<Wanted> wantedPackages(List<String> names, String dist) {
        Set<String> flavours = Set.of("base", dist, "generic_" + dist);
        List<Wanted> wanted = new ArrayList<>();
        for (String name : names) {
            Matcher match = PACKAGE.matcher(name);
            if (match.find() && flavours.contains(match.group(1))) {
                wanted.add(new Wanted(name, match.group(2), match.group(3)));
            }
        }
        // A stable sort: base packages first, otherwise the overlay's own order.
        List<Wanted> sorted = new ArrayList<>(wanted);
        sorted.sort(Comparator.comparing(item -> !item.member().contains(".base.")));
        return sorted;
    }

    /**
     * One package's matching jars and wars into {@code directory}, recorded in {@code extracted}. Both layers are
     * streams, member by member. An unreadable package is a warning and skipped, as the Python's.
     */
    static void extractPackage(ZipFile archive, String member, Path directory, List<String> patterns,
            Map<String, Path> extracted, Consumer<String> log) {
        try (InputStream raw = archive.getInputStream(archive.getEntry(member));
                TarStream outer = new TarStream(raw)) {
            for (TarStream.Entry vpk = outer.next(); vpk != null; vpk = outer.next()) {
                if (!vpk.isFile() || !vpk.name().endsWith(".vpk")) {
                    continue;
                }
                TarStream inner = new TarStream(outer.data());
                for (TarStream.Entry info = inner.next(); info != null; info = inner.next()) {
                    String name = info.name().substring(info.name().lastIndexOf('/') + 1);
                    if (!info.isFile() || !Components.matches(name, patterns)) {
                        continue;
                    }
                    Path target = directory.resolve(name);
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        inner.data().transferTo(out);
                    }
                    extracted.put(name, target);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.accept("WARN  coverage: " + member + " is not a readable package (" + e.getMessage()
                    + "); skipped");
        }
    }

    private static String key(String side, String pkg) {
        return side + "/" + pkg;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
