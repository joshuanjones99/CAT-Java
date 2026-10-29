package com.verifone.cat.cloudtunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.ForwardHandler;
import com.verifone.cat.provision.transport.ForwardedConnection;
import com.verifone.cat.provision.transport.InMemoryForwards;
import com.verifone.cat.provision.transport.Transport;

/**
 * The serve loop ({@link CloudTunnelServer}, the Python's {@code serve}) over in-memory sessions: the forwards it asks
 * for, its {@code up}/{@code reconnected} lines, the re-plans, the gateway re-apply and the session-error retry, and a
 * gateway connection carried end to end to a loopback echo server.
 *
 * <p>Fake-backed: evidence about this repository, not about a Commander. No SSH session is opened; each "session" is
 * an in-memory {@link Transport} over one shared fake Commander, and its forwards are {@link InMemoryForwards}. The
 * only sockets are on loopback.
 */
class CloudTunnelServerTest {

    static final Duration T = Duration.ofSeconds(5);
    static final Duration WAIT = Duration.ofSeconds(10);
    static final List<String> DNS = CloudTunnelTest.DNS;
    static final CloudTunnelServer.Timing FAST = new CloudTunnelServer.Timing(Duration.ofMillis(5),
            Duration.ofMillis(20), Duration.ofMillis(5), Duration.ofSeconds(30));
    static final String SSHD = CloudTunnel.SSHD_CONFIG;

    /** The Commander's state, shared by every session the serve loop opens. */
    static final class Box {
        final Map<String, String> files = new HashMap<>();
        final Map<String, byte[]> blobs = new HashMap<>();
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final List<Session> sessions = new CopyOnWriteArrayList<>();
        volatile String unreached = "";
        volatile boolean healthy = true;
        volatile boolean relayStarts = true;
        volatile RuntimeException nextConnect;
        /** The next session's sshd refuses every forward with this reason. */
        volatile String refuseNext;

        static Box qat() {
            Box box = new Box();
            box.files.put(CloudTunnel.PROFILE_FILE, "profile=qat\n");
            box.files.put(CloudTunnel.BRIDGE_PARAMS, CloudTunnelTest.BRIDGE);
            box.files.put("/home/coresvcs/config/cloudEndpoints_qat.prop", CloudTunnelTest.QAT_ENDPOINTS);
            box.files.put(SSHD, "AllowTcpForwarding no\n");
            return box;
        }

        /** Opens a session, as {@code CommanderAccess.connectedTransport} would; a set failure is thrown once. */
        Transport open() {
            RuntimeException failure = nextConnect;
            if (failure != null) {
                nextConnect = null;
                throw failure;
            }
            Session session = new Session(this);
            String refusal = refuseNext;
            if (refusal != null) {
                refuseNext = null;
                session.forwards.refuse(refusal);
            }
            sessions.add(session);
            return session;
        }

        synchronized CommandResult run(String command) {
            commands.add(command);
            boolean ok = true;
            String out = "";
            if (command.startsWith("cat ")) {
                String path = command.substring(4).split("\\s+")[0];
                ok = files.containsKey(path);
                out = files.getOrDefault(path, "");
            } else if (command.equals("/sbin/route -n")) {
                out = CloudTunnelTest.ROUTE_N;
            } else if (command.contains("/dev/tcp/")) {
                ok = false; // the lab LAN's gateway forwards nothing
            } else if (command.startsWith("grep -o")) {
                out = unreached;
            } else if (command.startsWith("echo ${SSH_CLIENT")) {
                out = "198.51.100.160\n";
            } else if (command.contains("nohup")) {
                ok = relayStarts;
            } else if (command.equals(CloudTunnel.gatewayHealthyCommand(DNS))) {
                ok = healthy;
                healthy = true; // re-applied, it is healthy again
            } else if (command.contains(">> " + SSHD)) {
                writeSshd(command);
            } else if (command.contains(">> " + CloudTunnel.LEARNED_FILE)) {
                String body = between(command, "printf '", "' >> ");
                files.merge(CloudTunnel.LEARNED_FILE, body.replace("\\n", "\n"), String::concat);
            }
            return new CommandResult(ok ? 0 : 1, out, ok ? "" : "failed");
        }

