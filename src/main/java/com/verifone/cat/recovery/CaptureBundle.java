package com.verifone.cat.recovery;

import java.util.List;

/**
 * What rung 0 collected for one failed test. Ported from the summary half of
 * {@code petrotest.recovery.capture.CaptureBundle}: the names of the sources that answered and
 * those that did not. Nothing is written to disk on this port (see {@link Capturer}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record CaptureBundle(String runId, String testId, List<String> collected, List<String> failed) {

    public CaptureBundle {
        collected = List.copyOf(collected);
        failed = List.copyOf(failed);
    }

    /** True when every source answered. An empty bundle (no sources) is complete, and says so. */
    public boolean complete() {
        return failed.isEmpty();
    }
}
