package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.configuration.ActionFields.require;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The configuration family: the actions that change or read the site's configuration. Ported from the Python's
 * {@code petrotest.pats.runner.configuration}, which is the specification.
 *
 * <ul>
 *   <li>{@code IMPORT_CONFIGURATION}: the Commander imports a configuration folder, read live from petroapps; the
 *       import's reply is a change the next init counts from.</li>
 *   <li>{@code UPDATE_CONFIGURATION}, {@code HTTP_SIM_EVENT}: simulator files, restored after a failed case.</li>
 *   <li>{@code NETWORK_REPORT}, {@code PRINT_FLASH_REPORT}, {@code CLOSE_REPORT}: the report keys and their waits.</li>
 *   <li>{@code REPORT_PRINT}: nothing. PATS sets a flag only; the receipt is the report's print.</li>
 *   <li>{@code CLOUD_HTTP_REQUEST}: the C-Site request, through the cloud seam.</li>
 * </ul>
 *
 * <p>{@code CONTROL_FUNCTION} is the inits family's, registered by {@code InitsFamily}. Both families share one
 * {@link com.verifone.cat.pats.runner.handlers.inits.InitsState} for a run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class ConfigurationFamily {

    private ConfigurationFamily() {
    }

    /**
     * Registers the configuration actions.
     *
     * @throws IllegalStateException when a name is already registered (see {@link ActionRegistry#register})
     */
    public static void register(ActionRegistry registry, ConfigurationContext context) {
        Objects.requireNonNull(registry, "registry");
        ReportActions reports = new ReportActions(context);
        SimulatorFiles simulators = new SimulatorFiles(context);
        CloudRequest cloud = new CloudRequest(context);
        registry.register("REPORT_PRINT", (run, action) -> {
            // PATS sets a flag only: the case's receipt is the report's print, which CAT's capture accepts without TRAN#.
        });
        registry.register("NETWORK_REPORT", reports::networkReport);
        registry.register("PRINT_FLASH_REPORT", reports::flashReport);
        registry.register("CLOSE_REPORT", reports::closeReport);
        registry.register("CLOUD_HTTP_REQUEST", cloud::request);
        registry.register("UPDATE_CONFIGURATION", simulators::updateConfiguration);
        registry.register("HTTP_SIM_EVENT", simulators::httpSimEvent);
        registry.register("IMPORT_CONFIGURATION", (run, action) -> importConfiguration(context, run, action));
    }

    /**
     * {@code SiteControllerService.importConfiguration}: pushes every file of the case's folder to the Commander,
     * then notes the change, so the next init waits as PATS's SLEEP does. The import's own reply is the change's end.
     */
    static void importConfiguration(ConfigurationContext context, CaseRun run, RunnableAction action) throws Exception {
        Path folder = run.fixture(FixtureName.CONFIGURATIONS, Path.class).resolve(require(action, "SELECT_FOLDER"));
        run.fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class).importConfigurationFolder(folder);
        context.inits().changed();
    }
}
