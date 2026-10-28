package com.verifone.cat.sims;

import java.io.InputStream;
import java.io.OutputStream;

/** A {@link Process} that is only a PID and an alive flag: nothing is spawned. */
final class FakeProcess extends Process {

    private final long pid;
    volatile boolean alive = true;
    volatile int exitCode;

    FakeProcess(long pid) {
        this.pid = pid;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
        return exitCode;
    }

    @Override
    public int exitValue() {
        if (alive) {
            throw new IllegalThreadStateException("still running");
        }
        return exitCode;
    }

    @Override
    public void destroy() {
        alive = false;
    }
}
