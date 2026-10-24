package com.verifone.cat.commander.links;

import java.util.Objects;

/**
 * One connection the Commander must hold before a run can pay: a {@code name -> host:port} pair. A port of
 * {@code petrotest.commander_links.CommanderLink}.
 *
 * <p>The host always comes from the rig file, never from a literal here (DESIGN.md section 4.2 rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record CommanderLink(String name, String host, int port) {

    /** A peer address as {@code netstat} prints it: host and port. */
    public record Endpoint(String host, int port) {}

    public CommanderLink {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
    }

    /** The peer this link dials. */
    public Endpoint endpoint() {
        return new Endpoint(host, port);
    }

    @Override
    public String toString() {
        return name + " -> " + host + ":" + port;
    }
}
