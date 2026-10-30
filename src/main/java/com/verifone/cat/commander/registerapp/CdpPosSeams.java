package com.verifone.cat.commander.registerapp;

import java.time.Duration;
import java.util.Optional;

import com.microsoft.playwright.Frame;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The register's POS page over CDP, for {@code register-app}: the Python's {@code hosted_app} and
 * {@code _press_pos_panel}, one CDP attach each. Moved here from {@code launch.CommanderStages}, so the
 * {@code register-app} command and step 4 share it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CdpPosSeams implements RegisterApp.PosSeams {

    private final Rig rig;

    public CdpPosSeams(Rig rig) {
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
