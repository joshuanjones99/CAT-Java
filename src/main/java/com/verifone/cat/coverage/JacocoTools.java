package com.verifone.cat.coverage;

import java.nio.file.Path;

/**
 * The JaCoCo tools a coverage step runs: the Java that runs the CLI, the CLI, and the agent (when asked for).
 *
 * <p>A port of {@code petrotest.coverage.jacoco.JacocoTools}. UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param java  the java executable that runs {@code cli}
 * @param cli   {@code jacococli.jar}, at least {@link Jacoco#MIN_CLI_VERSION}
 * @param agent {@code jacocoagent.jar}, or null when none was looked for or found
 */
public record JacocoTools(Path java, Path cli, Path agent) {}
