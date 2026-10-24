package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.handlers.inits.TestActions;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration family's registration, and {@code IMPORT_CONFIGURATION} against a loopback Commander: each file
 * of the folder is imported as one command, and the import's reply counts as the change the next init waits from.
 */
class ConfigurationFamilyTest {

    private static final Set<String> ACTIONS = Set.of("REPORT_PRINT", "NETWORK_REPORT", "PRINT_FLASH_REPORT",
            "CLOSE_REPORT", "CLOUD_HTTP_REQUEST", "UPDATE_CONFIGURATION", "HTTP_SIM_EVENT", "IMPORT_CONFIGURATION");

    @TempDir
    Path folder;

    private TestClock clock;
    private FakeCommander commander;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        commander = new FakeCommander(request -> "validate".equals(request.cmd())
                ? FakeCommander.Reply.ok(FakeCommander.FRESH_COOKIE)
                : FakeCommander.Reply.ok(FakeCommander.OK));
    }

    @AfterEach
    void tearDown() {
        commander.close();
    }

    private ConfigurationContext context() throws Exception {
        return ConfigFixtures.context(folder, clock, RegisterPos.UNBOUND, StuckCloseRecovery.UNBOUND,
                CloudSender.UNBOUND);
    }

    @Test
    void theFamilyRegistersTheEightActionsItOwnsAndEachIsAFieldTableAction() throws Exception {
        ActionRegistry registry = new ActionRegistry();

        ConfigurationFamily.register(registry, context());

        assertEquals(ACTIONS, registry.supportedActions());
        for (String action : ACTIONS) {
            assertTrue(Fields.FIELDS_READ.containsKey(action), action + " is not in the field table");
        }
        assertTrue(registry.supportedActions().stream().noneMatch(name -> name.equals("CONTROL_FUNCTION")),
                "CONTROL_FUNCTION is the inits family's");
    }

    @Test
    void aSecondRegistrationOfAnActionNameIsRefused() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        ConfigurationFamily.register(registry, context());

        assertThrows(IllegalStateException.class,
                () -> registry.register("CLOSE_REPORT", (run, action) -> { }));
    }

    @Test
    void importConfigurationSendsEachFileAsItsCommandAndNotesTheChange() throws Exception {
        Path configurations = Files.createDirectories(folder.resolve("configurations"));
        Path pop = Files.createDirectories(configurations.resolve("POP"));
        Files.writeString(pop.resolve("a.xml"), "<a/>", StandardCharsets.UTF_8);
        Files.writeString(pop.resolve("b.xml"), "<b/>", StandardCharsets.UTF_8);
        ConfigurationContext context = context();
        PdkShim shim = PdkShim.onPort(commander.host(), commander.port(), Duration.ofSeconds(5), () -> "123456");
        FixtureSource fixtures = name -> switch (name) {
            case CONFIGURATIONS -> configurations;
            case COMMANDER_CONFIG -> shim;
            default -> throw new IllegalStateException("no fixture " + name);
        };
        ActionRegistry registry = new ActionRegistry();
        ConfigurationFamily.register(registry, context);
        CaseRun run = TestActions.run(
                kase("PETROCORE-80", act(0, "IMPORT_CONFIGURATION", "SELECT_FOLDER", "POP")), registry, fixtures,
                Set.of(FixtureName.CONFIGURATIONS, FixtureName.COMMANDER_CONFIG));

        run.run();

        List<String> imports = commander.commands().stream().filter(c -> c.startsWith("u")).toList();
        assertEquals(List.of("ua", "ub"), imports);
        // The import's reply is the change: 10 s later the next init's spacing has passed, so nothing is waited.
        clock.advance(Duration.ofSeconds(10));
        context.inits().beforeInit("PETROCORE-80", 60.0);
        assertEquals(Duration.ZERO, clock.pauses().get(clock.pauses().size() - 1));
    }

    @Test
    void aReportPrintIsAFlagAndSendsNothing() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        ConfigurationFamily.register(registry, context());
        CaseRun run = TestActions.run(
                kase("PETROCORE-81", act(0, "REPORT_PRINT")), registry, ConfigFixtures.noFixtures(), Set.of());

        run.run();

        assertEquals(List.of(), clock.pauses());
    }
}
