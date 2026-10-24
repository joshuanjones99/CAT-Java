package com.verifone.cat.plans;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The plan catalogue, cache first: the cache, then the network, then the shipped seed.
 *
 * <p>This is the Python {@code cat.plans._load} ladder. Every branch that can return no rows
 * names a reason, and a successful network read that comes back empty never overwrites a
 * known-good cache: on this page an empty read means the page moved or changed shape.
 *
 * <p>{@link Mode#CACHE_ONLY} is what a picker opens with. It reads the cache and the seed and
 * stops, so opening the picker cannot block on the network. The network is reached only with
 * {@link Mode#REFRESH} or on a cache miss.
 *
 * <p>Node verification ({@link CaseNodes}) runs on every case list on the way out, whatever
 * the mode, because a cached node is only as fresh as the checkout it was resolved in.
 *
 * <p>Ported from {@code cat.plans}' cache-first loaders and name lookups. The Python module's
 * HTML page parser and its HTTP fetchers are not here; a {@link PlanSource} supplies rows.
 * UNVERIFIED AGAINST HARDWARE: none of this touches a rig, and it has no live-site evidence.
 */
public final class PlanCatalogue {

    /** How a load may use the network. */
    public enum Mode {
        /** Cache, then network, then the shipped seed. */
        CACHE_FIRST,
        /** Network first, falling back to the cache and then the seed when it cannot be read. */
        REFRESH,
        /** Cache, then the seed. Never the network. */
        CACHE_ONLY
    }

    /** Where every write goes: this build's own cache, never the Python CAT's. */
    private final Path cacheDir;
    /**
     * The Python CAT's cache, read-only and only after {@link #cacheDir} misses. Null when
     * there is none to fall back to (tests that must not reach the real home).
     */
    private final Path pythonCacheDir;
    private final PlanSource network;
    private final PlanSource seed;
    private final CaseNodes nodes;

    /**
     * @param cacheDir where {@code catalogue.json}, {@code requirements.json} and
     *     {@code cases-<key>.json} are written and read first; the default is
     *     {@code ~/.cat/java}
     * @param repoRoot the checkout whose {@code tests/suites} node paths are resolved against.
     *     Passed in, never taken from the working directory: a launch from elsewhere would
     *     otherwise report every case Not Run with no error
     */
    public PlanCatalogue(Path cacheDir, PlanSource network, PlanSource seed, Path repoRoot) {
        this(cacheDir, null, network, seed, repoRoot);
    }

    /**
     * @param cacheDir the writable cache, read first (see the four-argument form)
     * @param pythonCacheDir the Python CAT's cache, read only when {@code cacheDir} has no
     *     readable copy of a file; never written. Null for none.
     */
    public PlanCatalogue(Path cacheDir, Path pythonCacheDir, PlanSource network, PlanSource seed,
            Path repoRoot) {
        this.cacheDir = cacheDir;
        this.pythonCacheDir = pythonCacheDir;
        this.network = network;
        this.seed = seed;
        this.nodes = new CaseNodes(repoRoot);
    }

    /**
     * The default for this build: writes to {@code ~/.cat/java}, reads that first and falls
     * back read-only to the Python CAT's {@code ~/.cat}, no network source, and the shipped seed.
     */
    public static PlanCatalogue offline(Path repoRoot) {
        Path home = Path.of(System.getProperty("user.home"));
        return new PlanCatalogue(
                home.resolve(".cat").resolve("java"),
                home.resolve(".cat"),
                PlanSource.unavailable("no Atlassian source in this build"),
                new SeedPlanSource(),
                repoRoot);
    }

    public Snapshot<TestPlan> catalogue(Mode mode) {
        return load("catalogue.json", TestPlan.class, network::catalogue, seed::catalogue, mode,
                "the Confluence test-plan catalogue");
    }

    public Snapshot<PlanRequirements> requirements(Mode mode) {
        return load("requirements.json", PlanRequirements.class, network::requirements,
                seed::requirements, mode, "the Supported CAT Test Plans page");
    }

    /** A plan's cases, each node re-checked against the checkout (see {@link CaseNodes}). */
    public Snapshot<PlanCase> cases(String planKey, Mode mode) {
        Snapshot<PlanCase> loaded = load("cases-" + planKey + ".json", PlanCase.class,
                () -> network.cases(planKey), () -> seed.cases(planKey), mode,
                "the Xray case list for " + planKey);
        List<PlanCase> verified = nodes.inPlanSuite(planKey, nodes.verify(loaded.rows()));
        if (verified.equals(loaded.rows())) {
            return loaded;
        }
        return new Snapshot<>(verified, loaded.fetchedAt(), loaded.source(), loaded.reason());
    }

    /** The catalogue's name for {@code key}, or empty when it has none. Cache and seed only. */
    public String planName(String key) {
        return catalogueNames().getOrDefault(key, "");
    }

    /**
     * Each key as an operator reads it, in the same order: by its name, with the key added
     * where two of the keys share one (the catalogue holds three plans called Cadence).
     */
    public List<String> planNames(Collection<String> keys) {
        Map<String, String> names = catalogueNames();
        List<String> found = new ArrayList<>(keys.size());
        for (String key : keys) {
            found.add(names.getOrDefault(key, ""));
        }
        List<String> out = new ArrayList<>(keys.size());
        int index = 0;
        for (String key : keys) {
            String name = found.get(index++);
            boolean shared = !name.isEmpty() && Collections.frequency(found, name) > 1;
            out.add(shared ? name + " (" + key + ")" : (name.isEmpty() ? key : name));
        }
        return out;
    }

    /** Case key to Jira summary over the cached (or seeded) case lists of {@code planKeys}. */
    public Map<String, String> caseSummaries(Collection<String> planKeys) {
        Map<String, String> summaries = new HashMap<>();
        for (String plan : new LinkedHashSet<>(planKeys)) {
            // CACHE_ONLY never calls the fetch, so the empty supplier is never run.
            Snapshot<PlanCase> snapshot = load("cases-" + plan + ".json", PlanCase.class,
                    List::of, () -> seed.cases(plan), Mode.CACHE_ONLY,
                    "the Xray case list for " + plan);
            for (PlanCase row : snapshot.rows()) {
                if (!row.summary().isEmpty()) {
                    summaries.putIfAbsent(row.key(), row.summary());
                }
            }
        }
        return summaries;
    }

    private Map<String, String> catalogueNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (TestPlan plan : catalogue(Mode.CACHE_ONLY).rows()) {
            if (!plan.feature().isEmpty()) {
                names.putIfAbsent(plan.key(), plan.feature());
            }
        }
        return names;
    }

    private <T> Snapshot<T> load(String fileName, Class<T> type, Supplier<List<T>> fetch,
            Supplier<List<T>> seeded, Mode mode, String what) {
        Path file = cacheDir.resolve(fileName);
        if (mode != Mode.REFRESH) {
            Optional<SnapshotFiles.Stored<T>> cached = readCache(fileName, type);
            if (cached.isPresent()) {
                List<T> rows = cached.get().rows();
                String reason = rows.isEmpty()
                        ? "the cached copy of " + what + " is empty — refresh to re-read it" : "";
                return new Snapshot<>(rows, cached.get().fetchedAt(), Snapshot.Source.CACHE, reason);
            }
        }
        if (mode == Mode.CACHE_ONLY) {
            Optional<Snapshot<T>> shipped = shipped(seeded.get(), what);
            return shipped.orElseGet(() -> new Snapshot<>(List.of(), null, Snapshot.Source.EMPTY,
                    "nothing cached for " + what + " yet — refresh to fetch it"));
        }
        List<T> fetched;
        try {
            fetched = fetch.get();
        } catch (PlanSourceUnavailable exc) {
            Optional<SnapshotFiles.Stored<T>> stale = mode == Mode.REFRESH
                    ? readCache(fileName, type) : Optional.empty();
            if (stale.isPresent()) {
                return new Snapshot<>(stale.get().rows(), stale.get().fetchedAt(),
                        Snapshot.Source.CACHE, exc.getMessage());
            }
            Optional<Snapshot<T>> shipped = shipped(seeded.get(), what);
            if (shipped.isPresent()) {
                // Better the snapshot than an empty picker; the reason names both the failure
                // and the fact that these rows did not come from it.
                return shipped.get().withReason(exc.getMessage() + " — showing the shipped snapshot");
            }
            return new Snapshot<>(List.of(), null, Snapshot.Source.EMPTY, exc.getMessage());
        }
        Instant fetchedAt = Instant.now();
        if (!fetched.isEmpty()) {
            try {
                SnapshotFiles.write(file, fetched, fetchedAt);
            } catch (IOException e) {
                // A read-only home must not fail an otherwise-good fetch.
            }
            return new Snapshot<>(fetched, fetchedAt, Snapshot.Source.NETWORK, "");
        }
        String reason = what + " came back empty — it may have moved or changed shape";
        Optional<SnapshotFiles.Stored<T>> stale = readCache(fileName, type);
        if (stale.isPresent() && !stale.get().rows().isEmpty()) {
            // Keep the operator's last known-good rows; the empty read is the reason, not the data.
            return new Snapshot<>(stale.get().rows(), stale.get().fetchedAt(), Snapshot.Source.CACHE, reason);
        }
        Optional<Snapshot<T>> shipped = shipped(seeded.get(), what);
        if (shipped.isPresent()) {
            return shipped.get().withReason(reason + " — showing the shipped snapshot");
        }
        return new Snapshot<>(List.of(), fetchedAt, Snapshot.Source.NETWORK, reason);
    }

    /**
     * The first readable copy of {@code fileName}: this build's cache, then the Python CAT's.
     * Only reads; nothing is ever written to the Python cache.
     */
    private <T> Optional<SnapshotFiles.Stored<T>> readCache(String fileName, Class<T> type) {
        Optional<SnapshotFiles.Stored<T>> own = SnapshotFiles.read(cacheDir.resolve(fileName), type);
        if (own.isPresent() || pythonCacheDir == null) {
            return own;
        }
        return SnapshotFiles.read(pythonCacheDir.resolve(fileName), type);
    }

    /**
     * The seed's rows as a snapshot, or empty when there are none. The capture date is dropped:
     * nothing on this machine fetched these rows (see {@link Snapshot#fetchedAt()}).
     */
    private static <T> Optional<Snapshot<T>> shipped(List<T> rows, String what) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot<>(rows, null, Snapshot.Source.SEED,
                "shipped snapshot of " + what + " — nothing fetched on this machine yet"));
    }
}
