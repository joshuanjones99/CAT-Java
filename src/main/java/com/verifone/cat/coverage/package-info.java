/**
 * JaCoCo coverage of the product while a run's tests drive it (HANDOFF.md §1.143 in the Python CAT).
 *
 * <p>A port of {@code petrotest.coverage}, in three parts: {@link com.verifone.cat.coverage.Components} (the
 * JVM table and the agent's paths), {@link com.verifone.cat.coverage.Jacoco} (finding {@code jacococli} and
 * running it bounded), and {@link com.verifone.cat.coverage.CoverageDevice} (the agent on the devices, before
 * the tests and after them). {@link com.verifone.cat.coverage.CoverageReport} writes the reports, the summary
 * and the bundle.
 *
 * <p>Not wired into the launch steps yet: the Python does it in {@code cat.launch} step 4 ({@code prepare},
 * before {@code provision}) and step 6 ({@code collect}, then {@code report}, then {@code cat.xray.attach}).
 * {@code LaunchConfig.coverage()} carries the Config tab's switch for that wiring.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
package com.verifone.cat.coverage;
