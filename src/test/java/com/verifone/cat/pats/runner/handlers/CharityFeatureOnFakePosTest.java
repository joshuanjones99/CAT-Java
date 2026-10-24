package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.pos.fake.FakePosPinpad;
import com.verifone.cat.pos.pinpad.PinpadDouble;
import com.verifone.cat.report.CaseRecord;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One real Charity case, a {@code .feature}, run step by step through {@link CaseRun} with the {@link StandardHandlers}
 * set, against the fake POS in headless Chromium. Evidence about this repository only: no rig, no register, no Commander
 * is reached (the shim and the Commander point at nothing). Skipped, not failed, when Chromium is not installed for
 * Playwright.
 *
 * <p>The assertions are about the wiring, not the outcome: every action of the case has a handler; the run ends with
 * every step passed, or stops at a named step with a {@link PatsStepError}, or lists its failed validations; and no
 * internal error (a null, a cast) escapes. How far the case got is printed, and is recorded in the test's log.
 */
class CharityFeatureOnFakePosTest {

    /** A Charity outdoor case: a sale, a tender and a receipt, with no forecourt and no DCR. */
    private static final String CASE = "/pats/feature/charity_outdoor/PETROCORE-37039-TestCase.feature";

    @TempDir
    Path root;

    @Test
    void theCaseRunsThroughTheHandlerSetOnTheFakePos() throws Exception {
        Path feature = Path.of(getClass().getResource(CASE).toURI());
        RunnableCase kase = PatsCaseLoader.load(feature);
        assertFalse(kase.actions().isEmpty(), "the feature reads to actions");

        PinpadDouble pinpad = new PinpadDouble("1", Duration.ofSeconds(2));
        try (FakePos fake = FakePos.open(FakePosPinpad.wire(pinpad));
                Playwright playwright = Playwright.create()) {
            Browser browser = launchOrNull(playwright);
            assumeTrue(browser != null, "Chromium is not installed for Playwright; skipping");
            try (browser) {
                Page page = browser.newPage();
                page.navigate(fake.url().toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

                HandlerFixtures fixtures = WiringFixtures.over(page, root, pinpad, closedPort(), null);
                ActionRegistry registry = StandardHandlers.create(fixtures);
                FixtureSource sources = StandardHandlers.fixtureSource(fixtures);

                for (var action : kase.actions()) {
                    assertTrue(registry.handlerFor(action.name()).isPresent(),
                            kase.key() + " names " + action.name() + ", which has no handler");
                }

                Set<com.verifone.cat.pats.runner.FixtureName> declared =
                        new HashSet<>(FixtureRequirements.fixturesFor(kase));
                CaseRecord record = new CaseRecord("e2e::" + kase.key(), kase.key(), root.resolve(kase.key()),
                        Instant.now());
                CaseRun run = new CaseRun(kase, record, registry, sources, declared, new RunState());

                String verdict = runAndJudge(run);
                long passed = record.steps().stream().filter(s -> "passed".equals(s.outcome())).count();
                System.out.println("[e2e] " + kase.key() + " on the fake POS: " + verdict + "; " + passed + " of "
                        + kase.actions().size() + " steps passed; " + record.steps().size() + " recorded");
                for (CaseRecord.StepRecord step : record.steps()) {
                    if (!"passed".equals(step.outcome())) {
                        System.out.println("[e2e]   stopped at " + step.text() + " -> " + step.detail());
                        break;
                    }
                }
            }
        }
    }

    /**
     * Runs the case and says how it ended. Only a step's named failure or a failed validation list is an acceptable
     * end; anything else is an internal error, which fails the test.
     */
    private static String runAndJudge(CaseRun run) {
        try {
            run.run();
            return "completed, every validation passed";
        } catch (ValidationsFailed failed) {
            return "completed with failed validations (" + failed.getMessage().split("\n", 2)[0] + ")";
        } catch (PatsStepError stopped) {
            assertFalse(String.valueOf(stopped.getMessage()).contains("no handler for"),
                    "an action with a handler reported none: " + stopped.getMessage());
            String cause = rootCause(stopped);
            if (rootIsDevice(stopped)) {
                return "stopped at a step that needs a device not wired here (" + cause + ")";
            }
            return "stopped at a named step (" + cause + ")";
        }
    }

    private static boolean rootIsDevice(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DeviceUnavailable) {
                return true;
            }
        }
        return false;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /** A port nothing listens on, so a shim read fails by name rather than reaching a register. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Browser launchOrNull(Playwright playwright) {
        try {
            return playwright.chromium().launch();
        } catch (PlaywrightException missing) {
            return null;
        }
    }
}
