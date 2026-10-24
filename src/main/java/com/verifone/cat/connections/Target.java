package com.verifone.cat.connections;

/**
 * One endpoint to probe: a name for the status row, a host, and how to ask about it.
 *
 * <p>Plain data on purpose. The Python CAT derives its targets from the rig model; this
 * port takes them as descriptions so the probes do not depend on the rig model being
 * ported yet (see {@link Endpoints}).
 *
 * @param name the row's label, as the operator reads it
 * @param host the host to ask; for {@link Kind#LOCAL} the address this PC should hold
 * @param port the service port for {@link Kind#TCP}; {@code null} means the rig file
 *             declared no port, and the probe reports {@code unknown} rather than {@code down}
 * @param kind how the endpoint is asked about
 */
public record Target(String name, String host, Integer port, Kind kind) {

    /** How a target is probed. */
    public enum Kind {
        /** A TCP connect to the service port. */
        TCP,
        /** Whether this machine holds the address; no port, no packet. */
        LOCAL
    }

    /** A TCP service endpoint. */
    public static Target tcp(String name, String host, Integer port) {
        return new Target(name, host, port, Kind.TCP);
    }

    /** An address that should be one of this machine's own. */
    public static Target local(String name, String host) {
        return new Target(name, host, null, Kind.LOCAL);
    }
}
