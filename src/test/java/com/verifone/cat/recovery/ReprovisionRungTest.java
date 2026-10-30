package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionReports;

/**
 * Rung 5 over a recorded provision and screen: the Python's {@code LiveRecoveryTarget.reprovision} (provision
 * --force, the wait for a restarted POS to draw, then preflight). Nothing reaches a rig.
 */
class ReprovisionRungTest {

    /** A screen whose {@code drawn()} answers true from its {@code drawnOn}-th call. */
    private static final class Screen implements PosScreen {
        final AtomicInteger asked = new AtomicInteger();
        final int drawnOn;

        Screen(int drawnOn) {
            this.drawnOn = drawnOn;
        }

        @Override
        public boolean idle() {
            return false;
        }

        @Override
        public boolean atLogin() {
            return false;
        }

        @Override
        public boolean drawn() {
            return asked.incrementAndGet() >= drawnOn;
        }

        @Override
        public boolean signedOn() {
            return false;
        }

        @Override
        public void softCancel() {}

        @Override
        public String voidOpenSale() {
            return "idle";
        }
    }

    private static LiveRecoveryTarget target(Screen screen, Supplier<ProvisionReport> provision, Duration wait) {
        return new LiveRecoveryTarget("lab", null, null, null, screen, null, null, provision, wait,
                Duration.ofMillis(1));
    }

    @Test
    void withoutAProvisionTheRungIsUnavailable() {
        LiveRecoveryTarget live = new LiveRecoveryTarget("lab", null, null, null, new Screen(1), null, null);
        DeviceUnavailable gone = assertThrows(DeviceUnavailable.class, live::reprovision);
        assertTrue(gone.getMessage().contains("provision_force"), gone.getMessage());
    }

    @Test
    void aProvisionThatIsNotOkFailsTheRungAndPreflightIsNotRun() {
        Screen screen = new Screen(1);
        AtomicInteger preflights = new AtomicInteger();
        LiveRecoveryTarget live = target(screen, ProvisionReports::failed, Duration.ofSeconds(1))
                .withPreflight(() -> preflights.incrementAndGet() > 0);
        assertFalse(live.reprovision());
        assertEquals(0, preflights.get());
        assertEquals(0, screen.asked.get());
    }

    @Test
    void aRestartedPosIsWaitedForBeforePreflightDecides() {
        Screen screen = new Screen(3);
        AtomicInteger preflights = new AtomicInteger();
        LiveRecoveryTarget live = target(screen, () -> ProvisionReports.ok(true), Duration.ofSeconds(5))
                .withPreflight(() -> {
                    preflights.incrementAndGet();
                    return true;
                });
        assertTrue(live.reprovision());
        assertEquals(3, screen.asked.get());
        assertEquals(1, preflights.get());
    }

    @Test
    void aPosThatNeverDrawsIsAWarningAndPreflightStillReads() {
        Screen screen = new Screen(Integer.MAX_VALUE);
        LiveRecoveryTarget live = target(screen, () -> ProvisionReports.ok(true), Duration.ofMillis(30))
                .withPreflight(() -> false);
        assertFalse(live.reprovision(), "preflight's answer, not the wait's");
        assertTrue(screen.asked.get() >= 1);
    }

    @Test
    void noRestartMeansNoWait() {
        Screen screen = new Screen(1);
        LiveRecoveryTarget live = target(screen, () -> ProvisionReports.ok(false), Duration.ofSeconds(1))
                .withPreflight(() -> true);
        assertTrue(live.reprovision());
        assertEquals(0, screen.asked.get());
    }
}
