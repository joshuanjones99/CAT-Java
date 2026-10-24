package com.verifone.cat.devices.eps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.Checklist;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * The read-only half of the Python CAT's {@code petrotest.commander_links}: which payment links the
 * Commander must hold, and which it holds now, read from its {@code netstat -ant}.
 *
 * <p><b>What "applied" means is a live connection, not a file</b> (the Python's module docstring,
 * 2026-09-21): the Commander rewrote its FEP table seven minutes after the process that reads it started,
 * while the EPS was already using the new address, so file ages say nothing. Whether the Commander holds the
 * socket is unambiguous.
 *
 * <p>Only {@code netstat} is run: safe inside {@code verify()} (DESIGN.md 6.2). The import and the one PMC
 * restart ({@code ensure_payment_links}, {@code payment_links_cli}) are step 4's, and not ported here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Parsed only from rows shaped like the ones read off the lab
 * Commander on 2026-09-21; this port has run against {@code InMemoryTransport} only.
 */
public final class CommanderLinks {

    /** Buypass over UMF: the port the FEP config sets, a property of the protocol, not of a rig. */
    public static final int BUYPASS_FEP_PORT = Checklist.BUYPASS_UMF_PORT;

    /** The one command this class runs on the Commander. */
    public static final String NETSTAT = "netstat -ant";

    /**
     * Socket states that mean the Commander is holding a working connection. {@code CLOSE_WAIT} is
     * deliberately excluded: the POP socket sat in it on 2026-09-21, dialled correctly then half-closed by
     * the far end, which is a link that will not carry a transaction.
     */
    static final Set<String> LIVE_STATES = Set.of("ESTABLISHED");

    /** States in which a socket never carried a byte to its peer: a dial in progress, or a listener. */
    static final Set<String> UNCONNECTED_STATES = Set.of("SYN_SENT", "LISTEN", "CLOSE", "CLOSED");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,5}");
    private static final String MAPPED = "::ffff:";

    private CommanderLinks() {}

    /** A peer endpoint as {@code netstat -n} prints it. */
    public record Endpoint(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /** One {@code netstat -ant} row: local endpoint, peer host, peer port, state. */
    public record NetstatRow(String local, String peerHost, int peerPort, String state) {}

    /**
     * The links a card sale needs that a socket can honestly report. Just one: the Buypass FEP, which
     * BUYPASS Host answers on the controller. It is a heartbeat channel (a COMM TEST every 30 s), so the
     * Commander holds it ESTABLISHED continuously.
     *
     * <p>The ViperPay POP is deliberately not here: it is request/response and its socket sits in
     * {@code CLOSE_WAIT} between exchanges, so a socket rule reports a working pinpad path as down.
     *
     * <p>The port is the rig's {@code commander.eps_port}, dialled on {@code controller.host}. {@code netstat
     * -n} prints addresses, so {@code controller.host} must be the address the FEP config dials for the link
     * to be recognised; a DNS name there reads as a missing link.
     */
    public static List<CommanderLink> paymentLinks(Rig rig) {
        int port = rig.commander().epsPort() > 0 ? rig.commander().epsPort() : BUYPASS_FEP_PORT;
        return List.of(new CommanderLink("buypass fep", rig.controller().host(), port));
    }

    /** Peer endpoints the Commander holds a live ({@link #LIVE_STATES}) socket to. */
    public static Set<Endpoint> establishedEndpoints(Transport transport, Duration timeout) {
        Set<Endpoint> live = new LinkedHashSet<>();
        for (NetstatRow row : netstatRows(transport, timeout)) {
            if (LIVE_STATES.contains(row.state())) {
                live.add(new Endpoint(row.peerHost(), row.peerPort()));
            }
        }
        return live;
    }

    /**
     * The Commander's sockets to {@code host:port} that the far end accepted: local endpoint to state. For a
     * request/response peer (a physical pinpad's POP port), where a held ESTABLISHED socket is the wrong
     * test: any state but {@link #UNCONNECTED_STATES} counts.
     */
    public static Map<String, String> peerSockets(Transport transport, String host, int port, Duration timeout) {
        Map<String, String> sockets = new LinkedHashMap<>();
        for (NetstatRow row : netstatRows(transport, timeout)) {
            if (row.peerHost().equals(host) && row.peerPort() == port && !UNCONNECTED_STATES.contains(row.state())) {
                sockets.put(row.local(), row.state());
            }
        }
        return sockets;
    }

    /** Splits {@code wanted} into what the Commander already holds and what it does not. */
    public static LinkPlan plan(List<CommanderLink> wanted, Set<Endpoint> established) {
        List<CommanderLink> present = new ArrayList<>();
        List<CommanderLink> missing = new ArrayList<>();
        for (CommanderLink link : wanted) {
            (established.contains(link.endpoint()) ? present : missing).add(link);
        }
        return new LinkPlan(present, missing);
    }

    /**
     * {@code netstat -ant} on the Commander, parsed. Reads the last three columns ({@code <local> <peer>
     * <state>}), the shape of both the busybox and GNU builds on these devices. Header and listener rows fall
     * out: their peer has no numeric port. A failing {@code netstat} raises: it is not "no sockets".
     */
    static List<NetstatRow> netstatRows(Transport transport, Duration timeout) {
        CommandResult result = transport.run(NETSTAT, timeout);
        if (!result.ok()) {
            throw new DeviceError("netstat on the Commander exited " + result.exitStatus());
        }
        return parse(result.stdout());
    }

    /** The parsing half of {@link #netstatRows}, for a caller that already has the text. */
    public static List<NetstatRow> parse(String stdout) {
        List<NetstatRow> rows = new ArrayList<>();
        if (stdout == null) {
            return rows;
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] fields = WHITESPACE.split(trimmed);
            if (fields.length < 2) {
                continue;
            }
            String peer = fields[fields.length - 2];
            int colon = peer.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String portText = peer.substring(colon + 1);
            if (!DIGITS.matcher(portText).matches()) {
                continue;
            }
            String local = fields.length >= 3 ? unmapped(fields[fields.length - 3]) : "";
            // A dual-stack (tcp6) socket may print an IPv4 peer in mapped form.
            rows.add(new NetstatRow(local, unmapped(peer.substring(0, colon)), Integer.parseInt(portText),
                    fields[fields.length - 1]));
        }
        return rows;
    }

    private static String unmapped(String address) {
        return address.startsWith(MAPPED) ? address.substring(MAPPED.length()) : address;
    }
}
