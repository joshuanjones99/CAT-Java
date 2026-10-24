package com.verifone.cat.plans;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The ticked plans' requirements together: what this run needs of the machine.
 *
 * <p>Where no plan names any simulator or device, the lists are empty, and that means the
 * defaults apply: start every simulator CAT starts by default, probe every device but the
 * physical pinpad, and need a printer. Ported from {@code cat.requirements.Requirements}.
 *
 * @param unlisted ticked plan keys the page has no row for
 */
public record Requirements(List<PlanRequirements> plans, List<String> unlisted) {

    public static final String PRINTER = "printer";

    public Requirements {
        plans = List.copyOf(plans);
        unlisted = List.copyOf(unlisted);
    }

    /** The rows of {@code planKeys}, in that order, and the keys the page does not list. */
    public static Requirements forPlans(Collection<String> planKeys, Collection<PlanRequirements> rows) {
        Map<String, PlanRequirements> byKey = new LinkedHashMap<>();
        for (PlanRequirements row : rows) {
            byKey.put(row.key(), row);
        }
        List<PlanRequirements> plans = new ArrayList<>();
        List<String> unlisted = new ArrayList<>();
        for (String key : new LinkedHashSet<>(planKeys)) {
            PlanRequirements row = byKey.get(key);
            if (row == null) {
                unlisted.add(key);
            } else {
                plans.add(row);
            }
        }
        return new Requirements(plans, unlisted);
    }

    /** Each plan's Required Base rule, keyed by plan, in plan order. */
    public Map<String, BaseRule> baseRules() {
        Map<String, BaseRule> rules = new LinkedHashMap<>();
        for (PlanRequirements plan : plans) {
            plan.baseRule().ifPresent(rule -> rules.put(plan.key(), rule));
        }
        return rules;
    }

    /** The plans that need Commander Connect onboarding. */
    public List<String> commanderConnect() {
        List<String> keys = new ArrayList<>();
        for (PlanRequirements plan : plans) {
            if (plan.commanderConnect()) {
                keys.add(plan.key());
            }
        }
        return keys;
    }

    /** Every simulator a plan names, de-duplicated in order; empty when none is named. */
    public List<String> simulators() {
        return distinct(plan -> plan.simulators());
    }

    /** Every device a plan names, de-duplicated in order; empty when none is named. */
    public List<String> devices() {
        return distinct(plan -> plan.devices());
    }

    /** The printer is needed unless the plans name their devices and not the printer. */
    public boolean needsPrinter() {
        List<String> devices = devices();
        return devices.isEmpty() || devices.stream().anyMatch(name -> name.equalsIgnoreCase(PRINTER));
    }

    private List<String> distinct(Function<PlanRequirements, List<String>> names) {
        return plans.stream()
                .flatMap(plan -> names.apply(plan).stream())
                .distinct()
                .toList();
    }
}
