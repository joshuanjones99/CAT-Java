package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.closeday.CommanderAccess;
import com.verifone.cat.pats.runner.handlers.configuration.BuypassV2;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.devices.DevicesTiming;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.posui.PosUiTimings;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pats.runner.handlers.sale.SaleTiming;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.pos.receipt.ReceiptSource;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.posshim.PosShimClient;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Everything one run provides to the action handlers: the POS page objects and the pos-shim client, the receipt source,
 * the pinpad, the Commander, the petroapps checkout, the timings and the environment. {@link StandardHandlers#create}
 * builds every family's context from it.
 *
 * <p>The optional devices are {@code null} when the run has none, and their family's default applies (throws
 * {@link com.verifone.cat.devices.DeviceUnavailable}; nothing is reported as done):
 * <ul>
 *   <li>{@code dcr}: the DCR; {@code null} means the DCR actions are unavailable.</li>
 *   <li>{@code fuelPumps}: the forecourt's pumps.</li>
 *   <li>{@code cloudSite}: the C-Site client (no Java implementation is ported yet, so production passes {@code null}).</li>
 *   <li>{@code buypassV2}: FEPSIM v2 serving Buypass this run.</li>
 *   <li>{@code commanderAccess}: SSH and CGILink to the Commander, for the close-day recovery and the cloud settle's
 *       Commander log; {@code null} means the stuck-close recovery is unbound and the cloud waits are skipped.</li>
 * </ul>
 *
 * <p>There is no baseline directory here: a case's baselines live beside its own test file, so {@link StandardHandlers}
 * sets the case's folder as its baseline folder when the case begins, unless the caller set one.
 *
 * @param pos            the POS page objects (its driver is the POS page's {@code PosDriver})
 * @param posShim        the pos-shim client: the legacy RMI reads and the named key presses
 * @param receipts       the POS receipt capture
 * @param pinpad         the pinpad
 * @param commander      the Commander's CGILink client
 * @param commanderAccess the Commander's SSH and CGILink access, or {@code null}
 * @param petroapps      the petroapps checkout, read live
 * @param toolsRoot      the tools root: the simulators' property files and HTTPSIM's folders
 * @param resultsDir     the run's results root: where a deadlocked coresvcs's thread dump is kept
 * @param environment    the environment variables the families read (the rebase switches among them)
 * @param inits          the run's init state, shared by the inits, configuration and POS UI families
 * @param dcr            the DCR, or {@code null}
 * @param fuelPumps      the forecourt's pumps, or {@code null}
 * @param cloudSite      the C-Site client, or {@code null}
 * @param buypassV2     FEPSIM v2 for Buypass, or {@code null}
 * @param widgetText     the English text of a widget key, or {@code null} for the key itself (see the TODO in
 *                       {@link StandardHandlers})
 * @param posUiTimings   the POS UI family's waits
 * @param saleTiming     the sale family's waits
 * @param devicesTiming  the devices family's waits
 * @param saleRmi       the register's RMI calls, or {@code null} for {@link SaleRmi#UNAVAILABLE}
 * @param cashier       the rig's cashier, for the step library's sign-on steps, or {@code null} when the run gives none
 * @param printer       the register's receipt printer (its receipt count is the POS UI family's print count), or
 *                      {@code null} when the run has none
 */
public record HandlerFixtures(
        Pos pos,
        PosShimClient posShim,
        ReceiptSource receipts,
        PinpadDevice pinpad,
        PdkShim commander,
        CommanderAccess commanderAccess,
        PetroappsFiles petroapps,
        Path toolsRoot,
        Path resultsDir,
        Map<String, String> environment,
        InitsState inits,
        DcrDevice dcr,
        FuelPumps fuelPumps,
        CloudSite cloudSite,
        BuypassV2 buypassV2,
        Function<String, String> widgetText,
        PosUiTimings posUiTimings,
        SaleTiming saleTiming,
        DevicesTiming devicesTiming,
        SaleRmi saleRmi,
        ReceiptPrinter printer,
        Rig.Cashier cashier) {

    /** The fixtures before {@code cashier} was added: the step library's sign-on steps have no cashier. */
    public HandlerFixtures(
            Pos pos,
            PosShimClient posShim,
            ReceiptSource receipts,
            PinpadDevice pinpad,
            PdkShim commander,
            CommanderAccess commanderAccess,
            PetroappsFiles petroapps,
            Path toolsRoot,
            Path resultsDir,
            Map<String, String> environment,
            InitsState inits,
            DcrDevice dcr,
            FuelPumps fuelPumps,
            CloudSite cloudSite,
            BuypassV2 buypassV2,
            Function<String, String> widgetText,
            PosUiTimings posUiTimings,
            SaleTiming saleTiming,
            DevicesTiming devicesTiming,
            SaleRmi saleRmi,
            ReceiptPrinter printer) {
        this(pos, posShim, receipts, pinpad, commander, commanderAccess, petroapps, toolsRoot, resultsDir,
                environment, inits, dcr, fuelPumps, cloudSite, buypassV2, widgetText, posUiTimings, saleTiming,
                devicesTiming, saleRmi, printer, null);
    }

    /** The fixtures before {@code saleRmi} and {@code printer} were added: those two are unwired. */
    public HandlerFixtures(
            Pos pos,
            PosShimClient posShim,
            ReceiptSource receipts,
            PinpadDevice pinpad,
            PdkShim commander,
            CommanderAccess commanderAccess,
            PetroappsFiles petroapps,
            Path toolsRoot,
            Path resultsDir,
            Map<String, String> environment,
            InitsState inits,
            DcrDevice dcr,
            FuelPumps fuelPumps,
            CloudSite cloudSite,
            BuypassV2 buypassV2,
            Function<String, String> widgetText,
            PosUiTimings posUiTimings,
            SaleTiming saleTiming,
            DevicesTiming devicesTiming) {
        this(pos, posShim, receipts, pinpad, commander, commanderAccess, petroapps, toolsRoot, resultsDir,
                environment, inits, dcr, fuelPumps, cloudSite, buypassV2, widgetText, posUiTimings, saleTiming,
                devicesTiming, null, null, null);
    }

    public HandlerFixtures {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(posShim, "posShim");
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(pinpad, "pinpad");
        Objects.requireNonNull(commander, "commander");
        Objects.requireNonNull(petroapps, "petroapps");
        Objects.requireNonNull(toolsRoot, "toolsRoot");
        Objects.requireNonNull(resultsDir, "resultsDir");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(inits, "inits");
        Objects.requireNonNull(posUiTimings, "posUiTimings");
        Objects.requireNonNull(saleTiming, "saleTiming");
        Objects.requireNonNull(devicesTiming, "devicesTiming");
        saleRmi = saleRmi == null ? SaleRmi.UNAVAILABLE : saleRmi;
        environment = Map.copyOf(environment);
    }
}
