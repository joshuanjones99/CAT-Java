package com.verifone.cat.launch;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.BuildException;
import com.verifone.cat.build.CommanderBuildFetch;
import com.verifone.cat.build.StoredArtifactoryCredentials;
import com.verifone.cat.build.Installed;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.commander.emv.EmvKeys;
import com.verifone.cat.devices.commander.CommanderSuiteInfo;
import com.verifone.cat.upgrade.NewInstall;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.PsiInit;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.CommanderPrep;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.PaymentReady;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.commander.connect.CommanderConnectCommand;
import com.verifone.cat.commander.connect.ConnectService;
import com.verifone.cat.commander.connect.ConnectTiming;
import com.verifone.cat.commander.connect.PdkShimConnectClient;
import com.verifone.cat.commander.links.PaymentLinks;
import com.verifone.cat.commander.SecondaryFepCli;
import com.verifone.cat.commander.connect.CSiteReset;
import com.verifone.cat.commander.connect.CloudSnapshotCommand;
import com.verifone.cat.commander.registerapp.RegisterAppCommand;
import com.verifone.cat.provision.PosShimBuild;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.commander.snapshot.CommanderSnapshotCommand;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.Stage;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.Events;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The stages of step 4, each the Python step's {@code petrotest <command>} child run in-process: a stage writes its
 * lines to a {@link Say} and returns whether it passed. A stage whose work CAT-Java does not have throws
 * {@link StageUnavailable}, which the step reports as unavailable (a failure). {@link #real} reaches the rig; a test
 * passes recorders, so no step test signs in to a box.
 *
 * <p>Besides the Commander's stages, the seams step 4 reaches outside itself: the pinpad simulator's log, a simulator
 * restart, and the waits of the pinpad link.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
interface CommanderStages {

    /** The builds root, {@code $CAT_BUILDS_ROOT} or {@code C:\CICD\Builds}: where a download lands and an install looks. */
    default Path buildsRoot() {
        throw new UnsupportedOperationException("buildsRoot: not given to these stages");
    }

    /**
     * {@code petrotest build}: {@code overlay.zip} and the PSI jars of {@code branch} build {@code build}'s
     * {@code dist} folder, into {@link #buildsRoot()}. A failure is written to {@code say}'s error stream.
     */
    default boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
        throw new UnsupportedOperationException("downloadCommanderBuild: not given to these stages");
    }

    /**
     * {@code petrotest build --branch B --build N --dist D --mode M [--foundation-branch FB --foundation-build FN]}:
     * a PSI build without a foundation is {@link #downloadCommanderBuild(String, String, String, Say)}; any other is
     * {@link Real}'s ({@code InstallCommanderBuild.realDownload}).
     */
    default boolean downloadCommanderBuild(String branch, String build, String dist, UpgradeMode mode,
            String foundationBranch, String foundationBuild, Say say) {
        boolean foundation = !foundationBranch.isEmpty() && !foundationBuild.isEmpty();
        if (mode == UpgradeMode.NEW_INSTALL && !foundation) {
            return downloadCommanderBuild(branch, build, dist, say);
        }
        throw new UnsupportedOperationException("downloadCommanderBuild (" + mode.wire()
                + (foundation ? ", a foundation" : "") + "): not given to these stages");
    }

    /** {@code petrotest upgrade --mode M --dist D [--build-dir <buildDir>]} for classic or VHQ. */
    default boolean installUpgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Say say) {
        throw new UnsupportedOperationException("installUpgrade (" + mode.wire() + "): not given to these stages");
    }

    /** The newest build under {@link #buildsRoot()} holding {@code dist} with the PSI files, as the Python's upgrade picks. */
    default Optional<Path> newestBuild(String dist) {
        throw new UnsupportedOperationException("newestBuild: not given to these stages");
    }

    /** {@code petrotest upgrade --mode new_install --build-dir <buildDir>}: the PSI install. */
    default boolean installNewInstall(Rig rig, Path buildDir, Say say) {
        throw new UnsupportedOperationException("installNewInstall: not given to these stages");
    }

    /** {@code petrotest psi-init} ({@code --no-pairing} when {@code pair} is false). */
    default boolean psiInit(Rig rig, boolean pair, Say say) {
        throw new UnsupportedOperationException("psiInit: not given to these stages");
    }

    /** {@code petrotest.build.installed.installed_build_name}: the build on the Commander, by commit hash. Never throws. */
    default Installed.Identification installedBuild(Rig rig, String dist) {
        throw new UnsupportedOperationException("installedBuild: not given to these stages");
    }

    /** {@code petrotest provision}: the pos-shim payload pushed, verified and stamped on every target. */
    boolean provision(Rig rig, Say say);

    /** {@code petrotest commander-prep}: the license dates and the Gemreg marker. */
    boolean commanderPrep(Rig rig, Say say);

    /** {@code petrotest commander-init} ({@code --no-forecourt} when {@code forecourt} is false). */
    boolean commanderInit(Rig rig, boolean forecourt, Say say);

    /** {@code petrotest payment-ready}: the EPS ready for card sales. */
    boolean paymentReady(Rig rig, Say say);

    /** The Python module the cloud tunnel lives in. */
    String CLOUD_TUNNEL_MODULE = "src/cat/launch/tunnel.py";

    /** What the cloud tunnel needs that these stages do not have. */
    String CLOUD_TUNNEL_MISSING = "the cloud tunnel's start (launch.TunnelStart over cat-java cloud-tunnel), "
            + "which a run onboarding to Commander Connect starts before the inits; the lab Commander has no WAN link";

    /**
     * The cloud tunnel, adopted or started before the inits when the run onboards to Commander Connect
     * ({@link TunnelStart}).
     *
     * @param rigName the rig as the run names it, which the started {@code cloud-tunnel} loads
     */
    default boolean cloudTunnel(String rigName, Rig rig, Say say) {
        throw new StageUnavailable(CLOUD_TUNNEL_MODULE, CLOUD_TUNNEL_MISSING);
    }

    /**
     * Whether {@link #cloudTunnel} is there to run. Asked before step 4 installs anything, so a run onboarding to
     * Commander Connect stops before a PSI install wipes the devices, not after. True for {@link Real}.
     */
    default boolean cloudTunnelPorted() {
        return false;
    }

    /** {@code petrotest commander-connect}: onboards the Commander. */
    default boolean commanderConnect(Rig rig, CommanderConnect settings, Say say) {
        throw new StageUnavailable(CommanderConfigurationStep.PYTHON_MODULE, "petrotest commander-connect");
    }

    /** {@code petrotest csite-reset}: the smart suggestions and tips deleted through C-Site. */
    default boolean csiteReset(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
        throw new StageUnavailable("src/petrotest/devices/csite.py", "petrotest csite-reset (csite_reset_cli)");
    }

    /** {@code petrotest cloud-snapshot restore}: the committed cloud golden pushed down. */
    default boolean cloudSnapshotRestore(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
        throw new StageUnavailable("src/petrotest/cloud_snapshot.py",
                "the petrotest cloud-snapshot restore command (cloud_snapshot_cli: the golden folder and C-Site "
                        + "client wiring around CloudSnapshot.restore)");
    }

    /** {@code petrotest secondary-fep --fep ...} ({@code none} when {@code feps} is empty). */
    default boolean secondaryFep(Rig rig, List<String> feps, String serviceId, Map<String, String> csiteEnv,
            Say say) {
        throw new StageUnavailable("src/petrotest/secondary_fep.py", "petrotest secondary-fep");
    }

    /**
     * {@code petrotest pinpad-clear}: a simulated card out of a {@code pinpad: vats} rig's M400. The vats slice's:
     * {@link Real} leaves it unavailable until that command exists (hook: override it in {@link Real} with the
     * vats {@code pinpad-clear} logic, its lines on {@code say}).
     */
    default boolean pinpadClear(Rig rig, Say say) {
        throw new StageUnavailable("src/petrotest/devices/vats/pinpad.py", "petrotest pinpad-clear (pinpad_clear_cli)");
    }

    /** {@code petrotest payment-links}: the Buypass FEP and ViperPay links, with POP 1 and the FEP config. */
    default boolean paymentLinks(Rig rig, Say say) {
        throw new StageUnavailable("src/petrotest/commander_links.py", "petrotest payment-links");
    }

    /**
     * {@code petrotest emv-keys [--tables T]}: EPS Network Functions' table load ({@code always}, {@code auto}, or null
     * for none), then the EMV CA key download.
     */
    default boolean emvKeys(Rig rig, String tables, Say say) {
        throw new StageUnavailable("src/petrotest/pos/emv_keys.py",
                "the EMV tables and CA key download (petrotest emv-keys; commander.emv.EmvKeys in the real stages)");
    }

    /**
     * {@code petrotest emv-keys --tables T [--host-packets F]}: {@link #emvKeys(Rig, String, Say)} with the host packet
     * file a FEP simulator superseding BUYPASS Host writes; null is BUYPASS Host's own.
     */
    default boolean emvKeys(Rig rig, String tables, Path hostPackets, Say say) {
        return emvKeys(rig, tables, say);
    }

    /**
     * The Python's {@code host_packet_args(ctx)}: the capture file of a FEP simulator that serves BUYPASS Host's
     * traffic this run ({@link StartSimulatorsStep#hostPackets}), or null for BUYPASS Host's own {@code packetFile.txt}.
     */
    default Path hostPackets(RunContext ctx) {
        return StartSimulatorsStep.hostPackets(ctx).orElse(null);
    }

    /**
     * The pos-shim payload folder ({@code payload/pos}) step 4 builds before provision, or null when these stages
     * build none (a test's recorder): step 4 then shows no {@code pos-shim build} row.
     */
    default Path posShimPayload() {
        return null;
    }

    /** The Python CAT checkout the shim sources and the stamp's recipe are read from. */
    default Path posShimRepoRoot() {
        throw new UnsupportedOperationException("posShimRepoRoot: not given to these stages");
    }

    /**
     * {@code tools/build_pos_shim.py --rig R --petroapps P} ({@link PosShimBuild}): the payload built from the
     * sources, the vendor jars fetched from the rig's register when the cache lacks one. A failure is said on
     * {@code say}'s error stream.
     */
    default boolean buildPosShim(Rig rig, Path petroapps, Say say) {
        throw new UnsupportedOperationException("buildPosShim: not given to these stages");
    }

    /** {@code petrotest register-app}: every register back in the cashier POS app. */
    default boolean registerApp(Rig rig, Say say) {
        throw new StageUnavailable("src/petrotest/register_app.py", "petrotest register-app");
    }

    /** {@code petrotest close-day}, as {@link DayClose} runs it. */
    default CloseDay.Outcome closeDay(Rig rig, Say say) {
        throw new StageUnavailable("src/petrotest/close_day.py", "petrotest close-day");
    }

    /**
     * {@code petrotest commander-snapshot capture --rig R --if-missing --twice [--build-label L]}.
     *
     * @param rigName the rig's name, as the command loads it
     */
    default boolean snapshotCapture(String rigName, String buildLabel, Say say) {
        throw new StageUnavailable("src/petrotest/commander_snapshot.py", "petrotest commander-snapshot capture");
    }

    /** The pinpad simulator's log, from its registry row, or null when the row names none. */
    default Path pinpadLog(Rig rig) {
        return null;
    }

    /**
     * Stops the CAT-started instance of {@code simulator} and starts it again: the Python's {@code _restart_simulator}.
     *
     * @return "" when it is ready again, else the step's failure detail
     */
    default String restartSimulator(RunContext ctx, String simulator, StepEmitter step) {
        return simulator + " cannot be restarted from here";
    }

    /** The pinpad link's waits. */
    default PinpadLink.Timing pinpadTiming() {
        return PinpadLink.Timing.PRODUCTION;
    }

    /** The pinpad link's pauses. */
    default Pauser pauser() {
        return Pauser.REAL;
    }

    /**
     * How step 4 opens the register's printer port for its hold ({@link PrinterHold}). None by default, so a test's
     * recorder never opens a real serial port; {@link Real} opens {@link com.verifone.cat.devices.printer.TopazPrinter}.
     */
    default PrinterHold.Opener printerOpener() {
        return PrinterHold.Opener.NONE;
    }

    /** The Artifactory client's per-request budget for a build download, the web Downloads page's. */
    Duration ARTIFACTORY_TIMEOUT = Duration.ofMinutes(10);

    /** The real stages, against the rig's devices, with {@code environ} for keys and paths. */
    static CommanderStages real(Map<String, String> environ) {
        return new Real(environ);
    }

    /** The production stages. */
    final class Real implements CommanderStages {

        /** The Python's {@code SIM_START_TIMEOUT}, for a restart. */
        static final Duration SIM_START_TIMEOUT = Duration.ofSeconds(60);

        private final Map<String, String> environ;

        Real(Map<String, String> environ) {
            this.environ = environ == null ? Map.of() : environ;
        }

        @Override
        public Path buildsRoot() {
            return BuildDiscovery.buildsRoot(environ);
        }

        @Override
        public PrinterHold.Opener printerOpener() {
            return PrinterHold.Opener.TOPAZ;
        }

        @Override
        public boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
            ArtifactoryClient client = new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                    StoredArtifactoryCredentials.forEnvironment(environ), ARTIFACTORY_TIMEOUT);
            try {
                CommanderBuildFetch.fetch(client, branch, build, dist, CommanderBuildFetch.PSI_FILES,
                        buildsRoot(), say::out);
                return true;
            } catch (BuildException e) {
                say.err("build: " + e.getMessage());
                return false;
            }
        }

        @Override
        public boolean downloadCommanderBuild(String branch, String build, String dist, UpgradeMode mode,
                String foundationBranch, String foundationBuild, Say say) {
            boolean foundation = !foundationBranch.isEmpty() && !foundationBuild.isEmpty();
            if (mode == UpgradeMode.NEW_INSTALL && !foundation) {
                return downloadCommanderBuild(branch, build, dist, say);
            }
            return InstallCommanderBuild.realDownload(branch, build, dist, mode, foundationBranch, foundationBuild,
                    buildsRoot(), environ, say);
        }

        @Override
        public boolean installUpgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Say say) {
            return InstallCommanderBuild.realInstall(rig, mode, buildDir, dist, buildsRoot(), environ, say);
        }

        @Override
        public Optional<Path> newestBuild(String dist) {
            return BuildDiscovery.latestBuildDir(buildsRoot(), dist, CommanderBuildFetch.PSI_FILES);
        }

        @Override
        public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
            NewInstallTools tools;
            try {
                tools = NewInstallTools.forBuild(buildDir, environ);
            } catch (IllegalArgumentException e) {
                say.err("upgrade: " + e.getMessage());
                return false;
            }
            List<com.verifone.cat.upgrade.StepResult> results =
                    NewInstall.system(tools, say::out).run(rig, environ);
            for (com.verifone.cat.upgrade.StepResult result : results) {
                if (result.failed()) {
                    say.err("upgrade: " + result.step() + " failed: " + result.detail());
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean psiInit(Rig rig, boolean pair, Say say) {
            return PsiInit.forRig(rig, environ, say).run(rig, pair);
        }

        @Override
        public Installed.Identification installedBuild(Rig rig, String dist) {
            CommanderAccess access = access(rig);
            return Installed.installedBuildName(
                    () -> CommanderSuiteInfo.read(access::transport, access.sshTimeout()), dist, buildsRoot());
        }

        /**
         * {@code petrotest emv-keys [--tables T]}: {@link EmvKeys#cli} with no {@code --host-packets}, so BUYPASS Host's
         * {@code packetFile.txt} is the host log it reads. Any fault is a failed stage, its line on {@code say}.
         */
        @Override
        public boolean emvKeys(Rig rig, String tables, Say say) {
            return emvKeys(rig, tables, null, say);
        }

        /** {@link EmvKeys#cli} with {@code --host-packets} when a FEP simulator serves the payment host. */
        @Override
        public boolean emvKeys(Rig rig, String tables, Path hostPackets, Say say) {
            try {
                return new EmvKeys(rig, access(rig), EmvKeys::connectRegister, say).cli(tables, hostPackets);
            } catch (RuntimeException e) {
                say.err("emv-keys: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }

        @Override
        public Path posShimPayload() {
            return PosShimBuild.defaultPayload(environ);
        }

        @Override
        public Path posShimRepoRoot() {
            return ProvisionPaths.pythonRoot(environ);
        }

        /**
         * {@code python tools/build_pos_shim.py --rig R --petroapps P}, in-process: the missing vendor jars from the
         * rig's first register, then {@link PosShimBuild#build} with this JVM's javac.
         */
        @Override
        public boolean buildPosShim(Rig rig, Path petroapps, Say say) {
            Path deps = PosShimBuild.defaultDeps(environ);
            try {
                if (PosShimBuild.checkDepsMissing(deps)) {
                    PosShimBuild.pullDepsFromRig(rig, environ, deps, JschTransport::new, say::out);
                }
                PosShimBuild.build(new PosShimBuild.Options(posShimRepoRoot(), petroapps, deps, posShimPayload(),
                        false), PosShimBuild.javac(), say::out);
                return true;
            } catch (PosShimBuild.BuildError e) {
                say.err("ERROR: " + e.getMessage());
                return false;
            }
        }

        @Override
        public boolean cloudTunnelPorted() {
            return true;
        }

        /** {@link TunnelStart}: adopt a running {@code cloud-tunnel} for the rig, or start one and wait for "up". */
        @Override
        public boolean cloudTunnel(String rigName, Rig rig, Say say) {
            return TunnelStart.start(rigName, rig, environ, Path.of("").toAbsolutePath(), TunnelStart.system(environ),
                    say);
        }

        /** {@code petrotest csite-reset --service-id ID}, the run's C-Site account in its environment. */
        @Override
        public boolean csiteReset(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
            Map<String, String> env = merged(csiteEnv);
            return CSiteReset.cli(() -> CSiteReset.LIVE.open(rig, env), serviceId, say);
        }

        /** {@code petrotest cloud-snapshot restore --service-id ID}: the committed golden, then the settle. */
        @Override
        public boolean cloudSnapshotRestore(Rig rig, String serviceId, Map<String, String> csiteEnv, Say say) {
            Map<String, String> env = merged(csiteEnv);
            return CloudSnapshotCommand.cli("restore", rig, serviceId, Path.of("tests", "suites"),
                    CloudSnapshotCommand.goldenDir(env), env, CloudSnapshotCommand.LIVE, say);
        }

        /** {@code environ} with the run's {@code CAT_CSITE_*} account over it (the Python's {@code env=csite}). */
        private Map<String, String> merged(Map<String, String> csiteEnv) {
            if (csiteEnv == null || csiteEnv.isEmpty()) {
                return environ;
            }
            Map<String, String> env = new java.util.HashMap<>(environ);
            env.putAll(csiteEnv);
            return env;
        }

        @Override
        public boolean provision(Rig rig, Say say) {
            ProvisionOptions options = ProvisionOptions.defaults()
                    .payloadRoot(ProvisionPaths.payloadRoot(environ))
                    .stageSources(Stage.Sources.fromEnv(environ))
                    .environ(environ)
                    .toolVersion("cat-java");
            ProvisionReport report;
            try {
                report = ProvisionRunner.provisionRig(rig, options);
            } catch (IllegalArgumentException e) {
                say.err("provision: " + e.getMessage());
                return false;
            }
            say.out(report.render());
            if (!report.ok()) {
                say.err("provision: a target is not provisioned (its report is above)");
            }
            return report.ok();
        }

        @Override
        public boolean commanderPrep(Rig rig, Say say) {
            return CommanderPrep.run(access(rig), LocalDate.now(), say);
        }

        @Override
        public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
            return CommanderInit.forRig(access(rig), say).run(null, forecourt, true);
        }

        @Override
        public boolean paymentReady(Rig rig, Say say) {
            return PaymentReady.run(access(rig), say);
        }

        @Override
        public boolean commanderConnect(Rig rig, CommanderConnect settings, Say say) {
            CommanderAccess access = access(rig);
            ConnectService service = new ConnectService(access::connectedTransport,
                    () -> new PdkShimConnectClient(access.pdkShim()), ConnectTiming.defaults(), access.sshTimeout(),
                    line -> say.out("commander-connect: " + line));
            PrintStream out = LinePrintStream.out(say);
            PrintStream err = LinePrintStream.err(say);
            try {
                return CommanderConnectCommand.cli(service, settings, out, err) == CommanderConnectCommand.EXIT_OK;
            } finally {
                out.close();
                err.close();
            }
        }

        /**
         * The Python's {@code secondary_fep_cli} ({@link SecondaryFepCli#select}): the table, the selection, a route
         * read and a trust over SSH, and with a service id and the run's C-Site account, the Commander Connect push.
         */
        @Override
        public boolean secondaryFep(Rig rig, List<String> feps, String serviceId, Map<String, String> csiteEnv,
                Say say) {
            return SecondaryFepCli.select(rig, feps, petroappsRoot(), serviceId, merged(csiteEnv), access(rig), say);
        }

        @Override
        public boolean paymentLinks(Rig rig, Say say) {
            CommanderAccess access = access(rig);
            try (PdkShim shim = access.pdkShim()) {
                return PaymentLinks.run(rig, petroappsRoot(), PaymentLinks.ConfigPort.of(shim),
                        access::connectedTransport, Pauser.REAL, say);
            }
        }

        @Override
        public boolean registerApp(Rig rig, Say say) {
            return RegisterAppCommand.forRig(rig, access(rig), say);
        }

        @Override
        public CloseDay.Outcome closeDay(Rig rig, Say say) {
            return DayClose.real(environ).close(rig, say);
        }

        @Override
        public boolean snapshotCapture(String rigName, String buildLabel, Say say) {
            List<String> argv = new ArrayList<>(List.of("capture", "--rig", rigName, "--if-missing", "--twice"));
            if (buildLabel != null && !buildLabel.isBlank()) {
                argv.add("--build-label");
                argv.add(buildLabel);
            }
            PrintStream out = LinePrintStream.out(say);
            PrintStream err = LinePrintStream.err(say);
            try {
                return new CommanderSnapshotCommand().run(argv.toArray(String[]::new), environ, out, err)
                        == CommanderSnapshotCommand.EXIT_OK;
            } finally {
                out.close();
                err.close();
            }
        }

        @Override
        public Path pinpadLog(Rig rig) {
            Optional<SimEntry> entry = Registry.byName(StartSimulatorsStep.PETRO_SIMULATOR);
            if (entry.isEmpty() || entry.get().log() == null || entry.get().log().isBlank()) {
                return null;
            }
            return sims(rig).cwdFor(entry.get()).resolve(entry.get().log());
        }

        /**
         * The Python's {@code _restart_simulator}: only a CAT-started instance (D9), stopped, started again in the
         * simulators' context step 3 used ({@link StartSimulatorsStep#runSims}: its Java set and TLS port), and
         * recorded on the run in place of the old one.
         */
        @Override
        public String restartSimulator(RunContext ctx, String simulator, StepEmitter step) {
            List<SimProcess> owned = ctx.simulators().stream()
                    .filter(p -> p.entry().name().equals(simulator) && p.spawned()).toList();
            if (owned.isEmpty()) {
                return notStartedByCat(simulator);
            }
            step.log("restarting " + simulator, "INFO");
            Supervisor supervisor = ctx.supervisor();
            supervisor.stop(owned, Supervisor.STOP_WAIT);
            SimProcess fresh = supervisor.start(owned.get(0).entry(), StartSimulatorsStep.runSims(ctx), Events.NONE,
                    "", "", SIM_START_TIMEOUT);
            ctx.replaceSimulators(owned, fresh);
            if (fresh.status().state() != SimStatus.State.READY) {
                return simulator + " did not come back: " + fresh.status().state().label() + " — "
                        + fresh.status().detail();
            }
            step.log(simulator + ": ready again", "INFO");
            return "";
        }

        private SimContext sims(Rig rig) {
            return SimContext.of(ToolsRoot.resolve(environ.get(ToolsRoot.ENV), null),
                    rig == null ? "" : rig.controller().host());
        }

        private Path petroappsRoot() {
            String configured = environ.getOrDefault("PETROAPPS_ROOT", "");
            return Path.of(configured.isEmpty() ? Collect.DEFAULT_PETROAPPS_ROOT : configured);
        }

        private CommanderAccess access(Rig rig) {
            return new CommanderAccess(rig, JschTransport::new, environ);
        }
    }

    /** The Python's detail for a restart of a simulator CAT did not start (D9). */
    static String notStartedByCat(String simulator) {
        return simulator + " was not started by CAT (adopted, or not running); restart it by hand and re-run — CAT "
                + "stops only what it started (D9)";
    }
}
