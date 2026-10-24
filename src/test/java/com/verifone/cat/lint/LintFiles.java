package com.verifone.cat.lint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Which files a design-rule gate reads. Paths are relative to the project root, forward slashes. */
final class LintFiles {

    /** The project root: surefire runs the tests from the module directory. */
    static final Path ROOT = Path.of("").toAbsolutePath();

    private LintFiles() {}

    /**
     * The regular files under {@code root} whose names pass {@code name}, sorted, minus those whose relative path
     * passes {@code skip}. A missing root is an error, not an empty scan: a gate that reads nothing must not pass.
     */
    static List<Path> files(Path root, Predicate<String> name, Predicate<String> skip) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("gate scope " + root.toAbsolutePath() + " does not exist");
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> name.test(p.getFileName().toString()))
                    .filter(p -> !skip.test(rel(p)))
                    .sorted()
                    .toList();
        }
    }

    static String rel(Path p) {
        return ROOT.relativize(p.toAbsolutePath()).toString().replace('\\', '/');
    }
}
