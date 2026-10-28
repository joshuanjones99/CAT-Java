package com.verifone.cat.launch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.devices.viperpay.ViperPayPinpad;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link RunServices.Connector}: the POS driver, the pos-shim and Commander clients, the rig's pinpad and
 * the receipt printer, all from the rig file. Petro Simulator Suite's pumps and DCR are step 5's session listeners
 * ({@link PetroSession}); the connector passes on the ones the base already has.
 *
 * <ul>
 *   <li><b>Fake POS</b> (the run has {@code fakePosUrl}): headless Chromium opens the fake POS's page on loopback and a
 *       {@link PosDriver} is built over it. Nothing else is connected: the shim and the Commander stay absent, so their
 *       actions fail by name.</li>
 *   <li><b>Rig</b> (no fake POS, a rig loaded): Playwright attaches over CDP to the rig's register {@code cdp_port}
 *       ({@code connectOverCDP}), the register's page is the POS page. The pos-shim and the Commander's PDK shim are
 *       built over the rig's hosts whether or not the page attaches, so the devices still answer when the browser does
 *       not. The rig's pinpad is ViperPay's when the rig says {@code pinpad: viperpay}; the primary
 *       register's {@code printer_port} is
 *       opened as its receipt printer. Each is logged and left absent when it cannot start, so its actions fail by
 *       name.</li>
 * </ul>
 *
 * <p>{@link #clients} builds the network clients without connecting or listening, so the wiring is testable on
 * documentation names. {@link #connect} starts them. {@link #close} releases everything {@link #connect} opened, the
 * printer's port included, as the Python's tests_run step hands the port over at the end of the run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: this code path has not been run against a register, a Commander or a forecourt. It
 * is written from the rig schema and the Python's {@code petrotest.pos}, {@code devices.context} and {@code pos.plugin}.
 */
public final class BrowserConnector implements RunServices.Connector {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.launch");

    /** The pos-shim's timeout on a rig, the PATS request timeout's order of magnitude. */
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(30);

    /** The client name the pos-shim and the Commander see. */
    static final String CLIENT = "CAT";

    /** The register id a fake POS run's driver is named by (the Python's fake-POS register). */
    static final String FAKE_REGISTER = "local";

    /** The ViperPay pinpad's id, as PATS names its one pinpad. */
    static final String VIPERPAY_PINPAD_ID = "1";

    /** DCR 1, the one DCR a rig drives. */
    static final int DCR_NUMBER = 1;

    /**
     * The network clients a rig run uses, built over the rig's hosts and not started. A null field is a surface the rig
     * does not have. {@link BrowserConnector#connect} starts the pinpad, pumps and DCR listeners.
     */
    record Clients(PosShimClient shim, PdkShim commander, ViperPayPinpad viperPay) {
    }

    private Playwright playwright;
    private Browser browser;
    private ViperPayPinpad viperPay;
    private TopazPrinter printer;

    @Override
    public RunServices connect(RunServices base) {
        if (base.fakePosUrl() != null) {
            try {
                return fakePos(base);
            } catch (PlaywrightException e) {
                LOG.warn("could not open the POS page; the POS actions will fail by name: {}", e.getMessage());
                return base;
            }
        }
        if (base.rig() != null) {
            return rig(base);
        }
        return base;
    }

    private RunServices fakePos(RunServices base) {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        Page page = browser.newPage();
        page.navigate(base.fakePosUrl().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        PosDriver driver = PosDriver.builder(page).registerId(FAKE_REGISTER).settleOverlays(false).build();
        return with(base, driver, null, null, base.pinpad(), null, base.dcr(), base.fuelPumps());
    }

    private RunServices rig(RunServices base) {
        Rig rig = base.rig().rig();
        Rig.Register register = rig.primaryRegister();
        Clients clients = clients(rig, base.pinpad() == null);

        PosDriver driver = openPos(register);
        PinpadDevice pinpad = base.pinpad();
        if (clients.viperPay() != null) {
            viperPay = clients.viperPay();
            pinpad = startViperPay(viperPay);
        }
        printer = openPrinter(register);
        // The DCR and the pumps are step 5's session listeners (PetroSession), already in the base.
        return with(base, driver, clients.shim(), clients.commander(), pinpad, printer, base.dcr(), base.fuelPumps());
    }

    /**
     * The rig's network clients, built and not started: the pos-shim and the Commander always; the ViperPay pinpad when
     * the rig has one and {@code wantPinpad}. Constructing one connects to nothing. Petro Simulator Suite's pumps and DCR
     * are not here: step 5's {@link PetroSession} starts them, once PATS's port is released to it.
     */
    static Clients clients(Rig rig, boolean wantPinpad) {
        Timeouts timeouts = Timeouts.of(rig.timeouts());
        String controller = rig.controller().host();
        PosShimClient shim = new PosShimClient(rig.primaryRegister().host(), PosShimClient.DEFAULT_SHIM_PORT, CLIENT,
                SHIM_TIMEOUT);
        PdkShim commander = new CommanderAccess(rig).pdkShim();
        ViperPayPinpad viperPay = wantPinpad && rig.pinpad() == Rig.Pinpad.VIPERPAY
                ? new ViperPayPinpad(controller, VIPERPAY_PINPAD_ID, ViperPayPinpad.DEFAULT_LISTEN_PORT,
                        ViperPayPinpad.DEFAULT_SIMULATOR_PORT, timeouts.promptWait(), timeouts.http())
                : null;
        return new Clients(shim, commander, viperPay);
    }

    /** The POS page over CDP, or null when the browser cannot attach (logged; the POS actions then fail by name). */
    private PosDriver openPos(Rig.Register register) {
        String endpoint = "http://" + register.host() + ":" + register.cdpPort();
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().connectOverCDP(endpoint);
            Page page = browser.contexts().get(0).pages().get(0);
            return PosDriver.builder(page).registerId(register.id()).build();
        } catch (PlaywrightException e) {
            LOG.warn("could not open the POS page at {}; the POS actions will fail by name: {}", endpoint,
                    e.getMessage());
            return null;
        }
    }

    private static PinpadDevice startViperPay(ViperPayPinpad pinpad) {
        try {
            pinpad.start();
            return pinpad;
        } catch (IOException e) {
            LOG.warn("the ViperPay display port {} is held (PATS running?); the pinpad actions will fail by name: {}",
                    pinpad.listenPort(), e.getMessage());
            pinpad.close();
            return null;
        }
    }

    private static TopazPrinter openPrinter(Rig.Register register) {
        String port = register.printerPort();
        if (port == null || port.isBlank()) {
            return null;
        }
        try {
            return TopazPrinter.open(port);
        } catch (DeviceError e) {
            LOG.warn("the receipt printer on {} did not open; receipts fall back to the screen: {}", port,
                    e.getMessage());
            return null;
        }
    }

    private static RunServices with(RunServices base, PosDriver driver, PosShimClient shim, PdkShim commander,
            PinpadDevice pinpad, ReceiptPrinter receiptPrinter, DcrDevice dcrDevice, FuelPumps fuelPumps) {
        return new RunServices(base.config(), base.paths(), base.runId(), base.runFolder(), base.rig(), driver,
                shim, commander, pinpad, base.fakePosUrl(), base.recovery(), receiptPrinter, dcrDevice, fuelPumps);
    }

    @Override
    public void close() {
        quietly("the ViperPay display port", () -> {
            if (viperPay != null) {
                viperPay.close();
                viperPay = null;
            }
        });
        quietly("the receipt printer's port", () -> {
            if (printer != null) {
                printer.close();
                printer = null;
            }
        });
        quietly("the browser", () -> {
            if (browser != null) {
                browser.close();
                browser = null;
            }
        });
        quietly("Playwright", () -> {
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        });
    }

    /** Runs one release, logging a failure rather than stopping the others. */
    private static void quietly(String what, Runnable release) {
        try {
            release.run();
        } catch (RuntimeException e) {
            LOG.warn("could not release {}: {}", what, e.getMessage());
        }
    }
}
