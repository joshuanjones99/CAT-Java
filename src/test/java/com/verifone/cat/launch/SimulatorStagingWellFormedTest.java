package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.sims.FepHosts;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;

/**
 * System Checks' {@code well_formed:} and CAPK source lines (the Python's {@code config._well_formed} and
 * {@code verify}), the {@code fep_only} filter, and the Secondary FEPs' host simulators ({@code all_simulators}). Reads a
 * {@code @TempDir} only.
 */
class SimulatorStagingWellFormedTest {

    @TempDir
    Path tools;

    private static SimulatorStaging.Check line(List<SimulatorStaging.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() ->
                new AssertionError(name + " not in " + checks));
    }

    @Test
    void aCutShortFuelTotalsFileIsARequiredFailNamingTheFix() throws IOException {
        SimEntry petro = Registry.byName("Petro Simulator Suite").orElseThrow();
        Path config = Files.createDirectories(tools.resolve("Petro_Simulator_Suite/config"));
        SimContext ctx = SimContext.of(tools, "");

        Files.writeString(config.resolve("fuelPosTotals.xml"), "<totals><pump id=\"1\">");
        SimulatorStaging.Check cut = line(SimulatorStaging.verify(ctx, List.of(petro)),
                "Petro Simulator Suite fuelPosTotals.xml");
        assertFalse(cut.ok());
        assertTrue(cut.required());
        assertTrue(cut.detail().endsWith("restore it from a good copy before starting Petro Simulator Suite"),
                cut.detail());

        Files.writeString(config.resolve("fuelPosTotals.xml"), "");
        assertTrue(line(SimulatorStaging.verify(ctx, List.of(petro)), "Petro Simulator Suite fuelPosTotals.xml")
                .detail().contains(": empty;"));

        Files.writeString(config.resolve("fuelPosTotals.xml"), "<totals><pump id=\"1\"/></totals>");
        SimulatorStaging.Check good = line(SimulatorStaging.verify(ctx, List.of(petro)),
                "Petro Simulator Suite fuelPosTotals.xml");
        assertTrue(good.ok() && good.required());
    }

    @Test
    void buypassHostsCapkSourceJarIsALine() throws IOException {
        SimEntry buypass = Registry.byName("BUYPASS Host").orElseThrow();
        Files.createDirectories(tools.resolve("BUYPASS"));
        SimContext ctx = SimContext.of(tools, "");
        SimulatorStaging.Check jar = line(SimulatorStaging.verify(ctx, List.of(buypass)),
                "BUYPASS Host CAPK source jar");
        assertFalse(jar.ok());
        assertFalse(jar.required());
    }

    @Test
    void aFepOnlyRowIsCheckedOnlyWhenItsFepIsChosen() {
        SimEntry host = SimEntry.javaw("Fleet Host").fepOnly().build();
        SimEntry other = SimEntry.javaw("Other").build();
        assertEquals(List.of(other), SimulatorStaging.rowsFor(List.of(host, other), List.of()));
        assertEquals(List.of(host, other), SimulatorStaging.rowsFor(List.of(host, other), List.of("Fleet Host")));
    }

    private static SecondaryFep fep(String name, String simulator) {
        return new SecondaryFep(name, name, "fleet", "fepcfg", List.of(), false, simulator, "", "", "", "", "", "",
                "");
    }

    @Test
    void eachChosenFepsHostIsAddedOnceAndNeverOnAFakePosRun() {
        List<SecondaryFep> table = List.of(fep("mudflap", "Petro Simulator Suite"), fep("WEX-OTR",
                "Petro Simulator Suite"), fep("InComm", ""));
        List<SecondaryFep> rows = FepHosts.secondaryFepRows(List.of("Mudflap", "wex-otr", "incomm", "nope"), table);
        assertEquals(3, rows.size());
        assertEquals(Map.of("Petro Simulator Suite", "mudflap, WEX-OTR"), FepHosts.fepSimulators(rows));
        assertEquals(List.of("PCATS01 Loyalty"), FepHosts.allSimulators(List.of("PCATS01 Loyalty"),
                List.of("mudflap"), true));
        assertEquals(List.of(), FepHosts.allSimulators(List.of(), List.of(), false));
    }

    @Test
    void theShippedTableGivesMudflapItsHost() {
        assertEquals(List.of("Petro Simulator Suite"),
                FepHosts.allSimulators(List.of(), List.of("mudflap"), false));
    }
}
