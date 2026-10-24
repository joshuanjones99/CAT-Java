package com.verifone.cat.pats;

import java.util.List;

/**
 * A parsed PATS case: its title (the {@code <testcase name=>} or the {@code Scenario:} line) and its actions in
 * order. The Python's {@code (title, actions)} tuple from {@code parse_actions} and {@code parse_scenario}.
 */
public record ParsedCase(String title, List<Action> actions) {

    public ParsedCase {
        actions = List.copyOf(actions);
    }
}
