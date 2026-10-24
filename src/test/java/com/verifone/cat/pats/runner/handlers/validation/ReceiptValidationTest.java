package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.resource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.run;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pos.receipt.ReceiptWaitException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code VALIDATE_RECEIPT}, driven through the runner with fakes. Ported from the Python
 * {@code tests/unit/test_pats_runner.py} receipt, layout-drift, POP-block and merchant-copy cases.
 *
 * <p>In the CSV sources a literal {@code \n} stands for a line break, so a row fits on one line.
 */
class ReceiptValidationTest {

    private static final String RECEIPT = "PETROCORE-9-Receipt.txt";
    private static final String BASELINE = "PETROCORE-9-CATReceipt.txt";

    @TempDir
    Path folder;

    private static Map<String, String> rebaseLayout() {
        return Map.of(ValidationContext.RECEIPT_REBASE_ENV, "layout");
    }

    private static String lines(String csvCell) {
        return csvCell.replace("\\n", "\n");
    }

    private CaseRun receiptCase(ValidationContext context, boolean update, String name) {
        return run(kase("PETROCORE-9", action(0, "VALIDATE_RECEIPT", "SELECT_FILE", name, "DEVICEID", "topaz101")),
                context, folder, update, declaredNothing(), folder);
    }

    private static ValidationContext contextWith(String printed, ValidationKit.FakeReferences references,
            Map<String, String> env) {
        return context(receiptSource(printed), new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), references, env);
    }

    @Test
    void theReceiptCapturedFromThePosIsTheReferenceFixtureAndPasses() throws IOException {
        String reference = resource("validate/petroapps/PETROCORE-10823-Receipt.txt");
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Receipt", "PETROCORE-10823-Receipt.txt", reference);
        CaseRun run = receiptCase(contextWith(reference, refs, noEnv()), false, "PETROCORE-10823-Receipt.txt");

        assertEquals("", verdict(run));
        assertEquals(1, run.record().validations().size());
        assertTrue(run.record().validations().get(0).passed());
    }

    @Test
    void anUpdateRunWritesCatsOwnBaselineAndJudgesNothing() throws IOException {
        CaseRun run = receiptCase(contextWith("PLU-AA 10.00\n", new ValidationKit.FakeReferences(), noEnv()), true, RECEIPT);

        assertEquals("", verdict(run));
        assertEquals("PLU-AA 10.00\n", Files.readString(folder.resolve(BASELINE)));
        assertTrue(run.record().validations().isEmpty(), "an update run gives no verdict");
    }

    @Test
    void aReceiptDifferingFromPatsReferenceFailsAndTheCaseCarriesOn() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Receipt", RECEIPT, "PLU-AA 10.00\n");
        CaseRun run = receiptCase(contextWith("PLU-AB 10.00\n", refs, noEnv()), false, RECEIPT);

        String message = verdict(run);
        assertTrue(message.contains("receipt differs:"), message);
        assertTrue(message.contains("+PLU-AB 10.00"), message);
        assertFalse(run.record().validations().get(0).passed());
    }

    @Test
    void catsOwnBaselineIsJudgedInPlaceOfPatsReference() throws IOException {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Receipt", RECEIPT, "PLU-AA 10.00\n");
        Files.writeString(folder.resolve(BASELINE), "PLU-AB 10.00\n");
        CaseRun run = receiptCase(contextWith("PLU-AA 10.00\n", refs, noEnv()), false, RECEIPT);

        String message = verdict(run);
        assertTrue(message.contains("PLU-AB"), message);
        assertTrue(message.contains("PETROCORE-9-CATReceipt.txt (snapshot)"), message);
    }

    @Test
    void aReceiptWithNoReferenceFailsWithItsCaptureToSeedABaseline() {
        CaseRun run = receiptCase(contextWith("WELCOME TO\nDECLINED\n", new ValidationKit.FakeReferences(), noEnv()),
                false, "PETROCORE-9-1-Receipt.txt");

        String message = verdict(run);
        assertTrue(message.contains("does not exist (PATS fails the same way)"), message);
        assertTrue(message.contains("PETROCORE-9-1-CATReceipt.txt (snapshot)"), message);
        assertTrue(message.contains("+DECLINED"), message);
    }

    @Test
    void aStoreLineDriftIsRefusedUnlessTheRebaseSwitchIsSet() throws IOException {
        String reference = "AGE VERIFICATION\nSTORE\n     9999999\nBEER  6.99\n";
        String printed = "AGE VERIFICATION\nSTORE\n      99999\nBEER  6.99\n";
        Files.writeString(folder.resolve(BASELINE), reference);

        String message = verdict(receiptCase(contextWith(printed, new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT));
        assertTrue(message.contains("layout drift only"), message);
        assertEquals(reference, Files.readString(folder.resolve(BASELINE)));

        assertEquals("", verdict(receiptCase(contextWith(printed, new ValidationKit.FakeReferences(), rebaseLayout()),
                false, RECEIPT)));
        assertEquals(printed, Files.readString(folder.resolve(BASELINE)));
    }

    @Test
    void aDriftOnlyReceiptRewritesItsBaselineWhenAskedAndNotOtherwise() throws IOException {
        Files.writeString(folder.resolve(BASELINE), "A\n\nB\n");
        String printed = "A\nPOP-DISCNT-1\nB\n";

        String message = verdict(receiptCase(contextWith(printed, new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT));
        assertTrue(message.contains("layout drift only"), message);
        assertEquals("A\n\nB\n", Files.readString(folder.resolve(BASELINE)));

        assertEquals("", verdict(receiptCase(contextWith(printed, new ValidationKit.FakeReferences(), rebaseLayout()),
                false, RECEIPT)));
        assertEquals(printed, Files.readString(folder.resolve(BASELINE)));
    }

    @Test
    void popDiscountBlocksInEitherOrderMatchAndTheirValuesStillCount() throws IOException {
        String two = "POP-DISCNT-2\nYOU RECEIVED  $0.20 / GALLON DISCOUNT\nFOR  5.000 GALLONS\n";
        String three = "POP-DISCNT-3\nYOU RECEIVED  $0.30 / GALLON DISCOUNT\nFOR  27.788 GALLONS\n";
        Files.writeString(folder.resolve(BASELINE), "A\n" + two + "\n\n" + three + "\nB\n");

        String printed = "A\n" + three + "\n\n" + two + "\nB\n";
        assertEquals("", verdict(receiptCase(contextWith(printed, new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT)));

        String wrong = printed.replace("5.000", "6.000");
        String message = verdict(receiptCase(contextWith(wrong, new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT));
        assertTrue(message.contains("6.000"), message);
    }

    @Test
    void aLeadingMerchantCopyIsOneSaleJobOrTwoAndALeadingCustomerCopyStillMatches() throws IOException {
        String merchant = "WELCOME TO\nPREPAY Receipt\nMERCHANT COPY\n\n";
        String customer = "WELCOME TO\nPREPAY Receipt\nCUSTOMER COPY\n";
        Path baseline = folder.resolve(BASELINE);
        for (String[] pair : new String[][] {{merchant + customer, customer}, {customer, merchant + customer}}) {
            Files.writeString(baseline, pair[0]);
            assertEquals("", verdict(receiptCase(contextWith(pair[1], new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT)));
        }
        Files.writeString(baseline, customer + customer);
        String message = verdict(receiptCase(contextWith(customer, new ValidationKit.FakeReferences(), noEnv()), false, RECEIPT));
        assertTrue(message.contains("receipt differs"), message);
    }

    @Test
    void aReceiptThatNeverPrintsIsAFailedValidationNotAStop() {
        ValidationContext context = context(() -> {
            throw ReceiptWaitException.timedOut("the receipt");
        }, new ValidationKit.FakePos(), new ValidationKit.FakeCommander(), new ValidationKit.FakePackets(),
                new ValidationKit.FakeReferences(), noEnv());

        String message = verdict(receiptCase(context, false, RECEIPT));
        assertTrue(message.contains("receipt not received"), message);
        assertTrue(message.contains("PATS fails the same way"), message);
    }

    @ParameterizedTest(name = "drift only = {2}: {0}")
    @CsvSource(delimiterString = "|", value = {
            "A\\nPOP-DISCNT-2\\nB\\n99999\\nC|A\\n\\nB\\nC|true",
            "A\\n\\nB\\nC|A\\n\\nB\\nC|false",
            "A\\nPOP-DISCNT-2\\nB\\nD|A\\n\\nB\\nC|false",
            "A\\n\\nB\\n99999\\n99999\\nC|A\\n\\nB\\nC|true",
            "A\\nTOTAL 9.99\\nB\\nC|A\\n\\nB\\nC|false",
    })
    void onlyThisBuildsLayoutChangesCountAsDrift(String got, String expected, boolean drift) {
        assertEquals(drift, ReceiptCanon.layoutDriftOnly(lines(expected), lines(got)));
    }

    @ParameterizedTest(name = "wash drift = {1}: {0}")
    @CsvSource(delimiterString = "|", value = {
            "Code\\n   CAR-WASH-PLU-4   <CARWASH_CODE>\\n\\nX|true",
            "Code\\n   CAR-WASH-PLU-4   <CARWASH_CODE>\\nPOP-DISCNT-1\\nX|true",
            "Code\\n   CAR-WASH-PLU-4   12345\\n\\nX|false",
            "Code\\n<CARWASH_CODE>\\n\\nY|false",
    })
    void theWashNameBeforeACarWashCodeCountsAsDrift(String got, boolean drift) {
        assertEquals(drift, ReceiptCanon.layoutDriftOnly(lines("Code\\n <CARWASH_CODE>\\n\\nX"), lines(got)));
    }

    @ParameterizedTest(name = "store drift = {1}: {0}")
    @CsvSource(delimiterString = "|", value = {
            "STORE\\n  99999\\n\\nA|true",
            "STORE\\n  99999\\nPOP-DISCNT-1\\nA|true",
            "STORE\\n  12345\\n\\nA|false",
            "STORE\\n  99999\\n\\nB|false",
    })
    void theStoreLineAfterTheBuypassTableLoadCountsAsDrift(String got, boolean drift) {
        assertEquals(drift, ReceiptCanon.layoutDriftOnly(lines("STORE\\n  9999999\\n\\nA"), lines(got)));
    }

    @Test
    void aStoreLineReadingUnloadedTablesIsNotDrift() {
        assertFalse(ReceiptCanon.layoutDriftOnly("STORE\n  99999\nA", "STORE\n  9999999\nA"));
    }

    @Test
    void endOfPlanReportReceiptsMaskWhatChangesEachRun() {
        String approvals = "22.96   21.017  0.00    0.15     22.81\n3\n\n10.00   5.492   0.00    0.15     9.85\n9\n";
        String swapped = "10.00   5.492   0.00    0.15     9.85\n9\n\n22.96   21.017  0.00    0.15     22.81\n3\n";
        assertEquals(ReceiptCanon.reportCanonical(approvals), ReceiptCanon.reportCanonical(swapped));
        String moved = swapped.replace("\n9\n", "\n8\n");
        assertFalse(ReceiptCanon.reportCanonical(approvals).equals(ReceiptCanon.reportCanonical(moved)));

        String detail = "374245001751006      AX 00001325  11.50\n75166\n";
        String again = "374245001751006      AX 00001523  11.50\n76561\n";
        assertEquals(ReceiptCanon.reportCanonical(detail), ReceiptCanon.reportCanonical(again));
        assertFalse(ReceiptCanon.reportCanonical(detail).equals(
                ReceiptCanon.reportCanonical(again.replace("11.50", "11.60"))));

        assertEquals(ReceiptCanon.reportCanonical("CASHIER REPORT #022\n"),
                ReceiptCanon.reportCanonical("CASHIER REPORT #024\n"));

        String loyalty = "XXXXXXXXXXXXXX3161     52.14       0.50\nXXXXXXXXXXXXXX3161     19.45       0.20\n";
        String reordered = "XXXXXXXXXXXXXX3161     19.45       0.20\nXXXXXXXXXXXXXX3161     52.14       0.50\n";
        assertEquals(ReceiptCanon.reportCanonical(loyalty), ReceiptCanon.reportCanonical(reordered));
    }

    @Test
    void fuelStreetPriceRowsMatchInAnyOrder() {
        String a = "PETROL-LCA   1      4.121          4.121\n          1    1.213      5.00\n";
        String b = "DIESEL-LCR   1      3.121          3.121\n          1    6.408     20.00\n";
        assertEquals(ReceiptCanon.reportCanonical(a + b), ReceiptCanon.reportCanonical(b + a));
        assertFalse(ReceiptCanon.reportCanonical(a + b).equals(
                ReceiptCanon.reportCanonical(b + a.replace("5.00", "5.01"))));
    }
}
