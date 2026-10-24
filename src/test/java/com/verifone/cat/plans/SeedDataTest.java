package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The committed offline snapshot, read from the real classpath resources.
 *
 * <p>These pin the shipped data, not the live page: PETROCORE-135127 is the plan the Python
 * CAT's HANDOFF §1.88 captured on 2026-09-22, in the plan's own order.
 */
class SeedDataTest {

    @TempDir
    Path repo;

    @Test
    void theShippedCatalogueListsSixtyEightPlansAndNamesCadence() {
        List<TestPlan> plans = new SeedPlanSource().catalogue();
        assertEquals(68, plans.size());
        TestPlan cadence = plans.stream()
                .filter(p -> p.key().equals("PETROCORE-135127"))
                .findFirst()
                .orElseThrow();
        assertEquals("Cadence", cadence.feature());
        assertEquals("Working", cadence.status());
        assertEquals("Non Viper Features", cadence.section());
    }

    @Test
    void aPlansCasesComeBackInTheirOwnOrder() {
        List<PlanCase> cases = new SeedPlanSource().cases("PETROCORE-135127");
        assertEquals(358, cases.size());
        assertEquals(
                List.of("PETROCORE-79588", "PETROCORE-124034", "PETROCORE-79590", "PETROCORE-37043"),
                cases.subList(0, 4).stream().map(PlanCase::key).toList());
        assertEquals("PETROCORE-37072", cases.get(cases.size() - 1).key());
    }

    @Test
    void theSeedCarriesTheSecondShippedPlanToo() {
        assertEquals(39, new SeedPlanSource().cases("PETROCORE-70960").size());
    }

    @Test
    void aPlanWithNoShippedFileIsEmptyNotAnError() {
        assertTrue(new SeedPlanSource().cases("PETROCORE-1").isEmpty());
    }

    @Test
    void theShippedRequirementsSayWhatCadenceNeeds() {
        PlanRequirements cadence = new SeedPlanSource().requirements().stream()
                .filter(p -> p.key().equals("PETROCORE-135127"))
                .findFirst()
                .orElseThrow();
        assertTrue(cadence.simulators().contains("BUYPASS Host"));
        assertTrue(cadence.baseRule().isPresent());
        assertEquals(57, cadence.baseRule().get().major());
        assertFalse(cadence.baseRule().get().atMost());
    }

    @Test
    void theSeedNodesAreRecheckedAgainstTheCheckout() {
        // The seed says tests/suites/cadence/... exists. In an empty checkout none of it does,
        // so every case reads as Not Run rather than handing pytest a missing path.
        PlanCatalogue catalogue = new PlanCatalogue(repo.resolve("cache"),
                PlanSource.unavailable("offline"), new SeedPlanSource(), repo);
        List<PlanCase> cases = catalogue.cases("PETROCORE-135127", PlanCatalogue.Mode.CACHE_ONLY).rows();
        assertEquals(358, cases.size());
        assertEquals("PETROCORE-79588", cases.get(0).key());
        assertNull(cases.get(0).node());
    }

    @Test
    void theCatalogueFallsBackToTheSeedAndSaysSo() {
        PlanCatalogue catalogue = new PlanCatalogue(repo.resolve("cache"),
                PlanSource.unavailable("offline"), new SeedPlanSource(), repo);
        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY);
        assertEquals(Snapshot.Source.SEED, snapshot.source());
        assertFalse(snapshot.reason().isEmpty());
        assertNull(snapshot.fetchedAt());
    }
}
