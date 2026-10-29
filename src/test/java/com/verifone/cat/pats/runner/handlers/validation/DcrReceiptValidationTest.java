package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
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
 * VALIDATE_RECEIPT in a case that prints at the DCR: the DCR's receipt is compared as printed, centred header and
 * amount columns included, as the Python compares {@code receipt_since}'s string.
 *
 * <p>CAT-Java run 5285e78aa370, PETROCORE-118399: the DCR receipt went through {@link ReceiptParser#parse}, which
 * drops blank lines and collapses spacing, and failed against the centred baseline ("     WELCOME TO" against
 * "WELCOME TO", "GALLONS:      16.005" against "GALLONS: 16.005"). The receipt below is 118399's DCR receipt as the
 * Python's passing run 2581320c4367 captured it. Fake-tested only.
 */
class DcrReceiptValidationTest {

    private static final String KEY = "PETROCORE-118399";
    private static final String NAME = KEY + "-Receipt.txt";

    /** 118399's DCR receipt, line for line as the DCR printed it (trailing spaces included). */
    static final String PRINTED = String.join("\n",
            "     WELCOME TO     ",
            "      VERIFONE      ",
            "   AUTOMATION LAB   ",
            "    HAVE YOU RUN    ",
            "  AUTOMATION TODAY  ",
            "     VFI-QA-LAB     ",
            "     CLEARWATER     ",
            "       TPA FL       ",
            "       560100       ",
            "                    ",
            "",
            "DATE 10/23/26 23:04 ",
            "TRAN#9010294        ",
            "PUMP# 01            ",
            "SERVICE LEVEL: SELF ",
            "PRODUCT: PETR-UNLD1 ",
            "GALLONS:      16.005",
            "PRICE/G:      $0.821",
            "FUEL SALE     $13.14",
            "   CREDIT     $13.14",
            "",
            "POP-DISCNT-3        ",
            "YOU SAVED 0.300/G   ",
            "",
            "",
            "AMEX                ",
            "***********1005     ",
            "Entry: Swiped       ",
            "Auth #: 123628      ",
            "Resp Code: 000      ",
            "Stan: 006837358     ",
            "Invoice #: 2513     ",
            "Store # 99999       ",
            "",
            "",
            "",
            "   THANK YOU FOR    ",
            " AUTOMATING THINGS  ",
            "     PLEASE RUN     ",
            "     AUTOMATION     ",
            "  AGAIN AND AGAIN   ");

    @TempDir
    Path folder;

    private String verdictFor(Function<String, Receipt> dcrReads) throws IOException {
        // The baseline is the receipt as printed: what `--update` writes from this capture.
        Files.writeString(folder.resolve(KEY + "-CATReceipt.txt"), PRINTED + "\n");
        ValidationContext.DcrReceipts dcr = new ValidationContext.DcrReceipts() {
            @Override
            public long printCount() {
                return 0;
            }

            @Override
            public Receipt receiptSince(long mark, double timeoutSeconds) {
                return dcrReads.apply(PRINTED);
            }
        };
        ValidationContext context = new ValidationContext(receiptSource("the POS's receipt, not the DCR's"), dcr,
                kase -> true, new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), new ValidationKit.FakeReferences(), noEnv()::get);
        CaseRun run = run(kase(KEY, action(12, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), context, folder, false,
                declaredNothing(), folder);
        return verdict(run);
    }

    @Test
    void theDcrReceiptAsPrintedMatchesItsCentredBaseline() throws IOException {
        assertEquals("", verdictFor(text -> Receipt.printed(text, Source.SERIAL)));
    }

    /** The read before this fix: the parsed receipt has lost the layout the baseline holds. */
    @Test
    void theParsedDcrReceiptIsWhatFailedOnTheRig() throws IOException {
        String message = verdictFor(text -> ReceiptParser.parse(text, Source.SERIAL));
        assertTrue(message.contains("receipt differs"), message);
        assertTrue(message.contains("+WELCOME TO"), message);
    }

    @Test
    void aPrintedReceiptGivesItsTextBackUnchanged() {
        assertEquals(PRINTED, Receipt.printed(PRINTED, Source.SERIAL).text());
    }
}
