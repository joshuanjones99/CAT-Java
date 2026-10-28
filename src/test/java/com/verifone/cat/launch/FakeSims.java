package com.verifone.cat.launch;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;

/** Simulator handles for step tests: a process that is never a real child, spawned or adopted. */
final class FakeSims {

    private FakeSims() {}

    /** A handle as if CAT spawned {@code name}: its process is a {@link FakeProcess}. */
    static SimProcess spawned(String name) {
        return new SimProcess(SimEntry.javaw(name).argv("-jar", "x.jar").build(),
                new SimStatus(name, SimStatus.State.READY, SimStatus.Owner.CAT, "", ""), new FakeProcess(), null);
    }

    /** A handle as if CAT adopted {@code name}: no process. */
    static SimProcess adopted(String name) {
        return new SimProcess(SimEntry.javaw(name).argv("-jar", "x.jar").build(),
                new SimStatus(name, SimStatus.State.ADOPTED, SimStatus.Owner.EXTERNAL, "", ""), null, null);
    }

    /** A process that is alive until destroyed. Nothing runs. */
    static final class FakeProcess extends Process {

        volatile boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
