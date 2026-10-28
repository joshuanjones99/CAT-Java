package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.recovery.Timeouts;

/**
 * {@code provision_rig} end to end against {@link RecordingTransport} and fake probes: the hash guard, the push,
 * verification, the checklist and the stamp. No host is reached. UNVERIFIED AGAINST HARDWARE (Java port).
 */
class ProvisionRunnerTest {

    private static final String RIG_NAME = "bench";
    private static final String STAMP = "/home/topaz/config/.petrotest-provision.json";
    private static final String COMMANDER_STAMP = "/home/common/config/.petrotest-provision.json";
    private static final String SHIM_RESTART = FakeShim.SHIM_RESTART;
    private static final String POS_RESTART_MARK = "/etc/init.d/apps";
    private static final String RMI_KEYSTORE = "test -f /home/common/x509/rmi_keystore.jks";
    private static final Instant WHEN = Instant.parse("2026-10-24T11:40:05Z");

    @TempDir
    Path dir;

    /** Probes that record what was asked, in order. */
    static final class FakeProbe implements AgentProbe {
        final List<String> calls = new ArrayList<>();
        private final boolean shim;
        private final BooleanSupplier rmi;

        FakeProbe(boolean shim, BooleanSupplier rmi) {
            this.shim = shim;
            this.rmi = rmi;
        }

        @Override
        public boolean shimAnswers(Duration timeout) {
            calls.add("shim");
            return shim;
        }

        @Override
        public boolean rmiAgentAnswers(Duration timeout) {
            calls.add("rmi");
            return rmi.getAsBoolean();
        }
    }

    /** A checklist step standing in for {@code EnsurePosShimRunning} on a freshly started shim. */
    static final class StartedFresh implements ChecklistStep {
        @Override
        public String name() {
            return "ensure_pos_shim_running";
        }

        @Override
        public StepResult apply(com.verifone.cat.provision.transport.Transport transport, String controllerHost,
                Duration timeout) {
            return new StepResult(name(), StepStatus.DONE, "started");
        }
    }

    /** A checklist step standing in for {@code EnsurePosShimRunning} when the shim was already running. */
    static final class AlreadyRunning implements ChecklistStep {
        @Override
        public String name() {
            return "ensure_pos_shim_running";
        }

        @Override
        public StepResult apply(com.verifone.cat.provision.transport.Transport transport, String controllerHost,
                Duration timeout) {
            return new StepResult(name(), StepStatus.ALREADY, "pid 1");
        }
    }

    /** One rig's fake boxes, reused across runs so the stamps persist. */
    final class Harness {
        final Map<String, RecordingTransport> transports = new HashMap<>();
        final Map<String, FakeProbe> probes = new HashMap<>();
        final Map<String, byte[]> content = new HashMap<>();
        final Rig rig;

        Harness() {
            this(RigFixture.RIG);
        }

        Harness(String yaml) {
            this.rig = RigFixture.load(dir, yaml);
        }

        RecordingTransport transport(String name) {
            return transports.computeIfAbsent(name, n -> new RecordingTransport(
                    n.equals("commander") ? "commander.invalid" : "register.invalid"));
        }

        ProvisionOptions options() {
            return new ProvisionOptions()
                    .read(file -> content.containsKey(file.source()) ? content.get(file.source())
                            : ("content-of-" + file.source()).getBytes(StandardCharsets.UTF_8))
                    .transports(spec -> transport(spec.name()))
                    .probes(spec -> spec.role() == TargetRole.REGISTER
                            ? probes.computeIfAbsent(spec.name(), n -> new FakeProbe(true, () -> true))
                            : new AgentProbe.Null())
                    .timeouts(Timeouts.defaults())
                    .toolVersion("test")
                    .now(WHEN)
                    .settle(Duration.ofSeconds(2))
                    .poll(Duration.ofMillis(20));
        }

        ProvisionReport run(ProvisionOptions options) {
            return ProvisionRunner.provisionRig(rig, options);
        }

        ProvisionReport run() {
            return run(options());
        }
    }

    private static TargetReport target(ProvisionReport report, TargetRole role) {
        return report.targets().stream().filter(t -> t.role() == role).findFirst().orElseThrow();
    }

