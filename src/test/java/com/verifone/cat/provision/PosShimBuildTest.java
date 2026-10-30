package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * {@link PosShimBuild} (the port of {@code tools/build_pos_shim.py}) with this JVM's javac over fixture sources that
 * need nothing from the vendor jars, which are empty stand-ins here (the real ones are Verifone's and never in a
 * test). The payload, its stamp, the jar's stamp and main class, {@code --check}, and the dependency fetch over an
 * in-memory transport. Evidence about this repository only.
 */
class PosShimBuildTest {

    @TempDir
    Path dir;

    private Path cat;
    private Path petroapps;
    private Path deps;
    private Path payload;

    private void tree() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        String shim = "bridge/pos-shim/src/main/java/com/verifone/petrotest/posshim";
        files.put("cat/" + shim + "/PosShim.java", "package com.verifone.petrotest.posshim;\n"
                + "public final class PosShim {\n    public static void main(String[] args) {\n"
                + "        new com.x.ObserverImpl();\n    }\n}\n");
        files.put("cat/bridge/pos-shim/petrotest-pos-shim.sh", "#!/bin/sh\r\nexec java\r\n");
        files.put("cat/tools/build_pos_shim.py", "TARGET_RELEASE = '17'\n");
        files.put("petroapps/" + ShimStamp.AGENT_SUBPATH + "/com/x/ObserverImpl.java",
                "package com.x;\npublic class ObserverImpl {\n}\n");
        files.put("petroapps/" + ShimStamp.TOPAZERP_SUBPATH + "/" + ShimStamp.TOPAZERP_CLASSES.get(0),
                "package com.verifone.isd.pcapps.topazERP;\npublic class VsmsCommonImpl {\n}\n");
        ShimStampSourceTest.write(dir, files, false);
        cat = dir.resolve("cat");
        petroapps = dir.resolve("petroapps");
        deps = Files.createDirectories(dir.resolve("deps"));
        for (String jar : ShimStamp.DEP_JARS) {
            try (OutputStream out = Files.newOutputStream(deps.resolve(jar));
                    JarOutputStream empty = new JarOutputStream(out, new Manifest())) {
                empty.flush();
            }
        }
        payload = dir.resolve("payload").resolve("pos");
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    @Test
    void theBuildWritesEveryOutputAndTheStampLast() throws IOException {
        tree();
        List<String> said = new ArrayList<>();
        Optional<String> stamp = PosShimBuild.build(new PosShimBuild.Options(cat, petroapps, deps, payload, false),
                PosShimBuild.javac(), said::add);

        assertEquals(Optional.of(ShimStamp.sourceStamp(cat, petroapps)), stamp);
        for (String name : PosShimBuild.OUTPUTS) {
            assertTrue(Files.isRegularFile(payload.resolve(name)), name);
        }
        assertEquals(stamp, ShimStamp.payloadStamp(payload));
        assertFalse(Files.readString(payload.resolve("petrotest-pos-shim.sh")).contains("\r"));
        assertFalse(Files.readString(payload.resolve("tokenRMI.prop")).contains("\r"));
        assertTrue(Files.readString(payload.resolve("tokenRMI.prop")).contains("token.host=127.0.0.1\n"));
        try (JarFile jar = new JarFile(payload.resolve("petrotest-pos-shim.jar").toFile())) {
            assertEquals(PosShimBuild.MAIN_CLASS, jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals(stamp.get() + "\n", new String(jar.getInputStream(jar.getEntry(ShimStamp.STAMP_NAME))
                    .readAllBytes(), StandardCharsets.US_ASCII));
            assertNotNull(jar.getEntry("com/verifone/petrotest/posshim/PosShim.class"));
        }
        try (JarFile jar = new JarFile(payload.resolve("topazerp-connection-enabler.jar").toFile())) {
            assertNotNull(jar.getEntry("com/verifone/isd/pcapps/topazERP/VsmsCommonImpl.class"));
        }
        assertTrue(said.get(said.size() - 1).startsWith("OK — payload/pos is ready"), said.toString());
    }

    @Test
    void checkCompilesAndWritesNothing() throws IOException {
        tree();
        Optional<String> stamp = PosShimBuild.build(new PosShimBuild.Options(cat, petroapps, deps, payload, true),
                PosShimBuild.javac(), line -> { });
        assertEquals(Optional.empty(), stamp);
        assertFalse(Files.exists(payload));
    }

    @Test
    void aCompileErrorIsABuildErrorAndPackagesNothing() throws IOException {
        tree();
        Files.createDirectories(payload);
        Files.writeString(payload.resolve(ShimStamp.STAMP_NAME), "old\n");
        Files.writeString(ShimStamp.resolve(cat, "bridge/pos-shim/src/main/java/com/verifone/petrotest/posshim/"
                + "Broken.java"), "class Broken { int x = ; }\n");
        PosShimBuild.BuildError error = assertThrows(PosShimBuild.BuildError.class, () -> PosShimBuild.build(
                new PosShimBuild.Options(cat, petroapps, deps, payload, false), PosShimBuild.javac(), line -> { }));
        assertTrue(error.getMessage().startsWith("javac (shim) failed"), error.getMessage());
        assertEquals(Optional.of("old"), ShimStamp.payloadStamp(payload), "nothing was packaged");
    }

    @Test
    void missingVendorJarsAreNamedWithHowToGetThem() throws IOException {
        tree();
        Files.delete(deps.resolve(ShimStamp.DEP_JARS.get(0)));
        PosShimBuild.BuildError error = assertThrows(PosShimBuild.BuildError.class,
                () -> PosShimBuild.checkDeps(deps));
        assertTrue(error.getMessage().contains(ShimStamp.DEP_JARS.get(0)), error.getMessage());
        assertTrue(error.getMessage().contains("--pull-deps-from"), error.getMessage());
        assertTrue(PosShimBuild.checkDepsMissing(deps));
    }

    @Test
    void theVendorJarsComeOverTheTransportAndOnlyTheMissingOnes() throws IOException {
        Path cache = Files.createDirectories(dir.resolve("cache"));
        Files.writeString(cache.resolve(ShimStamp.DEP_JARS.get(1)), "kept");
        InMemoryTransport register = new InMemoryTransport("register.invalid");
        for (String jar : ShimStamp.DEP_JARS) {
            register.withFile(PosShimBuild.REMOTE_LIB + "/" + jar, ("jar " + jar).getBytes(StandardCharsets.UTF_8));
        }
        PosShimBuild.pullDeps(register, cache, true, line -> { });
        assertEquals("kept", Files.readString(cache.resolve(ShimStamp.DEP_JARS.get(1))));
        assertEquals("jar " + ShimStamp.DEP_JARS.get(0), Files.readString(cache.resolve(ShimStamp.DEP_JARS.get(0))));
        assertEquals(3, register.reads().size(), register.reads().toString());
    }

    @Test
    void aJarTheRegisterLacksIsNamed() {
        InMemoryTransport register = new InMemoryTransport("register.invalid");
        PosShimBuild.BuildError error = assertThrows(PosShimBuild.BuildError.class,
                () -> PosShimBuild.pullDeps(register, dir.resolve("cache"), false, line -> { }));
        assertEquals("register.invalid has no " + PosShimBuild.REMOTE_LIB + "/" + ShimStamp.DEP_JARS.get(0),
                error.getMessage());
    }

    @Test
    void theCommandBuildsIntoTheGivenPayloadAndAFailureExitsOne() throws IOException {
        tree();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("CAT_PYTHON_ROOT", cat.toString());
        PosShimBuildCommand command = new PosShimBuildCommand(target -> {
            throw new AssertionError("no SSH in a test");
        }, PosShimBuild.javac());
        int code = command.run(new String[] {"--deps-dir", deps.toString(), "--petroapps", petroapps.toString(),
            "--payload", payload.toString()}, env, stream(out), stream(err));
        assertEquals(PosShimBuildCommand.EXIT_OK, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(ShimStamp.payloadStamp(payload).isPresent());

        int failed = command.run(new String[] {"--deps-dir", dir.resolve("none").toString(), "--petroapps",
            petroapps.toString(), "--payload", payload.toString()}, env, stream(out), stream(err));
        assertEquals(PosShimBuildCommand.EXIT_FAILED, failed);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("ERROR: missing build dependencies"));
        assertEquals(PosShimBuildCommand.EXIT_USAGE,
                command.run(new String[] {"--bogus", "x"}, env, stream(out), stream(err)));
    }
}
