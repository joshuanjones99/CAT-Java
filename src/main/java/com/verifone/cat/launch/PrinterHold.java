package com.verifone.cat.launch;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.pos.receipt.ReceiptPrinter;

import java.util.List;
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
 * <p>The receipts the register prints while CAT holds the port reach the run's stream ({@link ReceiptRelay}, the
 * Python's {@code relay_receipts} / {@code _ReceiptRelay}): each as the {@link TopazPrinter#RECEIPT_LOGGER} lines the
 * printer logs ({@code ┌ receipt N on COM1 ...}, {@code │ <line>}, {@code └ end of receipt N}), with no step, since a
 * hold spans System Checks to Tests Run, so the Receipt Printer row's view shows them. {@link #launchRow} is Launch
 * Simulators' row for the printer outside a run (the Python's {@code launch_row}).
 *
 * <p>Deviations from the Python: the not-held line is a WARNING where the Python's is INFO. The relay reads the
 * printer's receipts every {@link ReceiptRelay#POLL} rather than hooking its logger (slf4j-simple has no handlers to
 * add): the receipt lines and the open and close lines are relayed; the printer's unknown-command and read-failed
 * records are not (they stay in the process log). HOOK: a receipt listener on {@code TopazPrinter} would make the
 * relay exact.
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
        ctx.heldPrinter(printer instanceof ReceiptPrinter receipts
                ? new ReceiptRelay(ctx, port, receipts, printer) : printer);
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

    /**
     * Launch Simulators' row for the printer, which it does not hold: is the port listed. Outside a run nothing answers
     * the register's printer, so the row is {@code stopped} and says when CAT does; {@code failed} is the USB-serial
     * adapter gone from Windows; a probe that could not tell is {@code needs attention}. Read as step 2's Connections
     * row reads it. Nothing when the rig names no printer port. The Python's {@code launch_row}.
     */
    static void launchRow(RunContext ctx, java.util.function.Function<com.verifone.cat.connections.Target,
            com.verifone.cat.connections.ProbeResult> probe) {
        if (ctx.rig() == null) {
            return;
        }
        java.util.Optional<com.verifone.cat.connections.Target> target =
                com.verifone.cat.connections.Endpoints.printerTarget(ctx.rig().rig());
        if (target.isEmpty()) {
            return;
        }
        com.verifone.cat.connections.ProbeResult result = probe.apply(target.get());
        String state;
        String detail;
        if (result.state() == com.verifone.cat.connections.ProbeState.UP) {
            state = "stopped";
            detail = result.detail() + "; CAT holds it during a run's setup and tests";
        } else if (result.state() == com.verifone.cat.connections.ProbeState.DOWN) {
            state = "failed";
            detail = result.detail();
        } else {
            state = "needs attention";
            detail = result.detail();
        }
        row(ctx, state, detail);
    }

    /**
     * The printer CAT holds, with the relay of what it prints into the run's stream: {@link #close} closes the printer,
     * then relays its last receipts and its closing line, then ends the relay (the Python's {@code HeldPrinter}).
     */
    static final class ReceiptRelay implements AutoCloseable {

        /** How often the relay reads the printer's receipts. */
        static final java.time.Duration POLL = java.time.Duration.ofMillis(500);

        private final RunContext ctx;
        private final String port;
        private final ReceiptPrinter receipts;
        private final AutoCloseable printer;
        private final java.util.function.Supplier<String> during;
        private final Thread thread;
        private volatile boolean stopped;
        private int relayed;

        ReceiptRelay(RunContext ctx, String port, ReceiptPrinter receipts, AutoCloseable printer) {
            this(ctx, port, receipts, printer, () -> "");
        }

        /**
         * @param printer what {@link #close} closes first (the step-5 session's relay passes a no-op: the connector owns
         *                that printer)
         * @param during  the key of the case running now, or "": a receipt's start line names it ({@code during
         *                PETROCORE-61334}), as the Python's {@code _log_receipt} reads {@code case_record.current}
         */
        ReceiptRelay(RunContext ctx, String port, ReceiptPrinter receipts, AutoCloseable printer,
                java.util.function.Supplier<String> during) {
            this.ctx = ctx;
            this.port = port;
            this.receipts = receipts;
            this.printer = printer;
            this.during = during;
            line(port + " open: answering the register as its printer");
            this.thread = new Thread(this::loop, "printer-relay-" + port);
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            while (!stopped) {
                java.util.concurrent.locks.LockSupport.parkNanos(POLL.toNanos());
                relay();
            }
        }

        /** Relays every receipt printed since the last call. Never throws: a lost port ends the relay. */
        synchronized void relay() {
            List<List<String>> fresh;
            try {
                if (receipts.receiptCount() <= relayed) {
                    return;
                }
                fresh = receipts.receiptsSince(relayed);
            } catch (RuntimeException e) {
                stopped = true;
                return;
            }
            String key = during.get();
            String tag = key == null || key.isEmpty() ? "" : " during " + key;
            for (List<String> lines : fresh) {
                relayed++;
                line(TopazPrinter.RECEIPT_START + "receipt " + relayed + " on " + port + ", " + lines.size()
                        + " lines [" + TopazPrinter.receiptKey(lines) + "]" + tag);
                for (String text : lines) {
                    line(TopazPrinter.RECEIPT_LINE + text);
                }
                line(TopazPrinter.RECEIPT_END + "end of receipt " + relayed);
            }
        }

        private void line(String message) {
            ctx.emit(new CatEvent.LogLine("INFO", TopazPrinter.RECEIPT_LOGGER, message, ""));
        }

        @Override
        public void close() throws Exception {
            try {
                printer.close();
            } finally {
                stopped = true;
                thread.interrupt();
                relay(); // after the close, so its last receipt and its own line are relayed too
                line(port + " closed");
            }
        }
    }

    /** A receipt check's verdict line on the printer's log: the Python's {@code topaz_printer.RECEIPT_CHECK}. */
    static final String RECEIPT_CHECK = com.verifone.cat.runservice.ReceiptRoll.RECEIPT_CHECK;

    /** One line of a receipt check's diff on the printer's log: the Python's {@code topaz_printer.RECEIPT_DIFF}. */
    static final String RECEIPT_DIFF = com.verifone.cat.runservice.ReceiptRoll.RECEIPT_DIFF;

    /**
     * A receipt check's verdict on the printer's log, for the Receipt Printer's roll: {@code ╞ <receipt key> <case>
     * passed|failed <name>}, then its diff a line at a time as {@code ┊ <line>}. The Python's
     * {@code case_record.receipt_checked}; the roll puts the verdict beside the receipt with the same key.
     */
    static void receiptChecked(RunContext ctx, com.verifone.cat.report.CaseRecord.ReceiptCheck check, String step) {
        ctx.emit(new CatEvent.LogLine("INFO", TopazPrinter.RECEIPT_LOGGER, RECEIPT_CHECK + check.key() + " "
                + (check.caseKey().isEmpty() ? "-" : check.caseKey()) + " " + (check.passed() ? "passed" : "failed")
                + " " + (check.name().isEmpty() ? "-" : check.name()), step));
        for (String line : check.diff().split("\n", -1)) {
            if (!check.diff().isEmpty()) {
                ctx.emit(new CatEvent.LogLine("INFO", TopazPrinter.RECEIPT_LOGGER, RECEIPT_DIFF + line, step));
            }
        }
    }

    /** The Receipt Printer's row: one {@code SimulatorState}, as step 3 sends for each simulator. */
    static void row(RunContext ctx, String state, String detail) {
        String port = port(ctx);
        ctx.emit(new CatEvent.SimulatorState(TopazPrinter.PRINTER_NAME, state, "supervised", "CAT",
                port == null ? "" : port, detail));
    }
}
