package com.verifone.cat.devices.vats;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.connections.Readiness;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.rig.Rig;

/**
 * The run-start checks for a {@code pinpad: vats} rig, each failing with what a person should do. A port of the
 * Python CAT's {@code petrotest.devices.vats.readiness}, whose lines the Python's {@code cat.readiness.check_rig} adds
 * after the rig network lines, so a closed VATS port stops the run at step 1, named, instead of the first pinpad case
 * timing out half an hour in. Every line is required.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsReadiness {

    /**
     * What to do about a terminal whose VATS port is closed. The packages are the AQA Confluence page's ("Reference
     * for automating PATS testcases using an actual pin-pad device", 183274336): a stock OS/VIPA bundle has no VATS
     * agent at all.
     */
    public static final String VATS_PORT_HINT = "check the pinpad is powered on and cabled to the rig network, and that "
            + "it runs the VATS-enabled OS and VIPA packages (whitelisted serial; see the AQA Confluence page "
            + "'Reference for automating PATS testcases using an actual pin-pad device'), then start the run again";

    /** The Python's {@code checks} default. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private final Path connectorRoot;
    private final Btt btt;

    /**
     * @param connectorRoot the staged {@code terminalConnector-V4} folder
     * @param btt           where BTT installs are looked for
     */
    public VatsReadiness(Path connectorRoot, Btt btt) {
        this.connectorRoot = connectorRoot;
        this.btt = btt;
    }

    /** The VATS lines for {@code rig}; none unless its pinpad is {@code vats}. */
    public List<Readiness> checks(Rig rig, Duration timeout) {
        List<Readiness> lines = new ArrayList<>();
        if (rig.pinpad() != Rig.Pinpad.VATS || rig.vats() == null) {
            return lines;
        }
        Rig.Vats config = rig.vats();

        // A relay already holding the terminal (a CAT session, or PATS's connector) proves the port was open, and a
        // second connection would only compete with it.
        if (Ports.listening(config.km4Port())) {
            lines.add(new Readiness(true, "pinpad VATS port",
                    "a VATS relay already holds the terminal (port " + config.km4Port() + ")", true));
        } else if (Ports.listening(config.port(), config.host(), timeout)) {
            lines.add(new Readiness(true, "pinpad VATS port", config.host() + ":" + config.port() + " is open", true));
        } else {
            lines.add(new Readiness(false, "pinpad VATS port closed", config.host() + ":" + config.port()
                    + " does not accept a connection — " + VATS_PORT_HINT, true));
        }

        try {
            VatsSdk sdk = VatsSdk.locate(connectorRoot);
            lines.add(new Readiness(true, "VATS SDK staged", sdk.root().toString(), true));
        } catch (DeviceUnavailable e) {
            lines.add(new Readiness(false, "VATS SDK not staged", e.getMessage(), true));
        }

        List<Btt.Install> installed = btt.installs(config.bttVersion());
        if (!installed.isEmpty()) {
            Btt.Install first = installed.get(0);
            lines.add(new Readiness(true, "BTT installed", first.label() + " at " + first.root(), true));
        } else {
            String wanted = config.bttVersion() != null && !config.bttVersion().isEmpty()
                    ? "BTT " + config.bttVersion() : "no Brand Test Tool";
            lines.add(new Readiness(false, "BTT not installed", wanted + " under " + btt.describeGlobs()
                    + " — install it from Fime", true));
        }
        return lines;
    }
}
