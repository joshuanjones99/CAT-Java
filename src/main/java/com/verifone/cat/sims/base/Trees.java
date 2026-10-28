package com.verifone.cat.sims.base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Removing a folder tree, best effort: Python's {@code shutil.rmtree(..., ignore_errors=True)}. */
public final class Trees {

    private Trees() {
    }

    /** Deletes {@code root} and everything under it; whatever cannot be deleted is left. Never throws. */
    public static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            return;
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // Left behind, as rmtree(ignore_errors=True) leaves it.
            }
        }
    }
}
