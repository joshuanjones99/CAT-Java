package com.verifone.cat.provision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The pos-shim's build stamp: one hash of everything the shim build compiles and ships. A port of
 * {@code petrotest.provision.shim_stamp}, which is the specification.
 *
 * <p>{@link PosShimBuild} (the port of {@code tools/build_pos_shim.py}) writes it as
 * {@code payload/pos/petrotest-pos-shim.build}, last, and into the shim jar's root under the same name; step 4 rebuilds
 * when that file is missing or is not {@link #sourceStamp} of the sources as they are now; the shim reports the one in
 * its jar as {@code GET /health}'s {@code build}, and provision's {@code EnsurePosShimCurrent} replaces a running shim
 * whose {@code build} is not the payload's.
 *
 * <p><b>What is hashed</b>, each under a name that does not depend on where a checkout lives: {@code shim/...} (every
 * {@code .java} under {@code bridge/pos-shim/src/main/java}, and the launcher), {@code agent/...} (the retained agent's
 * {@code .java} in petroapps), {@code topazerp/...} (the one TopazERP class) and {@code recipe/build_pos_shim.py} (the
 * Python CAT's build script). The recipe is the Python checkout's {@code tools/build_pos_shim.py}, hashed as the Python
 * hashes it, so a payload either CAT built carries the same stamp for the same sources: both CATs read and write the
 * one payload, and a stamp that differed by which CAT built it would rebuild the shim, and restart the POS, on every
 * switch between them. Text is hashed with CRLF read as LF.
 *
 * <p>The ordering of each tree's files is the Python's on Windows: {@code sorted(Path.rglob(...))} compares a path's
 * parts case-insensitively, part by part.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ShimStamp {

    /** The stamp's file name, in {@code payload/pos/} and at the root of the shim jar. */
    public static final String STAMP_NAME = "petrotest-pos-shim.build";

    /** Bumped only if the way the stamp is computed changes, so an old stamp cannot match. */
    public static final int STAMP_VERSION = 1;

    /** {@code bridge/pos-shim/}, relative to the Python CAT checkout. */
    public static final String SHIM_DIR = "bridge/pos-shim";
    /** The build script, relative to the Python CAT checkout; hashed as the recipe. */
    public static final String BUILD_SCRIPT = "tools/build_pos_shim.py";
    /** The launcher provision pushes beside the shim jar. */
    public static final String LAUNCHER_NAME = "petrotest-pos-shim.sh";

    /** The retained agent's source, in the petroapps checkout. */
    public static final String AGENT_SUBPATH = "isdApps/pcapps/automation/target/pos/topaz/src";

    /** One class out of petroapps' TopazERP component, shipped as its own jar. */
    public static final String TOPAZERP_SUBPATH = "isdApps/pcapps/utility/TopazERP/src";
    public static final List<String> TOPAZERP_CLASSES = List.of("com/verifone/isd/pcapps/topazERP/VsmsCommonImpl.java");

    /** The Topaz jars both compilations need, fetched off a register. Compiled against, not in: not stamped. */
    public static final List<String> DEP_JARS = List.of(
            "isdApps.topaz.pos.app-V1.jar",
            "slf4j-api-1.7.32.jar",
            "slf4j-log4j12-1.7.32.jar",
            "log4j-1.2.16.jar");

    /** An input of the stamp is not where the build would read it from. */
    public static final class StampInputMissing extends RuntimeException {
        public StampInputMissing(String message) {
            super(message);
        }
    }

    /** One named input. */
    public record Input(String name, Path path) {}

    private ShimStamp() {}

    /** The shim's {@code .java} files, as the build compiles them. */
    public static List<Path> shimJavaSources(Path repoRoot) {
        return javaUnder(shimJava(repoRoot));
    }

    /** The retained agent's {@code .java} files in a petroapps checkout; empty if it has none. */
    public static List<Path> agentJavaSources(Path petroapps) {
        return javaUnder(resolve(petroapps, AGENT_SUBPATH));
    }

    /** The TopazERP class(es) the build compiles, whether or not they exist. */
    public static List<Path> topazerpJavaSources(Path petroapps) {
        return TOPAZERP_CLASSES.stream().map(name -> resolve(resolve(petroapps, TOPAZERP_SUBPATH), name)).toList();
    }

    /**
     * Every input, named, in a fixed order.
     *
     * @throws StampInputMissing naming a gap
     */
    public static List<Input> inputs(Path repoRoot, Path petroapps) {
        Path shimJava = shimJava(repoRoot);
        List<Path> shim = shimJavaSources(repoRoot);
        if (shim.isEmpty()) {
            throw new StampInputMissing("no shim sources under " + shimJava);
        }
        Path agentDir = resolve(petroapps, AGENT_SUBPATH);
        List<Path> agent = agentJavaSources(petroapps);
        if (agent.isEmpty()) {
            throw new StampInputMissing("no retained agent sources under " + agentDir);
        }
        List<Input> named = new ArrayList<>();
        for (Path path : shim) {
            named.add(new Input("shim/" + posix(shimJava.relativize(path)), path));
        }
        named.add(new Input("shim/" + LAUNCHER_NAME, resolve(resolve(repoRoot, SHIM_DIR), LAUNCHER_NAME)));
        for (Path path : agent) {
            named.add(new Input("agent/" + posix(agentDir.relativize(path)), path));
        }
        List<Path> topazerp = topazerpJavaSources(petroapps);
        for (int i = 0; i < TOPAZERP_CLASSES.size(); i++) {
            named.add(new Input("topazerp/" + TOPAZERP_CLASSES.get(i), topazerp.get(i)));
        }
        named.add(new Input("recipe/build_pos_shim.py", resolve(repoRoot, BUILD_SCRIPT)));
        for (Input input : named) {
            if (!Files.isRegularFile(input.path())) {
                throw new StampInputMissing(input.name() + " is not at " + input.path());
            }
        }
        return List.copyOf(named);
    }

    /**
     * The stamp of the sources as they are now: a sha256 hex digest.
     *
     * @param repoRoot  the Python CAT checkout holding {@code bridge/pos-shim/} and the build script
     * @param petroapps the checkout holding the retained agent and TopazERP
     * @throws StampInputMissing when an input is absent: a build could not run either
     */
    public static String sourceStamp(Path repoRoot, Path petroapps) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
        digest.update(("petrotest-pos-shim build stamp v" + STAMP_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
        for (Input input : inputs(repoRoot, petroapps)) {
            byte[] data = lfOnly(read(input.path()));
            digest.update((input.name() + "\0" + data.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(data);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The stamp a build wrote into {@code payloadPos}; empty when there is none, or it is blank. */
    public static Optional<String> payloadStamp(Path payloadPos) {
        try {
            String text = Files.readString(payloadPos.resolve(STAMP_NAME), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + payloadPos.resolve(STAMP_NAME) + ": " + e.getMessage(), e);
        }
    }

    // -- helpers -----------------------------------------------------------------------------

    public static Path shimJava(Path repoRoot) {
        return resolve(resolve(repoRoot, SHIM_DIR), "src/main/java");
    }

    /** {@code base} plus a {@code /}-separated relative path. */
    public static Path resolve(Path base, String relative) {
        Path out = base;
        for (String part : relative.split("/")) {
            out = out.resolve(part);
        }
        return out;
    }

    private static String posix(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    /** Every {@code .java} file under {@code dir}, sorted as Python sorts Windows paths; empty when it is not a dir. */
    static List<Path> javaUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(PYTHON_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir + ": " + e.getMessage(), e);
        }
    }

    /** {@code PureWindowsPath}'s order: part by part, each case-folded. */
    static final Comparator<Path> PYTHON_ORDER = (a, b) -> {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            String x = a.getName(i).toString().toLowerCase(Locale.ROOT);
            String y = b.getName(i).toString().toLowerCase(Locale.ROOT);
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    };

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /** CRLF read as LF; a lone CR is kept, as Python's {@code replace(b"\r\n", b"\n")} keeps it. */
    static byte[] lfOnly(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\r' && i + 1 < data.length && data[i + 1] == '\n') {
                continue;
            }
            out.write(data[i]);
        }
        return out.toByteArray();
    }
}
