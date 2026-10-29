package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * The waits after a coverage reboot, which {@link DeviceOps#defaults} now wires. SSH answers through an in-memory
 * transport; CGILink through a loopback server. Nothing reaches a device.
 */
class CoverageWaitsTest {

    private static final TargetSpec COMMANDER = new TargetSpec(TargetRole.COMMANDER, "commander", "commander.invalid",
            "root", TargetSpec.DEFAULT_SSH_PORT, TargetSpec.DEFAULT_SHIM_PORT, null);

    @Test
    void onlyABootAfterTheRebootCounts() {
        long rebootedAt = System.nanoTime() - Duration.ofSeconds(60).toNanos();
        InMemoryTransport young = new InMemoryTransport().command("cat /proc/uptime",
                new CommandResult(0, "12.50 40.00\n", ""));
        InMemoryTransport old = new InMemoryTransport().command("cat /proc/uptime",
                new CommandResult(0, "86400.00 1.00\n", ""));
        assertTrue(CoverageWaits.bootedAfter(spec -> young, COMMANDER, rebootedAt));
        assertFalse(CoverageWaits.bootedAfter(spec -> old, COMMANDER, rebootedAt), "the boot before the reboot");
        assertFalse(CoverageWaits.bootedAfter(spec -> {
            throw new DeviceUnreachable("commander", "refused");
        }, COMMANDER, rebootedAt), "a device mid-boot");
        InMemoryTransport garbled = new InMemoryTransport().command("cat /proc/uptime",
                new CommandResult(0, "half a banner", ""));
        assertFalse(CoverageWaits.bootedAfter(spec -> garbled, COMMANDER, rebootedAt));
    }

    @Test
    void theSshWaitPollsUntilTheDeviceIsBackAndGivesUpAtItsBound() {
        AtomicInteger tries = new AtomicInteger();
        long rebootedAt = System.nanoTime() - Duration.ofSeconds(60).toNanos();
        DeviceOps.SshWait wait = CoverageWaits.ssh(spec -> {
            if (tries.incrementAndGet() < 3) {
                throw new DeviceUnreachable("commander", "still rebooting");
            }
            return new InMemoryTransport().command("cat /proc/uptime", new CommandResult(0, "5.0 1.0", ""));
        }, Duration.ofSeconds(5), Duration.ofMillis(1));
        assertTrue(wait.await(COMMANDER, rebootedAt));
        assertEquals(3, tries.get());

        DeviceOps.SshWait never = CoverageWaits.ssh(spec -> {
            throw new DeviceUnreachable("commander", "gone");
        }, Duration.ofMillis(30), Duration.ofMillis(5));
        assertFalse(never.await(COMMANDER, rebootedAt));
    }

    @Test
    void cgilinkCountsOnlyAnAnsweringApplicationTier() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/cgi-bin/CGILink", exchange -> {
            int n = requests.incrementAndGet();
            byte[] body = (n == 1 ? "fault: AAA service unavailable" : "<error>bad request</error>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 2 ? 503 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String host = InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
            assertFalse(CoverageWaits.cgilinkAnswers(host), "AAA down");
            assertFalse(CoverageWaits.cgilinkAnswers(host), "Apache up, Tomcat starting");
            assertTrue(CoverageWaits.cgilinkAnswers(host), "answering on its own terms");
            assertTrue(CoverageWaits.cgilink(CoverageWaits::cgilinkAnswers, Duration.ofSeconds(5), Duration.ofMillis(1))
                    .test(host));
        } finally {
            server.stop(0);
        }
        assertFalse(CoverageWaits.cgilink(h -> false, Duration.ofMillis(20), Duration.ofMillis(5)).test("x"));
    }

    @Test
    void theDefaultsWireBothWaits() {
        DeviceOps ops = DeviceOps.defaults(line -> { });
        assertTrue(ops.waitForSsh() != null && ops.waitForCgilink() != null);
    }
}
