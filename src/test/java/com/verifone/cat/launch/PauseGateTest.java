package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Run tab's Pause and Continue, as the launcher's gate: the flag file and the wait. */
class PauseGateTest {

    @TempDir
    Path dir;

    @Test
    void aPauseBeforeTheRunIsBoundIsWrittenWhenItIsBound() throws IOException {
        PauseGate gate = new PauseGate();
        Path flag = dir.resolve("run1").resolve(PauseGate.PAUSE_FILE);

        gate.pause();
        gate.bind(flag);

        assertTrue(gate.paused());
        assertTrue(Files.isRegularFile(flag));
        gate.resume();
        assertFalse(Files.exists(flag));
    }

    @Test
    void toggleFlipsAndKeepsTheFlagFileInStep() throws IOException {
        PauseGate gate = new PauseGate();
        Path flag = dir.resolve(PauseGate.PAUSE_FILE);
        gate.bind(flag);

        assertTrue(gate.toggle());
        assertTrue(Files.isRegularFile(flag));
        assertFalse(gate.toggle());
        assertFalse(Files.exists(flag));
    }

    @Test
    void aStopEndsThePauseWithoutAContinue() {
        PauseGate gate = new PauseGate();
        gate.pause();

        // The wait returns at once when the run has been stopped, however long it is paused.
        gate.awaitContinue(() -> true);

        assertTrue(gate.paused());
    }

    @Test
    void theWaitHoldsUntilContinueAndNotAfter() throws InterruptedException {
        PauseGate gate = new PauseGate();
        gate.pause();
        AtomicBoolean through = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            gate.awaitContinue(() -> false);
            through.set(true);
        });
        waiter.start();

        // Bounded join, not a poll: the waiter is still held after this, and it is not a race that
        // could make a held waiter look released.
        waiter.join(Duration.ofMillis(100).toMillis());
        assertTrue(waiter.isAlive());
        assertFalse(through.get());

        gate.resume();
        waiter.join(Duration.ofSeconds(30).toMillis());
        assertFalse(waiter.isAlive());
        assertTrue(through.get());
    }
}
