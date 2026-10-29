package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.vats.Btt;
import com.verifone.cat.devices.vats.VatsRigs;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;

/**
 * The launch's VATS pieces through their real entry points with fakes at the edges: step 3's BTT card server
 * ({@link VatsCardServerStart}), step 4's pinpad link to the M400 ({@link VatsPinpadLink}, the Python's
 * {@code test_cat_launch_vats_link.py} netstat rows), step 5's lazily opened session ({@link VatsRunPinpad}) and
 * recovery's abort ({@link VatsRecovery}). Evidence about this repository only.
 */
class VatsLaunchTest {

    @TempDir
    Path dir;

    private static final String COMMANDER = "192.0.2.11";
    private static final String CONTROLLER = "192.0.2.10";
    private static final String PINPAD = "192.0.2.126";

    private static final String HEADER = "Active Internet connections (servers and established)\n"
            + "Proto Recv-Q Send-Q Local Address           Foreign Address         State\n";

    /** GNU net-tools, dual-stack: IPv4 peers in {@code ::ffff:} form, as on the lab Commander. */
    private static final String GNU_BEFORE = HEADER
            + "tcp6       0      0 :::8080                 :::*                    LISTEN\n"
            + "tcp6       0      0 " + COMMANDER + ":52370        " + CONTROLLER + ":41107        ESTABLISHED\n"
            + "tcp6       1      0 ::ffff:" + COMMANDER + ":40258 ::ffff:" + PINPAD + ":5000 LAST_ACK\n";
    private static final String GNU_NEW = "tcp6       0      0 ::ffff:" + COMMANDER + ":40312 ::ffff:" + PINPAD
            + ":5000 TIME_WAIT\n";

    /** busybox {@code netstat}: plain {@code tcp} rows, IPv4 addresses as-is. */
    private static final String BUSYBOX_BEFORE = HEADER
            + "tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN\n"
            + "tcp        0      0 " + COMMANDER + ":52370        " + CONTROLLER + ":41107        ESTABLISHED\n"
            + "tcp        1      0 " + COMMANDER + ":40258        " + PINPAD + ":5000           LAST_ACK\n";
    private static final String BUSYBOX_NEW = "tcp        0      0 " + COMMANDER + ":40330        " + PINPAD
            + ":5000           ESTABLISHED\n";

    /** New, but to the controller's Petro (its ViperPay face is also 5000): not the M400. */
    private static final String TO_PETRO = "tcp        0      0 " + COMMANDER + ":40400        " + CONTROLLER
            + ":5000         CLOSE_WAIT\n";
    /** New, but the dial never completed: the terminal did not answer. */
    private static final String DIALLING = "tcp        0      1 " + COMMANDER + ":40410        " + PINPAD
            + ":5000           SYN_SENT\n";

    private RunContext context(LaunchConfig config, Rig rig) throws IOException {
        Path folder = dir.resolve("aaaaaaaaaaaa");
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "aaaaaaaaaaaa", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        if (rig != null) {
            ctx.rig(new LoadedRig(rig, dir.resolve("vatsbench.yaml")));
        }
        return ctx;
    }

    private Rig vatsRig() throws IOException {
        return VatsRigs.load(dir, "vats", "  host: " + PINPAD);
    }

