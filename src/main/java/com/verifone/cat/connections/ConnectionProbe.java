package com.verifone.cat.connections;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks whether each endpoint is answering, every probe bounded by a timeout.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code cat.connections.probe}
 * and {@code probe_all} are the specification; the Java port keeps their rules:
 *
 * <ul>
 *   <li>A probe is a TCP connect to the service port, not a ping. A Commander that answers
 *       ICMP with a dead Tomcat is not "up" in any sense a run cares about.</li>
 *   <li>A target with no port reports {@code unknown}, not {@code down}.</li>
 *   <li>Probes run concurrently, and the budget is per probe, not per sweep: every probe in a
 *       sweep starts at once and shares one deadline. A blackholed address costs exactly the
 *       timeout, however many targets there are.</li>
 *   <li>No single target can take the sweep down. An unexpected failure becomes that row's
 *       {@code unknown}, and the other rows still report.</li>
 * </ul>
 *
 * <p>Name resolution in the Java port happens on the worker thread, so a slow DNS server is
 * bounded by the same deadline as the connect. A worker that outlives its deadline is cancelled
 * as far as the JDK allows, which for a stuck resolver means it finishes on its own.
 */
public final class ConnectionProbe {

    private final ExecutorService executor;

    /**
     * @param executor runs the probes; it should have a thread per target a sweep may
     *                 contain, or the later probes queue and report {@code down} for time
     *                 they spent waiting for a thread. The caller shuts it down.
     */
    public ConnectionProbe(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** A cached pool of daemon threads, sized to whatever a sweep needs. */
    public static ExecutorService newProbePool() {
        ThreadFactory daemons = runnable -> {
            Thread thread = new Thread(runnable, "cat-connection-probe");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(daemons);
    }

    /** One probe, bounded by {@code timeout}. */
    public ProbeResult probe(Target target, Duration timeout) {
        return probeAll(List.of(target), timeout).get(0);
    }

    /**
     * Every target probed at once, one result per target, in the same order.
     *
     * @param timeout the budget for each probe, counted from the start of the sweep
     */
    public List<ProbeResult> probeAll(List<Target> targets, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Future<ProbeResult>> futures = new ArrayList<>(targets.size());
        for (Target target : targets) {
            futures.add(submit(target, timeout));
        }
        List<ProbeResult> results = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            results.add(await(targets.get(i), futures.get(i), deadline, timeout));
        }
        return results;
    }

    private Future<ProbeResult> submit(Target target, Duration timeout) {
        try {
            return executor.submit(() -> run(target, timeout));
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private ProbeResult await(Target target, Future<ProbeResult> future, long deadline, Duration timeout) {
        if (future == null) {
            return unknown(target, "probe could not start: the probe pool refused it");
        }
        long remaining = Math.max(0L, deadline - System.nanoTime());
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return timedOut(target, timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return unknown(target, "probe failed: " + cause.getClass().getSimpleName() + ": " + message(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return unknown(target, "probe interrupted");
        }
    }

    private static ProbeResult run(Target target, Duration timeout) {
        if (target.kind() == Target.Kind.LOCAL) {
            return HostAddressCheck.check(target);
        }
        if (target.port() == null) {
            return unknown(target, "no address declared in the rig file");
        }
        return tcpConnect(target, timeout);
    }

    private static ProbeResult tcpConnect(Target target, Duration timeout) {
        // Socket.connect(address, 0) blocks with no limit, so the millisecond floor is what
        // keeps a zero timeout from becoming an unbounded wait.
        int connectMillis = (int) Math.max(1L, timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), connectMillis);
            return new ProbeResult(target.name(), ProbeState.UP,
                    "connected on port " + target.port(), Target.Kind.TCP);
        } catch (SocketTimeoutException e) {
            return new ProbeResult(target.name(), ProbeState.DOWN,
                    "no answer on port " + target.port() + " in " + seconds(timeout) + "s", Target.Kind.TCP);
        } catch (IOException e) {
            return new ProbeResult(target.name(), ProbeState.DOWN,
                    "port " + target.port() + ": " + message(e), Target.Kind.TCP);
        }
    }

    private static ProbeResult timedOut(Target target, Duration timeout) {
        if (target.kind() == Target.Kind.LOCAL) {
            // The local check is name resolution plus an interface listing, neither of which
            // can be interrupted, so the worker outlives this call. Reporting unknown beats
            // letting the bound take the caller's sweep with it.
            return new ProbeResult(target.name(), ProbeState.UNKNOWN,
                    "local interface check did not finish in " + seconds(timeout) + "s", Target.Kind.LOCAL);
        }
        return new ProbeResult(target.name(), ProbeState.DOWN,
                "no answer on port " + target.port() + " in " + seconds(timeout) + "s", Target.Kind.TCP);
    }

    static ProbeResult unknown(Target target, String detail) {
        return new ProbeResult(target.name(), ProbeState.UNKNOWN, detail, target.kind());
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** A timeout as the operator reads it: {@code 3}, {@code 0.5}. */
    static String seconds(Duration d) {
        double s = d.toMillis() / 1000.0;
        return s == Math.floor(s) ? String.valueOf((long) s) : String.valueOf(s);
    }
}
