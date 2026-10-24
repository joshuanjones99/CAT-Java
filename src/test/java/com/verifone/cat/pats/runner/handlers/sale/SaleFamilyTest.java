package com.verifone.cat.pats.runner.handlers.sale;

import static com.verifone.cat.pats.runner.handlers.HandlerHarness.act;
import static com.verifone.cat.pats.runner.handlers.HandlerHarness.caseRun;
import static com.verifone.cat.pats.runner.handlers.HandlerHarness.registerTestHandlers;
import static com.verifone.cat.pats.runner.handlers.HandlerFakes.scanData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Pumps;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Rmi;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Scanner;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Screen;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Tlog;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sale family, driven through the real {@link CaseRun} against the fakes: the screen, the RMI, the pumps, the
 * scanner data and the TLog are all fakes, and nothing reaches a register.
 */
class SaleFamilyTest {

    /** Short waits, so the bounded polls in these tests finish at once. */
    static final SaleTiming FAST = new SaleTiming(Duration.ofMillis(200), Duration.ofMillis(200),
            Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(200), Duration.ofSeconds(1),
            Duration.ofMillis(10));

    @TempDir
    Path folder;

    private final List<Duration> pauses = new ArrayList<>();
    private Screen screen;
    private Rmi rmi;
    private Pumps pumps;
    private Scanner scanner;
    private Tlog tlog;
    private ActionRegistry registry;

    @BeforeEach
    void setUp() {
        screen = new Screen();
        rmi = new Rmi();
        pumps = new Pumps();
        scanner = new Scanner();
        tlog = new Tlog();
        registry = registryWith(context(FAST, tlog));
    }

    private SaleContext context(SaleTiming timing, LastTlog tlogSource) {
        return new SaleContext(screen, rmi, pumps, scanner, tlogSource, pauses::add,
                new TouchPacer(pauses::add, timing.touchSettle()), timing);
    }

    private static ActionRegistry registryWith(SaleContext context) {
        ActionRegistry registry = new ActionRegistry();
        SaleFamily.register(registry, context);
        registerTestHandlers(registry);
        return registry;
    }

    private CaseRun run(Set<FixtureName> declared, RunnableAction... actions) {
        return caseRun(registry, folder, declared, actions);
    }

    private CaseRun run(RunnableAction... actions) {
        return run(Set.of(), actions);
    }

    @Test
    void registersTheTwentySaleActionsAndNoOthers() {
        assertEquals(Set.copyOf(SaleFamily.ACTIONS), registry.supportedActions().stream()
                .filter(name -> !name.startsWith("SET_") && !name.equals("CARRY"))
                .collect(Collectors.toSet()));
        assertEquals(20, SaleFamily.ACTIONS.size());
    }

    @Test
    void totalClearsTheBlockingOverlaysThenPressesTotal() {
        run(act(0, "TOTAL")).run();

        assertEquals(List.of("total"), rmi.calls);
        assertEquals(1, screen.clears);
    }

    @Test
    void confirmationSendsItsAnswerAsOneBareKey() {
        run(act(0, "CONFIRMATION", "YES/NO", "YES")).run();

        assertEquals(List.of("key:YES"), rmi.calls);
    }

