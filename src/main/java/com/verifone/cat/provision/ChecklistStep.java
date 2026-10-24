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

    default ChecklistStep forTarget(TargetSpec spec, Path payloadRoot) {
        return this;
    }
}
