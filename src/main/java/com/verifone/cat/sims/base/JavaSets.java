package com.verifone.cat.sims.base;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.build.ArtifactoryException;

/**
 * The two Java 17 sets CAT's simulators run on, which one a Commander's base wants, and staging the one that is
 * missing from Artifactory: the Python CAT's {@code cat.sims.java} ({@code JAVA_SETS}, {@code base_of},
 * {@code base_major}, {@code java_for_base}, {@code holds_set}, {@code ensure_java}).
 *
 * <p>Rig owner, 2026-10-09: base 56 and earlier run the Petro Simulator Suite on v1.1 ({@code <tools>/Java17v1.1}),
 * base 57 and later on v1.2 ({@code <tools>/Java17}). A set is there only if its {@code jaxb-ri/mod} carries the set's
 * JAXB namespace (v1.2 Jakarta, v1.1 javax): a {@code Java17} that is a copy of v1.1 is not v1.2. Such a folder is
 * renamed aside, kept, and the real set downloaded in its place.
 *
 * <p>Deviation: {@link #ensureJava} takes the Artifactory client as a supplier and makes it only when a set has to be
 * downloaded. The Python opens its client first, so a controller with no Artifactory login fails step 1 even with both
 * sets staged; here it fails only when something has to be fetched.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory} with zips shaped
 * as the Python's tests record the real ones (v1.2 holds {@code Java17/Java17/}, v1.1 {@code Java17/}).
 */
public final class JavaSets {

    /** Where both sets are published. */
    public static final String JAVA_REPO = "RMS_Release_CLW/Petro/ToolsInternal/Java17_automation";

    /** The first base that wants v1.2. */
    public static final int V12_FROM_BASE = 57;

    /**
     * One published Java 17 set and where it goes under the tools root.
     *
     * @param version  {@code v1.2} or {@code v1.1}
     * @param target   the folder under the tools root ({@code Java17}, {@code Java17v1.1})
     * @param holds    the folder inside the zip whose contents become {@code target}
     * @param forBases which Commanders want it, for the log line
     * @param file     the zip's name
     */
    public record JavaSet(String version, String target, String holds, String forBases, String file) {

        /** The zip's repo path. */
        public String remote() {
            return JAVA_REPO + "/" + version + "/" + file;
        }
    }

    /** v1.2 for base 57 and later; CAT's java home. */
    public static final JavaSet V12 = new JavaSet("v1.2", "Java17", "Java17/Java17", "Commander base 57 and later",
            "Java17.zip");

    /** v1.1 for base 56 and earlier; the Petro row's fallback. */
    public static final JavaSet V11 = new JavaSet("v1.1", "Java17v1.1", "Java17", "Commander base 56 and earlier",
            "Java17.zip");

    /** Both sets, v1.2 first, as the Python's {@code JAVA_SETS}. */
    public static final List<JavaSet> SETS = List.of(V12, V11);

    /** The JAXB namespace each set's {@code jaxb-ri/mod} carries. */
    public static final Map<String, String> JAXB_NAMESPACE = Map.of("v1.2", "jakarta", "v1.1", "javax");

    /** A base's major and minor, from a {@code Base_Level} ({@code 055.02.09}) or a branch ({@code master055.02}). */
    public record Base(int major, int minor) {

        /** As the Python formats it: {@code 055.02}. */
        public String text() {
            return String.format("%03d.%02d", major, minor);
        }
    }

    private static final Pattern BASE = Pattern.compile("(?<!\\d)0?(\\d{2})\\.(\\d{2})");

    private JavaSets() {
    }