        /** The tagged block the loop wrote, in place of the old one, as {@code sed -i} and {@code printf} would. */
        private void writeSshd(String command) {
            String body = between(command, "printf '\\n", "\\n' >> " + SSHD);
            StringBuilder kept = new StringBuilder();
            for (String line : files.getOrDefault(SSHD, "").split("\n")) {
                if (!line.contains(CloudTunnel.TAG) && !line.isEmpty()) {
                    kept.append(line).append('\n');
                }
            }
            for (String line : body.split("\\\\n")) {
                kept.append(line).append('\n');
            }
            files.put(SSHD, kept.toString());
        }

        private static String between(String text, String from, String to) {
            int start = text.indexOf(from) + from.length();
            return text.substring(start, text.indexOf(to, start));
        }

        long ran(String text) {
            synchronized (commands) {
                return commands.stream().filter(c -> c.contains(text)).count();
            }
        }
    }

    /** One SSH session to the box: commands go to it, forwards are in memory. */
    static final class Session implements Transport {
        final Box box;
        final InMemoryForwards forwards = new InMemoryForwards();
        volatile boolean active = true;
        volatile boolean closed;
        volatile Duration keepalive;

        Session(Box box) {
            this.box = box;
        }

        @Override
        public String host() {
            return "fake-commander";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            return box.run(command);
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            synchronized (box) {
                box.blobs.put(remotePath, content.clone());
            }
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            synchronized (box) {
                return Optional.ofNullable(box.blobs.get(remotePath));
            }
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {}

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return false;
        }

        @Override
        public void requestReverseForward(String bindAddress, int port, ForwardHandler handler, Duration timeout) {
            forwards.request(bindAddress, port, handler);
        }

        @Override
        public void keepalive(Duration interval) {
            keepalive = interval;
        }

        @Override
        public boolean isActive() {
            return active && !closed;
        }

        /** A Commander reboot. */
        void drop() {
            active = false;
            forwards.clear();
        }

        @Override
        public void close() {
            closed = true;
            forwards.clear();
        }

        List<Integer> ports() {
            return forwards.requested();
        }
    }

    /** A serve loop on its own thread, with its operator lines collected. */
    final class Running {
        final Box box;
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CloudTunnelServer server;
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread thread;

        Running(Box box, boolean gateway, Supplier<List<String>> dns) {
            this.box = box;
            this.server = new CloudTunnelServer(box::open, T, dns, CloudTunnelTest.RELAY_SOURCE, lines::add, FAST);
            this.thread = new Thread(() -> {
                try {
                    result.set(server.serve(false, gateway));
                } catch (RuntimeException e) {
                    result.set(e);
                }
            }, "serve-under-test");
            thread.setDaemon(true);
            thread.start();
            running.add(this);
        }

        /** The first line matching, waiting for it. */
        String line(Predicate<String> wanted) {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (String line : lines) {
                    if (wanted.test(line)) {
                        return line;
                    }
                }
                LockSupport.parkNanos(2_000_000L);
            }
            fail("no such line in " + lines);
            return null;
        }

        String startingWith(String prefix) {
            return line(l -> l.startsWith(prefix));
        }

        /** The first line from {@code mark} on that starts with {@code prefix}, waiting for it. */
        String after(int mark, String prefix) {
            return line(l -> lines.indexOf(l) >= mark && l.startsWith(prefix));
        }

        /** The session serving now: the last one opened. Session 0 is the discovery session before the loop. */
        Session serving() {
            return box.sessions.get(box.sessions.size() - 1);
        }

        Session session(int index) {
            return box.sessions.get(index);
        }

