package com.verifone.cat.core;

import java.nio.file.Path;

/**
 * Where CAT-Java keeps its own files (hard rule 6: it writes only under {@code ~/.cat/java}). Production
 * defaults only: a class that a test drives still takes its directory as a parameter and uses these as the
 * default argument, so no test touches the real home.
 */
public final class CatHome {

    private CatHome() {}

    /** The user's home directory. */
    public static Path user() {
        return Path.of(System.getProperty("user.home"));
    }

    /** {@code ~/.cat/java}: the only directory CAT-Java writes. */
    public static Path java() {
        return java(user());
    }

    /** {@code <home>/.cat/java}, for a caller that was handed a home directory. */
    public static Path java(Path home) {
        return python(home).resolve("java");
    }

    /** {@code ~/.cat}: the Python CAT's directory. Read-only to CAT-Java. */
    public static Path python() {
        return python(user());
    }

    /** {@code <home>/.cat}, for a caller that was handed a home directory. Read-only to CAT-Java. */
    public static Path python(Path home) {
        return home.resolve(".cat");
    }
}
