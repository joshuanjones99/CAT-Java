package com.verifone.cat.sims;

import java.time.Duration;
import java.util.List;

/**
 * The Windows queries and kills the supervisor needs, behind one seam so the adoption and
 * stop rules can be tested without touching a real process.
 *
 * <p>Every method is bounded by its timeout. A query that could not be performed comes back
 * with a non-empty {@code error}: that is a visible row, never an empty answer read as "nothing
 * is running" (the Python CAT's rule).
 *
 * <p>{@link #taskkill} is the only method that changes anything. Callers pass it only PIDs that
 * {@link Supervisor} found by an exact command line or listening port.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ProcessOps {

    /**
     * One query's answer.
     *
     * @param text the output, possibly empty
     * @param error why the query could not be performed; empty when it was
     */
    record Snapshot(String text, String error) {
        public Snapshot {
            text = text == null ? "" : text;
            error = error == null ? "" : error;
        }

        public static Snapshot ok(String text) {
            return new Snapshot(text, "");
        }

        public static Snapshot failed(String error) {
            return new Snapshot("", error);
        }
    }

    /** The full command line of every process whose image name is one of {@code names}, one per line. */
    Snapshot commandLines(List<String> names, Duration timeout);

    /** The command line of the process listening on {@code port}, or empty. */
    Snapshot listenerCommandLine(int port, Duration timeout);

    /**
     * One pass over the listeners on {@code ports} and the processes named {@code names}:
     * {@code L<TAB>port<TAB>pid} per listener, {@code P<TAB>pid<TAB>commandline} per process.
     */
    Snapshot listing(List<String> names, List<Integer> ports, Duration timeout);

    /** The PIDs (as whitespace-separated numbers in the text) still running after a short wait. */
    Snapshot stillAlive(List<Long> pids, Duration timeout);

    /**
     * {@code taskkill /T /F} for every PID in one call. Returns {@code ""} or what taskkill said
     * when it exited non-zero. The caller decides from {@link #stillAlive} whether a PID went.
     */
    String taskkill(List<Long> pids, Duration timeout);
}
