package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.build.Installed;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 4 over recording stages: the Python step's order, before and after the inits, the skips a default lab run
 * takes, the unavailable gate an onboarding run stops at, the day close, the pinpad link and the WARN-only rules. No
 * stage here reaches a device.
 */
class CommanderConfigurationStepTest {

    @TempDir
    Path dir;

    private int runs;

    /** Records each stage it is asked to run, and answers as told. */
    private static class Stages implements CommanderStages {

        final List<String> ran = new ArrayList<>();
        boolean provision = true;
        boolean prep = true;
        boolean init = true;
        boolean ready = true;
        boolean tunnelPorted = true;
        boolean csiteReset = true;
        boolean emvPorted = true;
        boolean capture = true;
        boolean driftFixed;
        String restart = "";
        CloseDay.Outcome close = CloseDay.Outcome.CLOSED;
        Path pinpadLog;
        /** emv-keys without --tables (the link's) writes the EPS request line from this attempt on; 0 never. */
        int linkOnAttempt = 1;
        int linkAttempts;

        @Override
        public boolean provision(Rig rig, Say say) {
            ran.add("provision");
            say.out("provision report");
            if (!provision) {
                say.err("provision: a target is not provisioned (its report is above)");
            }
            return provision;
        }

        @Override
        public boolean commanderPrep(Rig rig, Say say) {
            ran.add("commander-prep");
            return prep;
        }

        @Override
        public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
            ran.add("commander-init forecourt=" + forecourt);
            if (!init) {
                say.err("Commander syslog, FCC/TWS/COR errors:\n(none in the last 5000 lines)");
                say.err("commander init on commander.invalid failed: DeviceError: cfuelinit: CGIPortal.InternalError");
            }
            return init;
        }

        @Override
        public boolean paymentReady(Rig rig, Say say) {
            ran.add("payment-ready");
            if (!ready) {
                say.err("payment-ready: NOT ready after 900s: payment controller down");
            }
            return ready;
        }

        @Override
        public boolean cloudTunnelPorted() {
            return tunnelPorted;
        }

        @Override
        public boolean cloudTunnel(Rig rig, Say say) {
            if (!tunnelPorted) {
                return CommanderStages.super.cloudTunnel(rig, say);
            }
            ran.add("cloud-tunnel");
            return true;
        }

        @Override
        public boolean commanderConnect(Rig rig, CommanderConnect settings, Say say) {
            ran.add("commander-connect " + settings.serviceId());
            return true;
        }

        @Override
        public boolean csiteReset(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
            ran.add("csite-reset " + serviceId);
            if (!csiteReset) {
                say.err("csite-reset: C-Site answered 503");
            }
            return csiteReset;
        }

        @Override
        public boolean cloudSnapshotRestore(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
            ran.add("cloud-snapshot restore " + serviceId);
            return true;
        }

        @Override
        public boolean secondaryFep(Rig rig, List<String> feps, String serviceId, Map<String, String> csiteEnv,
                Say say) {
            ran.add("secondary-fep " + feps);
            return true;
        }

        @Override
        public boolean pinpadClear(Rig rig, Say say) {
            ran.add("pinpad-clear");
            return true;
        }

        @Override
        public boolean paymentLinks(Rig rig, Say say) {
            ran.add("payment-links");
            if (driftFixed) {
                say.out(CommanderLinks.FEP_DRIFT_FIXED + " (sslEnabled: true, wanted false)");
                driftFixed = false;
            }
            return true;
        }

        @Override
        public boolean emvKeys(Rig rig, String tables, Say say) {
            if (!emvPorted) {
                return CommanderStages.super.emvKeys(rig, tables, say);
            }
            ran.add("emv-keys " + tables);
            if (tables == null && pinpadLog != null) {
                linkAttempts++;
                if (linkOnAttempt > 0 && linkAttempts >= linkOnAttempt) {
                    append(pinpadLog, "INFO DeviceRequest Receieved from EPS\n");
                }
            }
            return true;
        }

        @Override
        public boolean registerApp(Rig rig, Say say) {
            ran.add("register-app");
            return true;
        }

        @Override
        public CloseDay.Outcome closeDay(Rig rig, Say say) {
            ran.add("close-day");
            if (close != CloseDay.Outcome.CLOSED) {
                say.err("close day on commander.invalid failed: CGIPortal.InternalError");
            }
            return close;
        }

        @Override
        public boolean snapshotCapture(String rigName, String buildLabel, Say say) {
            ran.add("commander-snapshot capture " + rigName + " " + buildLabel);
            if (!capture) {
                say.err("capture: 10 of 127 views faulted; not published");
            }
            return capture;
        }

