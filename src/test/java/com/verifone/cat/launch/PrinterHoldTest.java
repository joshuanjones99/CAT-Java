package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.rig.RigLoader;

/**
 * CAT's printer-port hold ({@link PrinterHold}) with a fake opener: taken once, released for the session or at the
 * run's end, and every port that will not open reported on its row. No serial port is opened.
 */
class PrinterHoldTest {

    @TempDir
    Path dir;

    private int runs;

    private RunContext context(boolean fakePos) throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        Files.writeString(paths.rigsDir().resolve("held.yaml"), """
                name: held
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                registers:
                  - id: topaz101
                    host: register.invalid
                    printer_port: COM3
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                build:
                  expected_version: "057.02"
                """);
        Path folder = dir.resolve("run" + runs++);
        RunContext ctx = new RunContext(new LaunchConfig.Builder("held").fakePos(fakePos).build(), paths,
                "eeeeeeeeeeee", folder, RunEvents.open(folder, Clock.systemUTC()), new PauseGate(),
                Clock.systemUTC());
        ctx.rig(RigLoader.loadResolved("held", paths.rigsDir()));
        return ctx;
    }

    private static List<String> rows(RunContext ctx) throws IOException {
        ctx.closeEvents();
        List<String> rows = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            if (event.get("type").asText().equals("simulator.state")) {
                rows.add(event.get("name").asText() + ": " + event.get("state").asText() + " "
                        + event.get("bind").asText());
            }
        }
        return rows;
    }

    @Test
    void theHoldIsTakenOnceAndGivenBackForTheSessionWithNoStoppedRow() throws IOException {
        RunContext ctx = context(false);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);
        List<String> opened = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        PrinterHold.Opener opener = port -> {
            opened.add(port);
            return () -> closed.add(port);
        };

        PrinterHold.hold(ctx, step, opener);
        PrinterHold.hold(ctx, step, opener); // step 4's, after provision: already held
        PrinterHold.release(ctx, true);
        PrinterHold.release(ctx, false); // the launcher's, at the run's end: nothing left to release

        assertEquals(List.of("COM3"), opened);
        assertEquals(List.of("COM3"), closed);
        assertNull(ctx.heldPrinter());
        assertEquals(List.of("Receipt Printer: ready COM3"), rows(ctx));
    }

    @Test
    void aRunThatEndsBeforeItsTestsReleasesThePortAndSaysSo() throws IOException {
        RunContext ctx = context(false);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);
        PrinterHold.hold(ctx, step, port -> () -> { });
        PrinterHold.release(ctx, false);

        assertEquals(List.of("Receipt Printer: ready COM3", "Receipt Printer: stopped COM3"), rows(ctx));
    }

    @Test
    void aBusyPortIsAFailedRowAndNoOpenerIsNeedsAttentionNeverAPretendHold() throws IOException {
        RunContext ctx = context(false);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);
        PrinterHold.hold(ctx, step, port -> {
            throw new DeviceUnreachable(port, "in use");
        });
        assertNull(ctx.heldPrinter());
        PrinterHold.hold(ctx, step, PrinterHold.Opener.NONE);
        assertNull(ctx.heldPrinter());

        assertEquals(List.of("Receipt Printer: failed COM3", "Receipt Printer: needs attention COM3"), rows(ctx));
    }

    @Test
    void aFakePosRunHoldsNothing() throws IOException {
        RunContext ctx = context(true);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);
        List<String> opened = new ArrayList<>();
        PrinterHold.hold(ctx, step, port -> {
            opened.add(port);
            return () -> { };
        });
        assertTrue(opened.isEmpty());
        assertEquals(List.of(), rows(ctx));
    }

    /** A fake printer: receipts added by the test, read by the relay. */
    private static final class FakePrinter implements com.verifone.cat.pos.receipt.ReceiptPrinter, AutoCloseable {
        final List<List<String>> printed = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean closed;

        @Override
        public int receiptCount() {
            return printed.size();
        }

        @Override
        public List<List<String>> receiptsSince(int index) {
            return List.copyOf(printed.subList(Math.min(index, printed.size()), printed.size()));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static List<String> receiptLines(RunContext ctx) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            if (event.get("type").asText().equals("log.line")
                    && event.get("logger").asText().equals(com.verifone.cat.devices.printer.TopazPrinter.RECEIPT_LOGGER)) {
                lines.add(event.get("message").asText());
            }
        }
        return lines;
    }

    /** The Python's relay_receipts: what the register prints while CAT holds the port reaches the run's stream. */
    @Test
    void theReceiptsPrintedDuringTheHoldAreRelayedIntoTheRun() throws Exception {
        RunContext ctx = context(false);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);
        FakePrinter printer = new FakePrinter();
        PrinterHold.hold(ctx, step, port -> printer);
        printer.printed.add(List.of("STORE 1", "TOTAL 1.00"));
        PrinterHold.release(ctx, true);
        ctx.closeEvents();

        assertTrue(printer.closed);
        List<String> lines = receiptLines(ctx);
        assertEquals("COM3 open: answering the register as its printer", lines.get(0));
        assertTrue(lines.get(1).startsWith("┌ receipt 1 on COM3, 2 lines ["), lines.toString());
        assertEquals(List.of("│ STORE 1", "│ TOTAL 1.00", "└ end of receipt 1", "COM3 closed"),
                lines.subList(2, lines.size()));
    }

    /** Launch Simulators' row: a listed port is stopped (CAT holds it only in a run), a missing one failed. */
    @Test
    void theLaunchRowSaysWhetherThePortIsListed() throws IOException {
        RunContext ctx = context(false);
        PrinterHold.launchRow(ctx, target -> new com.verifone.cat.connections.ProbeResult(target.name(),
                com.verifone.cat.connections.ProbeState.UP, "COM3 is listed", target.kind()));
        PrinterHold.launchRow(ctx, target -> new com.verifone.cat.connections.ProbeResult(target.name(),
                com.verifone.cat.connections.ProbeState.DOWN, "COM3 is not listed", target.kind()));
        PrinterHold.launchRow(ctx, target -> new com.verifone.cat.connections.ProbeResult(target.name(),
                com.verifone.cat.connections.ProbeState.UNKNOWN, "?", target.kind()));

        assertEquals(List.of("Receipt Printer: stopped COM3", "Receipt Printer: failed COM3",
                "Receipt Printer: needs attention COM3"), rows(ctx));
    }
}
