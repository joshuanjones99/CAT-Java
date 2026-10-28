package com.verifone.cat.provision;

import java.nio.file.Path;
import java.time.Duration;

import com.verifone.cat.provision.transport.Transport;

/**
 * A checklist item {@code provision} performs after the file push. Named, idempotent, bounded, and it reports
 * what it did. A port of {@code petrotest.provision.checklist.ChecklistStep}.
 *
 * <p>{@link #forTarget} binds a step to the box it is about to run against, for a step that needs the box's
 * address or the payload's files rather than only its transport. The default returns the step unchanged.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ChecklistStep {

    String name();

    StepResult apply(Transport transport, String controllerHost, Duration timeout);

    /**
     * True when the box cannot work without this step, so a SKIPPED result fails the target as FAILED does. Not in
     * the Python, where SKIPPED always passes: added after run {@code ac22943e6604} (2026-10-28), where a skipped
     * browser override left a wiped register with no CDP and {@code provision} still PASS. The Commander's stubs are
     * not required: they are manual checklist items until ported.
     */
    default boolean required() {
        return false;
    }

    default ChecklistStep forTarget(TargetSpec spec, Path payloadRoot) {
        return this;
    }
}