        @Override
        public Path pinpadLog(Rig rig) {
            return pinpadLog;
        }

        @Override
        public String restartSimulator(RunContext ctx, String simulator, StepEmitter step) {
            ran.add("restart " + simulator);
            return restart;
        }

        @Override
        public PinpadLink.Timing pinpadTiming() {
            return new PinpadLink.Timing(Duration.ZERO, Duration.ZERO, Duration.ofMillis(1));
        }

        @Override
        public Pauser pauser() {
            return duration -> { };
        }

        // The install half (InstallCommanderBuild and the psi-init insertion points).
        Path root = Path.of("builds-root-unused");
        boolean download = true;
        boolean install = true;
        boolean psiNoPairing = true;
        boolean psiPairing = true;
        Optional<Path> newest = Optional.empty();
        Installed.Identification installed;

        @Override
        public Path buildsRoot() {
            return root;
        }

        @Override
        public boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
            ran.add("build " + branch + " " + build + " " + dist);
            if (!download) {
                say.err("build: Solutions 134 holds no buypass folder ()");
            }
            return download;
        }

        @Override
        public Optional<Path> newestBuild(String dist) {
            ran.add("newest " + dist);
            return newest;
        }

        @Override
        public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
            ran.add("upgrade " + root.relativize(buildDir).toString().replace('\\', '/'));
            if (!install) {
                say.err("upgrade: install_commander failed: 'Installation Failed'");
            }
            return install;
        }

        @Override
        public boolean psiInit(Rig rig, boolean pair, Say say) {
            ran.add(pair ? "psi-init" : "psi-init --no-pairing");
            boolean ok = pair ? psiPairing : psiNoPairing;
            if (!ok) {
                say.err("psi-init failed: changepasswd still faulting after 6 attempts");
            }
            return ok;
        }

        @Override
        public Installed.Identification installedBuild(Rig rig, String dist) {
            if (installed == null) {
                throw new DeviceError("could not read suiteinfo.dat");
            }
            return installed;
        }
    }

    private static void append(Path log, String text) {
        try {
            Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RunContext context(LaunchConfig config, boolean withRig) throws IOException {
        Path folder = dir.resolve("run" + runs++);
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "cccccccccccc", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        if (withRig) {
            ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        }
        return ctx;
    }

    private record Outcome(boolean keepGoing, StepEmitter step, Map<String, String> rows, Map<String, String> details,
            List<String> logs, RunContext ctx) {}

    private Outcome run(RunContext ctx, Stages stages, Map<String, String> environ) throws IOException {
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);
        boolean keepGoing = new CommanderConfigurationStep(stages, environ).run(ctx, step);
        ctx.closeEvents();
        Map<String, String> rows = new LinkedHashMap<>();
        Map<String, String> details = new LinkedHashMap<>();
        List<String> logs = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            String type = event.get("type").asText();
            if (type.equals("step.check")) {
                rows.put(event.get("label").asText(), event.get("outcome").asText());
                details.put(event.get("label").asText(), event.get("detail").asText());
            } else if (type.equals("log.line")) {
                logs.add(event.get("message").asText());
            }
        }
        return new Outcome(keepGoing, step, rows, details, logs, ctx);
    }

    private Outcome run(LaunchConfig config, Stages stages, Map<String, String> environ, boolean withRig)
            throws IOException {
        return run(context(config, withRig), stages, environ);
    }

    private Outcome run(LaunchConfig config, Stages stages) throws IOException {
        return run(config, stages, Map.of(), true);
    }

    private static String label(String command) {
        return CommanderConfigurationStep.label(command);
    }

    private static LaunchConfig withPetro() {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
    }

    private static LaunchConfig onboarding(CommanderConnect settings) {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).commanderConnect(true).commanderConnect(settings).build();
    }

    // -- before the inits -----------------------------------------------------------------------------------------

    @Test
    void aFakePosRunTouchesNothingAndTheStepIsSkipped() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder("").fakePos(true).build(), stages, Map.of(), false);

        assertTrue(out.keepGoing());
        assertEquals(StepResult.SKIPPED, out.step().state());
        assertEquals("existing build, upgrade mode 'skip', provision not requested", out.step().detail());
        assertTrue(stages.ran.isEmpty());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("provision")));
    }

    @Test
    void aClassicInstallGoesToTheUpgradeStageBeforeAnythingElseRuns() throws IOException {
        // The classic install is InstallCommanderBuild's own stage (its tests drive it); a recorder has none to give.
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.CLASSIC).build(), stages);
        assertFalse(out.keepGoing());
        assertEquals(StepResult.FAILED, out.step().state());
        assertTrue(out.step().detail().contains("installUpgrade (classic)"), out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("upgrade")));
        assertTrue(stages.ran.isEmpty());
    }

    @Test
    void theStepMarksTheCommanderReachedForTheDayClose() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        assertFalse(ctx.commanderReached());
        run(ctx, new Stages(), Map.of());
        assertTrue(ctx.commanderReached());
    }

    @Test
    void aFailedProvisionFailsTheStepWithItsLastErrorLineAndStopsThere() throws IOException {
        Stages stages = new Stages();
        stages.provision = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("provision"), stages.ran);
        assertEquals("provision: a target is not provisioned (its report is above)", out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("provision")));
    }

    @Test
    void aFailedInitIsTheStepsReasonInItsOwnWords() throws IOException {
        Stages stages = new Stages();
        stages.init = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertFalse(out.keepGoing());
        assertEquals("commander init on commander.invalid failed: DeviceError: cfuelinit: CGIPortal.InternalError",
                out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("commander-init")));
        assertEquals(List.of("provision", "commander-prep", "commander-init forecourt=false"), stages.ran);
    }

    @Test
    void aFailedPrepStopsBeforeTheInits() throws IOException {
        Stages stages = new Stages();
        stages.prep = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);
        assertFalse(out.keepGoing());
        assertEquals(List.of("provision", "commander-prep"), stages.ran);
        assertEquals("commander-prep failed", out.step().detail());
    }

    @Test
    void coverageAskedForIsUnavailableBeforeProvision() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).coverage(true).build(), stages);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().contains("JaCoCo coverage's launch wiring"), out.step().detail());
    }

    @Test
    void aRigRunWithNoLoadedRigFails() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages, Map.of(), false);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().startsWith("the rig file did not load"));
    }

    @Test
    void theSnapshotRestoreIsTheInitsOwnSoTheStepWarnsNothingAboutIt() throws IOException {
        // commander-init restores the snapshot itself (SnapshotRestore); the step adds no row or warning for it.
        for (Map<String, String> environ : List.of(Map.<String, String>of(),
                Map.of(CommanderInit.NO_SNAPSHOT_ENV, "1"))) {
            Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), new Stages(), environ, true);
            assertFalse(out.rows().keySet().stream().anyMatch(r -> r.toLowerCase().contains("snapshot restore")),
                    out.rows().toString());
            assertFalse(out.logs().stream().anyMatch(l -> l.contains("snapshot") && l.contains("unavailable")),
                    out.logs().toString());
        }
    }

    @Test
    void patsPumpPortIsLentToTheInitAndToTheCloseAndHeldAgainAfterEach() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        List<String> events = new ArrayList<>();
        ctx.petroPortOpener(port -> {
            events.add("open " + port);
            return () -> events.add("close " + port);
        });
        PetroPorts.hold(ctx, new StepEmitter(ctx, LaunchStepNames.SIMULATORS)); // step 3's holds
        events.clear();
        Stages stages = new Stages() {
            @Override
            public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
                events.add("init holds 1072=" + ctx.petroSinks().containsKey(1072));
                return super.commanderInit(rig, forecourt, say);
            }

            @Override
            public CloseDay.Outcome closeDay(Rig rig, Say say) {
                events.add("close-day holds 1072=" + ctx.petroSinks().containsKey(1072));
                return super.closeDay(rig, say);
            }
        };
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals(List.of("close 1072", "init holds 1072=false", "open 1072", "close 1072",
                "close-day holds 1072=false", "open 1072"), events);
        assertTrue(ctx.petroSinks().containsKey(1072));
        assertTrue(ctx.petroSinks().containsKey(9706), "the UI port is never lent");
    }

    @Test
    void petroSelectedSendsTheForecourtInits() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "");
        Stages stages = new Stages();
        stages.pinpadLog = log;
        run(withPetro(), stages);
        assertTrue(stages.ran.contains("commander-init forecourt=true"), stages.ran.toString());
    }

    // -- after the inits ------------------------------------------------------------------------------------------

    @Test
    void aDefaultLabRunRunsEveryStageAfterTheInitsInThePythonsOrderAndCompletes() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertEquals(List.of("provision", "commander-prep", "commander-init forecourt=false", "secondary-fep [none]",
                "payment-links", "emv-keys auto", "register-app", "close-day", "payment-ready"), stages.ran);
        assertTrue(out.keepGoing());
        assertEquals(StepResult.COMPLETE, out.step().state(), out.step().detail());

        for (String command : List.of("provision", "commander-prep", "commander-init", "secondary-fep",
                "payment-links", "emv-keys", "register-app", "close-day", "payment-ready")) {
            assertEquals(SubstepOutcome.PASSED, out.rows().get(label(command)), command);
        }
        assertEquals("day closed", out.details().get(label("close-day")));
        // No onboarding asked for: the Commander Connect row is skipped with the reason, nothing cloud-side runs.
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("commander-connect")));
        assertEquals(CommanderConfigurationStep.NO_ONBOARDING, out.details().get(label("commander-connect")));
        assertFalse(out.rows().containsKey("cloud-tunnel"));
        // A viperpay rig: no pinpad clear. Petro not selected: no pinpad link.
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get("pinpad-clear"));
        assertEquals("the rig's pinpad is viperpay, not vats", out.details().get("pinpad-clear"));
        assertFalse(out.rows().containsKey(PinpadLink.LABEL));
        // No fresh install, no capture asked for.
        assertFalse(out.rows().containsKey("commander-snapshot"));
        assertEquals(SubstepOutcome.WARNED, out.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));
    }

    @Test
    void theRunsSecondaryFepsAreTheOnesSwitchedOn() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderOptions(new RunContext.CommanderOptions(List.of("Comdata", "WEX-OTR"), false, ""));
        Stages stages = new Stages();
        run(ctx, stages, Map.of());
        assertTrue(stages.ran.contains("secondary-fep [Comdata, WEX-OTR]"), stages.ran.toString());
    }

    @Test
    void onboardingAskedForStopsUnavailableAtTheTunnelBeforeAnythingRuns() throws IOException {
        Stages stages = new Stages();
        stages.tunnelPorted = false;
        Outcome out = run(onboarding(new CommanderConnect("QAT", "lab@example.com", "1234567", "pw")), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of(), stages.ran);
        assertFalse(out.ctx().commanderReached(), "nothing touched the Commander");
        assertEquals(StepResult.FAILED, out.step().state());
        assertTrue(out.step().detail().startsWith("unavailable in CAT-Java: needs the cloud tunnel's serve loop"),
                out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get("cloud-tunnel"));
        assertFalse(out.rows().containsKey(label("commander-init")));
    }

    @Test
    void theProductionTunnelIsUnavailable() {
        StageUnavailable gone = assertThrows(StageUnavailable.class,
                () -> CommanderStages.real(Map.of()).cloudTunnel(null, null));
        assertTrue(gone.missing().contains("cloudtunnel"), gone.missing());
    }

    @Test
    void onceTheTunnelIsPortedOnboardingRunsConnectCsiteAndTheCloudRestoreInThePythonsOrder() throws IOException {
        Stages stages = new Stages();
        stages.csiteReset = false;
        Outcome out = run(onboarding(new CommanderConnect("QAT", "lab@example.com", "1234567", "pw")), stages);

        assertEquals(List.of("provision", "commander-prep", "cloud-tunnel", "commander-init forecourt=false",
                "commander-connect 1234567", "csite-reset 1234567", "cloud-snapshot restore 1234567",
                "secondary-fep [none]", "payment-links", "emv-keys auto", "register-app", "close-day",
                "payment-ready"), stages.ran);
        // csite-reset's failure leaves its row failed and the step going.
        assertTrue(out.keepGoing());
        assertEquals(StepResult.COMPLETE, out.step().state(), out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get("csite-reset"));
        assertTrue(out.logs().contains("WARN  csite-reset — csite-reset: C-Site answered 503"), out.logs().toString());
    }

    @Test
    void onboardingWithoutACsiteAccountSkipsTheCloudRestore() throws IOException {
        Stages stages = new Stages();
        run(onboarding(new CommanderConnect("QAT", "", "1234567", "")), stages);
        assertTrue(stages.ran.contains("commander-connect 1234567"), stages.ran.toString());
        assertFalse(stages.ran.stream().anyMatch(s -> s.startsWith("csite-reset")), stages.ran.toString());
    }

    @Test
    void anEmvKeysStageThatIsNotThereStopsTheStepThere() throws IOException {
        Stages stages = new Stages();
        stages.emvPorted = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertFalse(out.keepGoing());
        assertEquals("payment-links", stages.ran.get(stages.ran.size() - 1));
        assertTrue(out.step().detail().startsWith("unavailable in CAT-Java: needs the EMV tables and CA key download"),
                out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("emv-keys")));
    }

    @Test
    void theProductionEmvKeysStageIsWiredAndAFaultIsAFailedStageNotUnavailable() {
        // The real stage reaches commander.emv.EmvKeys; with no rig it faults, which is a failed stage.
        Say.Recorded say = new Say.Recorded();
        assertFalse(CommanderStages.real(Map.of()).emvKeys(null, "auto", say));
        assertTrue(say.lastErr().startsWith("emv-keys: "), say.errText());
    }

    @Test
    void aDriftFixRestartsACatStartedBuypassHostAndChecksTheLinksAgain() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.addSimulator(FakeSims.spawned(CommanderConfigurationStep.PAYMENT_HOST));
        Stages stages = new Stages();
        stages.driftFixed = true;
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals(List.of("payment-links", "restart BUYPASS Host", "payment-links", "emv-keys auto"),
                stages.ran.subList(4, 8));
    }

    @Test
    void aDriftFixWithAnAdoptedBuypassHostIsAWarnAndNoRestart() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.addSimulator(FakeSims.adopted(CommanderConfigurationStep.PAYMENT_HOST));
        Stages stages = new Stages();
        stages.driftFixed = true;
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing());
        assertFalse(stages.ran.contains("restart BUYPASS Host"));
        assertEquals(1, stages.ran.stream().filter("payment-links"::equals).count());
        assertTrue(out.logs().stream().anyMatch(l -> l.startsWith("WARN  BUYPASS Host was not started by CAT")),
                out.logs().toString());
    }

    @Test
    void aBuypassHostThatDoesNotComeBackFailsTheStep() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.addSimulator(FakeSims.spawned(CommanderConfigurationStep.PAYMENT_HOST));
        Stages stages = new Stages();
        stages.driftFixed = true;
        stages.restart = "BUYPASS Host did not come back: failed — port 41107 not bound";
        Outcome out = run(ctx, stages, Map.of());

        assertFalse(out.keepGoing());
        assertEquals("BUYPASS Host did not come back: failed — port 41107 not bound", out.step().detail());
        assertFalse(stages.ran.contains("emv-keys auto"));
    }

    @Test
    void aDayThatDoesNotCloseFailsTheStepBeforeThePinpadLinkAndPaymentReady() throws IOException {
        Stages stages = new Stages();
        stages.close = CloseDay.Outcome.FORCED_AEOD;
        Outcome out = run(withPetro(), stages);

        assertFalse(out.keepGoing());
        // Step 4's close is not Cleanup's: a forced-AEOD refusal is a failure here.
        assertEquals("close day FAILED (close day on commander.invalid failed: CGIPortal.InternalError)",
                out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(DayClose.LABEL));
        assertEquals("close-day", stages.ran.get(stages.ran.size() - 1));
        assertFalse(out.rows().containsKey(PinpadLink.LABEL));
    }

    @Test
    void aPsiInstallThatDidNotReachPsiInitSkipsTheCloseAndGoesOn() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderLoginReady(false);
        Stages stages = new Stages();
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing());
        assertFalse(stages.ran.contains("close-day"));
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(DayClose.LABEL));
        assertEquals("PSI install did not reach psi-init, manager password not reset",
                out.details().get(DayClose.LABEL));
    }

    // -- the pinpad link ------------------------------------------------------------------------------------------

    @Test
    void anEpsThatAlreadyReachedPetroSinceStepThreeNeedsNoDownload() throws IOException {
        RunContext ctx = context(withPetro(), true);
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "old line\n");
        ctx.epsLogMark(Files.size(log));
        append(log, "INFO DeviceRequest Receieved from EPS\n");
        Stages stages = new Stages();
        stages.pinpadLog = log;
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals(SubstepOutcome.PASSED, out.rows().get(PinpadLink.LABEL));
        assertFalse(stages.ran.contains("emv-keys null"), stages.ran.toString());
        assertTrue(out.logs().contains("PASS  the Commander's EPS reached Petro Simulator Suite; no key download "
                + "needed"), out.logs().toString());
        // In the Python's order: the close, then the link, then payment-ready.
        assertEquals(List.of("close-day", "payment-ready"), stages.ran.subList(stages.ran.size() - 2,
                stages.ran.size()));
    }

    @Test
    void aSilentEpsGetsTheKeyDownloadAndTheNewRequestLineLinksIt() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "INFO DeviceRequest Receieved from EPS\n");
        RunContext ctx = context(withPetro(), true);
        ctx.epsLogMark(Files.size(log));
        Stages stages = new Stages();
        stages.pinpadLog = log;
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals(List.of("close-day", "emv-keys null", "payment-ready"),
                stages.ran.subList(stages.ran.size() - 3, stages.ran.size()));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(PinpadLink.LABEL));
        assertTrue(out.logs().contains("CAT-Java emv-keys --rig bench (attempt 1)"), out.logs().toString());
    }

    @Test
    void aStillSilentEpsRestartsTheSimulatorOnceThenFails() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "");
        Stages stages = new Stages();
        stages.pinpadLog = log;
        stages.linkOnAttempt = 0;
        Outcome out = run(withPetro(), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("close-day", "emv-keys null", "restart Petro Simulator Suite", "emv-keys null"),
                stages.ran.subList(stages.ran.size() - 4, stages.ran.size()));
        assertEquals("the EPS never reached Petro Simulator Suite, even after a restart", out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(PinpadLink.LABEL));
        assertFalse(stages.ran.contains("payment-ready"));
    }

    @Test
    void theRestartSucceedsOnTheSecondAttempt() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "");
        Stages stages = new Stages();
        stages.pinpadLog = log;
        stages.linkOnAttempt = 2;
        Outcome out = run(withPetro(), stages);
        assertTrue(out.keepGoing(), out.step().detail());
        assertTrue(stages.ran.contains("restart Petro Simulator Suite"));
    }

    @Test
    void anAdoptedPetroIsNotRestartedAndTheLinkFailsSayingSo() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "");
        RunContext ctx = context(withPetro(), true);
        ctx.addSimulator(FakeSims.adopted(StartSimulatorsStep.PETRO_SIMULATOR));
        Stages stages = new Stages() {
            @Override
            public String restartSimulator(RunContext c, String simulator, StepEmitter step) {
                ran.add("restart " + simulator);
                return CommanderStages.real(Map.of()).restartSimulator(c, simulator, step);
            }
        };
        stages.pinpadLog = log;
        stages.linkOnAttempt = 0;
        Outcome out = run(ctx, stages, Map.of());

        assertFalse(out.keepGoing());
        assertEquals(CommanderStages.notStartedByCat(StartSimulatorsStep.PETRO_SIMULATOR), out.step().detail());
        assertEquals(1, stages.ran.stream().filter("emv-keys null"::equals).count());
    }

    @Test
    void aPinpadSimulatorWithNoLogCannotConfirmTheLink() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(withPetro(), stages);
        assertFalse(out.keepGoing());
        assertEquals("'Petro Simulator Suite' has no registry row with a log to confirm the link", out.step().detail());
    }

    @Test
    void theRealPinpadLogIsPetrosRegistryLogUnderTheToolsRoot() {
        Path tools = dir.resolve("tools");
        Path log = CommanderStages.real(Map.of("CAT_TOOLS_ROOT", tools.toString())).pinpadLog(null);
        assertEquals(tools.resolve("Petro_Simulator_Suite").resolve("log4j-application.log"), log);
    }

    // -- after payment-ready ------------------------------------------------------------------------------------

    @Test
    void onceEverythingRanPaymentReadyIsAWarnNeverAFailure() throws IOException {
        Stages stages = new Stages();
        stages.ready = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertTrue(out.keepGoing());
        assertEquals(StepResult.COMPLETE, out.step().state());
        assertEquals("payment-ready", stages.ran.get(stages.ran.size() - 1));
        assertEquals(SubstepOutcome.WARNED, out.rows().get(label("payment-ready")));
        assertEquals("payment-ready: NOT ready after 900s: payment controller down",
                out.details().get(label("payment-ready")));
    }

    @Test
    void aCaptureAskedForRunsAfterPaymentReadyAndItsFailureIsOnlyAWarn() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderOptions(new RunContext.CommanderOptions(List.of(), true, "Solutions134"));
        Stages stages = new Stages();
        stages.capture = false;
        Outcome out = run(ctx, stages, Map.of());

        assertTrue(out.keepGoing());
        assertEquals(StepResult.COMPLETE, out.step().state(), out.step().detail());
        assertEquals("commander-snapshot capture bench Solutions134", stages.ran.get(stages.ran.size() - 1));
        assertEquals("payment-ready", stages.ran.get(stages.ran.size() - 2));
        assertEquals(SubstepOutcome.FAILED, out.rows().get("commander-snapshot"));
        assertTrue(out.logs().contains("WARN  commander-snapshot capture failed; see its lines above"));
    }

    // ------------------------------------------------------------ the clean install

    private static LaunchConfig psi(String branch, String build) {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch(branch).commanderBuild(build).build();
    }

    @Test
    void aNewInstallOrdersDownloadInstallResetProvisionPairProvisionThenPrep() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Outcome out = run(psi("Solutions", "134"), stages);

        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass", "psi-init --no-pairing",
                "provision", "psi-init", "provision", "commander-prep", "commander-init forecourt=false",
                "secondary-fep [none]", "payment-links", "emv-keys always", "register-app", "close-day",
                "payment-ready", "commander-snapshot capture bench Solutions134"), stages.ran);
        assertTrue(out.keepGoing(), out.step().detail());
        assertTrue(out.ctx().commanderReached());
        assertTrue(out.ctx().commanderLoginReady());
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("build")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("upgrade")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("psi-init")));
        assertEquals("Download the Commander build", label("build"));
        assertEquals("Install the Commander build", label("upgrade"));
        assertEquals("PSI init (Commander password, register pairing)", label("psi-init"));
    }

    @Test
    void aNewInstallAskingForWhatCatJavaCannotDoStopsBeforeTheDownloadAndTheInstall() throws IOException {
        // Coverage and the onboarding's cloud tunnel would end the step after the PSI install wiped both devices.
        LaunchConfig.Builder base = new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch("Solutions").commanderBuild("134");
        Stages coverage = new Stages();
        coverage.root = dir.resolve("builds");
        Outcome covered = run(base.coverage(true).build(), coverage);
        assertFalse(covered.keepGoing());
        assertEquals(List.of(), coverage.ran);
        assertEquals(SubstepOutcome.FAILED, covered.rows().get(label("coverage")));
        assertTrue(covered.step().detail().contains("JaCoCo coverage's launch wiring"), covered.step().detail());
        assertFalse(covered.rows().containsKey(label("build")));

        Stages tunnel = new Stages();
        tunnel.root = dir.resolve("builds");
        tunnel.tunnelPorted = false;
        Outcome onboarded = run(new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch("Solutions").commanderBuild("134").commanderConnect(true).build(), tunnel);
        assertFalse(onboarded.keepGoing());
        assertEquals(List.of(), tunnel.ran);
        assertEquals(SubstepOutcome.FAILED, onboarded.rows().get("cloud-tunnel"));
        assertTrue(onboarded.ctx().commanderLoginReady(), "no install started");
    }

    @Test
    void theProductionTunnelIsNotPortedSoTheGapIsCheckedUpFront() {
        assertFalse(CommanderStages.real(Map.of()).cloudTunnelPorted());
        LaunchConfig onboarding = new LaunchConfig.Builder(LaunchFixtures.RIG).commanderConnect(true).coverage(true)
                .build();
        assertEquals(List.of(label("coverage"), "cloud-tunnel"), CommanderConfigurationStep.unported(onboarding, false)
                .stream().map(CommanderConfigurationStep.Gap::row).toList());
        assertEquals(List.of(label("coverage")), CommanderConfigurationStep.unported(onboarding, true).stream()
                .map(CommanderConfigurationStep.Gap::row).toList());
        assertEquals(List.of(), CommanderConfigurationStep.unported(new LaunchConfig.Builder("").fakePos(true)
                .commanderConnect(true).coverage(true).build(), false));
    }

    @Test
    void aBuildAlreadyDownloadedIsInstalledWithoutAskingArtifactory() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Path buypass = Files.createDirectories(stages.root.resolve("Solutions134").resolve("buypass"));
        for (String name : List.of("overlay.zip", "Petro_Suite_Installer.jar", "Auto_Upgrade.jar")) {
            Files.writeString(buypass.resolve(name), "x", StandardCharsets.UTF_8);
        }
        Outcome out = run(psi("Solutions", "134"), stages);

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals("upgrade Solutions134/buypass", stages.ran.get(0), stages.ran.toString());
        assertFalse(stages.ran.stream().anyMatch(s -> s.startsWith("build ")), stages.ran.toString());
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("build")));
        assertTrue(out.details().get(label("build")).startsWith("already downloaded: "),
                out.details().get(label("build")));
        assertTrue(out.details().get(label("build")).endsWith("Artifactory not asked"),
                out.details().get(label("build")));
    }

    @Test
    void aBuildMissingOneFileIsDownloaded() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Path buypass = Files.createDirectories(stages.root.resolve("Solutions134").resolve("buypass"));
        Files.writeString(buypass.resolve("overlay.zip"), "x", StandardCharsets.UTF_8);
        Files.writeString(buypass.resolve("Petro_Suite_Installer.jar"), "x", StandardCharsets.UTF_8);
        Files.write(buypass.resolve("Auto_Upgrade.jar"), new byte[0]); // empty is not downloaded
        run(psi("Solutions", "134"), stages);

        assertEquals("build Solutions 134 buypass", stages.ran.get(0), stages.ran.toString());
    }

    @Test
    void aFailedPasswordResetLeavesTheLoginNotReadyAndStopsBeforeProvision() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.psiNoPairing = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass", "psi-init --no-pairing"),
                stages.ran);
        assertFalse(out.ctx().commanderLoginReady(), "the day close must not log in with the lab password");
        assertTrue(out.ctx().commanderReached());
        assertEquals("psi-init failed: changepasswd still faulting after 6 attempts", out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("psi-init")));
    }

    @Test
    void aFailedPairingStopsTheStepButThePasswordIsAlreadyReset() throws IOException {
        // As the Python: the --no-pairing reset already made the login usable; pairing failing does not undo that.
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.psiPairing = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals("psi-init", stages.ran.get(stages.ran.size() - 1));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void aFailedInstallStopsWithTheLoginNotReady() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.install = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass"), stages.ran);
        assertFalse(out.ctx().commanderLoginReady());
        assertEquals("upgrade: install_commander failed: 'Installation Failed'", out.step().detail());
    }

    @Test
    void aFailedDownloadInstallsNothingAndLeavesTheLoginAlone() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.download = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass"), stages.ran);
        assertTrue(out.ctx().commanderLoginReady(), "nothing was installed, so the password is still the lab's");
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("build")));
    }

    @Test
    void noBuildChosenInstallsTheNewestAlreadyDownloaded() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.newest = Optional.of(stages.root.resolve("Solutions90").resolve("buypass"));
        Outcome out = run(psi("", ""), stages);

        assertEquals(List.of("newest buypass", "upgrade Solutions90/buypass", "psi-init --no-pairing", "provision",
                "psi-init", "provision", "commander-prep", "commander-init forecourt=false"),
                stages.ran.subList(0, 8));
        // No build chosen, so the capture has no build label.
        assertEquals("commander-snapshot capture bench ", stages.ran.get(stages.ran.size() - 1));
        assertFalse(out.rows().containsKey(label("build")));
    }

    @Test
    void noBuildChosenAndNoneDownloadedFailsNamingTheRoot() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Outcome out = run(psi("", ""), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("newest buypass"), stages.ran);
        assertTrue(out.step().detail().startsWith("no build under " + stages.root + " holds buypass"),
                out.step().detail());
    }

    @Test
    void aChosenBuildWithModeSkipDownloadsNothing() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).commanderBranch("Solutions")
                .commanderBuild("134").build(), stages);

        assertEquals("provision", stages.ran.get(0));
        assertEquals("Solutions 134: Upgrade mode is Skip, nothing to install", out.details().get(label("build")));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void vhqFailsAtTheUpgradeStageBeforeAnythingElseRuns() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.VHQ).build(), stages);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().contains("installUpgrade (vhq)"), out.step().detail());
    }

    @Test
    void aFakePosNewInstallInstallsNothing() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder("").fakePos(true).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch("Solutions").commanderBuild("134").build(), stages, Map.of(), false);

        assertTrue(out.keepGoing());
        assertTrue(stages.ran.isEmpty(), stages.ran.toString());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("upgrade")));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void theInstalledBuildIsReadAndFallsBackToTheBuildThisRunInstalled() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.installed = new Installed.Identification("Solutions134", "Solutions134 (Commit_Hash abc, built t)");
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);
        assertEquals(SubstepOutcome.PASSED, out.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));
        assertEquals("Solutions134", out.ctx().installedBuild());

        Stages unread = new Stages();
        unread.root = dir.resolve("builds");
        Outcome installedHere = run(psi("Solutions", "134"), unread);
        assertEquals("Solutions134", installedHere.ctx().installedBuild());
        assertEquals("Solutions134, installed by this run (DeviceError: could not read suiteinfo.dat)",
                installedHere.details().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));

        Stages unknown = new Stages();
        Outcome notHere = run(LaunchConfig.forRig(LaunchFixtures.RIG), unknown);
        assertEquals("", notHere.ctx().installedBuild());
        assertEquals(SubstepOutcome.WARNED, notHere.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));
    }

    @Test
    void theDownloadsFieldsDefaultEmptyAndAreTrimmed() {
        LaunchConfig plain = LaunchConfig.forRig(LaunchFixtures.RIG);
        assertEquals("", plain.commanderBranch());
        assertEquals("", plain.simBuild());
        assertFalse(plain.commanderBuildChosen());
        LaunchConfig chosen = new LaunchConfig.Builder(LaunchFixtures.RIG).commanderBranch(" Solutions ")
                .commanderBuild("134").simBranch("master").simBuild("74").build();
        assertTrue(chosen.commanderBuildChosen());
        assertEquals("Solutions", chosen.commanderBranch());
        assertEquals("master", chosen.simBranch());
        assertEquals("74", chosen.simBuild());
    }

    @Test
    void theProductionStepIsTheOneTheLauncherRuns() {
        CommanderConfigurationStep step = new CommanderConfigurationStep();
        assertEquals(LaunchStepNames.COMMANDER, step.name());
        assertThrows(StageUnavailable.class, () -> CommanderStages.real(Map.of()).pinpadClear(null, null));
        assertThrows(StageUnavailable.class,
                () -> CommanderStages.real(Map.of()).cloudSnapshotRestore(null, "1", Map.of(), null));
    }
}
