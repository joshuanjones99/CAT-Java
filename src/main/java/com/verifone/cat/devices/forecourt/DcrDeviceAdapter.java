package com.verifone.cat.devices.forecourt;

import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@link DcrDevice} over a {@link PetroDcr}: the seam the devices family drives the DCR through.
 *
 * <p>{@code keyPress} is PATS's numeric {@code KEY_PRESS} ({@link PetroDcr#keyPress}); {@code press} is a soft key
 * ({@link PetroDcr#press}). A prompt that never shows throws {@link ForecourtWaitExpired}, and so does a receipt that
 * does not arrive ({@link PetroDcr#receiptSince}). {@link #close} releases the DCR's listener.
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

    @Override
    public List<String> promptLines() {
        return dcr.promptLines();
    }

    @Override
    public int printCount() {
        return dcr.printCount();
    }

    @Override
    public String receiptSince(int mark, Duration timeout) {
        return dcr.receiptSince(mark, timeout);
    }

    @Override
    public void close() {
        dcr.close();
    }
}
