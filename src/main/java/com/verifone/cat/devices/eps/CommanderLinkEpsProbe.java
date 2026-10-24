package com.verifone.cat.devices.eps;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.preflight.Surfaces;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * {@code eps_reachable}'s probe (DESIGN.md 6.2): does the Commander hold a live socket to its payment host.
 * Ported from the Python CAT's {@code petrotest.devices.eps.CommanderLinkEpsProbe}.
 *
 * <p><b>The direction, corrected 2026-09-21 (the Python's HANDOFF.md 1.73).</b> {@code eps_port} (41107) is
 * the Buypass UMF port, and the listener on it is the payment-host simulator on the <i>controller</i>. The
 * Commander's EPS is the client: it holds {@code <commander>:<ephemeral> -> <controller>:41107 ESTABLISHED}
 * and nothing on it listens on 41107. So this asks the Commander, over SSH, for its {@code netstat} and
 * nothing else, which is safe inside {@code verify()}.
 *
 * <p><b>Cannot tell is not FAIL</b> (hard rule 5). A rig with no SSH key raises {@link DeviceUnavailable};
 * an SSH failure or a failing {@code netstat} raises too. Preflight records each as ERROR.
 *
 * <p>The transport is opened per probe and always closed: an SSH session held across a Commander restart
 * would turn the next probe into a stale-channel error that looks like a rig fault.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Run only against {@code InMemoryTransport}.
 */
public final class CommanderLinkEpsProbe implements Surfaces.EpsProbe {

    private static final Logger LOG = LoggerFactory.getLogger(CommanderLinkEpsProbe.class);

    /** The surface named in {@link DeviceUnavailable}. */
    static final String SURFACE = "eps_reachable's Commander socket probe";

    private final List<CommanderLink> links;
    private final Supplier<Transport> transportFactory;
    private final String unavailableDetail;

    /**
     * @param links             the links the Commander must hold
     * @param transportFactory  opens a transport to the Commander; null when the rig has no way to ask
     * @param unavailableDetail why {@code transportFactory} is null, for the ERROR line
     */
    public CommanderLinkEpsProbe(List<CommanderLink> links, Supplier<Transport> transportFactory,
            String unavailableDetail) {
        this.links = List.copyOf(links);
        this.transportFactory = transportFactory;
        this.unavailableDetail = unavailableDetail == null ? "" : unavailableDetail;
    }

    /** The rig's payment links, asked over an injected transport (a test's in-memory one). */
    public static CommanderLinkEpsProbe fromRig(Rig rig, Supplier<Transport> transportFactory) {
        return new CommanderLinkEpsProbe(CommanderLinks.paymentLinks(rig), transportFactory, "");
    }

    /**
     * The rig's payment links, asked over SSH with the rig's key. With none, the probe is built unavailable
     * and its error names the variable it wanted.
     */
    public static CommanderLinkEpsProbe fromRig(Rig rig, Map<String, String> environ) {
        CommanderSsh.Opener opener = CommanderSsh.opener(rig, environ);
        if (!opener.available()) {
            return new CommanderLinkEpsProbe(CommanderLinks.paymentLinks(rig), null,
                    "the check reads the Commander's netstat over SSH and rig '" + rig.name()
                            + "' has no SSH key to do it with (" + opener.missingKey() + ")");
        }
        return new CommanderLinkEpsProbe(CommanderLinks.paymentLinks(rig), opener.open(), "");
    }

    public List<CommanderLink> links() {
        return links;
    }

    @Override
    public String describe() {
        return links.stream().map(CommanderLink::toString).collect(Collectors.joining(", "));
    }

    /**
     * The wanted links the Commander is not holding ESTABLISHED. Raises rather than answering "all of them"
     * when it cannot look.
     */
    public List<CommanderLink> missing(Duration timeout) {
        if (transportFactory == null) {
            throw new DeviceUnavailable(SURFACE, "DESIGN.md 6.2: " + unavailableDetail);
        }
        LinkPlan plan;
        Transport transport = transportFactory.get();
        try {
            if (transport instanceof JschTransport ssh) {
                ssh.connect(timeout);
            }
            plan = CommanderLinks.plan(links, CommanderLinks.establishedEndpoints(transport, timeout));
        } finally {
            transport.close();
        }
        for (CommanderLink link : plan.missing()) {
            LOG.info("the Commander holds no ESTABLISHED socket for {}", link);
        }
        return plan.missing();
    }

    /** True when the Commander holds every payment link ESTABLISHED. */
    @Override
    public boolean reachable(Duration timeout) {
        return missing(timeout).isEmpty();
    }

    @Override
    public String toString() {
        return "CommanderLinkEpsProbe(" + describe() + ")";
    }
}
