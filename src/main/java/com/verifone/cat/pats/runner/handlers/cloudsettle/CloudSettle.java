package com.verifone.cat.pats.runner.handlers.cloudsettle;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.commander.CgiReplies;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;

/**
 * Waiting for Commander Connect to reach the Commander, which PATS's fixed SLEEPs assume. Ported from the Python
 * {@code petrotest.pats.runner.cloud_settle}, which is the specification.
 *
 * <p>On a Commander onboarded to Commander Connect, a cloud write (any verb but GET or HEAD) lands on the
 * Commander minutes later. The Commander logs {@code Received config update for the dataset - <ds> from cloud}
 * and then {@code Successfully applied the <ds> config update}. After such a write {@link #waitApplied} waits for
 * every dataset received past a mark taken before the send to be applied, then {@link CloudSettleContext#quiet()}
 * more with no new push. A wait that runs out logs a warning and lets the case go on, as PATS would. A Commander
 * not onboarded, or whose status cannot be read, gets no wait.
 *
 * <p>Imported {@code imagecfg.xml} images are not waited for (the Python dropped that wait on 2026-10-23).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the log lines were read from a lab Commander's log (2026-10-21); no case has run
 * with this wait yet in CAT-Java.
 */
public final class CloudSettle {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private static final Pattern RECEIVED =
            Pattern.compile("Received config update for the dataset - (\\S+) from cloud");
    private static final Pattern APPLIED = Pattern.compile("Successfully applied the (\\S+) config update");

    /** The datasets a log text shows received, and those not yet applied after it. */
    public record Pending(Set<String> received, Set<String> waiting) {
    }

    /** The outcome of {@link #settle}: whether it settled, the datasets received, and those still outstanding. */
    public record Settled(boolean done, Set<String> received, Set<String> outstanding) {
    }

    private final CloudSettleContext context;

    /** @param context the log reader, budgets and clock; {@code null} means {@link #disabled()} */
    public CloudSettle(CloudSettleContext context) {
        this.context = context;
    }

    /** A settle that never waits: for a wiring with no Commander log reader. */
    public static CloudSettle disabled() {
        return new CloudSettle(null);
    }

    /** Whether a request with {@code method} is a write that must be waited on (any verb but GET or HEAD). */
    public static boolean isWrite(String method) {
        String verb = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        return !verb.isEmpty() && !verb.equals("GET") && !verb.equals("HEAD");
    }

