package com.verifone.cat.provision;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where the provisioning inputs live on this controller. The Python CAT owns them: its checkout builds
 * {@code payload/} (the pos-shim jar, its launcher and stamp, through {@code tools/build_pos_shim.py}), keeps
 * the bridge sources and the rig templates, and PATS keeps the JaCoCo agent. Each value is an environment
 * variable first, else the Python CAT's default, as {@code RigLoader} and {@code LaunchPaths} do.
 *
 * <p>Environment variables: {@code CAT_PYTHON_ROOT} (the Python checkout), {@code CAT_PAYLOAD_ROOT} (the payload
 * directory), {@code CAT_PATS_RUN} (PATS's run folder, for its JaCoCo agent).
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

    /** The staged payload the push reads, {@code payload/} under the Python checkout unless overridden. */
    public static Path payloadRoot(Map<String, String> env) {
        return fromEnv(env, "CAT_PAYLOAD_ROOT", pythonRoot(env).resolve("payload"));
    }

    /** The rig templates the render step fills in (checked-in Python source, not payload). */
    public static Path templatesRoot(Map<String, String> env) {
        return pythonRoot(env).resolve("src").resolve("petrotest").resolve("provision").resolve("templates");
    }

    /** The bridge sources the browser-override files are copied from. */
    public static Path browserOverrideDir(Map<String, String> env) {
        return pythonRoot(env).resolve("bridge").resolve("browser-override");
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
