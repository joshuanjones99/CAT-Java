/**
 * JaCoCo coverage of the product while a run's tests drive it (HANDOFF.md §1.143 in the Python CAT).
 *
 * <p>A port of {@code petrotest.coverage}: {@link com.verifone.cat.coverage.Components} (the JVM table, the seeds and
 * the agent's paths), {@link com.verifone.cat.coverage.Jacoco} (finding {@code jacococli} and running it bounded),
 * {@link com.verifone.cat.coverage.CoverageDevice} and {@link com.verifone.cat.coverage.CoverageWaits} (the agent on
 * the devices, before the tests and after them), {@link com.verifone.cat.coverage.ClassFileExtractor} (the class files
 * out of the build's overlay), {@link com.verifone.cat.coverage.SourceResolver} (petroapps source roots),
 * {@link com.verifone.cat.coverage.CoverageReport} and {@link com.verifone.cat.coverage.CoverageSummary} (the reports,
 * the summary, the build's running total and the bundle), and {@link com.verifone.cat.coverage.CoverageCommand}
 * ({@code cat-java coverage prepare|collect|report}).
 *
 * <p>Wired into the launch by {@code launch.CoverageLaunch} (step 4's prepare before {@code provision}; step 6's
 * collect, report and the detached {@code cat-java xray-attach}), when {@code LaunchConfig.coverage()} is on.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
package com.verifone.cat.coverage;
