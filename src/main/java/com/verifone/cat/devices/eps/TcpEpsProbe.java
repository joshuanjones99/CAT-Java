package com.verifone.cat.devices.eps;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.preflight.Surfaces;
import com.verifone.cat.rig.Rig;

/**
 * A bounded TCP connect to one listener. Ported from the Python CAT's {@code petrotest.devices.eps.TcpEpsProbe}.
 * <b>Not {@code eps_reachable}'s probe</b>: aimed at {@code commander.host:eps_port} it fails on a healthy rig,
 * because that port listens on the controller (see {@link CommanderLinkEpsProbe}).
 *
 * <p>Two properties worth keeping: it connects and disconnects and never sends (a byte at a payment endpoint
 * during a verification would be a mutation), and the host and port come from the rig, never a literal.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Exercised against a loopback listener only.
 */
public final class TcpEpsProbe implements Surfaces.EpsProbe {

    private static final Logger LOG = LoggerFactory.getLogger(TcpEpsProbe.class);

    private final String host;
    private final int port;

    public TcpEpsProbe(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * {@code controller.host:commander.eps_port}: where the payment host listens. It answers "is the
     * payment-host simulator bound", and says nothing about whether the Commander's EPS has connected to it.
     */
    public static TcpEpsProbe fromRig(Rig rig) {
        return new TcpEpsProbe(rig.controller().host(), rig.commander().epsPort());
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public String describe() {
        return host + ":" + port;
    }

    /**
     * True if the listener accepted a connection within {@code timeout}. "Did not accept" is an answer, not
     * an error, so a refusal, a timeout or an unresolvable name is {@code false}, logged with its type.
     */
    @Override
    public boolean reachable(Duration timeout) {
        int ms = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), ms);
            // Connected. Closed at once by try-with-resources: this probe never writes.
            return true;
        } catch (SocketTimeoutException e) {
            LOG.info("TCP probe to {} timed out after {} ms", describe(), ms);
            return false;
        } catch (IOException | IllegalArgumentException e) {
            LOG.info("TCP probe to {} refused or unreachable: {}", describe(), e.toString());
            return false;
        }
    }

    @Override
    public String toString() {
        return "TcpEpsProbe(" + describe() + ")";
    }
}
