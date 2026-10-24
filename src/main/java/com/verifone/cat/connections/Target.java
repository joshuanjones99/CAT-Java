package com.verifone.cat.connections;

/**
 * One endpoint to probe: a name for the status row, a host, and how to ask about it.
 *
 * <p>The Python CAT's {@code cat.connections.Target}. {@link Endpoints#targetsFor} derives these
 * from the rig model as {@code targets_for} does; a caller may also build one directly.
 *
 * @param name  the row's label, as the operator reads it
 * @param host  the host to ask; for {@link Kind#LOCAL} the address this PC should hold
 * @param port  the service port for {@link Kind#TCP}; {@code null} means the rig file
 *              declared no port, and the probe reports {@code unknown} rather than {@code down}
 * @param kind  how the endpoint is asked about
 * @param fixed whether the run cannot start without this endpoint: a failed fixed row fails
 *              Connections, a failed optional row is only a warning
 */
public record Target(String name, String host, Integer port, Kind kind, boolean fixed) {

    /** How a target is probed. */
    public enum Kind {
        /** A TCP connect to the service port. */
        TCP,
        /** Whether this machine holds the address; no port, no packet. */
        LOCAL
    }

    /** A target that is not fixed: present only when selected. */
    public Target(String name, String host, Integer port, Kind kind) {
        this(name, host, port, kind, false);
    }

    /** This target, marked fixed. */
    public Target asFixed() {
        return new Target(name, host, port, kind, true);
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
