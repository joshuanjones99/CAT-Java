package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.sims.FakeProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.Supervisor;

/**
 * Launch Simulators' panel asks every row's port at once, and each once per draw. The port probe is a stand-in: no
 * socket is opened, no process is started. Evidence about this repository only.
 */
class SimulatorsBoardTest {

    private static final int ROWS = 4;

    @TempDir
    Path tools;

    private SimulatorsService service() {
        List<SimEntry> rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            rows.add(SimEntry.exeRow("Row " + i).exe("missing-simulator.exe").match("cat-board-test-" + i)
                    .tcp(47000 + i).build());
        }
        return new SimulatorsService(new Supervisor(new FakeProcessOps(), Duration.ofMillis(300)),
                SimContext.of(tools, ""), rows, Runnable::run);
    }

    @Test
    void everyRowsPortIsAskedAtOnce() {
        // Each probe waits until every row's probe has started: asked one after another, the first would wait alone
        // until its bound and say so.
        CountDownLatch allAsking = new CountDownLatch(ROWS);
        AtomicBoolean waitedAlone = new AtomicBoolean();
        SimulatorsService sims = service().withPortProbe((entry, ctx) -> {
            allAsking.countDown();
            try {
                if (!allAsking.await(5, TimeUnit.SECONDS)) {
                    waitedAlone.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Supervisor.Probe(false, "");
        });

        SimulatorsService.Board board = sims.board();

        assertFalse(waitedAlone.get(), "a probe waited for the others: the ports were asked one after another");
        assertEquals(List.of("Row 0", "Row 1", "Row 2", "Row 3"), List.copyOf(board.rows().keySet()));
        board.rows().values().forEach(view -> assertEquals("stopped", view.state(), view.toString()));
        assertFalse(board.allTickedUp());
    }

    @Test
    void aDrawAsksEachPortOnceAndOffersStopWhenEveryTickedRowAnswers() {
        Map<String, AtomicInteger> asked = new ConcurrentHashMap<>();
        SimulatorsService sims = service().withPortProbe((entry, ctx) -> {
            asked.computeIfAbsent(entry.name(), name -> new AtomicInteger()).incrementAndGet();
            return new Supervisor.Probe(true, "localhost");
        });

        String panel = SimulatorsPage.panel(sims);

        assertEquals(ROWS, asked.size());
        asked.forEach((name, count) -> assertEquals(1, count.get(), name + " was asked " + count + " times"));
        // Start or Stop comes from the same answers as the rows: every ticked row is up, so Stop.
        assertTrue(panel.contains("hx-post=\"/simulators/stop\""), panel);
        assertFalse(panel.contains("hx-post=\"/simulators/start\""), panel);
        assertTrue(panel.contains("already running"), panel);
    }
}
