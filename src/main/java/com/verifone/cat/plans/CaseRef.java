package com.verifone.cat.plans;

/**
 * A plan case as a selection sees it: its key and its runnable node, the pair Python's
 * {@code petrotest.selection} passes around as a tuple.
 */
public record CaseRef(String key, String node) {}
