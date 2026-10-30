package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.pos.pages.FunctionMenu;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.rig.Rig;

/**
 * The {@link PosScreen} of a rig's primary register: each call attaches its own CDP session ({@link PosDriver#connect})
 * and closes it, as the Python's {@code with connect_pos(rig.primary_register, ...)} does in every rung. Ported from
 * {@code petrotest/recovery/plugin.py} ({@code _live_soft_cancel}, {@code _press_ok_message}, {@code _press_sco_cancel},
 * {@code _cancel_open_message}, {@code _live_void}) and {@code petrotest/devices/context.py} ({@code live_pos_idle},
 * {@code live_at_login}, {@code live_signed_on}).
 *
 * <p>A session of its own, not the test run's driver: the ladder runs each rung on a bounded worker thread, and a rung
 * that overruns keeps running while the next starts; Playwright's objects must not be shared across threads that way.
 *
 * <p>{@code _live_void}'s VATS pinpad abort ({@code _abort_pinpad_payment}) runs through
 * {@code launch.VatsRecovery} on the run's open VATS session, if there is one; on any other rig it does nothing.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no rung here has run against a register.
 */
public final class LivePosScreen implements PosScreen {

    private static final Logger LOG = LoggerFactory.getLogger(LivePosScreen.class);

    /** The Python {@code MAX_OK_MESSAGES}: how many OK-only messages, one after another, SOFT presses away. */
    static final int MAX_OK_MESSAGES = 3;

    /** The Python's 5 s: a press's wait, and the wait for the screen to change after it. */
    static final Duration PRESS_WAIT = Duration.ofSeconds(5);

    private static final Duration POLL = Duration.ofMillis(250);

    private final Supplier<PosDriver> connect;

    /** @param connect opens a CDP session on the register; each call's driver is closed after the call */
    public LivePosScreen(Supplier<PosDriver> connect) {
        this.connect = connect;
    }

    /** The rig's primary register, over CDP, with the rig's timeouts. Building it connects to nothing. */
    public static LivePosScreen forRig(Rig rig) {
        RegisterEndpoint endpoint = RegisterEndpoint.of(rig.primaryRegister());
        Timeouts timeouts = Timeouts.of(rig.timeouts());
        return new LivePosScreen(() -> PosDriver.connect(endpoint, timeouts, null, null, null, false));
    }

    private <T> T withDriver(Function<PosDriver, T> work) {
        try (PosDriver driver = connect.get()) {
            return work.apply(driver);
        }
    }

    @Override
    public boolean idle() {
        // A lane in the SCO app never shows the POS express panel: its welcome screen is its testable state.
        return withDriver(driver -> new Pos(driver).isIdle()
                || driver.isPresent(Locators.WELCOME_SCREEN_VIEW_LOGO_PANEL));
    }

    @Override
    public boolean atLogin() {
        return withDriver(driver -> new Pos(driver).atLogin());
    }

