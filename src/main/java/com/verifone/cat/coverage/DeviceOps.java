package com.verifone.cat.coverage;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;

/**
 * The seams {@link CoverageDevice} reaches the world through. Tests replace each one; production takes the
 * defaults from {@link #defaults}.
 *
 * <p>{@link #defaults} wires {@link #waitForSsh} and {@link #waitForCgilink} to {@link CoverageWaits} (the Python's
 * {@code new_install.wait_for_ssh} and {@code wait_for_cgilink}, as {@code device._ops_defaults} does). A test that
 * passes null for either gets the old behaviour: a step that must wait after a reboot reports that it cannot,
 * rather than assuming the device is back.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param transportFactory   opens a transport to one box
 * @param waitForSsh         waits for SSH to answer from a boot after {@code rebootedAtNanos}; null = not wired
 * @param waitForCgilink     waits for CGILink on the host; null = not wired
 * @param tools              the JaCoCo tools, or null to find them from the environment
 * @param cli                runs {@code jacococli}
 * @param log                one line per step, as the Python CAT prints them
 * @param mountTimeout       how long the Commander's data drive gets to mount before its exec files are read
 * @param mountPollInterval  the gap between mount checks
 */
public record DeviceOps(
        Function<TargetSpec, Transport> transportFactory,
        SshWait waitForSsh,
        Predicate<String> waitForCgilink,
        JacocoTools tools,
        Jacoco.CliRunner cli,
        Consumer<String> log,
        Duration mountTimeout,
        Duration mountPollInterval) {

    /** Waits for SSH from a boot after the reboot whose {@link System#nanoTime} is {@code rebootedAtNanos}. */
    @FunctionalInterface
    public interface SshWait {
        boolean await(TargetSpec spec, long rebootedAtNanos);
    }

    /** The mount wait the Python CAT uses by default. */
    public static final Duration DEFAULT_MOUNT_TIMEOUT = Duration.ofSeconds(180);

    /** The gap the Python CAT uses between mount checks. */
    public static final Duration DEFAULT_MOUNT_POLL = Duration.ofSeconds(5);

    public DeviceOps {
        if (transportFactory == null) {
            throw new IllegalArgumentException("a transport factory is required");
        }
        cli = cli == null ? Jacoco.processRunner() : cli;
        log = log == null ? line -> { } : log;
        mountTimeout = mountTimeout == null ? DEFAULT_MOUNT_TIMEOUT : mountTimeout;
        mountPollInterval = mountPollInterval == null ? DEFAULT_MOUNT_POLL : mountPollInterval;
    }

    /** The production defaults: SSH through {@link JschTransport}, the real CLI runner, and {@link CoverageWaits}. */
    public static DeviceOps defaults(Consumer<String> log) {
        Function<TargetSpec, Transport> transports = spec -> new JschTransport(spec.toSshTarget());
        return new DeviceOps(transports, CoverageWaits.ssh(transports), CoverageWaits.cgilink(), null,
                Jacoco.processRunner(), log, null, null);
    }

    /** The same operations with {@code tools} in place of the environment's. */
    public DeviceOps withTools(JacocoTools value) {
        return new DeviceOps(transportFactory, waitForSsh, waitForCgilink, value, cli, log, mountTimeout,
                mountPollInterval);
    }
}
