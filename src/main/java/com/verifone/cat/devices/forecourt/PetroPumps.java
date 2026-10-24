package com.verifone.cat.devices.forecourt;

import com.verifone.cat.devices.DeviceUnreachable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Petro Simulator Suite's fuel simulator, every pump, as PATS's {@code PumpManager} drives it. Ported from the
 * Python {@code PetroPumps}.
 *
 * <p>PATS writes one {@code FuelHandleUpEntity[]} or {@code FuelHandleDownEntity[]} per action to the suite's port
 * {@value #PETRO_FUEL_PORT}. The suite writes a {@code StatusInfo[]} to PATS's port {@value #PATS_FUEL_PORT} whenever
 * a pump changes state, and every pump's state once at login. Each action waits on the state it needs (idle, authorised,
 * nozzle up), bounded by {@link ForecourtTimeouts}, instead of PATS's fixed sleeps.
 *
 * <p>{@link #clear} is not a PATS step: it puts a pump a failed case left mid-sale back to IDLEOFF.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python port ran against the lab suite on 2026-09-17 (it logged in and
 * answered with all 16 pumps); no Java run has touched a simulator.
 */
public final class PetroPumps implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PetroPumps.class);

    /** The suite's two pump-state clients: its own UI's Pump Simulator view, then PATS. */
    public static final int PETRO_UI_PORT = 9706;
    /** PATS's pump-state port. */
    public static final int PATS_FUEL_PORT = 1072;
    /** The suite's fuel login port. */
    public static final int PETRO_FUEL_PORT = 9700;

    /** What {@link #clear} lifts an authorised, unfuelled pump with: the pump's own limit ends the fill. */
    public static final BigDecimal CLEAR_FILL = new BigDecimal("999.99");

    /** The ceiling on a preset pump's authorisation before {@link #fillAmount} lifts it anyway. */
    public static final Duration PRESET_AUTHORISE_WAIT = Duration.ofSeconds(10);

    /** States with the nozzle out of its holster. IDLEON is lifted but not authorised. */
    static final Set<String> NOZZLE_UP = Set.of("IDLEON", "BUSYON", "DCRIDLEON");

    private static final String STATUS_INFO = ForecourtWire.FUEL_ENT + "StatusInfo";

    private final String simulatorHost;
    private final int listenPort;
    private final int simulatorPort;
    private final Integer uiPort;
    private final ForecourtTimeouts timeouts;
    private volatile Duration presetAuthoriseWait = PRESET_AUTHORISE_WAIT;

    private final Object changed = new Object();
    private final Map<Integer, PumpReport> states = new HashMap<>();
    private int seq;
    private final Map<Integer, Integer> sent = new HashMap<>();
    private final Map<Integer, Integer> hungUp = new HashMap<>();
    private final Map<Integer, BigDecimal> amounts = new HashMap<>();
    private final Set<Integer> lifted = new HashSet<>();
    private final Set<Integer> touched = new HashSet<>();

    private PushListener listener;
    private PortSink uiSink;

    /** A pump's last report, with the sequence number that orders it against an action. */
    private record PumpReport(String state, int seq) {
    }

    /**
     * @param uiPort the suite's UI port, kept by {@link PortSink} unless something already holds it; null for none
     */
    public PetroPumps(String simulatorHost, int listenPort, int simulatorPort, Integer uiPort, ForecourtTimeouts timeouts) {
        this.simulatorHost = Objects.requireNonNull(simulatorHost, "simulatorHost");
        this.listenPort = listenPort;
        this.simulatorPort = simulatorPort;
        this.uiPort = uiPort;
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /** A client on the suite's standard ports, already started. */
    public static PetroPumps started(String simulatorHost, ForecourtTimeouts timeouts) {
        return started(simulatorHost, PATS_FUEL_PORT, PETRO_FUEL_PORT, PETRO_UI_PORT, timeouts);
    }

    /** A client on the given ports, already started: listening, then logged in. */
    public static PetroPumps started(String simulatorHost, int listenPort, int simulatorPort, Integer uiPort,
            ForecourtTimeouts timeouts) {
        PetroPumps pumps = new PetroPumps(simulatorHost, listenPort, simulatorPort, uiPort, timeouts);
        pumps.start();
        return pumps;
    }

    /**
     * Listens for pump states, then logs in so the suite reports every pump. The UI port is kept too, unless
     * something already holds it.
     *
     * @throws DeviceUnreachable when the listen port is taken or the suite does not accept the login
     */
    public void start() {
        try {
            listener = new PushListener(listenPort, this::onStatus, timeouts.http(), null);
        } catch (java.io.IOException e) {
            throw new DeviceUnreachable("the Petro suite's port " + listenPort, "already held: " + e.getMessage(), e);
        }
        if (uiPort != null) {
            try {
                uiSink = new PortSink(uiPort);
            } catch (DeviceUnreachable heldElsewhere) {
                LOG.debug("the suite's UI port {} is held elsewhere; not kept", uiPort);
            }
        }
        try {
            send(ForecourtWire.encodeLogin(true));
        } catch (DeviceUnreachable e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (listener != null) {
            listener.close();
        }
        if (uiSink != null) {
            uiSink.close();
        }
    }

    /** The last state the suite reported for {@code pump} ({@code IDLEOFF}, {@code BUSYON}, ...), or null. */
    public String state(int pump) {
        synchronized (changed) {
            touched.add(pump);
            PumpReport current = states.get(pump);
            return current == null ? null : current.state();
        }
    }

    /** True when {@code fillAmount} or {@code handleUp} lifted {@code pump} and nothing has hung it up. */
    public boolean lifted(int pump) {
        synchronized (changed) {
            return amounts.containsKey(pump) || lifted.contains(pump);
        }
    }

    /** Every pump a test lifted, hung up, or waited on: what {@link #clear} may touch. */
    public Set<Integer> touched() {
        synchronized (changed) {
            return Set.copyOf(touched);
        }
    }

    /** The state the suite reports for {@code pump}, waiting for its first report (bounded by {@code timeout}). */
    public String reported(int pump, Duration timeout) {
        waitFor(pump, s -> true, "pump " + pump + " state", -1, timeout);
        String state = state(pump);
        return state == null ? "" : state;
    }

    /**
     * {@code FUEL_AMOUNT}: PATS's {@code PumpManager.fillAmountFuel}. Waits until the pump can take the nozzle (BUSYOFF
     * when authorised for a prepay, otherwise IDLEOFF, DCRIDLEON or BUSYOFF), then lifts the handle with {@code amount}
     * as the preset.
     *
     * @param preset the POS has just preset this pump, so the Commander is about to authorise it: the lift waits for
     *               that BUSYOFF, at most {@link #PRESET_AUTHORISE_WAIT}, then goes ahead as PATS's would
     */
    public void fillAmount(int pump, BigDecimal amount, int hose, boolean prepay, boolean preset) {
        if (preset && !prepay) {
            try {
                waitFor(pump, s -> s.contains("BUSYOFF"), "pump " + pump + " authorised for its preset", -1,
                        presetAuthoriseWait);
            } catch (ForecourtWaitExpired expired) {
                LOG.warn("lifting pump {} anyway: {}", pump, expired.getMessage());
            }
        }
        waitFor(pump, anyOf(liftStates(prepay)), "pump " + pump + " " + liftStates(prepay), -1, null);
        amounts.put(pump, amount);
        act(pump, ForecourtWire.encodeHandleUp(pump, amount, hose));
    }

    /** {@code HANDLE_UP}: PATS's {@code PumpManager.handleUp}, lifting the nozzle with no fill. */
    public void handleUp(int pump, int hose, boolean prepay) {
        waitFor(pump, anyOf(liftStates(prepay)), "pump " + pump + " " + liftStates(prepay), -1, null);
        BigDecimal preset = amounts.remove(pump);
        act(pump, ForecourtWire.encodeHandleUp(pump, preset == null ? BigDecimal.ZERO : preset, hose));
        lifted.add(pump);
    }

    /**
     * {@code HANDLE_DOWN}: PATS's {@code PumpManager.handleDown}. Waits until the pump is authorised with its handle up
     * (BUSYON or DCRIDLEON), then hangs up. For a prepay it then waits for IDLEOFF.
     */
    public void handleDown(int pump, boolean prepay) {
        waitFor(pump, s -> s.equals("BUSYON") || s.equals("DCRIDLEON"), "pump " + pump + " [BUSYON, DCRIDLEON]",
                -1, null);
        act(pump, ForecourtWire.encodeHandleDown(pump, amounts.remove(pump)));
        lifted.remove(pump);
        synchronized (changed) {
            hungUp.put(pump, sent.get(pump));
        }
        if (prepay) {
            waitUntilIdle(pump, null);
        }
    }

    /**
     * If the last thing done to {@code pump} was a hang-up, waits for the IDLEOFF after it, so a tap on the pump's tile
     * does not open its detail view mid-EOT. Returns at once when there was no such hang-up.
     */
    public void waitForPostedSale(int pump, Duration timeout) {
        Integer after;
        synchronized (changed) {
            after = hungUp.get(pump);
            if (after == null || !after.equals(sent.get(pump))) {
                return;
            }
        }
        waitFor(pump, "IDLEOFF"::equals, "pump " + pump + " IDLEOFF", after, timeout);
        synchronized (changed) {
            hungUp.remove(pump);
        }
    }

    /** Waits, bounded, for IDLEOFF reported after the last action on {@code pump}. */
    public void waitUntilIdle(int pump, Duration timeout) {
        int after;
        synchronized (changed) {
            after = sent.getOrDefault(pump, 0);
        }
        waitFor(pump, "IDLEOFF"::equals, "pump " + pump + " IDLEOFF", after, timeout);
    }

    /**
     * Puts a pump a failed case left mid-sale back to IDLEOFF, and returns the state it was in (null when it was idle
     * or never reported). Not a PATS step: PATS leaves the pump as the case left it.
     *
     * <ul>
     *   <li>IDLEOFF, or never reported: nothing to do.</li>
     *   <li>BUSYOFF (authorised, nozzle down): lift with {@link #CLEAR_FILL}, then hang up.</li>
     *   <li>BUSYON or DCRIDLEON (authorised, nozzle up): hang up, which sells the fill.</li>
     *   <li>Anything else (nozzle up, not authorised): hang up, which sells nothing.</li>
     * </ul>
     */
    public String clear(int pump) {
        String before = state(pump);
        if (before == null || "IDLEOFF".equals(before)) {
            return null;
        }
        if ("BUSYOFF".equals(before)) {
            fillAmount(pump, CLEAR_FILL, 1, true, false);
            handleDown(pump, false);
        } else if ("BUSYON".equals(before) || "DCRIDLEON".equals(before)) {
            handleDown(pump, false);
        } else {
            act(pump, ForecourtWire.encodeHandleDown(pump, null));
            lifted.remove(pump);
        }
        waitUntilIdle(pump, null);
        return before;
    }

    /** Test seam: the ceiling on a preset's authorisation, which the Python tests shorten. */
    void setPresetAuthoriseWait(Duration wait) {
        this.presetAuthoriseWait = wait;
    }

    /** The port this client listens on for the suite's pushes (PATS's 1072 on a rig). */
    int listenPort() {
        return listenPort;
    }

    // -- internals ---------------------------------------------------------

    private static List<String> liftStates(boolean prepay) {
        return prepay ? List.of("BUSYOFF") : List.of("IDLEOFF", "DCRIDLEON", "BUSYOFF");
    }

    private static Predicate<String> anyOf(List<String> wanted) {
        return s -> wanted.stream().anyMatch(s::contains);
    }

    private void act(int pump, byte[] payload) {
        synchronized (changed) {
            sent.put(pump, seq);
        }
        send(payload);
    }

    private void send(byte[] payload) {
        Transport.send(simulatorHost, simulatorPort, payload, timeouts.http(), false);
    }

    private void onStatus(Object obj) {
        if (!(obj instanceof List<?> items)) {
            return; // Transaction[] and FuelInitStatus: PumpManager ignores them too
        }
        synchronized (changed) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> status && STATUS_INFO.equals(status.get("__class__"))) {
                    Integer pump = ForecourtWire.intField(status, "fuelingPosition").orElseThrow(
                            () -> new IllegalArgumentException("a StatusInfo without a fuelingPosition"));
                    seq++;
                    states.put(pump, new PumpReport(String.valueOf(status.get("deviceStatus")), seq));
                }
            }
            changed.notifyAll();
        }
    }

    /** Waits for a report newer than {@code after} for {@code pump} that satisfies {@code accept}. */
    private void waitFor(int pump, Predicate<String> accept, String what, int after, Duration timeout) {
        synchronized (changed) {
            touched.add(pump);
        }
        Duration budget = timeout != null ? timeout : timeouts.fuelComplete();
        BooleanSupplier ready = () -> {
            PumpReport current = states.get(pump);
            return current != null && current.seq() > after && accept.test(current.state());
        };
        Waits.await(changed, ready, budget,
                () -> what + " (last reported: " + lastReported(pump) + ")");
    }

    private String lastReported(int pump) {
        synchronized (changed) {
            PumpReport current = states.get(pump);
            return current == null ? "none" : current.state();
        }
    }
}
