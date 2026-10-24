package com.verifone.cat.pats.runner;

import java.util.List;

/**
 * One resolved case as the runner sees it: its key, its title, where it came from, and its actions in
 * order. The parsed PATS case type adapts to this; the runner needs nothing else from it. Reference
 * files and the store they come from stay with the case reader, behind the handlers that need them.
 */
public interface RunnableCase {

    /** The case's key, {@code PETROCORE-<n>}. */
    String key();

    /** The case's title, for the log line the runner writes when the case begins. */
    String title();

    /** Where the case was read from (a git path or a file path), for the log line. */
    String source();

    /** The case's actions, in the order PATS runs them. */
    List<RunnableAction> actions();
}
