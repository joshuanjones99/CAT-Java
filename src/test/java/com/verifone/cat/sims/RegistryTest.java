package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryTest {

    @TempDir
    Path tools;

    private static List<String> names(List<SimEntry> rows) {
        return rows.stream().map(SimEntry::name).toList();
    }

    @Test
    void theSevenRowsAreInThePythonOrder() {
        assertEquals(List.of("BUYPASS Host", "PCATS01 Loyalty", "PCATS02 Loyalty", "PCATS03 Loyalty",
                "MPPA Server Sim", "Petro Simulator Suite", "Terminal Connector"), names(Registry.all()));
    }

    @Test
    void theTerminalConnectorIsStagedAndNeverLaunched() {
        assertFalse(names(Registry.launchable(false)).contains("Terminal Connector"));
        assertEquals(6, Registry.launchable(false).size());
        assertTrue(Registry.byName("Terminal Connector").orElseThrow().stagedOnly());
    }

    @Test
    void theLoySimAndMppaRowsNeedTheAccessBridge() {
        for (String name : List.of("PCATS01 Loyalty", "PCATS02 Loyalty", "PCATS03 Loyalty", "MPPA Server Sim")) {
            assertTrue(Registry.byName(name).orElseThrow().needsClicks(), name);
        }
        assertEquals(List.of("Start Host"), Registry.byName("PCATS01 Loyalty").orElseThrow().click());
        assertEquals(List.of("Load Data", "Load Servers"),
                Registry.byName("MPPA Server Sim").orElseThrow().click());
    }

    @Test
    void buypassIsRefusedBecauseItsJarNeedsCapkRedating() {
        SimEntry buypass = Registry.byName("BUYPASS Host").orElseThrow();
        assertEquals("ArbitraryHostSim_v5.00.jar", buypass.capkFrom());
        assertEquals(41107, buypass.readyTcp());
        assertTrue(buypass.adoptOnlyMatch());
    }

    @Test
    void petroCarriesItsJaxbFallbackAndStaleReplacement() {
        SimEntry petro = Registry.byName("Petro Simulator Suite").orElseThrow();
        assertEquals(9001, petro.readyTcp());
        assertTrue(petro.adoptOnlyMatch());
        assertTrue(petro.replaceStale());
        assertEquals("javax/xml/bind/JAXBException", petro.fallbackWhen());
        assertEquals("{tools_root}/Java17v1.1/jdk-17.0.5+8", petro.fallbackJdk());
        assertEquals("Petro_Simulator_Suite.jar", petro.match());
    }

    @Test
    void aBaseFiftySixOrEarlierRunTakesTheOlderForms() {
        String loysim7 = Registry.forBase(true).get(1).argv().get(1);
        assertEquals("{cwd}/LoySim7.jar", loysim7);
        assertEquals("{cwd}/LoySim9.1.jar", Registry.forBase(false).get(1).argv().get(1));
        assertEquals("MPPAServerSim-03.00.00", Registry.forBase(true).get(4).cwd());
        assertEquals("MPPAServerSim-04.00.00", Registry.forBase(false).get(4).cwd());
    }

    @Test
    void everyRowsPlaceholdersResolveAgainstAStagedRoot() {
        SimContext ctx = SimContext.of(tools, "controller-host");
        for (boolean older : new boolean[] {false, true}) {
            for (SimEntry row : Registry.forBase(older)) {
                for (String text : row.argv()) {
                    ctx.substitute(text, row);
                }
                if (!row.exe().isEmpty()) {
                    ctx.substitute(row.exe(), row);
                }
                for (String value : row.env().values()) {
                    ctx.substitute(value, row);
                }
                if (!row.match().isEmpty()) {
                    ctx.substitute(row.match(), row);
                }
            }
        }
    }

    @Test
    void theEnvironmentOfTheTerminalConnectorIsItsOwnPath() {
        SimEntry connector = Registry.byName("Terminal Connector").orElseThrow();
        assertEquals(Map.of("PYTHONPATH", "app/vats_runner"), connector.env());
    }
}
