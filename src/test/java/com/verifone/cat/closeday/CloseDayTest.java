package com.verifone.cat.closeday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.closeday.CloseDayFakes.Access;
import com.verifone.cat.closeday.CloseDayFakes.Box;
import com.verifone.cat.closeday.CloseDayFakes.Capture;
import com.verifone.cat.devices.commander.CommanderFault;

/**
 * The day close against the in-memory Commander: the outcome read from the log, the forced-AEOD undo and
 * naming, the stuck-close recovery and the retry after a coresvcs restart. A port of the register-independent
 * cases of the Python's {@code tests/unit/test_close_day.py}. The register pass is a recorded no-op here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Evidence about this code, not about a Commander.
 */
class CloseDayTest {

    private static final String INTERNAL = "CGIPortal.InternalError";
    private static final String IN_PROGRESS_LINE = CommanderLog.IN_PROGRESS;

    @TempDir
    Path dir;

    private Path base() throws Exception {
        Path base = dir.resolve("base");
        Files.createDirectories(base);
        Files.writeString(base.resolve(CloseDay.BUYPASS_FEP_FILE), "<buypass brand=\"BUYPASS_GENERIC\"/>",
                StandardCharsets.UTF_8);
        return base;
    }

    private static Path results(Path dir) {
        return dir.resolve("results");
    }

    /** A close whose log before the mark is {@code before} and whose reads after {@code cclosepdnow} bring {@code after}. */
    private static Box box(List<String> before, List<String> after) {
        return new Box(before, after);
    }

    // ---- the register pass and the forced AEOD -------------------------------------------------

