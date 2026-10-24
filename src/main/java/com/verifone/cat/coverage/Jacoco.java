package com.verifone.cat.coverage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.verifone.cat.sims.ToolsRoot;

/**
 * The JaCoCo tools on the controller: where they are, and the commands CAT runs with them.
 *
 * <p><b>Which jars.</b> The agent on the devices is 0.8.8. The CLI must read the build's Java 17 classes,
 * and only 0.8.7 and later can, so a CLI older than {@link #MIN_CLI_VERSION} is refused by name.
 * Looked for, in order: {@code <tools root>/jacoco/}, then PATS's {@code Bin/Lib/}, then {@code Bin/}.
 * {@code C:/CICD/Tools/jacococli.jar} (0.8.4) is therefore never chosen when a newer copy exists beside it.
 *
 * <p><b>Which Java.</b> The simulators' JDK, {@code <tools root>/Java17/jdk*}\bin\java.exe, else
 * {@code java} on PATH.
 *
 * <p><b>How commands run.</b> {@link #processRunner()} starts {@code java @argfile} with a bounded wait,
 * writing the output to a temporary file (no pipe to deadlock on), and the argfile quotes each argument
 * so a report with hundreds of source roots never reaches the Windows command-line limit.
 *
 * <p>A port of {@code petrotest.coverage.jacoco}. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Jacoco {

    /** The first JaCoCo that reads Java 17 class files. */
    public static final int[] MIN_CLI_VERSION = {0, 8, 7};

    /** PATS's deployed tree on this controller when {@code CAT_PATS_RUN} is unset. */
    public static final Path DEFAULT_PATS_RUN = Path.of("C:/CICD/Run/automn");

    private static final Pattern VERSION = Pattern.compile("[\\d.]+");

    private Jacoco() {}

    /** The outcome of one CLI run: whether it exited 0, and the last few meaningful lines of its output. */
    public record CliResult(boolean ok, String detail) {}

    /** Runs {@code java -jar jacococli.jar <args>}, bounded by {@code timeout}. */
    @FunctionalInterface
    public interface CliRunner {
        CliResult run(JacocoTools tools, List<String> args, Duration timeout);
    }

    /**
     * Where the tools are looked for: the tools root, PATS's run tree, and the java found on PATH (or null).
     *
     * @param toolsRoot the tools root ({@code CAT_TOOLS_ROOT}, else {@code C:/CICD/Tools})
     * @param patsRun   PATS's run tree ({@code CAT_PATS_RUN}, else {@code C:/CICD/Run/automn})
     * @param javaOnPath the {@code java} found on PATH, or null
     */
    public record Locations(Path toolsRoot, Path patsRun, Path javaOnPath) {

        /** The locations from the process environment (or the given map, in tests). */
        public static Locations fromEnvironment(Map<String, String> env) {
            Path tools = ToolsRoot.resolve(env.get(ToolsRoot.ENV), null);
            String pats = env.getOrDefault("CAT_PATS_RUN", "").strip();
            Path patsRun = pats.isEmpty() ? DEFAULT_PATS_RUN : Path.of(pats);
            return new Locations(tools, patsRun, javaOnPath(env.getOrDefault("PATH", "")));
        }

        private static Path javaOnPath(String path) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String name : List.of("java.exe", "java")) {
                    Path candidate = Path.of(dir.strip(), name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Finds the CLI (and the agent, when {@code needAgent}), and a Java to run the CLI with.
     *
     * @throws JacocoUnavailable when no CLI at least {@link #MIN_CLI_VERSION} is found, when the agent is
     *                           wanted and missing, or when there is no Java
     */
    public static JacocoTools findTools(Locations where, boolean needAgent) {
        Path cli = null;
        List<String> tooOld = new ArrayList<>();
        for (Path candidate : candidates(where, "jacococli.jar")) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            int[] version = jarVersion(candidate);
            if (version.length > 0 && compareVersions(version, MIN_CLI_VERSION) < 0) {
                tooOld.add(candidate + " (" + joinVersion(version) + ")");
                continue;
            }
            cli = candidate;
            break;
        }
        if (cli == null) {
            String detail = tooOld.isEmpty() ? "" : "; too old to read Java 17 classes: " + String.join(", ", tooOld);
            throw new JacocoUnavailable("no jacococli.jar 0.8.7 or later at " + joinPaths(candidates(where,
                    "jacococli.jar")) + detail + ". Copy JaCoCo 0.8.8's jacococli.jar and jacocoagent.jar into "
                    + where.toolsRoot().resolve("jacoco"));
        }
        Path agent = candidates(where, "jacocoagent.jar").stream().filter(Files::isRegularFile).findFirst()
                .orElse(null);
        if (needAgent && agent == null) {
            throw new JacocoUnavailable("no jacocoagent.jar at " + joinPaths(candidates(where, "jacocoagent.jar")));
        }
        return new JacocoTools(javaFor(where), cli, agent);
    }

    /** {@code Implementation-Version} from a jar's manifest as numbers; empty when unreadable. */
    public static int[] jarVersion(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String version = file.getManifest() == null ? null
                    : file.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (version == null) {
                return new int[0];
            }
            Matcher match = VERSION.matcher(version.strip());
            if (!match.lookingAt()) {
                return new int[0];
            }
            return Arrays.stream(match.group().split("\\."))
                    .filter(part -> !part.isEmpty())
                    .mapToInt(Integer::parseInt)
                    .toArray();
        } catch (IOException | NumberFormatException e) {
            return new int[0];
        }
    }

    /** {@code dump}: pull a running JVM's coverage over the agent's TCP port. */
    public static List<String> dumpArgs(String host, int port, Path destfile, boolean reset) {
        List<String> args = new ArrayList<>(List.of("dump", "--address", host, "--port", String.valueOf(port),
                "--destfile", destfile.toString()));
        if (reset) {
            args.add("--reset");
        }
        args.add("--quiet");
        return List.copyOf(args);
    }

    /**
     * {@code report}: one exec file against its class files, with source roots when given, HTML when
     * {@code html} is not null, and always XML.
     */
    public static List<String> reportArgs(List<Path> execfiles, List<Path> classfiles, String name, Path html,
            Path xml, List<Path> sourcefiles) {
        List<String> args = new ArrayList<>();
        args.add("report");
        execfiles.forEach(path -> args.add(path.toString()));
        for (Path path : classfiles) {
            args.add("--classfiles");
            args.add(path.toString());
        }
        for (Path path : sourcefiles) {
            args.add("--sourcefiles");
            args.add(path.toString());
        }
        if (html != null) {
            args.add("--html");
            args.add(html.toString());
        }
        args.addAll(List.of("--xml", xml.toString(), "--name", name, "--encoding", "UTF-8", "--quiet"));
        return List.copyOf(args);
    }

    /** The CLI runner the production code uses: a real {@code java @argfile} process. */
    public static CliRunner processRunner() {
        return Jacoco::runProcess;
    }

    /**
     * The lines that say what went wrong: stack frames ({@code at ...}, {@code ...}) and {@code [INFO]} lines
     * dropped, the last four joined with {@code " | "}.
     */
    public static String tail(String output) {
        List<String> lines = output.lines()
                .filter(raw -> !raw.startsWith("[INFO]"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !(line.startsWith("at ") || line.startsWith("...")))
                .toList();
        List<String> kept = lines.subList(Math.max(0, lines.size() - 4), lines.size());
        return String.join(" | ", kept);
    }

    /** Java's {@code @argfile} form: one argument per line, quoted so a path's spaces survive. */
    static String quote(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static int compareVersions(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static List<Path> candidates(Locations where, String name) {
        return List.of(where.toolsRoot().resolve("jacoco").resolve(name),
                where.patsRun().resolve("Bin").resolve("Lib").resolve(name),
                where.patsRun().resolve("Bin").resolve(name));
    }

    private static Path javaFor(Locations where) {
        Path jdkRoot = where.toolsRoot().resolve("Java17");
        if (Files.isDirectory(jdkRoot)) {
            try (Stream<Path> jdks = Files.list(jdkRoot)) {
                Path found = jdks.filter(p -> p.getFileName().toString().startsWith("jdk"))
                        .map(p -> p.resolve("bin").resolve("java.exe"))
                        .filter(Files::isRegularFile)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException e) {
                // Unlistable: fall through to PATH, as an absent JDK does.
            }
        }
        if (where.javaOnPath() != null) {
            return where.javaOnPath();
        }
        throw new JacocoUnavailable("no Java to run jacococli: none under " + jdkRoot + " and none on PATH");
    }

    private static CliResult runProcess(JacocoTools tools, List<String> args, Duration timeout) {
        Path argfile = null;
        Path output = null;
        try {
            argfile = Files.createTempFile("jacoco-", ".args");
            List<String> all = new ArrayList<>(List.of("-jar", tools.cli().toString()));
            all.addAll(args);
            Files.writeString(argfile, String.join("\n", all.stream().map(Jacoco::quote).toList()),
                    StandardCharsets.UTF_8);
            output = Files.createTempFile("jacoco-", ".out");
            Process process = new ProcessBuilder(tools.java().toString(), "@" + argfile)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CliResult(false, "jacococli " + args.get(0) + " did not finish within "
                        + timeout.toSeconds() + "s");
            }
            return new CliResult(process.exitValue() == 0, tail(Files.readString(output, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new CliResult(false, "jacococli " + args.get(0) + " could not start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliResult(false, "jacococli " + args.get(0) + " was interrupted");
        } finally {
            deleteQuietly(argfile);
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // A temporary file left behind is not worth failing a report for.
        }
    }

    private static String joinPaths(List<Path> paths) {
        return String.join(", ", paths.stream().map(Path::toString).toList());
    }

    private static String joinVersion(int[] version) {
        return String.join(".", Arrays.stream(version).mapToObj(String::valueOf).toList());
    }
}
