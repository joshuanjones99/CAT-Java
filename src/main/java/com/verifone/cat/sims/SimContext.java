package com.verifone.cat.sims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything a registry row's placeholders resolve against, and which JDK launches it.
 *
 * <p>The placeholders are the Python CAT's: {@code {tools_root}}, {@code {java_home}},
 * {@code {jaxb}}, {@code {cwd}}, {@code {controller_host}} and {@code {username}}. An unknown
 * one, or a stray brace, is an {@link IllegalArgumentException} naming the row, so a typo is a
 * failed row and never a launch with the text left in it.
 *
 * <p>The JDK is D13's: one java home, {@code <tools_root>/<javaDir>}, with the {@code jdk*}
 * folder that holds a {@code bin/javaw.exe} at the highest version. A non-null {@code jdk}
 * replaces that, for the Petro row's fallback relaunch.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param toolsRoot the staging root, from {@link ToolsRoot}
 * @param controllerHost the controller's address, which BUYPASS binds; empty if unknown
 * @param javaDir the Java 17 set under the tools root: {@code Java17}, or {@code Java17v1.1}
 * @param jdk one specific JDK directory, or null for the highest under {@code javaDir}
 * @param jaxb the JAXB module directory for {@code {jaxb}}, or null for {@code {java_home}/jaxb-ri/mod}
 */
public record SimContext(Path toolsRoot, String controllerHost, String javaDir, Path jdk, Path jaxb) {

    /** The Java 17 set Commander base 57 and later launch on (D13). */
    public static final String JAVA_DIR = "Java17";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");

    public SimContext {
        Objects.requireNonNull(toolsRoot, "toolsRoot");
        controllerHost = controllerHost == null ? "" : controllerHost.strip();
        javaDir = javaDir == null || javaDir.isBlank() ? JAVA_DIR : javaDir;
    }

    /** A context on the standard Java set, with no JDK override. */
    public static SimContext of(Path toolsRoot, String controllerHost) {
        return new SimContext(toolsRoot, controllerHost, JAVA_DIR, null, null);
    }

    /** This context, relaunching on one specific JDK (the Petro row's {@code fallback_jdk}). */
    public SimContext withJdk(Path onJdk, Path onJaxb) {
        return new SimContext(toolsRoot, controllerHost, javaDir, onJdk, onJaxb);
    }

    /** The java home: the parent of the override JDK, or {@code <tools_root>/<javaDir>}. */
    public Path javaHome() {
        return jdk != null ? jdk.getParent() : toolsRoot.resolve(javaDir);
    }

    /** The row's working directory: {@code toolsRoot/cwd}, or the tools root when the row names none. */
    public Path cwdFor(SimEntry entry) {
        return entry.cwd().isEmpty() ? toolsRoot : toolsRoot.resolve(entry.cwd());
    }

    /** The {@code javaw.exe} a javaw row launches with, if one exists. */
    public Optional<Path> javawExe() {
        if (jdk == null) {
            return javaw(toolsRoot, javaDir);
        }
        Path exe = jdk.resolve("bin").resolve("javaw.exe");
        return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
    }

    /** {@code windowsaccessbridge-64.dll} beside the resolved {@code javaw.exe}, if present. */
    public Optional<Path> bridgeDll() {
        return javawExe()
                .map(exe -> exe.resolveSibling("windowsaccessbridge-64.dll"))
                .filter(Files::isRegularFile);
    }

    /** {@code %USERNAME%} for this controller, or empty. */
    public String username() {
        String value = System.getenv("USERNAME");
        return value == null ? "" : value;
    }

