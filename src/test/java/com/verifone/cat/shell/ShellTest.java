package com.verifone.cat.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The shell's state, and the Python's {@code test_cat_web_autoclose.py} for {@link PageWatch}: no I/O, a fake clock.
 */
class ShellTest {

    private static long seconds(double s) {
        return (long) (s * 1_000_000_000L);
    }

    // ---- PageWatch (cat.web.autoclose) ------------------------------------------------------

    @Test
    void theFirstPageGetsTwoMinutesThenEveryLaterGapThirtySeconds() {
        PageWatch watch = new PageWatch(0);
        assertFalse(watch.shouldClose(seconds(119), 0, ""), "the browser is still starting");
        assertTrue(watch.shouldClose(seconds(120), 0, ""));

        PageWatch seen = new PageWatch(0);
        assertFalse(seen.shouldClose(seconds(5), 1, ""));
        assertTrue(seen.seenPage());
        assertFalse(seen.shouldClose(seconds(34), 0, ""));
        assertTrue(seen.shouldClose(seconds(35), 0, ""));
    }

    @Test
    void somethingHoldingCatKeepsItServingWithNoPageOpen() {
        PageWatch watch = new PageWatch(0);
        watch.shouldClose(seconds(1), 1, "");
        assertFalse(watch.shouldClose(seconds(500), 0, "a run"));
        assertFalse(watch.shouldClose(seconds(529), 0, ""), "the quiet time restarts when the run ends");
        assertTrue(watch.shouldClose(seconds(530), 0, ""));
    }

    // ---- RigBusy (CatService.busy) --------------------------------------------------------------

    @Test
    void oneActionAtATimeInThePythonsWords() {
        RigBusy busy = new RigBusy();
        assertEquals("", busy.begin(RigBusy.INSTALL));
        assertEquals("An install is already in progress.", busy.begin(RigBusy.SIMS_LAUNCH));
        busy.end(RigBusy.SIMS_LAUNCH);
        assertEquals(RigBusy.INSTALL, busy.current(), "ending what it does not hold changes nothing");
        busy.end(RigBusy.INSTALL);
        assertEquals("", busy.current());

        AtomicReference<String> run = new AtomicReference<>("run");
        busy.attachRun(run::get);
        assertEquals("A run is already in progress.", busy.begin(RigBusy.SIMS_STOP));
        assertEquals("", busy.own());
        run.set("");
        assertEquals("", busy.begin(RigBusy.SIMS_STOP));
        assertEquals("A simulator stop is already in progress.", busy.refusal());
    }

    // ---- Shell ----------------------------------------------------------------------------------

    @Test
    void aPageThatOpensLaterDoesNotReplayOldToastsAndOnlyFiftyAreKept() {
        Shell shell = new Shell();
        shell.notify("one");
        long opened = shell.lastToast();
        shell.notify("two", "error", 15);
        List<Shell.Toast> fresh = shell.toastsSince(opened);
        assertEquals(1, fresh.size());
        assertEquals("two", fresh.get(0).message());
        assertEquals("error", fresh.get(0).kind());
        for (int i = 0; i < 60; i++) {
            shell.notify("n" + i);
        }
        assertEquals(50, shell.toastsSince(0).size());
    }

    @Test
    void theLoadingLineAndTheSetupStatusInThePythonsWords() {
        Shell shell = new Shell();
        assertEquals("", shell.loadingLine());
        shell.loading("setup", "checking setup");
        shell.loading("tools", "downloading Java17 (runs wait for it)");
        assertEquals("Loading CAT — checking setup · downloading Java17 (runs wait for it)",
                shell.loadingLine());
        shell.doneLoading("setup");
        assertEquals("downloading Java17 (runs wait for it)", shell.loading("tools"));

        assertEquals("", Shell.setupStatusText(0, 0, 0));
        assertEquals("2 login(s) rejected: open Setup to fix", Shell.setupStatusText(3, 2, 2));
        assertEquals("Sign in on Setup · 3 setup item(s)", Shell.setupStatusText(3, 1, 0));
        assertEquals("Setup: 2 item(s) need attention", Shell.setupStatusText(2, 0, 0));
    }

    @Test
    void pagesAreOpenWhileTheyPollAndClosedWhenTheySaySo() {
        Shell shell = new Shell();
        shell.heartbeat("a", 0);
        shell.heartbeat("b", 0);
        assertEquals(2, shell.openPages(seconds(10)));
        shell.pageClosed("a");
        assertEquals(1, shell.openPages(seconds(10)));
        assertEquals(0, shell.openPages(seconds(10) + Shell.PAGE_ALIVE.toNanos()), "a page that stopped polling");
        shell.heartbeat("", 0);
        assertEquals(0, shell.openPages(1));
    }

    @Test
    void holdingNamesWhatKeepsCatServing() {
        Shell shell = new Shell();
        assertEquals("", shell.holding());
        shell.holdWhile(() -> "");
        shell.holdWhile(() -> "a scheduled run");
        assertEquals("a scheduled run", shell.holding());
        shell.busy().begin(RigBusy.INSTALL);
        assertEquals("an install", shell.holding());
        assertEquals("An install is in progress. Shutting down stops it.", shell.shutdownNote());

        Shell staging = new Shell();
        staging.toolsStaging(true);
        assertEquals("a download", staging.holding());
        assertEquals("Every open CAT page stops working until CAT is started again.", staging.shutdownNote());
    }

    @Test
    void everyShutdownHookRunsEvenWhenOneThrows() {
        Shell shell = new Shell();
        StringBuilder ran = new StringBuilder();
        shell.onShutdown(() -> {
            throw new IllegalStateException("gone");
        });
        shell.onShutdown(() -> ran.append("second"));
        shell.shutdown();
        assertEquals("second", ran.toString());
    }

    @Test
    void theAutoCloseWatchClosesOnceUnattended() throws Exception {
        Shell shell = new Shell();
        long[] now = {0};
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        StringBuilder said = new StringBuilder();
        now[0] = seconds(1000);
        try (AutoClose watch = AutoClose.watch(shell, () -> now[0] += seconds(200), said::append, closed::countDown)) {
            assertTrue(closed.await(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("no CAT page open and nothing running: closing CAT", said.toString());
        assertTrue(PageWatch.CHECK_EVERY.compareTo(Duration.ofSeconds(5)) == 0);
    }
}
