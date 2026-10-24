package com.verifone.cat.launch;

import java.net.URI;
import java.nio.file.Path;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.recovery.RecoveryTarget;
import com.verifone.cat.rig.LoadedRig;

/**
 * What one run can provide to its action handlers. Built by the Tests Run step, with the devices the
 * {@link Connector} adds. A null component is a surface this run does not have: a handler that needs it
 * throws {@link com.verifone.cat.devices.DeviceUnavailable}, so the action fails and says why.
 *
 * <p>Components:
 * <ul>
 *   <li>{@code config}, {@code paths}, {@code runId}, {@code runFolder}: the run's own facts.</li>
 *   <li>{@code rig}: the rig the System Checks step loaded, or null on a fake-POS run.</li>
 *   <li>{@code pos}: a connected POS driver. Null until the connector supplies one.</li>
 *   <li>{@code posShim}: the pos-shim HTTP client (register:8801). Null until the connector supplies one.</li>
 *   <li>{@code commander}: the Commander PDK shim. Null until the connector supplies one.</li>
 *   <li>{@code pinpad}: the pinpad double, when fake-pinpad or fake-POS is selected; otherwise null until the connector
 *       supplies the rig's ViperPay pinpad.</li>
 *   <li>{@code fakePosUrl}: the loopback URL of the fake POS this run started, on a fake-POS run; else null.
 *       The connector can open a driver on it.</li>
 *   <li>{@code recovery}: the rig surface the recovery ladder walks, or null, which leaves failed cases
 *       unrecovered (the ladder is walked only on a rig, as the Python's is).</li>
 *   <li>{@code printer}: the register's receipt printer (its {@code printer_port}), or null.</li>
 *   <li>{@code dcr}: Petro Simulator Suite's DCR 1, or null.</li>
 *   <li>{@code fuelPumps}: Petro Simulator Suite's pumps, or null.</li>
 * </ul>
 *
 * @param config     the run's configuration
 * @param paths      the launcher's paths
 * @param runId      the run's id
 * @param runFolder  the run's folder
 * @param rig        the loaded rig, or null
 * @param pos        the POS driver, or null
 * @param posShim    the pos-shim client, or null
 * @param commander  the Commander PDK shim, or null
 * @param pinpad     the pinpad, or null
 * @param fakePosUrl the fake POS's URL, or null
 * @param recovery   the recovery target, or null
 * @param printer    the receipt printer, or null
 * @param dcr        the DCR, or null
 * @param fuelPumps  the forecourt's pumps, or null
 */
public record RunServices(
        LaunchConfig config,
        LaunchPaths paths,
        String runId,
        Path runFolder,
        LoadedRig rig,
        PosDriver pos,
        PosShimClient posShim,
        PdkShim commander,
        PinpadDevice pinpad,
        URI fakePosUrl,
        RecoveryTarget recovery,
        ReceiptPrinter printer,
        DcrDevice dcr,
        FuelPumps fuelPumps) {

    /** The services before {@code printer}, {@code dcr} and {@code fuelPumps} were added: those three are unwired. */
    public RunServices(
            LaunchConfig config,
            LaunchPaths paths,
            String runId,
            Path runFolder,
            LoadedRig rig,
            PosDriver pos,
            PosShimClient posShim,
            PdkShim commander,
            PinpadDevice pinpad,
            URI fakePosUrl,
            RecoveryTarget recovery) {
        this(config, paths, runId, runFolder, rig, pos, posShim, commander, pinpad, fakePosUrl, recovery,
                null, null, null);
    }

    /** Adds the devices a run connects. The Tests Run step calls it once, after the base is built. */
    @FunctionalInterface
    public interface Connector {

        /** Returns the services with whatever surfaces this connector can supply. */
        RunServices connect(RunServices base);

        /**
         * Releases what {@link #connect} opened (a browser session, its driver, the device listeners and the printer
         * port). The Tests Run step calls it once when the step ends, whether or not the cases ran. Nothing to release
         * by default.
         */
        default void close() {
        }

        /** Connects nothing: the run has only what the step itself built (fake-POS URL, pinpad). */
        Connector NONE = base -> base;
    }
}
