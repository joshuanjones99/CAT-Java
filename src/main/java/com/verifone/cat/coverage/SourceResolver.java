package com.verifone.cat.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Source roots for a report's line-by-line view: petroapps' {@code componentDef.xml} graph. The Python's
 * {@code petrotest.coverage.sources}, itself a port of the petroapps skill's {@code Resolve-ComponentRecursive}.
 *
 * <p>A JVM's app jar bundles the classes of every component it depends on, so its sources are the {@code src/}
 * of each component reached by following {@code <componentDef name="..."/>} from the jar's seed directory
 * ({@link Components#SEEDS}). jacococli resolves each class against every root it is given, so the roots need no
 * pairing with the class files.
 *
 * <p>The cache is per directory and complete for that directory alone; the in-progress set is only for cycle
 * detection (the skill's first version shared one visited-set and came back short).
 *
 * <p><b>The sources are the checkout's, not the build's.</b> {@link #sourceCommitNote} says so whenever the
 * checkout's commit is not the build's. The checkout is only read: nothing here writes under it.
 */
public final class SourceResolver {

    /** The bound on {@code git rev-parse HEAD}, as the Python's. */
    static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final Path repoRoot;
    private final Map<Path, List<Path>> cache = new HashMap<>();
    private final Set<Path> inProgress = new HashSet<>();

    public SourceResolver(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /** Every {@code src/} directory the class files named by {@code patterns} (dist substituted) are built from. */
    public List<Path> rootsFor(List<String> patterns, String dist) {
        List<Path> result = new ArrayList<>();
        for (String pattern : patterns) {
            String seed = null;
            for (Map.Entry<String, String> entry : Components.SEEDS.entrySet()) {
                if (entry.getKey().replace("{dist}", dist).equals(pattern)) {
                    seed = entry.getValue();
                    break;
                }
            }
            if (seed == null) {
                continue;
            }
            for (Path root : resolve(repoRoot.resolve(seed.replace("{dist}", dist)))) {
                if (!result.contains(root)) {
                    result.add(root);
                }
            }
        }
        return result;
    }

    List<Path> resolve(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Path canonical;
        try {
            canonical = directory.toRealPath();
        } catch (IOException e) {
            return List.of();
        }
        List<Path> cached = cache.get(canonical);
        if (cached != null) {
            return cached;
        }
        if (inProgress.contains(canonical)) {
            return List.of(); // a genuine cycle: this directory's own closure is already being built
        }
        inProgress.add(canonical);
        List<Path> mine = new ArrayList<>();
        if (Files.isDirectory(canonical.resolve("src"))) {
            mine.add(canonical.resolve("src"));
        }
        Path definition = canonical.resolve("componentDef.xml");
        if (Files.isRegularFile(definition)) {
            for (String name : dependencyNames(definition)) {
                for (Path root : resolve(repoRoot.resolve(name))) {
                    if (!mine.contains(root)) {
                        mine.add(root);
                    }
                }
            }
        }
        inProgress.remove(canonical);
        List<Path> result = List.copyOf(mine);
        cache.put(canonical, result);
        return result;
    }

    /** Every {@code componentDef} element's {@code name}, leading slashes stripped, in document order. */
    private static List<String> dependencyNames(Path definition) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(definition.toFile());
        } catch (Exception e) {
            return List.of(); // unparseable: no dependencies, as the Python's ParseError branch
        }
        List<String> names = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("componentDef");
        for (int i = 0; i < nodes.getLength(); i++) {
            String name = ((Element) nodes.item(i)).getAttribute("name").strip().replaceFirst("^/+", "");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** The checkout's {@code HEAD} commit, or "" when git cannot say (bounded at {@link #GIT_TIMEOUT}). */
    static String head(Path repoRoot) {
        Path output = null;
        try {
            output = Files.createTempFile("cat-git-head-", ".out");
            Process process = new ProcessBuilder("git", "-C", repoRoot.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(false)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? Files.readString(output, StandardCharsets.UTF_8).strip() : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException e) {
                    // a temporary file left behind is harmless
                }
            }
        }
    }

    /** One sentence on whether the sources match the build; "" when they do. */
    public static String sourceCommitNote(Path repoRoot, String buildCommit) {
        return sourceCommitNote(repoRoot, buildCommit, SourceResolver::head);
    }

    static String sourceCommitNote(Path repoRoot, String buildCommit, Function<Path, String> headOf) {
        String head = headOf.apply(repoRoot);
        String build = buildCommit == null ? "" : buildCommit;
        if (head.isEmpty()) {
            return "sources from " + repoRoot + " (its commit could not be read)";
        }
        String wanted = build.toLowerCase(Locale.ROOT).substring(0, Math.min(12, build.length()));
        if (!build.isEmpty() && head.toLowerCase(Locale.ROOT).startsWith(wanted)) {
            return "";
        }
        return "sources from " + repoRoot + " at " + head.substring(0, Math.min(12, head.length()))
                + "; the build is " + (build.isEmpty() ? "of an unknown commit" : build.substring(0,
                        Math.min(12, build.length())))
                + ": a highlighted line may sit on the wrong source line where the two differ";
    }
}
