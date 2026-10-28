package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.provision.transport.Transport;

/**
 * {@link BrowserOverride} against {@link InMemoryTransport} and a loopback HTTP server standing in for the relay's
 * DevTools port: a port of {@code tests/unit/test_browser_override.py}'s {@code browser_override} cases.
 *
 * <p>Evidence about this repository only. No register is reached; nothing here is rig-verified.
 */
class BrowserOverrideTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String REGISTER = "topaz101.invalid";
    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();
    private static final String CURL = "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:"
            + BrowserOverride.DEFAULT_CDP_PORT + "/json/version";

    /** A trimmed but structurally real topaz101 rc.local: ends with {@code apps start} then {@code exit 0}. */
    private static final String FAKE_RC_LOCAL = """
            #!/bin/bash
            [ -r /etc/init.d/functions ] && source /etc/init.d/functions

            setss A8 > /dev/null 2>&1
            # start apps here
            /etc/init.d/apps start

            # apps have started!
            setss A9 > /dev/null 2>&1
            exit 0
            """;

    private static InMemoryTransport withWrapper(InMemoryTransport transport) {
        return transport.withFile(BrowserOverride.CHROMIUM_WRAPPER_PATH, "#!/bin/sh\nexec ...\n".getBytes(
                StandardCharsets.UTF_8));
    }

    private static InMemoryTransport register() {
        return withWrapper(new InMemoryTransport(REGISTER));
    }

    private static byte[] rcLocal() {
        return FAKE_RC_LOCAL.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Optional<byte[]> content) {
        return new String(content.orElseThrow(), StandardCharsets.UTF_8);
    }

    /** A restart callback that records the transports it was handed. */
    private static final class Restarts implements BrowserOverride.Restart {
        final List<Transport> calls = new ArrayList<>();

        @Override
        public void restart(Transport transport) {
            calls.add(transport);
        }
    }

    // --- enable / disable --------------------------------------------------------------------------------------

    @Test
    void enableFailsIfTheWrapperWasNeverPushed() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER)
                .command("test -x " + BrowserOverride.CHROMIUM_WRAPPER_PATH, new CommandResult(1, "", "No such file"));

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, null);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("chromium_override"), result.detail());
        assertFalse(transport.ran().contains("chmod -x " + BrowserOverride.RUNBROWSER_PATH));
    }

    @Test
    void enableChmodsRunbrowserOffAndReportsNoRestartByDefault() {
        InMemoryTransport transport = register();

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, null);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(transport.ran().contains("chmod -x " + BrowserOverride.RUNBROWSER_PATH));
        assertTrue(result.detail().contains("no restart callback"), result.detail());
        assertTrue(result.detail().contains("NOT"), result.detail());
        assertFalse(result.restarted());
    }

    @Test
    void enableInvokesTheSuppliedRestartCallback() {
        InMemoryTransport transport = register();
        Restarts restarts = new Restarts();

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, restarts, null, BrowserOverride.DEFAULT_CDP_PORT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(transport), restarts.calls);
        assertTrue(result.detail().contains("restart callback invoked"), result.detail());
        assertTrue(result.detail().contains("relay not started"), result.detail());
        assertTrue(result.restarted());
    }

    @Test
    void enableFailsLoudlyWhenChmodItselfFails() {
        InMemoryTransport transport = register()
                .command("chmod -x " + BrowserOverride.RUNBROWSER_PATH, new CommandResult(1, "", "permission denied"));

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, null);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("permission denied"), result.detail());
    }

    @Test
    void disableRestoresRunbrowserAndRemovesTheWrapper() {
        InMemoryTransport transport = register();

        StepResult result = BrowserOverride.disable(transport, TIMEOUT, null, true);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(transport.ran().contains("chmod +x " + BrowserOverride.RUNBROWSER_PATH));
        assertTrue(transport.ran().contains("rm -f " + BrowserOverride.CHROMIUM_WRAPPER_PATH));
        assertTrue(result.detail().contains("no restart callback"), result.detail());
    }

    @Test
    void disableNeverSkipsRestoringRunbrowserEvenIfWrapperRemovalFails() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER)
                .command("rm -f " + BrowserOverride.CHROMIUM_WRAPPER_PATH, new CommandResult(1, "", "busy"));

        StepResult result = BrowserOverride.disable(transport, TIMEOUT, null, true);

        assertTrue(transport.ran().contains("chmod +x " + BrowserOverride.RUNBROWSER_PATH));
        assertEquals(StepStatus.DONE, result.status());
        assertTrue(result.detail().contains("restored"), result.detail());
    }

    @Test
    void disableFailsWhenRunbrowserCannotBeRestored() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER)
                .command("chmod +x " + BrowserOverride.RUNBROWSER_PATH, new CommandResult(1, "", "read-only"));

        StepResult result = BrowserOverride.disable(transport, TIMEOUT, null, true);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("read-only"), result.detail());
    }

    @Test
    void enableSkipsRestartWhenTheBrowserIsAlreadyServingCdp() {
        // 2026-09-11: restarting an already-fine browser is what broke a working register.
        InMemoryTransport transport = register().command(CURL, new CommandResult(0, "200", ""));
        Restarts restarts = new Restarts();

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, restarts, null, BrowserOverride.DEFAULT_CDP_PORT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(), restarts.calls);
        assertTrue(result.detail().contains("restart skipped"), result.detail());
        assertTrue(result.detail().contains("already answering CDP"), result.detail());
    }

    /**
     * A register serving CDP whose override files are {@code current}, launched as {@code stamp} (null: none, as after
     * a reboot) and {@code newer} than the browser or not. Answers {@code overrideChangedSinceLaunch}'s shell line as
     * the register's shell would, and keeps the stamp {@code enable} writes.
     */
    private static final class ServingRegister implements Transport {
        final InMemoryTransport inner = register().command(CURL, new CommandResult(0, "200", ""));
        final String current;
        final boolean newer;
        String stamp;

        ServingRegister(String current, String stamp, boolean newer) {
            this.current = current;
            this.stamp = stamp;
            this.newer = newer;
        }

        @Override
        public String host() {
            return inner.host();
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            if (command.startsWith("if [ -f " + BrowserOverride.LAUNCH_STAMP_FILE + " ]")) {
                inner.run(command, timeout);
                assertTrue(command.contains("-newer " + BrowserOverride.VM_PID_FILE), command);
                if (stamp != null) {
                    return new CommandResult(0, stamp.equals(current) ? "" : "changed\n", "");
                }
                return new CommandResult(0, newer ? BrowserOverride.CHROMIUM_POLICY_PATH + "\n" : "", "");
            }
            if (command.startsWith("sha256sum") && command.contains("> " + BrowserOverride.LAUNCH_STAMP_FILE)) {
                inner.run(command, timeout);
                stamp = current;
                return new CommandResult(0, "", "");
            }
            return inner.run(command, timeout);
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            inner.put(remotePath, content, mode, timeout);
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            return inner.get(remotePath, timeout);
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            inner.mkdir(remotePath, timeout);
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return inner.exists(remotePath, timeout);
        }

        @Override
        public void close() {
            inner.close();
        }
    }

    private static StepResult enableWithoutRelay(Transport transport, Restarts restarts) {
        return BrowserOverride.enable(transport, TIMEOUT, restarts, null, BrowserOverride.DEFAULT_CDP_PORT);
    }

    @Test
    void enableRestartsAServingBrowserLaunchedBeforeTheFilesChanged() {
        // 2026-09-15: Chrome reads the wrapper's flags and the policy only at launch.
        ServingRegister transport = new ServingRegister("new", null, true);
        Restarts restarts = new Restarts();

        StepResult result = enableWithoutRelay(transport, restarts);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(transport), restarts.calls);
        assertTrue(result.detail().contains("changed since the running browser launched"), result.detail());
        assertEquals("new", transport.stamp);
    }

    @Test
    void enableRestartsWhenTheStampedFilesChanged() {
        ServingRegister transport = new ServingRegister("new", "old", true);
        Restarts restarts = new Restarts();

        enableWithoutRelay(transport, restarts);

        assertEquals(List.of(transport), restarts.calls);
        assertEquals("new", transport.stamp);
    }

    @Test
    void aForcedReprovisionOfTheSameFilesDoesNotRestartThePos() {
        // Run 4a04258badad, 2026-10-15: same bytes, newer dates, and enable killed a fine POS.
        ServingRegister transport = new ServingRegister("same", "same", true);
        Restarts restarts = new Restarts();

        StepResult result = enableWithoutRelay(transport, restarts);

        assertEquals(List.of(), restarts.calls);
        assertTrue(result.detail().contains("restart skipped"), result.detail());
    }

    @Test
    void aServingBrowserWithNoStampIsStampedForNextTime() {
        ServingRegister transport = new ServingRegister("same", null, false);
        Restarts restarts = new Restarts();

        enableWithoutRelay(transport, restarts);

        assertEquals(List.of(), restarts.calls);
        assertEquals("same", transport.stamp);
    }

    @Test
    void enableRestartsWhenTheBrowserIsNotYetServingCdp() {
        InMemoryTransport transport = register();
        Restarts restarts = new Restarts();

        StepResult result = enableWithoutRelay(transport, restarts);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(transport), restarts.calls);
        assertTrue(result.detail().contains("restart callback invoked"), result.detail());
    }

    @Test
    void theLoopbackProbeBoundsCurlByTheTimeoutBetweenOneAndFiveSeconds() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER);

        BrowserOverride.locallyServingCdp(transport, 9220, Duration.ofMillis(300));
        BrowserOverride.locallyServingCdp(transport, 9220, Duration.ofSeconds(3));
        BrowserOverride.locallyServingCdp(transport, 9220, Duration.ofSeconds(30));

        assertTrue(transport.ran().get(0).contains("--max-time 1 "), transport.ran().get(0));
        assertTrue(transport.ran().get(1).contains("--max-time 3 "), transport.ran().get(1));
        assertTrue(transport.ran().get(2).contains("--max-time 5 "), transport.ran().get(2));
    }

    // --- restart_topaz_browser ----------------------------------------------------------------------------------

    @Test
    void theRestartReplaysStopappsSequenceThenDetachesTopazShWithNohupOnly() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER);

        BrowserOverride.restartTopazBrowser(transport, TIMEOUT);

        String stop = transport.ran().get(0);
        assertTrue(stop.contains("cat " + BrowserOverride.VM_PID_FILE), stop);
        int term = stop.indexOf("kill -TERM");
        int kill = stop.indexOf("kill -KILL");
        int quit = stop.indexOf("kill -QUIT");
        assertTrue(term > 0 && kill > term && quit > kill, stop);
        assertTrue(stop.contains("kill -TERM \"$pid\"; sleep 2; "), stop);
        assertTrue(stop.contains("kill -KILL \"$pid\"; sleep 2; "), stop);
        assertTrue(stop.contains("kill -QUIT \"$pid\"; sleep 1; "), stop);
        assertTrue(stop.endsWith("rm -f " + BrowserOverride.VM_PID_FILE), stop);

        String launch = transport.ran().get(1);
        assertTrue(launch.contains("topaz.sh"));
        assertFalse(launch.contains("setsid"), launch);
        assertTrue(launch.contains("nohup"), launch);
        assertTrue(launch.strip().endsWith("&'"), launch);
        assertTrue(launch.contains("$(cat /home/topaz/.topaz.startup"), launch);
        assertTrue(launch.contains(BrowserOverride.TOPAZ_SH + " $mode"), launch);
        assertTrue(launch.startsWith("su - topaz -c '"), launch);
    }

    @Test
    void theRestartReportsALaunchTheShellRefused() {
        // 2026-09-21: `setsid: command not found`, nothing read the exit, the register had no browser.
        InMemoryTransport transport = new InMemoryTransport(REGISTER).command(BrowserOverride.topazLaunchCommand(),
                new CommandResult(127, "", "-su: setsid: command not found"));

        DeviceError error = assertThrows(DeviceError.class,
                () -> BrowserOverride.restartTopazBrowser(transport, TIMEOUT));

        assertTrue(error.getMessage().contains("command not found"), error.getMessage());
    }

    @Test
    void aRefusedRestartPropagatesOutOfEnableAsTheTransportErrorDoes() {
        InMemoryTransport transport = register().command(BrowserOverride.topazLaunchCommand(),
                new CommandResult(127, "", "nope"));

        assertThrows(DeviceError.class, () -> BrowserOverride.enable(transport, TIMEOUT,
                BrowserOverride.TOPAZ_RESTART, null, BrowserOverride.DEFAULT_CDP_PORT));
    }

    @Test
    void theChecklistWrappersDelegateAndIgnoreTheControllerHost() {
        InMemoryTransport transport = register();

        StepResult enabled = new BrowserOverride.EnableBrowserOverride().apply(transport, "", TIMEOUT);

        // No restart callback: DONE, and saying the browser was not restarted, before any relay work.
        assertEquals(StepStatus.DONE, enabled.status());
        assertEquals("enable_browser_override", enabled.step());
        StepResult disabled = new BrowserOverride.DisableBrowserOverride().apply(transport, "", TIMEOUT);
        assertEquals(StepStatus.DONE, disabled.status());
        assertEquals("disable_browser_override", disabled.step());
    }

    // --- the CDP relay -------------------------------------------------------------------------------------------

    @Test
    void startingTheRelayFailsIfTheSourceWasNeverPushed() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER)
                .command("test -f " + BrowserOverride.RELAY_JAVA_PATH, new CommandResult(1, "", "No such file"));

        StepResult result = BrowserOverride.startCdpRelay(transport, 19220, 9220, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("cdp_relay"), result.detail());
    }

    private static InMemoryTransport noBootScript() {
        return new InMemoryTransport(REGISTER)
                .command("test -x " + BrowserOverride.RELAY_BOOT_SCRIPT_PATH, new CommandResult(1, "", ""));
    }

    @Test
    void startingTheRelayRunsTheBootScriptWhenItIsPushed() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER);

        StepResult result = BrowserOverride.startCdpRelay(transport, 19220, 9220, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(transport.ran().contains("nohup " + BrowserOverride.RELAY_BOOT_SCRIPT_PATH
                + " < /dev/null > /dev/null 2>&1 &"), transport.ran().toString());
        assertFalse(transport.ran().stream().anyMatch(c -> c.contains("pgrep")));
    }

    @Test
    void aBootScriptThatExitsNonZeroFailsTheRelayStart() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).command("nohup "
                + BrowserOverride.RELAY_BOOT_SCRIPT_PATH + " < /dev/null > /dev/null 2>&1 &",
                new CommandResult(2, "", "bad interpreter"));

        StepResult result = BrowserOverride.startCdpRelay(transport, 19220, 9220, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("bad interpreter"), result.detail());
    }

    @Test
    void startingTheRelayLaunchesItDoubleBackgroundedWhenNothingIsRunning() {
        InMemoryTransport transport = noBootScript();

        StepResult result = BrowserOverride.startCdpRelay(transport, 19220, 9220, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(transport.ran().stream().anyMatch(c -> c.startsWith("nohup su - topaz -c '")
                && c.contains(BrowserOverride.JAVA_BIN + " CdpRelay.java 19220 9220")
                && c.contains("cd /home/topaz/browser/chromium && ")
                && c.strip().endsWith("&")), transport.ran().toString());
    }

    @Test
    void startingTheRelayLeavesAnAlreadyRunningRelayAlone() {
        InMemoryTransport transport = noBootScript()
                .command("pgrep -f CdpRelay.java", new CommandResult(0, "4321\n", ""));

        StepResult result = BrowserOverride.startCdpRelay(transport, 19220, 9220, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(result.detail().contains("already running") && result.detail().contains("4321"), result.detail());
        assertFalse(transport.ran().stream().anyMatch(c -> c.contains("nohup")));
    }

    @Test
    void stoppingTheRelayIsANoOpIfNothingWasRunning() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER);

        StepResult result = BrowserOverride.stopCdpRelay(transport, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of("pkill -f CdpRelay.java 2>/dev/null; rm -f " + BrowserOverride.RELAY_PID_FILE + "; true"),
                transport.ran());
    }

    @Test
    void disableStopsTheRelayBeforeRestoringRunbrowser() {
        InMemoryTransport transport = register();

        StepResult result = BrowserOverride.disable(transport, TIMEOUT, null, true);

        assertEquals(StepStatus.DONE, result.status());
        List<String> ran = transport.ran();
        int pkill = indexOfPrefix(ran, "pkill -f CdpRelay.java");
        assertTrue(pkill >= 0 && ran.get(pkill).contains(BrowserOverride.RELAY_PID_FILE), ran.toString());
        assertTrue(pkill < ran.indexOf("chmod +x " + BrowserOverride.RUNBROWSER_PATH), ran.toString());
    }

    private static int indexOfPrefix(List<String> items, String prefix) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // --- off-box CDP reachability ------------------------------------------------------------------------------

    /** A loopback HTTP server standing in for the relay's DevTools port: /json/version and /json/list. */
    private static final class FakeCdpServer implements AutoCloseable {
        final HttpServer server;
        final int port;
        private boolean started;

        FakeCdpServer(int targets, String versionBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body;
                if (path.equals("/json/version")) {
                    body = versionBody != null ? versionBody : "{\"Browser\": \"Chrome/152.0.7977.42\"}";
                } else if (path.equals("/json/list")) {
                    StringBuilder list = new StringBuilder("[");
                    for (int i = 0; i < targets; i++) {
                        list.append(i == 0 ? "{}" : ", {}");
                    }
                    body = list.append(']').toString();
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }

        FakeCdpServer() throws IOException {
            this(2, null);
        }

        FakeCdpServer start() {
            server.start();
            started = true;
            return this;
        }

        @Override
        public void close() {
            if (started) {
                server.stop(0);
            }
        }
    }

    @Test
    void verifyingCdpAgainstARealLoopbackSocket() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer().start()) {
            StepResult result = BrowserOverride.verifyCdpReachable(LOOPBACK, server.port, TIMEOUT);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertTrue(result.detail().contains("2 target(s)"), result.detail());
            assertTrue(result.detail().contains("browser=Chrome/152"), result.detail());
        }
    }

    @Test
    void aVersionAnswerThatIsNotJsonIsNotReadyYet() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer(2, "<html>starting</html>").start()) {
            StepResult result = BrowserOverride.verifyCdpReachable(LOOPBACK, server.port, TIMEOUT);

            assertEquals(StepStatus.FAILED, result.status(), result.detail());
        }
    }

    @Test
    void verifyingCdpFailsWhenNothingIsListening() {
        StepResult result = BrowserOverride.verifyCdpReachable(LOOPBACK, 1, Duration.ofSeconds(2));

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("could not reach"), result.detail());
    }

    @Test
    void theRetryGivesUpAtItsBoundWithTheLastProbesDetail() {
        long started = System.nanoTime();

        StepResult result = BrowserOverride.verifyCdpReachableWithRetry(LOOPBACK, 1, Duration.ofMillis(1500));

        double elapsed = (System.nanoTime() - started) / 1e9;
        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("could not reach"), result.detail());
        assertTrue(elapsed < 10, "bounded: " + elapsed);
    }

    private static InMemoryTransport loopbackRegister() {
        return withWrapper(new InMemoryTransport(LOOPBACK));
    }

    @Test
    void enableStartsAndVerifiesTheRelayWhenARestartIsGiven() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer().start()) {
            InMemoryTransport transport = loopbackRegister();
            Restarts restarts = new Restarts();

            StepResult result = BrowserOverride.enable(transport, TIMEOUT, restarts, server.port, 9220);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertEquals(List.of(transport), restarts.calls);
            assertTrue(result.detail().contains("off-box reachability"), result.detail());
            assertTrue(result.detail().contains("2 target(s)"), result.detail());
            assertTrue(result.restarted());
        }
    }

    @Test
    void enableFailsWhenTheRelayIsNeverReachable() {
        // The live failure of run ac22943e6604: nothing served CDP on the relay port. Now the step says so. The
        // browser answers on loopback and is unchanged, so no restart and the wait is the 1 s SSH timeout.
        InMemoryTransport transport = loopbackRegister().command(CURL.replace("--max-time 5", "--max-time 1"),
                new CommandResult(0, "200", ""));
        Restarts restarts = new Restarts();

        StepResult result = BrowserOverride.enable(transport, Duration.ofSeconds(1), restarts, 1, 9220);

        assertEquals(List.of(), restarts.calls);
        assertEquals(StepStatus.FAILED, result.status(), result.detail());
        assertTrue(result.detail().contains("off-box reachability: could not reach"), result.detail());
    }

    @Test
    void enableFailsWhenTheRelaySourceIsMissing() {
        InMemoryTransport transport = loopbackRegister()
                .command("test -f " + BrowserOverride.RELAY_JAVA_PATH, new CommandResult(1, "", ""));

        StepResult result = BrowserOverride.enable(transport, TIMEOUT, t -> { }, 19220, 9220);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("cdp_relay"), result.detail());
    }

    @Test
    void enableWaitsForARestartedBrowserLongerThanTheSshTimeout() throws IOException {
        // HANDOFF.md 1.62, 2026-09-18: the browser answered after the 30 s SSH timeout. Here: 2 s after the restart,
        // against a 1 s SSH timeout.
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();
        try (FakeCdpServer server = new FakeCdpServer()) {
            InMemoryTransport transport = loopbackRegister();

            StepResult result = BrowserOverride.enable(transport, Duration.ofSeconds(1),
                    t -> later.schedule(server::start, 2, TimeUnit.SECONDS), server.port, 9220);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertTrue(result.detail().contains("2 target(s)"), result.detail());
        } finally {
            later.shutdownNow();
        }
    }

    // --- the boot hook ---------------------------------------------------------------------------------------------

    @Test
    void installingTheBootHookFailsIfRcLocalIsMissing() {
        StepResult result = BrowserOverride.installRelayBootHook(new InMemoryTransport(REGISTER), TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
    }

    @Test
    void aSlowRcLocalReadIsAFailedResultNotAThrow() {
        // 2026-09-16: a slow read escaped and failed a whole stage.
        InMemoryTransport transport = new InMemoryTransport(REGISTER).failReadsOf(BrowserOverride.RC_LOCAL_PATH);

        StepResult result = BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
    }

    @Test
    void aFailedRcLocalWriteIsAFailedResultNotAThrow() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER)
                .withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal()).failWritesTo(BrowserOverride.RC_LOCAL_PATH);

        StepResult result = BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
    }

    @Test
    void theBootHookGoesBeforeRcLocalsFinalCommandAndKeepsEverythingElse() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());

        StepResult result = BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        String updated = text(transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT));
        List<String> lines = updated.lines().toList();
        assertEquals("exit 0", lines.get(lines.size() - 1));
        assertEquals(BrowserOverride.BOOT_HOOK_END, lines.get(lines.size() - 2));
        assertTrue(updated.contains("[ -x " + BrowserOverride.RELAY_BOOT_SCRIPT_PATH + " ] && "
                + BrowserOverride.RELAY_BOOT_SCRIPT_PATH + " &\n"), updated);
        for (String original : FAKE_RC_LOCAL.lines().toList()) {
            assertTrue(updated.contains(original), original);
        }
        assertEquals(Optional.of(0755), transport.modeOf(BrowserOverride.RC_LOCAL_PATH));
    }

    @Test
    void theBootHookMarkerIsThePythonsByteForByte() {
        // A register the Python CAT hooked must read as hooked here, or the block is added twice.
        assertEquals("# >>> petrotest cdp_relay boot hook (BROWSER_STRATEGY.md §7) >>>",
                BrowserOverride.BOOT_HOOK_BEGIN);
        assertEquals("# <<< petrotest cdp_relay boot hook <<<", BrowserOverride.BOOT_HOOK_END);
    }

    @Test
    void installingTheBootHookIsIdempotent() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());

        BrowserOverride.installRelayBootHook(transport, TIMEOUT);
        byte[] once = transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT).orElseThrow();
        StepResult second = BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.DONE, second.status());
        assertTrue(second.detail().contains("already present"), second.detail());
        assertArrayEquals(once, transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT).orElseThrow());
        String text = new String(once, StandardCharsets.UTF_8);
        assertEquals(2, text.split("petrotest cdp_relay boot hook", -1).length - 1);
    }

    @Test
    void removingTheBootHookDeletesExactlyTheInsertedBlock() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());
        BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        StepResult result = BrowserOverride.removeRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(FAKE_RC_LOCAL, text(transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT)));
    }

    @Test
    void aSlowReadWhileRemovingTheBootHookIsAFailedResultNotAThrow() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).failReadsOf(BrowserOverride.RC_LOCAL_PATH);

        StepResult result = BrowserOverride.removeRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
    }

    @Test
    void removingANeverInstalledBootHookIsANoOp() {
        InMemoryTransport transport = new InMemoryTransport(REGISTER).withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());

        StepResult result = BrowserOverride.removeRelayBootHook(transport, TIMEOUT);

        assertEquals(StepStatus.DONE, result.status());
        assertTrue(result.detail().contains("not present"), result.detail());
        assertFalse(transport.writes().contains(BrowserOverride.RC_LOCAL_PATH));
    }

    @Test
    void enableInstallsTheBootHookAsBestEffortNotAHardRequirement() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer().start()) {
            StepResult result = BrowserOverride.enable(loopbackRegister(), TIMEOUT, t -> { }, server.port, 9220);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertTrue(result.detail().contains("boot hook"), result.detail());
            assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
        }
    }

    @Test
    void enableSurvivesABootHookReadThatTimesOut() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer().start()) {
            InMemoryTransport transport = loopbackRegister().failReadsOf(BrowserOverride.RC_LOCAL_PATH);

            StepResult result = BrowserOverride.enable(transport, TIMEOUT, t -> { }, server.port, 9220);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertTrue(result.detail().contains(BrowserOverride.RC_LOCAL_PATH), result.detail());
        }
    }

    @Test
    void enableInstallsAWorkingBootHookWhenRcLocalIsReachable() throws IOException {
        try (FakeCdpServer server = new FakeCdpServer().start()) {
            InMemoryTransport transport = loopbackRegister().withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());

            StepResult result = BrowserOverride.enable(transport, TIMEOUT, t -> { }, server.port, 9220);

            assertEquals(StepStatus.DONE, result.status(), result.detail());
            assertTrue(text(transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT))
                    .contains(BrowserOverride.RELAY_BOOT_SCRIPT_PATH));
        }
    }

    @Test
    void disableRemovesTheBootHookAlongWithStoppingTheRelay() {
        InMemoryTransport transport = register().withFile(BrowserOverride.RC_LOCAL_PATH, rcLocal());
        BrowserOverride.installRelayBootHook(transport, TIMEOUT);

        StepResult result = BrowserOverride.disable(transport, TIMEOUT, null, true);

        assertEquals(StepStatus.DONE, result.status());
        assertFalse(text(transport.get(BrowserOverride.RC_LOCAL_PATH, TIMEOUT))
                .contains(BrowserOverride.RELAY_BOOT_SCRIPT_PATH));
    }
}
