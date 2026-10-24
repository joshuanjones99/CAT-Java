package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The cache, network, seed ladder, with a fake network source. No network is reached. */
class PlanCatalogueTest {

    @TempDir
    Path tmp;

    /** A network source that counts its calls and returns what the test sets, or fails. */
    static final class FakeSource implements PlanSource {
        final List<TestPlan> plans = new ArrayList<>();
        final List<PlanCase> cases = new ArrayList<>();
        String failure;
        int calls;

        @Override
        public List<TestPlan> catalogue() {
            calls++;
            if (failure != null) {
                throw new PlanSourceUnavailable(failure);
            }
            return plans;
        }

        @Override
        public List<PlanCase> cases(String planKey) {
            calls++;
            if (failure != null) {
                throw new PlanSourceUnavailable(failure);
            }
            return cases;
        }

        @Override
        public List<PlanRequirements> requirements() {
            calls++;
            if (failure != null) {
                throw new PlanSourceUnavailable(failure);
            }
            return List.of();
        }
    }

    private static TestPlan plan(String key, String feature) {
        return new TestPlan(key, feature, "", "Working", "3", "", "XML");
    }

    private PlanCatalogue catalogue(PlanSource network, PlanSource seed) {
        return new PlanCatalogue(tmp.resolve("cache"), network, seed, tmp.resolve("repo"));
    }

    @Test
    void aSuccessfulFetchIsWrittenToTheCacheAndReadBackWithoutTheNetwork() {
        FakeSource network = new FakeSource();
        network.plans.add(plan("PETROCORE-1", "Alpha"));
        PlanCatalogue catalogue = catalogue(network, PlanSource.unavailable("unused"));

        Snapshot<TestPlan> fetched = catalogue.catalogue(PlanCatalogue.Mode.CACHE_FIRST);
        assertEquals(Snapshot.Source.NETWORK, fetched.source());
        assertNotNull(fetched.fetchedAt());

        Snapshot<TestPlan> again = catalogue.catalogue(PlanCatalogue.Mode.CACHE_FIRST);
        assertEquals(Snapshot.Source.CACHE, again.source());
        assertEquals(List.of(plan("PETROCORE-1", "Alpha")), again.rows());
        assertEquals(1, network.calls, "the second read must not reach the network");
    }

