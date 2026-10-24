package com.verifone.cat.runconfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.verifone.cat.plans.CaseRef;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.Selection;
import com.verifone.cat.plans.SelectionError;
import com.verifone.cat.plans.TagExpressionError;

/**
 * What a Run scope selects from the ticked plans' cases, and what prerequisites it adds.
 *
 * <p>Ported from {@code cat.panels.config.scope.resolve}. It reads nothing from disk: the
 * caller passes every case's {@link CaseFacts} by node. The selection is
 * {@link PartialRun#select} (plan order; {@code only} is the exact list), and the prerequisites
 * are {@link PartialRun#prerequisiteCases}, the detection the run's own warnings use.
 *
 * <p>A scope that selects nothing carries a {@link Result#problem()} worded the way the Config
 * tab says it ("Nothing to run yet: pick ..."), and the run is refused on it.
 */
public final class ScopeResolver {

    /**
     * @param total the cases on the list
     * @param matched Tags: the cases the expression matched, in run order
     * @param expression the Tags expression as the run evaluates it (stripped)
     * @param chosen what the scope picks, in run order
     * @param added prerequisites added, in plan order (empty when left out)
     * @param leftOut prerequisites left out, in plan order (empty when added)
     * @param unreadable left-out cases ahead of the selection whose actions CAT cannot read
     * @param planEnd selected {@code @plan_end} cases, which a partial run skips
     * @param runKeys what the run runs: chosen and added, in run order
     * @param problem why the scope cannot start a run; empty when it can
     * @param tagError why the Tags expression does not parse; empty when it does
     */
    public record Result(
            int total,
            List<String> matched,
            String expression,
            List<String> chosen,
            List<PartialRun.Prerequisite> added,
            List<PartialRun.Prerequisite> leftOut,
            int unreadable,
            List<String> planEnd,
            List<String> runKeys,
            String problem,
            String tagError) {

        public Result {
            matched = List.copyOf(matched);
            expression = expression == null ? "" : expression;
            chosen = List.copyOf(chosen);
            added = List.copyOf(added);
            leftOut = List.copyOf(leftOut);
            planEnd = List.copyOf(planEnd);
            runKeys = List.copyOf(runKeys);
            problem = problem == null ? "" : problem;
            tagError = tagError == null ? "" : tagError;
        }

        /** A refusal: nothing chosen, the problem said. */
        static Result refused(int total, String problem) {
            return new Result(total, List.of(), "", List.of(), List.of(), List.of(), 0,
                    List.of(), List.of(), problem, "");
        }
    }

    private ScopeResolver() {}

    /**
     * @param cases the ticked plans' cases, in run order
     * @param facts each case's file facts, keyed by node
     * @param repoRoot the checkout, as {@link PartialRun#select} takes it (no tag filter is
     *     passed to it, so nothing is read here)
     */
    public static Result resolve(RunScope scope, List<ScopeCase> cases, Map<String, CaseFacts> facts,
            Path repoRoot) {
        List<String> keys = cases.stream().map(ScopeCase::key).toList();
        if (scope.mode() == RunScope.Mode.WHOLE) {
            return new Result(cases.size(), List.of(), "", keys, List.of(), List.of(), 0,
                    List.of(), keys, "", "");
        }
        if (cases.isEmpty()) {
            return Result.refused(0,
                    "a run scope with cases to choose from (no ticked plan's case is on disk)");
        }
        List<String> matched = List.of();
        String expression = "";
        List<String> chosen;
        switch (scope.mode()) {
            case TAGS -> {
                expression = scope.tags().strip();
                if (expression.isEmpty()) {
                    return Result.refused(cases.size(),
                            "a tag expression for the run scope (Tags is chosen; it is empty)");
                }
                Predicate<Set<String>> predicate;
                try {
                    predicate = PartialRun.parseTags(expression);
                } catch (TagExpressionError exc) {
                    return new Result(cases.size(), List.of(), expression, List.of(), List.of(),
                            List.of(), 0, List.of(), List.of(),
                            "a tag expression Cucumber can parse (" + exc.getMessage() + ")",
                            exc.getMessage());
                }
                List<String> hit = new ArrayList<>();
                for (ScopeCase item : cases) {
                    CaseFacts own = facts.get(item.node());
                    if (own != null && own.tags().stream().anyMatch(predicate)) {
                        hit.add(item.key());
                    }
                }
                matched = hit;
                if (scope.tagCases() == null) {
                    chosen = matched;
                } else {
                    Set<String> hand = new HashSet<>(scope.tagCases());
                    chosen = keys.stream().filter(hand::contains).toList();
                }
                if (chosen.isEmpty()) {
                    String why = scope.tagCases() == null
                            ? "--tags '" + expression + "': no case of the plan is selected"
                            : "Tags: every case is unticked";
                    return new Result(cases.size(), matched, expression, List.of(), List.of(),
                            List.of(), 0, List.of(), List.of(),
                            "a run scope that selects a case (" + why + ")", "");
                }
            }
            case CASES -> {
                Set<String> ticked = new HashSet<>();
                for (String key : scope.cases()) {
                    ticked.add(PartialRun.normaliseKey(key));
                }
                chosen = keys.stream().filter(ticked::contains).toList();
                if (chosen.isEmpty()) {
                    return Result.refused(cases.size(),
                            "a run scope that selects a case (Cases: no case is ticked)");
                }
            }
            default -> { // FROM
                String start = scope.fromCase().isBlank() ? "" : PartialRun.normaliseKey(scope.fromCase());
                if (!keys.contains(start)) {
                    return Result.refused(cases.size(),
                            "a case to start from (From a case: no case is picked)");
                }
                chosen = keys.subList(keys.indexOf(start), keys.size());
            }
        }

        List<CaseRef> pairs = cases.stream().map(c -> new CaseRef(c.key(), c.node())).toList();
        Selection selection;
        try {
            selection = PartialRun.select(pairs, "", chosen, "", repoRoot);
        } catch (SelectionError exc) {
            return Result.refused(cases.size(),
                    "a run scope that selects a case (" + exc.getMessage() + ")");
        }
        PartialRun.Prerequisites found = PartialRun.prerequisiteCases(selection, node -> {
            CaseFacts own = facts.get(node);
            return own == null || own.actions() == null ? Optional.empty() : Optional.of(own.actions());
        });
        List<PartialRun.Prerequisite> added = scope.prerequisites() ? found.cases() : List.of();
        List<PartialRun.Prerequisite> leftOut = scope.prerequisites() ? List.of() : found.cases();
        Set<String> run = new HashSet<>(chosen);
        added.forEach(p -> run.add(p.key()));
        List<String> planEnd = new ArrayList<>();
        for (ScopeCase item : cases) {
            CaseFacts own = facts.get(item.node());
            if (chosen.contains(item.key()) && own != null && own.planEnd()) {
                planEnd.add(item.key());
            }
        }
        return new Result(
                cases.size(),
                matched,
                expression,
                chosen,
                added,
                leftOut,
                found.unreadable(),
                planEnd,
                keys.stream().filter(run::contains).toList(),
                "",
                "");
    }
}
