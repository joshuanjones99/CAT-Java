package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.closeday.RegisterPass;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleContext;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleFamily;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.configuration.ConfigurationContext;
import com.verifone.cat.pats.runner.handlers.configuration.ConfigurationFamily;
import com.verifone.cat.pats.runner.handlers.configuration.RegisterPos;
import com.verifone.cat.pats.runner.handlers.configuration.StuckCloseRecovery;
import com.verifone.cat.pats.runner.handlers.configuration.CloudSender;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.DevicePauses;
import com.verifone.cat.pats.runner.handlers.devices.DevicesContext;
import com.verifone.cat.pats.runner.handlers.devices.DevicesFamily;
import com.verifone.cat.pats.runner.handlers.devices.DevicesTiming;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.http.HttpContext;
import com.verifone.cat.pats.runner.handlers.http.HttpFamily;
import com.verifone.cat.pats.runner.handlers.inits.InitsContext;
import com.verifone.cat.pats.runner.handlers.inits.InitsFamily;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.inits.Waits;
import com.verifone.cat.pats.runner.handlers.posui.FuelPumpsView;
import com.verifone.cat.pats.runner.handlers.posui.PinpadView;
import com.verifone.cat.pats.runner.handlers.posui.PosUiContext;
import com.verifone.cat.pats.runner.handlers.posui.PosUiFamily;
import com.verifone.cat.pats.runner.handlers.sale.LastTlog;
import com.verifone.cat.pats.runner.handlers.sale.SaleContext;
import com.verifone.cat.pats.runner.handlers.sale.SaleFamily;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pats.runner.handlers.sale.SaleScreen;
import com.verifone.cat.pats.runner.handlers.sale.ScannerData;
import com.verifone.cat.pats.runner.handlers.sale.TouchPacer;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;
import com.verifone.cat.pats.runner.handlers.validation.ValidationFamily;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one handler set a run registers: the eight action families (POS UI, sale, devices, HTTP, cloud settle,
 * configuration, inits, validation), their shared state, their seams, and the hooks PATS's runner takes around a case.
 * The Python's {@code petrotest.pats.runner} builds its {@code _HANDLERS} table and its marks the same way.
 *
 * <p>Shared across families, one instance each per run:
 * <ul>
 *   <li>one {@link InitsState}, shared by the inits family, the configuration family and the POS UI SLEEP;</li>
 *   <li>one {@link TouchPacer}, whose {@code TOUCHED} flag and last-touch time the POS UI family reads too (its
 *       {@code PosUiContext} is bound to it), so posui, sale and the scan family make one boundary pause;</li>
 *   <li>the devices family's {@code KEYED_AT}, which the POS UI touch reads through its case state;</li>
 *   <li>one {@link CloudSettle}, behind the HTTP family's {@code CSITE} request and the configuration family's
 *       {@code CLOUD_HTTP_REQUEST}, so a cloud write is waited on the same way from either;</li>
 *   <li>the scenario context under {@code ScratchKey.SCENARIO_CONTEXT}, read by HTTP and configuration.</li>
 * </ul>
 *
 * <p>Hooks: the first begin hook remembers the case for the adapters that read its folder, and sets its baseline
 * folder; the finish hook presses the dead-end OK on a failed case, as the Python's {@code finish} does
 * ({@link PosUiFamily#clearDeadEnd}). The inits family's reply pause and the validation family's begin marks are the
 * families' own.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: every seam without a Java device behind it is a {@link DeviceUnavailable} default.
 */
public final class StandardHandlers {

    /** The widget texts the POS shows in English, from the Python's {@code widget_keys.json}: the identity until ported. */
    // TODO(widget_keys.json): the table is being ported in parallel; until it is wired a widget key stands for itself.
    private static final Function<String, String> WIDGET_TEXT_UNPORTED = Function.identity();

    private StandardHandlers() {
    }

    /**
     * Builds the run's registry: every family registered once, their hooks, and their shared state.
     *
     * @throws IllegalStateException when two families register one action name (a wiring error)
     */
    public static ActionRegistry create(HandlerFixtures f) {
        Objects.requireNonNull(f, "fixtures");
        ActionRegistry registry = new ActionRegistry();
        CaseFolders folders = new CaseFolders(f.petroapps());

        // The first begin hook: every later begin and action sees the case in hand.
        registry.onBegin(run -> {
            folders.begin(run.kase());
            if (run.baselineDir().isEmpty()) {
                folders.folder().ifPresent(run::withBaselineDir);
            }
        });

        Function<String, String> widgetText = f.widgetText() == null ? WIDGET_TEXT_UNPORTED : f.widgetText();
        InitsState inits = f.inits();
        TouchPacer pacer = new TouchPacer(DevicePauses.REAL, f.saleTiming().touchSettle());
        FuelPumps pumps = f.fuelPumps() == null ? FuelPumps.UNAVAILABLE : f.fuelPumps();

        // POS UI: the page driver and the shim, bound to the shared touch clock and the shared init state.
        PosUiContext posUi = new PosUiContext(f.pos().driver(), f.posShim(), widgetText)
                .withTimings(f.posUiTimings())
                // The Python's print count is the serial printer's; with none wired it reads -1, as its does.
                .withPrintCount(run -> -1)
                .withInits(inits)
                .withTouchPacer(pacer);

        // Sale: the screen over the page objects, the pumps, and the petroapps scanner data.
        SaleContext sale = new SaleContext(SaleScreen.of(f.pos()), SaleRmi.UNAVAILABLE, pumps,
                scannerData(f.petroapps()), LastTlog.UNAVAILABLE, DevicePauses.REAL, pacer, f.saleTiming());

        DcrDevice dcr = f.dcr() == null ? DcrDevice.UNAVAILABLE : f.dcr();
        DevicesContext devices = new DevicesContext(f.pinpad(), dcr, pumps, DevicePauses.REAL, f.devicesTiming());

        // Cloud settle: one context, one CloudSettle, shared by HTTP's CSITE and configuration's CLOUD_HTTP_REQUEST.
        Supplier<? extends CommanderLog> commanderLog = f.commanderAccess() == null
                ? null : CommanderSeams.commanderLogs(f.commanderAccess());
        CloudSettleContext cloudSettle = CloudSettleContext.of(commanderLog);
        CloudSettle settle = new CloudSettle(cloudSettle);

        HttpContext http = new HttpContext(folders.httpFiles(), f.cloudSite(), settle);

        CloudSender cloudSender = CommanderSeams.cloudSender(f.commander(), f.cloudSite(), settle);
        StuckCloseRecovery closeRecovery = f.commanderAccess() == null ? StuckCloseRecovery.UNBOUND
                : CommanderSeams.stuckCloseRecovery(closeDay(f));
        ConfigurationContext configuration = new ConfigurationContext(f.petroapps(), f.toolsRoot(),
                RegisterPos.UNBOUND, cloudSender, closeRecovery, () -> Optional.ofNullable(f.buypassV2()),
                folders.configurationFiles(), inits, Waits.production());

        ValidationContext validation = new ValidationContext(f.receipts(), null,
                FixtureRequirements::printsAtDcr, unavailablePosTransactions(),
                CommanderSeams.reads(f.commander()), unavailablePackets(), folders.references(),
                f.environment()::get);

        // The eight families, in the Python's order.
        PosUiFamily.register(registry, posUi);
        SaleFamily.register(registry, sale);
        DevicesFamily.register(registry, devices);
        HttpFamily.register(registry, http);
        CloudSettleFamily.register(registry, cloudSettle);
        ConfigurationFamily.register(registry, configuration);
        InitsFamily.register(registry, new InitsContext(inits));
        ValidationFamily.register(registry, validation);

        // The verdict: a failed case's dead-end OK is pressed, as the Python's finish does.
        registry.onFinish(run -> {
            if (!run.softFailures().isEmpty()) {
                PosUiFamily.clearDeadEnd(run, posUi);
            }
        });
        return registry;
    }

    /**
     * The fixtures a case's test declares, as the runner's {@link FixtureSource}: each one's object for the run. A fixture
     * the run does not have is its unavailable default, so an action that asks for it fails by name.
     */
    public static FixtureSource fixtureSource(HandlerFixtures f) {
        PinpadView pinpad = new PinpadView() {
        };
        FuelPumpsView pumps = unavailablePumps();
        DcrDevice dcr = f.dcr() == null ? DcrDevice.UNAVAILABLE : f.dcr();
        return name -> switch (name) {
            case POS -> f.pos();
            case LEGACY_RMI -> f.posShim();
            case PINPAD -> pinpad;
            case DCR -> dcr;
            case FUEL_PUMPS -> pumps;
            case COMMANDER_CONFIG -> f.commander();
            case CONFIGURATIONS -> f.petroapps().configurations();
        };
    }

    /** The close-day family's close, built over the run's Commander access, for the stuck-close recovery. */
    private static CloseDay closeDay(HandlerFixtures f) {
        return new CloseDay(f.commanderAccess(), RegisterPass.NOT_PORTED,
                f.petroapps().configurations().resolve("base"), f.resultsDir(), CloseDay.Timing.production(),
                System.out, System.err);
    }

    /** {@code SCAN_ITEM}'s and {@code ADD_ITEM}'s scanner files: {@code AutomationTests/scannerdata}, read live. */
    private static ScannerData scannerData(PetroappsFiles petroapps) {
        return fileName -> {
            try {
                return new String(petroapps.shared("AutomationTests/scannerdata", fileName), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PatsStepError("scannerdata/" + fileName + " is not in the petroapps checkout: "
                        + e.getMessage(), e);
            }
        };
    }

    private static FuelPumpsView unavailablePumps() {
        return new FuelPumpsView() {
            @Override
            public boolean waitForPostedSale(int pump, Duration timeout) {
                throw unavailable("wait for pump " + pump + "'s posted sale");
            }

            @Override
            public boolean lifted(int pump) {
                throw unavailable("read whether pump " + pump + " is lifted");
            }

            @Override
            public String state(int pump) {
                throw unavailable("read pump " + pump + "'s state");
            }
        };
    }

    private static ValidationContext.PosTransactions unavailablePosTransactions() {
        return new ValidationContext.PosTransactions() {
            @Override
            public String newestTransactionNumber() {
                throw unavailable("read the POS's newest transaction number");
            }

            @Override
            public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds) {
                throw unavailable("read the POS's newest TLog");
            }
        };
    }

    private static ValidationContext.PacketSource unavailablePackets() {
        return new ValidationContext.PacketSource() {
            @Override
            public List<Path> captured(String device) {
                throw unavailable("list " + device + "'s captured packets");
            }

            @Override
            public void reset(String device) {
                throw unavailable("reset " + device + "'s captured packets");
            }
        };
    }

    private static DeviceUnavailable unavailable(String operation) {
        return new DeviceUnavailable("simulators", operation + ": no Java implementation is wired to this run");
    }
}
