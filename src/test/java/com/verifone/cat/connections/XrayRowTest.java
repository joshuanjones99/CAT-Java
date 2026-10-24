package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.verifone.cat.atlassian.AtlassianUnavailable;

/**
 * The Xray row: the sign-in's answer, the cache a signed-in answer gets, and the budget an Xray row is
 * given. The sign-in is a fake, so nothing here reaches Atlassian.
 */
class XrayRowTest {

    private static final Target XRAY = Target.xray("Xray", "xray.cloud.getxray.app");

    @Test
    void aSignedInAnswerIsUpAndNamesTheAccount() {
        ProbeResult result = new XrayProbe(() -> "Joshua Jones").apply(XRAY);

        assertEquals(ProbeState.UP, result.state());
        assertEquals("signed in as Joshua Jones", result.detail());
        assertEquals("Xray", result.target());
        assertEquals(Target.Kind.XRAY, result.kind());
    }

    @Test
    void aSignedInAnswerIsKeptSoTheWatchDoesNotSignInEveryRound() {
        AtomicInteger signIns = new AtomicInteger();
        XrayProbe probe = new XrayProbe(() -> {
            signIns.incrementAndGet();
            return "Joshua Jones";
        });

        probe.apply(XRAY);
        ProbeResult again = probe.apply(XRAY);

        assertEquals(ProbeState.UP, again.state());
        assertEquals(1, signIns.get());
    }

    @Test
    void aRedAnswerIsNotKeptAndIsAskedAgainEachRound() {
        AtomicInteger signIns = new AtomicInteger();
        XrayProbe probe = new XrayProbe(() -> {
            signIns.incrementAndGet();
            throw new AtlassianUnavailable("Xray authenticate -> HTTP 401");
        });

        ProbeResult first = probe.apply(XRAY);
        ProbeResult second = probe.apply(XRAY);

        assertEquals(ProbeState.DOWN, first.state());
        assertEquals("Xray authenticate -> HTTP 401", first.detail());
        assertEquals(ProbeState.DOWN, second.state());
        assertEquals(2, signIns.get());
    }

    @Test
    void anXrayRowIsGivenAtlassiansOwnCeilingNotTheConnectsBudget() {
        // The connect budget is 50 ms; the sign-in takes 300 ms and succeeds, so only a row given its
        // own budget can say UP.
        ExecutorService pool = ConnectionProbe.newProbePool();
        try {
            Target slowSignIn = Target.xray("Xray", "xray.cloud.getxray.app");
            ConnectionProbe probe = new ConnectionProbe(pool, target -> {
                sleep(300);
                return new ProbeResult(target.name(), ProbeState.UP, "signed in as tester", Target.Kind.XRAY);
            });

            List<ProbeResult> results = probe.probeAll(List.of(slowSignIn), Duration.ofMillis(50));

            assertEquals(ProbeState.UP, results.get(0).state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anXrayRowWithNoSignInWiredIsUnknownNotDown() {
        ExecutorService pool = ConnectionProbe.newProbePool();
        try {
            ProbeResult result = new ConnectionProbe(pool)
                    .probeAll(List.of(XRAY), Duration.ofSeconds(1)).get(0);

            assertEquals(ProbeState.UNKNOWN, result.state());
            assertTrue(result.detail().contains("no Xray probe"), result.detail());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theXrayRowIsAddressedOnTheHttpsPort() {
        assertEquals(Integer.valueOf(443), XRAY.port());
        assertEquals(Target.Kind.XRAY, XRAY.kind());
        assertEquals(false, XRAY.fixed());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
