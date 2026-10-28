package com.verifone.cat.closeday;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.ItemPanel;

/**
 * What the day close's register pass ({@link PosRegisterPass}) reads and presses on the register: the
 * driver calls and the {@code Pos} page calls the Python's {@code close_day} makes, and nothing else. The
 * production one is {@link PosRegisterScreen}, over {@link PosDriver} and {@code pos.pages.Pos}; a test
 * passes a fake, so the pass's logic is exercised without a browser.
 *
 * <p>Every method is the Python's call of the same name; a null timeout is the driver's default wait.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface RegisterScreen extends AutoCloseable {

    /** Opens the register. Throws when it cannot be reached; the pass reports that and the close goes on. */
    @FunctionalInterface
    interface Connector {
        RegisterScreen connect();
    }

    // -- the driver's calls ------------------------------------------------------------------

    /** {@code driver.element_states}: {@code (visible, text)} of every match. One look, no wait. */
    List<PosDriver.ElementState> elementStates(Locator locator);

    /** {@code driver.visible_texts}: the text of every visible match. One look, no wait. */
    List<String> visibleTexts(Locator locator);

    /** {@code driver.press}: press once it exists, waiting at most {@code timeout} (null: the default). */
    void press(Locator locator, Duration timeout);

    /** {@code driver.dispatch_press}: press the {@code index}th visible match. No wait. */
    boolean dispatchPress(Locator locator, int index);

    /** {@code driver.clear_blocking_overlays}: the driver's own guard for the busy window and the nags. */
    void clearBlockingOverlays();

    // -- the Pos page's calls ----------------------------------------------------------------

    /** {@code Pos.at_login}: the login screen or its ID/password prompt is showing. */
    boolean atLogin();

    /** {@code Pos.is_idle}: no sale is open. */
    boolean isIdle();

    /** {@code pos.review_overlay.title.is_present()}. */
    boolean reviewOverlayShown();

    /** {@code pos.review_overlay.close()}. */
    void closeReviewOverlay();

    /** {@code Pos.clear_open_sale}: pay a fuel sale off in CASH, void any other; "idle", "settled" or "voided". */
    String clearOpenSale();

    /** {@code Pos.holds_dispensed_fuel}: the open ticket carries a collected fill. No wait. */
    boolean holdsDispensedFuel();

    /** {@code Pos.sign_on_from_login(id, password, till=OPEN_TILL, timeout=MENU_WAIT)}. */
    void signOnFromLogin(String cashierId, String password);

    /** {@code pos.item_panel.select(mop, timeout)}. */
    void selectMop(ItemPanel.MOP mop, Duration timeout);

    /** {@code pos.keypad.ok(timeout)}. */
    void keypadOk(Duration timeout);

    /** {@code Pos.expect_idle(timeout)}: throws when the register is not idle in time. */
    void expectIdle(Duration timeout);

    /** {@code pos.fuel_panel.reserved(pump)}: the pump's reserve text, empty when it holds none. */
    Optional<String> reserved(int pump);

    /** Releases the connection. Never closes the register's browser. Never throws. */
    @Override
    void close();
}
