package com.verifone.cat.launch;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.forecourt.DcrDeviceAdapter;
import com.verifone.cat.devices.forecourt.DcrLink;
import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.FuelPumpsAdapter;
import com.verifone.cat.devices.forecourt.PetroDcr;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Petro Simulator Suite's pumps and DCR 1 for the whole of step 5, as the Python's pytest session holds them
 * ({@code petrotest.pos.plugin}: {@code _petro_pumps_session}, {@code fuel_pumps}, {@code _petro_dcr_session},
 * {@code dcr}, {@code _recover_dcr_link}).
 *
 * <ul>
 *   <li><b>One listener each, for the session.</b> Started at the step's start, before the first case, when a case of
 *       the run declares {@code fuel_pumps} (or {@code dcr}) on a {@code forecourt: petro} rig. Petro pushes every pump
 *       state to PATS's 1072 whichever case runs, and a closed port froze its fuel side (Reg101, 2026-09-22); it pushes
 *       a DCR display only when it changes, so a listener opened per case never heard the WELCOME DCR 1 showed. A
 *       listener that will not start is logged and kept as the reason: the cases that declare it fail at their setup,
 *       naming it; the others run.</li>
 *   <li><b>A case's setup</b> ({@link #beginCase}): a case declaring either fixture on a rig without
 *       {@code forecourt: petro}, or with no rig, fails with {@link DeviceUnavailable}. A DCR case first reads Petro's
 *       log for DCR 1's link ({@link DcrLink#fault}); a fault gets one recovery per session
 *       ({@link CommanderInit#recoverDcrLink}: the DCR inits, then an FCC restart and the inits again). When that does
 *       not bring DCR 1 back, this case and every later DCR case fail at once with {@link DeviceUnreachable} rather
 *       than each waiting out a prompt (26 cases, 52 minutes, in run 33e23af47960). A recovery that worked does not stop
 *       a later drop being recovered.</li>
 *   <li><b>A case's teardown</b> ({@link #afterCase}): after a case that declared {@code fuel_pumps} and failed before
 *       it finished its actions, every pump it touched is put back to IDLEOFF ({@link FuelPumps#clear}) and, when it
 *       declared {@code pos}, the fills due on it are paid off in CASH ({@link Pos#settleDueFuel}); a pump left
 *       reserved is logged for a person. Not after a case that failed only on its validations: every action ran as PATS
 *       runs it, and a fill stacked there belongs to the next case (Penny's 133235 and 131297, 2026-10-14). Nothing
 *       there raises: the case's own failure is the one reported. None of this is a PATS step.</li>
 * </ul>
 *
 * <p>Deviation: the Python's {@code PetroPumps.touched} is cleared at each fuel case's setup; the Java client exposes
 * only a copy, so the pumps a case touched are recorded here, by the session's {@link FuelPumps} wrapper, from every
 * call naming a pump (lift, fill, hang-up, state read, waits).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested against loopback fakes of the suite's sockets and a fake
 * recovery; no Java run has heard a pump or a DCR.
 */
final class PetroSession implements AutoCloseable {

    /** DCR 1, the one DCR a rig drives. */
    static final int DCR_NUMBER = 1;

    /** Where the session's lines go: {@code (level, message)}. */
    @FunctionalInterface
    interface Log {
        void line(String level, String message);
    }

    /** The suite's devices and the DCR link's reads, from the rig. A test passes fakes over loopback. */
    interface Devices {

        /** The pump client, listening on PATS's port and logged in. */
        PetroPumps startPumps();

        /** DCR 1's client, listening on PATS's DCR port and logged in. */
        PetroDcr startDcr();

        /** Why DCR {@code dcr} is not connected to the Commander, from Petro's log; null when it is, or unknown. */
        String dcrLinkFault(int dcr);

        /** Brings DCR {@code dcr} back; empty when it is driven again, else why not. */
        Optional<String> recoverDcrLink(int dcr, Log log);

        /** The production devices: the rig controller's suite on its standard ports, the Commander's inits. */
        static Devices forRig(Rig rig) {
            ForecourtTimeouts timeouts = ForecourtTimeouts.of(Timeouts.of(rig.timeouts()));
            String host = rig.controller().host();
            return new Devices() {
                @Override
                public PetroPumps startPumps() {
                    return PetroPumps.started(host, PetroPumps.PATS_FUEL_PORT, PetroPumps.PETRO_FUEL_PORT,
                            PetroPumps.PETRO_UI_PORT, timeouts);
                }

                @Override
                public PetroDcr startDcr() {
                    return PetroDcr.started(host, DCR_NUMBER, PetroDcr.PATS_DCR_PORT, PetroDcr.PETRO_DCR_PORT,
                            timeouts);
                }

                @Override
                public String dcrLinkFault(int dcr) {
                    return DcrLink.fault(CommanderInit.petroLog(ToolsRoot.resolve(null)), dcr);
                }

                @Override
                public Optional<String> recoverDcrLink(int dcr, Log log) {
                    Say say = new Say() {
                        @Override
                        public void out(String line) {
                            log.line("WARNING", line);
                        }

                        @Override
                        public void err(String line) {
                            log.line("WARNING", line);
                        }
                    };
                    return CommanderInit.builder(new CommanderAccess(rig)).say(say).build().recoverDcrLink(null, dcr);
                }
            };
        }
    }

    /** What pays off the fills due on a pump after a failed case: the POS's own recovery. */
    interface DueFuel {

        /** {@link Pos#settleDueFuel}: the amounts paid. */
        List<String> settle(int pump);

        /** The pump's reserve while the POS is idle, or empty. */
        Optional<String> reservedIfIdle(int pump);

        /** Over the run's POS page objects. */
        static DueFuel over(Pos pos) {
            return new DueFuel() {
                @Override
                public List<String> settle(int pump) {
                    return pos.settleDueFuel(pump, null);
                }

                @Override
                public Optional<String> reservedIfIdle(int pump) {
                    return pos.isIdle() ? pos.fuelPanel().reserved(pump) : Optional.empty();
                }
            };
        }
    }

    private final Rig rig;
    private final boolean fakePos;
    private final Devices devices;
    private final Log log;

    private PetroPumps pumps;
    private RuntimeException pumpsFailure;
    private TrackedPumps tracked;
    private PetroDcr dcr;
    private RuntimeException dcrFailure;
    private DcrDevice dcrDevice;
    /** Why the session's DCR recovery failed; null while none has been tried, "" after one that worked. */
    private String dcrRecovery;

    private PetroSession(Rig rig, boolean fakePos, Devices devices, Log log) {
        this.fakePos = fakePos;
        this.rig = rig;
        this.devices = devices;
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Starts the session's listeners the run's cases need: the pumps when {@code wanted} has {@code fuel_pumps}, DCR 1
     * when it has {@code dcr}, each only on a {@code forecourt: petro} rig and never under {@code --fake-pos}.
     *
     * @param rig     the run's rig, or null (a fake-POS run)
     * @param devices the suite's devices, or null for {@link Devices#forRig}
     */
    static PetroSession start(Rig rig, boolean fakePos, Set<FixtureName> wanted, Devices devices, Log log) {
        boolean petro = rig != null && !fakePos && rig.forecourt() == Rig.Forecourt.PETRO;
        Devices suite = devices != null ? devices : petro ? Devices.forRig(rig) : null;
        PetroSession session = new PetroSession(rig, fakePos, suite, log);
        if (!petro) {
            return session;
        }
        if (wanted.contains(FixtureName.FUEL_PUMPS)) {
            try {
                session.pumps = suite.startPumps();
                session.tracked = new TrackedPumps(new FuelPumpsAdapter(session.pumps));
                log.line("INFO", "pumps: listening on PATS's port " + PetroPumps.PATS_FUEL_PORT + " for the session");
            } catch (RuntimeException e) {
                session.pumpsFailure = e;
                log.line("WARNING", "pumps: not listening from session start: " + e.getMessage());
            }
        }
        if (wanted.contains(FixtureName.DCR)) {
            try {
                session.dcr = suite.startDcr();
                session.dcrDevice = new DcrDeviceAdapter(session.dcr);
                log.line("INFO", "DCR: listening on PATS's port " + PetroDcr.PATS_DCR_PORT + " for the session");
            } catch (RuntimeException e) {
                session.dcrFailure = e;
                log.line("WARNING", "DCR: not listening from session start: " + e.getMessage());
            }
        }
        return session;
    }

    /** The session's pumps for the handlers, or null when the session has none. */
    FuelPumps fuelPumps() {
        return tracked;
    }

    /** The session's DCR 1 for the handlers, or null when the session has none. */
    DcrDevice dcr() {
        return dcrDevice;
    }

    /**
     * A case's setup for the session's fixtures it declares. Throws, so the case errors at its setup, when a declared
     * fixture cannot be had.
     *
     * @throws DeviceUnavailable the rig has no Petro forecourt
     * @throws DeviceUnreachable the session's listener did not start, or DCR 1 is not connected to the Commander
     */
    void beginCase(Set<FixtureName> declared) {
        if (tracked != null) {
            tracked.resetTouched();
        }
        if (declared.contains(FixtureName.FUEL_PUMPS)) {
            requirePetro("fuel_pumps");
            if (tracked == null) {
                throw new DeviceUnreachable("pumps", "the session's listener did not start (PATS's port "
                        + PetroPumps.PATS_FUEL_PORT + ", then a login to the suite's port " + PetroPumps.PETRO_FUEL_PORT
                        + " on " + rig.controller().host() + "): " + reason(pumpsFailure), pumpsFailure);
            }
        }
        if (declared.contains(FixtureName.DCR)) {
            requirePetro("dcr");
            if (dcrDevice == null) {
                throw new DeviceUnreachable("DCR", "the session's listener did not start (PATS's port "
                        + PetroDcr.PATS_DCR_PORT + ", then a login to the suite's port " + PetroDcr.PETRO_DCR_PORT
                        + " on " + rig.controller().host() + "): " + reason(dcrFailure), dcrFailure);
            }
            String fault = devices.dcrLinkFault(DCR_NUMBER);
            if (fault != null) {
                fault = recoverDcrLink(fault);
            }
            if (fault != null) {
                throw new DeviceUnreachable("DCR " + DCR_NUMBER, fault);
            }
        }
    }

    /**
     * A case's teardown: after a case that declared {@code fuel_pumps} and failed before it finished its actions, the
     * pumps it touched are cleared and, with {@code dueFuel}, their due fills settled. Logs; never throws.
     *
     * @param callFailed the case failed in its actions (not at its setup, and not only on its validations)
     * @param dueFuel    the POS's settle, when the case declared {@code pos} and the run has one; else null
     */
    void afterCase(Set<FixtureName> declared, boolean callFailed, DueFuel dueFuel) {
        if (!callFailed || tracked == null || !declared.contains(FixtureName.FUEL_PUMPS)) {
            return;
        }
        List<Integer> touched = List.copyOf(new TreeSet<>(tracked.touched()));
        for (int pump : touched) {
            try {
                String was = tracked.clear(pump);
                if (was != null) {
                    log.line("WARNING", "pump " + pump + ": cleared from " + was + " after the failure");
                }
            } catch (RuntimeException e) {
                log.line("WARNING", "pump " + pump + ": not cleared after the failure: " + e.getMessage());
            }
        }
        if (dueFuel == null) {
            return;
        }
        for (int pump : touched) {
            try {
                List<String> paid = dueFuel.settle(pump);
                if (!paid.isEmpty()) {
                    log.line("WARNING", "pump " + pump + ": settled due fill(s) " + paid + " after the failure");
                }
            } catch (RuntimeException e) {
                log.line("WARNING", "pump " + pump + ": due fills not settled after the failure: " + e.getMessage());
            }
            Optional<String> held;
            try {
                held = dueFuel.reservedIfIdle(pump);
            } catch (RuntimeException e) {
                held = Optional.empty();
            }
            held.ifPresent(reserve -> log.line("WARNING",
                    "pump " + pump + ": " + reserve + " reserved after the failure; left for a person"));
        }
    }

    @Override
    public void close() {
        if (dcr != null) {
            try {
                dcr.close();
            } catch (RuntimeException e) {
                log.line("WARNING", "could not release the DCR listener: " + e.getMessage());
            }
            dcr = null;
            dcrDevice = null;
        }
        if (pumps != null) {
            try {
                pumps.close();
            } catch (RuntimeException e) {
                log.line("WARNING", "could not release the pump listener: " + e.getMessage());
            }
            pumps = null;
            tracked = null;
        }
    }

    // -- internals -------------------------------------------------------------------------------

    private void requirePetro(String fixture) {
        if (rig == null || fakePos) {
            throw new DeviceUnavailable(fixture, "this run drives the fake POS (--fake-pos), and nothing else in this repo "
                    + "drives a real forecourt");
        }
        if (rig.forecourt() != Rig.Forecourt.PETRO || devices == null) {
            throw new DeviceUnavailable(fixture, "rigs/" + rig.name() + ".yaml declares no `forecourt: petro`, and "
                    + "nothing else in this repo drives a real forecourt");
        }
    }

    /**
     * Brings DCR 1 back once per session. Returns null when it came back, else the reason to fail the case with. Once a
     * recovery has failed none is tried again: a link it did not fix will not be fixed by the next case, and each try
     * costs minutes.
     */
    private String recoverDcrLink(String fault) {
        if (dcrRecovery != null && !dcrRecovery.isEmpty()) {
            return fault + "; the session's DCR re-init " + dcrRecovery;
        }
        log.line("WARNING", "DCR " + DCR_NUMBER + ": " + fault + "; recovering it");
        String problem;
        try {
            problem = devices.recoverDcrLink(DCR_NUMBER, log).orElse("");
        } catch (RuntimeException e) {
            problem = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (!problem.isEmpty()) {
            dcrRecovery = "did not bring it back (" + problem + ")";
            log.line("WARNING", "DCR " + DCR_NUMBER + ": the re-init " + dcrRecovery);
            return fault + "; the session's DCR re-init " + dcrRecovery;
        }
        dcrRecovery = "";
        log.line("WARNING", "DCR " + DCR_NUMBER + ": connected again after the recovery");
        return null;
    }

    private static String reason(RuntimeException failure) {
        return failure == null ? "not started" : failure.getMessage();
    }

    /** The session's pumps, recording which pumps the current case touched. */
    static final class TrackedPumps implements FuelPumps {

        private final FuelPumps pumps;
        private final Set<Integer> touched = new HashSet<>();

        TrackedPumps(FuelPumps pumps) {
            this.pumps = pumps;
        }

        synchronized void resetTouched() {
            touched.clear();
        }

        private synchronized void touch(int pump) {
            touched.add(pump);
        }

        @Override
        public synchronized Set<Integer> touched() {
            return Set.copyOf(touched);
        }

        @Override
        public void fillAmount(int pump, String amount, int hose, boolean prepay, boolean preset) {
            touch(pump);
            pumps.fillAmount(pump, amount, hose, prepay, preset);
        }

        @Override
        public void handleUp(int pump, int hose, boolean prepay) {
            touch(pump);
            pumps.handleUp(pump, hose, prepay);
        }

        @Override
        public void handleDown(int pump, boolean prepay) {
            touch(pump);
            pumps.handleDown(pump, prepay);
        }

        @Override
        public Optional<String> state(int pump) {
            touch(pump);
            return pumps.state(pump);
        }

        @Override
        public void waitUntilIdle(int pump, Duration timeout) {
            touch(pump);
            pumps.waitUntilIdle(pump, timeout);
        }

        @Override
        public boolean waitForPostedSale(int pump, Duration timeout) {
            touch(pump);
            return pumps.waitForPostedSale(pump, timeout);
        }

        @Override
        public boolean lifted(int pump) {
            return pumps.lifted(pump);
        }

        @Override
        public String clear(int pump) {
            return pumps.clear(pump);
        }
    }
}
