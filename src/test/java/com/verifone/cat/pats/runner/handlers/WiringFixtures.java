package com.verifone.cat.pats.runner.handlers;

import com.microsoft.playwright.Page;
import com.verifone.cat.closeday.CommanderAccess;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.devices.DevicesTiming;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.posui.PosUiTimings;
import com.verifone.cat.pats.runner.handlers.sale.SaleTiming;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.receipt.StaticReceiptSource;
import com.verifone.cat.posshim.PosShimClient;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The run's {@link HandlerFixtures} for the wiring tests: the real Java objects where they are pure Java, the fake POS's
 * page where a browser gives one, and short waits so a failing step does not sit out PATS's.
 *
 * <p>Nothing here reaches a device: the Commander and the pos-shim are built, not connected, and the shim points at a
 * port the caller chooses (a closed one, so a read fails by name).
 */
final class WiringFixtures {

    private WiringFixtures() {
    }

    /** Fixtures over a page that refuses every call: enough to build the registry, not to run a case on it. */
    static HandlerFixtures unbrowsed(Path root, PinpadDevice pinpad) {
        Page refusing = (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[] {Page.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no browser in this test: Page." + method.getName());
                });
        return over(refusing, root, pinpad, 1, null);
    }

    /**
     * Fixtures over a live page (the fake POS), with the pos-shim pointed at {@code shimPort} and the Commander's access
     * (when given) for the close-day and cloud-settle seams.
     */
    static HandlerFixtures over(Page page, Path root, PinpadDevice pinpad, int shimPort, CommanderAccess access) {
        PosDriver driver = PosDriver.builder(page).registerId("local").settleOverlays(false).build();
        return new HandlerFixtures(
                new Pos(driver),
                new PosShimClient("localhost", shimPort, "test", Duration.ofSeconds(2)),
                new StaticReceiptSource(""),
                pinpad,
                PdkShim.onPort("localhost", shimPort, Duration.ofSeconds(2), () -> ""),
                access,
                new PetroappsFiles(root.resolve("petroapps")),
                root,
                root.resolve("results"),
                Map.of(),
                InitsState.production(root),
                null,
                null,
                null,
                null,
                null,
                shortPosUi(),
                shortSale(),
                shortDevices());
    }

    /** The POS UI waits, shortened: every wait keeps its place in the order, and none is PATS's length. */
    static PosUiTimings shortPosUi() {
        Duration s = Duration.ofSeconds(3);
        Duration ms = Duration.ofMillis(200);
        return new PosUiTimings(s, s, s, Duration.ofSeconds(2), Duration.ofSeconds(2), ms, ms, ms,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(2), ms, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(100),
                ms);
    }

    static SaleTiming shortSale() {
        Duration ms = Duration.ofMillis(200);
        return new SaleTiming(ms, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(1), ms, Duration.ofMillis(100));
    }

    static DevicesTiming shortDevices() {
        Duration ms = Duration.ofMillis(200);
        return new DevicesTiming(Duration.ofSeconds(3), Duration.ofSeconds(3), ms, ms);
    }
}
