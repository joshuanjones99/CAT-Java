package com.verifone.cat.rig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exclusive use of one rig, so that two shards cannot drive the same Commander at once.
 * Port of {@code petrotest/rig/lease.py} (DESIGN.md §5.5).
 *
 * <p>The lock is a real OS file lock ({@link FileChannel#tryLock()}), not an in-process monitor:
 * two processes on one controller must see the same lock. The OS releases it when the holding
 * process exits, so a crashed or killed process cannot leave a rig locked, and there is no
 * stale-lock reaper and no TTL to get wrong.
 *
 * <p>The acquire is always bounded (§6.3). There is no mode that waits forever: a wedged rig must
 * drain its shard and be reported, not hang the run. The lock file is never deleted, because
 * deleting it races a process that has just opened it. The sibling {@code <rig>.holder.json} names
 * the holder for the error message only. It is advisory and may be stale after a crash.
 *
 * <p>The lease says nothing about rig health. Marking a rig unhealthy belongs to recovery.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). It locks a file on the controller and touches no device.
 */
public final class RigLease implements AutoCloseable {

    public static final String LEASE_DIR_ENV = "PETROTEST_LEASE_DIR";
    public static final String LEASE_TIMEOUT_ENV = "PETROTEST_LEASE_TIMEOUT";

    /** Long enough to queue behind one in-flight test (§9), short enough to report a wedged shard in a suite. */
    public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

    private static final String LEASE_DIRNAME = "petrotest-leases";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String rigName;
    private final Path leaseDir;
    private final String holder;
    private final Path lockPath;
    private final Path holderPath;
    private FileChannel channel;
    private FileLock lock;
    private LeaseInfo info;

    /** Who holds a rig, since when. Recorded for reporting and for the busy message. */
    public record LeaseInfo(String rig, String holder, long pid, Instant acquiredAt, Path lockPath) {

        /** The holder record as JSON, the same fields the Python lease writes. */
        public String asJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rig", rig);
            m.put("holder", holder);
            m.put("pid", pid);
            m.put("acquired_at", acquiredAt.toString());
            try {
                return JSON.writeValueAsString(m);
            } catch (IOException e) {
                throw new IllegalStateException("cannot render a lease record", e);
            }
        }

        /** How long the lease has been held. */
        public Duration heldFor() {
            return Duration.between(acquiredAt, Instant.now());
        }
    }

    /**
     * A lease on {@code rigName}, not yet taken.
     *
     * @param leaseDir where the lock files live; null for {@code $PETROTEST_LEASE_DIR}, else the temp dir
     * @param holder   who is asking, for the record; null for this process
     */
    public RigLease(String rigName, Path leaseDir, String holder) {
        if (rigName == null || rigName.isBlank()) {
            throw new IllegalArgumentException("a lease needs a rig name (DESIGN.md §5.5)");
        }
        this.rigName = rigName.strip();
        this.leaseDir = resolveLeaseDir(leaseDir, System.getenv());
        this.holder = holder == null ? defaultHolder() : holder;
        this.lockPath = this.leaseDir.resolve(this.rigName + ".lock");
        this.holderPath = this.leaseDir.resolve(this.rigName + ".holder.json");
    }

    /** The lock directory: the argument, else {@code $PETROTEST_LEASE_DIR}, else a fixed directory under the temp dir. */
    public static Path resolveLeaseDir(Path explicit, Map<String, String> env) {
        Path path;
        if (explicit != null) {
            path = explicit;
        } else if (env.get(LEASE_DIR_ENV) != null && !env.get(LEASE_DIR_ENV).isBlank()) {
            path = Path.of(env.get(LEASE_DIR_ENV));
        } else {
            // Shared between every process on this controller, which rules out a per-process temp dir.
            path = Path.of(System.getProperty("java.io.tmpdir"), LEASE_DIRNAME);
        }
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the lease directory " + path, e);
        }
        return path;
    }

    public boolean held() {
        return channel != null;
    }

    /** The lease taken by this object, or null when it is not held. */
    public LeaseInfo info() {
        return info;
    }

    /**
     * Takes the lease, or throws {@link RigBusyException} when the window expires.
     *
     * @param timeout      the bound; null reads {@code $PETROTEST_LEASE_TIMEOUT}, else {@link #DEFAULT_ACQUIRE_TIMEOUT}
     * @param pollInterval how often to retry; bounded too
     */
    public LeaseInfo acquire(Duration timeout, Duration pollInterval) {
        if (held()) {
            throw new IllegalStateException("lease on rig '" + rigName + "' is already held by this object");
        }
        Duration window = resolveTimeout(timeout, System.getenv());
        Duration interval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        Instant deadline = Instant.now().plus(window);

        FileChannel opened;
        try {
            opened = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open the lock file " + lockPath + ": " + e.getMessage(), e);
        }

        try {
            while (true) {
                FileLock got = tryLock(opened);
                if (got != null) {
                    channel = opened;
                    lock = got;
                    info = new LeaseInfo(rigName, holder, ProcessHandle.current().pid(), Instant.now(), lockPath);
                    writeHolder(info);
                    return info;
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isZero() || remaining.isNegative()) {
                    throw new RigBusyException("rig '" + rigName + "' is leased by another shard and did not free within "
                            + Durations.format(window) + " (" + describeCurrentHolder() + "). "
                            + "DESIGN.md §5.5: one shard at a time per rig. Either add rigs (§9) or reduce -n.");
                }
                Duration nap = interval.compareTo(remaining) < 0 ? interval : remaining;
                // A bounded park, not Thread.sleep: the wait ends at the deadline whatever wakes it.
                LockSupport.parkNanos(nap.toNanos());
            }
        } catch (RuntimeException e) {
            closeQuietly(opened);
            throw e;
        }
    }

    /** {@link #acquire(Duration, Duration)} with the default poll interval. */
    public LeaseInfo acquire(Duration timeout) {
        return acquire(timeout, DEFAULT_POLL_INTERVAL);
    }

    /** Releases the lease. Safe to call when not held. */
    public void release() {
        if (!held()) {
            return;
        }
        try {
            clearHolder();
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel releases the lock regardless; a failure here must not mask the caller's outcome.
        } finally {
            closeQuietly(channel);
            channel = null;
            lock = null;
            info = null;
        }
    }

    @Override
    public void close() {
        release();
    }

    /** Who last recorded holding this lease and since when, for an error message. */
    public String describeHolder() {
        return describeCurrentHolder();
    }

    private FileLock tryLock(FileChannel fc) {
        try {
            return fc.tryLock();
        } catch (OverlappingFileLockException e) {
            // Another thread of this process holds it. Same answer as another process: busy.
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("cannot lock " + lockPath + ": " + e.getMessage(), e);
        }
    }

    private void writeHolder(LeaseInfo record) {
        try {
            Files.writeString(holderPath, record.asJson());
        } catch (IOException ignored) {
            // Advisory only; never fail a run over the audit trail.
        }
    }

    private void clearHolder() {
        try {
            Files.deleteIfExists(holderPath);
        } catch (IOException ignored) {
            // Advisory only.
        }
    }

    private String describeCurrentHolder() {
        try {
            JsonNode record = JSON.readTree(Files.readString(holderPath));
            return "last recorded holder: " + record.path("holder").asText() + " since "
                    + record.path("acquired_at").asText();
        } catch (IOException | RuntimeException e) {
            return "holder unknown";
        }
    }

    private static void closeQuietly(FileChannel fc) {
        if (fc == null) {
            return;
        }
        try {
            fc.close();
        } catch (IOException ignored) {
            // Nothing to do: the descriptor is gone either way.
        }
    }

    /** Explicit timeout, else {@code $PETROTEST_LEASE_TIMEOUT}, else the default. Never unbounded (§6.3). */
    static Duration resolveTimeout(Duration timeout, Map<String, String> env) {
        Duration resolved;
        if (timeout != null) {
            resolved = timeout;
        } else if (env.get(LEASE_TIMEOUT_ENV) != null && !env.get(LEASE_TIMEOUT_ENV).isBlank()) {
            resolved = Durations.parse(env.get(LEASE_TIMEOUT_ENV));
        } else {
            resolved = DEFAULT_ACQUIRE_TIMEOUT;
        }
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException("lease timeout must be positive, got " + Durations.format(resolved));
        }
        return resolved;
    }

    private static String defaultHolder() {
        return "pid" + ProcessHandle.current().pid();
    }
}
