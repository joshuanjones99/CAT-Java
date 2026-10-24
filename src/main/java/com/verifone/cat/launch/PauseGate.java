package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pause and Continue for one run: the Python CAT's {@code cat.launch.pause.PauseGate}.
 *
 * <p>A pause takes effect before the next step, never in the middle of one. {@link RunLauncher}
 * calls {@link #awaitContinue} before each step. The pytest session would read the same flag file
 * in the run's folder ({@link #PAUSE_FILE}); nothing in this port starts that session yet.
 *
 * <p>The wait is a monitor wait, woken by {@link #resume()} or {@link #wake()}, not a poll.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class PauseGate {

    /** The flag file in the run's folder: present while the run is paused. */
    public static final String PAUSE_FILE = "PAUSED";

    private static final Logger LOG = LoggerFactory.getLogger(PauseGate.class);

    private final Object lock = new Object();
    private boolean paused;
    private Path flagPath;

    /** Whether the run is paused now. */
    public boolean paused() {
        synchronized (lock) {
            return paused;
        }
    }

    /** Names the run's flag file and writes it now if already paused. */
    public void bind(Path flag) {
        synchronized (lock) {
            flagPath = flag;
            syncFlag();
        }
    }

    /** Pauses: the run stops before its next step. */
    public void pause() {
        synchronized (lock) {
            paused = true;
            syncFlag();
        }
        LOG.info("run paused: it stops before the next step");
    }

    /** Continues a paused run. */
    public void resume() {
        synchronized (lock) {
            paused = false;
            syncFlag();
            lock.notifyAll();
        }
        LOG.info("run continued");
    }

    /** Pause if running, continue if paused; returns the new state. */
    public boolean toggle() {
        synchronized (lock) {
            if (paused) {
                resume();
            } else {
                pause();
            }
            return paused;
        }
    }

    /**
     * Blocks while the run is paused, and returns when it is continued or {@code stopped} says the
     * run was stopped. Returns at once when it is not paused.
     *
     * @param stopped asked after each wake-up; a stop ends the wait, and the caller then stops
     */
    public void awaitContinue(BooleanSupplier stopped) {
        synchronized (lock) {
            while (paused && !stopped.getAsBoolean()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Wakes a waiter so it can notice a stop. Does not change the paused state. */
    public void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void syncFlag() {
        Path path = flagPath;
        if (path == null) {
            return;
        }
        try {
            if (paused) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, "paused from CAT's Run tab\n", StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) { // the in-process gate still holds the steps
            LOG.warn("pause flag {} could not be written: {}", path, e.getMessage());
        }
    }
}
