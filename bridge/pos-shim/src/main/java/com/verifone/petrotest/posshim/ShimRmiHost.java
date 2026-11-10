package com.verifone.petrotest.posshim;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.isd.pcapps.automation.target.pos.topaz.app.rmi.LexTokenRMIServiceImpl;
import com.verifone.isd.pcapps.automation.target.pos.topaz.app.rmi.TopazRMIService;
import com.verifone.isd.pcapps.automation.target.pos.topaz.ent.TokenInfoHolder;
import com.verifone.isd.topaz.sys.tokenRMI.pres.IRemoteLexTokenRMIService;

/**
 * The RMI half of the shim: hosts the service the POS agent connects <em>into</em>.
 *
 * <h2>Which way round this runs</h2>
 *
 * This is the part every previous attempt at the bridge had backwards, so it is
 * worth being explicit. The retained agent is not a server offering
 * {@code isPrinterConnected()} for us to call. It is a <strong>client</strong>:
 * {@code POSStatusRMIService} inside the Topaz GUI JVM reads
 * {@code tokenRMI.prop}, connects out to {@code token.host:token.port}, and asks
 * for the remote objects it should push into. The registry and the bound
 * {@code tokenRMI.app} object live on the <em>other</em> end — in PATS, inside
 * the PATS application on the controller PC.
 *
 * <p>So the bridge cannot be a thing that dials into the POS. It has to be the
 * thing the POS dials, which means hosting a registry, binding the service name
 * the agent looks up, and handing out the two remote objects it asks for. That
 * is exactly what {@link TopazRMIService} already does, so this class starts it
 * rather than reimplementing it — §5.2's "retained, not rewritten" applies to
 * this side of the wire too.
 *
 * <h2>Where it runs</h2>
 *
 * On the register, alongside the POS, with {@code tokenRMI.prop} pointing the
 * agent at {@code 127.0.0.1}. PATS instead ran this on the controller and shipped
 * a {@code tokenRMI.prop} carrying {@code token.host=<lab PC IP>} — a
 * hardcoded lab PC address, which is precisely the coupling §4.2 rule 5 exists to
 * remove and which breaks the moment an engineer's machine is not that IP.
 * Co-locating costs nothing (the register has its own JRE), keeps the RMI
 * callbacks on loopback instead of across the bench network, and means
 * `provision` pushes a {@code tokenRMI.prop} that is identical on every rig.
 * It also matches where §6.1's table already places the artifact.
 */
final class ShimRmiHost {

    private final Config config;
    private final TopazRMIService service;
    private final long startedAtMillis;

    ShimRmiHost(Config config) {
        this.config = config;
        this.service = TopazRMIService.getInstance();
        this.startedAtMillis = System.currentTimeMillis();
    }

    /**
     * Creates the registry, exports the service, and seeds the client holder.
     *
     * <p>Blocking and slow by design — {@code startTopazRMIService} sleeps 5s
     * after binding in the retained code. The HTTP listener is only started
     * afterwards (see {@link PosShim}) so that a caller who gets a 200 from
     * {@code /health} knows the RMI side is genuinely up, rather than racing it.
     */
    void start() {
        service.startTopazRMIService(config.rmiPort(), config.rmiClientPort(), config.serviceName());
        for (String client : config.seedClients()) {
            ensureHolder(client);
        }
    }

