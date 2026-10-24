package com.verifone.cat.connections;

import java.util.List;

/**
 * The three endpoints a run cannot start without, given as plain hosts.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code cat.connections.targets_for}
 * derives these from the rig model. This record takes the hosts directly so the probes can be
 * wired to the full rig model later without the rig model being a dependency now.
 *
 * <p>TODO (derive from the rig model once it is ported): {@code commanderHost} from
 * {@code rig.commander.host}; {@code registerId}/{@code registerHost} from
 * {@code rig.primary_register} (its id and host); {@code controllerHost} from
 * {@code rig.controller.host}. Still not derived, and so not probed here: the optional rows
 * (extra registers from {@code rig.registers[1:]}, each {@code rig.devices} key, and the
 * Config tab's selection), the Xray row, the Receipt Printer row
 * ({@code printer_port}, a serial listing), the VATS checks for {@code pinpad: vats}, and
 * the per-register printer-port checks.
 *
 * <p>The two ports are the platform's, not rig fields, as in the Python CAT: CGILink on
 * port 80 ({@code docs/commander-config-surface.md}), and SSH on port 22, which
 * {@code provision} connects over. A register is probed on SSH, not on its CDP port, because
 * the CDP port only opens once provisioning has pushed the browser.
 *
 * @param commanderHost   the Commander's host
 * @param registerId      the primary register's id, used only in its row label
 * @param registerHost    the primary register's host
 * @param controllerHost  the address the controller PC should hold on its rig adapter
 */
public record Endpoints(String commanderHost, String registerId, String registerHost, String controllerHost) {

    /** CGILink, the Commander's configuration surface. */
    public static final int COMMANDER_SERVICE_PORT = 80;

    /** SSH, the port a register is provisioned over. */
    public static final int REGISTER_SERVICE_PORT = 22;

    /** The three targets every run probes, in the order the status page shows them. */
    public List<Target> fixedTargets() {
        return List.of(
                Target.tcp("Commander", commanderHost, COMMANDER_SERVICE_PORT),
                Target.tcp("Register " + registerId, registerHost, REGISTER_SERVICE_PORT),
                Target.local("Host PC", controllerHost));
    }
}
