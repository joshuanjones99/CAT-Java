package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.posshim.ShimReplies.Health;
import com.verifone.cat.provision.transport.CommandResult;

/**
 * {@code EnsurePosShimCurrent} and {@code EnsurePosShimRunning} against a fake shim on loopback, through the real
 * {@code PosShimClient}. Evidence about this repository, not a register: UNVERIFIED AGAINST HARDWARE.
 */
class RegisterBringupTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String LOG_TAIL = "tail -n 5 " + RegisterBringup.POS_SHIM_LOG;
    private static final String START = "su - topaz -c '/home/topaz/automation/petrotest-pos-shim.sh start'";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT = Duration.ofMillis(300);

    @TempDir
    Path dir;

    private RecordingTransport register;
    private FakeShim shim;
    private Path payloadRoot;

    @BeforeEach
    void setUp() throws IOException {
        register = new RecordingTransport("topaz101.invalid");
        register.inner
                .command(FakeShim.SHIM_RESTART, new CommandResult(0,
                        "petrotest-pos-shim: stopped\npetrotest-pos-shim: started (pid 4242)\n", ""))
                .command(START, new CommandResult(0, "petrotest-pos-shim: already running (pid 4242)\n", ""))
                .command(LOG_TAIL, new CommandResult(0, "[pos-shim] FATAL: could not start the RMI host\n", ""));
        shim = new FakeShim(register);
        payloadRoot = Files.createDirectories(dir.resolve("payload"));
        Files.createDirectories(payloadRoot.resolve("pos"));
        Files.writeString(payloadRoot.resolve("pos").resolve(ShimStamp.STAMP_NAME), FakeShim.NEW + "\n");
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    private RegisterBringup.EnsurePosShimCurrent step(Duration shimStart, Duration settle) {
        return new RegisterBringup.EnsurePosShimCurrent(LOOPBACK, shim.port(), payloadRoot, shimStart,
                Duration.ofMillis(20), settle, Duration.ofMillis(20));
    }

    private RegisterBringup.EnsurePosShimCurrent step() {
        return step(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private List<String> ran() {
        return register.ran();
    }

    @Test
    void thePayloadsOwnBuildAlreadyRunningIsLeftAlone() {
        shim.running = FakeShim.NEW;

        StepResult result = step().apply(register, "", TIMEOUT);

        assertEquals(StepStatus.ALREADY, result.status(), result.detail());
        assertTrue(result.detail().contains(FakeShim.NEW.substring(0, 12)), result.detail());
        assertEquals(List.of(), ran());
        assertEquals(1, shim.reads.get());
    }

    @ParameterizedTest(name = "another build ({0}) is replaced: shim, then POS, then waits for the POS")
    @NullSource
    @ValueSource(strings = {"0ld000000000"})
    void anotherBuildIsReplacedShimThenPosThenWaitsForThePos(String running) {
        shim.running = running;

        StepResult result = step().apply(register, "", TIMEOUT);

        assertEquals(StepStatus.DONE, result.status(), result.detail());
        assertEquals(List.of(FakeShim.SHIM_RESTART, RegisterBringup.POS_APP_RESTART), ran());
        assertEquals(List.of(
                new RecordingTransport.Event("GET /health", "old shim"),
                new RecordingTransport.Event("ran", FakeShim.SHIM_RESTART),
                new RecordingTransport.Event("GET /health", "new shim, POS not restarted"),
                new RecordingTransport.Event("ran", RegisterBringup.POS_APP_RESTART),
                new RecordingTransport.Event("GET /health", "POS silent"),
                new RecordingTransport.Event("GET /health", "POS silent"),
                new RecordingTransport.Event("GET /health", "POS back")),
                register.events());
        String was = running == null ? "unstamped (built before 2026-10-16)"
                : "build " + FakeShim.OLD.substring(0, 12);
        assertTrue(result.detail().contains("the shim was " + was + ": restarted on build "
                + FakeShim.NEW.substring(0, 12) + ", then the POS"), result.detail());
        assertTrue(result.detail().contains(FakeShim.CLIENT + " drivable with state"), result.detail());
    }

    @Test
    void aPosThatNeverComesBackFailsAfterItsBoundNamingWhatHealthSaid() {
        shim.posBack = false;

        StepResult result = step(Duration.ofSeconds(2), SHORT).apply(register, "", TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().startsWith("the POS did not come back in 0.3 s after its restart"),
                result.detail());
        assertTrue(result.detail().contains("build " + FakeShim.NEW.substring(0, 12)), result.detail());
        assertTrue(result.detail().contains("drivable_but_silent ['" + FakeShim.CLIENT + "']"), result.detail());
        assertTrue(result.detail().contains("state_present False"), result.detail());
        assertTrue(result.detail().contains("(" + FakeShim.SILENT_WARNING + ")"), result.detail());
        assertEquals(List.of(FakeShim.SHIM_RESTART, RegisterBringup.POS_APP_RESTART), ran());
    }

    @Test
    void healthSaidAppendsTheShimsWarningOnlyWhenItSendsOne() {
        assertFalse(RegisterBringup.healthSaid(health(null)).contains("("), RegisterBringup.healthSaid(health(null)));
        assertFalse(RegisterBringup.healthSaid(health("")).contains("("), RegisterBringup.healthSaid(health("")));
        assertTrue(RegisterBringup.healthSaid(health("the shim's own words")).endsWith(" (the shim's own words)"),
                RegisterBringup.healthSaid(health("the shim's own words")));
    }

    private static Health health(String warning) {
        return new Health(true, FakeShim.NEW, 3, List.of(FakeShim.CLIENT), List.of(), List.of(), false, Map.of(),
                List.of(FakeShim.CLIENT), warning);
    }

    @Test
    void aRestartedShimOnAnotherBuildFailsAndThePosIsLeftRunning() {
        shim.restartedBuild = FakeShim.OLD;

        StepResult result = step().apply(register, "", TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("reports build " + FakeShim.OLD.substring(0, 12)
                + ", not the payload's " + FakeShim.NEW.substring(0, 12)), result.detail());
        assertEquals(List.of(FakeShim.SHIM_RESTART), ran());
    }

    @Test
    void aShimThatDoesNotComeBackFailsWithItsLogAndThePosIsLeftRunning() {
        shim.restartedDown = true;

        StepResult result = step(SHORT, Duration.ofSeconds(2)).apply(register, "", TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("did not answer GET /health in 0.3 s"), result.detail());
        assertTrue(result.detail().contains("returned 503"), result.detail());
        assertTrue(result.detail().contains("could not start the RMI host"), result.detail());
        assertEquals(List.of(FakeShim.SHIM_RESTART, LOG_TAIL), ran());
    }

    @Test
    void aFailedShimRestartFailsWithItsOutput() {
        register.inner.command(FakeShim.SHIM_RESTART,
                new CommandResult(1, "", "petrotest-pos-shim: no JVM at x"));

        StepResult result = step().apply(register, "", TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("restart exited 1: petrotest-pos-shim: no JVM at x"), result.detail());
        assertEquals(List.of(FakeShim.SHIM_RESTART), ran());
    }

    @Test
    void noShimListeningIsLeftToEnsurePosShimRunning() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            port = probe.getLocalPort();
        }
        RegisterBringup.EnsurePosShimCurrent step = new RegisterBringup.EnsurePosShimCurrent(LOOPBACK, port,
                payloadRoot, Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofSeconds(2),
                Duration.ofMillis(20));

        StepResult result = step.apply(register, "", TIMEOUT);

        assertEquals(StepStatus.SKIPPED, result.status());
        assertTrue(result.detail().contains("none to replace") && result.detail().contains("ensure_pos_shim_running"),
                result.detail());
        assertEquals(List.of(), ran());
    }

    @Test
    void aPayloadWithNoStampIsNotCompared() throws IOException {
        Path bare = Files.createDirectories(dir.resolve("bare"));

        StepResult result = new RegisterBringup.EnsurePosShimCurrent(LOOPBACK, shim.port(), bare,
                Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofSeconds(2), Duration.ofMillis(20))
                .apply(register, "", TIMEOUT);

        assertEquals(StepStatus.SKIPPED, result.status());
        assertTrue(result.detail().contains(ShimStamp.STAMP_NAME) && result.detail().contains("build_pos_shim.py"),
                result.detail());
        assertEquals(0, shim.reads.get());
        assertEquals(List.of(), ran());
    }

    @Test
    void anUnboundStepAsksNothing() {
        StepResult result = new RegisterBringup.EnsurePosShimCurrent().apply(register, "", TIMEOUT);

        assertEquals(StepStatus.SKIPPED, result.status());
        assertTrue(result.detail().contains("for_target"), result.detail());
        assertEquals(List.of(), register.events());
    }

    @Test
    void bindingTakesTheRegistersHostAndShimPort() {
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, "topaz101", LOOPBACK, "root", 22, shim.port(), null);

        ChecklistStep bound = new RegisterBringup.EnsurePosShimCurrent().forTarget(spec, payloadRoot);

        assertTrue(bound instanceof RegisterBringup.EnsurePosShimCurrent current
                && LOOPBACK.equals(current.host()) && current.port() == shim.port());
    }

    @Test
    void theStartStepKeepsARunningShimAndReportsDone() {
        assertEquals(StepStatus.ALREADY,
                RegisterBringup.ensurePosShimRunning(register, TIMEOUT).status());

        register.inner.command(START, new CommandResult(0, "petrotest-pos-shim: started (pid 9)\n", ""));
        StepResult started = RegisterBringup.ensurePosShimRunning(register, TIMEOUT);
        assertEquals(StepStatus.DONE, started.status());
        assertEquals("petrotest-pos-shim: started (pid 9)", started.detail());
    }

    @Test
    void aStartThatExitsNonZeroFailsWithItsOutputOrExitCode() {
        register.inner.command(START, new CommandResult(2, "", ""));

        StepResult result = RegisterBringup.ensurePosShimRunning(register, TIMEOUT);

        assertEquals(StepStatus.FAILED, result.status());
        assertEquals("exit 2", result.detail());
    }

    @Test
    void thePosRestartIsDetachedAndDropsStaleSerialLocks() {
        assertTrue(RegisterBringup.POS_APP_RESTART.startsWith("nohup sh -c '/etc/init.d/apps stop; "));
        assertTrue(RegisterBringup.POS_APP_RESTART.contains("/var/lock/LCK..tty*"));
        assertTrue(RegisterBringup.POS_APP_RESTART.endsWith("/etc/init.d/apps start' </dev/null >/dev/null 2>&1 &"));
    }
}
