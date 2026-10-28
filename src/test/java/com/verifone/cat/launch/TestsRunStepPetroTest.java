package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.devices.forecourt.PetroDcr;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 5 on a Petro rig, over loopback fakes of the suite and a fake opener for the port holds: PATS's port 1072 is
 * released before the session's pump listener starts and held again after; the session's devices reach the fixtures
 * through the run's services; a DCR case whose link will not come back errors at its setup, with one recovery for the
 * session; the Receipt Printer row is lent, then stopped; and the listeners are closed when the step ends. Nothing
 * here reaches a rig.
 */
class TestsRunStepPetroTest {

    @TempDir
    Path dir;

    private Path writeCase(String key, String action) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("petroapps").resolve(key));
        Path file = folder.resolve(key + "-TestCase.xml");
        Files.writeString(file, "<testscript><testcase name=\"" + key + "\"><action name=\"" + action + "\"/>"
                + "</testcase></testscript>", StandardCharsets.UTF_8);
        return file;
    }

    private CaseEntry entry(String key, String action) throws IOException {
        Path file = writeCase(key, action);
        return new CaseEntry(key, "tests/suites/pulled/" + file.getFileName(), file);
    }

    @Test
    void theSessionTakesPatsPortGivesTheDevicesToTheFixturesAndGivesThePortBack() throws Exception {
        Path rigs = Files.createDirectories(dir.resolve("petro-rigs"));
        Files.writeString(rigs.resolve("bench.yaml"), RunServicesWiringTest.RIG);
        Path folder = dir.resolve("run");
        RunContext ctx = new RunContext(LaunchConfig.forRig(LaunchFixtures.RIG), LaunchFixtures.paths(dir),
                "petro0000001", folder, RunEvents.open(folder, Clock.systemUTC()), new PauseGate(),
                Clock.systemUTC());
        ctx.rig(RigLoader.loadResolved("bench", rigs));
        List<String> portCalls = new CopyOnWriteArrayList<>();
        ctx.petroPortOpener(port -> {
            portCalls.add("open " + port);
            return () -> portCalls.add("close " + port);
        });
        PetroPorts.hold(ctx, new StepEmitter(ctx, LaunchStepNames.SIMULATORS)); // step 3's holds

        try (FakePetroSuite pumpSuite = FakePetroSuite.pumps(); FakePetroSuite dcrSuite = FakePetroSuite.dcr()) {
            int pumpPort = FakePetroSuite.freePort();
            List<Boolean> patsPortHeldAtStart = new ArrayList<>();
            AtomicInteger recoveries = new AtomicInteger();
            PetroSession.Devices devices = new PetroSession.Devices() {
                @Override
                public PetroPumps startPumps() {
                    patsPortHeldAtStart.add(ctx.petroSinks().containsKey(PetroPumps.PATS_FUEL_PORT));
                    return pumpSuite.startedPumps(pumpPort);
                }

                @Override
                public PetroDcr startDcr() {
                    return dcrSuite.startedDcr(FakePetroSuite.freePort());
                }

                @Override
                public String dcrLinkFault(int dcr) {
                    return "not connected to the Commander";
                }

                @Override
                public Optional<String> recoverDcrLink(int dcr, PetroSession.Log log) {
                    recoveries.incrementAndGet();
                    return Optional.of("no display after the inits");
                }
            };

            AtomicReference<RunServices> seen = new AtomicReference<>();
            List<String> ran = new CopyOnWriteArrayList<>();
            RegistryFactory factory = new RegistryFactory() {
                @Override
                public ActionRegistry create(RunServices services) {
                    ActionRegistry registry = new ActionRegistry();
                    registry.register("FUEL_AMOUNT", (run, action) -> {
                        FuelPumps pumps = run.fixture(FixtureName.FUEL_PUMPS, FuelPumps.class);
                        ran.add("fuel " + pumps.state(4));
                        throw new IllegalStateException("the lift failed");
                    });
                    registry.register("WAIT_DCR_PROMPT", (run, action) -> ran.add("dcr"));
                    return registry;
                }

                @Override
                public Built build(RunServices services) {
                    seen.set(services);
                    FixtureSource standard = TestsRunStep.fixtureSource(services);
                    FixtureSource fixtures = name -> name == FixtureName.FUEL_PUMPS ? services.fuelPumps()
                            : standard.fixture(name);
                    return new Built(create(services), fixtures, null);
                }
            };
            List<CaseEntry> entries = List.of(entry("PETROCORE-1", "FUEL_AMOUNT"),
                    entry("PETROCORE-2", "WAIT_DCR_PROMPT"), entry("PETROCORE-3", "WAIT_DCR_PROMPT"));

            StepRunner.runLocal(ctx, new TestsRunStep(factory, config -> entries, null, devices));

            assertEquals(List.of(false), patsPortHeldAtStart, "1072 is released before the pump listener binds it");
            assertEquals(List.of("open 9706", "open 1072", "close 1072", "open 1072"), portCalls,
                    "lent to the session, held again after; 9706 stays held");
            assertEquals(Set.of(9706, 1072), ctx.petroSinks().keySet());
            assertTrue(ctx.testsStarted());

            assertNotNull(seen.get().fuelPumps(), "the session's pumps reach the services");
            assertNotNull(seen.get().dcr(), "and DCR 1");
            assertEquals(List.of("fuel Optional.empty"), ran, "the DCR cases never reach their actions");
            assertEquals(1, recoveries.get(), "one DCR recovery for the session");

            List<JsonNode> events = LaunchFixtures.events(ctx.runFolder());
            List<String> outcomes = new ArrayList<>();
            List<String> printer = new ArrayList<>();
            for (JsonNode event : events) {
                String type = event.get("type").asText();
                if ("test.finished".equals(type)) {
                    outcomes.add(event.get("outcome").asText());
                } else if ("simulator.state".equals(type) && "Receipt Printer".equals(event.get("name").asText())) {
                    printer.add(event.get("state").asText() + " " + event.get("bind").asText());
                }
            }
            assertEquals(List.of("failed", "error", "error"), outcomes);
            assertEquals(List.of("started COM7", "stopped COM7"), printer);
            assertTrue(PetroPorts.bindable(pumpPort), "the session's pump listener is closed with the step");
        } finally {
            PetroPorts.releaseAll(ctx);
        }
    }
}
