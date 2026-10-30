package com.verifone.cat.devices.vats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The java agent that presses BTT's {@code Start Card Simulation Server} menu item from inside BTT's own JVM, built
 * into a jar once per source revision. A port of the Python CAT's {@code btt.build_agent}.
 *
 * <p>The source, {@code btt_agent/MenuAgent.java} in the Python CAT, ships verbatim as the class-path resource
 * {@value #RESOURCE} (a resource, not a class of this build: it runs in BTT's 32-bit Java 8 and uses Swing, which
 * CAT-Java's own code does not). It is compiled for Java 8 ({@code --release 8}) by this JDK's own compiler when it has
 * one (the JDK {@code run.cmd} uses), else by {@code <tools root>/Java17/jdk*}{@code /bin/javac.exe}, as the Python
 * compiles it; the jar carries {@code Premain-Class: cat.btt.MenuAgent} and is cached under the source's digest.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's agent pressed the menu of the lab's BTT 5.9.0 on
 * 2026-10-06; this build of it has been loaded only into a stand-in Swing window.
 */
public final class BttMenuAgent {

    /** The agent's source, the Python CAT's {@code btt_agent/MenuAgent.java} byte for byte. */
    public static final String RESOURCE = "/vats/btt_agent/MenuAgent.java";

    /** The agent's class, which its manifest names. */
    public static final String AGENT_CLASS = "cat.btt.MenuAgent";

    private BttMenuAgent() {}

    /** The shipped source's bytes. */
    public static byte[] source() {
        try (InputStream in = BttMenuAgent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the BTT menu agent " + RESOURCE + " is not on the class path");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + RESOURCE, e);
        }
    }

    /** The first 12 hex digits of the source's SHA-256: the jar's revision. */
    static String digest(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The agent jar in {@code cache}, built if this revision is not there yet.
     *
     * @param toolsRoot where a {@code Java17/jdk*} lives, for when this JVM has no compiler; may be null
     * @throws IOException      the cache cannot be written, or the compiler cannot be run
     * @throws IllegalStateException no compiler, or the build failed (its errors in the message)
     */
    public static Path build(Path cache, Path toolsRoot) throws IOException {
        byte[] source = source();
        Path jar = cache.resolve("cat-btt-menu-agent-" + digest(source) + ".jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Files.createDirectories(cache);
        Path tmp = Files.createTempDirectory(cache, "build-");
        try {
            Path src = tmp.resolve("src").resolve("MenuAgent.java");
            Path classes = tmp.resolve("classes");
            Files.createDirectories(src.getParent());
            Files.createDirectories(classes);
            Files.write(src, source);
            compile(src, classes, toolsRoot);
            Path staging = jar.resolveSibling(jar.getFileName() + ".tmp");
            writeJar(classes, staging);
            Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteTree(tmp);
        }
        return jar;
    }

    private static void compile(Path source, Path classes, Path toolsRoot) throws IOException {
        List<String> args = List.of("--release", "8", "-nowarn", "-d", classes.toString(), source.toString());
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler != null) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int code = compiler.run(null, errors, errors, args.toArray(new String[0]));
            if (code != 0) {
                throw new IllegalStateException("building the BTT agent failed: "
                        + errors.toString(StandardCharsets.UTF_8).strip());
            }
            return;
        }
        Path javac = javac(toolsRoot);
        if (javac == null) {
            throw new IllegalStateException("no JDK under " + (toolsRoot == null ? "the tools root"
                    : toolsRoot.resolve("Java17")) + " to build the BTT agent");
        }
        List<String> argv = new ArrayList<>();
        argv.add(javac.toString());
        argv.addAll(args);
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("building the BTT agent timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("building the BTT agent was interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("building the BTT agent failed: "
                    + new String(output, StandardCharsets.UTF_8).strip());
        }
    }

    /** The newest {@code <toolsRoot>/Java17/jdk*}{@code /bin/javac.exe}, or null. */
    static Path javac(Path toolsRoot) {
        if (toolsRoot == null) {
            return null;
        }
        Path java17 = toolsRoot.resolve("Java17");
        if (!Files.isDirectory(java17)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> jdks = Files.newDirectoryStream(java17, "jdk*")) {
            for (Path jdk : jdks) {
                Path javac = jdk.resolve("bin").resolve("javac.exe");
                if (Files.isRegularFile(javac)) {
                    found.add(javac);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return found.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static void writeJar(Path classes, Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), AGENT_CLASS);
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file, manifest);
                Stream<Path> walk = Files.walk(classes)) {
            for (Path cls : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(cls).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(cls));
                out.closeEntry();
            }
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // a leftover build folder in the cache is harmless
        }
    }
}
