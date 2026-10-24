package com.verifone.cat.validate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The precedence order PATS's rule-file readers resolve their file by (the Python CAT's
 * {@code petrotest.validate.rulefile}).
 *
 * <p>The first source that is set decides. It is returned if it names an existing file, and
 * nothing is returned otherwise: a later source is not tried. Sources, in order: an explicit
 * path, the environment variable naming the file, then {@code $ROOT/relpath} for the environment
 * variable naming a checkout root.
 *
 * <p>The Python version also consults a rig attribute. A Java rig file has no such field, so
 * that step is left out here.
 */
public final class RuleFiles {

    private RuleFiles() {}

    /**
     * The first of {@code explicit}, {@code env[envPath]}, {@code env[envRoot]/relpath} that is
     * set, if it names an existing file. Environment values are read as given: an empty value
     * counts as unset, as it does in the Python version.
     */
    public static Optional<Path> resolve(Path explicit, Map<String, String> env, String envPath,
            String envRoot, Path relpath) {
        if (explicit != null) {
            return existing(explicit);
        }
        String fromEnv = env.get(envPath);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return existing(Path.of(fromEnv));
        }
        String root = env.get(envRoot);
        if (root != null && !root.isEmpty()) {
            return existing(Path.of(root).resolve(relpath));
        }
        return Optional.empty();
    }

    private static Optional<Path> existing(Path candidate) {
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }
}