    @Test
    void anOutageWithNoCacheFallsBackToTheSeedAndNamesTheFailure() {
        FakeSource network = new FakeSource();
        network.failure = "no credentials";
        PlanCatalogue catalogue = catalogue(network, new SeedFixed(List.of(plan("PETROCORE-9", "Seeded"))));

        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.CACHE_FIRST);
        assertEquals(Snapshot.Source.SEED, snapshot.source());
        assertEquals("no credentials — showing the shipped snapshot", snapshot.reason());
        assertNull(snapshot.fetchedAt(), "a seed's capture date is not a fetch on this machine");
    }

    @Test
    void aRefreshThatFailsKeepsTheCachedRowsAndSaysWhy() {
        FakeSource network = new FakeSource();
        network.plans.add(plan("PETROCORE-1", "Alpha"));
        PlanCatalogue catalogue = catalogue(network, PlanSource.unavailable("unused"));
        catalogue.catalogue(PlanCatalogue.Mode.REFRESH);

        network.failure = "HTTP 503";
        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.REFRESH);
        assertEquals(Snapshot.Source.CACHE, snapshot.source());
        assertEquals("HTTP 503", snapshot.reason());
        assertEquals("Alpha", snapshot.rows().get(0).feature());
    }

    @Test
    void anEmptyFetchNeverOverwritesAKnownGoodCache() {
        FakeSource network = new FakeSource();
        network.plans.add(plan("PETROCORE-1", "Alpha"));
        PlanCatalogue catalogue = catalogue(network, PlanSource.unavailable("unused"));
        catalogue.catalogue(PlanCatalogue.Mode.REFRESH);

        network.plans.clear();
        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.REFRESH);
        assertEquals(Snapshot.Source.CACHE, snapshot.source());
        assertTrue(snapshot.reason().contains("came back empty"), snapshot.reason());
        assertEquals("Alpha", snapshot.rows().get(0).feature());
    }

    @Test
    void cacheOnlyNeverCallsTheNetworkAndSaysWhenNothingIsCached() {
        FakeSource network = new FakeSource();
        PlanCatalogue catalogue = catalogue(network, new SeedFixed(List.of()));

        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY);
        assertEquals(Snapshot.Source.EMPTY, snapshot.source());
        assertTrue(snapshot.reason().startsWith("nothing cached for"), snapshot.reason());
        assertEquals(0, network.calls);
    }

    @Test
    void aCacheWithAFieldThisVersionDoesNotKnowIsAMissNotAPartialRead() throws IOException {
        Path cache = tmp.resolve("cache").resolve("catalogue.json");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache,
                "{\"fetched_at\": \"2026-09-09T00:00:00+00:00\", \"rows\": [{\"key\": \"PETROCORE-1\", \"surprise\": 1}]}",
                StandardCharsets.UTF_8);
        PlanCatalogue catalogue = catalogue(PlanSource.unavailable("offline"), new SeedFixed(List.of(plan("PETROCORE-2", "Seed"))));

        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY);
        assertEquals(Snapshot.Source.SEED, snapshot.source());
        assertEquals("PETROCORE-2", snapshot.rows().get(0).key());
    }

    @Test
    void aCachedPlanNodeThatHasGoneIsReResolvedNotTrusted() throws IOException {
        Path suite = tmp.resolve("repo").resolve("tests").resolve("suites").resolve("charity");
        Files.createDirectories(suite);
        Files.writeString(suite.resolve("PETROCORE-42-TestCase.py"), "def test_x(): pass\n", StandardCharsets.UTF_8);
        FakeSource network = new FakeSource();
        network.cases.add(new PlanCase("PETROCORE-42", "Moved", "tests/suites/gone/PETROCORE-42-TestCase.py"));
        PlanCatalogue catalogue = catalogue(network, PlanSource.unavailable("unused"));

        Snapshot<PlanCase> cases = catalogue.cases("PETROCORE-7", PlanCatalogue.Mode.REFRESH);
        assertEquals("tests/suites/charity/PETROCORE-42-TestCase.py", cases.rows().get(0).node());
    }

    @Test
    void planNamesDisambiguateTwoPlansThatShareAName() throws IOException {
        Path cache = tmp.resolve("cache");
        FakeSource network = new FakeSource();
        network.plans.add(plan("PETROCORE-1", "Cadence"));
        network.plans.add(plan("PETROCORE-2", "Cadence"));
        network.plans.add(plan("PETROCORE-3", "Charity"));
        PlanCatalogue catalogue = catalogue(network, PlanSource.unavailable("unused"));
        catalogue.catalogue(PlanCatalogue.Mode.REFRESH);

        assertEquals(
                List.of("Cadence (PETROCORE-1)", "Cadence (PETROCORE-2)", "Charity", "PETROCORE-9"),
                catalogue.planNames(List.of("PETROCORE-1", "PETROCORE-2", "PETROCORE-3", "PETROCORE-9")));
        assertEquals("Charity", catalogue.planName("PETROCORE-3"));
        assertTrue(Files.exists(cache.resolve("catalogue.json")));
    }

    @Test
    void theSeedFallbackDoesNotNeedAnyCacheDirectory() {
        PlanCatalogue catalogue = catalogue(PlanSource.unavailable("offline"), new SeedPlanSource());
        assertEquals(68, catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY).rows().size());
        assertFalse(catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY).rows().isEmpty());
    }

    /** A seed source that always returns the rows it was given, for the ladder's own branches. */
    static final class SeedFixed implements PlanSource {
        private final List<TestPlan> plans;

        SeedFixed(List<TestPlan> plans) {
            this.plans = plans;
        }

        @Override
        public List<TestPlan> catalogue() {
            return plans;
        }

        @Override
        public List<PlanCase> cases(String planKey) {
            return List.of();
        }

        @Override
        public List<PlanRequirements> requirements() {
            return List.of();
        }
    }
}
