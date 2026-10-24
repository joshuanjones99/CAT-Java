package com.verifone.cat.pats.runner.handlers.cloudsettle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle.Pending;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Answer;
import com.verifone.cat.report.CaseRecord;

/**
 * The cloud-settle waits, driven through a fake Commander log and a loopback CGILink. The clock advances only when
 * the wait pauses, so no test waits real time. UNVERIFIED AGAINST HARDWARE.
 */
class CloudSettleTest {

    private static final String RECEIVED =
            "INFO CloudMessagePersistHandler - Received config update for the dataset - %s from cloud";
    private static final String APPLIED =
            "INFO CloudConfigUpdatesMgr - Successfully applied the %s config update, going to delete the file";
    /** The Commander's periodic re-sync, which says "applied" in other words: not a cloud write. */
    private static final String RESYNC = "INFO CoresvcsAgent - Successfully applied the configuration, going to delete "
            + "the file - /home/coresvcs/data/coresvcsagent/in/tipcfg-1.json";

    @TempDir
    Path folder;

    /** The Commander's log: each read returns the next text, the last repeating. */
    static final class FakeLog implements CommanderLog {
        final List<String> texts;
        final boolean fail;
        int reads;
        boolean marked;
        boolean closed;

        FakeLog(boolean fail, String... texts) {
            this.texts = List.of(texts);
            this.fail = fail;
        }

        @Override
        public void mark() {
            marked = true;
        }

