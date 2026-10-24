package com.verifone.cat.launch;

import java.nio.file.Path;

/**
 * One case the Tests Run step may run, in plan order.
 *
 * @param key    the case key, {@code PETROCORE-<n>}
 * @param nodeid the test's node id as the run reports it (the repository-relative path of the case's
 *               file); the key when the case has no file
 * @param file   the case's file, which {@code PatsCaseLoader} reads, or {@code null} for a case the plan
 *               lists but that has no migrated test (Not Run). A Not Run case is never counted as run
 */
public record CaseEntry(String key, String nodeid, Path file) {

    public CaseEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a case needs a key");
        }
        nodeid = nodeid == null || nodeid.isBlank() ? key : nodeid;
    }

    /** Whether the case has a file the run can read. */
    public boolean runnable() {
        return file != null;
    }
}
