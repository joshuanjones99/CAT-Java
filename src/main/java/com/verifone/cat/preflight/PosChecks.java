package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.verifone.cat.preflight.Surfaces.ParkedTransaction;
import com.verifone.cat.preflight.Surfaces.UnsettledSuspends;

/**
 * The POS-side checks, DESIGN.md 6.2 rows 1-5. Ported from the Python CAT's
 * {@code petrotest/preflight/checks/pos.py}.
 *
 * <p>Every probe goes through {@link Surfaces.PosStatus} and {@link Surfaces.PosControl}. In this
 * port those are backed by {@link ShimPosStatus} (the pos-shim's pushed screen) and by no control
 * surface yet, so {@code pos_idle} is the one row that reads a real value, and the repairs raise
 * {@link com.verifone.cat.devices.DeviceUnavailable} (ERROR) until the control path is ported.
 *
 * <p>Python's DOM readers ({@code RigContext.screen_idle}, {@code signed_on}, {@code printer_ports})
 * and the Commander's suspended-ticket summary ({@code RigContext.parked_tickets}) are not ported;
 * the checks keep the Python fallbacks for when those are null.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing here has run against a Topaz register.
 */
public final class PosChecks {

    private PosChecks() {}

    /** Row 1. The printer port must be open on the register (or listed by this controller). */
    public static final class PrinterConnected extends BaseCheck {

        public PrinterConnected() {
            super("printer_connected", Duration.ofSeconds(30));
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.printerPorts() != null) {
                Map<String, Boolean> ports = rig.printerPorts().get();
                List<String> missing = new ArrayList<>();
                ports.forEach((port, listed) -> {
                    if (!listed) {
                        missing.add(port);
                    }
                });
                if (!missing.isEmpty()) {
                    return Result.failed(name(), "not listed on this controller: " + String.join(", ", missing)
                            + " — the USB-serial adapter has dropped off Windows; unplug and replug it");
                }
                return Result.passed(name(), "listed on this controller: " + String.join(", ", ports.keySet())
                        + " (the register's own view of its printer is not read)");
            }
            if (rig.posStatus().printerPortOpen()) {
                return Result.passed(name());
            }
            return Result.failed(name(), "printer port is not open on the register");
        }

