package com.verifone.cat.launch;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.devices.forecourt.PortSink;

/**
 * Petro Simulator Suite's pump-state ports, held by the run from before step 3 starts Petro until the run ends. Ported
 * from the Python's {@code cat.launch.steps.simulators} ({@code _hold_petro_ports}, {@code _release_petro_port},
 * {@code _pats_port_free_for_pumps}) and {@code cat.launch.port_guard}.
 *
 * <p>Petro sends each pump's state to two clients, its UI's Pump view on {@value PetroPumps#PETRO_UI_PORT} and PATS on
 * {@value PetroPumps#PATS_FUEL_PORT}, and a push refused on either can freeze its whole fuel side for good (Reg101,
 * three times on 2026-09-22). Step 4's inits change pump states long before the tests, so both are held from step 3.
 * PATS's {@value PetroPumps#PATS_FUEL_PORT} is lent to step 5's pump listener and to step 4's {@code commander-init}
 * (its pump release), and held again after each; {@value PetroPumps#PETRO_UI_PORT} stays held. The holds live in
 * {@link RunContext#petroSinks()}.
 *
 * <p>Deviation: the Python's continuous runs and Launch Simulators hand their own sinks to a run
 * ({@code launch_run(petro_sinks=)}, {@code keeps_petro_ports}); CAT-Java has neither, so a run's holds are always its
 * own, and {@link #releaseAll} frees them when the run ends.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the holds are tested with a fake opener and loopback sockets only.
 */
public final class PetroPorts {

    /** The ports held, in the order the Python takes them. PATS's DCR port 1074 is not held: Petro retries it. */
    static final List<Integer> HELD_PORTS = List.of(PetroPumps.PETRO_UI_PORT, PetroPumps.PATS_FUEL_PORT);

    /** {@code  TCP    0.0.0.0:1072    0.0.0.0:0    LISTENING    32852}. */
    private static final Pattern NETSTAT_LISTEN =
            Pattern.compile("(?m)^\\s*TCP\\s+\\S+:(\\d+)\\s+\\S+\\s+LISTENING\\s+(\\d+)\\s*$");

    /** Opens a hold on a port: a sink that accepts and drops whatever Petro pushes. */
    @FunctionalInterface
    public interface Opener {

        /**
         * @throws DeviceError when the port is taken (a {@code PortSink} throws {@code DeviceUnreachable})
         */
        AutoCloseable open(int port);

        /** The production opener: a {@link PortSink} listening on the port. */
        Opener PORT_SINK = PortSink::new;
    }

    /** One port the run needs and cannot take, and a sentence naming its holder. */
    record Conflict(int port, String who) {
    }

    private PetroPorts() {
    }

    /**
     * Holds every port of {@link #HELD_PORTS} this run does not hold yet. A port something else holds (PATS running,
     * the Pump view open, another CAT) is logged and left to its holder.
     */
    public static void hold(RunContext ctx, StepEmitter step) {
        Map<Integer, AutoCloseable> sinks = ctx.petroSinks();
        for (int port : HELD_PORTS) {
            if (sinks.containsKey(port)) {
                continue;
            }
            try {
                sinks.put(port, ctx.petroPortOpener().open(port));
            } catch (DeviceError | IllegalStateException e) {
                step.log("Petro's pump-state port " + port + " not held (" + e.getMessage() + "); left to its holder",
                        "INFO");
                continue;
            }
            step.log("holding Petro's pump-state port " + port + " for this run", "INFO");
        }
    }

    /** Closes the run's hold on {@code port}. Returns whether the run held it, so the caller holds it again after. */
    public static boolean release(RunContext ctx, int port) {
        AutoCloseable sink = ctx.petroSinks().remove(port);
        if (sink == null) {
            return false;
        }
        closeQuietly(sink);
        return true;
    }

    /** Closes every hold the run has: the run is over, or Petro has been stopped. */
    public static void releaseAll(RunContext ctx) {
        for (Integer port : new ArrayList<>(ctx.petroSinks().keySet())) {
            release(ctx, port);
        }
    }

