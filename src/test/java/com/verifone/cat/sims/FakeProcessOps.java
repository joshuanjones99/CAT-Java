package com.verifone.cat.sims;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ProcessOps} that answers from fields the test sets and never touches a process.
 * {@link #taskkill} records the PIDs it was asked to kill and kills nothing, so a test can
 * assert which PIDs the supervisor chose without any risk of killing a real one.
 *
 * <p>Public, so the web tests can use it too. Every field defaults to an empty answer.
 */
public final class FakeProcessOps implements ProcessOps {

    /** What the probe pass sees: the command lines, and why it could not, if so. */
    public String commandLines = "";
    public String commandError = "";

    /** What the listener query answers for {@code adopt_only_match} rows. */
    public String listenerLine = "";

    /** What the listing pass answers, and why it could not, if so. */
    public String listingText = "";
    public String listingError = "";

    /** The PIDs still running after the wait. Empty means every killed PID went. */
    public String stillAliveText = "";

    /** Every {@link #taskkill} call, in order. */
    public final List<List<Long>> killRequests = new ArrayList<>();

    @Override
    public Snapshot commandLines(List<String> names, Duration timeout) {
        return new Snapshot(commandLines, commandError);
    }

    @Override
    public Snapshot listenerCommandLine(int port, Duration timeout) {
        return Snapshot.ok(listenerLine);
    }

    @Override
    public Snapshot listing(List<String> names, List<Integer> ports, Duration timeout) {
        return new Snapshot(listingText, listingError);
    }

    @Override
    public Snapshot stillAlive(List<Long> pids, Duration timeout) {
        return Snapshot.ok(stillAliveText);
    }

    @Override
    public String taskkill(List<Long> pids, Duration timeout) {
        killRequests.add(List.copyOf(pids));
        return "";
    }
}
