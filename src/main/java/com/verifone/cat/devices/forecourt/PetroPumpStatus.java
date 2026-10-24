package com.verifone.cat.devices.forecourt;

import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.preflight.Surfaces;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code pumps_idle} read of Petro Simulator Suite: the states it reports at login, nothing lifted or hung up.
 * Ported from the Python {@code PetroPumpStatus}.
 *
 * <p>It logs in on PATS's port (1072) and reads every requested pump from the answer. While PATS or a running test
 * holds that port, the bind fails with {@link com.verifone.cat.devices.DeviceUnreachable}, which preflight reports as
 * ERROR, not as a pump fault. A pump the suite never reports within {@code http} is {@link Bounded.TimeoutExpired},
 * which preflight reports as TIMEOUT.
 *
 * <p>Only {@code IDLEOFF} is idle. {@code BUSYOFF} (authorised, nobody fuelling: a prepay a failed case never
 * fuelled) reads as a pending prepay. Any other state is reported by its own name.
 *
 * <p>Wiring: {@code RigContextFactory} would pass one of these as the rig's {@code Surfaces.PumpStatus} when the rig
 * names a Petro forecourt. This class is not wired there; see the port notes.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a preflight probe (Java port). The login answer is the one the lab suite gave.
 */
public final class PetroPumpStatus implements Surfaces.PumpStatus {

    private final String simulatorHost;
    private final int listenPort;
    private final int simulatorPort;
    private final ForecourtTimeouts timeouts;

    public PetroPumpStatus(String simulatorHost, int listenPort, int simulatorPort, ForecourtTimeouts timeouts) {
        this.simulatorHost = Objects.requireNonNull(simulatorHost, "simulatorHost");
        this.listenPort = listenPort;
        this.simulatorPort = simulatorPort;
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /** The standard ports: PATS's 1072 to listen on, the suite's 9700 to log in to. */
    public static PetroPumpStatus standard(String simulatorHost, ForecourtTimeouts timeouts) {
        return new PetroPumpStatus(simulatorHost, PetroPumps.PATS_FUEL_PORT, PetroPumps.PETRO_FUEL_PORT, timeouts);
    }

    public String simulatorHost() {
        return simulatorHost;
    }

    @Override
    public List<Surfaces.PumpState> pumps(List<Integer> numbers) {
        PetroPumps suite = PetroPumps.started(simulatorHost, listenPort, simulatorPort, PetroPumps.PETRO_UI_PORT,
                timeouts);
        try {
            long end = System.nanoTime() + timeouts.http().toNanos();
            List<Surfaces.PumpState> out = new ArrayList<>();
            for (int number : numbers) {
                long left = Math.max(0L, end - System.nanoTime());
                out.add(pumpState(number, suite.reported(number, Duration.ofNanos(left))));
            }
            return out;
        } catch (ForecourtWaitExpired expired) {
            throw new Bounded.TimeoutExpired(expired.getMessage());
        } finally {
            suite.close();
        }
    }

    /** The preflight view of one pump's reported state. */
    static Surfaces.PumpState pumpState(int number, String state) {
        return new Surfaces.PumpState(
                number,
                "IDLEOFF".equals(state) ? "idle" : state,
                PetroPumps.NOZZLE_UP.contains(state),
                "BUSYOFF".equals(state));
    }
}
