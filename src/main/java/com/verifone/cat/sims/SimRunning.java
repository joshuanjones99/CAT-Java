package com.verifone.cat.sims;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Which registry rows are running now, by the supervisor's own adoption test: the Python CAT's
 * {@code cat.sims.fetch.running_rows}. A row the supervisor would adopt is one whose files a running process may hold,
 * so a chosen Simulator Build refuses to swap it.
 *
 * <p>One process listing for all the rows. A listing that could not be made is reported against every row, never read
 * as "nothing is running". Reads only: nothing is started or stopped.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested with a fake {@link ProcessOps} and a fake port check.
 */
public final class SimRunning {

    private SimRunning() {
    }

    /** Row name to why it counts as running; a row that is not running is absent. */
    public static Map<String, String> runningRows(List<SimEntry> entries, SimContext ctx, ProcessOps ops) {
        return runningRows(entries, ctx, ops, Supervisor::firstListening);
    }

    /**
     * As {@link #runningRows(List, SimContext, ProcessOps)}, with the port check a test passes.
     *
     * @param firstListening the first of the hosts accepting on the port, or {@code ""}
     */
    static Map<String, String> runningRows(List<SimEntry> entries, SimContext ctx, ProcessOps ops,
            BiFunction<List<String>, Integer, String> firstListening) {
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
            if (entry.hasTcpReady() && !firstListening.apply(Supervisor.hosts(ctx), entry.readyTcp()).isEmpty()) {
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

    /** A row's state before anything starts: the Launch Simulators tab's first look. */
    public record Running(String status, String detail) {
        /** Listening, or its process is running. */
        public static final String ALREADY_RUNNING = "already running";
        /** Neither. */
        public static final String NOT_RUNNING = "not running";
        /** The process listing could not be taken, and nothing listens: it may be running. */
        public static final String UNKNOWN = "unknown";
        /** A registry row whose placeholders do not resolve. */
        public static final String FAILED = "failed";
    }

    /**
     * Each row's state before anything starts, {@code name -> (status, detail)}: the Python's
     * {@code sims_launch.probe_running} for the simulator rows (its connector rows, the cloud tunnel and BTT's card
     * server, are not here). The question {@link Supervisor#start} asks before each start (D9), asked up front: one
     * process listing for every row, then the row's port and its match token. A listing that could not be taken makes
     * a row {@code unknown}, never {@code not running}. Blocks up to the listing's 15 s and a 0.5 s connect per port.
     */
    public static Map<String, Running> probeRunning(List<SimEntry> entries, SimContext ctx, ProcessOps ops) {
        return probeRunning(entries, ctx, ops, Supervisor::firstListening);
    }

    /** {@link #probeRunning(List, SimContext, ProcessOps)} with the port check a test passes. */
    static Map<String, Running> probeRunning(List<SimEntry> entries, SimContext ctx, ProcessOps ops,
            BiFunction<List<String>, Integer, String> firstListening) {
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
        Running unknown = new Running(Running.UNKNOWN, "could not list running processes: " + error);
        Map<String, Running> rows = new LinkedHashMap<>();
        for (SimEntry entry : entries) {
            String token;
            try {
                token = Supervisor.normalise(ctx.matchToken(entry));
            } catch (IllegalArgumentException e) {
                rows.put(entry.name(), new Running(Running.FAILED, "bad registry row: " + e.getMessage()));
                continue;
            }
            if (entry.hasTcpReady() && !firstListening.apply(Supervisor.hosts(ctx), entry.readyTcp()).isEmpty()) {
                rows.put(entry.name(), new Running(Running.ALREADY_RUNNING, "listening"));
            } else if (!cmdlines.isEmpty() && Supervisor.normalise(cmdlines).contains(token)) {
                rows.put(entry.name(), new Running(Running.ALREADY_RUNNING, "its process is running"));
            } else {
                rows.put(entry.name(), error.isEmpty() ? new Running(Running.NOT_RUNNING, "") : unknown);
            }
        }
        return rows;
    }
}
