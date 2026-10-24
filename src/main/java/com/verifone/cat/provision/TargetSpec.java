package com.verifone.cat.provision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.rig.Rig;

/**
 * One box {@code provision} signs in to. A port of {@code petrotest.provision.targets}: the rig becomes one
 * Commander target and one target per register, because the register rows go to every register a rig has.
 *
 * <p>The Java rig schema has no per-register {@code ssh_user}, {@code ssh_port} or {@code shim_port}, nor a
 * per-register key, so a register takes the Commander's account and key on port 22, and the shim on 8801. The
 * Python schema allows overrides; this port does not, and {@link Rig} is the authority.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record TargetSpec(TargetRole role, String name, String host, String sshUser, int sshPort, int shimPort,
        String sshKeyFilename) {

    /** The shim's HTTP port. A rig file cannot override it yet. */
    public static final int DEFAULT_SHIM_PORT = PosShimClient.DEFAULT_SHIM_PORT;

    /** The default SSH port. */
    public static final int DEFAULT_SSH_PORT = 22;

    public TargetSpec {
        if (name == null || name.isEmpty() || host == null || host.isEmpty() || sshUser == null
                || sshUser.isEmpty()) {
            throw new IllegalArgumentException("a provisioning target needs a name, host and ssh user");
        }
        if (sshPort < 1 || sshPort > 65535 || shimPort < 1 || shimPort > 65535) {
            throw new IllegalArgumentException("ports must be 1 to 65535");
        }
    }

    /** Every box a rig provisions: the Commander first, then each register in rig order. */
    public static List<TargetSpec> fromRig(Rig rig, Map<String, String> environ) {
        Rig.Commander commander = rig.commander();
        String commanderKey = commander.resolveKeyFilename(environ);
        List<TargetSpec> targets = new ArrayList<>();
        targets.add(new TargetSpec(TargetRole.COMMANDER, "commander", commander.host(), commander.sshUser(),
                DEFAULT_SSH_PORT, DEFAULT_SHIM_PORT, commanderKey));
        for (Rig.Register register : rig.registers()) {
            targets.add(new TargetSpec(TargetRole.REGISTER, register.id(), register.host(), commander.sshUser(),
                    DEFAULT_SSH_PORT, DEFAULT_SHIM_PORT, commanderKey));
        }
        if (targets.size() == 1) {
            throw new IllegalArgumentException("rig declares no registers; the POS RMI agent and HTTP shim go to every"
                    + " register, so a rig with none cannot be provisioned");
        }
        return List.copyOf(targets);
    }

    /** The SSH target this box is reached through, for {@code JschTransport}. */
    public SshTarget toSshTarget() {
        return new SshTarget(name, host, sshPort, sshUser, sshKeyFilename);
    }

    /** {@code name (role)}, as the run report names a box. */
    public String describe() {
        return name + " (" + role.wire() + ")";
    }
}
