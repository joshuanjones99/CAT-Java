package com.verifone.cat.pos.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

/**
 * An {@link ArtifactSink} over one directory, created on first use. Port of the Python
 * {@code DirectoryArtifacts}.
 */
public record DirectoryArtifacts(Path root) implements ArtifactSink {

    public DirectoryArtifacts {
        if (root == null) {
            throw new IllegalArgumentException("artifacts root is required");
        }
    }

    @Override
    public Path pathFor(String name) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + root, e);
        }
        return root.resolve(name);
    }
}
