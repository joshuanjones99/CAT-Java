package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The run's record of repairs, recoveries and drains. Ported from {@code petrotest.recovery.summary}.
 *
 * <p>Thread-safe: preflight verifies in parallel and the ladder may run on more than one rig. A
 * summary that dropped records under concurrency would under-report exactly the rig that is failing
 * most, so every method takes the monitor.
 *
 * <p>{@link #findings()} carries the two sentences DESIGN.md asks for: a rig that needed five repairs
 * (6.2), and a run where most recoveries needed rung 4 or 5 (6.3). Both thresholds are the Python's.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RunSummary {

    /** DESIGN.md 6.2: "a rig that needs five repairs a night is a hardware problem being hidden by software." */
    public static final int REPAIRS_PER_RIG_CONCERN = 5;

    /** DESIGN.md 6.3: "If most failures need rung 4 or 5". Read as a majority. */
    public static final double STRUCTURAL_RUNG_SHARE = 0.5;

    public record RepairRecord(String rig, String check, String outcome, String detail, Duration elapsed) {}

    public record RecoveryRecord(String rig, String testId, Rung rung, List<Rung> attempted, boolean recovered,
            Duration elapsed, String artifacts) {

        public RecoveryRecord {
            attempted = List.copyOf(attempted);
        }
    }

    public record DrainRecord(String rig, String reason) {}

    private final String runId;
    private final List<RepairRecord> repairs = new ArrayList<>();
    private final List<RecoveryRecord> recoveries = new ArrayList<>();
    private final List<DrainRecord> drains = new ArrayList<>();

    public RunSummary(String runId) {
        this.runId = runId == null ? "" : runId;
    }

    public String runId() {
        return runId;
    }

    public synchronized void recordRepair(String rig, String check, String outcome, String detail, Duration elapsed) {
        repairs.add(new RepairRecord(rig, check, outcome, detail, elapsed));
    }

    public synchronized void recordRecovery(String rig, String testId, Rung rung, List<Rung> attempted,
            boolean recovered, Duration elapsed, String artifacts) {
        recoveries.add(new RecoveryRecord(rig, testId, rung, attempted, recovered, elapsed, artifacts));
    }

    public synchronized void recordDrain(String rig, String reason) {
        drains.add(new DrainRecord(rig, reason));
    }

    public synchronized List<RepairRecord> repairs() {
        return List.copyOf(repairs);
    }

    public synchronized List<RecoveryRecord> recoveries() {
        return List.copyOf(recoveries);
    }

    public synchronized List<DrainRecord> drains() {
        return List.copyOf(drains);
    }

    /** DESIGN.md 6.3's distribution over the rung that worked for each recovery. */
    public synchronized Map<Rung, Integer> rungDistribution() {
        Map<Rung, Integer> counts = new EnumMap<>(Rung.class);
        for (RecoveryRecord record : recoveries) {
            counts.merge(record.rung(), 1, Integer::sum);
        }
        return counts;
    }

    public synchronized Map<String, Integer> repairsByRig() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RepairRecord record : repairs) {
            counts.merge(record.rig(), 1, Integer::sum);
        }
        return counts;
    }

    public synchronized Map<String, Integer> repairsByCheck() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RepairRecord record : repairs) {
            counts.merge(record.check(), 1, Integer::sum);
        }
        return counts;
    }

    /** The sentences DESIGN.md asks for. Empty when the run was clean. */
    public synchronized List<String> findings() {
        List<String> found = new ArrayList<>();
        List<Map.Entry<String, Integer>> rigs = new ArrayList<>(repairsByRig().entrySet());
        rigs.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        for (Map.Entry<String, Integer> rig : rigs) {
            if (rig.getValue() >= REPAIRS_PER_RIG_CONCERN) {
                String worst = repairs.stream()
                        .filter(r -> r.rig().equals(rig.getKey()))
                        .collect(Collectors.groupingBy(RepairRecord::check, LinkedHashMap::new, Collectors.counting()))
                        .entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(3)
                        .map(e -> e.getKey() + " x" + e.getValue())
                        .collect(Collectors.joining(", "));
                found.add(rig.getKey() + " needed " + rig.getValue() + " repairs this run (" + worst + "). "
                        + "DESIGN.md 6.2: a rig that needs five repairs a night is a hardware problem "
                        + "being hidden by software.");
            }
        }
        long laddered = recoveries.stream().filter(r -> r.rung() != Rung.CAPTURE).count();
        long structural = recoveries.stream().filter(r -> r.rung() != Rung.CAPTURE && r.rung().isStructuralSignal()).count();
        if (laddered > 0 && (double) structural / laddered >= STRUCTURAL_RUNG_SHARE) {
            found.add(structural + "/" + laddered + " recoveries needed rung 4 or 5. DESIGN.md 6.3: if most "
                    + "failures need rung 4 or 5, something structural is wrong.");
        }
        for (DrainRecord drain : drains) {
            found.add(drain.rig() + " was DRAINED (rung 6): " + drain.reason());
        }
        return found;
    }

    /** The run summary as an operator reads it. */
    public synchronized String render() {
        List<String> lines = new ArrayList<>();
        List<String> findings = findings();
        if (!findings.isEmpty()) {
            lines.add("!! operability findings");
            for (String line : findings) {
                lines.add("!!   " + line);
            }
        }
        if (!repairs.isEmpty()) {
            lines.add("preflight repairs: " + repairs.size());
            for (Map.Entry<String, Integer> rig : new java.util.TreeMap<>(repairsByRig()).entrySet()) {
                lines.add("  " + rig.getKey() + ": " + rig.getValue());
            }
            for (RepairRecord r : repairs) {
                lines.add("    " + r.rig() + "/" + r.check() + " " + r.outcome() + " ("
                        + String.format("%.1f", r.elapsed().toNanos() / 1e9) + "s) " + r.detail());
            }
        }
        if (!recoveries.isEmpty()) {
            lines.add("recovery ladder: " + recoveries.size() + " invocation(s)");
            int total = recoveries.size();
            for (Map.Entry<Rung, Integer> e : rungDistribution().entrySet()) {
                lines.add(String.format("  %-14s %4d (%5.1f%%) %s", e.getKey().label(), e.getValue(),
                        100.0 * e.getValue() / total, "#".repeat(e.getValue())));
            }
        }
        if (lines.isEmpty()) {
            lines.add("no preflight repairs and no recoveries - clean run");
        }
        return String.join("\n", lines);
    }
}
