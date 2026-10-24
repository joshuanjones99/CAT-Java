package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.devices.DeviceUnavailable;

import java.time.Duration;

/**
 * The DCR beside the POS: its card reader, its display and its keys. Ported from the Python
 * {@code DCRManager} that the {@code DCR} fixture hands the devices family.
 *
 * <p>No Java DCR exists yet, so the default {@link #UNAVAILABLE} throws {@link DeviceUnavailable} for every
 * call: a DCR action fails its step rather than passing against nothing.
 */
public interface DcrDevice {

    /** The DCR that is not wired: every call throws {@link DeviceUnavailable}. */
    DcrDevice UNAVAILABLE = new DcrDevice() {
        @Override
        public void insertCard(String card) {
            throw unavailable("insert card " + card);
        }

        @Override
        public void waitForPrompt(String text, Duration timeout) {
            throw unavailable("wait for the prompt " + text);
        }

        @Override
        public void keyPress(String key) {
            throw unavailable("press " + key);
        }

        @Override
        public void press(String functionKey) {
            throw unavailable("press " + functionKey);
        }
    };

    /** Slides a card through the DCR's reader. */
    void insertCard(String card);

    /** Waits until a line of the DCR's display contains {@code text}, ignoring case. */
    void waitForPrompt(String text, Duration timeout);

    /** Presses a numeric key on the DCR. */
    void keyPress(String key);

    /** Presses a DCR function key, named as PATS names it: {@code L1}..{@code L4}, {@code R1}..{@code R4}. */
    void press(String functionKey);

    /** The step failure an unwired DCR gives. */
    static DeviceUnavailable unavailable(String operation) {
        return new DeviceUnavailable("the DCR", operation + ": no DCR is wired to this run");
    }
}