    private static List<String> stepNames(TargetReport target) {
        return target.checklist().stream().map(StepResult::step).toList();
    }

    @Test
    void theFirstRunProvisionsEveryTargetAndVerifies() {
        Harness harness = new Harness();

        ProvisionReport report = harness.run();

        assertTrue(report.ok(), report.render());
        assertEquals(List.of("commander (commander)", "topaz101 (register)"),
                report.targets().stream().map(TargetReport::target).toList());
        assertEquals(TargetReport.Decision.PROVISIONED, target(report, TargetRole.REGISTER).decision());
        assertEquals(TargetReport.Decision.PROVISIONED, target(report, TargetRole.COMMANDER).decision());

        List<String> registerPuts = harness.transport("topaz101").puts();
        assertTrue(registerPuts.contains("/home/topaz/automation/petrotest-pos-shim.jar"));
        assertTrue(registerPuts.contains("/home/topaz/config/private_system.prop"));
        assertTrue(registerPuts.contains("/home/common/filebeat-8.2.2-linux-x86_64/filebeat.yml"));
        assertTrue(registerPuts.contains("/home/common/lib/jacocoagent.jar"));
        List<String> commanderPuts = harness.transport("commander").puts();
        assertTrue(commanderPuts.contains("/home/coresvcs/config/private_system.prop"));
        assertFalse(commanderPuts.stream().anyMatch(p -> p.contains("automation")));
        assertTrue(registerPuts.contains(STAMP), "the stamp is written last");
        assertEquals(COMMANDER_STAMP, commanderPuts.get(commanderPuts.size() - 1));

        assertEquals(List.of("shim", "rmi"), harness.probes.get("topaz101").calls);
    }

    @Test
    void aSecondRunIsANoOpThatWritesNothingAndIsSubTwoSeconds() {
        Harness harness = new Harness();
        assertTrue(harness.run().ok());
        for (RecordingTransport t : harness.transports.values()) {
            t.clear();
        }

        ProvisionReport report = harness.run();

        assertTrue(report.didNothing(), report.render());
        assertTrue(report.targets().stream().allMatch(t -> t.elapsedSeconds() < ProvisionRunner.NOOP_BUDGET.toSeconds()));
        assertEquals(List.of(), harness.transport("topaz101").puts());
        assertEquals(List.of(), harness.transport("commander").puts());
    }

    @Test
    void forceOverridesTheGuard() {
        Harness harness = new Harness();
        assertTrue(harness.run().ok());

        ProvisionReport report = harness.run(harness.options().force(true));

        assertTrue(report.ok());
        assertEquals(TargetReport.Decision.PROVISIONED, target(report, TargetRole.REGISTER).decision());
        assertFalse(harness.transport("topaz101").puts().isEmpty());
    }

    @Test
    void aChangedPayloadRePushesAndNamesTheDriftWhileTheCommanderStaysOnTheFastPath() {
        Harness harness = new Harness();
        assertTrue(harness.run().ok());

        harness.content.put("props/private_system.prop", "new content".getBytes(StandardCharsets.UTF_8));
        ProvisionReport report = harness.run();

        TargetReport register = target(report, TargetRole.REGISTER);
        assertEquals(TargetReport.Decision.PROVISIONED, register.decision());
        assertTrue(register.drift().contains("/home/topaz/config/private_system.prop"), register.drift().toString());
        assertEquals(TargetReport.Decision.UP_TO_DATE, target(report, TargetRole.COMMANDER).decision());
    }

    @Test
    void aSilentlyDroppedFileIsCaughtAndNoStampIsWritten() {
        Harness harness = new Harness();
        harness.transport("topaz101").inner.silentlyDrop("/home/common/lib/jacocoagent.jar");

        ProvisionReport report = harness.run();

        TargetReport register = target(report, TargetRole.REGISTER);
        assertEquals(TargetReport.Decision.FAILED, register.decision());
        assertTrue(register.error().contains("absent after push"), register.error());
        assertFalse(harness.transport("topaz101").puts().contains(STAMP), "no stamp for a rig that did not provision");
        assertFalse(report.ok());
        assertFalse(harness.run().targets().stream().filter(t -> t.role() == TargetRole.REGISTER)
                .allMatch(t -> t.decision() == TargetReport.Decision.UP_TO_DATE));
    }

