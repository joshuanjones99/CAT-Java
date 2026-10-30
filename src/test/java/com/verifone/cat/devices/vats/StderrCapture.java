package com.verifone.cat.devices.vats;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * What slf4j-simple wrote to {@code System.err} while a block ran (its default output, looked up at each write). The
 * test resources' level is WARN, so only warnings and errors are seen.
 */
final class StderrCapture implements AutoCloseable {

    private final PrintStream original = System.err;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    StderrCapture() {
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    String text() {
        System.err.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        System.setErr(original);
    }
}
