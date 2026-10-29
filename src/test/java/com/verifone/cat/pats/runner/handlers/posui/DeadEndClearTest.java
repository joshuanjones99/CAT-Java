package com.verifone.cat.pats.runner.handlers.posui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;
import com.verifone.cat.pats.runner.handlers.validation.ValidationFamily;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A receipt wait held by an OK-only message gives up through the POS UI family's dialog watch, and the case's finish
 * presses that OK for the next case: the Python's {@code DialogWatch} on {@code _pos_receipt} and
 * {@code CaseRun._clear_dead_end}, against markup in headless Chromium, wired as {@code StandardHandlers} wires them.
 * Mirrors {@code test_run_time_levers.py::test_the_message_a_case_gave_up_on_is_pressed_for_the_next_case}.
 *
 * <p>The live evidence (CAT-Java run {@code dec5794107fa}, 2026-10-28): PETROCORE-36879 left "Transaction balance
 * fully paid / Payment not needed / Press ENTER/OK to Continue" up, and 36880 failed at its first step on it; in the
 * Python's run {@code 2581320c4367} the same message was pressed after 36879 and 36880 passed. Evidence about this
 * repository only; skipped, not failed, where Chromium is not installed for Playwright.
 */
class DeadEndClearTest {

    private static final PosUiTimings FAST = new PosUiTimings(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofMillis(100), Duration.ofMillis(100),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(50),
            Duration.ofMillis(50), Duration.ofSeconds(1));

    private static Browser browser;
    private static Playwright playwright;

    @TempDir
    Path folder;

    @BeforeAll
    static void openChromium() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        } catch (PlaywrightException missing) {
            browser = null;
        }
    }

    @AfterAll
    static void closeChromium() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void needsChromium() {
        Assumptions.assumeTrue(browser != null, "Chromium is not installed for Playwright; skipping");
    }

    /** A printer that never prints: the sale is held by the message. */
    private static final ReceiptPrinter SILENT = new ReceiptPrinter() {
        @Override
        public int receiptCount() {
            return 0;
        }

        @Override
        public List<List<String>> receiptsSince(int index) {
            return List.of();
        }
    };

    private static String dialog(String button) {
        String ok = Locators.OVERLAY_MSG_VIEW_OK_BTN.value();
        return "<div id=\"msg\">"
                + "<div id=\"" + Locators.OVERLAY_MSG_VIEW_TITLE.value() + "\">Transaction balance fully paid</div>"
                + "<div id=\"" + Locators.OVERLAY_MSG_VIEW_LINE1.value() + "\">Payment not needed</div>"
                + "<div id=\"" + Locators.OVERLAY_MSG_VIEW_LINE2.value() + "\">Press ENTER/OK to Continue</div>"
                + "<button id=\"" + ok + "\" onclick=\"document.getElementById('msg').remove()\">" + button
                + "</button></div>";
    }

    private CaseRun run(Page page, PosUiFakeShim shim) {
        PosDriver driver = PosDriver.builder(page).timeouts(new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .registerId("local").settleOverlays(false).build();
        PosUiContext ctx = new PosUiContext(driver, shim.client(), key -> key).withTimings(FAST);
        PrinterReceipts receipts = new PrinterReceipts(SILENT, List::of, Duration.ofSeconds(30));
        ValidationContext validation = new ValidationContext(receipts, null, null, new NoTransactions(),
                new NoCommander(), new NoPackets(), new NoReferences(), Map.<String, String>of()::get,
                r -> PosUiFamily.dialogWatch(r, ctx));
        ActionRegistry registry = new ActionRegistry();
        PosUiFamily.register(registry, ctx);
        ValidationFamily.register(registry, validation);
        registry.onBegin(r -> receipts.begin());
        // StandardHandlers' verdict hook.
        registry.onFinish(r -> {
            if (!r.softFailures().isEmpty()) {
                PosUiFamily.clearDeadEnd(r, ctx);
            }
        });
        String key = "PETROCORE-36879";
        RunnableCase kase = new Kase(key, "DEPT-Negative-Cash", "memory", List.of(
                Action.of("VALIDATE_RECEIPT", "SELECT_FILE", key + "-Receipt.txt").withIndex(0)));
        CaseRecord record = new CaseRecord("test::" + key, key, folder.resolve(key), Instant.now());
        return new CaseRun(kase, record, registry, name -> {
            throw new IllegalStateException("no fixture " + name.key() + " in this test");
        }, Set.of(FixtureName.POS), new RunState()).withBaselineDir(folder).withUpdate(false);
    }

    @Test
    void theMessageAReceiptWaitGaveUpOnIsPressedForTheNextCase() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            page.setContent(dialog("OK"));
            CaseRun run = run(page, shim);

            long started = System.nanoTime();
            ValidationsFailed failed = assertThrows(ValidationsFailed.class, run::run);

            assertTrue(failed.getMessage().contains("waiting for OK"), failed.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(15)) < 0,
                    "the dead end, not the receipt's budget");
            assertEquals(0, page.locator("#msg").count(), "the OK was pressed for the next case");
        }
    }

    @Test
    void aMessageWithAnotherButtonIsNeverADeadEnd() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            page.setContent(dialog("Cancel"));
            CaseRun run = run(page, shim);
            run.begin();
            Optional<List<String>> before = PosUiCaseState.of(run).deadEnd();
            java.util.function.Supplier<String> watch = PosUiFamily.dialogWatch(run,
                    new PosUiContext(PosDriver.builder(page).registerId("local").settleOverlays(false).build(),
                            shim.client(), key -> key).withTimings(FAST));
            long end = System.nanoTime() + Duration.ofMillis(1600).toNanos();
            String reason = null;
            while (reason == null && System.nanoTime() < end) {
                reason = watch.get();
                page.waitForTimeout(50);
            }

            assertEquals(null, reason);
            assertTrue(before.isEmpty() && PosUiCaseState.of(run).deadEnd().isEmpty());
            assertEquals(1, page.locator("#msg").count());
        }
    }

    /** A case's waits that read no transaction here. */
    private static final class NoTransactions implements ValidationContext.PosTransactions {
        @Override
        public String newestTransactionNumber() {
            return null;
        }

        @Override
        public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds) {
            throw new IllegalStateException("no TLog in this test");
        }
    }

    private static final class NoCommander implements ValidationContext.CommanderReads {
        @Override
        public List<String> listTransactionNumbers() {
            return List.of();
        }

        @Override
        public org.w3c.dom.Element fetchTlogByTrseq(String trseq) {
            throw new IllegalStateException("no Commander in this test");
        }

        @Override
        public org.w3c.dom.Element fetchReport(String cmd, String report, String period, String filename,
                String cashier, String register) {
            throw new IllegalStateException("no Commander in this test");
        }
    }

    private static final class NoPackets implements ValidationContext.PacketSource {
        @Override
        public List<Path> captured(String device) {
            return List.of();
        }

        @Override
        public void reset(String device) {
        }
    }

    private static final class NoReferences implements ValidationContext.CaseReferences {
        @Override
        public Optional<byte[]> reference(String rule, String filename) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> shared(String folder, String filename) {
            return Optional.empty();
        }
    }

    /** A case of the given actions, as the runner reads it. */
    record Kase(String key, String title, String source, List<RunnableAction> actions) implements RunnableCase {
    }
}
