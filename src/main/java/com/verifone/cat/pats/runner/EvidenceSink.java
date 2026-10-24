package com.verifone.cat.pats.runner;

/**
 * Where a failed validation's evidence goes: a screenshot and the DOM, beside the case's other
 * artifacts, named by {@code <case key>-<label>}. The runner calls it only when the case declared the
 * POS fixture, and a failure to capture is logged and does not change the verdict.
 */
@FunctionalInterface
public interface EvidenceSink {

    void capture(String label) throws Exception;
}
