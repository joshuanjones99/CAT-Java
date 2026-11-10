package com.verifone.cat.provision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.core.CatHome;
import com.verifone.cat.desktop.Shortcuts;

/**
 * Where the provisioning inputs live on this controller. The register-side sources (the pos-shim and the browser
 * override, {@code bridge/}) are CAT-Java's own, a copy of the Python CAT's (user, 2026-11-10), read from this
 * checkout ({@link #bridgeRoot}). The rig templates are still the Python checkout's, and PATS keeps the JaCoCo agent.
 * The payload is the Python checkout's {@code payload/} when that checkout is here, so both CATs push one payload;
 * else CAT-Java's own under {@code ~/.cat/java/payload}. Each value is an environment variable first.
 *
 * <p>Environment variables: {@code CAT_PYTHON_ROOT} (the Python checkout), {@code CAT_PAYLOAD_ROOT} (the payload
 * directory), {@code CAT_BRIDGE_ROOT} (the folder holding {@code bridge/}), {@code CAT_PATS_RUN} (PATS's run folder,
 * for its JaCoCo agent).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ProvisionPaths {

    /** The Python CAT's checkout on this controller. */
    public static final Path DEFAULT_PYTHON_ROOT = Path.of("C:\\gitrepos\\CAT");

    /** PATS's deployed run folder on this controller, as the Python CAT's {@code CAT_PATS_RUN} default. */
    public static final Path DEFAULT_PATS_RUN = Path.of("C:\\CICD\\Run\\automn");

    private ProvisionPaths() {}

    public static Path pythonRoot(Map<String, String> env) {
        return fromEnv(env, "CAT_PYTHON_ROOT", DEFAULT_PYTHON_ROOT);
    }

    /**
     * The staged payload the push reads: {@code $CAT_PAYLOAD_ROOT}; else {@code payload/} under the Python checkout when
     * that checkout is here (a rig controller, where the Python CAT stages what CAT-Java cannot, such as the pushed
     * Chromium); else CAT-Java's own {@code ~/.cat/java/payload}.
     */
    public static Path payloadRoot(Map<String, String> env) {
        Path python = pythonRoot(env);
        return fromEnv(env, "CAT_PAYLOAD_ROOT", Files.isDirectory(python) ? python.resolve("payload")
                : CatHome.java().resolve("payload"));
    }

    /**
     * The folder holding {@code bridge/pos-shim/} and {@code bridge/browser-override/}: {@code $CAT_BRIDGE_ROOT}, else
     * this CAT-Java checkout (CAT-Java runs from its checkout, {@code run.cmd}).
     */
    public static Path bridgeRoot(Map<String, String> env) {
        return fromEnv(env, "CAT_BRIDGE_ROOT", Shortcuts.repoRoot());
    }

    /** The rig templates the render step fills in (checked-in Python source, not payload). */
    public static Path templatesRoot(Map<String, String> env) {
        return pythonRoot(env).resolve("src").resolve("petrotest").resolve("provision").resolve("templates");
    }

    /** The bridge sources the browser-override files are copied from: CAT-Java's own {@code bridge/browser-override}. */
    public static Path browserOverrideDir(Map<String, String> env) {
        return bridgeRoot(env).resolve("bridge").resolve("browser-override");
    }

    /** PATS's deployed JaCoCo agent, the fallback when the target has no copy. */
    public static Path patsJacocoAgent(Map<String, String> env) {
        return fromEnv(env, "CAT_PATS_RUN", DEFAULT_PATS_RUN).resolve("Bin").resolve("jacocoagent.jar");
    }

    private static Path fromEnv(Map<String, String> env, String name, Path fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}
