package com.verifone.cat.runconfig;

/** One case on the Run scope's list: a ticked plan's migrated case, in run order. */
public record ScopeCase(String key, String node, String title) {

    public ScopeCase {
        key = key == null ? "" : key;
        node = node == null ? "" : node;
        title = title == null ? "" : title.strip();
    }

    /** The file part of the node: {@code tests/a.py::test_x} names {@code tests/a.py}. */
    public String file() {
        int at = node.indexOf("::");
        return at < 0 ? node : node.substring(0, at);
    }
}
