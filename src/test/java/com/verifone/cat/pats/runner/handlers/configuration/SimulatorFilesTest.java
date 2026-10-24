package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code UPDATE_CONFIGURATION} and {@code HTTP_SIM_EVENT} over {@code @TempDir} petroapps and tools roots: the files
 * written, the restore a hard failure makes, and the stops PATS makes. The Python's tests are in
 * tests/unit/test_pats_runner.py (the restore) and tests/unit/test_pats_sco_actions.py (HTTP_SIM_EVENT).
 */
class SimulatorFilesTest {

    private static final String ENDPOINTS = "AutomationTests/configurations/EndPoints";
    private static final String CONTROL = "app.target=GLORY_EVENT\napp.port=7000\n";
    private static final String HEARTBEAT = "<Heartbeat/>";

    @TempDir
    Path folder;

    private TestClock clock;
    private Path tools;
    private Path petroapps;

    @BeforeEach
    void setUp() throws Exception {
        clock = new TestClock();
        tools = Files.createDirectories(folder.resolve("tools"));
        petroapps = Files.createDirectories(folder.resolve("petroapps"));
    }

    private ConfigurationContext context(Optional<BuypassV2> buypass, CaseFiles caseFiles) throws Exception {
        return ConfigFixtures.context(folder, clock, new FakeRegister(clock), StuckCloseRecovery.UNBOUND,
                CloudSender.UNBOUND, () -> buypass, caseFiles);
    }

    private CaseRun run(ConfigurationContext context, RunnableCase kase) {
        return ConfigFixtures.caseRun(context, ConfigFixtures.noFixtures(), kase, Set.of());
    }

    private Path endpoint(String device, String relative) {
        return petroapps.resolve(ENDPOINTS).resolve(device).resolve(relative);
    }

    private static RunnableAction update(int index, String device, String file) {
        return act(index, "UPDATE_CONFIGURATION", "DEVICE", device, "SELECT_FILE", file);
    }

    private String propertyOf(String device, String file) throws IOException {
        return Files.readString(tools.resolve(device).resolve(file), StandardCharsets.UTF_8);
    }

    // -- UPDATE_CONFIGURATION -----------------------------------------------------------------

    @Test
    void anUpdateWritesTheSimulatorsPropertyFileAndAFinishedCaseKeepsIt() throws Exception {
        ConfigFixtures.write(tools.resolve("BUYPASS").resolve("PayHostSim.prop"), "AllApprove.txt");
        ConfigFixtures.write(endpoint("BUYPASS", "PartialAuth.txt"), "PartialAuth.txt");
        RunnableCase kase = kase("PETROCORE-50", update(0, "BUYPASS", "PartialAuth.txt"));

        run(context(Optional.empty(), CaseFiles.NONE), kase).run();

        assertEquals("PartialAuth.txt", propertyOf("BUYPASS", "PayHostSim.prop"));
    }

    @Test
    void aHardFailureAfterAnUpdatePutsTheSimulatorBackAsItWas() throws Exception {
        ConfigFixtures.write(tools.resolve("BUYPASS").resolve("PayHostSim.prop"), "AllApprove.txt");
        ConfigFixtures.write(endpoint("BUYPASS", "PartialAuth.txt"), "PartialAuth.txt");
        RunnableCase kase = kase("PETROCORE-51", update(0, "BUYPASS", "PartialAuth.txt"),
                update(1, "NOPE", "x.txt"));

        PatsStepError error = assertThrows(PatsStepError.class, run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("NOPE"), error.getMessage());
        assertEquals("AllApprove.txt", propertyOf("BUYPASS", "PayHostSim.prop"));
    }

