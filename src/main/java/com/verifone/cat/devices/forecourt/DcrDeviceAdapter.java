package com.verifone.cat.devices.forecourt;

import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link DcrDevice} over a {@link PetroDcr}: the seam the devices family drives the DCR through.
 *
 * <p>{@code keyPress} is PATS's numeric {@code KEY_PRESS} ({@link PetroDcr#keyPress}); {@code press} is a soft key
 * ({@link PetroDcr#press}). A prompt that never shows throws {@link ForecourtWaitExpired}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class DcrDeviceAdapter implements DcrDevice {

    private final PetroDcr dcr;

    public DcrDeviceAdapter(PetroDcr dcr) {
        this.dcr = Objects.requireNonNull(dcr, "dcr");
    }

    @Override
    public void insertCard(String card) {
        dcr.insertCard(card);
    }

    @Override
    public void waitForPrompt(String text, Duration timeout) {
        dcr.waitForPrompt(text, timeout);
    }

    @Override
    public void keyPress(String key) {
        dcr.keyPress(key);
    }

    @Override
    public void press(String functionKey) {
        dcr.press(functionKey);
    }
}
