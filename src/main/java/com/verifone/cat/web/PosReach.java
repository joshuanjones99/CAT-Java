package com.verifone.cat.web;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Whether the POS page can show the register: the register answering on the network, and its pos-shim port open.
 * No Python equivalent (its web UI has no POS screen); user, 2026-11-10.
 *
 * <p>The real probe is two TCP connects, never a request. The shim port answering is {@link State#LIVE}. A refused
 * connect proves the register is up with nothing listening there ({@link State#NO_SHIM}); otherwise (a timeout, no
 * route, an unknown name) the register's SSH port is tried, and an answer or a refusal there is {@link State#NO_SHIM}
 * too. Nothing answering at all is {@link State#NOT_CONNECTED}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): checked against loopback listeners only ({@code PosReachTest}).
 */
@FunctionalInterface
interface PosReach {

    /** What the probe found. */
    enum State {
        /** The register does not answer on the network. */
        NOT_CONNECTED,
        /** The register answers, but nothing listens on its pos-shim port. */
        NO_SHIM,
        /** The pos-shim port is open. */
        LIVE
    }

    /** The register's SSH port, the second thing tried when the shim port does not answer. */
    int SSH_PORT = 22;

    /** Each connect's budget: a register on the lab LAN answers in milliseconds. */
    Duration TIMEOUT = Duration.ofMillis(1500);

    State probe(String host, int shimPort);

    /** The real probe, with {@link #TIMEOUT} per connect. */
    static PosReach tcp() {
        return tcp(SSH_PORT, TIMEOUT);
    }

    /** The probe over TCP, trying {@code sshPort} when the shim port does not answer. */
    static PosReach tcp(int sshPort, Duration timeout) {
        int millis = (int) Math.max(1, timeout.toMillis());
        return (host, shimPort) -> {
            switch (connect(host, shimPort, millis)) {
                case OPEN:
                    return State.LIVE;
                case REFUSED:
                    return State.NO_SHIM;
                default:
                    return connect(host, sshPort, millis) == Answer.SILENT ? State.NOT_CONNECTED : State.NO_SHIM;
            }
        };
    }

    /** One connect's outcome. */
    enum Answer { OPEN, REFUSED, SILENT }

    private static Answer connect(String host, int port, int millis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), millis);
            return Answer.OPEN;
        } catch (ConnectException e) {
            // "Connection refused" is the host answering with a reset; "no route" and the rest are silence.
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            return message.contains("refused") ? Answer.REFUSED : Answer.SILENT;
        } catch (IOException | IllegalArgumentException e) {
            return Answer.SILENT;
        }
    }
}
