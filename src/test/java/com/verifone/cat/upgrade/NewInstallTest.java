package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PSI install driver, against scripted fakes: an in-memory SSH target, a jar runner that records argv,
 * and installers that write their breadcrumb as soon as they start. No real host, no real process.
 */
class NewInstallTest {

    private static final String RIG = "/upgrade/rigs/psi.yaml";

    @TempDir
    Path build;

    private Rig rig() throws Exception {
        return RigLoader.loadFile(Path.of(getClass().getResource(RIG).toURI())).rig();
    }

    private NewInstallTools tools() {
        return new NewInstallTools(build, "java", "NetLoaderClient.jar", build.resolve("jacoco.tgz").toString(),
                build.resolve("gemreg.tgz").toString());
    }

    /** The Commander answers {@code cat /proc/uptime} with {@code uptime}; everything else succeeds. */
    private static InMemoryTransport box(String uptime) {
        return new InMemoryTransport("192.0.2.11")
                .command("cat /proc/uptime", new CommandResult(0, uptime + " 0.0\n", ""));
    }

    private static NewInstall.Settings fastSettings() {
        return new NewInstall.Settings(Duration.ofMillis(300), Duration.ofMillis(20), Duration.ofMillis(200),
                Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofSeconds(5), Duration.ofMillis(300));
    }

    private static List<String> stepsOf(List<StepResult> results) {
        return results.stream().map(StepResult::step).collect(Collectors.toList());
    }

    /** A complete install: each installer writes {@code breadcrumb} as it starts. */
    private List<StepResult> run(Jars jars, FakeInstallers installers, InMemoryTransport box,
            boolean cgilinkAnswers, boolean sshPushed) throws Exception {
        NewInstall install = new NewInstall(tools(), target -> box, jars, installers, fastSettings(),
                host -> cgilinkAnswers, target -> sshPushed, line -> { });
        return install.run(rig(), Map.of());
    }

    @Test
    void aFullInstallRunsEveryStepInOrderAndPushesEachDevice() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Complete");
        var results = run(jars, installers, box("0.0"), true, false);

