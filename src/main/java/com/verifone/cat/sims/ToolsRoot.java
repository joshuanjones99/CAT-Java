package com.verifone.cat.sims;

import java.nio.file.Path;

/**
 * Where the PATS simulators are staged: the one place this package resolves the tools root.
 *
 * <p>The order is the Python CAT's {@code cat.sims.config.tools_root}: {@code CAT_TOOLS_ROOT},
 * then the rig file's {@code sims:} block, then {@link #DEFAULT}. Blank values are absent.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ToolsRoot {

    /** The default staging root on a CAT controller. Never written to by this package. */
    public static final Path DEFAULT = Path.of("C:/CICD/Tools");

    /** The environment variable that moves the tools root. */
    public static final String ENV = "CAT_TOOLS_ROOT";

    private ToolsRoot() {
    }

    /**
     * Resolves the tools root from its three sources, highest first.
     *
     * @param envValue the value of {@code CAT_TOOLS_ROOT}, or null
     * @param rigValue the rig file's {@code sims:} tools root, or null
     */
    public static Path resolve(String envValue, Path rigValue) {
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue.strip());
        }
        if (rigValue != null) {
            return rigValue;
        }
        return DEFAULT;
    }

    /** {@link #resolve} with the process's own environment. */
    public static Path resolve(Path rigValue) {
        return resolve(System.getenv(ENV), rigValue);
    }
}
