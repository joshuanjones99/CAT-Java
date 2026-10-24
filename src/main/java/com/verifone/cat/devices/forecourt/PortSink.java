package com.verifone.cat.devices.forecourt;

import com.verifone.cat.devices.DeviceUnreachable;

import java.io.IOException;
import java.time.Duration;

/**
 * Accepts, and throws away, whatever Petro Simulator Suite pushes to {@code port}.
 *
 * <p>A refused push can freeze the suite's whole fuel side (the UI's port 9706 is listened to only once its Pump
 * Simulator view is opened), so CAT keeps the port. Construction fails with {@link DeviceUnreachable} when the port
 * is taken, by the UI or by CAT's launcher.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PortSink implements AutoCloseable {

    private final int port;
    private final PushListener listener;

    public PortSink(int port) {
        this.port = port;
        try {
            this.listener = new PushListener(port, obj -> { }, Duration.ofSeconds(5), null);
        } catch (IOException e) {
            throw new DeviceUnreachable("the Petro suite's port " + port, "already held: " + e.getMessage(), e);
        }
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        listener.close();
    }
}
