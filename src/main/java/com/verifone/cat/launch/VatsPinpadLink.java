package com.verifone.cat.launch;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * Step 4's pinpad link on a {@code pinpad: vats} rig: {@code emv-keys}, until the Commander's EPS opens a new socket to
 * the M400. A port of the Python CAT's {@code cat.launch.steps.simulators._link_vats_pinpad}.
 *
 * <p>A physical pinpad cannot produce Petro Simulator Suite's {@code DeviceRequest Receieved from EPS} line, so run
 * c60a8f1b0e6d failed waiting for it (Python HANDOFF.md section 1.111). The evidence is on the Commander instead: on
 * 2026-10-07 its {@code netstat} held sockets to {@code <vats.host>:5000} ({@link CommanderLinks#VIPERPAY_POP_PORT},
 * POP 1's port) in {@code LAST_ACK}, and the link is request/response, so a socket whose local endpoint was not there
 * before the download is a new exchange with the terminal ({@link CommanderLinks#peerSockets} has the states that
 * count). One read of {@code netstat} before the download, then a read every {@link Timing#poll} for
 * {@link Timing#linkTimeout} after it. The download is the EPS's (its CA key tables), so it still runs. <b>Petro is
 * never restarted here</b>: a silent terminal is not fixed by restarting a simulator. One attempt.
 *
 * <p>Wired into {@code CommanderConfigurationStep.pinpadLink}'s {@code vats} branch; the method's tail records the
 * row.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the Python's was built 2026-10-08 off-rig and driven only against a fake
 * transport serving {@code netstat} rows shaped like the lab Commander's; so is this one.
 */
final class VatsPinpadLink {

    /**
     * The waits: the Python's {@code VATS_LINK_TIMEOUT} (the simulator's {@code EPS_LINK_TIMEOUT}, 60 s) and
     * {@code VATS_LINK_POLL} (2 s).
     */
    public record Timing(Duration linkTimeout, Duration poll) {

        public static final Timing PRODUCTION = new Timing(Duration.ofSeconds(60), Duration.ofSeconds(2));
    }

    private final Supplier<Transport> commander;
    private final Duration sshTimeout;
    private final Pauser pauser;
    private final Timing timing;

    /**
     * @param commander  a connected transport to the Commander, closed here after the link
     * @param sshTimeout each {@code netstat}'s budget
     */
    public VatsPinpadLink(Supplier<Transport> commander, Duration sshTimeout, Pauser pauser, Timing timing) {
        this.commander = commander;
        this.sshTimeout = sshTimeout;
        this.pauser = pauser;
        this.timing = timing;
    }

    /** The rig's Commander over SSH, with the production timing. */
    public static VatsPinpadLink forRig(Rig rig, Pauser pauser) {
        CommanderAccess access = new CommanderAccess(rig);
        return new VatsPinpadLink(access::connectedTransport, access.sshTimeout(), pauser, Timing.PRODUCTION);
    }

    /**
     * Downloads the keys and watches for the EPS's new socket to the terminal.
     *
     * @param rigName the rig as typed, for the log line
     * @param emvKeys one {@code emv-keys} run (no tables); may throw {@link StageUnavailable}
     */
    public PinpadLink.Result link(StepEmitter step, Rig rig, String rigName, PinpadLink.EmvKeys emvKeys) {
        String host = rig.vats().host();
        int port = CommanderLinks.VIPERPAY_POP_PORT;
        String where = "the pinpad at " + host + ":" + port;
        Transport transport;
        try {
            transport = commander.get();
        } catch (RuntimeException e) {
            return new PinpadLink.Result(false, "cannot reach the Commander to watch its sockets to " + where + ": "
                    + e.getMessage());
        }
        try {
            Map<String, String> before;
            try {
                before = CommanderLinks.peerSockets(transport, host, port, sshTimeout);
            } catch (RuntimeException e) {
                return new PinpadLink.Result(false, "cannot read the Commander's sockets to " + where + ": "
                        + e.getMessage());
            }
            step.log("the Commander holds " + before.size() + " socket(s) to " + where + " before the key download"
                    + (before.isEmpty() ? "" : " (" + states(before) + ")"), "INFO");
            step.log("CAT-Java emv-keys --rig " + rigName, "INFO");
            CommanderConfigurationStep.StepSay say = new CommanderConfigurationStep.StepSay(step);
            if (!emvKeys.run(say)) {
                return new PinpadLink.Result(false, say.lastErr().isEmpty() ? "emv-keys failed" : say.lastErr());
            }
            Map<String, String> last = new LinkedHashMap<>(before);
            Map<String, String> fresh = new LinkedHashMap<>();
            RuntimeException[] failed = new RuntimeException[1];
            boolean linked = pauser.until(() -> {
                try {
                    Map<String, String> now = CommanderLinks.peerSockets(transport, host, port, sshTimeout);
                    last.clear();
                    last.putAll(now);
                } catch (RuntimeException e) {
                    failed[0] = e;
                    return true;
                }
                fresh.clear();
                last.forEach((local, state) -> {
                    if (!before.containsKey(local)) {
                        fresh.put(local, state);
                    }
                });
                return !fresh.isEmpty();
            }, timing.linkTimeout(), timing.poll());
            if (failed[0] != null) {
                return new PinpadLink.Result(false, "cannot read the Commander's sockets to " + where + ": "
                        + failed[0].getMessage());
            }
            if (!linked) {
                return new PinpadLink.Result(false, "the EPS opened no new socket to " + where + " within "
                        + timing.linkTimeout().toSeconds() + "s of the key download (the Commander held "
                        + last.size() + (last.isEmpty() ? "" : ": " + states(last)) + ")");
            }
            step.log("PASS  the Commander's EPS reached " + where + ": new socket(s) " + states(fresh), "INFO");
            return new PinpadLink.Result(true, "");
        } finally {
            transport.close();
        }
    }

    /** {@code "<local> <state>, ..."}, sorted by local endpoint, as the Python's {@code _states}. */
    static String states(Map<String, String> sockets) {
        return new TreeMap<>(sockets).entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