    @Test
    void confirmationWithNoAnswerIsAStepFailureNotANullKey() {
        assertThrows(PatsStepError.class, () -> run(act(0, "CONFIRMATION", "YES/NO", "")).run());

        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void aKeyboardEventWithNoKeyCodeIsSkipped() {
        run(act(0, "POS_KEYBOARD_EVENT", "DATA", "0x69")).run();

        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void aKeyboardEventUnderAMessagePausesOneSecondThenPressesItsKey() {
        screen.overlay = Map.of("TITLE", "Notice", "LINE1", "", "LINE2", "", "LINE3", "");

        run(act(0, "POS_KEYBOARD_EVENT", "KEY_EVENT_ADDR", "0x69")).run();

        assertEquals(List.of(Duration.ofSeconds(1)), pauses);
        assertEquals(List.of("key:0x69"), rmi.calls);
    }

    @Test
    void aVoidLineWithNoMessageUpGoesAtOnce() {
        run(act(0, "VOID_LINE", "PLU", "8002", "QUANTITY", "2")).run();

        assertEquals(List.of("voidLine:8002:2"), rmi.calls);
        assertTrue(pauses.isEmpty());
    }

    @Test
    void aVoidTicketUnderAMessageWaitsOneSecondFirst() {
        screen.overlay = Map.of("TITLE", "Notice", "LINE1", "", "LINE2", "", "LINE3", "");

        run(act(0, "VOID_TICKET")).run();

        assertEquals(List.of(Duration.ofSeconds(1)), pauses);
        assertEquals(List.of("voidTicket"), rmi.calls);
    }

    @Test
    void suspendWaitsUntilTheExpressPanelOffersPriceCheckThenSuspends() {
        screen.express.add(List.of("Foo"));
        screen.express.add(List.of("Price Check"));

        run(act(0, "SUSP")).run();

        assertEquals(List.of("suspend"), rmi.calls);
    }

    @Test
    void suspendThatIsNeverOfferedIsAHardFailureAndPressesNothing() {
        screen.express.add(List.of("Foo"));

        PatsStepError stopped = assertThrows(PatsStepError.class, () -> run(act(0, "SUSP")).run());

        assertTrue(stopped.getMessage().contains("Foo"), stopped.getMessage());
        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void aScanOfAnXmlFileSendsItsTokenAndMarksATouch() {
        scanner.files.put("a.xml", scanData("0123", 3));
        CaseRun kase = run(act(0, "SCAN_ITEM", "SELECT_FILE", "a.xml"));

        kase.run();

        assertEquals(List.of("a.xml"), scanner.reads);
        assertEquals(1, rmi.scans.size());
        SaleRmi.ScanItem item = rmi.scans.get(0);
        assertEquals("0123", item.scanData());
        assertEquals(3, item.scanDataType());
        assertTrue(item.decodeData());
        assertEquals(null, item.scanDataLabel());
        assertEquals(0, item.status());
        assertTrue(kase.scratch(TouchPacer.TOUCHED).orElse(false));
    }

    @Test
    void aScanOfAFileThatIsNotXmlDoesNothing() {
        run(act(0, "SCAN_ITEM", "SELECT_FILE", "a.txt")).run();

        assertTrue(scanner.reads.isEmpty());
        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void aScannerFileWithNoDataElementIsAStepFailure() {
        scanner.files.put("b.xml", "<ScannerData/>");

        assertThrows(PatsStepError.class, () -> run(act(0, "SCAN_ITEM", "SELECT_FILE", "b.xml")).run());
        assertTrue(rmi.scans.isEmpty());
    }

    @Test
    void aScanThatChangesNothingUnderANagIsSentAgainOnceTheNagIsCleared() {
        scanner.files.put("a.xml", scanData("0123", 3));
        screen.overlay = Map.of("TITLE", "NOT ONBOARDED", "LINE1", "", "LINE2", "", "LINE3", "");
        screen.overlayRemovedAtClear = 2;
        screen.settles.add(false);
        screen.settles.add(true);

        run(act(0, "SCAN_ITEM", "SELECT_FILE", "a.xml")).run();

        assertEquals(2, rmi.scans.size());
        assertEquals(2, screen.clears);
    }

    @Test
    void anItemOnTheRegisterAsksForItsScannerFileAndScansIt() {
        rmi.addItemScans = true;
        scanner.files.put("item.xml", scanData("8002", 1));

        run(act(0, "ADD_ITEM", "PLU", "8002", "SELECT_FILE", "item.xml")).run();

        assertEquals(List.of("additem:8002:1", "scan:8002"), rmi.calls);
        assertEquals(List.of("item.xml"), scanner.reads);
    }

    @Test
    void aQuantityPressesItsKeyThenEnterWithTheQuantity() {
        run(act(0, "QTY", "QUANTITY_KEY", "*", "QUANTITY", "3")).run();

        assertEquals(List.of("keys:*", "enter:3"), rmi.calls);
    }

    @Test
    void pressEnterWithNoDataIsBare() {
        run(act(0, "PRESS_ENTER")).run();

        assertEquals(List.of("enter:null"), rmi.calls);
    }

    @Test
    void aTenderOnAPosThatIsDeclaredWaitsForTheRegisterToReadReady() {
        screen.express.add(List.of("Foo"));
        screen.express.add(List.of("Reprint"));

        run(Set.of(FixtureName.POS), act(0, "MOP", "MOP", "CASH", "DATA", "100")).run();

        assertEquals(List.of("mop:CASH:100"), rmi.calls);
    }

    @Test
    void aTenderWithNoPosDeclaredDoesNotWaitForReady() {
        screen.express.add(List.of("Foo"));

        run(act(0, "OTHER_MOP", "OTHER_MOP", "CREDIT", "DATA", "5", "POSITION", "3")).run();

        assertEquals(List.of("othermop:CREDIT:5:3:N"), rmi.calls);
    }

    @Test
    void aRefundTypesTheStanTheRegisterAsksForFromTheStoredReceipt() {
        screen.receipt = "TRAN# 1234  Stan: 567";
        screen.overlay = Map.of("TITLE", "", "LINE1", "ENTER STAN", "LINE2", "", "LINE3", "");

        run(act(0, "STORE_TRANS_MAP", "REFUND_KEY", "K1"),
                act(1, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "K1")).run();

        assertEquals(List.of("enter:567"), rmi.calls);
    }

    @Test
    void aRefundKeysTheTranWhenTheRegisterDoesNotAskForAStan() {
        screen.receipt = "TRAN# 1234  Stan: 567";

        run(act(0, "STORE_TRANS_MAP", "REFUND_KEY", "K1"),
                act(1, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "K1")).run();

        assertEquals(List.of("enter:1234"), rmi.calls);
    }

    @Test
    void aRefundWithNothingStoredVoidsTheTicketAsPatsDoes() {
        run(act(0, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "K9")).run();

        assertEquals(List.of("voidTicket"), rmi.calls);
    }

    @Test
    void aRefundTakesItsStanFromTheNewestTlogWhenTheReceiptHasNone() {
        screen.receipt = "TRAN# 1234";
        screen.overlay = Map.of("TITLE", "", "LINE1", "ENTER STAN", "LINE2", "", "LINE3", "");
        tlog.stan = Optional.of("9988");

        run(act(0, "STORE_TRANS_MAP", "REFUND_KEY", "K1"),
                act(1, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "K1")).run();

        assertEquals(List.of("enter:9988"), rmi.calls);
    }

    @Test
    void aRefundWithNoTlogSourceFailsRatherThanVoidingOnAGuess() {
        ActionRegistry unwired = registryWith(new SaleContext(screen, rmi, pumps, scanner,
                LastTlog.UNAVAILABLE, pauses::add, new TouchPacer(pauses::add, FAST.touchSettle()), FAST));
        screen.receipt = "TRAN# 1234";

        PatsStepError failed = assertThrows(PatsStepError.class, () -> caseRun(unwired, folder, Set.of(),
                act(0, "STORE_TRANS_MAP", "REFUND_KEY", "K1"),
                act(1, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "K2")).run());

        assertInstanceOf(DeviceUnavailable.class, failed.getCause());
        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void anApprovalWaitsForThePumpToBeAwaitingApproval() {
        pumps.states.put(14, "IDLEON");

        run(act(0, "APPROVE", "PUMP NO", "14")).run();

        assertEquals(List.of("with:APPROVE:14"), rmi.calls);
    }

    @Test
    void anApprovalForAPumpThatNeverWaitsIsAStepFailure() {
        SaleTiming shortPinpad = new SaleTiming(FAST.touchSettle(), FAST.cancelSessionWait(),
                Duration.ofMillis(200), FAST.stateWait(), FAST.overlayRead(), FAST.overlaySeenPause(),
                FAST.pollInterval());
        ActionRegistry shortRegistry = registryWith(context(shortPinpad, tlog));

        assertThrows(PatsStepError.class,
                () -> caseRun(shortRegistry, folder, Set.of(), act(0, "APPROVE", "PUMP NO", "14")).run());
        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void aPrepayMarksTheCaseAndWaitsForThePumpToBeIdle() {
        CaseRun kase = run(act(0, "PREPAY", "PUMP NO", "14", "AMOUNT", "10.00"));

        kase.run();

        assertEquals(List.of("prepay:14:N:0:10.00:CASH"), rmi.calls);
        assertEquals(List.of("idle:14"), pumps.calls);
        assertTrue(kase.scratch(ScratchKey.PREPAY).orElse(false));
    }

    @Test
    void aFuelSaleOnAPumpWaitsForItToBeClaimable() {
        run(act(0, "FUEL", "PUMP", "3")).run();

        assertEquals(List.of("fuel:3:STACKED"), rmi.calls);
        assertEquals(List.of("idle:3"), pumps.calls);
    }

    @Test
    void aDepartmentItemIsKeyedAsItIsGiven() {
        run(act(0, "ADD_DEPT_ITEM", "DEPT", "7", "AMOUNT", "5.00")).run();

        assertEquals(List.of("deptitem:7:5.00:0"), rmi.calls);
    }

    @Test
    void aMissingRequiredFieldIsAStepFailure() {
        assertThrows(PatsStepError.class, () -> run(act(0, "ADD_ITEM", "QUANTITY", "2")).run());
        assertTrue(rmi.calls.isEmpty());
    }

    @Test
    void anUnwiredRegisterFailsTheStepRatherThanPassing() {
        ActionRegistry unwired = registryWith(SaleContext.of(screen, SaleRmi.UNAVAILABLE));
        CaseRun kase = caseRun(unwired, folder, Set.of(), act(0, "TOTAL"));

        PatsStepError failed = assertThrows(PatsStepError.class, kase::run);

        assertInstanceOf(DeviceUnavailable.class, failed.getCause());
        assertEquals("failed", kase.record().steps().get(0).outcome());
    }
}
