package com.verifone.cat.devices.eps;

import java.util.Objects;

/**
 * One connection the Commander must hold before a run can pay. Ported from the Python CAT's
 * {@code petrotest.commander_links.CommanderLink}.
 *
 * <p>{@code host} always comes from the rig file, never from a literal here (DESIGN.md 4.2 rule 5). On
 * this bench every one of them is the controller, because the controller runs the simulators.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record CommanderLink(String name, String host, int port) {

    public CommanderLink {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
    }

    /** The peer endpoint the Commander's socket must point at. */
    public CommanderLinks.Endpoint endpoint() {
        return new CommanderLinks.Endpoint(host, port);
    }

    /** {@code buypass fep -> <controller>:41107}, as a FAIL message names it. */
    @Override
    public String toString() {
        return name + " -> " + host + ":" + port;
    }
}
