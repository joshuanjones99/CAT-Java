package com.verifone.cat.upgrade;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * An {@link Installer} over a {@link Process}. Package-private: callers get one from {@link Installer#SYSTEM}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class ProcessInstaller implements Installer {

    private final Process process;

    ProcessInstaller(Process process) {
        this.process = process;
    }

    @Override
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    @Override
    public void terminate() {
        process.destroy();
    }

    @Override
    public void kill() {
        process.destroyForcibly();
    }

    @Override
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