    /**
     * {@code live_pos_drawn}: the login screen, or the sales screen's CSR key whatever it reads. A register still
     * starting (CDP down, a page mid-load) is "not yet".
     */
    @Override
    public boolean drawn() {
        try {
            return withDriver(driver -> {
                Pos pos = new Pos(driver);
                return pos.atLogin() || pos.expressPanel().csrFunction().isPresent();
            });
        } catch (RuntimeException e) {
            LOG.debug("POS not drawn yet: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean signedOn() {
        return withDriver(driver -> {
            Pos pos = new Pos(driver);
            if (pos.atLogin()) {
                return false;
            }
            if (pos.expressPanel().csrFunction().isPresent()) {
                return true;
            }
            throw new DeviceError(driver.registerId() + " shows neither its login screen nor the sales screen's CSR "
                    + "key, so whether a cashier is signed on cannot be read");
        });
    }

    @Override
    public void softCancel() {
        withDriver(driver -> {
            pressOkMessage(driver);
            pressScoCancel(driver);
            FunctionMenu menu = new Pos(driver).functionMenu();
            Duration wait = driver.timeouts().defaultWait();
            menu.closeToMainMenu(wait);
            if (!menu.headers().isEmpty()) {
                menu.close(wait);
            }
            return null;
        });
    }

    @Override
    public String voidOpenSale() {
        return withDriver(driver -> {
            Pos pos = new Pos(driver);
            if (pos.isIdle()) {
                return "idle";
            }
            Duration wait = driver.timeouts().defaultWait();
            if (pos.reviewOverlay().title().isPresent()) {
                pos.reviewOverlay().close(wait);
            }
            // _live_void's _abort_pinpad_payment: a pinpad: vats rig's open terminal payment ended first.
            com.verifone.cat.launch.VatsRecovery.abortPinpadPayment(driver);
            cancelOpenMessage(driver);
            String done = pos.clearOpenSale(wait);
            LOG.info("recovery VOID: open sale {}", done);
            return done;
        });
    }

    /**
     * {@code _press_ok_message}: press OK on an OK-only message, which no other rung gets past. A message whose button
     * reads anything else is a question and is left; a nag ({@link PosDriver#NAG_OVERLAY_TITLES}) is the driver's.
     */
    static void pressOkMessage(PosDriver driver) {
        List<Locator> buttons = List.of(Locators.OVERLAY_MSG_VIEW_OK_BTN, Locators.OVERLAY_MSG_VIEW_CANCEL_BTN);
        for (int i = 0; i < MAX_OK_MESSAGES; i++) {
            String message = title(driver);
            String upper = message.toUpperCase(Locale.ROOT);
            if (message.isEmpty() || PosDriver.NAG_OVERLAY_TITLES.stream().anyMatch(upper::contains)) {
                return;
            }
            Locator ok = null;
            for (Locator button : buttons) {
                if (driver.elementStates(button).stream().anyMatch(s -> s.visible() && "OK".equals(s.text()))) {
                    ok = button;
                    break;
                }
            }
            if (ok == null) {
                return;
            }
            LOG.warn("recovery SOFT: pressing OK on '{}'", message);
            driver.press(ok, PRESS_WAIT);
            try {
                PollUntil.until(() -> !title(driver).equals(message), PRESS_WAIT, POLL, "'" + message + "' gone");
            } catch (DriverTimeout stillUp) {
                return; // pressing again could answer whatever replaces it
            }
        }
    }

    /** {@code _press_sco_cancel}: on a lane in the SCO app, press its overlay's Cancel until none is up. */
    static void pressScoCancel(PosDriver driver) {
        for (int i = 0; i < MAX_OK_MESSAGES; i++) {
            if (!driver.anyVisible(Locators.SCO_OVERLAY_MSG_VIEW_CANCEL_BUTTON)) {
                return;
            }
            String before = driver.screenText();
            driver.press(Locators.SCO_OVERLAY_MSG_VIEW_CANCEL_BUTTON, null);
            try {
                PollUntil.until(() -> !driver.screenText().equals(before), PRESS_WAIT, POLL,
                        "the SCO screen to change after Cancel");
            } catch (DriverTimeout unchanged) {
                return;
            }
        }
    }

    /** {@code _cancel_open_message}: press a message's Cancel, which a Void Ticket tap does not get past. */
    static void cancelOpenMessage(PosDriver driver) {
        Locator button = Locators.OVERLAY_MSG_VIEW_CANCEL_BTN;
        if (driver.elementStates(button).stream().anyMatch(s -> s.visible() && "Cancel".equals(s.text()))) {
            driver.press(button, PRESS_WAIT);
        }
    }

    /** The shown message's title, or "" when none shows. */
    private static String title(PosDriver driver) {
        for (PosDriver.ElementState state : driver.elementStates(Locators.OVERLAY_MSG_VIEW_TITLE)) {
            if (state.visible()) {
                return state.text();
            }
        }
        return "";
    }
}
