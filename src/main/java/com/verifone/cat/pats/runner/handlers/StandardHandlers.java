package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.closeday.RegisterPass;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.WidgetKeys;
import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.bdd.BddContext;
import com.verifone.cat.pats.runner.handlers.bdd.BddFamily;
import com.verifone.cat.pats.runner.handlers.bdd.BddScreen;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleContext;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleFamily;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.configuration.ConfigurationContext;
import com.verifone.cat.pats.runner.handlers.configuration.ConfigurationFamily;
import com.verifone.cat.pats.runner.handlers.configuration.LegacyRegister;
import com.verifone.cat.pats.runner.handlers.configuration.RegisterPos;
import com.verifone.cat.devices.legacyrmi.LegacyRmi;
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
import com.verifone.cat.pats.runner.handlers.steps.LibraryStepFamily;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;
import com.verifone.cat.pats.runner.handlers.validation.ValidationFamily;
import com.verifone.cat.pats.runner.handlers.configuration.BuypassV2;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.w3c.dom.Element;

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

        Function<String, String> widgetText = f.widgetText() == null ? WidgetKeys.standard() : f.widgetText();
        InitsState inits = f.inits();
        TouchPacer pacer = new TouchPacer(DevicePauses.REAL, f.saleTiming().touchSettle());
        FuelPumps pumps = f.fuelPumps() == null ? FuelPumps.UNAVAILABLE : f.fuelPumps();

        // POS UI: the page driver and the shim, bound to the shared touch clock and the shared init state.
        PosUiContext posUi = new PosUiContext(f.pos().driver(), f.posShim(), widgetText)
                .withTimings(f.posUiTimings())
                // The Python's print count is the serial printer's; with none wired it reads -1, as its does.
                .withPrintCount(run -> f.printer() == null ? -1 : f.printer().receiptCount())
                .withInits(inits)
                .withTouchPacer(pacer);

        // Sale: the screen over the page objects, the pumps, and the petroapps scanner data.
        // Its receipt is the run's printed one (the Python's run.pos.receipt()), not the ticket on screen.
        SaleContext sale = new SaleContext(SaleScreen.of(f.pos(), f.receipts()), f.saleRmi(), pumps,
                scannerData(f.petroapps()), LastTlog.VALIDATED, DevicePauses.REAL, pacer, f.saleTiming());

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
                reportRegister(f, widgetText), cloudSender, closeRecovery, () -> Optional.ofNullable(f.buypassV2()),
                folders.configurationFiles(), inits, Waits.production());

        ValidationContext validation = new ValidationContext(f.receipts(), dcrReceipts(f.dcr()),
                FixtureRequirements::printsAtDcr, posTransactions(f),
                CommanderSeams.reads(f.commander()), f.packets() == null ? unavailablePackets() : f.packets(),
                folders.references(),
                f.environment()::get,
                // The receipt and TLog waits' abort, the Python's DialogWatch(run).
                run -> PosUiFamily.dialogWatch(run, posUi));

        // The eight families, in the Python's order.
        PosUiFamily.register(registry, posUi);
        SaleFamily.register(registry, sale);
        DevicesFamily.register(registry, devices);
        HttpFamily.register(registry, http);
        CloudSettleFamily.register(registry, cloudSettle);
        ConfigurationFamily.register(registry, configuration);
        InitsFamily.register(registry, new InitsContext(inits));
        ValidationFamily.register(registry, validation);

        // The BDD-only actions press the POS through the POS UI family's own TOUCH_EVENT handler, so they share its
        // pacing and its dead-end handling; the handler is taken from the registry, not built a second time.
        ActionHandler touch = registry.handlerFor("TOUCH_EVENT").orElseThrow(
                () -> new IllegalStateException("the POS UI family registers TOUCH_EVENT before the BDD family"));
        BddFamily.register(registry, BddContext.of(touch, BddScreen.overPos(f.pos().driver())));

        // The step library's own phrases, for a .feature written in them rather than in PATS action phrases.
        LibraryStepFamily.register(registry, f);

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
        PinpadView pinpad = pinpadView(f.pinpad());
        FuelPumpsView pumps = f.fuelPumps() == null ? unavailablePumps() : pumpsView(f.fuelPumps());
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

    /**
     * The POS UI family's view of the case's pinpad: a physical (VATS) pinpad's settle and last decline, so a SLEEP
     * after a pinpad action ends on the terminal and a receipt wait ends early on a decline; any other pinpad keeps
     * the defaults.
     */
    public static PinpadView pinpadView(com.verifone.cat.pos.pinpad.PinpadDevice device) {
        if (device instanceof com.verifone.cat.devices.vats.PhysicalPinpad physical) {
            return new PinpadView() {
                @Override
                public java.util.Optional<Boolean> settle(Duration seconds) {
                    return java.util.Optional.of(physical.settle(seconds));
                }

                @Override
                public java.util.OptionalLong declinedAtNanos() {
                    return physical.declinedAtNanos();
                }
            };
        }
        return new PinpadView() {
        };
    }

    /**
     * The report actions' register: the pos-shim's keys (the Python's {@code run.rmi}, its before-action the driver's
     * overlay clear, as {@code pos/plugin.py} wires it) and the POS screen's reads, paced by the sale timing's
     * {@code TOUCH_SETTLE} and {@code OVERLAY_SEEN_PAUSE}.
     */
    static RegisterPos reportRegister(HandlerFixtures f, Function<String, String> widgetText) {
        Pos pos = f.pos();
        LegacyRmi rmi = new LegacyRmi(f.posShim(), () -> pos.driver().clearBlockingOverlays(null));
        return new LegacyRegister(rmi, SaleScreen.of(pos, f.receipts()), widgetText, f.saleTiming().touchSettle(),
                f.saleTiming().overlaySeenPause(), DevicePauses.REAL::pause);
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

    /** The POS UI family's view of the run's pumps: the fuel panel's tile press waits on the same client the sale uses. */
    private static FuelPumpsView pumpsView(FuelPumps pumps) {
        return new FuelPumpsView() {
            @Override
            public boolean waitForPostedSale(int pump, Duration timeout) {
                return pumps.waitForPostedSale(pump, timeout);
            }

            @Override
            public boolean lifted(int pump) {
                return pumps.lifted(pump);
            }

            @Override
            public String state(int pump) {
                return pumps.state(pump).orElse("");
            }
        };
    }

    /**
     * The DCR's receipts for the validations, or null when the run has no DCR (the validation then reads the POS's own
     * receipt, as it did before a DCR was wired). The DCR's text is parsed as a printed receipt.
     */
    static ValidationContext.DcrReceipts dcrReceipts(DcrDevice dcr) {
        if (dcr == null) {
            return null;
        }
        return new ValidationContext.DcrReceipts() {
            @Override
            public long printCount() {
                return dcr.printCount();
            }

            @Override
            public Receipt receiptSince(long mark, double timeoutSeconds) {
                String text = dcr.receiptSince((int) mark, Duration.ofMillis(Math.round(timeoutSeconds * 1000)));
                return Receipt.printed(text, Source.SERIAL); // as printed: the Python compares the string
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

    /**
     * {@code VALIDATE_TRANSACTION}'s POS reads, as the Python's {@code pos} fixture wires them: the transaction numbers
     * the Commander lists ({@code vtransnumlist}, newest first) and its TLog of one ({@code vtransdetails}), behind
     * {@link Pos#newestTransaction} and {@link Pos#newestTlog}. PATS's {@code TransactionCommitNumber} is the last sale
     * the Commander committed. The Python's {@code abort} (its dialog watch) ends the wait for a newer one early, and
     * the newest there is is read. A run without a Commander reads the stand-in, which fails by name.
     */
    static ValidationContext.PosTransactions posTransactions(HandlerFixtures f) {
        Pos reads = new Pos(f.pos().driver(), f.receipts(), number -> f.commander().fetchTlog(number),
                () -> f.commander().listTransactionNumbers());
        return new ValidationContext.PosTransactions() {
            @Override
            public String newestTransactionNumber() {
                return reads.newestTransaction();
            }

            @Override
            public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds) {
                return newestTlog(after, timeoutSeconds, null);
            }

            @Override
            public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds, Supplier<String> abort) {
                Element tlog = (Element) reads.newestTlog(after, Duration.ofMillis(Math.round(timeoutSeconds * 1000)),
                        abort);
                return new ValidationContext.PosTlog(tlog, reads.newestRead());
            }
        };
    }

    /** The folder a simulator writes the packets PATS validates into: {@code <tools>/<DEVICE>/packetsForValidation}. */
    public static final String PACKET_FOLDER = "packetsForValidation";

    /**
     * The simulators' captured packets, as the Python's {@code _packet_dir}, {@code _reset_packets} and
     * {@code _validate_packets} read them: each device's {@code <tools root>/<DEVICE>/packetsForValidation} folder,
     * listed, and emptied by a reset. FEPSIM v2 serving Buypass keeps its own folder, which this port does not reach
     * ({@code devices.fepsim_legacy} is not bound at launch): with {@code buypassV2} given, BUYPASS's packets are
     * unavailable rather than read from the wrong folder.
     *
     * <p>Not for a fake-POS run: a reset deletes the files in a lab simulator's folder.
     *
     * <p>UNVERIFIED AGAINST HARDWARE (Java port).
     */
    public static ValidationContext.PacketSource packetFolders(Path toolsRoot, BuypassV2 buypassV2) {
        Objects.requireNonNull(toolsRoot, "toolsRoot");
        return new ValidationContext.PacketSource() {
            @Override
            public List<Path> captured(String device) {
                Path folder = folder(device);
                if (!java.nio.file.Files.isDirectory(folder)) {
                    return List.of();
                }
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(folder)) {
                    return files.filter(java.nio.file.Files::isRegularFile).toList();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("could not list " + folder, e);
                }
            }

            @Override
            public void reset(String device) {
                for (Path file : captured(device)) {
                    try {
                        java.nio.file.Files.deleteIfExists(file);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException("could not delete " + file, e);
                    }
                }
            }

            private Path folder(String device) {
                if (buypassV2 != null && "BUYPASS".equals(device)) {
                    throw new DeviceUnavailable("FEPSIM v2's packets",
                            "BUYPASS is served by FEPSIM v2 this run, whose packet folder is not ported");
                }
                return toolsRoot.resolve(device).resolve(PACKET_FOLDER);
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
