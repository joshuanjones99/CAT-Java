package com.verifone.cat.launch;

import java.util.Map;

import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.rig.Rig;

/**
 * The step-4 stages the Tools page runs on their own, outside a run: exactly {@link CommanderStages#real}'s, so a
 * tool pairs, provisions and onboards with the code a run uses and nothing of its own.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the stages are rig-verified inside step 4 (HANDOFF §4); run alone from
 * the Tools page they are not.
 */
public final class ToolStages {

    private final CommanderStages stages;

    private ToolStages(CommanderStages stages) {
        this.stages = stages;
    }

    /** The production stages, with {@code environ} for keys and paths. */
    public static ToolStages real(Map<String, String> environ) {
        return new ToolStages(CommanderStages.real(environ));
    }

    /** {@code petrotest provision}: the pos-shim payload, the browser override and the CDP relay on every target. */
    public boolean provision(Rig rig, Say say) {
        return stages.provision(rig, say);
    }

    /** {@code petrotest psi-init}: the Commander password if the installer left one, then every register paired. */
    public boolean pairRegisters(Rig rig, Say say) {
        return stages.psiInit(rig, true, say);
    }

    /** {@link TunnelStart}: a running {@code cloud-tunnel} for the rig adopted, else one started and left running. */
    public boolean cloudTunnel(String rigName, Rig rig, Say say) {
        return stages.cloudTunnel(rigName, rig, say);
    }

    /** {@code petrotest commander-connect}: the Commander onboarded with {@code settings}. */
    public boolean commanderConnect(Rig rig, CommanderConnect settings, Say say) {
        return stages.commanderConnect(rig, settings, say);
    }
}
