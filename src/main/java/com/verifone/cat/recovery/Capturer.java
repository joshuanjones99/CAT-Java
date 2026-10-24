package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.verifone.cat.preflight.Bounded;

/**
 * Rung 0: capture before recovery, unconditionally (DESIGN.md 6.3). Ported from
 * {@code petrotest.recovery.capture.Capturer}.
 *
 * <p>Each source runs in parallel under one bounded budget, and a source that fails or overruns is
 * recorded, never allowed to stop the ladder. The Python version also writes an artifact folder and
 * a manifest under {@code artifacts/<run>/<test-id>/}; this port does not write them yet, so a
 * bundle here is a record of what was asked for, not files on disk.
 *
 * <p>Python's {@code _capture_sources} returns no sources today (simhub, the one source it had, is
 * gone: HANDOFF.md 1.120), so the default constructor captures nothing and says so.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Capturer {

    /** The Python {@code CAPTURE_BUDGET_SECONDS}. */
    public static final Duration CAPTURE_BUDGET = Duration.ofSeconds(30);

    /** A named source of evidence. Returns a description of what it collected, or throws. */
    public interface Source {
        String name();

        String collect();
    }

    private final List<Source> sources;
    private final Duration budget;

    public Capturer() {
        this(List.of(), CAPTURE_BUDGET);
    }

    public Capturer(List<Source> sources, Duration budget) {
        this.sources = List.copyOf(sources);
        this.budget = budget == null ? CAPTURE_BUDGET : budget;
    }

    public CaptureBundle capture(String runId, String testId, String reason) {
        List<Supplier<String>> work = new ArrayList<>(sources.size());
        for (Source source : sources) {
            work.add(source::collect);
        }
        List<String> collected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (work.isEmpty()) {
            return new CaptureBundle(runId, testId, collected, failed);
        }
        List<Bounded.Completed<String>> done = Bounded.runAll(work, budget);
        for (int i = 0; i < sources.size(); i++) {
            Bounded.Completed<String> outcome = done.get(i);
            String name = sources.get(i).name();
            if (outcome.timedOut()) {
                failed.add(name + " (over the " + budget.toSeconds() + "s capture budget)");
            } else if (outcome.failure() != null) {
                failed.add(name + " (" + outcome.failure().getClass().getSimpleName() + ": "
                        + outcome.failure().getMessage() + ")");
            } else {
                collected.add(name + ": " + outcome.value());
            }
        }
        return new CaptureBundle(runId, testId, collected, failed);
    }
}
