package com.verifone.cat.sims;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Reads what a child process wrote, for the callers that wait on the child against their own timeout. */
final class ChildOutput {

    private ChildOutput() {
    }

    /** Everything on {@code stream} up to its end, then closes it; {@code ""} when the read fails. */
    static String read(InputStream stream, Charset charset) {
        try (stream) {
            return new String(stream.readAllBytes(), charset);
        } catch (IOException e) {
            return "";
        }
    }
}
