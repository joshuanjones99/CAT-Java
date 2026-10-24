package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loopback only. The watch reports changes, warns on a drop, and survives a failing listener. */
class ConnectionWatchTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ExecutorService pool;
    private ScheduledExecutorService scheduler;
    private ServerSocket listener;
    private final List<ProbeResult> emitted = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        pool = ConnectionProbe.newProbePool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        listener = new ServerSocket(0, 50, ConnectionProbeTest.loopback());
    }

    @AfterEach
    void tearDown() throws IOException {
        listener.close();
        scheduler.shutdownNow();
        pool.shutdownNow();
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ConnectionWatch watch() {
        Target commander = Target.tcp("Commander", ConnectionProbeTest.loopbackHost(), listener.getLocalPort());
        return new ConnectionWatch(new ConnectionProbe(pool), List.of(commander), TIMEOUT,
                emitted::add, warnings::add);
    }

    @Test
    void emitsTheFirstResultThenOnlyChanges() throws IOException {
        ConnectionWatch watch = watch();

        watch.round();
        watch.round();
        assertEquals(1, emitted.size());
        assertEquals(ProbeState.UP, emitted.get(0).state());
        assertTrue(warnings.isEmpty());

        listener.close();
        watch.round();

        assertEquals(2, emitted.size());
        assertEquals(ProbeState.DOWN, emitted.get(1).state());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("Commander was up and is now down: "), warnings.get(0));
    }

    @Test
    void aTargetThatWasNeverUpDoesNotWarn() throws IOException {
        listener.close();
        ConnectionWatch watch = watch();

        watch.round();

        assertEquals(1, emitted.size());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aFailingListenerIsReportedAndDoesNotEndTheSchedule() throws InterruptedException {
        CountDownLatch rounds = new CountDownLatch(2);
        Target commander = Target.tcp("Commander", ConnectionProbeTest.loopbackHost(), listener.getLocalPort());
        List<String> failures = new ArrayList<>();
        ConnectionWatch watch = new ConnectionWatch(new ConnectionProbe(pool), List.of(commander), TIMEOUT,
                result -> {
                    rounds.countDown();
                    if (rounds.getCount() == 1) {
                        // The second round must see a change, or it would emit nothing to fail on.
                        closeQuietly(listener);
                    }
                    throw new IllegalStateException("listener broke");
                },
                failures::add);

        ScheduledFuture<?> handle = watch.start(scheduler, Duration.ofMillis(10));

        assertTrue(rounds.await(10, TimeUnit.SECONDS), "the watch stopped after its first failure");
        handle.cancel(false);
        assertTrue(failures.stream().anyMatch(line -> line.contains("listener broke")), failures.toString());
    }
}
