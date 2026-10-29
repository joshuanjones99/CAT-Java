package com.verifone.cat.cloudtunnel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.verifone.cat.provision.transport.ForwardHandler;
import com.verifone.cat.provision.transport.Transport;

/**
 * The cloud tunnel's serve loop: the Python {@code petrotest.cloud_tunnel.serve} with its {@code _prepare} and
 * {@code _allow_forwards}. Runs until stopped; {@link #serve} returns true for "not needed" or a clean stop.
 *
 * <p>Before the loop, one session: discover the profile's endpoints, clear a stale gateway (a dead tunnel's relay
 * accepts every connect, so the reachability probe would pass), and stop with {@code not needed} when the Commander
 * reaches its first endpoint itself (unless forced). Then plan the routes and allow their forwards in sshd.
 *
 * <p>Each loop pass is a fresh SSH session (a reloaded sshd's rule applies only to new logins): put back an sshd rule
 * an install wiped (and log in again when that reloaded sshd), keepalive, one reverse forward per route (and the
 * gateway's in gateway mode), the hosts lines and redirects, the gateway, then {@code up} (first time) or
 * {@code reconnected}. While the session is active it is polled every {@link Timing#alivePoll}; every
 * {@link Timing#profilePoll} the gateway's health is checked and re-applied, the profile is re-read (a change
 * re-plans), and hosts the Commander logged it could not reach are learned (a new one re-plans). A dropped session
 * waits {@link Timing#reconnectWait} and reconnects; a re-plan reconnects at once.
 *
 * <p>Operator lines are the Python's, each {@code cloud-tunnel: <message>} on standard output, flushed: step 4 reads
 * {@code cloud-tunnel: up} from the tunnel's log.
 *
 * <p>Deviation: the Python catches every exception in a session and retries; this catches every
 * {@link RuntimeException} (JSch's checked exceptions surface as {@code DeviceError}s from the transport).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's loop ran on the lab Commander (2026-10-03, 2026-10-15);
 * this one has run only against in-memory transports.
 */
public final class CloudTunnelServer {

    /** How often the loop checks the session, re-reads the profile, and waits after a drop. */
    public record Timing(Duration alivePoll, Duration profilePoll, Duration reconnectWait, Duration keepalive) {

        public Timing {
            Objects.requireNonNull(alivePoll, "alivePoll");
            Objects.requireNonNull(profilePoll, "profilePoll");
            Objects.requireNonNull(reconnectWait, "reconnectWait");
            Objects.requireNonNull(keepalive, "keepalive");
        }

