package com.verifone.cat.launch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.verifone.cat.connections.Readiness;
import com.verifone.cat.devices.vats.Btt;
import com.verifone.cat.devices.vats.VatsReadiness;
import com.verifone.cat.devices.vats.VatsSdk;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Step 1's VATS lines for a {@code pinpad: vats} rig: the terminal's VATS port, the staged SDK and BTT, each required.
 * The Python CAT's {@code cat.readiness.check_rig} adds them after the rig network lines
 * ({@code petrotest.devices.vats.readiness.checks}); none on any other rig.
 *
 * <p>Hooks (SystemCheckStages / SystemChecksStep, owned by launchcore): in {@code SystemCheckStages.real}'s
 * {@code rigNetwork(rig)}, append {@code VatsSystemChecks.lines(rig, environ)} to the list it returns (so a test's
 * fake stages never probe a pinpad or read this PC's BTT folders); in {@code SystemChecksStep.rigOnlyGaps}, drop the
 * {@code pinpad: vats} gap.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsSystemChecks {

    /** Each probe's budget: the Python's {@code check_rig} default, 3 s. */
    public static final Duration TIMEOUT = Duration.ofSeconds(3);

    private VatsSystemChecks() {}

    /** The VATS lines for {@code rig} on this controller ({@code CAT_TOOLS_ROOT}, the real BTT folders). */
    public static List<Readiness> lines(Rig rig, Map<String, String> environ) {
        if (rig.pinpad() != Rig.Pinpad.VATS || rig.vats() == null) {
            return List.of();
        }
        Path tools = ToolsRoot.resolve(environ.get(ToolsRoot.ENV), null);
        Btt btt = Btt.forThisMachine(Path.of(System.getProperty("user.home")), tools);
        return new VatsReadiness(tools.resolve(VatsSdk.CONNECTOR_DIR), btt).checks(rig, TIMEOUT);
    }
}
