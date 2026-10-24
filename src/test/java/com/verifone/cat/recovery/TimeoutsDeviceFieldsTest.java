package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.rig.Rig;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The prompt, fuel-complete and http budgets the device clients take from the rig file (the Python's Timeouts). */
class TimeoutsDeviceFieldsTest {

    @Test
    void defaultsAreThePythonsOwn() {
        Timeouts t = Timeouts.defaults();
        assertEquals(Duration.ofSeconds(15), t.defaultWait());
        assertEquals(Duration.ofSeconds(30), t.ssh());
        assertEquals(Duration.ofSeconds(30), t.promptWait());
        assertEquals(Duration.ofSeconds(90), t.fuelComplete());
        assertEquals(Duration.ofSeconds(15), t.http());
    }

    @Test
    void parseReadsEveryKeyAndDefaultsTheRest() {
        Timeouts t = Timeouts.parse(Map.of("prompt_wait", "45s", "fuel_complete", "2m"));
        assertEquals(Duration.ofSeconds(45), t.promptWait());
        assertEquals(Duration.ofMinutes(2), t.fuelComplete());
        assertEquals(Duration.ofSeconds(15), t.http());
        assertEquals(Duration.ofSeconds(15), t.defaultWait());
    }

    @Test
    void sshFollowsALongerDefaultWaitAsThePythonDoes() {
        assertEquals(Duration.ofMinutes(2), Timeouts.parse(Map.of("default_wait", "2m")).ssh());
    }

    @Test
    void theRigBlockCarriesPromptAndFuelWait() {
        Rig.Timeouts block = new Rig.Timeouts(Duration.ofSeconds(20), Duration.ofSeconds(45), Duration.ofSeconds(120));
        Timeouts t = Timeouts.of(block);
        assertEquals(Duration.ofSeconds(45), t.promptWait());
        assertEquals(Duration.ofSeconds(120), t.fuelComplete());
        assertEquals(Duration.ofSeconds(20), t.defaultWait());
        assertEquals(Duration.ofSeconds(15), t.http());
    }

    @Test
    void theForecourtTakesItsThreeBoundsFromTheRecoveryBudgets() {
        ForecourtTimeouts f = ForecourtTimeouts.of(
                Timeouts.parse(Map.of("prompt_wait", "45s", "fuel_complete", "120s", "http", "7s")));
        assertEquals(new ForecourtTimeouts(Duration.ofSeconds(120), Duration.ofSeconds(45), Duration.ofSeconds(7)), f);
    }

    @Test
    void aZeroBoundIsRefusedWhereverItIsNamed() {
        assertThrows(IllegalArgumentException.class, () -> Timeouts.parse(Map.of("http", "0s")));
        assertThrows(IllegalArgumentException.class, () -> Timeouts.parse(Map.of("prompt_wait", "0s")));
        assertThrows(IllegalArgumentException.class, () -> Timeouts.parse(Map.of("fuel_complete", "0s")));
    }
}
