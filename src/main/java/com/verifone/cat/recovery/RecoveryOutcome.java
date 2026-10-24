package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of one trip up the ladder. Ported from {@code petrotest.recovery.ladder.RecoveryOutcome}.
 *
 * <p>{@link #rung()} is the rung that restored a testable state, or {@link Rung#DRAIN} when none did.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RecoveryOutcome {

    private final String rig;
    private final String testId;
    private final List<RungAttempt> attempts = new ArrayList<>();
    private Rung rung = Rung.DRAIN;
    private boolean recovered;
    private CaptureBundle bundle;
    private Duration elapsed = Duration.ZERO;

    RecoveryOutcome(String rig, String testId) {
        this.rig = rig;
        this.testId = testId;
    }

    public String rig() {
        return rig;
    }

    public String testId() {
        return testId;
    }

    public Rung rung() {
        return rung;
    }

    public boolean recovered() {
        return recovered;
    }

    public List<RungAttempt> attempts() {
        return List.copyOf(attempts);
    }

    public List<Rung> attempted() {
        return attempts.stream().map(RungAttempt::rung).toList();
    }

    public CaptureBundle bundle() {
        return bundle;
    }

    public Duration elapsed() {
        return elapsed;
    }

    void addAttempt(RungAttempt attempt) {
        attempts.add(attempt);
    }

    void finish(Rung rung, boolean recovered, CaptureBundle bundle, Duration elapsed) {
        this.rung = rung;
        this.recovered = recovered;
        this.bundle = bundle;
        this.elapsed = elapsed;
    }

    /** The operator's view of the trip: the verdict, the rungs walked, and where the capture went. */
    public String render() {
        String verdict = recovered ? "recovered at " + rung.label() : "DRAINED";
        StringBuilder trail = new StringBuilder();
        for (RungAttempt a : attempts) {
            if (trail.length() > 0) {
                trail.append(" -> ");
            }
            trail.append(a.rung().label()).append(a.performed() ? "" : "(failed)");
        }
        return "recovery " + rig + "/" + testId + ": " + verdict + " in " + String.format("%.1f", seconds(elapsed)) + "s\n"
                + "  " + trail + "\n"
                + "  capture: " + (bundle == null ? "(none)" : bundle.collected().size() + " source(s) captured, "
                + bundle.failed().size() + " failed");
    }

    private static double seconds(Duration d) {
        return d.toNanos() / 1_000_000_000.0;
    }
}