    @Test
    void aDeadShimFailsProvisioningEvenThoughEveryFileLanded() {
        Harness harness = new Harness();
        harness.probes.put("topaz101", new FakeProbe(false, () -> false));

        TargetReport register = target(harness.run(), TargetRole.REGISTER);

        assertEquals(TargetReport.Decision.FAILED, register.decision());
        assertTrue(register.error().contains("HTTP shim did not answer"), register.error());
        assertFalse(register.pushed().isEmpty(), "the files did land; that is the point");
    }

    @Test
    void aDeadRmiAgentBehindALiveShimIsReportedDistinctly() {
        Harness harness = new Harness();
        harness.probes.put("topaz101", new FakeProbe(true, () -> false));

        TargetReport register = target(harness.run(), TargetRole.REGISTER);

        assertTrue(register.error().contains("RMI agent did not answer"), register.error());
    }

    @Test
    void aCrlfShellScriptFailsItsTargetBeforeAnythingIsPushed() {
        Harness harness = new Harness();
        harness.content.put("pos/petrotest-pos-shim.sh", "#!/bin/sh\r\nexit 0\r\n".getBytes(StandardCharsets.UTF_8));

        TargetReport register = target(harness.run(), TargetRole.REGISTER);

        assertEquals(TargetReport.Decision.FAILED, register.decision());
        assertTrue(register.error().contains("CRLF line endings"), register.error());
        assertEquals(List.of(), harness.transport("topaz101").puts());
    }

    @Test
    void theDummyRootCertRunsForRealOnTheCommander() {
        Harness harness = new Harness();

        TargetReport commander = target(harness.run(), TargetRole.COMMANDER);

        assertEquals(StepStatus.DONE, commander.checklist().stream()
                .filter(s -> s.step().equals("dummy_root_cert")).findFirst().orElseThrow().status());
        List<String> ran = harness.transport("commander").ran();
        assertTrue(ran.contains("mkdir -p /home/common/data/security/trustStoreDir"));
        assertTrue(ran.contains("touch /home/common/data/security/trustStoreDir/root_ca.crt"));
        assertTrue(ran.stream().anyMatch(c -> c.startsWith("chown coresvcs:vfi")));
    }

    @Test
    void aFailingDummyRootCertCommandFailsTheCommander() {
        Harness harness = new Harness();
        harness.transport("commander").inner.command("mkdir -p /home/common/data/security/trustStoreDir",
                new CommandResult(1, "", "denied"));

        ProvisionReport report = harness.run();

        TargetReport commander = target(report, TargetRole.COMMANDER);
        assertFalse(commander.ok());
        assertTrue(commander.checklist().stream().anyMatch(s -> s.step().equals("dummy_root_cert")
                && s.status() == StepStatus.FAILED && s.detail().contains("exited 1")));
    }

    @Test
    void theStubsAreReportedSkippedAndTheRegisterHasNoChecklist() {
        ProvisionReport report = new Harness().run();

        TargetReport commander = target(report, TargetRole.COMMANDER);
        assertEquals(List.of("dummy_root_cert", "buypass_fep_umf", "table_download"), stepNames(commander));
        assertEquals(StepStatus.SKIPPED, commander.checklist().get(1).status());
        assertTrue(commander.checklist().get(1).detail().contains("41107"));
        assertTrue(report.ok(), "a skipped step is visible, not a failure");
        assertEquals(List.of(), target(report, TargetRole.REGISTER).checklist());
    }

