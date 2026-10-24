package com.verifone.cat.connections;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * Is the rig's controller address one this PC actually holds?
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). This is the Host PC row. It exists for the failure
 * in HANDOFF.md §1.11: the controller silently stopped being the machine the rig file described,
 * and every check after that answered about the wrong box.
 *
 * <p>The Python CAT answers by binding the address (its port-0 bind fails with
 * {@code EADDRNOTAVAIL} when the OS does not hold it). Java has no portable errno, and a bind
 * failure's message is locale text, so this port asks the OS for its interface addresses
 * instead: the same question, answered by listing rather than by a failing call. A wildcard
 * address is never a positive answer, because every machine holds it.
 *
 * <p>The verdict is three-valued, as in the Python: {@code up} needs one address this PC holds;
 * {@code down} needs every address to have been checked and none held; anything else (a
 * wildcard, or the adapters could not be listed) is {@code unknown}. Reporting "could not ask"
 * as "this is not the controller" would send an operator to edit the rig file on no evidence.
 */
public final class HostAddressCheck {

    private HostAddressCheck() {}

    /** The Host PC verdict for {@code target}, whose host is the address to hold. */
    public static ProbeResult check(Target target) {
        String host = target.host();
        if (host == null || host.isBlank()) {
            return ConnectionProbe.unknown(target, "no address declared in the rig file");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return detail(target, ProbeState.DOWN, host + " does not resolve here");
        }

        Set<InetAddress> held;
        try {
            held = heldAddresses();
        } catch (SocketException e) {
            return detail(target, ProbeState.UNKNOWN,
                    "could not list this PC's network adapters: " + e.getMessage());
        }

        boolean anyHeld = false;
        boolean anyUntestable = false;
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()) {
                anyUntestable = true;
            } else if (held.contains(address)) {
                anyHeld = true;
            }
        }
        if (anyHeld) {
            return detail(target, ProbeState.UP, host + " is an address this PC holds");
        }
        if (anyUntestable) {
            return detail(target, ProbeState.UNKNOWN, host + " is a wildcard address. Every machine "
                    + "holds it, so it proves nothing about which machine this is; name the "
                    + "controller's own address in the rig file");
        }
        return detail(target, ProbeState.DOWN, host + " is not held by any local network adapter"
                + " — this PC is not the controller the rig file names");
    }

    private static Set<InetAddress> heldAddresses() throws SocketException {
        Set<InetAddress> held = new HashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
            while (addresses.hasMoreElements()) {
                held.add(addresses.nextElement());
            }
        }
        return held;
    }

    private static ProbeResult detail(Target target, ProbeState state, String detail) {
        return new ProbeResult(target.name(), state, detail, Target.Kind.LOCAL);
    }
}
