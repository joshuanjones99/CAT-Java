package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.List;

/**
 * What a launch came to: the run's id, its folder, every step's end, whether the operator stopped it, and how many
 * cases failed.
 *
 * @param runId       the twelve-character run id, also the folder's name
 * @param runFolder   {@code <results root>/<run-id>/}, which holds {@code events.ndjson}, {@code run.log}
 *                    and {@code run.ended}
 * @param steps       all seven steps, in run order
 * @param cancelled   whether the operator stopped the run
 * @param testsFailed the cases that failed, as the Python's reducer counts {@code test_count_failed}
 */
public record LaunchResult(String runId, Path runFolder, List<StepResult> steps, boolean cancelled, int testsFailed) {

    public LaunchResult {
        steps = List.copyOf(steps);
    }

    /** A result with no failed case counted. */
    public LaunchResult(String runId, Path runFolder, List<StepResult> steps, boolean cancelled) {
        this(runId, runFolder, steps, cancelled, 0);
    }

    /**
     * {@code run_plan}'s exit verdict: the run was not stopped, no case failed and no step failed. A skipped or pending
     * step is not a failure ({@code ok = state.status == "complete" and not state.test_count_failed and not
     * failed_steps}).
     */
    public boolean complete() {
        return !cancelled && testsFailed == 0
                && steps.stream().noneMatch(step -> StepResult.FAILED.equals(step.state()));
    }
}
