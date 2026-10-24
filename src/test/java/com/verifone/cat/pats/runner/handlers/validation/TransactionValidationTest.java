package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredPosAndCommander;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.resource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.run;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.xml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code VALIDATE_TRANSACTION}, driven through the runner with fakes. Ported from the Python
 * {@code tests/unit/test_pats_runner.py} TLog cases: the CAT baseline rules, the discount order, the totaliser,
 * the rule file's lists, the DCR register's series, and the mark a POS read waits past.
 */
class TransactionValidationTest {

    private static final String TLOG = "t-Tlog.xml";
    private static final String BASELINE = "t-CATTlog.xml";

    @TempDir
    Path folder;

    private CaseRun tlogCase(ValidationKit.FakePos pos, ValidationKit.FakeCommander commander,
            ValidationKit.FakeReferences refs, boolean update, boolean commanderDeclared, String device) {
        ValidationContext context = context(receiptSource(""), pos, commander, new ValidationKit.FakePackets(), refs,
                noEnv());
        return run(kase("PETROCORE-9", action(0, "VALIDATE_TRANSACTION", "SELECT_FILE", TLOG,
                "DEVICEID", device)), context, folder, update,
                commanderDeclared ? declaredPosAndCommander() : declaredNothing(), folder);
    }

    private static ValidationKit.FakePos posReading(String xmlText) {
        ValidationKit.FakePos pos = new ValidationKit.FakePos();
        pos.tlog = xml(xmlText);
        pos.readNumber = "1010001";
        return pos;
    }

    private static String fuelLog(String reading, String grade) {
        return "<trans><trLines><trLine><trlFuel><fuelDispenserBegin>"
                + "<dispenserReading>" + reading + "</dispenserReading></fuelDispenserBegin>"
                + "<fuelGrade>" + grade + "</fuelGrade></trlFuel></trLine></trLines></trans>";
    }

    private static String pop(String program, String amount) {
        return "<trlPopDisc><trlpdAmount>" + amount + "</trlpdAmount>"
                + "<trlpdDiscProgramId>" + program + "</trlpdDiscProgramId></trlPopDisc>";
    }

    private static String tlogWith(String... discounts) {
        return "<trans><trLines><trLine>" + String.join("", discounts) + "</trLine></trLines></trans>";
    }

    @Test
    void theSaleReadFromThePosIsTheReferenceFixtureAndPasses() throws IOException {
        String reference = resource("validate/tlog/PETROCORE-10823-Tlog.xml");
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences().reference("Tlog", TLOG, reference);
        CaseRun run = tlogCase(posReading(reference), new ValidationKit.FakeCommander(), refs, false, false, "topaz101");

        assertEquals("", verdict(run));
        assertTrue(run.record().validations().get(0).passed());
    }

