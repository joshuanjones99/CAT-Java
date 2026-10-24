package com.verifone.cat.launch;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.Rig;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link RunServices.Connector}: the POS driver, and the pos-shim and Commander clients when a rig is
 * loaded.
 *
 * <ul>
 *   <li><b>Fake POS</b> (the run has {@code fakePosUrl}): headless Chromium opens the fake POS's page on loopback and a
 *       {@link PosDriver} is built over it. Nothing else is connected: the shim and the Commander stay absent, so their
 *       actions fail by name.</li>
 *   <li><b>Rig</b> (no fake POS, a rig loaded): Playwright attaches over CDP to the rig's register {@code cdp_port}
 *       ({@code connectOverCDP}), the register's page is the POS page, and the pos-shim and the Commander's PDK shim
 *       are built from the rig's hosts. <b>This code path has not been run against a register</b>: it is written from
 *       the rig schema and the Python's {@code petrotest.pos} attach, and is UNVERIFIED AGAINST HARDWARE.</li>
 * </ul>
 *
 * <p>A connection that fails (no Chromium, no register answering) is logged and the services are returned as they
 * came in, so the run's actions fail by name rather than the step crashing. The browser and Playwright are released by
 * {@link #close}, which the Tests Run step calls when the step ends.
 */
public final class BrowserConnector implements RunServices.Connector {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.launch");

    /** The pos-shim's timeout on a rig, the PATS request timeout's order of magnitude. */
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(30);

    /** The client name the pos-shim and the Commander see. */
    static final String CLIENT = "CAT";

    /** The register id a fake POS run's driver is named by (the Python's fake-POS register). */
    static final String FAKE_REGISTER = "local";

    private Playwright playwright;
    private Browser browser;

    @Override
    public RunServices connect(RunServices base) {
        try {
            if (base.fakePosUrl() != null) {
                return fakePos(base);
            }
            if (base.rig() != null) {
                return rig(base);
            }
        } catch (PlaywrightException e) {
            LOG.warn("could not open the POS page; the POS actions will fail by name: {}", e.getMessage());
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
        return with(base, driver, null, null);
    }

    private RunServices rig(RunServices base) {
        Rig rig = base.rig().rig();
        Rig.Register register = rig.register(null);
        playwright = Playwright.create();
        browser = playwright.chromium().connectOverCDP("http://" + register.host() + ":" + register.cdpPort());
        Page page = browser.contexts().get(0).pages().get(0);
        PosDriver driver = PosDriver.builder(page).registerId(register.id()).build();
        PosShimClient shim = new PosShimClient(register.host(), PosShimClient.DEFAULT_SHIM_PORT, CLIENT, SHIM_TIMEOUT);
        PdkShim commander = new PdkShim(rig.commander().host());
        return with(base, driver, shim, commander);
    }

    private static RunServices with(RunServices base, PosDriver driver, PosShimClient shim, PdkShim commander) {
        return new RunServices(base.config(), base.paths(), base.runId(), base.runFolder(), base.rig(), driver,
                shim, commander, base.pinpad(), base.fakePosUrl(), base.recovery());
    }

    @Override
    public void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }
}
