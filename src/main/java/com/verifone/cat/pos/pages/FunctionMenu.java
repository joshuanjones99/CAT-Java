package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.List;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code FunctionMenu}, the CSR function list. Port of the Python {@code pages.menus.FunctionMenu}.
 *
 * <p>Sub-menu item ids are positional and repeat across stacked menus, so below the Main Menu prefer
 * {@link #selectByText} over {@link #select}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class FunctionMenu extends BasePage {

    /** The CSR function list's root header. Sub-menus stack on top of it. */
    public static final String MAIN_MENU = "Main Menu";

    private static final Duration POLL = Duration.ofSeconds(1);

    public FunctionMenu(PosDriver driver) {
        super(driver);
    }

    public BaseElement item(int functionId) {
        return element(Locators.FUNCTION_MENU_N, functionId);
    }

    public void select(int functionId, Duration timeout) {
        item(functionId).tap(timeout);
    }

    /**
     * Press the topmost open menu's item whose label starts with {@code label}. Returns the pressed
     * element's text.
     */
    public String selectByText(String label, Duration timeout) {
        return driver().pressLastVisible(Locators.FUNCTION_MENU_N, label, timeout);
    }

    public BaseElement header() {
        return element(Locators.FUNCTION_MENU_HEADER_LABEL);
    }

    /** Every open menu's header, bottom of the stack first. Empty when none is open. */
    public List<String> headers() {
        return driver().visibleTexts(Locators.FUNCTION_MENU_HEADER_LABEL);
    }

    public void close(Duration timeout) {
        element(Locators.FUNCTION_MENU_CROSS_BTN).tap(timeout);
    }

    /**
     * Close stacked sub-menus, topmost first, until at most the Main Menu is open. Each look takes the
     * screen as it is: the register can close the stack by itself, so the close button is pressed only
     * while a sub-menu still shows.
     */
    public void closeToMainMenu(Duration timeout) {
        Duration budget = timeout != null ? timeout : driver().timeouts().defaultWait();
        PollUntil.until(() -> {
            List<String> open = headers();
            if (open.isEmpty() || open.equals(List.of(MAIN_MENU))) {
                return true;
            }
            driver().pressLastVisibleOnce(Locators.FUNCTION_MENU_CROSS_BTN, "");
            return false;
        }, budget, POLL, "CSR sub-menus to close");
    }
}
