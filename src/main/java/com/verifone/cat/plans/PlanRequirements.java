package com.verifone.cat.plans;

import java.util.List;
import java.util.Optional;

/**
 * One plan's row of the Supported CAT Test Plans page: what a machine must have to run it.
 *
 * <p>The rig owner decided on 2026-10-21 that the plan dictates the rig's configuration, so
 * a run checks these before it starts. Ported from {@code cat.requirements.PlanRequirements}.
 *
 * @param base the Required Base cell as the page writes it; {@link #baseRule()} reads it
 * @param simulators simulators the plan needs started, in the registry's names
 * @param devices rig {@code devices:} names the plan needs probed
 */
public record PlanRequirements(
        String key,
        String base,
        boolean commanderConnect,
        List<String> simulators,
        List<String> devices) {

    public PlanRequirements {
        key = Text.orEmpty(key);
        base = Text.orEmpty(base);
        simulators = simulators == null ? List.of() : List.copyOf(simulators);
        devices = devices == null ? List.of() : List.copyOf(devices);
    }

    public Optional<BaseRule> baseRule() {
        return BaseRule.parse(base);
    }
}
