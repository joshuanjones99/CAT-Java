package com.verifone.cat.tools;

import java.util.List;
import java.util.Map;

import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.launch.ToolStages;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * The Tools page's operator tools, each one thing done to the rig outside a run. The web page runs them through this
 * seam, so its tests pass a fake and nothing reaches a device.
 *
 * <ul>
 *   <li><b>POS Pairing</b>: {@code provision} (pairing drives the register's browser over CDP, which the browser
 *       override brings up), {@code psi-init} with pairing (the Commander password first, if the installer left one;
 *       then {@code cpairingsvc}, the OTP typed on the POS), and {@code provision} again, because pairing reboots the
 *       register: step 4's order after a PSI install.</li>
 *   <li><b>Onboard to Commander Connect</b>: the values checked first (nothing is sent when one is wrong), the cloud
 *       tunnel adopted or started (the lab Commander has no WAN link), then {@code commander-connect}: step 4's.</li>
 *   <li><b>Reboot C18</b>: one register rebooted ({@link RigReboot}), then {@code provision} to bring its pos-shim back,
 *       as step 4 does after pairing's reboot.</li>
 *   <li><b>Reboot Commander</b>: rebooted, then back on SSH and CGILink ({@link RigReboot}).</li>
 *   <li><b>Push shim to register</b> (the POS page's button, user 2026-11-10): {@code provision}, so a register that
 *       answers but has no pos-shim listening gets one.</li>
 * </ul>
 *
 * <p>Every method reports a failure as the last line on {@code say}'s error stream, and returns false.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): none of these has been run from the Tools page against a rig.
 */
public interface RigTools {

    /** The register ids of the rig named, in rig order; empty when it does not load. */
    List<String> registers(String rigName);

    boolean pair(String rigName, Say say);

    boolean onboard(String rigName, CommanderConnect settings, Say say);

    boolean rebootRegister(String rigName, String registerId, Say say);

    boolean rebootCommander(String rigName, Say say);

    /**
     * The POS page's Push shim to register: {@code provision} on the rig, which pushes the pos-shim payload (with the
     * browser override and the CDP relay) and starts the shim, so the register's shim port opens. Refused, with
     * nothing pushed, when the rig has no register {@code registerId}.
     */
    boolean pushShim(String rigName, String registerId, Say say);

    /** The real tools: the rig file from {@code $PETROTEST_RIGS_DIR}, the run's stages, SSH through JSch. */
    static RigTools standard(Map<String, String> environ) {
        return new Standard(environ, ToolStages.real(environ), RigReboot.Seams.system());
    }

    /** The production tools. */
    final class Standard implements RigTools {

        private final Map<String, String> environ;
        private final ToolStages stages;
        private final RigReboot.Seams reboots;

        Standard(Map<String, String> environ, ToolStages stages, RigReboot.Seams reboots) {
            this.environ = environ == null ? Map.of() : environ;
            this.stages = stages;
            this.reboots = reboots;
        }

        @Override
        public List<String> registers(String rigName) {
            try {
                return load(rigName).registers().stream().map(Rig.Register::id).toList();
            } catch (RuntimeException e) {
                return List.of();
            }
        }

        @Override
        public boolean pair(String rigName, Say say) {
            Rig rig = load(rigName, say);
            if (rig == null) {
                return false;
            }
            say.out("provision: the browser override and CDP relay pairing drives");
            if (!stages.provision(rig, say)) {
                return false;
            }
            say.out("psi-init: pairing every register");
            if (!stages.pairRegisters(rig, say)) {
                return false;
            }
            say.out("provision: the pos-shim back on the register pairing rebooted");
            return stages.provision(rig, say);
        }

        @Override
        public boolean onboard(String rigName, CommanderConnect settings, Say say) {
            List<String> problems = settings.problems();
            if (!problems.isEmpty()) {
                say.err("Commander Connect not onboarded, nothing was sent: " + String.join("; ", problems));
                return false;
            }
            Rig rig = load(rigName, say);
            if (rig == null) {
                return false;
            }
            return stages.cloudTunnel(rigName, rig, say) && stages.commanderConnect(rig, settings, say);
        }

        @Override
        public boolean rebootRegister(String rigName, String registerId, Say say) {
            Rig rig = load(rigName, say);
            if (rig == null) {
                return false;
            }
            TargetSpec box = target(rig, TargetRole.REGISTER, registerId);
            if (box == null) {
                say.err("rig " + rigName + " has no register " + registerId + "; nothing was rebooted");
                return false;
            }
            if (!RigReboot.reboot(box, reboots, say)) {
                return false;
            }
            say.out("provision: the pos-shim back on " + registerId);
            return stages.provision(rig, say);
        }

        @Override
        public boolean pushShim(String rigName, String registerId, Say say) {
            Rig rig = load(rigName, say);
            if (rig == null) {
                return false;
            }
            if (target(rig, TargetRole.REGISTER, registerId) == null) {
                say.err("rig " + rigName + " has no register " + registerId + "; nothing was pushed");
                return false;
            }
            say.out("provision: the pos-shim, the browser override and the CDP relay, for " + registerId);
            return stages.provision(rig, say);
        }

        @Override
        public boolean rebootCommander(String rigName, Say say) {
            Rig rig = load(rigName, say);
            if (rig == null) {
                return false;
            }
            return RigReboot.reboot(target(rig, TargetRole.COMMANDER, null), reboots, say);
        }

        /** The rig's box with {@code role} (and, for a register, {@code id}), or null. */
        private TargetSpec target(Rig rig, TargetRole role, String id) {
            for (TargetSpec spec : TargetSpec.fromRig(rig, environ)) {
                if (spec.role() == role && (id == null || spec.name().equals(id))) {
                    return spec;
                }
            }
            return null;
        }

        private Rig load(String rigName) {
            return RigLoader.load(rigName, RigLoader.rigsDir(environ));
        }

        private Rig load(String rigName, Say say) {
            try {
                return load(rigName);
            } catch (RuntimeException e) {
                say.err("cannot load rig " + rigName + ": " + e.getMessage());
                return null;
            }
        }
    }
}
