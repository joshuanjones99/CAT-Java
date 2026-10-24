package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report actions, key for key as the Python's tests pin them (tests/unit/test_pats_ported_actions.py and
 * test_pats_runner.py), on a fake register and a fake clock.
 */
class ReportActionsTest {

    @TempDir
    Path folder;

    private TestClock clock;
    private FakeRegister register;
    private ConfigurationContext context;
    private StuckCloseRecovery recovery = StuckCloseRecovery.UNBOUND;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        register = new FakeRegister(clock);
    }

    /** A run over the context as the test has set it up by now (its recovery may be replaced first). */
    private CaseRun run(String key, RunnableAction... actions) throws Exception {
        context = ConfigFixtures.context(folder, clock, register, recovery, CloudSender.UNBOUND);
        return ConfigFixtures.caseRun(context, ConfigFixtures.noFixtures(), kase(key, actions), Set.of());
    }

    private static RunnableAction flash() {
        return act(0, "PRINT_FLASH_REPORT", "MAIN MENU NO", "1", "FUNCTIONS MENU NO", "20",
                "FLASH REPORTS MENU NO", "0");
    }

    private static RunnableAction close(String reportType) {
        return act(0, "CLOSE_REPORT", "REPORT TYPE", reportType, "CLOSE REPORT NO", "6", "MAIN MENU NO", "1",
                "CASHIERNUM", "3", "DEVICE", "POS");
    }

    private static RunnableAction network(String... fields) {
        String[] all = new String[fields.length + 4];
        System.arraycopy(fields, 0, all, 0, fields.length);
        all[fields.length] = "MAIN_MENU_NO";
        all[fields.length + 1] = "2";
        all[fields.length + 2] = "NETWORK_MENU_NO";
        all[fields.length + 3] = "4";
        return act(0, "NETWORK_REPORT", all);
    }

    // -- PRINT_FLASH_REPORT ---------------------------------------------------------------------

    @Test
    void aFlashReportKeysItsThreeMenusOnceThePosOffersItsFunctions() throws Exception {
        CaseRun run = run("PETROCORE-30", flash());

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:20", "ENTER:0", "EXIT", "EXIT", "EXIT"), register.keys);
        assertTrue(register.notes.contains("overlayPause"));
    }

    @Test
    void aFlashReportWithThePosNeverReadyIsAHardFailureAndKeysNothing() throws Exception {
        register.express = List.of("Error Correct");
        CaseRun run = run("PETROCORE-31", flash());

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("Recall, CSR Func or Price Check"), error.getMessage());
        assertEquals(List.of(), register.keys);
    }

    // -- NETWORK_REPORT -------------------------------------------------------------------------

    @Test
    void aSecureNetworkReportWithNoUserSendsABareEnterForTheUser() throws Exception {
        CaseRun run = run("PETROCORE-32", network("IS_SECURE_REPORT", "Y", "SECURE_PASSWORD", "1",
                "REPORT_NO", "1000"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:2", "ENTER:4", "ENTER", "ENTER", "ENTER:1", "ENTER:1000", "PRINT_REPORT"),
                register.keys);
    }

    @Test
    void aNetworkReportCarriesAZeroAsPatsPendingTokenDoes() throws Exception {
        CaseRun run = run("PETROCORE-33", network("IS_SECURE_REPORT", "N", "REPORT_NO", "1", "DAY_SEQUENCE", "0"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:2", "ENTER:4", "ENTER:1", "ENTER:0", "PRINT_REPORT"), register.keys);
    }

    @Test
    void anEmptyNetworkMenuNoIsTheBareEnterPatsPendingTokenSends() throws Exception {
        CaseRun run = run("PETROCORE-34", act(0, "NETWORK_REPORT", "MAIN_MENU_NO", "1", "NETWORK_MENU_NO", "",
                "REPORT_NO", "11", "SUB_REPORT_NO", "1", "IS_SECURE_REPORT", "N", "SECURE_USER", "",
                "SECURE_PASSWORD", "", "DAY_SEQUENCE", "", "BATCH_NUMBER", ""));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER", "ENTER:11", "ENTER:1", "PRINT_REPORT", "EXIT"), register.keys);
    }

    @Test
    void aReportStillInAMenuAfterFourExitsIsBackedOutToReady() throws Exception {
        // Run fe27ab66557f: the report's view was still one menu deep. Ready only once five EXITs have gone.
        register.readyNow = () -> register.keys.stream().filter("EXIT"::equals).count() >= 5;
        CaseRun run = run("PETROCORE-35", network("IS_SECURE_REPORT", "N", "REPORT_NO", "18", "DAY_SEQUENCE", "1"));

        run.run();

        int printAt = register.keys.indexOf("PRINT_REPORT");
        assertEquals(List.of("EXIT", "EXIT", "EXIT", "EXIT"), register.keys.subList(printAt + 1, register.keys.size()));
    }

    @Test
    void aReportWithThePosReadyAfterItsViewIsNotBackedOut() throws Exception {
        CaseRun run = run("PETROCORE-36", network("IS_SECURE_REPORT", "N", "REPORT_NO", "1"));

        run.run();

        assertEquals("PRINT_REPORT", register.keys.get(register.keys.size() - 1));
    }

    // -- CLOSE_REPORT ---------------------------------------------------------------------------

    @Test
    void aPeriodCloseKeysTheMenusConfirmsAndRecoversTheRegisterWithoutVoiding() throws Exception {
        CaseRun run = run("PETROCORE-40", close("period"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:6", "ENTER", "ENTER", "EXIT", "EXIT", "EXIT", "ENTER",
                "ENTER:10000"), register.keys);
    }

    @Test
    void aCloseFromARegisterNotReadyVoidsItsTicketFirst() throws Exception {
        register.readyNow = () -> false;
        CaseRun run = run("PETROCORE-41", close("period"));

        run.run();

        assertEquals(List.of("VOID_TICKET", "EXIT"), register.keys.subList(0, 2));
    }

    @Test
    void aCashierCloseWaitsForCompletedSuccessfullyThenPrintsIt() throws Exception {
        register.otherOverlay = Optional.of(Map.of("TITLE", "Close Cashier", "LINE2", "Completed Successfully"));
        CaseRun run = run("PETROCORE-42", close("Cashier"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:6", "ENTER:3", "ENTER", "ENTER", "ENTER:2", "ENTER:3", "ENTER",
                "EXIT", "EXIT", "ENTER", "ENTER:10000"), register.keys);
    }

    @Test
    void aCashierCloseThatNeverCompletesIsAHardFailure() throws Exception {
        register.otherOverlay = Optional.of(Map.of("TITLE", "Close Cashier", "LINE2", "In Progress"));
        CaseRun run = run("PETROCORE-43", close("Cashier"));

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("the close to complete"), error.getMessage());
    }

    @Test
    void aPeriodCloseKeysNothingIntoTheClosingCashiersMessage() throws Exception {
        register.closingFor = Duration.ofMillis(500);
        CaseRun run = run("PETROCORE-44", close("period"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:6", "ENTER", "ENTER", "EXIT", "EXIT", "EXIT", "ENTER",
                "ENTER:10000"), register.keys);
        assertEquals(List.of(false, false, false, false, false, false, false, false, false, false),
                register.messageUpAtKey);
    }

    @Test
    void aPeriodCloseStillClosingCashiersIsAHardFailureNamingIt() throws Exception {
        register.closingFor = FakeRegister.FOREVER;
        CaseRun run = run("PETROCORE-45", close("period"));

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("never finished: the register still showed \"Closing Cashiers\""),
                error.getMessage());
        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:6", "ENTER", "ENTER"), register.keys);
    }

    @Test
    void aCloseOnADeadlockedCoresvcsIsClearedBeforeItFailsAsAProductDefect() throws Exception {
        register.closingFor = FakeRegister.FOREVER;
        recovery = new StuckCloseRecovery() {
            @Override
            public boolean restartIfDeadlocked() {
                register.clearClosing();
                return true;
            }

            @Override
            public Optional<Path> keptDump() {
                return Optional.of(folder.resolve("jstack.txt"));
            }
        };
        CaseRun run = run("PETROCORE-46", close("period"));

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("PRODUCT DEFECT: the period close deadlocked"), error.getMessage());
        assertTrue(error.getMessage().contains("thread dump: "), error.getMessage());
        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:6", "ENTER", "ENTER"), register.keys);
    }

    @Test
    void aRecoveryThatCannotCheckCoresvcsLeavesTheCloseAsNeverFinished() throws Exception {
        register.closingFor = FakeRegister.FOREVER;
        recovery = new StuckCloseRecovery() {
            @Override
            public boolean restartIfDeadlocked() throws Exception {
                throw new Exception("no ssh");
            }

            @Override
            public Optional<Path> keptDump() {
                return Optional.empty();
            }
        };
        CaseRun run = run("PETROCORE-47", close("period"));

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("never finished"), error.getMessage());
        assertFalse(error.getMessage().contains("PRODUCT DEFECT"));
    }

    @Test
    void theCloseWaitsItsClosingMessageOutOnTheClockAndNotInRealTime() throws Exception {
        register.closingFor = Duration.ofSeconds(60);
        CaseRun run = run("PETROCORE-48", close("period"));
        long started = clock.nanos();

        run.run();

        assertTrue(clock.nanos() - started >= Duration.ofSeconds(60).toNanos());
        assertEquals(10, register.keys.size());
    }
}