    @Test
    void theRestoreIsTheValueBeforeTheFirstChangeNotTheIntermediate() throws Exception {
        ConfigFixtures.write(tools.resolve("BUYPASS").resolve("PayHostSim.prop"), "AllApprove.txt");
        ConfigFixtures.write(endpoint("BUYPASS", "PartialAuth.txt"), "PartialAuth.txt");
        ConfigFixtures.write(endpoint("BUYPASS", "Second.txt"), "Second.txt");
        RunnableCase kase = kase("PETROCORE-52", update(0, "BUYPASS", "PartialAuth.txt"),
                update(1, "BUYPASS", "Second.txt"), update(2, "NOPE", "x.txt"));

        assertThrows(PatsStepError.class, run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertEquals("AllApprove.txt", propertyOf("BUYPASS", "PayHostSim.prop"));
    }

    @Test
    void anEmptyDeviceNamesNoSimulator() throws Exception {
        RunnableCase kase = kase("PETROCORE-53", update(0, "", "x.txt"));

        PatsStepError error = assertThrows(PatsStepError.class, run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("no simulator property file"), error.getMessage());
    }

    @Test
    void theFepsimV2ServingBuypassReceivesTheTextAndIsRestoredOnFailure() throws Exception {
        ConfigFixtures.write(tools.resolve("BUYPASS").resolve("PayHostSim.prop"), "AllApprove.txt");
        ConfigFixtures.write(endpoint("BUYPASS", "PartialAuth.txt"), "PartialAuth.txt");
        FakeFepsim fepsim = new FakeFepsim("before-rules");
        RunnableCase kase = kase("PETROCORE-54", update(0, "BUYPASS", "PartialAuth.txt"), update(1, "NOPE", "x"));

        assertThrows(PatsStepError.class,
                run(context(Optional.of(fepsim), CaseFiles.NONE), kase)::run);

        // The update's text went to FEPSIM v2, then its restore put back the text it held before.
        assertEquals(List.of("PartialAuth.txt", "before-rules"), fepsim.applied);
        assertEquals("before-rules", fepsim.text);
        assertEquals("AllApprove.txt", propertyOf("BUYPASS", "PayHostSim.prop"));
    }

    // -- HTTP_SIM_EVENT -----------------------------------------------------------------------

    private Path httpsimControl() {
        return tools.resolve("Petro_Simulator_Suite/config/HTTPSIM/control/httpSimControl.prop");
    }

    private Path httpsimPayload() {
        return tools.resolve("Petro_Simulator_Suite/config/HTTPSIM/payload/httpSimPayLoad.txt");
    }

    private void httpsimFolders() throws IOException {
        Files.createDirectories(httpsimControl().getParent());
        Files.createDirectories(httpsimPayload().getParent());
    }

    private static RunnableAction httpEvent(String control, String payload) {
        return act(0, "HTTP_SIM_EVENT", "SELECT_CONTROL_FILE", control, "SELECT_PAYLOAD_FILE", payload,
                "DEVICE", "HTTPSIM");
    }

    @Test
    void theEventIsTheCasesPayloadUnderTheSimulatorsNames() throws Exception {
        httpsimFolders();
        ConfigFixtures.write(endpoint("HTTPSIM", "control/httpSimControl.prop"), CONTROL);
        ConfigFixtures.write(endpoint("HTTPSIM", "payload/Heartbeat-httpSimPayload.txt"), HEARTBEAT);
        RunnableCase kase = kase("PETROCORE-60", httpEvent("httpSimControl.prop", "Heartbeat-httpSimPayload.txt"));

        run(context(Optional.empty(), CaseFiles.NONE), kase).run();

        assertArrayEquals(CONTROL.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(httpsimControl()));
        assertEquals(HEARTBEAT, Files.readString(httpsimPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void aPayloadTheCaseDoesNotCarryComesFromAnotherCaseAsPatsCopiesIt() throws Exception {
        httpsimFolders();
        ConfigFixtures.write(endpoint("HTTPSIM", "control/httpSimControl.prop"), CONTROL);
        CaseFiles others = new CaseFiles() {
            @Override
            public Optional<byte[]> reference(RunnableCase kase, String kind, String name) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> fromAnyCase(String kind, String name) {
                return Optional.of("another case's copy".getBytes(StandardCharsets.UTF_8));
            }
        };
        RunnableCase kase = kase("PETROCORE-61", httpEvent("httpSimControl.prop", "Odd-httpSimPayLoad.txt"));

        run(context(Optional.empty(), others), kase).run();

        assertEquals("another case's copy", Files.readString(httpsimPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void aPayloadNoFolderHoldsIsAHardFailure() throws Exception {
        httpsimFolders();
        ConfigFixtures.write(endpoint("HTTPSIM", "control/httpSimControl.prop"), CONTROL);
        RunnableCase kase = kase("PETROCORE-62", httpEvent("httpSimControl.prop", "New-httpSimPayLoad.txt"));

        PatsStepError error = assertThrows(PatsStepError.class,
                run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("no payload 'New-httpSimPayLoad.txt'"), error.getMessage());
    }

    @Test
    void aMissingControlFileIsAHardFailureNamingIt() throws Exception {
        httpsimFolders();
        RunnableCase kase = kase("PETROCORE-63", httpEvent("missing.prop", "Heartbeat-httpSimPayload.txt"));

        PatsStepError error = assertThrows(PatsStepError.class,
                run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("missing.prop"), error.getMessage());
    }

    @Test
    void noSimulatorFolderMeansTheHttpSimulatorIsNotRunning() throws Exception {
        ConfigFixtures.write(endpoint("HTTPSIM", "control/httpSimControl.prop"), CONTROL);
        ConfigFixtures.write(endpoint("HTTPSIM", "payload/Heartbeat-httpSimPayload.txt"), HEARTBEAT);
        RunnableCase kase = kase("PETROCORE-64", httpEvent("httpSimControl.prop", "Heartbeat-httpSimPayload.txt"));

        PatsStepError error = assertThrows(PatsStepError.class,
                run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("not running"), error.getMessage());
        assertTrue(!Files.exists(httpsimPayload()));
    }

    @Test
    void anHttpSimEventWithNoDeviceDoesNothingForPatsAndFailsHere() throws Exception {
        RunnableCase kase = kase("PETROCORE-65", act(0, "HTTP_SIM_EVENT", "SELECT_CONTROL_FILE", "c",
                "SELECT_PAYLOAD_FILE", "p"));

        PatsStepError error = assertThrows(PatsStepError.class,
                run(context(Optional.empty(), CaseFiles.NONE), kase)::run);

        assertTrue(error.getMessage().contains("names no DEVICE"), error.getMessage());
    }

    /** FEPSIM v2 in memory: its text, and the texts applied to it. */
    static final class FakeFepsim implements BuypassV2 {
        String text;
        final List<String> applied = new java.util.ArrayList<>();

        FakeFepsim(String text) {
            this.text = text;
        }

        @Override
        public String configText() {
            return text;
        }

        @Override
        public void applyConfig(String value) {
            applied.add(value);
            text = value;
        }
    }
}