    /** The datasets received from the cloud in {@code text}, and those not yet applied after it. */
    public static Pending pending(String text) {
        Set<String> received = new TreeSet<>();
        Set<String> waiting = new TreeSet<>();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            Matcher found = RECEIVED.matcher(line);
            if (found.find()) {
                received.add(found.group(1));
                waiting.add(found.group(1));
                continue;
            }
            Matcher applied = APPLIED.matcher(line);
            if (applied.find()) {
                waiting.remove(applied.group(1));
            }
        }
        return new Pending(received, waiting);
    }

    /**
     * Before a cloud request: the Commander's log, marked, when a write needs waiting on. Empty when nothing will be
     * waited for (a read, a Commander not onboarded, no reader wired, or a log that cannot be read: the wait is CAT's
     * addition and never a reason to fail the step).
     */
    public Optional<CommanderLog> mark(CaseRun run, String method) {
        if (context == null || !isWrite(method) || !onboarded(run)) {
            return Optional.empty();
        }
        Optional<CommanderLog> log = context.openLog();
        if (log.isEmpty()) {
            LOG.warn("{}: no Commander log reader is wired; not waiting for the cloud write", run.kase().key());
            return Optional.empty();
        }
        try {
            log.get().mark();
        } catch (CommanderLogException e) {
            LOG.warn("{}: not waiting for the cloud write: {}", run.kase().key(), e.getMessage());
            log.get().close();
            return Optional.empty();
        }
        return log;
    }

    /** Closes a mark whose request failed: there is nothing to wait for. */
    public void release(Optional<CommanderLog> mark) {
        mark.ifPresent(CommanderLog::close);
    }

    /**
     * After a cloud write: waits for the Commander to apply what the cloud pushes back. A status outside 2xx is not
     * waited for. Always closes the mark.
     */
    public void waitApplied(CaseRun run, Optional<CommanderLog> mark, int status, String what) {
        if (mark.isEmpty()) {
            return;
        }
        CommanderLog log = mark.get();
        try {
            if (status < 200 || status >= 300) {
                return;
            }
            long started = context.nanoTime();
            Settled done = settle(log, "the Commander to apply " + what, null, Set.of());
            if (!done.done()) {
                LOG.warn("{}: {}: after {} s the Commander has received {} from the cloud and not applied {}; "
                        + "going on, as PATS would", run.kase().key(), what,
                        context.applyBudget().toSeconds(),
                        done.received().isEmpty() ? "nothing" : done.received(),
                        done.outstanding().isEmpty() ? "none" : done.outstanding());
                return;
            }
            LOG.info("{}: {}: the Commander applied {} after {} s", run.kase().key(), what,
                    String.join(", ", done.received()),
                    String.format(Locale.ROOT, "%.0f", (context.nanoTime() - started) / 1e9));
        } catch (CommanderLogException e) {
            LOG.warn("{}: stopped waiting for {}: {}", run.kase().key(), what, e.getMessage());
        } finally {
            log.close();
        }
    }

    /**
     * Waits until every dataset the cloud pushed past {@code log}'s mark is applied, each of {@code expect} has
     * arrived, and then {@code quiet} has passed with no new push, all within {@code budget} (null for the
     * context's {@code applyBudget}).
     *
     * <p>Quiet runs from the last push, not from the last new dataset name: a restore of about 95 departments
     * arrived one push every 2 s for minutes, all named {@code departmentcfg} (2026-10-22). Writes are not counted:
     * three age-validation POSTs arrived as one push.
     *
     * @throws CommanderLogException when the log cannot be read
     */
    public Settled settle(CommanderLog log, String what, Duration budget, Set<String> expect) {
        Duration limit = budget == null ? context.applyBudget() : budget;
        long deadline = context.nanoTime() + limit.toNanos();
        long quietNanos = context.quiet().toNanos();
        long pushes = -1;
        long since = 0;
        while (true) {
            String text = log.linesSinceMark();
            Pending state = pending(text);
            long count = RECEIVED.matcher(text).results().count();
            boolean whole = !state.received().isEmpty() && state.waiting().isEmpty()
                    && state.received().containsAll(expect);
            if (!whole) {
                pushes = -1;
            } else if (pushes != count) {
                pushes = count;
                since = context.nanoTime();
            } else if (context.nanoTime() - since >= quietNanos) {
                Pending last = pending(log.linesSinceMark());
                return new Settled(true, last.received(), Set.of());
            }
            if (context.nanoTime() >= deadline) {
                Pending last = pending(log.linesSinceMark());
                Set<String> outstanding = new TreeSet<>(last.waiting());
                for (String name : expect) {
                    if (!last.received().contains(name)) {
                        outstanding.add(name);
                    }
                }
                return new Settled(false, last.received(), outstanding);
            }
            context.pause(context.pollInterval());
        }
    }

    /**
     * Whether the Commander is onboarded to Commander Connect, asked each time. A Commander that cannot say is taken
     * as not onboarded: these waits are CAT's.
     */
    boolean onboarded(CaseRun run) {
        try {
            CgiReplies.CloudConnectStatus status =
                    run.fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class).cloudConnectStatus();
            if (!status.registered()) {
                LOG.info("{}: Commander Connect not onboarded ({}): no wait", run.kase().key(), status.detail());
            }
            return status.registered();
        } catch (RuntimeException e) {
            LOG.warn("{}: Commander Connect status unread, not waiting: {}", run.kase().key(), e.getMessage());
            return false;
        }
    }
}