    private List<String> messages() throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(dir.resolve("aaaaaaaaaaaa"))) {
            out.add(event.toString());
        }
        return out;
    }

    // -- step 3 ----------------------------------------------------------------------------

    @Test
    void aCardServerStartedPassesItsRowAndLogsUnderCatBtt() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig("vatsbench"), vatsRig());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        List<Rig.Vats> asked = new ArrayList<>();
        assertTrue(VatsCardServerStart.start(ctx, step, (config, timeout, log) -> {
            asked.add(config);
            assertEquals(Duration.ofSeconds(120), timeout);
            log.accept("BTT 5.9.0 launched from C:\\Fime\\BTT.exe");
            return new Btt.CardServerStart(true, "already running", "card server answers on 9050", null);
        }));
        assertEquals(9050, asked.get(0).cardServerPort());
        assertEquals("complete", step.state());
        List<String> events = messages();
        assertTrue(events.stream().anyMatch(e -> e.contains("cat.btt") && e.contains("BTT 5.9.0 launched")),
                events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("BTT card server") && e.contains("already running")),
                events.toString());
    }

    @Test
    void noCardServerStopsTheRunAtStep3WithTheReason() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig("vatsbench"), vatsRig());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        assertFalse(VatsCardServerStart.start(ctx, step, (config, timeout, log) -> new Btt.CardServerStart(false,
                "not installed", "BTT 0.0.0 not found under C:\\ProgramData\\Fime\\BTT\\*", null)));
        assertEquals("failed", step.state());
        assertTrue(step.detail().startsWith("BTT card server: BTT 0.0.0 not found"), step.detail());
    }

    @Test
    void otherRigsAndFakePosRunsStartNoCardServer() throws IOException {
        VatsCardServerStart.Starter never = (config, timeout, log) -> {
            throw new AssertionError("started BTT");
        };
        RunContext viperpay = context(LaunchConfig.forRig("bench"), VatsRigs.load(dir, "viperpay", null));
        assertTrue(VatsCardServerStart.start(viperpay, new StepEmitter(viperpay, LaunchStepNames.SIMULATORS), never));
        RunContext fake = context(new LaunchConfig.Builder("vatsbench").fakePos(true).build(), vatsRig());
        assertTrue(VatsCardServerStart.start(fake, new StepEmitter(fake, LaunchStepNames.SIMULATORS), never));
        RunContext none = context(LaunchConfig.forRig("vatsbench"), null);
        assertTrue(VatsCardServerStart.start(none, new StepEmitter(none, LaunchStepNames.SIMULATORS), never));
    }

    // -- step 4 ----------------------------------------------------------------------------

    /** {@code netstat -ant} on a fake Commander: {@code before} until the download ran, then {@code after}. */
    private static final class FakeCommander implements Transport {
        final AtomicInteger downloads;
        final String before;
        final String after;
        final List<Boolean> reads = new ArrayList<>();
        boolean closed;

        FakeCommander(AtomicInteger downloads, String before, String after) {
            this.downloads = downloads;
            this.before = before;
            this.after = after;
        }

        @Override
        public String host() {
            return "commander.invalid";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            assertEquals("netstat -ant", command);
            boolean ran = downloads.get() > 0;
            reads.add(ran);
            return new CommandResult(0, ran ? after : before, "");
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new AssertionError("put");
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            throw new AssertionError("get");
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            throw new AssertionError("mkdir");
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            throw new AssertionError("exists");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private record Linked(PinpadLink.Result result, FakeCommander commander, StepEmitter step, AtomicInteger downloads) {}

    private Linked link(String before, String after, boolean downloadOk) throws IOException {
        Rig rig = VatsRigs.load(dir, "vats", "  host: " + PINPAD);
        RunContext ctx = context(LaunchConfig.forRig("vatsbench"), rig);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);
        AtomicInteger downloads = new AtomicInteger();
        FakeCommander commander = new FakeCommander(downloads, before, after);
        VatsPinpadLink link = new VatsPinpadLink(() -> commander, Duration.ofSeconds(5), noWait(),
                new VatsPinpadLink.Timing(Duration.ofMillis(50), Duration.ofMillis(1)));
        PinpadLink.Result result = link.link(step, rig, "vatsbench", say -> {
            downloads.incrementAndGet();
            if (!downloadOk) {
                say.err("emv-keys: the download failed");
            }
            return downloadOk;
        });
        return new Linked(result, commander, step, downloads);
    }

    /** Polls without waiting: at most a few hundred looks within the link's own short budget. */
    private static Pauser noWait() {
        return duration -> { };
    }

    @Test
    void aNewSocketToTheM400AfterTheDownloadPassesGnu() throws IOException {
        assertNewSocketPasses(GNU_BEFORE, GNU_NEW);
    }

    @Test
    void aNewSocketToTheM400AfterTheDownloadPassesBusybox() throws IOException {
        assertNewSocketPasses(BUSYBOX_BEFORE, BUSYBOX_NEW);
    }

    private void assertNewSocketPasses(String before, String fresh) throws IOException {
        Linked linked = link(before, before + TO_PETRO + fresh, true);
        assertTrue(linked.result().linked(), linked.result().detail());
        assertFalse(linked.commander().reads.get(0), "the before-read must precede the download");
        assertTrue(linked.commander().reads.get(linked.commander().reads.size() - 1));
        assertTrue(linked.commander().closed);
        List<String> events = messages();
        assertTrue(events.stream().anyMatch(e -> e.contains("holds 1 socket(s) to the pinpad at")
                && e.contains("LAST_ACK")), events.toString());
        String passed = events.stream().filter(e -> e.contains("PASS  the Commander's EPS reached")).findFirst()
                .orElseThrow();
        assertTrue(passed.contains(PINPAD + ":5000"), passed);
        assertFalse(passed.contains("40258"), "the socket held before the download is not new");
        assertFalse(passed.contains("40400"), "a socket to the controller's Petro is not the M400");
    }

    @Test
    void onlyTheOldSocketsFailsNamingWhatTheCommanderHeld() throws IOException {
        Linked linked = link(GNU_BEFORE, GNU_BEFORE + TO_PETRO, true);
        assertFalse(linked.result().linked());
        assertTrue(linked.result().detail().contains("no new socket to the pinpad at " + PINPAD + ":5000"),
                linked.result().detail());
        assertTrue(linked.result().detail().contains("LAST_ACK"), linked.result().detail());
        assertTrue(linked.commander().reads.size() > 2, "the window is polled, not read once");
        assertFalse(linked.result().detail().contains("Petro Simulator Suite"));
    }

    @Test
    void aDialTheTerminalNeverAnsweredIsNotALink() throws IOException {
        Linked linked = link(BUSYBOX_BEFORE, BUSYBOX_BEFORE + DIALLING, true);
        assertFalse(linked.result().linked());
        assertTrue(linked.result().detail().contains("no new socket"), linked.result().detail());
        assertFalse(linked.result().detail().contains("SYN_SENT"), linked.result().detail());
    }

    @Test
    void aFailedDownloadFailsWithoutWatching() throws IOException {
        Linked linked = link(GNU_BEFORE, GNU_BEFORE + GNU_NEW, false);
        assertFalse(linked.result().linked());
        assertEquals("emv-keys: the download failed", linked.result().detail());
        assertEquals(List.of(false), linked.commander().reads);
        assertTrue(linked.commander().closed);
    }

    @Test
    void anUnreachableCommanderIsNamed() throws IOException {
        Rig rig = vatsRig();
        RunContext ctx = context(LaunchConfig.forRig("vatsbench"), rig);
        VatsPinpadLink link = new VatsPinpadLink(() -> {
            throw new DeviceError("ssh: connection refused");
        }, Duration.ofSeconds(1), noWait(), VatsPinpadLink.Timing.PRODUCTION);
        PinpadLink.Result result = link.link(new StepEmitter(ctx, LaunchStepNames.COMMANDER), rig, "vatsbench",
                say -> {
                    throw new AssertionError("downloaded without a Commander to watch");
                });
        assertEquals("cannot reach the Commander to watch its sockets to the pinpad at " + PINPAD
                + ":5000: ssh: connection refused", result.detail());
    }

    // -- step 5 and recovery ---------------------------------------------------------------

    @Test
    void theRunsPinpadOpensItsSessionOnceAtFirstUseAndRemembersAFailure() {
        AtomicInteger opens = new AtomicInteger();
        VatsRunPinpad pinpad = new VatsRunPinpad(() -> {
            opens.incrementAndGet();
            throw new DeviceError("the VATS relay is unreachable: exited with 1");
        });
        assertEquals("1", pinpad.id());
        assertEquals(0, opens.get(), "building it opens nothing");
        assertTrue(pinpad.declinedAtNanos().isEmpty());
        assertEquals(List.of(), pinpad.abortPayment(), "no session: no payment to end");
        assertEquals(0, opens.get(), "neither the decline nor the abort opens a session");
        DeviceError first = assertThrows(DeviceError.class, () -> pinpad.atPrompt("Enter PIN", null));
        DeviceError second = assertThrows(DeviceError.class, () -> pinpad.key("YES"));
        assertEquals("pinpad: the VATS session did not start: the VATS relay is unreachable: exited with 1",
                first.getMessage());
        assertEquals(first.getMessage(), second.getMessage());
        assertEquals(1, opens.get(), "a failed session is not retried");
        assertNull(VatsRunPinpad.current());
        pinpad.close();
    }

    @Test
    void recoveryDoesNothingWithoutAnOpenVatsSession() {
        assertNull(VatsRunPinpad.current());
        VatsRecovery.abortPinpadPayment(null, null); // a null driver would throw if touched
        VatsRecovery.abortPinpadPayment(null);
    }

    @Test
    void theProductionPinpadIsBuiltWithoutOpeningAnything() throws IOException {
        VatsRunPinpad pinpad = VatsRunPinpad.forRig(vatsRig(), Map.of("CAT_TOOLS_ROOT", dir.toString()));
        assertTrue(pinpad.declinedAtNanos().isEmpty());
        pinpad.close();
        assertSame(null, VatsRunPinpad.current());
    }
}
