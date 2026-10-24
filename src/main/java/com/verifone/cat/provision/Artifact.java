package com.verifone.cat.provision;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One row of the provision table (DESIGN.md 6.1).
 *
 * <p>{@code replaces} is the PATS method the row replaces, kept for traceability. {@code replacedGate} is the
 * PATS property that used to gate it, recorded and not reimplemented; it may be null.
 *
 * <p>A port of {@code petrotest.provision.artifacts.Artifact}. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Artifact(String name, Set<TargetRole> roles, List<ArtifactFile> files, String replaces,
        String replacedGate) {

    public Artifact {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("artifact needs a name");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("artifact " + name + " has no files");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("artifact " + name + " has no target role");
        }
        roles = Collections.unmodifiableSet(EnumSet.copyOf(roles));
        files = List.copyOf(files);
    }

    /** The files this artifact pushes to a target of {@code role}, in push order. */
    public List<ArtifactFile> filesFor(TargetRole role) {
        return roles.contains(role) ? files : List.of();
    }
}
