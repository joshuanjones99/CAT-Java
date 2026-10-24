package com.verifone.cat.launch;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.csite.CSiteCloud;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.handlers.HandlerFixtures;
import com.verifone.cat.pats.runner.handlers.StandardHandlers;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.devices.DevicesTiming;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.posui.PosUiTimings;
import com.verifone.cat.pats.runner.handlers.sale.SaleTiming;
import com.verifone.cat.pos.driver.PosDomDriver;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.posshim.PosShimClient;

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
 *   <li>no pos-shim, no Commander, no pinpad: the shim and the Commander point at a port nothing listens on, and the
 *       pinpad throws {@link DeviceUnavailable}.</li>
 * </ul>
 * The C-Site client is built only when its settings are in the environment ({@code csite.username}), and is then the
 * one the HTTP and configuration families send through; without them the CSITE step fails by name.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the wiring is tested on the fake POS only.
 */
public final class StandardRegistry implements RegistryFactory {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.launch");

    /** A port nothing listens on: the stand-in for a surface the run does not have, so a read fails by name. */
    static final int UNBOUND_PORT = 1;

    /** The environment variable that names the tools root (the simulators' folders, the property files). */
    static final String TOOLS_ROOT_ENV = "CAT_TOOLS_ROOT";

    /** The tools root when the environment does not name one: PATS's folder. */
    static final Path DEFAULT_TOOLS_ROOT = Path.of("C:\\CICD\\Tools");

    /** The C-Site settings' key: present, with a value, when the run has C-Site settings. */
    static final String CSITE_USER_ENV = "csite.username";

    @Override
    public ActionRegistry create(RunServices services) {
        if (services.pos() == null) {
            LOG.warn("no POS driver is connected to this run; no action has a handler");
            return new ActionRegistry();
        }
        return StandardHandlers.create(fixtures(services, System.getenv()));
    }

    /** The handler fixtures a run's services give, with the stand-ins for the surfaces it lacks. */
    static HandlerFixtures fixtures(RunServices services, Map<String, String> env) {
        PosDriver driver = services.pos();
        Path tools = toolsRoot(env);
        Map<String, String> environment = Map.copyOf(env);
        return new HandlerFixtures(
                new Pos(driver),
                services.posShim() != null ? services.posShim()
                        : new PosShimClient("localhost", UNBOUND_PORT, "CAT", Duration.ofSeconds(5)),
                new DomReceiptCapture(new PosDomDriver(driver), PosDomDriver.itemFamilies()),
                services.pinpad() != null ? services.pinpad() : unboundPinpad(),
                services.commander() != null ? services.commander()
                        : PdkShim.onPort("localhost", UNBOUND_PORT, Duration.ofSeconds(5), () -> ""),
                null,
                PetroappsFiles.fromEnvironment(environment),
                tools,
                services.paths().resultsRoot(),
                environment,
                InitsState.production(tools),
                null,
                null,
                cloudSite(environment),
                null,
                null,
                PosUiTimings.defaults(),
                SaleTiming.DEFAULT,
                DevicesTiming.DEFAULT);
    }

    /** {@code CAT_TOOLS_ROOT}, else PATS's {@code C:\CICD\Tools}. */
    static Path toolsRoot(Map<String, String> env) {
        String named = env.getOrDefault(TOOLS_ROOT_ENV, "").strip();
        return named.isEmpty() ? DEFAULT_TOOLS_ROOT : Path.of(named);
    }

    /** The C-Site client over the environment's settings, or null when there are none or they are not ported. */
    static CSiteCloud cloudSite(Map<String, String> env) {
        if (env.getOrDefault(CSITE_USER_ENV, "").isBlank()) {
            return null;
        }
        try {
            return CSiteCloud.of(env, Map.of());
        } catch (DeviceError e) {
            LOG.warn("the C-Site settings are not usable here; the CSITE steps will fail by name: {}", e.getMessage());
            return null;
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
