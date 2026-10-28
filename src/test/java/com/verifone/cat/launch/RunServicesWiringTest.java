package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Page;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.forecourt.DcrDeviceAdapter;
import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.FuelPumpsAdapter;
import com.verifone.cat.devices.forecourt.PetroDcr;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.handlers.posui.FuelPumpsView;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.verifone.cat.devices.legacyrmi.LegacySaleRmi;
import com.verifone.cat.devices.printer.PrinterPort;
import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.handlers.HandlerFixtures;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.RigLoader;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run's devices, built from a test rig's hosts. Every client is constructed and none is connected: the rig uses
 * documentation names (RFC 2606 {@code .invalid}), so nothing here can reach a real box even by mistake.
 */
class RunServicesWiringTest {

    /** A rig with a ViperPay pinpad, a Petro forecourt and a printed register, on documentation names. */
    static final String RIG = """
            name: bench
            commander:
              host: commander.invalid
              ssh_user: root
              eps_port: 41107
              ssh_key_path_env: PETROTEST_TEST_SSH_KEY
            registers:
              - id: topaz101
                host: register.invalid
                cdp_port: 9222
                printer_port: COM7
            controller:
              host: controller.invalid
            pinpad: viperpay
            forecourt: petro
            pos_shim: true
            timeouts:
              prompt_wait: 45s
              fuel_complete: 120s
            build:
              expected_version: "057.02"
            """;

    /** The same rig with no forecourt: no pumps and no DCR. */
    static final String RIG_NO_FORECOURT = RIG.replace("forecourt: petro\n", "");

    @TempDir
    Path dir;

    private LoadedRig load(String yaml) throws IOException {
        Files.writeString(dir.resolve("bench.yaml"), yaml);
        return RigLoader.loadResolved("bench", dir);
    }

    @Test
    void theClientsAreTheRigsOwnHostsAndPortsAndConnectToNothing() throws IOException {
        BrowserConnector.Clients c = BrowserConnector.clients(load(RIG).rig(), true);

        assertEquals("register.invalid", c.shim().baseUri().getHost());
        assertEquals(8801, c.shim().baseUri().getPort());
        assertEquals("commander.invalid", c.commander().commanderHost());
        assertEquals(PdkShim.COMMANDER_READ_TIMEOUT, c.commander().timeout());

        assertEquals("controller.invalid", c.viperPay().simulatorHost());
        assertEquals(9001, c.viperPay().simulatorPort());
        assertEquals(9002, c.viperPay().listenPort(), "unstarted: the port is the one asked for, not bound");

    }

    /**
     * Run f6b5c0596966: a rig run had no recovery target, so no case after a failed one was recovered. The rig's own
     * target is built over its hosts (shim, DOM, preflight) and connects to nothing until a rung runs.
     */
    @Test
    void aRigRunsRecoveryTargetIsTheRigsAndConnectsToNothing() throws IOException {
        com.verifone.cat.recovery.RecoveryTarget target = BrowserConnector.recoveryTarget(load(RIG).rig(), "run-1");

        assertInstanceOf(com.verifone.cat.recovery.LiveRecoveryTarget.class, target);
        assertEquals("bench", target.rigName());
    }

    @Test
    void thePinpadIsLeftOutWhenTheRunHasOneAndTheForecourtWhenTheRigHasNone() throws IOException {
        LoadedRig rig = load(RIG);
        assertNull(BrowserConnector.clients(rig.rig(), false).viperPay(), "a run with a double keeps the double");

        BrowserConnector.Clients noForecourt = BrowserConnector.clients(load(RIG_NO_FORECOURT).rig(), true);
        assertNotNull(noForecourt.viperPay());
    }

    /** Petro's pumps on documentation names, built and not started: step 5's session starts the real ones. */
    private static PetroPumps unstartedPumps() {
        return new PetroPumps("controller.invalid", PetroPumps.PATS_FUEL_PORT, PetroPumps.PETRO_FUEL_PORT, null,
                ForecourtTimeouts.of(Timeouts.defaults()));
    }

    /** Petro's DCR 1 on documentation names, built and not started. */
    private static PetroDcr unstartedDcr() {
        return new PetroDcr("controller.invalid", 1, PetroDcr.PATS_DCR_PORT, PetroDcr.PETRO_DCR_PORT,
                ForecourtTimeouts.of(Timeouts.defaults()));
    }

    @Test
    void theHandlerFixturesAreTheRealClientsOverTheRunsServices() throws IOException {
        LoadedRig rig = load(RIG);
        BrowserConnector.Clients c = BrowserConnector.clients(rig.rig(), true);
        TopazPrinter printer = new TopazPrinter(new SilentPort(), "COM7", () -> List.of("COM7"));
        RunServices services = services(rig, c.shim(), c.commander(), c.viperPay(), printer,
                new DcrDeviceAdapter(unstartedDcr()), new FuelPumpsAdapter(unstartedPumps()));

        HandlerFixtures f = StandardRegistry.fixtures(services, Map.of());

        assertSame(c.shim(), f.posShim());
        assertSame(c.commander(), f.commander());
        assertSame(c.viperPay(), f.pinpad());
        assertEquals("commander.invalid", f.commanderAccess().host());
        assertInstanceOf(LegacySaleRmi.class, f.saleRmi());
        assertInstanceOf(PrinterReceipts.class, f.receipts());
        assertSame(printer, f.printer());
        assertInstanceOf(DcrDeviceAdapter.class, f.dcr());
        assertInstanceOf(FuelPumpsAdapter.class, f.fuelPumps());
        assertNull(f.cloudSite(), "no C-Site settings in this environment");
    }

