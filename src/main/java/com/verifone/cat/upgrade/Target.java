package com.verifone.cat.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.rig.Rig;

/**
 * One device the install puts a build on: the Commander, or a register, with its SSH target.
 *
 * <p>A port of {@code petrotest.provision.targets.TargetSpec} and its role, as the upgrade needs them. The
 * Commander is always first, as {@code targets_from_rig} lists it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Target(Role role, SshTarget ssh) {

    /** Which kind of device this is; the root-access push differs between them. */
    public enum Role { COMMANDER, REGISTER }

    /** The name the run's log and step names use: {@code commander}, or the register's id. */
    public String name() {
        return ssh.name();
    }

    public String host() {
        return ssh.host();
    }

    /** The Commander, then every register in the rig file's order. */
    public static List<Target> fromRig(Rig rig, Map<String, String> environ) {
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(Role.COMMANDER, SshTarget.commander(rig, environ)));
        for (Rig.Register register : rig.registers()) {
            targets.add(new Target(Role.REGISTER, SshTarget.register(rig, register, environ)));
        }
        return List.copyOf(targets);
    }
}