    /**
     * Makes sure a {@link TokenInfoHolder} exists for {@code client}.
     *
     * <p>This is load-bearing, and the reason is a trap in the retained code:
     * {@code POSStatusMonitorImpl.update*Event} starts with
     * {@code if (null != tokenInfoHolder)} and <em>silently drops the event</em>
     * otherwise. The holder is only created inside
     * {@code TokenERP.getTokenERPInstance()} — the LexToken path — so a POS whose
     * observer connected but whose keyboard strategy has not yet will push panel
     * updates into nothing, and the shim would report an empty screen for a POS
     * that is talking to it perfectly well.
     *
     * <p>Seeding it ourselves closes that window. {@code setTopazTokenInfoHandler}
     * is public and idempotent from our side as long as we never overwrite a
     * holder that already exists — overwriting would discard the state the POS
     * has already pushed, which is the same bug wearing a different hat.
     */
    void ensureHolder(String client) {
        if (client == null || client.isEmpty()) {
            return;
        }
        if (service.getTopazTokenInfoHandler(client) == null) {
            service.setTopazTokenInfoHandler(client, new TokenInfoHolder(client));
        }
    }

    /**
     * Every client host the shim knows about.
     *
     * <p>Two sources, unioned: holders (seeded by us or created by the LexToken
     * path) and the exported ERP services (created when a POS asks for its token
     * service). A name in the second but not the first would mean a POS
     * connected for keystrokes without a holder — the trap above — so the union
     * is also how {@code /health} can show that state rather than hide it.
     */
    List<String> clients() {
        Set<String> names = new LinkedHashSet<>();
        for (String client : config.seedClients()) {
            if (service.getTopazTokenInfoHandler(client) != null) {
                names.add(client);
            }
        }
        names.addAll(service.getERPServices().keySet());
        return new ArrayList<>(names);
    }

    /** Client hosts that have asked for a token service — i.e. can be driven. */
    List<String> drivableClients() {
        return new ArrayList<>(service.getERPServices().keySet());
    }

    TokenInfoHolder holder(String client) {
        return service.getTopazTokenInfoHandler(client);
    }

    /**
     * The snapshot for {@code client}, refusing to answer without pushed state.
     *
     * <p>Every derived predicate on this surface is read off fields that start
     * null, so a POS that has never pushed anything looks exactly like a POS
     * sitting on a clean idle screen: no overlay, no totals, {@code idle=true}.
     * That is the one wrong answer this shim must never give — it would take a
     * register whose observer never loaded and report it as ready to test.
     *
     * <p>So the predicate routes go through here and get a 503 in that window,
     * which the client turns into ERROR rather than PASS. {@code GET /pos/screen}
     * deliberately does not: it is the diagnostic route, and answering it with
     * {@code state_present: false} is how somebody sees the difference.
     */
    Snapshot requireSnapshot(String client) {
        TokenInfoHolder holder = holder(client);
        if (holder == null) {
            throw new ShimException(503, "no-state",
                    "No state holder for POS '" + client + "'; it has not connected.");
        }
        Snapshot snapshot = new Snapshot(holder);
        if (!snapshot.statePresent()) {
            throw new ShimException(503, "no-state",
                    "POS '" + client + "' is registered but has pushed no panel state, so "
                            + "nothing can be said about its screen. An answer here would be "
                            + "indistinguishable from an idle POS. "
                            + diagnoseSilentObserver(client)
                            + " GET /pos/screen reports the raw state.");
        }
        return snapshot;
    }

    /**
     * Resolves the client a request is about.
     *
     * <p>A rig has one register today and §6.1 pushes to every register a rig
     * has, so this must not quietly assume one. With an explicit {@code ?client=}
     * it is used as given; with exactly one known client that one is used; with
     * several and no parameter the caller gets an error naming the candidates,
     * because picking the first of two registers would produce a status report
     * about the wrong POS.
     */
    String resolveClient(String requested) {
        if (requested != null && !requested.isEmpty()) {
            ensureHolder(requested);
            return requested;
        }
        List<String> known = clients();
        if (known.size() == 1) {
            return known.get(0);
        }
        if (known.isEmpty()) {
            throw new ShimException(503, "no-clients",
                    "No POS has connected to the shim yet. Check that the register's "
                            + "tokenRMI.prop names this host and port, and that "
                            + "private_system.prop sets automation.observer.class.");
        }
        throw new ShimException(400, "ambiguous-client",
                "Several POS clients are connected " + known + "; pass ?client=<host>.");
    }