    @Test
    void aChangedAmountFailsWithTheTlogDifferenceAndTheCaseCarriesOn() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, tlogWith(pop("3", "6.22")));
        CaseRun run = tlogCase(posReading(tlogWith(pop("3", "6.23"))), new ValidationKit.FakeCommander(), refs,
                false, false, "topaz101");

        String message = verdict(run);
        assertTrue(message.contains("TLog differs from " + TLOG), message);
        assertFalse(run.record().validations().get(0).passed());
    }

    @Test
    void catWritesItsOwnTlogBaselineOnlyWhereTheBuildDiffers() throws IOException {
        String reference = "<trans><trLine>fuel</trLine></trans>";
        String built = "<trans><trLine>fuel<fuelHoseID>1</fuelHoseID></trLine></trans>";
        Path baseline = folder.resolve(BASELINE);
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences().reference("Tlog", TLOG, reference);

        assertEquals("", verdict(tlogCase(posReading(reference), new ValidationKit.FakeCommander(), refs, true, false,
                "topaz101")));
        assertFalse(Files.exists(baseline), "matches PATS: no CAT baseline");

        assertEquals("", verdict(tlogCase(posReading(built), new ValidationKit.FakeCommander(), refs, true, false,
                "topaz101")));
        assertTrue(Files.isRegularFile(baseline), "the build differs, so CAT writes its baseline");

        assertEquals("", verdict(tlogCase(posReading(built), new ValidationKit.FakeCommander(), refs, false, false,
                "topaz101")), "judged against CAT's baseline now");

        String message = verdict(tlogCase(posReading(reference), new ValidationKit.FakeCommander(), refs, false, false,
                "topaz101"));
        assertTrue(message.contains(BASELINE), message);
    }

    @Test
    void aLinesDiscountsMatchInEitherOrderAndTheirValuesStillCount() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, tlogWith(pop("3", "6.22"), pop("6", "1.55")));

        assertEquals("", verdict(tlogCase(posReading(tlogWith(pop("6", "1.55"), pop("3", "6.22"))),
                new ValidationKit.FakeCommander(), refs, false, false, "topaz101")));

        String message = verdict(tlogCase(posReading(tlogWith(pop("6", "1.56"), pop("3", "6.22"))),
                new ValidationKit.FakeCommander(), refs, false, false, "topaz101"));
        assertTrue(message.contains("TLog differs"), message);
    }

    @Test
    void aPumpsTotaliserIsNotComparedAndEverythingElseIs() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, fuelLog("5.234", "UNLD1"));

        assertEquals("", verdict(tlogCase(posReading(fuelLog("18.683", "UNLD1")), new ValidationKit.FakeCommander(),
                refs, false, false, "topaz101")));

        String message = verdict(tlogCase(posReading(fuelLog("18.683", "DIESL")), new ValidationKit.FakeCommander(),
                refs, false, false, "topaz101"));
        assertTrue(message.contains("fuelGrade"), message);
    }

    @Test
    void theRuleFileListsAreAppliedToTheComparison() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, fuelLog("5.234", "UNLD1"))
                .sharedFile("AutomationTests/rulefiles/tlog", "TLOG-RULE.txt", "TAG_NAMES=fuelGrade;\n");

        assertEquals("", verdict(tlogCase(posReading(fuelLog("5.234", "DIESL")), new ValidationKit.FakeCommander(),
                refs, false, false, "topaz101")));
    }

    @Test
    void aMissingTlogReferenceIsAFailedValidation() {
        String message = verdict(tlogCase(posReading(tlogWith()), new ValidationKit.FakeCommander(),
                new ValidationKit.FakeReferences(), false, false, "topaz101"));
        assertTrue(message.contains("reference file " + TLOG + " does not exist (PATS fails the same way)"), message);
    }

    @Test
    void theDcrRegisterSaleIsTheNewestNumberedInItsSeriesAfterTheCasesMark() {
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        commander.listings.add(List.of("9010002"));                       // the case's mark, taken as it begins
        commander.listings.add(List.of("9010003", "9010002", "8000009")); // then a newer sale is listed
        commander.tlogs.put("9010003", xml(tlogWith(pop("3", "6.22"))));
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, tlogWith(pop("3", "6.22")));

        assertEquals("", verdict(tlogCase(new ValidationKit.FakePos(), commander, refs, false, true, "DCR1")));
        assertEquals(List.of("9010003"), commander.fetched);
    }

    @Test
    void aDcrRegisterWithNoTransactionIsAFailedValidation() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences().reference("Tlog", TLOG, tlogWith());

        String message = verdict(tlogCase(new ValidationKit.FakePos(), new ValidationKit.FakeCommander(), refs,
                false, false, "DCR1"));
        assertTrue(message.contains("the Commander holds no transaction for DCR1 (register 901)"), message);
    }

    @Test
    void aPosReadWaitsPastTheSaleTheLastReadReturned() {
        ValidationKit.FakePos pos = posReading(tlogWith(pop("3", "6.22")));
        pos.newestNumber = "100";
        pos.readNumber = "101";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Tlog", TLOG, tlogWith(pop("3", "6.22")));
        RunnableCase twoReads = kase("PETROCORE-9",
                action(0, "VALIDATE_TRANSACTION", "SELECT_FILE", TLOG, "DEVICEID", "topaz101"),
                action(1, "VALIDATE_TRANSACTION", "SELECT_FILE", TLOG, "DEVICEID", "topaz101"));
        CaseRun run = run(twoReads, context(receiptSource(""), pos, new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), refs, noEnv()), folder, false, declaredPosAndCommander(), folder);

        assertEquals("", verdict(run));
        assertEquals(List.of("100", "101"), pos.marksSeen);
    }

    @Test
    void aDcrSeriesIsTheRegisterNumberPlus900AndOnlyForATransactionValidationOnADcr() {
        assertEquals("901", TransactionHandler.dcrSeries(action(0, "VALIDATE_TRANSACTION", "DEVICEID", "DCR1")));
        assertEquals("902", TransactionHandler.dcrSeries(action(0, "validate_transaction", "DEVICEID", "dcr2")));
        assertNull(TransactionHandler.dcrSeries(action(0, "VALIDATE_TRANSACTION", "DEVICEID", "topaz101")));
        assertNull(TransactionHandler.dcrSeries(action(0, "VALIDATE_RECEIPT", "DEVICEID", "DCR1")));
        assertNull(TransactionHandler.dcrSeries(action(0, "VALIDATE_TRANSACTION", "DEVICEID", "DCR")));
    }

    @Test
    void theNewestInASeriesIsTheHighestNumberThatStartsWithIt() {
        assertEquals("9010003", TransactionHandler.newestInSeries(List.of("9010002", "9010003", "8000009"), "901"));
        assertNull(TransactionHandler.newestInSeries(List.of("8000009"), "901"));
    }

    @Test
    void aNewerNumberIsReturnedAtOnceAndAnEmptyListingIsNoneAfterTheCeiling() {
        assertEquals("9010002", TransactionHandler.newerTransaction(() -> "9010002", "", 0));
        assertNull(TransactionHandler.newerTransaction(() -> null, "", 0));
    }

    @Test
    void aCaseWithNoBaselineDirectoryIsAHardStepFailureNotAValidation() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences().reference("Tlog", TLOG, tlogWith());
        CaseRun run = run(kase("PETROCORE-9", action(0, "VALIDATE_TRANSACTION", "SELECT_FILE", TLOG)),
                context(receiptSource(""), posReading(tlogWith()), new ValidationKit.FakeCommander(),
                        new ValidationKit.FakePackets(), refs, noEnv()),
                null, false, declaredPosAndCommander(), folder);

        assertThrows(PatsStepError.class, run::run);
    }
}
