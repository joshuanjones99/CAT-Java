package com.verifone.cat.collect;

import java.nio.file.Path;

/**
 * What one {@link Collect#collect} call is asked to do.
 *
 * @param revision the petroapps branch to read ({@code origin/<revision>}, else the local {@code <revision>})
 * @param dist     the dist folder that wins for tests ({@code <id>/<dist>/} before {@code <id>/})
 * @param outDir   where pulled tests and baselines are written, or {@code null} for a dry run that writes nothing
 * @param repoRoot this CAT checkout: its {@code tests/} is checked for names a pull would shadow (A5)
 * @param fetch    {@code true} to run {@code git fetch origin <revision>} first
 */
public record CollectOptions(String revision, String dist, Path outDir, Path repoRoot, boolean fetch) {

    /** The Python CAT's defaults: {@code master}, {@code buypass}, a dry run, no fetch. */
    public static CollectOptions defaults(Path repoRoot) {
        return new CollectOptions("master", "buypass", null, repoRoot, false);
    }
}
