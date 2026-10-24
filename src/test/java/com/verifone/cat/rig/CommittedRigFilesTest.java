package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The Python CAT's real rig files, read from the checkout, not copied here. Loading the real
 * {@code lab.yaml} under the Java schema is the check that the port reads what the rig uses.
 * Skipped when the checkout is absent, so the build passes on a machine without it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). This reads two YAML files and makes no connection.
 */
class CommittedRigFilesTest {

    private static final Path PYTHON_RIGS = Path.of("C:\\gitrepos\\CAT\\rigs");

    @Test
    void theLabRigLoadsUnderTheJavaSchema() {
        assumeTrue(Files.isRegularFile(PYTHON_RIGS.resolve("lab.yaml")), "no lab.yaml in " + PYTHON_RIGS);

        LoadedRig loaded = RigLoader.loadResolved("lab", PYTHON_RIGS);
        Rig rig = loaded.rig();

        assertEquals("lab", rig.name());
        assertEquals(Rig.Pinpad.VIPERPAY, rig.pinpad());
        assertTrue(rig.posShim());
        assertEquals(Rig.Forecourt.PETRO, rig.forecourt());
        assertEquals(16, rig.pumps().size());
        assertEquals("055.02.09", rig.build().expectedVersion());
        assertEquals("env:PETROTEST_CASHIER_PASSWORD", rig.cashier().secretSource());
        assertEquals("COM2", rig.controller().tlsPort());

        Rig.Register register = rig.register(null);
        assertEquals("topaz101", register.id());
        assertEquals(19220, register.cdpPort());
        assertEquals(Rig.BrowserOverride.CHROMIUM, register.browserOverride());
        assertEquals("COM1", register.printerPort());
        assertEquals(Boolean.FALSE, register.cashDrawer());

        assertEquals(22001, rig.vats().port());
        assertEquals(9050, rig.vats().cardServerPort());
        assertEquals(22001, rig.devices().get("pinpad_hardware").port());
        assertNull(rig.wiremock());
        assertNull(loaded.wiremockMappings());
        assertEquals(Duration.ofSeconds(15), rig.timeouts().defaultWait());
    }

    @Test
    void theExampleRigLoadsUnderTheJavaSchema() {
        assumeTrue(Files.isRegularFile(PYTHON_RIGS.resolve("example.yaml")), "no example.yaml in " + PYTHON_RIGS);

        Rig rig = RigLoader.load("example", PYTHON_RIGS);

        assertEquals("example", rig.name());
        assertNotNull(rig.primaryRegister());
        assertEquals(Rig.Rci.COMMANDER, rig.rci());
        assertEquals(java.util.List.of(1, 2, 3, 4), rig.pumps());
    }
}