        @Override
        public Result repair(RigContext rig) {
            if (rig.printerPorts() != null) {
                return Result.unrepairable(name(), "a serial adapter missing from Windows comes back only when replugged");
            }
            // Reopen first, reset the serial link only if that does not take. Both are idempotent.
            if (rig.posControl().reopenPrinterPort()) {
                return new Result(name(), Outcome.REPAIRED, "reopened the printer port");
            }
            if (rig.posControl().resetSerialConnection()) {
                return new Result(name(), Outcome.REPAIRED, "reset the serial connection");
            }
            return Result.failed(name(), "printer port would not reopen and the serial reset failed — check the "
                    + "PC COM1 -> Topaz COM4 cable (DESIGN.md 3.4 checklist)");
        }
    }

    /**
     * Row 2. Both halves are required: no active transaction, and the idle screen up. The shim's
     * pushed state can say idle while an overlay is still showing, and that is the case worth catching.
     */
    public static final class PosIdle extends BaseCheck {

        public PosIdle() {
            super("pos_idle", Duration.ofSeconds(45));
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.screenIdle() != null) {
                if (rig.screenIdle().getAsBoolean()) {
                    return Result.passed(name(), "idle sales screen, read from the register's DOM");
                }
                return Result.failed(name(), "the register is not on the idle sales screen (read from its DOM): "
                        + "an open ticket, an overlay, or no cashier signed on");
            }
            if (rig.posStatus().transactionActive()) {
                return Result.failed(name(), "a transaction is still active");
            }
            if (!rig.posStatus().idleScreenPresent()) {
                return Result.failed(name(), "no transaction, but the idle screen is not displayed");
            }
            return Result.passed(name());
        }

        @Override
        public Result repair(RigContext rig) {
            // Rungs 1 and 2 of the cancel/void ladder (DESIGN.md 6.3). Not the whole ladder.
            if (rig.posControl().cancelLadder()) {
                return new Result(name(), Outcome.REPAIRED, "cancel/escape ladder returned POS to idle");
            }
            if (rig.posControl().voidOpenTicket()) {
                return new Result(name(), Outcome.REPAIRED, "voided the open ticket");
            }
            return Result.failed(name(), "cancel ladder and void both failed to reach the idle screen");
        }
    }

    /**
     * Row 3. A parked transaction survives a POS restart and changes the totals of whatever runs
     * next. Held back mid-run when the Commander's summary is the source (HANDOFF.md 1.128).
     */
    public static final class NoParkedTransaction extends BaseCheck {

        public NoParkedTransaction() {
            super("no_parked_transaction", Duration.ofSeconds(60));
        }

        @Override
        public String operatorOnly(RigContext rig) {
            if (rig.parkedTickets() == null) {
                return "";
            }
            return "a case may park a ticket for the next one to recall (36977, then 36978), so a parked "
                    + "ticket mid-run is not a fault; run `petrotest preflight` (CAT's `p`) for this row";
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.parkedTickets() != null) {
                UnsettledSuspends unsettled = rig.parkedTickets().get();
                if (unsettled.count() == 0) {
                    return Result.passed(name(), "the Commander's Day summary counts no unsettled suspended ticket");
                }
                String total = unsettled.amount().isEmpty() ? "" : " totalling " + unsettled.amount();
                return Result.failed(name(), unsettled.count() + " parked ticket(s)" + total
                        + ", per the Commander's Day summary (suspendUnsettled)");
            }
            List<ParkedTransaction> parked = rig.posStatus().parkedTransactions();
            if (parked.isEmpty()) {
                return Result.passed(name());
            }
            List<String> ids = new ArrayList<>();
            for (ParkedTransaction transaction : parked.subList(0, Math.min(5, parked.size()))) {
                ids.add(transaction.ticketId());
            }
            return Result.failed(name(), parked.size() + " parked transaction(s): " + String.join(", ", ids));
        }

        @Override
        public Result repair(RigContext rig) {
            if (rig.parkedTickets() != null) {
                return Result.unrepairable(name(), "the Commander's summary gives a count, not ticket numbers, and no "
                        + "recall has been built: at the register's idle screen, SUSPEND recalls a parked ticket; "
                        + "void it, and repeat until none is left");
            }
            List<String> voided = new ArrayList<>();
            List<String> stuck = new ArrayList<>();
            for (ParkedTransaction transaction : rig.posStatus().parkedTransactions()) {
                if (rig.posControl().recallAndVoid(transaction.ticketId())) {
                    voided.add(transaction.ticketId());
                } else {
                    stuck.add(transaction.ticketId());
                }
            }
            if (!stuck.isEmpty()) {
                return Result.failed(name(), "voided " + voided.size() + ", could not void " + stuck.size() + ": "
                        + String.join(", ", stuck));
            }
            return new Result(name(), Outcome.REPAIRED, "recalled and voided " + voided.size());
        }
    }

    /**
     * Row 4. "Prompt-free close; fail loudly if physically open." A drawer a person left open is
     * not something software should retry.
     */
    public static final class DrawerClosed extends BaseCheck {

        public DrawerClosed() {
            super("drawer_closed", Duration.ofSeconds(15));
        }

        @Override
        public Result verify(RigContext rig) {
            Map<String, Boolean> drawers = rig.cashDrawers() == null ? Map.of() : rig.cashDrawers();
            if (!drawers.isEmpty() && drawers.values().stream().noneMatch(v -> !Boolean.FALSE.equals(v))) {
                return Result.passed(name(), "the rig file declares no cash drawer on "
                        + String.join(", ", drawers.keySet()) + " (cash_drawer: false); nothing was read");
            }
            if (!rig.posStatus().drawerOpen()) {
                return Result.passed(name());
            }
            if (rig.posStatus().drawerPhysicallyBlocked()) {
                return Result.failed(name(), "cash drawer is PHYSICALLY OPEN — needs a human at the rig");
            }
            return Result.failed(name(), "cash drawer is open");
        }

        @Override
        public Result repair(RigContext rig) {
            if (rig.posStatus().drawerPhysicallyBlocked()) {
                return Result.unrepairable(name(), "drawer is physically open; no software repair exists — close it at the rig");
            }
            if (rig.posControl().closeDrawer()) {
                return new Result(name(), Outcome.REPAIRED, "closed the drawer (prompt-free)");
            }
            return Result.failed(name(), "prompt-free close did not take");
        }
    }

    /** Row 5. The expected cashier session is active; repair signs on with the rig's credentials. */
    public static final class CashierSignedOn extends BaseCheck {

        public CashierSignedOn() {
            super("cashier_signed_on", Duration.ofSeconds(30));
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.signedOn() != null) {
                // Read from the register's DOM, which does not name the cashier.
                if (rig.signedOn().getAsBoolean()) {
                    return Result.passed(name(), "a cashier is signed on, read from the register's DOM "
                            + "(which does not say which cashier)");
                }
                return Result.failed(name(), "no cashier is signed on: the register is at its login screen");
            }
            String expected = rig.expectations().cashier();
            Optional<String> current = rig.posStatus().cashierSignedOn();
            if (current.isEmpty()) {
                return Result.failed(name(), "no cashier is signed on");
            }
            if (!expected.isEmpty() && !expected.equals(current.get())) {
                return Result.failed(name(), "signed on as '" + current.get() + "', expected '" + expected + "'");
            }
            return Result.passed(name(), "signed on as '" + current.get() + "'");
        }

        @Override
        public Result repair(RigContext rig) {
            String expected = rig.expectations().cashier();
            if (expected.isEmpty()) {
                return Result.failed(name(), "rig declares no expected cashier, so none can be signed on");
            }
            if (rig.posControl().signOn(expected, rig.expectations().cashierPassword())) {
                return new Result(name(), Outcome.REPAIRED, "signed on as '" + expected + "'");
            }
            return Result.failed(name(), "sign-on as '" + expected + "' was rejected");
        }
    }
}
