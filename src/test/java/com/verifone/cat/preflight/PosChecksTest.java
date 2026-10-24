package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * DESIGN.md 6.2 rows 1-5 against a fake POS. Ported from the POS half of the Python CAT's
 * {@code tests/unit/test_preflight_checks.py}. What this cannot show is that the register's
 * real surfaces report what the checks assume: that needs a rig.
 */
class PosChecksTest {

    private static PreflightReport runDefaults(RigContext rig) {
        return PreflightRunner.run(rig, DefaultChecks.defaults().checks(), PreflightRunner.RunOptions.defaults());
    }

    private static CheckRecord record(PreflightReport report, String name) {
        return report.records().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theDefaultRegistryHoldsTheFiveWiredPosChecksInTableOrder() {
        assertEquals(DefaultChecks.POS_CHECK_ORDER, DefaultChecks.defaults().names());
        assertEquals(5, DefaultChecks.defaults().size());
    }

    @Test
    void aHealthyRigPassesEveryPosCheck() {
        PreflightReport report = runDefaults(Fixtures.rig(new FakePos()));

        assertTrue(report.healthy(), report.render());
        assertEquals(5, report.records().size());
        assertTrue(report.skipped().isEmpty());
    }

    @Test
    void printerConnectedReopensThenResetsTheSerialLink() {
        FakePos pos = new FakePos();
        pos.printerOpen = false;
        pos.reopenOk = false;
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        assertEquals(List.of("reopen_printer_port", "reset_serial_connection"), pos.actions.subList(0, 2));
        assertTrue(report.healthy(), report.render());
        assertEquals("reset the serial connection", record(report, "printer_connected").repair().detail());
    }

    @Test
    void posIdleRequiresBothNoTransactionAndTheIdleScreen() {
        // The case worth catching: the transaction finished, an overlay is still up.
        FakePos pos = new FakePos();
        pos.idleScreen = false;
        Result result = new PosChecks.PosIdle().verify(Fixtures.rig(pos));

        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("idle screen is not displayed"), result.detail());
    }

    @Test
    void posIdleRepairRunsTheCancelLadderFirst() {
        FakePos pos = new FakePos();
        pos.transaction = true;
        pos.idleScreen = false;
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        assertEquals("cancel_ladder", pos.actions.get(0));
        assertTrue(report.healthy(), report.render());
    }

    @Test
    void noParkedTransactionRecallsAndVoidsEach() {
        FakePos pos = new FakePos();
        pos.parked.add(new Surfaces.ParkedTransaction("T1"));
        pos.parked.add(new Surfaces.ParkedTransaction("T2"));
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        assertEquals(List.of("recall_and_void:T1", "recall_and_void:T2"), pos.actions);
        assertTrue(report.healthy(), report.render());
        assertTrue(pos.parked.isEmpty());
    }

    @Test
    void aParkedTicketOnTheCommandersSummaryPassesWhenNoneIsCounted() {
        RigContext rig = Fixtures.rig(new FakePos());
        rig.setParkedTickets(() -> new Surfaces.UnsettledSuspends(0, ""));
        Result result = new PosChecks.NoParkedTransaction().verify(rig);

        assertEquals(Outcome.PASS, result.outcome(), result.toString());
        assertTrue(result.detail().contains("Day summary"), result.detail());
    }

    @Test
    void aParkedTicketOnTheSummaryFailsAndTheRepairDeclines() {
        FakePos pos = new FakePos();
        RigContext rig = Fixtures.rig(pos);
        rig.setParkedTickets(() -> new Surfaces.UnsettledSuspends(1, "18.25"));

        Result result = new PosChecks.NoParkedTransaction().verify(rig);
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("1 parked ticket(s) totalling 18.25"), result.detail());

