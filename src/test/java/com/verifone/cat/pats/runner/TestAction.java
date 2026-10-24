package com.verifone.cat.pats.runner;

import java.util.ArrayList;
import java.util.List;

/** A test's own action: what a PATS XML action looks like to the runner, with no parser involved. */
record TestAction(String name, int index, List<ActionField> fields) implements RunnableAction {

    /** An action at {@code index} with {@code name} and {@code name, value, name, value, ...} fields. */
    static TestAction of(int index, String name, String... nameValuePairs) {
        List<ActionField> fields = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.add(new ActionField(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new TestAction(name, index, List.copyOf(fields));
    }
}
