package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the plan cache lives. Every home here is a {@code @TempDir}: the real {@code ~/.cat}
 * is never read or written. The Python CAT's cache is a fixture written by the same snapshot
 * writer the Python format matches, and its bytes and mtime are checked after each load.
 */
class PlanCatalogueCacheHomeTest {

    @TempDir
    Path tmp;

    private static TestPlan plan(String key, String feature) {
        return new TestPlan(key, feature, "", "Working", "3", "", "XML");
    }

    /** A home with the Python CAT's cache seeded in it, plus the paths a launch would use. */
    private record Homes(Path home, Path javaCache, Path pythonCache, Path repo) {}

    private Homes homes() throws IOException {
        Path home = tmp.resolve("home");
        Path python = home.resolve(".cat");
        Files.createDirectories(python);
        SnapshotFiles.write(python.resolve("catalogue.json"),
                List.of(plan("PETROCORE-9", "Python Cadence")), Instant.parse("2026-10-20T10:00:00Z"));
        Files.setLastModifiedTime(python.resolve("catalogue.json"),
                FileTime.fromMillis(1_600_000_000_000L));
        return new Homes(home, home.resolve(".cat").resolve("java"), python, tmp.resolve("repo"));
    }

    private static PlanCatalogue withPython(Homes h, PlanSource network) {
        return new PlanCatalogue(h.javaCache(), h.pythonCache(), network,
                PlanSource.unavailable("unused"), h.repo());
    }

    /** A network source that returns one plan. */
    private static PlanSource networkWith(String key, String feature) {
        PlanCatalogueTest.FakeSource source = new PlanCatalogueTest.FakeSource();
        source.plans.add(plan(key, feature));
        return source;
    }

    @Test
    void aFetchIsWrittenUnderDotCatJavaAndNeverIntoThePythonCache() throws IOException {
        Homes h = homes();
        Path pythonFile = h.pythonCache().resolve("catalogue.json");
        byte[] before = Files.readAllBytes(pythonFile);
        FileTime mtime = Files.getLastModifiedTime(pythonFile);

        PlanCatalogue catalogue = withPython(h, networkWith("PETROCORE-1", "Alpha"));
        assertEquals(Snapshot.Source.NETWORK,
                catalogue.catalogue(PlanCatalogue.Mode.REFRESH).source());

        assertTrue(Files.isRegularFile(h.javaCache().resolve("catalogue.json")));
        assertArrayEquals(before, Files.readAllBytes(pythonFile));
        assertEquals(mtime, Files.getLastModifiedTime(pythonFile));
        // The only new entry under the Python folder is this build's own java/ subfolder.
        try (var listing = Files.list(h.pythonCache())) {
            assertEquals(List.of(h.pythonCache().resolve("java"), pythonFile).stream().sorted().toList(),
                    listing.sorted().toList());
        }
    }

    @Test
    void thePythonCacheIsReadWhenTheJavaFileIsAbsent() throws IOException {
        Homes h = homes();
        PlanCatalogue catalogue = withPython(h, PlanSource.unavailable("offline"));

        Snapshot<TestPlan> loaded = catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY);

        assertEquals(Snapshot.Source.CACHE, loaded.source());
        assertEquals(List.of(plan("PETROCORE-9", "Python Cadence")), loaded.rows());
        assertFalse(Files.exists(h.javaCache().resolve("catalogue.json")));
    }

    @Test
    void aLoadAndARefreshLeaveThePythonCacheBytesAndMtimeUnchanged() throws IOException {
        Homes h = homes();
        Path pythonFile = h.pythonCache().resolve("catalogue.json");
        byte[] before = Files.readAllBytes(pythonFile);
        FileTime mtime = Files.getLastModifiedTime(pythonFile);

        // Load from the Python fallback on a cache miss, with the network never reached.
        PlanCatalogue offline = withPython(h, PlanSource.unavailable("offline"));
        assertEquals("Python Cadence", offline.planName("PETROCORE-9"));
        assertArrayEquals(before, Files.readAllBytes(pythonFile));
        assertEquals(mtime, Files.getLastModifiedTime(pythonFile));

        // A refresh that fails reads the Python copy as its stale fallback, writes nothing there.
        Snapshot<TestPlan> stale = offline.catalogue(PlanCatalogue.Mode.REFRESH);
        assertEquals(Snapshot.Source.CACHE, stale.source());
        assertArrayEquals(before, Files.readAllBytes(pythonFile));
        assertEquals(mtime, Files.getLastModifiedTime(pythonFile));

        // A refresh that succeeds writes to .cat/java and still leaves the Python copy alone.
        PlanCatalogue online = withPython(h, networkWith("PETROCORE-1", "Alpha"));
        assertEquals(Snapshot.Source.NETWORK,
                online.catalogue(PlanCatalogue.Mode.REFRESH).source());
        assertArrayEquals(before, Files.readAllBytes(pythonFile));
        assertEquals(mtime, Files.getLastModifiedTime(pythonFile));
    }

    @Test
    void aJavaCopyWinsOverThePythonCopy() throws IOException {
        Homes h = homes();
        withPython(h, networkWith("PETROCORE-1", "Alpha"))
                .catalogue(PlanCatalogue.Mode.REFRESH);

        PlanCatalogue later = withPython(h, PlanSource.unavailable("offline"));
        assertEquals(List.of(plan("PETROCORE-1", "Alpha")),
                later.catalogue(PlanCatalogue.Mode.CACHE_ONLY).rows());
    }

    @Test
    void offlineUsesDotCatJavaForWritesAndFallsBackToDotCatForReads() throws IOException {
        Homes h = homes();
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", h.home().toString());
        try {
            // The default build reads the Python cache through offline(); nothing else is reached.
            PlanCatalogue offline = PlanCatalogue.offline(h.repo());
            assertEquals("Python Cadence", offline.planName("PETROCORE-9"));
            assertEquals(Snapshot.Source.CACHE,
                    offline.catalogue(PlanCatalogue.Mode.CACHE_ONLY).source());
            assertFalse(Files.exists(h.javaCache().resolve("catalogue.json")));
        } finally {
            System.setProperty("user.home", previous);
        }
    }
}