        PreflightReport report = runDefaults(rig);
        CheckRecord record = record(report, "no_parked_transaction");
        assertEquals(Outcome.UNREPAIRABLE, record.repair().outcome());
        assertTrue(record.repair().detail().contains("SUSPEND recalls"), record.repair().detail());
        assertTrue(pos.actions.stream().noneMatch(a -> a.startsWith("recall_and_void")));
    }

    @Test
    void theParkedTicketCheckIsHeldBackMidRun() {
        RigContext rig = Fixtures.rig(new FakePos());
        rig.setParkedTickets(() -> new Surfaces.UnsettledSuspends(1, ""));
        PreflightReport report = PreflightRunner.run(rig, DefaultChecks.defaults().checks(),
                new PreflightRunner.RunOptions(true, PreflightRunner.VERIFY_BUDGET, true));

        assertTrue(report.records().stream().noneMatch(r -> r.name().equals("no_parked_transaction")));
        assertTrue(report.heldBack().containsKey("no_parked_transaction"));
        assertTrue(report.testable());
    }

    @Test
    void aPhysicallyOpenDrawerFailsLoudlyAndIsNeverRepaired() {
        FakePos pos = new FakePos();
        pos.drawer = true;
        pos.drawerBlocked = true;
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        CheckRecord record = record(report, "drawer_closed");
        assertEquals(Outcome.UNREPAIRABLE, record.repair().outcome());
        assertFalse(pos.actions.contains("close_drawer"));
        assertTrue(record.failedTwice());
        assertTrue(report.unhealthyChecks().contains("drawer_closed"));
    }

    @Test
    void aDrawerMerelyOpenIsClosedPromptFree() {
        FakePos pos = new FakePos();
        pos.drawer = true;
        pos.drawerBlocked = false;
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        assertTrue(pos.actions.contains("close_drawer"));
        assertTrue(report.healthy(), report.render());
    }

    @Test
    void aRigThatDeclaresNoDrawerPassesWithoutReadingOne() {
        FakePos pos = new FakePos();
        pos.drawer = true;
        pos.drawerBlocked = true;
        RigContext rig = Fixtures.rig(pos);
        rig.setCashDrawers(Map.of("topaz101", false));
        Result result = new PosChecks.DrawerClosed().verify(rig);

        assertEquals(Outcome.PASS, result.outcome(), result.toString());
        assertTrue(result.detail().contains("declares no cash drawer on topaz101"), result.detail());
    }

    @Test
    void aDrawerDeclaredOnAnyRegisterIsStillRead() {
        FakePos pos = new FakePos();
        pos.drawer = true;
        pos.drawerBlocked = true;
        for (Map<String, Boolean> declared : List.of(
                Map.of("topaz101", false, "topaz102", true),
                Map.of("topaz101", true))) {
            RigContext rig = Fixtures.rig(pos);
            rig.setCashDrawers(declared);
            Result result = new PosChecks.DrawerClosed().verify(rig);
            assertEquals(Outcome.FAIL, result.outcome(), declared.toString());
            assertTrue(result.detail().contains("PHYSICALLY OPEN"), result.detail());
        }
    }

    @Test
    void cashierSignedOnUsesTheRigCredentials() {
        FakePos pos = new FakePos();
        pos.cashier = null;
        PreflightReport report = runDefaults(Fixtures.rig(pos));

        assertTrue(pos.actions.contains("sign_on:PATS"), pos.actions.toString());
        assertTrue(report.healthy(), report.render());

        FakePos wrong = new FakePos();
        wrong.cashier = "SOMEONE_ELSE";
        Result result = new PosChecks.CashierSignedOn().verify(Fixtures.rig(wrong));
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("expected 'PATS'"), result.detail());
    }

    @Test
    void aRigWithNoDomReaderFallsBackToTheStatusSurfaceForCashier() {
        RigContext rig = Fixtures.rig(new FakePos());
        assertNull(rig.signedOn());
        assertEquals(Outcome.PASS, new PosChecks.CashierSignedOn().verify(rig).outcome());
    }
}
