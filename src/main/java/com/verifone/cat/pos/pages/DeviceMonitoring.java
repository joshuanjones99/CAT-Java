package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The Device Monitoring dashboard, basket view and confirm prompts. Port of the Python
 * {@code petrotest.pos.pages.device_monitoring}.
 *
 * <p>This is not Topaz: it is the embedded secondary web app, reached through the
 * {@code DEVICE_MONITORING} frame. Its selectors are utility classes, not {@code *_View_} IDs,
 * and most XPath references in the corpus sit behind that frame. Which DOM iframe it is remains
 * unverified; the driver raises {@code FrameNotFoundError} rather than match nothing.
 *
 * <p>The Python's {@code index=1} defaults are the no-argument overloads. A null timeout takes the
 * rig's default wait.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class DeviceMonitoring extends BasePage {

    public DeviceMonitoring(PosDriver driver) {
        super(driver);
    }

    // -- dashboard -----------------------------------------------------------

    public BaseElement leftCard() {
        return leftCard(1);
    }

    /** A monitored-device card, left column. */
    public BaseElement leftCard(int index) {
        return element(Locators.XP_DIV_LEFT_CARD_CONTAINER_ROW, index);
    }

    public BaseElement rightCard() {
        return rightCard(1);
    }

    public BaseElement rightCard(int index) {
        return element(Locators.XP_DIV_RIGHT_CARD_CONTAINER_ROW, index);
    }

    public BaseElement deviceId() {
        return deviceId(1);
    }

    public BaseElement deviceId(int index) {
        return element(Locators.XP_DIV_MONITORED_DEVICE_ID_ROW, index);
    }

    public BaseElement registerId() {
        return element(Locators.XP_DIV_REG_ID);
    }

    // -- basket --------------------------------------------------------------

    public BaseElement totalAmount() {
        return totalAmount(1);
    }

    public BaseElement totalAmount(int index) {
        return element(Locators.XP_DIV_TOTAL_AMOUNT_IS_FONT_20_ROW, index);
    }

    public BaseElement amountDue() {
        return element(Locators.XP_DIV_DUE);
    }

    // -- actions and prompts -------------------------------------------------

    /** Void the open ticket: recovery ladder rung 2. */
    public void voidTicket(Duration timeout) {
        element(Locators.XP_BUTTON_TEXT_VOID_TICKET).tap(timeout);
    }

    public void voidLine(Duration timeout) {
        element(Locators.XP_BUTTON_TEXT_VOID_LINE).tap(timeout);
    }

    public BaseElement errorMessage() {
        return element(Locators.XP_DIV_ERROR_PROMPT_MESSAGE_IS_FONT_48);
    }

    /** A snapshot check for preflight and the recovery ladder. No wait. */
    public boolean hasError() {
        return errorMessage().isPresent();
    }
}
