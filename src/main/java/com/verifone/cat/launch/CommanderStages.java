package com.verifone.cat.launch;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.microsoft.playwright.Frame;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.collect.Collect;
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
import com.verifone.cat.commander.registerapp.RegisterApp;
import com.verifone.cat.commander.snapshot.CommanderSnapshotCommand;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.fep.FepClient;
import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.fep.SecondaryFeps;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.Stage;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
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

    /** {@code petrotest provision}: the pos-shim payload pushed, verified and stamped on every target. */
    boolean provision(Rig rig, Say say);

    /** {@code petrotest commander-prep}: the license dates and the Gemreg marker. */
    boolean commanderPrep(Rig rig, Say say);

    /** {@code petrotest commander-init} ({@code --no-forecourt} when {@code forecourt} is false). */
    boolean commanderInit(Rig rig, boolean forecourt, Say say);

    /** {@code petrotest payment-ready}: the EPS ready for card sales. */
    boolean paymentReady(Rig rig, Say say);

    /** The cloud tunnel, started before the inits when the run onboards to Commander Connect. */
    default boolean cloudTunnel(Rig rig, Say say) {
        throw new StageUnavailable("src/cat/launch/tunnel.py",
                "the cloud tunnel's serve loop (cloudtunnel: SSH reverse forwards in Transport), which a run "
                        + "onboarding to Commander Connect starts before the inits; the lab Commander has no WAN link");
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

    /** {@code petrotest pinpad-clear}: a simulated card out of a {@code pinpad: vats} rig's M400. */
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
                "the EMV tables and CA key download (petrotest emv-keys; commander.emv.EmvKeys, wired at merge)");
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
         * The Python's {@code secondary_fep_cli}: the table, the selection, a route read and a trust over SSH. With
         * nothing chosen, a fault is a warning (only the switching-off failed, and the run needs none of it).
         * Deviation: no Commander Connect cloud sender (the Python's {@code _cloud_sender}); a chosen Commercial Fleet
         * FEP is pointed at directly, with {@link SecondaryFeps#select}'s warning.
         */
        @Override
        public boolean secondaryFep(Rig rig, List<String> feps, String serviceId, Map<String, String> csiteEnv,
                Say say) {
            List<SecondaryFep> catalogue;
            List<SecondaryFep> chosen;
            try {
                catalogue = SecondaryFeps.loadCatalogue();
                chosen = SecondaryFeps.resolve(feps, catalogue);
            } catch (RuntimeException e) {
                say.err("secondary-fep: " + e.getMessage());
                return false;
            }
            CommanderAccess access = access(rig);
            Supplier<String> route = () -> {
                try {
                    Transport transport = access.connectedTransport();
                    try {
                        return SecondaryFeps.fleetRoute(transport, access.sshTimeout());
                    } finally {
                        transport.close();
                    }
                } catch (RuntimeException e) {
                    return "";
                }
            };
            SecondaryFeps.Context context = new SecondaryFeps.Context(petroappsRoot(), rig.controller().host(),
                    ToolsRoot.resolve(environ.get(ToolsRoot.ENV), null), null, serviceId == null ? "" : serviceId,
                    route, SecondaryFeps.Trust.viaCommander(access, say));
            try (PdkShim shim = access.pdkShim()) {
                return SecondaryFeps.select(FepClient.of(shim), chosen, catalogue, context, say);
            } catch (RuntimeException e) {
                String why = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (chosen.isEmpty()) {
                    say.out("WARNING secondary FEPs not checked: " + why);
                    return true;
                }
                say.err("secondary-fep " + chosen.stream().map(SecondaryFep::name).collect(Collectors.joining(", "))
                        + " on " + rig.commander().host() + " failed: " + why);
                return false;
            }
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
            CommanderAccess access = access(rig);
            Function<Rig.Register, Transport> transports = register -> {
                Transport transport = access.registerTransport(register);
                if (transport instanceof JschTransport jsch) {
                    try {
                        jsch.connect(Duration.ofSeconds(20));
                    } catch (RuntimeException e) {
                        transport.close();
                        throw e;
                    }
                }
                return transport;
            };
            return RegisterApp.run(rig, transports, new PosSeams(rig), Pauser.REAL, RegisterApp.SWITCH_TIMEOUT, say);
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
         * The Python's {@code _restart_simulator}: only a CAT-started instance (D9), stopped, started again, and
         * recorded on the run in place of the old one.
         */
        @Override
        public String restartSimulator(RunContext ctx, String simulator, StepEmitter step) {
            List<SimProcess> owned = ctx.simulators().stream()
                    .filter(p -> p.entry().name().equals(simulator) && p.spawned()).toList();
            if (owned.isEmpty()) {
                return notStartedByCat(simulator);
            }
            Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
            step.log("restarting " + simulator, "INFO");
            Supervisor supervisor = ctx.supervisor();
            supervisor.stop(owned, Supervisor.STOP_WAIT);
            SimProcess fresh = supervisor.start(owned.get(0).entry(), sims(rig), Events.NONE, "", "",
                    SIM_START_TIMEOUT);
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

    /**
     * The register's POS page over CDP, for {@code register-app}: the Python's {@code hosted_app} and
     * {@code _press_pos_panel}, one CDP attach each.
     */
    final class PosSeams implements RegisterApp.PosSeams {

        private final Rig rig;

        PosSeams(Rig rig) {
            this.rig = rig;
        }

        @Override
        public Optional<String> hostedApp(Rig.Register register) {
            try (PosDriver pos = PosDriver.connect(RegisterEndpoint.of(register), Timeouts.of(rig.timeouts()), null,
                    null, null, false)) {
                for (Frame frame : pos.page().frames()) {
                    Optional<String> app = RegisterApp.appForFrame(frame.url());
                    if (app.isPresent()) {
                        return app;
                    }
                }
                return Optional.empty();
            } catch (RuntimeException e) {
                // the page is reloading, or CDP is not back yet
                return Optional.empty();
            }
        }

        @Override
        public void pressPosPanel(Rig.Register register) {
            try (PosDriver pos = PosDriver.connect(RegisterEndpoint.of(register), Timeouts.of(rig.timeouts()), null,
                    null, null, false)) {
                pos.press(Locators.APP_SELECTOR_SWITCHER_VIEW_MAIN_PANEL, RegisterApp.SWITCHER_WAIT);
                pos.press(Locators.APP_SELECTION_PROMPT_VIEW_APP_PANEL_POS, Duration.ofSeconds(30));
            }
        }
    }
}