    @Test
    void aFreshlyStartedShimGetsThePosRestartedAndTheAgentsAskedAfter() {
        Harness harness = new Harness();
        RecordingTransport register = harness.transport("topaz101");
        harness.probes.put("topaz101", new FakeProbe(true, () -> register.ran().stream()
                .anyMatch(c -> c.contains(POS_RESTART_MARK))));
        ProvisionOptions options = harness.options().posShimChecklist(List.of(new StartedFresh()));

        ProvisionReport report = harness.run(options);

        TargetReport target = target(report, TargetRole.REGISTER);
        assertEquals(TargetReport.Decision.PROVISIONED, target.decision(), target.error());
        assertEquals(List.of("ensure_pos_shim_running", "restart_pos_after_shim_start"), stepNames(target));
        assertTrue(register.ran().stream().anyMatch(c -> c.contains("/etc/init.d/apps stop")));
        List<String> calls = harness.probes.get("topaz101").calls;
        assertEquals("rmi", calls.get(calls.size() - 1));
    }

    @Test
    void aShimAlreadyRunningRestartsNothing() {
        Harness harness = new Harness();

        ProvisionOptions options = harness.options().posShimChecklist(List.of(new AlreadyRunning()));
        TargetReport target = target(harness.run(options), TargetRole.REGISTER);

        assertEquals(TargetReport.Decision.PROVISIONED, target.decision(), target.error());
        assertEquals(List.of("ensure_pos_shim_running"), stepNames(target));
        assertFalse(harness.transport("topaz101").ran().stream().anyMatch(c -> c.contains(POS_RESTART_MARK)));
    }

    @Test
    void anUnpairedRegisterIsPushedAndStampedButItsAgentsAreNotAsked() {
        Harness harness = new Harness();
        RecordingTransport register = harness.transport("topaz101");
        register.inner.command(RMI_KEYSTORE, new CommandResult(1, "", ""));
        harness.probes.put("topaz101", new FakeProbe(false, () -> false));
        ProvisionOptions options = harness.options().posShimChecklist(List.of(new StartedFresh()));

        TargetReport first = target(harness.run(options), TargetRole.REGISTER);

        assertEquals(TargetReport.Decision.PROVISIONED, first.decision(), first.error());
        assertEquals(List.of(), harness.probes.get("topaz101").calls);
        assertTrue(first.checklist().stream().anyMatch(s -> s.step().equals("agents_after_pairing")
                && s.status() == StepStatus.SKIPPED && s.detail().contains("psi-init")));
        assertFalse(register.ran().stream().anyMatch(c -> c.contains(POS_RESTART_MARK)));

        // psi-init pairs the register; the next provision is a stamp match that finds the keystore, and restarts the POS.
        register.inner.command(RMI_KEYSTORE, new CommandResult(0, "", ""));
        harness.probes.put("topaz101", new FakeProbe(true, () -> register.ran().stream()
                .anyMatch(c -> c.contains(POS_RESTART_MARK))));
        TargetReport second = target(harness.run(options), TargetRole.REGISTER);
        assertEquals(TargetReport.Decision.UP_TO_DATE, second.decision(), second.error());
        assertTrue(register.ran().stream().anyMatch(c -> c.contains("/etc/init.d/apps stop")));
    }

    @Test
    void aStampMatchOnAnUnpairedRegisterSkipsThePosRestart() {
        Harness harness = new Harness();
        RecordingTransport register = harness.transport("topaz101");
        register.inner.command(RMI_KEYSTORE, new CommandResult(1, "", ""));
        harness.probes.put("topaz101", new FakeProbe(false, () -> false));
        ProvisionOptions options = harness.options().posShimChecklist(List.of(new StartedFresh()));
        harness.run(options);

        TargetReport rerun = target(harness.run(options), TargetRole.REGISTER);

        assertEquals(TargetReport.Decision.UP_TO_DATE, rerun.decision(), rerun.error());
        assertTrue(rerun.checklist().stream().anyMatch(s -> s.step().equals("agents_after_pairing")
                && s.status() == StepStatus.SKIPPED));
        assertFalse(register.ran().stream().anyMatch(c -> c.contains(POS_RESTART_MARK)));
        assertEquals(List.of(), harness.probes.get("topaz101").calls);
    }

    @Test
    void theRenderedReportNamesTheRigEachTargetAndTheTotal() {
        ProvisionReport report = new Harness().run();

        String text = report.render();

        assertTrue(text.startsWith("provision bench"), text);
        assertTrue(text.contains("commander (commander)") && text.contains("topaz101 (register)"), text);
        assertTrue(text.contains("  total ") && text.contains(" - OK"), text);
    }

