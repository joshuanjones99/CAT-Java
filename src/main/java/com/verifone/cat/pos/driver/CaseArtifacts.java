package com.verifone.cat.pos.driver;

import java.nio.file.Path;

/**
 * An {@link ArtifactSink} re-pointed at each test's folder for the test's duration, then back at the session's: the
 * Python {@code pos} fixture's {@code pos_driver.artifacts = pos_artifacts} and its restore. A {@link PosDriver} is
 * built once per session with one of these, so a failed validation's screenshot and DOM land in the case folder of the
 * case that failed ({@code <run>/<KEY>/}, beside its {@code steps.log}) rather than the session's.
 *
 * <p>Thread-safe: the run's thread points it, a capture reads it. A folder is created only when something is written
 * to it, as the Python's {@code DirectoryArtifacts.path_for}.
 */
public final class CaseArtifacts implements ArtifactSink {

    private final Path session;
    private volatile Path current;

    /** @param session the folder used outside a test (the Python's {@code results_root()/<register id>}) */
    public CaseArtifacts(Path session) {
        if (session == null) {
            throw new IllegalArgumentException("the session's artifacts folder is required");
        }
        this.session = session;
    }

    /** Points the sink at {@code folder} until {@link #restore}; null is the same as {@link #restore}. */
    public void pointAt(Path folder) {
        current = folder;
    }

    /** Points the sink back at the session's folder. */
    public void restore() {
        current = null;
    }

    /** The folder captures go to now. */
    public Path folder() {
        Path folder = current;
        return folder == null ? session : folder;
    }

    @Override
    public Path pathFor(String name) {
        return new DirectoryArtifacts(folder()).pathFor(name);
    }
}