        assertTrue(results.stream().noneMatch(StepResult::failed), results.toString());
        assertEquals(List.of(
                "bypass_mem_size_check",
                "reboot_commander",
                "reboot_topaz101",
                "install_commander",
                "push_commander_root_access",
                "install_topaz101",
                "push_register_root_access",
                "ssh_commander",
                "ssh_topaz101",
                "bypass_mem_size_check",
                "reboot_commander_after_bypass",
                "ssh_commander",
                "cgilink_commander"), stepsOf(results));
        assertEquals(2, installers.ended, "each installer is closed once its breadcrumb has landed");
        assertFalse(installers.killed);
        assertEquals("gemreg.tgz", Path.of(jars.calls.get(0).get(5)).getFileName().toString());
        assertEquals("jacoco.tgz", Path.of(jars.calls.get(1).get(5)).getFileName().toString());
        assertEquals("192.0.2.101", jars.calls.get(2).get(jars.calls.get(2).size() - 2));
    }

    @Test
    void aFailedInstallPushesNothingAndEndsEveryInstaller() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Failed");
        var results = run(jars, installers, box("0.0"), true, false);

        assertEquals("install_commander", results.get(results.size() - 1).step());
        assertTrue(results.get(results.size() - 1).failed());
        assertTrue(jars.calls.isEmpty());
        assertEquals(2, installers.ended, "an early return still ends every installer it started");
    }

    @Test
    void noBreadcrumbInTimePushesNothing() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, null);
        var results = run(jars, installers, box("0.0"), true, false);

        StepResult last = results.get(results.size() - 1);
        assertTrue(last.failed());
        assertTrue(last.detail().contains("psi_complete_CMDR"), last.detail());
        assertTrue(jars.calls.isEmpty());
        assertEquals(2, installers.ended, "neither installer is left running after the timeout");
    }

    @Test
    void aDeviceThatNeverAnswersSshAfterItsPushFailsTheInstall() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Complete");
        NewInstall.Settings quick = new NewInstall.Settings(Duration.ofMillis(300), Duration.ofMillis(20),
                Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(100),
                Duration.ofMillis(300));
        var box = box("9999.0");
        NewInstall install = new NewInstall(tools(), target -> box, jars, installers, quick,
                host -> true, target -> false, line -> { });
        var results = install.run(rig(), Map.of());

        StepResult last = results.get(results.size() - 1);
        assertEquals("ssh_commander", last.step());
        assertTrue(last.failed());
    }

    @Test
    void aCommanderWhoseTomcatNeverAnswersFailsTheLastStep() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Complete");
        var results = run(jars, installers, box("0.0"), false, false);

        StepResult last = results.get(results.size() - 1);
        assertEquals("cgilink_commander", last.step());
        assertTrue(last.failed());
    }

    @Test
    void aRegisterPushThatTheJarDidNotConfirmIsRetriedThenFails() throws Exception {
        var jars = new ScriptedJars("java.net.SocketTimeoutException");
        var installers = new FakeInstallers(build, "Installation Complete");
        var results = run(jars, installers, box("0.0"), true, false);

        StepResult last = results.get(results.size() - 1);
        assertEquals("push_register_root_access", last.step());
        assertTrue(last.failed());
        assertTrue(jars.calls.size() > 2, "the register push is retried, not sent once");
    }

    @Test
    void theInstallerConfigNamesTheDistribution() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Complete");
        run(jars, installers, box("0.0"), true, false);

        String config = Files.readString(build.resolve(LabAnswers.PSI_CONFIG_FILE), StandardCharsets.UTF_8);
        assertTrue(config.contains("dist=" + build.getFileName().toString().toUpperCase()), config);
    }

    @Test
    void anInstallerThatWasStartedAgainstTheRightHostsAndDirectory() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var installers = new FakeInstallers(build, "Installation Complete");
        run(jars, installers, box("0.0"), true, false);

        assertEquals(List.of("-q", "-addr=192.0.2.11"), installers.argv.get(0).subList(3, 5));
        assertEquals(List.of("-q", "-addr=192.0.2.101"), installers.argv.get(1).subList(3, 5));
    }

    @Test
    void theRigsDevicesAreTheCommanderThenEveryRegister() throws Exception {
        List<Target> targets = Target.fromRig(rig(), Map.of());

        assertEquals(List.of("commander", "topaz101"), targets.stream().map(Target::name).toList());
        assertEquals(Target.Role.COMMANDER, targets.get(0).role());
        assertEquals(Target.Role.REGISTER, targets.get(1).role());
    }

    @Test
    void theBreadcrumbIsNamedByTheLastOctet() {
        assertEquals("psi_complete_CMDR", NewInstall.breadcrumbPath(tools(), "192.0.2.11").getFileName().toString());
        assertEquals("psi_complete_Reg101", NewInstall.breadcrumbPath(tools(), "192.0.2.101").getFileName().toString());
    }

    // -- resumeRootAccess: an install that stopped at its root-access push ---------------------------------------

    /** Root answers on a device once a jar has pushed to its host: the package's own effect. */
    private static java.util.function.Predicate<Target> rootAfterPush(ScriptedJars jars, java.util.Set<String> already) {
        return target -> already.contains(target.name())
                || jars.calls.stream().anyMatch(argv -> argv.contains(target.host()));
    }

    private List<StepResult> resume(Jars jars, java.util.function.Predicate<Target> root, InMemoryTransport box,
            boolean cgilinkAnswers, List<String> log) throws Exception {
        NewInstall install = new NewInstall(tools(), target -> box, jars, (argv, cwd, file) -> {
            throw new AssertionError("a resume starts no installer");
        }, fastSettings(), host -> cgilinkAnswers, root, log::add);
        return install.resumeRootAccess(rig(), Map.of());
    }

    @Test
    void aCommanderThatAnswersCgilinkButNotRootSshIsPushedThenTheTailRuns() throws Exception {
        // The lab Commander on 2026-10-29: installed, CGILink up with the PSI password, no root SSH; nor the register.
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        InMemoryTransport box = box("0.0");
        List<String> log = new ArrayList<>();
        var results = resume(jars, rootAfterPush(jars, java.util.Set.of()), box, true, log);

        assertTrue(results.stream().noneMatch(StepResult::failed), results.toString());
        assertEquals(List.of(
                "push_commander_root_access",
                "push_register_root_access",
                "ssh_commander",
                "ssh_topaz101",
                "bypass_mem_size_check",
                "reboot_commander_after_bypass",
                "ssh_commander",
                "cgilink_commander"), stepsOf(results));
        assertEquals("gemreg.tgz", Path.of(jars.calls.get(0).get(5)).getFileName().toString());
        assertEquals("jacoco.tgz", Path.of(jars.calls.get(1).get(5)).getFileName().toString());
        assertEquals("192.0.2.101", jars.calls.get(2).get(jars.calls.get(2).size() - 2));
        assertTrue(box.ran().contains("setenforce 0"), box.ran().toString());
        assertFalse(Files.exists(build.resolve(LabAnswers.PSI_CONFIG_FILE)), "nothing is installed");
    }

    @Test
    void aDeviceWhoseRootAlreadyAnswersIsNeitherPushedNorWaitedFor() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var results = resume(jars, rootAfterPush(jars, java.util.Set.of("topaz101")), box("0.0"), true,
                new ArrayList<>());

        assertEquals(List.of(
                "push_commander_root_access",
                "push_register_root_access",
                "ssh_commander",
                "bypass_mem_size_check",
                "reboot_commander_after_bypass",
                "ssh_commander",
                "cgilink_commander"), stepsOf(results));
        assertEquals("root access confirmed over SSH", results.get(1).detail());
        assertEquals(2, jars.calls.size(), "only the Commander's two packages");
    }

    @Test
    void aHardFaultOnThePushStopsTheResumeBeforeAnyWait() throws Exception {
        Jars faulting = (argv, timeout) -> new Jars.Result(500, "",
                "<faultCode>CGIPortal.InternalError</faultCode><e:message>Uploaded file is corrupt</e:message>");
        InMemoryTransport box = box("0.0");
        var results = resume(faulting, target -> false, box, true, new ArrayList<>());

        assertEquals(List.of("push_commander_root_access"), stepsOf(results));
        assertTrue(results.get(0).failed());
        assertTrue(results.get(0).detail().contains("Uploaded file is corrupt"), results.get(0).detail());
        assertTrue(box.ran().isEmpty(), "no tail: " + box.ran());
    }

    @Test
    void aCommanderWhoseCgilinkNeverComesBackFailsTheTail() throws Exception {
        var jars = new ScriptedJars(RootAccess.NETLOADER_SUCCESS);
        var results = resume(jars, rootAfterPush(jars, java.util.Set.of()), box("0.0"), false, new ArrayList<>());

        StepResult last = results.get(results.size() - 1);
        assertEquals("cgilink_commander", last.step());
        assertTrue(last.failed());
    }

    /** Records every jar call and answers each with the given stdout, exit 0. */
    static final class ScriptedJars implements Jars {
        final List<List<String>> calls = new ArrayList<>();
        private final String stdout;

        ScriptedJars(String stdout) {
            this.stdout = stdout;
        }

        @Override
        public Result run(List<String> argv, Duration timeout) throws IOException {
            calls.add(List.copyOf(argv));
            return new Result(0, stdout, "");
        }
    }

    /**
     * Starts nothing: records the argv, writes the breadcrumb for the host named in {@code -addr=}, and counts
     * how many it was asked to end.
     */
    static final class FakeInstallers implements Installer.Launcher {
        final List<List<String>> argv = new ArrayList<>();
        int ended;
        boolean killed;
        private final Path buildDir;
        private final String breadcrumb;

        FakeInstallers(Path buildDir, String breadcrumb) {
            this.buildDir = buildDir;
            this.breadcrumb = breadcrumb;
        }

        @Override
        public Installer start(List<String> command, Path cwd, Path logFile) throws IOException {
            argv.add(command);
            String host = command.get(command.size() - 1).substring("-addr=".length());
            if (breadcrumb != null) {
                Files.writeString(NewInstall.breadcrumbPath(new NewInstallTools(buildDir, "java", "", "", ""), host),
                        breadcrumb);
            }
            return new Installer() {
                private boolean terminated;

                @Override
                public OptionalInt exitCode() {
                    return OptionalInt.empty();
                }

                @Override
                public void terminate() {
                    terminated = true;
                    ended++;
                }

                @Override
                public void kill() {
                    killed = true;
                }

                @Override
                public boolean awaitExit(Duration timeout) {
                    return terminated;
                }
            };
        }
    }
}
