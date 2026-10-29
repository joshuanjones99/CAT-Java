package com.verifone.cat.sims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.fep.SecondaryFeps;

/**
 * Which simulators a run's Secondary FEPs bring with them: the Python CAT's {@code LaunchConfig.secondary_fep_rows},
 * {@code fep_simulators} and {@code all_simulators} ({@code cat/launch/config.py}), here because {@code LaunchConfig}
 * belongs to another part of the port. A chosen FEP's host simulator ({@code secondary_feps.yaml}'s
 * {@code simulator:}) is started in step 3 whether or not it was ticked, because choosing the FEP is choosing its
 * host; never on a fake-POS run, where there is no Commander to dial it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): reads the shipped table only.
 */
public final class FepHosts {

    private FepHosts() {
    }

    /** The shipped table, or empty when it will not load (step 4's {@code secondary-fep} names that fault). */
    static List<SecondaryFep> catalogue() {
        try {
            return SecondaryFeps.loadCatalogue();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The named FEPs' table rows, in order, each once; a name the table lacks is left out. */
    public static List<SecondaryFep> secondaryFepRows(List<String> names) {
        return secondaryFepRows(names, names.isEmpty() ? List.of() : catalogue());
    }

    /** {@link #secondaryFepRows(List)} over a given table. */
    public static List<SecondaryFep> secondaryFepRows(List<String> names, List<SecondaryFep> catalogue) {
        Map<String, SecondaryFep> byName = new LinkedHashMap<>();
        catalogue.forEach(row -> byName.put(row.name().toLowerCase(Locale.ROOT), row));
        Set<SecondaryFep> rows = new LinkedHashSet<>();
        for (String name : names) {
            SecondaryFep row = byName.get(name.toLowerCase(Locale.ROOT));
            if (row != null) {
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    /** Each Secondary FEP's simulator, keyed by simulator: {@code {"Petro Simulator Suite": "mudflap"}}. */
    public static Map<String, String> fepSimulators(List<String> feps, boolean fakePos) {
        return fakePos || feps.isEmpty() ? Map.of() : fepSimulators(secondaryFepRows(feps));
    }

    /** {@link #fepSimulators(List, boolean)} over table rows already resolved. */
    public static Map<String, String> fepSimulators(List<SecondaryFep> rows) {
        Map<String, String> hosts = new LinkedHashMap<>();
        for (SecondaryFep row : rows) {
            if (!row.simulator().isEmpty()) {
                hosts.merge(row.simulator(), row.name(), (was, more) -> was + ", " + more);
            }
        }
        return hosts;
    }

    /** What step 3 adopts or starts: {@code simulators}, plus every Secondary FEP's simulator, each once. */
    public static List<String> allSimulators(List<String> simulators, List<String> feps, boolean fakePos) {
        Set<String> all = new LinkedHashSet<>(simulators);
        all.addAll(fepSimulators(feps, fakePos).keySet());
        return new ArrayList<>(all);
    }
}
