package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.plans.TestPlan;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.runconfig.RunScope;

/** {@code launch}'s run_plan flags: each one parsed into the config, and how the cases and defaults are read. */
class LaunchCommandOptionsTest {

    @TempDir
    Path dir;

    private static LaunchConfig config(String... argv) {
        return LaunchCommand.Options.parse(argv).toConfig(LastConfig.none(), Map.of(), List.of(), List.of());
    }

    @Test
    void everyFlagReachesTheConfig() {
        LaunchConfig c = config("--rig", "bench", "--plan", "PETROCORE-1", "PETROCORE-2", "--plan", "PETROCORE-3",
                "--plan", "PETROCORE-1", "--suite", "tests/suites/x", "--upgrade", "new_install", "--exec", "BDD",
                "--dist", "chevron", "--branch", "release", "--fep", "WEX-OTR", "--fep", "Comdata", "--fep", "WEX-OTR",
                "--no-xray", "--coverage", "--cc-onboard", "--waive-cc-requirement", "--capture-snapshot",
                "--snapshot-update", "--buypass-host", "--cache-only", "--fake-pinpad");

        assertEquals("bench", c.rig());
        assertEquals(List.of("PETROCORE-1", "PETROCORE-2", "PETROCORE-3"), c.testPlans(), "ordered, each once");
        assertEquals(List.of("tests/suites/x"), c.suites());
        assertEquals(UpgradeMode.NEW_INSTALL, c.upgradeMode());
        assertEquals("BDD", c.executionMode());
        assertEquals("chevron", c.dist());
        assertEquals("release", c.testPlanBranch());
        assertEquals(List.of("WEX-OTR", "Comdata"), c.secondaryFeps());
        assertFalse(c.xrayReport());
        assertTrue(c.coverage());
        assertTrue(c.commanderConnect());
        assertTrue(c.ccRequirementWaived());
        assertTrue(c.captureSnapshot());
        assertTrue(c.snapshotUpdate());
        assertTrue(c.buypassHost());
        assertTrue(c.fakePinpad());
        assertEquals(RunScope.Mode.WHOLE, c.scope().mode());
    }

    @Test
    void noCoverageWinsOverCoverage() {
        assertFalse(config("--rig", "bench", "--coverage", "--no-coverage").coverage());
    }

