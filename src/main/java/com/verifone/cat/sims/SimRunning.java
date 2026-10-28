package com.verifone.cat.sims;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which registry rows are running now, by the supervisor's own adoption test: the Python CAT's
 * {@code cat.sims.fetch.running_rows}. A row the supervisor would adopt is one whose files a running process may hold,
 * so a chosen Simulator Build refuses to swap it.
 *
 * <p>One process listing for all the rows. A listing that could not be made is reported against every row, never read
 * as "nothing is running". Reads only: nothing is started or stopped.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested with a fake {@link ProcessOps}.
 */
public final class SimRunning {

    private SimRunning() {
    }

    /** Row name to why it counts as running; a row that is not running is absent. */
    public static Map<String, String> runningRows(List<SimEntry> entries, SimContext ctx, ProcessOps ops) {
        String cmdlines;
        String error;
        try {
            ProcessOps.Snapshot snapshot = ops.commandLines(Supervisor.processNames(entries, ctx),
                    Supervisor.PROBE_TIMEOUT);
            cmdlines = snapshot.text();
            error = snapshot.error();
        } catch (IllegalArgumentException e) {
            cmdlines = "";
            error = e.getMessage();
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (SimEntry entry : entries) {
            String why = "";
            if (entry.hasTcpReady() && !Supervisor.firstListening(Supervisor.hosts(ctx), entry.readyTcp()).isEmpty()) {
                why = "something is listening on its port " + entry.readyTcp();
            } else if (!cmdlines.isEmpty()
                    && Supervisor.normalise(cmdlines).contains(Supervisor.normalise(ctx.matchToken(entry)))) {
                why = "its process is running";
            } else if (!error.isEmpty()) {
                why = "could not list processes to tell (" + error + ")";
            }
            if (!why.isEmpty()) {
                if (entry.stagedOnly()) {
                    why += "; CAT's own VATS connector runs from this copy";
                }
                found.put(entry.name(), why);
            }
        }
        return found;
    }
}
