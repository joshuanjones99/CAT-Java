package com.verifone.cat.pats;

import java.util.List;

import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;

/**
 * A parsed PATS case: its key ({@code PETROCORE-<n>}), its title (the {@code <testcase name=>} or the
 * {@code Scenario:} line), where it was read from, and its actions in order. The Python's
 * {@code (title, actions)} tuple from {@code parse_actions} and {@code parse_scenario}, carrying the identity
 * the runner's log line needs ({@link RunnableCase}).
 *
 * <p>The component is named {@code steps} because {@link #actions()} is the {@link RunnableCase} view, typed for
 * the runner; the parsed {@link Action}s are the same objects.
 */
public record ParsedCase(String key, String title, String source, List<Action> steps) implements RunnableCase {

    public ParsedCase {
        steps = List.copyOf(steps);
    }

    /** A case read with no key or source yet, as the parsers return it; the loader sets both. */
    public ParsedCase(String title, List<Action> steps) {
        this("", title, "", steps);
    }

    /** This case with its key and source, where the loader found them. */
    public ParsedCase withSource(String caseKey, String caseSource) {
        return new ParsedCase(caseKey, title, caseSource, steps);
    }

    @Override
    public List<RunnableAction> actions() {
        return List.<RunnableAction>copyOf(steps);
    }
}
