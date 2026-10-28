package com.verifone.cat.launch;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import com.verifone.cat.commander.Say;

/**
 * A {@link PrintStream} whose each written line goes to a sink, blank lines dropped: how a ported command that
 * prints (the day close, the snapshot capture, Commander Connect) reports into a step's {@link Say}.
 */
final class LinePrintStream {

    private LinePrintStream() {}

    /** A stream whose lines go to {@code sink}. Close it to flush a last line with no newline. */
    static PrintStream of(Consumer<String> sink) {
        return new PrintStream(new Lines(sink), true, StandardCharsets.UTF_8);
    }

    /** A stream whose lines are {@code say}'s out lines. */
    static PrintStream out(Say say) {
        return of(say::out);
    }

    /** A stream whose lines are {@code say}'s error lines. */
    static PrintStream err(Say say) {
        return of(say::err);
    }

    /** Splits written bytes into lines, dropping blank ones, and hands each to the sink. */
    private static final class Lines extends OutputStream {

        private final Consumer<String> sink;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Lines(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                pending.write(b);
            }
        }

        @Override
        public void close() {
            if (pending.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String line = pending.toString(StandardCharsets.UTF_8).strip();
            pending.reset();
            if (!line.isEmpty()) {
                sink.accept(line);
            }
        }
    }
}