    /**
     * The token service used to inject keystrokes into a POS.
     *
     * <p>The map is typed as the remote interface, but {@code sendToken} is only
     * on the implementation — this shim and the POS are the same JVM's worth of
     * classes on this side of the wire, so the cast is sound. It is checked
     * rather than assumed so that a future second implementation shows up as a
     * clear error instead of a {@code ClassCastException} in a stack trace.
     */
    LexTokenRMIServiceImpl tokenService(String client) {
        Map<String, IRemoteLexTokenRMIService> services = service.getERPServices();
        IRemoteLexTokenRMIService found = services.get(client);
        if (found == null) {
            throw new ShimException(503, "client-not-drivable",
                    "POS '" + client + "' has not registered a token service, so no key can be "
                            + "sent to it. The observer may be connected while the keyboard Lex "
                            + "strategy is not; known drivable clients: " + drivableClients() + ".");
        }
        if (!(found instanceof LexTokenRMIServiceImpl impl)) {
            throw new ShimException(500, "unexpected-token-service",
                    "Token service for '" + client + "' is a "
                            + found.getClass().getName() + ", which this shim cannot drive.");
        }
        return impl;
    }

    /**
     * Names the likely cause when a POS is connected but silent.
     *
     * <h2>The failure this exists to name</h2>
     *
     * Observed on the lab rig, 2026-08-03: after restarting <em>only</em> the shim,
     * the POS re-registered its token service within seconds — keys could be
     * pressed and were acknowledged in ~230ms — but no panel state ever arrived
     * again, and no error appeared anywhere the operator would look.
     *
     * <p>The cause is in the retained agent, and it is not fixable from this side.
     * {@code POSStatusRMIService.remoteToken} is a <strong>static</strong> field
     * holding a stub to the {@code POSStatusMonitorImpl} that <em>this shim's
     * previous JVM</em> exported, and {@code ObserverImpl.update} re-initialises
     * only when it is null:
     *
     * <pre>
     *     if (remoteToken == null) { initRMIService(); }
     *     if (remoteToken != null) { remoteToken.updateOverlayMsgEvent(...); }
     * </pre>
     *
     * A stub to a dead JVM is not null. So every push throws {@code RemoteException}
     * into the observer's own {@code catch}, which logs and returns — permanently,
     * until the POS process itself restarts and the static is reinitialised.
     *
     * <p>The drive path recovers because it goes through {@code RMIConnector}'s
     * reset listener, which reconnects. The read path has no such listener. The
     * asymmetry is why "drivable but silent" is a specific, diagnosable state
     * rather than a mystery, and why saying so here is worth more than another
     * generic 503: without it, the operator's reasonable next move is to go and
     * re-check {@code private_system.prop}, which is not the problem.
     */
    String diagnoseSilentObserver(String client) {
        if (!drivableClients().contains(client)) {
            return "Check that private_system.prop on the register sets "
                    + "automation.observer.class, that automation-target-pos-topaz.jar is in "
                    + "/home/topaz/lib, and that the POS has restarted since.";
        }
        return "NOTE: this POS is drivable, so it is connected and its keyboard listener is "
                + "live — only the status push is silent. That is the signature of a shim "
                + "restart: the agent's POSStatusRMIService.remoteToken is a static stub to "
                + "the previous shim JVM, and ObserverImpl only re-initialises it when null, "
                + "so a dead stub is never replaced. The POS must be restarted "
                + "(/etc/init.d/apps restart) to restore the status surface; restarting the "
                + "shim again will not.";
    }

    long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAtMillis) / 1000L;
    }

    /** The host name the POS agent will identify itself as, when co-located. */
    static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // The agent derives its client name the same way and would fail
            // first, so this is unreachable in practice; returning a marker
            // beats a null that spreads into the client map.
            return "unknown-host";
        }
    }
}
