package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredPosAndCommander;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptSource;
import com.verifone.cat.pos.receipt.ReceiptWait;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The receipt and TLog waits take the case's dialog watch as their abort, as the Python's {@code _pos_receipt}
 * ({@code source.abort = DialogWatch(run)}) and {@code _validate_transaction} ({@code abort=DialogWatch(run)}) do.
 *
 * <p>The live evidence (CAT-Java run {@code dec5794107fa}, 2026-10-28): PETROCORE-36879 ends on "Transaction balance
 * fully paid / Payment not needed / Press ENTER/OK to Continue". With no watch its receipt and TLog waits ran 117 s
 * each, no dead end was recorded, the finish pressed nothing, and 36880 failed at its first step on that message.
 * Mirrors {@code test_run_time_levers.py::test_a_receipt_held_by_an_ok_only_message_is_given_up_early} and
 * {@code test_a_tlog_wait_held_by_an_ok_only_message_ends_early}. Fake-tested only.
 */
class DialogWatchWaitsTest {

    private static final String REASON = "the POS has shown [Transaction balance fully paid, Payment not needed, "
            + "Press ENTER/OK to Continue] for 20s, waiting for OK, and nothing presses it while the case waits";

    @TempDir
    Path folder;

    private final List<Supplier<String>> watches = new ArrayList<>();

    private ValidationContext context(ReceiptSource receipts, ValidationKit.FakePos pos) {
        return new ValidationContext(receipts, null, null, pos, new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(),
                new ValidationKit.FakeReferences().reference("Tlog", "PETROCORE-9-Tlog.xml", "<trans/>"),
                Map.<String, String>of()::get,
                run -> {
                    Supplier<String> watch = () -> REASON;
                    watches.add(watch);
                    return watch;
                });
    }

    @Test
    void aReceiptHeldByAnOkOnlyMessageIsGivenUpEarly() {
        ReceiptJobPickTest.FakePrinter printer = new ReceiptJobPickTest.FakePrinter();
        PrinterReceipts receipts = new PrinterReceipts(printer, List::of, Duration.ofSeconds(30));
        CaseRun run = ValidationKit.run(kase("PETROCORE-36879",
                        action(0, "VALIDATE_RECEIPT", "SELECT_FILE", "PETROCORE-36879-Receipt.txt")),
                context(receipts, new ValidationKit.FakePos()), folder, false, declaredPosAndCommander(), folder);
        receipts.begin();

        long started = System.nanoTime();
        String failed = verdict(run);

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "not the receipt's 117 s budget");
        assertTrue(failed.contains("waiting for OK"), failed);
        assertTrue(failed.contains("(PATS fails the same way, later)"), failed);
    }

    @Test
    void theReceiptWaitIsHandedTheCasesWatch() {
        List<ReceiptWait> waits = new ArrayList<>();
        ReceiptSource source = new ReceiptSource() {
            @Override
            public Receipt capture() {
                throw new AssertionError("the validation reads with its wait");
            }

            @Override
            public Receipt capture(ReceiptWait wait) {
                waits.add(wait);
                return ValidationKit.receipt("TRAN#1010001");
            }
        };
        CaseRun run = ValidationKit.run(kase("PETROCORE-9",
                        action(0, "VALIDATE_RECEIPT", "SELECT_FILE", "PETROCORE-9-Receipt.txt")),
                context(source, new ValidationKit.FakePos()), folder, false, declaredPosAndCommander(), folder);

        verdict(run);

        assertEquals(1, waits.size());
        assertNotNull(waits.get(0).abort());
        assertEquals(REASON, waits.get(0).abort().get());
    }

    @Test
    void theTlogWaitIsHandedTheCasesWatch() {
        List<Supplier<String>> aborts = new ArrayList<>();
        ValidationKit.FakePos pos = new ValidationKit.FakePos() {
            @Override
            public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds, Supplier<String> abort) {
                aborts.add(abort);
                return super.newestTlog(after, timeoutSeconds, abort);
            }
        };
        pos.tlog = ValidationKit.xml("<trans/>");
        CaseRun run = ValidationKit.run(kase("PETROCORE-9",
                        action(0, "VALIDATE_TRANSACTION", "SELECT_FILE", "PETROCORE-9-Tlog.xml")),
                context(ValidationKit.receiptSource(""), pos), folder, false, declaredPosAndCommander(), folder);

        verdict(run);

        assertEquals(1, aborts.size());
        assertEquals(REASON, aborts.get(0).get());
    }

    @Test
    void aContextWithNoWatchHandsTheWaitsNone() {
        ValidationContext none = new ValidationContext(ValidationKit.receiptSource(""), null, null,
                new ValidationKit.FakePos(), new ValidationKit.FakeCommander(), new ValidationKit.FakePackets(),
                new ValidationKit.FakeReferences(), Map.<String, String>of()::get);

        assertNull(none.watch(null));
    }
}
