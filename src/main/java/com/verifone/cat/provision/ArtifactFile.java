package com.verifone.cat.provision;

/**
 * One file within an artifact bundle.
 *
 * <p>{@code source} is relative to the payload root, so the manifest is machine-independent: two checkouts
 * must compute the same digest for the same content. {@code remotePath} is absolute on the target. {@code mode}
 * is the POSIX permission bits pushed with the file.
 *
 * <p>A port of {@code petrotest.provision.artifacts.ArtifactFile}. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record ArtifactFile(String source, String remotePath, int mode) {

    /** Mode 0644, the Python default. */
    public static final int DEFAULT_MODE = 0644;

    public ArtifactFile(String source, String remotePath) {
        this(source, remotePath, DEFAULT_MODE);
    }

    public ArtifactFile {
        if (source == null || source.isEmpty() || source.startsWith("/")) {
            throw new IllegalArgumentException("artifact source must be payload-relative: " + source);
        }
        if (remotePath == null || !remotePath.startsWith("/")) {
            throw new IllegalArgumentException("remote path must be absolute: " + remotePath);
        }
    }
}
