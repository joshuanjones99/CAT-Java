package com.verifone.cat.cloudtunnel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * {@code cloud-tunnel}: endpoint discovery, route planning and the Commander-side commands. A port of the Python
 * {@code tests/unit/test_cloud_tunnel.py}, for what this port has (no serve loop, no step-4 adoption).
 *
 * <p>Fake-backed: evidence about this repository, not about a Commander. Every Commander-side command is checked as
 * a string handed to a fake transport; nothing here runs a shell or touches this machine's hosts file, routes, DNS,
 * NAT or firewall. The relay tests use loopback sockets only. The Python's real-bash check of the {@code --stop}
 * pipeline is not ported, because it runs {@code sed -i} on the hosts file; its string half is
 * {@link #stopsForwardRuleGrepTakesItsPatternWithDashE}.
 */
class CloudTunnelTest {

    /** RFC 5737 documentation address standing in for DEV's literal bridge IP. */
    static final String DEV_IP = "192.0.2.10";
    /** RFC 5737 documentation addresses standing in for the controller's DNS servers. */
    static final List<String> DNS = List.of("203.0.113.20", "203.0.113.220");
    static final Duration T = Duration.ofSeconds(5);

    static final String BRIDGE = "\napp.profile=prod\ndev.tlsEnabled=false\ndev.port=1883\nqat.port=8883\n"
            + "cst.port=8883\ndev.host=" + DEV_IP + "\nqat.host=mqtt-qat.example.com\ncst.host=mqtt.example.com\n";
    static final String QAT_ENDPOINTS = "\npems.url=https://gsc-qat.example.com/ms/pems/v1\n"
            + "pems.otp=https://gsc-qat.example.com/ms/pems/v1/otp/send\n"
            + "report.url=https://reports-qat.example.com:8443/ms/prms/v1/reports/sync\n"
            + "bridge.local=http://localhost:8089/api\n";

    /** A /24 mask, built so no netmask literal sits in the source. */
    static final String MASK24 = String.join(".", "255", "255", "255", "0");
    static final String ROUTE_N = "Kernel IP routing table\n"
            + "Destination     Gateway         Genmask         Flags Metric Ref    Use Iface\n"
            + "0.0.0.0         198.51.100.31   0.0.0.0         UG    0      0        0 eth1\n"
            + "198.51.100.0    0.0.0.0         " + MASK24 + "   U     0      0        0 eth1\n"
            + "192.0.2.0       0.0.0.0         " + MASK24 + "   U     0      0        0 eth0\n";

    /** Answers {@code cat}, {@code route -n}, {@code sshd -t}, the probe and the relay start; records everything. */
    static final class FakeCommander implements Transport {
        final Map<String, String> files = new HashMap<>();
        final Map<String, byte[]> blobs = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        String routeN = "";
        boolean sshdOk = true;
        boolean reachesCloud;
        boolean relayStarts = true;
        boolean hooked = true;
        String unreached = "";
        String controller = "198.51.100.5";

        static FakeCommander qat() {
            FakeCommander c = new FakeCommander();
            c.files.put(CloudTunnel.PROFILE_FILE, "profile=qat\n");
            c.files.put(CloudTunnel.BRIDGE_PARAMS, BRIDGE);
            c.files.put("/home/coresvcs/config/cloudEndpoints_qat.prop", QAT_ENDPOINTS);
            c.files.put(CloudTunnel.SSHD_CONFIG, "AllowTcpForwarding no\n");
            return c;
        }

        @Override
        public String host() {
            return "fake-commander";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            commands.add(command);
            boolean ok = true;
            String out = "";
            if (command.startsWith("cat ")) {
                String path = command.substring(4).split("\\s+")[0];
                ok = files.containsKey(path);
                out = files.getOrDefault(path, "");
            } else if (command.startsWith("/sbin/route -n")) {
                out = routeN;
            } else if (command.startsWith("/usr/sbin/sshd -t")) {
                ok = sshdOk;
            } else if (command.startsWith("grep -o")) {
                out = unreached;
            } else if (command.contains("/dev/tcp/")) {
                ok = reachesCloud;
            } else if (command.startsWith("echo ${SSH_CLIENT")) {
                out = controller + "\n";
            } else if (command.contains("nohup")) {
                ok = relayStarts;
            } else if (command.contains("-C OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN) && !command.contains("||")
                    && !command.contains("-F ")) {
                ok = hooked;
            }
            return new CommandResult(ok ? 0 : 1, out, ok ? "" : "bad config");
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            blobs.put(remotePath, content.clone());
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            return Optional.ofNullable(blobs.get(remotePath));
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {}

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return files.containsKey(remotePath) || blobs.containsKey(remotePath);
        }

        @Override
        public void close() {}

        boolean any(String text) {
            return commands.stream().anyMatch(c -> c.contains(text));
        }

        String first(String text) {
            return commands.stream().filter(c -> c.contains(text)).findFirst().orElseThrow();
        }
    }

    // --- discovery and planning --------------------------------------------------------------------------------

    @Test
    void profileDefaultsToProd() {
        assertEquals("qat", CloudTunnel.profileOf("profile=QAT\n"));
        assertEquals("prod", CloudTunnel.profileOf(""));
    }

    @Test
    void qatEndpointsBridgeFirstThenEachUrlAuthorityOnce() {
        assertEquals(List.of(new Endpoint("mqtt-qat.example.com", 8883), new Endpoint("gsc-qat.example.com", 443),
                new Endpoint("reports-qat.example.com", 8443)),
                CloudTunnel.endpointsFor("qat", BRIDGE, List.of(QAT_ENDPOINTS)));
    }

    @Test
    void devBridgeIsAnIpEndpoint() {
        List<Endpoint> found = CloudTunnel.endpointsFor("dev", BRIDGE, List.of());
        assertEquals(List.of(new Endpoint(DEV_IP, 1883)), found);
        assertTrue(found.get(0).isIp());
    }

    @Test
    void namesGetTheirOwnLoopbackAliasAndIpsKeepTheirAddress() {
        List<Route> routes = CloudTunnel.planRoutes(List.of(new Endpoint("a.example.com", 443),
                new Endpoint("b.example.com", 443), new Endpoint(DEV_IP, 1883)));
        assertEquals(List.of(20000, 20001, 20002), routes.stream().map(Route::forwardPort).toList());
        String a = routes.get(0).localIp();
        String b = routes.get(1).localIp();
        // The Python's 127.0.0.1 + 16, then + 1 per name.
        assertEquals("127.0.0.17", a);
        assertEquals("127.0.0.18", b);
        assertEquals(DEV_IP, routes.get(2).localIp());
        // Only names go into /etc/hosts; the IP is reached through a host route instead.
        assertEquals(List.of(a + " a.example.com  " + CloudTunnel.TAG, b + " b.example.com  " + CloudTunnel.TAG),
                CloudTunnel.hostsLines(routes));
    }

    @Test
    void aRepeatedNameSharesItsAlias() {
        List<Route> routes = CloudTunnel.planRoutes(List.of(new Endpoint("a.example.com", 443),
                new Endpoint("a.example.com", 8443)));
        assertEquals(routes.get(0).localIp(), routes.get(1).localIp());
        assertEquals(1, CloudTunnel.hostsLines(routes).size());
    }

    @Test
    void tooManyEndpointsAreRefused() {
        List<Endpoint> many = new ArrayList<>();
        for (int i = 0; i <= CloudTunnel.MAX_ROUTES; i++) {
            many.add(new Endpoint("h" + i + ".example.com", 443));
        }
        assertThrows(DeviceError.class, () -> CloudTunnel.planRoutes(many));
    }

    @Test
    void escapedPropertyUrlsAreFound() {
        String text = "forgerockURL=https\\://login-qat.example.com\ncloudURL=https\\://gsc-qat.example.com/oidc";
        assertEquals(List.of(new Endpoint("mqtt-qat.example.com", 8883), new Endpoint("login-qat.example.com", 443),
                new Endpoint("gsc-qat.example.com", 443)), CloudTunnel.endpointsFor("qat", BRIDGE, List.of(text)));
    }

    @Test
    void prodReadsTheUnsuffixedEndpointFiles() {
        assertEquals(List.of("/home/coresvcs/config/cloudEndpoints.prop", "/home/coresvcs/config/cloud_connect.prop"),
                CloudTunnel.endpointFiles("prod"));
        assertEquals("/home/coresvcs/config/cloudEndpoints_qat.prop", CloudTunnel.endpointFiles("qat").get(0));
    }

    @Test
    void aHostTheCommanderCouldNotReachIsLearnedOnce() {
        FakeCommander c = FakeCommander.qat();
        List<Endpoint> known = List.of(new Endpoint("login-qat.example.com", 443));
        c.unreached = "Couldn't connect to https://uis.login-qat.example.com\n"
                + "Couldn't connect to https://login-qat.example.com\n";
        assertEquals(List.of(new Endpoint("uis.login-qat.example.com", 443)), CloudTunnel.learnEndpoints(c, known, T));
        String saved = c.commands.stream().filter(x -> x.contains(CloudTunnel.LEARNED_FILE) && x.contains(">>"))
                .findFirst().orElseThrow();
        assertTrue(saved.contains("https://uis.login-qat.example.com:443"), saved);
        // discover reads the saved file, so a re-plan relays it.
        c.files.put(CloudTunnel.LEARNED_FILE, "https://uis.login-qat.example.com:443\n");
        List<Endpoint> endpoints = new ArrayList<>(CloudTunnel.discover(c, T).endpoints());
        assertTrue(endpoints.contains(new Endpoint("uis.login-qat.example.com", 443)));
        endpoints.addAll(known);
        assertEquals(List.of(), CloudTunnel.learnEndpoints(c, endpoints, T));
    }

    @Test
    void aRememberedEndpointSurvivesAWipedLearnedFile() {
        FakeCommander c = FakeCommander.qat();
        List<Endpoint> remembered = List.of(new Endpoint("uis.login-qat.example.com", 443));
        List<Endpoint> endpoints = CloudTunnel.discover(c, T, remembered).endpoints();
        assertEquals(remembered.get(0), endpoints.get(endpoints.size() - 1));
        List<Endpoint> more = new ArrayList<>(remembered);
        more.add(endpoints.get(0));
        List<Endpoint> again = CloudTunnel.discover(c, T, more).endpoints();
        assertEquals(1, again.stream().filter(endpoints.get(0)::equals).count());
    }

    @Test
    void aCommanderWithNoEndpointsIsAnError() {
        FakeCommander c = new FakeCommander();
        DeviceError e = assertThrows(DeviceError.class, () -> CloudTunnel.discover(c, T));
        assertTrue(e.getMessage().contains("'prod'"));
    }

    // --- sshd and routes ---------------------------------------------------------------------------------------

    @Test
    void sshdRuleWrittenInASelinuxWindowValidatedAndReloaded() {
        FakeCommander c = FakeCommander.qat();
        assertTrue(CloudTunnel.ensureSshd(c, "198.51.100.5", List.of(20000, 20001), T));
        String write = c.first(">> " + CloudTunnel.SSHD_CONFIG);
        assertTrue(write.contains("setenforce 0") && write.contains("setenforce 1"));
        assertTrue(write.contains("PermitListen 127.0.0.1:20000 127.0.0.1:20001"));
        assertTrue(c.commands.stream().anyMatch(x -> x.startsWith("/usr/sbin/sshd -t")));
        assertTrue(c.commands.stream().anyMatch(x -> x.startsWith("kill -HUP")));
    }

    @Test
    void sshdUntouchedWhenTheRuleIsAlreadyThere() {
        FakeCommander c = FakeCommander.qat();
        c.files.merge(CloudTunnel.SSHD_CONFIG,
                String.join("\n", CloudTunnel.sshdBlock("198.51.100.5", List.of(20000))) + "\n", String::concat);
        assertFalse(CloudTunnel.ensureSshd(c, "198.51.100.5", List.of(20000), T));
        assertFalse(c.commands.stream().anyMatch(x -> x.startsWith("kill -HUP")));
    }

    @Test
    void aRuleAnInstallWipedIsPutBack() {
        FakeCommander c = FakeCommander.qat();
        assertTrue(CloudTunnel.ensureSshd(c, "198.51.100.5", List.of(20000), T));
        c.files.put(CloudTunnel.SSHD_CONFIG, "AllowTcpForwarding no\n"); // a PSI install
        assertTrue(CloudTunnel.ensureSshd(c, "198.51.100.5", List.of(20000), T));
    }

    @Test
    void aRejectedSshdRuleIsRestoredAndSshdNotReloaded() {
        FakeCommander c = FakeCommander.qat();
        c.sshdOk = false;
        DeviceError e = assertThrows(DeviceError.class,
                () -> CloudTunnel.ensureSshd(c, "198.51.100.5", List.of(20000), T));
        assertTrue(e.getMessage().contains("restored"));
        assertTrue(c.commands.stream().anyMatch(x -> x.contains("sshd_config.orig")
                && x.contains(" " + CloudTunnel.SSHD_CONFIG) && x.contains("cp -p")));
        assertFalse(c.commands.stream().anyMatch(x -> x.startsWith("kill -HUP")));
    }

    @Test
    void applyRoutesHostsRouteAndRedirectPerEndpoint() {
        FakeCommander c = FakeCommander.qat();
        List<Route> routes = CloudTunnel.planRoutes(List.of(new Endpoint("gsc-qat.example.com", 443),
                new Endpoint(DEV_IP, 1883)));
        CloudTunnel.applyRoutes(c, routes, T);
        String hosts = c.first(">> " + CloudTunnel.HOSTS);
        assertTrue(hosts.contains("gsc-qat.example.com") && !hosts.contains(DEV_IP) && hosts.contains("setenforce 0"));
        assertTrue(c.commands.contains("/sbin/route add -host " + DEV_IP + " dev lo 2>/dev/null"));
        List<String> nat = c.commands.stream().filter(x -> x.contains("iptables -t nat -A OUTPUT")).toList();
        assertEquals(2, nat.size());
        assertTrue(nat.stream().allMatch(x -> x.contains("setenforce 0")));
        assertTrue(nat.stream().anyMatch(x -> x.contains("-d " + DEV_IP + " -p tcp --dport 1883")
                && x.contains(":20001")));
        assertTrue(c.any("printf '%s\\n' " + DEV_IP + " > " + CloudTunnel.ROUTES_FILE));
    }

    @Test
    void stopsForwardRuleGrepTakesItsPatternWithDashE() {
        FakeCommander c = FakeCommander.qat();
        CloudTunnel.removeRoutes(c, T);
        String rules = c.first("iptables -t nat -S OUTPUT");
        // -e: a pattern starting "--" is otherwise read as an option, and nothing matched (2026-10-15).
        assertTrue(rules.contains("grep -E -e '--to-destination 127\\.0\\.0\\.1:200[0-9]{2}$'"), rules);
        assertTrue(rules.contains("sed 's/^-A /-D /'"));
    }

    // --- the command line --------------------------------------------------------------------------------------

    private static String[] command(FakeCommander c, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CloudTunnelCommand((rig, env) -> c).run(argv, Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new String[] {String.valueOf(code), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void stopRemovesHostsLinesRedirectsHostRoutesAndTheGateway() {
        FakeCommander c = FakeCommander.qat();
        String[] result = command(c, "--rig", "lab", "--stop");
        assertEquals("0", result[0]);
        String joined = String.join("\n", c.commands);
        assertTrue(joined.contains("sed -i '/" + CloudTunnel.TAG + "/d' " + CloudTunnel.HOSTS));
        assertTrue(joined.contains("iptables -t nat -S OUTPUT") && joined.contains("route del -host"));
        assertTrue(joined.contains("iptables -t nat -D OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN));
    }

    @Test
    void aCommanderThatReachesItsBridgeNeedsNoTunnel() {
        FakeCommander c = FakeCommander.qat();
        c.routeN = ROUTE_N;
        c.reachesCloud = true;
        String[] result = command(c, "--rig", "lab");
        assertEquals("0", result[0], result[2]);
        assertTrue(result[1].contains("not needed"));
        assertTrue(c.any("/dev/tcp/mqtt-qat.example.com/8883"));
        // Only the clean-up of a stale gateway (-D/-F/-X) touches iptables: nothing is added.
        for (String added : List.of("iptables -t nat -A", "iptables -t nat -N", "iptables -t nat -C")) {
            assertFalse(c.any(added), added);
        }
        assertFalse(c.any(">> " + CloudTunnel.SSHD_CONFIG));
        assertTrue(c.any("-D OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN));
    }

    @Test
    void aRouteThatReachesNothingStillNeedsTheTunnelWhichThisPortCannotServe() {
        for (boolean route : new boolean[] {false, true}) {
            FakeCommander c = FakeCommander.qat();
            c.routeN = route ? ROUTE_N : "";
            String[] result = command(c, "--rig", "lab");
            assertEquals("1", result[0]);
            assertTrue(result[2].contains("not ported"), result[2]);
            assertEquals(route, c.any("/dev/tcp/"));
            assertFalse(c.any(">> " + CloudTunnel.HOSTS));
        }
    }

    @Test
    void usageErrorsExitTwo() {
        FakeCommander c = FakeCommander.qat();
        assertEquals("2", command(c)[0]);
        assertEquals("2", command(c, "--rig")[0]);
        assertEquals("2", command(c, "--rig", "lab", "--what")[0]);
        assertEquals(List.of(), c.commands);
    }

    // --- gateway mode ------------------------------------------------------------------------------------------

    @Test
    void theCommandersOwnSubnetsAreTheRoutesWithNoGateway() {
        assertEquals(List.of("198.51.100.0/24", "192.0.2.0/24"), CloudTunnel.connectedSubnets(ROUTE_N));
    }

    @Test
    void theGatewayChainReturnsLocalTrafficThenRedirectsTheRest() {
        String rules = CloudTunnel.gatewayRules(List.of("198.51.100.0/24", "192.0.2.0/24"), List.of());
        String chain = "iptables -t nat -A " + CloudTunnel.GATEWAY_CHAIN;
        List<String> order = List.of(chain + " -o lo -j RETURN", chain + " -d 198.51.100.0/24 -j RETURN",
                chain + " -d 192.0.2.0/24 -j RETURN",
                chain + " -p tcp -j REDIRECT --to-ports " + CloudTunnel.GATEWAY_LISTEN);
        int[] at = order.stream().mapToInt(rules::indexOf).toArray();
        int[] sorted = at.clone();
        Arrays.sort(sorted);
        assertTrue(at[0] >= 0);
        assertArrayEquals(sorted, at);
        // Flushed first and hooked once, so a re-apply never stacks rules or jumps.
        assertTrue(rules.indexOf("-F " + CloudTunnel.GATEWAY_CHAIN) < at[0]);
        assertTrue(rules.contains("iptables -t nat -C OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN + " 2>/dev/null ||"));
    }

    @Test
    void dnsGoesOverTcpBecauseTheRelayCarriesTcpOnly() {
        assertEquals(List.of("nameserver 203.0.113.20", "nameserver 203.0.113.220",
                "options use-vc  " + CloudTunnel.TAG), CloudTunnel.resolvText(DNS).lines().toList());
    }

    static final byte[] RELAY_SOURCE = "public class CatNetRelay {\r\n}\r\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theGatewayPushesTheRelayRedirectsAndWritesDns() {
        FakeCommander c = new FakeCommander();
        c.routeN = ROUTE_N;
        c.controller = "198.51.100.160";
        List<String> said = new ArrayList<>();
        assertTrue(CloudTunnel.applyGateway(c, DNS, RELAY_SOURCE, T, said::add));
        String pushed = new String(c.blobs.get(CloudTunnel.RELAY_REMOTE), StandardCharsets.US_ASCII);
        assertTrue(pushed.contains("class CatNetRelay") && !pushed.contains("\r\n"));
        String start = c.first("nohup");
        assertTrue(start.contains(CloudTunnel.COMMANDER_JAVA + " -Djava.net.preferIPv4Stack=true "
                + CloudTunnel.RELAY_REMOTE + " " + CloudTunnel.GATEWAY_LISTEN + " " + CloudTunnel.GATEWAY_FORWARD));
        String rules = c.first("REDIRECT");
        assertTrue(rules.contains("-d 198.51.100.0/24 -j RETURN") && rules.contains("setenforce"));
        // The controller's own 443 (TruAge's /register) goes through the relay, ahead of its subnet's RETURN.
        String carried = "-d 198.51.100.160 -p tcp --dport 443 -j REDIRECT --to-ports " + CloudTunnel.GATEWAY_LISTEN;
        assertTrue(rules.indexOf(carried) >= 0 && rules.indexOf(carried) < rules.indexOf("-d 198.51.100.0/24 -j RETURN"));
        String resolv = c.first("> " + CloudTunnel.RESOLV);
        // printf's own escape: the newline reaches the shell as a backslash and an n.
        assertTrue(resolv.contains("nameserver 203.0.113.20\\n") && resolv.contains(CloudTunnel.RESOLV_BACKUP));
        // The Commander has a default route: none is added.
        assertFalse(c.any("route add default"));
    }

    @Test
    void aCommanderWithNoDefaultRouteGetsOneViaLo() {
        FakeCommander c = new FakeCommander();
        assertTrue(CloudTunnel.applyGateway(c, List.of(), RELAY_SOURCE, T, line -> {}));
        assertTrue(c.any("/sbin/route add default dev lo && echo lo > " + CloudTunnel.DEFAULT_ROUTE_FILE));
        // No DNS servers: resolv.conf is left alone.
        assertFalse(c.any("nameserver"));
    }

    @Test
    void aRelayThatNeverListensLeavesOnlyTheEndpointRoutes() {
        FakeCommander c = new FakeCommander();
        c.routeN = ROUTE_N;
        c.relayStarts = false;
        c.files.put(CloudTunnel.RELAY_LOG, "relay log line\n");
        List<String> said = new ArrayList<>();
        assertFalse(CloudTunnel.applyGateway(c, DNS, RELAY_SOURCE, T, said::add));
        assertFalse(c.any("REDIRECT") || c.any("> " + CloudTunnel.RESOLV));
        assertTrue(said.get(0).contains("cloud endpoints only") && said.get(0).contains("relay log line"));
    }

    @Test
    void theRelayIsNotPushedAgainWhenItIsAlreadyThere() {
        FakeCommander c = new FakeCommander();
        c.routeN = ROUTE_N;
        CloudTunnel.applyGateway(c, DNS, RELAY_SOURCE, T, line -> {});
        c.commands.clear();
        CloudTunnel.applyGateway(c, DNS, RELAY_SOURCE, T, line -> {});
        assertFalse(c.any("kill $(cat"));
    }

    @Test
    void stopRemovesTheGatewayAndRestoresResolvConf() {
        FakeCommander c = new FakeCommander();
        CloudTunnel.removeGateway(c, T);
        String joined = String.join("\n", c.commands);
        assertTrue(joined.contains("iptables -t nat -D OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN));
        assertTrue(joined.contains("iptables -t nat -X " + CloudTunnel.GATEWAY_CHAIN));
        assertTrue(joined.contains("cp -p " + CloudTunnel.RESOLV_BACKUP + " " + CloudTunnel.RESOLV));
        assertTrue(joined.contains("kill $(cat " + CloudTunnel.RELAY_PID + ")"));
    }

    @Test
    void anAdoptedTunnelsGatewayGetsThisCodesRules() {
        for (boolean hooked : new boolean[] {true, false}) {
            FakeCommander c = new FakeCommander();
            c.routeN = ROUTE_N;
            c.hooked = hooked;
            String done = CloudTunnel.refreshGateway(c, DNS, RELAY_SOURCE, T, line -> {});
            List<String> redirects = c.commands.stream().filter(x -> x.contains("REDIRECT")).toList();
            if (hooked) {
                assertEquals("gateway rules re-applied", done);
                assertTrue(redirects.stream().anyMatch(x -> x.contains("--dport 443 -j REDIRECT")));
            } else {
                assertEquals("gateway not on: left alone", done);
                assertEquals(List.of(), redirects);
            }
        }
    }

    @Test
    void theHealthCheckNamesTheRelayTheHookAndResolvConf() {
        String check = CloudTunnel.gatewayHealthyCommand(DNS);
        assertTrue(check.contains(String.format("0100007F:%04X", CloudTunnel.GATEWAY_LISTEN)));
        assertTrue(check.contains("iptables -t nat -C OUTPUT -j " + CloudTunnel.GATEWAY_CHAIN));
        assertTrue(check.contains("use-vc  " + CloudTunnel.TAG));
        assertFalse(CloudTunnel.gatewayHealthyCommand(List.of()).contains("use-vc"));
    }

    // --- the relay, over loopback ------------------------------------------------------------------------------

    @Test
    void aConnectHeaderNamesHostAndPort() {
        assertEquals(new Endpoint("203.0.113.5", 443),
                CloudTunnelRelay.parseConnect("CONNECT 203.0.113.5 443".getBytes(StandardCharsets.US_ASCII)));
        for (String bad : List.of("GET / HTTP/1.1", "CONNECT 203.0.113.5", "CONNECT host 70000", "CONNECT host x")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CloudTunnelRelay.parseConnect(bad.getBytes(StandardCharsets.US_ASCII)));
        }
    }

    /** A connected pair on loopback: {@code [ours, theirs]}. */
    private static Socket[] pair(ServerSocket server) throws IOException {
        Socket ours = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket theirs = server.accept();
        return new Socket[] {ours, theirs};
    }

    private static String readSome(InputStream in, int expected) throws IOException {
        byte[] buffer = new byte[1024];
        int total = 0;
        while (total < expected) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return new String(buffer, 0, total, StandardCharsets.US_ASCII);
    }

    @Test
    void aRelayedConnectionReachesItsDestinationWithTheBytesAfterTheHeader() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket echo = new ServerSocket(0, 1, loopback);
                ServerSocket channels = new ServerSocket(0, 1, loopback)) {
            echo.setSoTimeout(5000);
            Thread server = new Thread(() -> {
                try (Socket conn = echo.accept()) {
                    InputStream in = conn.getInputStream();
                    OutputStream out = conn.getOutputStream();
                    byte[] buffer = new byte[1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // the test's own sockets closing
                }
            });
            server.setDaemon(true);
            server.start();
            Socket[] ends = pair(channels);
            try (Socket relay = ends[0]) {
                relay.setSoTimeout(5000);
                relay.getOutputStream().write(("CONNECT " + loopback.getHostAddress() + " " + echo.getLocalPort()
                        + "\nhello").getBytes(StandardCharsets.US_ASCII));
                CloudTunnelRelay.openForward(ends[1], null);
                assertEquals("hello", readSome(relay.getInputStream(), 5));
                relay.getOutputStream().write(" again".getBytes(StandardCharsets.US_ASCII));
                assertEquals(" again", readSome(relay.getInputStream(), 6));
            }
        }
    }

    @Test
    void aBadHeaderClosesTheChannel() throws Exception {
        try (ServerSocket channels = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket[] ends = pair(channels);
            try (Socket relay = ends[0]) {
                relay.setSoTimeout(5000);
                relay.getOutputStream().write("HELLO\n".getBytes(StandardCharsets.US_ASCII));
                CloudTunnelRelay.openForward(ends[1], null);
                assertEquals(-1, relay.getInputStream().read());
            }
        }
    }
}
