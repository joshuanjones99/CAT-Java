package com.verifone.cat.pats.runner.handlers.inits;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;

import java.io.IOException;
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
 * {@code CONTROL_FUNCTION} against a loopback Commander ({@link FakeCommander}) through the real {@link PdkShim}, and
 * the reply pause the before-action hook takes before the next action.
 */
class InitsFamilyTest {

    private static final String PUMPS = InitsStateTest.PUMPS;

    @TempDir
    Path folder;

    private Path log;
    private TestClock clock;
    private InitsState state;
    private FakeCommander commander;
    private PdkShim shim;
    private ActionRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        log = folder.resolve("log4j-application.log");
        Files.writeString(log, "started\n", StandardCharsets.UTF_8);
        clock = new TestClock();
        state = new InitsState(() -> log, clock.waits(), new InitsState.Timing(
                Duration.ofMillis(200), Duration.ofMillis(50), Duration.ofMillis(600),
                Duration.ofMillis(300), Duration.ofMillis(1500)));
        commander = new FakeCommander(request -> "validate".equals(request.cmd())
                ? FakeCommander.Reply.ok(FakeCommander.FRESH_COOKIE)
                : FakeCommander.Reply.ok(FakeCommander.OK));
        shim = PdkShim.onPort(commander.host(), commander.port(), Duration.ofSeconds(5), () -> "123456");
        registry = new ActionRegistry();
        InitsFamily.register(registry, new InitsContext(state));
        registry.register("NOOP", (run, action) -> { });
    }

    @AfterEach
    void tearDown() {
        commander.close();
    }

    private CaseRun caseRun(String key, com.verifone.cat.pats.runner.RunnableAction... actions) {
        return run(kase(key, actions), registry, name -> {
            if (name == FixtureName.COMMANDER_CONFIG) {
                return shim;
            }
            throw new IllegalStateException("no fixture " + name);
        }, Set.of(FixtureName.COMMANDER_CONFIG));
    }

    @Test
    void controlFunctionSendsItsInitAndWaitsOnPetrosSignalNotThePause() {
        CaseRun run = caseRun("PETROCORE-20", act(0, "CONTROL_FUNCTION", "INIT_TYPE", "cfuelinit"));

        run.run();

        assertTrue(commander.commands().contains("cfuelinit"), commander.commands().toString());
        assertEquals(List.of("cfuelinit"), state.pending());
        assertTrue(clock.pauses().isEmpty(), "no pause after the reply: the signal ends it, " + clock.pauses());
    }

    @Test
    void theReplyPauseBeforeTheNextActionEndsOnPetrosSignal() {
        clock.writeAfter(Duration.ofMillis(600), log, PUMPS);
        long[] seenAt = new long[1];
        registry.register("PRESS", (run, action) -> seenAt[0] = clock.nanos());
        long started = clock.nanos();

        caseRun("PETROCORE-21", act(0, "CONTROL_FUNCTION", "INIT_TYPE", "cfuelinit"), act(1, "PRESS")).run();

        long took = seenAt[0] - started;
        assertTrue(took >= Duration.ofMillis(600).toNanos() && took < Duration.ofMillis(1600).toNanos(), "took " + took);
        assertEquals(List.of(), state.pending());
    }

    @Test
    void aSleepIsNotHeldByTheReplyPause() {
        registry.register("SLEEP", (run, action) -> { });

        caseRun("PETROCORE-22", act(0, "CONTROL_FUNCTION", "INIT_TYPE", "cfuelinit"), act(1, "SLEEP")).run();

        assertTrue(clock.pauses().isEmpty(), "a SLEEP does not take the reply pause: " + clock.pauses());
        assertEquals(List.of("cfuelinit"), state.pending());
    }

    @Test
    void anActionWithNoInitTypeIsAStepFailure() {
        CaseRun run = caseRun("PETROCORE-23", act(0, "CONTROL_FUNCTION", "DEVICE", "SITECONTROLLER"));

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertTrue(error.getMessage().contains("INIT_TYPE"), error.getMessage());
    }
}