    /**
     * Resolves one registry string for a row.
     *
     * @throws IllegalArgumentException for an unknown placeholder or a stray brace
     */
    public String substitute(String text, SimEntry entry) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start()).append(valueOf(matcher.group(1), entry, text));
            last = matcher.end();
        }
        out.append(text.substring(last));
        String literal = PLACEHOLDER.matcher(text).replaceAll("");
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) {
            throw new IllegalArgumentException(entry.name() + ": unbalanced brace in " + text);
        }
        return out.toString();
    }

    private String valueOf(String key, SimEntry entry, String text) {
        return switch (key) {
            case "tools_root" -> toolsRoot.toString();
            case "java_home" -> javaHome().toString();
            case "jaxb" -> jaxb != null
                    ? jaxb.toString()
                    : javaHome().resolve("jaxb-ri").resolve("mod").toString();
            case "cwd" -> cwdFor(entry).toString();
            case "controller_host" -> controllerHost;
            case "username" -> username();
            default -> throw new IllegalArgumentException(
                    entry.name() + ": unknown placeholder {" + key + "} in " + text);
        };
    }

    /** The row's argv, placeholders resolved, without the executable. */
    public List<String> substitutedArgv(SimEntry entry) {
        List<String> resolved = new ArrayList<>();
        for (String arg : entry.argv()) {
            resolved.add(substitute(arg, entry));
        }
        return resolved;
    }

    /**
     * The full command line, executable first.
     *
     * @throws IllegalStateException when the JDK a javaw row needs is not there
     * @throws IllegalArgumentException for a bad placeholder
     */
    public List<String> argv(SimEntry entry) {
        Path exe;
        if (entry.runner() == Runner.JAVAW) {
            exe = javawExe().orElseThrow(() -> new IllegalStateException(
                    "no jdk with bin/javaw.exe under " + (jdk != null ? jdk : javaHome())));
        } else {
            exe = Path.of(substitute(entry.exe(), entry));
        }
        List<String> full = new ArrayList<>();
        full.add(exe.toString());
        full.addAll(substitutedArgv(entry));
        return full;
    }

    /** The row's extra environment, placeholders resolved. */
    public Map<String, String> envFor(SimEntry entry) {
        Map<String, String> resolved = new LinkedHashMap<>();
        entry.env().forEach((key, value) -> resolved.put(key, substitute(value, entry)));
        return resolved;
    }

    /**
     * The jar after {@code -jar}, under {@link #cwdFor} unless absolute; empty if there is none.
     * The first token after {@code -jar} that is not an option: the Petro Simulator Suite's argv
     * puts {@code -agentlib:jdwp=...} between {@code -jar} and the jar (Python
     * {@code SimContext.jar_for}).
     */
    public Optional<Path> jarFor(SimEntry entry) {
        List<String> argv = substitutedArgv(entry);
        int jar = argv.indexOf("-jar");
        if (jar < 0 || jar >= argv.size() - 1) {
            return Optional.empty();
        }
        return argv.subList(jar + 1, argv.size()).stream()
                .filter(arg -> !arg.startsWith("-"))
                .findFirst()
                .map(arg -> cwdFor(entry).resolve(arg));
    }

    /**
     * The substring that identifies a running instance in a command line: the explicit
     * {@code match}, else the token after {@code -jar}, else the last argv element, else the name.
     */
    public String matchToken(SimEntry entry) {
        if (!entry.match().isEmpty()) {
            return substitute(entry.match(), entry);
        }
        List<String> argv = substitutedArgv(entry);
        int jar = argv.indexOf("-jar");
        if (jar >= 0 && jar < argv.size() - 1) {
            return argv.get(jar + 1);
        }
        return argv.isEmpty() ? entry.name() : argv.get(argv.size() - 1);
    }

    /**
     * The highest {@code jdk*} directory under {@code root/javaDir} that holds a
     * {@code bin/javaw.exe}, as that exe. Empty when there is none.
     */
    public static Optional<Path> javaw(Path root, String javaDir) {
        Path dir = root.resolve(javaDir);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children
                    .filter(child -> child.getFileName().toString().startsWith("jdk"))
                    .filter(child -> Files.isRegularFile(child.resolve("bin").resolve("javaw.exe")))
                    .max(Comparator.comparing(SimContext::versionKey, SimContext::compareKeys))
                    .map(child -> child.resolve("bin").resolve("javaw.exe"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The numbers in a folder name, in order: {@code jdk-17.0.5+8} gives 17, 0, 5, 8. */
    static List<Long> versionKey(Path folder) {
        List<Long> key = new ArrayList<>();
        Matcher digits = Pattern.compile("\\d+").matcher(folder.getFileName().toString());
        while (digits.find()) {
            key.add(Long.parseLong(digits.group()));
        }
        return key;
    }

    /** Lexicographic, as Python compares tuples: a longer key with the same prefix is greater. */
    static int compareKeys(List<Long> a, List<Long> b) {
        int shared = Math.min(a.size(), b.size());
        for (int i = 0; i < shared; i++) {
            int c = Long.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
