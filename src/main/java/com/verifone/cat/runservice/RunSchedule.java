package com.verifone.cat.runservice;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.launch.LaunchConfig;

/**
 * The schedule (Schedule run, Clear schedule) and the continuous run (Continuous run), as the Python
 * CAT's {@code cat.schedule.RunScheduler} and {@code CatService}'s schedule and continuous methods.
 *
 * <p>Nothing here persists: the Python's scheduler is in memory on purpose ({@code cat/schedule.py}:
 * a schedule that survives a restart is a run nobody is watching). Not thread-safe; {@link RunService}
 * calls it only while holding its own lock, and starts runs through the {@link Starter} it was given.
 *
 * <p>Refusals (busy, no rig, a missing requirement) are decided by {@link RunService} before it calls
 * in here; this class only keeps the state and says what the Overview should show.
 */
public final class RunSchedule {

    /** Starts one run; {@code done} gets the outcome of that run once it has ended. */
    @FunctionalInterface
    interface Starter {
        boolean start(LaunchConfig config, Consumer<PassEnd> done);
    }

    /** How a run ended, for a continuous pass: its id, or {@code null} when stopped or never started. */
    record PassEnd(String runId, int verdicts) {
        static final PassEnd NONE = new PassEnd(null, 0);
    }

    /** One pending run. */
    record ScheduledRun(int id, LocalDateTime at, LaunchConfig config) {}

    /** A pending run as the Overview shows it: its time, the seconds left, and its plans. */
    public record PendingLine(int id, String at, long secondsLeft, String plans) {}

    /** Everything the Overview's schedule block shows. */
    public record View(String status, List<PendingLine> pending, boolean continuous, int pass, String holdUntil) {

        /** Whether something is waiting that the page should keep up with. */
        public boolean active() {
            return !pending.isEmpty() || continuous;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final Supplier<LocalDateTime> now;
    private final Starter starter;
    private final List<ScheduledRun> runs = new ArrayList<>();
    private int nextId = 1;
    private String status = "";

    private LaunchConfig continuous;
    private int pass;
    private LocalDateTime passStarted;
    private Duration passLength;
    private LocalDateTime hold;

    RunSchedule(Supplier<LocalDateTime> now, Starter starter) {
        this.now = now;
        this.starter = starter;
    }

    // ------------------------------------------------------------------ schedule

    /** Schedule run: one run per time in {@code raw}, against {@code config}. Sets the status line. */
    void schedule(String raw, LaunchConfig config) {
        List<LocalDateTime> times;
        try {
            times = ScheduleTimes.parseScheduleTimes(raw, now.get());
        } catch (IllegalArgumentException e) {
            status = "Could not read that time - " + e.getMessage();
            return;
        }
        if (times.isEmpty()) {
            status = "Enter a time to schedule a run.";
            return;
        }
        for (LocalDateTime at : times) {
            add(at, config);
        }
        status = times.size() > 1
                ? "Scheduled " + times.size() + " run(s)."
                : "Scheduled for " + STAMP.format(times.get(0)) + ".";
    }

    /** Sets the status line to a refusal, when {@link RunService} refused the schedule before it was read. */
    void reject(String why) {
        status = "Cannot schedule: " + why;
    }

    void clear() {
        runs.clear();
        status = "Schedule cleared.";
    }

    /** Drops one pending run. Returns whether it was there. */
    boolean cancel(int id) {
        return runs.removeIf(run -> run.id() == id);
    }

    /**
     * Called on each tick, only while nothing holds the rig. Starts the continuous pass whose hold has
     * passed, else the earliest due scheduled run; the rest that are due wait one tick.
     */
    void tick() {
        LocalDateTime at = now.get();
        if (hold != null && !at.isBefore(hold)) {
            hold = null;
            if (continuous != null) {
                nextPass();
                return;
            }
        }
        List<ScheduledRun> ready = takeDue(at);
        if (ready.isEmpty()) {
            return;
        }
        ScheduledRun first = ready.get(0);
        for (ScheduledRun later : ready.subList(1, ready.size())) {
            add(at, later.config());
        }
        starter.start(first.config(), null);
    }

    /** The Overview's view of the schedule, at the current time. */
    View view() {
        LocalDateTime at = now.get();
        List<PendingLine> lines = new ArrayList<>();
        for (ScheduledRun run : runs) {
            long left = Math.max(0, Duration.between(at, run.at()).getSeconds());
            lines.add(new PendingLine(run.id(), STAMP.format(run.at()), left,
                    String.join(", ", run.config().testPlans())));
        }
        return new View(status, List.copyOf(lines), continuous != null, pass,
                hold == null ? "" : CLOCK.format(hold));
    }

    private void add(LocalDateTime at, LaunchConfig config) {
        runs.add(new ScheduledRun(nextId++, at, config));
        runs.sort(Comparator.comparing(ScheduledRun::at));
    }

    /** Removes and returns the runs whose time has come, soonest first. */
    private List<ScheduledRun> takeDue(LocalDateTime at) {
        List<ScheduledRun> ready = new ArrayList<>();
        for (ScheduledRun run : runs) {
            if (!run.at().isAfter(at)) {
                ready.add(run);
            }
        }
        runs.removeAll(ready);
        return ready;
    }

    // ------------------------------------------------------------------ continuous

    boolean continuousOn() {
        return continuous != null;
    }

    /** Continuous run on: the first pass starts now, or past the forced end of day. */
    void startContinuous(LaunchConfig config) {
        continuous = config;
        pass = 0;
        passLength = null;
        status = "Continuous run on.";
        nextPassOrHold();
    }

    /**
     * Continuous run off. Idle, it ends at once; with a pass in flight it ends after that pass.
     *
     * @return the notice for the page, or empty when there is none
     */
    String finishContinuous(boolean busy) {
        if (continuous == null) {
            return "No continuous run in progress.";
        }
        continuous = null;
        if (!busy) {
            endContinuous("Continuous run finished.");
            return "";
        }
        status = "Continuous run: finishing after this pass.";
        return "The continuous run will finish after pass " + pass + ".";
    }

    /** A pass has ended: start the next, or end the continuous run and say why. */
    void passEnded(PassEnd end) {
        int done = pass;
        if (passStarted != null) {
            passLength = Duration.between(passStarted, now.get());
        }
        if (continuous == null) {
            endContinuous("Continuous run finished after " + done + " pass(es).");
        } else if (end.runId() == null) {
            endContinuous("Continuous run ended: pass " + done + " was stopped or never started.");
        } else if (end.verdicts() == 0) {
            endContinuous("Continuous run ended: pass " + done + " ran no test cases.");
        } else {
            nextPassOrHold();
        }
    }

    private void nextPassOrHold() {
        LocalDateTime wait = ForcedAeod.continuousHold(now.get(),
                passLength == null ? ForcedAeod.FIRST_PASS_LENGTH : passLength);
        if (wait == null) {
            nextPass();
            return;
        }
        hold = wait;
        status = "Continuous run: pass " + (pass + 1) + " waits until " + CLOCK.format(wait)
                + ", past the Commander's forced automatic end of day.";
    }

    private void nextPass() {
        pass++;
        passStarted = now.get();
        status = "Continuous run: pass " + pass + " running.";
        if (!starter.start(continuous, this::passEnded)) {
            endContinuous("Continuous run ended: pass " + pass + " could not start.");
        }
    }

    private void endContinuous(String message) {
        continuous = null;
        hold = null;
        status = message;
    }
}
