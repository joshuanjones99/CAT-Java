package com.verifone.cat.launch;

import com.verifone.cat.closeday.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.csite.CSiteCloud;
import com.verifone.cat.devices.legacyrmi.LegacyRmi;
import com.verifone.cat.devices.legacyrmi.LegacySaleRmi;
import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.HandlerFixtures;
import com.verifone.cat.pats.runner.handlers.StandardHandlers;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.devices.DevicesTiming;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.posui.PosUiTimings;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pats.runner.handlers.sale.SaleTiming;
import com.verifone.cat.pos.driver.PosDomDriver;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.ReceiptSource;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.recovery.Timeouts;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link RegistryFactory}: builds the run's {@link ActionRegistry} with
 * {@link StandardHandlers#create} from the services the run has.
 *
 * <p>A surface the run lacks is bound to a stand-in that throws {@link DeviceUnavailable} or refuses to connect, so an
 * action that needs it fails by name and nothing reports success:
 * <ul>
 *   <li>no POS driver: the registry is empty, so every action fails with "no handler" (the step is then unusable);</li>
 *   <li>no pos-shim, no Commander: the shim and the Commander point at a port nothing listens on;</li>
 *   <li>no pinpad, no DCR, no pumps, no printer: their families' defaults throw {@link DeviceUnavailable}, and the
 *       receipt falls back to the screen.</li>
 * </ul>
 *
 * <p>With the devices the connector supplies, the fixtures are the real clients, each built from the rig file:
 * <ul>
 *   <li>the pos-shim and the Commander's PDK shim, as the connector built them;</li>
 *   <li>{@code SaleRmi}: {@link LegacySaleRmi} over the pos-shim, its overlay sweep the POS driver's
 *       {@link PosDriver#clearBlockingOverlays};</li>
 *   <li>the receipt: {@link PrinterReceipts} over the register's printer, begun at each case's start, when the run has a
 *       printer; the screen's {@link DomReceiptCapture} otherwise. Its print count is the printer's;</li>
 *   <li>the Commander's SSH and CGILink access ({@link CommanderAccess#forRig}), for the close-day recovery and the
 *       cloud settle's Commander log;</li>
 *   <li>on a rig, the C-Site client, built at its first request (the Python's {@code CsiteSettings.from_env(rig=...)}):
 *       the environment's {@code CAT_CSITE_*} settings, else the run's Commander Connect account
 *       ({@code CSiteClient.accountEnv}), over the endpoints the Commander is onboarded to
 *       ({@code CommanderCloudConfig.forCommander}, read over SSH once per Commander).</li>
 * </ul>
 * The pinpad comes from the connector; the DCR and the pumps from step 5's Petro session ({@link PetroSession}), both
 * through {@link RunServices}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the wiring is tested on the fake POS and on documentation names only.
 */
public final class StandardRegistry implements RegistryFactory {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.launch");

    /** A port nothing listens on: the stand-in for a surface the run does not have, so a read fails by name. */
    static final int UNBOUND_PORT = 1;

    /** The environment variable that names the tools root (the simulators' folders, the property files). */
    static final String TOOLS_ROOT_ENV = "CAT_TOOLS_ROOT";

    /** The tools root when the environment does not name one: PATS's folder. */
    static final Path DEFAULT_TOOLS_ROOT = Path.of("C:\\CICD\\Tools");

    /** FEPSIM v2 serving Buypass this run, or null: set by step 5 before it builds the registry. */
    private volatile com.verifone.cat.pats.runner.handlers.configuration.BuypassV2 buypassV2;

    /**
     * FEPSIM v2's Buypass listener this run ({@code StartSimulatorsStep.fepsimBuypass}, the Python's
     * {@code fepsim_buypass_env}), or null when BUYPASS Host serves Buypass: the {@code buypassV2} fixture and the
     * packet folders' source. Step 5 sets it before {@link #build}.
     */
    void buypassV2(com.verifone.cat.pats.runner.handlers.configuration.BuypassV2 listener) {
        buypassV2 = listener;
    }

    @Override
    public ActionRegistry create(RunServices services) {
        return build(services).registry();
    }

    /**
     * One {@link HandlerFixtures} for the run, the registry over it, and {@link StandardHandlers#fixtureSource} over the
     * same fixtures: {@code LEGACY_RMI} is the pos-shim, {@code DCR} and {@code FUEL_PUMPS} the session's Petro devices.
     * A surface the run lacks still fails by name ({@link #fixtureSource(RunServices, HandlerFixtures)}).
     */
    @Override
    public Built build(RunServices services) {
        if (services.pos() == null) {
            LOG.warn("no POS driver is connected to this run; no action has a handler");
            return new Built(new ActionRegistry(), TestsRunStep.fixtureSource(services), null);
        }
        HandlerFixtures fixtures = fixtures(services, System.getenv(), buypassV2);
        ActionRegistry registry = StandardHandlers.create(fixtures);
        if (fixtures.receipts() instanceof PrinterReceipts printed) {
            // Each case's receipt is picked from the Commander's numbers as they stand when the case begins.
            registry.onBegin(run -> printed.begin());
        }
        return new Built(registry, fixtureSource(services, fixtures), fixtures);
    }

    /**
     * {@link StandardHandlers#fixtureSource} over {@code fixtures}, except that a surface the run does not have throws
     * {@link DeviceUnavailable} rather than handing out a stand-in: the pos-shim ({@code LEGACY_RMI}, as the Python's
     * {@code legacy_rmi} fixture refuses a rig with no {@code pos_shim:} and the fake POS), the pinpad, and the
     * Commander. The DCR and the pumps are the families' unwired defaults when the run has none, which throw too.
     */
    static FixtureSource fixtureSource(RunServices services, HandlerFixtures fixtures) {
        FixtureSource standard = StandardHandlers.fixtureSource(fixtures);
        return name -> switch (name) {
            case LEGACY_RMI -> {
                if (services.posShim() == null) {
                    throw new DeviceUnavailable("legacy_rmi", "no pos-shim is connected to this run (the fake POS has "
                            + "nothing listening on the RMI path)");
                }
                if (services.rig() != null && !services.rig().rig().posShim()) {
                    throw new DeviceUnavailable("legacy_rmi", "rigs/" + services.rig().rig().name()
                            + ".yaml has no pos_shim: block, so there is nothing at the register's 8801 to drive");
                }
                yield standard.fixture(name);
            }
            case PINPAD -> {
                if (services.pinpad() == null) {
                    throw new DeviceUnavailable("pinpad", "no pinpad is connected to this run");
                }
                yield standard.fixture(name);
            }
            case COMMANDER_CONFIG -> {
                if (services.commander() == null) {
                    throw new DeviceUnavailable("commander_config", "no Commander PDK shim is connected to this run");
                }
                yield standard.fixture(name);
            }
            default -> standard.fixture(name);
        };
    }

    /** The handler fixtures a run's services give, with the stand-ins for the surfaces it lacks. */
    static HandlerFixtures fixtures(RunServices services, Map<String, String> env) {
        return fixtures(services, env, null);
    }

    /** As {@link #fixtures(RunServices, Map)}, with FEPSIM v2's Buypass listener when it serves Buypass this run. */
    static HandlerFixtures fixtures(RunServices services, Map<String, String> env,
            com.verifone.cat.pats.runner.handlers.configuration.BuypassV2 buypassV2) {
        PosDriver driver = services.pos();
        Path tools = toolsRoot(env);
        Map<String, String> environment = Map.copyOf(env);
        PosShimClient shim = services.posShim() != null ? services.posShim()
                : new PosShimClient("localhost", UNBOUND_PORT, "CAT", Duration.ofSeconds(5));
        PdkShim commander = services.commander() != null ? services.commander()
                : PdkShim.onPort("localhost", UNBOUND_PORT, Duration.ofSeconds(5), () -> "");
        Timeouts timeouts = services.rig() == null ? Timeouts.defaults() : Timeouts.of(services.rig().rig().timeouts());
        return new HandlerFixtures(
                new Pos(driver),
                shim,
                receipts(services, commander, timeouts),
                services.pinpad() != null ? services.pinpad() : unboundPinpad(),
                commander,
                commanderAccess(services, environment),
                PetroappsFiles.fromEnvironment(environment),
                tools,
                services.paths().resultsRoot(),
                environment,
                InitsState.production(tools),
                services.dcr(),
                services.fuelPumps(),
                cloudSite(services, environment),
                buypassV2,
                null,
                PosUiTimings.defaults(),
                SaleTiming.DEFAULT,
                DevicesTiming.DEFAULT,
                saleRmi(services, driver, shim),
                services.printer(),
                services.rig() == null ? null : services.rig().rig().cashier(),
                services.rig() == null || (services.config() != null && services.config().fakePos()) ? null
                        : StandardHandlers.packetFolders(tools, buypassV2));
    }

    /** {@code CAT_TOOLS_ROOT}, else PATS's {@code C:\CICD\Tools}. */
    static Path toolsRoot(Map<String, String> env) {
        String named = env.getOrDefault(TOOLS_ROOT_ENV, "").strip();
        return named.isEmpty() ? DEFAULT_TOOLS_ROOT : Path.of(named);
    }

    /**
     * The POS receipt: the printed one, begun per case, when the run has a printer (as the Python's {@code pos} fixture
     * wires {@code SerialReceiptCapture}); the screen's otherwise.
     */
    static ReceiptSource receipts(RunServices services, PdkShim commander, Timeouts timeouts) {
        if (services.printer() == null) {
            return new DomReceiptCapture(new PosDomDriver(services.pos()), PosDomDriver.itemFamilies());
        }
        return new PrinterReceipts(services.printer(),
                () -> commander.listTransactionNumbers().stream().map(Long::valueOf).toList(),
                timeouts.promptWait());
    }

    /**
     * The register's RMI calls over the pos-shim's key path, with the POS driver's overlay sweep before each action (the
     * Python's {@code legacy_rmi} fixture, which wires {@code clear_blocking_overlays} when the test holds {@code pos}).
     * Unwired when the run has no pos-shim.
     */
    static SaleRmi saleRmi(RunServices services, PosDriver driver, PosShimClient shim) {
        if (services.posShim() == null) {
            return SaleRmi.UNAVAILABLE;
        }
        return new LegacySaleRmi(new LegacyRmi(shim, () -> driver.clearBlockingOverlays(null)));
    }

    /** The Commander's SSH and CGILink access over the rig, or null on a run with no rig. */
    static CommanderAccess commanderAccess(RunServices services, Map<String, String> env) {
        return services.rig() == null ? null : CommanderAccess.forRig(services.rig().rig(), env);
    }

    /**
     * The run's C-Site client, or null on a run with no rig. Built at its first use, not here: building it reads the
     * Commander's cloud settings over SSH, which a session that never sends to the cloud must not pay for. A client
     * that cannot be built fails the request that asked for it ({@link PatsStepError}).
     */
    static CloudSite cloudSite(RunServices services, Map<String, String> env) {
        if (services.rig() == null) {
            return null;
        }
        Map<String, String> environ = new java.util.HashMap<>(env);
        com.verifone.cat.commander.connect.CommanderConnect account = services.config() == null
                ? LaunchConfig.NO_COMMANDER_CONNECT : services.config().commanderConnectSettings();
        environ.putAll(com.verifone.cat.devices.csite.CSiteClient.accountEnv(account.lab(), account.email(),
                account.password(), env));
        com.verifone.cat.rig.Rig rig = services.rig().rig();
        return new LazyCloudSite(() -> {
            com.verifone.cat.commander.CommanderAccess access = new com.verifone.cat.commander.CommanderAccess(rig);
            return CSiteCloud.of(environ, com.verifone.cat.devices.csite.CommanderCloudConfig.forCommander(
                    rig.commander().host(), access::connectedTransport, access.sshTimeout()));
        });
    }

    /** A {@link CloudSite} built from {@code build} at its first use, and kept. */
    static final class LazyCloudSite implements CloudSite {

        private final java.util.function.Supplier<CloudSite> build;
        private CloudSite site;

        LazyCloudSite(java.util.function.Supplier<CloudSite> build) {
            this.build = build;
        }

        private synchronized CloudSite site() {
            if (site == null) {
                try {
                    site = build.get();
                } catch (DeviceError e) {
                    throw new PatsStepError("the C-Site client could not be built: " + e.getMessage(), e);
                }
            }
            return site;
        }

        @Override
        public String environment() {
            return site().environment();
        }

        @Override
        public boolean stripServicePrefix() {
            return site().stripServicePrefix();
        }

        @Override
        public com.verifone.cat.pats.runner.handlers.http.HttpReply send(String method, String endpoint, String body,
                boolean retry) {
            return site().send(method, endpoint, body, retry);
        }
    }

    /** A pinpad that throws {@link DeviceUnavailable} for every call: the run has no pinpad. */
    static PinpadDevice unboundPinpad() {
        return new PinpadDevice() {
            @Override
            public String id() {
                throw unavailable("read the pinpad's id");
            }

            @Override
            public PinpadDisplay display() {
                throw unavailable("read the pinpad's display");
            }

            @Override
            public String atPrompt(String prompt, Duration timeout) {
                throw unavailable("wait for the pinpad prompt " + prompt);
            }

            @Override
            public String expectPromptCleared(String prompt, Duration timeout) {
                throw unavailable("wait for the pinpad prompt " + prompt + " to clear");
            }

            @Override
            public String insertCard(String label, String waitForPrompt, Duration timeout) {
                throw unavailable("insert card " + label);
            }

            @Override
            public String swipeCard(String label, String waitForPrompt, Duration timeout) {
                throw unavailable("swipe card " + label);
            }

            @Override
            public String tapCard(String label, String waitForPrompt, Duration timeout) {
                throw unavailable("tap card " + label);
            }

            @Override
            public void removeCard() {
                throw unavailable("remove the card");
            }

            @Override
            public String key(String key, String waitForPrompt, Duration timeout) {
                throw unavailable("press pinpad key " + key);
            }

            @Override
            public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
                throw unavailable("key " + value + " on the pinpad");
            }
        };
    }

    private static DeviceUnavailable unavailable(String operation) {
        return new DeviceUnavailable("pinpad", operation + ": no pinpad is connected to this run");
    }
}
