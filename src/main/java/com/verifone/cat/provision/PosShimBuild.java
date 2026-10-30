package com.verifone.cat.provision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Builds the POS shim and the retained RMI agent into {@code payload/pos/}: a port of the Python CAT's
 * {@code tools/build_pos_shim.py}, which is the specification.
 *
 * <p>It produces {@code automation-target-pos-topaz.jar} (the retained agent, compiled from petroapps),
 * {@code topazerp-connection-enabler.jar} (the one TopazERP class the POS's {@code LexStrategy} gates its keyboard
 * listener on), {@code petrotest-pos-shim.jar} (the HTTP shim, from the Python checkout's {@code bridge/pos-shim/}),
 * the launcher, {@code tokenRMI.prop}, and last the build stamp ({@link ShimStamp}), which is also compiled into the
 * shim jar so the running shim reports it as {@code GET /health}'s {@code build}.
 *
 * <p>Both compilations need {@code isdApps.topaz.pos.app-V1.jar} and the logging jars ({@link ShimStamp#DEP_JARS}),
 * which are Verifone build outputs and never committed: they are fetched off a register into the deps cache
 * ({@code <tools root>/pos-shim-deps}) over SFTP, or supplied by hand.
 *
 * <p>Deviations from the Python: javac is this JVM's own compiler ({@link ToolProvider#getSystemJavaCompiler}), run
 * in-process with the same {@code --release 17} and flags, rather than a {@code javac.exe} found through
 * {@code JAVA_HOME}, {@code PATH} or the staged JDK: {@code run.cmd} always runs CAT-Java on the CICD JDK 17, and a JRE
 * has no compiler, which is reported as the Python reports a missing javac. The jars are written with
 * {@link JarOutputStream}, not the {@code jar} tool: the same entries and {@code Main-Class}, and a manifest without
 * {@code jar}'s {@code Created-By}. The payload written is the one both CATs push ({@link ProvisionPaths#payloadRoot}):
 * outside {@code ~/.cat/java/} by necessity, and with the stamp the Python's build would write for the same sources.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the dependency fetch reaches a register over SSH; the compile has run
 * only against fixture sources here.
 */
public final class PosShimBuild {

    /** The register's JRE is Corretto 17.0.5: compiled for anything newer, the shim would not start there. */
    public static final String TARGET_RELEASE = "17";
    /** Where the Topaz jars live on a register. */
    public static final String REMOTE_LIB = "/home/topaz/lib";
    /** Each SSH step of the dependency fetch. */
    public static final Duration SSH_TIMEOUT = Duration.ofSeconds(60);
    /** The shim's main class. */
    public static final String MAIN_CLASS = "com.verifone.petrotest.posshim.PosShim";
    /** The environment variable naming the SSH key for {@code --pull-deps-from}. */
    public static final String SSH_KEY_ENV = "PETROTEST_COMMANDER_SSH_KEY";

    /** What the build writes into {@code payload/pos/} together, besides its stamp: the Python's {@code POS_SHIM_OUTPUTS}. */
    public static final List<String> OUTPUTS = List.of(
            "automation-target-pos-topaz.jar",
            "topazerp-connection-enabler.jar",
            "petrotest-pos-shim.jar",
            "petrotest-pos-shim.sh",
            "tokenRMI.prop");

    /**
     * What the register's agent reads to find the shim: loopback, because the shim runs on the register. Fixed
     * content, written with LF endings (a CR would end up inside the {@code token.host} value).
     */
    public static final String TOKEN_RMI_PROP = """
            # Written by tools/build_pos_shim.py — see DESIGN.md §5.2.
            #
            # Points the POS's retained automation agent (automation.observer.class in
            # private_system.prop, and the keyboard/MSR LexStrategy) at the petrotest POS
            # shim. The shim runs on this same register, so this is loopback.
            token.host=127.0.0.1
            token.port=1094
            token.client=5003
            token.rmi=tokenRMI.app
            """;

    /** Something the operator has to fix: reported without a stack trace. */
    public static final class BuildError extends RuntimeException {
        public BuildError(String message) {
            super(message);
        }

        public BuildError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One build.
     *
     * @param repoRoot  the Python CAT checkout: {@code bridge/pos-shim/} and the recipe the stamp hashes
     * @param petroapps the petroapps checkout holding the retained agent and TopazERP
     * @param depsDir   the vendor compile jars
     * @param payload   the output directory ({@code payload/pos})
     * @param check     compile both trees and stop; no jars written
     */
    public record Options(Path repoRoot, Path petroapps, Path depsDir, Path payload, boolean check) {}

    /** Compiles {@code sources} into {@code into} against {@code classpath}; throws {@link BuildError} on failure. */
    @FunctionalInterface
    public interface Compiler {
        /** @return the compiler's output on success, possibly empty */
        String compile(List<Path> sources, Path into, List<Path> classpath, boolean lint, String what);
    }

    private PosShimBuild() {}

    /** {@code <tools root>/pos-shim-deps}: the deps cache, moved with {@code $CAT_TOOLS_ROOT}. */
    public static Path defaultDeps(Map<String, String> environ) {
        return ToolsRoot.resolve(environ.get(ToolsRoot.ENV), null).resolve("pos-shim-deps");
    }

    /** {@code payload/pos} under the payload both CATs push. */
    public static Path defaultPayload(Map<String, String> environ) {
        return ProvisionPaths.payloadRoot(environ).resolve("pos");
    }

    // -- the vendor jars ---------------------------------------------------------------------

    /**
     * Copies the compile dependencies off a register into {@code depsDir}, over SFTP. With {@code onlyMissing}, a jar
     * the cache already holds is not fetched again.
     */
    public static void pullDeps(Transport transport, Path depsDir, boolean onlyMissing, Consumer<String> say) {
        List<String> wanted = ShimStamp.DEP_JARS.stream()
                .filter(jar -> !(onlyMissing && Files.isRegularFile(depsDir.resolve(jar)))).toList();
        if (wanted.isEmpty()) {
            return;
        }
        mkdirs(depsDir);
        String host = transport.host();
        for (String jar : wanted) {
            say.accept("  fetching " + jar + " from " + host);
            String remote = REMOTE_LIB + "/" + jar;
            Optional<byte[]> content;
            try {
                content = transport.get(remote, SSH_TIMEOUT);
            } catch (RuntimeException e) {
                throw new BuildError("fetching " + jar + " from " + host + " failed: " + e.getMessage(), e);
            }
            if (content.isEmpty()) {
                throw new BuildError(host + " has no " + remote);
            }
            try {
                Files.write(depsDir.resolve(jar), content.get());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Fetches whichever compile jars are missing from {@code rig}'s first register: host, user and key from the rig
     * file, never a literal here.
     */
    public static void pullDepsFromRig(Rig rig, Map<String, String> environ, Path depsDir,
            Function<SshTarget, Transport> transports, Consumer<String> say) {
        if (rig.registers().isEmpty()) {
            throw new BuildError("rig '" + rig.name() + "' declares no register to fetch the Topaz jars from");
        }
        Rig.Register register = rig.registers().get(0);
        say.accept("Fetching missing build dependencies from " + register.id() + " (" + register.host() + ")");
        try (Transport transport = transports.apply(SshTarget.register(rig, register, environ))) {
            pullDeps(transport, depsDir, true, say);
        }
    }

    /** Whether the cache lacks any dependency jar. */
    public static boolean checkDepsMissing(Path depsDir) {
        return ShimStamp.DEP_JARS.stream().anyMatch(jar -> !Files.isRegularFile(depsDir.resolve(jar)));
    }

    /** The dependency jars, confirmed present; a missing one is named with how to get it. */
    public static List<Path> checkDeps(Path depsDir) {
        List<String> missing = ShimStamp.DEP_JARS.stream().filter(jar -> !Files.isRegularFile(depsDir.resolve(jar)))
                .toList();
        if (!missing.isEmpty()) {
            throw new BuildError("missing build dependencies in " + depsDir + ":\n  " + String.join("\n  ", missing)
                    + "\n\nFetch them from a register with:\n"
                    + "  cat-java build-pos-shim --pull-deps-from <register-host> --ssh-key <key>\n"
                    + "or copy them by hand out of " + REMOTE_LIB + " on any Topaz build.");
        }
        return ShimStamp.DEP_JARS.stream().map(depsDir::resolve).toList();
    }

    // -- the sources -------------------------------------------------------------------------

    /** The retained agent's {@code .java} files. */
    public static List<Path> agentSources(Path petroapps) {
        Path src = ShimStamp.resolve(petroapps, ShimStamp.AGENT_SUBPATH);
        if (!Files.isDirectory(src)) {
            throw new BuildError("retained agent source not found at " + src + ".\n"
                    + "Point --petroapps at a petroapps checkout (or set PETROAPPS_ROOT).");
        }
        List<Path> sources = ShimStamp.agentJavaSources(petroapps);
        if (sources.isEmpty()) {
            throw new BuildError("no .java files under " + src);
        }
        return sources;
    }

    /** The single TopazERP class the POS's {@code LexStrategy} gates on. */
    public static List<Path> topazerpSources(Path petroapps) {
        List<Path> sources = ShimStamp.topazerpJavaSources(petroapps);
        List<Path> missing = sources.stream().filter(path -> !Files.isRegularFile(path)).toList();
        if (!missing.isEmpty()) {
            throw new BuildError("TopazERP source not found:\n  "
                    + String.join("\n  ", missing.stream().map(Path::toString).toList())
                    + "\n\nWithout it the POS registers no keyboard listener and the shim can read the POS but not "
                    + "drive it. Point --petroapps at a checkout that has isdApps/pcapps/utility/TopazERP.");
        }
        return sources;
    }

    /** The shim's own sources. */
    public static List<Path> shimSources(Path repoRoot) {
        List<Path> sources = ShimStamp.shimJavaSources(repoRoot);
        if (sources.isEmpty()) {
            throw new BuildError("no shim sources under " + ShimStamp.shimJava(repoRoot));
        }
        return sources;
    }

    /** The stamp of the sources this build compiles. */
    public static String buildStamp(Path repoRoot, Path petroapps) {
        try {
            return ShimStamp.sourceStamp(repoRoot, petroapps);
        } catch (ShimStamp.StampInputMissing e) {
            throw new BuildError("cannot stamp the build: " + e.getMessage(), e);
        }
    }

    // -- the build ---------------------------------------------------------------------------

    /**
     * Runs the build: the deps checked, the three trees compiled, then (unless {@link Options#check}) the old stamp
     * removed, the jars written, the launcher and {@code tokenRMI.prop} copied, and the new stamp written last.
     *
     * @return the stamp built, or empty for a {@code check}
     * @throws BuildError for anything the operator has to fix
     */
    public static Optional<String> build(Options options, Compiler compiler, Consumer<String> say) {
        List<Path> deps = checkDeps(options.depsDir());
        List<Path> agent = agentSources(options.petroapps());
        List<Path> topazerp = topazerpSources(options.petroapps());
        List<Path> shim = shimSources(options.repoRoot());
        // Before anything is compiled: the stamp names what was built, not what the tree held by the end of it.
        String stamp = buildStamp(options.repoRoot(), options.petroapps());
        say.accept("Build stamp " + stamp.substring(0, 12) + " (bridge/pos-shim, the retained agent, TopazERP, recipe)");

        Path tmp;
        try {
            tmp = Files.createTempDirectory("pos-shim-build-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Path agentClasses = tmp.resolve("agent");
            Path erpClasses = tmp.resolve("topazerp");
            Path shimClasses = tmp.resolve("shim");

            say.accept("Compiling retained RMI agent (petroapps §5.2)");
            printed(compiler.compile(agent, agentClasses, deps, false, "javac (agent)"), say);
            say.accept("Compiling TopazERP connection enabler (LexStrategy's gate class)");
            printed(compiler.compile(topazerp, erpClasses, deps, false, "javac (topazERP)"), say);
            say.accept("Compiling POS HTTP shim");
            List<Path> shimCp = new ArrayList<>(deps);
            shimCp.add(agentClasses);
            printed(compiler.compile(shim, shimClasses, shimCp, true, "javac (shim)"), say);

            if (options.check()) {
                say.accept("");
                say.accept("OK — both trees compile. No jars written (--check).");
                return Optional.empty();
            }

            say.accept("Packaging");
            Path payload = options.payload();
            mkdirs(payload);
            // The old stamp goes first and the new one is written last: a payload that has a stamp is one whose
            // every file came from that build.
            Files.deleteIfExists(payload.resolve(ShimStamp.STAMP_NAME));
            Files.write(shimClasses.resolve(ShimStamp.STAMP_NAME), (stamp + "\n").getBytes(StandardCharsets.US_ASCII));
            makeJar(agentClasses, payload.resolve("automation-target-pos-topaz.jar"), null, say);
            makeJar(erpClasses, payload.resolve("topazerp-connection-enabler.jar"), null, say);
            makeJar(shimClasses, payload.resolve("petrotest-pos-shim.jar"), MAIN_CLASS, say);
            copyLauncher(options.repoRoot(), payload, say);
            writeTokenRmiProp(payload, say);
            Path dest = payload.resolve(ShimStamp.STAMP_NAME);
            Files.write(dest, (stamp + "\n").getBytes(StandardCharsets.US_ASCII));
            say.accept("  wrote " + dest + " (build " + stamp.substring(0, 12) + ")");
            say.accept("");
            say.accept("OK — payload/pos is ready for `provision`.");
            return Optional.of(stamp);
        } catch (IOException e) {
            throw new BuildError("writing the payload failed: " + e.getMessage(), e);
        } finally {
            deleteTree(tmp);
        }
    }

    /** javac as this JVM carries it, in-process. */
    public static Compiler javac() {
        return (sources, into, classpath, lint, what) -> {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new BuildError("javac not found: this JVM (" + System.getProperty("java.home")
                        + ") is not a JDK. A JDK " + TARGET_RELEASE + " or newer is required to build the shim.");
            }
            mkdirs(into);
            List<String> args = new ArrayList<>(List.of("--release", TARGET_RELEASE));
            // The retained agent is petroapps' code, kept as-is: its warnings are noise. The shim's get -Xlint:all.
            args.add(lint ? "-Xlint:all" : "-nowarn");
            args.add("-cp");
            args.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
            args.add("-d");
            args.add(into.toString());
            sources.forEach(source -> args.add(source.toString()));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            int code = compiler.run(null, text, text, args.toArray(String[]::new));
            String output = text.toString(StandardCharsets.UTF_8).strip();
            if (code != 0) {
                throw new BuildError(what + " failed (exit " + code + "):\n" + output);
            }
            return output;
        };
    }

    /** One jar of {@code classes}, with {@code mainClass} in its manifest when given. */
    static void makeJar(Path classes, Path jar, String mainClass, Consumer<String> say) throws IOException {
        mkdirs(jar.toAbsolutePath().getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(classes)) {
            entries = walk.filter(path -> !path.equals(classes))
                    .sorted(Comparator.comparing(path -> classes.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (Path path : entries) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    out.putNextEntry(new JarEntry(name + "/"));
                } else {
                    out.putNextEntry(new JarEntry(name));
                    out.write(Files.readAllBytes(path));
                }
                out.closeEntry();
            }
        }
        say.accept("  wrote " + jar + " (" + String.format(Locale.ROOT, "%,d", Files.size(jar)) + " bytes)");
    }

    /** The launcher, LF only: run by {@code /bin/sh} on the register, a CR breaks its shebang line. */
    static void copyLauncher(Path repoRoot, Path payload, Consumer<String> say) throws IOException {
        Path source = ShimStamp.resolve(ShimStamp.resolve(repoRoot, ShimStamp.SHIM_DIR), ShimStamp.LAUNCHER_NAME);
        String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Path dest = payload.resolve(ShimStamp.LAUNCHER_NAME);
        Files.write(dest, text.getBytes(StandardCharsets.UTF_8));
        say.accept("  wrote " + dest + " (" + String.format(Locale.ROOT, "%,d", Files.size(dest)) + " bytes)");
    }

    static void writeTokenRmiProp(Path payload, Consumer<String> say) throws IOException {
        Path dest = payload.resolve("tokenRMI.prop");
        Files.write(dest, TOKEN_RMI_PROP.getBytes(StandardCharsets.UTF_8));
        say.accept("  wrote " + dest + " (" + String.format(Locale.ROOT, "%,d", Files.size(dest)) + " bytes)");
    }

    private static void printed(String output, Consumer<String> say) {
        if (output != null && !output.isBlank()) {
            say.accept(output.strip());
        }
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // a temporary folder left behind is not a failed build
        }
    }
}