        /** Stops it and returns what serve returned. */
        Object stop() throws InterruptedException {
            server.stop();
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive(), "serve did not return after stop");
            return result.get();
        }
    }

    private final List<Running> running = new ArrayList<>();

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (Running r : running) {
            r.server.stop();
            r.thread.join(WAIT.toMillis());
        }
    }

    private Running serve(Box box) {
        return new Running(box, true, () -> DNS);
    }

    static final String QAT_UP = "up (qat): mqtt-qat.example.com:8883, gsc-qat.example.com:443, "
            + "reports-qat.example.com:8443; gateway on, DNS 203.0.113.20, 203.0.113.220";

    // --- coming up ---------------------------------------------------------------------------------------------

    @Test
    void comesUpWithOneForwardPerRouteAndTheGatewaysAndSaysSo() throws Exception {
        Running r = serve(Box.qat());
        assertEquals(QAT_UP, r.startingWith("up ("));
        assertEquals(List.of(), r.session(0).ports(), "the discovery session forwards nothing");
        assertTrue(r.session(0).closed);
        Session session = r.serving();
        assertEquals(List.of(20000, 20001, 20002, CloudTunnel.GATEWAY_FORWARD), session.ports());
        for (InMemoryForwards.Forward forward : session.forwards.forwards()) {
            assertEquals("127.0.0.1", forward.bindAddress());
        }
        assertEquals(Duration.ofSeconds(30), session.keepalive);
        assertTrue(r.lines.contains("sshd: remote forwarding allowed from the controller"));
        assertTrue(r.box.ran(">> " + CloudTunnel.HOSTS) > 0, "hosts lines written");
        assertTrue(r.box.ran("REDIRECT --to-ports " + CloudTunnel.GATEWAY_LISTEN) > 0, "gateway chain written");
        assertEquals(Boolean.TRUE, r.stop());
        assertTrue(session.closed, "the session is closed on stop");
    }

    @Test
    void withoutTheGatewayOnlyTheRoutesAreForwarded() throws Exception {
        Running r = new Running(Box.qat(), false, () -> DNS);
        assertEquals("up (qat): mqtt-qat.example.com:8883, gsc-qat.example.com:443, reports-qat.example.com:8443",
                r.startingWith("up ("));
        assertEquals(List.of(20000, 20001, 20002), r.serving().ports());
        assertEquals(0, r.box.ran("nohup"));
        r.stop();
    }

    @Test
    void aControllerWithNoDnsServerIsSaidAndTheGatewayStillComesUp() throws Exception {
        Running r = new Running(Box.qat(), true, List::of);
        r.startingWith("gateway: this controller has no IPv4 DNS server; no DNS for the Commander");
        assertTrue(r.startingWith("up (").endsWith("; gateway on, DNS none"));
        r.stop();
    }

    @Test
    void aRelayThatNeverListensLeavesTheEndpointRoutesUp() throws Exception {
        Box box = Box.qat();
        box.relayStarts = false;
        Running r = serve(box);
        r.startingWith("gateway: CatNetRelay did not listen; cloud endpoints only.");
        assertFalse(r.startingWith("up (").contains("gateway on"));
        r.stop();
    }

    @Test
    void aStopBeforeTheLoopReturnsTrueAfterPreparing() {
        Box box = Box.qat();
        List<String> lines = new ArrayList<>();
        CloudTunnelServer server = new CloudTunnelServer(box::open, T, () -> DNS, CloudTunnelTest.RELAY_SOURCE,
                lines::add, FAST);
        server.stop();
        assertTrue(server.serve(false, true));
        assertEquals(1, box.sessions.size());
        assertTrue(box.sessions.get(0).closed);
        assertEquals(List.of(), box.sessions.get(0).ports());
        assertTrue(box.ran(">> " + SSHD) > 0);
    }

    // --- carrying connections ----------------------------------------------------------------------------------

    /** Reads everything until end of stream, then answers {@code got <n>} and closes: a reply after a half-close. */
    private static ServerSocket countingServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout((int) WAIT.toMillis());
        Thread thread = new Thread(() -> {
            try (Socket conn = server.accept()) {
                byte[] all = conn.getInputStream().readAllBytes();
                OutputStream out = conn.getOutputStream();
                out.write(("got " + new String(all, StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException ignored) {
                // the test's own socket closing
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    @Test
    void aGatewayConnectionReachesItsDestinationAndTheReplyFollowsTheRequestsEnd() throws Exception {
        Running r = serve(Box.qat());
        r.startingWith("up (");
        try (ServerSocket server = countingServer()) {
            ForwardedConnection client = r.serving().forwards.connect(CloudTunnel.GATEWAY_FORWARD);
            OutputStream out = client.output();
            out.write(("CONNECT " + InetAddress.getLoopbackAddress().getHostAddress() + " " + server.getLocalPort()
                    + "\nhello").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client.shutdownOutput();
            assertEquals("got hello", new String(readAll(client.input()), StandardCharsets.US_ASCII));
            client.close();
        }
        r.stop();
    }

    @Test
    void theHandlerReturnsBeforeTheHeaderArrives() {
        // The SSH dispatcher calls the handler; a handler that waited for the header would hold every channel.
        InMemoryForwards forwards = new InMemoryForwards();
        forwards.request("127.0.0.1", CloudTunnel.GATEWAY_FORWARD, CloudTunnelRelay.forwardHandler(Map.of()));
        ForwardedConnection client = forwards.connect(CloudTunnel.GATEWAY_FORWARD); // returns: nothing was written
        client.close();
    }

    @Test
    void aRouteForwardGoesToItsEndpoint() throws Exception {
        try (ServerSocket server = countingServer()) {
            Endpoint endpoint = new Endpoint(InetAddress.getLoopbackAddress().getHostAddress(), server.getLocalPort());
            InMemoryForwards forwards = new InMemoryForwards();
            forwards.request("127.0.0.1", 20000, CloudTunnelRelay.forwardHandler(Map.of(20000, endpoint)));
            ForwardedConnection client = forwards.connect(20000);
            client.output().write("ping".getBytes(StandardCharsets.US_ASCII));
            client.shutdownOutput();
            assertEquals("got ping", new String(readAll(client.input()), StandardCharsets.US_ASCII));
            client.close();
        }
    }

    @Test
    void anUnreachableDestinationClosesTheChannel() throws Exception {
        int closedPort;
        try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = gone.getLocalPort();
        }
        InMemoryForwards forwards = new InMemoryForwards();
        forwards.request("127.0.0.1", CloudTunnel.GATEWAY_FORWARD, CloudTunnelRelay.forwardHandler(Map.of()));
        ForwardedConnection client = forwards.connect(CloudTunnel.GATEWAY_FORWARD);
        client.output().write(("CONNECT 127.0.0.1 " + closedPort + "\npayload").getBytes(StandardCharsets.US_ASCII));
        assertEquals(-1, client.input().read());
        client.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            all.write(buffer, 0, n);
        }
        return all.toByteArray();
    }

    // --- staying up --------------------------------------------------------------------------------------------

    @Test
    void aDroppedSessionReconnectsAndForwardsAgain() throws Exception {
        Running r = serve(Box.qat());
        r.startingWith("up (");
        Session first = r.serving();
        first.drop(); // a Commander reboot
        assertEquals("re" + "connected" + QAT_UP.substring(2), r.startingWith("reconnected ("));
        Session second = r.serving();
        assertTrue(first != second && first.closed);
        assertEquals(List.of(20000, 20001, 20002, CloudTunnel.GATEWAY_FORWARD), second.ports());
        r.stop();
    }

    @Test
    void anSshdRuleAnInstallWipedIsPutBackAndTheLoginRenewed() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        long reloads = box.ran("kill -HUP");
        Session first = r.serving();
        synchronized (box) {
            box.files.put(SSHD, "AllowTcpForwarding no\n"); // a PSI install
        }
        first.drop();
        r.startingWith("reconnected (");
        assertEquals(reloads + 1, box.ran("kill -HUP"));
        int at = box.sessions.indexOf(first);
        // The session that reloaded sshd asked for no forward: the rule applies to new logins only.
        assertEquals(List.of(), r.session(at + 1).ports());
        assertTrue(r.session(at + 1).closed);
        assertEquals(4, r.session(at + 2).ports().size());
        r.stop();
    }

    @Test
    void aProfileSwitchRemovesTheRoutesAndRePlans() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        long removals = box.ran("iptables -t nat -S OUTPUT");
        synchronized (box) {
            box.files.put(CloudTunnel.PROFILE_FILE, "profile=cst\n");
        }
        assertEquals("re-planned (qat -> cst)", r.startingWith("re-planned"));
        assertEquals("reconnected (cst): mqtt.example.com:8883; gateway on, DNS 203.0.113.20, 203.0.113.220",
                r.startingWith("reconnected ("));
        assertTrue(box.ran("iptables -t nat -S OUTPUT") > removals, "the old routes were removed");
        assertEquals(List.of(20000, CloudTunnel.GATEWAY_FORWARD), r.serving().ports());
        r.stop();
    }

    @Test
    void aHostTheCommanderCouldNotReachIsLearnedRememberedAndRelayed() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        box.unreached = "Couldn't connect to https://uis.login-qat.example.com\n";
        assertEquals("learned uis.login-qat.example.com:443", r.startingWith("learned"));
        assertEquals("re-planned (qat -> qat)", r.startingWith("re-planned"));
        assertTrue(r.startingWith("reconnected (").contains("uis.login-qat.example.com:443"));
        assertEquals(List.of(20000, 20001, 20002, 20003, CloudTunnel.GATEWAY_FORWARD), r.serving().ports());
        synchronized (box) {
            assertTrue(box.files.get(CloudTunnel.LEARNED_FILE).contains("https://uis.login-qat.example.com:443"));
        }
        // Run 02532b025576: a PSI install wiped the learned file. The next re-plan still relays the host.
        int mark = r.lines.size();
        synchronized (box) {
            box.files.remove(CloudTunnel.LEARNED_FILE);
        }
        box.unreached = "Couldn't connect to https://uis.login-qat.example.com\n"
                + "Couldn't connect to https://pems.login-qat.example.com\n";
        assertEquals("learned pems.login-qat.example.com:443", r.after(mark, "learned"));
        String again = r.after(mark, "reconnected (");
        assertTrue(again.contains("uis.login-qat.example.com:443") && again.contains("pems.login-qat.example.com:443"),
                again);
        r.stop();
    }

    @Test
    void anUnhealthyGatewayIsReApplied() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        long starts = box.ran("nohup");
        box.healthy = false; // a network apply emptied resolv.conf
        assertEquals("gateway: re-applied", r.startingWith("gateway: re"));
        assertTrue(box.ran("nohup") > starts);
        r.stop();
    }

    @Test
    void aGatewayThatCannotBeReAppliedIsLost() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        box.relayStarts = false;
        box.healthy = false;
        assertEquals("gateway: lost", r.startingWith("gateway: lost"));
        r.stop();
    }

    @Test
    void aSessionErrorIsSaidAndRetried() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        box.nextConnect = new DeviceUnreachable("ssh fake-commander", "connect failed: refused");
        r.serving().drop();
        assertEquals("session error: ssh fake-commander is unreachable: connect failed: refused; retrying in 0 s",
                r.startingWith("session error"));
        r.startingWith("reconnected (");
        r.stop();
    }

    @Test
    void aRefusedForwardIsASessionErrorAndTheNextSessionServes() throws Exception {
        Box box = Box.qat();
        Running r = serve(box);
        r.startingWith("up (");
        box.refuseNext = "TCP forwarding request denied";
        r.serving().drop();
        assertEquals("session error: remote port forwarding failed for listen port 20000: TCP forwarding request"
                + " denied; retrying in 0 s", r.startingWith("session error"));
        r.startingWith("reconnected (");
        r.stop();
    }
}