    /** A register checklist step that records each run and answers {@code status}. */
    static final class FixedStep implements ChecklistStep {
        int calls;
        private final String name;
        private final StepStatus status;
        private final boolean required;

        FixedStep(String name, StepStatus status, boolean required) {
            this.name = name;
            this.status = status;
            this.required = required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public StepResult apply(com.verifone.cat.provision.transport.Transport transport, String controllerHost,
                Duration timeout) {
            calls++;
            return new StepResult(name, status, "said " + status.wire());
        }
    }

    private static TargetReport named(ProvisionReport report, String prefix) {
        return report.targets().stream().filter(t -> t.target().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void theRegisterChecklistRunsOnlyOnTheOptedInRegisterAndOnTheFastPathToo() {
        Harness harness = new Harness(RigFixture.TWO_REGISTERS);
        FixedStep step = new FixedStep("recording_step", StepStatus.DONE, true);
        ProvisionOptions options = harness.options().registerChecklist(List.of(step));

        ProvisionReport first = harness.run(options);

        assertTrue(first.ok(), first.render());
        assertEquals(List.of("recording_step"), stepNames(named(first, "topaz102")));
        assertEquals(List.of(), named(first, "topaz101").checklist());
        assertEquals(1, step.calls);

        ProvisionReport second = harness.run(options);

        assertTrue(second.ok(), second.render());
        assertEquals(TargetReport.Decision.UP_TO_DATE, named(second, "topaz102").decision());
        assertEquals(2, step.calls);
    }

    @Test
    void aRequiredStepThatWasSkippedFailsTheTargetAndTheReport() {
        // Run ac22943e6604 (2026-10-28): the override step was SKIPPED and provision still said PASS.
        Harness harness = new Harness(RigFixture.TWO_REGISTERS);
        ProvisionOptions options = harness.options()
                .registerChecklist(List.of(new FixedStep("enable_browser_override", StepStatus.SKIPPED, true)));

        ProvisionReport report = harness.run(options);

        assertFalse(report.ok(), report.render());
        TargetReport override = named(report, "topaz102");
        assertEquals(TargetReport.Decision.FAILED, override.decision());
        assertTrue(override.error().contains("required checklist step enable_browser_override was skipped"),
                override.error());
        assertEquals(TargetReport.Decision.PROVISIONED, named(report, "topaz101").decision());
        assertTrue(report.render().endsWith("FAILED"), report.render());
    }

    @Test
    void aRequiredStepSkippedOnTheFastPathFailsTheTargetToo() {
        Harness harness = new Harness(RigFixture.TWO_REGISTERS);
        assertTrue(harness.run(harness.options()).ok());

        ProvisionReport second = harness.run(harness.options()
                .registerChecklist(List.of(new FixedStep("enable_browser_override", StepStatus.SKIPPED, true))));

        assertEquals(TargetReport.Decision.FAILED, named(second, "topaz102").decision());
        assertFalse(second.ok());
    }

    @Test
    void aSkipThatIsNotRequiredStillPassesAsInPython() {
        Harness harness = new Harness(RigFixture.TWO_REGISTERS);
        ProvisionOptions options = harness.options()
                .registerChecklist(List.of(new FixedStep("optional", StepStatus.SKIPPED, false)));

        ProvisionReport report = harness.run(options);

        assertTrue(report.ok(), report.render());
    }

    @Test
    void aFailingStepOnTheFastPathStillFailsTheTarget() {
        Harness harness = new Harness(RigFixture.TWO_REGISTERS);
        ProvisionOptions failing = harness.options()
                .registerChecklist(List.of(new FixedStep("failing_step", StepStatus.FAILED, false)));
        harness.run(failing);
        assertTrue(harness.run(harness.options()).ok(), "seeds the stamp");

        ProvisionReport second = harness.run(failing);

        TargetReport override = named(second, "topaz102");
        assertEquals(TargetReport.Decision.FAILED, override.decision());
        assertTrue(override.error().contains("said failed"), override.error());
    }
}
