package com.verifone.cat.commander;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the Commander bring-up writes its lines: the Python commands' {@code print} to stdout and to
 * stderr. A launch step routes them into its log; a command prints them.
 *
 * <p>The last error line matters, as it does to the Python CAT's relay: a failed command's last stderr
 * line is the step's reason. Every failure here is written last for that reason.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface Say {

    /** A progress line (the Python's stdout). */
    void out(String line);

    /** A warning or failure line (the Python's stderr). It may hold several lines. */
    void err(String line);

    /** Both streams of a command line. */
    static Say to(PrintStream out, PrintStream err) {
        return new Say() {
            @Override
            public void out(String line) {
                out.println(line);
                out.flush();
            }

            @Override
            public void err(String line) {
                err.println(line);
                err.flush();
            }
        };
    }

    /** A recorder, for tests and for a caller that reads the lines afterwards. */
    final class Recorded implements Say {

        private final List<String> out = new ArrayList<>();
        private final List<String> err = new ArrayList<>();

        @Override
        public synchronized void out(String line) {
            out.addAll(List.of(line.split("\n", -1)));
        }

        @Override
        public synchronized void err(String line) {
            err.addAll(List.of(line.split("\n", -1)));
        }

        /** Every stdout line, one per line of text. */
        public synchronized List<String> outLines() {
            return List.copyOf(out);
        }

        /** Every stderr line, one per line of text. */
        public synchronized List<String> errLines() {
            return List.copyOf(err);
        }

        /** The stdout text, lines joined with newlines. */
        public synchronized String outText() {
            return String.join("\n", out);
        }

        /** The stderr text, lines joined with newlines. */
        public synchronized String errText() {
            return String.join("\n", err);
        }

        /** The last non-blank stderr line, or "". */
        public synchronized String lastErr() {
            for (int i = err.size() - 1; i >= 0; i--) {
                if (!err.get(i).isBlank()) {
                    return err.get(i);
                }
            }
            return "";
        }
    }
}