    /**
     * Step 3's check after the holds: fails the step when another process holds PATS's
     * {@value PetroPumps#PATS_FUEL_PORT} and the run drives pumps, since step 5's pump listener binds it and every pump
     * case would fail at its first lift (run 8e8abe355501, 2026-10-13: 54 pump cases). A port this run holds passes;
     * so does a run with no pump case, or one whose cases are unknown ({@code drivesPumps} empty), as in the Python.
     *
     * @param drivesPumps whether the run's cases drive a pump, or empty when that is not known yet
     */
    static boolean patsPortFreeForPumps(RunContext ctx, StepEmitter step, Optional<Boolean> drivesPumps) {
        int port = PetroPumps.PATS_FUEL_PORT;
        if (ctx.petroSinks().containsKey(port)) {
            return true;
        }
        if (drivesPumps.isEmpty()) {
            step.log("PATS's port " + port + " is held by another process; the cases are not known before step 5, "
                    + "so the run goes on, and any pump case will fail at its first lift", "WARNING");
            return true;
        }
        if (!drivesPumps.get()) {
            return true;
        }
        return step.fail("PATS's port " + port + " is held by another process (a CAT window's Launch Simulators, "
                + "or PATS); this run's pump cases cannot listen on it. Close it and rerun");
    }

    // -- the port guard (System Checks) ---------------------------------------------------------

    /**
     * Each of {@code ports} this run cannot take: not held by this run already, and not bindable. The Python's
     * {@code petro_port_conflicts}.
     */
    static List<Conflict> conflicts(List<Integer> ports, Map<Integer, ?> held, IntPredicate bindable,
            IntFunction<String> holder) {
        List<Conflict> out = new ArrayList<>();
        for (int port : ports) {
            if (!held.containsKey(port) && !bindable.test(port)) {
                out.add(new Conflict(port, holder.apply(port)));
            }
        }
        return out;
    }

    /** Whether {@code port} can be listened on here now, bound as a {@code PortSink} binds it. */
    static boolean bindable(int port) {
        try (ServerSocket server = new ServerSocket(port)) {
            return server.isBound();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A sentence naming the process listening on {@code port}, as far as this controller says: {@code netstat -ano}
     * for the pid, then the process's command line. A CAT is named as one. The Python reads the command line with
     * PowerShell; this reads it from {@link ProcessHandle}, which may not see another user's process.
     */
    static String portHolder(int port) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "another process";
        }
        String netstat = run(List.of("netstat", "-ano", "-p", "TCP"));
        Optional<Long> pid = listeningPid(netstat, port);
        if (pid.isEmpty()) {
            return "another process (netstat names none)";
        }
        String command = ProcessHandle.of(pid.get()).flatMap(p -> p.info().commandLine()).orElse("");
        return describeHolder(pid.get(), command);
    }

    /** The lowest pid {@code netstat -ano} lists as listening on {@code port}. */
    static Optional<Long> listeningPid(String netstat, int port) {
        TreeSet<Long> pids = new TreeSet<>();
        Matcher m = NETSTAT_LISTEN.matcher(netstat == null ? "" : netstat);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == port) {
                pids.add(Long.parseLong(m.group(2)));
            }
        }
        return pids.isEmpty() ? Optional.empty() : Optional.of(pids.first());
    }

    /** The holder's sentence from its pid and command line, as the Python's {@code port_holder} words it. */
    static String describeHolder(long pid, String command) {
        String cmd = command == null ? "" : command;
        String shortCmd = cmd.length() > 120 ? cmd.substring(0, 120) : cmd;
        if (cmd.contains("from cat import main") || cmd.contains("cat-term") || cmd.contains("catt")
                || cmd.contains("cat-java")) {
            return "a CAT window (pid " + pid + "): close it, or start this run from it";
        }
        if (cmd.contains("tools.rig.run_plan") || cmd.contains("cat.launch") || cmd.contains("com.verifone.cat")) {
            return "another CAT run (pid " + pid + ", " + shortCmd + ")";
        }
        return "pid " + pid + (cmd.isEmpty() ? "" : " (" + shortCmd + ")");
    }

    private static String run(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static void closeQuietly(AutoCloseable sink) {
        try {
            sink.close();
        } catch (Exception e) {
            // a sink that will not close has already stopped listening
        }
    }
}
