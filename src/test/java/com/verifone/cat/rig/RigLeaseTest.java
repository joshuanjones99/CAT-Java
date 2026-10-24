package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cross-process rig lease. Two {@link RigLease} objects in one JVM take the same OS lock
 * (the second sees an overlapping-lock failure, which is busy, not an error), which is the
 * closest a single test process can come to two xdist workers.
 */
class RigLeaseTest {

    @TempDir
    Path dir;

    @Test
    void aSecondShardWaitsAndThenIsRefusedWithTheHolderNamed() {
        try (RigLease first = new RigLease("bench", dir, "shard-a")) {
            first.acquire(Duration.ofSeconds(5));

            RigLease second = new RigLease("bench", dir, "shard-b");
            RigBusyException busy = assertThrows(RigBusyException.class,
                    () -> second.acquire(Duration.ofMillis(300), Duration.ofMillis(50)));

            assertTrue(busy.getMessage().contains("leased by another shard"), busy.getMessage());
            assertTrue(busy.getMessage().contains("shard-a"), busy.getMessage());
            assertFalse(second.held());
        }
    }

    @Test
    void releasingLetsTheNextShardTakeIt() {
        RigLease.LeaseInfo info;
        try (RigLease first = new RigLease("bench", dir, "shard-a")) {
            info = first.acquire(Duration.ofSeconds(5));
            assertEquals("bench", info.rig());
            assertEquals("shard-a", info.holder());
        }

        try (RigLease next = new RigLease("bench", dir, "shard-b")) {
            assertEquals("shard-b", next.acquire(Duration.ofSeconds(5)).holder());
        }
    }

    @Test
    void theHolderRecordIsWrittenAndClearedOnRelease() {
        RigLease lease = new RigLease("bench", dir, "shard-a");
        lease.acquire(Duration.ofSeconds(5));
        assertTrue(lease.describeHolder().contains("shard-a"), lease.describeHolder());

        lease.release();

        assertEquals("holder unknown", lease.describeHolder());
        assertFalse(lease.held());
        assertNull(lease.info());
    }

    @Test
    void theLockFileIsNeverDeleted() {
        try (RigLease lease = new RigLease("bench", dir, "shard-a")) {
            lease.acquire(Duration.ofSeconds(5));
        }
        assertTrue(Files.exists(dir.resolve("bench.lock")));
    }

    @Test
    void aSecondAcquireOnTheSameObjectIsAnError() {
        try (RigLease lease = new RigLease("bench", dir, "shard-a")) {
            lease.acquire(Duration.ofSeconds(5));
            assertThrows(IllegalStateException.class, () -> lease.acquire(Duration.ofSeconds(5)));
        }
    }

    @Test
    void theLeaseDirectoryComesFromTheArgumentThenTheEnvironment() {
        Path fromEnv = dir.resolve("env");
        assertEquals(dir.resolve("explicit"),
                RigLease.resolveLeaseDir(dir.resolve("explicit"), Map.of(RigLease.LEASE_DIR_ENV, fromEnv.toString())));
        assertEquals(fromEnv, RigLease.resolveLeaseDir(null, Map.of(RigLease.LEASE_DIR_ENV, fromEnv.toString())));
        assertTrue(Files.isDirectory(fromEnv));
    }

    @Test
    void theAcquireTimeoutIsBoundedAndReadFromTheEnvironment() {
        assertEquals(Duration.ofSeconds(2),
                RigLease.resolveTimeout(null, Map.of(RigLease.LEASE_TIMEOUT_ENV, "2s")));
        assertEquals(Duration.ofMinutes(5), RigLease.resolveTimeout(null, Map.of()));
        assertEquals(Duration.ofSeconds(9), RigLease.resolveTimeout(Duration.ofSeconds(9), Map.of()));
    }

    @Test
    void aZeroTimeoutIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> RigLease.resolveTimeout(Duration.ZERO, Map.of()));
    }

    @Test
    void theInfoRecordRendersTheSameFieldsAsThePythonLease() {
        try (RigLease lease = new RigLease("bench", dir, "shard-a")) {
            RigLease.LeaseInfo info = lease.acquire(Duration.ofSeconds(5));
            String json = info.asJson();
            assertNotNull(json);
            assertTrue(json.contains("\"rig\":\"bench\""), json);
            assertTrue(json.contains("\"holder\":\"shard-a\""), json);
            assertTrue(json.contains("\"acquired_at\""), json);
        }
    }
}