    @Test
    void theCacheOnlyFlagPicksTheCatalogueMode() {
        assertEquals(PlanCatalogue.Mode.REFRESH, LaunchCommand.casesMode(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench"})));
        assertEquals(PlanCatalogue.Mode.CACHE_ONLY, LaunchCommand.casesMode(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench", "--cache-only"})));
    }

    @Test
    void thePartialFlagsAreEachAScope() {
        LaunchConfig only = config("--rig", "bench", "--only", "PETROCORE-7,PETROCORE-8", "PETROCORE-9");
        assertEquals(RunScope.Mode.CASES, only.scope().mode());
        assertEquals(List.of("PETROCORE-7", "PETROCORE-8", "PETROCORE-9"), only.scope().cases());

        LaunchConfig from = config("--rig", "bench", "--from", "PETROCORE-7");
        assertEquals(RunScope.Mode.FROM, from.scope().mode());
        assertEquals("PETROCORE-7", from.scope().fromCase());

        LaunchConfig tags = config("--rig", "bench", "--tags", "@smoke and not @slow");
        assertEquals(RunScope.Mode.TAGS, tags.scope().mode());
        assertEquals("@smoke and not @slow", tags.scope().tags());

        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommand.Options.parse(new String[] {"--rig", "b", "--tags", "@a", "--from", "K"}));
    }

    @Test
    void badValuesAreUsageErrors() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommand.Options.parse(new String[] {"--rig", "b", "--exec", "prod"}));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommand.Options.parse(new String[] {"--rig", "b", "--upgrade", "psi"}));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommand.Options.parse(new String[] {"--rig", "b", "--plan"}));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommand.Options.parse(new String[] {"--rig", "b", "--only"}));
        LaunchCommand.Options options = LaunchCommand.Options.parse(new String[] {"--rig", "b"});
        LastConfig badSaved = LastConfig.of(Map.of("config-exec", "prod"));
        assertThrows(IllegalArgumentException.class,
                () -> options.toConfig(badSaved, Map.of(), List.of(), List.of()));
    }

    /** A network source that counts its case and requirements reads. */
    private static final class CountingSource implements PlanSource {
        final List<String> calls = new ArrayList<>();
        final List<PlanRequirements> requirements;

        CountingSource(List<PlanRequirements> requirements) {
            this.requirements = requirements;
        }

        @Override
        public List<TestPlan> catalogue() {
            return List.of();
        }

        @Override
        public List<PlanCase> cases(String planKey) {
            calls.add("cases " + planKey);
            return List.of(new PlanCase("PETROCORE-11", "one", null));
        }

        @Override
        public List<PlanRequirements> requirements() {
            calls.add("requirements");
            return requirements;
        }
    }

    private LaunchPaths paths(PlanSource network) throws IOException {
        LaunchPaths base = LaunchFixtures.paths(dir);
        PlanCatalogue catalogue = new PlanCatalogue(dir.resolve("cache"), dir.resolve("python-cache"), network,
                new SeedPlanSource(), base.repoRoot());
        return new LaunchPaths(base.resultsRoot(), base.rigsDir(), base.leaseDir(), base.repoRoot(),
                base.petroappsRoot(), catalogue, base.setupEnv());
    }

    private static PrintStream sink(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    @Test
    void thePlansCasesAreReadFromXrayFirstAndTheCacheOnlyWithCacheOnly() throws IOException {
        CountingSource network = new CountingSource(List.of());
        LaunchPaths paths = paths(network);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        LaunchCommand.resolve(LaunchCommand.Options.parse(new String[] {"--rig", "bench", "--plan", "PETROCORE-5"}),
                paths, LastConfig.none(), Map.of(), sink(out));
        assertTrue(network.calls.contains("cases PETROCORE-5"), network.calls.toString());
        assertTrue(Files.isRegularFile(dir.resolve("cache").resolve("cases-PETROCORE-5.json")), "the fresh read is cached");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("PETROCORE-5: 1 case(s) from network"), out.toString());

        network.calls.clear();
        LaunchCommand.resolve(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench", "--plan", "PETROCORE-5", "--cache-only", "--simulator", "X"}),
                paths, LastConfig.none(), Map.of(), sink(new ByteArrayOutputStream()));
        assertFalse(network.calls.contains("cases PETROCORE-5"), network.calls.toString());
    }

    @Test
    void thePlansRowNamesTheSimulatorsAndDevicesUnlessSimulatorIsGiven() throws IOException {
        CountingSource network = new CountingSource(List.of(
                new PlanRequirements("PETROCORE-5", "", false, List.of("BUYPASS Host"), List.of("Printer", "scale"))));
        LaunchPaths paths = paths(network);

        LaunchConfig fromRow = LaunchCommand.resolve(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench", "--plan", "PETROCORE-5"}), paths, LastConfig.none(), Map.of(),
                sink(new ByteArrayOutputStream()));
        assertEquals(List.of("BUYPASS Host"), fromRow.simulators());
        assertEquals(List.of("scale"), fromRow.connections(), "the printer is not a probe row");

        LaunchConfig given = LaunchCommand.resolve(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench", "--plan", "PETROCORE-5", "--simulator", "PCATS01 Loyalty"}), paths,
                LastConfig.none(), Map.of(), sink(new ByteArrayOutputStream()));
        assertEquals(List.of("PCATS01 Loyalty"), given.simulators());
    }

    @Test
    void aPlanWithNoRowTakesEveryLaunchableSimulatorAndAFakePosRunTakesNone() throws IOException {
        LaunchPaths paths = paths(new CountingSource(List.of()));

        LaunchConfig rig = LaunchCommand.resolve(LaunchCommand.Options.parse(
                new String[] {"--rig", "bench", "--plan", "PETROCORE-5"}), paths, LastConfig.none(), Map.of(),
                sink(new ByteArrayOutputStream()));
        assertEquals(LaunchCommand.defaultSimulators(), rig.simulators());
        assertFalse(rig.simulators().isEmpty());

        LaunchConfig fake = LaunchCommand.resolve(LaunchCommand.Options.parse(
                new String[] {"--fake-pos", "--plan", "PETROCORE-5"}), paths,
                LastConfig.of(Map.of("config-upgrade", "new_install")), Map.of(), sink(new ByteArrayOutputStream()));
        assertTrue(fake.simulators().isEmpty());
        assertTrue(fake.connections().isEmpty());
        assertEquals(UpgradeMode.SKIP, fake.upgradeMode(), "a fake-POS run reads no saved choices");
    }

    @Test
    void theDefaultConnectionsAreEveryOptionalTargetButThePinpad() throws IOException {
        Path file = dir.resolve("fleet.yaml");
        Files.writeString(file, """
                name: fleet
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                registers:
                  - id: topaz101
                    host: register.invalid
                    cdp_port: 9222
                  - id: topaz102
                    host: register2.invalid
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                pos_shim: true
                build:
                  expected_version: "057.02"
                devices:
                  pinpad_hardware:
                    host: pinpad.invalid
                    port: 22001
                  scale:
                    host: scale.invalid
                    port: 22002
                """);

        assertEquals(List.of("topaz102", "scale"),
                LaunchCommand.defaultConnections(RigLoader.loadFile(file).rig()));
        assertEquals(List.of(), LaunchCommand.defaultConnections(null));
    }
}
