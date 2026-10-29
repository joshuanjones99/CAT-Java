package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.sims.FakeProcessOps;
import com.verifone.cat.sims.Fepsim;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimTestSupport;
import com.verifone.cat.sims.Supervisor;

/**
 * Step 3's FEP hosts: the Python CAT's {@code _supersede}, {@code _retire_instances}, {@code _fep_listeners},
 * {@code _await_superseding_listener} and {@code _mark_fep_captures}, and what later steps read
 * ({@code host_packet_args}, {@code fepsim_buypass_env}, {@code fep_exchange_lines}).
 *
 * <p>The rows are the test's own, named as the registry's: "Petro Simulator Suite" is a jar holding the test's
 * {@code FakeSimulator} and FEPSIM's marker classes, run by the test JDK on a free port; BUYPASS Host is never a PATS
 * simulator either. The listener ports are answered by a seam; the process layer is {@link FakeProcessOps}, which kills
 * nothing. Evidence about this code only.
 */
class StartSimulatorsFepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final Set<Integer> open = ConcurrentHashMap.newKeySet();
    private final List<RunContext> contexts = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopWhatTheTestStarted() {
        for (RunContext ctx : contexts) {
            ctx.supervisor().stop(ctx.simulators(), Duration.ofSeconds(5));
            PetroPorts.releaseAll(ctx);
        }
    }

    private Path tools() {
        return dir.resolve("tools");
    }

    private Path suite() {
        return tools().resolve("Petro_Simulator_Suite");
    }

    /** The suite's jar: FakeSimulator as its main class, and FEPSIM's marker classes of {@code generation}. */
    private void petroJar(int generation) throws IOException {
        Files.createDirectories(suite());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.verifone.cat.sims.FakeSimulator");
        String fake = "com/verifone/cat/sims/FakeSimulator.class";
        try (OutputStream out = Files.newOutputStream(suite().resolve("Petro_Simulator_Suite.jar"));
                JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry(fake));
            jar.write(Files.readAllBytes(SimTestSupport.testClasses().resolve(fake)));
            jar.closeEntry();
            if (generation >= 1) {
                jar.putNextEntry(new JarEntry("com/verifone/isd/pcapps/simulators/fepsim/rt/CommonFEPSimRT.class"));
                jar.closeEntry();
            }
            if (generation >= 2) {
                jar.putNextEntry(new JarEntry(
                        "com/verifone/isd/pcapps/simulators/fepsim/comm/client/InstanceRepository.class"));
                jar.closeEntry();
            }
        }
    }

    private void example(String instance, int port) throws IOException {
        Path examples = Files.createDirectories(suite().resolve("config/fepsim/instances.examples"));
        Files.writeString(examples.resolve(instance + ".json"), JSON.writeValueAsString(Map.of("instanceId",
                instance, "enabled", true, "autoStart", false, "mode", "SIMULATE", "port", port, "upstream",
                Map.of("host", "<certification host>"))));
    }

    private SimEntry petro(int port) {
        return SimEntry.exeRow(StartSimulatorsStep.PETRO_SIMULATOR)
                .exe(SimTestSupport.testJava().orElseThrow().toString())
                .cwd("Petro_Simulator_Suite")
                .argv("-jar", "Petro_Simulator_Suite.jar", String.valueOf(port), "petro-fake", "ready")
                .match("Petro_Simulator_Suite.jar")
                .tcp(port)
                .build();
    }

    private SimEntry buypass(int listener, int wiremock, int own) throws IOException {
        Files.createDirectories(tools().resolve("BUYPASS"));
        List<String> args = SimTestSupport.fakeArgs(own, "buypass-fake", "ready");
        return SimEntry.exeRow(StartSimulatorsStep.PAYMENT_HOST)
                .exe(SimTestSupport.testJava().orElseThrow().toString())
                .cwd("BUYPASS")
                .argv(args.toArray(String[]::new))
                .tcp(own)
                .supersededBy(StartSimulatorsStep.PETRO_SIMULATOR, 2, "BUYPASS-41107", "BUYPASS", listener, wiremock)
                .build();
    }

    private RunContext context(LaunchConfig config, String runId, List<String> feps) throws IOException {
        Path folder = dir.resolve(runId);
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), runId, folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        ctx.supervisor(new Supervisor(new FakeProcessOps(), Duration.ofMillis(300)));
        ctx.petroPortOpener(port -> () -> { });
        ctx.commanderOptions(new RunContext.CommanderOptions(feps, false, ""));
        contexts.add(ctx);
        return ctx;
    }

    private StartSimulatorsStep step(List<SimEntry> rows) {
        StartSimulatorsStep step = new StartSimulatorsStep(c -> Optional.of(false), sims -> null);
        step.toolsRoot = tools();
        step.registry = older -> rows;
        step.accepts = (host, port) -> open.contains(port);
        step.listenerWait = Duration.ofMillis(300);
        step.listenerPoll = Duration.ofMillis(50);
        return step;
    }

    private static String eventsText(RunContext ctx) throws IOException {
        return Files.readString(ctx.runFolder().resolve("events.ndjson"), StandardCharsets.UTF_8);
    }

    @Test
    void withFepsimV2StagedBuypassHostIsNotStartedAndPetroServesItsPort() throws IOException {
        int petroPort = SimTestSupport.freePort();
        petroJar(2);
        example("BUYPASS-41107", 41107);
        int listener = SimTestSupport.freePort();
        int wiremock = SimTestSupport.freePort();
        open.add(listener);
        open.add(wiremock);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .simulator(StartSimulatorsStep.PAYMENT_HOST).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
        RunContext ctx = context(config, "a00000000001", List.of());
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertTrue(step(List.of(buypass(listener, wiremock, SimTestSupport.freePort()), petro(petroPort)))
                .run(ctx, emitter), emitter.detail());

        assertEquals(List.of(StartSimulatorsStep.PETRO_SIMULATOR),
                ctx.simulators().stream().map(p -> p.entry().name()).toList(), "BUYPASS Host is not started");
        JsonNode instance = JSON.readTree(Files.readString(
                suite().resolve("config/fepsim/instances/BUYPASS-41107.json")));
        assertTrue(instance.get("autoStart").booleanValue());
        assertEquals("BUYPASS", instance.get("legacy").get("profile").asText());
        assertTrue(StartSimulatorsStep.paymentHostSuperseded(ctx));
        assertEquals(List.of("--host-packets", Fepsim.captureFile(suite(), "BUYPASS-41107").toString()),
                StartSimulatorsStep.hostPacketArgs(ctx));
        Map<String, String> env = StartSimulatorsStep.fepsimBuypassEnv(ctx);
        JsonNode legacy = JSON.readTree(env.get(com.verifone.cat.devices.fep.FepsimLegacy.ENV));
        assertEquals("http://localhost:8099", legacy.get("admin").asText());
        assertEquals(listener, legacy.get("port").intValue());
        String events = eventsText(ctx);
        assertTrue(events.contains("simulator.state") && events.contains("Petro Simulator Suite"), events);
    }

    @Test
    void anAdoptedSuiteIsHandedTheInstanceOverItsAdminApi() throws IOException {
        int petroPort = SimTestSupport.freePort();
        petroJar(2);
        example("BUYPASS-41107", 41107);
        int listener = SimTestSupport.freePort();
        int wiremock = SimTestSupport.freePort();
        open.add(wiremock);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .simulator(StartSimulatorsStep.PAYMENT_HOST).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
        RunContext ctx = context(config, "a00000000002", List.of());
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        StartSimulatorsStep step = step(List.of(buypass(listener, wiremock, SimTestSupport.freePort()),
                petro(petroPort)));
        List<String> registered = new CopyOnWriteArrayList<>();
        step.registrar = (admin, suite, instance) -> {
            registered.add(admin + " " + instance);
            open.add(listener);
            return "created through " + admin + "/admin/fep/instances";
        };
        try (ServerSocket running = new ServerSocket(petroPort)) { // a Petro already up: adopted (D9)
            assertTrue(step.run(ctx, emitter), emitter.detail());
        }
        assertEquals(List.of("http://localhost:8099 BUYPASS-41107"), registered);
        assertTrue(ctx.simulators().isEmpty(), "nothing was spawned");
    }

    @Test
    void aDeadWiremockOnAnAdoptedSuiteFailsTheStepSayingWhy() throws IOException {
        int petroPort = SimTestSupport.freePort();
        petroJar(2);
        example("BUYPASS-41107", 41107);
        int listener = SimTestSupport.freePort();
        int wiremock = SimTestSupport.freePort();
        open.add(listener);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .simulator(StartSimulatorsStep.PAYMENT_HOST).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
        RunContext ctx = context(config, "a00000000003", List.of());
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        try (ServerSocket running = new ServerSocket(petroPort)) {
            assertFalse(step(List.of(buypass(listener, wiremock, SimTestSupport.freePort()), petro(petroPort)))
                    .run(ctx, emitter));
        }
        assertEquals("BUYPASS-41107: localhost:[" + wiremock + "] not accepting 0s after Petro Simulator Suite was "
                + "ready", emitter.detail());
    }

    @Test
    void anOlderSuiteKeepsBuypassHostAndSaysWhy() throws IOException {
        petroJar(1);
        int own = SimTestSupport.freePort();
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .simulator(StartSimulatorsStep.PAYMENT_HOST).build();
        RunContext ctx = context(config, "a00000000004", List.of());
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        assertTrue(step(List.of(buypass(SimTestSupport.freePort(), SimTestSupport.freePort(), own),
                petro(SimTestSupport.freePort()))).run(ctx, emitter), emitter.detail());
        assertEquals(List.of(StartSimulatorsStep.PAYMENT_HOST),
                ctx.simulators().stream().map(p -> p.entry().name()).toList());
        assertFalse(StartSimulatorsStep.paymentHostSuperseded(ctx));
        assertEquals(List.of(), StartSimulatorsStep.hostPacketArgs(ctx));
        assertEquals(Map.of(), StartSimulatorsStep.fepsimBuypassEnv(ctx));
    }

    @Test
    void buypassHostAskedForTurnsTheInstanceOff() throws IOException {
        int petroPort = SimTestSupport.freePort();
        petroJar(2);
        example("BUYPASS-41107", 41107);
        Fepsim.installInstance(suite(), "BUYPASS-41107", "BUYPASS");
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG).buypassHost(true)
                .simulator(StartSimulatorsStep.PAYMENT_HOST).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
        RunContext ctx = context(config, "a00000000005", List.of());
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        assertTrue(step(List.of(buypass(SimTestSupport.freePort(), SimTestSupport.freePort(),
                SimTestSupport.freePort()), petro(petroPort))).run(ctx, emitter), emitter.detail());
        JsonNode instance = JSON.readTree(Files.readString(
                suite().resolve("config/fepsim/instances/BUYPASS-41107.json")));
        assertFalse(instance.get("autoStart").booleanValue());
        assertEquals(2, ctx.simulators().size(), "both started");
        assertFalse(StartSimulatorsStep.paymentHostSuperseded(ctx));
    }

    private static SecondaryFep wexOtr() {
        return new SecondaryFep("WEX-OTR", "WEX OTR", "fleet", "license", List.of(), false,
                StartSimulatorsStep.PETRO_SIMULATOR, "", "", "WEX-OTR-8030", "", "", "", "");
    }

    @Test
    void aFleetFepsListenerIsWrittenWaitedForAndItsRequestsCounted() throws IOException {
        int petroPort = SimTestSupport.freePort();
        petroJar(2);
        int fepPort = SimTestSupport.freePort();
        example("WEX-OTR-8030", fepPort);
        int wiremock = SimTestSupport.freePort();
        Files.writeString(suite().resolve("config/HttpSim_config.properties"), "httpServerPort=" + wiremock + "\n");
        open.add(fepPort);
        open.add(wiremock);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG).build();
        RunContext ctx = context(config, "a00000000006", List.of("WEX-OTR"));
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        StartSimulatorsStep step = step(List.of(petro(petroPort)));
        step.fepRows = names -> List.of(wexOtr());

        assertTrue(step.run(ctx, emitter), emitter.detail());

        assertEquals(1, StartSimulatorsStep.hosts(ctx).listeners().size());
        assertTrue(JSON.readTree(Files.readString(suite().resolve("config/fepsim/instances/WEX-OTR-8030.json")))
                .get("autoStart").booleanValue());
        assertEquals(List.of("WARN  WEX-OTR: 0 request(s) to WEX-OTR-8030 — no sale reached this FEP"),
                StartSimulatorsStep.fepExchangeLines(ctx));
        Path capture = Fepsim.captureFile(suite(), "WEX-OTR-8030");
        Files.createDirectories(capture.getParent());
        Files.writeString(capture, "{\"source\": \"VIPER\", \"messageType\": \"IC\"}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(List.of("FEP   WEX-OTR: 1 request(s) to WEX-OTR-8030 (IC 1)"),
                StartSimulatorsStep.fepExchangeLines(ctx));
    }

    @Test
    void aFleetFepWhoseSuiteHasNoFepsimV2FailsTheStep() throws IOException {
        petroJar(1);
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG).build();
        RunContext ctx = context(config, "a00000000007", List.of("WEX-OTR"));
        StepEmitter emitter = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);
        StartSimulatorsStep step = step(List.of(petro(SimTestSupport.freePort())));
        step.fepRows = names -> List.of(wexOtr());
        assertFalse(step.run(ctx, emitter));
        assertTrue(emitter.detail().contains("needs v2") && emitter.detail().contains("PETROCORE-142214"),
                emitter.detail());
        assertTrue(ctx.simulators().isEmpty(), "nothing started");
    }

    @Test
    void aPythonTupleReadsAsPythonPrintsIt() {
        assertEquals("(41107, 80)", StartSimulatorsStep.tuple(List.of(41107, 80)));
        assertEquals("(8030,)", StartSimulatorsStep.tuple(List.of(8030)));
    }
}