    @Test
    void theRegisterIsCheckedBeforeTheCloseIsSent() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Capture out = new Capture();
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), out, err).close());
        assertTrue(box.calls.indexOf("clear") < box.calls.indexOf("cclosepdnow"), box.calls.toString());
    }

    @Test
    void aForcedAeodIsUndoneByReimportingOnlyBaseBuypass() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);
        access.cgi.refinteg = "<reportConfig><aeod><forceAEOD>true</forceAEOD></aeod></reportConfig>";
        access.cgi.importClearsForce = true;
        Path base = base();
        Capture out = new Capture();
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(access, base, results(dir), out, err).close());
        assertEquals(List.of(List.of(CloseDay.BUYPASS_FEP_FILE)), access.cgi.importedNames);
        assertEquals(List.of("<buypass brand=\"BUYPASS_GENERIC\"/>"), access.cgi.importedContent);
        assertTrue(box.calls.contains("refresh"), box.calls.toString());
        assertTrue(out.text().contains("forced AEOD undone: refinteg forceAEOD=false"), out.text());
    }

    @Test
    void noForcedAeodImportsNothing() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);

        CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), new Capture()).close();

        assertFalse(box.calls.contains("import"), box.calls.toString());
    }

    @Test
    void aForcedAeodThatStaysIsReported() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);
        access.cgi.refinteg = "<reportConfig><aeod><forceAEOD>true</forceAEOD></aeod></reportConfig>";
        Capture err = new Capture();

        CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), err).close();

        assertTrue(err.text().contains("the Commander still forces automatic end of day after the base Buypass import"),
                err.text());
    }

    @Test
    void aFailedUndoIsReportedAndTheCloseIsStillSent() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);
        access.cgi.refinteg = "<reportConfig><aeod><forceAEOD>true</forceAEOD></aeod></reportConfig>";
        Capture err = new Capture();

        // No base folder: the Buypass file cannot be copied.
        CloseDay.Outcome outcome = CloseDayFakes.closeDay(access, dir.resolve("no-base"), results(dir),
                new Capture(), err).close();

        assertTrue(err.text().contains("could not undo the forced AEOD"), err.text());
        assertTrue(box.calls.contains("cclosepdnow"), box.calls.toString());
        assertEquals(CloseDay.Outcome.CLOSED, outcome);
    }

    @Test
    void aRefusedCloseOnAForcedAeodIsNamedAndExitsFour() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of());
        Access access = new Access(box);
        access.cgi.refinteg = "<reportConfig><aeod><forceAEOD>true</forceAEOD></aeod></reportConfig>";
        access.cgi.buypass = "<fepParams><brandSelection>EXXONMOBIL</brandSelection></fepParams>";
        access.cgi.closeActions.add(() -> {
            throw new CommanderFault(INTERNAL);
        });
        Capture err = new Capture();

        CloseDay.Outcome outcome = CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), err).close();

        assertEquals(CloseDay.Outcome.FORCED_AEOD, outcome);
        assertEquals(4, outcome.exitCode());
        assertTrue(err.text().contains("forces automatic end of day"), err.text());
        assertTrue(err.text().contains("EXXONMOBIL"), err.text());
    }

    // ---- the outcome is read from the log ----------------------------------------------------

    @Test
    void aCompletedCloseIsASuccessAndIsPolledFor() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        box.delayReads = 3;
        Capture out = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), out, new Capture()).close());
        assertTrue(out.text().contains("closed the period on " + CloseDayFakes.HOST + ": " + CloseDayFakes.IDLE),
                out.text());
    }

    @Test
    void aCancelledCloseIsAFailure() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING, CloseDayFakes.CANCELED));
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertEquals("close day on " + CloseDayFakes.HOST + " was CANCELLED by the Commander: "
                + CloseDayFakes.CANCELED, err.lastLine());
    }

    @Test
    void aCloseAServiceCancelledIsAFailureThoughItReachesIdle() throws Exception {
        String vetoed = "15:26:28.981 LockServicesState [PeriodCloseStateThread] - "
                + "PERIOD_CLOSE_CANCELED_BYSales Service";
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING, vetoed, CloseDayFakes.IDLE));
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertTrue(err.text().contains("was CANCELLED by the Commander: " + vetoed), err.text());
    }

    @Test
    void noOutcomeWithinTheBudgetIsAFailure() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING));
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertTrue(err.text().contains("no outcome within 1s"), err.text());
    }

    @Test
    void anEarlierClosesSuccessDoesNotCount() throws Exception {
        Box box = box(List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE, CloseDayFakes.UNRELATED),
                List.of(CloseDayFakes.LOCKING));
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertTrue(err.text().contains("no outcome within"), err.text());
    }

    @Test
    void anEarlierClosesCancelDoesNotCount() throws Exception {
        Box box = box(List.of(CloseDayFakes.LOCKING, CloseDayFakes.CANCELED),
                List.of(CloseDayFakes.LOCKING, CloseDayFakes.ACKED, CloseDayFakes.IDLE));

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), new Capture()).close());
    }

    @Test
    void aLogRotatedMidWaitIsReadFromItsStart() throws Exception {
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            before.add(CloseDayFakes.UNRELATED);
        }
        Box box = box(before, List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        box.rotate = true;

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), new Capture()).close());
    }

    @Test
    void aLogGoneForAMomentIsReadAgain() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        box.delayReads = 2;
        box.missingReads = 1;

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), new Capture()).close());
    }

    @Test
    void anUnreadableLogStillSendsTheCloseAndFails() throws Exception {
        Box box = box(List.of(), List.of(CloseDayFakes.IDLE));
        box.sshDown = true;
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertTrue(box.calls.contains("cclosepdnow"), box.calls.toString());
        assertTrue(err.lastLine().contains("outcome unknown"), err.text());
    }

    // ---- the stuck close ------------------------------------------------------------------------

    @Test
    void aStuckCloseIsRecoveredOnceThenClosed() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);
        access.cgi.closeActions.add(() -> {
            box.lines.add(IN_PROGRESS_LINE);
            throw new CommanderFault(INTERNAL);
        });
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), err).close());
        assertEquals(1, box.restarts, "coresvcs restarted once");
        assertEquals(2, box.calls.stream().filter("cclosepdnow"::equals).count(), box.calls.toString());
        assertTrue(err.text().contains("restarting coresvcs to clear the stuck period close"), err.text());
    }

    @Test
    void aCloseThatDeadlocksItselfIsRestartedThenCloses() throws Exception {
        // Run 3c866212c74e (2026-10-15): every client ACKed, no outcome, and the dump names the deadlock.
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING));
        box.jstack = "Found one Java-level deadlock:\n\"PeriodCloseStateThread\":\n  waiting to lock monitor 0x1\n";
        // The restart is what brings the next close's outcome, as coresvcs does once it is back.
        box.onRestart = () -> box.afterClose.addAll(List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertEquals(1, box.restarts);
        assertTrue(err.text().contains("the stuck close is a Java-level deadlock in coresvcs between "
                + "'PeriodCloseStateThread'"), err.text());
    }

    @Test
    void aSecondCloseWithNoOutcomeIsNotRecoveredAgain() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING));
        box.jstack = "Found one Java-level deadlock:\n\"PeriodCloseStateThread\":\n  waiting to lock monitor 0x1\n";
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), err).close());
        assertEquals(1, box.restarts, "the first close's dump restarted coresvcs once");
        assertEquals(2, box.calls.stream().filter("cclosepdnow"::equals).count(), box.calls.toString());
    }

    @Test
    void anotherFaultIsNotRecovered() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of());
        Access access = new Access(box);
        access.cgi.closeActions.add(() -> {
            throw new CommanderFault("CGIPortal.LoginRequired");
        });
        Capture err = new Capture();

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), err).close());
        assertEquals(0, box.restarts);
        assertTrue(err.text().contains("CGIPortal.LoginRequired"), err.text());
    }

    @Test
    void theRecoveryNamesTheDeadlockRestartsCoresvcsAndKeepsTheDump() throws Exception {
        String dump = "Found one Java-level deadlock:\n=============================\n"
                + "\"PeriodCloseStateThread\":\n  waiting to lock monitor 0x1 (object 0x2, a "
                + "com.empress.jdbc.EmpressConnection),\n  which is held by \"RMI TCP Connection(103)-topaz101.test\"\n\n"
                + "\"RMI TCP Connection(103)-topaz101.test\":\n"
                + "  waiting to lock monitor 0x3 (object 0x4, a com.empress.jdbc.EmpressPreparedStatement)\n";
        Box box = box(List.of(), List.of());
        box.jstack = dump;
        Capture err = new Capture();
        Path results = results(dir);

        CloseDay close = CloseDayFakes.closeDay(new Access(box), base(), results, new Capture(), err);
        assertTrue(close.recoverStuckClose(false));

        assertTrue(err.text().contains("'PeriodCloseStateThread' and 'RMI TCP Connection(103)-topaz101.test'"),
                err.text());
        assertEquals(1, box.restarts);
        assertTrue(box.commands.contains(CloseDay.CORESVCS_JSTACK), box.commands.toString());
        Path kept = close.keptDump();
        assertNotNull(kept, "the dump is the defect's evidence");
        assertTrue(kept.startsWith(results.resolve("defect-evidence")), kept.toString());
        assertEquals(dump, Files.readString(kept, StandardCharsets.UTF_8));
    }

    @Test
    void aSlowCloseIsNeverRestartedUnderWhenItsDumpNamesNoDeadlock() throws Exception {
        Box box = box(List.of(), List.of());
        box.jstack = "\"main\":\n  at java.lang.Thread.run\n";
        CloseDay close = CloseDayFakes.closeDay(new Access(box), base(), results(dir), new Capture(), new Capture());

        assertFalse(close.recoverStuckClose(true));
        assertEquals(0, box.restarts);
    }

    // ---- the retry after a restart ----------------------------------------------------------------

    @Test
    void aCloseThatFaultsWhileCoresvcsStartsIsSentAgain() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Access access = new Access(box);
        access.cgi.closeActions.add(() -> {
            box.lines.add(IN_PROGRESS_LINE);
            throw new CommanderFault(INTERNAL);
        });
        access.cgi.closeActions.add(() -> {
            throw new CommanderFault(INTERNAL);  // coresvcs's close manager is not up yet
        });
        Capture out = new Capture();

        assertEquals(CloseDay.Outcome.CLOSED,
                CloseDayFakes.closeDay(access, base(), results(dir), out, new Capture()).close());
        assertTrue(out.text().contains("sending it again"), out.text());
        assertEquals(3, box.calls.stream().filter("cclosepdnow"::equals).count(), box.calls.toString());
    }

    @Test
    void closeAlreadyInProgressAfterTheRestartIsNotSentAgain() throws Exception {
        Box box = box(List.of(CloseDayFakes.UNRELATED), List.of());
        Access access = new Access(box);
        access.cgi.closeActions.add(() -> {
            box.lines.add(IN_PROGRESS_LINE);
            throw new CommanderFault(INTERNAL);
        });
        access.cgi.closeActions.add(() -> {
            box.lines.add(IN_PROGRESS_LINE);  // the deadlock is back
            throw new CommanderFault(INTERNAL);
        });

        assertEquals(CloseDay.Outcome.FAILED,
                CloseDayFakes.closeDay(access, base(), results(dir), new Capture(), new Capture()).close());
        assertEquals(2, box.calls.stream().filter("cclosepdnow"::equals).count(), box.calls.toString());
        assertEquals(1, box.restarts);
    }
}
