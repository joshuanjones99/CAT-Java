package com.verifone.cat.launch;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.Rig;

/**
 * CAT's hold of the register's receipt printer port during a run's setup: the Python's
 * {@code cat.launch.steps.commander._hold_printer_port} and {@code _release_printer_port}, with the Receipt Printer row
 * of {@code cat.launch.printer} ({@code printer_row}).
 *
 * <p>{@link TopazPrinter} on the controller's port <em>is</em> the register's Epson: it answers the ID and status
 * queries the register's {@code PrinterMonitor} sends about every ten minutes. Until one has succeeded after a POS
 * start, the register's printer control is null and the first print job is dropped; and a port left unanswered between
 * launches makes the register back off its retests past the run's start (Python HANDOFF §1.106, runs
 * {@code 38b931774e45}, {@code aaa86ab51eb2}). PATS's own emulator holds the port for its whole run, so CAT holds it
 * too: from System Checks, once the printer's lines pass, and again (idempotent) after step 4's {@code provision}; it is
 * released the moment before step 5's session opens the port itself ({@link #release} with {@code lent}), and by the
 * launcher when a run ends any other way. Nothing is printed and no checklist row is added; the step's log says what
 * happened, and the Receipt Printer's {@code simulator.state} row reads {@code ready}, {@code failed},
 * {@code needs attention} or {@code stopped}.
 *
 * <p>Hard rule 5: a port that will not open (missing, busy) is a {@code failed} row and a WARNING line naming the port
 * and why; a {@link DeviceUnavailable} (no opener on this machine) is {@code needs attention}. Never a silent pass. As
 * in the Python, the hold itself never fails a step: the printer's readiness line in System Checks and the session's
 * printer are what fail a run.
 *
 * <p>Deviations from the Python: the receipts the register prints while CAT holds the port go to
 * {@link TopazPrinter#RECEIPT_LOGGER} only; the Python's {@code relay_receipts}, which copies them into the run's
 * event stream, is not ported. The not-held line is a WARNING where the Python's is INFO.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested with a fake opener only; nothing here has held COM1 or seen
 * Reg101 print.
 */
final class PrinterHold {

    /** Opens a register's printer port as its printer, answering on it until closed. */
    @FunctionalInterface
    interface Opener {

        /**
         * @throws DeviceError when the port will not open ({@code DeviceUnreachable}), or {@link DeviceUnavailable}
         *                     when this machine cannot open one at all
         */
        AutoCloseable open(String port);

        /** The production opener: {@link TopazPrinter#open}, which starts answering at once. */
        Opener TOPAZ = TopazPrinter::open;

        /** No opener given (a test's stages): every open is {@link DeviceUnavailable}, never a pretend hold. */
        Opener NONE = port -> {
            throw new DeviceUnavailable("the receipt printer on " + port, "no printer opener given to these stages");
        };
    }

    private PrinterHold() {
    }

    /** The primary register's {@code printer_port}, the one port CAT and the session hold; or null. */
    static String port(RunContext ctx) {
        if (ctx.rig() == null) {
            return null;
        }
        Rig.Register primary = ctx.rig().rig().primaryRegister();
        String port = primary == null ? null : primary.printerPort();
        return port == null || port.isBlank() ? null : port;
    }

    /**
     * Opens the primary register's printer port with {@code opener} and keeps it on {@link RunContext#heldPrinter}.
     * Nothing when it is already held, on a fake-POS run, or with no rig or no {@code printer_port}. A port that will
     * not open is logged and its row set, and left alone.
     */
    static void hold(RunContext ctx, StepEmitter step, Opener opener) {
        String port = port(ctx);
        if (ctx.heldPrinter() != null || ctx.config().fakePos() || port == null) {
            return;
        }
        AutoCloseable printer;
        try {
            printer = opener.open(port);
        } catch (DeviceUnavailable e) {
            notHeld(ctx, step, port, "needs attention", e);
            return;
        } catch (DeviceError | IllegalStateException e) {
            notHeld(ctx, step, port, "failed", e);
            return;
        }
        ctx.heldPrinter(printer);
        step.log("holding " + port + " as the register's printer until the tests start", "INFO");
        row(ctx, "ready", "CAT holds it for the register until the tests start");
    }

    private static void notHeld(RunContext ctx, StepEmitter step, String port, String state, RuntimeException e) {
        step.log("printer port " + port + " not held during setup (" + e.getMessage() + ")", "WARNING");
        row(ctx, state, e.getMessage());
    }

    /**
     * Closes CAT's hold, if it holds the port. {@code lent}: the test session takes the port next and says so on the
     * row itself; otherwise the row reads {@code stopped}.
     */
    static void release(RunContext ctx, boolean lent) {
        AutoCloseable printer = ctx.heldPrinter();
        if (printer == null) {
            return;
        }
        ctx.heldPrinter(null);
        try {
            printer.close();
        } catch (Exception e) {
            // As the Python's contextlib.suppress: the port is let go either way.
        }
        if (!lent) {
            row(ctx, "stopped", "released by CAT; nothing holds it");
        }
    }

    /** The Receipt Printer's row: one {@code SimulatorState}, as step 3 sends for each simulator. */
    static void row(RunContext ctx, String state, String detail) {
        String port = port(ctx);
        ctx.emit(new CatEvent.SimulatorState(TopazPrinter.PRINTER_NAME, state, "supervised", "CAT",
                port == null ? "" : port, detail));
    }
}
