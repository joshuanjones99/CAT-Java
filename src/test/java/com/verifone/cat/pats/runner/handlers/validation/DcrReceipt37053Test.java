package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.resource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.run;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptParser;
import com.verifone.cat.pos.receipt.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PETROCORE-37053 (Prepay-OutDoor-Loyalty-Multiple-Credit), an outdoor case whose receipt is the DCR's, against the
 * committed Cadence baseline {@code PETROCORE-37053-CATReceipt.txt} (copied verbatim from
 * {@code C:\gitrepos\CAT\tests\suites\cadence}).
 *
 * <p>CAT-Java run 5285e78aa370 failed 36918, 36927 and 37053 with the centred header lines deleted and left-aligned
 * ones added, the amount columns collapsed to two spaces, the blank lines and the closing banner gone. All three
 * cases print at the DCR ({@code WAIT_DCR_PROMPT}, {@code INSERT_OR_SLIDE_CARD(DEVICE='DCR1')}), so their receipt
 * came through {@code StandardHandlers.dcrReceipts}, which in that run's jar (built 21:48, before e60ed7a) parsed the
 * DCR's string with {@link ReceiptParser#parse}. The POS's serial path keeps raw lines and was not involved.
 *
 * <p>The capture here is the DCR receipt the Python's passing run 2581320c4367 recorded for the same case (different
 * date, TRAN#, STAN, invoice and car-wash code from the baseline's run), so this proves the normalisers, not only the
 * read, make it equal. Fake-tested only; nothing here is rig-verified.
 */
class DcrReceipt37053Test {

    private static final String KEY = "PETROCORE-37053";
    private static final String NAME = KEY + "-Receipt.txt";
    private static final String DIR = "pats/runner/validation/dcr37053/";

    @TempDir
    Path folder;

    private String verdictFor(String printed, Function<String, Receipt> dcrReads) throws IOException {
        Files.writeString(folder.resolve(KEY + "-CATReceipt.txt"), resource(DIR + KEY + "-CATReceipt.txt"));
        ValidationContext.DcrReceipts dcr = new ValidationContext.DcrReceipts() {
            @Override
            public long printCount() {
                return 0;
            }

            @Override
            public Receipt receiptSince(long mark, double timeoutSeconds) {
                return dcrReads.apply(printed);
            }
        };
        ValidationContext context = new ValidationContext(receiptSource("the POS's receipt, not the DCR's"), dcr,
                kase -> true, new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), new ValidationKit.FakeReferences(), noEnv()::get);
        CaseRun run = run(kase(KEY,
                action(20, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), context, folder, false, declaredNothing(), folder);
        return verdict(run);
    }

    /** The Python's DCR capture, kept as printed, equals the committed baseline through the real handler. */
    @Test
    void thePythonsDcrCaptureAsPrintedMatchesTheCommittedBaseline() throws IOException {
        String printed = resource(DIR + KEY + "-printed-2581320c4367.txt");
        assertEquals("", verdictFor(printed, text -> Receipt.printed(text, Source.SERIAL)));
    }

    /** The same capture parsed, as run 5285e78aa370's jar read it, fails the way the rig did. */
    @Test
    void theSameCaptureParsedFailsAsOnTheRig() throws IOException {
        String printed = resource(DIR + KEY + "-printed-2581320c4367.txt");
        String message = verdictFor(printed, text -> ReceiptParser.parse(text, Source.SERIAL));
        assertTrue(message.contains("receipt differs"), message);
        assertTrue(message.contains("-     WELCOME TO"), message);
        assertTrue(message.contains("+WELCOME TO"), message);
        assertTrue(message.contains("+GALLONS: 19.194"), message);
    }

    /** What run 5285e78aa370 recorded as its capture is already the parsed text: no read can restore it. */
    @Test
    void theRigsRecordedCaptureIsTheParsedForm() throws IOException {
        String recorded = resource(DIR + KEY + "-parsed-5285e78aa370.txt");
        String parsed = ReceiptParser.parse(resource(DIR + KEY + "-printed-2581320c4367.txt"), Source.SERIAL).text();
        assertTrue(recorded.startsWith("WELCOME TO\nVERIFONE\nAUTOMATION LAB\n"), recorded);
        assertTrue(parsed.startsWith("WELCOME TO\nVERIFONE\nAUTOMATION LAB\n"), parsed);
        assertTrue(parsed.contains("\nGALLONS: 19.194\n"), parsed);
    }
}
