package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.collect.CaseResult;
import com.verifone.cat.collect.CollectResult;

/**
 * The run's cases in plan order: the Tests Run step asks for them once, at the start of the step.
 *
 * <p>The production source is {@link #collected}: the orchestrator collects the plan's cases from
 * petroapps ({@code collect.Collect}), which says where each case's file was pulled to, and this
 * source keeps that order. Choosing the revision and dist, and running the collect, is the caller's:
 * the launch configuration does not carry them yet.
 */
@FunctionalInterface
public interface CaseSource {

    /**
     * The cases to run, in plan order, including Not Run ones (with a {@code null} file).
     *
     * @throws IOException when the case list cannot be read
     */
    List<CaseEntry> cases(LaunchConfig config) throws IOException;

    /**
     * A source over an already collected plan. Each case with a pulled test is a runnable entry whose
     * file is {@code repoRoot} plus the pulled path; a case with no pulled test is Not Run.
     *
     * @param collected the collect result, whose cases are in the plan's order
     * @param repoRoot  the repository the pulled tests sit in
     */
    static CaseSource collected(CollectResult collected, Path repoRoot) {
        return config -> {
            List<CaseEntry> entries = new ArrayList<>();
            for (CaseResult result : collected.cases) {
                String node = result.node();
                entries.add(node == null
                        ? new CaseEntry(result.key, null, null)
                        : new CaseEntry(result.key, node, repoRoot.resolve(node)));
            }
            return List.copyOf(entries);
        };
    }
}
