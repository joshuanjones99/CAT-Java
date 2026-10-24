package com.verifone.cat.pats.runner;

import java.util.List;

/** A test's own case: a key, a title, a source and actions, with no parser involved. */
record TestCase(String key, String title, String source, List<RunnableAction> actions) implements RunnableCase {

    /** A case named {@code key} whose actions are {@code actions}, numbered from 0 in the order given. */
    static TestCase of(String key, RunnableAction... actions) {
        return new TestCase(key, "a test case", "memory", List.of(actions));
    }
}
