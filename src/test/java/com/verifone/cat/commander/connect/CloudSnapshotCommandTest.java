package com.verifone.cat.commander.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.CgiReplies;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleContext;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.rig.Rig;

/**
 * {@code cloud-snapshot capture|diff|restore} (the Python's {@code cloud_snapshot_cli}, {@code _mark_commander} and
 * {@code _wait_applied}) over the in-memory Commander Connect of {@link CloudSnapshotTest} and a recorded Commander
 * log; the settle runs on a test clock. Nothing reaches a cloud or a Commander.
 */
class CloudSnapshotCommandTest {

    @TempDir
    Path directory;

    private CloudSnapshotTest.FakeCloud fake;
    private CloudSnapshot.Cloud cloud;

    /** The Commander's log: the lines {@code push} has added since the mark. */
    private static final class Log implements CommanderLog {
        final StringBuilder lines = new StringBuilder();
        boolean marked;
        boolean closed;

        @Override
        public void mark() {
            marked = true;
        }

        @Override
        public String linesSinceMark() {
            return lines.toString();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Wiring implements CloudSnapshotCommand.Wiring {
        final CloudSnapshot.Cloud cloud;
        final Log log;
        long now;

        Wiring(CloudSnapshot.Cloud cloud, Log log) {
            this.cloud = cloud;
            this.log = log;
        }

        @Override
        public CloudSnapshot.Cloud cloud(Rig rig, String serviceId, Map<String, String> environ) {
            if (cloud == null) {
                throw new DeviceError("the Commander (onboarded to qat) names no base URL");
            }
            return cloud;
        }

        @Override
        public CommanderLog markCommander(Rig rig, Map<String, String> environ, Say say) {
            if (log != null) {
                log.mark();
            }
            return log;
        }

        @Override
        public CloudSettle settle() {
            return new CloudSettle(new CloudSettleContext(null, CloudSettleContext.APPLY_BUDGET,
                    Duration.ofSeconds(10), Duration.ofSeconds(5), () -> now, d -> now += d.toNanos()));
        }
    }

    @BeforeEach
    void capture() {
        ObjectNode data = CloudSnapshotTest.golden();
        fake = new CloudSnapshotTest.FakeCloud(data.deepCopy(), Set.of("tips"));
        List<String> names = new ArrayList<>();
        data.fieldNames().forEachRemaining(names::add);
        cloud = new CloudSnapshot.Cloud("1181078", fake::send);
        CloudSnapshot.capture(cloud, names, directory, Clock.fixed(Instant.parse("2026-10-22T01:20:00Z"),
                ZoneOffset.UTC));
    }

    private boolean cli(String action, Wiring wiring, Say say) {
        return CloudSnapshotCommand.cli(action, null, "1181078", directory, directory, Map.of(), wiring, say);
    }

    @Test
    void aDiffWithNothingDifferentPasses() {
        Say.Recorded say = new Say.Recorded();
        assertTrue(cli("diff", new Wiring(cloud, null), say));
        assertEquals(List.of("cloud-snapshot: 0 of 5 datasets differ"), say.outLines());
    }

    @Test
    void aDiffNamesWhatDiffers() {
        ((ObjectNode) fake.items("taxrates").get(0)).put("rate", 9.9999);
        Say.Recorded say = new Say.Recorded();
        assertFalse(cli("diff", new Wiring(cloud, null), say));
        assertTrue(say.outLines().get(0).startsWith("DIFFERS taxrates: "), say.outText());
    }

    @Test
    void aRestoreWaitsForTheCommanderToApplyWhatItWrote() {
        ((ObjectNode) fake.items("taxrates").get(0)).put("rate", 9.9999);
        Log log = new Log();
        log.lines.append("Received config update for the dataset - taxratecfg from cloud\n")
                .append("Successfully applied the taxratecfg config update\n");
        Say.Recorded say = new Say.Recorded();

        assertTrue(cli("restore", new Wiring(cloud, log), say), say.errText());

        assertTrue(log.marked && log.closed);
        assertTrue(say.outText().contains("cloud-snapshot: restored service 1181078 to the golden (1181078, "
                + "2026-10-22T01:20:00+00:00); 1 write(s) to 1 dataset(s), 0 still differ"), say.outText());
        assertEquals("cloud-snapshot: the Commander applied taxratecfg",
                say.outLines().get(say.outLines().size() - 1));
    }

    @Test
    void aRestoreTheCommanderNeverAppliesFails() {
        ((ObjectNode) fake.items("taxrates").get(0)).put("rate", 9.9999);
        Log log = new Log();
        Say.Recorded say = new Say.Recorded();
        assertFalse(cli("restore", new Wiring(cloud, log), say));
        assertEquals("cloud-snapshot: FAIL the Commander has not applied the restored taxratecfg: received nothing, "
                + "not applied taxratecfg", say.lastErr());
        assertTrue(log.closed);
    }

    @Test
    void aCommanderNotOnboardedIsNotWaitedFor() {
        ((ObjectNode) fake.items("taxrates").get(0)).put("rate", 9.9999);
        Say.Recorded say = new Say.Recorded();
        assertTrue(cli("restore", new Wiring(cloud, null), say));
        assertFalse(say.outText().contains("applied"), say.outText());
    }

    @Test
    void noSnapshotOrNoCloudFails() {
        Say.Recorded none = new Say.Recorded();
        assertFalse(CloudSnapshotCommand.cli("restore", null, "1", directory, directory.resolve("absent"), Map.of(),
                new Wiring(cloud, null), none));
        assertTrue(none.lastErr().startsWith("cloud-snapshot: FAIL no snapshot in "), none.errText());

        Say.Recorded unreachable = new Say.Recorded();
        assertFalse(cli("restore", new Wiring(null, null), unreachable));
        assertEquals("cloud-snapshot: FAIL restore not done: the Commander (onboarded to qat) names no base URL",
                unreachable.lastErr());
    }

    @Test
    void theCommanderIsMarkedOnlyWhenOnboarded() {
        Say.Recorded say = new Say.Recorded();
        Log log = new Log();
        assertNull(CloudSnapshotCommand.markCommander(() -> new CgiReplies.CloudConnectStatus(false, "not registered"),
                () -> log, say));
        assertFalse(log.marked);
        assertNull(CloudSnapshotCommand.markCommander(() -> {
            throw new DeviceError("LoginRequired");
        }, () -> log, say));
        assertEquals(List.of("cloud-snapshot: Commander Connect status unread: LoginRequired"), say.outLines());
        assertEquals(log, CloudSnapshotCommand.markCommander(() -> new CgiReplies.CloudConnectStatus(true, ""),
                () -> log, say));
        assertTrue(log.marked);
    }

    @Test
    void theCommandLineNeedsAnActionAndAServiceId() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        CloudSnapshotCommand command = new CloudSnapshotCommand((rig, env) -> null, new Wiring(cloud, null));
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"--rig", "lab", "--service-id", "1"}, Map.of(),
                o, e));
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"restore", "--rig", "lab"}, Map.of(), o, e));
        assertEquals(RigCommand.EXIT_USAGE, command.run(new String[] {"wipe", "--rig", "lab", "--service-id", "1"},
                Map.of(), o, e));
        assertEquals(RigCommand.EXIT_OK, command.run(new String[] {"diff", "--rig", "lab", "--service-id", "1181078",
            "--dir", directory.toString()}, Map.of(), o, e), err.toString(StandardCharsets.UTF_8));
    }
}
