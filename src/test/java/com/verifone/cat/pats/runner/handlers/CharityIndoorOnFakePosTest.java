package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureRequirements;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.pos.fake.FakePosPinpad;
import com.verifone.cat.pos.pinpad.PinpadDouble;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.driver.PosDomDriver;
import com.verifone.cat.report.CaseRecord;
import com.verifone.cat.validate.ValidationFailedException;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PETROCORE-61334, a Charity-Indoor case written in the step library's phrases, run step by step through
 * {@link CaseRun} and the {@link StandardHandlers} set against the fake POS and the pinpad double in headless Chromium.
 *
 * <p>It ends where the Python's {@code pytest tests/suites/charity --fake-pos} ends it (2026-10-28): every step up to
 * the receipt check passes, and the receipt check fails, because the fake POS prints its own synthetic receipt (its
 * prices deliberately do not match the corpus). Evidence about this repository only. Skipped, not failed, when
 * Chromium is not installed for Playwright.
 */
class CharityIndoorOnFakePosTest {

    private static final String CASE = "/pats/feature/charity_indoor/PETROCORE-61334-TestCase.feature";

    @TempDir
    Path root;

    @Test
    void theCashCharityCaseRunsToItsReceiptCheckOnTheFakePos() throws Exception {
        Path source = Path.of(getClass().getResource(CASE).toURI());
        // The case's folder, with its baseline beside it, as the run's collect pulls them.
        Files.copy(source, root.resolve(source.getFileName()));
        Files.copy(source.resolveSibling("PETROCORE-61334-CATReceipt.txt"), root.resolve("PETROCORE-61334-CATReceipt.txt"));
        RunnableCase kase = PatsCaseLoader.load(root.resolve(source.getFileName()));

        PinpadDouble pinpad = new PinpadDouble("1", Duration.ofSeconds(5));
        try (FakePos fake = FakePos.open(FakePosPinpad.wire(pinpad));
                Playwright playwright = Playwright.create()) {
            Browser browser = launchOrNull(playwright);
            assumeTrue(browser != null, "Chromium is not installed for Playwright; skipping");
            try (browser) {
                Page page = browser.newPage();
                page.navigate(fake.url().toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                HandlerFixtures base = WiringFixtures.over(page, root, pinpad, closedPort(), null);
                HandlerFixtures fixtures = withDomReceipts(base);
                CaseRecord record = new CaseRecord("e2e::" + kase.key(), kase.key(), root.resolve("out"), Instant.now());
                CaseRun run = new CaseRun(kase, record, StandardHandlers.create(fixtures),
                        StandardHandlers.fixtureSource(fixtures), new HashSet<>(FixtureRequirements.fixturesFor(kase)),
                        new RunState());

                PatsStepError stopped = assertThrows(PatsStepError.class, run::run);

                assertTrue(stopped.getCause() instanceof ValidationFailedException, String.valueOf(stopped.getCause()));
                assertTrue(stopped.getMessage().contains("#7 the receipt matches its baseline"), stopped.getMessage());
                assertEquals(List.of("passed", "passed", "passed", "passed", "passed", "passed", "failed", "skipped"),
                        record.steps().stream().map(CaseRecord.StepRecord::outcome).toList());
                assertEquals(1, record.validations().size());
                assertTrue(!record.validations().get(0).passed());
            }
        }
    }

    /** The fixtures with the screen's receipt, as a fake-POS run reads it (the run has no printer). */
    private static HandlerFixtures withDomReceipts(HandlerFixtures f) {
        return new HandlerFixtures(f.pos(), f.posShim(),
                new DomReceiptCapture(new PosDomDriver(f.pos().driver()), PosDomDriver.itemFamilies()), f.pinpad(),
                f.commander(), f.commanderAccess(), f.petroapps(), f.toolsRoot(), f.resultsDir(), f.environment(),
                f.inits(), f.dcr(), f.fuelPumps(), f.cloudSite(), f.buypassV2(), f.widgetText(), f.posUiTimings(),
                f.saleTiming(), f.devicesTiming(), f.saleRmi(), f.printer(), f.cashier(), f.packets());
    }

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
