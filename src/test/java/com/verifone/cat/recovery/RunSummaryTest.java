package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The run summary's findings and the timeouts the ladder reads (DESIGN.md 6.2, 6.3, 8). Evidence about
 * this repository only.
 */
class RunSummaryTest {

    @Test
    void aCleanRunSaysSo() {
        assertEquals("no preflight repairs and no recoveries - clean run", new RunSummary("r").render());
        assertTrue(new RunSummary("r").findings().isEmpty());
    }

    @Test
    void fiveRepairsOnOneRigIsAFindingNamingTheWorstChecks() {
        RunSummary summary = new RunSummary("r");
        for (int i = 0; i < 4; i++) {
            summary.recordRepair("lab", "pos_idle", "repaired", "", Duration.ZERO);
        }
        summary.recordRepair("lab", "drawer_closed", "repaired", "", Duration.ZERO);
        summary.recordRepair("other", "pos_idle", "repaired", "", Duration.ZERO);

        List<String> findings = summary.findings();

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).startsWith("lab needed 5 repairs this run (pos_idle x4, drawer_closed x1)"),
                findings.get(0));
    }

    @Test
    void mostRecoveriesNeedingRung4Or5IsStructural() {
        RunSummary summary = new RunSummary("r");
        summary.recordRecovery("lab", "t1", Rung.PREFLIGHT, List.of(Rung.SOFT, Rung.PREFLIGHT), true, Duration.ZERO, "");
        summary.recordRecovery("lab", "t2", Rung.SOFT, List.of(Rung.SOFT), true, Duration.ZERO, "");

        List<String> findings = summary.findings();

        assertTrue(findings.stream().anyMatch(f -> f.startsWith("1/2 recoveries needed rung 4 or 5")), findings.toString());
        assertEquals(1, summary.rungDistribution().get(Rung.PREFLIGHT));
        assertEquals(1, summary.rungDistribution().get(Rung.SOFT));
    }

    @Test
    void aDrainIsAlwaysAFinding() {
        RunSummary summary = new RunSummary("r");
        summary.recordDrain("lab", "rung 6 hit");

        assertEquals(List.of("lab was DRAINED (rung 6): rung 6 hit"), summary.findings());
        assertTrue(summary.render().startsWith("!! operability findings"));
    }

    @Test
    void rungLabelsAreAsciiAndRungsOrder() {
        assertEquals("1:SOFT", Rung.SOFT.label());
        assertEquals("6:DRAIN", Rung.DRAIN.label());
        assertTrue(Rung.SOFT.isCheap() && Rung.RESET.isCheap());
        assertFalse(Rung.PREFLIGHT.isCheap());
        assertTrue(Rung.PREFLIGHT.isStructuralSignal() && Rung.REPROVISION.isStructuralSignal());
        assertFalse(Rung.VOID.isStructuralSignal());
    }

    @Test
    void timeoutsParseThroughTheOneDurationGrammar() {
        Timeouts parsed = Timeouts.parse(java.util.Map.of("default_wait", "2m"));

        assertEquals(Duration.ofSeconds(120), parsed.defaultWait());
        assertEquals(Duration.ofSeconds(120), parsed.ssh(), "ssh defaults to max(default_wait, 30s)");
        assertEquals(Duration.ofSeconds(15), Timeouts.defaults().defaultWait());
        assertEquals(Duration.ofSeconds(30), Timeouts.defaults().ssh());
    }

    @Test
    void aDurationTheGrammarRefusesIsRefusedAtLoad() {
        assertThrows(IllegalArgumentException.class, () -> Timeouts.parse(java.util.Map.of("default_wait", "15 sec")));
    }

    @Test
    void aZeroTimeoutIsRefusedBecauseItMeansWaitForever() {
        assertThrows(IllegalArgumentException.class, () -> new Timeouts(Duration.ZERO, null));
    }
}
