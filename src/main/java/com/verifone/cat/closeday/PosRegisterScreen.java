package com.verifone.cat.closeday;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.pos.pages.ItemPanel;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The production {@link RegisterScreen}: the rig's primary register over CDP ({@link PosDriver#connect}, the
 * Python's {@code connect_pos(register, timeouts=..., build_version=..., trace=False)}), with {@link Pos} for the
 * page calls. Each method is one call on the driver or the page; no logic lives here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). No Java run has attached to a register for the day close.
 */
public final class PosRegisterScreen implements RegisterScreen {

    private final PosDriver driver;
    private final Pos pos;

    public PosRegisterScreen(PosDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.pos = new Pos(driver);
    }

    /** Attaches to the rig's primary register, no trace, as the Python's close does. */
    public static PosRegisterScreen connect(Rig rig) {
        PosDriver driver = PosDriver.connect(RegisterEndpoint.of(rig.primaryRegister()), Timeouts.of(rig.timeouts()),
                rig.build().expectedVersion(), null, null, false);
        return new PosRegisterScreen(driver);
    }

    @Override
    public List<PosDriver.ElementState> elementStates(Locator locator) {
        return driver.elementStates(locator);
    }

    @Override
    public List<String> visibleTexts(Locator locator) {
        return driver.visibleTexts(locator);
    }

    @Override
    public void press(Locator locator, Duration timeout) {
        driver.press(locator, timeout);
    }

    @Override
    public boolean dispatchPress(Locator locator, int index) {
        return driver.dispatchPress(locator, index);
    }

    @Override
    public void clearBlockingOverlays() {
        driver.clearBlockingOverlays(null);
    }

    @Override
    public boolean atLogin() {
        return pos.atLogin();
    }

    @Override
    public boolean isIdle() {
        return pos.isIdle();
    }

    @Override
    public boolean reviewOverlayShown() {
        return pos.reviewOverlay().title().isPresent();
    }

    @Override
    public void closeReviewOverlay() {
        pos.reviewOverlay().close(null);
    }

    @Override
    public String clearOpenSale() {
        return pos.clearOpenSale(null);
    }

    @Override
    public boolean holdsDispensedFuel() {
        return pos.holdsDispensedFuel();
    }

    @Override
    public void signOnFromLogin(String cashierId, String password) {
        pos.signOnFromLogin(cashierId, password, Pos.OPEN_TILL, Pos.MENU_WAIT);
    }

    @Override
    public void selectMop(ItemPanel.MOP mop, Duration timeout) {
        pos.itemPanel().select(mop, timeout);
    }

    @Override
    public void keypadOk(Duration timeout) {
        pos.keypad().ok(timeout);
    }

    @Override
    public void expectIdle(Duration timeout) {
        pos.expectIdle(timeout);
    }

    @Override
    public Optional<String> reserved(int pump) {
        return pos.fuelPanel().reserved(pump);
    }

    @Override
    public void close() {
        driver.close();
    }
}
