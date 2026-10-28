package com.verifone.cat.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;

/**
 * The pinpad link: {@code emv-keys} until the Commander's EPS reaches the Petro Simulator Suite's ViperPay pinpad. A
 * port of the Python CAT's {@code cat.launch.steps.simulators._link_pinpad_simulator} (the rig owner's procedure of
 * 2026-09-14).
 *
 * <p>Success is read from the simulator's own log: a new {@code DeviceRequest Receieved from EPS} line (Petro's
 * spelling) after a byte mark. First, when step 3 marked the log ({@link RunContext#epsLogMark}), the EPS may already
 * have reached Petro by itself: for a Petro this run started it gets {@link Timing#reconnectWait} to do so, for an
 * adopted one a single look. Then up to two attempts: {@code emv-keys}, then {@link Timing#linkTimeout} for a new
 * request line; after the first silent attempt the simulator is restarted once, and only one CAT itself started (D9).
 * An adopted Petro fails the link with that said.
 *
 * <p>A {@code pinpad: vats} rig's link ({@code _link_vats_pinpad}, a new socket on the Commander to the M400) is not
 * ported: the caller reports it unavailable. When {@code emv-keys} is unavailable, so is this link: the
 * {@link StageUnavailable} the stage throws is the caller's to report.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PinpadLink {

    /** The line Petro Simulator Suite logs for every EPS request, in its spelling. */
    static final byte[] EPS_REQUEST_MARKER = "DeviceRequest Receieved from EPS".getBytes(StandardCharsets.US_ASCII);

    /** The checklist row, as the Python's {@code step.task} label. */
    static final String LABEL = "Pinpad link (the EPS reaches the pinpad)";

    /**
     * The waits: the Python's {@code EPS_LINK_TIMEOUT} after a download, {@code EPS_RECONNECT_WAIT} for a Petro this
     * run started, and the 2 s poll of {@code _eps_reached}.
     */
    record Timing(Duration linkTimeout, Duration reconnectWait, Duration poll) {

        /** The Python's: 60 s, 60 s, 2 s. */
        static final Timing PRODUCTION = new Timing(Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(2));
    }

    /** One {@code emv-keys} run; true when it exited 0. May throw {@link StageUnavailable}. */
    @FunctionalInterface
    interface EmvKeys {
        boolean run(Say say);
    }

    /** Stops the CAT-started instance of a simulator and starts it again: "" when it is ready, else why not. */
    @FunctionalInterface
    interface Restart {
        String restart(String simulator);
    }

    /** How the link ended: passed, or the step's failure detail. */
    record Result(boolean linked, String detail) {}

    private final Pauser pauser;
    private final Timing timing;

    PinpadLink(Pauser pauser, Timing timing) {
        this.pauser = pauser;
        this.timing = timing;
    }

    /**
     * Links the EPS to the pinpad simulator.
     *
     * @param logPath  the simulator's log, or null when its registry row names none
     * @param mark     step 3's mark in that log, or null
     * @param started  whether this run started the simulator (rather than adopting it)
     * @param emvKeys  one {@code emv-keys} run
     * @param restart  the one restart
     */
    Result link(StepEmitter step, String rigName, Path logPath, Long mark, boolean started, EmvKeys emvKeys,
            Restart restart) {
        String simulator = StartSimulatorsStep.PETRO_SIMULATOR;
        if (logPath == null) {
            return new Result(false, "'" + simulator + "' has no registry row with a log to confirm the link");
        }
        if (mark != null && reached(logPath, mark, started ? timing.reconnectWait() : Duration.ZERO)) {
            step.log("PASS  the Commander's EPS reached " + simulator + "; no key download needed", "INFO");
            return new Result(true, "");
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            long before = sizeOf(logPath);
            step.log("CAT-Java emv-keys --rig " + rigName + " (attempt " + attempt + ")", "INFO");
            CommanderConfigurationStep.StepSay emvSay = new CommanderConfigurationStep.StepSay(step);
            if (!emvKeys.run(emvSay)) {
                String detail = emvSay.lastErr().isEmpty() ? "emv-keys failed" : emvSay.lastErr();
                return new Result(false, detail);
            }
            if (reached(logPath, before, timing.linkTimeout())) {
                step.log("PASS  the Commander's EPS reached " + simulator, "INFO");
                return new Result(true, "");
            }
            step.log("FAIL  no EPS request in " + logPath.getFileName() + " within "
                    + timing.linkTimeout().toSeconds() + "s", "WARNING");
            if (attempt == 1) {
                String why = restart.restart(simulator);
                if (!why.isEmpty()) {
                    return new Result(false, why);
                }
            }
        }
        return new Result(false, "the EPS never reached " + simulator + ", even after a restart");
    }

    /** True once {@code log} gains an EPS request line after byte {@code mark}; a zero timeout looks once. */
    boolean reached(Path log, long mark, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            return seen(log, mark);
        }
        return pauser.until(() -> seen(log, mark), timeout, timing.poll());
    }

    /** One look: the marker after {@code mark}, or from the start when the log shrank (rotated or truncated). */
    static boolean seen(Path log, long mark) {
        long start = sizeOf(log) >= mark ? mark : 0;
        try (InputStream in = Files.newInputStream(log)) {
            in.skipNBytes(start);
            return indexOf(in.readAllBytes(), EPS_REQUEST_MARKER) >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The file's size, or 0 when it cannot be read. */
    static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
