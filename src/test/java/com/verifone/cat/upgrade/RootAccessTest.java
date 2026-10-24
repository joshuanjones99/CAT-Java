package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The root-access pushes and the bypass switch, against scripted jars and an in-memory SSH target. */
class RootAccessTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final String HOST = "192.0.2.11";

    /** What a 1052 Commander answers the jacoco package with, in 0.4 s (2026-09-21). */
    private static final String TOO_LARGE_FAULT = "2026-09-21 19:29:01: SEVERE: Host reported failure\n"
            + "<VFI:Fault><faultCode>CGIPortal.InternalError</faultCode><detail><e:vfiFault xmlns:e=\"x\">"
            + "<e:message>Uploaded file exceeds size limit</e:message></e:vfiFault></detail></VFI:Fault>\n"
            + "2026-09-21 19:29:01: SEVERE: Error occurred, error code: 500 (0 seconds elapsed)";

    @TempDir
    Path dir;

    private NewInstallTools tools() {
        return new NewInstallTools(dir, "java", dir.resolve("NetLoaderClient.jar").toString(),
                dir.resolve("update-all-jacoco-noPW.tgz").toString(),
                dir.resolve("update_pearl-controller_gemreg-force-registered.tgz").toString());
    }

    /** Scripted jars: each call is answered by {@code answer}; every argv is recorded. */
    private static final class Jar implements Jars {
        final List<List<String>> calls = new ArrayList<>();
        private final Answer answer;

        Jar(Answer answer) {
            this.answer = answer;
        }

        @Override
        public Result run(List<String> argv, Duration timeout) throws IOException {
            calls.add(List.copyOf(argv));
            return answer.apply(argv);
        }

        /** How a scripted jar answers one call. */
        @FunctionalInterface
        interface Answer {
            Result apply(List<String> argv) throws IOException;
        }
    }

    private static boolean isGemreg(List<String> argv) {
        return argv.get(5).contains("gemreg");
    }

    @Test
    void aHardFaultEndsTheRetryAtOnce() {
        var jar = new Jar(argv -> isGemreg(argv)
                ? new Jars.Result(0, "", "")
                : new Jars.Result(500, "", TOO_LARGE_FAULT));
        List<String> logged = new ArrayList<>();

        StepResult result = RootAccess.pushUntilConfirmed(tools(), jar, commander(), T, Duration.ofSeconds(5),
                Duration.ofMillis(20), t -> false, logged::add);

        assertTrue(result.failed());
        assertTrue(result.detail().contains("CGIPortal.InternalError"), result.detail());
        assertTrue(result.detail().contains("Uploaded file exceeds size limit"), result.detail());
        assertEquals(2, jar.calls.size(), "gemreg once, root access once, no retry");
        assertEquals(List.of("push_commander_root_access attempt 1: failed " + result.detail()), logged);
    }

    @Test
    void theSystemsPackageIsPushedWhenTheJacocoOneIsTooLarge() throws IOException {
        Files.writeString(dir.resolve("update-all-systems-noPW.tgz"), "x");
        var jar = new Jar(argv -> argv.get(5).endsWith("update-all-jacoco-noPW.tgz")
                ? new Jars.Result(500, "", TOO_LARGE_FAULT)
                : new Jars.Result(0, "", ""));

        StepResult result = RootAccess.pushCommanderRootAccess(tools(), jar, HOST, T);

        assertEquals(StepResult.Status.DONE, result.status());
        assertTrue(result.detail().contains("update-all-systems-noPW.tgz"), result.detail());
        assertEquals(List.of("update_pearl-controller_gemreg-force-registered.tgz",
                "update-all-jacoco-noPW.tgz", "update-all-systems-noPW.tgz"),
                jar.calls.stream().map(c -> Path.of(c.get(5)).getFileName().toString()).toList());
    }

    @Test
    void aPushTheJarTimedOutOnCountsOnceRootSshAnswers() {
        var jar = new Jar(argv -> {
            if (isGemreg(argv)) {
                return new Jars.Result(0, "", "");
            }
            throw new IOException("timed out after 5000 ms: java");
        });
        Iterator<Boolean> answers = List.of(false, true).iterator();

        StepResult result = RootAccess.pushUntilConfirmed(tools(), jar, commander(), T, Duration.ofSeconds(5),
                Duration.ofMillis(20), t -> answers.next(), line -> { });

        assertEquals(StepResult.Status.DONE, result.status());
        assertTrue(result.detail().contains("confirmed over SSH"), result.detail());
        assertEquals(2, jar.calls.size(), "gemreg and root access once; nothing re-sent after the boot");
    }

    @Test
    void aRegisterPushIsConfirmedOnlyByItsOwnSuccessLine() {
        var jar = new Jar(argv -> new Jars.Result(0, "java.net.SocketTimeoutException", ""));

        StepResult result = RootAccess.pushRegisterRootAccess(tools(), jar, "192.0.2.101", T);

        assertTrue(result.failed());
        assertEquals("NetLoaderClient.jar: java.net.SocketTimeoutException", result.detail());
        assertEquals("192.0.2.101", jar.calls.get(0).get(3));
    }

    @Test
    void aRegisterPushSucceedsOnTheJarsSuccessLine() {
        var jar = new Jar(argv -> new Jars.Result(0, "progress\n" + RootAccess.NETLOADER_SUCCESS + "\n", ""));

        assertEquals(StepResult.Status.DONE, RootAccess.pushRegisterRootAccess(tools(), jar, "192.0.2.101", T).status());
    }

    @Test
    void aJarThatCannotRunFailsTheStepWithItsReason() {
        var jar = new Jar(argv -> {
            throw new IOException("Cannot run program \"java\"");
        });

        StepResult result = RootAccess.pushRegisterRootAccess(tools(), jar, "192.0.2.101", T);

        assertTrue(result.failed());
        assertTrue(result.detail().contains("Cannot run program"), result.detail());
    }

    @Test
    void theBypassSwitchIsSetOnTheCommander() {
        var box = new InMemoryTransport(HOST);

        assertEquals(StepResult.Status.DONE, RootAccess.setBypassMemSizeCheck(box, T).status());
        assertTrue(box.ran().get(0).contains("bypass.mem.size.check=true"), box.ran().get(0));
        assertTrue(box.ran().get(0).contains("/home/coresvcs/config/private_system.prop"));
    }

    @Test
    void aBypassCommandThatExitsNonZeroFails() {
        var box = new InMemoryTransport(HOST);
        box.command(findBypassCommand(box), new CommandResult(1, "", "sed: no such file"));

        StepResult result = RootAccess.setBypassMemSizeCheck(box, T);

        assertTrue(result.failed());
        assertTrue(result.detail().startsWith("exit 1"), result.detail());
    }

    @Test
    void aDroppedConnectionFailsTheBypassStep() {
        var box = new InMemoryTransport(HOST).failRunsWith(new DeviceUnreachable("ssh " + HOST, "reset"));

        assertTrue(RootAccess.setBypassMemSizeCheck(box, T).failed());
    }

    @Test
    void onlyAHardFaultStopsTheRetry() {
        assertTrue(RootAccess.isHardFault(TOO_LARGE_FAULT));
        assertFalse(RootAccess.isHardFault("java.net.SocketTimeoutException"));
        assertFalse(RootAccess.isHardFault(null));
    }

    /** The bypass command is the first one a fresh target runs; {@link InMemoryTransport} keys replies by text. */
    private static String findBypassCommand(InMemoryTransport box) {
        InMemoryTransport probe = new InMemoryTransport(HOST);
        RootAccess.setBypassMemSizeCheck(probe, T);
        return probe.ran().get(0);
    }

    private static Target commander() {
        return new Target(Target.Role.COMMANDER, new com.verifone.cat.provision.transport.SshTarget(
                "commander", HOST, 22, "root", null));
    }
}
