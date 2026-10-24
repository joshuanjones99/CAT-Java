package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.List;

/**
 * What a launch came to: the run's id, its folder, every step's end, and whether the operator stopped it.
 *
 * @param runId     the twelve-character run id, also the folder's name
 * @param runFolder {@code <results root>/<run-id>/}, which holds {@code events.ndjson}, {@code run.log}
 *                  and {@code run.ended}
 * @param steps     all seven steps, in run order
 * @param cancelled whether the operator stopped the run
 */
public record LaunchResult(String runId, Path runFolder, List<StepResult> steps, boolean cancelled) {

    public LaunchResult {
        steps = List.copyOf(steps);
    }

    /** True only when every step completed and nothing stopped the run. */
    public boolean complete() {
        return !cancelled && steps.stream().allMatch(step -> StepResult.COMPLETE.equals(step.state()));
    }
}
