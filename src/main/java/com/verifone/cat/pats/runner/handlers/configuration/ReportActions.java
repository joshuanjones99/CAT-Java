package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.configuration.ActionFields.nullIfEmpty;
import static com.verifone.cat.pats.runner.handlers.configuration.ActionFields.require;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The report actions: {@code NETWORK_REPORT}, {@code PRINT_FLASH_REPORT} and {@code CLOSE_REPORT}, key for key as
 * the Python's {@code configuration.py} sends them, with the waits it takes between them.
 *
 * <p>Key order is PATS's. Where the Python paces a key by the screen (its {@code settle}), so does this, through
 * {@link RegisterPos#settle}. Each Python poll is a bounded poll here, against the same ceiling: a poll that times
 * out is a log line and the key goes anyway, or a hard failure that names what was not seen, as the Python's is.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the flash and close reports were ported from the deployed {@code Automn.jar} and
 * have never driven a register (the Python's own note).
 */
final class ReportActions {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** PATS's wait for Ready before each EXIT that backs out of a report. */
    static final Duration BACK_OUT_WAIT = Duration.ofSeconds(2);
    /** The wait for a built report's view, whose express bar offers "Print Report". */
    static final Duration REPORT_VIEW_WAIT = Duration.ofSeconds(30);
    /** The ceiling of a state wait (the Python's {@code STATE_WAIT}), before the case's carried sleeps are added. */
    static final Duration STATE_WAIT = Duration.ofSeconds(117);
    /** How long a close reads the register's Ready state before it presses anything (the Python's READY_READ_WAIT). */
    static final Duration READY_READ_WAIT = Duration.ofSeconds(3);
    /** {@code Utility.FUEL_ADAPTER_RECOVER_INTERVAL}: the pause after each key of a close's recovery. */
    static final Duration CLOSE_RECOVER_PAUSE = Duration.ofSeconds(25);
    /** The amount a close's recovery keys last ({@code topaz.recover.open.drawer.amount}). */
    static final String RECOVER_OPEN_DRAWER_AMOUNT = "10000";
    /** The close's cashier branch waits for its message's line 2 to read this. */
    static final String CLOSE_COMPLETED = "completed successfully";
    /** The label a report's view offers, which the network report waits for. */
    static final String PRINT_REPORT_LABEL = "Print Report";

    /** The default poll interval (the Python's {@code poll_until} default). */
    static final Duration POLL = Duration.ofMillis(250);
    static final Duration REPORT_VIEW_POLL = Duration.ofMillis(200);
    static final Duration CLOSING_POLL = Duration.ofMillis(500);

    private final ConfigurationContext context;

    ReportActions(ConfigurationContext context) {
        this.context = context;
    }

    // -- NETWORK_REPORT -------------------------------------------------------------------------

    /**
     * {@code POSDevice.networkReport}, key for key. Every value goes as the pending token PATS sends, so a "0" is
     * carried and an empty field is a bare ENTER. A secure report with no user sends a bare ENTER for the user.
     */
    void networkReport(CaseRun run, RunnableAction action) {
        RegisterPos pos = context.register();
        pos.sweep();
        press(pos, pos::exitKey);
        String mainMenu = require(action, "MAIN_MENU_NO");
        press(pos, () -> pos.pendingEnter(mainMenu));
        // 17 TAX cases carry NETWORK_MENU_NO="": PATS's pendingToken("ENTER", "") is a bare ENTER.
        String networkMenu = require(action, "NETWORK_MENU_NO");
        press(pos, () -> pos.pendingEnter(networkMenu));
        if ("Y".equals(action.get("IS_SECURE_REPORT").toUpperCase(Locale.ROOT))) {
            press(pos, pos::pressEnter);
            String user = nullIfEmpty(action.get("SECURE_USER"));
            press(pos, () -> pos.pendingEnter(user));
            String password = nullIfEmpty(action.get("SECURE_PASSWORD"));
            press(pos, () -> pos.pendingEnter(password));
        }
        String reportNo = require(action, "REPORT_NO");
        press(pos, () -> pos.pendingEnter(reportNo));
        String subReport = action.get("SUB_REPORT_NO").strip();
        for (String value : List.of(subReport, action.get("DAY_SEQUENCE").strip(),
                action.get("BATCH_NUMBER").strip())) {
            if (!value.isEmpty()) {
                press(pos, () -> pos.pendingEnter(value));
            }
        }
        awaitReportView();
        pos.printReport();
        if (!subReport.isEmpty()) {
            pos.exitKey();
        }
        backOut(run);
    }

    /** Waits for the built report's view; at {@link #REPORT_VIEW_WAIT} PRINT_REPORT goes anyway, as PATS's does. */
    private void awaitReportView() {
        boolean shown = context.waits().pollUntil(
                () -> context.register().screenText().contains(PRINT_REPORT_LABEL),
                REPORT_VIEW_WAIT, REPORT_VIEW_POLL);
        if (!shown) {
            LOG.warn("no report view; pressing PRINT_REPORT anyway");
        }
    }

    /**
     * {@code app.platform=Commander}: back out, up to four EXITs, as PATS does; then up to four more, each only while
     * the POS still is not Ready (CAT's own, from run {@code fe27ab66557f}).
     */
    private void backOut(CaseRun run) {
        RegisterPos pos = context.register();
        for (int attempt = 0; attempt < 8; attempt++) {
            boolean ready = context.waits().pollUntil(pos::ready, BACK_OUT_WAIT, POLL);
            if (!ready) {
                if (attempt >= 4) {
                    LOG.warn("{}: not Ready after PATS's four EXITs; EXIT again", run.kase().key());
                }
                pos.exitKey();
            } else if (attempt >= 4) {
                return;
            }
        }
    }

    // -- PRINT_FLASH_REPORT ---------------------------------------------------------------------

    /**
     * {@code POSDevice.printFlashReport}: once the express panel offers its functions, the overlay check, then the
     * seven keys of {@code LegacyRmi.print_flash_report} back to back: EXIT, the three menus as ENTERs, and three
     * EXITs to back out of the report view. The keys are sent unpaced, as PATS sends them.
     */
    void flashReport(CaseRun run, RunnableAction action) {
        String mainMenu = require(action, "MAIN MENU NO");
        String functionsMenu = require(action, "FUNCTIONS MENU NO");
        String flashMenu = require(action, "FLASH REPORTS MENU NO");
        RegisterPos pos = context.register();
        pos.sweep();
        Duration wait = budget(run, STATE_WAIT);
        boolean offered = context.waits().pollUntil(() -> offersFunctions(pos, pos.expressLabels()), wait, POLL);
        if (!offered) {
            throw new PatsStepError("the POS did not offer its functions within " + seconds(wait)
                    + " (an express button reading Recall, CSR Func or Price Check); the express panel reads "
                    + pos.expressLabels());
        }
        pos.overlayPause();
        pos.exitKey();
        pos.pendingEnter(mainMenu);
        pos.pendingEnter(functionsMenu);
        pos.pendingEnter(flashMenu);
        pos.exitKey();
        pos.exitKey();
        pos.exitKey();
    }

    private static boolean offersFunctions(RegisterPos pos, List<String> labels) {
        Set<String> wanted = pos.functionLabels();
        return labels.stream().anyMatch(wanted::contains);
    }

    // -- CLOSE_REPORT ---------------------------------------------------------------------------

    /**
     * {@code POSDevice.closeReport}, key for key. A register not Ready gets a VOID_TICKET first. Then EXIT, the main
     * menu and the close report number; then the cashier or the period branch; then the recovery of the register to
     * Ready: EXIT, EXIT, ENTER, and the open drawer amount, each followed by PATS's 25 s. CAT's one addition: every
     * key after the report number first waits for the close's "Closing Cashiers" message to go.
     */
    void closeReport(CaseRun run, RunnableAction action) throws Exception {
        String mainMenu = require(action, "MAIN MENU NO");
        String reportNo = require(action, "CLOSE REPORT NO");
        String cashier = nullIfEmpty(action.get("CASHIERNUM"));
        RegisterPos pos = context.register();
        pos.sweep();
        boolean ready = context.waits().pollUntil(pos::ready, READY_READ_WAIT, POLL);
        if (!ready) {
            LOG.info("{}: not Ready before the close; VOID_TICKET, as PATS", run.kase().key());
            press(pos, pos::voidTicket);
        }
        press(pos, pos::exitKey);
        press(pos, () -> pos.pendingEnter(mainMenu));
        press(pos, () -> pos.pendingEnter(reportNo));
        if ("cashier".equals(action.get("REPORT TYPE").strip().toLowerCase(Locale.ROOT))) {
            closingKey(run, pos, () -> pos.pendingEnter(cashier));
            closingKey(run, pos, pos::pressEnter);
            awaitCloseCompleted(run);
            closingKey(run, pos, pos::pressEnter);
            closingKey(run, pos, () -> pos.pendingEnter("2"));
            closingKey(run, pos, () -> pos.pendingEnter(cashier));
            closingKey(run, pos, pos::pressEnter);
        } else {
            closingKey(run, pos, pos::pressEnter);
            closingKey(run, pos, pos::pressEnter);
            closingKey(run, pos, pos::exitKey);
        }
        for (Runnable recover : List.<Runnable>of(pos::exitKey, pos::exitKey, pos::pressEnter)) {
            awaitNotClosing(run);
            recover.run();
            context.waits().pause(CLOSE_RECOVER_PAUSE);
        }
        awaitNotClosing(run);
        pos.pendingEnter(RECOVER_OPEN_DRAWER_AMOUNT);
        boolean back = context.waits().pollUntil(pos::ready, CLOSE_RECOVER_PAUSE, POLL);
        if (!back) {
            LOG.warn("{}: not Ready {} s after the close's recovery", run.kase().key(),
                    CLOSE_RECOVER_PAUSE.toSeconds());
        }
    }

    /** A key sent once the close's "Closing Cashiers" message has gone. */
    private void closingKey(CaseRun run, RegisterPos pos, Runnable key) {
        awaitNotClosing(run);
        press(pos, key);
    }

    /** A key: the screen read before it, the key, then the POS family's settle. */
    private static void press(RegisterPos pos, Runnable key) {
        String before = pos.screenText();
        key.run();
        pos.settle(before);
    }

    /**
     * Waits, within the case's state budget, for the close's "Closing Cashiers" message to go. A close still running
     * then is a hard failure. When coresvcs holds a deadlock, it is restarted first, so the plan can go on, and the
     * register is left at its sale screen; the case still fails, as the product defect it is.
     */
    private void awaitNotClosing(CaseRun run) {
        Duration wait = budget(run, STATE_WAIT);
        RegisterPos pos = context.register();
        if (context.waits().pollUntil(() -> !closing(pos.overlay()), wait, CLOSING_POLL)) {
            return;
        }
        String waited = wait.isZero() ? "its wait" : seconds(wait);
        boolean restarted;
        try {
            restarted = context.closeRecovery().restartIfDeadlocked();
        } catch (Exception recoveryError) {
            LOG.warn("could not check coresvcs for a stuck close: {}", recoveryError.getMessage());
            restarted = false;
        }
        if (restarted) {
            leaveAfterRestart(run);
            String dump = context.closeRecovery().keptDump().map(p -> "; thread dump: " + p).orElse("");
            throw new PatsStepError("PRODUCT DEFECT: the period close deadlocked in coresvcs on the Commander "
                    + "(the register sat on \"Closing Cashiers\" for " + waited + "). CAT restarted coresvcs so the "
                    + "plan can go on" + dump);
        }
        throw new PatsStepError("the period close never finished: the register still showed \"Closing Cashiers\" after "
                + waited + ", and coresvcs held no deadlock; nothing more was keyed");
    }

    /**
     * After coresvcs is restarted under a deadlocked close: wait for the close's message to go, then ENTER (the OK),
     * EXIT and EXIT, each settled, and no more once the register reads Ready. These are PATS's close-recovery keys,
     * not yet seen on this screen (the Python's own note).
     */
    private void leaveAfterRestart(CaseRun run) {
        RegisterPos pos = context.register();
        context.waits().pollUntil(() -> !closing(pos.overlay()), budget(run, STATE_WAIT), CLOSING_POLL);
        for (Runnable key : List.<Runnable>of(pos::pressEnter, pos::exitKey, pos::exitKey)) {
            if (pos.ready()) {
                break;
            }
            press(pos, key);
        }
        if (!context.waits().pollUntil(pos::ready, CLOSE_RECOVER_PAUSE, POLL)) {
            LOG.warn("{}: not Ready after leaving the restarted close", run.kase().key());
        }
    }

    /** The cashier branch's wait: until no message is up, or its line 2 reads "Completed Successfully". */
    private void awaitCloseCompleted(CaseRun run) {
        RegisterPos pos = context.register();
        Duration wait = budget(run, STATE_WAIT);
        boolean done = context.waits().pollUntil(() -> {
            Optional<Map<String, String>> shown = pos.overlay();
            return shown.isEmpty() || line(shown.get(), "LINE2").equals(CLOSE_COMPLETED);
        }, wait, POLL);
        if (!done) {
            throw new PatsStepError("the close to complete did not finish within " + seconds(wait)
                    + "; the message reads " + pos.overlay().orElse(Map.of()));
        }
    }

    /**
     * Whether {@code shown} is the period close's progress message: line 1 "Closing Cashiers", whatever its title and
     * line 2. Not once line 2 reads "Completed Successfully": that is the message the cashier branch waits for.
     */
    private boolean closing(Optional<Map<String, String>> shown) {
        if (shown.isEmpty()) {
            return false;
        }
        Map<String, String> message = shown.get();
        String closingLine = context.register().closingCashiersLine().strip().toLowerCase(Locale.ROOT);
        return line(message, "LINE1").equals(closingLine) && !line(message, "LINE2").equals(CLOSE_COMPLETED);
    }

    // -- helpers --------------------------------------------------------------------------------

    /** The run's budget for a state wait: {@code base} plus whatever the case's preceding sleeps carried, spent. */
    static Duration budget(CaseRun run, Duration base) {
        return Duration.ofNanos(Math.round(run.budget(base.toNanos() / 1e9) * 1e9));
    }

    private static String line(Map<String, String> shown, String key) {
        return shown.getOrDefault(key, "").strip().toLowerCase(Locale.ROOT);
    }

    private static String seconds(Duration duration) {
        return String.format(Locale.ROOT, "%.0f s", duration.toMillis() / 1000.0);
    }
}
