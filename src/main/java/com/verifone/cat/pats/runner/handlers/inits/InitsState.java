package com.verifone.cat.pats.runner.handlers.inits;

import com.verifone.cat.commander.CommanderInit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The waits around a case's driver inits, ended by the Commander's and Petro's own signals. Ported from the
 * Python {@code petrotest.pats.runner.inits}, which documents what each wait is for and what ends it; the
 * timings here are that module's, and the Python is the specification.
 *
 * <p>PATS brackets every {@code CONTROL_FUNCTION} with fixed waits: the case's SLEEP before it, the
 * {@link Timing#replyPause() 20 s} after its reply, and a SLEEP after that. A wait here ends on what it
 * waits for, as Petro's log shows it ({@link CommanderInit#reinitSignal}), and is capped at what PATS waited
 * there. Without Petro's log the waits are PATS's, as before.
 *
 * <p>State is per run, as {@code RunState} is: one instance is shared by the run's cases, because a case can
 * end on an init that the next case's first SLEEP must wait for. It is not thread-safe; a run has one runner
 * thread.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the Java port's waits are fake-backed; the timings come from the Python's
 * rig runs (HANDOFF.md §1.134 and the module's docstring).
 */
public final class InitsState {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** What Petro logs once a driver init has taken effect, by init command (the Python's {@code SIGNALS}). */
    static final Map<String, Pattern> SIGNALS = Map.of(
            "cfuelinit", Pattern.compile(Pattern.quote("Processing restart pump request")),
            "cfueldrvinit", Pattern.compile(Pattern.quote("Processing restart pump request")),
            "cdcrinit", CommanderInit.dcrDisplaySignal(1),
            "cdcrdriverinit", CommanderInit.dcrDisplaySignal(1),
            "crefreshcfg", CommanderInit.dcrDisplaySignal(1));

    private static final String REFRESH = "crefreshcfg";

    /**
     * The waits' lengths. Production uses {@link #production()}; a test passes shorter ones.
     *
     * @param quiet          how long Petro's log must go without another signal line for the init to be over
     * @param poll           how often Petro's log is re-read while an init settles
     * @param refreshFloor   the least time after {@code crefreshcfg} before it counts as settled
     * @param betweenInits   the spacing between one change to the Commander's configuration and the next init
     * @param replyPause     PATS's pause after an init's reply
     */
    public record Timing(Duration quiet, Duration poll, Duration refreshFloor, Duration betweenInits,
            Duration replyPause) {

        public static Timing production() {
            return new Timing(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(15),
                    Duration.ofSeconds(5), Duration.ofSeconds(20));
        }
    }

    /** An init sent and not yet seen to settle. */
    private static final class Pending {
        final String init;
        final long sentNanos;
        final long mark;
        int count;
        long changedNanos;

        Pending(String init, long sentNanos, long mark) {
            this.init = init;
            this.sentNanos = sentNanos;
            this.mark = mark;
        }

        boolean settled(long now, int seen, Timing timing) {
            if (seen != count) {
                count = seen;
                changedNanos = now;
            }
            long floor = REFRESH.equals(init) ? timing.refreshFloor().toNanos() : 0L;
            return count > 0 && now - changedNanos >= timing.quiet().toNanos() && now - sentNanos >= floor;
        }
    }

    private final Supplier<Path> petroLog;
    private final Waits waits;
    private final Timing timing;
    private final List<Pending> pending = new ArrayList<>();
    private Long lastChangeNanos;
    private Duration banked = Duration.ZERO;

    /**
     * @param petroLog where Petro Simulator Suite's log is, re-resolved at each call
     * @param waits    the clock and the pauser the waits use
     * @param timing   the waits' lengths
     */
    public InitsState(Supplier<Path> petroLog, Waits waits, Timing timing) {
        this.petroLog = Objects.requireNonNull(petroLog, "petroLog");
        this.waits = Objects.requireNonNull(waits, "waits");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /** The production state: Petro's log under the tools root, the real clock and pauser, PATS's timings. */
    public static InitsState production(Path toolsRoot) {
        return new InitsState(() -> CommanderInit.petroLog(toolsRoot), Waits.production(), Timing.production());
    }

    /** Whether {@code init} has a signal Petro logs, so its waits can end on it. */
    public static boolean knows(String init) {
        return init != null && SIGNALS.containsKey(init.toLowerCase(Locale.ROOT));
    }

    /** Forgets every init and change. For a session that starts over, and for tests. */
    public void reset() {
        pending.clear();
        lastChangeNanos = null;
        banked = Duration.ZERO;
    }

    /** Whether Petro's log is there to read the signals from. */
    public boolean watching() {
        return Files.isRegularFile(petroLog.get());
    }

    /**
     * Petro's log size now: the offset a signal is counted from. {@code -1} when there is no log to read.
     */
    public long petroMark() {
        try {
            return Files.size(petroLog.get());
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Notes a change to the Commander's configuration: an import's reply. */
    public void changed() {
        lastChangeNanos = waits.nanos();
    }

    /**
     * Notes an init's reply. An init with no known signal, or sent with no Petro log to read ({@code mark < 0}),
     * is not watched: the SLEEPs around it are PATS's.
     */
    public void sent(String init, long mark) {
        changed();
        if (knows(init) && mark >= 0) {
            pending.add(new Pending(init.toLowerCase(Locale.ROOT), waits.nanos(), mark));
        } else {
            banked = Duration.ZERO;
        }
    }

    /** The inits sent and not yet seen to settle, by command name. */
    public List<String> pending() {
        return pending.stream().map(p -> p.init).toList();
    }

    /**
     * A SLEEP straight after an init: until every init sent is settled, capped at the SLEEP plus the pause
     * after the reply it stands in for, plus what was not waited of the SLEEP before the init.
     *
     * @param seconds the SLEEP's length
     */
    public void afterInit(String caseKey, double seconds) {
        Duration cap = seconds(seconds).plus(owed()).plus(bankedOnce());
        settle(caseKey, cap, "its " + format(seconds) + "s SLEEP");
    }

    /**
     * A SLEEP whose next action is an init: until every init still pending has settled, then until
     * {@link Timing#betweenInits()} has passed since the last change, capped at the SLEEP (plus any pause still
     * owed after a pending init's reply).
     *
     * @param seconds the SLEEP's length
     */
    public void beforeInit(String caseKey, double seconds) {
        long started = waits.nanos();
        Duration total = seconds(seconds);
        if (!pending.isEmpty()) {
            total = total.plus(owed());
            settle(caseKey, total, "the " + format(total.toMillis() / 1000.0) + "s SLEEP before an init");
        }
        Duration left = total.minus(elapsed(started));
        Duration pause = lastChangeNanos == null
                ? timing.betweenInits()
                : positive(timing.betweenInits().minusNanos(waits.nanos() - lastChangeNanos));
        waits.pause(min(positive(pause), positive(left)));
        banked = positive(total.minus(elapsed(started)));
        LOG.info("{}: SLEEP {}s before an init ended after {}s", caseKey, format(total.toMillis() / 1000.0),
                format(elapsed(started).toMillis() / 1000.0));
    }

    /**
     * PATS's pause after an init's reply, before an action that is not a SLEEP waiting on the init: until the
     * init has settled or the pause has passed since its reply.
     */
    public void replyPause(String caseKey) {
        if (pending.isEmpty()) {
            return;
        }
        long newest = newestSent();
        Duration left = timing.replyPause().minusNanos(waits.nanos() - newest).plus(bankedOnce());
        if (left.isNegative() || left.isZero()) {
            pending.clear();
            return;
        }
        settle(caseKey, left, "PATS's pause after the reply");
    }

    // -- internals ------------------------------------------------------------------------------

    private int count(Pending p) {
        Path log = petroLog.get();
        return CommanderInit.reinitSignal(log, p.mark, SIGNALS.get(p.init));
    }

    /**
     * Waits, up to {@code cap}, for every pending init to settle. Logs each one's outcome and forgets it either
     * way: one not settled by {@code cap} has had PATS's whole wait.
     */
    private boolean settle(String caseKey, Duration cap, String what) {
        long started = waits.nanos();
        List<Pending> open = new ArrayList<>(pending);
        BooleanSupplier allSettled = () -> {
            long now = waits.nanos();
            for (Pending p : List.copyOf(open)) {
                if (p.settled(now, count(p), timing)) {
                    open.remove(p);
                    LOG.info("{}: {} settled {}s after its reply ({} signal lines), {}", caseKey, p.init,
                            format((now - p.sentNanos) / 1e9), p.count, what);
                }
            }
            return open.isEmpty();
        };
        boolean ok = waits.pollUntil(allSettled, positive(cap), timing.poll());
        if (!ok) {
            for (Pending p : open) {
                LOG.info("{}: {} not settled after {}s ({} signal lines); {} waited out", caseKey, p.init,
                        format((waits.nanos() - started) / 1e9), p.count, what);
            }
        }
        pending.clear();
        return ok;
    }

    /** What is left of PATS's pause after the newest pending init's reply. */
    private Duration owed() {
        if (pending.isEmpty()) {
            return Duration.ZERO;
        }
        return positive(timing.replyPause().minusNanos(waits.nanos() - newestSent()));
    }

    private long newestSent() {
        long newest = Long.MIN_VALUE;
        for (Pending p : pending) {
            newest = Math.max(newest, p.sentNanos);
        }
        return newest;
    }

    /** The SLEEP before the init that {@link #beforeInit} did not wait, once: a wait after the init may need it. */
    private Duration bankedOnce() {
        Duration once = banked;
        banked = Duration.ZERO;
        return once;
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(waits.nanos() - started);
    }

    private static Duration seconds(double seconds) {
        return Duration.ofNanos(Math.round(seconds * 1e9));
    }

    private static Duration positive(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.0f", seconds);
    }
}
