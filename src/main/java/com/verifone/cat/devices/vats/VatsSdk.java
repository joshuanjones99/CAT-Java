package com.verifone.cat.devices.vats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Where Verifone's VATS SDK is staged on this controller. A port of the Python CAT's
 * {@code petrotest.devices.vats.sdk}.
 *
 * <p>CAT does not run PATS's Terminal Connector (Python HANDOFF.md section 1.110). It uses two things out of the same
 * staged zip, {@code terminalConnector-V4.zip} (the simulator registry still fetches it): the embedded Python 3.9 and
 * {@code app/vats_runner/}, which is Verifone's VATS PC package. Nothing from {@code app/patslib/}, PATS's own glue,
 * is used. Nothing here writes to the staged folder.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param root        the staged connector's folder
 * @param interpreter run the agent and relay under this instead of the staged interpreter, or null. For tests:
 *                    {@code agent.py} is 3.9-compatible, so any Python 3.9+ runs it too
 */
public record VatsSdk(Path root, Path interpreter) {

    /** The simulator registry's Terminal Connector {@code cwd}, which is also the zip's root. */
    public static final String CONNECTOR_DIR = "terminalConnector-V4";

    /** Quoted in every {@link DeviceUnavailable} this package raises (DESIGN.md section 7.4). */
    public static final String SECTION = "§7.4";

    public VatsSdk {
        if (root == null) {
            throw new IllegalArgumentException("the VATS SDK's root is required");
        }
    }

    /** The staged SDK under {@code root}, with its own interpreter. */
    public VatsSdk(Path root) {
        this(root, null);
    }

    /** The staged connector's embedded Python 3.9, or the test's interpreter. */
    public Path python() {
        return interpreter != null ? interpreter : root.resolve("python-3.9.11-embed-amd64").resolve("python.exe");
    }

    public Path app() {
        return root.resolve("app");
    }

    public Path vatsRunner() {
        return app().resolve("vats_runner");
    }

    /** Verifone's KM4 relay: the terminal on one side, test scripts on the other. */
    public Path relayScript() {
        return vatsRunner().resolve("vats_connector.py");
    }

    /** The card images the connector zip ships (1,590 on 2026-10-06). */
    public Path collisImages() {
        return app().resolve("Collis_Images");
    }

    /** The pieces this needs that are not there, as paths. */
    public List<String> missing() {
        List<String> gaps = new ArrayList<>();
        for (Path p : List.of(python(), relayScript())) {
            if (!Files.isRegularFile(p)) {
                gaps.add(p.toString());
            }
        }
        return gaps;
    }

    /** The staged SDK under the process's tools root ({@code CAT_TOOLS_ROOT}, else {@code C:\CICD\Tools}). */
    public static VatsSdk locate() {
        return locate(ToolsRoot.resolve(null).resolve(CONNECTOR_DIR));
    }

    /**
     * The staged SDK at {@code root}, or {@link DeviceUnavailable} naming what is missing and how to get it: the
     * Python's {@code locate}.
     */
    public static VatsSdk locate(Path root) {
        VatsSdk sdk = new VatsSdk(root);
        List<String> gaps = sdk.missing();
        if (!gaps.isEmpty()) {
            throw unavailable("the VATS SDK", "not staged: " + String.join(", ", gaps) + " — CAT fetches "
                    + CONNECTOR_DIR + ".zip from Artifactory when a Simulator Build is chosen (Config tab > Downloads)");
        }
        return sdk;
    }

    /**
     * A {@link DeviceUnavailable} naming {@link #SECTION}, as the Python's {@code DeviceUnavailable(what, SECTION,
     * detail)} does: {@code "<what> is not available: <detail> (DESIGN.md §7.4)"}.
     */
    public static DeviceUnavailable unavailable(String what, String detail) {
        return new DeviceUnavailable(what, detail + " (DESIGN.md " + SECTION + ")");
    }
}