        /**
         * The Python's: {@code ALIVE_POLL} 5 s, {@code PROFILE_POLL} 30 s, {@code RECONNECT_WAIT} 30 s, and
         * {@code set_keepalive(30)}.
         */
        public static Timing production() {
            return new Timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
                    Duration.ofSeconds(30));
        }
    }

    /** The endpoints a session serves, and their routes. */
    public record Plan(String profile, List<Endpoint> endpoints, List<Route> routes) {}

    private final Supplier<Transport> sessions;
    private final Duration timeout;
    private final Supplier<List<String>> dns;
    private final byte[] relaySource;
    private final Consumer<String> say;
    private final Timing timing;
    private final CountDownLatch stop = new CountDownLatch(1);

    /**
     * @param sessions    opens a new, connected session to the Commander on each call
     * @param timeout     the rig's SSH budget, for each command
     * @param dns         the controller's IPv4 DNS servers ({@link CloudTunnel#controllerDns})
     * @param relaySource {@code CatNetRelay.java} ({@link CloudTunnel#relaySource})
     * @param say         one operator line, without the {@code cloud-tunnel: } prefix
     */
    public CloudTunnelServer(Supplier<Transport> sessions, Duration timeout, Supplier<List<String>> dns,
            byte[] relaySource, Consumer<String> say, Timing timing) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.dns = Objects.requireNonNull(dns, "dns");
        this.relaySource = relaySource.clone();
        this.say = Objects.requireNonNull(say, "say");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /** Ends {@link #serve} at its next wait (the Python's {@code stop_event.set()}). */
    public void stop() {
        stop.countDown();
    }

    public boolean stopped() {
        return stop.getCount() == 0;
    }

    /** A bounded wait that ends early on {@link #stop}: the Python's {@code bounded_wait(..., cancel=stop_event)}. */
    private void await(Duration duration) {
        try {
            stop.await(Math.max(0, duration.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
    }

    /**
     * Runs the tunnel until {@link #stop}. Returns true for "not needed" or a clean stop; a failure before the loop
     * (no SSH, no endpoints, a refused sshd rule) throws.
     *
     * @param force   run even when the Commander reaches its first endpoint itself
     * @param gateway also carry every other outbound TCP connection and DNS through {@code CatNetRelay} (the
     *                default; rig owner, 2026-10-15)
     */
    public boolean serve(boolean force, boolean gateway) {
        List<String> servers = List.of();
        List<Endpoint> remembered;
        Plan plan;
        Transport first = sessions.get();
        try {
            CloudTunnel.Discovery found = CloudTunnel.discover(first, timeout);
            // A relay left by a tunnel that died accepts every connect, so wanUp would pass.
            CloudTunnel.removeGateway(first, timeout);
            Endpoint probe = found.endpoints().get(0);
            if (!force && CloudTunnel.wanUp(first, probe, timeout)) {
                say.accept("not needed - the Commander reaches " + probe + " itself");
                return true;
            }
            if (gateway) {
                servers = List.copyOf(dns.get());
                if (servers.isEmpty()) {
                    say.accept("gateway: this controller has no IPv4 DNS server; no DNS for the Commander");
                }
            }
            remembered = new ArrayList<>(CloudTunnel.readLearned(first, timeout));
            plan = prepare(first, timeout, remembered, gateway, say);
        } finally {
            first.close();
        }

        boolean announced = false;
        while (!stopped()) {
            boolean replan = false;
            Transport transport = null;
            try {
                transport = sessions.get();
                // A PSI install or AU rewrites sshd_config without the rule, and the Commander then refuses every
                // forward ("TCP forwarding request denied", 2026-10-12): put it back on each reconnect, then log in
                // again so the reload applies.
                if (allowForwards(transport, plan.routes(), timeout, gateway, say)) {
                    transport.close();
                    transport = null;
                    transport = sessions.get();
                }
                transport.keepalive(timing.keepalive());
                ForwardHandler handler = CloudTunnelRelay.forwardHandler(byPort(plan.routes()));
                for (Route route : plan.routes()) {
                    transport.requestReverseForward(CloudTunnel.LOOPBACK, route.forwardPort(), handler, timeout);
                }
                CloudTunnel.applyRoutes(transport, plan.routes(), timeout);
                boolean gatewayOn = false;
                if (gateway) {
                    transport.requestReverseForward(CloudTunnel.LOOPBACK, CloudTunnel.GATEWAY_FORWARD, handler,
                            timeout);
                    gatewayOn = CloudTunnel.applyGateway(transport, servers, relaySource, timeout, say);
                }
                say.accept((announced ? "reconnected" : "up") + " (" + plan.profile() + "): " + join(plan.endpoints())
                        + (gatewayOn ? "; gateway on, DNS " + (servers.isEmpty() ? "none" : String.join(", ", servers))
                                : ""));
                announced = true;
                Duration waited = Duration.ZERO;
                while (transport.isActive() && !stopped()) {
                    await(timing.alivePoll());
                    waited = waited.plus(timing.alivePoll());
                    if (waited.compareTo(timing.profilePoll()) >= 0) {
                        waited = Duration.ZERO;
                        // A network apply empties resolv.conf; a crashed relay drops every connect.
                        if (gatewayOn && !transport.run(CloudTunnel.gatewayHealthyCommand(servers), timeout).ok()) {
                            gatewayOn = CloudTunnel.applyGateway(transport, servers, relaySource, timeout, say);
                            say.accept(gatewayOn ? "gateway: re-applied" : "gateway: lost");
                        }
                        String now = CloudTunnel.profileOf(
                                CloudTunnel.read(transport, CloudTunnel.PROFILE_FILE, timeout));
                        if (!now.equals(plan.profile())) {
                            replan = true;
                            break;
                        }
                        List<Endpoint> learned = CloudTunnel.learnEndpoints(transport, plan.endpoints(), timeout);
                        remembered.addAll(learned);
                        if (!learned.isEmpty()) {
                            say.accept("learned " + join(learned));
                            replan = true;
                            break;
                        }
                    }
                }
                if (replan) {
                    CloudTunnel.removeRoutes(transport, timeout);
                    String old = plan.profile();
                    plan = prepare(transport, timeout, remembered, gateway, say);
                    say.accept("re-planned (" + old + " -> " + plan.profile() + ")");
                }
            } catch (RuntimeException e) {
                say.accept("session error: " + describe(e) + "; retrying in " + timing.reconnectWait().toSeconds()
                        + " s");
            } finally {
                if (transport != null) {
                    transport.close();
                }
            }
            if (!replan && !stopped()) {
                await(timing.reconnectWait());
            }
        }
        return true;
    }

    /**
     * The Python's {@code _prepare}: discover the endpoints (with what this tunnel remembers), plan the routes, and
     * make sure sshd allows their forwards.
     */
    public static Plan prepare(Transport transport, Duration timeout, List<Endpoint> remembered, boolean gateway,
            Consumer<String> say) {
        CloudTunnel.Discovery found = CloudTunnel.discover(transport, timeout, remembered);
        List<Route> routes = CloudTunnel.planRoutes(found.endpoints());
        allowForwards(transport, routes, timeout, gateway, say);
        return new Plan(found.profile(), List.copyOf(found.endpoints()), List.copyOf(routes));
    }

    /**
     * The Python's {@code _allow_forwards}: {@link CloudTunnel#ensureSshd} for the routes' ports (and the gateway's
     * in gateway mode), from this session's controller address.
     *
     * @return whether sshd was reloaded
     */
    public static boolean allowForwards(Transport transport, List<Route> routes, Duration timeout, boolean gateway,
            Consumer<String> say) {
        String controllerIp = transport.run("echo ${SSH_CLIENT%% *}", timeout).stdout().strip();
        List<Integer> ports = new ArrayList<>();
        for (Route route : routes) {
            ports.add(route.forwardPort());
        }
        if (gateway) {
            ports.add(CloudTunnel.GATEWAY_FORWARD);
        }
        boolean reloaded = CloudTunnel.ensureSshd(transport, controllerIp, ports, timeout);
        if (reloaded) {
            say.accept("sshd: remote forwarding allowed from the controller");
        }
        return reloaded;
    }

    private static Map<Integer, Endpoint> byPort(List<Route> routes) {
        Map<Integer, Endpoint> map = new LinkedHashMap<>();
        for (Route route : routes) {
            map.put(route.forwardPort(), route.endpoint());
        }
        return map;
    }

    private static String join(List<Endpoint> endpoints) {
        return endpoints.stream().map(Endpoint::toString).collect(Collectors.joining(", "));
    }

    /** The exception's own message (the Python prints {@code str(exc)}), else its type. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