    @Test
    void theRegistryHasItsFamiliesOverTheRunsServices() throws IOException {
        LoadedRig rig = load(RIG);
        BrowserConnector.Clients c = BrowserConnector.clients(rig.rig(), true);
        RunServices services = services(rig, c.shim(), c.commander(), c.viperPay(), null,
                new DcrDeviceAdapter(unstartedDcr()), new FuelPumpsAdapter(unstartedPumps()));

        ActionRegistry registry = new StandardRegistry().create(services);

        assertTrue(registry.handlerFor("TOUCH_EVENT").isPresent());
    }

    @Test
    void aRunWithoutADeviceHasItsUnwiredDefaultsAndTheScreenForTheReceipt() throws IOException {
        RunServices services = services(null, null, null, null, null, null, null);
        HandlerFixtures f = StandardRegistry.fixtures(services, Map.of());

        assertInstanceOf(DomReceiptCapture.class, f.receipts());
        assertSame(SaleRmi.UNAVAILABLE, f.saleRmi());
        assertNull(f.printer());
        assertNull(f.dcr());
        assertNull(f.fuelPumps());
        assertNull(f.commanderAccess());
        assertNull(f.cloudSite());
    }

    @Test
    void theFixturesACaseAsksForAreTheRunsDevicesThroughStandardHandlers() throws IOException {
        LoadedRig rig = load(RIG);
        BrowserConnector.Clients c = BrowserConnector.clients(rig.rig(), true);
        DcrDevice dcr = new DcrDeviceAdapter(unstartedDcr());
        RunServices services = services(rig, c.shim(), c.commander(), c.viperPay(), null, dcr,
                new FuelPumpsAdapter(unstartedPumps()));

        RegistryFactory.Built built = new StandardRegistry().build(services);
        FixtureSource fixtures = built.fixtures();

        assertNotNull(built.handlers(), "one HandlerFixtures for the registry and the fixtures");
        assertSame(c.shim(), fixtures.fixture(FixtureName.LEGACY_RMI), "LEGACY_RMI is the pos-shim");
        assertSame(dcr, fixtures.fixture(FixtureName.DCR), "DCR is the session's DCR 1");
        assertInstanceOf(FuelPumpsView.class, fixtures.fixture(FixtureName.FUEL_PUMPS));
        assertSame(c.commander(), fixtures.fixture(FixtureName.COMMANDER_CONFIG));
        assertSame(built.handlers().pos(), fixtures.fixture(FixtureName.POS));
        assertEquals(EnumSet.allOf(FixtureName.class), TestsRunStep.declaredFixtures(services));
    }

    @Test
    void aSurfaceTheRunLacksFailsByNameRatherThanHandingOutAStandIn() throws IOException {
        RunServices noDevices = services(null, null, null, null, null, null, null);
        FixtureSource fixtures = new StandardRegistry().build(noDevices).fixtures();

        assertThrows(DeviceUnavailable.class, () -> fixtures.fixture(FixtureName.LEGACY_RMI));
        assertThrows(DeviceUnavailable.class, () -> fixtures.fixture(FixtureName.PINPAD));
        assertThrows(DeviceUnavailable.class, () -> fixtures.fixture(FixtureName.COMMANDER_CONFIG));
        DcrDevice dcr = (DcrDevice) fixtures.fixture(FixtureName.DCR);
        assertThrows(DeviceUnavailable.class, () -> dcr.waitForPrompt("WELCOME", null));
        assertEquals(EnumSet.of(FixtureName.POS, FixtureName.CONFIGURATIONS),
                TestsRunStep.declaredFixtures(noDevices));

        LoadedRig noShim = load(RIG.replace("pos_shim: true\n", ""));
        BrowserConnector.Clients c = BrowserConnector.clients(noShim.rig(), true);
        FixtureSource shimless = new StandardRegistry().build(services(noShim, c.shim(), c.commander(), null, null,
                null, null)).fixtures();
        DeviceUnavailable refused = assertThrows(DeviceUnavailable.class,
                () -> shimless.fixture(FixtureName.LEGACY_RMI));
        assertTrue(refused.getMessage().contains("no pos_shim"), refused.getMessage());
    }

    @Test
    void closingAConnectorThatConnectedNothingIsSafe() {
        new BrowserConnector().close();
        new BrowserConnector().close();
    }

    /**
     * The run's services over a POS page that refuses every call: the registry is built, nothing is driven. A null
     * {@code rig} is a run with no rig.
     */
    private RunServices services(LoadedRig rig, PosShimClient shim, PdkShim commander, PinpadDevice pinpad,
            TopazPrinter printer, DcrDevice dcr, FuelPumps pumps) throws IOException {
        Page refusing = (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[] {Page.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no browser in this test: Page." + method.getName());
                });
        PosDriver driver = PosDriver.builder(refusing).registerId("topaz101").settleOverlays(false).build();
        return new RunServices(null, LaunchFixtures.paths(dir), "run-wiring", dir, rig, driver, shim, commander,
                pinpad, null, null, printer, dcr, pumps);
    }

    /** A printer port that never has bytes: the printer is built, never started. */
    private static final class SilentPort implements PrinterPort {

        @Override
        public int read(byte[] buffer) {
            return 0;
        }

        @Override
        public void write(byte[] data) {
        }

        @Override
        public void close() {
        }
    }
}
