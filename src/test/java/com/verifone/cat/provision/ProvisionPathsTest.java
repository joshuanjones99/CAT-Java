package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.core.CatHome;

/**
 * Where provisioning reads its inputs, and CAT-Java's own copy of the register-side sources ({@code bridge/}, user
 * 2026-11-10). Reads this checkout's {@code bridge/} and temporary folders only. Evidence about this repository only.
 */
class ProvisionPathsTest {

    /** This checkout: the tests run from it. */
    private static final Path CHECKOUT = Path.of("").toAbsolutePath();

    @TempDir
    Path dir;

    @Test
    void thePayloadIsTheVariablesElseThePythonCheckoutsWhenItIsHereElseCatJavasOwn() throws IOException {
        Path python = dir.resolve("CAT");

        assertEquals(dir.resolve("p"), ProvisionPaths.payloadRoot(Map.of("CAT_PAYLOAD_ROOT", dir.resolve("p")
                .toString(), "CAT_PYTHON_ROOT", python.toString())));
        assertEquals(CatHome.java().resolve("payload"),
                ProvisionPaths.payloadRoot(Map.of("CAT_PYTHON_ROOT", python.toString())));

        Files.createDirectories(python);
        assertEquals(python.resolve("payload"), ProvisionPaths.payloadRoot(Map.of("CAT_PYTHON_ROOT",
                python.toString())));
    }

    @Test
    void theBridgeIsThisCheckoutsUnlessTheVariableMovesIt() {
        assertEquals(dir, ProvisionPaths.bridgeRoot(Map.of("CAT_BRIDGE_ROOT", dir.toString())));
        assertEquals(dir.resolve("bridge").resolve("browser-override"),
                ProvisionPaths.browserOverrideDir(Map.of("CAT_BRIDGE_ROOT", dir.toString())));
    }

    @Test
    void thisCheckoutHoldsTheShimSourcesAndItsLauncher() {
        List<String> names = ShimStamp.shimJavaSources(CHECKOUT).stream().map(p -> p.getFileName().toString())
                .toList();

        assertTrue(names.contains("PosShim.java"), names.toString());
        assertEquals(11, names.size(), names.toString());
        assertTrue(Files.isRegularFile(CHECKOUT.resolve(ShimStamp.SHIM_DIR).resolve(ShimStamp.LAUNCHER_NAME)));
        // The stamp's recipe: without it no build stamps, so no shim builds.
        assertTrue(Files.isRegularFile(CHECKOUT.resolve(ShimStamp.BUILD_SCRIPT)));
    }

    @Test
    void thisCheckoutHoldsEveryBrowserOverrideFileStagingCopies() {
        Path override = ProvisionPaths.browserOverrideDir(Map.of("CAT_BRIDGE_ROOT", CHECKOUT.toString()));
        for (String name : Stage.BRIDGE_FILES) {
            assertTrue(Files.isRegularFile(override.resolve(name)), override.resolve(name) + " is missing");
        }
    }

    @Test
    void theShellScriptsAreLineFeedOnly() throws IOException {
        for (Path script : List.of(CHECKOUT.resolve(ShimStamp.SHIM_DIR).resolve(ShimStamp.LAUNCHER_NAME),
                CHECKOUT.resolve("bridge/browser-override/chromium-wrapper.sh"),
                CHECKOUT.resolve("bridge/browser-override/cdp-relay-boot.sh"))) {
            assertTrue(Files.readString(script).indexOf('\r') < 0, script + " has a CR: a Linux shell would choke");
        }
    }
}