    /** The base named in {@code text}, or empty: the Python's {@code base_of}. */
    public static Optional<Base> baseOf(String text) {
        Matcher match = BASE.matcher(text == null ? "" : text);
        if (!match.find()) {
            return Optional.empty();
        }
        return Optional.of(new Base(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))));
    }

    /** The base's major number in {@code text}, or empty: the Python's {@code base_major}. */
    public static Optional<Integer> baseMajor(String text) {
        return baseOf(text).map(Base::major);
    }

    /** v1.2 for base 57 and later, v1.1 below it. */
    public static JavaSet javaForBase(int major) {
        return major >= V12_FROM_BASE ? V12 : V11;
    }

    /** The set a run on {@code base} launches on; an unreadable base is v1.2, as the Python's {@code java_for_rig}. */
    public static JavaSet javaForRun(String base) {
        return javaForBase(baseMajor(base).orElse(V12_FROM_BASE));
    }

    /** True when {@code base} is below 57: the registry's base-56-and-earlier forms. Unreadable is 57. */
    public static boolean olderBase(String base) {
        return baseMajor(base).orElse(V12_FROM_BASE) < V12_FROM_BASE;
    }

    /** {@code jakarta} or {@code javax}: the JAXB API the first of {@code jars} to carry one holds. */
    public static Optional<String> jaxbNamespace(List<Path> jars) {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (String namespace : List.of("jakarta", "javax")) {
                    if (zip.getEntry(namespace + "/xml/bind/JAXBException.class") != null) {
                        return Optional.of(namespace);
                    }
                }
            } catch (IOException e) {
                // Not a jar, or unreadable: the next one may say.
            }
        }
        return Optional.empty();
    }

    /** Whether {@code <toolsRoot>/<target>} is that set, not merely a folder by its name. */
    public static boolean holdsSet(JavaSet java, Path toolsRoot) {
        Path mod = toolsRoot.resolve(java.target()).resolve("jaxb-ri").resolve("mod");
        if (!Files.isDirectory(mod)) {
            return false;
        }
        List<Path> jars;
        try (Stream<Path> children = Files.list(mod)) {
            jars = children.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            return false;
        }
        return jaxbNamespace(jars).map(JAXB_NAMESPACE.get(java.version())::equals).orElse(false);
    }

    /** The sets not under {@code toolsRoot}: no folder, or a folder holding another set. */
    public static List<JavaSet> missing(Path toolsRoot) {
        List<JavaSet> out = new ArrayList<>();
        for (JavaSet java : SETS) {
            if (!holdsSet(java, toolsRoot)) {
                out.add(java);
            }
        }
        return out;
    }

    /**
     * Stages {@code java} unless {@code toolsRoot} already holds it. Returns {@code ""}, or why it is not there.
     * Never throws: no login, an unreachable server, a cut-short download and a zip of the wrong shape are each the
     * returned problem.
     *
     * @param clients makes the Artifactory client; asked only when a download is needed
     */
    public static String ensureJava(JavaSet java, Path toolsRoot, Supplier<ArtifactoryClient> clients,
            Consumer<String> log) {
        if (holdsSet(java, toolsRoot)) {
            return "";
        }
        try {
            return stage(java, toolsRoot, clients.get(), log);
        } catch (RuntimeException e) {
            return "Java 17 " + java.version() + ": " + artifactoryFailure(e);
        } catch (IOException e) {
            return "Java 17 " + java.version() + ": " + artifactoryFailure(new UncheckedIOException(e));
        }
    }

    /**
     * Downloads every set in {@link #missing}: the Python's {@code fetch_missing_java}. One problem per set that
     * failed, else empty. Never throws; the client is asked for only when a set is missing.
     */
    public static List<String> fetchMissingJava(Path toolsRoot, Supplier<ArtifactoryClient> clients,
            Consumer<String> log) {
        List<String> problems = new ArrayList<>();
        for (JavaSet java : missing(toolsRoot)) {
            String problem;
            try {
                problem = stage(java, toolsRoot, clients.get(), log);
            } catch (RuntimeException e) {
                problem = "Java 17 " + java.version() + ": " + artifactoryFailure(e);
            } catch (IOException e) {
                problem = "Java 17 " + java.version() + ": " + artifactoryFailure(new UncheckedIOException(e));
            }
            if (!problem.isEmpty()) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /**
     * Why a listing or download failed, in words: the Python's {@code cat.sims.fetch._artifactory_failure}. A
     * rejected login or an unreachable server is said as {@link ArtifactoryErrors#describe} says it; anything else as
     * its exception.
     */
    public static String artifactoryFailure(Throwable e) {
        if (e instanceof ArtifactoryException || e instanceof com.verifone.cat.build.ArtifactoryAuthError) {
            return "fetching from Artifactory failed: " + ArtifactoryErrors.describe(e, "");
        }
        return "fetching from Artifactory failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String stage(JavaSet java, Path toolsRoot, ArtifactoryClient client, Consumer<String> log)
            throws IOException {
        Path target = toolsRoot.resolve(java.target());
        if (Files.isDirectory(target)) {
            // Another set under this one's name: kept, renamed, never deleted.
            Path aside = target.resolveSibling(target.getFileName() + ".not-" + java.version() + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            try {
                Files.move(target, aside);
            } catch (IOException e) {
                return "Java 17 " + java.version() + ": " + target + " holds another set and is in use (" + e + ")";
            }
            log.accept("Java 17 " + java.version() + ": " + target + " held another set; moved it to " + aside);
        }
        log.accept("Java 17 " + java.version() + " (" + java.forBases() + "): downloading " + java.remote());
        Files.createDirectories(toolsRoot);
        Path scratch = Files.createTempDirectory(toolsRoot, ".cat-fetch-");
        try {
            ArtifactoryClient.FileInfo info = client.fileInfo(java.remote());
            Path archive = client.downloadFile(client.fileUrl(java.remote()), scratch.resolve(java.file()),
                    info.size(), info.sha256(), ArtifactoryClient.DEFAULT_WORKERS);
            Path unpacked = scratch.resolve("unpacked");
            Unzip.extract(archive, unpacked);
            Files.deleteIfExists(archive);
            Path staged = unpacked.resolve(java.holds());
            if (!Files.isDirectory(staged)) {
                return "Java 17 " + java.version() + ": " + java.file() + " holds no " + java.holds() + "/";
            }
            if (Files.isDirectory(target)) { // staged by someone else meanwhile: theirs stands
                return "";
            }
            Files.move(staged, target);
        } finally {
            Trees.deleteQuietly(scratch);
        }
        log.accept("Java 17 " + java.version() + ": unpacked into " + target);
        return "";
    }
}
