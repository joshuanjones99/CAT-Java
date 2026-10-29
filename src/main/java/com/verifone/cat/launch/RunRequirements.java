package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.verifone.cat.fep.SecondaryFeps;
import com.verifone.cat.plans.BaseRule;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.plans.Snapshot;
import com.verifone.cat.report.events.CatEvent;

/**
 * Run start: what the test plan needs decides the run's simulators, devices and FEPs. The Python CAT's
 * {@code cat.launch.requirements} ({@code apply_requirements}, {@code feps_from_tags}, {@code describe}); its
 * {@code check_requirements} half is System Checks' ({@link SystemChecksStep}).
 *
 * <p>The rig owner, 2026-10-21: the rig configuration is dictated by the test suite being run. After collection has fixed
 * the run's cases and before step 1:
 * <ul>
 *   <li><b>Secondary FEPs</b> come from the cases' tags ({@link SecondaryFeps#featuresForTags}): a case tagged
 *       {@code @comdata} needs Comdata. Any named outright ({@code --fep}) stay.</li>
 *   <li><b>Simulators</b> step 3 starts are the plans' Simulators column, when it names any; otherwise the defaults
 *       the caller handed over.</li>
 *   <li><b>Connections</b> step 2 probes are the plans' Devices column less the printer, when it names any; otherwise
 *       the defaults.</li>
 * </ul>
 * The page is read afresh ({@link PlanCatalogue.Mode#REFRESH}, which falls back to the cache and the seed); one that
 * cannot be read at all is the cache's, with its reason kept for System Checks' warning. Never on a fake-POS run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: driven only by the launch tests' fake rig.
 */
final class RunRequirements {

    /** The logger of the run-start lines, as the Python's {@code _say}. */
    static final String LOGGER = "cat.requirements";

    private RunRequirements() {
    }

    /** Sets the run's secondary FEPs, simulators and connections from its cases and plans. */
    static void apply(RunContext ctx) {
        LaunchConfig config = ctx.config();
        LaunchConfig.Builder updated = config.toBuilder();
        boolean changed = false;
        List<String> tagged = fepsFromTags(config.suites(), node -> PlanStart.fileOf(ctx, node));
        Set<String> feps = new LinkedHashSet<>(config.secondaryFeps());
        feps.addAll(tagged);
        if (!List.copyOf(feps).equals(config.secondaryFeps())) {
            updated.secondaryFeps(List.copyOf(feps));
            changed = true;
        }
        if (!tagged.isEmpty()) {
            say(ctx, "secondary FEPs, from the cases' tags: " + String.join(", ", tagged));
        }
        if (!config.testPlans().isEmpty() && !config.fakePos()) {
            Snapshot<PlanRequirements> snapshot;
            String note;
            try {
                snapshot = ctx.paths().catalogue().requirements(PlanCatalogue.Mode.REFRESH);
                note = snapshot.reason();
            } catch (RuntimeException e) {
                // A slow or broken page: the cache, never a crash.
                snapshot = ctx.paths().catalogue().requirements(PlanCatalogue.Mode.CACHE_ONLY);
                note = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Requirements requirements = Requirements.forPlans(config.testPlans(), snapshot.rows());
            ctx.requirements(requirements, note);
            if (!requirements.simulators().isEmpty()) {
                updated.simulators(requirements.simulators());
                changed = true;
            }
            if (!requirements.devices().isEmpty()) {
                updated.connections(requirements.devices().stream()
                        .filter(name -> !name.toLowerCase(Locale.ROOT).equals(Requirements.PRINTER)).toList());
                changed = true;
            }
            say(ctx, "plan requirements: " + describe(requirements));
        }
        if (changed) {
            ctx.config(updated.build());
        }
    }

    /** The secondary FEPs the cases at {@code nodes} are tagged with, in the table's order (the Python's). */
    static List<String> fepsFromTags(List<String> nodes, Function<String, Path> fileOf) {
        Set<String> tags = new LinkedHashSet<>();
        for (String node : nodes) {
            for (Set<String> scenario : PartialRun.fileTags(fileOf.apply(node))) {
                tags.addAll(scenario);
            }
        }
        if (tags.isEmpty()) {
            return List.of();
        }
        try {
            return SecondaryFeps.featuresForTags(new ArrayList<>(tags), SecondaryFeps.loadCatalogue());
        } catch (RuntimeException e) {
            return List.of(); // step 4's secondary-fep reads the same table and says why
        }
    }

    /** One line: what the ticked plans need, as the page says it (the Python's {@code describe}). */
    static String describe(Requirements requirements) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, BaseRule> rule : requirements.baseRules().entrySet()) {
            parts.add(rule.getKey() + " " + rule.getValue());
        }
        if (!requirements.commanderConnect().isEmpty()) {
            parts.add("Commander Connect (" + String.join(", ", requirements.commanderConnect()) + ")");
        }
        if (!requirements.simulators().isEmpty()) {
            parts.add("simulators " + String.join(", ", requirements.simulators()));
        }
        if (!requirements.devices().isEmpty()) {
            parts.add("devices " + String.join(", ", requirements.devices()));
        }
        if (!requirements.unlisted().isEmpty()) {
            parts.add("not on the page: " + String.join(", ", requirements.unlisted()));
        }
        return parts.isEmpty() ? "nothing beyond the defaults" : String.join("; ", parts);
    }

    private static void say(RunContext ctx, String message) {
        ctx.emit(new CatEvent.LogLine("INFO", LOGGER, message, LaunchStepNames.SYSTEM_CHECKS));
    }
}