        @Override
        public String linesSinceMark() {
            if (fail) {
                throw new CommanderLogException("ssh dropped");
            }
            String text = texts.get(Math.min(reads, texts.size() - 1));
            reads++;
            return text;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A clock that moves only when the wait pauses on it. */
    static final class Clock {
        long nanos;

        long now() {
            return nanos;
        }

        void pause(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private static CloudSettleContext context(Supplier<CommanderLog> log, Clock clock) {
        return new CloudSettleContext(log, CloudSettleContext.APPLY_BUDGET, Duration.ZERO, Duration.ofSeconds(5),
                clock::now, clock::pause);
    }

    private CaseRun run(PdkShim commander) {
        RunnableCase kase = new RunnableCase() {
            @Override
            public String key() {
                return "PETROCORE-1";
            }

            @Override
            public String title() {
                return "a test case";
            }

            @Override
            public String source() {
                return "memory";
            }

            @Override
            public List<RunnableAction> actions() {
                return List.of();
            }
        };
        FixtureSource fixtures = name -> {
            if (name == FixtureName.COMMANDER_CONFIG && commander != null) {
                return commander;
            }
            throw new IllegalStateException("no fixture " + name);
        };
        CaseRecord record = new CaseRecord("test::PETROCORE-1", "PETROCORE-1", folder, Instant.now());
        return new CaseRun(kase, record, new ActionRegistry(), fixtures, Set.of(FixtureName.COMMANDER_CONFIG),
                new RunState());
    }

    /** A loopback CGILink that answers the CloudConnect status as the Commander is, or faults. */
    private static LoopbackServer cgi(String mode) {
        return new LoopbackServer(seen -> {
            if (seen.body().contains("cmd=validate")) {
                return new Answer(200, "text/xml", "<credential><cookie>abc</cookie></credential>");
            }
            if (mode.equals("fault")) {
                return new Answer(500, "text/xml", "<fault/>");
            }
            return mode.equals("registered")
                    ? new Answer(200, "text/xml", "<VFI:Response><registrationTimeStamp>2023</registrationTimeStamp>"
                            + "</VFI:Response>")
                    : new Answer(200, "text/xml", "<VFI:Response><cloudConnectStatus code=\"9\">not onboarded"
                            + "</cloudConnectStatus></VFI:Response>");
        });
    }

    private static PdkShim pdk(LoopbackServer server) {
        return PdkShim.onPort(server.host(), server.port(), Duration.ofSeconds(5), null);
    }

    @Test
    void pendingPairsEachReceivedDatasetWithItsAppliedLine() {
        String text = String.join("\n", String.format(RECEIVED, "smartsuggestioncfg"), RESYNC,
                String.format(APPLIED, "smartsuggestioncfg"), String.format(RECEIVED, "tipcfg"));

        Pending pending = CloudSettle.pending(text);

        assertEquals(Set.of("smartsuggestioncfg", "tipcfg"), pending.received());
        assertEquals(Set.of("tipcfg"), pending.waiting());
        assertEquals(Set.of(), CloudSettle.pending(RESYNC).received());
    }

    @Test
    void aWriteWaitsUntilEveryDatasetReceivedIsAppliedAndThenQuiet() {
        String ds = "smartsuggestioncfg";
        FakeLog log = new FakeLog(false, "", String.format(RECEIVED, ds),
                String.format(RECEIVED, ds) + "\n" + String.format(APPLIED, ds));
        Clock clock = new Clock();
        CloudSettle settle = new CloudSettle(context(() -> log, clock));

        Optional<CommanderLog> mark = settle.mark(run(null), "POST");
        assertTrue(mark.isEmpty(), "no Commander onboarding in this run: no wait");
        assertDoesNotThrow(() -> settle.waitApplied(run(null), Optional.of(log), 200, "smartsuggestions/1"));

        // Two reads before it is whole, one to start QUIET, one to end it, one for the received list.
        assertEquals(5, log.reads);
        assertTrue(log.closed);
    }

    @Test
    void aWriteTheCloudNeverPushesGoesOnWithoutFailing() {
        FakeLog log = new FakeLog(false, String.format(RECEIVED, "tipcfg"));
        Clock clock = new Clock();
        CloudSettle settle = new CloudSettle(context(() -> log, clock));

        assertDoesNotThrow(() -> settle.waitApplied(run(null), Optional.of(log), 200, "tips/1"));

        assertTrue(log.closed);
        assertTrue(clock.nanos >= CloudSettleContext.APPLY_BUDGET.toNanos());
    }

    @Test
    void aRefusedWriteIsNotWaitedFor() {
        for (int status : new int[] {0, 400, 500}) {
            FakeLog log = new FakeLog(false, "");
            CloudSettle settle = new CloudSettle(context(() -> log, new Clock()));

            settle.waitApplied(run(null), Optional.of(log), status, "tips/1");

            assertEquals(0, log.reads, "status " + status);
            assertTrue(log.closed);
        }
    }

    @Test
    void aLogThatCannotBeReadStopsTheWaitWithoutFailingTheStep() {
        FakeLog log = new FakeLog(true, "");
        CloudSettle settle = new CloudSettle(context(() -> log, new Clock()));

        assertDoesNotThrow(() -> settle.waitApplied(run(null), Optional.of(log), 200, "tips/1"));
        assertTrue(log.closed);
    }

    @Test
    void aCommanderNotOnboardedTakesNoMarkAndOpensNoLog() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        try (LoopbackServer server = cgi("unregistered")) {
            CloudSettle settle = new CloudSettle(context(() -> {
                opened.incrementAndGet();
                return new FakeLog(false, "");
            }, new Clock()));
            CaseRun run = run(pdk(server));

            assertTrue(settle.mark(run, "PUT").isEmpty());
            assertTrue(settle.mark(run, "GET").isEmpty());
            assertEquals(0, opened.get());
        }
    }

    @Test
    void aCommanderThatCannotSayWhetherItIsOnboardedIsNotWaitedOn() throws Exception {
        try (LoopbackServer server = cgi("fault")) {
            CloudSettle settle = new CloudSettle(context(() -> new FakeLog(false, ""), new Clock()));

            assertTrue(settle.mark(run(pdk(server)), "POST").isEmpty());
        }
    }

    @Test
    void anOnboardedWriteMarksTheCommanderLogAndTheReleaseCloses() throws Exception {
        FakeLog log = new FakeLog(false, "");
        try (LoopbackServer server = cgi("registered")) {
            CloudSettle settle = new CloudSettle(context(() -> log, new Clock()));

            Optional<CommanderLog> mark = settle.mark(run(pdk(server)), "post");

            assertTrue(mark.isPresent());
            assertTrue(log.marked);
            settle.release(mark);
            assertTrue(log.closed);
        }
    }

    @Test
    void aWriteWithNoLogReaderWiredIsNotWaitedFor() throws Exception {
        try (LoopbackServer server = cgi("registered")) {
            CloudSettle settle = new CloudSettle(context(() -> null, new Clock()));

            assertTrue(settle.mark(run(pdk(server)), "POST").isEmpty());
        }
    }

    @Test
    void theDisabledSettleNeverWaits() {
        assertTrue(CloudSettle.disabled().mark(run(null), "POST").isEmpty());
    }

    @Test
    void onlyWritesAreWaitedFor() {
        assertFalse(CloudSettle.isWrite("GET"));
        assertFalse(CloudSettle.isWrite("head"));
        assertFalse(CloudSettle.isWrite(""));
        assertTrue(CloudSettle.isWrite("post"));
        assertTrue(CloudSettle.isWrite(" DELETE "));
    }
}
