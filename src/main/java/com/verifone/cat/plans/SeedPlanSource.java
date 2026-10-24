package com.verifone.cat.plans;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The committed offline snapshot, read from the classpath under {@code plans/seed/}.
 *
 * <p>This is the floor under the picker. On a controller with no Atlassian token and no
 * {@code ~/.cat} cache, no run could start without it: the Python CAT's {@code src/cat/seed/}
 * holds the same 68 plans and the same shipped cases, copied here byte for byte. A snapshot
 * is evidence about the page on the day it was captured, which is why {@link PlanCatalogue}
 * labels its rows {@code seed}. It never reaches the network, so {@link #cases} and the
 * others return an empty list for a plan with no shipped file rather than throwing.
 */
public final class SeedPlanSource implements PlanSource {

    static final String SEED_PATH = "/plans/seed/";

    @Override
    public List<TestPlan> catalogue() {
        return rows("catalogue.json", TestPlan.class);
    }

    @Override
    public List<PlanCase> cases(String planKey) {
        return rows("cases-" + planKey + ".json", PlanCase.class);
    }

    @Override
    public List<PlanRequirements> requirements() {
        return rows("requirements.json", PlanRequirements.class);
    }

    private static <T> List<T> rows(String name, Class<T> type) {
        try (InputStream in = SeedPlanSource.class.getResourceAsStream(SEED_PATH + name)) {
            if (in == null) {
                return List.of();
            }
            return SnapshotFiles.read(in, type).map(SnapshotFiles.Stored::rows).orElse(List.of());
        } catch (IOException e) {
            return List.of();
        }
    }
}
